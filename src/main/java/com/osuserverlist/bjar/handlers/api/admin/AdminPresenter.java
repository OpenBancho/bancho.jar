package com.osuserverlist.bjar.handlers.api.admin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.LogEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Privileges;

import io.ebean.DB;

/**
 * Shapes accounts and staff history for the admin panel.
 *
 * <p>Kept in one place because the panel shows the same player in three different sizes: a card
 * in the list, a header on their own page, and a name on somebody else's history. If each
 * endpoint decided for itself what a player looks like, the badges would eventually disagree,
 * and a moderator who sees "restricted" in one place and not another stops trusting either.</p>
 */
final class AdminPresenter {

    /** Privileges worth showing as a badge. Bits like UNRESTRICTED are noise on a card. */
    private static final Privileges[] BADGES = {
        Privileges.DEVELOPER,
        Privileges.ADMINISTRATOR,
        Privileges.MODERATOR,
        Privileges.NOMINATOR,
        Privileges.TOURNEY_MANAGER,
        Privileges.ALUMNI,
        Privileges.PREMIUM,
        Privileges.SUPPORTER,
        Privileges.WHITELISTED,
        Privileges.VERIFIED,
    };

    private AdminPresenter() {
    }

    /** Lower-case privilege names, strongest first, for badges. */
    static List<String> roles(int privileges) {
        List<String> roles = new ArrayList<>();

        for (Privileges badge : BADGES) {
            if (Privileges.has(privileges, badge)) {
                roles.add(badge.name().toLowerCase(Locale.ROOT));
            }
        }

        return roles;
    }

    /**
     * The ids of everyone with a live bancho session, bots excluded.
     *
     * <p>Read once per request and passed down rather than asked per row: the list endpoint
     * renders up to a hundred cards, and walking every session a hundred times to answer the
     * same question is the kind of thing that only shows up once the server is busy.</p>
     */
    static Set<Integer> onlineIds() {
        Set<Integer> online = new HashSet<>();

        if (App.server == null || App.server.playerManager == null) {
            return online;
        }

        for (Player player : App.server.playerManager.getAllSessions()) {
            if (!player.isBot()) {
                online.add(player.getId());
            }
        }

        return online;
    }

    /** One player, as the moderation list and the player header both show them. */
    static Map<String, Object> player(UserEntity user, Set<Integer> online) {
        long now = System.currentTimeMillis() / 1000L;

        int privileges = user.getPrivileges() == null ? 0 : user.getPrivileges();
        long silenceEnd = user.getSilenceEnd() == null ? 0L : user.getSilenceEnd();
        long donorEnd = user.getDonorEnd() == null ? 0L : user.getDonorEnd();

        Map<String, Object> row = new LinkedHashMap<>();

        row.put("id", user.getId());
        row.put("name", user.getName());
        row.put("country", user.getCountry());
        row.put("priv", privileges);

        // A restriction is the absence of UNRESTRICTED, not a flag of its own.
        row.put("restricted", !Privileges.has(privileges, Privileges.UNRESTRICTED));
        row.put("silenced", silenceEnd > now);
        row.put("silence_end", silenceEnd);
        row.put("donor_end", donorEnd);
        row.put("supporter", donorEnd > now);
        row.put("online", online.contains(user.getId()));
        row.put("roles", roles(privileges));
        row.put("creation_time", user.getCreationTime() == null ? 0 : user.getCreationTime());
        row.put("latest_activity", user.getLatestActivity() == null ? 0 : user.getLatestActivity());

        return row;
    }

    /** One line of staff history, with both parties resolved to names. */
    static Map<String, Object> log(LogEntity entry, Map<Integer, String> names) {
        Map<String, Object> row = new LinkedHashMap<>();

        row.put("id", entry.getId());
        row.put("from_id", entry.getFromId());
        row.put("from_name", names.getOrDefault(entry.getFromId(), "Server"));
        row.put("to_id", entry.getToId());
        row.put("to_name", names.getOrDefault(entry.getToId(), "?"));
        row.put("action", entry.getAction());
        row.put("message", entry.getMessage());
        row.put("time", entry.getTime() == null ? null : entry.getTime().toString());

        return row;
    }

    /** Resolves every actor and target in a batch of history to a name, in one query. */
    static Map<Integer, String> namesFor(Collection<LogEntity> entries) {
        Set<Integer> ids = new HashSet<>();

        for (LogEntity entry : entries) {
            ids.add(entry.getFromId());
            ids.add(entry.getToId());
        }

        // Actor 0 means the server itself, which has no row to look up.
        ids.remove(0);

        return names(ids);
    }

    /** Resolves a set of user ids to names in one query. */
    static Map<Integer, String> names(Set<Integer> ids) {
        Map<Integer, String> names = new HashMap<>();

        if (ids.isEmpty()) {
            return names;
        }

        List<UserEntity> users = DB.find(UserEntity.class)
                .select("id, name")
                .where()
                .idIn(ids)
                .findList();

        for (UserEntity user : users) {
            names.put(user.getId(), user.getName());
        }

        return names;
    }

    /** Turns a batch of history into response rows, resolving names once. */
    static List<Map<String, Object>> logs(List<LogEntity> entries) {
        Map<Integer, String> names = namesFor(entries);
        List<Map<String, Object>> rows = new ArrayList<>();

        for (LogEntity entry : entries) {
            rows.add(log(entry, names));
        }

        return rows;
    }
}
