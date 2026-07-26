package com.osuserverlist.bjar.modules.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.datastore.Redis;
import com.osuserverlist.bjar.modules.main.GeoLocation;
import com.osuserverlist.bjar.packets.server.UtilServerPackets.NotificationPacket;
import com.osuserverlist.bjar.repos.BeatmapRepository;
import com.osuserverlist.bjar.repos.StatsRepository;
import com.osuserverlist.bjar.repos.UserRepository;

import io.ebean.DB;

/**
 * Moderation and profile actions, applied to both the database and every live session of
 * the affected player.
 *
 * <p>This layer is transport agnostic: it is called by the authenticated admin API and takes
 * the acting administrator's id purely so every action leaves an attributable log line.
 * Authentication and privilege checks belong to the caller.</p>
 */
public final class AdminActions {

    private static final Logger logger = LoggerFactory.getLogger("AdminActions");

    /** Leaderboard keys are written per game mode; bancho.jar tracks 9 of them. */
    public static final int MODE_COUNT = 9;

    private static final String LEADERBOARD_KEY = "bjar:leaderboard:";

    private AdminActions() {
    }

    // ------------------------------------------------------------------
    // restrict / unrestrict
    // ------------------------------------------------------------------

    /** @return {@code false} when the target user does not exist. */
    public static boolean restrict(int actorId, int userId, String reason) {
        UserEntity user = UserRepository.findById(userId);
        if (user == null) {
            return false;
        }

        user.setPrivileges(Privileges.removePrivilege(user.getPrivileges(), Privileges.UNRESTRICTED));
        UserRepository.save(user);

        for (int mode = 0; mode < MODE_COUNT; mode++) {
            Redis.getClient().zrem(LEADERBOARD_KEY + mode, String.valueOf(userId));
        }

        String message = reason == null || reason.isBlank()
                ? "Your account has been restricted."
                : "Your account has been restricted: " + reason;

        for (Player player : sessionsOf(userId)) {
            player.sendPacket(new NotificationPacket(message));
            App.server.playerManager.restrict(player);
        }

        logger.info("Admin <{}> restricted user <{}> (reason: {})", actorId, userId, reason);

        return true;
    }

    /** @return {@code false} when the target user does not exist. */
    public static boolean unrestrict(int actorId, int userId, String reason) {
        UserEntity user = UserRepository.findById(userId);
        if (user == null) {
            return false;
        }

        user.setPrivileges(Privileges.addPrivilege(user.getPrivileges(), Privileges.UNRESTRICTED));
        UserRepository.save(user);

        for (StatsEntity stats : StatsRepository.findAllByUser(userId)) {
            if (stats.getId() == null || stats.getPp() == null || stats.getPp() <= 0) {
                continue;
            }

            Redis.getClient().zadd(LEADERBOARD_KEY + stats.getId().getMode(),
                    stats.getPp(), String.valueOf(userId));
        }

        String message = reason == null || reason.isBlank()
                ? "Your account has been unrestricted."
                : "Your account has been unrestricted: " + reason;

        for (Player player : sessionsOf(userId)) {
            player.sendPacket(new NotificationPacket(message));
            App.server.playerManager.unrestrict(player);
        }

        logger.info("Admin <{}> unrestricted user <{}> (reason: {})", actorId, userId, reason);

        return true;
    }

    // ------------------------------------------------------------------
    // wipe
    // ------------------------------------------------------------------

    /** @return {@code false} when the target user does not exist. */
    public static boolean wipe(int actorId, int userId, int mode) {
        if (!UserRepository.exists(userId)) {
            return false;
        }

        List<ScoreEntity> scores = DB.find(ScoreEntity.class)
                .where()
                .eq("user.id", userId)
                .eq("mode", mode)
                .findList();

        if (!scores.isEmpty()) {
            DB.deleteAll(scores);
        }

        StatsEntity stats = StatsRepository.find(userId, mode);
        if (stats != null) {
            stats.setTotalScore(0L);
            stats.setRankedScore(0L);
            stats.setPp(0);
            stats.setPlays(0);
            stats.setPlaytime(0);
            stats.setAccuracy(0f);
            stats.setMaxCombo(0);
            stats.setTotalHits(0);
            stats.setReplayViews(0);
            stats.setXhCount(0);
            stats.setXCount(0);
            stats.setShCount(0);
            stats.setSCount(0);
            stats.setACount(0);

            StatsRepository.update(stats);
        }

        Redis.getClient().zrem(LEADERBOARD_KEY + mode, String.valueOf(userId));

        for (Player player : sessionsOf(userId)) {
            player.sendPacket(new NotificationPacket("Your statistics have been wiped."));
            App.server.playerManager.disconnect(player);
        }

        logger.info("Admin <{}> wiped {} score(s) of user <{}> in mode <{}>",
                actorId, scores.size(), userId, mode);

        return true;
    }

    // ------------------------------------------------------------------
    // broadcast
    // ------------------------------------------------------------------

    /** @return the number of sessions the notification was delivered to. */
    public static int alertAll(int actorId, String message) {
        int delivered = 0;

        for (Player player : App.server.playerManager.getAllSessions()) {
            if (player.isBot()) {
                continue;
            }

            player.sendPacket(new NotificationPacket(message));
            delivered++;
        }

        logger.info("Admin <{}> broadcasted an alert to <{}> session(s)", actorId, delivered);

        return delivered;
    }

    // ------------------------------------------------------------------
    // donator
    // ------------------------------------------------------------------

