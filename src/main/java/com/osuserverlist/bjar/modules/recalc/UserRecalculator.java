package com.osuserverlist.bjar.modules.recalc;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.modules.datastore.Redis;
import com.osuserverlist.bjar.repos.ScoreRepository;
import com.osuserverlist.bjar.repos.StatsRepository;

public class UserRecalculator {

    private static final Logger logger = LoggerFactory.getLogger(UserRecalculator.class);

    /**
     * Recalculates weighted PP for all users in the given mode.
     *
     * @return number of users processed
     */
    public int recalcUsers(int mode, boolean force) {
        AtomicInteger count = new AtomicInteger(0);

        try {
            List<StatsEntity> activeUsers = StatsRepository.findActiveByMode(mode);
            for (StatsEntity user : activeUsers) {
                int userId = user.getId().getId();
                int oldPp = user.getPp();

                double newPp = ScoreRepository.calculateWeightedPp(userId, mode);
                if (newPp != oldPp || force) {
                    logger.info("User <{}>: {}pp -> {}pp", userId, oldPp, newPp);
                    user.setPp((int) Math.round(newPp));
                    StatsRepository.update(user);
                    Redis.getClient().zadd("bjar:leaderboard:" + mode, newPp, String.valueOf(userId));
                }

                count.incrementAndGet();

            }

        } catch (

        Exception e) {
            logger.error("Fatal error fetching users for mode {}: {}", mode, e.getMessage(), e);
        }

        return count.get();
    }
}
