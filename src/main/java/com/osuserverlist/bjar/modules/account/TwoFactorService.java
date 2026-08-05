package com.osuserverlist.bjar.modules.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.modules.datastore.Redis;

import redis.clients.jedis.params.SetParams;

/**
 * The short-lived state around two factor logins: the secret somebody is in the middle of
 * setting up, the login that is waiting on a code, and the devices that already answered one.
 *
 * <p>None of it belongs in the database. A half finished setup that is never confirmed should
 * disappear on its own, a login challenge is worthless a few minutes later, and a trusted
 * machine is a convenience rather than a fact about the account - so all of it lives in Redis
 * under {@code bjar:2fa:*} and expires by itself.
 *
 * <p>Devices are keyed by a hash of the client hash the game sends at login: the same string the
 * client puts in the {@code ch} parameter when it opens the verification page. That is what lets
 * the page know which login it is answering without the browser having to be signed in - it
 * carries the identifier the game just used, and nothing about the account is readable from it.
 *
 * <p>Trust is revoked by counting up: every trusted device key contains a generation number, so
 * turning 2FA off - or turning it back on - moves the account to a new generation and forgets
 * every machine at once, without having to enumerate keys in Redis.
 */
public final class TwoFactorService {

    private static final Logger logger = LoggerFactory.getLogger("TwoFactor");

    /** A secret that has been shown to somebody but not confirmed with a code yet. */
    private static final String PENDING_PREFIX = "bjar:2fa:pending:";

    /** A login that was answered with the verification reply and is waiting for a code. */
    private static final String CHALLENGE_PREFIX = "bjar:2fa:challenge:";

    private static final String TRUSTED_PREFIX = "bjar:2fa:trusted:";

    /** Bumped to forget every trusted device of an account in one write. */
    private static final String GENERATION_PREFIX = "bjar:2fa:generation:";

    private static final String ATTEMPTS_PREFIX = "bjar:2fa:attempts:";

    /** Long enough to install an app and scan a code, short enough not to linger. */
    public static final long PENDING_TTL_SECONDS = 15L * 60L;

    /** The player has to alt-tab to a browser, so this is generous. */
    public static final long CHALLENGE_TTL_SECONDS = 30L * 60L;

    /** Half a year on one machine before it asks again. */
    public static final long TRUST_TTL_SECONDS = 180L * 24L * 3600L;

    /** Wrong codes allowed per challenge before it has to be restarted from the game. */
    public static final int MAX_ATTEMPTS = 10;

    private static final long ATTEMPTS_TTL_SECONDS = 3600L;

    private TwoFactorService() {
    }

