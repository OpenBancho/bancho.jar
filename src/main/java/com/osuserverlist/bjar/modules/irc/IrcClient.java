package com.osuserverlist.bjar.modules.irc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Channel;
import com.osuserverlist.bjar.models.essentials.Match;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.main.Commands;
import com.osuserverlist.bjar.modules.main.Cryptography;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.ChannelInfoPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.ChannelJoinSuccessPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.ChannelRevokedPacket;
import com.osuserverlist.bjar.packets.server.SpectateServerPackets.FellowSpectatorJoinedPacket;
import com.osuserverlist.bjar.packets.server.SpectateServerPackets.FellowSpectatorLeftPacket;
import com.osuserverlist.bjar.packets.server.SpectateServerPackets.SpectatorJoinedPacket;
import com.osuserverlist.bjar.packets.server.SpectateServerPackets.SpectatorLeftPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.SendMessagePacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.TargetIsSilencedPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.UserDmBlockedPacket;
import com.osuserverlist.bjar.packets.server.UserServerPackets.UserPresencePacket;
import com.osuserverlist.bjar.packets.server.UserServerPackets.UserStatsPacket;
import com.osuserverlist.bjar.packets.server.UtilServerPackets.NotificationPacket;
import com.osuserverlist.bjar.packets.server.UtilServerPackets.RestartPacket;
import com.osuserverlist.bjar.repos.UserRepository;

import java.util.Set;

/**
 * Handles a single IRC connection: registration (PASS/NICK/USER), the RFC
 * 1459 command subset needed by common IRC clients, and translation of
 * Bancho {@link ServerPacket}s into IRC lines for the attached
 * {@link IrcPlayer}.
 */
