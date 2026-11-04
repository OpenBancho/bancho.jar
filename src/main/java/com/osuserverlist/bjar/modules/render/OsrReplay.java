package com.osuserverlist.bjar.modules.render;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.tukaani.xz.LZMAInputStream;

/**
 * A parsed {@code .osr} file: the header of the play and its cursor / key frames.
 *
 * <p>Frame times are absolute, in beatmap milliseconds (the replay already stores them that way even with DT/HT).
 */
public final class OsrReplay {

    public static final int KEY_1 = 1;
    public static final int KEY_2 = 2;

    /** One sample of the cursor. {@code keys} has {@link #KEY_1} and {@link #KEY_2} (M1/K1 and M2/K2 merged). */
    /**
     * {@code raw} is the key byte as stored: 1 = M1, 2 = M2, 4 = K1, 8 = K2 (a keyboard press also sets the mouse bit
     * of its side).
     */
    public record Frame(int time, float x, float y, int keys, int raw) {
    }

    public int mode;
    public int version;
    public String mapMd5 = "";
    public String player = "";
    public int n300, n100, n50, nGeki, nKatu, nMiss;
    public int score;
    public int maxCombo;
    public boolean perfect;
    public int mods;
    public List<Frame> frames = new ArrayList<>();

    private OsrReplay() {
    }

    /**
     * bancho.jar stores what the game client uploads: the bare LZMA frame stream, not a complete .osr (a complete
     * file starts with a mode byte 0-3, the raw stream with the LZMA properties byte 0x5D). The header facts then
     * come from the score row, passed in here.
     */
    public static OsrReplay parseStored(byte[] data, int mods) throws IOException {
        if (data.length > 0 && (data[0] & 0xFF) != 0x5D) {
            return parse(data);
        }

        OsrReplay r = new OsrReplay();
        r.mods = mods;
        r.frames = decodeFrames(unpack(data));
        return r;
    }

    public static OsrReplay parse(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        OsrReplay r = new OsrReplay();

        r.mode = in.readUnsignedByte();
        r.version = Integer.reverseBytes(in.readInt());
        r.mapMd5 = readString(in);
        r.player = readString(in);
        readString(in); // replay md5
        r.n300 = Short.reverseBytes(in.readShort()) & 0xFFFF;
        r.n100 = Short.reverseBytes(in.readShort()) & 0xFFFF;
        r.n50 = Short.reverseBytes(in.readShort()) & 0xFFFF;
        r.nGeki = Short.reverseBytes(in.readShort()) & 0xFFFF;
        r.nKatu = Short.reverseBytes(in.readShort()) & 0xFFFF;
        r.nMiss = Short.reverseBytes(in.readShort()) & 0xFFFF;
        r.score = Integer.reverseBytes(in.readInt());
        r.maxCombo = Short.reverseBytes(in.readShort()) & 0xFFFF;
        r.perfect = in.readUnsignedByte() != 0;
        r.mods = Integer.reverseBytes(in.readInt());
        readString(in); // life graph
        in.readLong(); // windows ticks
        int length = Integer.reverseBytes(in.readInt());

        if (length <= 0 || length > in.available()) {
            throw new IOException("Replay has no frame data");
        }

        byte[] packed = new byte[length];
        in.readFully(packed);

        r.frames = decodeFrames(unpack(packed));
        return r;
    }

    // LZMA as osu! writes it: properties (5), 8 byte size, stream. The size may be -1, which LZMAInputStream accepts.
    private static byte[] unpack(byte[] packed) throws IOException {
        try (LZMAInputStream lzma = new LZMAInputStream(new ByteArrayInputStream(packed))) {
            return lzma.readAllBytes();
        }
    }

    private static List<Frame> decodeFrames(byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        List<Frame> out = new ArrayList<>();
        int time = 0;

        for (String part : text.split(",")) {
            String[] f = part.split("\\|");

            if (f.length < 4) {
                continue;
            }

            try {
                int delta = Integer.parseInt(f[0]);

                // The trailing "-12345|0|0|seed" entry is the RNG seed, not a frame.
                if (delta == -12345) {
                    continue;
                }

                time += delta;
                float x = Float.parseFloat(f[1]);
                float y = Float.parseFloat(f[2]);
                int z = (int) Float.parseFloat(f[3]);

                int keys = 0;

                if ((z & 5) != 0) {
                    keys |= KEY_1;
                }

                if ((z & 10) != 0) {
                    keys |= KEY_2;
                }

                // The two leading frames sit at time 0 / -1 with a bogus position; drop them.
                if (out.isEmpty() && time <= 0 && x == 256 && y == -500) {
                    continue;
                }

                out.add(new Frame(time, x, y, keys, z));
            } catch (NumberFormatException ignored) {
                // a damaged entry is skipped, the rest of the replay is still usable
            }
        }

        return out;
    }

    private static String readString(DataInputStream in) throws IOException {
        int marker = in.readUnsignedByte();

        if (marker == 0) {
            return "";
        }

        int len = 0;
        int shift = 0;
        int b;

        do {
            b = in.readUnsignedByte();
            len |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);

        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
