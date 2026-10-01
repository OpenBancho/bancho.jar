package com.osuserverlist.bjar.models.api;

import java.util.LinkedHashMap;
import java.util.Map;

import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.repos.BeatmapRepository;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;

/**
 * Serialization helpers that turn database entities into plain, ordered maps
 * for the v1 API. Building explicit maps (instead of serializing Ebean beans
 * directly) keeps the JSON shape stable and avoids triggering lazy loading of
 * unrelated associations.
 */
public final class ApiMappers {

    /**
     * Resolve a player from the {@code id} or {@code name} query parameters.
     * Returns {@code null} when neither is supplied or no such player exists.
     */
    public static UserEntity resolveUser(Context ctx) {
        String name = ctx.queryParam("name");
        if (name != null && !name.isBlank()) {
            return UserRepository.findByName(name.trim());
        }

        String idRaw = ctx.queryParam("id");
        if (idRaw != null && !idRaw.isBlank()) {
            try {
                return UserRepository.findById(Integer.parseInt(idRaw.trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return null;
    }

    /** Public-facing profile fields for a user. */
    public static Map<String, Object> userInfo(UserEntity user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("name", user.getName());
        map.put("country", user.getCountry());
        map.put("priv", user.getPrivileges());
        map.put("clan_id", user.getClanId());
        map.put("preferred_mode", user.getPreferredMode());
        map.put("play_style", user.getPlayStyle());
        // The custom badge is shown on the public profile, so it belongs in the
        // public mapper rather than only in the owner's own view.
        map.put("custom_badge_name", user.getCustomBadgeName());
        map.put("custom_badge_icon", user.getCustomBadgeIcon());
        // The cover picture is part of how a profile looks to visitors, so it is
        // public as well; the site draws it at the top of the page.
        map.put("custom_banner", user.getCustomBanner());
        map.put("creation_time", user.getCreationTime());
        map.put("latest_activity", user.getLatestActivity());
        return map;
    }

    /** A compact player reference, used when embedding a user inside a score. */
    public static Map<String, Object> userRef(UserEntity user) {
        if (user == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("name", user.getName());
        map.put("country", user.getCountry());
        return map;
    }

    /** Per-mode statistics for a user. */
    public static Map<String, Object> stats(StatsEntity stats) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", stats.getId().getMode());
        map.put("tscore", stats.getTotalScore());
        map.put("rscore", stats.getRankedScore());
        map.put("pp", stats.getPp());
        map.put("plays", stats.getPlays());
        map.put("playtime", stats.getPlaytime());
        map.put("acc", stats.getAccuracy());
        map.put("max_combo", stats.getMaxCombo());
        map.put("total_hits", stats.getTotalHits());
        map.put("replay_views", stats.getReplayViews());
        map.put("xh_count", stats.getXhCount());
        map.put("x_count", stats.getXCount());
        map.put("sh_count", stats.getShCount());
        map.put("s_count", stats.getSCount());
        map.put("a_count", stats.getACount());
        return map;
    }

    /** Beatmap metadata. Returns {@code null} when {@code beatmap} is null. */
    public static Map<String, Object> beatmap(BeatmapEntity beatmap) {
        if (beatmap == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", beatmap.getId());
        map.put("set_id", beatmap.getSetId());
        map.put("md5", beatmap.getMd5());
        map.put("artist", beatmap.getArtist());
        map.put("title", beatmap.getTitle());
        map.put("version", beatmap.getVersion());
        map.put("creator", beatmap.getCreator());
        map.put("filename", beatmap.getFilename());
        map.put("status", beatmap.getStatus());
        map.put("mode", beatmap.getMode());
        map.put("bpm", beatmap.getBpm());
        map.put("cs", beatmap.getCs());
        map.put("ar", beatmap.getAr());
        map.put("od", beatmap.getOd());
        map.put("hp", beatmap.getHp());
        map.put("diff", beatmap.getDiff());
        map.put("max_combo", beatmap.getMaxCombo());
        map.put("total_length", beatmap.getTotalLength());
        map.put("plays", beatmap.getPlays());
        map.put("passes", beatmap.getPasses());
        return map;
    }

    /**
     * Score fields. When {@code includeBeatmap} is true the associated beatmap
     * is looked up by md5 and embedded under {@code "beatmap"}.
     */
    public static Map<String, Object> score(ScoreEntity score, boolean includeBeatmap) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", score.getId());
        map.put("map_md5", score.getMapMd5());
        map.put("score", score.getScore());
        map.put("pp", score.getPp());
        map.put("acc", score.getAcc());
        map.put("max_combo", score.getMaxCombo());
        map.put("mods", score.getMods());
        map.put("lazer_score", score.getLazerScore() != null ? score.getLazerScore() : 0L);
        map.put("n300", score.getN300());
        map.put("n100", score.getN100());
        map.put("n50", score.getN50());
        map.put("nmiss", score.getNmiss());
        map.put("ngeki", score.getNgeki());
        map.put("nkatu", score.getNkatu());
        map.put("grade", score.getGrade());
        map.put("status", score.getStatus());
        map.put("mode", score.getMode());
        map.put("play_time", score.getPlayTime() != null ? score.getPlayTime().toString() : null);
        map.put("time_elapsed", score.getTimeElapsed());
        map.put("perfect", score.getPerfect());
        if (includeBeatmap) {
            map.put("beatmap", beatmap(BeatmapRepository.findByMd5(score.getMapMd5())));
        }
        return map;
    }
}
