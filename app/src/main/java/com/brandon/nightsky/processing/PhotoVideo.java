package com.brandon.nightsky.processing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The photo's video (1.8): a few seconds that show how the photo came to be, saved inside the photo as a Motion Photo
 * so the gallery plays it. Deep sky: the frames adding up in the order they were taken, lined up and matched as the
 * stack did it, so the sky's noise melts away and the faint stars come out of it (equal time for each doubling of the
 * frames, as the noise falls fastest at the start). Star trails: the trails drawing themselves, frame by frame. Either
 * way each picture is rendered with the photo's own analysis, settings and framing, and the video ends by fading into
 * the saved photo itself and holding it.
 * <p>
 * It works on a grid: the photo's crop scaled down to the video's size. Each grid pixel is the area average of the
 * photosites under it, per colour plane at the plane's own position (so the colours stay registered), read from the
 * frames on disk band by band as the stack reads them. Deep sky follows the stack's plans: each frame's rotation,
 * weight, background matching, transparency (cloudy parts left out), streak corridors (taken out or kept) and the
 * sensor's pattern. Samples far above the finished stack are left out, as the stack's outlier rejection left them out,
 * and what no frame has seen yet (a corner the first frames missed, the ground kept still) shows the finished stack.
 */
public final class PhotoVideo {
    public static final int FPS = 30;
    /** The video's long side, at most (pixels). */
    public static final int MAX_SIDE = 1440;
    /** Seconds of the frames adding up (deep sky, star trails), of the fade into the photo, and of the photo held. */
    static final double DEEP_BUILD_S = 2.4, TRAILS_BUILD_S = 3.0, FADE_S = 0.5, HOLD_S = 0.8;
    /**
     * A deep-sky sample this many times its noise above the finished stack, plus this share of the light above the sky
     * there (stars vary a little from frame to frame), is an outlier the stack left out: a plane, a cosmic ray.
     */
    static final float CLIP_K = 6f, CLIP_SHARE = 0.5f;
    private static final int STEP = SharpStacker.STEP;
    /** Per warp node: scale, weight, transparency, offset per channel, and the green noise after scaling. */
    private static final int N_SC = 0, N_W = 1, N_T = 2, N_OFF = 3, N_SG = 6, NV = 7;
    private static final float KR = 0.2126f, KG = 0.7152f, KB = 0.0722f;
    /** Grid rows read and added up at a time. */
    private static final int BAND = 48;
    /** The lens shading's gains are worked out every this many grid columns and interpolated between. */
    private static final int GAIN_STEP = 8;

    /** Receives the video's frames in order, each width x height ARGB row after row, at its presentation time. */
    public interface Sink {
        void frame(int[] argb, long ptsUs) throws IOException;
    }

    /** What developing kept for the video: how the frames were read, and the stack's plans (deep sky) or units (trails). */
    public static final class Source {
        final SensorSpec spec;
        final CameraModel model;
        final FrameStore store;
        final HotPixels hot;
        final List<SharpStacker.Plan> plans;
        final BackgroundGrid target;
        final float minTransparency;
        final GroundMap ground;
        final List<TrailsStacker.Unit> units;

        private Source(SensorSpec spec, CameraModel model, FrameStore store, HotPixels hot, List<SharpStacker.Plan> plans,
                       BackgroundGrid target, float minTransparency, GroundMap ground, List<TrailsStacker.Unit> units) {
            this.spec = spec;
            this.model = model;
            this.store = store;
            this.hot = hot;
            this.plans = plans;
            this.target = target;
            this.minTransparency = minTransparency;
            this.ground = ground;
            this.units = units;
        }

        /** A deep-sky stack's frames, as its last stack used them (sorted here by the time they were taken). */
        public static Source deepSky(SensorSpec spec, CameraModel model, FrameStore store, HotPixels hot, List<SharpStacker.Plan> plans,
                                     BackgroundGrid target, float minTransparency, GroundMap ground) {
            List<SharpStacker.Plan> sorted = new ArrayList<SharpStacker.Plan>(plans);
            Collections.sort(sorted, new Comparator<SharpStacker.Plan>() {
                @Override
                public int compare(SharpStacker.Plan a, SharpStacker.Plan b) {
                    return Double.compare(a.t, b.t);
                }
            });
            return new Source(spec, model, store, hot, sorted, target, minTransparency, ground, null);
        }

        /** Star trails' frames, in the order they were taken. */
        public static Source trails(SensorSpec spec, FrameStore store, HotPixels hot, List<TrailsStacker.Unit> units) {
            return new Source(spec, null, store, hot, null, null, 0f, null, new ArrayList<TrailsStacker.Unit>(units));
        }

        public boolean trails() {
            return units != null;
        }

        public int frames() {
            return units != null ? units.size() : plans.size();
        }
    }

    private final Source src;
    private final RgbImage stack;
    private final Renderer.Analysis a;
    private final Renderer.Params params;
    private final int W, H;
    /** The photo's crop in the stack, and the grid: the crop scaled to the video, sx x sy stack pixels a grid pixel. */
    private final int cx0, cy0, cw, ch, gw, gh;
    private final double sx, sy;
    /** How the grid picture is cut and turned as the photo is, and the size it comes out (null: as it is). */
    private final Framing framing;
    private final int fw, fh;
    /** The video: cut from the framed grid picture at (cutX, cutY), a multiple of 16 each way. */
    private final int vw, vh, cutX, cutY;
    private final int buildFrames, fadeFrames, holdFrames;
    private final int pR, pG1, pG2, pB;
    /** Per grid column: the warp node to its left and how far along to the next. */
    private final int[] colIx;
    private final float[] colT;
    /** The lens shading's gain per colour at every grid row and every GAIN_STEP-th column ([c][gy * gn + j]); null: none. */
    private float[][] gains;
    private int gn;
    /** The last picture of the frames adding up, as the video shows it. */
    private int[] last;
    private int framesOut;
    /** What the last build did, for the log; and where its time went (ms): reading the frames, adding them up, rendering. */
    private String note = "";
    private long readNs, addNs, renderNs, sendNs;

