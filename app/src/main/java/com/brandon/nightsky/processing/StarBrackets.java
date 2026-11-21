package com.brandon.nightsky.processing;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Bright-star brackets (2.2): a change to how the frames are taken. Every few long exposures the camera also takes one
 * {@link #RATIO} times shorter, and those short frames give back what the long ones throw away: the cores of the
 * brightest stars, which fill the sensor's photosites in a long exposure (a phone's photosites hold only a few
 * thousand electrons; a third-magnitude star's middle overflows them), so the long frames hold neither their
 * brightness nor their colour (green clips first, and a clipped star comes out white). Sixteen times shorter, a star
 * has to be three magnitudes brighter to clip, which leaves only Sirius, the brightest planets and the Moon.
 * <p>
 * The short frames aren't stacked: they're far too noisy for the sky. After the stack is made, wherever it is clipped
 * (its clip map) the short frames are read at the same place in the sky (each turned there by the rotation the long
 * frames on either side of it measured, as the stack turns its frames), every colour plane at its own photosites and
 * with the lens's colour shift and shading as the stack has them; per pixel the median of the frames is taken, matched
 * to the stack's own scale on stars that clip in neither (their light in an aperture, both ways: the short frames'
 * gain and the stack's normalisation, transparency included, come out of the same number), and laid over the clipped
 * core in a soft blend from where the stack starts to clip. The core then has its real light, up to sixteen times the
 * old clip level, and its real colour; its clip flag is cleared (a core that clips even in the short frames keeps it,
 * and keeps the stack's light if that is more). The picture is marked {@link RgbImage#bracketed}, so the renderer takes
 * colour from those cores and the HDR photo shows how much brighter they are.
 * <p>
 * Stored in the session's frames folder ({@code frames/bracket/}): the raw frames as the units are, and a list with
 * each one's time (the units' clock), exposure, ISO and black levels.
 */
public final class StarBrackets {
    /** The short exposure is this many times shorter than a long one. */
    public static final int RATIO = 16;
    /** A short frame after every this many long ones. */
    public static final int EVERY = 4;
    /** The shortest short exposure (s): shorter, and the phone's own shutter timing gets in the way. */
    public static final double MIN_SECONDS = 0.1;
    /** Short frames read, at most (spread evenly over the session). */
    public static final int MAX_FRAMES = 24;
    /** Margin round a clipped area read and blended (pixels), and the border that gives each area's sky. */
    static final int PAD = 5;
    /** A clipped area larger than this across (pixels) isn't a star: the Moon, a street light. */
    static final int MAX_AREA_SIDE = 96;
    /** Clipped areas taken, at most (the largest first), and pixels read in all. */
    static final int MAX_AREAS = 600;
    static final int MAX_PIXELS = 400_000;
    /** Stars that clip in neither the stack nor the short frames, matched for the scale: at most, and the least that will do. */
    static final int CAL_STARS = 80, MIN_CAL = 5;
    /** Aperture radius of the match (pixels) and its sky ring. */
    static final int CAL_R = 4;
    /** The highest value written (half floats end at 65504). */
    static final float MAX_VALUE = 60000f;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** One short frame: its index in the folder, time (s, the units' clock: the middle of the exposure), exposure (s), ISO, black. */
    public static final class Frame {
        public int index;
        public double t, exposure;
        public int iso;
        public final float[] black = new float[4];

        String line() {
            return String.format(Locale.US, "%d %.4f %.6f %d %.3f %.3f %.3f %.3f", index, t, exposure, iso, black[0], black[1], black[2], black[3]);
        }

        static Frame parse(String l) {
            String[] p = l.trim().split("\\s+");
            if (p.length < 8) return null;
            try {
                Frame f = new Frame();
                f.index = Integer.parseInt(p[0]);
                f.t = Double.parseDouble(p[1]);
                f.exposure = Double.parseDouble(p[2]);
                f.iso = Integer.parseInt(p[3]);
                for (int k = 0; k < 4; k++) f.black[k] = Float.parseFloat(p[4 + k]);
                return f.exposure > 0 ? f : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** What a repair did, for the log and the report. */
    public static final class Result {
        /** Short frames read, clipped areas found, those repaired, pixels given their light, pixels still clipped. */
        public int frames, areas, repaired, pixels, stillClipped;
        /** Areas left as they were: too large (the Moon) or past the limits. */
        public int skippedLarge, skippedLimit;
        /** The brightest repaired pixel against the old clip level. */
        public float peak;
        /** Unclipped stars the scale was matched on, the scale per colour (stack units per short-frame unit), and its spread. */
        public int calStars;
        public final float[] scale = new float[3];
        public float spread;
        /** The scale against the exposures' own ratio (1 = as the exposures say; less: haze or thin cloud in the stack). */
        public float scaleVsExposure;
        public long ms;
        public String why = "";

        public String describe() {
            if (repaired == 0) return "bright stars: none repaired from the short frames (" + why + ")";
            return String.format(Locale.US, "bright stars: %d clipped star%s measured again from %d short frames (1/%d of the exposure): %d "
                            + "pixels given their real light and colour, up to %.1f times the old clip level%s%s; scale matched on %d unclipped "
                            + "stars (%.3f, %.1f%% spread, %.2f of what the exposures say) (%d ms)", repaired, repaired == 1 ? "" : "s",
                    frames, RATIO, pixels, peak, stillClipped > 0 ? String.format(Locale.US, ", %d still clipped even then", stillClipped) : "",
                    skippedLarge + skippedLimit > 0 ? String.format(Locale.US, "; %d large clipped area%s left as %s were (the Moon, a light)",
                            skippedLarge + skippedLimit, skippedLarge + skippedLimit == 1 ? "" : "s", skippedLarge + skippedLimit == 1 ? "it" : "they")
                            : "", calStars, scale[1], 100 * spread, scaleVsExposure, ms);
        }
    }

    private StarBrackets() {}

    // ------------------------------------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------------------------------------

    public static File dir(SessionStore st) {
        return new File(st.framesDir, "bracket");
    }

    public static FrameStore store(SessionStore st, int w, int h) {
        return new FrameStore(dir(st), w, h);
    }

    public static synchronized void append(SessionStore st, Frame f) throws IOException {
        File d = dir(st);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("Cannot create " + d);
        Writer w = new OutputStreamWriter(new FileOutputStream(new File(d, "list.txt"), true), UTF8);
        try {
            w.write(f.line());
            w.write('\n');
        } finally {
            w.close();
        }
    }

    /** The session's short frames whose raw file is whole, in the order they were taken. */
    public static List<Frame> list(SessionStore st, int w, int h) {
        List<Frame> out = new ArrayList<Frame>();
        File l = new File(dir(st), "list.txt");
        if (!l.isFile()) return out;
        FrameStore fs = store(st, w, h);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(l), UTF8));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    Frame f = Frame.parse(line);
                    if (f != null && fs.exists(f.index)) out.add(f);
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            return out;
        }
        Collections.sort(out, new Comparator<Frame>() {
            @Override
            public int compare(Frame a, Frame b) {
                return Double.compare(a.t, b.t);
            }
        });
        return out;
    }

    /** The short exposure for long frames of {@code longNs} (ns), or 0 when they're too short to bracket. */
    public static long shortNs(long longNs) {
        long s = longNs / RATIO;
        return s >= (long) (MIN_SECONDS * 1e9) ? s : 0;
    }

    // ------------------------------------------------------------------------------------------------
    // The sky's turn at a short frame's moment
    // ------------------------------------------------------------------------------------------------

    /**
     * The rotation from the reference frame to the sky at time t, between the plans on either side (by the turn from one
     * to the other, a share of the way); null when no plan is within {@code maxGap} seconds on both sides (or one side,
     * at the session's ends).
     */
    static double[] rotationAt(List<SharpStacker.Plan> sorted, double t, double maxGap) {
        SharpStacker.Plan a = null, b = null;
        for (SharpStacker.Plan p : sorted) {
            if (p.t <= t) a = p;
            else {
                b = p;
                break;
            }
        }
        if (a == null && b == null) return null;
        if (a == null || b == null) {
            // the ends: the two nearest, carried on
            int n = sorted.size();
            if (n < 2) return null;
            SharpStacker.Plan p0 = a == null ? sorted.get(0) : sorted.get(n - 2), p1 = a == null ? sorted.get(1) : sorted.get(n - 1);
            double near = a == null ? p0.t - t : t - p1.t;
            if (near > maxGap) return null;
            a = p0;
            b = p1;
        } else if (t - a.t > maxGap || b.t - t > maxGap) {
            return null;
        }
        double span = b.t - a.t;
        double s = span > 1e-9 ? (t - a.t) / span : 0;
        double[] d = Rot.mul(b.R, Rot.transpose(a.R));
        double[] v = Rot.toRotVec(d);
        double[] part = Rot.fromRotVec(v[0] * s, v[1] * s, v[2] * s);
        return Rot.mul(part, a.R);
    }

    // ------------------------------------------------------------------------------------------------
    // The repair
    // ------------------------------------------------------------------------------------------------

    /** A rectangle of the stack read from the short frames: a clipped area with its margin, or a star to match on. */
    private static final class Area {
        int x0, y0, x1, y1;
        boolean cal;
        /** Offset of its first pixel in the sample buffers. */
        int off;
        /** Its clipped pixels (stack index), for a clipped area. */
        int clipped;
        int w() {
            return x1 - x0;
        }

        int h() {
            return y1 - y0;
        }
    }

    /**
     * Gives the stack's clipped star cores their light from the short frames. {@code plans}: the stack's (each frame's
     * rotation and time); {@code longExposure}: a stacked frame's exposure (s, as the stack's values are per frame);
     * {@code chroma}: the lens's colour shift the stack took out (may be null).
     */
    public static Result repair(RgbImage img, List<Frame> frames, FrameStore fs, SensorSpec spec, CameraModel model,
                                List<SharpStacker.Plan> plans, double longExposure, HotPixels hot, ChromaShift.Model chroma, Progress progress)
            throws IOException {
        long t0 = System.currentTimeMillis();
        Result res = new Result();
        int W = img.width, H = img.height;
        if (frames.isEmpty()) {
            res.why = "no short frames";
            return res;
        }
        if (img.clip == null) {
            res.why = "the stack kept no clip map";
            return res;
        }
        if (W != spec.width || H != spec.height) {
            res.why = "the stack isn't the sensor's size";
            return res;
        }
        List<SharpStacker.Plan> sorted = new ArrayList<SharpStacker.Plan>(plans);
        Collections.sort(sorted, new Comparator<SharpStacker.Plan>() {
            @Override
            public int compare(SharpStacker.Plan a, SharpStacker.Plan b) {
                return Double.compare(a.t, b.t);
            }
        });
        // the short frames to read: those the long frames' turns reach, evenly spread
        double gap = 3 * Math.max(longExposure, 1);
        List<Frame> use = new ArrayList<Frame>();
        List<double[]> rots = new ArrayList<double[]>();
        for (Frame f : frames) {
            double[] R = rotationAt(sorted, f.t, gap);
            if (R == null) continue;
            use.add(f);
            rots.add(R);
        }
        if (use.size() > MAX_FRAMES) {
            List<Frame> u2 = new ArrayList<Frame>();
            List<double[]> r2 = new ArrayList<double[]>();
            for (int i = 0; i < MAX_FRAMES; i++) {
                int k = (int) Math.round(i * (use.size() - 1) / (double) (MAX_FRAMES - 1));
                u2.add(use.get(k));
                r2.add(rots.get(k));
            }
            use = u2;
            rots = r2;
        }
        int K = use.size();
        if (K == 0) {
            res.why = "no short frame was taken between lined-up long ones";
            return res;
        }
        res.frames = K;
        float[] clipInv = Renderer.clipGrid(img, spec);
        float clipLevel = spec.range();
        // --- the clipped areas -------------------------------------------------------------------------------------
        byte[] clip = img.clip;
        int cx0 = img.x0, cy0 = img.y0, cx1 = img.x1, cy1 = img.y1;
        boolean[] seen = new boolean[W * H];
        List<Area> areas = new ArrayList<Area>();
        int[] stack = new int[4096];
        for (int y = cy0; y < cy1; y++) {
            for (int x = cx0; x < cx1; x++) {
                int i = y * W + x;
                if (clip[i] == 0 || seen[i]) continue;
                // one connected clipped area (8 neighbours)
                int ax0 = x, ax1 = x, ay0 = y, ay1 = y, n = 0, sp = 0;
                seen[i] = true;
                stack[sp++] = i;
                while (sp > 0) {
                    int q = stack[--sp];
                    int qx = q % W, qy = q / W;
                    n++;
                    ax0 = Math.min(ax0, qx);
                    ax1 = Math.max(ax1, qx);
                    ay0 = Math.min(ay0, qy);
                    ay1 = Math.max(ay1, qy);
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = qx + dx, ny = qy + dy;
                            if (nx < cx0 || ny < cy0 || nx >= cx1 || ny >= cy1) continue;
                            int r = ny * W + nx;
                            if (clip[r] == 0 || seen[r]) continue;
                            seen[r] = true;
                            if (sp == stack.length) stack = Arrays.copyOf(stack, sp * 2);
                            stack[sp++] = r;
                        }
                    }
                }
                res.areas++;
                if (ax1 - ax0 + 1 > MAX_AREA_SIDE || ay1 - ay0 + 1 > MAX_AREA_SIDE) {
                    res.skippedLarge++;
                    continue;
                }
                Area a = new Area();
                a.x0 = Math.max(cx0, ax0 - PAD);
                a.y0 = Math.max(cy0, ay0 - PAD);
                a.x1 = Math.min(cx1, ax1 + PAD + 1);
                a.y1 = Math.min(cy1, ay1 + PAD + 1);
                a.clipped = n;
                areas.add(a);
            }
        }
        seen = null;
        if (areas.isEmpty()) {
            res.why = res.areas == 0 ? "nothing clipped" : "only large clipped areas";
            return res;
        }
        Collections.sort(areas, new Comparator<Area>() {
            @Override
            public int compare(Area a, Area b) {
                return b.clipped - a.clipped;
            }
        });
        // --- stars to match the scale on: bright, but clipping nowhere near ----------------------------------------
        List<Area> cal = calibrationStars(img, clipInv, CAL_STARS);
        // the budget: the largest clipped areas first, then the matching stars
        List<Area> all = new ArrayList<Area>();
        int px = 0;
        for (Area a : areas) {
            if (all.size() >= MAX_AREAS || px + a.w() * a.h() > MAX_PIXELS) {
                res.skippedLimit++;
                continue;
            }
            a.off = px;
            px += a.w() * a.h();
            all.add(a);
        }
        for (Area a : cal) {
            if (px + a.w() * a.h() > MAX_PIXELS + 40_000) break;
            a.off = px;
            px += a.w() * a.h();
            all.add(a);
        }
        // --- every short frame's light at those pixels (per second, shading as the stack's) --------------------------
        short[] vals = new short[px * K * 3];
        byte[] over = new byte[(px * K + 7) / 8];
        short[] raw = new short[W * H];
        int pR = -1, pG1 = -1, pG2 = -1, pB = -1;
        for (int p = 0; p < 4; p++) {
            int c = spec.cfa[p];
            if (c == 0 && pR < 0) pR = p;
            else if (c == 2 && pB < 0) pB = p;
            else if (c == 1) {
                if (pG1 < 0) pG1 = p;
                else pG2 = p;
            }
        }
        if (pR < 0) pR = 0;
        if (pG1 < 0) pG1 = 1;
        if (pG2 < 0) pG2 = pG1;
        if (pB < 0) pB = 3;
        int[] planes = {pR, pG1, pG2, pB};
        float white = spec.white - Math.max(3f, 0.01f * clipLevel);
        double[] b = new double[3], q = new double[3], pp = new double[2];
        float[] t = new float[2];
        for (int k = 0; k < K; k++) {
            if (progress != null) {
                if (progress.cancelled()) throw new CancelledException();
                progress.update("Bright stars", k / (float) K);
            }
            Frame fr = use.get(k);
            fs.read(fr.index, raw);
            double[] R = rots.get(k);
            float inv = (float) (1 / fr.exposure);
            for (Area a : all) {
                for (int y = a.y0; y < a.y1; y++) {
                    for (int x = a.x0; x < a.x1; x++) {
                        model.unproject(x + 0.5, y + 0.5, b);
                        Rot.apply(R, b[0], b[1], b[2], q);
                        int o = a.off + (y - a.y0) * a.w() + (x - a.x0);
                        int vo = (o * K + k) * 3;
                        if (!model.project(q[0], q[1], q[2], pp) || pp[0] < 3 || pp[1] < 3 || pp[0] > W - 3 || pp[1] > H - 3) {
                            vals[vo] = vals[vo + 1] = vals[vo + 2] = Half.fromFloat(Float.NaN);
                            continue;
                        }
                        float mx = (float) pp[0], my = (float) pp[1];
                        float rx = mx, ry = my, bx = mx, by = my;
                        if (chroma != null) {
                            double ux = mx - chroma.cx, uy = my - chroma.cy;
                            double rho = Math.sqrt(ux * ux + uy * uy) / chroma.halfDiagonal;
                            double sr = chroma.shift(0, rho), sb = chroma.shift(2, rho), len = Math.max(1e-9, Math.sqrt(ux * ux + uy * uy));
                            rx = (float) (mx + ux / len * sr);
                            ry = (float) (my + uy / len * sr);
                            bx = (float) (mx + ux / len * sb);
                            by = (float) (my + uy / len * sb);
                        }
                        boolean clipped = false;
                        float[] rgb = new float[3];
                        for (int c = 0; c < 3; c++) {
                            float sx = c == 0 ? rx : c == 2 ? bx : mx, sy = c == 0 ? ry : c == 2 ? by : my;
                            float v;
                            if (c == 1) {
                                plane(raw, W, H, pG1, sx, sy, hot, t);
                                v = t[0] - fr.black[pG1];
                                if (t[1] >= white) clipped = true;
                                if (pG2 != pG1) {
                                    plane(raw, W, H, pG2, sx, sy, hot, t);
                                    v = 0.5f * (v + t[0] - fr.black[pG2]);
                                    if (t[1] >= white) clipped = true;
                                }
                            } else {
                                int pl = c == 0 ? pR : pB;
                                plane(raw, W, H, pl, sx, sy, hot, t);
                                v = t[0] - fr.black[pl];
                                if (t[1] >= white) clipped = true;
                            }
                            rgb[c] = v * inv;
                        }
                        // the lens's shading, taken out as the stack does it (at the stack's pixel)
                        if (spec.shading != null && !spec.shading.isUnity()) {
                            rgb[0] *= spec.shading.gain(pR, x + 0.5, y + 0.5);
                            rgb[1] *= pG2 != pG1 ? 0.5f * (spec.shading.gain(pG1, x + 0.5, y + 0.5) + spec.shading.gain(pG2, x + 0.5, y + 0.5))
                                    : spec.shading.gain(pG1, x + 0.5, y + 0.5);
                            rgb[2] *= spec.shading.gain(pB, x + 0.5, y + 0.5);
                        }
                        vals[vo] = Half.fromFloat(rgb[0]);
                        vals[vo + 1] = Half.fromFloat(rgb[1]);
                        vals[vo + 2] = Half.fromFloat(rgb[2]);
                        if (clipped) over[(o * K + k) >> 3] |= (byte) (1 << ((o * K + k) & 7));
                    }
                }
            }
        }
        raw = null;
        // --- per pixel: the frames' median, and whether it clipped in most -----------------------------------------
        float[] med = new float[px * 3];
        boolean[] sat = new boolean[px];
        float[] tmp = new float[K];
        for (int o = 0; o < px; o++) {
            int nOver = 0;
            for (int k = 0; k < K; k++) if ((over[(o * K + k) >> 3] & (1 << ((o * K + k) & 7))) != 0) nOver++;
            sat[o] = nOver * 2 > K;
            for (int c = 0; c < 3; c++) {
                int m = 0;
                for (int k = 0; k < K; k++) {
                    float v = Half.toFloat(vals[(o * K + k) * 3 + c]);
                    if (!Float.isNaN(v)) tmp[m++] = v;
                }
                med[o * 3 + c] = m > 0 ? median(tmp, m) : Float.NaN;
            }
        }
        vals = null;
        // --- the scale: the stack's light over the short frames', star by star (aperture sums less the sky ring) -----
        float[] sc = new float[3];
        float[][] ratios = {new float[cal.size()]};
        float[][] col = new float[3][cal.size()];
        int nCal = 0;
        for (Area a : all) {
            if (!a.cal) continue;
            double[] l = aperture(img, a, null), s = aperture(null, a, med);
            if (l == null || s == null) continue;
            // one scale for every colour (the exposures' ratio and the stack's normalisation are the same for all three),
            // on their sum, where the short frames' light is best measured
            double ls = l[0] + l[1] + l[2], ss = s[0] + s[1] + s[2];
            if (!(ls > 0 && ss > 0)) continue;
            ratios[0][nCal] = (float) (ls / ss);
            for (int c = 0; c < 3; c++) col[c][nCal] = (float) ((l[c] / s[c]) / (ls / ss));
            nCal++;
        }
        res.calStars = nCal;
        float expScale = (float) longExposure;
        if (nCal >= MIN_CAL) {
            float[] rr = Arrays.copyOf(ratios[0], nCal);
            float m = median(rr, nCal);
            for (int i = 0; i < nCal; i++) rr[i] = Math.abs(rr[i] / m - 1);
            res.spread = 1.4826f * median(rr, nCal);
            // and each colour's own small difference (the short frames' sparse red and blue samples against the
            // stack's demosaic), from the same stars, kept within 10%
            for (int c = 0; c < 3; c++) {
                float k = median(Arrays.copyOf(col[c], nCal), nCal);
                sc[c] = m * Math.max(0.9f, Math.min(1.1f, k));
            }
        } else {
            // too few to match on: as the exposures say (frames per second of light)
            sc[0] = sc[1] = sc[2] = expScale;
            res.spread = Float.NaN;
        }
        System.arraycopy(sc, 0, res.scale, 0, 3);
        res.scaleVsExposure = sc[1] / expScale;
        if (nCal >= MIN_CAL && !(res.spread < 0.4f)) {
            res.why = String.format(Locale.US, "the short frames don't match the stack well enough (%.0f%% spread on %d stars)", 100 * res.spread, nCal);
            res.ms = System.currentTimeMillis() - t0;
            return res;
        }
        // --- the clipped cores, given their light ------------------------------------------------------------------
        float peak = 0;
        for (Area a : all) {
            if (a.cal) continue;
            // the sky round the area, both ways: medians of its border
            float[] lb = new float[3], sb = new float[3];
            if (!border(img, a, med, lb, sb)) continue;
            int aw = a.w(), n = aw * a.h();
            float[] add = new float[n * 3];
            boolean[] touched = new boolean[n];
            boolean anySat = false, missing = false;
            for (int y = a.y0; y < a.y1; y++) {
                for (int x = a.x0; x < a.x1; x++) {
                    int i = y * W + x, j = (y - a.y0) * aw + (x - a.x0), o = a.off + j;
                    if (Float.isNaN(med[o * 3]) || Float.isNaN(med[o * 3 + 1]) || Float.isNaN(med[o * 3 + 2])) {
                        missing = true;
                        continue;
                    }
                    float cf = (clip[i] & 0xFF) / 255f;
                    // the stack's own level against its clip level here (the shading raises it towards the corners)
                    float lvl = level(img, clipInv, i, x, y);
                    float near = nearClip(clip, W, H, x, y, 2);
                    // a pixel clipped in any frame has lost light in it (green first, so its colour is off too): all of
                    // it from the short frames; one close to the clip level, a growing share
                    float wgt = (cf > 0 ? 1f : smooth(lvl, 0.72f, 0.92f)) * near;
                    if (wgt <= 0f) continue;
                    touched[j] = true;
                    if (sat[o]) anySat = true;
                    for (int c = 0; c < 3; c++) {
                        float old = img.channel(c)[i];
                        float v = lb[c] + sc[c] * (med[o * 3 + c] - sb[c]);
                        // a channel at its clip level in the stack is only a floor for its light; one below it is measured
                        if (sat[o] || old * clipInvAt(clipInv, img, c, x, y) >= 0.97f) v = Math.max(v, old);
                        add[j * 3 + c] = wgt * (Math.min(MAX_VALUE, v) - old);
                    }
                }
            }
            // each colour's whole light over the area as the short frames measure it (the sum keeps what their coarser
            // red and blue samples spread past the repaired core): the repaired pixels share what is still missing
            float[] fix = {1f, 1f, 1f};
            if (!anySat && !missing) {
                for (int c = 0; c < 3; c++) {
                    float[] chn = img.channel(c);
                    double want = 0, have = 0, added = 0;
                    for (int y = a.y0; y < a.y1; y++) {
                        for (int x = a.x0; x < a.x1; x++) {
                            int j = (y - a.y0) * aw + (x - a.x0), o = a.off + j;
                            want += sc[c] * (med[o * 3 + c] - sb[c]);
                            have += chn[y * W + x] + add[j * 3 + c] - lb[c];
                            added += add[j * 3 + c];
                        }
                    }
                    if (added > 1e-3 && want > 0) fix[c] = (float) Math.max(0.5, Math.min(2.0, 1 + (want - have) / added));
                }
            }
            boolean any = false;
            for (int y = a.y0; y < a.y1; y++) {
                for (int x = a.x0; x < a.x1; x++) {
                    int i = y * W + x, j = (y - a.y0) * aw + (x - a.x0), o = a.off + j;
                    if (!touched[j]) continue;
                    float top = 0;
                    for (int c = 0; c < 3; c++) {
                        float[] chn = img.channel(c);
                        float v = Math.min(MAX_VALUE, Math.max(0f, chn[i] + fix[c] * add[j * 3 + c]));
                        chn[i] = v;
                        top = Math.max(top, v * clipInvAt(clipInv, img, c, x, y));
                    }
                    peak = Math.max(peak, top);
                    if (sat[o]) {
                        res.stillClipped++;
                    } else if (clip[i] != 0) {
                        clip[i] = 0;
                        res.pixels++;
                        any = true;
                    }
                }
            }
            if (any) res.repaired++;
        }
        res.peak = peak;
        if (res.repaired > 0) img.bracketed = true;
        else res.why = "no clipped core was seen clear in the short frames";
        res.ms = System.currentTimeMillis() - t0;
        if (progress != null) progress.update("Bright stars", 1f);
        return res;
    }

    /** Plane p at (mx, my) bilinearly into t[0] (hot pixels left out), the largest of its photosites into t[1]. */
    private static void plane(short[] raw, int W, int H, int p, float mx, float my, HotPixels hot, float[] t) {
        int ox = p & 1, oy = p >> 1;
        float fx = (mx - 0.5f - ox) * 0.5f, fy = (my - 0.5f - oy) * 0.5f;
        int iu = (int) Math.floor(fx), iv = (int) Math.floor(fy);
        float ax = fx - iu, ay = fy - iv;
        float sum = 0, wsum = 0, mx4 = 0;
        for (int j = 0; j < 2; j++) {
            for (int i = 0; i < 2; i++) {
                int x = 2 * (iu + i) + ox, y = 2 * (iv + j) + oy;
                if (x < 0 || y < 0 || x >= W || y >= H) continue;
                float v = raw[y * W + x] & 0xFFFF;
                mx4 = Math.max(mx4, v);
                if (hot != null && hot.isBad(x, y)) continue;
                float wgt = (i == 0 ? 1 - ax : ax) * (j == 0 ? 1 - ay : ay);
                sum += wgt * v;
                wsum += wgt;
            }
        }
        t[0] = wsum > 1e-6f ? sum / wsum : mx4;
        t[1] = mx4;
    }

    /** The stack's brightest channel at i over its clip level there (1: at it). */
    private static float level(RgbImage img, float[] clipInv, int i, int x, int y) {
        float m = 0;
        for (int c = 0; c < 3; c++) m = Math.max(m, img.channel(c)[i] * clipInvAt(clipInv, img, c, x, y));
        return m;
    }

    private static float clipInvAt(float[] clipInv, RgbImage img, int c, int x, int y) {
        int cw = img.x1 - img.x0, ch = img.y1 - img.y0;
        float gx = (x - img.x0 + 0.5f) / cw * (Renderer.CGX - 1), gy = (y - img.y0 + 0.5f) / ch * (Renderer.CGY - 1);
        int i = Math.max(0, Math.min(Renderer.CGX - 2, (int) gx)), j = Math.max(0, Math.min(Renderer.CGY - 2, (int) gy));
        float tx = Math.max(0, Math.min(1, gx - i)), ty = Math.max(0, Math.min(1, gy - j));
        int base = c * Renderer.CGY * Renderer.CGX;
        float a = clipInv[base + j * Renderer.CGX + i], b = clipInv[base + j * Renderer.CGX + i + 1];
        float d = clipInv[base + (j + 1) * Renderer.CGX + i], e = clipInv[base + (j + 1) * Renderer.CGX + i + 1];
        float top = a + tx * (b - a), bot = d + tx * (e - d);
        return top + ty * (bot - top);
    }

    /** 1 within r pixels of a clipped pixel, else 0 (the blend stays on the cores and their edge). */
    private static float nearClip(byte[] clip, int W, int H, int x, int y, int r) {
        for (int dy = -r; dy <= r; dy++) {
            int yy = y + dy;
            if (yy < 0 || yy >= H) continue;
            for (int dx = -r; dx <= r; dx++) {
                int xx = x + dx;
                if (xx < 0 || xx >= W) continue;
                if (clip[yy * W + xx] != 0) return 1f;
            }
        }
        return 0f;
    }

    /** Medians of the area's outermost ring, the stack's (lb) and the short frames' (sb); false without enough of it. */
    private static boolean border(RgbImage img, Area a, float[] med, float[] lb, float[] sb) {
        int n = 2 * (a.w() + a.h());
        float[][] L = new float[3][n], S = new float[3][n];
        int m = 0;
        for (int y = a.y0; y < a.y1; y++) {
            for (int x = a.x0; x < a.x1; x++) {
                if (y != a.y0 && y != a.y1 - 1 && x != a.x0 && x != a.x1 - 1) continue;
                int i = y * img.width + x, o = a.off + (y - a.y0) * a.w() + (x - a.x0);
                if (Float.isNaN(med[o * 3]) || (img.clip[i] != 0)) continue;
                for (int c = 0; c < 3; c++) {
                    L[c][m] = img.channel(c)[i];
                    S[c][m] = med[o * 3 + c];
                }
                m++;
            }
        }
        if (m < 6) return false;
        for (int c = 0; c < 3; c++) {
            lb[c] = median(L[c], m);
            sb[c] = median(S[c], m);
        }
        return true;
    }

    /**
     * The light of a matching star in an aperture of radius {@link #CAL_R} less its sky (the median of the area's
     * border), per channel: the stack's ({@code img}) or the short frames' ({@code med}). Null if any pixel is missing.
     */
    private static double[] aperture(RgbImage img, Area a, float[] med) {
        int cx = (a.x0 + a.x1) / 2, cy = (a.y0 + a.y1) / 2;
        double[] out = new double[3];
        int n = 2 * (a.w() + a.h());
        float[][] ring = new float[3][n];
        int m = 0;
        for (int y = a.y0; y < a.y1; y++) {
            for (int x = a.x0; x < a.x1; x++) {
                if (y != a.y0 && y != a.y1 - 1 && x != a.x0 && x != a.x1 - 1) continue;
                for (int c = 0; c < 3; c++) {
                    float v = img != null ? img.channel(c)[y * img.width + x] : med[(a.off + (y - a.y0) * a.w() + (x - a.x0)) * 3 + c];
                    if (Float.isNaN(v)) return null;
                    ring[c][m] = v;
                }
                m++;
            }
        }
        float[] bg = new float[3];
        for (int c = 0; c < 3; c++) bg[c] = median(ring[c], m);
        for (int y = cy - CAL_R; y <= cy + CAL_R; y++) {
            for (int x = cx - CAL_R; x <= cx + CAL_R; x++) {
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) > CAL_R * CAL_R) continue;
                for (int c = 0; c < 3; c++) {
                    float v = img != null ? img.channel(c)[y * img.width + x] : med[(a.off + (y - a.y0) * a.w() + (x - a.x0)) * 3 + c];
                    if (Float.isNaN(v)) return null;
                    out[c] += v - bg[c];
                }
            }
        }
        return out;
    }

    /**
     * Stars to match the scale on: green peaks between 0.3 and 0.8 of the clip level (bright enough that the
     * short frames see them well; nowhere near clipping in either), with nothing clipped and no brighter peak within
     * their area, brightest first.
     */
    private static List<Area> calibrationStars(RgbImage img, float[] clipInv, int max) {
        int W = img.width;
        int half = CAL_R + 3;
        List<int[]> cand = new ArrayList<int[]>();
        byte[] clip = img.clip;
        float[] g = img.g;
        for (int y = img.y0 + half + 1; y < img.y1 - half - 1; y++) {
            for (int x = img.x0 + half + 1; x < img.x1 - half - 1; x++) {
                int i = y * W + x;
                float lv = g[i] * clipInvAt(clipInv, img, 1, x, y);
                if (lv < 0.3f || lv > 0.8f) continue;
                float v = g[i];
                if (v < g[i - 1] || v < g[i + 1] || v < g[i - W] || v < g[i + W] || v <= g[i - 1 - W] || v <= g[i + 1 + W]
                        || v <= g[i + 1 - W] || v <= g[i - 1 + W]) continue;
                boolean ok = true;
                for (int dy = -half; dy <= half && ok; dy++) {
                    for (int dx = -half; dx <= half; dx++) {
                        int k = (y + dy) * W + x + dx;
                        if (clip[k] != 0 || (g[k] > v && (dx != 0 || dy != 0))) {
                            ok = false;
                            break;
                        }
                    }
                }
                if (!ok) continue;
                cand.add(new int[]{x, y, Float.floatToIntBits(v)});
            }
        }
        Collections.sort(cand, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return Float.compare(Float.intBitsToFloat(b[2]), Float.intBitsToFloat(a[2]));
            }
        });
        List<Area> out = new ArrayList<Area>();
        for (int[] c : cand) {
            if (out.size() >= max) break;
            Area a = new Area();
            a.cal = true;
            a.x0 = c[0] - half;
            a.y0 = c[1] - half;
            a.x1 = c[0] + half + 1;
            a.y1 = c[1] + half + 1;
            out.add(a);
        }
        return out;
    }

    private static float smooth(float v, float a, float b) {
        if (v <= a) return 0f;
        if (v >= b) return 1f;
        float t = (v - a) / (b - a);
        return t * t * (3 - 2 * t);
    }

    private static float median(float[] a, int n) {
        float[] c = Arrays.copyOf(a, n);
        Arrays.sort(c);
        return (n & 1) == 1 ? c[n / 2] : 0.5f * (c[n / 2 - 1] + c[n / 2]);
    }
}
