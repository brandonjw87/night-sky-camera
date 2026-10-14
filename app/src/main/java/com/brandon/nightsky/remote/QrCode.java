package com.brandon.nightsky.remote;

import java.nio.charset.Charset;

/**
 * Minimal QR code encoder (ISO/IEC 18004) for short text such as a web address or a Wi-Fi login: byte mode,
 * versions 1 to 40, a fixed error-correction level, automatic mask choice. Follows the structure of Project
 * Nayuki's reference encoder.
 */
public final class QrCode {
    /** Error-correction levels, with their two-bit format codes. */
    public static final int LOW = 0, MEDIUM = 1, QUARTILE = 2, HIGH = 3;
    private static final int[] FORMAT_BITS = {1, 0, 3, 2};

    public final int version, size, mask, ecl;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    /** True if the module at column x, row y is dark. */
    public boolean get(int x, int y) {
        return x >= 0 && x < size && y >= 0 && y < size && modules[y][x];
    }

    /** Encodes text as UTF-8 at the given error-correction level, in the smallest version that fits. */
    public static QrCode encodeText(String text, int ecl) {
        return encodeBytes(text.getBytes(Charset.forName("UTF-8")), ecl, -1);
    }

    /** Encodes bytes; {@code forceMask} 0..7 fixes the mask (for tests), -1 picks the best. */
    public static QrCode encodeBytes(byte[] data, int ecl, int forceMask) {
        int version = -1;
        for (int v = 1; v <= 40; v++) {
            int cap = numDataCodewords(v, ecl) * 8;
            int need = 4 + (v <= 9 ? 8 : 16) + data.length * 8;
            if (need <= cap) {
                version = v;
                break;
            }
        }
        if (version < 0) throw new IllegalArgumentException("Too much data for a QR code");
        return encodeBytes(data, ecl, version, forceMask);
    }

