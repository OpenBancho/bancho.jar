package com.osuserverlist.bjar.modules.irc;

import com.osuserverlist.bjar.models.essentials.Channel;
import com.osuserverlist.bjar.models.essentials.Player;

/**
 * Static bridge between the core server (channel membership changes) and the
 * IRC gateway. All calls are no-ops when the IRC gateway is disabled.
 */
public final class IrcGateway {

    private static volatile IrcServer server;

    public static void setServer(IrcServer ircServer) {
        server = ircServer;
    }

    public static IrcServer getServer() {
        return server;
    }

    /**
     * Notifies IRC members of a channel that a player joined it.
     */
    public static void onChannelJoin(Channel channel, Player player) {
        IrcServer ircServer = server;
        if (ircServer == null || channel == null || player == null) {
            return;
        }

        ircServer.broadcastToChannel(channel, player,
                ":" + IrcServer.hostmask(player) + " JOIN :" + channel.getName());
    }

    /**
     * Notifies IRC members of a channel that a player left it.
     */
    public static void onChannelLeave(Channel channel, Player player) {
        IrcServer ircServer = server;
        if (ircServer == null || channel == null || player == null) {
            return;
        }

        ircServer.broadcastToChannel(channel, player,
                ":" + IrcServer.hostmask(player) + " PART :" + channel.getName());
    }
}
