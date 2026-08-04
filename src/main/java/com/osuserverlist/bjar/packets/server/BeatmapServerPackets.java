package com.osuserverlist.bjar.packets.server;

import java.util.List;

import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.packets.BanchoPacketWriter;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.PacketHandler;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacket;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacketHandler;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPackets;

import lombok.Value;

/**
 * Answers for the in-client beatmap information panel.
 *
 * <p>The client asks about the maps in its Songs folder over bancho (not over
 * the web routes) whenever the information panel is opened. Every entry it gets
 * back paints one row: the ranked status of the map and the grade the player
 * holds in each of the four vanilla modes.</p>
 */
public class BeatmapServerPackets {

    /**
     * Grade letters as the client numbers them. {@link #NONE} means the player
     * has never passed the map in that mode.
     */
    public enum ClientRank {
        XH(0),
        SH(1),
        X(2),
        S(3),
        A(4),
        B(5),
        C(6),
        D(7),
        F(8),
        NONE(9);

        public final int value;

        ClientRank(int value) {
            this.value = value;
        }

        /** Maps a stored grade string ({@code "XH"}, {@code "S"}, ...) to its client value. */
        public static int fromGrade(String grade) {
            if (grade == null || grade.isBlank()) {
                return NONE.value;
            }

            for (ClientRank rank : values()) {
                if (rank.name().equalsIgnoreCase(grade.trim())) {
                    return rank.value;
                }
            }

            // "SS" is the same thing as "X" in older score rows.
            if (grade.equalsIgnoreCase("SS")) {
                return X.value;
            }

            if (grade.equalsIgnoreCase("SSH")) {
                return XH.value;
            }

            return NONE.value;
        }
    }

    /** One row of the panel. */
    @Value
    public static class BeatmapInfo {
        /** Position in the request, so the client can match answers to questions. */
        int index;
        int beatmapId;
        int setId;
        int threadId;
        int status;
        int osuRank;
        int taikoRank;
        int fruitsRank;
        int maniaRank;
        String md5;
    }

    @Value
    public static class BeatmapInfoReplyPacket implements ServerPacket {
        List<BeatmapInfo> beatmaps;
    }

    @PacketHandler(BeatmapInfoReplyPacket.class)
    public static final class BeatmapInfoReplyHandler implements ServerPacketHandler<BeatmapInfoReplyPacket> {
        @Override
        public void write(BeatmapInfoReplyPacket packet, BanchoPacketWriter writer, Player player) {
            List<BeatmapInfo> beatmaps = packet.getBeatmaps();

            writer.startPacket(ServerPackets.BEATMAP_INFO_REPLY);
            writer.writeInt(beatmaps.size());

            for (BeatmapInfo info : beatmaps) {
                writer.writeShort(info.getIndex());
                writer.writeInt(info.getBeatmapId());
                writer.writeInt(info.getSetId());
                writer.writeInt(info.getThreadId());
                writer.writeByte(info.getStatus());
                writer.writeByte(info.getOsuRank());
                writer.writeByte(info.getTaikoRank());
                writer.writeByte(info.getFruitsRank());
                writer.writeByte(info.getManiaRank());
                writer.writeString(info.getMd5());
            }

            writer.endPacket();
        }
    }
}