    private PhotoVideo(Source src, RgbImage stack, Renderer.Analysis a, Renderer.Params params, String crop, double[] north, int maxSide) {
        this.src = src;
        this.stack = stack;
        this.a = a;
        this.params = params;
        W = src.spec.width;
        H = src.spec.height;
        cx0 = a.cx0;
        cy0 = a.cy0;
        cw = a.cw;
        ch = a.ch;
        Framing whole = Framing.of(crop, north, cw, ch);
        double s = Math.min(1.0, maxSide / (double) Math.max(whole.outW, whole.outH));
        gw = Math.max(1, (int) Math.round(cw * s));
        gh = Math.max(1, (int) Math.round(ch * s));
        sx = cw / (double) gw;
        sy = ch / (double) gh;
        Framing f = Framing.of(crop, north, gw, gh);
        framing = f.identity() ? null : f;
        fw = f.outW;
        fh = f.outH;
        vw = fw & ~15;
        vh = fh & ~15;
        cutX = (fw - vw) / 2;
        cutY = (fh - vh) / 2;
        buildFrames = (int) Math.round((src.trails() ? TRAILS_BUILD_S : DEEP_BUILD_S) * FPS);
        fadeFrames = (int) Math.round(FADE_S * FPS);
        holdFrames = (int) Math.round(HOLD_S * FPS);
        int r = -1, g1 = -1, g2 = -1, b = -1;
        for (int p = 0; p < 4; p++) {
            int c = src.spec.cfa[p];
            if (c == 0 && r < 0) r = p;
            else if (c == 2 && b < 0) b = p;
            else if (c == 1) {
                if (g1 < 0) g1 = p;
                else g2 = p;
            }
        }
        pR = r < 0 ? 0 : r;
        pG1 = g1 < 0 ? 1 : g1;
        pG2 = g2 < 0 ? pG1 : g2;
        pB = b < 0 ? 3 : b;
        int nx = WarpGrid.nodesX(W, STEP);
        colIx = new int[gw];
        colT = new float[gw];
        for (int x = 0; x < gw; x++) {
            double X = cx0 + (x + 0.5) * sx;
            int ix = Stats.clamp((int) (X / STEP), 0, nx - 2);
            colIx[x] = ix;
            colT[x] = (float) (X / STEP - ix);
        }
    }

    /**
     * The video of the photo developed from {@code src}: {@code stack} is the finished stack (deep sky: where the frames
     * end up, and what shows where none has been yet; may be null for star trails), {@code a} and {@code p} the photo's
     * analysis and settings, {@code crop} and {@code north} how it is cut and turned (north null: not turned). Null when
     * the photo is too small for a video.
     */
    public static PhotoVideo of(Source src, RgbImage stack, Renderer.Analysis a, Renderer.Params p, String crop, double[] north,
                                int maxSide) {
        if (src == null || a == null || src.frames() < 1) return null;
        if (!src.trails() && (stack == null || src.model == null)) return null;
        PhotoVideo v = new PhotoVideo(src, stack, a, p, crop, north, maxSide);
        return v.vw >= 16 && v.vh >= 16 ? v : null;
    }

    public int width() {
        return vw;
    }

    public int height() {
        return vh;
    }

    /** Frames in the whole video. */
    public int frameCount() {
        return buildFrames + fadeFrames + holdFrames;
    }

    /** Presentation time of the frame that is the photo itself (the last). */
    public long stillUs() {
        return pts(frameCount() - 1);
    }

    /** How long the video plays (ms). */
    public long durationMs() {
        return Math.round(frameCount() * 1000.0 / FPS);
    }

    /** What the build did, for the log ("" before it). */
    public String note() {
        return note;
    }

    /**
     * About how much memory making the video takes (bytes) beyond what developing already holds: per grid pixel the
     * sums, the finished stack, the background, a kept streak's light and the picture being rendered (deep sky), or the
     * average and the brightest moment (star trails); the pictures rendered, framed and cut; those the frames still
     * show; and a band of frame rows. Finishing it takes less: by then the grid's arrays are gone.
     */
    public long heapNeeded() {
        long np = (long) gw * gh, v = (long) vw * vh, f = (long) fw * fh;
        long grid = (src.trails() ? 43 : 60) * np;
        long render = 4 * np + 4 * f + 4 * v;
        long shown = 5 * 4 * v;
        long band = (long) Math.ceil((BAND + 2) * sy + 13) * W * 6;
        return grid + render + shown + band;
    }

    static long pts(int frame) {
        return Math.round(frame * 1e6 / FPS);
    }

    // ------------------------------------------------------------------------------------------------
    // The frames adding up
    // ------------------------------------------------------------------------------------------------

    /**
     * Sends the video's first part to {@code sink}: the frames adding up, every picture rendered as the photo is. Reads
     * every frame of the session again; the finished stack must still be there (deep sky).
     */
    public void build(Sink sink, Progress progress) throws IOException {
        long t0 = System.currentTimeMillis();
        int n = src.frames();
        Emitter em = new Emitter(n, sink);
        int renders;
        if (src.trails()) renders = buildTrails(em, progress);
        else renders = buildDeep(em, progress);
        em.end();
        last = em.lastState;
        gains = null;
        note = String.format(Locale.US, "%dx%d, %d frames adding up in %.1f s (%d pictures rendered%s), then %.1f s fading into the "
                        + "photo and %.1f s of it (%d ms: reading %d, adding up %d, rendering %d, encoding %d)", vw, vh, n,
                buildFrames / (double) FPS, renders, src.trails() ? ", the trails drawn as the frames came"
                        : ", lined up and matched as the stack did it", fadeFrames / (double) FPS, holdFrames / (double) FPS,
                System.currentTimeMillis() - t0, readNs / 1000000, addNs / 1000000, (renderNs - sendNs) / 1000000, sendNs / 1000000);
    }

    /**
     * Sends the rest to {@code sink}: the last picture fading into {@code photo} (the saved photo, cut and turned,
     * {@code pw} x {@code ph}), then the photo held. {@link #build} first.
     */
    public void finish(int[] photo, int pw, int ph, Sink sink) throws IOException {
        Still s = still(pw, ph);
        s.rows(photo, 0, pw, 0, ph);
        finish(s, sink);
    }

    /** {@link #finish(int[], int, int, Sink)} with the photo given in rows to {@code still}. */
    public void finish(Still still, Sink sink) throws IOException {
        if (last == null) throw new IllegalStateException("build first");
        int[] px = still.picture();
        int[] buf = new int[vw * vh];
        for (int j = 0; j < fadeFrames; j++) {
            double u = (j + 1) / (double) (fadeFrames + 1);
            double e = u * u * (3 - 2 * u);
            blend(last, px, (int) Math.round(e * 256), buf);
            sink.frame(buf, pts(framesOut++));
        }
        for (int j = 0; j < holdFrames; j++) sink.frame(px, pts(framesOut++));
        last = null;
    }

