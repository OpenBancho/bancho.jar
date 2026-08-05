package com.osuserverlist.bjar.handlers.cho;

import java.util.ArrayList;
import java.util.List;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.ConfigModels.ServerConfiguration.WelcomeMessage;
import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.ModeStats;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.LoginResponse;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.account.TotpService;
import com.osuserverlist.bjar.modules.account.TwoFactorService;
import com.osuserverlist.bjar.modules.main.Application.BuildInfo;
import com.osuserverlist.bjar.modules.main.GeoLocation;
import com.osuserverlist.bjar.modules.main.GeoLocation.Country;
import com.osuserverlist.bjar.modules.main.GeoLocation.GeoResponse;
import com.osuserverlist.bjar.modules.osu.OsuVersionParser;
import com.osuserverlist.bjar.modules.packets.BanchoPacketWriter;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.ChannelAutojoinPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.ChannelInfoEndPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.ChannelInfoPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.ChannelJoinSuccessPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.SendMessagePacket;
import com.osuserverlist.bjar.packets.server.LoginServerPackets.LoginReplyPacket;
import com.osuserverlist.bjar.packets.server.LoginServerPackets.MenuIconPacket;
import com.osuserverlist.bjar.packets.server.LoginServerPackets.PrivilegesPacket;
import com.osuserverlist.bjar.packets.server.LoginServerPackets.ProtocolVersionPacket;
import com.osuserverlist.bjar.packets.server.LoginServerPackets.SilenceInfoPacket;
import com.osuserverlist.bjar.packets.server.UserServerPackets.AccountRestrictedPacket;
import com.osuserverlist.bjar.packets.server.UserServerPackets.FriendsListPacket;
import com.osuserverlist.bjar.packets.server.UserServerPackets.UserPresenceBundlePacket;
import com.osuserverlist.bjar.packets.server.UserServerPackets.UserPresencePacket;
import com.osuserverlist.bjar.packets.server.UserServerPackets.UserStatsPacket;
import com.osuserverlist.bjar.packets.server.UtilServerPackets.NotificationPacket;
import com.osuserverlist.bjar.repos.IngameLoginRepository;
import com.osuserverlist.bjar.repos.RelationshipRepository;
import com.osuserverlist.bjar.repos.StatsRepository;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * Handles the initial Bancho login request (no {@code osu-token} header):
 * authentication, player construction, stat/channel/friend bootstrapping,
 * and all the one-time packets sent on connect.
 */
public class LoginHandler {

    private static final Logger logger = LoggerFactory.getLogger(LoginHandler.class);

    private static final String RESTRICTED_MSG = """
            Your account is currently in restricted mode.
            If you believe this is a mistake, or have waited a period
            greater than 3 months, you may appeal via discord.""";

    private static final int[] STATS_MODES = { 0, 1, 2, 3, 4, 5, 6, 8 };

    private static final int LOGIN_FAILED = -1;

    /**
     * Sends the client into its own account verification flow: it opens
     * {@code osu.<domain>/client-verifications/create?ch=<client hash>} in the player's browser
     * and returns to the login screen. That address is built into the client, so this is how a
     * player with two factor authentication is asked for a code without the client having to
     * know anything about us.
     */
    private static final int VERIFICATION_REQUIRED = -8;

