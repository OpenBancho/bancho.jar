package com.osuserverlist.bjar.packets.server;

import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.packets.BanchoPacketWriter;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.PacketHandler;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacket;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacketHandler;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPackets;

import lombok.Value;

public class ChatServerPackets {

    @Value
    public static class ChannelAutojoinPacket implements ServerPacket {
        private String channel;
    }

    @Value
    public static class ChannelInfoPacket implements ServerPacket {
        private String channelName;
        private String channelDescription;
        private int userCount;
    }

    @Value
    public static class ChannelJoinSuccessPacket implements ServerPacket {
        private String channelName;
    }

    @Value
    public static class ChannelRevokedPacket implements ServerPacket {
        private String channelName;
    }

    @Value
    public static class ChannelInfoEndPacket implements ServerPacket { }

    @Value
    public static class SendMessagePacket implements ServerPacket {
        private String senderName;
        private String message;
        private String target;
        private int senderId;
    }

    @Value
    public static class TargetIsSilencedPacket implements ServerPacket {
        private String senderName;
        private String message;
        private String target;
        private int senderId;
    }

    @Value
    public static class UserDmBlockedPacket implements ServerPacket {
        private String senderName;
        private String message;
        private String target;
        private int senderId;
    }

    @PacketHandler(ChannelAutojoinPacket.class)
    public static final class ChannelAutojoinHandler implements ServerPacketHandler<ChannelAutojoinPacket> {
        @Override
        public void write(ChannelAutojoinPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.CHANNEL_AUTO_JOIN);
            writer.writeString(packet.getChannel());
            writer.endPacket();
        }
    }

    @PacketHandler(ChannelInfoPacket.class)
    public static final class ChannelInfoHandler implements ServerPacketHandler<ChannelInfoPacket> {
        @Override
        public void write(ChannelInfoPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.CHANNEL_INFO);
            writer.writeString(packet.getChannelName());
            writer.writeString(packet.getChannelDescription());
            writer.writeShort(packet.getUserCount());
            writer.endPacket();
        }
    }

    @PacketHandler(ChannelJoinSuccessPacket.class)
    public static final class ChannelJoinSuccessHandler implements ServerPacketHandler<ChannelJoinSuccessPacket> {
        @Override
        public void write(ChannelJoinSuccessPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.CHANNEL_JOIN_SUCCESS);
            writer.writeString(packet.getChannelName());
            writer.endPacket();
        }
    }

    @PacketHandler(ChannelRevokedPacket.class)
    public static final class ChannelRevokedHandler implements ServerPacketHandler<ChannelRevokedPacket> {
        @Override
        public void write(ChannelRevokedPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.CHANNEL_KICK);
            writer.writeString(packet.getChannelName());
            writer.endPacket();
        }
    }

    @PacketHandler(ChannelInfoEndPacket.class)
    public static final class ChannelInfoEndHandler implements ServerPacketHandler<ChannelInfoEndPacket> {
        @Override
        public void write(ChannelInfoEndPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.CHANNEL_INFO_END);
            writer.endPacket();
        }
    }

    @PacketHandler(SendMessagePacket.class)
    public static final class SendMessageHandler implements ServerPacketHandler<SendMessagePacket> {
        @Override
        public void write(SendMessagePacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.SEND_MESSAGE);
            writer.writeString(packet.getSenderName());
            writer.writeString(packet.getMessage());
            writer.writeString(packet.getTarget());
            writer.writeInt(packet.getSenderId());
            writer.endPacket();
        }
    }

    @PacketHandler(TargetIsSilencedPacket.class)
    public static final class TargetIsSilencedHandler implements ServerPacketHandler<TargetIsSilencedPacket> {
        @Override
        public void write(TargetIsSilencedPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.TARGET_IS_SILENCED);
            writer.writeString(packet.getSenderName());
            writer.writeString(packet.getMessage());
            writer.writeString(packet.getTarget());
            writer.writeInt(packet.getSenderId());
            writer.endPacket();
        }
    }

    @PacketHandler(UserDmBlockedPacket.class)
    public static final class UserDmBlockedHandler implements ServerPacketHandler<UserDmBlockedPacket> {
        @Override
        public void write(UserDmBlockedPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.USER_DM_BLOCKED);
            writer.writeString(packet.getSenderName());
            writer.writeString(packet.getMessage());
            writer.writeString(packet.getTarget());
            writer.writeInt(packet.getSenderId());
            writer.endPacket();
        }
    }

}
