package com.osuserverlist.bjar.packets.server;

import com.osuserverlist.bjar.models.essentials.Match;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.packets.BanchoPacketWriter;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.PacketHandler;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacket;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacketHandler;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPackets;

import lombok.Value;

public class MultiplayerServerPackets {

    @Value
    public static class NewMatchPacket implements ServerPacket {
        private Match match;
    }

    @Value
    public static class MatchUpdatePacket implements ServerPacket {
        private Match match;
    }

    @Value
    public static class MatchStartClientPacket implements ServerPacket {
        private Match match;
    }

    @Value
    public static class MatchJoinSuccessPacket implements ServerPacket {
        private Match match;
    }

    @Value
    public static class DisposeMatchPacket implements ServerPacket {
        private int matchId;
    }

    @Value
    public static class MatchScoreUpdatePacket implements ServerPacket {
        private byte[] playData;
    }

    @Value
    public static class MatchPlayerFailedPacket implements ServerPacket {
        private int slotId;
    }

    @Value
    public static class MatchJoinFailPacket implements ServerPacket {
    }

    @Value
    public static class MatchTransferHostPacket implements ServerPacket {
    }

    @Value
    public static class MatchAllPlayersLoadedPacket implements ServerPacket {
    }

    @Value
    public static class MatchCompletePacket implements ServerPacket {
    }

    @PacketHandler(NewMatchPacket.class)
    public static final class NewMatchHandler implements ServerPacketHandler<NewMatchPacket> {
        @Override
        public void write(NewMatchPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.NEW_MATCH);
            writer.writeMatch(packet.getMatch());
            writer.endPacket();
        }
    }

    @PacketHandler(MatchUpdatePacket.class)
    public static final class MatchUpdateHandler implements ServerPacketHandler<MatchUpdatePacket> {
        @Override
        public void write(MatchUpdatePacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.UPDATE_MATCH);
            writer.writeMatch(packet.getMatch());
            writer.endPacket();
        }
    }

    @PacketHandler(MatchStartClientPacket.class)
    public static final class MatchStartClientHandler implements ServerPacketHandler<MatchStartClientPacket> {
        @Override
        public void write(MatchStartClientPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.MATCH_START);
            writer.writeMatch(packet.getMatch());
            writer.endPacket();
        }
    }

    @PacketHandler(MatchJoinSuccessPacket.class)
    public static final class MatchJoinSuccessHandler implements ServerPacketHandler<MatchJoinSuccessPacket> {
        @Override
        public void write(MatchJoinSuccessPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.MATCH_JOIN_SUCCESS);
            writer.writeMatch(packet.getMatch());
            writer.endPacket();
        }
    }

    @PacketHandler(DisposeMatchPacket.class)
    public static final class DisposeMatchHandler implements ServerPacketHandler<DisposeMatchPacket> {
        @Override
        public void write(DisposeMatchPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.DISPOSE_MATCH);
            writer.writeInt(packet.getMatchId());
            writer.endPacket();
        }
    }

    @PacketHandler(MatchScoreUpdatePacket.class)
    public static final class MatchScoreUpdateHandler implements ServerPacketHandler<MatchScoreUpdatePacket> {
        @Override
        public void write(MatchScoreUpdatePacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.MATCH_SCORE_UPDATE);
            writer.writeBytes(packet.getPlayData());
            writer.endPacket();
        }
    }

    @PacketHandler(MatchPlayerFailedPacket.class)
    public static final class MatchPlayerFailedHandler implements ServerPacketHandler<MatchPlayerFailedPacket> {
        @Override
        public void write(MatchPlayerFailedPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.MATCH_PLAYER_FAILED);
            writer.writeInt(packet.getSlotId());
            writer.endPacket();
        }
    }

    @PacketHandler(MatchJoinFailPacket.class)
    public static final class MatchJoinFailHandler implements ServerPacketHandler<MatchJoinFailPacket> {
        @Override
        public void write(MatchJoinFailPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.MATCH_JOIN_FAIL);
            writer.endPacket();
        }
    }

    @PacketHandler(MatchTransferHostPacket.class)
    public static final class MatchTransferHostHandler implements ServerPacketHandler<MatchTransferHostPacket> {
        @Override
        public void write(MatchTransferHostPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.MATCH_TRANSFER_HOST);
            writer.endPacket();
        }
    }

    @PacketHandler(MatchAllPlayersLoadedPacket.class)
    public static final class MatchAllPlayersLoadedHandler implements ServerPacketHandler<MatchAllPlayersLoadedPacket> {
        @Override
        public void write(MatchAllPlayersLoadedPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.MATCH_ALL_PLAYERS_LOADED);
            writer.endPacket();
        }
    }

    @PacketHandler(MatchCompletePacket.class)
    public static final class MatchCompleteHandler implements ServerPacketHandler<MatchCompletePacket> {
        @Override
        public void write(MatchCompletePacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.MATCH_COMPLETE);
            writer.endPacket();
        }
    }

}
