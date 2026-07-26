package com.osuserverlist.bjar.handlers.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.database.RelationshipEntity;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.models.database.UserAchievementEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.admin.AdminActions;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.api.TokenStore;
import com.osuserverlist.bjar.modules.datastore.Redis;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.packets.server.UtilServerPackets.NotificationPacket;
import com.osuserverlist.bjar.repos.StatsRepository;
import com.osuserverlist.bjar.repos.UserRepository;

import io.ebean.DB;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

/**
 * Self service endpoints: everything a signed in player may do to their own account.
 *
 * <p>These are separate from the moderation endpoints on purpose. Nothing here takes a user id:
 * the account being read or changed is always the one behind the access token, so there is no
 * way to aim any of it at somebody else. Reading needs the {@code identify} scope; every write
 * needs the {@code profile} scope plus an unrestricted account.</p>
 *
 * <p>Anything that changes a credential or destroys data asks for the current password again,
 * so a stolen access token on its own is not enough to take an account over.</p>
 */
public final class SelfApiRoutes {

    private static final Logger logger = LoggerFactory.getLogger("SelfApi");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Same rules as in-game registration, so an account cannot end up unable to log in. */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int BCRYPT_COST = 11;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 32;
    private static final int MIN_UNIQUE_PASSWORD_CHARS = 3;
    private static final int MAX_EMAIL_LENGTH = 254;

    /** Column limits from {@code UserEntity}. */
    private static final int MAX_USERPAGE_LENGTH = 2048;
    private static final int MAX_BADGE_NAME_LENGTH = 16;
    private static final int MAX_BADGE_ICON_LENGTH = 64;

    /** Mouse, keyboard, tablet and touch, as a bitmask. */
    private static final int MAX_PLAY_STYLE = 15;

    private static final String LEADERBOARD_KEY = "bjar:leaderboard:";

    private SelfApiRoutes() {
    }

    // ------------------------------------------------------------------
    // read
    // ------------------------------------------------------------------

