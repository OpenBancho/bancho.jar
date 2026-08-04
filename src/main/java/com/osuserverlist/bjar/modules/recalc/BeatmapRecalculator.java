package com.osuserverlist.bjar.modules.recalc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.ModeStats;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.datastore.Redis;
import com.osuserverlist.bjar.packets.server.UserServerPackets.UserStatsPacket;
import com.osuserverlist.bjar.repos.BeatmapRepository;
import com.osuserverlist.bjar.repos.ScoreRepository;
import com.osuserverlist.bjar.repos.ScoreRepository.AffectedPlayer;
import com.osuserverlist.bjar.repos.StatsRepository;
import com.osuserverlist.bjar.repos.UserRepository;

/**
 * Recalculates weighted PP for the players touched by a single beatmap's status change.
 *
 * <p>{@link com.osuserverlist.bjar.repos.ScoreRepository#calculateWeightedPp(long, int)} only
 * counts scores set on maps with status {@code 1} (Ranked), so the stored numbers are correct
 * the moment they are recomputed. The problem is that nothing recomputed them: unranking a map
 * rewrote the {@code maps} row and left {@code stats.pp} and the {@code bjar:leaderboard:*}
 * sorted sets holding a total that still included that map, until the next full recalculation.
 *
 * <p>This class closes that gap without the cost of a server-wide recalc: only the users who
 * actually have a score on the affected map are revisited, and only in the modes they played it
 * in. It runs on every status change, not just unranking, so ranking a map credits its scores
 * just as promptly.
 *
 * <p>The work is handed to the shared executor because a whole beatmap set can touch a lot of
 * players and the callers are an HTTP request and an in-game command.
 */
public final class BeatmapRecalculator {

    private static final Logger logger = LoggerFactory.getLogger(BeatmapRecalculator.class);

    private static final String LEADERBOARD_KEY = "bjar:leaderboard:";

    private BeatmapRecalculator() {
    }

    /** Recalculates everyone holding a score on the given beatmap. */
    public static void recalcForMap(long beatmapId) {
        BeatmapEntity beatmap = BeatmapRepository.findById(beatmapId);

        if (beatmap == null || beatmap.getMd5() == null || beatmap.getMd5().isBlank()) {
            logger.warn("Skipping the PP recalculation of beatmap <{}>: no cached checksum.", beatmapId);
            return;
        }

        String md5 = beatmap.getMd5();

        run("beatmap " + beatmapId, () -> ScoreRepository.findAffectedPlayersByMd5(md5));
    }

    /** Recalculates everyone holding a score on any difficulty of the given beatmap set. */
    public static void recalcForSet(long setId) {
        run("beatmap set " + setId, () -> ScoreRepository.findAffectedPlayersBySetId(setId));
    }

    private static void run(String label, Supplier<List<AffectedPlayer>> lookup) {
        Runnable task = () -> {
            try {
                List<AffectedPlayer> affected = lookup.get();

                if (affected.isEmpty()) {
                    logger.info("No scores to recalculate after the status change of {}.", label);
                    return;
                }

                Map<Integer, Boolean> listedCache = new HashMap<>();
                int changed = 0;

                for (AffectedPlayer player : affected) {
                    if (recalc(player.userId(), player.mode(), listedCache)) {
                        changed++;
                    }
                }

                logger.info("Recalculated PP after the status change of {}: {} of {} player/mode pairs changed.",
                        label, changed, affected.size());

            } catch (Exception e) {
                logger.error("Failed to recalculate PP after the status change of {}: {}",
                        label, e.getMessage(), e);
            }
        };

        try {
            App.server.executor.submit(task);
        } catch (Exception e) {
            // Better a slow caller than a ranking left holding PP from an unranked map.
            logger.warn("Running the PP recalculation of {} on the calling thread: {}", label, e.getMessage());
            task.run();
        }
    }

    /** @return {@code true} when the stored total actually changed. */
    private static boolean recalc(int userId, int mode, Map<Integer, Boolean> listedCache) {
        StatsEntity stats = StatsRepository.find(userId, mode);

        if (stats == null) {
            return false;
        }

        int oldPp = stats.getPp() != null ? stats.getPp() : 0;
        int newPp = (int) Math.round(ScoreRepository.calculateWeightedPp(userId, mode));

        if (newPp != oldPp) {
            stats.setPp(newPp);
            StatsRepository.update(stats);

            logger.info("User <{}> mode <{}>: {}pp -> {}pp", userId, mode, oldPp, newPp);
        }

        // A restriction is the absence of UNRESTRICTED, and restricted accounts are kept out of
        // the sorted sets entirely; writing their total back would quietly unhide them.
        boolean listed = listedCache.computeIfAbsent(userId, BeatmapRecalculator::isListed);

        if (listed) {
            Redis.getClient().zadd(LEADERBOARD_KEY + mode, newPp, String.valueOf(userId));
        } else {
            Redis.getClient().zrem(LEADERBOARD_KEY + mode, String.valueOf(userId));
        }

        refreshSession(userId, mode, newPp);

        return newPp != oldPp;
    }

    private static boolean isListed(int userId) {
        UserEntity user = UserRepository.findById(userId);

        return user != null && Privileges.has(user.getPrivileges(), Privileges.UNRESTRICTED);
    }

    /** Pushes the new total to a live session, so the in-game rank does not lag behind. */
    private static void refreshSession(int userId, int mode, int newPp) {
        Player player = App.server.playerManager.getById(userId);

        if (player == null) {
            return;
        }

        ModeStats[] modeStats = player.getModeStats();

        if (mode >= 0 && mode < modeStats.length && modeStats[mode] != null) {
            modeStats[mode].setPp(newPp);

            Long rank = Redis.getClient().zrevrank(LEADERBOARD_KEY + mode, String.valueOf(userId));
            modeStats[mode].setGlobalRank(rank != null ? Math.toIntExact(rank) + 1 : 0);
        }

        player.sendPacket(new UserStatsPacket(player));
    }
}
