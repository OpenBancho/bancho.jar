package com.osuserverlist.bjar.handlers.osu;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.repos.UserRepository;

/**
 * Shared credential handling for the in-game web routes.
 *
 * <p>Every {@code /web/*.php} endpoint identifies the caller the same way: the
 * client repeats the username and the md5 of its password on each request, and
 * the server matches that pair against the sessions it already handed out. A
 * player who is not online therefore cannot use these routes at all, which is
 * exactly the behaviour the client expects.</p>
 *
 * <p>This class is deliberately not a {@code Handler}, so the route scanner
 * ignores it.</p>
 */
public final class OsuWebAuth {

    private OsuWebAuth() {
    }

    /**
     * Resolves the online player behind a username / password-md5 pair.
     *
     * @return the live session, or {@code null} when the credentials do not match
     *         an online player.
     */
    public static Player authenticate(String username, String passwordMd5) {
        if (username == null || username.isBlank() || passwordMd5 == null || passwordMd5.isBlank()) {
            return null;
        }

        String decoded = decode(username);
        String apiIdent = String.format("%s|%s", decoded, passwordMd5);

        Player player = App.server.playerManager.getByApiIdent(apiIdent);

        if (player == null || player.isBot()) {
            return null;
        }

        return player;
    }

    /** The client percent-encodes names that contain spaces. */
    public static String decode(String value) {
        if (value == null) {
            return null;
        }

        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    /**
     * Privilege bits of a live session.
     *
     * <p>Reads the attached row when the session carries one and falls back to the
     * database, so a session created before a privilege change still answers with
     * the current value.</p>
     */
    public static int privilegesOf(Player player) {
        UserEntity entity = player.getEntity();

        if (entity == null) {
            entity = UserRepository.findById(player.getId());
        }

        if (entity == null || entity.getPrivileges() == null) {
            return 0;
        }

        return entity.getPrivileges();
    }
}
