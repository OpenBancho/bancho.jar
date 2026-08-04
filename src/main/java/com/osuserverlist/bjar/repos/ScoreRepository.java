package com.osuserverlist.bjar.repos;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
                    SELECT MAX(scores.score) AS best_score
                    FROM scores
                    JOIN users ON users.id = scores.userid
                    WHERE scores.map_md5 = :md5
                      AND scores.mode = :mode
                      AND scores.status = 2
                      AND (users.priv & 1) > 0
                    GROUP BY scores.userid
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
                    SELECT MAX(scores.score) AS best_score
                    FROM scores
                    JOIN users ON users.id = scores.userid
                    WHERE scores.map_md5 = :md5
                      AND scores.mode = :mode
                      AND scores.userid <> :userId
                      AND scores.status = 2
                      AND (users.priv & 1) > 0
                    GROUP BY scores.userid
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
                // A restricted account is on no leaderboard the game asks for.
                .raw("user.privileges & 3 = 3")
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
                // A restricted account is on no leaderboard the game asks for.
                .raw("user.privileges & 3 = 3")
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
                // A restricted account is on no leaderboard the game asks for.
                .raw("user.privileges & 3 = 3")
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
                      AND EXISTS (
                            SELECT 1
                            FROM users u
                            WHERE u.id = s.userid
                              AND (u.priv & 1) > 0
                      )
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

    /** A single {@code (user, mode)} pair holding a score on some beatmap. */
    public record AffectedPlayer(int userId, int mode) {
    }

    /**
     * Every user and mode holding a score on the given beatmap.
     *
     * <p>Used after a beatmap's status changes: only these players can have a different
     * weighted total afterwards, so there is no need to walk the whole server.</p>
     */
    public static List<AffectedPlayer> findAffectedPlayersByMd5(String md5) {
        List<SqlRow> rows = DB.sqlQuery("""
                SELECT DISTINCT s.userid AS userid, s.mode AS mode
                FROM scores s
                WHERE s.map_md5 = :md5
                """)
                .setParameter("md5", md5)
                .findList();

        return toAffectedPlayers(rows);
    }

    /** Every user and mode holding a score on any difficulty of the given beatmap set. */
    public static List<AffectedPlayer> findAffectedPlayersBySetId(long setId) {
        List<SqlRow> rows = DB.sqlQuery("""
                SELECT DISTINCT s.userid AS userid, s.mode AS mode
                FROM scores s
                JOIN maps m ON s.map_md5 = m.md5
                WHERE m.set_id = :setId
                """)
                .setParameter("setId", setId)
                .findList();

        return toAffectedPlayers(rows);
    }

    private static List<AffectedPlayer> toAffectedPlayers(List<SqlRow> rows) {
        List<AffectedPlayer> affected = new ArrayList<>(rows.size());

        for (SqlRow row : rows) {
            Integer userId = row.getInteger("userid");
            Integer mode = row.getInteger("mode");

            if (userId != null && mode != null) {
                affected.add(new AffectedPlayer(userId, mode));
            }
        }

        return affected;
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