    /** Takes the saved photo ({@code pw} x {@code ph}, cut and turned) in rows, for {@link #finish(Still, Sink)}. */
    public Still still(int pw, int ph) {
        return new Still(pw, ph);
    }

    /**
     * The saved photo given in rows (in any order, each once), scaled to the video's size by area averages in linear
     * light, so a small star keeps its light as the grid pictures, rendered from linear light at the video's size, show
     * it; then cut as the video is.
     */
    public final class Still {
        private final int pw, ph;
        private final Span xs;
        /** Per photo row r: the video rows it adds to, entries first[r] to first[r + 1] - 1, and its share of each. */
        private final int[] first, outRow;
        private final float[] outW;
        private final float[] acc;
        private long fed;

        private Still(int pw, int ph) {
            if (pw < 1 || ph < 1) throw new IllegalArgumentException("no photo");
            this.pw = pw;
            this.ph = ph;
            xs = Span.of(0, pw / (double) fw, fw);
            Span ys = Span.of(0, ph / (double) fh, fh);
            first = new int[ph + 1];
            for (int y = 0; y < fh; y++) {
                for (int m = ys.start[y]; m < ys.start[y + 1]; m++) first[ys.index[m] + 1]++;
            }
            for (int r = 0; r < ph; r++) first[r + 1] += first[r];
            outRow = new int[first[ph]];
            outW = new float[first[ph]];
            int[] at = Arrays.copyOf(first, ph);
            for (int y = 0; y < fh; y++) {
                for (int m = ys.start[y]; m < ys.start[y + 1]; m++) {
                    int r = ys.index[m], e = at[r]++;
                    outRow[e] = y;
                    outW[e] = ys.weight[m];
                }
            }
            acc = new float[fw * fh * 3];
        }

        /** Photo rows y0 to y0 + count - 1, row r at argb[off + (r - y0) * stride]. */
        public void rows(int[] argb, int off, int stride, int y0, int count) {
            if (y0 < 0 || y0 + count > ph) throw new IllegalArgumentException("rows " + y0 + "+" + count + " of " + ph);
            float[] line = new float[fw * 3];
            for (int j = 0; j < count; j++) {
                int r = y0 + j, o = off + j * stride;
                if (first[r] == first[r + 1]) continue;
                for (int x = 0; x < fw; x++) {
                    float rr = 0, gg = 0, bb = 0;
                    for (int e = xs.start[x]; e < xs.start[x + 1]; e++) {
                        int v = argb[o + xs.index[e]];
                        float w = xs.weight[e];
                        rr += w * DECODE[(v >> 16) & 0xFF];
                        gg += w * DECODE[(v >> 8) & 0xFF];
                        bb += w * DECODE[v & 0xFF];
                    }
                    line[x * 3] = rr;
                    line[x * 3 + 1] = gg;
                    line[x * 3 + 2] = bb;
                }
                for (int e = first[r]; e < first[r + 1]; e++) {
                    int a0 = outRow[e] * fw * 3;
                    float w = outW[e];
                    for (int i = 0; i < fw * 3; i++) acc[a0 + i] += w * line[i];
                }
            }
            fed += count;
        }

        /** The photo at the video's size and cut, once every row is in. */
        int[] picture() {
            if (fed != ph) throw new IllegalStateException(fed + " of the photo's " + ph + " rows given");
            int[] out = new int[fw * fh];
            for (int i = 0; i < out.length; i++) {
                out[i] = 0xFF000000 | (enc(acc[i * 3]) << 16) | (enc(acc[i * 3 + 1]) << 8) | enc(acc[i * 3 + 2]);
            }
            return cut(out, fw, fh);
        }
    }

    /** The order in which the frames adding up show the states (1 frame, 2 frames, ... n frames), and the blends between. */
    private final class Emitter {
        final int n;
        final int[] sa, sb, st;
        final boolean[] needed;
        final Sink sink;
        final TreeMap<Integer, int[]> states = new TreeMap<Integer, int[]>();
        int next;
        int[] lastState;
        final int[] buf = new int[vw * vh];

        Emitter(int n, Sink sink) {
            this.n = n;
            this.sink = sink;
            sa = new int[buildFrames];
            sb = new int[buildFrames];
            st = new int[buildFrames];
            steps(n, buildFrames, !src.trails(), sa, sb, st);
            needed = new boolean[n + 1];
            for (int i = 0; i < buildFrames; i++) {
                needed[sa[i]] = true;
                needed[sb[i]] = true;
            }
            needed[n] = true;
        }

        boolean needs(int k) {
            return needed[k];
        }

        /** State k (k frames added up), as the video shows it: sends every frame that has both its states. */
        void state(int k, int[] argb) throws IOException {
            states.put(k, argb);
            if (k == n) lastState = argb;
            while (next < buildFrames && sb[next] <= k) {
                int[] A = states.get(sa[next]), B = states.get(sb[next]);
                if (A == null || B == null) throw new IllegalStateException("state " + sa[next] + " or " + sb[next] + " missing");
                int[] f = A;
                if (st[next] != 0 && A != B) {
                    blend(A, B, st[next], buf);
                    f = buf;
                }
                long t = System.nanoTime();
                sink.frame(f, pts(framesOut++));
                sendNs += System.nanoTime() - t;
                next++;
            }
            // what no frame still to come shows lets go (the last state stays for the fade)
            int keepFrom = next < buildFrames ? sa[next] : n;
            Iterator<Map.Entry<Integer, int[]>> it = states.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, int[]> e = it.next();
                if (e.getKey() < keepFrom && e.getKey() != k) it.remove();
            }
        }

