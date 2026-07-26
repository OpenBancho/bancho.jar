package com.osuserverlist.bjar.handlers.api.me;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.api.TokenStore;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;

/** Shared validation, password and session helpers of the /api/v1/me endpoints. */
final class MeSupport {
    static final Logger logger = LoggerFactory.getLogger("SelfApi");

    static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Same rules as in-game registration, so an account cannot end up unable to log in. */
    static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    static final int BCRYPT_COST = 11;

    static final int MIN_PASSWORD_LENGTH = 8;

    static final int MAX_PASSWORD_LENGTH = 32;

    static final int MIN_UNIQUE_PASSWORD_CHARS = 3;

    static final int MAX_EMAIL_LENGTH = 254;

    /** Column limits from {@code UserEntity}. */
    static final int MAX_USERPAGE_LENGTH = 2048;

    static final int MAX_BADGE_NAME_LENGTH = 16;

    static final int MAX_BADGE_ICON_LENGTH = 64;

    /** Mouse, keyboard, tablet and touch, as a bitmask. */
    static final int MAX_PLAY_STYLE = 15;

    static final String LEADERBOARD_KEY = "bjar:leaderboard:";

    /**
     * Re-checks the caller's password before a dangerous change.
     *
     * <p>Accepts either {@code current_password} or a pre-hashed {@code current_password_md5},
     * matching the token endpoint. Writes the response and returns {@code null} on failure.</p>
     */
    static UserEntity confirmPassword(Context ctx, OAuthToken token, JsonNode body) {
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
    static List<Player> sessionsOf(int userId) {
        List<Player> sessions = new ArrayList<>();

        for (Player player : App.server.playerManager.getAllSessions()) {
            if (player.getId() == userId && !player.isBot()) {
                sessions.add(player);
            }
        }

        return sessions;
    }

    /** Expires both cookies, for browser clients that authenticated with them. */
    static void clearCookies(Context ctx) {
        ctx.res().addHeader("Set-Cookie", TokenStore.buildExpiredAccessCookie());
        ctx.res().addHeader("Set-Cookie", TokenStore.buildExpiredRefreshCookie());
    }

    static boolean checkPassword(UserEntity user, String passwordMd5) {
        try {
            return OpenBSDBCrypt.checkPassword(user.getPasswordHash(), passwordMd5.toCharArray());
        } catch (Exception e) {
            return false;
        }
    }

    /** bcrypt over the md5 of the password, exactly as registration and the game client do it. */
    static String hash(String password) {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);

        return OpenBSDBCrypt.generate(md5Hex(password).toCharArray(), salt, BCRYPT_COST);
    }

    static String md5Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");

            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is unavailable", e);
        }
    }
}
