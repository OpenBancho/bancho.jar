package com.osuserverlist.bjar.packets.server;

import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.packets.BanchoPacketWriter;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.PacketHandler;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacket;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacketHandler;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPackets;

import lombok.Value;

public class SpectateServerPackets {

    @Value
    public static class CantSpectatePacket implements ServerPacket {
        private int id;
    }

    @Value
    public static class FellowSpectatorJoinedPacket implements ServerPacket {
        private int id;
    }

    @Value
    public static class FellowSpectatorLeftPacket implements ServerPacket {
        private int id;
    }

    @Value
    public static class SpectateFramesPacket implements ServerPacket {
        private byte[] data;
    }

    @Value
    public static class SpectatorJoinedPacket implements ServerPacket {
        private int id;
    }

    @Value
    public static class SpectatorLeftPacket implements ServerPacket {
        private int id;
    }

    @PacketHandler(CantSpectatePacket.class)
    public static final class CantSpectateHandler implements ServerPacketHandler<CantSpectatePacket> {
        @Override
        public void write(CantSpectatePacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.SPECTATOR_CANT_SPECTATE);
            writer.writeInt(packet.getId());
            writer.endPacket();
        }
    }

    @PacketHandler(FellowSpectatorJoinedPacket.class)
    public static final class FellowSpectatorJoinedHandler implements ServerPacketHandler<FellowSpectatorJoinedPacket> {
        @Override
        public void write(FellowSpectatorJoinedPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.FELLOW_SPECTATOR_JOINED);
            writer.writeInt(packet.getId());
            writer.endPacket();
        }
    }

    @PacketHandler(FellowSpectatorLeftPacket.class)
    public static final class FellowSpectatorLeftHandler implements ServerPacketHandler<FellowSpectatorLeftPacket> {
        @Override
        public void write(FellowSpectatorLeftPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.FELLOW_SPECTATOR_LEFT);
            writer.writeInt(packet.getId());
            writer.endPacket();
        }
    }

    @PacketHandler(SpectateFramesPacket.class)
    public static final class SpectateFramesHandler implements ServerPacketHandler<SpectateFramesPacket> {
        @Override
        public void write(SpectateFramesPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.SPECTATE_FRAMES);
            writer.writeBytes(packet.getData());
            writer.endPacket();
        }
    }

    @PacketHandler(SpectatorJoinedPacket.class)
    public static final class SpectatorJoinedHandler implements ServerPacketHandler<SpectatorJoinedPacket> {
        @Override
        public void write(SpectatorJoinedPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.SPECTATOR_JOINED);
            writer.writeInt(packet.getId());
            writer.endPacket();
        }
    }

    @PacketHandler(SpectatorLeftPacket.class)
    public static final class SpectatorLeftHandler implements ServerPacketHandler<SpectatorLeftPacket> {
        @Override
        public void write(SpectatorLeftPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.SPECTATOR_LEFT);
            writer.writeInt(packet.getId());
            writer.endPacket();
        }
    }
}
