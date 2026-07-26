package com.osuserverlist.bjar.repos;

import java.time.LocalDateTime;
import java.util.List;

import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.OsuClientModels.LeaderboardType;

import io.ebean.DB;
import io.ebean.SqlRow;

public final class ScoreRepository {

    public static ScoreEntity getBestScore(UserEntity user, String beatmapMd5, int mode) {
        return DB.find(ScoreEntity.class)
                .where()
                .eq("user", user)
                .eq("mapMd5", beatmapMd5)
                .eq("mode", mode)
                .eq("status", 2)
                .orderBy("score desc")
                .setMaxRows(1)
                .findOne();
    }

    public static ScoreEntity getBestScore(int userId, String beatmapMd5, int mode) {
        return DB.find(ScoreEntity.class)
                .where()
                .eq("user.id", userId)
                .eq("mapMd5", beatmapMd5)
                .eq("mode", mode)
                .eq("status", 2)
                .orderBy("score desc")
                .setMaxRows(1)
                .findOne();
    }

    public static int getRank(String beatmapMd5, int mode, long score) {

        SqlRow row = DB.sqlQuery("""
                SELECT COUNT(*) + 1 AS osu_rank
                FROM (
                    SELECT MAX(score) AS best_score
                    FROM scores
                    WHERE map_md5 = :md5
                      AND mode = :mode
                      AND status = 2
                    GROUP BY userid
                ) best_scores
                WHERE best_score > :score
                """)
                .setParameter("md5", beatmapMd5)
                .setParameter("mode", mode)
                .setParameter("score", score)
                .findOne();

        return row == null ? 1 : row.getInteger("osu_rank");
    }

    public static int getPreviousRank(String beatmapMd5,
            int mode,
            UserEntity user,
            long score) {

        SqlRow row = DB.sqlQuery("""
                SELECT COUNT(*) + 1 AS osu_rank
                FROM (
                    SELECT MAX(score) AS best_score
                    FROM scores
                    WHERE map_md5 = :md5
                      AND mode = :mode
                      AND userid <> :userId
                      AND status = 2
                    GROUP BY userid
                ) best_scores
                WHERE best_score > :score
                """)
                .setParameter("md5", beatmapMd5)
                .setParameter("mode", mode)
                .setParameter("userId", user.getId())
                .setParameter("score", score)
                .findOne();

        return row == null ? 1 : row.getInteger("osu_rank");
    }

    public static ScoreEntity findById(long id) {
        return DB.find(ScoreEntity.class, id);
    }

    public static void save(ScoreEntity score) {
        DB.save(score);
    }

    public static void delete(ScoreEntity score) {
        DB.delete(score);
    }

    public static void updateStatus(long scoreId, int status) {

        DB.find(ScoreEntity.class)
                .where()
                .idEq(scoreId)
                .asUpdate()
                .set("status", status)
                .update();
    }

    public static List<ScoreEntity> getGlobalLeaderboard(String md5, int mode) {

        return DB.find(ScoreEntity.class)
                .fetch("user")
                .where()
                .eq("mapMd5", md5)
                .eq("mode", mode)
                .eq("status", 2)
                .orderBy("score desc, user.name")
                .setMaxRows(100)
                .findList();
    }

    public static List<ScoreEntity> getGlobalModsLeaderboard(String md5,
            int mode,
            int mods) {

        return DB.find(ScoreEntity.class)
                .fetch("user")
                .where()
                .eq("mapMd5", md5)
                .eq("mode", mode)
                .eq("mods", mods)
                .eq("status", 2)
                .orderBy("score desc, user.name")
                .setMaxRows(100)
                .findList();
    }

    public static List<ScoreEntity> getCountryLeaderboard(String md5,
            int mode,
            String country) {

        return DB.find(ScoreEntity.class)
                .fetch("user")
                .where()
                .eq("mapMd5", md5)
                .eq("mode", mode)
                .eq("status", 2)
                .eq("user.country", country)
                .orderBy("score desc, user.name")
                .setMaxRows(100)
                .findList();
    }

