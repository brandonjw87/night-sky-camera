package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.Json;
import com.brandon.nightsky.remote.JsonTree;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A stacked picture kept on disk (G1, G3): the stack's linear RGB as half floats, so a photo can be rendered again
 * (brightness, colour, crop) without stacking again, and a night whose frames were deleted for space can still be
 * merged. The develop step rounds its stack to half floats the same way before it renders anything
 * ({@link #quantize(PostProcessor.Output, SensorSpec, boolean)}), so a render from the master is the same photo,
 * pixel for pixel.
 * <p>
 * Files, in the session folder: masters/&lt;key&gt;.f16 (the picture) and masters/&lt;key&gt;.json (what made it:
 * the sessions, the stack's recipe and the pipeline version; its colour; where it points; and its hash), and
 * masters/&lt;key&gt;.report (its session report, when one was made).
 */
public final class Master {
    private Master() {}

    public static final String DIR = "masters";
    private static final int MAGIC = 0x4E534D31; // "NSM1"
    private static final int FORMAT = 1;
    private static final int HEADER_INTS = 10;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** What a master is. */
    public static final class Meta {
        /** Its stack key ({@link Recipe#stackKey}): which sessions, frames and stack settings made it. */
        public String key = "";
        /** The processing version that made it ({@link PostProcessor#PIPELINE}), and the app's version. */
        public int pipeline;
        public String app = "";
        public long createdMs;
        public boolean trails;
        /** Made by the develop step: kept as long as the session is. */
        public boolean develop;
        /** The sessions stacked (folder ids), the base first. */
        public final List<String> sessions = new ArrayList<String>();
        /** The stack part of the recipe that made it, as JSON. */
        public String stack = "{}";
        /** The colour it renders with: the stars' (F4) when they were measured, else the camera's fixed balance. */
        public float[] camToSrgb, camNeutral;
        public float wbCct;
        public boolean colourFromStars;
        public String summary = "";
        public double integrationSeconds, nEff = Double.NaN, medianHfr = Double.NaN;
        public int unitsUsed, unitsTotal;
        public float maxGain = 1f;
        /** The reference frame's lens model, and where it pointed (camera to J2000; null if it wasn't solved) and when. */
        public CameraModel model;
        public double[] solutionR;
        public double solutionJd = Double.NaN;
        /** The frame the stack was lined up on (-1 if unknown): the edit screen's starting reference. */
        public int referenceUnit = -1;
        /**
         * The reference frame's brightest stars, in the picture's pixels (x, y, flux; null if none): to place it on
         * another night's without its frames.
         */
        public float[] starX, starY, starFlux;
        /** When the capture began (Unix ms), and where. */
        public long startMs;
        public double lat = Double.NaN, lon = Double.NaN;
        public String siteId = "";
        /** SHA-256 of the picture as stored (hex). */
        public String hash = "";
        public long bytes;
        /** A merge's parts, coverage and total ({@link MergeProcessor#describe}; "" for one session). */
        public String merge = "";

        public File image(File sessionDir) {
            return new File(new File(sessionDir, DIR), key + ".f16");
        }
    }

    /** A master read back. */
    public static final class Loaded {
        public Meta meta;
        public RgbImage image;
    }

    // ---- the develop step's rounding ----

    /** Rounds every sample to the nearest half float, in place: what a master keeps. */
    public static void quantize(final RgbImage img) {
        Parallel.forRange(img.height, 16, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                int a = j0 * img.width, b = j1 * img.width;
                for (int c = 0; c < 3; c++) {
                    float[] p = img.channel(c);
                    for (int i = a; i < b; i++) p[i] = Half.toFloat(Half.fromFloat(p[i]));
                }
            }
        });
    }

    /**
     * Rounds the stack to half floats and measures it again for rendering, so the photo the develop step saves is
     * the one its master will give again.
     */
    public static void quantize(PostProcessor.Output o, SensorSpec spec, boolean trails) {
        quantize(o.image);
        o.analysis = analyze(o.image, o.colourSpec != null ? o.colourSpec : spec, trails);
    }

    /** The picture's measurements for rendering, as the develop step makes them. */
    public static Renderer.Analysis analyze(RgbImage img, SensorSpec colourSpec, boolean trails) {
        Renderer.Analysis a = Renderer.analyze(img, colourSpec);
        // star trails: colour over a longer window, as the trails' colour flickers with the sampling phase
        if (trails) a.colorSigma = 3f;
        return a;
    }

    /** Renders a picture's crop to ARGB pixels, row after row (what the develop step saves as the photo). */
    public static int[] render(RgbImage img, Renderer.Analysis a, Renderer.Params p) {
        final int[] pix = new int[a.cw * a.ch];
        Renderer.render(img, a, p, new Renderer.RowSink() {
            @Override
            public void rows(int y, int count, int[] argb, int width) {
                System.arraycopy(argb, 0, pix, y * width, count * width);
            }
        }, null);
        return pix;
    }

    /**
     * A recipe's photo from a stack (G3): rendered with its settings ({@link Recipe#params}), then cut and turned as
     * its framing says ({@link Recipe#framing}); outW x outH pixels of the framing, row after row.
     */
    public static int[] photo(RgbImage img, Renderer.Analysis a, Renderer.Params p, Framing f) {
        return f.apply(render(img, a, p));
    }

    /** The session's sensor description with the master's colour in place. */
    public static SensorSpec colourSpec(SensorSpec base, Meta m) {
        SensorSpec s = base.copy();
        if (m.camToSrgb != null && m.camToSrgb.length == 9) s.camToSrgb = m.camToSrgb.clone();
        if (m.camNeutral != null && m.camNeutral.length == 3) s.camNeutral = m.camNeutral.clone();
        if (m.wbCct > 0) s.wbCct = m.wbCct;
        return s;
    }

    /** What an output is, for its master; the caller adds the key, the stack's recipe and the session's place. */
    public static Meta meta(PostProcessor.Output o, SensorSpec spec, boolean trails) {
        Meta m = new Meta();
        m.pipeline = PostProcessor.PIPELINE;
        m.createdMs = System.currentTimeMillis();
        m.trails = trails;
        SensorSpec cs = o.colourSpec != null ? o.colourSpec : spec;
        m.camToSrgb = cs.camToSrgb.clone();
        m.camNeutral = cs.camNeutral.clone();
        m.wbCct = cs.wbCct;
        m.colourFromStars = o.colourSpec != null;
        m.summary = o.summary;
        m.integrationSeconds = o.integrationSeconds;
        m.nEff = o.noise != null ? o.noise.nEff : Double.NaN;
        m.medianHfr = o.medianHfr;
        m.unitsUsed = o.unitsUsed;
        m.unitsTotal = o.unitsTotal;
        m.maxGain = o.maxGain;
        m.model = o.model != null ? o.model.copy() : null;
        m.referenceUnit = o.referenceUnit;
        if (o.solution != null) {
            m.solutionR = o.solution.R.clone();
            m.solutionJd = o.solutionJd;
        }
        StarList st = o.referenceStars;
        if (st != null && st.count > 0) {
            Integer[] idx = new Integer[st.count];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            final float[] fl = st.flux;
            java.util.Arrays.sort(idx, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return Float.compare(fl[b], fl[a]);
                }
            });
            int n = Math.min(MAX_STARS, idx.length);
            m.starX = new float[n];
            m.starY = new float[n];
            m.starFlux = new float[n];
            for (int i = 0; i < n; i++) {
                m.starX[i] = st.x[idx[i]];
                m.starY[i] = st.y[idx[i]];
                m.starFlux[i] = st.flux[idx[i]];
            }
        }
        return m;
    }

    /** Stars kept in a master's description. */
    static final int MAX_STARS = 400;

    // ---- writing and reading ----

    /** Writes the picture and its description (each whole or not at all); fills in the hash and size. */
    public static void write(File sessionDir, Meta m, RgbImage img) throws IOException {
        if (m.key.isEmpty()) throw new IllegalArgumentException("a master needs a key");
        File dir = new File(sessionDir, DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File f = m.image(sessionDir);
        File tmp = new File(dir, f.getName() + ".part");
        MessageDigest md = sha256();
        byte[] clip = img.clip != null ? deflate(img.clip) : new byte[0];
        RandomAccessFile raf = new RandomAccessFile(tmp, "rw");
        try {
            raf.setLength(0);
            FileChannel ch = raf.getChannel();
            ByteBuffer head = ByteBuffer.allocate(4 * HEADER_INTS).order(ByteOrder.LITTLE_ENDIAN);
            head.putInt(MAGIC).putInt(FORMAT).putInt(img.width).putInt(img.height).putInt(img.x0).putInt(img.y0).putInt(img.x1)
                    .putInt(img.y1).putInt(clip.length).putInt(0);
            head.flip();
            md.update(head.array(), 0, head.limit());
            while (head.hasRemaining()) ch.write(head);
            ByteBuffer b = ByteBuffer.allocate(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
            for (int c = 0; c < 3; c++) {
                float[] p = img.channel(c);
                int i = 0;
                while (i < p.length) {
                    b.clear();
                    int n = Math.min(p.length - i, b.capacity() / 2);
                    for (int k = 0; k < n; k++) b.putShort(Half.fromFloat(p[i + k]));
                    b.flip();
                    md.update(b.array(), 0, b.limit());
                    while (b.hasRemaining()) ch.write(b);
                    i += n;
                }
            }
            if (clip.length > 0) {
                md.update(clip);
                ByteBuffer cb = ByteBuffer.wrap(clip);
                while (cb.hasRemaining()) ch.write(cb);
            }
            ch.force(false);
        } finally {
            raf.close();
        }
        m.hash = hex(md.digest());
        m.bytes = tmp.length();
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
        writeText(new File(dir, m.key + ".json"), toJson(m));
    }

    /** The master with this key in the session folder, or null if there is none (or it's damaged). */
    public static Loaded read(File sessionDir, String key) {
        Meta m = readMeta(new File(new File(sessionDir, DIR), key + ".json"));
        if (m == null) return null;
        RgbImage img = readImage(m.image(sessionDir), m.hash);
        if (img == null) return null;
        Loaded l = new Loaded();
        l.meta = m;
        l.image = img;
        return l;
    }

    /** The picture, or null if it's missing, damaged or (with a hash) not the one described. */
    public static RgbImage readImage(File f, String expectHash) {
        if (!f.isFile()) return null;
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                FileChannel ch = raf.getChannel();
                MessageDigest md = sha256();
                ByteBuffer head = ByteBuffer.allocate(4 * HEADER_INTS).order(ByteOrder.LITTLE_ENDIAN);
                while (head.hasRemaining()) if (ch.read(head) < 0) return null;
                md.update(head.array());
                head.flip();
                if (head.getInt() != MAGIC || head.getInt() != FORMAT) return null;
                int w = head.getInt(), h = head.getInt();
                if (w <= 0 || h <= 0 || (long) w * h > 200_000_000L) return null;
                int x0 = head.getInt(), y0 = head.getInt(), x1 = head.getInt(), y1 = head.getInt();
                int clipLen = head.getInt();
                if (f.length() != 4L * HEADER_INTS + 6L * w * h + clipLen) return null;
                RgbImage img = new RgbImage(w, h);
                img.x0 = x0;
                img.y0 = y0;
                img.x1 = x1;
                img.y1 = y1;
                ByteBuffer b = ByteBuffer.allocate(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
                for (int c = 0; c < 3; c++) {
                    float[] p = img.channel(c);
                    int i = 0;
                    while (i < p.length) {
                        b.clear();
                        int n = Math.min(p.length - i, b.capacity() / 2);
                        b.limit(2 * n);
                        while (b.hasRemaining()) if (ch.read(b) < 0) return null;
                        md.update(b.array(), 0, 2 * n);
                        b.flip();
                        for (int k = 0; k < n; k++) p[i + k] = Half.toFloat(b.getShort());
                        i += n;
                    }
                }
                if (clipLen > 0) {
                    ByteBuffer cb = ByteBuffer.allocate(clipLen);
                    while (cb.hasRemaining()) if (ch.read(cb) < 0) return null;
                    md.update(cb.array());
                    img.clip = inflate(cb.array(), w * h);
                    if (img.clip == null) return null;
                }
                if (expectHash != null && !expectHash.isEmpty() && !expectHash.equals(hex(md.digest()))) return null;
                return img;
            } finally {
                raf.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** The masters of a session folder (descriptions only), newest first. */
    public static List<Meta> list(File sessionDir) {
        List<Meta> out = new ArrayList<Meta>();
        File[] fs = new File(sessionDir, DIR).listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (!f.getName().endsWith(".json")) continue;
            Meta m = readMeta(f);
            if (m != null && m.image(sessionDir).isFile()) out.add(m);
        }
        Collections.sort(out, new Comparator<Meta>() {
            @Override
            public int compare(Meta a, Meta b) {
                return a.createdMs != b.createdMs ? (a.createdMs > b.createdMs ? -1 : 1) : a.key.compareTo(b.key);
            }
        });
        return out;
    }

    /** Bytes the session's masters take. */
    public static long bytes(File sessionDir) {
        long s = 0;
        File[] fs = new File(sessionDir, DIR).listFiles();
        if (fs != null) for (File f : fs) s += f.length();
        return s;
    }

    /** Deletes one master (picture, description and report). */
    public static void delete(File sessionDir, String key) {
        File dir = new File(sessionDir, DIR);
        //noinspection ResultOfMethodCallIgnored
        new File(dir, key + ".f16").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, key + ".json").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, key + ".report").delete();
    }

    /** Keeps the session report made with a master's stack beside it (its JSON), to show again when it's opened. */
    public static void writeReport(File sessionDir, String key, String json) throws IOException {
        File dir = new File(sessionDir, DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        writeText(new File(dir, key + ".report"), json);
    }

    /** The session report kept with a master, or null. */
    public static String readReport(File sessionDir, String key) {
        return readText(new File(new File(sessionDir, DIR), key + ".report"));
    }

    /**
     * Keeps the develop step's master, those in {@code keep} and the {@code extra} newest others; deletes the rest.
     * Returns the keys deleted.
     */
    public static List<String> prune(File sessionDir, int extra, Set<String> keep) {
        List<String> gone = new ArrayList<String>();
        int kept = 0;
        for (Meta m : list(sessionDir)) {
            if (m.develop || (keep != null && keep.contains(m.key))) continue;
            if (kept < extra) {
                kept++;
                continue;
            }
            delete(sessionDir, m.key);
            gone.add(m.key);
        }
        // leftovers of a write that didn't finish
        File[] fs = new File(sessionDir, DIR).listFiles();
        if (fs != null) {
            for (File f : fs) {
                //noinspection ResultOfMethodCallIgnored
                if (f.getName().endsWith(".part")) f.delete();
            }
        }
        return gone;
    }

    // ---- the description ----

    static String toJson(Meta m) {
        Json j = new Json().obj();
        j.put("file", "master").put("key", m.key).put("pipeline", m.pipeline).put("app", m.app).put("created", m.createdMs);
        j.put("trails", m.trails).put("develop", m.develop);
        j.arr("sessions");
        for (String s : m.sessions) j.val(s);
        j.end();
        // as text, so it reads back exactly as written (its hash is the master's key)
        j.put("stack", m.stack == null || m.stack.isEmpty() ? "{}" : m.stack);
        j.obj("colour");
        floats(j, "camToSrgb", m.camToSrgb);
        floats(j, "camNeutral", m.camNeutral);
        j.put("wbCct", m.wbCct, 9).put("fromStars", m.colourFromStars);
        j.end();
        j.put("summary", m.summary).put("integrationSeconds", m.integrationSeconds, 9).put("nEff", m.nEff, 6)
                .put("medianHfr", m.medianHfr, 6).put("unitsUsed", m.unitsUsed).put("unitsTotal", m.unitsTotal)
                .put("maxGain", m.maxGain, 9);
        if (m.model != null) {
            CameraModel c = m.model;
            j.obj("model").put("width", c.width).put("height", c.height).put("fx", c.fx, 17).put("fy", c.fy, 17).put("cx", c.cx, 17)
                    .put("cy", c.cy, 17).put("skew", c.skew, 17).put("k1", c.k1, 17).put("k2", c.k2, 17).put("k3", c.k3, 17)
                    .put("p1", c.p1, 17).put("p2", c.p2, 17).put("source", c.source).end();
        }
        if (m.solutionR != null) {
            j.arr("solutionR");
            for (double v : m.solutionR) j.val(v, 17);
            j.end();
            j.put("solutionJd", m.solutionJd, 17);
        }
        if (m.referenceUnit >= 0) j.put("referenceUnit", m.referenceUnit);
        if (m.starX != null) {
            j.obj("stars");
            floats(j, "x", m.starX);
            floats(j, "y", m.starY);
            floats(j, "flux", m.starFlux);
            j.end();
        }
        j.put("startMs", m.startMs).put("lat", m.lat, 9).put("lon", m.lon, 9).put("siteId", m.siteId);
        j.put("hash", m.hash).put("bytes", m.bytes);
        // as text, like the stack
        if (m.merge != null && !m.merge.isEmpty()) j.put("merge", m.merge);
        return j.end().toString();
    }

    private static void floats(Json j, String name, float[] v) {
        if (v == null) return;
        j.arr(name);
        for (float f : v) j.val(f, 9);
        j.end();
    }

    static Meta readMeta(File f) {
        String text = readText(f);
        if (text == null) return null;
        try {
            JsonTree.Obj o = JsonTree.object(text);
            if (!"master".equals(o.str("file", ""))) return null;
            Meta m = new Meta();
            m.key = o.str("key", "");
            if (m.key.isEmpty()) return null;
            m.pipeline = o.integer("pipeline", 0);
            m.app = o.str("app", "");
            m.createdMs = o.lng("created", 0);
            m.trails = o.bool("trails", false);
            m.develop = o.bool("develop", false);
            m.sessions.addAll(o.strs("sessions"));
            m.stack = o.str("stack", "{}");
            JsonTree.Obj c = o.obj("colour");
            if (c != null) {
                m.camToSrgb = toFloats(c.nums("camToSrgb"));
                m.camNeutral = toFloats(c.nums("camNeutral"));
                m.wbCct = (float) c.num("wbCct", 0);
                m.colourFromStars = c.bool("fromStars", false);
            }
            m.summary = o.str("summary", "");
            m.integrationSeconds = o.num("integrationSeconds", 0);
            m.nEff = o.num("nEff", Double.NaN);
            m.medianHfr = o.num("medianHfr", Double.NaN);
            m.unitsUsed = o.integer("unitsUsed", 0);
            m.unitsTotal = o.integer("unitsTotal", 0);
            m.maxGain = (float) o.num("maxGain", 1);
            JsonTree.Obj mo = o.obj("model");
            if (mo != null) {
                CameraModel cm = new CameraModel();
                cm.width = mo.integer("width", 0);
                cm.height = mo.integer("height", 0);
                cm.fx = mo.num("fx", 0);
                cm.fy = mo.num("fy", 0);
                cm.cx = mo.num("cx", 0);
                cm.cy = mo.num("cy", 0);
                cm.skew = mo.num("skew", 0);
                cm.k1 = mo.num("k1", 0);
                cm.k2 = mo.num("k2", 0);
                cm.k3 = mo.num("k3", 0);
                cm.p1 = mo.num("p1", 0);
                cm.p2 = mo.num("p2", 0);
                cm.source = mo.str("source", "master");
                if (cm.fx > 0 && cm.fy > 0) m.model = cm;
            }
            double[] r = o.nums("solutionR");
            if (r != null && r.length == 9) m.solutionR = r;
            m.solutionJd = o.num("solutionJd", Double.NaN);
            m.referenceUnit = o.integer("referenceUnit", -1);
            JsonTree.Obj so = o.obj("stars");
            if (so != null) {
                m.starX = toFloats(so.nums("x"));
                m.starY = toFloats(so.nums("y"));
                m.starFlux = toFloats(so.nums("flux"));
                if (m.starX == null || m.starY == null || m.starFlux == null || m.starX.length != m.starY.length
                        || m.starX.length != m.starFlux.length) {
                    m.starX = m.starY = m.starFlux = null;
                }
            }
            m.startMs = o.lng("startMs", 0);
            m.lat = o.num("lat", Double.NaN);
            m.lon = o.num("lon", Double.NaN);
            m.siteId = o.str("siteId", "");
            m.hash = o.str("hash", "");
            m.bytes = o.lng("bytes", 0);
            m.merge = o.str("merge", "");
            return m;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static float[] toFloats(double[] d) {
        if (d == null) return null;
        float[] f = new float[d.length];
        for (int i = 0; i < d.length; i++) f[i] = (float) d[i];
        return f;
    }

    // ---- small helpers ----

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lower-case hex of bytes. */
    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(2 * d.length);
        for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        return sb.toString();
    }

    /** Adds rendered pixels (ARGB, row after row) to a digest, as the bytes A, R, G, B of each. */
    public static void update(MessageDigest md, int[] argb, int off, int n) {
        byte[] b = new byte[4 * Math.min(n, 1 << 16)];
        int i = 0;
        while (i < n) {
            int k = Math.min(n - i, b.length / 4);
            for (int q = 0; q < k; q++) {
                int v = argb[off + i + q];
                b[4 * q] = (byte) (v >>> 24);
                b[4 * q + 1] = (byte) (v >>> 16);
                b[4 * q + 2] = (byte) (v >>> 8);
                b[4 * q + 3] = (byte) v;
            }
            md.update(b, 0, 4 * k);
            i += k;
        }
    }

    /** SHA-256 (hex) of rendered pixels. */
    public static String pixelHash(int[] argb) {
        MessageDigest md = sha256();
        update(md, argb, 0, argb.length);
        return hex(md.digest());
    }

    /** The digest's hex, for a caller that fed it rows ({@link #update}). */
    public static String finish(MessageDigest md) {
        return hex(md.digest());
    }

    /** A new SHA-256 digest, for {@link #update}. */
    public static MessageDigest digest() {
        return sha256();
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        try {
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(64, data.length / 64));
            byte[] buf = new byte[1 << 16];
            while (!d.finished()) {
                int n = d.deflate(buf);
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            d.end();
        }
    }

    private static byte[] inflate(byte[] data, int length) {
        Inflater in = new Inflater();
        try {
            in.setInput(data);
            byte[] out = new byte[length];
            int n = 0;
            while (n < length && !in.finished()) {
                int k = in.inflate(out, n, length - n);
                if (k == 0 && (in.needsInput() || in.needsDictionary())) break;
                n += k;
            }
            return n == length ? out : null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            in.end();
        }
    }

    static void writeText(File f, String text) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".part");
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
        try {
            w.write(text);
        } finally {
            w.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }

    static String readText(File f) {
        if (!f.isFile()) return null;
        try {
            InputStreamReader r = new InputStreamReader(new FileInputStream(f), UTF8);
            try {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                return sb.toString();
            } finally {
                r.close();
            }
        } catch (IOException e) {
            return null;
        }
    }
}
