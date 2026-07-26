package com.osuserverlist.bjar.modules.packets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.models.essentials.Match;
import com.osuserverlist.bjar.models.essentials.MatchSlot;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.match.MatchScoringType;
import com.osuserverlist.bjar.models.osu.match.MatchSpecialMode;
import com.osuserverlist.bjar.models.osu.match.MatchTeamType;
import com.osuserverlist.bjar.models.osu.match.MatchType;
import com.osuserverlist.bjar.models.osu.replay.ReplayAction;
import com.osuserverlist.bjar.models.osu.replay.ReplayFrame;
import com.osuserverlist.bjar.models.osu.replay.ReplayFrameBundle;
import com.osuserverlist.bjar.models.osu.replay.ScoreFrame;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.BanchoPacketHandler;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.ClientPackets;
import com.osuserverlist.bjar.packets.BanchoPacket;

public class BanchoPacketReader {

    private static final Logger logger = LoggerFactory.getLogger(BanchoPacketReader.class);

    private final ByteArrayInputStream data;
    private final Player player;

    private final List<Integer> packetIds = new ArrayList<>();

    private ByteArrayInputStream packetData;
    private byte[] currentPacketBody;

    private int currentPacketId;
    private int currentPacketLength;
    private boolean compressionFlag;

    public BanchoPacketReader(byte[] packetData, Player player) {
        this.data = new ByteArrayInputStream(packetData);
        this.player = player;
    }

    public boolean hasMorePackets() {
        return data.available() >= 7;
    }

    public boolean nextPacket() throws IOException {
        if (!hasMorePackets()) {
            return false;
        }

        int idLow = data.read() & 0xFF;
        int idHigh = data.read() & 0xFF;
        currentPacketId = idLow | (idHigh << 8);

        compressionFlag = (data.read() & 0xFF) != 0;

        int b0 = data.read() & 0xFF;
        int b1 = data.read() & 0xFF;
        int b2 = data.read() & 0xFF;
        int b3 = data.read() & 0xFF;

        currentPacketLength = b0 |
                (b1 << 8) |
                (b2 << 16) |
                (b3 << 24);

        if (currentPacketLength < 0 || currentPacketLength > 100000) {
            logger.error("Invalid packet length {}", currentPacketLength);
            return false;
        }

        currentPacketBody = new byte[currentPacketLength];

        if (currentPacketLength > 0) {
            int totalRead = 0;

            while (totalRead < currentPacketLength) {
                int read = data.read(
                        currentPacketBody,
                        totalRead,
                        currentPacketLength - totalRead);

                if (read == -1) {
                    logger.error(
                            "Unexpected EOF while reading packet body. Expected {} bytes, got {}.",
                            currentPacketLength,
                            totalRead);
                    return false;
                }

                totalRead += read;
            }
        }

        packetData = new ByteArrayInputStream(currentPacketBody);

        packetIds.add(currentPacketId);

        ClientPackets clientPacket = ClientPackets.getById(currentPacketId);

        if (logger.isDebugEnabled()) {
            logger.debug(
                    "Reading Packet: ID=({}) NAME=<{}>, Length=({}), Compressed={}",
                    currentPacketId,
                    ClientPackets.getById(currentPacketId).name(),
                    currentPacketLength,
                    compressionFlag);
        }

        BanchoPacketHandler handler = ClientPacketEngine.packetHandlers.get(clientPacket);

        if (handler == null) {
            handler = ClientPacketEngine.packetHandlers.get(ClientPackets.UNHANDLED_PACKET);
        }

        BanchoPacket packet = new BanchoPacket(
                currentPacketId,
                compressionFlag,
                clientPacket);

        return handler.handle(packet, this, player);
    }

    public byte[] getCurrentPacketBody() {
        return currentPacketBody;
    }

    public int getPacketPosition() {
        return currentPacketLength - packetData.available();
    }

    public int remainingInPacket() {
        return packetData.available();
    }

    public byte readByte() {
        return (byte) packetData.read();
    }

    public int readUnsignedByte() {
        return packetData.read() & 0xFF;
    }

    public short readShort() {
        int low = packetData.read() & 0xFF;
        int high = packetData.read() & 0xFF;

        return (short) (low | (high << 8));
    }

    public int readUnsignedShort() {
        int low = packetData.read() & 0xFF;
        int high = packetData.read() & 0xFF;

        return low | (high << 8);
    }

    public int readInt() {
        return (packetData.read() & 0xFF) |
                ((packetData.read() & 0xFF) << 8) |
                ((packetData.read() & 0xFF) << 16) |
                ((packetData.read() & 0xFF) << 24);
    }

    public long readLong() {
        return ((long) (packetData.read() & 0xFF)) |
                (((long) (packetData.read() & 0xFF)) << 8) |
                (((long) (packetData.read() & 0xFF)) << 16) |
                (((long) (packetData.read() & 0xFF)) << 24) |
                (((long) (packetData.read() & 0xFF)) << 32) |
                (((long) (packetData.read() & 0xFF)) << 40) |
                (((long) (packetData.read() & 0xFF)) << 48) |
                (((long) (packetData.read() & 0xFF)) << 56);
    }

    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    public boolean readBoolean() {
        return readByte() != 0;
    }

