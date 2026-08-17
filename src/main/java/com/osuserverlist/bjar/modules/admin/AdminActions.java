package com.osuserverlist.bjar.modules.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.GroupEntity;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.account.PasswordResetService;
import com.osuserverlist.bjar.modules.datastore.Redis;
import com.osuserverlist.bjar.modules.main.GeoLocation;
import com.osuserverlist.bjar.modules.recalc.BeatmapRecalculator;
import com.osuserverlist.bjar.packets.server.UtilServerPackets.NotificationPacket;
import com.osuserverlist.bjar.repos.BeatmapRepository;
import com.osuserverlist.bjar.repos.GroupRepository;
import com.osuserverlist.bjar.repos.LogRepository;
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
        record(actorId, userId, "restrict", reason);

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
        record(actorId, userId, "unrestrict", reason);

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
        record(actorId, userId, "wipe", "Wiped " + scores.size() + " score(s) in mode " + mode + ".");

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
        record(actorId, userId, "supporter", "Supporter until " + newEnd + " (added " + seconds + "s).");

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
        record(actorId, userId, "privileges",
                (add ? "Granted " : "Removed ") + privs + " (bitmask is now " + privileges + ").");

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

        // Scores set while the map was ranked keep their PP in stats and in the leaderboard
        // sorted sets until someone recomputes them, so unranking has to do it here.
        BeatmapRecalculator.recalcForMap(beatmapId);

        return true;
    }

    /**
     * Same as {@link #rankBeatmap(int, long, int, boolean)} for every difficulty of a set.
     *
     * @return {@code false} when no beatmap matched.
     */
    public static boolean rankBeatmapSet(int actorId, long setId, int status, boolean frozen) {
        int affected = BeatmapRepository.updateStatusBySetId(setId, status, frozen);

        if (affected == 0) {
            return false;
        }

        logger.info("Admin <{}> set beatmap set <{}> to status <{}> on {} difficulties (frozen: {})",
                actorId, setId, status, affected, frozen);

        BeatmapRecalculator.recalcForSet(setId);

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
        record(actorId, userId, "country", "Country set to " + code + ".");

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
        record(actorId, userId, "name", "Renamed to " + newName + ".");

        return true;
    }

    // ------------------------------------------------------------------
    // silence / unsilence
    // ------------------------------------------------------------------

    /**
     * Silences an account for a number of seconds, stopping it from posting in chat while
     * leaving it able to play.
     *
     * <p>A silence is not a restriction, which is why it lives next to one rather than inside
     * it: the punishment ladder in practice starts here, and a moderator reaching for it
     * should not have to take someone's scores away to use it.</p>
     *
     * @return the unix second the silence expires at, or {@code -1} on an unknown user.
     */
    public static long silence(int actorId, int userId, long seconds, String reason) {
        UserEntity user = UserRepository.findById(userId);
        if (user == null) {
            return -1;
        }

        long now = System.currentTimeMillis() / 1000L;
        int silenceEnd = (int) (now + seconds);

        user.setSilenceEnd(silenceEnd);
        UserRepository.save(user);

        String message = reason == null || reason.isBlank()
                ? "You have been silenced."
                : "You have been silenced: " + reason;

        for (Player player : sessionsOf(userId)) {
            player.setSilenceEnd(silenceEnd);
            player.sendPacket(new NotificationPacket(message));
            App.server.playerManager.silence(player, silenceEnd);
        }

        logger.info("Admin <{}> silenced user <{}> for <{}s> (reason: {})",
                actorId, userId, seconds, reason);
        record(actorId, userId, "silence",
                "Silenced for " + seconds + "s. " + (reason == null ? "" : reason));

        return silenceEnd;
    }

    /** Lifts a silence early. @return {@code false} when the target user does not exist. */
    public static boolean unsilence(int actorId, int userId, String reason) {
        UserEntity user = UserRepository.findById(userId);
        if (user == null) {
            return false;
        }

        user.setSilenceEnd(0);
        UserRepository.save(user);

        String message = reason == null || reason.isBlank()
                ? "You are no longer silenced."
                : "You are no longer silenced: " + reason;

        for (Player player : sessionsOf(userId)) {
            player.setSilenceEnd(0);
            player.sendPacket(new NotificationPacket(message));
            App.server.playerManager.unsilence(player);
        }

        logger.info("Admin <{}> unsilenced user <{}> (reason: {})", actorId, userId, reason);
        record(actorId, userId, "unsilence", reason);

        return true;
    }

    // ------------------------------------------------------------------
    // notes
    // ------------------------------------------------------------------

    /**
     * Attaches a free text note to an account without doing anything to it.
     *
     * <p>Most of what moderation actually consists of is not a punishment: it is the sentence
     * explaining why the next person should, or should not, hand one out. Without somewhere to
     * put that, it ends up in a private Discord channel and is lost.</p>
     *
     * @return {@code false} when the target user does not exist.
     */
    public static boolean note(int actorId, int userId, String message) {
        if (!UserRepository.exists(userId)) {
            return false;
        }

        record(actorId, userId, "note", message);

        return true;
    }

    // ------------------------------------------------------------------
    // password resets
    // ------------------------------------------------------------------

    /**
     * Hands out a one-shot password reset link for an account.
     *
     * <p>Nothing about the account changes here. The password is still chosen by whoever
     * redeems the ticket, which is the point: staff help a player back in without ever holding
     * their password, and the account itself keeps working until the reset is actually done.
     *
     * <p>The issue is logged against the account rather than only into the application log,
     * because a link that opens somebody's account is exactly the kind of favour that has to be
     * attributable afterwards.
     *
     * @return the issued ticket, or {@code null} when the user does not exist or Redis is down.
     */
    public static PasswordResetService.Issued issuePasswordReset(int actorId, int userId,
            long ttlSeconds) {

        if (!UserRepository.exists(userId)) {
            return null;
        }

        PasswordResetService.Issued issued = PasswordResetService.issue(userId, actorId, ttlSeconds);

        if (issued == null) {
            return null;
        }

        logger.info("Admin <{}> issued a password reset link for user <{}>, valid until <{}>",
                actorId, userId, issued.getExpiresAt());
        record(actorId, userId, "password-reset",
                "Issued a password reset link, valid for " + (issued.getTtlSeconds() / 3600)
                        + " hour(s).");

        return issued;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Appends one line to the staff history.
     *
     * <p>Failures are swallowed on purpose: the action itself has already happened by the time
     * this runs, and a database hiccup here must not turn a completed restriction into a 500
     * that invites the moderator to do it a second time.</p>
     */
    // ------------------------------------------------------------------
    // groups
    // ------------------------------------------------------------------

    /** Creates a group. Returns {@code null} when the name is already taken. */
    public static GroupEntity createGroup(int actorId, String name, String icon, String colour,
            String description) {
        if (GroupRepository.nameTaken(name, 0)) {
            return null;
        }

        GroupEntity group = new GroupEntity();
        group.setName(name);
        group.setIcon(icon);
        group.setColour(colour);
        group.setDescription(description);
        GroupRepository.save(group);

        logger.info("Admin <{}> created group <{}> (<{}>)", actorId, group.getId(), name);

        return group;
    }

    /** Edits a group. {@code false} when it does not exist or the new name is taken. */
    public static boolean updateGroup(int actorId, int groupId, String name, String icon,
            String colour, String description) {
        GroupEntity group = GroupRepository.findById(groupId);

        if (group == null || GroupRepository.nameTaken(name, groupId)) {
            return false;
        }

        group.setName(name);
        group.setIcon(icon);
        group.setColour(colour);
        group.setDescription(description);
        GroupRepository.save(group);

        logger.info("Admin <{}> edited group <{}> (<{}>)", actorId, groupId, name);

        return true;
    }

    /** Deletes a group and every membership in it. */
    public static boolean deleteGroup(int actorId, int groupId) {
        GroupEntity group = GroupRepository.findById(groupId);

        if (group == null) {
            return false;
        }

        GroupRepository.delete(group);
        logger.info("Admin <{}> deleted group <{}> (<{}>)", actorId, groupId, group.getName());

        return true;
    }

    /** Adds an account to a group. Already being a member is not an error. */
    public static void addToGroup(int actorId, int userId, GroupEntity group) {
        if (GroupRepository.isMember(userId, group.getId())) {
            return;
        }

        GroupRepository.addMember(userId, group.getId());

        logger.info("Admin <{}> added user <{}> to group <{}>", actorId, userId, group.getId());
        record(actorId, userId, "group", "Added to group \"" + group.getName() + "\".");
    }

    /** Removes an account from a group. Not being a member is not an error. */
    public static void removeFromGroup(int actorId, int userId, GroupEntity group) {
        if (!GroupRepository.isMember(userId, group.getId())) {
            return;
        }

        GroupRepository.removeMember(userId, group.getId());

        logger.info("Admin <{}> removed user <{}> from group <{}>", actorId, userId, group.getId());
        record(actorId, userId, "group", "Removed from group \"" + group.getName() + "\".");
    }

    private static void record(int actorId, int userId, String action, String message) {
        try {
            LogRepository.write(actorId, userId, action, message);
        } catch (Exception e) {
            logger.warn("Could not record the <{}> of user <{}> in the staff log", action, userId, e);
        }
    }

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
