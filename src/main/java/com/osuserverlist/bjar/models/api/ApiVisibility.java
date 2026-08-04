package com.osuserverlist.bjar.models.api;

import java.util.Objects;

import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.api.OAuthToken;

import io.javalin.http.Context;

/**
 * Who the public API is allowed to admit exists.
 *
 * <p>A restriction is stored as the absence of the {@code UNRESTRICTED} bit, and it is meant to
 * be total: the account is off every ranking, out of every list and count, and its profile
 * answers {@code 404} exactly like a name nobody ever registered. Staff are the exception,
 * because a moderator who cannot open the profile of the account they just restricted cannot
 * check their own work; so is the owner, who is shown their own page with the restriction
 * notice on it rather than being told they no longer exist.
 *
 * <p>The decision lives here, in one place, rather than as a privilege check copied into every
 * handler: the copy that gets forgotten is the one that leaks the account.
 */
public final class ApiVisibility {

    private ApiVisibility() {
    }

    /** Whether an account may be shown to anybody at all: it is not restricted. */
    public static boolean isPublic(UserEntity user) {
        return user != null && Privileges.has(user.getPrivileges(), Privileges.UNRESTRICTED);
    }

    /**
     * Whether a connected player belongs in public lists and counts.
     *
     * <p>The same three exclusions the online list already made — bots and the extra sessions a
     * tournament client opens — plus the restricted accounts, which are online but invisible.
     */
    public static boolean isPublic(Player player) {
        return player != null
                && !player.isBot()
                && !player.isTourneyClient()
                && Privileges.has(player.getServerPrivileges(), Privileges.UNRESTRICTED);
    }

    /** Whether the request comes from a staff account. */
    public static boolean isStaffRequest(Context ctx) {
        OAuthToken token = ApiAuth.optional(ctx);

        return token != null && ApiAuth.isStaff(token.getPrivileges());
    }

    /**
     * Whether this caller may be shown this account.
     *
     * <p>Public accounts are shown to everybody without a token being looked at. A restricted
     * one is shown only to staff and to itself.
     */
    public static boolean canView(Context ctx, UserEntity user) {
        if (user == null) {
            return false;
        }

        if (isPublic(user)) {
            return true;
        }

        OAuthToken token = ApiAuth.optional(ctx);

        if (token == null) {
            return false;
        }

        return ApiAuth.isStaff(token.getPrivileges())
                || Objects.equals(user.getId(), token.getUserId());
    }

    /**
     * The player asked for by {@code id} or {@code name}, or {@code null} when this caller is
     * not allowed to know they exist.
     *
     * <p>A drop-in replacement for {@link ApiMappers#resolveUser(Context)}: handlers already
     * answer {@code 404} on a null result, which is exactly the answer a hidden account should
     * produce. Nothing distinguishes "restricted" from "never existed" from the outside.
     */
    public static UserEntity resolveVisibleUser(Context ctx) {
        UserEntity user = ApiMappers.resolveUser(ctx);

        return canView(ctx, user) ? user : null;
    }
}
