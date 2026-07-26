package com.osuserverlist.bjar.packets.server;

import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.packets.BanchoPacketWriter;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.PacketHandler;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacket;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacketHandler;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPackets;

import lombok.Value;

public class UtilServerPackets {
    @Value
    public static class NotificationPacket implements ServerPacket {
        public String message;
    }

    @Value
    public static class RestartPacket implements ServerPacket {
        public int reconnectTime;
    }

    @Value
    public static class GetAttentionPacket implements ServerPacket { }

    @PacketHandler(NotificationPacket.class)
    public static final class NotificationHandler implements ServerPacketHandler<NotificationPacket> {
        @Override
        public void write(NotificationPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.NOTIFICATION);
            writer.writeString(packet.getMessage());
            writer.endPacket();
        }
    }

    @PacketHandler(RestartPacket.class)
    public static final class RestartHandler implements ServerPacketHandler<RestartPacket> {
        @Override
        public void write(RestartPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.RESTART);
            writer.writeInt(packet.getReconnectTime());
            writer.endPacket();
        }
    }

    @PacketHandler(GetAttentionPacket.class)
    public static final class GetAttentionHandler implements ServerPacketHandler<GetAttentionPacket> {
        @Override
        public void write(GetAttentionPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.GET_ATTENTION);
            writer.endPacket();
        }
    }
}
