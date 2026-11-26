package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Galileo's notebook's photo (3.2): Jupiter's burst and the moons' brighter one, each frame lined up on Jupiter's
 * middle and turned (by the phone's tilt at that moment) so Jupiter's pole is up and its equator across, east on the
 * left as Galileo drew it; merged on a grid twice as fine as the sensor's (the hand's shake fills it in), with any
 * frame's pixel far from the others at that place left out (a hot pixel, a cosmic ray, a plane). Jupiter's light
 * where the moons' frames burnt it white comes from its own burst. Then the moons: Jupiter's glow taken off (its own
 * light out from its middle, the same all round), what's left searched for small lights, and the four put where the
 * sums say they are, the picture's turn and scale fitted to the lights found; each named, or why it isn't there
 * (behind Jupiter, crossing it, in its shadow, lost in its glare).
 */
public final class JupiterStack {
    private JupiterStack() {}

    /** Result pixels per sensor pixel. */
    public static final int S = 2;
    /**
     * A moon in the photo: found; behind Jupiter; crossing in front of it; in its shadow; too near it to see; looked
     * for and not seen; outside the photo; right beside another moon found, its light in that one's (closer than the
     * lens's blur can split: {@link #BLEND_SIGMAS}).
     */
    public static final int FOUND = 0, HIDDEN = 1, CROSSING = 2, DARK = 3, GLARE = 4, MISSED = 5, OUTSIDE = 6, BLENDED = 7;
    /** Two moons closer than this (times the blur's sigma) are one light on the photo. */
    public static final double BLEND_SIGMAS = 2.4;
    /** A moon not found within this many blurs of one found: on that one's skirt, lost in its light. */
    public static final double SKIRT_SIGMAS = 3.6;
    /** Nearer Jupiter's middle than this (its radii) nothing is looked for: its own light. */
    public static final double GLARE_RJ = 2.2;

    public static final class Options {
        /** The sensor's pixel scale (arcseconds). */
        public double arcsecPerPx = 17;
        /** Jupiter at the burst's middle. */
        public JupiterGeo.View view;
        /** The burst's up from the tilt, for frames with none of their own (NaN: unknown). */
        public double upPa = Double.NaN;
        /** How far the fit may turn the picture from the tilt's (degrees: 180 when the tilt is unknown). */
        public double turnRange = 10;
        /** The lens's blur (sigma, sensor px), for the search until the moons show their own. */
        public double psfSigma = 1.1;
        /** The least worth calling a light: times the noise there. */
        public double sigmas = 5;
    }

    /** A small light found beside Jupiter. */
    public static final class Light {
        /** Its place (result px), its light over the glare (result units x px), and how far over the noise. */
        public double x, y, flux, snr;
        /** Which moon (0..3), or -1: a star behind, or something else. */
        public int moon = -1;
        /** Found only where a moon must be, fainter than a light found anywhere would have to be. */
        public boolean faint;
        /** Its place in Jupiter's radii, x west along its equator and y north, by the fit. */
        public double jx, jy;

        @Override
        public String toString() {
            return String.format(Locale.US, "%s at %.1f,%.1f (%.1f west, %.1f north) snr %.0f", moon >= 0 ? JupiterGeo.NAMES[moon] : "light", x, y, jx, jy, snr);
        }
    }

    public static final class Result {
        public int w;
        /** Linear sRGB (the Jupiter burst's frames' units: 1 is the white level), w x w; Jupiter's burst alone too. */
        public float[] r, g, b, r1, g1, b1;
        /** Jupiter's middle (result px) and its equatorial radius (result px). */
        public double cx, cy, rJ;
        /** The fit: the picture's turn from what the tilt gave (degrees, counterclockwise as seen) and its scale. */
        public double turn, scale = 1;
        /** Jupiter's brightest (linear), the sky's level and its noise (linear, a result pixel). */
        public double peak, sky, noise;
        public final List<Light> lights = new ArrayList<Light>();
        /** Each moon's state ({@link #FOUND} ...), its light (null unless found), and where the sums put it (result px). */
        public final int[] status = new int[4];
        public final Light[] moon = new Light[4];
        public final double[][] predicted = new double[4][];
        /** For a moon not found: how far over the noise it would have shown (from the moons found; NaN: unknown). */
        public final double[] expectSnr = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        /**
         * For a moon {@link #BLENDED}: the moon found whose light it's in; for one {@link #MISSED}: a moon found right
         * beside it (closer than {@link #SKIRT_SIGMAS} blurs), whose light it may be lost in; else -1.
         */
        public final int[] with = {-1, -1, -1, -1};
        public int used, total, moonsUsed, moonsTotal;
        public double moonsGain;
        /** The tilt said which way was up (else the turn was found from the moons alone). */
        public boolean tilted;
        /** Hot pixels left out, and how well each result pixel was covered (the frames' weight there, green). */
        public int hot;
        public double[] cover;
        /** Each result pixel's share of the moons' frames that were white there. */
        public double[] clip;
        /** The search's own pictures: Jupiter's glow taken off, that smoothed, and its noise ring by ring (3 px). */
        public float[] resid, filt;
        public double[] ringNoise;
        /** The glow's shifts and stretches fitted ({@link #shape}), for the log. */
        double[] shapeFit;
        /** The lens's blur in the result as the brightest lights show it (sigma, result px; 0 until measured). */
        public double psf;
        /** R's noise ring by ring (3 px). */
        double[] noiseRings;
        public String log = "";

        /** The glow's fitted shift (x, y) and stretch (cos, sin), for logs. */
        public double[] shapeFitPublic() {
            return shapeFit;
        }

        public int found() {
            int n = 0;
            for (int s : status) if (s == FOUND) n++;
            return n;
        }
    }

    /**
     * The photo from Jupiter's frames and (if any) the moons' frames, each with its gain against Jupiter's
     * ({@code gains}, null: {@code moonsGain} for all).
     */
    public static Result stack(List<MoonStack.Frame> main, List<MoonStack.Frame> moons, List<Double> gains, double moonsGain, SensorSpec spec,
                               Options o, Progress pr) {
        if (pr == null) pr = Progress.NONE;
        if (main.isEmpty()) throw new IllegalArgumentException("no frames");
        Result res = new Result();
        int side = main.get(0).w;
        int W = side * S;
        res.w = W;
        res.cx = res.cy = W / 2.0;
        double rJs = o.view != null ? o.view.radiusArcsec / o.arcsecPerPx : 1.15;
        res.rJ = rJs * S;
        double pole = o.view != null ? o.view.poleDeg : 0;
        StringBuilder log = new StringBuilder();
        // where Jupiter is in each frame, how sharp, its sky's noise
        pr.update("Finding Jupiter in every frame", 0);
        List<Prep> mp = prepare(main, spec, 1);
        List<Prep> op = new ArrayList<Prep>();
        if (moons != null) {
            for (int i = 0; i < moons.size(); i++) {
                double g = gains != null && i < gains.size() && gains.get(i) > 0 ? gains.get(i) : moonsGain;
                List<Prep> one = prepare(java.util.Collections.singletonList(moons.get(i)), spec, 1 / g);
                op.addAll(one);
            }
        }
        // pixels lit alone in nearly every frame at the same place on the sensor: hot pixels, left out
        List<Prep> all = new ArrayList<Prep>(mp);
        all.addAll(op);
        res.hot = hotPixels(all);
        // Jupiter's burst: the sharpest three quarters (at least a dozen)
        double[] sharp = new double[mp.size()];
        for (int i = 0; i < mp.size(); i++) sharp[i] = mp.get(i).ok ? mp.get(i).sharp : 0;
        double[] ss = sharp.clone();
        Arrays.sort(ss);
        double cut = ss[Math.max(0, Math.min(ss.length - 12, (int) Math.floor(0.25 * ss.length)))];
        List<Prep> keepMain = new ArrayList<Prep>();
        for (Prep p : mp) if (p.ok && p.sharp >= cut) keepMain.add(p);
        List<Prep> keepMoons = new ArrayList<Prep>();
        for (Prep p : op) if (p.ok) keepMoons.add(p);
        res.total = main.size();
        res.used = keepMain.size();
        res.moonsTotal = moons != null ? moons.size() : 0;
        res.moonsUsed = keepMoons.size();
        res.moonsGain = moonsGain;
        if (keepMain.isEmpty()) throw new IllegalArgumentException("Jupiter wasn't found in the frames");
        // each frame's turn to Jupiter's pole up
        boolean tilted = false;
        for (Prep p : mp) if (!Double.isNaN(p.f.upPa)) tilted = true;
        if (!Double.isNaN(o.upPa)) tilted = true;
        res.tilted = tilted;
        for (Prep p : mp) p.M = turnMatrix(Double.isNaN(p.f.upPa) ? o.upPa : p.f.upPa, pole, tilted);
        for (Prep p : op) p.M = turnMatrix(Double.isNaN(p.f.upPa) ? o.upPa : p.f.upPa, pole, tilted);
        pr.update("Lining up Jupiter's frames", 0.2f);
        double[] cover = new double[W * W];
        float[][] mainGlow = new float[3][W * W];
        float[][] mainRgb = merge(keepMain, spec, W, null, cover, mainGlow);
        float[][] moonsRgb = null, moonsGlow = new float[3][W * W];
        double[] clipShare = new double[W * W];
        if (keepMoons.size() >= 6) {
            pr.update("Lining up the moons' frames", 0.45f);
            moonsRgb = merge(keepMoons, spec, W, clipShare, cover, moonsGlow);
        }
        res.cover = cover;
        res.clip = clipShare;
        float[][] mainS = MoonStack.toSrgb(mainRgb, spec);
        res.r1 = mainS[0];
        res.g1 = mainS[1];
        res.b1 = mainS[2];
        if (moonsRgb != null) {
            // Jupiter's own burst where the moons' frames were mostly white (only there: it has a sixteenth of the
            // moons' light, so its noise would bury a moon close to Jupiter); where only some were, the moons' frames
            // that weren't. What's blended is each burst's light less its own glow; the glows go back blended ring
            // by ring (the bursts' glows differ a little, the moons' taking every frame and Jupiter's only its
            // sharpest, and the white region is a little longer along Jupiter's equator: blended pixel by pixel that
            // would leave a ghost along the moons' own line)
            float[] mask = new float[W * W];
            for (int k = 0; k < mask.length; k++) mask[k] = (float) Math.max(0, Math.min(1, (clipShare[k] - 0.05) / 0.35));
            double[] er = new double[W * W];
            for (int j = 0; j < W; j++) for (int i = 0; i < W; i++) er[j * W + i] = Math.hypot(i - W / 2.0, j - W / 2.0);
            double[][] mk = knots(mask, er);
            float[][] cam = new float[3][W * W];
            for (int k = 0; k < W * W; k++) {
                float m = mask[k], mr = (float) Math.max(0, Math.min(1, knotAt(mk, er[k])));
                for (int c = 0; c < 3; c++) {
                    cam[c][k] = (1 - m) * (moonsRgb[c][k] - moonsGlow[c][k]) + m * (mainRgb[c][k] - mainGlow[c][k])
                            + (1 - mr) * moonsGlow[c][k] + mr * mainGlow[c][k];
                }
            }
            float[][] cs = MoonStack.toSrgb(cam, spec);
            res.r = cs[0];
            res.g = cs[1];
            res.b = cs[2];
        } else {
            res.r = mainS[0];
            res.g = mainS[1];
            res.b = mainS[2];
        }
        pr.update("Looking for the moons", 0.75f);
        find(res, o, res.tilted ? 0 : Double.NaN);
        if (o.view != null) {
            name(res, o);
            // again with the moons' line where the naming put it (from the tilt, or from the moons the first search
            // found)
            if (res.tilted || res.found() >= 2) {
                find(res, o, res.turn);
                name(res, o);
            }
        }
        log.append(String.format(Locale.US, "jupiter: %d of %d frames, moons %d of %d (x%.1f), r %.2f px, turn %+.1f, scale %.3f, %s, %d hot pixels",
                res.used, res.total, res.moonsUsed, res.moonsTotal, moonsGain, res.rJ, res.turn, res.scale, res.tilted ? "tilt" : "no tilt", res.hot));
        for (int k = 0; k < 4; k++) log.append(String.format(Locale.US, "; %s %s", JupiterGeo.NAMES[k], statusWord(res.status[k])));
        for (Light l : res.lights) log.append("\n  ").append(l);
        res.log = log.toString();
        pr.update("Done", 1);
        return res;
    }

