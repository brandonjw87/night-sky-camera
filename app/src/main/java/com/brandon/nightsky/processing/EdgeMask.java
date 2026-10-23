package com.brandon.nightsky.processing;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The foreground (A7, F3, G3's edge-mask brush): what sits still on the sensor all session long (a branch, a roof
 * line, the ground) instead of moving with the sky. It is found automatically from the session's average on the
 * sensor, as structure far darker than the sky that reaches the frame's edge (with the lit parts of it), and it can
 * be painted by hand where that missed something or took sky for ground. Stars found in it are left out of the
 * alignment, and "keep the ground still" takes the picture there from the frames' plain average, not lined up, so
 * the ground stays sharp while the stars line up.
 * <p>
 * What was painted is kept as masks/&lt;hash&gt;.mask in the base session's folder, named by what it holds, so a
 * recipe that names it always means the same painting. Cells are {@link #CELL} sensor pixels square.
 */
public final class EdgeMask {
    public static final int CELL = 8;
    /** A cell as painted: not at all (the automatic finding decides), ground, or sky. */
    public static final byte NONE = 0, GROUND = 1, SKY = 2;
    public static final String DIR = "masks";
    private static final int MAGIC = 0x4E534531; // "NSE1"
    private static final int FORMAT = 1;
    /** On the session's average, a cell this far below the sky's median (and clearly so) is something in the way. */
    static final float DARK = 0.35f;
    /** ... and a cell this far above it is a lit part of it, when it touches the dark part. */
    static final float BRIGHT = 3f;
    /** Cells a piece of foreground needs (fewer is a dark speck, not a branch). */
    static final int MIN_CELLS = 12;

    /** The sensor's size, and the cells across and down. */
    public final int width, height, cw, ch;
    /** Per cell, row by row: {@link #NONE}, {@link #GROUND} or {@link #SKY}. */
    public final byte[] paint;

    public EdgeMask(int width, int height) {
        this.width = width;
        this.height = height;
        cw = cells(width);
        ch = cells(height);
        paint = new byte[cw * ch];
    }

    public static int cells(int px) {
        return (px + CELL - 1) / CELL;
    }

    public EdgeMask copy() {
        EdgeMask m = new EdgeMask(width, height);
        System.arraycopy(paint, 0, m.paint, 0, paint.length);
        return m;
    }

    /** True if nothing is painted. */
    public boolean isBlank() {
        for (byte b : paint) if (b != NONE) return false;
        return true;
    }

    /** Paints a disc of radius r (sensor pixels) around (x, y) with {@code value}; returns whether anything changed. */
    public boolean paintDisc(float x, float y, float r, byte value) {
        boolean changed = false;
        int c0 = Math.max(0, (int) Math.floor((x - r) / CELL)), c1 = Math.min(cw - 1, (int) Math.floor((x + r) / CELL));
        int r0 = Math.max(0, (int) Math.floor((y - r) / CELL)), r1 = Math.min(ch - 1, (int) Math.floor((y + r) / CELL));
        float r2 = r * r;
        for (int cy = r0; cy <= r1; cy++) {
            float dy = (cy + 0.5f) * CELL - y;
            for (int cx = c0; cx <= c1; cx++) {
                float dx = (cx + 0.5f) * CELL - x;
                if (dx * dx + dy * dy > r2) continue;
                int i = cy * cw + cx;
                if (paint[i] != value) {
                    paint[i] = value;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** Cells painted as ground and as sky. */
    public int[] painted() {
        int g = 0, s = 0;
        for (byte b : paint) {
            if (b == GROUND) g++;
            else if (b == SKY) s++;
        }
        return new int[]{g, s};
    }

    /** The foreground: the automatic finding ({@code auto}, null for none) with the painting over it. */
    public boolean[] ground(boolean[] auto) {
        boolean[] g = new boolean[paint.length];
        for (int i = 0; i < g.length; i++) {
            byte p = paint[i];
            g[i] = p == GROUND || (p == NONE && auto != null && auto[i]);
        }
        return g;
    }

    // ---- the file ----

    /** The painting as stored: a header, then the cells deflated. */
    byte[] encode() {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        byte[] z;
        try {
            d.setInput(paint);
            d.finish();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(256);
            byte[] buf = new byte[1 << 14];
            while (!d.finished()) bo.write(buf, 0, d.deflate(buf));
            z = bo.toByteArray();
        } finally {
            d.end();
        }
        ByteBuffer b = ByteBuffer.allocate(24 + z.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MAGIC).putInt(FORMAT).putInt(width).putInt(height).putInt(CELL).putInt(z.length).put(z);
        return b.array();
    }

    /** The painting stored in {@code data}, or null if it isn't one. */
    static EdgeMask decode(byte[] data) {
        if (data == null || data.length < 24) return null;
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != MAGIC || b.getInt() != FORMAT) return null;
        int w = b.getInt(), h = b.getInt(), cell = b.getInt(), n = b.getInt();
        if (cell != CELL || w <= 0 || h <= 0 || (long) w * h > 200_000_000L || n != data.length - 24) return null;
        EdgeMask m = new EdgeMask(w, h);
        Inflater in = new Inflater();
        try {
            in.setInput(data, 24, n);
            int got = 0;
            while (got < m.paint.length && !in.finished()) {
                int k = in.inflate(m.paint, got, m.paint.length - got);
                if (k == 0 && (in.needsInput() || in.needsDictionary())) break;
                got += k;
            }
            if (got != m.paint.length) return null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            in.end();
        }
        for (byte v : m.paint) if (v != NONE && v != GROUND && v != SKY) return null;
        return m;
    }

    /** Short hash of what it holds (its size and cells, not how they were compressed). */
    public String hash() {
        MessageDigest md = Master.sha256();
        ByteBuffer b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(width).putInt(height).putInt(CELL);
        md.update(b.array());
        md.update(paint);
        return Master.hex(md.digest()).substring(0, 16);
    }

    /** The name a recipe gives it, which is its file's in the session folder ("masks/&lt;hash&gt;.mask"). */
    public String name() {
        return DIR + "/" + hash() + ".mask";
    }

    /**
     * Keeps the painting in the session folder (once: the same painting is the same file) and returns its name for
     * the recipe ("masks/&lt;hash&gt;.mask").
     */
    public String save(File sessionDir) throws IOException {
        String name = name();
        File f = new File(sessionDir, name);
        if (f.isFile() && this.equalsPainting(load(sessionDir, name))) return name;
        File dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File tmp = new File(dir, f.getName() + ".part");
        FileOutputStream os = new FileOutputStream(tmp);
        try {
            os.write(encode());
        } finally {
            os.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
        return name;
    }

    private boolean equalsPainting(EdgeMask o) {
        return o != null && o.width == width && o.height == height && java.util.Arrays.equals(o.paint, paint);
    }

    /** The painting a recipe names, or null if it's missing or damaged (or the name isn't one of these files). */
    public static EdgeMask load(File sessionDir, String name) {
        if (name == null || !name.startsWith(DIR + "/") || !name.endsWith(".mask") || name.indexOf("..") >= 0) return null;
        File f = new File(sessionDir, name);
        if (!f.isFile() || f.length() > 64L << 20) return null;
        try {
            InputStream in = new FileInputStream(f);
            try {
                byte[] data = new byte[(int) f.length()];
                int n = 0;
                while (n < data.length) {
                    int k = in.read(data, n, data.length - n);
                    if (k < 0) return null;
                    n += k;
                }
                EdgeMask m = decode(data);
                // the name is the content's: a file that doesn't match it isn't the painting the recipe meant
                return m != null && name.equals(DIR + "/" + m.hash() + ".mask") ? m : null;
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    // ---- the automatic finding ----

    /**
     * The foreground in the session's average on the sensor ({@code avg}: black taken off, row by row): cells far
     * darker than the sky in pieces that reach the frame's edge, with the lit cells beside them, widened by a cell.
     * Null when there is none (or the average is no use).
     */
    public static boolean[] automatic(final float[] avg, SensorSpec spec) {
        final int W = spec.width, H = spec.height;
        if (avg == null || avg.length != W * H) return null;
        final int cw = cells(W), ch = cells(H);
        float[] v = levels(avg, spec);
        float[] t = v.clone();
        float level = Stats.median(t, t.length);
        if (!(level > 0)) return null;
        float clear = 6 * noise(v, cw, ch);
        boolean[] dark = new boolean[cw * ch], lit = new boolean[cw * ch];
        int nd = 0;
        for (int i = 0; i < v.length; i++) {
            dark[i] = v[i] < DARK * level && level - v[i] > clear;
            lit[i] = v[i] > BRIGHT * level && v[i] - level > clear;
            if (dark[i]) nd++;
        }
        return automatic(cw, ch, dark, lit, nd);
    }

    /**
     * Each cell's level in the session's average on the sensor: the mean of its green photosites, lens shading taken
     * out, row by row.
     */
    static float[] levels(final float[] avg, SensorSpec spec) {
        final int W = spec.width, H = spec.height, cw = cells(W), ch = cells(H);
        final int[] cfa = spec.cfa;
        final int gq = Preflight.greenSite(spec);
        final ShadingMap sh = spec.shading;
        final float[] v = new float[cw * ch];
        Parallel.forRange(ch, 8, new Parallel.Range() {
            @Override
            public void run(int r0, int r1) {
                for (int cy = r0; cy < r1; cy++) {
                    for (int cx = 0; cx < cw; cx++) {
                        double s = 0;
                        int n = 0;
                        for (int y = cy * CELL; y < Math.min(H, (cy + 1) * CELL); y++) {
                            for (int x = cx * CELL; x < Math.min(W, (cx + 1) * CELL); x++) {
                                if (cfa[((y & 1) << 1) | (x & 1)] != 1) continue;
                                s += avg[y * W + x];
                                n++;
                            }
                        }
                        v[cy * cw + cx] = n > 0 ? (float) (s / n) * gain(sh, gq, cx, cy) : 0f;
                    }
                }
            }
        });
        return v;
    }

    /** The lens shading's gain for the green photosites {@code gq} at the centre of cell (cx, cy). */
    static float gain(ShadingMap sh, int gq, int cx, int cy) {
        return sh != null ? sh.gain(gq, (cx + 0.5) * CELL, (cy + 0.5) * CELL) : 1f;
    }

    /** The noise of a cell's level: the median step between neighbouring cells, as a sigma. */
    static float noise(float[] v, int cw, int ch) {
        float[] steps = new float[cw * ch];
        int k = 0;
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx + 1 < cw; cx++) steps[k++] = Math.abs(v[cy * cw + cx + 1] - v[cy * cw + cx]);
        }
        return k > 0 ? (float) (1.4826 * Stats.median(steps, k) / Math.sqrt(2)) : 0f;
    }

    /** The finding from the dark and the lit cells ({@code nd} dark ones). */
    private static boolean[] automatic(int cw, int ch, boolean[] dark, boolean[] lit, int nd) {
        if (nd < MIN_CELLS) return null;
        // pieces of the dark cells (gaps of a cell closed) that reach the frame's edge
        boolean[] grown = dilate(dark, cw, ch, 1);
        int[] label = new int[cw * ch];
        java.util.Arrays.fill(label, -1);
        boolean[] keep = new boolean[cw * ch];
        boolean any = false;
        int[] queue = new int[cw * ch];
        List<Integer> piece = new ArrayList<Integer>();
        for (int s = 0; s < grown.length; s++) {
            if (!grown[s] || label[s] >= 0) continue;
            int qh = 0, qt = 0, darkCells = 0;
            boolean edge = false;
            piece.clear();
            queue[qt++] = s;
            label[s] = s;
            while (qh < qt) {
                int i = queue[qh++];
                piece.add(i);
                int x = i % cw, y = i / cw;
                if (dark[i]) darkCells++;
                if (x < 2 || y < 2 || x >= cw - 2 || y >= ch - 2) edge = true;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= cw || yy >= ch) continue;
                        int j = yy * cw + xx;
                        if (grown[j] && label[j] < 0) {
                            label[j] = s;
                            queue[qt++] = j;
                        }
                    }
                }
            }
            if (edge && darkCells >= MIN_CELLS) {
                for (int i : piece) keep[i] = true;
                any = true;
            }
        }
        if (!any) return null;
        // the lit parts beside it (leaves under a porch light, a lit window), then a cell's margin all round
        boolean[] near = dilate(keep, cw, ch, 1);
        for (int i = 0; i < keep.length; i++) if (lit[i] && near[i]) keep[i] = true;
        return dilate(keep, cw, ch, 1);
    }

    /**
     * The automatic foreground of a session whose frames are still on the phone (from its average on the sensor),
     * or null.
     */
    public static boolean[] automatic(SessionStore st, SensorSpec spec) {
        int[] n = new int[1];
        float[] sum = st.readSum(spec.width * spec.height, n);
        if (sum == null || n[0] <= 0) return null;
        float inv = 1f / n[0];
        for (int i = 0; i < sum.length; i++) sum[i] *= inv;
        return automatic(sum, spec);
    }

    static boolean[] dilate(boolean[] m, int w, int h, int r) {
        boolean[] o = new boolean[m.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!m[y * w + x]) continue;
                for (int yy = Math.max(0, y - r); yy <= Math.min(h - 1, y + r); yy++) {
                    for (int xx = Math.max(0, x - r); xx <= Math.min(w - 1, x + r); xx++) o[yy * w + xx] = true;
                }
            }
        }
        return o;
    }

    // ---- using it ----

    public static int count(boolean[] g) {
        int n = 0;
        if (g != null) for (boolean b : g) if (b) n++;
        return n;
    }

    /** Whether sensor pixel (x, y) is in the foreground {@code g} (cells of a w x h sensor). */
    public static boolean in(boolean[] g, int w, float x, float y) {
        int cw = cells(w);
        int cx = (int) Math.floor(x / CELL), cy = (int) Math.floor(y / CELL);
        if (cx < 0 || cy < 0 || cx >= cw) return false;
        int i = cy * cw + cx;
        return i < g.length && g[i];
    }

    /** The stars that aren't in the foreground (the same list when none is). */
    public static StarList filter(StarList s, boolean[] g, int w) {
        if (s == null || g == null) return s;
        int drop = 0;
        for (int i = 0; i < s.count; i++) if (in(g, w, s.x[i], s.y[i])) drop++;
        if (drop == 0) return s;
        StarList out = new StarList(Math.max(1, s.count - drop));
        out.background = s.background;
        out.noise = s.noise;
        for (int i = 0; i < s.count; i++) {
            if (!in(g, w, s.x[i], s.y[i])) out.add(s.x[i], s.y[i], s.flux[i], s.hfr[i], s.elong[i], s.saturated[i]);
        }
        return out;
    }

    /**
     * Rows of the sensor the foreground's cells span, with a margin of cells for its blend into the sky: [from, to);
     * to <= from when none.
     */
    public static int[] rows(boolean[] g, int cw, int ch, int H) {
        int lo = -1, hi = -1;
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                if (!g[y * cw + x]) continue;
                if (lo < 0) lo = y;
                hi = y;
                break;
            }
        }
        if (lo < 0) return new int[]{0, 0};
        // a margin either side, and even rows for the colour filter
        int from = Math.max(0, (lo - 2) * CELL) & ~3, to = Math.min(H, (hi + 3) * CELL);
        return new int[]{from, to};
    }

    /** Where the foreground is, in words: "the left edge", "the bottom and left edges"; "" if nowhere. */
    public static String where(boolean[] g, int cw, int ch) {
        if (g == null) return "";
        boolean left = false, right = false, top = false, bottom = false, middle = false;
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                if (!g[y * cw + x]) continue;
                double fx = (x + 0.5) / cw, fy = (y + 0.5) / ch;
                boolean edge = false;
                if (fx < 0.15) left = edge = true;
                if (fx > 0.85) right = edge = true;
                if (fy < 0.15) top = edge = true;
                if (fy > 0.85) bottom = edge = true;
                if (!edge) middle = true;
            }
        }
        List<String> sides = new ArrayList<String>();
        if (top) sides.add("top");
        if (bottom) sides.add("bottom");
        if (left) sides.add("left");
        if (right) sides.add("right");
        if (sides.isEmpty()) return middle ? "the middle" : "";
        return "the " + PostProcessor.join(sides) + (sides.size() > 1 ? " edges" : " edge");
    }
}
