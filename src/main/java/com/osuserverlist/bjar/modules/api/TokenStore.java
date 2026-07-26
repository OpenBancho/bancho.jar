package com.osuserverlist.bjar.modules.api;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.engine.ProductionLevel;
import com.osuserverlist.bjar.modules.datastore.Redis;

import io.github.cdimascio.dotenv.Dotenv;

import lombok.Data;

/**
 * Redis backed store for OAuth2 access and refresh tokens.
 *
 * <p>Tokens are opaque 256-bit random strings. Everything about them lives server side under
 * {@code bjar:oauth:*}, and Redis expiry is the single source of truth for their lifetime:
 * an access token simply stops resolving once its key is gone.</p>
 *
 * <p>Refresh tokens rotate. Each successful refresh issues a brand new pair and invalidates
 * the one that was presented, while the {@code familyId} stays the same. The family key holds
 * the only refresh token currently considered valid, so if an old one is presented again — the
 * classic sign of a stolen token being replayed — the whole family is destroyed and the user
 * has to log in again.</p>
 *
 * <p>Cookie flags are not configurable on purpose. The domain follows {@code DOMAIN}, so the
 * cookies work across {@code api.}, {@code osu.} and the rest of the server's subdomains, and
 * {@code Secure} follows {@code LEVEL}: on in production, off in development so local http
 * still works.</p>
 */
public final class TokenStore {

    private static final Logger logger = LoggerFactory.getLogger("OAuth");

    /** Cookie carrying the access token, for browser clients. */
    public static final String ACCESS_COOKIE = "bjar_access";

    /** Cookie carrying the refresh token; scoped to the token endpoint only. */
    public static final String REFRESH_COOKIE = "bjar_refresh";

    /** Path the refresh cookie is limited to, so it is never sent to ordinary endpoints. */
    public static final String REFRESH_COOKIE_PATH = "/api/v1/oauth";

    /** Scope granted to a plain user login. */
    public static final String DEFAULT_SCOPE = "identify";

    /**
     * Lax is the right answer here: the cookies are shared between subdomains of one site,
     * which Lax allows, while still refusing to ride along with genuine cross-site requests.
     */
    private static final String SAME_SITE = "Lax";

    private static final String ACCESS_PREFIX = "bjar:oauth:access:";
    private static final String REFRESH_PREFIX = "bjar:oauth:refresh:";
    private static final String FAMILY_PREFIX = "bjar:oauth:family:";

    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static volatile Settings settings;

    private TokenStore() {
    }

    /** Token lifetimes, read once from the environment. */
    public static final class Settings {
        private final long accessTtlSeconds;
        private final long refreshTtlSeconds;

        private Settings(long accessTtlSeconds, long refreshTtlSeconds) {
            this.accessTtlSeconds = accessTtlSeconds;
            this.refreshTtlSeconds = refreshTtlSeconds;
        }

        public long getAccessTtlSeconds() {
            return accessTtlSeconds;
        }

        public long getRefreshTtlSeconds() {
            return refreshTtlSeconds;
        }
    }

    /** What the token endpoint hands back to the client. */
    @Data
    public static final class TokenPair {
        private final String accessToken;
        private final String refreshToken;
        private final long accessExpiresIn;
        private final long refreshExpiresIn;
        private final String scope;
    }

    public static Settings settings() {
        Settings current = settings;

        if (current == null) {
            synchronized (TokenStore.class) {
                current = settings;

                if (current == null) {
                    Dotenv dotenv = Dotenv.configure().systemProperties().ignoreIfMissing().load();

                    long accessMinutes = parseLong(dotenv.get("OAUTH_ACCESS_TTL_MINUTES", "60"), 60L);
                    long refreshDays = parseLong(dotenv.get("OAUTH_REFRESH_TTL_DAYS", "30"), 30L);

                    current = new Settings(
                            Math.max(1L, accessMinutes) * 60L,
                            Math.max(1L, refreshDays) * 86400L);

                    settings = current;
                }
            }
        }

        return current;
    }

    // ------------------------------------------------------------------
    // issuing
    // ------------------------------------------------------------------

    /**
     * Issues a fresh access and refresh pair for a user the caller has already authenticated.
     *
     * @return the pair, or {@code null} when Redis is unavailable.
     */
    public static TokenPair issue(int userId, String username, int privileges, String scope,
            String clientId, String ip) {
        return issue(userId, username, privileges, scope, clientId, ip, newToken());
    }

