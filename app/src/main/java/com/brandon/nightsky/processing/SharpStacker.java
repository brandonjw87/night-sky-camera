package com.brandon.nightsky.processing;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Star-aligned integration of the stored units into one full-colour image, Bayer-drizzle style.
 * <p>
 * The sky drifts across the sensor during the session, so over the frames every output pixel is visited by
 * red, green and blue photosites at many sub-pixel offsets. Each output pixel and channel takes, from every
 * frame, the nearest photosite of that colour at the position the sky rotation maps it to, weighted by how
 * much a pixel-sized drop around that photosite overlaps the output pixel. All three colours then share the
 * same star shape (no colour fringes on undersampled stars), there is no interpolation ringing (no dark
 * moats around bright stars) and no per-frame demosaicing. Where the drift is too small to cover all colour
 * phases (very close to the celestial pole), bilinear interpolation of the planes fills in.
 * <p>
 * Every sample is normalised before it is compared or averaged: the frame's own smooth sky background
 * (measured on a grid of regions of the frame) is swapped for the session's target background, and the
 * starlight left over is scaled by the frame's transparency in that region relative to the clearest frames.
 * Thin cloud, a passing light dome or a brightening sky then doesn't make a frame look like an outlier. Each
 * sample is weighted by the inverse of its noise after normalisation (transparency squared over the
 * background variance), regions that a cloud covered get no weight at all, and all of this changes smoothly
 * between regions, so a frame clouded over in one part still adds its clear part without leaving a seam.
 * <p>
 * Outliers (satellites, planes, meteors, cosmic rays) are rejected per pixel and channel with a method that
 * suits the number of frames ({@link Rejection}). When memory allows, every frame's sample of a band of rows
 * is kept as a half float so the method can look at all of them at once: pass A collects them, the
 * rejection marks the outliers, pass B adds up the rest. Otherwise one-pass sigma clipping: pass A gathers a
 * noise-weighted mean and spread without the extreme values at both ends (so a satellite or a dark cloud edge
 * in one or two frames can't hide itself by inflating the spread), and pass B adds up the samples within the
 * limits. Each sample's noise allows for the smoothing of the interpolation that produced it, so frames
 * sampled at pixel centres (the reference frame) aren't clipped more than frames sampled between them.
 */
public final class SharpStacker {
    public static final class Plan {
        public int unit;
        /** Rotation taking reference-frame bearings to this unit's bearings. */
        public double[] R;
        /** Weight factor for the whole frame (1 = normal). */
        public float weight = 1f;
        /** stored value * invScale = per-frame ADU including black level */
        public float invScale = 1f;
        public final float[] black = new float[4];
        /** Capture time in seconds, for linear-fit clipping. */
        public double t;
        /** The frame's sky background and noise, in its own sensor coordinates; null leaves the frame as it is. */
        public BackgroundGrid background;
        /**
         * Per region, in reference coordinates: the frame's transparency relative to the clearest frames (1 =
         * clear). Interpolated between the region centres, it scales the starlight up to the clear level and
         * leaves the frame out wherever it is below {@link #minTransparency}. Null: 1 everywhere.
         */
        public LocalWeights transparency;
        /** The sensor's own pattern to take out of this frame as it is loaded ({@link SensorPattern}), or null. */
        public SensorPattern.Frame pattern;
        /**
         * Corridors where this frame's samples are left out (a satellite's or a plane's streak), in reference
         * coordinates: x0, y0, x1, y1 and half width per corridor; null for none.
         */
        public float[] masks;
        /**
         * Corridors where this frame's streak is kept at full brightness: its samples rejected as too bright there
         * go on top of the stack (whichever is brighter) instead of away. Same layout; null for none.
         */
        public float[] keep;
        /**
         * Where this frame is stored and its sensor's defective pixels, when they aren't the stacker's own (a frame
         * of another session in a merge, G1: each night is read from its own folder with its own hot pixels).
         */
        public FrameStore frames;
        public HotPixels hot;
        /**
         * The frame's starlight at full transparency relative to the stack's (1 = the same): another night's frames
         * (G1) may be longer, at another gain or through a clearer sky. It scales and weighs the frame like
         * transparency does, but never cuts it out.
         */
        public float signal = 1f;
    }

    public static final class Result {
        public RgbImage image;
        /**
         * Per plan: samples rejected as outliers (counted per channel), pixel samples on the frame, and pixel
         * samples that went into the stack.
         */
        public long[] rejected, samples, included;
        /** Per output row: first and one-past-last column with good coverage (-1 when none). */
        public int[] rowL, rowR;
        /** The rejection method asked for, and whether every sample was kept for it (false: one-pass sigma clipping). */
        public Rejection.Method method;
        public boolean keptSamples;
        public int bandRows;
        /** Samples from next to a cloud edge, judged against the frames clear all around, and how many of them went. */
        public long edgeSamples, edgeRejected;
        /** Pixel samples left out in the plans' corridors ({@link Plan#masks}). */
        public long masked;
        /**
         * With {@link #ground}: the output's pixels that no frame saw any sky at (as bits, row by row), which hold 0;
         * null otherwise.
         */
        public long[] empty;
    }

    public static final int STEP = 32;
    /** Clipping limits in units of the spread (the low side looser: dark outliers are rare and noise isn't). */
    public float kHigh = 3f, kLow = 4f;
    /** One-pass clipping: smallest spread, in units of each sample's expected noise. */
    public float sigmaFloor = 0.25f;
    /** Bytes for the per-band statistics (and kept samples). */
    public long memoryBudget = 48L << 20;
    /** Fraction of the frames a pixel needs to count as well covered (for the crop). */
    public float coverageFraction = 0.7f;
    /** How outliers are rejected; AUTO picks by the number of frames covering each pixel. */
    public Rejection.Method method = Rejection.Method.AUTO;
    /** The sky background (and its noise) every frame is matched to, in reference coordinates; null: no matching. */
    public BackgroundGrid target;
    /** Transparency below which a frame is left out (thick cloud); its starlight is scaled by at most the inverse. */
    public float minTransparency = 0.5f;
    /** Transparency above which a frame's starlight isn't scaled down any further. */
    static final float MAX_TRANSPARENCY = 1.5f;
    /** An image of the frame's size to stack into instead of a new one (all of it is overwritten), or null. */
    public RgbImage into;
    /** What the progress shows while stacking. */
    public String label = "Stacking";
    /** Measures the stack's real noise gain as it adds up the samples ({@link NoiseProbe}), or null. */
    public NoiseProbe noise;
    /** Collects where each frame had samples rejected as too bright ({@link StreakEvidence}), or null. */
    public StreakEvidence evidence;
    /**
     * When rowTo > rowFrom: only rows [rowFrom, rowTo) are stacked, into an image that holds just those rows (row
     * rowFrom first, not cropped): the foreground's plain average for keeping the ground still (G3), which needs only
     * its rows. rowFrom is taken down to a multiple of four. Otherwise the whole frame.
     */
    public int rowFrom, rowTo;
    /** The sensor row of the output image's first row (0 unless {@link #rowFrom} limits it). */
    private int outRow0;
    /**
     * The foreground, when the ground is kept still (G3): every frame's samples on it or next to it are left out, as if
     * off the frame, so the sky beside a branch isn't darkened by the frames in which the branch covered it. They
     * still count for the coverage (the crop). Null: every sample counts.
     */
    public GroundMap ground;

    /** Keeping every sample needs bands at least this tall, or re-reading the frames' overlapping rows costs too much. */
    static final int MIN_KEEP_ROWS = 24;
    /** Bytes per output pixel besides the kept samples: centre and four sums per channel, three counters. */
    private static final int KEEP_BYTES = 5 * 12 + 6;
    /** One-pass clipping: ten statistics per channel, a sum of squares in double and three counters. */
    private static final int STREAM_BYTES = 10 * 12 + 24 + 6;
    /**
     * Values per warp node in a frame's normalisation: scale, weight, transparency, offset per channel and
     * noise per channel (of one photosite, after normalisation).
     */
    private static final int K_T = 2, K_OFF = 3, K_NOISE = 6, K_DOUBT = 9, NK = 10;
    /**
     * One-pass clipping trims k values at each end before measuring the spread (k = 1 below
     * {@link #TRIM2_FROM} samples, else 2). The trimmed spread of normal noise is too small by a known factor:
     * the expected variance of the m - 2k middle values of m, from a simulation, for m = 2k + 2 .. 64.
     */
    static final int TRIM2_FROM = 10;
    private static final float[] TRIM1_VAR = {0.3002f, 0.3592f, 0.4066f, 0.4463f, 0.4792f, 0.5083f, 0.5334f, 0.5548f, 0.5747f, 0.5926f, 0.6086f,
            0.6232f, 0.6366f, 0.6488f, 0.6591f, 0.6699f, 0.6796f, 0.6888f, 0.6969f, 0.7045f, 0.7125f, 0.7194f,
            0.7256f, 0.7318f, 0.7376f, 0.7433f, 0.7484f, 0.7538f, 0.7588f, 0.7631f, 0.7676f, 0.7717f, 0.7761f,
            0.7805f, 0.7833f, 0.7875f, 0.7901f, 0.7936f, 0.7971f, 0.8004f, 0.803f, 0.8059f, 0.8087f, 0.8118f, 0.8145f,
            0.8158f, 0.8191f, 0.8213f, 0.8236f, 0.8254f, 0.8283f, 0.8299f, 0.832f, 0.8339f, 0.8359f, 0.8374f, 0.8394f,
            0.8414f, 0.8435f, 0.8448f, 0.8465f};
    private static final float[] TRIM2_VAR = {0.1438f, 0.1875f, 0.2261f, 0.2613f, 0.2927f, 0.32f, 0.346f, 0.3698f, 0.3912f, 0.4107f, 0.4288f, 0.4461f,
            0.4616f, 0.476f, 0.4896f, 0.5027f, 0.5151f, 0.5263f, 0.5373f, 0.5472f, 0.5568f, 0.5657f, 0.5747f, 0.583f,
            0.5908f, 0.5982f, 0.6056f, 0.6125f, 0.6197f, 0.6257f, 0.6316f, 0.6376f, 0.6434f, 0.6491f, 0.6543f, 0.659f,
            0.6642f, 0.6684f, 0.6729f, 0.6775f, 0.6821f, 0.6859f, 0.6904f, 0.694f, 0.6979f, 0.7013f, 0.705f, 0.7079f,
            0.712f, 0.7153f, 0.7182f, 0.7214f, 0.7242f, 0.7275f, 0.7301f, 0.7334f, 0.7362f, 0.7384f, 0.7411f};

    private static final float NEG = -Float.MAX_VALUE, POS = Float.MAX_VALUE;

    private final SensorSpec spec;
    private final CameraModel model;
    private final FrameStore store;
    private final HotPixels hot;
    private final int W, H;
    private final int pR, pG1, pG2, pB;

    public SharpStacker(SensorSpec spec, CameraModel model, FrameStore store, HotPixels hot) {
        this.spec = spec;
        this.model = model;
        this.store = store;
        this.hot = hot;
        W = spec.width;
        H = spec.height;
        int r = -1, g1 = -1, g2 = -1, b = -1;
        for (int p = 0; p < 4; p++) {
            int c = spec.cfa[p];
            if (c == 0 && r < 0) r = p;
            else if (c == 2 && b < 0) b = p;
            else if (c == 1) {
                if (g1 < 0) g1 = p;
                else g2 = p;
            }
        }
        if (g1 < 0) g1 = 1;
        if (g2 < 0) g2 = g1;
        if (r < 0) r = 0;
        if (b < 0) b = 3;
        pR = r;
        pG1 = g1;
        pG2 = g2;
        pB = b;
    }

    /** True if keeping every sample fits the memory budget for {@code frames} frames. */
    public boolean keepsSamples(int frames) {
        return method != Rejection.Method.SIGMA && keepRows(frames) >= MIN_KEEP_ROWS;
    }

    /** Rows per band when keeping every sample: what the budget allows, and what one Java array can hold. */
    private long keepRows(int frames) {
        long rows = memoryBudget / ((long) W * (6L * frames + KEEP_BYTES));
        return Math.min(rows, (Integer.MAX_VALUE - 8L) / (3L * W * Math.max(1, frames)));
    }

    /** One loaded band of a unit: black-subtracted float ADU for rows [rs, re). */
    private static final class Loaded {
        short[] raw = new short[0];
        float[] data = new float[0];
        int rs, re;
        boolean valid;
    }

    public Result stack(final List<Plan> plans, final Progress progress) throws IOException {
        final int nf = plans.size();
        final double[] nodeB = WarpGrid.nodeBearings(model, W, H, STEP);
        final WarpGrid[] grids = new WarpGrid[nf];
        for (int f = 0; f < nf; f++) grids[f] = WarpGrid.build(model, nodeB, W, H, STEP, plans.get(f).R);
        final int nx = WarpGrid.nodesX(W, STEP), ny = WarpGrid.nodesY(H, STEP);
        final byte[][] doubt = doubtful(plans, nx, ny);

        final Result res = new Result();
        final boolean part = rowTo > rowFrom;
        final int oy = part ? Math.max(0, rowFrom) & ~3 : 0, outH = part ? Math.min(H, rowTo) - oy : H;
        if (outH <= 0) throw new IllegalArgumentException("no rows to stack");
        outRow0 = oy;
        final RgbImage out = into != null && into.width == W && into.height == outH ? into : new RgbImage(W, outH);
        res.image = out;
        res.rejected = new long[nf];
        res.samples = new long[nf];
        res.included = new long[nf];
        res.rowL = new int[H];
        res.rowR = new int[H];
        if (out.clip == null || out.clip.length != W * outH) out.clip = new byte[W * outH];

        final boolean keep = keepsSamples(nf);
        long rows = keep ? keepRows(nf) : Math.max(32, memoryBudget / ((long) W * STREAM_BYTES));
        // bands start on a cell of the streak evidence, a deep one if it's kept (and on a Bayer row pair)
        int cell = evidence != null && evidence.deep() ? StreakEvidence.DEEP : StreakEvidence.CELL;
        int bandRows = (int) Math.min(Math.min(512, rows), H + 1) & ~(cell - 1);
        res.method = method;
        res.keptSamples = keep;
        res.bandRows = bandRows;
        if (noise != null) noise.reset(bandRows);
        final int bandPix = bandRows * W;
        final float[][] acc = new float[keep ? 5 : 10][bandPix * 3];
        final double[] sq = keep ? null : new double[bandPix * 3];
        final short[] cnt = new short[bandPix], icnt = new short[bandPix], ccnt = new short[bandPix];
        // samples left out on the ground, per pixel
        final short[] gcnt = ground != null ? new short[bandPix] : null;
        res.empty = ground != null ? new long[(W * outH + 63) >> 6] : null;
        final short[] vals = keep ? new short[bandPix * nf * 3] : null;
        final Rejection rej = keep ? new Rejection(nf) : null;
        if (rej != null) {
            rej.kHigh = kHigh;
            rej.kLow = kLow;
            rej.prepare(method);
        }
        // capture times about their mean, for linear-fit clipping
        final float[] times = new float[nf];
        double tm = 0;
        for (Plan p : plans) tm += p.t / nf;
        for (int f = 0; f < nf; f++) times[f] = (float) (plans.get(f).t - tm);
        final int maxNodeRows = bandRows / STEP + 3;
        // per frame, the normalisation at the band's warp nodes (one shared array when frames go one at a time)
        final float[][] nv = new float[keep ? nf : 1][maxNodeRows * nx * NK];
        final float[] tgt = new float[maxNodeRows * nx * 3];
        final int covThr = Math.max(1, Math.round(coverageFraction * nf));
        // the current plan's corridors in the band, and the brightest kept streak per pixel and channel
        boolean anyKeep = false, anyMask = false;
        for (Plan p : plans) {
            anyKeep |= p.keep != null;
            anyMask |= p.masks != null;
        }
        final long[] maskBits = anyMask ? new long[(bandPix + 63) >> 6] : null;
        final long[] keepBits = anyKeep ? new long[(bandPix + 63) >> 6] : null;
        final float[] trail = anyKeep ? new float[bandPix * 3] : null;
        final StreakEvidence ev = evidence;

        ExecutorService io = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "nightsky-io");
                t.setDaemon(true);
                return t;
            }
        });
        final Loaded[] bufs = {new Loaded(), new Loaded()};
        final int bands = (outH + bandRows - 1) / bandRows;
        try {
            for (int b = 0; b < bands; b++) {
                final int y0 = oy + b * bandRows, y1 = Math.min(oy + outH, y0 + bandRows);
                final int n = (y1 - y0) * W;
                final int iyA = Stats.clamp((int) ((y0 + 0.5) / STEP), 0, ny - 2);
                final int iyB = Stats.clamp((int) ((y1 - 0.5) / STEP), 0, ny - 2) + 1;
                targetNodes(iyA, iyB, nx, tgt);
                if (keep) {
                    for (int f = 0; f < nf; f++) normNodes(plans.get(f), grids[f], doubt[f], iyA, iyB, nx, tgt, nv[f]);
                    Arrays.fill(vals, 0, n * nf * 3, Half.MISSING);
                    for (int i = 1; i < 5; i++) Arrays.fill(acc[i], 0, n * 3, 0f);
                } else {
                    for (int i = 0; i < 10; i++) Arrays.fill(acc[i], 0, n * 3, i == 3 || i == 5 ? NEG : (i == 7 || i == 9 ? POS : 0f));
                    Arrays.fill(sq, 0, n * 3, 0.0);
                }
                Arrays.fill(cnt, 0, n, (short) 0);
                Arrays.fill(icnt, 0, n, (short) 0);
                Arrays.fill(ccnt, 0, n, (short) 0);
                if (gcnt != null) Arrays.fill(gcnt, 0, n, (short) 0);
                if (trail != null) Arrays.fill(trail, 0, n * 3, Float.NaN);
                if (ev != null) ev.beginBand(y0, y1, !keep);
                for (int pass = 0; pass < 2; pass++) {
                    Future<Loaded> next = io.submit(loader(plans.get(0), grids[0], y0, y1, bufs[0]));
                    for (int f = 0; f < nf; f++) {
                        Loaded cur = await(next);
                        if (f + 1 < nf) next = io.submit(loader(plans.get(f + 1), grids[f + 1], y0, y1, bufs[(f + 1) & 1]));
                        if (cur.valid) {
                            Plan p = plans.get(f);
                            float[] nvf = keep ? nv[f] : nv[0];
                            if (!keep) normNodes(p, grids[f], doubt[f], iyA, iyB, nx, tgt, nvf);
                            long[] mb = maskBits != null && corridors(p.masks, y0, y1, maskBits) ? maskBits : null;
                            long[] kb = keepBits != null && pass == 1 && corridors(p.keep, y0, y1, keepBits) ? keepBits : null;
                            if (keep && pass == 0) collect(cur, grids[f], nvf, iyA, f, nf, y0, y1, vals, cnt, gcnt, mb, res);
                            else if (keep) integrateKept(cur, grids[f], p, nvf, iyA, f, nf, y0, y1, vals, acc, icnt, ccnt, kb, trail, res);
                            else if (pass == 0) gather(cur, grids[f], nvf, iyA, f, y0, y1, acc, sq, cnt, icnt, gcnt, mb, res);
                            else {
                                integrateClipped(cur, grids[f], p, nvf, iyA, f, y0, y1, acc, icnt, ccnt, mb, kb, trail, ev, res);
                                if (ev != null) ev.endFrameBand(f);
                            }
                        }
                        if (progress.cancelled()) throw new CancelledException();
                        progress.update(label, ((b * 2 + pass) + (f + 1f) / nf) / (bands * 2f));
                    }
                    if (pass == 0) {
                        if (keep) reject(nv, grids, iyA, ny, nx, times, nf, y0, y1, vals, cnt, acc[0], tgt, rej, ev, res);
                        else limits(n, y0, acc, sq, icnt);
                    }
                }
                if (keep) {
                    finish(y0, y1, acc[1], acc[2], acc[3], acc[4], acc[0], false, cnt, icnt, ccnt, gcnt, covThr, out, res);
                    if (noise != null) noise.endBand(y0, y1, acc[1], acc[2], acc[3], acc[4], icnt);
                } else {
                    finish(y0, y1, acc[3], acc[4], acc[5], acc[0], acc[6], true, cnt, icnt, ccnt, gcnt, covThr, out, res);
                    if (noise != null) noise.endBand(y0, y1, acc[3], acc[4], acc[5], acc[0], icnt);
                }
                if (trail != null) lighten(y0, y1, trail, out);
                if (ev != null) ev.endBand();
            }
        } finally {
            io.shutdownNow();
        }
        applyShading(out);
        int[] crop = part ? new int[]{0, 0, W, outH} : cropFromSpans(res.rowL, res.rowR, W, H);
        out.x0 = crop[0];
        out.y0 = crop[1];
        out.x1 = crop[2];
        out.y1 = crop[3];
        return res;
    }

    /**
     * Sets the bits (pixels of the band [y0, y1), row by row) within any of the corridors {@code c} (x0, y0, x1,
     * y1, half width each, in reference coordinates); false, and nothing set, if none reaches the band.
     */
    private boolean corridors(float[] c, int y0, int y1, long[] bits) {
        if (c == null || c.length < 5) return false;
        boolean cleared = false, any = false;
        for (int s = 0; s + 4 < c.length; s += 5) {
            float ax = c[s], ay = c[s + 1], bx = c[s + 2], by = c[s + 3], hw = c[s + 4];
            int ya = Math.max(y0, (int) Math.floor(Math.min(ay, by) - hw)), yb = Math.min(y1 - 1, (int) Math.ceil(Math.max(ay, by) + hw));
            int xa = Math.max(0, (int) Math.floor(Math.min(ax, bx) - hw)), xb = Math.min(W - 1, (int) Math.ceil(Math.max(ax, bx) + hw));
            if (ya > yb || xa > xb) continue;
            if (!cleared) {
                Arrays.fill(bits, 0, ((y1 - y0) * W + 63) >> 6, 0L);
                cleared = true;
            }
            double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy, hw2 = (double) hw * hw;
            for (int y = ya; y <= yb; y++) {
                double py = y + 0.5 - ay;
                for (int x = xa; x <= xb; x++) {
                    double px = x + 0.5 - ax;
                    double t = l2 > 0 ? Math.max(0, Math.min(1, (px * dx + py * dy) / l2)) : 0;
                    double ex = px - t * dx, ey = py - t * dy;
                    if (ex * ex + ey * ey > hw2) continue;
                    int bp = (y - y0) * W + x;
                    bits[bp >> 6] |= 1L << (bp & 63);
                    any = true;
                }
            }
        }
        return any;
    }

    private static boolean bit(long[] bits, int bp) {
        return bits != null && (bits[bp >> 6] & (1L << (bp & 63))) != 0;
    }

    /** Kept streaks over the stack: per pixel and channel the brighter of the two. */
    private void lighten(int y0, int y1, float[] trail, RgbImage out) {
        for (int j = 0; j < y1 - y0; j++) {
            int o = (y0 + j - outRow0) * W;
            for (int x = 0; x < W; x++) {
                int k = (j * W + x) * 3;
                for (int c = 0; c < 3; c++) {
                    float t = trail[k + c];
                    if (t == t && t > out.channel(c)[o + x]) out.channel(c)[o + x] = t;
                }
            }
        }
    }

    private static Loaded await(Future<Loaded> f) throws IOException {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new IOException(c);
        }
    }

    private Callable<Loaded> loader(final Plan plan, final WarpGrid grid, final int y0, final int y1, final Loaded buf) {
        return new Callable<Loaded>() {
            @Override
            public Loaded call() throws IOException {
                buf.valid = false;
                float[] mr = grid.mappedRowRange(y0, y1);
                if (mr[0] > mr[1]) return buf;
                int rs = (int) Math.floor(mr[0]) - 6, re = (int) Math.ceil(mr[1]) + 7;
                rs = Math.max(0, rs);
                re = Math.min(H, re);
                if (re - rs < 8) return buf;
                int need = (re - rs) * W;
                if (buf.raw.length < need) {
                    buf.raw = new short[need];
                    buf.data = new float[need];
                }
                (plan.frames != null ? plan.frames : store).readBand(plan.unit, rs, re, buf.raw);
                HotPixels hp = plan.hot != null ? plan.hot : hot;
                if (hp != null) hp.fix(buf.raw, rs, re, rs);
                float sc = plan.invScale;
                short[] raw = buf.raw;
                float[] d = buf.data;
                for (int y = rs; y < re; y++) {
                    int o = (y - rs) * W;
                    int py = (y & 1) << 1;
                    float be = plan.black[py], bo = plan.black[py | 1];
                    for (int x = 0; x < W; x += 2) {
                        d[o + x] = (raw[o + x] & 0xFFFF) * sc - be;
                        d[o + x + 1] = (raw[o + x + 1] & 0xFFFF) * sc - bo;
                    }
                }
                if (plan.pattern != null) plan.pattern.subtract(d, rs, re);
                buf.rs = rs;
                buf.re = re;
                buf.valid = true;
                return buf;
            }
        };
    }

    // ------------------------------------------------------------------------------------------------
    // Normalisation at the warp nodes
    // ------------------------------------------------------------------------------------------------

    /** The target background at the warp nodes of node rows [iyA, iyB]: [((iy - iyA) * nx + ix) * 3 + c]. */
    private void targetNodes(int iyA, int iyB, int nx, float[] tgt) {
        for (int iy = iyA; iy <= iyB; iy++) {
            for (int ix = 0; ix < nx; ix++) {
                int o = ((iy - iyA) * nx + ix) * 3;
                for (int c = 0; c < 3; c++) tgt[o + c] = target != null ? target.level(c, ix * STEP, iy * STEP) : 0f;
            }
        }
    }

    /**
     * A frame's normalisation at the warp nodes of node rows [iyA, iyB]: per node the scale, the weight, the
     * offset per channel (normalised = value * scale + offset) and the noise per channel after normalisation.
     * Scale and cut come from the transparency interpolated to the node, so the frame is left out right up to
     * where its cloud thins below {@link #minTransparency}, and scaled by 1 / (transparency x signal) where it is
     * used; the weight is the inverse of its noise after scaling, relative to the target's, at most 4. Last, 1
     * where the frame's samples are only judged against the others' limits ({@link #doubtful}).
     */
    private void normNodes(Plan p, WarpGrid g, byte[] doubt, int iyA, int iyB, int nx, float[] tgt, float[] a) {
        boolean match = p.background != null && target != null;
        for (int iy = iyA; iy <= iyB; iy++) {
            for (int ix = 0; ix < nx; ix++) {
                int k = iy * nx + ix;
                double X = ix * STEP, Y = iy * STEP;
                float mx = g.mx[k], my = g.my[k];
                boolean on = mx > -1e5f && my > -1e5f;
                float tr = p.transparency != null ? p.transparency.at(X, Y) : 1f;
                float tc = Stats.clamp(tr, minTransparency, MAX_TRANSPARENCY);
                float sg = p.signal > 0 ? p.signal : 1f;
                float sc = 1f / (tc * sg);
                float r = sc;
                if (match && on) {
                    float sf = p.background.sigma(1, mx, my), st = target.sigma(1, X, Y);
                    if (sf > 0 && st > 0) r = sf / (tc * sg * st);
                }
                int o = ((iy - iyA) * nx + ix) * NK;
                int to = ((iy - iyA) * nx + ix) * 3;
                a[o] = sc;
                // the weight before the cut, which each pixel makes from the transparency interpolated to it
                a[o + 1] = p.weight * Math.min(4f, 1f / (r * r));
                a[o + K_T] = tr;
                a[o + K_DOUBT] = doubt != null ? doubt[k] : 0f;
                for (int c = 0; c < 3; c++) {
                    float bg = 0f, sig = 1f;
                    if (p.background != null && on) {
                        bg = p.background.level(c, mx, my);
                        sig = p.background.sigma(c, mx, my);
                    }
                    a[o + K_OFF + c] = match ? tgt[to + c] - bg * sc : 0f;
                    a[o + K_NOISE + c] = Math.max(1e-3f, sc * sig);
                }
            }
        }
    }

    /**
     * Per frame and warp node, 1 where the frame is used close to a region that cloud cut it out of. Its
     * background and scale there are interpolated across the cloud's edge, which can be much sharper than
     * the regions, so its samples there may be off by more than the noise; in a run where most frames have
     * the edge, they would drag the rejection's centre with them. So where at least
     * {@link Rejection#MIN_TRUSTED} frames are clear all around, those set the limits and the others are only
     * judged against them. Null for a frame without such nodes.
     */
    private byte[][] doubtful(List<Plan> plans, int nx, int ny) {
        int nf = plans.size(), nodes = nx * ny;
        byte[][] d = new byte[nf][];
        int[] clear = new int[nodes];
        for (int f = 0; f < nf; f++) {
            LocalWeights tr = plans.get(f).transparency;
            for (int k = 0; k < nodes; k++) {
                if (tr == null || edgeMin(tr, (k % nx) * STEP, (k / nx) * STEP) >= minTransparency) {
                    clear[k]++;
                    continue;
                }
                if (d[f] == null) d[f] = new byte[nodes];
                d[f][k] = 1;
            }
        }
        for (int f = 0; f < nf; f++) {
            if (d[f] == null) continue;
            boolean any = false;
            for (int k = 0; k < nodes; k++) {
                if (clear[k] < Rejection.MIN_TRUSTED) d[f][k] = 0;
                any |= d[f][k] != 0;
            }
            if (!any) d[f] = null;
        }
        return d;
    }

    /**
     * The least transparency among the regions interpolated between anywhere within a node step of (X, Y),
     * so that a pixel between two nodes is doubtful when its own interpolation reaches a cut region.
     */
    private static float edgeMin(LocalWeights tr, double X, double Y) {
        float m = Float.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) m = Math.min(m, tr.minAt(X + dx * STEP, Y + dy * STEP));
        return m;
    }

    /** Node values for continuous output row yc, interpolated between the node rows like {@link WarpGrid#row}. */
    private static void normRow(float[] a, int iyA, int ny, int nx, double yc, float[] out) {
        double gy = yc / STEP;
        int iy = (int) gy;
        if (iy > ny - 2) iy = ny - 2;
        if (iy < 0) iy = 0;
        float fy = (float) (gy - iy);
        int r0 = (iy - iyA) * nx * NK, r1 = r0 + nx * NK;
        for (int i = 0; i < nx * NK; i++) out[i] = a[r0 + i] + (a[r1 + i] - a[r0 + i]) * fy;
    }

    // ------------------------------------------------------------------------------------------------
    // Sampling
    // ------------------------------------------------------------------------------------------------

    /**
     * Bilinear R, G, B of the unit at frame position (mx, my) into rgb[0..2] (green from both green planes).
     * Returns true if one of the samples used is at or above its clip threshold ({@code thr}, may be null).
     * {@code nz} (may be null) gets each channel's noise relative to one photosite's ({@link #noiseFactors}).
     * {@code t} is scratch (3 floats).
     */
    private boolean sampleRgb(float[] d, int rs, float mx, float my, float[] t, float[] rgb, float[] thr, float[] nz) {
        boolean clip = false;
        bilinear(d, rs, pR, mx, my, t);
        rgb[0] = t[0];
        float vr = t[2];
        if (thr != null && t[1] >= thr[pR]) clip = true;
        bilinear(d, rs, pG1, mx, my, t);
        float g = t[0], vg = t[2];
        if (thr != null && t[1] >= thr[pG1]) clip = true;
        if (pG2 != pG1) {
            bilinear(d, rs, pG2, mx, my, t);
            g = 0.5f * (g + t[0]);
            vg = 0.25f * (vg + t[2]);
            if (thr != null && t[1] >= thr[pG2]) clip = true;
        }
        rgb[1] = g;
        bilinear(d, rs, pB, mx, my, t);
        rgb[2] = t[0];
        if (thr != null && t[1] >= thr[pB]) clip = true;
        if (nz != null) {
            nz[0] = (float) Math.sqrt(vr);
            nz[1] = (float) Math.sqrt(vg);
            nz[2] = (float) Math.sqrt(t[2]);
        }
        return clip;
    }

    /**
     * Bilinear value of plane p at (mx, my) into t[0], the largest of its four samples into t[1], and the
     * variance of the interpolated value relative to one sample's into t[2]: interpolation averages
     * neighbouring photosites, so a value between them is less noisy than one at a photosite.
     */
    private void bilinear(float[] d, int rs, int p, float mx, float my, float[] t) {
        int ox = p & 1, oy = p >> 1;
        float fx = (mx - 0.5f - ox) * 0.5f, fy = (my - 0.5f - oy) * 0.5f;
        int iu = (int) fx, iv = (int) fy; // positive here
        float ax = fx - iu, ay = fy - iv;
        int i = (2 * iv + oy - rs) * W + 2 * iu + ox;
        float d00 = d[i], d01 = d[i + 2], d10 = d[i + 2 * W], d11 = d[i + 2 * W + 2];
        t[0] = (1f - ay) * (d00 + ax * (d01 - d00)) + ay * (d10 + ax * (d11 - d10));
        t[1] = Math.max(Math.max(d00, d01), Math.max(d10, d11));
        t[2] = interpVariance(ax) * interpVariance(ay);
    }

    /** Variance of linear interpolation at fraction a between two independent samples, relative to one: (1-a)^2 + a^2. */
    private static float interpVariance(float a) {
        return 1f - 2f * a * (1f - a);
    }

    /**
     * Each channel's noise at frame position (mx, my) relative to one photosite's, as {@link #sampleRgb} gives
     * it in {@code nz}, without sampling.
     */
    private void noiseFactors(float mx, float my, float[] nz) {
        float vr = planeVariance(pR, mx, my), vg = planeVariance(pG1, mx, my);
        if (pG2 != pG1) vg = 0.25f * (vg + planeVariance(pG2, mx, my));
        nz[0] = (float) Math.sqrt(vr);
        nz[1] = (float) Math.sqrt(vg);
        nz[2] = (float) Math.sqrt(planeVariance(pB, mx, my));
    }

    private static float planeVariance(int p, float mx, float my) {
        float fx = (mx - 0.5f - (p & 1)) * 0.5f, fy = (my - 0.5f - (p >> 1)) * 0.5f;
        return interpVariance(fx - (float) Math.floor(fx)) * interpVariance(fy - (float) Math.floor(fy));
    }

    /**
     * Drizzle contributions of the unit to the output pixel that maps to (mx, my): per channel the weighted
     * value into dz[0..2] and the weight into dz[3..5]. The weight is the overlap of a pixel-sized drop
     * around the nearest photosite of that colour with the (pixel-sized) output pixel.
     */
    private void drizzleRgb(float[] d, int rs, float mx, float my, float[] t, float[] dz) {
        nearest(d, rs, pR, mx, my, t);
        dz[0] = t[0] * t[1];
        dz[3] = t[1];
        nearest(d, rs, pG1, mx, my, t);
        dz[1] = t[0] * t[1];
        dz[4] = t[1];
        if (pG2 != pG1) {
            nearest(d, rs, pG2, mx, my, t);
            dz[1] += t[0] * t[1];
            dz[4] += t[1];
        }
        nearest(d, rs, pB, mx, my, t);
        dz[2] = t[0] * t[1];
        dz[5] = t[1];
    }

    /** Nearest sample of plane p to (mx, my) into t[0], its drop overlap weight into t[1]. */
    private void nearest(float[] d, int rs, int p, float mx, float my, float[] t) {
        int ox = p & 1, oy = p >> 1;
        float fx = (mx - 0.5f - ox) * 0.5f, fy = (my - 0.5f - oy) * 0.5f;
        int iu = (int) (fx + 0.5f), iv = (int) (fy + 0.5f);
        float dx = 2f * Math.abs(fx - iu), dy = 2f * Math.abs(fy - iv);
        t[0] = d[(2 * iv + oy - rs) * W + 2 * iu + ox];
        t[1] = (1f - dx) * (1f - dy);
    }

    /** Per-plane clip threshold in the loaded data's units (black-subtracted per-frame ADU). */
    private float[] clipThresholds(Plan plan) {
        float range = spec.range();
        float level = spec.white - Math.max(3f, 0.01f * range);
        float[] t = new float[4];
        for (int p = 0; p < 4; p++) t[p] = level - plan.black[p];
        return t;
    }

    // ------------------------------------------------------------------------------------------------
    // Keeping every sample
    // ------------------------------------------------------------------------------------------------

    /** Pass A: every frame's normalised sample of every pixel, as half floats [pixel][frame][channel]. */
    private void collect(final Loaded cur, final WarpGrid grid, final float[] nvf, final int iyA, final int f, final int nf,
                         final int y0, final int y1, final short[] vals, final short[] cnt, final short[] gcnt, final long[] mask,
                         final Result res) {
        final GroundMap gm = ground;
        Parallel.forRange(y1 - y0, 2, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] rx = new float[grid.nx], ry = new float[grid.nx], nr = new float[grid.nx * NK];
                float[] st = new float[3], rgb = new float[3];
                float[] d = cur.data;
                int rs = cur.rs;
                long smp = 0, msk = 0;
                for (int j = j0; j < j1; j++) {
                    int y = y0 + j;
                    grid.row(y + 0.5, rx, ry);
                    normRow(nvf, iyA, grid.ny, grid.nx, y + 0.5, nr);
                    for (int ix = 0; ix < grid.nx - 1; ix++) {
                        int xa = ix * STEP;
                        if (xa >= W) break;
                        int xb = Math.min(W, xa + STEP);
                        float ax = rx[ix], ay = ry[ix];
                        float dx = (rx[ix + 1] - ax) / STEP, dy = (ry[ix + 1] - ay) / STEP;
                        int q = ix * NK;
                        float sc0 = nr[q], dsc = (nr[q + NK] - sc0) / STEP;
                        float or0 = nr[q + K_OFF], dor = (nr[q + NK + K_OFF] - or0) / STEP;
                        float og0 = nr[q + K_OFF + 1], dog = (nr[q + NK + K_OFF + 1] - og0) / STEP;
                        float ob0 = nr[q + K_OFF + 2], dob = (nr[q + NK + K_OFF + 2] - ob0) / STEP;
                        for (int x = xa; x < xb; x++) {
                            float t = x + 0.5f - xa;
                            float mx = ax + dx * t, my = ay + dy * t;
                            if (!(mx >= 4f && my >= 4f && mx < W - 4f && my < H - 4f)) continue;
                            int bp = j * W + x;
                            if (gm != null && gm.nearAt(mx, my)) {
                                gcnt[bp]++;
                                continue;
                            }
                            if (bit(mask, bp)) {
                                msk++;
                                continue;
                            }
                            sampleRgb(d, rs, mx, my, st, rgb, null, null);
                            float sc = sc0 + dsc * t;
                            cnt[bp]++;
                            smp++;
                            int k = (bp * nf + f) * 3;
                            vals[k] = Half.fromFloat(rgb[0] * sc + (or0 + dor * t));
                            vals[k + 1] = Half.fromFloat(rgb[1] * sc + (og0 + dog * t));
                            vals[k + 2] = Half.fromFloat(rgb[2] * sc + (ob0 + dob * t));
                        }
                    }
                }
                synchronized (res) {
                    res.samples[f] += smp;
                    res.masked += msk;
                }
            }
        });
    }

    /**
     * Between the passes: per pixel and channel, the rejection method decides which samples to keep (the
     * others are marked {@link Half#REJECTED}), and the plain mean of the kept ones goes to {@code centre}
     * for pixels that end up with no weight. Samples from where a frame isn't used (weight 0, or cloud
     * thicker than {@link #minTransparency}) take no part, unless no frame uses that pixel. Each sample's
     * noise is its frame's there, allowing for the smoothing of the interpolation that produced it; samples
     * from next to a cloud edge are judged against the limits of the others ({@link #doubtful}).
     */
    private void reject(final float[][] nv, final WarpGrid[] grids, final int iyA, final int ny, final int nx,
                        final float[] times, final int nf, final int y0, final int y1, final short[] vals,
                        final short[] cnt, final float[] centre, final float[] tgt, final Rejection rej,
                        final StreakEvidence ev, final Result res) {
        Parallel.forRange(y1 - y0, 1, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                Rejection.Work wk = new Rejection.Work(nf);
                StreakEvidence.Batch eb = ev != null ? ev.batch() : null;
                StreakEvidence.DeepBatch db = ev != null && ev.deep() ? ev.deepBatch(y0 + j0, y0 + j1) : null;
                // a pixel's standardized samples per frame and colour, and the noise each was divided by, for db
                float[] dzs = db != null ? new float[nf * 3] : null, dss = db != null ? new float[nf * 3] : null;
                float[][] nr = new float[nf][nx * NK];
                float[][] rxs = new float[nf][nx], rys = new float[nf][nx];
                float[] base = new float[nx * 3];
                float[] wf = new float[nf], nzf = new float[nf * 3], nz = new float[3];
                boolean[] trf = new boolean[nf];
                int[] idx = new int[nf];
                long[] rejF = new long[nf];
                long edge = 0, edgeRej = 0;
                for (int j = j0; j < j1; j++) {
                    int y = y0 + j;
                    double yc = y + 0.5;
                    for (int f = 0; f < nf; f++) {
                        normRow(nv[f], iyA, ny, nx, yc, nr[f]);
                        grids[f].row(yc, rxs[f], rys[f]);
                    }
                    targetRow(tgt, iyA, ny, nx, yc, base);
                    for (int x = 0; x < W; x++) {
                        int bp = j * W + x;
                        int k3 = bp * 3;
                        if (cnt[bp] == 0) {
                            centre[k3] = centre[k3 + 1] = centre[k3 + 2] = 0f;
                            continue;
                        }
                        int ix = x / STEP;
                        float t = x + 0.5f - ix * STEP;
                        int q = ix * NK;
                        int kv = bp * nf * 3;
                        boolean anyW = false;
                        for (int f = 0; f < nf; f++) {
                            if (vals[kv + f * 3 + 1] == Half.MISSING) {
                                wf[f] = Float.NaN;
                                continue;
                            }
                            float[] a = nr[f];
                            float w = a[q + 1] + (a[q + NK + 1] - a[q + 1]) / STEP * t;
                            float tr = a[q + K_T] + (a[q + NK + K_T] - a[q + K_T]) / STEP * t;
                            if (tr < minTransparency) w = 0f;
                            wf[f] = w;
                            trf[f] = a[q + K_DOUBT] + (a[q + NK + K_DOUBT] - a[q + K_DOUBT]) / STEP * t < 0.5f;
                            if (w > 0) anyW = true;
                            float[] rx = rxs[f], ry = rys[f];
                            float mx = rx[ix] + (rx[ix + 1] - rx[ix]) / STEP * t, my = ry[ix] + (ry[ix + 1] - ry[ix]) / STEP * t;
                            noiseFactors(mx, my, nz);
                            nzf[3 * f] = nz[0];
                            nzf[3 * f + 1] = nz[1];
                            nzf[3 * f + 2] = nz[2];
                        }
                        for (int c = 0; c < 3; c++) {
                            int n = 0;
                            for (int f = 0; f < nf; f++) {
                                float w = wf[f];
                                if (w != w || (anyW && !(w > 0))) continue;
                                float[] a = nr[f];
                                float s0 = a[q + K_NOISE + c], ds = (a[q + NK + K_NOISE + c] - s0) / STEP;
                                wk.v[n] = Half.toFloat(vals[kv + f * 3 + c]);
                                wk.s[n] = Math.max(1e-3f, (s0 + ds * t) * nzf[3 * f + c]);
                                wk.t[n] = times[f];
                                wk.trusted[n] = trf[f];
                                idx[n] = f;
                                n++;
                            }
                            float b0 = base[ix * 3 + c];
                            float bl = b0 + (base[(ix + 1) * 3 + c] - b0) / STEP * t;
                            if (n > 0) {
                                rej.run(method, n, bl, wk);
                                edge += wk.judged;
                                edgeRej += wk.judgedRejected;
                                // every sample, as far from the centre as the noise there makes it
                                if (db != null) {
                                    double sp = Rejection.spread(wk);
                                    for (int i = 0; i < n; i++) {
                                        int k = idx[i] * 3 + c;
                                        dzs[k] = sp == sp ? (float) Rejection.standardized(wk.v[i], wk.s[i], wk.t[i], wk) : Float.NaN;
                                        dss[k] = (float) (wk.s[i] * sp);
                                    }
                                }
                            }
                            double sum = 0;
                            int m = 0;
                            for (int i = 0; i < n; i++) {
                                if (wk.keep[i]) {
                                    sum += wk.v[i];
                                    m++;
                                }
                            }
                            float cm = m > 0 ? (float) (sum / m) : 0f;
                            for (int i = 0; i < n; i++) {
                                if (wk.keep[i]) continue;
                                vals[kv + idx[i] * 3 + c] = Half.REJECTED;
                                rejF[idx[i]]++;
                                if (eb != null && wk.v[i] > cm) eb.add(idx[i], x, y, c == 1 ? wk.v[i] - cm : 0f);
                            }
                            centre[k3 + c] = cm;
                        }
                        // the pixel's colours together into the deep map (the same frames take part in each)
                        if (db != null) {
                            for (int f = 0; f < nf; f++) {
                                float w = wf[f];
                                if (w != w || (anyW && !(w > 0))) continue;
                                db.add(f, x, y, dzs, dss, f * 3);
                            }
                        }
                    }
                }
                if (eb != null) eb.flush();
                if (db != null) db.flush();
                synchronized (res) {
                    for (int f = 0; f < nf; f++) res.rejected[f] += rejF[f];
                    res.edgeSamples += edge;
                    res.edgeRejected += edgeRej;
                }
            }
        });
    }

    /** The target background interpolated to output row yc at the warp nodes: out[ix * 3 + c]. */
    private static void targetRow(float[] tgt, int iyA, int ny, int nx, double yc, float[] out) {
        double gy = yc / STEP;
        int iy = (int) gy;
        if (iy > ny - 2) iy = ny - 2;
        if (iy < 0) iy = 0;
        float fy = (float) (gy - iy);
        int r0 = (iy - iyA) * nx * 3, r1 = r0 + nx * 3;
        for (int i = 0; i < nx * 3; i++) out[i] = tgt[r0 + i] + (tgt[r1 + i] - tgt[r0 + i]) * fy;
    }

    /**
     * Pass B with kept samples: for the samples not rejected, drizzle sums (s2 = weighted values, s3 =
     * weights) and interpolated sums (s4 = values, s5 = frame weights) of the normalised values.
     */
    private void integrateKept(final Loaded cur, final WarpGrid grid, final Plan plan, final float[] nvf, final int iyA,
                               final int f, final int nf, final int y0, final int y1, final short[] vals, final float[][] acc,
                               final short[] icnt, final short[] ccnt, final long[] keepB, final float[] trail,
                               final Result res) {
        final float[] thr = clipThresholds(plan);
        final float[] centre = acc[0], s2 = acc[1], s3 = acc[2], s4 = acc[3], s5 = acc[4];
        final NoiseProbe probe = noise;
        Parallel.forRange(y1 - y0, 2, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] rx = new float[grid.nx], ry = new float[grid.nx], nr = new float[grid.nx * NK];
                float[] st = new float[3], rgb = new float[3], dz = new float[6], nz = new float[3];
                float[] o0 = new float[3], dO = new float[3];
                float[] d = cur.data;
                int rs = cur.rs;
                long inc = 0;
                for (int j = j0; j < j1; j++) {
                    int y = y0 + j;
                    grid.row(y + 0.5, rx, ry);
                    normRow(nvf, iyA, grid.ny, grid.nx, y + 0.5, nr);
                    for (int ix = 0; ix < grid.nx - 1; ix++) {
                        int xa = ix * STEP;
                        if (xa >= W) break;
                        int xb = Math.min(W, xa + STEP);
                        float ax = rx[ix], ay = ry[ix];
                        float dx = (rx[ix + 1] - ax) / STEP, dy = (ry[ix + 1] - ay) / STEP;
                        int q = ix * NK;
                        float sc0 = nr[q], dsc = (nr[q + NK] - sc0) / STEP;
                        float w0 = nr[q + 1], dw = (nr[q + NK + 1] - w0) / STEP;
                        float tr0 = nr[q + K_T], dtr = (nr[q + NK + K_T] - tr0) / STEP;
                        for (int c = 0; c < 3; c++) {
                            o0[c] = nr[q + K_OFF + c];
                            dO[c] = (nr[q + NK + K_OFF + c] - o0[c]) / STEP;
                        }
                        float gn0 = nr[q + K_NOISE + 1], dgn = (nr[q + NK + K_NOISE + 1] - gn0) / STEP;
                        for (int x = xa; x < xb; x++) {
                            float t = x + 0.5f - xa;
                            float mx = ax + dx * t, my = ay + dy * t;
                            if (!(mx >= 4f && my >= 4f && mx < W - 4f && my < H - 4f)) continue;
                            float w = tr0 + dtr * t < minTransparency ? 0f : w0 + dw * t;
                            if (!(w > 0)) continue;
                            int bp = j * W + x;
                            int kv = (bp * nf + f) * 3;
                            if (vals[kv + 1] == Half.MISSING) continue;
                            boolean clipped = sampleRgb(d, rs, mx, my, st, rgb, thr, probe != null ? nz : null);
                            drizzleRgb(d, rs, mx, my, st, dz);
                            float sc = sc0 + dsc * t;
                            boolean any = false, kept = bit(keepB, bp);
                            for (int c = 0, k = bp * 3; c < 3; c++, k++) {
                                float o = o0[c] + dO[c] * t;
                                float v = rgb[c] * sc + o;
                                if (vals[kv + c] == Half.REJECTED) {
                                    if (kept && v > centre[k] && !(trail[k] >= v)) trail[k] = v;
                                    // the tail of the noise, not an outlier: the noise gain allows for it
                                    if (c == 1 && probe != null && Math.abs(v - centre[k])
                                            < NoiseProbe.NOISE_TAIL * Math.max(1e-3f, (gn0 + dgn * t) * nz[1])) {
                                        probe.addRejected(f, bp, w, v, dz[1] * sc + o * dz[4], dz[4]);
                                    }
                                    continue;
                                }
                                float dzv = dz[c] * sc + o * dz[c + 3];
                                s2[k] += w * dzv;
                                s3[k] += w * dz[c + 3];
                                s4[k] += w * v;
                                s5[k] += w;
                                if (c == 1 && probe != null) probe.add(f, bp, w, v, dzv, dz[c + 3]);
                                any = true;
                            }
                            if (any) {
                                icnt[bp]++;
                                if (clipped) ccnt[bp]++;
                                inc++;
                            }
                        }
                    }
                }
                synchronized (res) {
                    res.included[f] += inc;
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------------
    // One-pass sigma clipping
    // ------------------------------------------------------------------------------------------------

    /**
     * Pass A without kept samples: per pixel and channel the noise-weighted mean and spread of the normalised
     * samples (West's weighted form of Welford's method: acc[0] weights, acc[1] mean, sq the sum of weighted
     * squares, in double so that a very bright outlier can be taken out again exactly), and the two largest
     * and the two smallest values with their noise (acc[3], acc[4] and acc[5], acc[6]; acc[7], acc[8] and
     * acc[9], acc[2]). Samples from next to a cloud edge ({@link #doubtful}) are left out here and only judged
     * in pass B.
     */
    private void gather(final Loaded cur, final WarpGrid grid, final float[] nvf, final int iyA, final int f, final int y0,
                        final int y1, final float[][] acc, final double[] sq, final short[] cnt, final short[] icnt,
                        final short[] gcnt, final long[] mask, final Result res) {
        final GroundMap gm = ground;
        final float[] sW = acc[0], sM = acc[1], t1v = acc[3], t1s = acc[4], t2v = acc[5], t2s = acc[6];
        final float[] b1v = acc[7], b1s = acc[8], b2v = acc[9], b2s = acc[2];
        Parallel.forRange(y1 - y0, 2, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] rx = new float[grid.nx], ry = new float[grid.nx], nr = new float[grid.nx * NK];
                float[] st = new float[3], rgb = new float[3], nz = new float[3];
                float[] o0 = new float[3], dO = new float[3], n0 = new float[3], dN = new float[3];
                float[] d = cur.data;
                int rs = cur.rs;
                long smp = 0, msk = 0;
                for (int j = j0; j < j1; j++) {
                    int y = y0 + j;
                    grid.row(y + 0.5, rx, ry);
                    normRow(nvf, iyA, grid.ny, grid.nx, y + 0.5, nr);
                    for (int ix = 0; ix < grid.nx - 1; ix++) {
                        int xa = ix * STEP;
                        if (xa >= W) break;
                        int xb = Math.min(W, xa + STEP);
                        float ax = rx[ix], ay = ry[ix];
                        float dx = (rx[ix + 1] - ax) / STEP, dy = (ry[ix + 1] - ay) / STEP;
                        int q = ix * NK;
                        float sc0 = nr[q], dsc = (nr[q + NK] - sc0) / STEP;
                        float w0 = nr[q + 1], dw = (nr[q + NK + 1] - w0) / STEP;
                        float tr0 = nr[q + K_T], dtr = (nr[q + NK + K_T] - tr0) / STEP;
                        float du0 = nr[q + K_DOUBT], ddu = (nr[q + NK + K_DOUBT] - du0) / STEP;
                        for (int c = 0; c < 3; c++) {
                            o0[c] = nr[q + K_OFF + c];
                            dO[c] = (nr[q + NK + K_OFF + c] - o0[c]) / STEP;
                            n0[c] = nr[q + K_NOISE + c];
                            dN[c] = (nr[q + NK + K_NOISE + c] - n0[c]) / STEP;
                        }
                        for (int x = xa; x < xb; x++) {
                            float t = x + 0.5f - xa;
                            float mx = ax + dx * t, my = ay + dy * t;
                            if (!(mx >= 4f && my >= 4f && mx < W - 4f && my < H - 4f)) continue;
                            int bp = j * W + x;
                            if (gm != null && gm.nearAt(mx, my)) {
                                gcnt[bp]++;
                                continue;
                            }
                            if (bit(mask, bp)) {
                                msk++;
                                continue;
                            }
                            cnt[bp]++;
                            smp++;
                            float w = tr0 + dtr * t < minTransparency ? 0f : w0 + dw * t;
                            if (!(w > 0) || du0 + ddu * t >= 0.5f) continue;
                            sampleRgb(d, rs, mx, my, st, rgb, null, nz);
                            float sc = sc0 + dsc * t;
                            icnt[bp]++;
                            for (int c = 0, k = bp * 3; c < 3; c++, k++) {
                                float v = rgb[c] * sc + (o0[c] + dO[c] * t);
                                float s = Math.max(1e-3f, (n0[c] + dN[c] * t) * nz[c]);
                                float ws = 1f / (s * s);
                                float wn = sW[k] + ws;
                                float dl = v - sM[k];
                                float r = dl * ws / wn;
                                sM[k] += r;
                                sq[k] += (double) sW[k] * dl * r;
                                sW[k] = wn;
                                if (v > t1v[k]) {
                                    t2v[k] = t1v[k];
                                    t2s[k] = t1s[k];
                                    t1v[k] = v;
                                    t1s[k] = s;
                                } else if (v > t2v[k]) {
                                    t2v[k] = v;
                                    t2s[k] = s;
                                }
                                if (v < b1v[k]) {
                                    b2v[k] = b1v[k];
                                    b2s[k] = b1s[k];
                                    b1v[k] = v;
                                    b1s[k] = s;
                                } else if (v < b2v[k]) {
                                    b2v[k] = v;
                                    b2s[k] = s;
                                }
                            }
                        }
                    }
                }
                synchronized (res) {
                    res.samples[f] += smp;
                    res.masked += msk;
                }
            }
        });
    }

    /**
     * Between the passes: the centre (acc[1]) and the spread in units of each sample's noise (acc[2]) from
     * the statistics without the largest and the smallest value (the two largest and two smallest from
     * {@link #TRIM2_FROM} samples), the spread corrected for that trimming. Up to
     * {@link Rejection#PERCENTILE_MAX} samples say too little about their spread, so there acc[2] holds minus
     * percentile clipping's starlight allowance instead, as when keeping the samples. The other arrays are
     * cleared for pass B's sums and {@code icnt} for its count.
     */
    private void limits(final int n, final int y0, final float[][] acc, final double[] sq, final short[] icnt) {
        final float[] sW = acc[0], sM = acc[1], sd = acc[2], t1v = acc[3], t1s = acc[4], t2v = acc[5], t2s = acc[6];
        final float[] b1v = acc[7], b1s = acc[8], b2v = acc[9], b2s = acc[2];
        Parallel.forRange(n, 4096, new Parallel.Range() {
            @Override
            public void run(int a, int b) {
                double[] xs = new double[4], ss4 = new double[4];
                for (int bp = a; bp < b; bp++) {
                    int m0 = icnt[bp];
                    for (int c = 0, k = bp * 3; c < 3; c++, k++) {
                        float centre, spread;
                        if (m0 < 4) {
                            centre = sM[k];
                            spread = Float.POSITIVE_INFINITY;
                        } else {
                            int trim = m0 >= TRIM2_FROM ? 2 : 1;
                            xs[0] = t1v[k];
                            ss4[0] = t1s[k];
                            xs[1] = b1v[k];
                            ss4[1] = b1s[k];
                            xs[2] = t2v[k];
                            ss4[2] = t2s[k];
                            xs[3] = b2v[k];
                            ss4[3] = b2s[k];
                            double w = sW[k], mean = sM[k], ss = sq[k];
                            int m = m0;
                            for (int drop = 0; drop < 2 * trim; drop++) {
                                double x = xs[drop], s = ss4[drop];
                                double ws = 1.0 / (s * s);
                                double w1 = w - ws;
                                if (!(w1 > 1e-6 * w)) break;
                                double mean1 = (w * mean - ws * x) / w1;
                                ss -= ws * (x - mean) * (x - mean1);
                                w = w1;
                                mean = mean1;
                                m--;
                            }
                            centre = (float) mean;
                            if (m0 <= Rejection.PERCENTILE_MAX) {
                                float base = target != null ? target.level(c, bp % W + 0.5, y0 + bp / W + 0.5) : 0f;
                                spread = -Math.max(1e-6f, (centre - base) * Rejection.PERCENTILE_FRACTION);
                            } else {
                                double var = Math.max(ss, 0) / (m - 1) / trimVariance(m0, trim);
                                spread = (float) Math.max(sigmaFloor, Math.sqrt(var));
                            }
                        }
                        sM[k] = centre;
                        sd[k] = spread;
                        sW[k] = 0f;
                        t1v[k] = 0f;
                        t1s[k] = 0f;
                        t2v[k] = 0f;
                        t2s[k] = 0f;
                        sq[k] = 0.0;
                    }
                    icnt[bp] = 0;
                }
            }
        });
    }

    /** Expected variance of the m - 2k middle values of m normal values, relative to the whole sample's. */
    static double trimVariance(int m, int k) {
        float[] tab = k == 1 ? TRIM1_VAR : TRIM2_VAR;
        int first = 2 * k + 2;
        if (m < first) return tab[0];
        if (m - first < tab.length) return tab[m - first];
        // beyond the table: the shortfall shrinks about as a power of m
        double last = tab[tab.length - 1];
        return 1 - (1 - last) * Math.pow(64.0 / m, k == 1 ? 0.75 : 0.7);
    }

    /**
     * Pass B with one-pass clipping: samples within centre - kLow sd s .. centre + kHigh sd s (s: the sample's
     * noise; for few samples, percentile clipping's limits, see {@link #limits}) go into the drizzle sums (acc[3] weighted values, acc[4] weights) and the interpolated sums
     * (acc[5] values, acc[0] frame weights); acc[6] collects the plain sum of every sample, for pixels that
     * end up with no weight.
     */
    private void integrateClipped(final Loaded cur, final WarpGrid grid, final Plan plan, final float[] nvf, final int iyA,
                                  final int f, final int y0, final int y1, final float[][] acc, final short[] icnt,
                                  final short[] ccnt, final long[] mask, final long[] keepB, final float[] trail,
                                  final StreakEvidence ev, final Result res) {
        final float[] thr = clipThresholds(plan);
        final float[] cen = acc[1], sd = acc[2], s2 = acc[3], s3 = acc[4], s4 = acc[5], s5 = acc[0], fb = acc[6];
        final NoiseProbe probe = noise;
        final GroundMap gm = ground;
        Parallel.forRange(y1 - y0, 2, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] rx = new float[grid.nx], ry = new float[grid.nx], nr = new float[grid.nx * NK];
                float[] st = new float[3], rgb = new float[3], dz = new float[6], nz = new float[3], pz = new float[3], ps = new float[3];
                float[] o0 = new float[3], dO = new float[3], n0 = new float[3], dN = new float[3];
                float[] d = cur.data;
                int rs = cur.rs;
                long rej = 0, inc = 0, edge = 0, edgeRej = 0;
                StreakEvidence.Batch eb = ev != null ? ev.batch() : null;
                StreakEvidence.DeepBatch db = ev != null && ev.deep() ? ev.deepBatch(y0 + j0, y0 + j1) : null;
                for (int j = j0; j < j1; j++) {
                    int y = y0 + j;
                    grid.row(y + 0.5, rx, ry);
                    normRow(nvf, iyA, grid.ny, grid.nx, y + 0.5, nr);
                    for (int ix = 0; ix < grid.nx - 1; ix++) {
                        int xa = ix * STEP;
                        if (xa >= W) break;
                        int xb = Math.min(W, xa + STEP);
                        float ax = rx[ix], ay = ry[ix];
                        float dx = (rx[ix + 1] - ax) / STEP, dy = (ry[ix + 1] - ay) / STEP;
                        int q = ix * NK;
                        float sc0 = nr[q], dsc = (nr[q + NK] - sc0) / STEP;
                        float w0 = nr[q + 1], dw = (nr[q + NK + 1] - w0) / STEP;
                        float tr0 = nr[q + K_T], dtr = (nr[q + NK + K_T] - tr0) / STEP;
                        float du0 = nr[q + K_DOUBT], ddu = (nr[q + NK + K_DOUBT] - du0) / STEP;
                        for (int c = 0; c < 3; c++) {
                            o0[c] = nr[q + K_OFF + c];
                            dO[c] = (nr[q + NK + K_OFF + c] - o0[c]) / STEP;
                            n0[c] = nr[q + K_NOISE + c];
                            dN[c] = (nr[q + NK + K_NOISE + c] - n0[c]) / STEP;
                        }
                        for (int x = xa; x < xb; x++) {
                            float t = x + 0.5f - xa;
                            float mx = ax + dx * t, my = ay + dy * t;
                            if (!(mx >= 4f && my >= 4f && mx < W - 4f && my < H - 4f)) continue;
                            if (gm != null && gm.nearAt(mx, my)) continue;
                            int bp = j * W + x;
                            if (bit(mask, bp)) continue;
                            boolean edgeSample = du0 + ddu * t >= 0.5f;
                            boolean clipped = sampleRgb(d, rs, mx, my, st, rgb, thr, nz);
                            float sc = sc0 + dsc * t;
                            float w = tr0 + dtr * t < minTransparency ? 0f : w0 + dw * t;
                            boolean kept = bit(keepB, bp);
                            if (!(w > 0)) {
                                for (int c = 0, k = bp * 3; c < 3; c++, k++) fb[k] += rgb[c] * sc + (o0[c] + dO[c] * t);
                                continue;
                            }
                            drizzleRgb(d, rs, mx, my, st, dz);
                            boolean any = false;
                            for (int c = 0, k = bp * 3; c < 3; c++, k++) {
                                float o = o0[c] + dO[c] * t;
                                float v = rgb[c] * sc + o;
                                fb[k] += v;
                                float s = Math.max(1e-3f, (n0[c] + dN[c] * t) * nz[c]);
                                float sk = sd[k], dv = v - cen[k];
                                // every sample, as far from the centre as the noise there makes it
                                if (db != null) {
                                    float sp = s * Math.max(1f, sk);
                                    pz[c] = sk >= 0 && sk < Float.MAX_VALUE ? dv / sp : Float.NaN;
                                    ps[c] = sp;
                                }
                                boolean out;
                                if (sk >= 0) out = dv > kHigh * sk * s || -dv > kLow * sk * s;
                                else out = dv * dv > kHigh * kHigh * s * s + sk * sk && dv > 0
                                        || dv * dv > kLow * kLow * s * s + sk * sk && dv < 0;
                                if (edgeSample) {
                                    edge++;
                                    if (out) edgeRej++;
                                }
                                if (out) {
                                    rej++;
                                    if (dv > 0) {
                                        if (eb != null) eb.add(f, x, y, c == 1 ? dv : 0f);
                                        if (kept && !(trail[k] >= v)) trail[k] = v;
                                    }
                                    // the tail of the noise, not an outlier: the noise gain allows for it
                                    if (c == 1 && probe != null && Math.abs(dv) < NoiseProbe.NOISE_TAIL * s * Math.max(1f, sk)) {
                                        probe.addRejected(f, bp, w, v, dz[1] * sc + o * dz[4], dz[4]);
                                    }
                                    continue;
                                }
                                float dzv = dz[c] * sc + o * dz[c + 3];
                                s2[k] += w * dzv;
                                s3[k] += w * dz[c + 3];
                                s4[k] += w * v;
                                s5[k] += w;
                                if (c == 1 && probe != null) probe.add(f, bp, w, v, dzv, dz[c + 3]);
                                any = true;
                            }
                            if (db != null) db.add(f, x, y, pz, ps, 0);
                            if (any) {
                                icnt[bp]++;
                                if (clipped) ccnt[bp]++;
                                inc++;
                            }
                        }
                    }
                }
                if (eb != null) eb.flush();
                if (db != null) db.flush();
                synchronized (res) {
                    res.rejected[f] += rej;
                    res.included[f] += inc;
                    res.edgeSamples += edge;
                    res.edgeRejected += edgeRej;
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------------

    /**
     * The weighted mean of the interpolated samples, moved toward the drizzle mean as far as there are enough
     * drizzle samples. Pixels without weight take {@code fallback} (a mean already, or a sum over all
     * {@code cnt} samples when {@code fallbackIsSum}).
     */
    private void finish(final int y0, final int y1, final float[] s2, final float[] s3, final float[] s4, final float[] s5,
                        final float[] fallback, final boolean fallbackIsSum, final short[] cnt, final short[] icnt,
                        final short[] ccnt, final short[] gcnt, final int covThr, final RgbImage out, final Result res) {
        final long[] empty = res.empty;
        Parallel.forRange(y1 - y0, 2, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int j = j0; j < j1; j++) {
                    int y = y0 + j;
                    int left = -1, right = -1;
                    for (int x = 0; x < W; x++) {
                        int bp = j * W + x;
                        int c = cnt[bp], ic = icnt[bp];
                        if (c + (gcnt != null ? gcnt[bp] : 0) >= covThr) {
                            if (left < 0) left = x;
                            right = x + 1;
                        }
                        int o = (y - outRow0) * W + x;
                        if (c == 0 && empty != null) {
                            synchronized (empty) {
                                empty[o >> 6] |= 1L << o;
                            }
                        }
                        out.clip[o] = ic > 0 ? (byte) Math.min(255, (255 * ccnt[bp] + ic - 1) / ic) : 0;
                        for (int ch = 0, k = bp * 3; ch < 3; ch++, k++) {
                            float v;
                            if (c == 0) {
                                v = 0f;
                            } else if (s5[k] > 0) {
                                v = s4[k] / s5[k];
                                if (s3[k] > 0) {
                                    // effective number of drizzle samples ~ frames x mean drop weight
                                    float eff = s3[k] / s5[k] * ic;
                                    float a = Math.min(1f, eff * 0.5f);
                                    v += a * (s2[k] / s3[k] - v);
                                }
                            } else {
                                v = fallbackIsSum ? fallback[k] / c : fallback[k];
                            }
                            out.channel(ch)[o] = v;
                        }
                    }
                    res.rowL[y] = left;
                    res.rowR[y] = right;
                }
            }
        });
    }

    /** Lens shading (vignetting and colour shading) correction in reference-frame coordinates. */
    private void applyShading(final RgbImage img) {
        final ShadingMap sh = spec.shading;
        if (sh == null || sh.isUnity()) return;
        final int step = 16;
        final int nxs = W / step + 2;
        final int oy = outRow0;
        Parallel.forRange(img.height, 8, new Parallel.Range() {
            @Override
            public void run(int y0, int y1) {
                float[] gr = new float[nxs], gg = new float[nxs], gb = new float[nxs];
                for (int y = y0; y < y1; y++) {
                    double yc = y + oy + 0.5;
                    for (int i = 0; i < nxs; i++) {
                        double xc = i * step;
                        gr[i] = sh.gain(pR, xc, yc);
                        gg[i] = pG2 != pG1 ? 0.5f * (sh.gain(pG1, xc, yc) + sh.gain(pG2, xc, yc)) : sh.gain(pG1, xc, yc);
                        gb[i] = sh.gain(pB, xc, yc);
                    }
                    int o = y * W;
                    for (int x = 0; x < W; x++) {
                        float fx = (x + 0.5f) / step;
                        int i = (int) fx;
                        float t = fx - i;
                        img.r[o + x] *= gr[i] + (gr[i + 1] - gr[i]) * t;
                        img.g[o + x] *= gg[i] + (gg[i + 1] - gg[i]) * t;
                        img.b[o + x] *= gb[i] + (gb[i + 1] - gb[i]) * t;
                    }
                }
            }
        });
    }

    /**
     * Largest axis-aligned rectangle inside the per-row coverage spans (even-aligned). Falls back to the
     * whole image if coverage is degenerate.
     */
    public static int[] cropFromSpans(int[] rowL, int[] rowR, int W, int H) {
        int mid = H / 2;
        int minSpan = W / 2;
        if (rowL[mid] < 0 || rowR[mid] - rowL[mid] < minSpan) {
            // find any good row
            mid = -1;
            for (int y = 0; y < H; y++) {
                if (rowL[y] >= 0 && rowR[y] - rowL[y] >= minSpan) {
                    mid = y;
                    break;
                }
            }
            if (mid < 0) return new int[]{0, 0, W, H};
        }
        int top = mid, bot = mid;
        while (top > 0 && rowL[top - 1] >= 0 && rowR[top - 1] - rowL[top - 1] >= minSpan) top--;
        while (bot < H - 1 && rowL[bot + 1] >= 0 && rowR[bot + 1] - rowL[bot + 1] >= minSpan) bot++;
        long bestArea = -1;
        int bx0 = 0, by0 = 0, bx1 = W, by1 = H;
        int stride = Math.max(1, (bot - top + 1) / 768);
        for (int yt = top; yt <= bot; yt += stride) {
            int lx = 0, rx = W;
            for (int yb = yt; yb <= bot; yb++) {
                if (rowL[yb] > lx) lx = rowL[yb];
                if (rowR[yb] < rx) rx = rowR[yb];
                if (rx - lx < minSpan) break;
                long area = (long) (rx - lx) * (yb - yt + 1);
                if (area > bestArea) {
                    bestArea = area;
                    bx0 = lx;
                    bx1 = rx;
                    by0 = yt;
                    by1 = yb + 1;
                }
            }
        }
        if (bestArea <= 0) return new int[]{0, 0, W, H};
        bx0 = (bx0 + 1) & ~1;
        by0 = (by0 + 1) & ~1;
        bx1 &= ~1;
        by1 &= ~1;
        if (bx1 - bx0 < 16 || by1 - by0 < 16) return new int[]{0, 0, W, H};
        return new int[]{bx0, by0, bx1, by1};
    }
}
