package com.osuserverlist.bjar.modules.packets;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.models.essentials.Match;
import com.osuserverlist.bjar.models.osu.match.MatchSpecialMode;
import com.osuserverlist.bjar.models.osu.replay.ScoreFrame;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPackets;

public class BanchoPacketWriter {
    private static final Logger logger = LoggerFactory.getLogger(BanchoPacketWriter.class);

    private final List<byte[]> packets = new ArrayList<>();
    private ByteArrayOutputStream packetBuffer;
    private ByteArrayOutputStream output;

    public void startPacket(int id) {
        packetBuffer = new ByteArrayOutputStream();

        // Write packet ID (2 bytes, little-endian)
        packetBuffer.write(id & 0xFF); // Low byte first
        packetBuffer.write((id >> 8) & 0xFF); // High byte next

        // Write compression flag (1 byte, always 0 for modern clients)
        packetBuffer.write(0);

        // We'll write the payload length after we know what it is
        output = new ByteArrayOutputStream(); // This will hold our payload
    }

    public void startPacket(ServerPackets packetType) {
        startPacket(packetType.value);
    }

    public void endPacket() {
        byte[] payload = output.toByteArray();
        int length = payload.length;

        // Continue writing directly into the header buffer instead of copying
        // it into a separate stream: length field first...
        packetBuffer.write(length & 0xFF); // Least significant byte first
        packetBuffer.write((length >> 8) & 0xFF);
        packetBuffer.write((length >> 16) & 0xFF);
        packetBuffer.write((length >> 24) & 0xFF); // Most significant byte last

        // ...then the payload itself.
        packetBuffer.write(payload, 0, payload.length);

        byte[] finalPacketBytes = packetBuffer.toByteArray();

        if (logger.isDebugEnabled()) {
            // Log packet details
            StringBuilder sb = new StringBuilder();
            int packetId = (finalPacketBytes[0] & 0xFF) | ((finalPacketBytes[1] & 0xFF) << 8);

            sb.append("Writing Packet: ID=(").append(packetId).append(") NAME=<")
                    .append(ServerPackets.getNameById(packetId));
            sb.append(">, Length=(").append(length);
            sb.append("), HEX: <");
            for (byte b : finalPacketBytes) {
                sb.append(String.format("%02X ", b));
            }
            sb.delete(sb.length() - 1, sb.length());
            sb.append(">");
            logger.debug(sb.toString());
        }

        // Add the complete packet to the list
        packets.add(finalPacketBytes);
    }

    public byte[] getPackets() {
        int totalLength = 0;
        for (byte[] packet : packets) {
            totalLength += packet.length;
        }

        ByteArrayOutputStream all = new ByteArrayOutputStream(totalLength);
        for (byte[] packet : packets) {
            all.write(packet, 0, packet.length);
        }
        return all.toByteArray();
    }

    public void writeByte(int value) {
        output.write(value & 0xFF);
    }

    public void writeShort(int value) {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
    }

    public void writeInt(int value) {
        // Little-endian: Low byte first, then high bytes
        output.write(value & 0xFF); // Low byte first
        output.write((value >> 8) & 0xFF);
        output.write((value >> 16) & 0xFF);
        output.write((value >> 24) & 0xFF); // Most significant byte last
    }

    public void writeLong(long value) {
        output.write((int) (value & 0xFF));
        output.write((int) ((value >> 8) & 0xFF));
        output.write((int) ((value >> 16) & 0xFF));
        output.write((int) ((value >> 24) & 0xFF));
        output.write((int) ((value >> 32) & 0xFF));
        output.write((int) ((value >> 40) & 0xFF));
        output.write((int) ((value >> 48) & 0xFF));
        output.write((int) ((value >> 56) & 0xFF));
    }

    public void writeFloat(float value) {
        writeInt(Float.floatToRawIntBits(value));
    }

    public void writeBytes(byte[] data) {
        try {
            output.write(data);
        } catch (IOException e) {
            logger.error("Error writing bytes: ", e);
        }
    }

    /**
     * Writes a boolean value to the output stream. The boolean is represented as a single byte: 1 for true and 0 for false.
     *
     * @param value The boolean to write.
     */
    public void writeBoolean(boolean value) {
        writeByte(value ? 1 : 0);
    }

    /**
     * Writes a double to the output stream. The double is converted to its raw long bits representation and written as 8 bytes in little-endian order.
     *
     * @param value The double to write.
     */
    public void writeDouble(double value) {
        writeLong(Double.doubleToRawLongBits(value));
    }