    public void handle(Context ctx) {
        LoginResponse loginResponse = LoginResponse.parse(ctx);

        if (!loginResponse.isSuccess()) {
            sendLoginFailure(ctx, LOGIN_FAILED);
            return;
        }

        // TODO: fix issue where client is sending login request twice even after login
        // to c4 or any other subdom

        Server server = App.server;

        UserEntity userEntity = authenticate(loginResponse);
        if (userEntity == null) {
            sendLoginFailure(ctx, LOGIN_FAILED);
            return;
        }

        // The password was right, which is the first factor. An account with an authenticator
        // still has to answer a code from an unfamiliar machine, and the only way to ask for one
        // is to send the client to the website - the login is refused for now and the player
        // comes back to it after verifying.
        if (requiresTwoFactor(ctx, userEntity, loginResponse)) {
            return;
        }

        GeoResponse geoLocResponse = resolveGeoLocation(userEntity, loginResponse);

        var incomingVersion = OsuVersionParser.parse(loginResponse.getBuildName());
        boolean incomingIsTourney = incomingVersion != null
                && incomingVersion.getStream() == OsuVersionParser.OsuStream.TOURNAMENT;

        disconnectExistingSession(server, userEntity, incomingIsTourney);

        Player player = buildPlayer(userEntity, loginResponse, geoLocResponse);

        // Entity is used to update the database with the player's information later, so
        // we need to set it here.
        player.setEntity(userEntity);

        player.sendPacket(new ProtocolVersionPacket());
        player.sendPacket(new LoginReplyPacket(player.getId()));
        player.sendPacket(new PrivilegesPacket(player.getClientPrivileges()));

        loadPlayerStats(player);

        player.sendPacket(new UserPresencePacket(player));
        player.sendPacket(new UserStatsPacket(player));

        joinAvailableChannels(server, player);

        player.sendPacket(new ChannelInfoEndPacket());
        player.sendPacket(new UserPresenceBundlePacket());

        server.playerManager.add(player);

        notifyOtherPlayers(server, player);

        sendWelcomeMessages(server, player);
        sendRestrictionNoticeIfNeeded(server, player);
        player.sendPacket(new MenuIconPacket());

        sendSilenceInfoIfNeeded(server, player);

        logger.info("User {} logged in successfully from IP: {} Channel: {}", player, loginResponse.getIp(), player.getOsuVersion().getStream().name());

        scheduleBackgroundLoginTasks(server, player, userEntity, loginResponse);

        BanchoPacketWriter writer = new BanchoPacketWriter();
        ChoHandler.handlePendingPackets(writer, player);

        ctx.header("cho-token", loginResponse.getUuid())
                .status(HttpStatus.OK)
                .contentType("application/octet-stream")
                .result(writer.getPackets());

    }

    /**
     * Looks up the user and verifies their password. Returns {@code null}
     * (and logs a warning) if either step fails.
     */
    private UserEntity authenticate(LoginResponse loginResponse) {
        UserEntity userEntity = UserRepository.findByName(loginResponse.getUsername());
        if (userEntity == null) {
            return null;
        }

        boolean passwordMatches = OpenBSDBCrypt.checkPassword(
                userEntity.getPasswordHash(),
                loginResponse.getPasswordMd5().toCharArray());

        if (!passwordMatches) {
            logger.warn("Failed login attempt for user: {} from IP: {}",
                    loginResponse.getUsername(), loginResponse.getIp());
            return null;
        }

        return userEntity;
    }

    /**
     * Resolves the player's geo-location and backfills the user's stored
     * country if it hasn't been set yet (i.e. is still {@code XX}).
     */
    private GeoResponse resolveGeoLocation(UserEntity userEntity, LoginResponse loginResponse) {
        GeoResponse geoLocResponse = GeoLocation.getProvider().getCountryCode(loginResponse.getIp());

        if (userEntity.getCountry().equalsIgnoreCase("XX")) {
            Country country = Country.getById(geoLocResponse.getCountryId());
            userEntity.setCountry(country.name().toLowerCase());
            UserRepository.save(userEntity);
        }

        return geoLocResponse;
    }

    private void disconnectExistingSession(Server server, UserEntity userEntity, boolean incomingIsTourney) {
        // Tournament clients open several concurrent sessions under a single
        // account, so a tourney login must never kick an existing session, and
        // a normal login must not kick existing tournament sessions.
        if (incomingIsTourney) {
            return;
        }

        Player existingPlayer = server.playerManager.getByFilter(
                p -> p.getId() == userEntity.getId() && !p.isTourneyClient() && !p.isBot());

        if (existingPlayer != null) {
            server.playerManager.disconnect(existingPlayer);
        }
    }

