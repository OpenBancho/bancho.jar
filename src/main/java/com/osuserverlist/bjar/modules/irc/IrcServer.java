package com.osuserverlist.bjar.modules.irc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.essentials.Channel;
import com.osuserverlist.bjar.models.essentials.Player;

/**
 * A minimal IRC (RFC 1459 subset) gateway for the Bancho chat, allowing users
 * to connect with regular IRC clients (HexChat, mIRC, ...) or IRC based bots
 * to chat with in-game players.
 *
 * <p>One virtual thread is spawned per connection; the actual protocol
 * handling lives in {@link IrcClient}.</p>
 */
public class IrcServer {

    private static final Logger logger = LoggerFactory.getLogger(IrcServer.class);

    private final Set<IrcClient> clients = ConcurrentHashMap.newKeySet();

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private ScheduledFuture<?> pingTask;

    public void start(int port) throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(port));
        running = true;

        Thread.ofVirtual().name("irc-accept").start(this::acceptLoop);
        pingTask = App.server.executor.scheduleAtFixedRate(this::pingClients, 60, 60, TimeUnit.SECONDS);

        logger.info("IRC gateway listening on port <{}>", port);
    }

    public void stop() {
        running = false;

        if (pingTask != null) {
            pingTask.cancel(false);
        }

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                logger.warn("Failed to close IRC server socket", e);
            }
        }

        clients.forEach(client -> client.closeConnection("Server shutting down"));
        clients.clear();

        logger.info("IRC gateway stopped");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();

                String address = socket.getInetAddress() == null ? "" : socket.getInetAddress().getHostAddress();
                long fromAddress = clients.stream().filter(c -> c.remoteIp().equals(address)).count();

                if (clients.size() >= 500 || fromAddress >= 10) {
                    socket.close();
                    continue;
                }

                IrcClient client = new IrcClient(this, socket);
                clients.add(client);
                Thread.ofVirtual()
                        .name("irc-client-" + socket.getRemoteSocketAddress())
                        .start(client);
            } catch (IOException e) {
                if (running) {
                    logger.warn("Failed to accept IRC connection", e);
                }
            }
        }
    }

    private void pingClients() {
        String serverName = serverName();
        clients.forEach(client -> client.sendRawQuietly("PING :" + serverName));
    }

    void unregister(IrcClient client) {
        clients.remove(client);
    }

    /**
     * Sends a raw IRC line to every IRC member of the given channel, except
     * {@code exclude} (usually the originator of the event).
     */
    public void broadcastToChannel(Channel channel, Player exclude, String rawLine) {
        channel.getPlayers().forEach(member -> {
            if (member == exclude) {
                return;
            }
            if (member instanceof IrcPlayer ircMember) {
                ircMember.getClient().sendRawQuietly(rawLine);
            }
        });
    }

    /**
     * The name this IRC server identifies itself with.
     */
    public static String serverName() {
        String domain = App.server.enviromentConfig.getDomain();
        if (domain == null || domain.isBlank()) {
            return "bancho.jar";
        }
        return "irc." + domain;
    }

    /**
     * IRC nicknames cannot contain spaces; Bancho traditionally replaces them
     * with underscores.
     */
    public static String ircNick(String username) {
        return username == null ? "" : username.replace(' ', '_');
    }

    /**
     * A {@code nick!user@host} mask for the given player.
     */
    public static String hostmask(Player player) {
        String nick = ircNick(player.getUsername());
        return nick + "!" + nick + "@" + serverName();
    }
}
