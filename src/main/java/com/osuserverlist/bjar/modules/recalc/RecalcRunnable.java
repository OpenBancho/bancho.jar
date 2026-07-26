package com.osuserverlist.bjar.modules.recalc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.models.osu.GameMode;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class RecalcRunnable implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(RecalcRunnable.class);

    private static final int THREAD_COUNT = 4;
    private static final int[] MODES = { 0, 1, 2, 3, 4, 5, 6, 8 };
    private boolean force;

    @Override
    public void run() {
        logger.info("Starting multi-threaded recalculation across {} modes using {} threads...",
                MODES.length, THREAD_COUNT);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        List<Future<?>> futures = new ArrayList<>();

        for (int mode : MODES) {
            final int currentMode = mode;
            futures.add(executor.submit(() -> recalcMode(currentMode, force)));
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                logger.error("Unexpected failure in recalc task: {}", e.getMessage(), e);
            }
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.MINUTES)) {
                logger.warn("Executor did not terminate in time — forcing shutdown.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.error("Recalc interrupted while waiting for executor shutdown.", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("All recalculation tasks completed.");
    }

    private void recalcMode(int mode, boolean force) {
        logger.info("Recalculating mode {} on thread {}", GameMode.fromValue(mode), Thread.currentThread().getName());

        ScoreRecalculator scoreRecalculator = new ScoreRecalculator();
        int scores = scoreRecalculator.recalcScores(mode, force);

        UserRecalculator userRecalculator = new UserRecalculator();
        int users = userRecalculator.recalcUsers(mode, force);

        logger.info("Mode {} done — scores processed: {}, users processed: {}",
                GameMode.fromValue(mode), scores, users);

    }
}
