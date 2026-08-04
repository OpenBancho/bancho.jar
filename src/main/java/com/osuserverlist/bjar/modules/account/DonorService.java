package com.osuserverlist.bjar.modules.account;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.repos.UserRepository;

/**
 * Everything that decides whether an account currently counts as a supporter.
 *
 * <p>Two things can grant it: a {@code donor_end} timestamp in the future, which
 * is what the admin panel hands out, or the permanent SUPPORTER / PREMIUM
 * privilege bits. Both are treated the same everywhere in the server, so the
 * check lives here instead of being repeated at each call site.</p>
 */
public final class DonorService {

    private DonorService() {
    }

    public static int now() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    /** Whether the stored row is a supporter right now. */
    public static boolean isDonor(UserEntity user) {
        if (user == null) {
            return false;
        }

        Integer privileges = user.getPrivileges();

        if (privileges != null
                && Privileges.hasAny(privileges, Privileges.SUPPORTER, Privileges.PREMIUM)) {
            return true;
        }

        Integer donorEnd = user.getDonorEnd();

        return donorEnd != null && donorEnd > now();
    }

    /** Whether the live session is a supporter right now. */
    public static boolean isDonor(Player player) {
        if (player == null) {
            return false;
        }

        if (Privileges.hasAny(player.getServerPrivileges(), Privileges.SUPPORTER, Privileges.PREMIUM)) {
            return true;
        }

        if (player.getDonorEnd() > now()) {
            return true;
        }

        // A session created before the perk was granted still has the old
        // values, so fall back to the row.
        UserEntity entity = player.getEntity();

        if (entity == null) {
            entity = UserRepository.findById(player.getId());
        }

        return isDonor(entity);
    }

    /** Seconds of supporter left, or 0 when it is permanent or absent. */
    public static int remainingSeconds(Player player) {
        if (player == null) {
            return 0;
        }

        int donorEnd = player.getDonorEnd();

        if (donorEnd <= 0) {
            UserEntity entity = player.getEntity();

            if (entity == null) {
                entity = UserRepository.findById(player.getId());
            }

            if (entity != null && entity.getDonorEnd() != null) {
                donorEnd = entity.getDonorEnd();
            }
        }

        int remaining = donorEnd - now();

        return Math.max(remaining, 0);
    }

    /** Whether osu!direct is reserved for supporters on this server. */
    public static boolean directRequiresDonor() {
        return App.server.enviromentConfig.isDonorOnlyDirect();
    }

    /**
     * Gate for the osu!direct routes.
     *
     * @return {@code true} when the player may use osu!direct.
     */
    public static boolean canUseDirect(Player player) {
        if (!directRequiresDonor()) {
            return true;
        }

        if (player == null) {
            return false;
        }

        if (Privileges.hasAny(player.getServerPrivileges(),
                Privileges.MODERATOR, Privileges.ADMINISTRATOR, Privileges.DEVELOPER)) {
            return true;
        }

        return isDonor(player);
    }
}