    public static String statusWord(int s) {
        switch (s) {
            case FOUND:
                return "found";
            case HIDDEN:
                return "behind Jupiter";
            case CROSSING:
                return "crossing Jupiter";
            case DARK:
                return "in Jupiter's shadow";
            case GLARE:
                return "lost in Jupiter's glare";
            case OUTSIDE:
                return "outside the photo";
            case BLENDED:
                return "beside another moon, too close to tell apart";
            default:
                return "not seen";
        }
    }

    // ------------------------------------------------------------------------------------------------
    // the frames

    static final class Prep {
        MoonStack.Frame f;
        float[] v;
        boolean[] sat, hot;
        double cx, cy, sharp, noise, sky, unit;
        boolean ok;
        /** Its glow as a mix of the sharpness groups' ({@link #fitGlow}). */
        double[] mix;
        /** Jupiter's middle from the green pixels alone: where the sensor's column plus row is odd (1) or even (0); -1: all pixels. */
        int greens = -1;
        double[] M;
    }

    /** Each frame balanced, Jupiter's middle found, its sharpness and noise. */
    static List<Prep> prepare(List<MoonStack.Frame> frames, SensorSpec spec, double unit) {
        List<Prep> out = new ArrayList<Prep>();
        for (MoonStack.Frame f : frames) {
            Prep p = new Prep();
            p.f = f;
            p.unit = unit;
            p.sat = new boolean[f.w * f.h];
            p.v = MoonStack.balanced(f, spec, p.sat);
            p.greens = greens(spec);
            locate(p);
            out.add(p);
        }
        return out;
    }