        void end() {
            if (next < buildFrames || lastState == null) throw new IllegalStateException("the frames adding up stopped at " + next);
            states.clear();
        }
    }

    /**
     * Frame i of {@code frames} shows (1 - t / 256) x state a[i] + t / 256 x state b[i], states counted in frames added
     * up (1 to n): along n^u (geometric, equal time for each doubling) or 1 + u (n - 1), u from 0 to 1. Where the states
     * are denser than the frames, each frame shows the nearest one, never one before the frame before it.
     */
    static void steps(int n, int frames, boolean geometric, int[] a, int[] b, int[] t) {
        int prev = 1;
        for (int i = 0; i < frames; i++) {
            double x = position(n, frames, geometric, i);
            double xn = i + 1 < frames ? position(n, frames, geometric, i + 1) : x + 1;
            if (xn - x >= 1 || n == 1) {
                int k = Math.max(prev, (int) Math.round(x));
                k = Math.min(n, k);
                a[i] = b[i] = k;
                t[i] = 0;
            } else {
                int k = Math.max(1, Math.min(n, (int) Math.floor(x)));
                int q = (int) Math.round((x - k) * 256);
                if (k >= n) {
                    k = n;
                    q = 0;
                }
                if (q >= 256) {
                    k = Math.min(n, k + 1);
                    q = 0;
                }
                a[i] = k;
                b[i] = q > 0 ? k + 1 : k;
                t[i] = q;
            }
            prev = b[i];
        }
    }

    static double position(int n, int frames, boolean geometric, int i) {
        if (frames <= 1 || n <= 1) return n;
        double u = i / (double) (frames - 1);
        double x = geometric ? Math.pow(n, u) : 1 + u * (n - 1);
        return Math.max(1, Math.min(n, x));
    }

    // ------------------------------------------------------------------------------------------------
    // Deep sky
    // ------------------------------------------------------------------------------------------------

    private int buildDeep(Emitter em, Progress progress) throws IOException {
        final int np = gw * gh;
        final float[] acc = new float[np * 3], accw = new float[np];
        float[] trail = null;
        final float minT = src.minTransparency;
        makeGains();
        final float[] fin = finished();
        final float[] bgG = new float[np];
        for (int y = 0; y < gh; y++) {
            double Y = cy0 + (y + 0.5) * sy;
            for (int x = 0; x < gw; x++) {
                bgG[y * gw + x] = src.target != null ? src.target.level(1, cx0 + (x + 0.5) * sx, Y) : 0f;
            }
        }
        final double[] nodeB = WarpGrid.nodeBearings(src.model, W, H, STEP);
        final int nx = WarpGrid.nodesX(W, STEP), ny = WarpGrid.nodesY(H, STEP);
        final float[] nodes = new float[nx * ny * NV];
        final long[] maskBits = new long[(np + 63) >> 6], keepBits = new long[(np + 63) >> 6];
        final RgbImage S = new RgbImage(gw, gh);
        S.clip = clipOnGrid();
        final float hx = (float) (sx / 2), hy = (float) (sy / 2);
        short[] raw = new short[0];
        float[] d = new float[0];
        int renders = 0, k = 0;
        int n = src.plans.size();
        for (final SharpStacker.Plan p : src.plans) {
            k++;
            final WarpGrid g = WarpGrid.build(src.model, nodeB, W, H, STEP, p.R);
            nodeValues(p, g, nx, ny, nodes);
            final boolean anyMask = corridors(p.masks, maskBits);
            final boolean anyKeep = corridors(p.keep, keepBits);
            if (anyKeep && trail == null) {
                trail = new float[np * 3];
                Arrays.fill(trail, Float.NaN);
            }
            final float[] tr3 = trail;
            final FrameStore fs = p.frames != null ? p.frames : src.store;
            final HotPixels hp = p.hot != null ? p.hot : src.hot;
            for (int band = 0; band < gh; band += BAND) {
                final int gy0 = band, gy1 = Math.min(gh, gy0 + BAND);
                int ya = (int) Math.floor(cy0 + gy0 * sy), yb = (int) Math.ceil(cy0 + gy1 * sy);
                float[] mr = g.mappedRowRange(Math.max(0, ya), Math.min(H, yb));
                if (mr[0] > mr[1]) continue;
                int rs = Math.max(0, (int) Math.floor(mr[0] - hy) - 6), re = Math.min(H, (int) Math.ceil(mr[1] + hy) + 7);
                rs &= ~1;
                if (re - rs < 8) continue;
                int need = (re - rs) * W;
                if (raw.length < need) {
                    raw = new short[need];
                    d = new float[need];
                }
                long ta = System.nanoTime();
                load(fs, hp, p.unit, p.invScale, p.black, p.pattern, rs, re, raw, d);
                long tb = System.nanoTime();
                readNs += tb - ta;
                final float[] dd = d;
                final int frs = rs, fre = re;
                Parallel.forRange(gy1 - gy0, 2, new Parallel.Range() {
                    @Override
                    public void run(int j0, int j1) {
                        float[] rx = new float[nx], ry = new float[nx], row = new float[nx * NV], rgb = new float[3];
                        for (int j = j0; j < j1; j++) {
                            int y = gy0 + j;
                            double Y = cy0 + (y + 0.5) * sy;
                            g.row(Y, rx, ry);
                            nodeRow(nodes, nx, ny, Y, row);
                            for (int x = 0; x < gw; x++) {
                                int ix = colIx[x];
                                float t = colT[x];
                                float mx = rx[ix] + (rx[ix + 1] - rx[ix]) * t, my = ry[ix] + (ry[ix + 1] - ry[ix]) * t;
                                if (!(mx - hx >= 2f && my - hy >= 2f && mx + hx <= W - 2f && my + hy <= H - 2f)) continue;
                                if (my - hy - 2f < frs || my + hy + 2f > fre) continue;
                                int q = ix * NV;
                                float tr = row[q + N_T] + (row[q + NV + N_T] - row[q + N_T]) * t;
                                if (tr < minT) continue;
                                float w = row[q + N_W] + (row[q + NV + N_W] - row[q + N_W]) * t;
                                if (!(w > 0)) continue;
                                int pix = y * gw + x;
                                if (anyMask && bit(maskBits, pix)) continue;
                                if (src.ground != null && src.ground.nearAt(mx, my)) continue;
                                boxRgb(dd, frs, mx, my, hx, hy, rgb);
                                float sc = row[q + N_SC] + (row[q + NV + N_SC] - row[q + N_SC]) * t;
                                float sig = row[q + N_SG] + (row[q + NV + N_SG] - row[q + N_SG]) * t;
                                float v0 = rgb[0] * sc + row[q + N_OFF] + (row[q + NV + N_OFF] - row[q + N_OFF]) * t;
                                float v1 = rgb[1] * sc + row[q + N_OFF + 1] + (row[q + NV + N_OFF + 1] - row[q + N_OFF + 1]) * t;
                                float v2 = rgb[2] * sc + row[q + N_OFF + 2] + (row[q + NV + N_OFF + 2] - row[q + N_OFF + 2]) * t;
                                int k3 = pix * 3;
                                float bg = bgG[pix];
                                if (anyKeep && bit(keepBits, pix)) {
                                    // a kept streak goes on top at full brightness, as the stack keeps it, from its frame on
                                    float run = accw[pix] > 0 ? acc[k3 + 1] / accw[pix] : bg;
                                    if (v1 - run > CLIP_K * sig + CLIP_SHARE * Math.max(0f, run - bg)) {
                                        if (!(tr3[k3 + 1] >= v1)) {
                                            tr3[k3] = v0;
                                            tr3[k3 + 1] = v1;
                                            tr3[k3 + 2] = v2;
                                        }
                                        continue;
                                    }
                                } else {
                                    float cen = fin[k3 + 1];
                                    if (v1 - cen > CLIP_K * sig + CLIP_SHARE * Math.max(0f, cen - bg)) continue;
                                }
                                acc[k3] += w * v0;
                                acc[k3 + 1] += w * v1;
                                acc[k3 + 2] += w * v2;
                                accw[pix] += w;
                            }
                        }
                    }
                });
                addNs += System.nanoTime() - tb;
                if (progress.cancelled()) throw new CancelledException();
            }
            if (em.needs(k)) {
                long tr = System.nanoTime();
                em.state(k, stateDeep(S, acc, accw, trail, fin));
                renderNs += System.nanoTime() - tr;
                renders++;
            }
            progress.update("Making the photo's video", k / (float) n);
        }
        return renders;
    }

    /** The finished stack on the grid (area averages), before the lens shading's correction: [pixel * 3 + c]. */
    private float[] finished() {
        final float[] out = new float[gw * gh * 3];
        final Span xs = Span.of(cx0, sx, gw), ys = Span.of(cy0, sy, gh);
        final int sw = stack.width;
        Parallel.forRange(gh, 8, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] gr = new float[gw], gg = new float[gw], gb = new float[gw];
                for (int y = j0; y < j1; y++) {
                    int o = y * gw * 3;
                    for (int m = ys.start[y]; m < ys.start[y + 1]; m++) {
                        int sy0 = ys.index[m];
                        float wy = ys.weight[m];
                        for (int c = 0; c < 3; c++) {
                            float[] ch = stack.channel(c);
                            int row = sy0 * sw;
                            for (int x = 0; x < gw; x++) {
                                float acc = 0;
                                for (int e = xs.start[x]; e < xs.start[x + 1]; e++) acc += xs.weight[e] * ch[row + xs.index[e]];
                                out[o + x * 3 + c] += wy * acc;
                            }
                        }
                    }
                    gainRow(y, gr, gg, gb);
                    for (int x = 0; x < gw; x++) {
                        int k = o + x * 3;
                        out[k] /= gr[x];
                        out[k + 1] /= gg[x];
                        out[k + 2] /= gb[x];
                    }
                }
            }
        });
        return out;
    }

    /**
     * Where the finished stack's samples were clipped (the most of any pixel under each grid pixel), so the renderer
     * makes those star cores white as in the photo: averaged down to the grid, a clipped core no longer reaches the clip
     * level, and its green, which clips first, would leave it magenta. Null without a stack or its clip map.
     */
    private byte[] clipOnGrid() {
        if (stack == null || stack.clip == null) return null;
        final byte[] out = new byte[gw * gh];
        final Span xs = Span.of(cx0, sx, gw), ys = Span.of(cy0, sy, gh);
        final byte[] cl = stack.clip;
        final int sw = stack.width;
        Parallel.forRange(gh, 16, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int y = j0; y < j1; y++) {
                    for (int x = 0; x < gw; x++) {
                        int m = 0;
                        for (int a = ys.start[y]; a < ys.start[y + 1]; a++) {
                            int row = ys.index[a] * sw;
                            for (int e = xs.start[x]; e < xs.start[x + 1]; e++) m = Math.max(m, cl[row + xs.index[e]] & 0xFF);
                        }
                        out[y * gw + x] = (byte) m;
                    }
                }
            }
        });
        return out;
    }

    /** The state after the frames added so far, rendered as the photo is, cut and turned as the video shows it. */
    private int[] stateDeep(final RgbImage S, final float[] acc, final float[] accw, final float[] trail, final float[] fin) {
        Parallel.forRange(gh, 8, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] gr = new float[gw], gg = new float[gw], gb = new float[gw];
                for (int y = j0; y < j1; y++) {
                    gainRow(y, gr, gg, gb);
                    for (int x = 0; x < gw; x++) {
                        int pix = y * gw + x, k3 = pix * 3;
                        float w = accw[pix];
                        float r, g, b;
                        if (w > 0) {
                            float iw = 1f / w;
                            r = acc[k3] * iw;
                            g = acc[k3 + 1] * iw;
                            b = acc[k3 + 2] * iw;
                        } else {
                            r = fin[k3];
                            g = fin[k3 + 1];
                            b = fin[k3 + 2];
                        }
                        if (trail != null) {
                            float t1 = trail[k3 + 1];
                            if (t1 == t1 && t1 > g) {
                                r = Math.max(r, trail[k3]);
                                g = t1;
                                b = Math.max(b, trail[k3 + 2]);
                            }
                        }
                        S.r[pix] = r * gr[x];
                        S.g[pix] = g * gg[x];
                        S.b[pix] = b * gb[x];
                    }
                }
            }
        });
        return render(S);
    }

    /** A frame's normalisation at every warp node, as the stack worked it out ({@link SharpStacker}'s normNodes). */
    private void nodeValues(SharpStacker.Plan p, WarpGrid g, int nx, int ny, float[] a) {
        boolean match = p.background != null && src.target != null;
        float minT = src.minTransparency;
        for (int iy = 0; iy < ny; iy++) {
            for (int ix = 0; ix < nx; ix++) {
                int k = iy * nx + ix;
                double X = ix * STEP, Y = iy * STEP;
                float mx = g.mx[k], my = g.my[k];
                boolean on = mx > -1e5f && my > -1e5f;
                float tr = p.transparency != null ? p.transparency.at(X, Y) : 1f;
                float tc = Stats.clamp(tr, minT, SharpStacker.MAX_TRANSPARENCY);
                float sg = p.signal > 0 ? p.signal : 1f;
                float sc = 1f / (tc * sg);
                float r = sc;
                if (match && on) {
                    float sf = p.background.sigma(1, mx, my), st = src.target.sigma(1, X, Y);
                    if (sf > 0 && st > 0) r = sf / (tc * sg * st);
                }
                int o = k * NV;
                a[o + N_SC] = sc;
                a[o + N_W] = p.weight * Math.min(4f, 1f / (r * r));
                a[o + N_T] = tr;
                float sigG = 1f;
                for (int c = 0; c < 3; c++) {
                    float bg = 0f;
                    if (p.background != null && on) {
                        bg = p.background.level(c, mx, my);
                        if (c == 1) sigG = p.background.sigma(1, mx, my);
                    }
                    a[o + N_OFF + c] = match ? src.target.level(c, X, Y) - bg * sc : 0f;
                }
                a[o + N_SG] = Math.max(1e-3f, sc * sigG);
            }
        }
    }

    /** Node values at continuous reference row Y, interpolated between the node rows like {@link WarpGrid#row}. */
    private static void nodeRow(float[] a, int nx, int ny, double Y, float[] out) {
        double gy = Y / STEP;
        int iy = (int) gy;
        if (iy > ny - 2) iy = ny - 2;
        if (iy < 0) iy = 0;
        float fy = (float) (gy - iy);
        int r0 = iy * nx * NV, r1 = r0 + nx * NV;
        for (int i = 0; i < nx * NV; i++) out[i] = a[r0 + i] + (a[r1 + i] - a[r0 + i]) * fy;
    }

    /**
     * Sets the bits of the grid pixels whose footprint reaches any of the corridors {@code c} (x0, y0, x1, y1, half width
     * each, in the stack's coordinates); false, and nothing set, if there are none.
     */
    private boolean corridors(float[] c, long[] bits) {
        if (c == null || c.length < 5) return false;
        Arrays.fill(bits, 0L);
        boolean any = false;
        double reach = 0.5 * Math.max(sx, sy);
        for (int s = 0; s + 4 < c.length; s += 5) {
            double ax = c[s], ay = c[s + 1], bx = c[s + 2], by = c[s + 3], hw = c[s + 4] + reach;
            double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy, hw2 = hw * hw;
            int xa = Math.max(0, (int) Math.floor((Math.min(ax, bx) - hw - cx0) / sx));
            int xb = Math.min(gw - 1, (int) Math.ceil((Math.max(ax, bx) + hw - cx0) / sx));
            int ya = Math.max(0, (int) Math.floor((Math.min(ay, by) - hw - cy0) / sy));
            int yb = Math.min(gh - 1, (int) Math.ceil((Math.max(ay, by) + hw - cy0) / sy));
            for (int y = ya; y <= yb; y++) {
                double py = cy0 + (y + 0.5) * sy - ay;
                for (int x = xa; x <= xb; x++) {
                    double px = cx0 + (x + 0.5) * sx - ax;
                    double t = l2 > 0 ? Math.max(0, Math.min(1, (px * dx + py * dy) / l2)) : 0;
                    double ex = px - t * dx, ey = py - t * dy;
                    if (ex * ex + ey * ey > hw2) continue;
                    int pix = y * gw + x;
                    bits[pix >> 6] |= 1L << (pix & 63);
                    any = true;
                }
            }
        }
        return any;
    }

    private static boolean bit(long[] bits, int pix) {
        return (bits[pix >> 6] & (1L << (pix & 63))) != 0;
    }

    // ------------------------------------------------------------------------------------------------
    // Star trails
    // ------------------------------------------------------------------------------------------------

    private int buildTrails(Emitter em, Progress progress) throws IOException {
        final int np = gw * gh;
        final float[] mean = new float[np * 3], top = new float[np * 3], topY = new float[np];
        Arrays.fill(topY, -Float.MAX_VALUE);
        makeGains();
        final float[] nb = new float[3];
        for (int c = 0; c < 3; c++) nb[c] = 1f / Math.max(0.05f, src.spec.camNeutral[c] / Math.max(0.05f, src.spec.camNeutral[1]));
        final RgbImage S = new RgbImage(gw, gh);
        S.clip = clipOnGrid();
        final float hx = (float) (sx / 2), hy = (float) (sy / 2);
        short[] raw = new short[0];
        float[] d = new float[0];
        float[] sigma = null;
        int renders = 0, k = 0;
        int n = src.units.size();
        for (final TrailsStacker.Unit u : src.units) {
            k++;
            final float inv = 1f / k;
            for (int band = 0; band < gh; band += BAND) {
                final int gy0 = band, gy1 = Math.min(gh, gy0 + BAND);
                int rs = Math.max(0, (int) Math.floor(cy0 + gy0 * sy - hy) - 4) & ~1;
                int re = Math.min(H, (int) Math.ceil(cy0 + gy1 * sy + hy) + 5);
                int need = (re - rs) * W;
                if (raw.length < need) {
                    raw = new short[need];
                    d = new float[need];
                }
                long ta = System.nanoTime();
                load(src.store, src.hot, u.unit, u.invScale, u.black, null, rs, re, raw, d);
                final long tb = System.nanoTime();
                readNs += tb - ta;
                final float[] dd = d;
                final int frs = rs, fre = re;
                Parallel.forRange(gy1 - gy0, 2, new Parallel.Range() {
                    @Override
                    public void run(int j0, int j1) {
                        float[] rgb = new float[3];
                        for (int j = j0; j < j1; j++) {
                            int y = gy0 + j;
                            float my = (float) (cy0 + (y + 0.5) * sy);
                            if (my - hy - 2f < frs || my + hy + 2f > fre) continue;
                            for (int x = 0; x < gw; x++) {
                                float mx = (float) (cx0 + (x + 0.5) * sx);
                                if (!(mx - hx >= 0.5f && mx + hx <= W - 0.5f && my - hy >= 0.5f && my + hy <= H - 0.5f)) continue;
                                boxRgb(dd, frs, mx, my, hx, hy, rgb);
                                int pix = y * gw + x, k3 = pix * 3;
                                mean[k3] += (rgb[0] - mean[k3]) * inv;
                                mean[k3 + 1] += (rgb[1] - mean[k3 + 1]) * inv;
                                mean[k3 + 2] += (rgb[2] - mean[k3 + 2]) * inv;
                                // brightest by a colour-flattened luminance, so a red star doesn't make red win
                                float l = 0.25f * rgb[0] * nb[0] + 0.5f * rgb[1] * nb[1] + 0.25f * rgb[2] * nb[2];
                                if (l > topY[pix]) {
                                    topY[pix] = l;
                                    top[k3] = rgb[0];
                                    top[k3 + 1] = rgb[1];
                                    top[k3 + 2] = rgb[2];
                                }
                            }
                        }
                    }
                });
                addNs += System.nanoTime() - tb;
                if (progress.cancelled()) throw new CancelledException();
            }
            if (sigma == null) sigma = trailNoise(mean, nb);
            if (em.needs(k)) {
                long tr = System.nanoTime();
                em.state(k, stateTrails(S, k, mean, top, topY, sigma, nb));
                renderNs += System.nanoTime() - tr;
                renders++;
            }
            progress.update("Making the photo's video", k / (float) n);
        }
        return renders;
    }

    /** Regions of the grid over which one frame's luminance noise is measured (8 x 6, like the stack's regions). */
    private static final int TGX = 8, TGY = 6;

    /** One frame's luminance noise per region, from differences of grid pixels two apart (stars, being few, don't count). */
    private float[] trailNoise(float[] f, float[] nb) {
        float[] out = new float[TGX * TGY];
        float[] buf = new float[(gw / TGX + 2) * (gh / TGY + 2)];
        for (int cy = 0; cy < TGY; cy++) {
            for (int cx = 0; cx < TGX; cx++) {
                int xa = cx * gw / TGX, xb = (cx + 1) * gw / TGX, ya = cy * gh / TGY, yb = (cy + 1) * gh / TGY;
                int m = 0;
                for (int y = ya; y < yb; y += 2) {
                    for (int x = xa; x + 2 < xb && m < buf.length; x += 2) {
                        int i = (y * gw + x) * 3, j = i + 6;
                        float l0 = 0.25f * f[i] * nb[0] + 0.5f * f[i + 1] * nb[1] + 0.25f * f[i + 2] * nb[2];
                        float l1 = 0.25f * f[j] * nb[0] + 0.5f * f[j + 1] * nb[1] + 0.25f * f[j + 2] * nb[2];
                        buf[m++] = l1 - l0;
                    }
                }
                out[cy * TGX + cx] = m >= 8 ? Math.max(1e-4f, Stats.madSigma(buf, m, Stats.median(buf, m)) / (float) Math.sqrt(2)) : 1f;
            }
        }
        return out;
    }

    /**
     * Star trails after k frames: their average, and each pixel's brightest moment where it stands out of the noise
     * (through the soft threshold the trails stack uses, so the sky's noise isn't lifted), with the lens shading out.
     */
    private int[] stateTrails(final RgbImage S, int k, final float[] mean, final float[] top, final float[] topY, final float[] sigma,
                              final float[] nb) {
        final float kk = (float) Math.max(3.0, Math.sqrt(2 * Math.log(Math.max(2, k))) + 1.0);
        Parallel.forRange(gh, 8, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] gr = new float[gw], gg = new float[gw], gb = new float[gw];
                for (int y = j0; y < j1; y++) {
                    gainRow(y, gr, gg, gb);
                    for (int x = 0; x < gw; x++) {
                        int pix = y * gw + x, k3 = pix * 3;
                        float r = mean[k3], g = mean[k3 + 1], b = mean[k3 + 2];
                        float lm = 0.25f * r * nb[0] + 0.5f * g * nb[1] + 0.25f * b * nb[2];
                        float dl = topY[pix] - lm;
                        if (dl > 0) {
                            float sd = BackgroundGrid.interp(sigma, TGX, TGY, gw, gh, x + 0.5, y + 0.5);
                            float u = dl / (kk * sd);
                            float u4 = u * u * u * u;
                            float w = u4 / (1f + u4);
                            r += (top[k3] - r) * w;
                            g += (top[k3 + 1] - g) * w;
                            b += (top[k3 + 2] - b) * w;
                        }
                        S.r[pix] = r * gr[x];
                        S.g[pix] = g * gg[x];
                        S.b[pix] = b * gb[x];
                    }
                }
            }
        });
        return render(S);
    }

    // ------------------------------------------------------------------------------------------------
    // Reading the frames
    // ------------------------------------------------------------------------------------------------

    /** Rows [rs, re) of a stored frame into {@code d}: defective pixels mended, black taken off, the sensor's pattern out. */
    private void load(FrameStore fs, HotPixels hp, int unit, final float invScale, final float[] black, SensorPattern.Frame pattern,
                      final int rs, final int re, final short[] raw, final float[] d) throws IOException {
        fs.readBand(unit, rs, re, raw);
        if (hp != null) hp.fix(raw, rs, re, rs);
        Parallel.forRange(re - rs, 32, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int j = j0; j < j1; j++) {
                    int y = rs + j, o = j * W, py = (y & 1) << 1;
                    float be = black[py], bo = black[py | 1];
                    for (int x = 0; x < W; x += 2) {
                        d[o + x] = (raw[o + x] & 0xFFFF) * invScale - be;
                        d[o + x + 1] = (raw[o + x + 1] & 0xFFFF) * invScale - bo;
                    }
                }
            }
        });
        if (pattern != null) pattern.subtract(d, rs, re);
    }

    /** R, G and B over the box (mx +- hx, my +- hy) of the frame, each from its own photosites ({@link #box}). */
    private void boxRgb(float[] d, int rs, float mx, float my, float hx, float hy, float[] rgb) {
        rgb[0] = box(d, rs, pR, mx, my, hx, hy);
        float g = box(d, rs, pG1, mx, my, hx, hy);
        if (pG2 != pG1) g = 0.5f * (g + box(d, rs, pG2, mx, my, hx, hy));
        rgb[1] = g;
        rgb[2] = box(d, rs, pB, mx, my, hx, hy);
    }

    /**
     * Area average of plane p over the box (mx +- hx, my +- hy), each photosite standing for the 2 x 2 pixel square
     * centred on it (pixel k spans [k, k + 1)), so every colour is sampled at its own place.
     */
    private float box(float[] d, int rs, int p, float mx, float my, float hx, float hy) {
        int ox = p & 1, oy = p >> 1;
        float xa = mx - hx, xb = mx + hx, ya = my - hy, yb = my + hy;
        // photosite u of the plane covers [2u + ox - 0.5, 2u + ox + 1.5)
        int u0 = (int) Math.floor((xa - ox + 0.5f) * 0.5f), u1 = (int) Math.floor((xb - ox + 0.5f) * 0.5f);
        int v0 = (int) Math.floor((ya - oy + 0.5f) * 0.5f), v1 = (int) Math.floor((yb - oy + 0.5f) * 0.5f);
        float sum = 0, area = 0;
        for (int v = v0; v <= v1; v++) {
            float c0 = 2 * v + oy - 0.5f;
            float wy = Math.min(yb, c0 + 2f) - Math.max(ya, c0);
            if (wy <= 0) continue;
            int row = (2 * v + oy - rs) * W + ox;
            float s = 0, a = 0;
            for (int u = u0; u <= u1; u++) {
                float x0 = 2 * u + ox - 0.5f;
                float wx = Math.min(xb, x0 + 2f) - Math.max(xa, x0);
                if (wx <= 0) continue;
                s += wx * d[row + 2 * u];
                a += wx;
            }
            sum += wy * s;
            area += wy * a;
        }
        return area > 0 ? sum / area : 0f;
    }

    // ------------------------------------------------------------------------------------------------
    // Rendering, framing and the photo
    // ------------------------------------------------------------------------------------------------

    /** The lens shading's gains at every grid row and every GAIN_STEP-th column, in the stack's coordinates. */
    private void makeGains() {
        final ShadingMap sh = src.spec.shading;
        if (sh == null || sh.isUnity()) {
            gains = null;
            return;
        }
        gn = gw / GAIN_STEP + 2;
        final float[][] gs = new float[3][gh * gn];
        Parallel.forRange(gh, 16, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int y = j0; y < j1; y++) {
                    double Y = cy0 + (y + 0.5) * sy;
                    for (int j = 0; j < gn; j++) {
                        double X = cx0 + (j * GAIN_STEP + 0.5) * sx;
                        int o = y * gn + j;
                        gs[0][o] = sh.gain(pR, X, Y);
                        gs[1][o] = pG2 != pG1 ? 0.5f * (sh.gain(pG1, X, Y) + sh.gain(pG2, X, Y)) : sh.gain(pG1, X, Y);
                        gs[2][o] = sh.gain(pB, X, Y);
                    }
                }
            }
        });
        gains = gs;
    }

    /** The lens shading's gains along grid row y (1 without a shading map). */
    private void gainRow(int y, float[] gr, float[] gg, float[] gb) {
        float[][] gs = gains;
        if (gs == null) {
            Arrays.fill(gr, 1f);
            Arrays.fill(gg, 1f);
            Arrays.fill(gb, 1f);
            return;
        }
        int o = y * gn;
        for (int x = 0; x < gw; x++) {
            int j = x / GAIN_STEP;
            float t = (x - j * GAIN_STEP) / (float) GAIN_STEP;
            gr[x] = gs[0][o + j] + (gs[0][o + j + 1] - gs[0][o + j]) * t;
            gg[x] = gs[1][o + j] + (gs[1][o + j + 1] - gs[1][o + j]) * t;
            gb[x] = gs[2][o + j] + (gs[2][o + j + 1] - gs[2][o + j]) * t;
        }
    }

    /** A grid picture rendered as the photo is (its colour by its own noise), cut and turned as the video shows it. */
    private int[] render(RgbImage S) {
        // the photo's glow (1.10): its lights as the photo's render finds them in the stack (once; kept with the analysis)
        if (params.glow > 0 && stack != null) Renderer.glow(stack, a, a, 1);
        float noise = noise(S);
        float ns = a.noiseY > 0 && noise > 0 ? noise / a.noiseY : (float) (1 / sx);
        int[] px = Renderer.renderScaled(S, a, params, (float) sx, ns);
        if (framing != null) px = framing.apply(px);
        return cut(px, fw, fh);
    }

    /**
     * The luminance noise of a grid picture: the spread of differences of pixels two apart along rows (every fourth
     * row), each a sigma times the square root of 2; the few stars among them don't move the median.
     */
    float noise(RgbImage S) {
        float[] m = a.m;
        float cr = KR * m[0] + KG * m[3] + KB * m[6], cg = KR * m[1] + KG * m[4] + KB * m[7], cb = KR * m[2] + KG * m[5] + KB * m[8];
        int per = Math.max(1, (gw - 2) / 2);
        int rows = (gh + 3) / 4;
        float[] dif = new float[per * rows];
        int n = 0;
        for (int y = 0; y < gh; y += 4) {
            int o = y * gw;
            for (int x = 0; x + 2 < gw; x += 2) {
                int i = o + x, j = i + 2;
                float l0 = cr * S.r[i] + cg * S.g[i] + cb * S.b[i], l1 = cr * S.r[j] + cg * S.g[j] + cb * S.b[j];
                if (n < dif.length) dif[n++] = l1 - l0;
            }
        }
        if (n < 16) return 0f;
        float med = Stats.median(dif, n);
        return Stats.madSigma(dif, n, med) / (float) Math.sqrt(2);
    }

    /** The video's part (vw x vh, centred) of a framed grid picture (w x h). */
    private int[] cut(int[] px, int w, int h) {
        if (w == vw && h == vh) return px;
        int[] out = new int[vw * vh];
        for (int y = 0; y < vh; y++) System.arraycopy(px, (cutY + y) * w + cutX, out, y * vw, vw);
        return out;
    }

    /** (256 - t) / 256 of a and t / 256 of b, per channel. */
    static void blend(int[] a, int[] b, int t, int[] out) {
        int s = 256 - t;
        for (int i = 0; i < out.length; i++) {
            int p = a[i], q = b[i];
            int r = (((p >> 16) & 0xFF) * s + ((q >> 16) & 0xFF) * t + 128) >> 8;
            int g = (((p >> 8) & 0xFF) * s + ((q >> 8) & 0xFF) * t + 128) >> 8;
            int bl = ((p & 0xFF) * s + (q & 0xFF) * t + 128) >> 8;
            out[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
        }
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

    /**
     * Which source pixels make up each output pixel of a resampling by area, along one axis: output pixel i covers
     * [o + i s, o + (i + 1) s) of the source, and source pixel k spans [k, k + 1). Entries start[i] to start[i + 1] - 1.
     */
    static final class Span {
        int[] start, index;
        float[] weight, total;

        static Span of(double origin, double scale, int n) {
            Span s = new Span();
            s.start = new int[n + 1];
            s.total = new float[n];
            int cap = (int) Math.ceil(scale) + 2;
            s.index = new int[n * cap];
            s.weight = new float[n * cap];
            int m = 0;
            for (int i = 0; i < n; i++) {
                s.start[i] = m;
                double a = origin + i * scale, b = origin + (i + 1) * scale;
                float tot = 0;
                for (int k = (int) Math.floor(a); k < b; k++) {
                    double w = Math.min(b, k + 1) - Math.max(a, k);
                    if (w <= 1e-9) continue;
                    s.index[m] = k;
                    s.weight[m] = (float) w;
                    tot += (float) w;
                    m++;
                }
                s.total[i] = tot;
            }
            s.start[n] = m;
            for (int i = 0; i < n; i++) {
                // weights as shares of the pixel, so the source is averaged
                float tot = s.total[i];
                for (int e = s.start[i]; e < s.start[i + 1]; e++) s.weight[e] /= tot;
                s.total[i] = 1f;
            }
            return s;
        }
    }
}
