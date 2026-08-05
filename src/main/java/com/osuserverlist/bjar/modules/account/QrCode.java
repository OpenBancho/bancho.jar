package com.osuserverlist.bjar.modules.account;

import java.nio.charset.StandardCharsets;

/**
 * A QR code encoder, just enough of one to draw an {@code otpauth://} address.
 *
 * <p>Written out rather than pulled in as a dependency, and computed here rather than in the
 * browser: the first attempt at this loaded a library from a CDN, and a page that cannot reach
 * that CDN cannot show a QR code at all. The server has the string, the server can draw it, and
 * then nothing between the two has to work for the picture to appear.
 *
 * <p>Deliberately narrow. Byte mode only, error correction level M, versions 1 to 10 - which
 * tops out at 213 bytes, several times what an {@code otpauth://} address needs. Anything longer
 * returns {@code null} and the caller falls back to printing the secret, which is a fine answer
 * for a case that should not happen.
 *
 * <p>The output is an SVG: a QR code is squares, so it costs a few hundred bytes as vectors,
 * scales to any size without going fuzzy, and needs no image encoder.
 *
 * <p>Follows ISO/IEC 18004. The structure - function patterns first, then codewords in a zigzag,
 * then the best of the eight masks - is the one the specification describes, and the tables
 * below are its tables.
 */
public final class QrCode {

    /** The largest symbol this will produce; 213 bytes at level M. */
    public static final int MAX_VERSION = 10;

    /** Level M in the format information: two bits, and not the ones you would guess. */
    private static final int ECC_LEVEL_BITS = 0b00;

    /**
     * Error correction structure at level M, indexed by version: the number of error correction
     * codewords per block, then the two groups of blocks as (count, data codewords each).
     *
     * <p>Most versions have one group; the second exists because the data does not always divide
     * evenly, so some blocks carry one codeword more than the others.
     */
    private static final int[][] BLOCKS = {
            {},
            { 10, 1, 16, 0, 0 },
            { 16, 1, 28, 0, 0 },
            { 26, 1, 44, 0, 0 },
            { 18, 2, 32, 0, 0 },
            { 24, 2, 43, 0, 0 },
            { 16, 4, 27, 0, 0 },
            { 18, 4, 31, 0, 0 },
            { 22, 2, 38, 2, 39 },
            { 22, 3, 36, 2, 37 },
            { 26, 4, 43, 1, 44 },
    };

    /** Row and column centres of the alignment patterns, per version. */
    private static final int[][] ALIGNMENT = {
            {},
            {},
            { 6, 18 },
            { 6, 22 },
            { 6, 26 },
            { 6, 30 },
            { 6, 34 },
            { 6, 22, 38 },
            { 6, 24, 42 },
            { 6, 26, 46 },
            { 6, 28, 50 },
    };

    /**
     * The two eleven-module sequences that look like a finder pattern to a scanner. A mask that
     * produces them anywhere is penalised heavily, because a false finder is worse than an ugly
     * code.
     */
    private static final boolean[] FINDER_LIKE_A = {
            true, false, true, true, true, false, true, false, false, false, false
    };

    private static final boolean[] FINDER_LIKE_B = {
            false, false, false, false, true, false, true, true, true, false, true
    };

    private final int version;

    private final int size;

    /** The picture: true is dark. */
    private final boolean[][] modules;

    /** Which modules belong to the patterns rather than the data, and so are never masked. */
    private final boolean[][] function;

    private QrCode(int version) {
        this.version = version;
        this.size = version * 4 + 17;
        this.modules = new boolean[size][size];
        this.function = new boolean[size][size];
    }

    /**
     * Draws {@code text} as an SVG, or returns {@code null} when it does not fit in a version 10
     * symbol.
     *
     * @param quietZone the light border in modules; four is what the specification asks for, and
     *                  scanners do struggle without it
     */
    public static String toSvg(String text, int quietZone) {
        boolean[][] modules = encode(text);

        if (modules == null) {
            return null;
        }

        int size = modules.length;
        int dimension = size + quietZone * 2;

        StringBuilder path = new StringBuilder();

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!modules[y][x]) {
                    continue;
                }

