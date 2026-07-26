package com.osuserverlist.bjar.modules.recalc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.models.essentials.Score;
import com.osuserverlist.bjar.models.osu.GameMode;
import com.osuserverlist.bjar.modules.osu.OsuMapDownloader;
import com.osuserverlist.bjar.repos.BeatmapRepository;
import com.osuserverlist.bjar.repos.ScoreRepository;

public class ScoreRecalculator {

    private static final Logger logger = LoggerFactory.getLogger(ScoreRecalculator.class);

    private static final int SCORE_THREAD_COUNT = 3;

    /**
     * Recalculates PP for all ranked scores in the given mode in parallel.
     *
     * @return number of scores successfully processed
     */
    public int recalcScores(int mode, boolean force) {
        List<ScoreRow> rows = fetchScoreRows(mode);

        if (rows.isEmpty()) {
            logger.info("No scores found for mode {}", GameMode.fromValue(mode));
            return 0;
        }

        logger.info(
                "Dispatching {} scores for mode {} across {} threads",
                rows.size(),
                GameMode.fromValue(mode),
                SCORE_THREAD_COUNT);

        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(SCORE_THREAD_COUNT);
        List<Future<?>> futures = new ArrayList<>(rows.size());

        for (ScoreRow row : rows) {
            futures.add(executor.submit(() -> processScore(row, mode, successCount, force)));
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                logger.error("Unexpected executor-level error", e);
            }
        }

        executor.shutdown();

        try {
            if (!executor.awaitTermination(30, TimeUnit.MINUTES)) {
                logger.warn("Score executor for mode {} timed out - forcing shutdown.", mode);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.error("Score recalc interrupted for mode {}", mode, e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        return successCount.get();
    }

    private List<ScoreRow> fetchScoreRows(int mode) {
        List<ScoreRow> rows = new ArrayList<>();

        for (ScoreEntity entity : ScoreRepository.getRankedScoresByMode(mode)) {
            try {
                BeatmapEntity beatmap = BeatmapRepository.findByMd5(entity.getMapMd5());
                Score score = Score.fromEntity(entity, beatmap);

                score.setMode(GameMode.toVanillaMode(GameMode.fromValue(mode)).getValue());

                rows.add(new ScoreRow(
                        score,
                        beatmap.getId(),
                        entity.getPp()));

            } catch (Exception e) {
                logger.error("Failed to read score {}", entity.getId(), e);
            }
        }

        return rows;
    }

    private void processScore(
            ScoreRow row,
            int mode,
            AtomicInteger successCount,
            boolean force) {

        try {
            byte[] mapData = OsuMapDownloader.downloadMap(row.mapId);

            double newPP = App.server.performance.calculate(row.score, mapData);

            if (Math.abs(newPP - row.oldPP) > 0.01 || force) {
                logger.info(
                        "Score <{}>: {}pp -> {}pp",
                        row.score.getId(),
                        row.oldPP,
                        Math.round(newPP * 1000.0) / 1000.0);

                ScoreRepository.updatePp(row.score.getId(), newPP);
            }

            successCount.incrementAndGet();

        } catch (Exception e) {
            logger.error(
                    "Error recalculating score <{}> in mode {}",
                    row.score.getId(),
                    mode,
                    e);
        }
    }

    private static class ScoreRow {

        final Score score;
        final long mapId;
        final double oldPP;

        ScoreRow(Score score, long mapId, double oldPP) {
            this.score = score;
            this.mapId = mapId;
            this.oldPP = oldPP;
        }
    }
}
