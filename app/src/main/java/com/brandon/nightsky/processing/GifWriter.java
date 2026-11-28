package com.brandon.nightsky.processing;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * A looping animated GIF (2.4, Mission Replay), written frame by frame as it's made: GIF89a with one palette for the
 * whole film ({@link Palette}), each frame stored as only the rectangle that changed, the pixels in it that didn't
 * change left transparent so the frame before shows through (index {@link #KEEP}), LZW coded as the format has it
 * (Welch's method with the compress program's hashing, codes widening from 9 to 12 bits, a clear code when the table
 * fills). Frames that change nothing add their time to the frame before.
 */
public final class GifWriter {
    /** The palette index left transparent: "as the frame before". */
    public static final int KEEP = 255;
    public final int width, height;
    private final OutputStream os;
    /** What's on the screen so far (palette indices), and the frame waiting for its time to be known. */
    private final byte[] shown;
    private byte[] pending;
    private int pendingCs, pendingX, pendingY, pendingW, pendingH;
    private int frames;
    private long bytes;
    private final Lzw lzw = new Lzw();

    /** Starts a {@code w} x {@code h} GIF with {@code palette} (up to 255 colours, 0xRRGGBB) playing {@code loops} times (0: forever). */
    public GifWriter(OutputStream os, int w, int h, int[] palette, int loops) throws IOException {
        if (w <= 0 || h <= 0 || w > 65535 || h > 65535) throw new IllegalArgumentException("a GIF can't be " + w + "x" + h);
        if (palette.length > 255) throw new IllegalArgumentException("255 colours at most: the last index is kept for 'unchanged'");
        this.os = os;
        width = w;
        height = h;
        shown = new byte[w * h];
        Arrays.fill(shown, (byte) KEEP);
        byte[] head = new byte[13 + 768 + 19];
        int k = 0;
        for (char c : "GIF89a".toCharArray()) head[k++] = (byte) c;
        k = le(head, k, w);
        k = le(head, k, h);
        // a global colour table of 256 entries, 8 bits a colour channel
        head[k++] = (byte) 0xF7;
        head[k++] = 0;
        head[k++] = 0;
        for (int i = 0; i < 256; i++) {
            int c = i < palette.length ? palette[i] : 0;
            head[k++] = (byte) (c >> 16);
            head[k++] = (byte) (c >> 8);
            head[k++] = (byte) c;
        }
        // NETSCAPE2.0: how many times it plays
        head[k++] = 0x21;
        head[k++] = (byte) 0xFF;
        head[k++] = 11;
        for (char c : "NETSCAPE2.0".toCharArray()) head[k++] = (byte) c;
        head[k++] = 3;
        head[k++] = 1;
        k = le(head, k, loops);
        head[k++] = 0;
        write(head, k);
    }

    /** The next frame: {@code idx} (width x height palette indices, row after row) shown for {@code delayCs} hundredths of a second. */
    public void frame(byte[] idx, int delayCs) throws IOException {
        if (idx.length != width * height) throw new IllegalArgumentException("not a " + width + "x" + height + " frame");
        // the rectangle that changed
        int x0 = width, y0 = height, x1 = -1, y1 = -1;
        for (int y = 0; y < height; y++) {
            int o = y * width;
            int a = -1, b = -1;
            for (int x = 0; x < width; x++) {
                if (idx[o + x] != shown[o + x]) {
                    a = x;
                    break;
                }
            }
            if (a < 0) continue;
            for (int x = width - 1; x >= a; x--) {
                if (idx[o + x] != shown[o + x]) {
                    b = x;
                    break;
                }
            }
            if (y < y0) y0 = y;
            y1 = y;
            if (a < x0) x0 = a;
            if (b > x1) x1 = b;
        }
        if (x1 < 0) {
            // nothing changed: the frame before stays up longer
            if (pending != null) pendingCs += delayCs;
            else emitStill(delayCs);
            return;
        }
        flush();
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        byte[] part = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int o = (y0 + y) * width + x0, p = y * w;
            for (int x = 0; x < w; x++) {
                byte v = idx[o + x];
                if (v == shown[o + x]) {
                    part[p + x] = (byte) KEEP;
                } else {
                    part[p + x] = v;
                    shown[o + x] = v;
                }
            }
        }
        pending = part;
        pendingCs = delayCs;
        pendingX = x0;
        pendingY = y0;
        pendingW = w;
        pendingH = h;
    }

    /** Ends the GIF (its last frame, the trailer); the stream is left open. */
    public void finish() throws IOException {
        flush();
        if (frames == 0) emitStill(10);
        write(new byte[]{0x3B}, 1);
        os.flush();
    }

    public int frames() {
        return frames;
    }

    /** Bytes written so far. */
    public long bytes() {
        return bytes;
    }

    /** A one-pixel frame that changes nothing, for time alone. */
    private void emitStill(int delayCs) throws IOException {
        emit(new byte[]{(byte) KEEP}, 0, 0, 1, 1, delayCs);
    }

    private void flush() throws IOException {
        if (pending == null) return;
        emit(pending, pendingX, pendingY, pendingW, pendingH, pendingCs);
        pending = null;
    }

    private void emit(byte[] px, int x, int y, int w, int h, int delayCs) throws IOException {
        int cs = Math.max(0, Math.min(65535, delayCs));
        byte[] b = new byte[18];
        int k = 0;
        // graphic control: leave it in place, KEEP transparent, its time
        b[k++] = 0x21;
        b[k++] = (byte) 0xF9;
        b[k++] = 4;
        b[k++] = (1 << 2) | 1;
        k = le(b, k, cs);
        b[k++] = (byte) KEEP;
        b[k++] = 0;
        // where it goes
        b[k++] = 0x2C;
        k = le(b, k, x);
        k = le(b, k, y);
        k = le(b, k, w);
        k = le(b, k, h);
        b[k++] = 0;
        write(b, k);
        Counting c = new Counting(os);
        lzw.encode(px, w * h, 8, c);
        bytes += c.n;
        frames++;
    }

    private void write(byte[] b, int n) throws IOException {
        os.write(b, 0, n);
        bytes += n;
    }

    private static int le(byte[] b, int k, int v) {
        b[k] = (byte) v;
        b[k + 1] = (byte) (v >> 8);
        return k + 2;
    }

    private static final class Counting extends OutputStream {
        final OutputStream os;
        long n;

        Counting(OutputStream os) {
            this.os = os;
        }

        @Override
        public void write(int b) throws IOException {
            os.write(b);
            n++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            os.write(b, off, len);
            n += len;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // LZW
    // ------------------------------------------------------------------------------------------------

    /** GIF's LZW (the compress program's: open hashing over HSIZE slots, codes up to 12 bits). */
    static final class Lzw {
        private static final int BITS = 12, HSIZE = 5003, MAXMAX = 1 << BITS;
        private final int[] htab = new int[HSIZE], codetab = new int[HSIZE];
        private final byte[] block = new byte[256];
        private int nBits, maxcode, freeEnt, initBits, clearCode, eofCode, accum, accBits, count;
        private boolean clearFlag;
        private OutputStream out;

        /** {@code n} indices of {@code px}, each under 2^{@code minBits}: the code size byte, the data's sub-blocks, the terminator. */
        void encode(byte[] px, int n, int minBits, OutputStream os) throws IOException {
            out = os;
            os.write(minBits);
            initBits = minBits + 1;
            clearFlag = false;
            nBits = initBits;
            maxcode = (1 << nBits) - 1;
            clearCode = 1 << minBits;
            eofCode = clearCode + 1;
            freeEnt = clearCode + 2;
            accum = 0;
            accBits = 0;
            count = 0;
            int hshift = 0;
            for (int f = HSIZE; f < 65536; f *= 2) hshift++;
            hshift = 8 - hshift;
            Arrays.fill(htab, -1);
            output(clearCode);
            int ent = n > 0 ? px[0] & 0xFF : 0;
            outer:
            for (int p = 1; p < n; p++) {
                int c = px[p] & 0xFF;
                int fcode = (c << BITS) + ent;
                int i = (c << hshift) ^ ent;
                if (htab[i] == fcode) {
                    ent = codetab[i];
                    continue;
                }
                if (htab[i] >= 0) {
                    int disp = i == 0 ? 1 : HSIZE - i;
                    do {
                        if ((i -= disp) < 0) i += HSIZE;
                        if (htab[i] == fcode) {
                            ent = codetab[i];
                            continue outer;
                        }
                    } while (htab[i] >= 0);
                }
                output(ent);
                ent = c;
                if (freeEnt < MAXMAX) {
                    codetab[i] = freeEnt++;
                    htab[i] = fcode;
                } else {
                    Arrays.fill(htab, -1);
                    freeEnt = clearCode + 2;
                    clearFlag = true;
                    output(clearCode);
                }
            }
            if (n > 0) output(ent);
            output(eofCode);
            os.write(0);
            out = null;
        }

        private void output(int code) throws IOException {
            accum &= (1 << accBits) - 1;
            accum = accBits > 0 ? accum | (code << accBits) : code;
            accBits += nBits;
            while (accBits >= 8) {
                put(accum & 0xFF);
                accum >>>= 8;
                accBits -= 8;
            }
            if (freeEnt > maxcode || clearFlag) {
                if (clearFlag) {
                    nBits = initBits;
                    maxcode = (1 << nBits) - 1;
                    clearFlag = false;
                } else {
                    nBits++;
                    maxcode = nBits == BITS ? MAXMAX : (1 << nBits) - 1;
                }
            }
            if (code == eofCode) {
                while (accBits > 0) {
                    put(accum & 0xFF);
                    accum >>>= 8;
                    accBits -= 8;
                }
                flushBlock();
            }
        }

        private void put(int b) throws IOException {
            block[count++] = (byte) b;
            if (count >= 254) flushBlock();
        }

        private void flushBlock() throws IOException {
            if (count == 0) return;
            out.write(count);
            out.write(block, 0, count);
            count = 0;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // The palette
    // ------------------------------------------------------------------------------------------------

    /**
     * One palette for a whole film: median cut over samples of its pictures (the box with the most spread, times how
     * many samples it holds, split at its median along its widest colour, each box's colour its samples' mean), with
     * some colours kept exactly (the captions' white, black); pictures mapped onto it through ordered dithering (a
     * Bayer matrix: a pixel that doesn't change keeps its index from frame to frame, so the GIF stores only what moved)
     * and a lookup of the nearest colour for every 6-bit-a-channel colour, worked out as it's first needed.
     */
    public static final class Palette {
        /** The colours, 0xRRGGBB (at most 255). */
        public final int[] rgb;
        private final byte[] near = new byte[1 << 18];
        private final boolean[] known = new boolean[1 << 18];
        /** The dither's reach (levels of 255) either side. */
        static final float SPREAD = 9f;
        private static final int[] BAYER = {0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26, 12, 44, 4, 36, 14, 46, 6, 38, 60, 28,
                52, 20, 62, 30, 54, 22, 3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25, 15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55,
                23, 61, 29, 53, 21};

        public Palette(int[] rgb) {
            this.rgb = rgb.clone();
        }

        /**
         * Median cut of {@code n} samples (0xRRGGBB) into {@code colours} colours, {@code fixed} (exact colours) first;
         * duplicates of fixed colours dropped.
         */
        public static Palette cut(int[] samples, int n, int colours, int[] fixed) {
            int want = Math.max(1, Math.min(255, colours) - fixed.length);
            int[] s = Arrays.copyOf(samples, n);
            // boxes: [from, to) in s
            int[] from = new int[want], to = new int[want];
            int boxes = 0;
            if (n > 0) {
                from[0] = 0;
                to[0] = n;
                boxes = 1;
            }
            while (boxes < want) {
                // the box worth splitting most: its widest channel's range times its samples
                int best = -1, bestCh = 0;
                long bestScore = 0;
                for (int b = 0; b < boxes; b++) {
                    if (to[b] - from[b] < 2) continue;
                    int[] lo = {255, 255, 255}, hi = {0, 0, 0};
                    for (int i = from[b]; i < to[b]; i++) {
                        int v = s[i];
                        for (int c = 0; c < 3; c++) {
                            int x = (v >> (16 - 8 * c)) & 0xFF;
                            if (x < lo[c]) lo[c] = x;
                            if (x > hi[c]) hi[c] = x;
                        }
                    }
                    int ch = 0, range = -1;
                    for (int c = 0; c < 3; c++) {
                        // green counts most to the eye, blue least
                        int r = (hi[c] - lo[c]) * (c == 1 ? 4 : c == 0 ? 3 : 2);
                        if (r > range) {
                            range = r;
                            ch = c;
                        }
                    }
                    long score = (long) range * (long) Math.sqrt(to[b] - from[b]);
                    if (range > 0 && score > bestScore) {
                        bestScore = score;
                        best = b;
                        bestCh = ch;
                    }
                }
                if (best < 0) break;
                final int shift = 16 - 8 * bestCh;
                int a = from[best], e = to[best];
                Integer[] boxed = new Integer[e - a];
                for (int i = a; i < e; i++) boxed[i - a] = s[i];
                Arrays.sort(boxed, new java.util.Comparator<Integer>() {
                    @Override
                    public int compare(Integer p, Integer q) {
                        return ((p >> shift) & 0xFF) - ((q >> shift) & 0xFF);
                    }
                });
                for (int i = a; i < e; i++) s[i] = boxed[i - a];
                int mid = (a + e) >>> 1;
                // not through a run of one value: the split goes where the value changes
                int mv = (s[mid] >> shift) & 0xFF;
                int m = mid;
                while (m > a + 1 && ((s[m - 1] >> shift) & 0xFF) == mv) m--;
                if (m == a + 1 && ((s[a] >> shift) & 0xFF) == mv) {
                    m = mid;
                    while (m < e - 1 && ((s[m] >> shift) & 0xFF) == mv) m++;
                }
                to[boxes] = e;
                from[boxes] = m;
                to[best] = m;
                boxes++;
            }
            int[] out = new int[fixed.length + boxes];
            int k = 0;
            for (int f : fixed) out[k++] = f & 0xFFFFFF;
            for (int b = 0; b < boxes; b++) {
                long r = 0, g = 0, bl = 0;
                int c = to[b] - from[b];
                for (int i = from[b]; i < to[b]; i++) {
                    r += (s[i] >> 16) & 0xFF;
                    g += (s[i] >> 8) & 0xFF;
                    bl += s[i] & 0xFF;
                }
                int v = (int) ((r + c / 2) / c) << 16 | (int) ((g + c / 2) / c) << 8 | (int) ((bl + c / 2) / c);
                boolean dup = false;
                for (int f = 0; f < k && !dup; f++) dup = out[f] == v;
                if (!dup) out[k++] = v;
            }
            return new Palette(Arrays.copyOf(out, k));
        }

        /** {@code argb} (w x h) as palette indices through the ordered dither, into {@code out}. */
        public void index(int[] argb, int w, int h, byte[] out) {
            for (int y = 0; y < h; y++) {
                int o = y * w, by = (y & 7) << 3;
                for (int x = 0; x < w; x++) {
                    int v = argb[o + x];
                    float d = (BAYER[by | (x & 7)] + 0.5f) / 64f - 0.5f;
                    int add = Math.round(d * 2 * SPREAD);
                    int r = clamp(((v >> 16) & 0xFF) + add), g = clamp(((v >> 8) & 0xFF) + add), b = clamp((v & 0xFF) + add);
                    out[o + x] = (byte) nearest(r, g, b);
                }
            }
        }

        /** The index of the colour nearest (r, g, b), by 6 bits a channel. */
        int nearest(int r, int g, int b) {
            int key = (r >> 2) << 12 | (g >> 2) << 6 | (b >> 2);
            if (known[key]) return near[key] & 0xFF;
            int cr = (r & ~3) + 2, cg = (g & ~3) + 2, cb = (b & ~3) + 2;
            int best = 0;
            long bd = Long.MAX_VALUE;
            for (int i = 0; i < rgb.length; i++) {
                int p = rgb[i];
                int dr = ((p >> 16) & 0xFF) - cr, dg = ((p >> 8) & 0xFF) - cg, db = (p & 0xFF) - cb;
                long d = 3L * dr * dr + 4L * dg * dg + 2L * db * db;
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
            near[key] = (byte) best;
            known[key] = true;
            return best;
        }

        private static int clamp(int v) {
            return v < 0 ? 0 : (v > 255 ? 255 : v);
        }
    }
}
