package com.osuserverlist.bjar.packets.client;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.essentials.Channel;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.main.Commands;
import com.osuserverlist.bjar.modules.packets.BanchoPacketReader;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.ClientPacket;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.ClientPackets;
import com.osuserverlist.bjar.packets.BanchoPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.ChannelJoinSuccessPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.SendMessagePacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.TargetIsSilencedPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.UserDmBlockedPacket;

public class ChatPackets {

    private static final Logger logger = LoggerFactory.getLogger(ChatPackets.class);

    @ClientPacket(ClientPackets.CHANNEL_JOIN)
    public boolean joinChannel(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        String channelName = reader.readString();

        // Internal channel keys must never be joined directly: that would bypass
        // the match password check in JOIN_MATCH and the spectating check in
        // SpectatePackets. Clients only ever see these channels through their
        // "#multiplayer" / "#spectator" aliases, and membership is granted by
        // MatchManager.joinMatch / the spectate handler once those checks pass.
        if (isInternalChannelName(channelName)) {
            logger.warn("Player {} tried to join internal channel {} directly", player.toString(), channelName);
            return true;
        }

        Channel channel = App.server.channelManager.get(channelName);
        if (channel == null) {
            logger.warn("Player {} tried to join a not existing channel", player.toString());
            return true;
        }

        if(channel.getReadPriv() > player.getServerPrivileges()) {
            logger.warn("Player {} tried to join channel {} without sufficient privileges", player.toString(), channelName);
            return true;
        }

        if(channel.getName().equals("#lobby") && !player.isInLobby()) {
            return true; // Don't allow players to join the lobby if they are not in the lobby
        }

        player.sendPacket(new ChannelJoinSuccessPacket(channelName));

        App.server.channelManager.joinChannel(channelName, player);
        logger.info("Player {} joined channel {}", player.toString(), channelName);
        return true;
    }

    @ClientPacket(ClientPackets.CHANNEL_PART)
    public boolean leaveChannel(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        String channelName = reader.readString();

        if (List.of("#multiplayer", "#spectator").contains(channelName)) {
            Channel channel = resolveChannel(player, channelName);
            if(channel != null) {
                App.server.channelManager.leaveChannel(channel.getName(), player);
            }
            return true;
        }

        Server server = App.server;
        Channel channel = server.channelManager.get(channelName);
        if (channel == null) {
            logger.warn("Player {} tried to leave a not existing channel {}", player.toString(), channelName);
            return true;
        }

        server.channelManager.leaveChannel(channel.getName(), player);
        logger.info("Player {} left channel {}", player.toString(), channel.getName());
        return true;
    }

    @ClientPacket(ClientPackets.SEND_PUBLIC_MESSAGE)
    public boolean sendPublicMessage(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
       reader.readString(); // senderName
        String message = reader.readString();
        String target = reader.readString();

        Channel channel = resolveChannel(player, target);
        if (channel == null) {
            logger.warn("Player {} sent a message to a non-existing channel {}", player, target);
            return true;
        }

        if(!player.canChat()) {
            return true;
        }

        // A player may only speak in channels they actually joined. Without this
        // any channel could be written to by name, including match channels of
        // password-protected rooms and staff-only channels.
        if (!channel.getPlayers().contains(player)) {
            logger.warn("Player {} tried to write to channel {} without being a member", player.toString(),
                    channel.getName());
            return true;
        }

        if (channel.getWritePriv() > player.getServerPrivileges()) {
            logger.warn("Player {} tried to write to channel {} without sufficient privileges", player.toString(),
                    channel.getName());
            return true;
        }

        channel.getPlayers().forEach(user -> {
            if(user.isBot())
                return;
            if(user.getId() == player.getId())
                return; // Don't send the message back to the sender
            user.sendPacket(new SendMessagePacket(player.getUsername(), message, target, player.getId()));
        });

        Commands.processNp(player, message);
        Commands.processCommand(player, message, target, channel.getPlayers());

        logger.info("Message from {}: <{}> to <{}>", player.toString(), message, channel.getName());
        return true;
    }

    @ClientPacket(ClientPackets.SEND_PRIVATE_MESSAGE)
    public boolean sendPrivateMessage(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        reader.readString(); // senderName
        String message = reader.readString();
        String target = reader.readString();

        logger.info("Private Message from <{}>: <{}> to <{}>", player.getUsername(), message, target);

        Player targetPlayer = App.server.playerManager.getByFilter(p -> p.getUsername().equalsIgnoreCase(target));

        if (targetPlayer == null) {
            logger.warn("Target player not found for private message: " + target);
            return true;
        }

        if(!targetPlayer.canChat()) {
            String silenceOrRestricted = targetPlayer.isSilenced() ? "silenced" : "restricted";
            String silenceMessage = String.format("%s has been %s and is unable to respond to your messages right now.", targetPlayer.getUsername(), silenceOrRestricted);
            player.sendPacket(new TargetIsSilencedPacket(player.getUsername(), silenceMessage, target, player.getId()));
            return true;
        }

        if(targetPlayer.getBlocks().contains(player.getId())) {
            String blockMessage = String.format("%s is currently blocking private messages from people not on their friends list.", targetPlayer.getUsername());
            player.sendPacket(new UserDmBlockedPacket(player.getUsername(), blockMessage, target, player.getId()));
            return true;
        }

        targetPlayer.sendPacket(new SendMessagePacket(player.getUsername(), message, target, player.getId()));

        Commands.processNp(player, message);
        Commands.processCommand(player, message, target, Set.of(player));
        return true;
    }

    public Channel resolveChannel(Player player, String target) {
        Server server = App.server;

        if ("#spectator".equals(target)) {
            if(player.getSpectators().size() > 0) {
                return server.channelManager.get("#spec_" + player.getId());
            }
            if (player.getSpectating() != null) {
                return server.channelManager.get("#spec_" + player.getSpectating().getId());
            }

            logger.warn("Player is not spectating anyone but sent a message to #spectator");
        }

        if ("#multiplayer".equals(target)) {
            if (player.getMatch() == null) {
                logger.warn("Player is not in a match but sent a message to #multiplayer");
                return null;
            }

            return server.channelManager.get("#multi_" + player.getMatch().getMatchId());
        }

        // Match and spectator channels are only addressable through the aliases
        // handled above, which verify the match / spectating state first.
        if (isInternalChannelName(target)) {
            logger.warn("Player {} tried to address internal channel {} directly", player.toString(), target);
            return null;
        }

        Channel channel = server.channelManager.get(target);
        if (channel == null) {
            logger.warn("Channel not found for target: {}", target);
        }

        return channel;
    }

    /**
     * Internal channel keys that clients must not address directly.
     */
    private static boolean isInternalChannelName(String channelName) {
        if (channelName == null) {
            return false;
        }

        String lower = channelName.toLowerCase(Locale.ROOT);
        return lower.startsWith("#multi_") || lower.startsWith("#mp_") || lower.startsWith("#spec_");
    }

}
