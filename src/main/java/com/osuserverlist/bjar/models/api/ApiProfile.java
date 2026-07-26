package com.osuserverlist.bjar.models.api;

import io.ebean.DB;

/**
 * The derived numbers a profile page shows but no table stores: ranks, level
 * and follower counts.
 *
 * <p>All of it is computed on demand from {@code stats}, {@code users} and
 * {@code relationships}, so nothing has to be kept in sync and no schema
 * change is needed. The queries lean on the existing {@code stats_pp_index}.
 */
public final class ApiProfile {

    private ApiProfile() {
    }

    /**
     * Global pp rank, counting only unrestricted accounts.
     *
     * @return the position, or null for a player without pp, who is unranked.
     */
    public static Integer globalRank(int mode, long pp) {
        if (pp <= 0) {
            return null;
        }

        return DB.sqlQuery("SELECT COUNT(*) + 1 AS position FROM `stats` s"
                + " JOIN `users` u ON u.`id` = s.`id`"
                + " WHERE s.`mode` = :mode AND s.`pp` > :pp AND (u.`priv` & 1) > 0")
                .setParameter("mode", mode)
                .setParameter("pp", pp)
                .findOne()
                .getInteger("position");
    }

    /** The same, restricted to the player's own country. */
    public static Integer countryRank(int mode, long pp, String country) {
        if (pp <= 0 || country == null || country.isBlank() || country.equalsIgnoreCase("xx")) {
            return null;
        }

        return DB.sqlQuery("SELECT COUNT(*) + 1 AS position FROM `stats` s"
                + " JOIN `users` u ON u.`id` = s.`id`"
                + " WHERE s.`mode` = :mode AND s.`pp` > :pp"
                + " AND (u.`priv` & 1) > 0 AND u.`country` = :country")
                .setParameter("mode", mode)
                .setParameter("pp", pp)
                .setParameter("country", country.toLowerCase())
                .findOne()
                .getInteger("position");
    }

    /**
     * How many players added this one as a friend. The relationship is one
     * directional in osu!, so this is exactly a follower count.
     */
    public static int followers(int userId) {
        Integer count = DB.sqlQuery("SELECT COUNT(*) AS total FROM `relationships`"
                + " WHERE `user2` = :user AND `type` = 'friend'")
                .setParameter("user", userId)
                .findOne()
                .getInteger("total");

        return count == null ? 0 : count;
    }

    /** The osu! level reached with this much total score, capped at 200. */
    public static int level(long totalScore) {
        int level = 1;

        while (level < 200 && totalScore >= requiredScore(level + 1)) {
            level++;
        }

        return level;
    }

    /**
     * How far into the current level the player is, as a percentage. This is
     * the number the bar next to the level badge shows.
     */
    public static double levelProgress(long totalScore) {
        int level = level(totalScore);

        if (level >= 200) {
            return 100.0;
        }

        double reached = requiredScore(level);
        double next = requiredScore(level + 1);
        double span = next - reached;

        if (span <= 0) {
            return 0.0;
        }

        double progress = (totalScore - reached) / span * 100.0;

        return Math.max(0.0, Math.min(100.0, progress));
    }

    /**
     * Total score needed to reach a level, using the formula the osu! client
     * itself uses: a cubic up to level 100 and a flat 100 billion per level
     * after that.
     */
    private static double requiredScore(int level) {
        if (level <= 1) {
            return 0.0;
        }

        if (level <= 100) {
            return 5000.0 / 3.0 * (4.0 * Math.pow(level, 3) - 3.0 * Math.pow(level, 2) - level)
                    + 1.25 * Math.pow(1.8, level - 60);
        }

        return 26931190829.0 + 100000000000.0 * (level - 100);
    }
}
