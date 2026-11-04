package com.osuserverlist.bjar.modules.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.modules.datastore.Redis;

/**
 * Keeps one account from being online in the stable client and in lazer at the same time.
 *
 * <p>The two servers only share Redis, so the rule lives there: bancho publishes the accounts that
 * have a session in {@value #BANCHO_SET}, and lazer keeps the last-activity time of its accounts in
 * {@value #LAZER_ZSET}. Whoever got in first wins; the second client is refused until the first
 * one is gone. Redis being unreachable never blocks a login.
 */
public final class ClientExclusivity {

    private static final Logger logger = LoggerFactory.getLogger(ClientExclusivity.class);

    static final String BANCHO_SET = "bjar:online:bancho";
    static final String LAZER_ZSET = "bjar:online:lazer";

    /**
     * Lazer refreshes its score on every message of its hubs, and its clients ping every few
     * seconds, so an account that has been quiet for this long is no longer connected.
     */
    private static final long LAZER_ACTIVE_WINDOW_MS = 60_000L;

    private ClientExclusivity() {
    }

    /** Whether lazer currently has this account online. */
    public static boolean isLazerOnline(int userId) {
        try {
            Double lastSeen = Redis.getClient().zscore(LAZER_ZSET, String.valueOf(userId));

            return lastSeen != null && System.currentTimeMillis() - lastSeen <= LAZER_ACTIVE_WINDOW_MS;
        } catch (Exception e) {
            logger.warn("Could not check lazer presence of <{}>: {}", userId, e.getMessage());

            return false;
        }
    }

    public static void markBanchoOnline(int userId) {
        try {
            Redis.getClient().sadd(BANCHO_SET, String.valueOf(userId));
        } catch (Exception e) {
            logger.warn("Could not publish bancho presence of <{}>: {}", userId, e.getMessage());
        }
    }

    public static void markBanchoOffline(int userId) {
        try {
            Redis.getClient().srem(BANCHO_SET, String.valueOf(userId));
        } catch (Exception e) {
            logger.warn("Could not clear bancho presence of <{}>: {}", userId, e.getMessage());
        }
    }

    /** Sessions do not survive a restart, so neither may their entries - a stale one would lock the account out of lazer. */
    public static void clearBancho() {
        try {
            Redis.getClient().del(BANCHO_SET);
        } catch (Exception e) {
            logger.warn("Could not clear bancho presence set: {}", e.getMessage());
        }
    }
}