    private static TokenPair issue(int userId, String username, int privileges, String scope,
            String clientId, String ip, String familyId) {
        Settings config = settings();
        long now = epoch();

        String accessToken = newToken();
        String refreshToken = newToken();

        String grantedScope = scope == null || scope.isBlank() ? DEFAULT_SCOPE : scope.trim();

        OAuthToken access = build("access", familyId, userId, username, privileges, grantedScope,
                clientId, ip, now, now + config.getAccessTtlSeconds());

        OAuthToken refresh = build("refresh", familyId, userId, username, privileges, grantedScope,
                clientId, ip, now, now + config.getRefreshTtlSeconds());

        try {
            Redis.getClient().setex(ACCESS_PREFIX + accessToken, config.getAccessTtlSeconds(),
                    MAPPER.writeValueAsString(access));

            Redis.getClient().setex(REFRESH_PREFIX + refreshToken, config.getRefreshTtlSeconds(),
                    MAPPER.writeValueAsString(refresh));

            // The family remembers which refresh token is the live one.
            Redis.getClient().setex(FAMILY_PREFIX + familyId, config.getRefreshTtlSeconds(), refreshToken);
        } catch (Exception e) {
            logger.error("Failed to issue tokens for user <{}>", userId, e);
            return null;
        }

        return new TokenPair(accessToken, refreshToken, config.getAccessTtlSeconds(),
                config.getRefreshTtlSeconds(), grantedScope);
    }

    private static OAuthToken build(String type, String familyId, int userId, String username,
            int privileges, String scope, String clientId, String ip, long issuedAt, long expiresAt) {
        OAuthToken token = new OAuthToken();
        token.setType(type);
        token.setFamilyId(familyId);
        token.setUserId(userId);
        token.setUsername(username);
        token.setPrivileges(privileges);
        token.setScope(scope);
        token.setClientId(clientId);
        token.setIssuedAt(issuedAt);
        token.setExpiresAt(expiresAt);
        token.setIp(ip);
        return token;
    }

    // ------------------------------------------------------------------
    // lookup
    // ------------------------------------------------------------------

    /**
     * Resolves an access token.
     *
     * @return the token record, or {@code null} when it is unknown or expired.
     */
    public static OAuthToken resolveAccess(String token) {
        return read(ACCESS_PREFIX, token);
    }