public class IrcClient implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(IrcClient.class);

    private final IrcServer ircServer;
    private final Socket socket;

    private final Object writeLock = new Object();
    private final AtomicBoolean socketClosed = new AtomicBoolean(false);
    private final AtomicBoolean playerDetached = new AtomicBoolean(false);

    private BufferedWriter out;

    // Registration state
    private String pendingPass;
    private String pendingNick;
    private boolean registered = false;

    private IrcPlayer player;

    public IrcClient(IrcServer ircServer, Socket socket) {
        this.ircServer = ircServer;
        this.socket = socket;
    }

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            // An unregistered connection that stays silent is dropped; the server pings registered ones every minute.
            socket.setSoTimeout(60_000);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String line;
            while ((line = readBoundedLine(in)) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if (player != null) {
                    player.setLastPing(System.currentTimeMillis());
                }

                try {
                    handleLine(line);
                } catch (Exception e) {
                    logger.error("Error handling IRC line <{}>", line, e);
                }

                if (socketClosed.get()) {
                    break;
                }
            }
        } catch (IOException e) {
            // Connection reset / closed - normal for IRC clients
        } finally {
            cleanup();
        }
    }

    /** A line of at most 2048 characters; a longer one (or no end of line at all) ends the connection. */
    private static String readBoundedLine(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;

        while ((c = in.read()) != -1) {
            if (c == '\n') {
                return sb.toString();
            }

            if (c != '\r') {
                sb.append((char) c);
            }

            if (sb.length() > 2048) {
                throw new IOException("IRC line too long");
            }
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    String remoteIp() {
        return socket.getInetAddress() == null ? "" : socket.getInetAddress().getHostAddress();
    }

    private static final com.osuserverlist.bjar.modules.util.RateLimiter IRC_LIMIT =
            new com.osuserverlist.bjar.modules.util.RateLimiter(10, 15 * 60_000L);

    private void cleanup() {
        closeConnection("Connection closed");

        if (player != null && playerDetached.compareAndSet(false, true)) {
            Player current = App.server.playerManager.get(player.getOsuToken());
            if (current == player) {
                if (player.getSpectating() != null) {
                    try {
                        stopSpectatingFromIrc();
                    } catch (Exception e) {
                        logger.error("Error stopping IRC spectating for {}", player, e);
                    }
                }
                App.server.playerManager.disconnect(player);
                logger.info("IRC user {} disconnected", player);
            }
        }
    }

    /**
     * Called by {@code PlayerManager#disconnect} when the server side kicks
     * this player (duplicate login, inactivity, restriction, ...).
     */
    public void detachAndClose(String reason) {
        playerDetached.set(true);
        closeConnection(reason);
    }

    /**
     * Closes the underlying socket. Safe to call multiple times.
     */
    public void closeConnection(String reason) {
        if (!socketClosed.compareAndSet(false, true)) {
            return;
        }

        sendRawQuietly("ERROR :" + reason);

        try {
            socket.close();
        } catch (IOException ignored) {
        }

        ircServer.unregister(this);
    }

    // ------------------------------------------------------------------
    // Outgoing traffic
    // ------------------------------------------------------------------

    public void sendRaw(String line) throws IOException {
        synchronized (writeLock) {
            if (out == null || socketClosed.get()) {
                return;
            }
            out.write(line);
            out.write("\r\n");
            out.flush();
        }
    }

    public void sendRawQuietly(String line) {
        try {
            sendRaw(line);
        } catch (IOException ignored) {
        }
    }

    private void sendNumeric(String numeric, String params) {
        String nick = pendingNick != null ? pendingNick : "*";
        if (player != null) {
            nick = IrcServer.ircNick(player.getUsername());
        }
        sendRawQuietly(":" + IrcServer.serverName() + " " + numeric + " " + nick + " " + params);
    }

    private void sendFromServerNotice(String text) {
        for (String part : text.split("\n")) {
            sendRawQuietly(":" + IrcServer.serverName() + " NOTICE "
                    + (player != null ? IrcServer.ircNick(player.getUsername()) : "*") + " :" + part);
        }
    }

    // ------------------------------------------------------------------
    // Bancho packet translation (osu! -> IRC)
    // ------------------------------------------------------------------

    public void handleServerPacket(ServerPacket packet) {
        if (!registered) {
            return;
        }

        if (packet instanceof SendMessagePacket message) {
            String target = message.getTarget().startsWith("#")
                    ? ircChannelTarget(message.getTarget())
                    : IrcServer.ircNick(player.getUsername());

            String senderNick = IrcServer.ircNick(message.getSenderName());
            String mask = senderNick + "!" + senderNick + "@" + IrcServer.serverName();

            for (String part : message.getMessage().split("\n")) {
                sendRawQuietly(":" + mask + " PRIVMSG " + target + " :" + part);
            }
            return;
        }

        if (packet instanceof ChannelRevokedPacket revoked) {
            sendRawQuietly(":" + IrcServer.hostmask(player) + " PART :" + revoked.getChannelName());
            return;
        }

        if (packet instanceof NotificationPacket notification) {
            sendFromServerNotice(notification.getMessage());
            return;
        }

        if (packet instanceof TargetIsSilencedPacket silenced) {
            sendFromServerNotice(silenced.getMessage());
            return;
        }

        if (packet instanceof UserDmBlockedPacket blocked) {
            sendFromServerNotice(blocked.getMessage());
            return;
        }

        if (packet instanceof RestartPacket) {
            detachAndClose("Server is restarting");
            return;
        }

        // All other packets (presence, stats, channel info, ...) have no IRC
        // representation and are silently dropped.
    }

    // ------------------------------------------------------------------
    // Incoming traffic (IRC -> Bancho)
    // ------------------------------------------------------------------

    private void handleLine(String line) {
        // Strip optional prefix
        if (line.startsWith(":")) {
            int space = line.indexOf(' ');
            if (space == -1) {
                return;
            }
            line = line.substring(space + 1).trim();
        }

        String trailing = null;
        int trailingIndex = line.indexOf(" :");
        if (trailingIndex != -1) {
            trailing = line.substring(trailingIndex + 2);
            line = line.substring(0, trailingIndex);
        }

        String[] parts = line.split(" +");
        String command = parts[0].toUpperCase(Locale.ROOT);

        List<String> params = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            params.add(parts[i]);
        }
        if (trailing != null) {
            params.add(trailing);
        }

        switch (command) {
            case "CAP" -> handleCap(params);
            case "PASS" -> pendingPass = params.isEmpty() ? null : params.get(0);
            case "NICK" -> handleNick(params);
            case "USER" -> handleUser();
            case "PING" -> sendRawQuietly(":" + IrcServer.serverName() + " PONG " + IrcServer.serverName()
                    + " :" + (params.isEmpty() ? IrcServer.serverName() : params.get(0)));
            case "PONG" -> { /* lastPing already updated */ }
            case "JOIN" -> requireRegistered(() -> handleJoin(params));
            case "PART" -> requireRegistered(() -> handlePart(params));
            case "PRIVMSG" -> requireRegistered(() -> handlePrivmsg(params));
            case "LIST" -> requireRegistered(() -> handleList());
            case "NAMES" -> requireRegistered(() -> handleNames(params));
            case "TOPIC" -> requireRegistered(() -> handleTopic(params));
            case "MODE" -> requireRegistered(() -> handleMode(params));
            case "WHO" -> requireRegistered(() -> handleWho(params));
            case "WHOIS" -> requireRegistered(() -> handleWhois(params));
            case "ISON" -> requireRegistered(() -> handleIson(params));
            case "MOTD" -> requireRegistered(this::sendMotd);
            case "AWAY" -> sendNumeric("305", ":You are no longer marked as being away");
            case "QUIT" -> closeConnection("Quit: " + (params.isEmpty() ? "" : params.get(0)));
            default -> {
                if (registered) {
                    sendNumeric("421", command + " :Unknown command");
                }
            }
        }
    }

    private void requireRegistered(Runnable action) {
        if (!registered) {
            sendNumeric("451", ":You have not registered");
            return;
        }
        action.run();
    }

    private void handleCap(List<String> params) {
        if (!params.isEmpty() && params.get(0).equalsIgnoreCase("LS")) {
            sendRawQuietly(":" + IrcServer.serverName() + " CAP * LS :");
        }
        // CAP REQ / END are ignored - we do not support IRCv3 capabilities.
    }

    private void handleNick(List<String> params) {
        if (params.isEmpty()) {
            sendNumeric("431", ":No nickname given");
            return;
        }

        if (registered) {
            sendNumeric("484", ":Nickname changes are not supported on this server");
            return;
        }

        pendingNick = params.get(0);

        // Only attempt registration when a password was already provided
        // (PASS is sent before NICK by all common clients). Otherwise wait
        // for USER, so clients sending PASS late are not rejected too early.
        if (pendingPass != null) {
            tryRegister();
        }
    }

    private void handleUser() {
        if (registered) {
            return;
        }

        if (pendingPass == null) {
            sendNumeric("464", ":Password required. Configure your IRC client to send your account password via PASS.");
            closeConnection("Password required");
            return;
        }

        tryRegister();
    }

    // ------------------------------------------------------------------
    // Registration / authentication
    // ------------------------------------------------------------------

    private void tryRegister() {
        if (registered || pendingNick == null) {
            return;
        }

        if (pendingPass == null) {
            return;
        }

        String loginName = pendingNick.replace('_', ' ');
        UserEntity user = UserRepository.findByName(loginName);
        if (user == null && !loginName.equals(pendingNick)) {
            user = UserRepository.findByName(pendingNick);
        }

        String ipKey = "ip:" + remoteIp();
        String nickKey = "nick:" + pendingNick.toLowerCase();

        if (IRC_LIMIT.isBlocked(ipKey) || IRC_LIMIT.isBlocked(nickKey)) {
            sendNumeric("464", ":Too many failed attempts.");
            closeConnection("Too many failed attempts");
            return;
        }

        if (user == null || !checkPassword(user, pendingPass)) {
            IRC_LIMIT.record(ipKey);
            IRC_LIMIT.record(nickKey);
            logger.warn("Failed IRC login attempt for <{}> from {}", pendingNick, socket.getRemoteSocketAddress());
            sendNumeric("464", ":Bad authentication token.");
            closeConnection("Bad authentication token");
            return;
        }

        // The authenticator code cannot be asked for here, so a password alone must not open an account that has 2FA.
        if (com.osuserverlist.bjar.modules.account.TotpService.enabled(user.getTotpSecret())) {
            sendNumeric("464", ":Accounts with two factor authentication cannot use IRC.");
            closeConnection("Two factor accounts cannot use IRC");
            return;
        }

        IRC_LIMIT.reset(nickKey);

        Server server = App.server;

        // Kick a previous IRC session of the same account, if any.
        Player existing = server.playerManager.getById(user.getId());
        if (existing instanceof IrcPlayer existingIrc) {
            server.playerManager.disconnect(existingIrc);
        }

        player = new IrcPlayer(user.getId(), UUID.randomUUID().toString(), this);
        player.setUsername(user.getName());
        player.setEntity(user);
        player.setServerPrivileges(user.getPrivileges());
        player.setClientPrivileges(Privileges.toClientPrivileges(user.getPrivileges()));
        player.setSilenceEnd(user.getSilenceEnd());
        player.setDonorEnd(user.getDonorEnd());
        player.setActionText("on IRC");
        player.setApiIdent("irc|" + user.getId() + "|" + player.getOsuToken());

        if (!Privileges.hasAny(user.getPrivileges(), Privileges.UNRESTRICTED)) {
            player.setRestricted(true);
        }

        server.playerManager.add(player);
        registered = true;

        try {
            socket.setSoTimeout(5 * 60_000);
        } catch (java.net.SocketException ignored) {
            // keep the shorter timeout
        }

        // Make the IRC user visible to osu! clients.
        for (Player other : server.playerManager.getAllSessions()) {
            if (other == player) {
                continue;
            }
            other.sendPacket(new UserPresencePacket(player));
            other.sendPacket(new UserStatsPacket(player));
        }

        String nick = IrcServer.ircNick(player.getUsername());
        String serverName = IrcServer.serverName();

        sendRawQuietly(":" + serverName + " 001 " + nick + " :Welcome to " + serverName + ", " + nick);
        sendRawQuietly(":" + serverName + " 002 " + nick + " :Your host is " + serverName + ", running bancho.jar");
        sendRawQuietly(":" + serverName + " 003 " + nick + " :This server was created for osu!");
        sendRawQuietly(":" + serverName + " 004 " + nick + " " + serverName + " bancho.jar o o");

        sendMotd();

        logger.info("IRC user {} logged in from {}", player, socket.getRemoteSocketAddress());
    }

    private boolean checkPassword(UserEntity user, String password) {
        try {
            // osu! stores bcrypt(md5(password)). Accept the md5 hash directly
            // (the same secret the osu! client uses) ...
            if (password.matches("^[0-9a-fA-F]{32}$")
                    && OpenBSDBCrypt.checkPassword(user.getPasswordHash(),
                            password.toLowerCase(Locale.ROOT).toCharArray())) {
                return true;
            }

            // ... or the plaintext password, which we md5 ourselves.
            return OpenBSDBCrypt.checkPassword(user.getPasswordHash(),
                    Cryptography.generateChecksum(password).toCharArray());
        } catch (Exception e) {
            logger.error("Failed to verify IRC password for user <{}>", user.getName(), e);
            return false;
        }
    }

    private void sendMotd() {
        String serverName = IrcServer.serverName();
        sendNumeric("375", ":- " + serverName + " Message of the Day -");
        sendNumeric("372", ":- Welcome to the " + serverName + " IRC gateway.");
        sendNumeric("372", ":- Use /LIST to view available channels.");
        sendNumeric("372", ":- Use /JOIN #spec_<nick> to spectate a live player, /PART to stop.");
        sendNumeric("372", ":- Use /JOIN #lobby for the multiplayer lobby, or /JOIN #mp_<id> for a match.");
        sendNumeric("376", ":End of /MOTD command.");
    }

    // ------------------------------------------------------------------
    // Channels
    // ------------------------------------------------------------------

    private void handleJoin(List<String> params) {
        if (params.isEmpty()) {
            sendNumeric("461", "JOIN :Not enough parameters");
            return;
        }

        String[] channels = params.get(0).split(",");
        String[] keys = params.size() > 1 ? params.get(1).split(",") : new String[0];

        for (int i = 0; i < channels.length; i++) {
            String key = i < keys.length ? keys[i].trim() : null;
            joinSingleChannel(channels[i].trim(), key);
        }
    }

    private void joinSingleChannel(String channelName, String key) {
        Server server = App.server;

        // Spectator channels: "JOIN #spec_<nick>" starts spectating that player.
        if (isSpectatorChannelName(channelName)) {
            handleSpectateJoin(channelName);
            return;
        }

        // Multiplayer match channels: "JOIN #multi_<id>" (or "#mp_<id>") joins a
        // live match's chat, e.g. for a tournament referee.
        if (isMatchChannelName(channelName)) {
            handleMatchJoin(channelName, key);
            return;
        }

        Channel channel = server.channelManager.get(channelName);

        if (channel == null || channelName.equals("#highlight")) {
            sendNumeric("403", channelName + " :No such channel");
            return;
        }

        if (channel.getReadPriv() > player.getServerPrivileges()) {
            sendNumeric("473", channelName + " :Cannot join channel (insufficient privileges)");
            return;
        }

        if (channel.getPlayers().contains(player)) {
            return; // Already a member
        }

        server.channelManager.joinChannel(channel.getName(), player);

        sendRawQuietly(":" + IrcServer.hostmask(player) + " JOIN :" + channel.getName());
        sendTopic(channel);
        sendNames(channel);

        logger.info("IRC user {} joined channel {}", player, channel.getName());
    }

    private void handlePart(List<String> params) {
        if (params.isEmpty()) {
            sendNumeric("461", "PART :Not enough parameters");
            return;
        }

        for (String channelName : params.get(0).split(",")) {
            channelName = channelName.trim();

            // Spectator channels: "PART #spec_<nick>" stops spectating.
            if (isSpectatorChannelName(channelName)) {
                handleSpectatePart(channelName);
                continue;
            }

            // Multiplayer match channels: "PART #multi_<id>" leaves the match chat.
            if (isMatchChannelName(channelName)) {
                handleMatchPart(channelName);
                continue;
            }

            Channel channel = App.server.channelManager.get(channelName);

            if (channel == null || !channel.getPlayers().contains(player)) {
                sendNumeric("442", channelName + " :You're not on that channel");
                continue;
            }

            App.server.channelManager.leaveChannel(channel.getName(), player);
            sendRawQuietly(":" + IrcServer.hostmask(player) + " PART :" + channel.getName());

            logger.info("IRC user {} left channel {}", player, channel.getName());
        }
    }

    private void sendTopic(Channel channel) {
        String description = channel.getDescription();
        if (description == null || description.isBlank()) {
            sendNumeric("331", channel.getName() + " :No topic is set");
        } else {
            sendNumeric("332", channel.getName() + " :" + description);
        }
    }

    private void sendNames(Channel channel) {
        String names = channel.getPlayers().stream()
                .map(member -> IrcServer.ircNick(member.getUsername()))
                .collect(Collectors.joining(" "));

        sendNumeric("353", "= " + channel.getName() + " :" + names);
        sendNumeric("366", channel.getName() + " :End of /NAMES list.");
    }

    // ------------------------------------------------------------------
    // Messaging
    // ------------------------------------------------------------------

    private void handlePrivmsg(List<String> params) {
        if (params.size() < 2) {
            sendNumeric("461", "PRIVMSG :Not enough parameters");
            return;
        }

        String target = params.get(0);
        String message = params.get(1);

        if (message.isBlank()) {
            return;
        }

        if (!player.canChat()) {
            sendNumeric("404", target + " :You are silenced or restricted");
            return;
        }

        if (target.startsWith("#")) {
            sendChannelMessage(target, message);
        } else {
            sendPrivateMessage(target, message);
        }
    }

    private void sendChannelMessage(String target, String message) {
        Server server = App.server;

        // Spectator channels are keyed internally by host id ("#spec_<id>"),
        // but IRC users address them by host nick ("#spec_<nick>").
        Channel channel;
        if (isSpectatorChannelName(target)) {
            channel = resolveSpectatorChannel(target);
        } else if (isMatchChannelName(target)) {
            String key = matchChannelKey(target);
            channel = key == null ? null : server.channelManager.get(key);
        } else {
            channel = server.channelManager.get(target);
        }

        if (channel == null) {
            sendNumeric("403", target + " :No such channel");
            return;
        }

        if (!channel.getPlayers().contains(player)) {
            sendNumeric("404", target + " :Cannot send to channel (join it first)");
            return;
        }

        // osu! clients render messages by the channel alias ("#spectator" for
        // spectator channels); IRC clients translate that alias back into the
        // concrete channel name in handleServerPacket.
        String outTarget = channel.getAlias() != null ? channel.getAlias() : channel.getName();

        channel.getPlayers().forEach(user -> {
            if (user.isBot() || user.getId() == player.getId()) {
                return;
            }
            user.sendPacket(new SendMessagePacket(player.getUsername(), message, outTarget, player.getId()));
        });

        Commands.processNp(player, message);
        Commands.processCommand(player, message, outTarget, channel.getPlayers());

        logger.info("IRC message from {}: <{}> to <{}>", player, message, channel.getName());
    }

    private void sendPrivateMessage(String target, String message) {
        String targetName = target.replace('_', ' ');

        Player targetPlayer = App.server.playerManager.getByFilter(p ->
                p.getUsername().equalsIgnoreCase(targetName)
                        || IrcServer.ircNick(p.getUsername()).equalsIgnoreCase(target));

        if (targetPlayer == null) {
            sendNumeric("401", target + " :No such nick");
            return;
        }

        if (!targetPlayer.canChat()) {
            String state = targetPlayer.isSilenced() ? "silenced" : "restricted";
            sendFromServerNotice(targetPlayer.getUsername() + " has been " + state
                    + " and is unable to respond to your messages right now.");
            return;
        }

        if (targetPlayer.getBlocks().contains(player.getId())) {
            sendFromServerNotice(targetPlayer.getUsername()
                    + " is currently blocking private messages from people not on their friends list.");
            return;
        }

        targetPlayer.sendPacket(new SendMessagePacket(player.getUsername(), message,
                targetPlayer.getUsername(), player.getId()));

        Commands.processNp(player, message);
        Commands.processCommand(player, message, targetPlayer.getUsername(), Set.of(player));

        logger.info("IRC private message from <{}>: <{}> to <{}>", player.getUsername(), message, targetPlayer.getUsername());
    }

    // ------------------------------------------------------------------
    // Informational commands
    // ------------------------------------------------------------------

    private void handleList() {
        sendNumeric("321", "Channel :Users Name");
        App.server.channelManager.getAll().forEach(channel -> {
            if (!channel.isVisible() || channel.getReadPriv() > player.getServerPrivileges()) {
                return;
            }
            String description = channel.getDescription() == null ? "" : channel.getDescription();
            sendNumeric("322", channel.getName() + " " + channel.getPlayerCount() + " :" + description);
        });
        sendNumeric("323", ":End of /LIST");
    }

    private void handleNames(List<String> params) {
        if (params.isEmpty()) {
            sendNumeric("461", "NAMES :Not enough parameters");
            return;
        }

        Channel channel = App.server.channelManager.get(params.get(0));
        if (channel == null) {
            sendNumeric("403", params.get(0) + " :No such channel");
            return;
        }

        sendNames(channel);
    }

    private void handleTopic(List<String> params) {
        if (params.isEmpty()) {
            sendNumeric("461", "TOPIC :Not enough parameters");
            return;
        }

        Channel channel = App.server.channelManager.get(params.get(0));
        if (channel == null) {
            sendNumeric("403", params.get(0) + " :No such channel");
            return;
        }

        sendTopic(channel);
    }

    private void handleMode(List<String> params) {
        if (params.isEmpty()) {
            return;
        }

        if (params.get(0).startsWith("#")) {
            sendNumeric("324", params.get(0) + " +nt");
        } else {
            sendNumeric("221", "+i");
        }
    }

    private void handleWho(List<String> params) {
        if (!params.isEmpty() && params.get(0).startsWith("#")) {
            Channel channel = App.server.channelManager.get(params.get(0));
            if (channel != null) {
                channel.getPlayers().forEach(member -> {
                    String nick = IrcServer.ircNick(member.getUsername());
                    sendNumeric("352", channel.getName() + " " + nick + " " + IrcServer.serverName() + " "
                            + IrcServer.serverName() + " " + nick + " H :0 " + member.getUsername());
                });
            }
        }
        sendNumeric("315", (params.isEmpty() ? "*" : params.get(0)) + " :End of /WHO list.");
    }

    private void handleWhois(List<String> params) {
        if (params.isEmpty()) {
            sendNumeric("431", ":No nickname given");
            return;
        }

        String target = params.get(0);
        Player targetPlayer = App.server.playerManager.getByFilter(p ->
                IrcServer.ircNick(p.getUsername()).equalsIgnoreCase(target));

        if (targetPlayer == null) {
            sendNumeric("401", target + " :No such nick");
            return;
        }

        String nick = IrcServer.ircNick(targetPlayer.getUsername());

        sendNumeric("311", nick + " " + nick + " " + IrcServer.serverName() + " * :" + targetPlayer.getUsername());

        String channels = App.server.channelManager.getAll().stream()
                .filter(channel -> channel.isVisible() && channel.getPlayers().contains(targetPlayer))
                .map(Channel::getName)
                .collect(Collectors.joining(" "));

        if (!channels.isBlank()) {
            sendNumeric("319", nick + " :" + channels);
        }

        sendNumeric("312", nick + " " + IrcServer.serverName() + " :bancho.jar");
        sendNumeric("318", nick + " :End of /WHOIS list.");
    }

    private void handleIson(List<String> params) {
        String online = params.stream()
                .filter(nick -> App.server.playerManager.getByFilter(p ->
                        IrcServer.ircNick(p.getUsername()).equalsIgnoreCase(nick)) != null)
                .collect(Collectors.joining(" "));

        sendNumeric("303", ":" + online);
    }

    // ------------------------------------------------------------------
    // Multiplayer match channels (IRC users can chat in a live match,
    // e.g. as a tournament referee)
    // ------------------------------------------------------------------

    /**
     * Match channels are keyed internally as {@code #multi_<matchId>}. IRC
     * users may address them as {@code #multi_<id>} or, following the common
     * Bancho convention, {@code #mp_<id>}.
     */
    private boolean isMatchChannelName(String channelName) {
        String lower = channelName.toLowerCase(Locale.ROOT);
        return lower.startsWith("#multi_") || lower.startsWith("#mp_");
    }

    /** Normalises an IRC match-channel name to the internal {@code #multi_<id>} key. */
    private String matchChannelKey(String channelName) {
        String lower = channelName.toLowerCase(Locale.ROOT);
        String suffix;
        if (lower.startsWith("#multi_")) {
            suffix = channelName.substring("#multi_".length());
        } else if (lower.startsWith("#mp_")) {
            suffix = channelName.substring("#mp_".length());
        } else {
            return null;
        }
        return suffix.matches("\\d+") ? "#multi_" + suffix : null;
    }

    /** The match channel this IRC user currently belongs to, or {@code null}. */
    private Channel currentMatchChannel() {
        for (Channel channel : App.server.channelManager.getAll()) {
            if (channel.getName().toLowerCase(Locale.ROOT).startsWith("#multi_")
                    && channel.getPlayers().contains(player)) {
                return channel;
            }
        }
        return null;
    }

    private void handleMatchJoin(String channelName, String key) {
        String channelKey = matchChannelKey(channelName);
        Channel channel = channelKey == null ? null : App.server.channelManager.get(channelKey);

        if (channel == null) {
            sendFromServerNotice("No such match. Use /join #multi_<id> (or #mp_<id>) of a live match.");
            sendNumeric("403", channelName + " :No such channel");
            return;
        }

        if (channel.getPlayers().contains(player)) {
            return; // Already a member
        }

        // An IRC user may only be in one match channel at a time.
        Channel current = currentMatchChannel();
        if (current != null) {
            sendFromServerNotice("You are already in " + current.getName()
                    + " - PART it before joining another match.");
            sendNumeric("405", channelName + " :You are already in a match channel");
            return;
        }

        // Enforce the match password (if set), exactly like the osu! client does.
        Match match = App.server.matchManager.getById(
                (short) Integer.parseInt(channelKey.substring("#multi_".length())));
        if (match != null) {
            String password = match.getRoomPassword();
            if (password != null && !password.isEmpty() && !password.equals(key)) {
                sendNumeric("475", channelName + " :Cannot join channel (+k) - incorrect match password");
                sendFromServerNotice("This match is password-protected. Use: /join " + channelName + " <password>");
                return;
            }
        }

        App.server.channelManager.forceJoinChannel(channelKey, player);

        sendRawQuietly(":" + IrcServer.hostmask(player) + " JOIN :" + channelKey);
        sendTopic(channel);
        sendNames(channel);

        logger.info("IRC user {} joined match channel {}", player, channelKey);
    }

    private void handleMatchPart(String channelName) {
        String key = matchChannelKey(channelName);
        Channel channel = key == null ? null : App.server.channelManager.get(key);

        if (channel == null || !channel.getPlayers().contains(player)) {
            sendNumeric("442", channelName + " :You're not on that channel");
            return;
        }

        App.server.channelManager.forceLeaveChannel(key, player);
        sendRawQuietly(":" + IrcServer.hostmask(player) + " PART :" + key);

        logger.info("IRC user {} left match channel {}", player, key);
    }

    // ------------------------------------------------------------------
    // Spectating (IRC users can watch a live player's session)
    // ------------------------------------------------------------------

    /**
     * Spectator channels are addressed from IRC as {@code #spec_<nick>} (or
     * {@code #spectator_<nick>}), while osu! keys them internally by host id.
     */
    private boolean isSpectatorChannelName(String channelName) {
        String lower = channelName.toLowerCase(Locale.ROOT);
        return lower.startsWith("#spec_") || lower.startsWith("#spectator_");
    }

    /** IRC-facing name for a host's spectator channel, e.g. {@code #spec_Nick}. */
    private String ircSpectatorName(Player host) {
        return "#spec_" + IrcServer.ircNick(host.getUsername());
    }

    /** Resolves the host player referenced by an IRC spectator channel name. */
    private Player resolveSpectatorHost(String channelName) {
        String lower = channelName.toLowerCase(Locale.ROOT);
        String suffix;
        if (lower.startsWith("#spectator_")) {
            suffix = channelName.substring("#spectator_".length());
        } else if (lower.startsWith("#spec_")) {
            suffix = channelName.substring("#spec_".length());
        } else {
            return null;
        }

        if (suffix.isBlank()) {
            return null;
        }

        // Allow "#spec_<id>" in addition to "#spec_<nick>".
        if (suffix.matches("\\d+")) {
            Player byId = App.server.playerManager.getById(Integer.parseInt(suffix));
            if (byId != null) {
                return byId;
            }
        }

        String nickName = suffix.replace('_', ' ');
        return App.server.playerManager.getByFilter(p ->
                p.getUsername().equalsIgnoreCase(nickName)
                        || IrcServer.ircNick(p.getUsername()).equalsIgnoreCase(suffix));
    }

    /** Resolves the live backend spectator channel for an IRC spectator name. */
    private Channel resolveSpectatorChannel(String channelName) {
        Player host = resolveSpectatorHost(channelName);
        if (host == null) {
            return null;
        }
        return App.server.channelManager.get("#spec_" + host.getId());
    }

    /**
     * Translates a channel target coming from a Bancho packet into the name the
     * IRC client expects. osu! addresses spectator chat by the "#spectator"
     * alias; map it back to the concrete "#spec_<nick>" the IRC user joined.
     */
    private String ircChannelTarget(String banchoTarget) {
        if (banchoTarget.equalsIgnoreCase("#spectator")) {
            Player host = player.getSpectating();
            if (host == null && !player.getSpectators().isEmpty()) {
                host = player; // this IRC user is the one being spectated
            }
            if (host != null) {
                return ircSpectatorName(host);
            }
        }

        // osu! addresses all match chat by the "#multiplayer" alias; map it back
        // to the concrete "#multi_<id>" channel this IRC user has joined.
        if (banchoTarget.equalsIgnoreCase("#multiplayer")) {
            Channel matchChannel = currentMatchChannel();
            if (matchChannel != null) {
                return matchChannel.getName();
            }
        }

        return banchoTarget;
    }

    private void handleSpectateJoin(String channelName) {
        Player host = resolveSpectatorHost(channelName);

        if (host == null) {
            sendFromServerNotice("No such player to spectate. Use /join #spec_<nickname> of an online player.");
            sendNumeric("403", channelName + " :No such channel");
            return;
        }

        if (host.getId() == player.getId()) {
            sendFromServerNotice("You cannot spectate yourself.");
            return;
        }

        if (host.isBot()) {
            sendFromServerNotice("You cannot spectate a bot.");
            return;
        }

        startSpectatingFromIrc(host);
    }

    private void startSpectatingFromIrc(Player host) {
        Server server = App.server;

        Player currentHost = player.getSpectating();
        if (currentHost != null) {
            if (currentHost.getId() == host.getId()) {
                return; // Already spectating this host.
            }
            stopSpectatingFromIrc();
        }

        String channelName = "#spec_" + host.getId();
        Channel channel = server.channelManager.get(channelName);

        if (channel == null) {
            channel = Channel.builder().id(channelName).alias("#spectator").name(channelName)
                    .description("Spectator channel for " + host.getUsername())
                    .autoJoin(false)
                    .readPriv(0)
                    .writePriv(0)
                    .visible(false)
                    .build();

            server.channelManager.add(channel);

            server.channelManager.forceJoinChannel(channelName, host);
            host.sendPacket(new ChannelJoinSuccessPacket(channel.getAlias()));
            host.sendPacket(new ChannelInfoPacket(
                    channel.getAlias(), channel.getDescription(), (short) channel.getPlayerCount()));
        }

        server.channelManager.forceJoinChannel(channelName, player);

        if (!player.isStealth()) {
            FellowSpectatorJoinedPacket joinedPacket = new FellowSpectatorJoinedPacket(player.getId());
            for (Player spectator : host.getSpectators()) {
                spectator.sendPacket(joinedPacket);
            }
            host.sendPacket(new SpectatorJoinedPacket(player.getId()));
        }

        host.getSpectators().add(player);
        player.setSpectating(host);

        // IRC-facing view of the spectator channel.
        String ircName = ircSpectatorName(host);
        sendRawQuietly(":" + IrcServer.hostmask(player) + " JOIN :" + ircName);

        String description = channel.getDescription();
        if (description == null || description.isBlank()) {
            sendNumeric("331", ircName + " :No topic is set");
        } else {
            sendNumeric("332", ircName + " :" + description);
        }

        String names = channel.getPlayers().stream()
                .map(member -> IrcServer.ircNick(member.getUsername()))
                .collect(Collectors.joining(" "));
        sendNumeric("353", "= " + ircName + " :" + names);
        sendNumeric("366", ircName + " :End of /NAMES list.");

        logger.info("IRC user {} started spectating {}", player, host);
    }

    private void handleSpectatePart(String channelName) {
        Player host = player.getSpectating();

        if (host == null) {
            sendNumeric("442", channelName + " :You're not spectating anyone");
            return;
        }

        String ircName = ircSpectatorName(host);
        stopSpectatingFromIrc();
        sendRawQuietly(":" + IrcServer.hostmask(player) + " PART :" + ircName);
    }

    private void stopSpectatingFromIrc() {
        Server server = App.server;
        Player host = player.getSpectating();

        if (host == null) {
            return;
        }

        host.getSpectators().remove(player);
        player.setSpectating(null);

        host.sendPacket(new SpectatorLeftPacket(player.getId()));

        String channelName = "#spec_" + host.getId();
        Channel channel = server.channelManager.get(channelName);

        if (channel != null) {
            server.channelManager.forceLeaveChannel(channelName, player);

            ChannelInfoPacket infoPacket = new ChannelInfoPacket(
                    channel.getAlias(), channel.getDescription(), (short) channel.getPlayerCount());
            FellowSpectatorLeftPacket leftPacket = new FellowSpectatorLeftPacket(player.getId());

            for (Player spectator : host.getSpectators()) {
                spectator.sendPacket(leftPacket);
                spectator.sendPacket(infoPacket);
            }

            host.sendPacket(infoPacket);

            if (host.getSpectators().isEmpty()) {
                server.channelManager.forceLeaveChannel(channelName, host);
                host.sendPacket(new ChannelRevokedPacket(channel.getAlias()));
                server.channelManager.removeChannel(channelName);
            }
        }

        logger.info("IRC user {} stopped spectating {}", player, host);
    }
}
