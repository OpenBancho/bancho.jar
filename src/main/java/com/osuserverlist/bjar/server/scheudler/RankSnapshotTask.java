package com.osuserverlist.bjar.server.scheudler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.ebean.DB;

/**
 * Writes down where every ranked player stands, once a day.
 *
 * <p>A rank is not a stored number anywhere on this server: it is counted from
 * {@code stats} whenever a profile is opened, which gives today's position but
 * says nothing about the past. The graph on a profile needs that past, so this
 * task leaves one row per player, mode and day behind, and the graph is drawn
 * from those rows.
 *
 * <p>The whole day is written by a single statement: counting the players ahead
 * of each row inside the query is far cheaper than asking the database once per
 * player, and the primary key makes a second run on the same day overwrite the
 * earlier snapshot instead of piling up.
 */
public class RankSnapshotTask implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(RankSnapshotTask.class);

    /**
     * How long the history is kept. Two years is far more than any profile
     * graph asks for, and it stops the table from growing without end.
     */
    private static final int KEEP_DAYS = 730;

    private static boolean tableChecked;

    @Override
    public void run() {
        try {
            ensureTable();

            int written = DB.sqlUpdate(
                    "REPLACE INTO `rank_history` (`userid`, `mode`, `date`, `rank`, `pp`)"
                    + " SELECT s.`id`, s.`mode`, CURDATE(),"
                    + " (SELECT COUNT(*) + 1 FROM `stats` ahead"
                    + "  JOIN `users` peer ON peer.`id` = ahead.`id`"
                    + "  WHERE ahead.`mode` = s.`mode` AND ahead.`pp` > s.`pp`"
                    + "  AND (peer.`priv` & 1) > 0),"
                    + " s.`pp`"
                    + " FROM `stats` s"
                    + " JOIN `users` u ON u.`id` = s.`id`"
                    + " WHERE s.`pp` > 0 AND (u.`priv` & 1) > 0")
                    .execute();

            int removed = DB.sqlUpdate(
                    "DELETE FROM `rank_history` WHERE `date` < DATE_SUB(CURDATE(), INTERVAL :days DAY)")
                    .setParameter("days", KEEP_DAYS)
                    .execute();

            logger.info("Rank snapshot written for {} entries, {} old rows dropped", written, removed);
        } catch (Exception e) {
            // A missed snapshot only leaves a gap in a graph, so the task
            // complains and lives to try again on its next turn.
            logger.warn("Could not write the rank snapshot", e);
        }
    }

    /**
     * Creates the table if it is not there yet, so an existing database does not
     * have to be migrated by hand before the graph works.
     *
     * <p>The API handler calls this as well: a profile can be opened seconds
     * after a restart, before this task has had its first turn, and asking for
     * a table that does not exist yet would fail the request.
     */
    public static synchronized void ensureTable() {
        if (tableChecked) {
            return;
        }

        DB.sqlUpdate("CREATE TABLE IF NOT EXISTS `rank_history` ("
                + " `userid` int NOT NULL,"
                + " `mode` tinyint NOT NULL,"
                + " `date` date NOT NULL,"
                + " `rank` int NOT NULL,"
                + " `pp` int NOT NULL DEFAULT '0',"
                + " PRIMARY KEY (`userid`, `mode`, `date`),"
                + " KEY `rank_history_lookup` (`userid`, `mode`, `date`)"
                + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci")
                .execute();

        tableChecked = true;
    }

    /**
     * Writes down a single position for today, unless one is already stored.
     *
     * <p>Opening a profile calls this, so a server that has only just gained the
     * graph starts collecting real points from the first visit instead of
     * waiting hours for the first scheduled run. A snapshot already taken today
     * is left alone: the scheduled one is the authoritative reading.
     */
    public static void remember(int userId, int mode, Integer rank) {
        if (rank == null) {
            return;
        }

        try {
            ensureTable();

            DB.sqlUpdate(
                    "INSERT IGNORE INTO `rank_history` (`userid`, `mode`, `date`, `rank`, `pp`)"
                    + " SELECT :user, :mode, CURDATE(), :rank, COALESCE(s.`pp`, 0)"
                    + " FROM `stats` s WHERE s.`id` = :user AND s.`mode` = :mode")
                    .setParameter("user", userId)
                    .setParameter("mode", mode)
                    .setParameter("rank", rank)
                    .execute();
        } catch (Exception e) {
            // Reading a profile must not fail over a single missed point.
            logger.debug("Could not remember the rank of player {}", userId, e);
        }
    }
}
