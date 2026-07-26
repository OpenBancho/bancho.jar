package com.osuserverlist.bjar.models.osu;

import java.util.EnumSet;

public enum Privileges {
    UNRESTRICTED(1 << 0),
    VERIFIED(1 << 1),
    WHITELISTED(1 << 2),

    SUPPORTER(1 << 4),
    PREMIUM(1 << 5),

    ALUMNI(1 << 7),

    TOURNEY_MANAGER(1 << 10),
    NOMINATOR(1 << 11),

    MODERATOR(1 << 12),
    ADMINISTRATOR(1 << 13),
    DEVELOPER(1 << 14),

    NONE(0),

    STAFF(MODERATOR.value | ADMINISTRATOR.value | DEVELOPER.value);

    public final int value;

    Privileges(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static EnumSet<Privileges> fromInt(int privileges) {
        EnumSet<Privileges> result = EnumSet.noneOf(Privileges.class);

        for (Privileges priv : values()) {
            if (priv == STAFF) {
                continue;
            }

            if ((privileges & priv.value) == priv.value) {
                result.add(priv);
            }
        }

        if (hasAny(privileges, MODERATOR, ADMINISTRATOR, DEVELOPER, NOMINATOR)) {
            result.add(STAFF);
        }

        return result;
    }

    public static Privileges fromName(String name) {
        for (Privileges priv : values()) {
            if (priv.name().equalsIgnoreCase(name)) {
                return priv;
            }
        }

        return null;
    }

    public static int fromPrivs(Privileges... privs) {
        int result = 0;

        for (Privileges priv : privs) {
            result |= priv.value;
        }

        return result;
    }

    public static boolean has(int userPriv, Privileges priv) {
        return (userPriv & priv.value) == priv.value;
    }

    public static boolean hasAll(int userPriv, Privileges... privs) {
        for (Privileges priv : privs) {
            if (!has(userPriv, priv)) {
                return false;
            }
        }

        return true;
    }

    public static int allPrivsToInt() {
        int result = 0;

        for (Privileges priv : values()) {
            if (priv == STAFF) {
                continue;
            }

            result |= priv.value;
        }

        return result;
    }

    public static boolean hasAny(int userPriv, Privileges... privs) {
        for (Privileges priv : privs) {
            if (has(userPriv, priv)) {
                return true;
            }
        }

        return false;
    }

    public static int addPrivilege(int userPriv, Privileges priv) {
        return userPriv | priv.value;
    }

    public static int addPrivileges(int userPriv, Privileges... privs) {
        for (Privileges priv : privs) {
            userPriv |= priv.value;
        }

        return userPriv;
    }

    public static int removePrivilege(int userPriv, Privileges priv) {
        return userPriv & ~priv.value;
    }

    public static int removePrivileges(int userPriv, Privileges... privs) {
        for (Privileges priv : privs) {
            userPriv &= ~priv.value;
        }

        return userPriv;
    }

    public static int toClientPrivileges(int serverPrivs) {
        int clientPrivs = 0;

        clientPrivs |= 1; // PLAYER

        if (Privileges.has(serverPrivs, Privileges.SUPPORTER)
                || Privileges.has(serverPrivs, Privileges.PREMIUM)) {
            clientPrivs |= 1 << 2; // SUPPORTER
        }

        if (Privileges.has(serverPrivs, Privileges.MODERATOR)) {
            clientPrivs |= 1 << 1; // MODERATOR
        }

        if (Privileges.has(serverPrivs, Privileges.ADMINISTRATOR)) {
            clientPrivs |= 1 << 3; // OWNER
        }

        if (Privileges.has(serverPrivs, Privileges.DEVELOPER)) {
            clientPrivs |= 1 << 4; // DEVELOPER
        }

        return clientPrivs;
    }
}