    public String readString() throws IOException {
        int indicator = readUnsignedByte();

        if (indicator == 0x00) {
            return "";
        }

        if (indicator != 0x0B) {
            logger.warn("Unexpected string indicator: {}", indicator);
            return "";
        }

        int length = readUleb128();

        byte[] stringBytes = new byte[length];

        int read = packetData.read(stringBytes);

        if (read != length) {
            throw new IOException(
                    "Failed to read complete string. Expected "
                            + length
                            + " bytes, got "
                            + read);
        }

        return new String(stringBytes, StandardCharsets.UTF_8);
    }

    private int readUleb128() {
        int value = 0;
        int shift = 0;

        while (true) {
            int b = readUnsignedByte();

            value |= (b & 0x7F) << shift;

            if ((b & 0x80) == 0) {
                break;
            }

            shift += 7;
        }

        return value;
    }

    public List<Integer> readIntList() {
        int length = readUnsignedShort();

        List<Integer> values = new ArrayList<>(length);

        for (int i = 0; i < length; i++) {
            values.add(readInt());
        }

        return values;
    }

    public ReplayFrameBundle readReplayFrameBundle() {
        ReplayFrameBundle bundle = new ReplayFrameBundle();

        bundle.setRawData(currentPacketBody);

        bundle.setExtra(readInt());

        int frameCount = readUnsignedShort();

        List<ReplayFrame> frames = new ArrayList<>(frameCount);

        for (int i = 0; i < frameCount; i++) {
            frames.add(readReplayFrame());
        }

        bundle.setFrames(frames);

        bundle.setAction(
                ReplayAction.fromId(readUnsignedByte()));

        bundle.setScoreFrame(readScoreFrame());

        bundle.setSequence(readUnsignedShort());

        return bundle;
    }

    public ScoreFrame readScoreFrame() {
        ScoreFrame sf = new ScoreFrame();

        sf.setTime(readInt());
        sf.setId(readUnsignedByte());

        sf.setNum300(readUnsignedShort());
        sf.setNum100(readUnsignedShort());
        sf.setNum50(readUnsignedShort());

        sf.setNumGeki(readUnsignedShort());
        sf.setNumKatu(readUnsignedShort());
        sf.setNumMiss(readUnsignedShort());

        sf.setTotalScore(readInt());

        sf.setMaxCombo(readUnsignedShort());
        sf.setCurrentCombo(readUnsignedShort());

        sf.setPerfect(readBoolean());

        sf.setHp(readUnsignedByte());
        sf.setTagByte(readUnsignedByte());

        sf.setScoreVersion2(readBoolean());

        if (sf.isScoreVersion2()) {
            sf.setComboPortion(readDouble());
            sf.setBonusPortion(readDouble());
        }

        return sf;
    }

    public ReplayFrame readReplayFrame() {
        ReplayFrame frame = new ReplayFrame();

        // read_u8
        frame.setButtonState(readUnsignedByte());
        frame.setTaikoByte(readUnsignedByte());
        frame.setX(readFloat());
        frame.setY(readFloat());
        frame.setTime(readInt());
        return frame;
    }

    public Match readMatch() throws IOException {
        Match match = new Match();

        match.setMatchId(readShort());
        match.setInProgress(readBoolean());
        match.setMatchType(MatchType.byByte(readByte()));
        match.setMods(readInt());

        match.setRoomName(readString());
        match.setRoomPassword(readString());
        match.setBeatmapName(readString());
        match.setBeatmapId(readInt());
        match.setBeatmapChecksum(readString());

        for (int i = 0; i < Match.MAX_SLOTS; i++) {
            match.getSlots()[i] = new MatchSlot();
            match.getSlots()[i].setStatus(readByte());
        }

        for (int i = 0; i < Match.MAX_SLOTS; i++) {
            match.getSlots()[i].setTeam(readByte());
        }

        // Read player IDs only for occupied slots
        for (int i = 0; i < Match.MAX_SLOTS; i++) {
            if ((match.getSlots()[i].getStatus() & 0x7C) > 0) {
                match.getSlots()[i].setPlayerId(readInt());
            }
        }

        match.setHostId(readInt());

        match.setMode(readByte());
        match.setScoringType(MatchScoringType.byByte(readByte()));
        match.setTeamType(MatchTeamType.byByte(readByte()));
        match.setSpecialMode(MatchSpecialMode.byByte(readByte()));

        if (match.getSpecialMode() == MatchSpecialMode.FREE_MOD) {
            for (int i = 0; i < Match.MAX_SLOTS; i++) {
                match.getSlots()[i].setMods(readInt());
            }
        }

        match.setSeed(readInt());

        return match;
    }

    public void skip(int count) {
        packetData.skip(count);
    }

    public int available() {
        return packetData != null
                ? packetData.available()
                : 0;
    }

    public int getCurrentPacketId() {
        return currentPacketId;
    }

    public int getCurrentPacketLength() {
        return currentPacketLength;
    }

    public boolean isCompressed() {
        return compressionFlag;
    }

    public List<Integer> getPacketIds() {
        return packetIds;
    }
}