    public static List<ScoreEntity> getFriendsLeaderboard(String md5,
            int mode,
            int userId) {

        String sql = """
                SELECT s.*
                FROM (
                    SELECT s.*,
                           ROW_NUMBER() OVER (PARTITION BY s.userid ORDER BY s.score DESC) rn
                    FROM scores s
                    WHERE s.map_md5 = :md5
                      AND s.mode = :mode
                      AND s.status = 2
                      AND (
                            s.userid = :userId
                         OR s.userid IN (
                                SELECT user2
                                FROM relationships
                                WHERE user1 = :userId
                                  AND type = 'friend'
                         )
                      )
                ) s
                WHERE rn = 1
                ORDER BY score DESC
                LIMIT 100
                """;

        return DB.findNative(ScoreEntity.class, sql)
                .setParameter("md5", md5)
                .setParameter("mode", mode)
                .setParameter("userId", userId)
                .findList();
    }

    public static List<ScoreEntity> getLeaderboard(BeatmapEntity beatmap,
            int mode,
            LeaderboardType type,
            int mods,
            Player player) {
        return switch (type) {
            case GLOBAL ->
                getGlobalLeaderboard(beatmap.getMd5(), mode);

            case GLOBAL_MODS ->
                getGlobalModsLeaderboard(beatmap.getMd5(), mode, mods);

            case COUNTRY ->
                getCountryLeaderboard(beatmap.getMd5(), mode, String.valueOf(player.getCountry()));

            case FRIENDS ->
                getFriendsLeaderboard(beatmap.getMd5(), mode, player.getId());
        };
    }

    public static double calculateWeightedPp(long userId, int mode) {
        SqlRow row = DB.sqlQuery("""
                SELECT SUM(pp * POW(0.95, rn - 1)) AS weighted_pp
                FROM (
                    SELECT pp,
                           ROW_NUMBER() OVER (ORDER BY pp DESC) AS rn
                    FROM (
                        SELECT MAX(s.pp) AS pp
                        FROM scores s
                        JOIN maps m ON s.map_md5 = m.md5
                        WHERE s.userid = :userId
                          AND s.mode = :mode
                          AND m.status = 1
                          AND s.status = 2
                        GROUP BY s.map_md5
                    ) best_scores
                ) ranked
                """)
                .setParameter("userId", userId)
                .setParameter("mode", mode)
                .findOne();

        if (row == null) {
            return 0.0;
        }

        Double weightedPp = row.getDouble("weighted_pp");
        return weightedPp != null ? weightedPp : 0.0;
    }

    public static List<ScoreEntity> getRankedScoresByMode(int mode) {
        return DB.find(ScoreEntity.class)
                .where()
                .eq("status", 2)
                .eq("mode", mode)
                .orderBy("pp desc")
                .findList();
    }

    public static void updatePp(long scoreId, double pp) {
        DB.find(ScoreEntity.class)
                .where()
                .idEq(scoreId)
                .asUpdate()
                .set("pp", (float) pp)
                .update();
    }

    /**
     * Tells whether the given player already submitted a score carrying this
     * checksum since the given moment.
     *
     * <p>The checksum is produced by the client and covers the play itself,
     * so an identical value arriving twice within a few seconds means the
     * very same submission was replayed, not a second play.</p>
     */
    public static boolean existsRecentByChecksum(int userId, String onlineChecksum, LocalDateTime since) {
        if (onlineChecksum == null || onlineChecksum.isBlank()) {
            return false;
        }

        return DB.find(ScoreEntity.class)
                .select("id")
                .where()
                .eq("user.id", userId)
                .eq("onlineChecksum", onlineChecksum)
                .gt("playTime", since)
                .exists();
    }

    public static long count() {
        return DB.find(ScoreEntity.class)
                .findCount();
    }
}