                if (path.length() > 0) {
                    path.append(' ');
                }

                // One unit square per dark module. Adjacent squares share edges exactly, so
                // they render as solid runs rather than a grid of hairlines.
                path.append('M').append(x + quietZone).append(',').append(y + quietZone)
                        .append("h1v1h-1z");
            }
        }

        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 "
                + dimension + " " + dimension + "\" shape-rendering=\"crispEdges\">"
                + "<rect width=\"" + dimension + "\" height=\"" + dimension + "\" fill=\"#ffffff\"/>"
                + "<path d=\"" + path + "\" fill=\"#000000\"/>"
                + "</svg>";
    }

    /**
     * Encodes {@code text} and returns the module grid, {@code null} if it is too long.
     *
     * <p>The grid is indexed {@code [y][x]} and {@code true} means dark.
     */
    public static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);

        int version = -1;

        for (int candidate = 1; candidate <= MAX_VERSION; candidate++) {
            if (data.length <= byteCapacity(candidate)) {
                version = candidate;
                break;
            }
        }

        if (version < 0) {
            return null;
        }

        QrCode code = new QrCode(version);

        code.drawFunctionPatterns();
        code.drawCodewords(codewords(version, data));

        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;

        // The specification does not say which mask to use, it says to try all eight and keep
        // the one that scores worst on a list of things scanners dislike.
        for (int mask = 0; mask < 8; mask++) {
            code.applyMask(mask);
            code.drawFormatBits(mask);

            int penalty = code.penalty();

            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestMask = mask;
            }

            // Masking is its own inverse, so applying it again undoes it.
            code.applyMask(mask);
        }

        code.applyMask(bestMask);
        code.drawFormatBits(bestMask);

        return code.modules;
    }

    // ---- capacity and codewords -----------------------------------------------------------

    /** Data codewords available at this version, level M. */
    private static int dataCodewords(int version) {
        int[] blocks = BLOCKS[version];

        return blocks[1] * blocks[2] + blocks[3] * blocks[4];
    }

    /** How many bytes fit, after the mode indicator and the length field. */
    private static int byteCapacity(int version) {
        return (dataCodewords(version) * 8 - 4 - lengthBits(version)) / 8;
    }

    /** The length field grows at version 10; below that it is a single byte. */
    private static int lengthBits(int version) {
        return version < 10 ? 8 : 16;
    }

    /**
     * The final codeword sequence: data and error correction, interleaved block by block the way
     * the specification prescribes, so that damage to one part of the symbol is spread across
     * blocks rather than destroying one of them.
     */
    private static byte[] codewords(int version, byte[] data) {
        int[] spec = BLOCKS[version];
        int ecPerBlock = spec[0];
        int totalDataCodewords = dataCodewords(version);

        Bits bits = new Bits(totalDataCodewords);

        bits.append(0b0100, 4);                        // byte mode
        bits.append(data.length, lengthBits(version));

        for (byte b : data) {
            bits.append(b & 0xff, 8);
        }

        // The terminator and the bits up to the byte boundary are zero, and the buffer already
        // is; only the pad codewords have to be written out.
        byte[] dataCodewords = bits.bytes;

        for (int i = (bits.length + 7) / 8, alternate = 0; i < totalDataCodewords; i++, alternate++) {
            dataCodewords[i] = (byte) (alternate % 2 == 0 ? 0xEC : 0x11);
        }

        int blockCount = spec[1] + spec[3];
        byte[][] dataBlocks = new byte[blockCount][];
        byte[][] ecBlocks = new byte[blockCount][];

        int offset = 0;

        for (int i = 0; i < blockCount; i++) {
            int length = i < spec[1] ? spec[2] : spec[4];

            byte[] block = new byte[length];
            System.arraycopy(dataCodewords, offset, block, 0, length);
            offset += length;

            dataBlocks[i] = block;
            ecBlocks[i] = errorCorrection(block, ecPerBlock);
        }

        byte[] result = new byte[totalDataCodewords + ecPerBlock * blockCount];
        int written = 0;

        int longestBlock = Math.max(spec[2], spec[4]);

        for (int i = 0; i < longestBlock; i++) {
            for (byte[] block : dataBlocks) {
                // Shorter blocks simply have nothing to contribute to the last round.
                if (i < block.length) {
                    result[written++] = block[i];
                }
            }
        }

        for (int i = 0; i < ecPerBlock; i++) {
            for (byte[] block : ecBlocks) {
                result[written++] = block[i];
            }
        }

        return result;
    }

    // ---- Reed-Solomon ---------------------------------------------------------------------

    private static byte[] errorCorrection(byte[] data, int degree) {
        byte[] generator = generatorPolynomial(degree);
        byte[] remainder = new byte[degree];

        for (byte b : data) {
            int factor = (b ^ remainder[0]) & 0xff;

            System.arraycopy(remainder, 1, remainder, 0, degree - 1);
            remainder[degree - 1] = 0;

            for (int i = 0; i < degree; i++) {
                remainder[i] ^= (byte) multiply(generator[i] & 0xff, factor);
            }
        }

        return remainder;
    }

    private static byte[] generatorPolynomial(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;

        int root = 1;

        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) multiply(result[j] & 0xff, root);

                if (j + 1 < degree) {
                    result[j] ^= result[j + 1];
                }
            }

            root = multiply(root, 0x02);
        }

        return result;
    }

    /** Multiplication in GF(256) with the field the specification uses. */
    private static int multiply(int x, int y) {
        int z = 0;

        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }

        return z & 0xff;
    }

    // ---- drawing ---------------------------------------------------------------------------

    private void drawFunctionPatterns() {
        // Timing patterns: the alternating line that tells a scanner how wide a module is.
        for (int i = 0; i < size; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }

        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);

        int[] centres = ALIGNMENT[version];

        for (int i = 0; i < centres.length; i++) {
            for (int j = 0; j < centres.length; j++) {
                boolean corner = (i == 0 && j == 0)
                        || (i == 0 && j == centres.length - 1)
                        || (i == centres.length - 1 && j == 0);

                // The three corners are where the finder patterns already are.
                if (!corner) {
                    drawAlignment(centres[i], centres[j]);
                }
            }
        }

        // Reserves the format area; the real bits go in once a mask has been chosen.
        drawFormatBits(0);
        drawVersionBits();
    }

    private void drawFinder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int distance = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx;
                int yy = y + dy;

                if (xx >= 0 && xx < size && yy >= 0 && yy < size) {
                    // Rings at distance 0..1 and 3, light at 2 and 4 - the second being the
                    // separator that keeps the pattern away from the data.
                    setFunction(xx, yy, distance != 2 && distance != 4);
                }
            }
        }
    }

    private void drawAlignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFunction(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    /**
     * The fifteen format bits, twice: the error correction level and the mask, protected by a
     * BCH code and scrambled with a fixed pattern so an all-light symbol is not a valid one.
     */
    private void drawFormatBits(int mask) {
        int data = ECC_LEVEL_BITS << 3 | mask;
        int remainder = data;

        for (int i = 0; i < 10; i++) {
            remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537);
        }

        int bits = ((data << 10) | remainder) ^ 0x5412;

        for (int i = 0; i <= 5; i++) {
            setFunction(8, i, bit(bits, i));
        }

        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));

        for (int i = 9; i < 15; i++) {
            setFunction(14 - i, 8, bit(bits, i));
        }

        for (int i = 0; i < 8; i++) {
            setFunction(size - 1 - i, 8, bit(bits, i));
        }

        for (int i = 8; i < 15; i++) {
            setFunction(8, size - 15 + i, bit(bits, i));
        }

        // The one module that is always dark.
        setFunction(8, size - 8, true);
    }

    /** Version information, in two copies. Only symbols from version 7 up carry it. */
    private void drawVersionBits() {
        if (version < 7) {
            return;
        }

        int remainder = version;

        for (int i = 0; i < 12; i++) {
            remainder = (remainder << 1) ^ ((remainder >>> 11) * 0x1F25);
        }

        int bits = version << 12 | remainder;

        for (int i = 0; i < 18; i++) {
            boolean value = bit(bits, i);

            int far = size - 11 + i % 3;
            int near = i / 3;

            setFunction(far, near, value);
            setFunction(near, far, value);
        }
    }

    /**
     * Lays the codewords into the symbol: two module columns at a time, right to left, snaking
     * up and down and stepping over everything the patterns already own.
     */
    private void drawCodewords(byte[] data) {
        int i = 0;

        for (int right = size - 1; right >= 1; right -= 2) {
            // The vertical timing pattern occupies column six, so the pairing shifts around it.
            if (right == 6) {
                right = 5;
            }

            for (int vertical = 0; vertical < size; vertical++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vertical : vertical;

                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }

                    // Any modules left over after the data are light, which is what the
                    // specification asks for and what the array already holds.
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (function[y][x]) {
                    continue;
                }

                boolean invert = switch (mask) {
                    case 0 -> (x + y) % 2 == 0;
                    case 1 -> y % 2 == 0;
                    case 2 -> x % 3 == 0;
                    case 3 -> (x + y) % 3 == 0;
                    case 4 -> (x / 3 + y / 2) % 2 == 0;
                    case 5 -> x * y % 2 + x * y % 3 == 0;
                    case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
                    case 7 -> ((x + y) % 2 + x * y % 3) % 2 == 0;
                    default -> false;
                };

                modules[y][x] ^= invert;
            }
        }
    }

    // ---- mask scoring ----------------------------------------------------------------------

    /** The four penalties of the specification. Lower is better. */
    private int penalty() {
        int result = 0;

        boolean[] line = new boolean[size];

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                line[x] = modules[y][x];
            }

            result += linePenalty(line);
        }

        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                line[y] = modules[y][x];
            }

            result += linePenalty(line);
        }

        // Rule two: blocks of one colour, counted as overlapping two by two squares.
        for (int y = 0; y + 1 < size; y++) {
            for (int x = 0; x + 1 < size; x++) {
                boolean colour = modules[y][x];

                if (colour == modules[y][x + 1]
                        && colour == modules[y + 1][x]
                        && colour == modules[y + 1][x + 1]) {
                    result += 3;
                }
            }
        }

        // Rule four: how far the symbol is from being half dark.
        int dark = 0;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) {
                    dark++;
                }
            }
        }

        int total = size * size;
        int deviation = Math.abs(dark * 100 / total - 50) / 5;

        return result + deviation * 10;
    }

    /** Rules one and three, both of which look along a single row or column. */
    private static int linePenalty(boolean[] line) {
        int penalty = 0;
        int run = 1;

        for (int i = 1; i < line.length; i++) {
            if (line[i] == line[i - 1]) {
                run++;

                if (run == 5) {
                    penalty += 3;
                } else if (run > 5) {
                    penalty++;
                }
            } else {
                run = 1;
            }
        }

        for (int i = 0; i + 11 <= line.length; i++) {
            if (matches(line, i, FINDER_LIKE_A) || matches(line, i, FINDER_LIKE_B)) {
                penalty += 40;
            }
        }

        return penalty;
    }

    private static boolean matches(boolean[] line, int offset, boolean[] pattern) {
        for (int i = 0; i < pattern.length; i++) {
            if (line[offset + i] != pattern[i]) {
                return false;
            }
        }

        return true;
    }

    // ---- small helpers ---------------------------------------------------------------------

    private void setFunction(int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private static boolean bit(int value, int index) {
        return ((value >>> index) & 1) != 0;
    }

    /** A bit sink that writes straight into the codeword array, most significant bit first. */
    private static final class Bits {

        private final byte[] bytes;

        private int length;

        Bits(int capacityBytes) {
            this.bytes = new byte[capacityBytes];
        }

        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) {
                    bytes[length >>> 3] |= (byte) (1 << (7 - (length & 7)));
                }

                length++;
            }
        }
    }
}
