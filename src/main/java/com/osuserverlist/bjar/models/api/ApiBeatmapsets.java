package com.osuserverlist.bjar.models.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.BssMapsetEntity;
import com.osuserverlist.bjar.repos.BeatmapRepository;
import com.osuserverlist.bjar.repos.BssMapsetRepository;

/**
 * One beatmap set, the way the API hands it out.
 *
 * <p>The {@code maps} table stores difficulties, not sets, so a set is what a
 * group of rows sharing a {@code set_id} adds up to: the metadata of its
 * hardest difficulty plus the sums over all of them. When the set was uploaded
 * through the in-game submission system the matching {@code bss_mapsets} row
 * adds the creator id and whether the set is still downloadable here.
 */
public final class ApiBeatmapsets {

    private ApiBeatmapsets() {
    }

    /**
     * @return the set, or null when no difficulty with that set id is known.
     */
    public static Map<String, Object> summary(long setId) {
        List<BeatmapEntity> difficulties = new ArrayList<>(BeatmapRepository.findBySetId(setId));

        if (difficulties.isEmpty()) {
            return null;
        }

        difficulties.sort(Comparator.comparing(
                (BeatmapEntity map) -> map.getDiff() == null ? 0f : map.getDiff()));

        // The hardest difficulty carries the metadata shown for the whole set,
        // which is what osu! itself displays.
        BeatmapEntity primary = difficulties.get(difficulties.size() - 1);

        BssMapsetEntity hosted = setId > 0 && setId <= Integer.MAX_VALUE
                ? BssMapsetRepository.findBySetId((int) setId)
                : null;

        long plays = 0;
        long passes = 0;
        String lastUpdate = null;

        for (BeatmapEntity map : difficulties) {
            plays += map.getPlays() == null ? 0 : map.getPlays();
            passes += map.getPasses() == null ? 0 : map.getPasses();

            String updated = map.getLastUpdate() == null ? null : map.getLastUpdate().toString();

            if (updated != null && (lastUpdate == null || updated.compareTo(lastUpdate) > 0)) {
                lastUpdate = updated;
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("set_id", setId);
        body.put("server", primary.getServer());
        body.put("artist", primary.getArtist());
        body.put("title", primary.getTitle());
        body.put("creator", primary.getCreator());
        body.put("creator_id", hosted == null ? null : hosted.getCreatorId());
        body.put("status", primary.getStatus());
        body.put("mode", primary.getMode());
        body.put("bpm", primary.getBpm());
        body.put("total_length", primary.getTotalLength());
        body.put("last_update", lastUpdate);
        body.put("plays", plays);
        body.put("passes", passes);
        body.put("difficulty_count", difficulties.size());
        body.put("hosted", hosted != null && Boolean.TRUE.equals(hosted.getActive()));
        body.put("has_video", hosted == null ? null : hosted.getHasVideo());
        body.put("revision", hosted == null ? null : hosted.getRevision());
        body.put("submission_date", hosted == null || hosted.getSubmissionDate() == null
                ? null
                : hosted.getSubmissionDate().toString());

        List<Map<String, Object>> mapped = new ArrayList<>();

        for (BeatmapEntity map : difficulties) {
            mapped.add(ApiMappers.beatmap(map));
        }

        body.put("difficulties", mapped);

        return body;
    }
}
