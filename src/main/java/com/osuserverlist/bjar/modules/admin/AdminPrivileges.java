package com.osuserverlist.bjar.modules.admin;

import java.util.HashMap;
import java.util.Map;

import com.osuserverlist.bjar.models.osu.Privileges;

/**
 * Translates the privilege names accepted by the admin API onto bancho.jar's
 * {@link Privileges} enum.
 *
 * <p>Both bancho.py style names ({@code normal}, {@code dangerous}, {@code bat}) and the
 * enum's own names are accepted, so an existing frontend does not have to be rewritten.</p>
 */
public final class AdminPrivileges {

    private static final Map<String, Privileges> ALIASES = new HashMap<>();

    static {
        ALIASES.put("normal", Privileges.UNRESTRICTED);
        ALIASES.put("unrestricted", Privileges.UNRESTRICTED);
        ALIASES.put("verified", Privileges.VERIFIED);
        ALIASES.put("whitelisted", Privileges.WHITELISTED);
        ALIASES.put("supporter", Privileges.SUPPORTER);
        ALIASES.put("donator", Privileges.SUPPORTER);
        ALIASES.put("premium", Privileges.PREMIUM);
        ALIASES.put("alumni", Privileges.ALUMNI);
        ALIASES.put("tournament_manager", Privileges.TOURNEY_MANAGER);
        ALIASES.put("tourney_manager", Privileges.TOURNEY_MANAGER);
        ALIASES.put("nominator", Privileges.NOMINATOR);
        ALIASES.put("bat", Privileges.NOMINATOR);
        ALIASES.put("mod", Privileges.MODERATOR);
        ALIASES.put("moderator", Privileges.MODERATOR);
        ALIASES.put("admin", Privileges.ADMINISTRATOR);
        ALIASES.put("administrator", Privileges.ADMINISTRATOR);
        ALIASES.put("dangerous", Privileges.DEVELOPER);
        ALIASES.put("developer", Privileges.DEVELOPER);
    }

    private static final int ELEVATED = Privileges.ADMINISTRATOR.getValue() | Privileges.DEVELOPER.getValue();

    /**
     * Only a developer may hand out or take away administrator / developer rights, and only a developer may
     * touch an account that already holds one of them. Otherwise any administrator could make themselves a developer.
     */
    public static boolean mayChange(int actorPrivileges, int targetPrivileges, java.util.List<Privileges> changes) {
        if ((actorPrivileges & Privileges.DEVELOPER.getValue()) != 0) {
            return true;
        }

        if ((targetPrivileges & ELEVATED) != 0) {
            return false;
        }

        for (Privileges change : changes) {
            if ((change.getValue() & ELEVATED) != 0) {
                return false;
            }
        }

        return true;
    }

    /**
     * Resolves a privilege name sent by a client.
     *
     * @return the matching privilege, or {@code null} when the name is unknown.
     */
    public static Privileges resolve(String name) {
        if (name == null) {
            return null;
        }

        String key = name.trim().toLowerCase();
        if (key.isEmpty()) {
            return null;
        }

        Privileges alias = ALIASES.get(key);
        if (alias != null) {
            return alias;
        }

        Privileges byName = Privileges.fromName(key);
        if (byName == Privileges.STAFF || byName == Privileges.NONE) {
            return null;
        }

        return byName;
    }
}