    private static OAuthToken read(String prefix, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        String raw;

        try {
            raw = Redis.getClient().get(prefix + token);
        } catch (Exception e) {
            logger.error("Failed to read a token from Redis", e);
            return null;
        }

        if (raw == null) {
            return null;
        }

        try {
            OAuthToken record = MAPPER.readValue(raw, OAuthToken.class);
            record.setToken(token);

            // Redis expiry should have handled this already; belt and braces.
            if (record.getExpiresAt() > 0 && record.getExpiresAt() < epoch()) {
                Redis.getClient().del(prefix + token);
                return null;
            }

            return record;
        } catch (Exception e) {
            logger.error("Failed to parse a stored token, dropping it", e);
            safeDelete(prefix + token);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // rotation
    // ------------------------------------------------------------------

    /** Why a refresh attempt failed, so the endpoint can answer with the right OAuth2 error. */
    public enum RefreshError {
        /** Unknown or expired refresh token. */
        INVALID,
        /** A refresh token that had already been rotated away was presented again. */
        REPLAYED,
        /** Redis was unavailable. */
        UNAVAILABLE
    }

    /** Outcome of a rotation: either a new pair, or the reason it was refused. */
    @Data
    public static final class RefreshResult {
        private final TokenPair pair;
        private final RefreshError error;
        private final OAuthToken previous;

        public boolean isSuccess() {
            return pair != null;
        }
    }

    /**
     * Exchanges a refresh token for a new pair, invalidating the one presented.
     *
     * <p>The token that was presented stops working immediately: the family pointer moves to
     * the new token, and only the token the pointer names is accepted. If a token that is still
     * on record is presented after the pointer has moved past it, it has been replayed. Everything in that family is revoked, because either the client or an
     * attacker is using a token that should no longer exist and there is no way to tell which.</p>
     */
    public static RefreshResult refresh(String presented, String ip) {
        OAuthToken record = read(REFRESH_PREFIX, presented);

        if (record == null) {
            return new RefreshResult(null, RefreshError.INVALID, null);
        }

        String live;

        try {
            live = Redis.getClient().get(FAMILY_PREFIX + record.getFamilyId());
        } catch (Exception e) {
            logger.error("Failed to read a token family from Redis", e);
            return new RefreshResult(null, RefreshError.UNAVAILABLE, record);
        }

        if (live == null || !live.equals(presented)) {
            logger.warn("Refresh token replay detected for user <{}> from <{}>; revoking the family",
                    record.getUserId(), ip);

            revokeFamily(record.getFamilyId(), presented);

            return new RefreshResult(null, RefreshError.REPLAYED, record);
        }

        // The old record is deliberately left in Redis until its own TTL runs out. It is no
        // longer usable, because the family below now points at the new token, but keeping it
        // is what lets a replay be recognised as a replay rather than as a random bad token.
        TokenPair pair = issue(record.getUserId(), record.getUsername(), record.getPrivileges(),
                record.getScope(), record.getClientId(), ip, record.getFamilyId());

        if (pair == null) {
            return new RefreshResult(null, RefreshError.UNAVAILABLE, record);
        }

        return new RefreshResult(pair, null, record);
    }

    // ------------------------------------------------------------------
    // revocation
    // ------------------------------------------------------------------

    /** Revokes a single access token. */
    public static void revokeAccess(String token) {
        if (token != null && !token.isBlank()) {
            safeDelete(ACCESS_PREFIX + token);
        }
    }

    /**
     * Revokes a refresh token together with its whole family, which is what logging out means.
     *
     * <p>Access tokens already handed out are left to expire on their own; that is the standard
     * trade-off of opaque short-lived access tokens, and the reason they are short lived.</p>
     */
    public static void revokeRefresh(String token) {
        OAuthToken record = read(REFRESH_PREFIX, token);

        if (record == null) {
            safeDelete(REFRESH_PREFIX + token);
            return;
        }

        revokeFamily(record.getFamilyId(), token);
    }

    /**
     * Revokes a whole family, ending the refresh chain that descends from one login.
     *
     * <p>Access tokens already handed out keep working until they expire on their own; they
     * are not indexed by family, and their lifetime is short by design.</p>
     */
    public static void revokeFamily(String familyId) {
        if (familyId == null || familyId.isBlank()) {
            return;
        }

        safeDelete(FAMILY_PREFIX + familyId);
    }

    private static void revokeFamily(String familyId, String token) {
        safeDelete(REFRESH_PREFIX + token);

        if (familyId != null && !familyId.isBlank()) {
            safeDelete(FAMILY_PREFIX + familyId);
        }
    }

    private static void safeDelete(String key) {
        try {
            Redis.getClient().del(key);
        } catch (Exception e) {
            logger.error("Failed to delete <{}> from Redis", key, e);
        }
    }

    // ------------------------------------------------------------------
    // cookies
    // ------------------------------------------------------------------

    /** {@code Set-Cookie} for the access token. */
    public static String buildAccessCookie(String token) {
        return cookie(ACCESS_COOKIE, token, settings().getAccessTtlSeconds(), "/");
    }

    /** {@code Set-Cookie} for the refresh token, restricted to the token endpoint. */
    public static String buildRefreshCookie(String token) {
        return cookie(REFRESH_COOKIE, token, settings().getRefreshTtlSeconds(), REFRESH_COOKIE_PATH);
    }

    public static String buildExpiredAccessCookie() {
        return cookie(ACCESS_COOKIE, "", 0L, "/");
    }

    public static String buildExpiredRefreshCookie() {
        return cookie(REFRESH_COOKIE, "", 0L, REFRESH_COOKIE_PATH);
    }

    private static String cookie(String name, String value, long maxAge, String path) {
        StringBuilder builder = new StringBuilder()
                .append(name).append('=').append(value)
                .append("; Path=").append(path)
                .append("; Max-Age=").append(maxAge)
                .append("; HttpOnly")
                .append("; SameSite=").append(SAME_SITE);

        if (isSecure()) {
            builder.append("; Secure");
        }

        String domain = cookieDomain();

        if (domain != null) {
            builder.append("; Domain=").append(domain);
        }

        return builder.toString();
    }

    /**
     * The cookies belong to the server's own domain, so they follow {@code DOMAIN} and are
     * shared by every subdomain — {@code api.}, {@code osu.} and the frontend alike.
     *
     * @return the leading-dot domain, or {@code null} to let the browser scope the cookie to
     *         whichever host issued it.
     */
    private static String cookieDomain() {
        try {
            String domain = App.server.enviromentConfig.getDomain();

            if (domain != null && !domain.isBlank()) {
                return "." + domain.trim().toLowerCase(Locale.ROOT);
            }
        } catch (Exception e) {
            logger.warn("Could not read the configured domain; scoping cookies to the request host");
        }

        return null;
    }

    /**
     * {@code Secure} in production, dropped in development so the tokens still work over plain
     * http on localhost.
     */
    private static boolean isSecure() {
        try {
            return App.server.enviromentConfig.getLevel() != ProductionLevel.DEVELOPMENT;
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    private static long epoch() {
        return System.currentTimeMillis() / 1000L;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
