package com.osuserverlist.bjar.packets.client;

import java.io.IOException;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.essentials.Channel;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.replay.ReplayFrameBundle;
import com.osuserverlist.bjar.modules.packets.BanchoPacketReader;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.ClientPacket;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.ClientPackets;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacket;
import com.osuserverlist.bjar.packets.BanchoPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.ChannelInfoPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.ChannelJoinSuccessPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.ChannelRevokedPacket;
import com.osuserverlist.bjar.packets.server.SpectateServerPackets.*;

public class SpectatePackets {

    @ClientPacket(ClientPackets.SPECTATE_FRAMES)
    public boolean spectateFrames(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        ReplayFrameBundle frames = reader.readReplayFrameBundle();

        byte[] rawData = frames.getRawData();

        for (Player spectator : player.getSpectators()) {
            spectator.sendPacket(new SpectateFramesPacket(rawData));
        }

        return true;
    }

    @ClientPacket(ClientPackets.CANT_SPECTATE)
    public boolean cantSpectate(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        if(player.getSpectating() == null) return true;
        if(player.getSpectating().isStealth()) return true;

        ServerPacket cantSpectatePacket = new CantSpectatePacket(player.getId());
        Player host = player.getSpectating();
        host.sendPacket(cantSpectatePacket);

        for(Player p : host.getSpectators()) {
            p.sendPacket(cantSpectatePacket);
        }

        return true;
    }

    @ClientPacket(ClientPackets.START_SPECTATING)
    public boolean startSpectating(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        int targetUserId = reader.readInt();

        Server server = App.server;

        Player newHost = server.playerManager.getById(targetUserId);
        if (newHost == null) {
            return true;
        }

        // If already spectating someone else, stop spectating them first.
        Player currentHost = player.getSpectating();
        if (currentHost != null) {
            if (currentHost == newHost) {
                return true;
            }

            currentHost.getSpectators().remove(player);

            String oldChannelName = "#spec_" + currentHost.getId();
            Channel oldChannel = server.channelManager.get(oldChannelName);

            if (oldChannel != null) {
                server.channelManager.forceLeaveChannel(oldChannelName, player);
                player.sendPacket(new ChannelRevokedPacket(oldChannel.getAlias()));

                if (currentHost.getSpectators().isEmpty()) {
                    server.channelManager.forceLeaveChannel(oldChannelName, currentHost);
                    server.channelManager.removeChannel(oldChannelName);
                }
            }

            player.setSpectating(null);
        }

        String channelName = "#spec_" + newHost.getId();
        Channel channel = server.channelManager.get(channelName);

        if (channel == null) {
            channel = Channel.builder().id(channelName).alias("#spectator").name(channelName)
                    .description("Spectator channel for " + newHost.getUsername())
                    .autoJoin(false)
                    .readPriv(0)
                    .writePriv(0)
                    .visible(false)
                    .build();

            server.channelManager.add(channel);

            server.channelManager.forceJoinChannel(channelName, newHost);
            newHost.sendPacket(new ChannelJoinSuccessPacket(channel.getAlias()));
            newHost.sendPacket(
                    new ChannelInfoPacket(
                            channel.getAlias(),
                            channel.getDescription(),
                            (short) channel.getPlayerCount()));

        }

        // Join spectator channel
        server.channelManager.forceJoinChannel(channelName, player);

        player.sendPacket(new ChannelJoinSuccessPacket(channel.getAlias()));
        player.sendPacket(
                new ChannelInfoPacket(
                        channel.getAlias(),
                        channel.getDescription(),
                        (short) channel.getPlayerCount()));

        if (!player.isStealth()) {
            // Existing spectators -> joining spectator
            for (Player spectator : newHost.getSpectators()) {
                player.sendPacket(
                        new FellowSpectatorJoinedPacket(
                                spectator.getId()));
            }

            // Joining spectator -> existing spectators
            FellowSpectatorJoinedPacket joinedPacket = new FellowSpectatorJoinedPacket(player.getId());

            for (Player spectator : newHost.getSpectators()) {
                spectator.sendPacket(joinedPacket);
            }

            // Notify host
            newHost.sendPacket(
                    new SpectatorJoinedPacket(player.getId()));
        }

        newHost.getSpectators().add(player);
        player.setSpectating(newHost);
        return true;
    }

    @ClientPacket(ClientPackets.STOP_SPECTATING)
    public boolean stopSpectating(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        Player host = player.getSpectating();
        Server server = App.server;

        if (host == null) {
            return true;
        }

        host.getSpectators().remove(player);
        player.setSpectating(null);

        host.sendPacket(new SpectatorLeftPacket(player.getId()));

        String channelName = "#spec_" + host.getId();
        Channel channel = server.channelManager.get(channelName);

        if (channel != null) {
            server.channelManager.forceLeaveChannel(channelName, player);
            player.sendPacket(new ChannelRevokedPacket(channel.getAlias()));

            ChannelInfoPacket infoPacket = new ChannelInfoPacket(
                    channel.getAlias(),
                    channel.getDescription(),
                    (short) channel.getPlayerCount());

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
                player.sendPacket(new ChannelRevokedPacket(channel.getAlias()));
            }

        }

        return true;
    }

}
