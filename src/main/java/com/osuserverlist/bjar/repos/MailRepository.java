package com.osuserverlist.bjar.repos;

import io.ebean.DB;

/**
 * Offline messages.
 *
 * <p>This one repository speaks raw SQL on purpose. The `read` column is a
 * reserved word in MySQL, and Ebean does not quote identifiers by default, so an
 * entity mapped onto it would produce statements the server rejects. Backticked
 * SQL sidesteps that without touching the schema or the global Ebean config.</p>
 */
public final class MailRepository {

    private MailRepository() {
    }

    /**
     * Marks the conversation with one sender as read.
     *
     * @return how many messages were actually flipped, which is zero when the
     *         client is re-sending a request it already made.
     */
    public static int markConversationAsRead(int readerId, int senderId) {
        return DB.sqlUpdate(
                "UPDATE `mail` SET `read` = 1 WHERE `to_id` = :reader AND `from_id` = :sender AND `read` = 0")
                .setParameter("reader", readerId)
                .setParameter("sender", senderId)
                .execute();
    }

    public static int markAllAsRead(int readerId) {
        return DB.sqlUpdate("UPDATE `mail` SET `read` = 1 WHERE `to_id` = :reader AND `read` = 0")
                .setParameter("reader", readerId)
                .execute();
    }

    public static int countUnread(int readerId) {
        Integer count = DB.sqlQuery("SELECT COUNT(*) AS total FROM `mail` WHERE `to_id` = :reader AND `read` = 0")
                .setParameter("reader", readerId)
                .findOne()
                .getInteger("total");

        return count == null ? 0 : count;
    }

    /** Used when an account is deleted, in both directions. */
    public static int deleteForUser(int userId) {
        return DB.sqlUpdate("DELETE FROM `mail` WHERE `to_id` = :user OR `from_id` = :user")
                .setParameter("user", userId)
                .execute();
    }
}
