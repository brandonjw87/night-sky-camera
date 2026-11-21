package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The Earth's turn (2.2): a few seconds that loop like a GIF and show the sky turning over the still phone, as the
 * session measured it. The photo is the sky lined up on one frame (the reference); every moment of the session puts
 * that sky back where the stars were then, by the turn the frames measured ({@link SkyTurn}: one axis, the celestial
 * pole, and one steady rate). Each picture of the clip is the camera's view at that moment: the photo's sky turned to
 * it through the lens's own geometry ({@link CameraModel}), the ground (where the frames saw ground) kept still, and
 * the brightest stars drawing the arcs they travelled since the session began, fading as they age, so the circles
 * about the pole build up as the clip plays. The pole itself is marked (or, beyond the picture, pointed at).
 * <p>
 * The clip runs the session in {@link #TURN_S} seconds at a steady pace, holds the end, then fades back to the start
 * so it loops without a jump. The picture is cut in from the photo's edges just enough that the turned sky covers it
 * all the way through (the corners the reference frame never saw stay out of it).
 */
public final class EarthTurn {
    public static final int FPS = 30;
    /** Seconds of the sky turning, of the end held, and of the fade back to the start. */
    static final double TURN_S = 4.0, HOLD_S = 0.5, BACK_S = 0.6;
    /** The clip's long side, at most (pixels). */
    public static final int MAX_SIDE = 1440;
    /** Stars drawing their arcs, at most; their least spacing (clip pixels). */
    static final int TAIL_STARS = 90;
    static final float TAIL_SPACING = 7f;
    /** How much an arc's light fades as it ages: to FLOOR, with a time constant of this share of the session. */
    static final float TAIL_FLOOR = 0.22f, TAIL_TAU = 0.3f;
    /** An arc's light per pixel of its length, as a share of its star's peak. */
    static final float TAIL_GAIN = 0.55f;
    /** The warp is worked out every this many clip pixels and interpolated between. */
    static final int GRID = 8;

    /** Draws on a finished picture of the clip (a caption: the time and the turn so far); may be null. */
    public interface Overlay {
        /**
         * @param argb      the picture, w x h, row after row
         * @param fraction  how far through the session this picture is (0 to 1; on the fade back, the end's)
         * @param minutes   minutes since the first frame at this picture
         * @param degrees   how far the sky has turned since the first frame
         */
        void draw(int[] argb, int w, int h, double fraction, double minutes, double degrees);
    }

    private final int[] photo;
    private final int pw, ph;
    /** The photo's crop in the stack (sensor pixels), and how many sensor pixels one photo pixel is. */
    private final double px0, py0, f;
    private final int cropW, cropH;
    private final CameraModel model;
    private final SkyTurn turn;
    /** The clip: w x h pixels, each k sensor pixels, its corner at (rx0, ry0) on the sensor. */
    private final int w, h;
    private final double k, rx0, ry0;
    /** The ground's weight at each clip pixel (0..255), or null for none. */
    private final byte[] ground;
    /** Unit bearings of the warp nodes (3 per node), nodes nx x ny. */
    private final double[] nodeB;
    private final int nx, ny;
    /** The stars that draw arcs: their stack position, peak (linear) and colour (linear r, g, b over their mean). */
    private final List<float[]> stars;
    private final int turnFrames, holdFrames, backFrames;
    private String note = "";
    /** How much of the arcs' light is drawn (1: all of it; 0: none, the sky alone). */
    public float arcLight = 1f;

    private EarthTurn(int[] photo, int pw, int ph, double px0, double py0, double f, CameraModel model, SkyTurn turn,
                      GroundMap groundMap, int maxSide, double inset) {
        this.photo = photo;
        this.pw = pw;
        this.ph = ph;
        this.px0 = px0;
        this.py0 = py0;
        this.f = f;
        cropW = (int) Math.round(pw * f);
        cropH = (int) Math.round(ph * f);
        this.model = model;
        this.turn = turn;
        double rw = cropW * (1 - 2 * inset), rh = cropH * (1 - 2 * inset);
        double kk = Math.max(f, Math.max(rw, rh) / maxSide);
        int ww = ((int) Math.floor(rw / kk)) & ~15, hh = ((int) Math.floor(rh / kk)) & ~15;
        w = Math.max(16, ww);
        h = Math.max(16, hh);
        k = kk;
        rx0 = px0 + cropW / 2.0 - w * k / 2.0;
        ry0 = py0 + cropH / 2.0 - h * k / 2.0;
        nx = (w + GRID - 1) / GRID + 1;
        ny = (h + GRID - 1) / GRID + 1;
        nodeB = new double[nx * ny * 3];
        double[] b = new double[3];
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                model.unproject(rx0 + i * GRID * k, ry0 + j * GRID * k, b);
                System.arraycopy(b, 0, nodeB, (j * nx + i) * 3, 3);
            }
        }
        byte[] g = null;
        if (groundMap != null && groundMap.quads > 0) {
            g = new byte[w * h];
            boolean any = false;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int sx = (int) (rx0 + (x + 0.5) * k), sy = (int) (ry0 + (y + 0.5) * k);
                    if (sx < 0 || sy < 0 || sx >= groundMap.width || sy >= groundMap.height) continue;
                    float gw = groundMap.weightAt(sx, sy);
                    if (gw > 0.004f) {
                        g[y * w + x] = (byte) Math.min(255, Math.round(gw * 255));
                        any = true;
                    }
                }
            }
            if (!any) g = null;
        }
        ground = g;
        stars = findStars();
        turnFrames = (int) Math.round(TURN_S * FPS);
        holdFrames = (int) Math.round(HOLD_S * FPS);
        backFrames = (int) Math.round(BACK_S * FPS);
    }

    /**
     * The clip of the photo {@code photo} ({@code pw} x {@code ph}, the stack's crop from sensor pixel (px0, py0) on, one
     * photo pixel every {@code f} sensor pixels), turned as {@code turn} says through {@code model}; {@code ground}: the
     * session's ground (may be null). Null when there's no turn to show or the picture would be too small.
     */
    public static EarthTurn of(int[] photo, int pw, int ph, double px0, double py0, double f, CameraModel model, SkyTurn turn,
                               GroundMap ground, int maxSide) {
        if (photo == null || turn == null || model == null || pw < 32 || ph < 32 || photo.length < pw * ph) return null;
        double inset = insetFor(pw, ph, px0, py0, f, model, turn);
        if (Double.isNaN(inset)) return null;
        EarthTurn e = new EarthTurn(photo, pw, ph, px0, py0, f, model, turn, ground, maxSide, inset);
        return e.w >= 32 && e.h >= 32 ? e : null;
    }

    /**
     * The least share cut from each side so that every clip pixel's sky, at every moment of the session, comes from
     * inside the photo; NaN when even half the picture won't do (a turn too large for the frame).
     */
    static double insetFor(int pw, int ph, double px0, double py0, double f, CameraModel model, SkyTurn turn) {
        double lo = 0, hi = 0.4;
        if (!covered(pw, ph, px0, py0, f, model, turn, hi)) return Double.NaN;
        if (covered(pw, ph, px0, py0, f, model, turn, lo)) return 0;
        for (int it = 0; it < 18; it++) {
            double mid = (lo + hi) / 2;
            if (covered(pw, ph, px0, py0, f, model, turn, mid)) hi = mid;
            else lo = mid;
        }
        return hi + 0.002;
    }

    private static boolean covered(int pw, int ph, double px0, double py0, double f, CameraModel model, SkyTurn turn, double inset) {
        double cw = pw * f, ch = ph * f;
        double x0 = px0 + cw * inset, x1 = px0 + cw * (1 - inset), y0 = py0 + ch * inset, y1 = py0 + ch * (1 - inset);
        // the photo's own edge, a pixel in (its outermost pixels are half made of what's beyond)
        double ex0 = px0 + f, ex1 = px0 + cw - f, ey0 = py0 + f, ey1 = py0 + ch - f;
        double[] b = new double[3], p = new double[2];
        int steps = 48;
        double[][] Rs = new double[5][];
        for (int s = 0; s < 5; s++) Rs[s] = Rot.transpose(turn.at(turn.t0 + (turn.t1 - turn.t0) * s / 4.0));
        for (int e = 0; e < 4; e++) {
            for (int i = 0; i <= steps; i++) {
                double u = i / (double) steps;
                double x = e == 0 ? x0 + u * (x1 - x0) : e == 1 ? x1 : e == 2 ? x1 - u * (x1 - x0) : x0;
                double y = e == 0 ? y0 : e == 1 ? y0 + u * (y1 - y0) : e == 2 ? y1 : y1 - u * (y1 - y0);
                model.unproject(x, y, b);
                for (double[] R : Rs) {
                    double[] q = new double[3];
                    Rot.apply(R, b[0], b[1], b[2], q);
                    if (!model.project(q[0], q[1], q[2], p)) return false;
                    if (p[0] < ex0 || p[0] > ex1 || p[1] < ey0 || p[1] > ey1) return false;
                }
            }
        }
        return true;
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    public int frameCount() {
        return turnFrames + holdFrames + backFrames;
    }

    /** How long one loop plays (ms). */
    public long durationMs() {
        return Math.round(frameCount() * 1000.0 / FPS);
    }

    /** How many times faster than life the sky turns in the clip. */
    public double speedUp() {
        return (turn.t1 - turn.t0) / TURN_S;
    }

    /** The stars drawing arcs. */
    public int arcs() {
        return stars.size();
    }

    /** What the build did, for the log ("" before it). */
    public String note() {
        return note;
    }

    /** About how much memory making the clip takes (bytes) beyond the photo given: the arcs' light, a picture, the warp. */
    public long heapNeeded() {
        long np = (long) w * h;
        return 12 * np + 8 * np + (ground != null ? np : 0) + 8L * nx * ny + 4L * pw * ph + (8L << 20);
    }

    static long pts(int frame) {
        return Math.round(frame * 1e6 / FPS);
    }

    /** Where the clip's pixel (x, y) is on the sensor, and the north (or south) pole there; for tests. */
    public double[] sensorAt(double x, double y) {
        return new double[]{rx0 + x * k, ry0 + y * k};
    }

    /** The visible pole in clip pixels, NaN when it's behind the camera. */
    public double[] poleInClip() {
        double[] np = turn.visiblePole(), p = new double[2];
        if (!model.project(np[0], np[1], np[2], p)) return new double[]{Double.NaN, Double.NaN};
        return new double[]{(p[0] - rx0) / k, (p[1] - ry0) / k};
    }

    /** Where a star at stack position (sx, sy) shows at time t, in clip pixels (pixel centres at +0.5). */
    public double[] clipPosition(double sx, double sy, double t) {
        double[] b = new double[3], q = new double[3], p = new double[2];
        model.unproject(sx, sy, b);
        Rot.apply(turn.at(t), b[0], b[1], b[2], q);
        if (!model.project(q[0], q[1], q[2], p)) return new double[]{Double.NaN, Double.NaN};
        return new double[]{(p[0] - rx0) / k, (p[1] - ry0) / k};
    }

    // ------------------------------------------------------------------------------------------------

    /** Sends every picture of the clip to {@code sink}, in order; {@code overlay} draws on each (may be null). */
    public void build(PhotoVideo.Sink sink, Overlay overlay, Progress progress) throws java.io.IOException {
        long t0 = System.currentTimeMillis();
        int[] out = new int[w * h];
        int[] start = null, end = null;
        float[] tails = new float[3 * w * h];
        float[] sx = new float[nx * ny], sy = new float[nx * ny];
        double[][] path = paths();
        int n = 0;
        double span = turn.t1 - turn.t0;
        for (int fr = 0; fr < turnFrames; fr++) {
            if (progress != null) {
                if (progress.cancelled()) throw new CancelledException();
                progress.update("The Earth's turn", fr / (float) turnFrames);
            }
            double u = turnFrames == 1 ? 1 : fr / (double) (turnFrames - 1);
            double t = turn.t0 + u * span;
            warpNodes(t, sx, sy);
            drawTails(path, u, tails);
            render(sx, sy, tails, out);
            drawPole(out);
            if (fr == 0) start = out.clone();
            if (overlay != null) overlay.draw(out, w, h, u, span * u / 60, Math.toDegrees(turn.rate * span * u));
            sink.frame(out, pts(n++));
            if (fr == turnFrames - 1) end = out.clone();
        }
        int[] endShown = end;
        for (int j = 0; j < holdFrames; j++) sink.frame(endShown, pts(n++));
        // back to the start, for the loop: the end's picture (its caption too) fading into the first one
        int[] startShown = start.clone();
        if (overlay != null) overlay.draw(startShown, w, h, 0, 0, 0);
        int[] buf = new int[w * h];
        for (int j = 0; j < backFrames; j++) {
            double v = (j + 1) / (double) (backFrames + 1);
            double e = v * v * (3 - 2 * v);
            blend(endShown, startShown, (float) e, buf);
            sink.frame(buf, pts(n++));
        }
        if (progress != null) progress.update("The Earth's turn", 1f);
        note = String.format(Locale.US, "%dx%d, %d pictures at %d fps (%.1f s: the session's %.1f minutes %.0f times faster, %.1f s held, "
                        + "%.1f s back to the start), %d stars drawing arcs, cut in %.0f%% from the photo's edges%s (%d ms)", w, h, n, FPS,
                n / (double) FPS, span / 60, speedUp(), HOLD_S, BACK_S, stars.size(), 100 * insetShare(), ground != null ? ", the ground kept still"
                        : "", System.currentTimeMillis() - t0);
    }

    private double insetShare() {
        return Math.max(0, 1 - w * k / cropW) / 2;
    }

    /** The warp nodes' photo positions (photo pixels, centres at +0.5) for the moment t. */
    private void warpNodes(double t, float[] sx, float[] sy) {
        double[] RT = Rot.transpose(turn.at(t));
        double[] q = new double[3], p = new double[2];
        for (int i = 0, e = nx * ny; i < e; i++) {
            Rot.apply(RT, nodeB[3 * i], nodeB[3 * i + 1], nodeB[3 * i + 2], q);
            if (!model.project(q[0], q[1], q[2], p)) {
                sx[i] = Float.NaN;
                sy[i] = Float.NaN;
                continue;
            }
            sx[i] = (float) ((p[0] - px0) / f);
            sy[i] = (float) ((p[1] - py0) / f);
        }
    }

    /** One picture: the turned photo (the ground still), plus the arcs' light, encoded. */
    private void render(final float[] sx, final float[] sy, final float[] tails, final int[] out) {
        Parallel.forRange(h, 8, new Parallel.Range() {
            @Override
            public void run(int y0, int y1) {
                float[] c = new float[3], g = new float[3];
                for (int y = y0; y < y1; y++) {
                    float fy = (y + 0.5f) / GRID;
                    int j = Math.min(ny - 2, (int) fy);
                    float ty = fy - j;
                    for (int x = 0; x < w; x++) {
                        float fx = (x + 0.5f) / GRID;
                        int i = Math.min(nx - 2, (int) fx);
                        float tx = fx - i;
                        int a = j * nx + i;
                        float X = lerp2(sx[a], sx[a + 1], sx[a + nx], sx[a + nx + 1], tx, ty);
                        float Y = lerp2(sy[a], sy[a + 1], sy[a + nx], sy[a + nx + 1], tx, ty);
                        sample(X - 0.5f, Y - 0.5f, c);
                        int o = y * w + x;
                        if (ground != null && ground[o] != 0) {
                            float gw = (ground[o] & 0xFF) * (1f / 255f);
                            double SX = (rx0 + (x + 0.5) * k - px0) / f, SY = (ry0 + (y + 0.5) * k - py0) / f;
                            sample((float) SX - 0.5f, (float) SY - 0.5f, g);
                            for (int ch = 0; ch < 3; ch++) c[ch] += gw * (g[ch] - c[ch]);
                            float sky = 1 - gw;
                            c[0] += sky * tails[3 * o];
                            c[1] += sky * tails[3 * o + 1];
                            c[2] += sky * tails[3 * o + 2];
                        } else {
                            c[0] += tails[3 * o];
                            c[1] += tails[3 * o + 1];
                            c[2] += tails[3 * o + 2];
                        }
                        out[o] = 0xFF000000 | (enc(c[0]) << 16) | (enc(c[1]) << 8) | enc(c[2]);
                    }
                }
            }
        });
    }

    private static float lerp2(float a, float b, float c, float d, float tx, float ty) {
        float top = a + tx * (b - a), bot = c + tx * (d - c);
        return top + ty * (bot - top);
    }

    /** The photo in linear light at (x, y) (pixel centres at integers), bilinear; black off the photo. */
    private void sample(float x, float y, float[] c) {
        if (!(x > -1 && y > -1 && x < pw && y < ph)) {
            c[0] = c[1] = c[2] = 0;
            return;
        }
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        float tx = x - x0, ty = y - y0;
        int xa = Math.max(0, x0), xb = Math.min(pw - 1, x0 + 1), ya = Math.max(0, y0), yb = Math.min(ph - 1, y0 + 1);
        int p00 = photo[ya * pw + xa], p10 = photo[ya * pw + xb], p01 = photo[yb * pw + xa], p11 = photo[yb * pw + xb];
        float w00 = (1 - tx) * (1 - ty), w10 = tx * (1 - ty), w01 = (1 - tx) * ty, w11 = tx * ty;
        for (int ch = 0, sh = 16; ch < 3; ch++, sh -= 8) {
            c[ch] = w00 * DECODE[(p00 >> sh) & 0xFF] + w10 * DECODE[(p10 >> sh) & 0xFF] + w01 * DECODE[(p01 >> sh) & 0xFF]
                    + w11 * DECODE[(p11 >> sh) & 0xFF];
        }
    }

    // ------------------------------------------------------------------------------------------------
    // The arcs
    // ------------------------------------------------------------------------------------------------

    /** Steps along the session each arc is worked out at. */
    private static final int PATH_STEPS = 240;

    /**
     * The brightest stars in the photo, well apart, on the sky (not the ground) and inside the clip all the way through:
     * per star its stack position, its peak above the sky and its colour, brightest first.
     */
    private List<float[]> findStars() {
        final float[] lum = new float[pw * ph];
        for (int i = 0; i < lum.length; i++) {
            int v = photo[i];
            lum[i] = 0.2126f * DECODE[(v >> 16) & 0xFF] + 0.7152f * DECODE[(v >> 8) & 0xFF] + 0.0722f * DECODE[v & 0xFF];
        }
        // the sky's level and spread, from a sample of the pixels
        int step = Math.max(1, lum.length / 20000);
        float[] smp = new float[(lum.length + step - 1) / step];
        int ns = 0;
        for (int i = 0; i < lum.length; i += step) smp[ns++] = lum[i];
        Arrays.sort(smp, 0, ns);
        float med = smp[ns / 2], p16 = smp[(int) (ns * 0.16)];
        float sigma = Math.max(1e-4f, med - p16);
        float thr = med + Math.max(10 * sigma, 0.03f);
        List<float[]> cand = new ArrayList<float[]>();
        for (int y = 2; y < ph - 2; y++) {
            for (int x = 2; x < pw - 2; x++) {
                float v = lum[y * pw + x];
                if (v < thr) continue;
                boolean peak = true;
                for (int dy = -2; dy <= 2 && peak; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        float o = lum[(y + dy) * pw + x + dx];
                        if (o > v || (o == v && (dy < 0 || (dy == 0 && dx < 0)))) {
                            peak = false;
                            break;
                        }
                    }
                }
                if (!peak) continue;
                // light and colour over 3x3 above the sky
                float r = 0, g = 0, b = 0, s = 0, mx = 0, my = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int p = photo[(y + dy) * pw + x + dx];
                        float lr = Math.max(0, DECODE[(p >> 16) & 0xFF] - med), lg = Math.max(0, DECODE[(p >> 8) & 0xFF] - med),
                                lb = Math.max(0, DECODE[p & 0xFF] - med);
                        float l = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb;
                        r += lr;
                        g += lg;
                        b += lb;
                        s += l;
                        mx += l * dx;
                        my += l * dy;
                    }
                }
                if (s <= 0) continue;
                float m = (r + g + b) / 3;
                double sxs = px0 + (x + 0.5 + mx / s) * f, sys = py0 + (y + 0.5 + my / s) * f;
                cand.add(new float[]{(float) sxs, (float) sys, v - med, r / m, g / m, b / m, s});
            }
        }
        java.util.Collections.sort(cand, new java.util.Comparator<float[]>() {
            @Override
            public int compare(float[] a, float[] b) {
                return Float.compare(b[6], a[6]);
            }
        });
        List<float[]> kept = new ArrayList<float[]>();
        float minSq = (float) Math.pow(TAIL_SPACING * k, 2);
        for (float[] c : cand) {
            if (kept.size() >= TAIL_STARS) break;
            boolean ok = true;
            for (float[] q : kept) {
                float dx = q[0] - c[0], dy = q[1] - c[1];
                if (dx * dx + dy * dy < minSq) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            // in the clip at the start and the end, and on the sky
            double[] a = clipPosition(c[0], c[1], turn.t0), b = clipPosition(c[0], c[1], turn.t1);
            if (!inside(a) || !inside(b)) continue;
            if (ground != null) {
                int gx = (int) a[0], gy = (int) a[1];
                if ((ground[gy * w + gx] & 0xFF) > 60) continue;
            }
            kept.add(c);
        }
        return kept;
    }

    private boolean inside(double[] p) {
        return p[0] >= 1 && p[1] >= 1 && p[0] < w - 1 && p[1] < h - 1;
    }

    /** Each arc star's clip positions at PATH_STEPS + 1 even steps through the session: x, y interleaved. */
    private double[][] paths() {
        double[][] out = new double[stars.size()][];
        double[][] Rs = new double[PATH_STEPS + 1][];
        for (int s = 0; s <= PATH_STEPS; s++) Rs[s] = turn.at(turn.t0 + (turn.t1 - turn.t0) * s / PATH_STEPS);
        double[] b = new double[3], q = new double[3], p = new double[2];
        for (int i = 0; i < stars.size(); i++) {
            float[] st = stars.get(i);
            model.unproject(st[0], st[1], b);
            double[] path = new double[2 * (PATH_STEPS + 1)];
            for (int s = 0; s <= PATH_STEPS; s++) {
                Rot.apply(Rs[s], b[0], b[1], b[2], q);
                if (model.project(q[0], q[1], q[2], p)) {
                    path[2 * s] = (p[0] - rx0) / k;
                    path[2 * s + 1] = (p[1] - ry0) / k;
                } else {
                    path[2 * s] = path[2 * s + 1] = Double.NaN;
                }
            }
            out[i] = path;
        }
        return out;
    }

    /** The arcs' light at fraction u of the session, in linear light per clip pixel (r, g, b). */
    private void drawTails(double[][] paths, double u, float[] tails) {
        Arrays.fill(tails, 0f);
        if (u <= 0 || arcLight <= 0) return;
        double end = u * PATH_STEPS;
        for (int i = 0; i < paths.length; i++) {
            float[] st = stars.get(i);
            double[] path = paths[i];
            // the arc's light per pixel of length: a share of the star's peak, at the clip's scale
            float base = arcLight * TAIL_GAIN * st[2] * (float) Math.min(1.5, f / k);
            float cr = 0.6f + 0.4f * st[3], cg = 0.6f + 0.4f * st[4], cb = 0.6f + 0.4f * st[5];
            int last = (int) Math.floor(end);
            for (int s = 0; s < Math.min(last + 1, PATH_STEPS); s++) {
                double a0 = s, a1 = Math.min(s + 1, end);
                if (a1 <= a0) break;
                double x0 = path[2 * s], y0 = path[2 * s + 1], x1 = path[2 * s + 2], y1 = path[2 * s + 3];
                if (Double.isNaN(x0) || Double.isNaN(x1)) continue;
                double fr = (a1 - a0);
                x1 = x0 + (x1 - x0) * fr;
                y1 = y0 + (y1 - y0) * fr;
                double len = Math.hypot(x1 - x0, y1 - y0);
                int n = Math.max(1, (int) Math.ceil(len / 0.4));
                for (int m = 0; m < n; m++) {
                    double v = (m + 0.5) / n;
                    double age = (end - (a0 + v * (a1 - a0))) / PATH_STEPS;
                    float fade = TAIL_FLOOR + (1 - TAIL_FLOOR) * (float) Math.exp(-age / TAIL_TAU);
                    // the newest bit of arc gives way to the star itself
                    float head = (float) Math.min(1, age * PATH_STEPS / 1.5);
                    float a = base * fade * head * (float) (len / n);
                    splat(tails, x0 + v * (x1 - x0), y0 + v * (y1 - y0), a * cr, a * cg, a * cb);
                }
            }
        }
    }

    /** Adds light at (x, y) (pixel centres at +0.5), shared bilinearly among the four pixels around it. */
    private void splat(float[] t, double x, double y, float r, float g, float b) {
        double fx = x - 0.5, fy = y - 0.5;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = (float) (fx - x0), ty = (float) (fy - y0);
        add(t, x0, y0, (1 - tx) * (1 - ty), r, g, b);
        add(t, x0 + 1, y0, tx * (1 - ty), r, g, b);
        add(t, x0, y0 + 1, (1 - tx) * ty, r, g, b);
        add(t, x0 + 1, y0 + 1, tx * ty, r, g, b);
    }

    private void add(float[] t, int x, int y, float wgt, float r, float g, float b) {
        if (x < 0 || y < 0 || x >= w || y >= h || wgt <= 0) return;
        int o = 3 * (y * w + x);
        t[o] += wgt * r;
        t[o + 1] += wgt * g;
        t[o + 2] += wgt * b;
    }

    // ------------------------------------------------------------------------------------------------
    // The pole
    // ------------------------------------------------------------------------------------------------

    /** Marks the celestial pole: a ring round it in the picture, or an arrow at the edge pointing to it beyond. */
    private void drawPole(int[] out) {
        double[] p = poleInClip();
        if (Double.isNaN(p[0])) return;
        float alpha = 0.55f;
        int col = 0xFFB8D4FF;
        double r = Math.max(5, Math.min(w, h) / 90.0);
        if (p[0] >= r && p[1] >= r && p[0] < w - r && p[1] < h - r) {
            ring(out, p[0], p[1], r, 1.1, col, alpha);
            ring(out, p[0], p[1], r * 0.18, 0.9, col, alpha);
            return;
        }
        // beyond the picture: an arrow just inside the edge, on the line from the middle to the pole
        double cx = w / 2.0, cy = h / 2.0, dx = p[0] - cx, dy = p[1] - cy;
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) return;
        dx /= len;
        dy /= len;
        double m = 3 * r;
        double sx = dx != 0 ? ((dx > 0 ? w - m : m) - cx) / dx : Double.POSITIVE_INFINITY;
        double sy = dy != 0 ? ((dy > 0 ? h - m : m) - cy) / dy : Double.POSITIVE_INFINITY;
        double s = Math.min(Math.abs(sx), Math.abs(sy));
        double tipX = cx + dx * s, tipY = cy + dy * s;
        double size = 2.2 * r;
        double bx = tipX - dx * size, by = tipY - dy * size;
        double nx2 = -dy, ny2 = dx;
        line(out, tipX, tipY, bx + nx2 * size * 0.55, by + ny2 * size * 0.55, 1.2, col, alpha);
        line(out, tipX, tipY, bx - nx2 * size * 0.55, by - ny2 * size * 0.55, 1.2, col, alpha);
        line(out, tipX, tipY, tipX - dx * size * 1.6, tipY - dy * size * 1.6, 1.2, col, alpha);
    }

    private void ring(int[] out, double cx, double cy, double r, double width, int col, float alpha) {
        int x0 = (int) Math.floor(cx - r - width - 1), x1 = (int) Math.ceil(cx + r + width + 1);
        int y0 = (int) Math.floor(cy - r - width - 1), y1 = (int) Math.ceil(cy + r + width + 1);
        for (int y = Math.max(0, y0); y <= Math.min(h - 1, y1); y++) {
            for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
                double d = Math.abs(Math.hypot(x + 0.5 - cx, y + 0.5 - cy) - r);
                float cov = (float) Math.max(0, Math.min(1, width / 2 + 0.5 - d));
                if (cov > 0) over(out, y * w + x, col, alpha * cov);
            }
        }
    }

    private void line(int[] out, double ax, double ay, double bx, double by, double width, int col, float alpha) {
        int x0 = (int) Math.floor(Math.min(ax, bx) - width - 1), x1 = (int) Math.ceil(Math.max(ax, bx) + width + 1);
        int y0 = (int) Math.floor(Math.min(ay, by) - width - 1), y1 = (int) Math.ceil(Math.max(ay, by) + width + 1);
        double vx = bx - ax, vy = by - ay, l2 = vx * vx + vy * vy;
        for (int y = Math.max(0, y0); y <= Math.min(h - 1, y1); y++) {
            for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
                double qx = x + 0.5 - ax, qy = y + 0.5 - ay;
                double t = l2 > 0 ? Math.max(0, Math.min(1, (qx * vx + qy * vy) / l2)) : 0;
                double d = Math.hypot(qx - t * vx, qy - t * vy);
                float cov = (float) Math.max(0, Math.min(1, width / 2 + 0.5 - d));
                if (cov > 0) over(out, y * w + x, col, alpha * cov);
            }
        }
    }

    private static void over(int[] out, int i, int col, float a) {
        int v = out[i];
        int r = Math.round(((v >> 16) & 0xFF) + a * (((col >> 16) & 0xFF) - ((v >> 16) & 0xFF)));
        int g = Math.round(((v >> 8) & 0xFF) + a * (((col >> 8) & 0xFF) - ((v >> 8) & 0xFF)));
        int b = Math.round((v & 0xFF) + a * ((col & 0xFF) - (v & 0xFF)));
        out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** a and b mixed, a share {@code t} of b, in linear light. */
    static void blend(int[] a, int[] b, float t, int[] out) {
        for (int i = 0; i < out.length; i++) {
            int p = a[i], q = b[i];
            float r = DECODE[(p >> 16) & 0xFF] + t * (DECODE[(q >> 16) & 0xFF] - DECODE[(p >> 16) & 0xFF]);
            float g = DECODE[(p >> 8) & 0xFF] + t * (DECODE[(q >> 8) & 0xFF] - DECODE[(p >> 8) & 0xFF]);
            float bl = DECODE[p & 0xFF] + t * (DECODE[q & 0xFF] - DECODE[p & 0xFF]);
            out[i] = 0xFF000000 | (enc(r) << 16) | (enc(g) << 8) | enc(bl);
        }
    }

    /**
     * The clip's video bit rate (bits a second): the sky turns smoothly, so the encoder predicts most of each picture
     * from the one before and a lower rate than the photo's video (1.8) keeps it clean.
     */
    public static int bitRate(int w, int h) {
        return (int) Math.max(1_500_000, Math.min(12_000_000, 0.07 * w * h * FPS));
    }

    /**
     * The animated picture the clip is also shared as (a GIF's stand-in): every SHARE_EVERY-th picture, each
     * {@link #shareFactor} times smaller each way, as a still WebP of quality SHARE_QUALITY.
     */
    public static final int SHARE_EVERY = 2, SHARE_QUALITY = 60;
    /** Its long side, about (pixels). */
    static final int SHARE_SIDE = 480;

    public static int shareFactor(int w, int h) {
        return Math.max(2, (int) Math.round(Math.max(w, h) / (double) SHARE_SIDE));
    }

    /** A picture made {@code s} times smaller each way by averages in linear light (the edge's leftover pixels dropped). */
    public static int[] shrink(int[] argb, int w, int h, int s) {
        int hw = w / s, hh = h / s;
        int[] o = new int[hw * hh];
        float inv = 1f / (s * s);
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < hw; x++) {
                float r = 0, g = 0, b = 0;
                for (int dy = 0; dy < s; dy++) {
                    int row = (y * s + dy) * w + x * s;
                    for (int dx = 0; dx < s; dx++) {
                        int v = argb[row + dx];
                        r += DECODE[(v >> 16) & 0xFF];
                        g += DECODE[(v >> 8) & 0xFF];
                        b += DECODE[v & 0xFF];
                    }
                }
                o[y * hw + x] = 0xFF000000 | (enc(r * inv) << 16) | (enc(g * inv) << 8) | enc(b * inv);
            }
        }
        return o;
    }

    /** sRGB-encoded 8-bit values to linear light, and linear light (0..1 in 4096 steps) back. */
    private static final float[] DECODE = new float[256];
    private static final byte[] ENCODE = new byte[4097];

    static {
        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;
            DECODE[i] = (float) (v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i <= 4096; i++) {
            double v = i / 4096.0;
            double e = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
            ENCODE[i] = (byte) Math.max(0, Math.min(255, (int) Math.round(e * 255)));
        }
    }

    private static int enc(float v) {
        int i = (int) (v * 4096f + 0.5f);
        return ENCODE[i < 0 ? 0 : (i > 4096 ? 4096 : i)] & 0xFF;
    }
}