    /**
     * Writes a string to the output stream. The string is prefixed with its length as an unsigned LEB128 integer.
     *
     * @param str The string to write. If null or empty, a length of 0 is written.
     */
    public void writeString(String str) {
        if (str == null || str.isEmpty()) {
            writeByte(0x00); // Empty/null string indicator
            return;
        }

        writeByte(0x0b); // String present indicator

        // Get UTF-8 bytes of the string
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);

        // Write the length as ULEB128
        writeUleb128(bytes.length);

        // Write the actual string bytes
        try {
            output.write(bytes);
        } catch (IOException e) {
            logger.error("Error writing string: ", e);
        }
    }

    /**
     * Writes a list of integers to the output stream. The list is prefixed with its length as an unsigned short (2 bytes, little-endian).
     *
     * @param values The list of integers to write. If null, a length of 0 is written.
     */
    public void writeIntList(List<Integer> values) {
        if (values == null) {
            writeShort((short) 0);
            return;
        }

        // Length (uShort)
        writeShort((short) values.size());

        // Integers
        for (int value : values) {
            writeInt(value);
        }
    }

    /**
     * Writes a osu! Match object to the output stream. Match objects represent the state of a multiplayer match, including player slots, host information, and match settings.
     *
     * @param match The Match object containing the match data to write.
     */
    public void writeMatch(Match match) {
        writeShort(match.getMatchId());
        writeBoolean(match.isInProgress());
        writeByte(match.getMatchType().value);
        writeInt(match.getMods());

        writeString(match.getRoomName());
        writeString(match.getRoomPassword());
        writeString(match.getBeatmapName());
        writeInt(match.getBeatmapId());
        writeString(match.getBeatmapChecksum());

        // Slot statuses
        for (int i = 0; i < Match.MAX_SLOTS; i++) {
            writeByte(match.getSlots()[i].getStatus());
        }

        // Slot teams
        for (int i = 0; i < Match.MAX_SLOTS; i++) {
            writeByte(match.getSlots()[i].getTeam());
        }

        // Player IDs (only for occupied slots)
        for (int i = 0; i < Match.MAX_SLOTS; i++) {
            if ((match.getSlots()[i].getStatus() & 0x7C) > 0) {
                writeInt(match.getSlots()[i].getPlayerId());
            }
        }

        writeInt(match.getHostId());

        writeByte(match.getMode());
        writeByte(match.getScoringType().value);
        writeByte(match.getTeamType().value);
        writeByte(match.getSpecialMode().value);

        // Slot mods are only present in FreeMod
        if (match.getSpecialMode() == MatchSpecialMode.FREE_MOD) {
            for (int i = 0; i < Match.MAX_SLOTS; i++) {
                writeInt(match.getSlots()[i].getMods());
            }
        }

        writeInt(match.getSeed());
    }

    /**
     * Writes a osu! Score Frame to the output stream. ScoreFrames are used in replays and spectating to represent the state of a player's score at a specific point in time.
     *
     * @param scoreFrame The ScoreFrame object containing the score data to write.
     */
    public void writeScoreFrame(ScoreFrame scoreFrame) throws IOException {
        writeInt(scoreFrame.getTime());
        writeByte(scoreFrame.getId());
        writeShort(scoreFrame.getNum300());
        writeShort(scoreFrame.getNum100());
        writeShort(scoreFrame.getNum50());
        writeShort(scoreFrame.getNumGeki());
        writeShort(scoreFrame.getNumKatu());
        writeShort(scoreFrame.getNumMiss());
        writeInt(scoreFrame.getTotalScore());
        writeShort(scoreFrame.getMaxCombo());
        writeShort(scoreFrame.getCurrentCombo());

        writeBoolean(scoreFrame.isPerfect());

        writeByte(scoreFrame.getHp());
        writeByte(scoreFrame.getTagByte());

        writeBoolean(scoreFrame.isScoreVersion2());

        if(scoreFrame.isScoreVersion2()) {
            writeDouble(scoreFrame.getComboPortion());
            writeDouble(scoreFrame.getBonusPortion());
        }
    }

    /**
     * Writes an unsigned LEB128 encoded integer.
     * ULEB128 is a variable-length encoding for unsigned integers.
     *
     * @param value The value to encode
     */
    private void writeUleb128(int value) {
        do {
            byte b = (byte) (value & 0x7F);
            value >>= 7;
            if (value != 0) {
                b |= 0x80; // Set the continuation bit
            }
            writeByte(b);
        } while (value != 0);
    }

}
