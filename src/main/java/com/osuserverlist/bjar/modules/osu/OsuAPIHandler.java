package com.osuserverlist.bjar.modules.osu;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.MapsetEntity;
import com.osuserverlist.bjar.repos.BeatmapRepository;
import com.osuserverlist.bjar.repos.MapsetRepository;

import me.skiincraft.api.ousu.OusuAPI;
import me.skiincraft.api.ousu.entity.beatmap.Beatmap;
import me.skiincraft.api.ousu.entity.beatmap.BeatmapSet;
import me.skiincraft.api.ousu.exceptions.BeatmapException;

public class OsuAPIHandler {

    private static final Logger logger = LoggerFactory.getLogger(OsuAPIHandler.class);

    private static final Map<Long, Object> MAPSET_LOCKS = new ConcurrentHashMap<>();

    private final OusuAPI osuAPI;

    public OsuAPIHandler(String apiKey) {
        this.osuAPI = new OusuAPI(apiKey);
    }

    public BeatmapEntity getBeatmapById(long beatmapId) {
        BeatmapEntity cached = BeatmapRepository.findById(beatmapId);
        if (cached != null) {
            return cached;
        }

        Beatmap beatmap = osuAPI.getBeatmap(beatmapId).get();

        scheduleMapsetCaching(beatmap.getBeatmapSetId());

        return BeatmapEntity.fromBeatmap(beatmap);
    }

    public Beatmap getRawBeatmapById(long beatmapId) {
        try {
            return osuAPI.getBeatmap(beatmapId).get();
        } catch (BeatmapException e) {
            return null;
        }
    }

    public BeatmapEntity getBeatmapByHash(String md5) {
        BeatmapEntity cached = BeatmapRepository.findByMd5(md5);
        if (cached != null) {
            return cached;
        }

        try {
            Beatmap beatmap = osuAPI.getBeatmapByChecksum(md5).get();

            scheduleMapsetCaching(beatmap.getBeatmapSetId());

            return BeatmapEntity.fromBeatmap(beatmap);
        } catch (BeatmapException e) {
            return null;
        }
    }

    private void scheduleMapsetCaching(long setId) {
        if (isMapsetCached(setId) || MAPSET_LOCKS.containsKey(setId)) {
            return;
        }

        App.server.executor.submit(() -> cacheMapset(setId));
    }

    private boolean isMapsetCached(long setId) {
        return MapsetRepository.findById((int) setId) != null;
    }

    private void cacheMapset(long setId) {
        Object lock = MAPSET_LOCKS.computeIfAbsent(setId, k -> new Object());

        synchronized (lock) {
            try {
                if (isMapsetCached(setId)) {
                    return;
                }

                long start = System.currentTimeMillis();

                BeatmapSet set = osuAPI.getBeatmapSet(setId).get();

                int count = 0;

                for (Beatmap beatmap : set.getAsList()) {
                    BeatmapRepository.save(BeatmapEntity.fromBeatmap(beatmap));
                    count++;
                }

                MapsetEntity mapset = new MapsetEntity();
                mapset.setId((int) setId);
                mapset.setLastOsuApiCheck(LocalDateTime.now());
                MapsetRepository.save(mapset);

                logger.debug(
                    "Cached beatmap set {} ({} beatmaps) in {} ms",
                    setId,
                    count,
                    System.currentTimeMillis() - start
                );

            } finally {
                MAPSET_LOCKS.remove(setId, lock);
            }
        }
    }
}
