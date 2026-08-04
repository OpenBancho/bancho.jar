package com.osuserverlist.bjar.modules.account;

import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.bjar.modules.datastore.Redis;

import redis.clients.jedis.params.SetParams;

/**
 * One-shot password reset tickets, handed out by staff and redeemed by the player.
 *
 * <p>A player who has forgotten their password cannot prove who they are to this server: the
 * email column is never verified, so "send a mail and trust it" would be a way in rather than a
 * way back. The proof therefore stays human - staff decide, out of band, that the person asking
 * is the person who owns the account - and all this class does is turn that decision into
 * something the player can act on without staff ever learning, choosing or typing their new
 * password.
 *
 * <p>The ticket is an opaque 256-bit random string and lives only in Redis under
 * {@code bjar:password-reset:*}, so its lifetime is Redis expiry and nothing else: no column to
 * migrate, no row left behind when it is used, and no way to read a pending reset out of the
 * database. It is single use, and issuing a new one for an account destroys the previous one, so
 * a link that was pasted into the wrong chat window can be replaced by asking for another.
 */
public final class PasswordResetService {

    private static final Logger logger = LoggerFactory.getLogger("PasswordReset");

    private static final String TICKET_PREFIX = "bjar:password-reset:ticket:";

    /** Points at the account's live ticket, so issuing a second one retires the first. */
    private static final String USER_PREFIX = "bjar:password-reset:user:";

    private static final int TOKEN_BYTES = 32;

    /** How long a link is good for when the caller does not say. */
    public static final long DEFAULT_TTL_SECONDS = 24L * 3600L;

    /** Ten minutes is the shortest link worth sending to somebody. */
    public static final long MIN_TTL_SECONDS = 600L;

    /** A week: long enough for a player in another timezone, short enough to expire. */
    public static final long MAX_TTL_SECONDS = 7L * 24L * 3600L;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PasswordResetService() {
    }

    /** What a ticket knows: whose account it opens, who handed it out, and until when. */
    public static final class Ticket {
        private int userId;
        private int issuedBy;
        private long issuedAt;
        private long expiresAt;

        public int getUserId() {
            return userId;
        }

        public void setUserId(int userId) {
            this.userId = userId;
        }

        public int getIssuedBy() {
            return issuedBy;
        }

        public void setIssuedBy(int issuedBy) {
            this.issuedBy = issuedBy;
        }

        public long getIssuedAt() {
            return issuedAt;
        }

        public void setIssuedAt(long issuedAt) {
            this.issuedAt = issuedAt;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(long expiresAt) {
            this.expiresAt = expiresAt;
        }
    }

    /** What was issued: the secret to hand over, and when it stops working. */
    public static final class Issued {
        private final String token;
        private final long expiresAt;
        private final long ttlSeconds;

        private Issued(String token, long expiresAt, long ttlSeconds) {
            this.token = token;
            this.expiresAt = expiresAt;
            this.ttlSeconds = ttlSeconds;
        }

        public String getToken() {
            return token;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }
    }

    /** Keeps a requested lifetime inside the bounds above. */
    public static long clampTtl(long seconds) {
        if (seconds <= 0) {
            return DEFAULT_TTL_SECONDS;
        }

        return Math.min(MAX_TTL_SECONDS, Math.max(MIN_TTL_SECONDS, seconds));
    }

    /**
     * Creates a ticket for an account, replacing any ticket it already had.
     *
     * @param userId   the account the ticket opens
     * @param issuedBy the staff member who asked for it, kept for the audit trail
     * @return the issued ticket, or {@code null} when Redis is unavailable
     */
    public static Issued issue(int userId, int issuedBy, long ttlSeconds) {
        long ttl = clampTtl(ttlSeconds);
        long now = System.currentTimeMillis() / 1000L;

        String token = newToken();

        Ticket ticket = new Ticket();
        ticket.setUserId(userId);
        ticket.setIssuedBy(issuedBy);
        ticket.setIssuedAt(now);
        ticket.setExpiresAt(now + ttl);

        try {
            // A second link for the same account retires the first: two live links mean
            // nobody can say which one was pasted where.
            String previous = Redis.getClient().get(USER_PREFIX + userId);

            if (previous != null && !previous.isBlank()) {
                Redis.getClient().del(TICKET_PREFIX + previous);
            }

            Redis.getClient().set(
                    TICKET_PREFIX + token,
                    MAPPER.writeValueAsString(ticket),
                    SetParams.setParams().ex(ttl));

            Redis.getClient().set(
                    USER_PREFIX + userId,
                    token,
                    SetParams.setParams().ex(ttl));
        } catch (Exception e) {
            logger.error("Failed to issue a password reset ticket for user <{}>", userId, e);
            return null;
        }

        return new Issued(token, ticket.getExpiresAt(), ttl);
    }

    /**
     * Reads a ticket without spending it, so the reset page can tell somebody that their link
     * has expired before asking them to think of a password.
     *
     * @return the ticket, or {@code null} when it is unknown or expired
     */
    public static Ticket resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        String raw;

        try {
            raw = Redis.getClient().get(TICKET_PREFIX + token);
        } catch (Exception e) {
            logger.error("Failed to read a password reset ticket from Redis", e);
            return null;
        }

        if (raw == null) {
            return null;
        }

        try {
            Ticket ticket = MAPPER.readValue(raw, Ticket.class);

            // Redis expiry should have handled this already; belt and braces.
            if (ticket.getExpiresAt() > 0 && ticket.getExpiresAt() < System.currentTimeMillis() / 1000L) {
                consume(token, ticket.getUserId());
                return null;
            }

            return ticket;
        } catch (Exception e) {
            logger.error("Failed to parse a stored password reset ticket, dropping it", e);
            safeDelete(TICKET_PREFIX + token);
            return null;
        }
    }

    /** Spends a ticket. Called once the new password is actually stored. */
    public static void consume(String token, int userId) {
        safeDelete(TICKET_PREFIX + token);
        safeDelete(USER_PREFIX + userId);
    }

    /** Drops the live ticket of an account, if it has one. */
    public static void revoke(int userId) {
        String token;

        try {
            token = Redis.getClient().get(USER_PREFIX + userId);
        } catch (Exception e) {
            logger.error("Failed to read the password reset ticket of user <{}>", userId, e);
            return;
        }

        if (token != null && !token.isBlank()) {
            safeDelete(TICKET_PREFIX + token);
        }

        safeDelete(USER_PREFIX + userId);
    }

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);

        return ENCODER.encodeToString(bytes);
    }

    private static void safeDelete(String key) {
        try {
            Redis.getClient().del(key);
        } catch (Exception e) {
            logger.error("Failed to delete <{}> from Redis", key, e);
        }
    }
}
