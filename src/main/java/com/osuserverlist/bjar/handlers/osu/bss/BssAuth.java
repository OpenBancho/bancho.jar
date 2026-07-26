package com.osuserverlist.bjar.handlers.osu.bss;

import java.util.ArrayList;
import java.util.List;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;

/**
 * Shared helpers for the Beatmap Submission System endpoints.
 *
 * <p>The submission requests are sent by the beatmap editor, which is not
 * guaranteed to share the bancho session, so the credentials are verified
 * against the online session first and against the database afterwards.</p>
 */
final class BssAuth {

    private BssAuth() {
    }

    /**
     * @param username    osu! username, parameter {@code u}.
     * @param passwordMd5 MD5 of the password, parameter {@code h}.
     * @return the authenticated user, or {@code null}.
     */
    static UserEntity authenticate(String username, String passwordMd5) {
        if (username == null || username.isBlank() || passwordMd5 == null || passwordMd5.isBlank()) {
            return null;
        }

        Player player = App.server.playerManager.getByApiIdent(username + "|" + passwordMd5);

        if (player != null && player.getEntity() != null) {
            return player.getEntity();
        }

        UserEntity user = UserRepository.findByName(username);

        if (user == null || user.getPasswordHash() == null) {
            return null;
        }

        try {
            if (OpenBSDBCrypt.checkPassword(user.getPasswordHash(), passwordMd5.toCharArray())) {
                return user;
            }
        } catch (Exception e) {
            return null;
        }

        return null;
    }

    /**
     * Reads a parameter from the query string, falling back to the form body.
     * The editor is inconsistent about where it puts them.
     */
    static String param(Context ctx, String name) {
        String value = ctx.queryParam(name);

        if (value != null) {
            return value;
        }

        try {
            return ctx.formParam(name);
        } catch (Exception e) {
            return null;
        }
    }

    static int intParam(Context ctx, String name, int fallback) {
        String value = param(ctx, name);

        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Parses a comma separated list of beatmap ids. Empty entries become
     * {@code 0}, which marks a difficulty that still needs an id.
     */
    static List<Long> idList(String raw) {
        List<Long> ids = new ArrayList<>();

        if (raw == null || raw.isBlank()) {
            return ids;
        }

        for (String part : raw.split(",")) {
            String trimmed = part.trim();

            if (trimmed.isEmpty()) {
                ids.add(0L);
                continue;
            }

            try {
                ids.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                ids.add(0L);
            }
        }

        return ids;
    }

    static String join(List<Long> ids) {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }

            builder.append(ids.get(i));
        }

        return builder.toString();
    }
}