    /** @return the new donor expiry as unix seconds, or {@code -1} on an unknown user. */
    public static long giveDonator(int actorId, int userId, long seconds) {
        UserEntity user = UserRepository.findById(userId);
        if (user == null) {
            return -1;
        }

        long now = System.currentTimeMillis() / 1000L;
        long current = user.getDonorEnd() == null ? 0L : user.getDonorEnd();
        long newEnd = Math.max(current, now) + seconds;

        user.setDonorEnd((int) newEnd);
        user.setPrivileges(Privileges.addPrivilege(user.getPrivileges(), Privileges.SUPPORTER));
        UserRepository.save(user);

        for (Player player : sessionsOf(userId)) {
            player.setDonorEnd((int) newEnd);
            applyPrivileges(player, user.getPrivileges());
            player.sendPacket(new NotificationPacket("You have been given supporter status. Thank you!"));
        }

        logger.info("Admin <{}> granted donator to user <{}> until <{}>", actorId, userId, newEnd);

        return newEnd;
    }

    // ------------------------------------------------------------------
    // privileges
    // ------------------------------------------------------------------

    /** @return the resulting privilege bitfield, or {@code -1} on an unknown user. */
    public static int changePrivileges(int actorId, int userId, List<Privileges> privs, boolean add) {
        UserEntity user = UserRepository.findById(userId);
        if (user == null) {
            return -1;
        }

        int privileges = user.getPrivileges();

        for (Privileges priv : privs) {
            privileges = add
                    ? Privileges.addPrivilege(privileges, priv)
                    : Privileges.removePrivilege(privileges, priv);
        }

        if (privileges == user.getPrivileges()) {
            return privileges;
        }

        user.setPrivileges(privileges);
        UserRepository.save(user);

        boolean nowRestricted = !Privileges.has(privileges, Privileges.UNRESTRICTED);

        for (Player player : sessionsOf(userId)) {
            applyPrivileges(player, privileges);

            if (nowRestricted) {
                App.server.playerManager.disconnect(player);
            }
        }

        logger.info("Admin <{}> set privileges of user <{}> to <{}>", actorId, userId, privileges);

        return privileges;
    }

    // ------------------------------------------------------------------
    // beatmap status
    // ------------------------------------------------------------------

    /** @return {@code false} when no beatmap matched. */
    public static boolean rankBeatmap(int actorId, long beatmapId, int status, boolean frozen) {
        int affected = BeatmapRepository.updateStatusById(beatmapId, status, frozen);

        if (affected == 0) {
            return false;
        }

        logger.info("Admin <{}> set beatmap <{}> to status <{}> (frozen: {})",
                actorId, beatmapId, status, frozen);

        return true;
    }

    // ------------------------------------------------------------------
    // profile
    // ------------------------------------------------------------------

    /** @return {@code false} when the target user does not exist. */
    public static boolean changeCountry(int actorId, int userId, String country) {
        UserEntity user = UserRepository.findById(userId);
        if (user == null) {
            return false;
        }

        String code = country.trim().toLowerCase(Locale.ROOT);

        user.setCountry(code);
        UserRepository.save(user);

        int countryIndex = GeoLocation.Country.getIndexByCode(code);
        if (countryIndex >= 0) {
            for (Player player : sessionsOf(userId)) {
                player.setCountry((short) countryIndex);
            }
        }

        logger.info("Admin <{}> changed country of user <{}> to <{}>", actorId, userId, code);

        return true;
    }

    /** @return {@code false} when the target user does not exist. */
    public static boolean changeName(int actorId, int userId, String name) {
        UserEntity user = UserRepository.findById(userId);
        if (user == null) {
            return false;
        }

        String newName = name.trim();

        user.setName(newName);
        user.setSafeName(newName.toLowerCase(Locale.ROOT).replace(' ', '_'));
        UserRepository.save(user);

        for (Player player : sessionsOf(userId)) {
            player.setUsername(newName);
            player.sendPacket(new NotificationPacket("Your username has been changed to " + newName + "."));
            App.server.playerManager.disconnect(player);
        }

        logger.info("Admin <{}> changed name of user <{}> to <{}>", actorId, userId, newName);

        return true;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Returns a snapshot of every live session belonging to a user. A snapshot is required
     * because callers disconnect players while iterating.
     */
    private static List<Player> sessionsOf(int userId) {
        List<Player> sessions = new ArrayList<>();

        for (Player player : App.server.playerManager.getAllSessions()) {
            if (player.getId() == userId && !player.isBot()) {
                sessions.add(player);
            }
        }

        return sessions;
    }

    private static void applyPrivileges(Player player, int privileges) {
        player.setServerPrivileges(privileges);
        player.setClientPrivileges(Privileges.toClientPrivileges(privileges));
    }

    /**
     * Parses durations such as {@code 30d}, {@code 2w} or {@code 12h}. A bare number is
     * treated as seconds.
     *
     * @return the duration in seconds, or {@code -1} when it cannot be parsed.
     */
    public static long parseDuration(String duration) {
        if (duration == null) {
            return -1;
        }

        String value = duration.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return -1;
        }

        char unit = value.charAt(value.length() - 1);
        long multiplier;

        if (Character.isDigit(unit)) {
            multiplier = 1L;
        } else {
            switch (unit) {
                case 's' -> multiplier = 1L;
                case 'm' -> multiplier = 60L;
                case 'h' -> multiplier = 3600L;
                case 'd' -> multiplier = 86400L;
                case 'w' -> multiplier = 604800L;
                default -> {
                    return -1;
                }
            }

            value = value.substring(0, value.length() - 1).trim();
        }

        try {
            long amount = Long.parseLong(value);
            return amount <= 0 ? -1 : amount * multiplier;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