    /**
     * Jupiter's middle in a frame (its centre of brightness on the green pixels alone: Jupiter is yellow, so on all of
     * them its red pixels would pull the middle towards themselves, the same way in every frame, and its glow would
     * sit off the middle the search measures it from), its sharpness (its brightest over its whole light) and the
     * sky's noise.
     */
    static void locate(Prep p) {
        int w = p.f.w, h = p.f.h;
        float[] v = p.v;
        float[] edge = new float[2 * (w + h) * 3];
        int ne = 0;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                if (i >= 3 && j >= 3 && i < w - 3 && j < h - 3) continue;
                if (ne < edge.length) edge[ne++] = v[j * w + i];
            }
        }
        Arrays.sort(edge, 0, ne);
        float sky = edge[ne / 2];
        for (int k = 0; k < ne; k++) edge[k] = Math.abs(edge[k] - sky);
        Arrays.sort(edge, 0, ne);
        p.noise = 1.4826 * edge[ne / 2];
        p.sky = sky;
        // the brightest place (a 3 x 3 sum, so one hot pixel doesn't win)
        double best = -1;
        int bi = -1, bj = -1;
        for (int j = 6; j < h - 6; j++) {
            for (int i = 6; i < w - 6; i++) {
                double s = 0;
                for (int d = -1; d <= 1; d++) s += v[(j + d) * w + i - 1] + v[(j + d) * w + i] + v[(j + d) * w + i + 1];
                if (s > best) {
                    best = s;
                    bi = i;
                    bj = j;
                }
            }
        }
        if (bi < 0) return;
        int g0 = p.f.x0 + p.f.y0;
        double cx = bi, cy = bj, peak = 0, flux = 0;
        // its centre of brightness in a round window that fades out from it (a sigma of 1.5 px: Jupiter's disc and
        // the lens's blur), iterated until it sits on it: a moon three or four pixels out (Io a few of Jupiter's
        // radii away, sixteen times brighter in the moons' burst) would pull a plain one towards itself, and the
        // two bursts' middles apart
        for (int it = 0; it < 6; it++) {
            double ax = 0, ay = 0, aw = 0;
            peak = 0;
            flux = 0;
            int ix = (int) Math.round(cx), iy = (int) Math.round(cy);
            for (int dy = -5; dy <= 5; dy++) {
                for (int dx = -5; dx <= 5; dx++) {
                    int x = ix + dx, y = iy + dy;
                    if (x < 0 || y < 0 || x >= w || y >= h) continue;
                    if (p.greens >= 0 && ((x + y + g0) & 1) != p.greens) continue;
                    double rx = x - cx, ry = y - cy, r2 = rx * rx + ry * ry;
                    if (r2 > 25) continue;
                    double e = v[y * w + x] - sky;
                    if (e > peak) peak = e;
                    if (e <= 3 * p.noise) continue;
                    flux += e;
                    double wgt = e * Math.exp(-r2 / (2 * 1.5 * 1.5));
                    ax += wgt * x;
                    ay += wgt * y;
                    aw += wgt;
                }
            }
            if (aw <= 0) return;
            double nx = ax / aw, ny = ay / aw;
            // the window's own pull is towards its middle: step twice as far (the usual for a Gaussian window)
            nx = cx + 2 * (nx - cx);
            ny = cy + 2 * (ny - cy);
            boolean done = Math.abs(nx - cx) < 1e-3 && Math.abs(ny - cy) < 1e-3;
            cx = Math.max(ix - 2, Math.min(ix + 2, nx));
            cy = Math.max(iy - 2, Math.min(iy + 2, ny));
            if (done) break;
        }
        p.cx = cx;
        p.cy = cy;
        p.sharp = flux > 0 ? peak / flux : 0;
        p.ok = flux > (p.greens >= 0 ? 10 : 20) * p.noise;
    }

    /**
     * Where the colour filter's greens are, if on a checkerboard (RGGB, BGGR: column plus row odd, 1; GRBG, GBRG:
     * even, 0); -1 if not.
     */
    static int greens(SensorSpec spec) {
        int a = spec.cfa[0], b = spec.cfa[1], c = spec.cfa[2], d = spec.cfa[3];
        if (b == 1 && c == 1 && a != 1 && d != 1) return 1;
        if (a == 1 && d == 1 && b != 1 && c != 1) return 0;
        return -1;
    }

    /**
     * Hot pixels: lit far over their eight neighbours (a light through the lens never is: it spreads) in at least
     * three of every five frames that had them, at the same place on the sensor. Marked in every frame; their number.
     */
    static int hotPixels(List<Prep> all) {
        if (all.isEmpty()) return 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Prep p : all) {
            minX = Math.min(minX, p.f.x0);
            minY = Math.min(minY, p.f.y0);
            maxX = Math.max(maxX, p.f.x0 + p.f.w);
            maxY = Math.max(maxY, p.f.y0 + p.f.h);
        }
        int UW = maxX - minX, UH = maxY - minY;
        if ((long) UW * UH > 16_000_000L) return 0;
        int[] seen = new int[UW * UH], spike = new int[UW * UH];
        for (Prep p : all) {
            int w = p.f.w, h = p.f.h;
            float[] v = p.v;
            for (int j = 1; j < h - 1; j++) {
                for (int i = 1; i < w - 1; i++) {
                    int u = (p.f.y0 + j - minY) * UW + p.f.x0 + i - minX;
                    seen[u]++;
                    int k = j * w + i;
                    double e = v[k] - p.sky;
                    if (e < 6 * p.noise || p.sat[k]) continue;
                    double mx = Math.max(Math.max(Math.max(v[k - 1], v[k + 1]), Math.max(v[k - w], v[k + w])),
                            Math.max(Math.max(v[k - w - 1], v[k - w + 1]), Math.max(v[k + w - 1], v[k + w + 1]))) - p.sky;
                    if (e > 2.5 * Math.max(mx, 0)) spike[u]++;
                }
            }
        }
        int hot = 0;
        boolean[] isHot = new boolean[UW * UH];
        for (int u = 0; u < isHot.length; u++) {
            if (seen[u] >= 8 && spike[u] >= 0.6 * seen[u]) {
                isHot[u] = true;
                hot++;
            }
        }
        for (Prep p : all) {
            p.hot = new boolean[p.f.w * p.f.h];
            for (int j = 0; j < p.f.h; j++) {
                for (int i = 0; i < p.f.w; i++) {
                    int u = (p.f.y0 + j - minY) * UW + p.f.x0 + i - minX;
                    if (isHot[u]) p.hot[j * p.f.w + i] = true;
                }
            }
        }
        return hot;
    }

    /**
     * The 2 x 2 matrix from a frame's pixels (offsets from Jupiter's middle) to the result's (times {@link #S}): the
     * frame's up at position angle {@code upPa}, the result's at Jupiter's pole {@code pole}; no tilt known: unturned.
     */
    static double[] turnMatrix(double upPa, double pole, boolean tilted) {
        if (!tilted || Double.isNaN(upPa)) return new double[]{S, 0, 0, S};
        double a = Math.toRadians(upPa), p = Math.toRadians(pole);
        // each picture's up and right as (east, north)
        double uae = Math.sin(a), uan = Math.cos(a), rae = -Math.cos(a), ran = Math.sin(a);
        double upe = Math.sin(p), upn = Math.cos(p), rpe = -Math.cos(p), rpn = Math.sin(p);
        // frame (x right, y down) to the sky: x * right_a - y * up_a; the sky to the result: (sky . right_p, -(sky . up_p))
        double m11 = rae * rpe + ran * rpn, m12 = -(uae * rpe + uan * rpn);
        double m21 = -(rae * upe + ran * upn), m22 = uae * upe + uan * upn;
        return new double[]{S * m11, S * m12, S * m21, S * m22};
    }

    /**
     * The frames merged on the result's grid, per colour (balanced camera colour, in Jupiter's burst's units): every
     * pixel spread over the four result pixels round where it lands; then again leaving out any far from the first
     * round's mean there; holes filled from round about. {@code clipShare}, if given, gets each result pixel's share
     * of white-level pixels (left out of the merge).
     * <p>
     * In the second round each frame's own glow comes off its pixels before they're spread, and the frames' mean glow
     * goes back at each result pixel's own middle. Jupiter's light falls by half a pixel in the result close in, the
     * hand blurs each frame differently (one frame's glow at a place twice another's), and each result pixel gets the
     * pixels of only some of the frames, landed a little unevenly round its middle: left in, that reads as a speckle
     * over the glow as bright as a moon. A frame's own glow: a mix of the glows of the sharpest third of the frames,
     * the middle third and the most blurred third (each merged apart in the first round, the same all round), fitted
     * to the frame's own pixels. {@code glowOut}, if given, gets the mean glow put back.
     */
    static float[][] merge(List<Prep> frames, SensorSpec spec, int W, double[] clipShare, double[] cover, float[][] glowOut) {
        int n = W * W;
        double[][] sw = new double[3][n], sv = new double[3][n], sv2 = new double[3][n];
        double[] clipW = clipShare != null ? new double[n] : null;
        double floor = 0;
        for (Prep p : frames) floor += p.noise * p.unit;
        floor /= Math.max(1, frames.size());
        // the groups by sharpness
        int groups = Math.max(1, Math.min(3, frames.size() / 8));
        double[] sh = new double[frames.size()];
        for (int f = 0; f < sh.length; f++) sh[f] = frames.get(f).sharp;
        double[] ss = sh.clone();
        Arrays.sort(ss);
        double[][][] gw = new double[groups][3][n], gv = new double[groups][3][n];
        for (int f = 0; f < frames.size(); f++) {
            Prep p = frames.get(f);
            splat(p, spec, W, sw, sv, sv2, clipW, null, null, 0, null, null);
            int g = 0;
            while (g < groups - 1 && sh[f] > ss[Math.min(ss.length - 1, (g + 1) * ss.length / groups)]) g++;
            splat(p, spec, W, gw[g], gv[g], null, null, null, null, 0, null, null);
        }
        // the first round's mean and spread at each place
        float[][] mean = new float[3][n], sd = new float[3][n];
        for (int c = 0; c < 3; c++) {
            for (int k = 0; k < n; k++) {
                if (sw[c][k] < 1e-9) continue;
                double m = sv[c][k] / sw[c][k], var = sv2[c][k] / sw[c][k] - m * m;
                mean[c][k] = (float) m;
                sd[c][k] = (float) (sw[c][k] >= 4 ? Math.sqrt(Math.max(var, 0) + floor * floor) : Double.MAX_VALUE / 4);
            }
        }
        // each group's glow per colour, out from Jupiter's middle
        float[][][] tab = new float[groups][3][];
        for (int g = 0; g < groups; g++) {
            for (int c = 0; c < 3; c++) {
                float[] m = new float[n];
                for (int k = 0; k < n; k++) m[k] = gw[g][c][k] > 1e-9 ? (float) (gv[g][c][k] / gw[g][c][k]) : 0f;
                tab[g][c] = glowTable(m, gw[g][c], W);
            }
        }
        gw = null;
        gv = null;
        // each frame's mix, and the mean mix
        double[] avg = new double[groups];
        for (Prep p : frames) {
            p.mix = fitGlow(p, spec, tab);
            for (int g = 0; g < groups; g++) avg[g] += p.mix[g] / frames.size();
        }
        double[][] tw = new double[3][n], tv = new double[3][n];
        for (Prep p : frames) splat(p, spec, W, tw, tv, null, null, mean, sd, 3.5, tab, avg);
        float[][] out = new float[3][];
        double half = W / 2.0;
        for (int c = 0; c < 3; c++) {
            out[c] = fill(tv[c], tw[c], W, 0.6);
            for (int j = 0; j < W; j++) {
                for (int i = 0; i < W; i++) {
                    float gl = (float) mixAt(tab, c, avg, Math.hypot(i - half, j - half));
                    out[c][j * W + i] += gl;
                    if (glowOut != null) glowOut[c][j * W + i] = gl;
                }
            }
        }
        if (clipShare != null) {
            for (int k = 0; k < n; k++) clipShare[k] = clipW[k] / Math.max(1e-9, clipW[k] + sw[1][k]);
        }
        if (cover != null) for (int k = 0; k < n; k++) cover[k] = Math.max(cover[k], tw[1][k] / Math.max(1, frames.size()));
        return out;
    }

    /** A mix of the groups' glows at distance r (result px), colour c. */
    static double mixAt(float[][][] tab, int c, double[] mix, double r) {
        double v = 0;
        for (int g = 0; g < mix.length; g++) v += mix[g] * tableAt(tab[g][c], r);
        return v;
    }

    /**
     * A frame's glow as a mix of the groups': somewhere along the line from the sharpest group's glow through the
     * middle's to the most blurred's (a little past either end allowed), times a brightness; where and how bright
     * fitted to its own pixels from 1.5 to 8 sensor pixels out (none white or hot, each weighted as its light's noise
     * goes; once more without any far off the first fit). Along a line, not any mix: the groups' glows are much
     * alike, and a free mix of them would chase the frame's noise with huge sums of opposite sign.
     */
    static double[] fitGlow(Prep p, SensorSpec spec, float[][][] tab) {
        int G = tab.length, w = p.f.w, h = p.f.h;
        double noise = p.noise * p.unit;
        // the frame's pixels in reach, and each group's glow at each
        int x0 = Math.max(0, (int) Math.floor(p.cx - 8)), x1 = Math.min(w - 1, (int) Math.ceil(p.cx + 8));
        int y0 = Math.max(0, (int) Math.floor(p.cy - 8)), y1 = Math.min(h - 1, (int) Math.ceil(p.cy + 8));
        int cap = (x1 - x0 + 1) * (y1 - y0 + 1);
        double[] v = new double[cap], wt = new double[cap];
        double[][] t = new double[G][cap];
        int n = 0;
        for (int j = y0; j <= y1; j++) {
            for (int i = x0; i <= x1; i++) {
                double rho = Math.hypot(i - p.cx, j - p.cy);
                if (rho < 1.5 || rho > 8) continue;
                int k = j * w + i;
                if (p.sat[k] || (p.hot != null && p.hot[k])) continue;
                int c = spec.cfa[(((j + p.f.y0) & 1) << 1) | ((i + p.f.x0) & 1)];
                v[n] = p.v[k] * p.unit;
                for (int g = 0; g < G; g++) t[g][n] = tableAt(tab[g][c], S * rho);
                wt[n] = 1 / (Math.max(0, t[G / 2][n]) + 2 * noise + 1e-12);
                n++;
            }
        }
        double[] mix = new double[G];
        Arrays.fill(mix, 1.0 / G);
        if (n < 8 || G == 1) {
            if (n >= 8) {
                double sxy = 0, sxx = 0;
                for (int q = 0; q < n; q++) {
                    sxy += wt[q] * t[0][q] * v[q];
                    sxx += wt[q] * t[0][q] * t[0][q];
                }
                if (sxx > 0) mix[0] = sxy / sxx;
            }
            return mix;
        }
        boolean[] use = new boolean[n];
        Arrays.fill(use, true);
        double bestT = (G - 1) / 2.0, bestA = 1;
        for (int round = 0; round < 2; round++) {
            double bestChi = Double.MAX_VALUE;
            for (double u = -0.3; u <= G - 1 + 0.3 + 1e-9; u += 0.05) {
                double[] wg = along(u, G);
                double sxy = 0, sxx = 0;
                for (int q = 0; q < n; q++) {
                    if (!use[q]) continue;
                    double m = 0;
                    for (int g = 0; g < G; g++) m += wg[g] * t[g][q];
                    sxy += wt[q] * m * v[q];
                    sxx += wt[q] * m * m;
                }
                if (sxx <= 0) continue;
                double amp = sxy / sxx, chi = 0;
                for (int q = 0; q < n; q++) {
                    if (!use[q]) continue;
                    double m = 0;
                    for (int g = 0; g < G; g++) m += wg[g] * t[g][q];
                    double d = v[q] - amp * m;
                    chi += wt[q] * d * d;
                }
                if (chi < bestChi) {
                    bestChi = chi;
                    bestT = u;
                    bestA = amp;
                }
            }
            if (round == 0) {
                // anything far off the fit (a moon, a cosmic ray) out, and once more
                double[] wg = along(bestT, G);
                double[] d = new double[n];
                int nd = 0;
                for (int q = 0; q < n; q++) {
                    double m = 0;
                    for (int g = 0; g < G; g++) m += wg[g] * t[g][q];
                    d[q] = Math.abs(v[q] - bestA * m) * Math.sqrt(wt[q]);
                }
                double[] ds = Arrays.copyOf(d, n);
                Arrays.sort(ds);
                double cut = 4 * 1.4826 * ds[n / 2] + 1e-12;
                for (int q = 0; q < n; q++) if (d[q] > cut) use[q] = false;
                for (int q = 0; q < n; q++) if (use[q]) nd++;
                if (nd < 8) break;
            }
        }
        double[] wg = along(bestT, G);
        for (int g = 0; g < G; g++) mix[g] = bestA * wg[g];
        return mix;
    }

    /** The weights of the groups' glows at {@code u} along the line from the first (0) to the last (G - 1). */
    static double[] along(double u, int G) {
        double[] wg = new double[G];
        int seg = (int) Math.floor(Math.max(0, Math.min(G - 2, u)));
        double f = u - seg;
        wg[seg] = 1 - f;
        wg[seg + 1] = f;
        return wg;
    }

    /** Gaussian elimination on an augmented G x (G+1) matrix; null if singular. */
    static double[] solve(double[][] A) {
        int G = A.length;
        for (int col = 0; col < G; col++) {
            int piv = col;
            for (int i = col + 1; i < G; i++) if (Math.abs(A[i][col]) > Math.abs(A[piv][col])) piv = i;
            if (Math.abs(A[piv][col]) < 1e-18) return null;
            double[] tmp = A[col];
            A[col] = A[piv];
            A[piv] = tmp;
            for (int i = 0; i < G; i++) {
                if (i == col) continue;
                double f = A[i][col] / A[col][col];
                for (int j = col; j <= G; j++) A[i][j] -= f * A[col][j];
            }
        }
        double[] x = new double[G];
        for (int i = 0; i < G; i++) x[i] = A[i][G] / A[i][i];
        return x;
    }

    /** A glow table's step (result px). */
    static final double TAB_STEP = 0.05;

    /**
     * The glow, the same all round, out from the result's middle in steps of {@link #TAB_STEP}, from a merged image
     * where it was covered ({@code wt} over a tenth of its median).
     */
    static float[] glowTable(float[] img, double[] wt, int W) {
        int n = W * W;
        double[] ws = wt.clone();
        Arrays.sort(ws);
        double need = 0.1 * ws[n / 2];
        int m = 0;
        for (int k = 0; k < n; k++) if (wt[k] > need && wt[k] > 1e-9) m++;
        float[] v = new float[m];
        double[] er = new double[m];
        double half = W / 2.0;
        m = 0;
        for (int k = 0; k < n; k++) {
            if (!(wt[k] > need && wt[k] > 1e-9)) continue;
            v[m] = img[k];
            er[m] = Math.hypot(k % W - half, k / W - half);
            m++;
        }
        double[][] knots = knots(v, er);
        double rMax = W * 0.75;
        float[] tab = new float[(int) Math.ceil(rMax / TAB_STEP) + 2];
        for (int q = 0; q < tab.length; q++) tab[q] = (float) knotAt(knots, q * TAB_STEP);
        return tab;
    }

    static double tableAt(float[] tab, double r) {
        double t = r / TAB_STEP;
        int q = (int) t;
        if (q >= tab.length - 1) return tab[tab.length - 1];
        double f = t - q;
        return tab[q] * (1 - f) + tab[q + 1] * f;
    }

    /**
     * One frame's pixels onto the grid (with {@code mean}: only those within {@code k} spreads of it; with
     * {@code tab}: less the frame's own glow where each lands, the frames' mean glow {@code avg} at the nearest pixel
     * put back for the check against the mean).
     */
    static void splat(Prep p, SensorSpec spec, int W, double[][] sw, double[][] sv, double[][] sv2, double[] clipW, float[][] mean, float[][] sd,
                      double k, float[][][] tab, double[] avg) {
        MoonStack.Frame f = p.f;
        double[] M = p.M;
        double half = W / 2.0;
        for (int j = 0; j < f.h; j++) {
            double dy = j - p.cy;
            for (int i = 0; i < f.w; i++) {
                double dx = i - p.cx;
                double ox = half + M[0] * dx + M[1] * dy, oy = half + M[2] * dx + M[3] * dy;
                int x0 = (int) Math.floor(ox), y0 = (int) Math.floor(oy);
                if (x0 < 0 || y0 < 0 || x0 >= W - 1 || y0 >= W - 1) continue;
                double fx = ox - x0, fy = oy - y0;
                int kk = j * f.w + i;
                if (p.hot != null && p.hot[kk]) continue;
                int c = spec.cfa[(((j + f.y0) & 1) << 1) | ((i + f.x0) & 1)];
                if (p.sat[kk]) {
                    if (clipW != null) {
                        clipW[y0 * W + x0] += (1 - fx) * (1 - fy);
                        clipW[y0 * W + x0 + 1] += fx * (1 - fy);
                        clipW[(y0 + 1) * W + x0] += (1 - fx) * fy;
                        clipW[(y0 + 1) * W + x0 + 1] += fx * fy;
                    }
                    if (clipW != null) continue;
                }
                double v = p.v[kk] * p.unit;
                int near = (int) Math.round(oy) * W + (int) Math.round(ox);
                if (tab != null) {
                    // the frame's own glow where it landed (its distance from the middle on the sensor, times S)
                    v -= mixAt(tab, c, p.mix, S * Math.hypot(dx, dy));
                    if (mean != null) {
                        double gn = mixAt(tab, c, avg, Math.hypot(Math.round(ox) - half, Math.round(oy) - half));
                        if (Math.abs(v + gn - mean[c][near]) > k * sd[c][near]) continue;
                    }
                } else if (mean != null && Math.abs(v - mean[c][near]) > k * sd[c][near]) {
                    continue;
                }
                add(sw[c], sv[c], sv2 != null ? sv2[c] : null, y0 * W + x0, (1 - fx) * (1 - fy), v);
                add(sw[c], sv[c], sv2 != null ? sv2[c] : null, y0 * W + x0 + 1, fx * (1 - fy), v);
                add(sw[c], sv[c], sv2 != null ? sv2[c] : null, (y0 + 1) * W + x0, (1 - fx) * fy, v);
                add(sw[c], sv[c], sv2 != null ? sv2[c] : null, (y0 + 1) * W + x0 + 1, fx * fy, v);
            }
        }
    }

    private static void add(double[] sw, double[] sv, double[] sv2, int k, double w, double v) {
        if (w <= 0) return;
        sw[k] += w;
        sv[k] += w * v;
        if (sv2 != null) sv2[k] += w * v * v;
    }

    /** The weighted sums smoothed a little and divided: the mean everywhere, holes filled from round about. */
    static float[] fill(double[] sv, double[] sw, int W, double sigma) {
        double[] a = blur(sv, W, sigma), b = blur(sw, W, sigma);
        float[] out = new float[W * W];
        for (int k = 0; k < out.length; k++) out[k] = b[k] > 1e-6 ? (float) (a[k] / b[k]) : 0;
        return out;
    }

    static double[] blur(double[] img, int W, double sigma) {
        int r = Math.max(1, (int) Math.ceil(3 * sigma));
        double[] k = new double[2 * r + 1];
        double s = 0;
        for (int i = -r; i <= r; i++) s += k[i + r] = Math.exp(-i * i / (2 * sigma * sigma));
        for (int i = 0; i < k.length; i++) k[i] /= s;
        double[] tmp = new double[W * W], out = new double[W * W];
        for (int j = 0; j < W; j++) {
            for (int i = 0; i < W; i++) {
                double v = 0;
                for (int d = -r; d <= r; d++) {
                    int x = Math.max(0, Math.min(W - 1, i + d));
                    v += k[d + r] * img[j * W + x];
                }
                tmp[j * W + i] = v;
            }
        }
        for (int j = 0; j < W; j++) {
            for (int i = 0; i < W; i++) {
                double v = 0;
                for (int d = -r; d <= r; d++) {
                    int y = Math.max(0, Math.min(W - 1, j + d));
                    v += k[d + r] * tmp[y * W + i];
                }
                out[j * W + i] = v;
            }
        }
        return out;
    }

    /** A mask grown: 1 within {@code in} px of any set pixel, falling to 0 at {@code out} px. */
    static float[] grow(float[] m, int W, double in, double out) {
        float[] o = new float[m.length];
        int R = (int) Math.ceil(out);
        for (int j = 0; j < W; j++) {
            for (int i = 0; i < W; i++) {
                double best = Double.MAX_VALUE;
                for (int dj = -R; dj <= R; dj++) {
                    int y = j + dj;
                    if (y < 0 || y >= W) continue;
                    for (int di = -R; di <= R; di++) {
                        int x = i + di;
                        if (x < 0 || x >= W || m[y * W + x] <= 0) continue;
                        best = Math.min(best, Math.hypot(di, dj));
                    }
                }
                o[j * W + i] = best <= in ? 1f : best >= out ? 0f : (float) ((out - best) / (out - in));
            }
        }
        return o;
    }

    // ------------------------------------------------------------------------------------------------
    // the lights beside Jupiter

    /**
     * Jupiter's glow taken off and the small lights left found. The glow: the same all round ({@link #glow}); then,
     * the moons' line known (at {@code turnDeg}; NaN: not), what isn't the same all round ({@link #shape}, then
     * {@link #harmonics}) fitted away from the line and taken off too.
     */
    static void find(Result res, Options o, double turnDeg) {
        int W = res.w;
        float[] Y = new float[W * W];
        for (int k = 0; k < Y.length; k++) Y[k] = 0.2126f * res.r[k] + 0.7152f * res.g[k] + 0.0722f * res.b[k];
        double[] er = radii(res);
        double[][] kn = knots(Y, er);
        float[] R = new float[W * W];
        for (int k = 0; k < W * W; k++) R[k] = (float) (Y[k] - knotAt(kn, er[k]));
        res.sky = kn[1][kn[1].length - 1];
        res.peak = 0;
        for (int k = 0; k < W * W; k++) if (er[k] < res.rJ && Y[k] > res.peak) res.peak = Y[k];
        if (!Double.isNaN(turnDeg)) {
            R = shape(res, o, R, er, kn, turnDeg);
            R = harmonics(res, o, R, er, turnDeg);
        }
        search(res, o, R, er);
    }

    /**
     * A degree-5 curve through the logarithm of the glow's rings between distances a and b, in u = (r - mid)/half:
     * its six coefficients, then mid and half.
     */
    static double[] logPoly(double[][] kn, double a, double b) {
        int D = 6;
        double mid = (a + b) / 2, half = Math.max(1e-6, (b - a) / 2);
        double[][] M = new double[D][D + 1];
        double[] kr = kn[0], kv = kn[1];
        for (int i = 0; i < kr.length; i++) {
            if (kr[i] < a || kr[i] > b || kv[i] <= 0) continue;
            double u = (kr[i] - mid) / half, y = Math.log(kv[i]);
            double[] f = new double[D];
            for (int j = 0; j < D; j++) f[j] = Math.pow(u, j);
            for (int j = 0; j < D; j++) {
                for (int q = 0; q < D; q++) M[j][q] += f[j] * f[q];
                M[j][D] += f[j] * y;
            }
        }
        double[] c = solve(M);
        double[] out = new double[D + 2];
        if (c != null) System.arraycopy(c, 0, out, 0, D);
        out[D] = mid;
        out[D + 1] = half;
        return out;
    }

    /** The moons' band either side of their line: its half width at distance r (result px). */
    static double band(Result res, Options o, double r) {
        double spread = 0;
        if (o.view != null) {
            for (JupiterGeo.Moon m : o.view.moons) if (m.showing()) spread = Math.max(spread, Math.abs(m.y) * res.rJ);
        }
        return 2.5 * o.psfSigma * S + spread + r * Math.toRadians(res.tilted ? 3 : 1.5);
    }

    /**
     * What's left of the glow that isn't the same all round, as small shifts and stretches of a round glow make it:
     * its middle a little off (the glow's slope P' times the cosine and sine of the angle) and the glow a little
     * longer one way than the other (Jupiter's flattened disc, the lens, the hand: P'' - P'/r times the cosine and
     * sine of twice the angle). Four numbers for the whole picture, fitted to its pixels away from the moons' line
     * from Jupiter's edge out to eight of its radii, each weighted by its ring's spread; taken off everywhere. Close
     * in, where a ring's pixels away from the line are too few to say what it does, this still knows.
     */
    static float[] shape(Result res, Options o, float[] R0, double[] er, double[][] kn, double turnDeg) {
        int W = res.w;
        double cx = res.cx, cy = res.cy, rJ = res.rJ;
        double a = Math.toRadians(turnDeg);
        double lx = Math.cos(a), ly = -Math.sin(a);
        double rIn = 2.0 * rJ, rOut = Math.min(W / 2.0 - 2, 8 * rJ);
        // each 1 px ring's spread (all its pixels: the moons are few)
        int nr = (int) Math.ceil(rOut) + 1;
        double[] spread = new double[nr];
        int[] cnt = new int[nr];
        for (int k = 0; k < W * W; k++) if (er[k] < nr) cnt[(int) er[k]]++;
        float[][] v = new float[nr][];
        for (int q = 0; q < nr; q++) v[q] = new float[cnt[q]];
        Arrays.fill(cnt, 0);
        for (int k = 0; k < W * W; k++) if (er[k] < nr) v[(int) er[k]][cnt[(int) er[k]]++] = R0[k];
        for (int q = 0; q < nr; q++) {
            int n = cnt[q];
            if (n < 4) {
                spread[q] = Double.NaN;
                continue;
            }
            Arrays.sort(v[q], 0, n);
            float med = v[q][n / 2];
            for (int i = 0; i < n; i++) v[q][i] = Math.abs(v[q][i] - med);
            Arrays.sort(v[q], 0, n);
            spread[q] = Math.max(1e-12, 1.4826 * v[q][n / 2]);
        }
        // the glow's slope and bend from a smooth curve through its logarithm (the rings' medians, differenced, are
        // far too rough: their bend jumps from ring to ring)
        double[] lp = logPoly(kn, 0.75 * rIn, 1.25 * rOut);
        double u0 = lp[lp.length - 2], us = lp[lp.length - 1];
        double[][] A = new double[4][5];
        float[][] basis = new float[4][W * W];
        for (int k = 0; k < W * W; k++) {
            double r = er[k];
            if (r < 0.5 * rJ || r > rOut + 4) continue;
            double p0 = knotAt(kn, r);
            double u = (r - u0) / us, L1 = 0, L2 = 0;
            for (int i = 1; i < lp.length - 2; i++) L1 += i * lp[i] * Math.pow(u, i - 1) / us;
            for (int i = 2; i < lp.length - 2; i++) L2 += i * (i - 1) * lp[i] * Math.pow(u, i - 2) / (us * us);
            double d1 = p0 * L1, d2 = p0 * (L2 + L1 * L1);
            double dx = k % W - cx, dy = k / W - cy;
            double c = dx / r, sn = dy / r, c2 = c * c - sn * sn, s2 = 2 * c * sn;
            double q2 = d2 - d1 / r;
            basis[0][k] = (float) (d1 * c);
            basis[1][k] = (float) (d1 * sn);
            basis[2][k] = (float) (q2 * c2);
            basis[3][k] = (float) (q2 * s2);
            if (r < rIn || r > rOut) continue;
            double perp = Math.abs(-ly * dx + lx * dy);
            if (perp < band(res, o, r)) continue;
            double sd = spread[(int) r];
            if (Double.isNaN(sd)) continue;
            double wt = 1 / (sd * sd);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) A[i][j] += wt * basis[i][k] * basis[j][k];
                A[i][4] += wt * basis[i][k] * R0[k];
            }
        }
        double[] c = solve(A);
        float[] out = R0.clone();
        if (c == null) return out;
        // no more than can be: the frames were lined up on Jupiter's middle (a shift of more than half a pixel
        // isn't one), and a stretch past a pixel's length isn't the lens's or the hand's (noise far out, where the
        // basis is small, would otherwise put huge values close in)
        double sh = Math.hypot(c[0], c[1]), st = Math.hypot(c[2], c[3]);
        if (sh > 0.5) {
            c[0] *= 0.5 / sh;
            c[1] *= 0.5 / sh;
        }
        if (st > 0.5) {
            c[2] *= 0.5 / st;
            c[3] *= 0.5 / st;
        }
        for (int k = 0; k < W * W; k++) {
            if (er[k] < rJ) continue;
            double m = 0;
            for (int i = 0; i < 4; i++) m += c[i] * basis[i][k];
            out[k] = (float) (R0[k] - m);
        }
        res.shapeFit = c;
        return out;
    }

    static double[] radii(Result res) {
        int W = res.w;
        double[] er = new double[W * W];
        for (int j = 0; j < W; j++) for (int i = 0; i < W; i++) er[j * W + i] = Math.hypot(i - res.cx, j - res.cy);
        return er;
    }

    /**
     * The small lights in R (Jupiter's glow taken off): R smoothed by the lens's blur (leaving out Jupiter's own
     * middle, where what's left of its light is far over any moon's and would spill out), against its noise ring by
     * ring.
     */
    static void search(Result res, Options o, float[] R, double[] er) {
        int W = res.w;
        double rJ = res.rJ;
        double rMax = W * 0.75;
        double rMin = GLARE_RJ * rJ;
        double sig = res.psf > 0 ? res.psf : o.psfSigma * S;
        float[] wt = new float[W * W], Rw = new float[W * W];
        for (int k = 0; k < W * W; k++) {
            wt[k] = er[k] < rMin - 1.5 ? 0f : 1f;
            Rw[k] = R[k] * wt[k];
        }
        float[] F = MoonStack.blur(Rw, W, W, sig), Fw = MoonStack.blur(wt, W, W, sig);
        for (int k = 0; k < W * W; k++) F[k] = Fw[k] > 0.3f ? F[k] / Fw[k] : 0f;
        int rings = (int) Math.ceil(rMax / 3) + 1;
        double[] noiseF = ringNoise(F, er, W, rings, 3);
        double[] noiseR = ringNoise(R, er, W, rings, 3);
        res.resid = R;
        res.filt = F;
        res.ringNoise = noiseF;
        res.noise = noiseR[Math.min(rings - 1, (int) (W * 0.4 / 3))];
        res.lights.clear();
        int edge = (int) Math.ceil(3 * sig) + 1;
        // only where the frames covered well (the crops' edges wander with the hand): the coverage smoothed (pixel to
        // pixel it goes up and down with how the sensor's greens fall on the finer grid) against its middle's
        double coverNeed = 0;
        double[] coverS = null;
        if (res.cover != null) {
            coverS = blur(res.cover, W, 2.0);
            float[] c = new float[W * W];
            int nc = 0;
            for (int k = 0; k < W * W; k++) if (er[k] < W * 0.3) c[nc++] = (float) coverS[k];
            Arrays.sort(c, 0, nc);
            coverNeed = 0.5 * c[nc / 2];
        }
        for (int j = edge; j < W - edge; j++) {
            for (int i = edge; i < W - edge; i++) {
                int k = j * W + i;
                if (er[k] < rMin) continue;
                if (coverS != null && minCover(coverS, W, i, j, 3) < coverNeed) continue;
                double nf = noiseF[Math.min(rings - 1, (int) (er[k] / 3))];
                double need = (er[k] < 4 * rJ ? 1.6 : 1) * o.sigmas * nf;
                if (F[k] <= need) continue;
                // a peak among its eight neighbours (a faint star on a bright moon's skirt is one, if not among
                // the 24 round it); two peaks this close are one light, below
                boolean top = true;
                for (int dj = -1; dj <= 1 && top; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        if ((di != 0 || dj != 0) && F[k + dj * W + di] > F[k]) {
                            top = false;
                            break;
                        }
                    }
                }
                if (!top) continue;
                // its centre of brightness on R, and its light
                double ax = 0, ay = 0, aw = 0, flux = 0;
                for (int dj = -3; dj <= 3; dj++) {
                    for (int di = -3; di <= 3; di++) {
                        int y = j + dj, x = i + di;
                        if (x < 0 || y < 0 || x >= W || y >= W || di * di + dj * dj > 9) continue;
                        double e = R[y * W + x];
                        flux += e;
                        if (e <= 0) continue;
                        ax += e * x;
                        ay += e * y;
                        aw += e;
                    }
                }
                if (aw <= 0) continue;
                Light l = new Light();
                double[] pk = peakAt(F, W, i, j);
                l.x = pk[0];
                l.y = pk[1];
                l.flux = flux;
                l.snr = F[k] / Math.max(1e-12, nf);
                res.lights.add(l);
            }
        }
        // peaks closer than the blur's width: one light, the brighter
        for (int q = res.lights.size() - 1; q >= 0; q--) {
            Light l = res.lights.get(q);
            for (Light m : res.lights) {
                if (m != l && m.snr >= l.snr && Math.hypot(m.x - l.x, m.y - l.y) < 1.2 * sig) {
                    res.lights.remove(q);
                    break;
                }
            }
        }
        res.noiseRings = noiseR;
        // the lens's blur as the brightest lights show it (once), then every light's place fitted
        if (res.psf <= 0) {
            res.psf = measurePsf(res.lights, R, W, er, rMin - 1.5, sig);
            if (Math.abs(res.psf - sig) > 0.15 * sig) {
                // the search again, its smoothing matched to the lights' own blur
                search(res, o, R, er);
                return;
            }
        }
        fitLights(res.lights, R, W, er, noiseR, 3, res.psf, rMin - 1.5);
    }

    /** The smoothed picture's peak at pixel (i, j), to a fraction of a pixel (a parabola each way). */
    static double[] peakAt(float[] F, int W, int i, int j) {
        int k = j * W + i;
        double fl = F[k - 1], fc = F[k], fr = F[k + 1], fu = F[k - W], fd = F[k + W];
        double dx = fl - 2 * fc + fr, dy = fu - 2 * fc + fd;
        double ox = dx < 0 ? Math.max(-0.5, Math.min(0.5, 0.5 * (fl - fr) / dx)) : 0;
        double oy = dy < 0 ? Math.max(-0.5, Math.min(0.5, 0.5 * (fu - fd) / dy)) : 0;
        return new double[]{i + ox, j + oy};
    }

    /** A round blur of width s at (dx, dy) from its middle, 1 at its middle. */
    static double gauss(double dx, double dy, double s) {
        return Math.exp(-(dx * dx + dy * dy) / (2 * s * s));
    }

    /**
     * The lens's blur in the result as the lights show it: for each bright light (over 15 times the noise, no other
     * within four widths) the width of a round blur on a tilted plane that fits it best; their median ({@code dflt}
     * if none).
     */
    static double measurePsf(List<Light> lights, float[] R, int W, double[] er, double rMask, double dflt) {
        List<Double> ws = new ArrayList<Double>();
        for (Light l : lights) {
            if (l.snr < 15) continue;
            boolean alone = true;
            for (Light m : lights) if (m != l && Math.hypot(m.x - l.x, m.y - l.y) < 4 * dflt + 2) alone = false;
            if (!alone) continue;
            double best = Double.MAX_VALUE, bs = Double.NaN;
            double[] chi = new double[40];
            int nb = 0;
            for (double s = 0.9; s <= 4.01; s += 0.1, nb++) {
                double x0 = l.x, y0 = l.y;
                double[] f = fitOne(R, W, er, rMask, x0, y0, s, null, null, -1, null, 1);
                chi[nb] = f == null ? Double.MAX_VALUE : f[6];
                if (chi[nb] < best) {
                    best = chi[nb];
                    bs = s;
                }
            }
            if (!Double.isNaN(bs) && bs > 0.95 && bs < 3.95) ws.add(bs);
        }
        if (ws.isEmpty()) return dflt;
        java.util.Collections.sort(ws);
        return ws.get(ws.size() / 2);
    }

    /**
     * One light fitted: a round blur (width s) of height a at (x0, y0) moved to where it fits best, on a plane, over R
     * within 2.5 widths (none nearer Jupiter's middle than rMask), the other lights' blurs ({@code others}, heights
     * {@code amps}; {@code self}: its own index, skipped) taken off first. Returns {a, x, y, b0, bx, by, chi2/n}, or
     * null if it can't be fitted.
     */
    static double[] fitOne(float[] R, int W, double[] er, double rMask, double x0, double y0, double s, List<Light> others, double[] amps, int self,
                           double[] noiseR, double ringW) {
        int rad = (int) Math.ceil(2.5 * s);
        double a = 0, b0 = 0, bx = 0, by = 0, chi = 0;
        double sx0 = x0, sy0 = y0;
        for (int gn = 0; gn < 6; gn++) {
            double[][] M = new double[6][7];
            int ic = (int) Math.round(x0), jc = (int) Math.round(y0), n = 0;
            for (int dj = -rad; dj <= rad; dj++) {
                for (int di = -rad; di <= rad; di++) {
                    int x = ic + di, y = jc + dj;
                    if (x < 0 || y < 0 || x >= W || y >= W) continue;
                    double dx = x - x0, dy = y - y0;
                    if (dx * dx + dy * dy > 6.25 * s * s) continue;
                    int k = y * W + x;
                    if (er[k] < rMask) continue;
                    double v = R[k];
                    if (others != null) {
                        for (int q = 0; q < others.size(); q++) {
                            if (q == self || amps[q] <= 0) continue;
                            Light o = others.get(q);
                            double ox = x - o.x, oy = y - o.y;
                            if (ox * ox + oy * oy < 16 * s * s) v -= amps[q] * gauss(ox, oy, s);
                        }
                    }
                    double g = gauss(dx, dy, s);
                    double[] f = {g, g * dx / (s * s), g * dy / (s * s), 1, dx, dy};
                    // each pixel as much as its noise allows (close to Jupiter its glow's leftovers are many times the sky's)
                    double wt = 1;
                    if (noiseR != null) {
                        double nz = noiseR[Math.min(noiseR.length - 1, (int) (er[k] / ringW))];
                        wt = 1 / (nz * nz);
                    }
                    for (int i = 0; i < 6; i++) {
                        for (int j = 0; j < 6; j++) M[i][j] += wt * f[i] * f[j];
                        M[i][6] += wt * f[i] * v;
                    }
                    n++;
                }
            }
            if (n < 12) return null;
            double[] sol = solve(M);
            if (sol == null || sol[0] <= 0) return null;
            a = sol[0];
            double stx = Math.max(-0.5, Math.min(0.5, sol[1] / a)), sty = Math.max(-0.5, Math.min(0.5, sol[2] / a));
            b0 = sol[3];
            bx = sol[4];
            by = sol[5];
            x0 += stx;
            y0 += sty;
            if (Math.hypot(x0 - sx0, y0 - sy0) > 2.5) return null;
            if (Math.abs(stx) < 0.003 && Math.abs(sty) < 0.003) break;
        }
        // the fit's misfit, for the blur's width
        int ic = (int) Math.round(x0), jc = (int) Math.round(y0), n = 0;
        for (int dj = -rad; dj <= rad; dj++) {
            for (int di = -rad; di <= rad; di++) {
                int x = ic + di, y = jc + dj;
                if (x < 0 || y < 0 || x >= W || y >= W) continue;
                double dx = x - x0, dy = y - y0;
                if (dx * dx + dy * dy > 6.25 * 2.2 * 2.2) continue;
                int k = y * W + x;
                if (er[k] < rMask) continue;
                double m = a * gauss(dx, dy, s) + b0 + bx * dx + by * dy;
                chi += (R[k] - m) * (R[k] - m);
                n++;
            }
        }
        return new double[]{a, x0, y0, b0, bx, by, n > 0 ? chi / n : Double.MAX_VALUE};
    }

    /**
     * Every light's place and light: a round blur the lens's width on a tilted plane, fitted to R round it, the other
     * lights near it taken off first, four times round (two moons three of Jupiter's radii apart overlap: each alone
     * would be pulled towards the other).
     */
    static void fitLights(List<Light> lights, float[] R, int W, double[] er, double[] noiseR, double ringW, double s, double rMask) {
        int n = lights.size();
        double[] amps = new double[n];
        double[] x = new double[n], y = new double[n];
        for (int q = 0; q < n; q++) {
            x[q] = lights.get(q).x;
            y[q] = lights.get(q).y;
        }
        for (int it = 0; it < 4; it++) {
            for (int q = 0; q < n; q++) {
                Light l = lights.get(q);
                double[] f = fitOne(R, W, er, rMask, x[q], y[q], s, lights, amps, q, noiseR, ringW);
                // a fit that wanders more than a pixel from the smoothed picture's peak has followed the glow's
                // leftovers, not the light: the peak it is
                if (f == null || Math.hypot(f[1] - x[q], f[2] - y[q]) > 1.0) {
                    l.x = x[q];
                    l.y = y[q];
                    continue;
                }
                amps[q] = f[0];
                l.x = f[1];
                l.y = f[2];
                l.flux = f[0] * 2 * Math.PI * s * s;
            }
        }
    }

    /**
     * What the same-all-round glow left, taken off: Jupiter is flattened, so close in its glow is a little longer
     * along its equator (the moons' own line), and the middle its frames were lined up on may sit a little off its
     * glow's. Ring by ring (a pixel wide) the residual's slow turn round Jupiter, once and twice round, fitted to its
     * pixels away from the moons' line (a band either side as wide as a moon's light, the moons' spread across the
     * line and the turn's doubt), any far off the fit left out and the fit again; between rings by the distance.
     * Out to where the glow is lost in the sky's noise.
     */
    static float[] harmonics(Result res, Options o, float[] R, double[] er, double turnDeg) {
        int W = res.w;
        double cx = res.cx, cy = res.cy, rJ = res.rJ;
        double a = Math.toRadians(turnDeg);
        // the line's direction in the result (y down; the turn counterclockwise as seen)
        double lx = Math.cos(a), ly = -Math.sin(a);
        int nr = (int) Math.min(W / 2.0 - 2, 16 * rJ + 2);
        int r0 = (int) Math.floor(Math.max(1, GLARE_RJ * rJ - 2));
        double[][] coef = new double[nr + 1][];
        double[] fit = new double[5];
        // pixels by ring
        int[] cnt = new int[nr + 1];
        for (int k = 0; k < W * W; k++) {
            int q = (int) er[k];
            if (q >= r0 && q < nr) cnt[q]++;
        }
        int[][] px = new int[nr + 1][];
        for (int q = 0; q <= nr; q++) px[q] = new int[cnt[q]];
        Arrays.fill(cnt, 0);
        for (int k = 0; k < W * W; k++) {
            int q = (int) er[k];
            if (q >= r0 && q < nr) px[q][cnt[q]++] = k;
        }
        for (int q = r0; q < nr; q++) {
            // only rings mostly clear of the moons' band (closer in, the pixels left are all to two sides and the
            // fit would say anything across the band: there the shifts and stretches of {@link #shape} do)
            if (band(res, o, q + 0.5) > 0.4 * (q + 0.5)) continue;
            int n = cnt[q];
            double[] c = new double[n], sn = new double[n], c2 = new double[n], s2 = new double[n], v = new double[n];
            boolean[] use = new boolean[n];
            int m = 0;
            for (int t = 0; t < n; t++) {
                int k = px[q][t];
                double dx = k % W - cx, dy = k / W - cy, r = er[k];
                double perp = Math.abs(-ly * dx + lx * dy);
                if (perp < band(res, o, r)) continue;
                c[t] = dx / r;
                sn[t] = dy / r;
                c2[t] = c[t] * c[t] - sn[t] * sn[t];
                s2[t] = 2 * c[t] * sn[t];
                v[t] = R[k];
                use[t] = true;
                m++;
            }
            if (m < 14) continue;
            for (int it = 0; it < 3; it++) {
                if (!lsq(c, sn, c2, s2, v, use, fit)) break;
                // the spread of what's left, and any far off it out
                double[] dev = new double[n];
                int nd = 0;
                for (int t = 0; t < n; t++) if (use[t]) dev[nd++] = Math.abs(v[t] - eval(fit, c[t], sn[t], c2[t], s2[t]));
                Arrays.sort(dev, 0, nd);
                double mad = 1.4826 * dev[nd / 2];
                int kept = 0;
                for (int t = 0; t < n; t++) {
                    if (!use[t]) continue;
                    if (Math.abs(v[t] - eval(fit, c[t], sn[t], c2[t], s2[t])) > 3 * mad + 1e-12) use[t] = false;
                    else kept++;
                }
                if (kept < 12) break;
            }
            if (!lsq(c, sn, c2, s2, v, use, fit)) continue;
            // and nothing wild: the slow turn no bigger than the ring's own spread allows
            double[] dev = new double[n];
            int nd = 0;
            for (int t = 0; t < n; t++) if (use[t]) dev[nd++] = Math.abs(v[t] - fit[0]);
            Arrays.sort(dev, 0, nd);
            double spread = 1.4826 * dev[nd / 2];
            if (Math.hypot(fit[1], fit[2]) + Math.hypot(fit[3], fit[4]) > 4 * spread + 1e-12) continue;
            coef[q] = fit.clone();
        }
        float[] out = R.clone();
        for (int k = 0; k < W * W; k++) {
            double r = er[k];
            if (r < r0 + 0.5 || r >= nr - 0.5) continue;
            // between the rings' middles
            double t = r - 0.5;
            int q = (int) Math.floor(t);
            double f = t - q;
            double[] A = coef[q], B = q + 1 <= nr ? coef[q + 1] : null;
            if (A == null && B == null) continue;
            double dx = k % W - cx, dy = k / W - cy;
            double cc = dx / r, ss = dy / r, cc2 = cc * cc - ss * ss, ss2 = 2 * cc * ss;
            double va = A != null ? eval(A, cc, ss, cc2, ss2) : eval(B, cc, ss, cc2, ss2);
            double vb = B != null ? eval(B, cc, ss, cc2, ss2) : va;
            out[k] = (float) (R[k] - (va * (1 - f) + vb * f));
        }
        return out;
    }

    static double eval(double[] f, double c, double s, double c2, double s2) {
        return f[0] + f[1] * c + f[2] * s + f[3] * c2 + f[4] * s2;
    }

    /** Least squares of v on 1, cos, sin, cos 2, sin 2 over the pixels in use; false if it can't be solved. */
    static boolean lsq(double[] c, double[] s, double[] c2, double[] s2, double[] v, boolean[] use, double[] out) {
        double[][] A = new double[5][6];
        for (int t = 0; t < v.length; t++) {
            if (!use[t]) continue;
            double[] f = {1, c[t], s[t], c2[t], s2[t]};
            for (int i = 0; i < 5; i++) {
                for (int j = 0; j < 5; j++) A[i][j] += f[i] * f[j];
                A[i][5] += f[i] * v[t];
            }
        }
        for (int col = 0; col < 5; col++) {
            int piv = col;
            for (int i = col + 1; i < 5; i++) if (Math.abs(A[i][col]) > Math.abs(A[piv][col])) piv = i;
            if (Math.abs(A[piv][col]) < 1e-9) return false;
            double[] tmp = A[col];
            A[col] = A[piv];
            A[piv] = tmp;
            for (int i = 0; i < 5; i++) {
                if (i == col) continue;
                double f = A[i][col] / A[col][col];
                for (int j = col; j < 6; j++) A[i][j] -= f * A[col][j];
            }
        }
        for (int i = 0; i < 5; i++) out[i] = A[i][5] / A[i][i];
        return true;
    }

    /**
     * Jupiter's glow as the same all round: out from its middle, the median of each ring of pixels at nearly the same
     * distance (a sixth of a pixel wide close in, where the pixels' distances come in steps and the light falls by
     * half a pixel, then a third, then half), each ring's at its own pixels' median distance, and between rings by the
     * distance, in the logarithm where both are lit (the fall is close to exponential). Per pixel; {@code sky[0]}
     * gets the outermost ring's.
     */
    static float[] glow(float[] Y, double[] er, double[] sky) {
        double[][] kn = knots(Y, er);
        double[] kv = kn[1];
        sky[0] = kv[kv.length - 1];
        float[] out = new float[Y.length];
        for (int k = 0; k < Y.length; k++) out[k] = (float) knotAt(kn, er[k]);
        return out;
    }

    /** The rings' {distances, medians} for {@link #glow}. */
    static double[][] knots(float[] Y, double[] er) {
        int n = Y.length;
        long[] key = new long[n];
        for (int k = 0; k < n; k++) key[k] = (Math.round(er[k] * 1e5) << 22) | k;
        Arrays.sort(key);
        int[] order = new int[n];
        for (int k = 0; k < n; k++) order[k] = (int) (key[k] & ((1 << 22) - 1));
        double[] kr = new double[n], kv = new double[n];
        int nk = 0;
        float[] vals = new float[n];
        double[] rads = new double[n];
        for (int g0 = 0; g0 < n; ) {
            double r0 = er[order[g0]];
            double width = r0 < 6 ? 0.15 : r0 < 10 ? 0.3 : 0.5;
            int g1 = g0;
            while (g1 < n && er[order[g1]] - r0 < width) g1++;
            int m = g1 - g0;
            for (int q = 0; q < m; q++) {
                vals[q] = Y[order[g0 + q]];
                rads[q] = er[order[g0 + q]];
            }
            Arrays.sort(vals, 0, m);
            // the radii come sorted
            kr[nk] = m % 2 == 1 ? rads[m / 2] : 0.5 * (rads[m / 2 - 1] + rads[m / 2]);
            kv[nk] = m % 2 == 1 ? vals[m / 2] : 0.5 * (vals[m / 2 - 1] + vals[m / 2]);
            nk++;
            g0 = g1;
        }
        return new double[][]{Arrays.copyOf(kr, nk), Arrays.copyOf(kv, nk)};
    }

    /** The glow at distance r from the rings' knots. */
    static double knotAt(double[][] kn, double r) {
        double[] kr = kn[0], kv = kn[1];
        int nk = kr.length;
        if (nk == 0) return 0;
        if (r <= kr[0]) return kv[0];
        if (r >= kr[nk - 1]) return kv[nk - 1];
        int lo = 0, hi = nk - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (kr[mid] <= r) lo = mid;
            else hi = mid;
        }
        double ra = kr[lo], rb = kr[hi], va = kv[lo], vb = kv[hi];
        double t = rb > ra ? (r - ra) / (rb - ra) : 0;
        if (va > 0 && vb > 0) return Math.exp(Math.log(va) * (1 - t) + Math.log(vb) * t);
        return va * (1 - t) + vb * t;
    }

    /** The least coverage within r px of (i, j). */
    static double minCover(double[] cover, int W, int i, int j, int r) {
        double m = Double.MAX_VALUE;
        for (int dj = -r; dj <= r; dj++) {
            for (int di = -r; di <= r; di++) {
                int x = i + di, y = j + dj;
                if (x < 0 || y < 0 || x >= W || y >= W) return 0;
                m = Math.min(m, cover[y * W + x]);
            }
        }
        return m;
    }

    static double mean(float[] v, int n) {
        double s = 0;
        for (int i = 0; i < n; i++) s += v[i];
        return n > 0 ? s / n : 0;
    }

    /** The noise (spread of the median's distance) of an image in rings {@code width} px wide out from the middle. */
    static double[] ringNoise(float[] img, double[] er, int W, int rings, double width) {
        return ringNoise(img, er, W, rings, width, null, 0);
    }

    /** The same, leaving out the pixels within {@code skipR} of the places in {@code skip}. */
    static double[] ringNoise(float[] img, double[] er, int W, int rings, double width, List<double[]> skip, double skipR) {
        boolean[] out0 = null;
        if (skip != null && !skip.isEmpty()) {
            out0 = new boolean[img.length];
            int rr = (int) Math.ceil(skipR);
            for (double[] p : skip) {
                if (p == null) continue;
                int ci = (int) Math.round(p[0]), cj = (int) Math.round(p[1]);
                for (int j = Math.max(0, cj - rr); j <= Math.min(W - 1, cj + rr); j++) {
                    for (int i = Math.max(0, ci - rr); i <= Math.min(W - 1, ci + rr); i++) {
                        if (Math.hypot(i - p[0], j - p[1]) <= skipR) out0[j * W + i] = true;
                    }
                }
            }
        }
        int[] cnt = new int[rings];
        for (int k = 0; k < img.length; k++) if (out0 == null || !out0[k]) cnt[Math.min(rings - 1, (int) (er[k] / width))]++;
        float[][] v = new float[rings][];
        for (int b = 0; b < rings; b++) v[b] = new float[cnt[b]];
        Arrays.fill(cnt, 0);
        for (int k = 0; k < img.length; k++) {
            if (out0 != null && out0[k]) continue;
            int b = Math.min(rings - 1, (int) (er[k] / width));
            v[b][cnt[b]++] = img[k];
        }
        double[] out = new double[rings];
        for (int b = 0; b < rings; b++) {
            int n = cnt[b];
            if (n < 8) {
                out[b] = Double.NaN;
                continue;
            }
            Arrays.sort(v[b], 0, n);
            float med = v[b][n / 2];
            for (int i = 0; i < n; i++) v[b][i] = Math.abs(v[b][i] - med);
            Arrays.sort(v[b], 0, n);
            out[b] = 1.4826 * v[b][n / 2];
        }
        // rings too small to say: the next one out's
        for (int b = rings - 2; b >= 0; b--) if (Double.isNaN(out[b])) out[b] = out[b + 1];
        for (int b = 0; b < rings; b++) if (Double.isNaN(out[b]) || out[b] <= 0) out[b] = 1e-9;
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // naming them

    /** The four put where the sums say, the turn and scale fitted to the lights found, each named or its absence said. */
    static void name(Result res, Options o) {
        JupiterGeo.Moon[] ms = o.view.moons;
        double rJ = res.rJ;
        List<Integer> want = new ArrayList<Integer>();
        Arrays.fill(res.with, -1);
        for (int k = 0; k < 4; k++) {
            JupiterGeo.Moon m = ms[k];
            if (m.hidden()) res.status[k] = HIDDEN;
            else if (m.transit()) res.status[k] = CROSSING;
            else if (m.eclipsed()) res.status[k] = DARK;
            else if (m.dist() < GLARE_RJ + 0.3) res.status[k] = GLARE;
            else {
                double px = res.cx + m.x * rJ, py = res.cy - m.y * rJ;
                if (px < 4 || py < 4 || px > res.w - 5 || py > res.w - 5) res.status[k] = OUTSIDE;
                else {
                    res.status[k] = MISSED;
                    want.add(k);
                }
            }
        }
        // the turn and scale that put the most of them on lights (near the tilt's own; anywhere if it's unknown)
        double range = res.tilted ? o.turnRange : 180, stepT = res.tilted ? 0.25 : 0.5;
        double tau = 1.3, bestScore = -1, bestT = 0, bestS = 1;
        for (double t = -range; t <= range + 1e-9; t += stepT) {
            for (double s = 0.95; s <= 1.0501; s += 0.01) {
                double score = 0;
                for (int k : want) {
                    double[] p = at(res, ms[k], t, s);
                    double d2 = Double.MAX_VALUE;
                    for (Light l : res.lights) d2 = Math.min(d2, (l.x - p[0]) * (l.x - p[0]) + (l.y - p[1]) * (l.y - p[1]));
                    score += Math.exp(-d2 / (2 * tau * tau));
                }
                // a little for being near what the tilt and the lens said
                score -= 0.002 * Math.abs(t) / Math.max(1, range) + 0.02 * Math.abs(s - 1);
                if (score > bestScore) {
                    bestScore = score;
                    bestT = t;
                    bestS = s;
                }
            }
        }
        // each moon onto the nearest light, the farthest out first; then the fit again on the pairs
        for (int round = 0; round < 2; round++) {
            for (Light l : res.lights) l.moon = -1;
            for (int k = 0; k < 4; k++) {
                res.moon[k] = null;
                if (res.status[k] == FOUND) res.status[k] = MISSED;
            }
            Integer[] order = want.toArray(new Integer[0]);
            final JupiterGeo.Moon[] fm = ms;
            Arrays.sort(order, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return Double.compare(fm[b].dist(), fm[a].dist());
                }
            });
            for (int k : order) {
                double[] p = at(res, ms[k], bestT, bestS);
                Light best = null;
                double bd = round == 0 ? 3.0 : 2.2;
                for (Light l : res.lights) {
                    if (l.moon >= 0) continue;
                    double d = Math.hypot(l.x - p[0], l.y - p[1]);
                    if (d < bd) {
                        bd = d;
                        best = l;
                    }
                }
                if (best != null) {
                    best.moon = k;
                    res.moon[k] = best;
                    res.status[k] = FOUND;
                }
            }
            if (round == 0) {
                // the turn and scale from the pairs (two or more): least squares, as complex numbers
                double zr = 0, zi = 0, pp = 0;
                int n = 0;
                for (int k = 0; k < 4; k++) {
                    if (res.moon[k] == null) continue;
                    double px = ms[k].x * rJ, py = -ms[k].y * rJ;
                    double dx = res.moon[k].x - res.cx, dy = res.moon[k].y - res.cy;
                    // y down: a turn counterclockwise as seen is a negative angle in these axes
                    zr += px * dx + py * dy;
                    zi += px * dy - py * dx;
                    pp += px * px + py * py;
                    n++;
                }
                if (n >= 2 && pp > 0) {
                    double s = Math.hypot(zr, zi) / pp, t = -Math.toDegrees(Math.atan2(zi, zr));
                    if (s > 0.9 && s < 1.1 && Math.abs(t - bestT) < 3) {
                        bestS = s;
                        bestT = t;
                    }
                }
            }
        }
        res.turn = bestT;
        res.scale = bestS;
        for (int k = 0; k < 4; k++) res.predicted[k] = at(res, ms[k], bestT, bestS);
        // the noise ring by ring again, without the lights found or where the sums put the moons: close to Jupiter a
        // ring is small, and a moon in it not yet found would raise its noise enough to hide itself
        if (res.filt != null) {
            List<double[]> skip = new ArrayList<double[]>();
            for (Light l : res.lights) skip.add(new double[]{l.x, l.y});
            for (int k = 0; k < 4; k++) skip.add(res.predicted[k]);
            double sig0 = res.psf > 0 ? res.psf : o.psfSigma * S;
            res.ringNoise = ringNoise(res.filt, radii(res), res.w, res.ringNoise.length, 3, skip, 2.5 * sig0);
        }
        // a moon not found where the sums put it (and the fit put the picture): looked for there again, fainter
        // (where a moon must be, a light half as far over the noise is enough: anywhere, it would be chance)
        if (res.filt != null && res.ringNoise != null) {
            for (int k : want) {
                if (res.status[k] != MISSED) continue;
                Light l = guided(res, o, res.predicted[k]);
                if (l == null) continue;
                l.moon = k;
                l.faint = true;
                res.lights.add(l);
                res.moon[k] = l;
                res.status[k] = FOUND;
            }
            // what's still missing: lost in Jupiter's glare (as bright as the moons found say it would show,
            // against the noise where it is, it couldn't be seen: close to Jupiter its glow's noise is many times
            // the sky's), or truly not seen (clouds, a plane, the sums wrong)
            List<Double> cal = new ArrayList<Double>();
            for (int k = 0; k < 4; k++) {
                Light l = res.moon[k];
                if (res.status[k] != FOUND || l == null || l.faint || l.snr < 8) continue;
                double r = Math.hypot(l.x - res.cx, l.y - res.cy);
                cal.add(l.snr * ringAt(res.ringNoise, r) * Math.pow(10, 0.4 * ms[k].mag));
            }
            java.util.Collections.sort(cal);
            for (int k : want) {
                if (res.status[k] != MISSED) continue;
                double[] p = res.predicted[k];
                double r = Math.hypot(p[0] - res.cx, p[1] - res.cy);
                double expect = cal.isEmpty() ? Double.NaN : cal.get(cal.size() / 2) * Math.pow(10, -0.4 * ms[k].mag) / ringAt(res.ringNoise, r);
                // (a moon that would show under about six times the noise can be missed by chance: the glare's)
                boolean glare = Double.isNaN(expect) ? ms[k].dist() < NEAR_RJ : expect < 6.5;
                if (glare && ms[k].dist() < 6) res.status[k] = GLARE;
                res.expectSnr[k] = expect;
            }
        }
        // a moon not found right beside one that was (closer than the blur can split): its light is in that one's,
        // the two one dot on the photo
        double sig = res.psf > 0 ? res.psf : o.psfSigma * S;
        for (int k : want) {
            if (res.status[k] != MISSED && res.status[k] != GLARE) continue;
            int near = -1;
            double nd = BLEND_SIGMAS * sig;
            for (int j = 0; j < 4; j++) {
                if (j == k || res.status[j] != FOUND || res.moon[j] == null || res.predicted[j] == null) continue;
                double dd = Math.hypot(res.predicted[k][0] - res.predicted[j][0], res.predicted[k][1] - res.predicted[j][1]);
                if (dd < nd) {
                    nd = dd;
                    near = j;
                }
            }
            if (near >= 0) {
                res.status[k] = BLENDED;
                res.with[k] = near;
                continue;
            }
            // not that close, but on a found one's skirt and still not picked out: lost in that one's light
            if (res.status[k] != MISSED) continue;
            nd = SKIRT_SIGMAS * sig;
            for (int j = 0; j < 4; j++) {
                if (j == k || res.status[j] != FOUND || res.moon[j] == null) continue;
                double dd = Math.hypot(res.predicted[k][0] - res.moon[j].x, res.predicted[k][1] - res.moon[j].y);
                if (dd < nd) {
                    nd = dd;
                    res.with[k] = j;
                }
            }
        }
        // each light's place in Jupiter's radii by the fit
        double a = Math.toRadians(bestT);
        for (Light l : res.lights) {
            double dx = l.x - res.cx, dy = l.y - res.cy;
            // turn back (the fit's turn is counterclockwise as seen: clockwise back)
            double ux = dx * Math.cos(a) - dy * Math.sin(a), uy = dx * Math.sin(a) + dy * Math.cos(a);
            l.jx = ux / (rJ * bestS);
            l.jy = -uy / (rJ * bestS);
        }
    }

    /** With nothing found to measure by, a moon not seen nearer Jupiter than this (its radii) is put down to its glare. */
    public static final double NEAR_RJ = 3.5;

    static double ringAt(double[] rings, double r) {
        return rings[Math.min(rings.length - 1, (int) (r / 3))];
    }

    /** Where a moon must be: a light over {@link #GUIDED_SIGMAS} times the noise this near Jupiter. */
    public static final double GUIDED_SIGMAS = 3.5;

    /**
     * The brightest of the search's smoothed picture within 2.5 px of {@code p}, if a peak there (no higher within
     * a pixel), well over its noise ({@link #GUIDED_SIGMAS}, more close to Jupiter, where the glow's leftovers
     * aren't all noise) and not on a light already found; fitted as the others. The lights already found near it are
     * taken off the smoothed picture first: a moon on a brighter one's skirt (Io three of Jupiter's radii from
     * Ganymede, closer than the smoothing can split) is a shoulder there, not a peak, until that one's light is off.
     */
    static Light guided(Result res, Options o, double[] p) {
        int W = res.w;
        float[] F = res.filt;
        double rJ = res.rJ;
        double sig = res.psf > 0 ? res.psf : o.psfSigma * S;
        // a light in the smoothed picture: its own blur and the smoothing's
        double sF2 = 2 * sig * sig;
        List<double[]> near = new ArrayList<double[]>();
        for (Light l : res.lights) {
            double d = Math.hypot(l.x - p[0], l.y - p[1]);
            // (one on the place itself is that light, below; far ones don't reach)
            if (d < 1.5 * sig + 2.5 || d > 4 * Math.sqrt(sF2) + 4) continue;
            near.add(new double[]{l.x, l.y, smoothedAt(F, W, l.x, l.y)});
        }
        int ic = (int) Math.round(p[0]), jc = (int) Math.round(p[1]);
        final int h = 4, side = 2 * h + 1;
        double[] G = new double[side * side];
        for (int dj = -h; dj <= h; dj++) {
            for (int di = -h; di <= h; di++) {
                int x = ic + di, y = jc + dj;
                double v = x < 0 || y < 0 || x >= W || y >= W ? 0 : F[y * W + x];
                for (double[] q : near) v -= q[2] * Math.exp(-((x - q[0]) * (x - q[0]) + (y - q[1]) * (y - q[1])) / (2 * sF2));
                G[(dj + h) * side + di + h] = v;
            }
        }
        double best = -Double.MAX_VALUE;
        int bi = 0, bj = 0;
        for (int dj = -3; dj <= 3; dj++) {
            for (int di = -3; di <= 3; di++) {
                int x = ic + di, y = jc + dj;
                if (x < 2 || y < 2 || x >= W - 2 || y >= W - 2) continue;
                if (Math.hypot(x - p[0], y - p[1]) > 2.5) continue;
                double v = G[(dj + h) * side + di + h];
                if (v > best) {
                    best = v;
                    bi = di;
                    bj = dj;
                }
            }
        }
        if (best == -Double.MAX_VALUE) return null;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) if (G[(bj + dj + h) * side + bi + di + h] > best) return null;
        // its place to a part of a pixel, from the peak's curve
        double gl = G[(bj + h) * side + bi - 1 + h], gr = G[(bj + h) * side + bi + 1 + h];
        double gu = G[(bj - 1 + h) * side + bi + h], gd = G[(bj + 1 + h) * side + bi + h];
        double cxx = gl - 2 * best + gr, cyy = gu - 2 * best + gd;
        double[] pk = {ic + bi + (cxx < 0 ? Math.max(-0.5, Math.min(0.5, 0.5 * (gl - gr) / cxx)) : 0),
                jc + bj + (cyy < 0 ? Math.max(-0.5, Math.min(0.5, 0.5 * (gu - gd) / cyy)) : 0)};
        int px = ic + bi, py = jc + bj;
        double r = Math.hypot(px - res.cx, py - res.cy);
        if (r < GLARE_RJ * rJ) return null;
        double nf = res.ringNoise[Math.min(res.ringNoise.length - 1, (int) (r / 3))];
        double need = (r < 4 * rJ ? 1.3 : 1) * GUIDED_SIGMAS * nf;
        if (best < need) return null;
        for (Light l : res.lights) if (Math.hypot(l.x - px, l.y - py) < 1.5 * sig) return null;
        double[] er = radii(res);
        double[] amps = new double[res.lights.size()];
        for (int q = 0; q < amps.length; q++) amps[q] = res.lights.get(q).flux / (2 * Math.PI * sig * sig);
        double[] f = fitOne(res.resid, W, er, GLARE_RJ * rJ - 1.5, pk[0], pk[1], sig, res.lights, amps, -1, res.noiseRings, 3);
        if (f != null && Math.hypot(f[1] - pk[0], f[2] - pk[1]) > 1.0) f = null;
        Light l = new Light();
        l.x = f != null ? f[1] : pk[0];
        l.y = f != null ? f[2] : pk[1];
        l.flux = f != null ? f[0] * 2 * Math.PI * sig * sig : 0;
        l.snr = best / Math.max(1e-12, nf);
        return l;
    }

    /** The smoothed picture at a light's place (between pixels, from the four round it). */
    static double smoothedAt(float[] F, int W, double x, double y) {
        int i = (int) Math.floor(x), j = (int) Math.floor(y);
        if (i < 0 || j < 0 || i >= W - 1 || j >= W - 1) return 0;
        double fx = x - i, fy = y - j;
        int k = j * W + i;
        return (1 - fy) * ((1 - fx) * F[k] + fx * F[k + 1]) + fy * ((1 - fx) * F[k + W] + fx * F[k + W + 1]);
    }

    /** Where moon m lands in the result for a turn t (degrees, counterclockwise as seen) and scale s. */
    static double[] at(Result res, JupiterGeo.Moon m, double t, double s) {
        double px = m.x * res.rJ * s, py = -m.y * res.rJ * s;
        double a = Math.toRadians(t);
        // counterclockwise as seen, y down
        double x = px * Math.cos(a) + py * Math.sin(a), y = -px * Math.sin(a) + py * Math.cos(a);
        return new double[]{res.cx + x, res.cy + y};
    }
}