    /** GET /api/v1/me */
    @Host("api.")
    @Path("/api/v1/me")
    @HttpMethod("GET")
    public static class MeHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Own profile",
            description = "The account behind the access token, including the private fields (email, silence and donor end, userpage). Requires the identify scope.",
            tags = { "Me" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.SelfResponse.class) }, description = "Own profile and stats per mode"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token"),
                @OpenApiResponse(status = "403", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The token lacks the required scope, or the account lacks the required privilege"),
                @OpenApiResponse(status = "404", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The account no longer exists")
            },
            path = "/api/v1/me",
            methods = io.javalin.openapi.HttpMethod.GET
        )
        public void handle(@NotNull Context ctx) {
            OAuthToken token = ApiAuth.require(ctx);
            if (token == null || !ApiAuth.requireScope(ctx, token, ApiAuth.SCOPE_IDENTIFY)) {
                return;
            }

            UserEntity user = UserRepository.findById(token.getUserId());
            if (user == null) {
                ApiAuth.notFound(ctx, "No such user.");
                return;
            }

            // The public mapper, plus the fields only the owner is allowed to see.
            Map<String, Object> info = new LinkedHashMap<>(ApiMappers.userInfo(user));
            info.put("email", user.getEmail());
            info.put("silence_end", user.getSilenceEnd());
            info.put("donor_end", user.getDonorEnd());
            info.put("clan_priv", user.getClanPriv());
            info.put("userpage_content", user.getUserpageContent());
            info.put("custom_badge_name", user.getCustomBadgeName());
            info.put("custom_badge_icon", user.getCustomBadgeIcon());

            Map<String, Object> statsByMode = new LinkedHashMap<>();
            for (StatsEntity stats : StatsRepository.findAllByUser(user.getId())) {
                statsByMode.put(String.valueOf(stats.getId().getMode()), ApiMappers.stats(stats));
            }

            Map<String, Object> body = ApiAuth.success();
            body.put("info", info);
            body.put("stats", statsByMode);
            body.put("scope", token.getScope());

            ctx.json(body);
        }
    }

    // ------------------------------------------------------------------
    // profile
    // ------------------------------------------------------------------

    /** POST /api/v1/me/update */
    @Host("api.")
    @Path("/api/v1/me/update")
    @HttpMethod("POST")
    public static class UpdateHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Update own profile",
            description = "Changes the profile fields of the account behind the access token. Only the supplied fields are touched. Requires the profile scope and an unrestricted account.",
            tags = { "Me" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.SelfUpdateRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) }, description = "Done"),
                @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing or invalid field"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token"),
                @OpenApiResponse(status = "403", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The token lacks the required scope, or the account lacks the required privilege")
            },
            path = "/api/v1/me/update",
            methods = io.javalin.openapi.HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            OAuthToken token = ApiAuth.require(ctx);
            if (token == null || !ApiAuth.requireProfile(ctx, token)) {
                return;
            }

            JsonNode body = ApiAuth.body(ctx);
            if (body == null) {
                return;
            }

            UserEntity user = UserRepository.findById(token.getUserId());
            if (user == null) {
                ApiAuth.notFound(ctx, "No such user.");
                return;
            }

            boolean changed = false;

            if (body.has("userpage_content")) {
                JsonNode node = body.get("userpage_content");

                if (node.isNull()) {
                    user.setUserpageContent(null);
                } else if (node.isTextual()) {
                    String value = node.asText();

                    if (value.length() > MAX_USERPAGE_LENGTH) {
                        ApiAuth.badRequest(ctx, "The userpage may be at most "
                                + MAX_USERPAGE_LENGTH + " characters long.");
                        return;
                    }

                    user.setUserpageContent(value);
                } else {
                    ApiAuth.badRequest(ctx, "userpage_content must be a string or null.");
                    return;
                }

                changed = true;
            }

            if (body.has("preferred_mode")) {
                int mode = ApiAuth.intField(body, "preferred_mode");

                if (mode < 0 || mode >= AdminActions.MODE_COUNT) {
                    ApiAuth.badRequest(ctx, "preferred_mode must be between 0 and "
                            + (AdminActions.MODE_COUNT - 1) + ".");
                    return;
                }

                user.setPreferredMode(mode);
                changed = true;
            }

            if (body.has("play_style")) {
                int style = ApiAuth.intField(body, "play_style");

                if (style < 0 || style > MAX_PLAY_STYLE) {
                    ApiAuth.badRequest(ctx, "play_style must be between 0 and "
                            + MAX_PLAY_STYLE + ".");
                    return;
                }

                user.setPlayStyle(style);
                changed = true;
            }

            boolean touchesBadge = body.has("custom_badge_name") || body.has("custom_badge_icon");

            // A custom badge is a supporter perk in game; the API keeps the same rule.
            if (touchesBadge && !ApiAuth.requireAny(ctx, token, Privileges.SUPPORTER, Privileges.PREMIUM)) {
                return;
            }

            if (body.has("custom_badge_name")) {
                JsonNode node = body.get("custom_badge_name");

                if (node.isNull()) {
                    user.setCustomBadgeName(null);
                } else if (node.isTextual() && node.asText().length() <= MAX_BADGE_NAME_LENGTH) {
                    user.setCustomBadgeName(node.asText());
                } else {
                    ApiAuth.badRequest(ctx, "custom_badge_name must be null or at most "
                            + MAX_BADGE_NAME_LENGTH + " characters.");
                    return;
                }

                changed = true;
            }

            if (body.has("custom_badge_icon")) {
                JsonNode node = body.get("custom_badge_icon");

                if (node.isNull()) {
                    user.setCustomBadgeIcon(null);
                } else if (node.isTextual() && node.asText().length() <= MAX_BADGE_ICON_LENGTH) {
                    user.setCustomBadgeIcon(node.asText());
                } else {
                    ApiAuth.badRequest(ctx, "custom_badge_icon must be null or at most "
                            + MAX_BADGE_ICON_LENGTH + " characters.");
                    return;
                }

                changed = true;
            }

            if (!changed) {
                ApiAuth.badRequest(ctx, "Nothing to update.");
                return;
            }

            UserRepository.save(user);

            logger.info("User <{}> updated their profile from <{}>", user.getId(), ctx.ip());

            Map<String, Object> response = ApiAuth.success();
            response.put("info", ApiMappers.userInfo(user));

            ctx.json(response);
        }
    }

    // ------------------------------------------------------------------
    // credentials
    // ------------------------------------------------------------------

    /** POST /api/v1/me/email */
    @Host("api.")
    @Path("/api/v1/me/email")
    @HttpMethod("POST")
    public static class EmailHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Change own email",
            description = "Changes the email address. The current password has to be supplied again, so a stolen access token is not enough. Requires the profile scope.",
            tags = { "Me" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.SelfEmailRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) }, description = "Done"),
                @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing or invalid field"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token"),
                @OpenApiResponse(status = "403", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The token lacks the required scope, or the account lacks the required privilege")
            },
            path = "/api/v1/me/email",
            methods = io.javalin.openapi.HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            OAuthToken token = ApiAuth.require(ctx);
            if (token == null || !ApiAuth.requireProfile(ctx, token)) {
                return;
            }

            JsonNode body = ApiAuth.body(ctx);
            if (body == null) {
                return;
            }

            UserEntity user = confirmPassword(ctx, token, body);
            if (user == null) {
                return;
            }

            String email = ApiAuth.stringField(body, "email");
            if (email == null) {
                ApiAuth.badRequest(ctx, "An email is required.");
                return;
            }

            String trimmed = email.trim().toLowerCase(Locale.ROOT);

            if (trimmed.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(trimmed).matches()) {
                ApiAuth.badRequest(ctx, "That email is not valid.");
                return;
            }

            UserEntity existing = UserRepository.findByEmail(trimmed);

            if (existing != null && !existing.getId().equals(user.getId())) {
                ApiAuth.badRequest(ctx, "That email is already in use.");
                return;
            }

            user.setEmail(trimmed);
            UserRepository.save(user);

            logger.info("User <{}> changed their email from <{}>", user.getId(), ctx.ip());

            ctx.json(ApiAuth.success());
        }
    }

    /** POST /api/v1/me/password */
    @Host("api.")
    @Path("/api/v1/me/password")
    @HttpMethod("POST")
    public static class PasswordHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Change own password",
            description = "Changes the password and revokes every other session of the account. The current password has to be supplied again. Requires the profile scope.",
            tags = { "Me" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.SelfPasswordRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) }, description = "Done"),
                @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing or invalid field"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token"),
                @OpenApiResponse(status = "403", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The token lacks the required scope, or the account lacks the required privilege")
            },
            path = "/api/v1/me/password",
            methods = io.javalin.openapi.HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            OAuthToken token = ApiAuth.require(ctx);
            if (token == null || !ApiAuth.requireProfile(ctx, token)) {
                return;
            }

            JsonNode body = ApiAuth.body(ctx);
            if (body == null) {
                return;
            }

            UserEntity user = confirmPassword(ctx, token, body);
            if (user == null) {
                return;
            }

            String password = ApiAuth.stringField(body, "new_password");
            if (password == null) {
                ApiAuth.badRequest(ctx, "A new_password is required.");
                return;
            }

            if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
                ApiAuth.badRequest(ctx, "The password must be "
                        + MIN_PASSWORD_LENGTH + "-" + MAX_PASSWORD_LENGTH + " characters in length.");
                return;
            }

            if (password.chars().distinct().count() <= MIN_UNIQUE_PASSWORD_CHARS) {
                ApiAuth.badRequest(ctx, "The password must contain more than "
                        + MIN_UNIQUE_PASSWORD_CHARS + " unique characters.");
                return;
            }

            user.setPasswordHash(hash(password));
            UserRepository.save(user);

            // A password change ends the sessions that were opened with the old one.
            TokenStore.revokeAccess(token.getToken());
            TokenStore.revokeFamily(token.getFamilyId());
            clearCookies(ctx);

            for (Player player : sessionsOf(user.getId())) {
                player.sendPacket(new NotificationPacket("Your password was changed; please log in again."));
                App.server.playerManager.disconnect(player);
            }

            logger.info("User <{}> changed their password from <{}>", user.getId(), ctx.ip());

            Map<String, Object> response = ApiAuth.success();
            response.put("reauthenticate", true);

            ctx.json(response);
        }
    }

    // ------------------------------------------------------------------
    // deletion
    // ------------------------------------------------------------------

    /** POST /api/v1/me/delete */
    @Host("api.")
    @Path("/api/v1/me/delete")
    @HttpMethod("POST")
    public static class DeleteHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Delete own account",
            description = "Irreversibly deletes the account behind the access token together with its scores, stats and sessions. The current password has to be supplied again. Requires the profile scope.",
            tags = { "Me" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.SelfDeleteRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) }, description = "Done"),
                @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing or invalid field"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token"),
                @OpenApiResponse(status = "403", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The token lacks the required scope, or the account lacks the required privilege")
            },
            path = "/api/v1/me/delete",
            methods = io.javalin.openapi.HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            OAuthToken token = ApiAuth.require(ctx);
            if (token == null || !ApiAuth.requireProfile(ctx, token)) {
                return;
            }

            JsonNode body = ApiAuth.body(ctx);
            if (body == null) {
                return;
            }

            UserEntity user = confirmPassword(ctx, token, body);
            if (user == null) {
                return;
            }

            // Deliberately awkward: this cannot be undone.
            if (!ApiAuth.booleanField(body, "confirm", false)) {
                ApiAuth.badRequest(ctx, "Set confirm to true to delete the account.");
                return;
            }

            int userId = user.getId();

            List<ScoreEntity> scores = DB.find(ScoreEntity.class)
                    .where()
                    .eq("user.id", userId)
                    .findList();

            if (!scores.isEmpty()) {
                DB.deleteAll(scores);
            }

            for (StatsEntity stats : StatsRepository.findAllByUser(userId)) {
                StatsRepository.delete(stats);
            }

            List<RelationshipEntity> owned = DB.find(RelationshipEntity.class)
                    .where()
                    .eq("owner.id", userId)
                    .findList();

            if (!owned.isEmpty()) {
                DB.deleteAll(owned);
            }

            // Also the rows where somebody else befriended or blocked this account.
            List<RelationshipEntity> incoming = DB.find(RelationshipEntity.class)
                    .where()
                    .eq("target.id", userId)
                    .findList();

            if (!incoming.isEmpty()) {
                DB.deleteAll(incoming);
            }

            List<UserAchievementEntity> achievements = DB.find(UserAchievementEntity.class)
                    .where()
                    .eq("user.id", userId)
                    .findList();

            if (!achievements.isEmpty()) {
                DB.deleteAll(achievements);
            }

            for (int mode = 0; mode < AdminActions.MODE_COUNT; mode++) {
                Redis.getClient().zrem(LEADERBOARD_KEY + mode, String.valueOf(userId));
            }

            for (Player player : sessionsOf(userId)) {
                player.sendPacket(new NotificationPacket("Your account has been deleted."));
                App.server.playerManager.disconnect(player);
            }

            TokenStore.revokeAccess(token.getToken());
            TokenStore.revokeFamily(token.getFamilyId());
            clearCookies(ctx);

            UserRepository.delete(user);

            logger.warn("User <{}> deleted their own account from <{}> ({} score(s) removed)",
                    userId, ctx.ip(), scores.size());

            ctx.json(ApiAuth.success());
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Re-checks the caller's password before a dangerous change.
     *
     * <p>Accepts either {@code current_password} or a pre-hashed {@code current_password_md5},
     * matching the token endpoint. Writes the response and returns {@code null} on failure.</p>
     */
    private static UserEntity confirmPassword(Context ctx, OAuthToken token, JsonNode body) {
        UserEntity user = UserRepository.findById(token.getUserId());

        if (user == null) {
            ApiAuth.notFound(ctx, "No such user.");
            return null;
        }

        String password = ApiAuth.stringField(body, "current_password");
        String passwordMd5 = ApiAuth.stringField(body, "current_password_md5");

        if (password == null && passwordMd5 == null) {
            ApiAuth.badRequest(ctx, "Your current_password is required.");
            return null;
        }

        if (passwordMd5 == null) {
            passwordMd5 = md5Hex(password);
        }

        if (user.getPasswordHash() == null || !checkPassword(user, passwordMd5)) {
            logger.warn("Rejected an account change for user <{}> from <{}>: wrong password",
                    user.getId(), ctx.ip());
            ApiAuth.unauthorized(ctx, "invalid_grant", "The current password is incorrect.");
            return null;
        }

        return user;
    }

    /** Every live bancho session belonging to an account. */
    private static List<Player> sessionsOf(int userId) {
        List<Player> sessions = new ArrayList<>();

        for (Player player : App.server.playerManager.getAllSessions()) {
            if (player.getId() == userId && !player.isBot()) {
                sessions.add(player);
            }
        }

        return sessions;
    }

    /** Expires both cookies, for browser clients that authenticated with them. */
    private static void clearCookies(Context ctx) {
        ctx.res().addHeader("Set-Cookie", TokenStore.buildExpiredAccessCookie());
        ctx.res().addHeader("Set-Cookie", TokenStore.buildExpiredRefreshCookie());
    }

    private static boolean checkPassword(UserEntity user, String passwordMd5) {
        try {
            return OpenBSDBCrypt.checkPassword(user.getPasswordHash(), passwordMd5.toCharArray());
        } catch (Exception e) {
            return false;
        }
    }

    /** bcrypt over the md5 of the password, exactly as registration and the game client do it. */
    private static String hash(String password) {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);

        return OpenBSDBCrypt.generate(md5Hex(password).toCharArray(), salt, BCRYPT_COST);
    }

    private static String md5Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");

            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is unavailable", e);
        }
    }
}