    /**
     * The identifier of a machine: the client hash, folded to one hex string.
     *
     * <p>Normalised before hashing because the same hash reaches us twice by different roads -
     * once in the login body and once through a browser address bar, where the trailing colon
     * may or may not survive.
     */
    public static String fingerprint(String clientHash) {
        if (clientHash == null) {
            return null;
        }

        String normalized = clientHash.trim().toLowerCase(Locale.ROOT);

        while (normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (normalized.isEmpty()) {
            return null;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    // ---- setup ---------------------------------------------------------------------------

    /** Remembers a secret while the player scans it. Not yet in effect. */
    public static void storePending(int userId, String secret) {
        write(PENDING_PREFIX + userId, secret, PENDING_TTL_SECONDS);
    }

    /** The secret an account is in the middle of setting up, if any. */
    public static String pending(int userId) {
        return read(PENDING_PREFIX + userId);
    }

    public static void clearPending(int userId) {
        delete(PENDING_PREFIX + userId);
    }

    // ---- login challenges ----------------------------------------------------------------

    /**
     * Records that this machine's login is waiting for a code, so the page it was sent to can
     * tell whose account is being verified.
     *
     * @return the fingerprint the challenge was filed under, or {@code null} if there was no
     *         usable client hash to file it under
     */
    public static String openChallenge(String clientHash, int userId) {
        String fingerprint = fingerprint(clientHash);

        if (fingerprint == null) {
            return null;
        }

        write(CHALLENGE_PREFIX + fingerprint, String.valueOf(userId), CHALLENGE_TTL_SECONDS);

        return fingerprint;
    }

    /** Whose login is waiting on this machine, or {@code null} when nothing is. */
    public static Integer challengeUser(String fingerprint) {
        if (fingerprint == null) {
            return null;
        }

        String raw = read(CHALLENGE_PREFIX + fingerprint);

        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            delete(CHALLENGE_PREFIX + fingerprint);

            return null;
        }
    }

    public static void closeChallenge(String fingerprint) {
        if (fingerprint != null) {
            delete(CHALLENGE_PREFIX + fingerprint);
            delete(ATTEMPTS_PREFIX + fingerprint);
        }
    }

    // ---- trusted devices -----------------------------------------------------------------

    /** Whether this machine has already answered a code for this account. */
    public static boolean isTrusted(int userId, String fingerprint) {
        if (fingerprint == null) {
            return false;
        }

        return read(trustedKey(userId, fingerprint)) != null;
    }

    /** Remembers a machine, so the next login from it goes straight through. */
    public static void trust(int userId, String fingerprint) {
        if (fingerprint == null) {
            return;
        }

        write(trustedKey(userId, fingerprint),
                String.valueOf(System.currentTimeMillis() / 1000L),
                TRUST_TTL_SECONDS);
    }

    /**
     * Forgets every machine of an account.
     *
     * <p>Called when 2FA is switched off and again when it is switched on, so a secret that was
     * once removed cannot come back to a machine that is still remembered from before.
     */
    public static void forgetDevices(int userId) {
        long generation = generation(userId);

        write(GENERATION_PREFIX + userId, String.valueOf(generation + 1), 0L);
    }

    private static String trustedKey(int userId, String fingerprint) {
        return TRUSTED_PREFIX + userId + ":" + generation(userId) + ":" + fingerprint;
    }

    private static long generation(int userId) {
        String raw = read(GENERATION_PREFIX + userId);

        if (raw == null || raw.isBlank()) {
            return 0L;
        }

        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ---- guessing ------------------------------------------------------------------------

    /**
     * Counts a wrong code and says whether this challenge has had too many.
     *
     * <p>Six digits are guessable given enough tries, and the code is the only thing standing
     * in front of the account at this point.
     */
    public static boolean tooManyFailures(String fingerprint) {
        if (fingerprint == null) {
            return false;
        }

        String raw = read(ATTEMPTS_PREFIX + fingerprint);

        int attempts = 0;

        if (raw != null && !raw.isBlank()) {
            try {
                attempts = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                attempts = 0;
            }
        }

        return attempts >= MAX_ATTEMPTS;
    }

    public static void countFailure(String fingerprint) {
        if (fingerprint == null) {
            return;
        }

        String raw = read(ATTEMPTS_PREFIX + fingerprint);

        int attempts = 0;

        if (raw != null && !raw.isBlank()) {
            try {
                attempts = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                attempts = 0;
            }
        }

        write(ATTEMPTS_PREFIX + fingerprint, String.valueOf(attempts + 1), ATTEMPTS_TTL_SECONDS);
    }

    /**
     * Forgets the wrong codes counted against a key.
     *
     * <p>Called after a code finally fits, so somebody who fat-fingered a few digits does not
     * carry those attempts around for the rest of the hour.
     */
    public static void clearFailures(String fingerprint) {
        if (fingerprint != null) {
            delete(ATTEMPTS_PREFIX + fingerprint);
        }
    }

    // ---- redis ---------------------------------------------------------------------------

    private static void write(String key, String value, long ttlSeconds) {
        try {
            if (ttlSeconds > 0) {
                Redis.getClient().set(key, value, SetParams.setParams().ex(ttlSeconds));
            } else {
                Redis.getClient().set(key, value);
            }
        } catch (Exception e) {
            logger.error("Failed to write <{}> to Redis", key, e);
        }
    }

    private static String read(String key) {
        try {
            return Redis.getClient().get(key);
        } catch (Exception e) {
            logger.error("Failed to read <{}> from Redis", key, e);

            return null;
        }
    }

    private static void delete(String key) {
        try {
            Redis.getClient().del(key);
        } catch (Exception e) {
            logger.error("Failed to delete <{}> from Redis", key, e);
        }
    }
}