    private Player buildPlayer(UserEntity userEntity, LoginResponse loginResponse, GeoResponse geoLocResponse) {
        Player player = new Player(userEntity.getId(), false, loginResponse.getUuid());

        player.setTimezone(Integer.parseInt(loginResponse.getUtcOffset()));
        player.setCountry((short) geoLocResponse.getCountryId());
        player.setLongitude(geoLocResponse.getLongitude());
        player.setLatitude(geoLocResponse.getLatitude());
        player.setDisplayCityLocation(loginResponse.isDisplayCityLocation());
        player.setFriendOnlyDms(loginResponse.isFriendOnlyDms());
        player.setUsername(userEntity.getName());
        player.setServerPrivileges(userEntity.getPrivileges());
        var osuVersion = OsuVersionParser.parse(loginResponse.getBuildName());
        player.setOsuVersion(osuVersion);
        player.setTourneyClient(osuVersion != null
                && osuVersion.getStream() == OsuVersionParser.OsuStream.TOURNAMENT);

        if (!Privileges.hasAny(userEntity.getPrivileges(), Privileges.VERIFIED)) {
            player.setServerPrivileges(userEntity.getPrivileges() | Privileges.VERIFIED.getValue());
            updatePrivsSoon(player);
        }

        player.setSilenceEnd(userEntity.getSilenceEnd());
        player.setDonorEnd(userEntity.getDonorEnd());

        int clientPrivs = Privileges.addPrivilege(userEntity.getPrivileges(), Privileges.SUPPORTER);
        player.setClientPrivileges(Privileges.toClientPrivileges(clientPrivs));

        player.setApiIdent(String.format("%s|%s", player.getUsername(), loginResponse.getPasswordMd5()));

        return player;
    }

    private void updatePrivsSoon(Player player) {
        App.server.executor.submit(() -> {
            UserEntity userEntity = player.getEntity();
            userEntity.setPrivileges(player.getServerPrivileges());
            UserRepository.save(userEntity);
        });
    }

    private void sendSilenceInfoIfNeeded(Server server, Player player) {
        if (player.getSilenceEnd() > 0) {
            if (player.getSilenceEnd() > (System.currentTimeMillis() / 1000L)) {
                int silenceSecondsRemaining = (int) (player.getSilenceEnd() - (System.currentTimeMillis() / 1000L));
                player.sendPacket(new SilenceInfoPacket(silenceSecondsRemaining));
                player.sendPacket(new SendMessagePacket(server.botPlayer.getUsername(), "You are currently silenced.",
                        player.getUsername(), server.botPlayer.getId()));
            } else {
                player.sendPacket(new SilenceInfoPacket(0));
            }
        }
    }

    private void sendRestrictionNoticeIfNeeded(Server server, Player player) {
        if (Privileges.hasAny(player.getServerPrivileges(), Privileges.UNRESTRICTED)) {
            return;
        }
        player.setRestricted(true);

        player.sendPacket(new AccountRestrictedPacket());
        player.sendPacket(new SendMessagePacket(server.botPlayer.getUsername(), RESTRICTED_MSG,
                player.getUsername(), server.botPlayer.getId()));
    }

    /**
     * Queues non-critical, response-blocking work (login logging, achievement
     * loading) to run after the login response has already been sent to the
     * client, since neither is needed before the client can start playing.
     */
    private void scheduleBackgroundLoginTasks(Server server, Player player, UserEntity userEntity, LoginResponse loginResponse) {
        server.executor.submit(() -> {
            IngameLoginRepository.log(userEntity, loginResponse.getIp(), player.getOsuVersion().getDate().toString(), player.getOsuVersion().getStream().name().toLowerCase());

            player.getFriends().addAll(RelationshipRepository.getFriendUsers(player.getEntity()).stream().map(UserEntity::getId).toList());
            player.getBlocks().addAll(RelationshipRepository.getBlockedUsers(player.getEntity()).stream().map(UserEntity::getId).toList());
            player.sendPacket(new FriendsListPacket(player.getFriends().stream().map(Integer::intValue).toList()));

            server.achievementManager.loadForPlayer(player);
        });
    }

    private void loadPlayerStats(Player player) {
        List<StatsEntity> statsList = StatsRepository.findAllByUser(player.getId());

        boolean[] statsFound = new boolean[9];

        for (StatsEntity stats : statsList) {
            int mode = stats.getId().getMode();
            ModeStats modeStats = ModeStats.fromEntity(stats, mode, player);
            player.getModeStats()[mode] = modeStats;
            statsFound[mode] = true;
        }

        for (int mode : STATS_MODES) {
            if (!statsFound[mode]) {
                logger.warn("Stats not found for player ID={} mode={}", player.getId(), mode);
            }
        }
    }