    /** Encodes bytes in exactly this version (for tests). */
    public static QrCode encodeBytes(byte[] data, int ecl, int version, int forceMask) {
        int capBits = numDataCodewords(version, ecl) * 8;
        Bits bb = new Bits(capBits);
        bb.append(4, 4); // byte mode
        bb.append(data.length, version <= 9 ? 8 : 16);
        for (byte b : data) bb.append(b & 0xFF, 8);
        if (bb.length > capBits) throw new IllegalArgumentException("Data does not fit version " + version);
        bb.append(0, Math.min(4, capBits - bb.length));
        bb.append(0, (8 - bb.length % 8) % 8);
        for (int pad = 0xEC; bb.length < capBits; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] codewords = new byte[bb.length / 8];
        for (int i = 0; i < bb.length; i++) {
            if (bb.get(i)) codewords[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
        }
        return new QrCode(version, ecl, codewords, forceMask);
    }

    private QrCode(int version, int ecl, byte[] dataCodewords, int forceMask) {
        this.version = version;
        this.ecl = ecl;
        size = version * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];
        drawFunctionPatterns();
        byte[] all = addEccAndInterleave(dataCodewords);
        drawCodewords(all);
        int m = forceMask;
        if (m < 0) {
            int best = Integer.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                applyMask(i);
                drawFormatBits(i);
                int p = penaltyScore();
                if (p < best) {
                    m = i;
                    best = p;
                }
                applyMask(i); // undo
            }
        }
        mask = m;
        applyMask(m);
        drawFormatBits(m);
    }

    // ---- function patterns ----

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        int[] pos = alignmentPositions();
        int n = pos.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                drawAlignment(pos[i], pos[j]);
            }
        }
        drawFormatBits(0); // placeholder, overwritten once the mask is known
        drawVersion();
    }

    private void drawFormatBits(int msk) {
        int data = FORMAT_BITS[ecl] << 3 | msk;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setFunction(8, i, bit(bits, i));
        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, bit(bits, i));
        setFunction(8, size - 8, true);
    }

    private void drawVersion() {
        if (version < 7) return;
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = bit(bits, i);
            int a = size - 11 + i % 3, c = i / 3;
            setFunction(a, c, b);
            setFunction(c, a, b);
        }
    }

    private void drawFinder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) setFunction(xx, yy, dist != 2 && dist != 4);
            }
        }
    }

    private void drawAlignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) setFunction(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
    }

    private void setFunction(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private int[] alignmentPositions() {
        if (version == 1) return new int[0];
        int n = version / 7 + 2;
        int step = version == 32 ? 26 : (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
        int[] r = new int[n];
        r[0] = 6;
        for (int i = n - 1, pos = size - 7; i >= 1; i--, pos -= step) r[i] = pos;
        return r;
    }

    // ---- codewords ----

    private byte[] addEccAndInterleave(byte[] data) {
        int numBlocks = NUM_BLOCKS[ecl][version];
        int blockEcc = ECC_PER_BLOCK[ecl][version];
        int raw = numRawDataModules(version) / 8;
        int numShort = numBlocks - raw % numBlocks;
        int shortLen = raw / numBlocks;
        byte[][] blocks = new byte[numBlocks][];
        byte[] div = rsDivisor(blockEcc);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int dl = shortLen - blockEcc + (i < numShort ? 0 : 1);
            byte[] dat = new byte[dl];
            System.arraycopy(data, k, dat, 0, dl);
            k += dl;
            byte[] ecc = rsRemainder(dat, div);
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(dat, 0, block, 0, dl);
            System.arraycopy(ecc, 0, block, block.length - blockEcc, blockEcc);
            blocks[i] = block;
        }
        byte[] out = new byte[raw];
        int n = 0;
        for (int i = 0; i < shortLen + 1; i++) {
            for (int j = 0; j < numBlocks; j++) {
                // short blocks have no byte at index shortLen - blockEcc
                if (i != shortLen - blockEcc || j >= numShort) out[n++] = blocks[j][i];
            }
        }
        return out;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int msk) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inv;
                switch (msk) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                modules[y][x] ^= inv & !isFunction[y][x];
            }
        }
    }

    // ---- penalty ----

    private int penaltyScore() {
        int result = 0;
        int[] hist = new int[7];
        for (int y = 0; y < size; y++) {
            boolean runColor = false;
            int run = 0;
            java.util.Arrays.fill(hist, 0);
            for (int x = 0; x < size; x++) {
                if (modules[y][x] == runColor) {
                    run++;
                    if (run == 5) result += 3;
                    else if (run > 5) result++;
                } else {
                    addHistory(run, hist);
                    if (!runColor) result += countPatterns(hist) * 40;
                    runColor = modules[y][x];
                    run = 1;
                }
            }
            result += terminateAndCount(runColor, run, hist) * 40;
        }
        for (int x = 0; x < size; x++) {
            boolean runColor = false;
            int run = 0;
            java.util.Arrays.fill(hist, 0);
            for (int y = 0; y < size; y++) {
                if (modules[y][x] == runColor) {
                    run++;
                    if (run == 5) result += 3;
                    else if (run > 5) result++;
                } else {
                    addHistory(run, hist);
                    if (!runColor) result += countPatterns(hist) * 40;
                    runColor = modules[y][x];
                    run = 1;
                }
            }
            result += terminateAndCount(runColor, run, hist) * 40;
        }
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3;
            }
        }
        int dark = 0;
        for (boolean[] row : modules) for (boolean b : row) if (b) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        result += k * 10;
        return result;
    }

    private int countPatterns(int[] h) {
        int n = h[1];
        boolean core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n;
        return (core && h[0] >= n * 4 && h[6] >= n ? 1 : 0) + (core && h[6] >= n * 4 && h[0] >= n ? 1 : 0);
    }

    private int terminateAndCount(boolean runColor, int run, int[] h) {
        if (runColor) {
            addHistory(run, h);
            run = 0;
        }
        run += size;
        addHistory(run, h);
        return countPatterns(h);
    }

    private void addHistory(int run, int[] h) {
        if (h[0] == 0) run += size;
        System.arraycopy(h, 0, h, 1, h.length - 1);
        h[0] = run;
    }

    // ---- Reed-Solomon over GF(2^8) mod 0x11D ----

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) gfMul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) gfMul(div[i] & 0xFF, factor);
        }
        return r;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---- capacities ----

    static int numRawDataModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int n = ver / 7 + 2;
            r -= (25 * n - 10) * n - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    static int numDataCodewords(int ver, int ecl) {
        return numRawDataModules(ver) / 8 - ECC_PER_BLOCK[ecl][ver] * NUM_BLOCKS[ecl][ver];
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private static final int[][] ECC_PER_BLOCK = {
            {-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
            {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28},
            {-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
            {-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
    };

    private static final int[][] NUM_BLOCKS = {
            {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25},
            {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49},
            {-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68},
            {-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81},
    };

    /** Growable bit string, most significant bit first. */
    private static final class Bits {
        private final boolean[] b;
        int length;

        Bits(int capacity) {
            b = new boolean[capacity + 32];
        }

        void append(int val, int len) {
            for (int i = len - 1; i >= 0; i--) b[length++] = ((val >>> i) & 1) != 0;
        }

        boolean get(int i) {
            return b[i];
        }
    }

    /**
     * The text of a "join this Wi-Fi" code, as phone cameras read it, with the special characters escaped.
     *
     * @param sae true for a WPA3-only network (written T:SAE), false for WPA2 or WPA2/WPA3 (T:WPA)
     */
    public static String wifiPayload(String ssid, String password, boolean sae) {
        StringBuilder sb = new StringBuilder("WIFI:T:");
        sb.append(password == null || password.isEmpty() ? "nopass" : (sae ? "SAE" : "WPA"));
        sb.append(";S:").append(escape(ssid)).append(';');
        if (password != null && !password.isEmpty()) sb.append("P:").append(escape(password)).append(';');
        return sb.append(';').toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }
}
