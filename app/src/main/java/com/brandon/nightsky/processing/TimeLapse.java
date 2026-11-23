package com.brandon.nightsky.processing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The time lapse (2.5): the whole night as a video, one picture for every stored unit in the order they were taken, the
 * camera held still as it lay, so the sky turns over the ground, clouds drift through, the Moon's light comes and goes and
 * satellites and planes cross. Unlike the Earth's turn (a photo turned by the sky's measured turn), every picture is a
 * real moment of the night.
 * <p>
 * Each picture is the unit read as the photo's video reads its frames ({@link PhotoVideo#load}: defective pixels mended,
 * black off, the sensor's pattern out where the stack measured it), area-averaged to the video's size per colour plane at
 * the plane's own place, with the lens shading out. Its sky's grain is then averaged with the units either side wherever
 * they agree to within the noise: a moving star, a satellite or a cloud's edge differs by far more than that, so those
 * stay sharp where they are while the sky between them gets about √3 times less grainy. It's rendered with the photo's
 * own colours, background and tone curve (the Analysis and Params it was developed with), so the video looks like the
 * photo.
 * <p>
 * Three looks ({@link Style}): the sky as it was; star trails growing as the night goes on (the trails photo drawing
 * itself, at the night's own pace); and comet tails, every star dragging a fading tail of where it has just been.
 * <p>
 * A short night is stretched to {@link #MIN_SECONDS} by blending between pictures: the sky moves less than a pixel of
 * the video from one unit to the next, so a blend between two looks like the move between them.
 */
public final class TimeLapse {
    public static final int FPS = 30;
    /** The video's long side, at most (pixels): 1920 x 1440 from a 4:3 sensor. */
    public static final int MAX_SIDE = 1920;
    /** The size used when memory or the phone's encoder can't take {@link #MAX_SIDE}. */
    public static final int SMALL_SIDE = 1440;
    /** Fewer units than this make a flicker, not a film: no time lapse. */
    public static final int MIN_UNITS = 24;
    /** Short nights are stretched to this long (seconds), by blending between pictures... */
    static final double MIN_SECONDS = 8.0;
    /** ...but no unit stands for more than this many video frames (more would be a slide show). */
    static final int MAX_REPEAT = 4;
    /** The last picture is held this long (seconds) at the end. */
    static final double HOLD_S = 0.7;
    /** Units either side whose sky is averaged in where it agrees. */
    static final int NEAR = 2;
    /** Two pictures "agree" at a pixel when their luminance differs by less than about this many noise sigmas. */
    static final float AGREE_K = 2.5f;
    /** Grid rows read and added up at a time. */
    private static final int BAND = 48;
    /** The lens shading's gains are worked out every this many grid columns and interpolated between. */
    private static final int GAIN_STEP = 8;
    /** A unit's colour noise counts this many times more than its luminance's grain says (see {@link #lookFor}). */
    static final float CHROMA_K = 1.6f;
    /** Colour strength against the photo's: a little less, as the colours of a unit's faint stars are less sure. */
    static final float SATURATION = 0.85f;
    /** Regions of the grid over which a unit's noise is measured. */
    private static final int TGX = 8, TGY = 6;

    /** How the night looks. */
    public enum Style {
        SKY("sky", "The sky turning"), TRAILS("trails", "Star trails growing"), COMET("comet", "Comet tails");

        public final String id, label;

        Style(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** The style with this id; {@link #SKY} for anything else. */
        public static Style parse(String id) {
            if (id != null) for (Style s : values()) if (s.id.equalsIgnoreCase(id.trim())) return s;
            return SKY;
        }
    }

    /** One stored unit: which, when (seconds from the session's start to its middle), and how its values are read. */
    public static final class Frame {
        public final int unit;
        public final double t;
        public final float invScale;
        public final float[] black;
        /** The sensor's pattern the stack took out of it, or null. */
        public SensorPattern.Frame pattern;

        public Frame(int unit, double t, float invScale, float[] black) {
            this.unit = unit;
            this.t = t;
            this.invScale = invScale;
            this.black = black.clone();
        }
    }

    /**
     * What a time lapse is made from, all of it small: the frames on disk and how to read them, and the photo's analysis
     * and settings. Kept with the result after developing, so the video can be made again in another style while the
     * frames are still there.
     */
    public static final class Source {
        public final SensorSpec spec;
        public final FrameStore store;
        public final HotPixels hot;
        public final List<Frame> frames;
        public final Renderer.Analysis analysis;
        public final Renderer.Params params;
        /** Wall-clock time (ms) of the session's start, for the clock on the pictures; 0: none. */
        public final long startWallMs;

        public Source(SensorSpec spec, FrameStore store, HotPixels hot, List<Frame> frames, Renderer.Analysis analysis,
                      Renderer.Params params, long startWallMs) {
            this.spec = spec;
            this.store = store;
            this.hot = hot;
            List<Frame> f = new ArrayList<Frame>(frames);
            Collections.sort(f, new Comparator<Frame>() {
                @Override
                public int compare(Frame a, Frame b) {
                    return Double.compare(a.t, b.t);
                }
            });
            this.frames = Collections.unmodifiableList(f);
            this.analysis = analysis;
            this.params = params;
            this.startWallMs = startWallMs;
        }

        /**
         * The units of a developed session: {@code units} as the session recorded them (those the phone moved in are left
         * out, as are any whose file is gone), read with the hot pixels and sensor's pattern of {@code video} (how
         * developing read the frames; may be null) and rendered as {@code a} and {@code p} render the photo.
         */
        public static Source of(SensorSpec spec, FrameStore store, PhotoVideo.Source video, List<UnitRecord> units,
                                Renderer.Analysis a, Renderer.Params p, long startWallMs) {
            java.util.Map<Integer, SensorPattern.Frame> patterns = new java.util.HashMap<Integer, SensorPattern.Frame>();
            HotPixels hot = null;
            if (video != null) {
                hot = video.hot;
                if (video.plans != null) for (SharpStacker.Plan pl : video.plans) if (pl.pattern != null) patterns.put(pl.unit, pl.pattern);
            }
            List<Frame> fs = new ArrayList<Frame>();
            for (UnitRecord r : units) {
                if (r.disturbed || !store.exists(r.index)) continue;
                Frame f = new Frame(r.index, r.t, r.invScale, r.black);
                f.pattern = patterns.get(r.index);
                fs.add(f);
            }
            return new Source(spec, store, hot, fs, a, p, startWallMs);
        }

        /**
         * The units of a session developed earlier and read again (opened from the sessions page), whose frames are still
         * kept: read with {@code hot} (its defective pixels found again from the session's average, {@link
         * PostProcessor#storedHotPixels}; null for none), without the sensor's pattern (measured only while developing).
         */
        public static Source reread(SensorSpec spec, FrameStore store, HotPixels hot, List<UnitRecord> units, Renderer.Analysis a,
                                    Renderer.Params p, long startWallMs) {
            List<Frame> fs = new ArrayList<Frame>();
            for (UnitRecord r : units) {
                if (r.disturbed || !store.exists(r.index)) continue;
                fs.add(new Frame(r.index, r.t, r.invScale, r.black));
            }
            return new Source(spec, store, hot, fs, a, p, startWallMs);
        }

        /** How long the night it covers was, first unit to last (seconds; 0 with fewer than two). */
        public double spanSeconds() {
            int n = frames.size();
            return n < 2 ? 0 : frames.get(n - 1).t - frames.get(0).t;
        }

        /** Seconds of sky between one unit and the next (the median step), or 0 with fewer than two. */
        public double secondsPerUnit() {
            int n = frames.size();
            if (n < 2) return 0;
            float[] d = new float[n - 1];
            for (int i = 1; i < n; i++) d[i - 1] = (float) (frames.get(i).t - frames.get(i - 1).t);
            return Stats.median(d, d.length);
        }

        /** Whether there are enough units for a time lapse. */
        public boolean enough() {
            return frames.size() >= MIN_UNITS;
        }
    }

    /** Draws the caption on a picture (the app: the clock and how much faster than the sky; tests: nothing). */
    public interface Overlay {
        /**
         * @param wallMs  the picture's moment (ms since 1970), or 0 when the session's start isn't known
         * @param minutes minutes since the first unit
         * @param speedUp how many times faster than the sky the video plays
         */
        void draw(int[] argb, int w, int h, long wallMs, double minutes, double speedUp);
    }

    private final Source src;
    private final Style style;
    /** The photo's analysis, and the one the pictures are rendered with (made at the first picture: see {@link #lookFor}). */
    private final Renderer.Analysis pa;
    private Renderer.Analysis a;
    private final Renderer.Params params;
    private final int W, H;
    /** The photo's crop on the sensor, and the grid: the crop scaled to the video, sx x sy sensor pixels a grid pixel. */
    private final int cx0, cy0, cw, ch, gw, gh;
    private final double sx, sy;
    /** The video: cut from the grid picture at (cutX, cutY), a multiple of 16 each way. */
    private final int vw, vh, cutX, cutY;
    private final int pR, pG1, pG2, pB;
    /** Video frames in all, and the unit (fractional) each one shows. */
    private final int outFrames, holdFrames;
    private float[][] gains;
    private int gn;
    private String note = "";
    private long readNs, gridNs, filterNs, renderNs, sendNs;
    /** Units either side averaged in ({@link #NEAR}; a test sets 0 to see a unit as it is). */
    int near = NEAR;
    /** How much grainier than the photo the first picture was (at the video's scale), for the log. */
    private float grain = 1f;

    private TimeLapse(Source src, Style style, int maxSide) {
        this.src = src;
        this.style = style;
        W = src.spec.width;
        H = src.spec.height;
        pa = src.analysis;
        int x0 = Math.max(0, pa.cx0), y0 = Math.max(0, pa.cy0);
        int x1 = Math.min(W, pa.cx0 + pa.cw), y1 = Math.min(H, pa.cy0 + pa.ch);
        cx0 = x0;
        cy0 = y0;
        cw = Math.max(1, x1 - x0);
        ch = Math.max(1, y1 - y0);
        double s = Math.min(1.0, maxSide / (double) Math.max(cw, ch));
        gw = Math.max(1, (int) Math.round(cw * s));
        gh = Math.max(1, (int) Math.round(ch * s));
        sx = cw / (double) gw;
        sy = ch / (double) gh;
        vw = gw & ~15;
        vh = gh & ~15;
        cutX = (gw - vw) / 2;
        cutY = (gh - vh) / 2;
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
        a = lookFor(1f);
        params = copy(src.params);
        params.glow = 0;
        params.saturation *= SATURATION;
        if (style != Style.SKY) params.sharp = 0;
        int n = src.frames.size();
        int want = (int) Math.ceil(MIN_SECONDS * FPS);
        outFrames = Math.max(n, Math.min(n * MAX_REPEAT, want));
        holdFrames = (int) Math.round(HOLD_S * FPS);
    }

    /**
     * The photo's look for pictures {@code k} times as grainy as the photo (at the same scale): the stretch's foot and the
     * colours' noise that much further out ({@link Renderer#noisier}), so a single unit's grain is shown as quietly as the
     * stack's is, at the same grey; without what belongs to the stack's own pixels (no glow: it's drawn where the photo's
     * stars are, and the sky has turned since); star trails' colour windows for the styles that draw trails.
     */
    private Renderer.Analysis lookFor(float k) {
        Renderer.Analysis r = Renderer.noisier(pa, Math.max(1f, k));
        r.trails = pa.trails || style != Style.SKY;
        // the stars' colours measured over a wider window than the photo's (2 pixels of the video, 3 for trails): a single
        // unit has far fewer photons per star than the stack, and a colour guessed from too few comes out as confetti
        float win = (float) ((style != Style.SKY ? 3.0 : 2.0) * sx);
        if (r.colorSigma < win) r.colorSigma = win;
        r.colorWindows = null;
        // and faint stars' colours shrunk to white sooner: their colour noise is a unit's, not the stack's
        r.noiseS *= CHROMA_K;
        r.noiseP *= CHROMA_K;
        return r;
    }

    /**
     * A time lapse of {@code src} in {@code style}, its long side at most {@code maxSide}; null when there are too few
     * units ({@link #MIN_UNITS}) or the picture is too small for a video.
     */
    public static TimeLapse of(Source src, Style style, int maxSide) {
        if (src == null || src.analysis == null || src.params == null || !src.enough()) return null;
        TimeLapse t = new TimeLapse(src, style == null ? Style.SKY : style, maxSide);
        return t.vw >= 64 && t.vh >= 64 ? t : null;
    }

    /** Each unit as it is, without its neighbours' sky averaged in (for comparing, in the tests). */
    public TimeLapse unfiltered() {
        near = 0;
        return this;
    }

    public int width() {
        return vw;
    }

    public int height() {
        return vh;
    }

    public Style style() {
        return style;
    }

    /** How much grainier than the photo the first picture was, at the video's scale (after {@link #build}). */
    public float grain() {
        return grain;
    }

    /** Video frames in all (the night and the held last picture). */
    public int frameCount() {
        return outFrames + holdFrames;
    }

    /** How long the video plays (ms). */
    public long durationMs() {
        return Math.round(frameCount() * 1000.0 / FPS);
    }

    /** How many times faster than the sky the video plays. */
    public double speedUp() {
        double spu = src.secondsPerUnit();
        int n = src.frames.size();
        return spu > 0 ? spu * FPS * n / (double) outFrames : 0;
    }

    /** What the build did, for the log ("" before it). */
    public String note() {
        return note;
    }

    /**
     * About how much memory making it takes (bytes): per grid pixel the units either side and the one being made, the
     * style's own (the trails' average and brightest moment, or the tails), the picture rendered; the two pictures blended
     * between and the one sent; a band of frame rows.
     */
    public long heapNeeded() {
        long np = (long) gw * gh, v = (long) vw * vh;
        long units = (2L * near + 1) * np * 12;
        long styled = style == Style.SKY ? 0 : style == Style.TRAILS ? np * 28 : np * 16;
        long render = np * 12 + np * 4 + 3 * v * 4;
        long band = (long) Math.ceil((BAND + 2) * sy + 13) * W * 6;
        return units + styled + render + band;
    }

    /**
     * Sends every picture of the video to {@code sink} in order, {@code overlay} (null: none) drawing each one's caption.
     * Reads every unit once.
     */
    public void build(PhotoVideo.Sink sink, Overlay overlay, Progress progress) throws IOException {
        long t0 = System.currentTimeMillis();
        final List<Frame> frames = src.frames;
        final int n = frames.size(), np = gw * gh;
        makeGains();
        final float[] nb = new float[3];
        for (int c = 0; c < 3; c++) nb[c] = 1f / Math.max(0.05f, src.spec.camNeutral[c] / Math.max(0.05f, src.spec.camNeutral[1]));
        // the units around the one being made: ring[k % size] holds unit k's grid
        final int ringSize = 2 * near + 1;
        float[][] ring = new float[ringSize][];
        final RgbImage S = new RgbImage(gw, gh);
        final float[] cur = new float[np * 3];
        // the style's own: star trails' running average and brightest moment, or the comet tails' fading brightest
        float[] mean = null, top = null, topY = null;
        if (style == Style.TRAILS) {
            mean = new float[np * 3];
            top = new float[np * 3];
            topY = new float[np];
            Arrays.fill(topY, -Float.MAX_VALUE);
        } else if (style == Style.COMET) {
            top = new float[np * 3];
            topY = new float[np];
            Arrays.fill(topY, -Float.MAX_VALUE);
        }
        // a tail fades to half in about a sixth of the night (at least 6 units): long enough to see the stars' paths
        final float decay = (float) Math.pow(0.5, 1.0 / Math.max(6.0, n / 6.0));
        float[] sigma = null;
        float ns = 0;
        Emitter em = new Emitter(n, sink);
        double spu = src.secondsPerUnit(), speed = speedUp();
        int read = 0;
        for (int i = 0; i < n; i++) {
            // the grids this picture needs: up to NEAR units ahead
            while (read < n && read <= i + near) {
                float[] g = ring[read % ringSize];
                if (g == null) g = new float[np * 3];
                grid(frames.get(read), g, progress);
                ring[read % ringSize] = g;
                read++;
            }
            if (sigma == null) sigma = noiseOf(ring[i % ringSize], nb);
            long tf = System.nanoTime();
            filter(ring, ringSize, i, n, cur, sigma, nb);
            float[] shown = cur;
            if (style == Style.TRAILS) {
                stateTrails(cur, mean, top, topY, i + 1, sigma, nb, S);
            } else if (style == Style.COMET) {
                stateComet(cur, top, topY, decay, sigma, nb, S);
            } else {
                toImage(shown, S);
            }
            filterNs += System.nanoTime() - tf;
            long tr = System.nanoTime();
            float noise = noise(S);
            if (i == 0) {
                // how much grainier than the photo (at this scale) a picture is: the look made for that, once, so the
                // stretch doesn't flicker from one picture to the next
                float photo = pa.noiseY / (float) sx;
                grain = photo > 0 && noise > 0 ? noise / photo : 1f;
                a = lookFor(grain);
            }
            if (ns == 0 || style == Style.TRAILS) {
                float k = a.noiseY > 0 && noise > 0 ? noise / a.noiseY : (float) (1 / sx);
                ns = ns == 0 ? k : 0.9f * ns + 0.1f * k;
            }
            int[] px = cut(Renderer.renderScaled(S, a, params, (float) sx, ns));
            if (overlay != null) {
                Frame f = frames.get(i);
                long wall = src.startWallMs > 0 ? src.startWallMs + Math.round(f.t * 1000) : 0;
                overlay.draw(px, vw, vh, wall, (f.t - frames.get(0).t) / 60.0, speed);
            }
            renderNs += System.nanoTime() - tr;
            long ts = System.nanoTime();
            em.picture(i, px);
            sendNs += System.nanoTime() - ts;
            if (progress.cancelled()) throw new CancelledException();
            progress.update("Making the time lapse", (i + 1) / (float) n);
        }
        em.end(holdFrames);
        gains = null;
        note = String.format(Locale.US, "%dx%d, %s, %d units (%.1f s of sky each) in %d pictures, %.1f s at %d a second, %.0f times faster "
                        + "than the sky, %.1f times the photo's grain shown as the photo's (%d ms: reading %d, the grid %d, the noise and the "
                        + "style %d, rendering %d, encoding %d)", vw, vh,
                style.label.toLowerCase(Locale.US), n, spu, em.sent, em.sent / (double) FPS, FPS, speed, grain, System.currentTimeMillis() - t0,
                readNs / 1000000, gridNs / 1000000, filterNs / 1000000, (renderNs) / 1000000, sendNs / 1000000);
    }

    // ------------------------------------------------------------------------------------------------
    // One unit on the grid
    // ------------------------------------------------------------------------------------------------

    private short[] raw = new short[0];
    private float[] d = new float[0];

    /** Unit {@code f} area-averaged onto the grid (camera RGB, linear, the lens shading out) into {@code g}. */
    private void grid(Frame f, final float[] g, Progress progress) throws IOException {
        final float hx = (float) (sx / 2), hy = (float) (sy / 2);
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
            PhotoVideo.load(W, src.store, src.hot, f.unit, f.invScale, f.black, f.pattern, rs, re, raw, d);
            long tb = System.nanoTime();
            readNs += tb - ta;
            final float[] dd = d;
            final int frs = rs, fre = re;
            Parallel.forRange(gy1 - gy0, 2, new Parallel.Range() {
                @Override
                public void run(int j0, int j1) {
                    float[] rgb = new float[3];
                    float[] gr = new float[gw], gg = new float[gw], gb = new float[gw];
                    for (int j = j0; j < j1; j++) {
                        int y = gy0 + j;
                        gainRow(y, gr, gg, gb);
                        float my = (float) (cy0 + (y + 0.5) * sy);
                        boolean rowOk = my - hy - 2f >= frs && my + hy + 2f <= fre;
                        for (int x = 0; x < gw; x++) {
                            int k3 = (y * gw + x) * 3;
                            float mx = (float) (cx0 + (x + 0.5) * sx);
                            if (!rowOk || !(mx - hx >= 0.5f && mx + hx <= W - 0.5f && my - hy >= 0.5f && my + hy <= H - 0.5f)) {
                                g[k3] = g[k3 + 1] = g[k3 + 2] = 0f;
                                continue;
                            }
                            boxRgb(dd, frs, mx, my, hx, hy, rgb);
                            g[k3] = rgb[0] * gr[x];
                            g[k3 + 1] = rgb[1] * gg[x];
                            g[k3 + 2] = rgb[2] * gb[x];
                        }
                    }
                }
            });
            gridNs += System.nanoTime() - tb;
            if (progress.cancelled()) throw new CancelledException();
        }
        // the edge rows and columns the box couldn't fill take their neighbours' values (no black frame round the video)
        fillEdges(g);
    }

    /** Grid pixels left at 0 along the edges (where a box ran off the sensor) copied from the nearest filled one. */
    private void fillEdges(float[] g) {
        for (int y = 0; y < gh; y++) {
            int o = y * gw;
            int first = -1, last = -1;
            for (int x = 0; x < gw; x++) {
                int k = (o + x) * 3;
                if (g[k] != 0f || g[k + 1] != 0f || g[k + 2] != 0f) {
                    if (first < 0) first = x;
                    last = x;
                }
            }
            if (first < 0) continue;
            for (int x = 0; x < first; x++) System.arraycopy(g, (o + first) * 3, g, (o + x) * 3, 3);
            for (int x = last + 1; x < gw; x++) System.arraycopy(g, (o + last) * 3, g, (o + x) * 3, 3);
        }
        int firstRow = -1, lastRow = -1;
        for (int y = 0; y < gh; y++) {
            int k = y * gw * 3;
            boolean any = false;
            for (int i = 0; i < gw * 3 && !any; i++) any = g[k + i] != 0f;
            if (any) {
                if (firstRow < 0) firstRow = y;
                lastRow = y;
            }
        }
        if (firstRow < 0) return;
        for (int y = 0; y < firstRow; y++) System.arraycopy(g, firstRow * gw * 3, g, y * gw * 3, gw * 3);
        for (int y = lastRow + 1; y < gh; y++) System.arraycopy(g, lastRow * gw * 3, g, y * gw * 3, gw * 3);
    }

    /** R, G and B over the box (mx +- hx, my +- hy) of the frame, each from its own photosites. */
    private void boxRgb(float[] d, int rs, float mx, float my, float hx, float hy, float[] rgb) {
        rgb[0] = box(d, rs, pR, mx, my, hx, hy);
        float g = box(d, rs, pG1, mx, my, hx, hy);
        if (pG2 != pG1) g = 0.5f * (g + box(d, rs, pG2, mx, my, hx, hy));
        rgb[1] = g;
        rgb[2] = box(d, rs, pB, mx, my, hx, hy);
    }

    /**
     * Area average of plane p over the box (mx +- hx, my +- hy), each photosite standing for the 2 x 2 pixel square
     * centred on it (as {@link PhotoVideo} reads it), so every colour is sampled at its own place.
     */
    private float box(float[] d, int rs, int p, float mx, float my, float hx, float hy) {
        int ox = p & 1, oy = p >> 1;
        float xa = mx - hx, xb = mx + hx, ya = my - hy, yb = my + hy;
        int u0 = (int) Math.floor((xa - ox + 0.5f) * 0.5f), u1 = (int) Math.floor((xb - ox + 0.5f) * 0.5f);
        int v0 = (int) Math.floor((ya - oy + 0.5f) * 0.5f), v1 = (int) Math.floor((yb - oy + 0.5f) * 0.5f);
        float sum = 0, area = 0;
        for (int v = v0; v <= v1; v++) {
            float c0 = 2 * v + oy - 0.5f;
            float wy = Math.min(yb, c0 + 2f) - Math.max(ya, c0);
            if (wy <= 0) continue;
            int row = (2 * v + oy - rs) * W + ox;
            float s = 0, ar = 0;
            for (int u = u0; u <= u1; u++) {
                float x0 = 2 * u + ox - 0.5f;
                float wx = Math.min(xb, x0 + 2f) - Math.max(xa, x0);
                if (wx <= 0) continue;
                s += wx * d[row + 2 * u];
                ar += wx;
            }
            sum += wy * s;
            area += wy * ar;
        }
        return area > 0 ? sum / area : 0f;
    }

    // ------------------------------------------------------------------------------------------------
    // The sky's grain, and the styles
    // ------------------------------------------------------------------------------------------------

    /** A unit's luminance noise per region, from differences of grid pixels two apart (the few stars don't count). */
    private float[] noiseOf(float[] f, float[] nb) {
        float[] out = new float[TGX * TGY];
        float[] buf = new float[(gw / TGX + 2) * (gh / TGY + 2)];
        for (int cy = 0; cy < TGY; cy++) {
            for (int cx = 0; cx < TGX; cx++) {
                int xa = cx * gw / TGX, xb = (cx + 1) * gw / TGX, ya = cy * gh / TGY, yb = (cy + 1) * gh / TGY;
                int m = 0;
                for (int y = ya; y < yb; y += 2) {
                    for (int x = xa; x + 2 < xb && m < buf.length; x += 2) {
                        int i = (y * gw + x) * 3, j = i + 6;
                        buf[m++] = lum(f, j, nb) - lum(f, i, nb);
                    }
                }
                out[cy * TGX + cx] = m >= 8 ? Math.max(1e-4f, Stats.madSigma(buf, m, Stats.median(buf, m)) / (float) Math.sqrt(2)) : 1f;
            }
        }
        return out;
    }

    private static float lum(float[] f, int k3, float[] nb) {
        return 0.25f * f[k3] * nb[0] + 0.5f * f[k3 + 1] * nb[1] + 0.25f * f[k3 + 2] * nb[2];
    }

    /**
     * Unit i with the units either side averaged in where they agree with it ({@link #AGREE_K} sigmas: the sky's grain);
     * where something moved or changed (a star's step, a satellite, a cloud's edge) unit i stays as it is.
     */
    private void filter(final float[][] ring, final int size, final int i, final int n, final float[] out, final float[] sigma,
                        final float[] nb) {
        final float[] c = ring[i % size];
        final int lo = Math.max(0, i - near), hi = Math.min(n - 1, i + near);
        Parallel.forRange(gh, 8, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int y = j0; y < j1; y++) {
                    for (int x = 0; x < gw; x++) {
                        int k3 = (y * gw + x) * 3;
                        float sd = BackgroundGrid.interp(sigma, TGX, TGY, gw, gh, x + 0.5, y + 0.5);
                        float lim = AGREE_K * sd * (float) Math.sqrt(2);
                        float l0 = lum(c, k3, nb);
                        float r = c[k3], g = c[k3 + 1], b = c[k3 + 2], w = 1f;
                        for (int k = lo; k <= hi; k++) {
                            if (k == i) continue;
                            float[] o = ring[k % size];
                            float dl = (lum(o, k3, nb) - l0) / lim;
                            float d2 = dl * dl;
                            float wk = 1f / (1f + d2 * d2);
                            r += wk * o[k3];
                            g += wk * o[k3 + 1];
                            b += wk * o[k3 + 2];
                            w += wk;
                        }
                        float inv = 1f / w;
                        out[k3] = r * inv;
                        out[k3 + 1] = g * inv;
                        out[k3 + 2] = b * inv;
                    }
                }
            }
        });
    }

    private void toImage(float[] f, RgbImage S) {
        int np = gw * gh;
        for (int p = 0; p < np; p++) {
            S.r[p] = f[p * 3];
            S.g[p] = f[p * 3 + 1];
            S.b[p] = f[p * 3 + 2];
        }
    }

    /**
     * Star trails after k units: their average, and each pixel's brightest moment where it stands out of the noise, through
     * the soft threshold the trails stack uses (so the sky's grain isn't lifted).
     */
    private void stateTrails(final float[] f, final float[] mean, final float[] top, final float[] topY, int k, final float[] sigma,
                             final float[] nb, final RgbImage S) {
        final float inv = 1f / k;
        final float kk = (float) Math.max(3.0, Math.sqrt(2 * Math.log(Math.max(2, k))) + 1.0);
        Parallel.forRange(gh, 8, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int y = j0; y < j1; y++) {
                    for (int x = 0; x < gw; x++) {
                        int pix = y * gw + x, k3 = pix * 3;
                        mean[k3] += (f[k3] - mean[k3]) * inv;
                        mean[k3 + 1] += (f[k3 + 1] - mean[k3 + 1]) * inv;
                        mean[k3 + 2] += (f[k3 + 2] - mean[k3 + 2]) * inv;
                        float l = lum(f, k3, nb);
                        if (l > topY[pix]) {
                            topY[pix] = l;
                            top[k3] = f[k3];
                            top[k3 + 1] = f[k3 + 1];
                            top[k3 + 2] = f[k3 + 2];
                        }
                        float r = mean[k3], g = mean[k3 + 1], b = mean[k3 + 2];
                        float dl = topY[pix] - lum(mean, k3, nb);
                        if (dl > 0) {
                            float sd = BackgroundGrid.interp(sigma, TGX, TGY, gw, gh, x + 0.5, y + 0.5);
                            float u = dl / (kk * sd);
                            float u4 = u * u * u * u;
                            float w = u4 / (1f + u4);
                            r += (top[k3] - r) * w;
                            g += (top[k3 + 1] - g) * w;
                            b += (top[k3 + 2] - b) * w;
                        }
                        S.r[pix] = r;
                        S.g[pix] = g;
                        S.b[pix] = b;
                    }
                }
            }
        });
    }

    /**
     * Comet tails: the picture as it is, and over it each pixel's brightest recent moment fading by {@code decay} a unit
     * (above the sky, which stays put), where it stands out of the noise; so each star drags a fading tail behind it.
     */
    private void stateComet(final float[] f, final float[] top, final float[] topY, final float decay, final float[] sigma,
                            final float[] nb, final RgbImage S) {
        final float kk = 3f;
        Parallel.forRange(gh, 8, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int y = j0; y < j1; y++) {
                    for (int x = 0; x < gw; x++) {
                        int pix = y * gw + x, k3 = pix * 3;
                        float l = lum(f, k3, nb);
                        // the tail fades towards the sky as it is now, not towards black
                        if (topY[pix] > -Float.MAX_VALUE) {
                            top[k3] = f[k3] + (top[k3] - f[k3]) * decay;
                            top[k3 + 1] = f[k3 + 1] + (top[k3 + 1] - f[k3 + 1]) * decay;
                            top[k3 + 2] = f[k3 + 2] + (top[k3 + 2] - f[k3 + 2]) * decay;
                            topY[pix] = lum(top, k3, nb);
                        }
                        if (l >= topY[pix]) {
                            topY[pix] = l;
                            top[k3] = f[k3];
                            top[k3 + 1] = f[k3 + 1];
                            top[k3 + 2] = f[k3 + 2];
                        }
                        float r = f[k3], g = f[k3 + 1], b = f[k3 + 2];
                        float dl = topY[pix] - l;
                        if (dl > 0) {
                            float sd = BackgroundGrid.interp(sigma, TGX, TGY, gw, gh, x + 0.5, y + 0.5);
                            float u = dl / (kk * sd);
                            float u4 = u * u * u * u;
                            float w = u4 / (1f + u4);
                            r += (top[k3] - r) * w;
                            g += (top[k3 + 1] - g) * w;
                            b += (top[k3 + 2] - b) * w;
                        }
                        S.r[pix] = r;
                        S.g[pix] = g;
                        S.b[pix] = b;
                    }
                }
            }
        });
    }

    /** The luminance noise of a grid picture (as {@link PhotoVideo} measures it), for the stretch's foot. */
    private float noise(RgbImage S) {
        float[] m = a.m;
        final float KR = 0.2126f, KG = 0.7152f, KB = 0.0722f;
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

    // ------------------------------------------------------------------------------------------------
    // The lens shading, the cut, and sending the pictures
    // ------------------------------------------------------------------------------------------------

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

    /** The video's part (vw x vh, centred) of a grid picture. */
    private int[] cut(int[] px) {
        if (gw == vw && gh == vh) return px;
        int[] out = new int[vw * vh];
        for (int y = 0; y < vh; y++) System.arraycopy(px, (cutY + y) * gw + cutX, out, y * vw, vw);
        return out;
    }

    /**
     * Sends the video frames as the pictures come: frame j shows unit u = j (n - 1) / (out - 1), a blend of the pictures
     * of the units either side of u when it falls between two.
     */
    private final class Emitter {
        final int n;
        final PhotoVideo.Sink sink;
        int[] prev;
        int next;
        int sent;
        final int[] buf = new int[vw * vh];

        Emitter(int n, PhotoVideo.Sink sink) {
            this.n = n;
            this.sink = sink;
        }

        double position(int j) {
            return outFrames <= 1 ? 0 : j * (n - 1) / (double) (outFrames - 1);
        }

        void picture(int i, int[] px) throws IOException {
            while (next < outFrames) {
                double u = position(next);
                if (u > i + 1e-9) break;
                double frac = u - (i - 1);
                if (prev == null || u >= i - 1e-9 || frac <= 1e-9) {
                    sink.frame(px, pts(sent++));
                } else {
                    PhotoVideo.blend(prev, px, (int) Math.round(frac * 256), buf);
                    sink.frame(buf, pts(sent++));
                }
                next++;
            }
            prev = px;
        }

        void end(int hold) throws IOException {
            if (prev == null) return;
            for (int k = 0; k < hold; k++) sink.frame(prev, pts(sent++));
        }
    }

    static long pts(int frame) {
        return Math.round(frame * 1e6 / FPS);
    }

    private static Renderer.Params copy(Renderer.Params p) {
        Renderer.Params c = new Renderer.Params();
        if (p == null) return c;
        c.brightness = p.brightness;
        c.saturation = p.saturation;
        c.lumaNr = p.lumaNr;
        c.detail = p.detail;
        c.glow = p.glow;
        c.sharp = p.sharp;
        c.removeGradient = p.removeGradient;
        c.stretch = p.stretch;
        c.ev = p.ev;
        c.black = p.black;
        c.look = p.look;
        return c;
    }

    /** A video bit rate for this size (bits a second): 0.12 bits a pixel, plenty for a sky of stars that move. */
    public static int bitRate(int w, int h) {
        return (int) Math.min(40_000_000L, Math.max(4_000_000L, Math.round(w * (double) h * FPS * 0.12)));
    }
}