    private void joinAvailableChannels(Server server, Player player) {
        server.channelManager.getAll().forEach(channel -> {
            if (channel.getReadPriv() > player.getServerPrivileges() || !channel.isVisible()) {
                return;
            }

            if (channel.isAutoJoin()) {
                player.sendPacket(new ChannelAutojoinPacket(channel.getName()));
                player.sendPacket(new ChannelInfoPacket(channel.getName(), channel.getDescription(),
                        channel.getPlayerCount() + 1));
                player.sendPacket(new ChannelJoinSuccessPacket(channel.getName()));
                server.channelManager.joinChannel(channel.getName(), player);
            } else {
                player.sendPacket(new ChannelInfoPacket(channel.getName(), channel.getDescription(),
                        channel.getPlayerCount()));
            }
        });
    }

    /**
     * Sends the newly-connected player's presence to every other online,
     * non-bot player, off the request thread.
     */
    private void notifyOtherPlayers(Server server, Player player) {
        List<Player> toNotify = new ArrayList<>();

        server.playerManager.getAllSessions().forEach(p -> {
            if (p.getId() == player.getId()) {
                return;
            }
            if (p.isBot()) {
                player.sendPacket(new UserPresencePacket(p));
                return;
            }
            toNotify.add(p);
        });

        // Tournament clients are invisible auxiliary sessions; don't announce
        // them to everyone else as a newly connected user.
        if (player.isTourneyClient() || toNotify.isEmpty()) {
            return;
        }

        server.executor.submit(() -> {
            for (Player p : toNotify) {
                p.sendPacket(new UserPresencePacket(player));
            }
        });
    }

    private void sendWelcomeMessages(Server server, Player player) {
        WelcomeMessage welcomeConfig = server.config.getWelcomeMessage();

        if (welcomeConfig.isNotificationEnabled()) {
            String notificationMessage = welcomeConfig.getNotificationMessage()
                    .replace("%version%", BuildInfo.VERSION);
            player.sendPacket(new NotificationPacket(notificationMessage));
        }

        if (welcomeConfig.isBotEnabled()) {
            String botMessage = welcomeConfig.getBotMessage().replace("%version%", BuildInfo.VERSION);
            player.sendPacket(new SendMessagePacket(server.botPlayer.getUsername(), botMessage,
                    player.getUsername(), server.botPlayer.getId()));
        }
    }

    /**
     * Whether this login has to be answered with the verification reply instead of a session.
     *
     * <p>True only when the account has two factor authentication switched on and this machine
     * has not answered a code for it before. Everyone else - which is everyone, until they turn
     * it on themselves in the settings - is unaffected.
     *
     * <p>When it is true the login is filed under the machine's fingerprint before the reply
     * goes out, so the page the client is about to open knows whose code it is waiting for. The
     * response is written here rather than by the caller because the caller has nothing left to
     * do either way.
     */
    private boolean requiresTwoFactor(Context ctx, UserEntity userEntity, LoginResponse loginResponse) {
        if (!TotpService.enabled(userEntity.getTotpSecret())) {
            return false;
        }

        String fingerprint = TwoFactorService.fingerprint(loginResponse.getClientHash());

        // No usable client hash means no way to recognise the machine, and no way for the
        // verification page to find this login either. Refusing every such login would lock the
        // account out of a client that sends an odd hash, so the password is taken as enough.
        if (fingerprint == null) {
            logger.warn("Login of <{}> carries no usable client hash; skipping the 2FA check",
                    userEntity.getName());

            return false;
        }

        if (TwoFactorService.isTrusted(userEntity.getId(), fingerprint)) {
            return false;
        }

        TwoFactorService.openChallenge(loginResponse.getClientHash(), userEntity.getId());

        logger.info("Login of <{}> from <{}> needs two factor verification",
                userEntity.getName(), loginResponse.getIp());

        sendLoginFailure(ctx, VERIFICATION_REQUIRED);

        return true;
    }

    private void sendLoginFailure(Context ctx, int loginState) {
        BanchoPacketWriter writer = new BanchoPacketWriter();
        writer.startPacket(ServerPacketEngine.ServerPackets.LOGIN_REPLY);
        writer.writeInt(loginState);
        writer.endPacket();

        ctx.status(HttpStatus.OK)
                .header("cho-token", "")
                .contentType("application/octet-stream")
                .result(writer.getPackets());
    }
}
