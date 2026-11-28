package com.brandon.nightsky.processing;

import java.util.Arrays;
import java.util.Locale;
import java.util.List;

/**
 * The Earth's glow on the Moon's night side (3.1): the "Da Vinci glow". The part of a crescent Moon the Sun doesn't
 * light isn't black: the Earth, nearly full in the Moon's sky then, lights it with the sunlight its oceans and clouds
 * throw back (Leonardo da Vinci worked that out around 1510, in the notebook now called the Codex Leicester). It is a
 * few thousand times fainter than the sunlit crescent, so no one exposure holds both: the Moonshot's burst is metered
 * for the crescent, and right after it the camera takes a second burst {@code G} times brighter, the crescent burnt
 * white and the night side showing. Here the second burst is merged into the first's stack:
 * <ol>
 * <li>every frame developed in 2x2 colour blocks, its hot pixels taken out, its light scaled back to the stack's;</li>
 * <li>the frames lined up on each other by the burnt-white crescent (the middle of it, and the phone's roll from the
 * tilt between them), and averaged where they aren't burnt;</li>
 * <li>the Moon's true centre and radius found on the average by its dark edge against the sky; its turn against the
 * stack from the phone's tilt (or, with none, by the burnt crescent's shape against the stack's);</li>
 * <li>the burnt crescent's bleed taken off: the crescent, dozens of times the white level in these frames, spills
 * over the pixels round its burnt part (the night side's next to the terminator too) as the frames' blur spreads it.
 * The stack holds that light unburnt, so its bright parts, spread by the blur that fits best, are matched to the
 * glow's light round the burnt part, which lines the two up to a fraction of a pixel too;</li>
 * <li>the sky and the lens's glare taken off: the glare as the crescent itself spread by the lens's wide halo, five
 * Gaussian spreads from 3 to 80 pixels, plus a sky with a slope, all fitted on the sky around the Moon and then taken
 * off everywhere, the Moon too;</li>
 * <li>both put together on the stack's grid, every pixel weighted by how far each can be trusted there (their noise,
 * the glare, whether the bright frames were burnt): the stack where the Sun shines, the glow in the night, the stack's
 * bright light itself (sharper than the glow's frames had it) where the two meet; right by the burnt part, where
 * neither can be trusted, the glow filled in from round it; past the Moon's edge, the glow's black sky.</li>
 * </ol>
 * Pure Java, for the phone and the simulator.
 */
public final class Earthshine {
    private Earthshine() {}

    /** The fewest frames worth merging. */
    public static final int MIN_FRAMES = 8;
    /** A patch of burnt 2x2 blocks smaller than this is hot pixels, not the crescent. */
    static final int HOT_PATCH = 12;
    /**
     * The glow's frames' blur past the stack's own (sigma, sensor px): the spreads the burnt crescent's bleed is made
     * of (the frames' blurs are a mix: most sharp, some shaken), and the one guessed when it can't be fitted.
     */
    static final double[] SPILL = {0.6, 0.9, 1.3, 1.8, 2.5, 3.5, 5, 7, 10};
    /**
     * Of {@link #SPILL}, the first CORE are the frames' own blur (shake, the blocks, the lining up: about all of the
     * stack's light between them); the wider ones the lens's glare close round the crescent, fitted with them so it
     * isn't taken for blur.
     */
    static final int CORE = 5;
    static final double SPILL_GUESS = 1.0;
    /**
     * The stack's light that bleeds: all of it past SPILL_HIGH of the glow's frames' white level, none below SPILL_LOW
     * of it, in patches that reach the white level; never below FLOOR_HIGH and FLOOR_LOW times the stack's sharpening
     * floor ({@link MoonStack#RL_FLOOR}: at the brightest glow the white level is down in the stack's noise).
     */
    static final double SPILL_LOW = 0.12, SPILL_HIGH = 0.3, FLOOR_LOW = 1.25, FLOOR_HIGH = 2.25;
    /**
     * A patch of light apart from the crescent: past SPECK times the stack's noise's SPECK_AT share (its specks'
     * brightest), and not within SPECK_EDGE px of where the stack ends.
     */
    static final double SPECK = 3, SPECK_AT = 0.999, SPECK_EDGE = 6;
    /**
     * The stack's bright edge: where the Moon just inside it is past LIMB_LIT (its bright parts 1); the circle through
     * it trusted if its points are within LIMB_SPREAD px of it (their spread).
     */
    static final double LIMB_LIT = 0.1, LIMB_SPREAD = 0.6;
    /** How far from the burnt part (px) its bleed is fitted. */
    static final double NEAR = 10;
    /** The bleed's fit: only where the stack's light, spread by the widest bleed, is at most this share of the white level. */
    static final double REGION = 0.6;
    /** The lens's wide halo past the bleed: the spreads (sigma, sensor pixels) whose sum is fitted to the glare. */
    static final double[] HALO = {3, 7, 16, 36, 80};
    /** How far the glare taken off can be trusted (a share of it): the glow's weight falls where it was large. */
    static final double GLARE_DOUBT = 0.05;
    /** The glow's frames are trusted up to this share of the white level (they stay straight below it), fading over FADE. */
    static final double TRUST = 0.6, FADE = 0.25;
    /** Nor where more than this share of them burnt (the rest average too dim there): trusted less from none. */
    static final double CF_FADE = 0.01;
    /** The glow filled in next to the burnt part is held to this share of the night side's level. */
    static final double FILL_DOUBT = 0.35;
    /** The glow's colour is smoothed this much (sigma, sensor px): the Earth lights the night side nearly one colour. */
    static final double CHROMA = 6;
    /** The fill's wider reach (sigma, sensor px), where there's too little glow close round. */
    /** The fill takes the stack's light along where it's at most FILL_HI times the stack's noise (faint: the fill's better). */
    static final double FILL_HI = 5;
    static final double FILL_WIDE = 5;
    /** Past the Moon's edge by this much (sensor px) is sky: the stack's light there isn't taken. */
    static final double SKY_CLEAR = 1.5;
    /** The stack's, the glow's and the fill's shares smoothed this much (sigma, sensor px) where they meet. */
    static final double MIX_SMOOTH = 0.6;
    /** Sunlit (the bright parts 1): the stack's own there, whatever its neighbours. */
    static final double SUNLIT = 0.05;
    /** The stack's faint parts smoothed this much (sigma, sensor px) where it has a share of the night. */
    static final double MAIN_SMOOTH = 0.75;
    /** The dark edge is sought this far (a share of the radius, or 12 px) from the burnt part. */
    static final double ARC_CLEAR = 0.35;

    public static final class Result {
        public int w, h;
        /** The stack with the Earth's glow in its night side: linear sRGB in the stack's units (1: its bright parts). */
        public float[] r, g, b;
        /** The glow's brightness on the night side (its median), as a share of the sunlit Moon's bright parts. */
        public double level = Double.NaN;
        /** The photo's black point: the merged sky's noise, three times (the stack's units), for {@link MoonLook}. */
        public double black;
        /** How much brighter the glow's frames were than the stack's (their median); merged, of how many. */
        public double gain;
        public int used, total;
        /** The glow frame the others were lined up on (an index into them). */
        public int reference = -1;
        /** The glow's brightness over its noise on the night side (a pixel's, smoothed), and its dark edge's. */
        public double snr, edgeSnr;
        /** The glow frames' turn against the stack (degrees): from the crescent's direction, and from the tilt. */
        public double turn, turnTilt = Double.NaN;
        /** The glare's share of the light on the night side (its median there, before it was taken off). */
        public double glare;
        /** The Moon's centre in the glow's average, and its radius (the reference glow frame's crop px). */
        public double cx, cy, radius;
        /**
         * The Moon's true centre and radius on the stack's grid, from the night side's edge (the stack's own, from a
         * thin crescent's bright edge alone, can be a little off): for the photo's framing and its labels.
         */
        public double moonCx, moonCy, moonR;
        /** The glow frames' average (luminance, the stack's units, the sky and glare still in), for a look: w x h. */
        public float[] mean;
        /** And the share of its frames burnt there, and the sky and glare fitted to it (luminance). */
        public float[] burnt, background;
        public int meanW, meanH;
        public boolean ok;
        public String log = "";
    }

    /** A frame's burnt-white crescent: its middle (the crop's px) and its size (2x2 blocks). */
    static final class Burnt {
        double kx, ky;
        int area;
        boolean ok;
    }

    // ------------------------------------------------------------------------------------------------
    // a frame

    /** The burnt part of a frame: its biggest patch of 2x2 blocks with a sample at the white level. */
    static Burnt burnt(MoonStack.Frame f, SensorSpec spec) {
        int bw = f.w / 2, bh = f.h / 2;
        int satAt = (int) spec.white - 2;
        boolean[] clip = new boolean[bw * bh];
        for (int bj = 0; bj < bh; bj++) {
            for (int bi = 0; bi < bw; bi++) {
                int k = 2 * bj * f.w + 2 * bi;
                clip[bj * bw + bi] = (f.raw[k] & 0xffff) >= satAt || (f.raw[k + 1] & 0xffff) >= satAt
                        || (f.raw[k + f.w] & 0xffff) >= satAt || (f.raw[k + f.w + 1] & 0xffff) >= satAt;
            }
        }
        int[] comp = largest(clip, bw, bh);
        Burnt b = new Burnt();
        double sx = 0, sy = 0;
        for (int q : comp) {
            sx += 2 * (q % bw) + 0.5;
            sy += 2 * (q / bw) + 0.5;
        }
        b.area = comp.length;
        b.ok = comp.length >= 12;
        if (b.ok) {
            b.kx = sx / comp.length;
            b.ky = sy / comp.length;
        }
        return b;
    }

    /** The biggest 4-connected patch of set cells (their indices). */
    static int[] largest(boolean[] set, int w, int h) {
        int[] label = new int[w * h];
        int[] stack = new int[w * h];
        int best = 0, bestN = 0, next = 0;
        for (int s = 0; s < w * h; s++) {
            if (!set[s] || label[s] != 0) continue;
            next++;
            int sp = 0, cnt = 0;
            stack[sp++] = s;
            label[s] = next;
            while (sp > 0) {
                int q = stack[--sp];
                cnt++;
                int x = q % w, y = q / w;
                if (x > 0 && set[q - 1] && label[q - 1] == 0) { label[q - 1] = next; stack[sp++] = q - 1; }
                if (x < w - 1 && set[q + 1] && label[q + 1] == 0) { label[q + 1] = next; stack[sp++] = q + 1; }
                if (y > 0 && set[q - w] && label[q - w] == 0) { label[q - w] = next; stack[sp++] = q - w; }
                if (y < h - 1 && set[q + w] && label[q + w] == 0) { label[q + w] = next; stack[sp++] = q + w; }
            }
            if (cnt > bestN) {
                bestN = cnt;
                best = next;
            }
        }
        int[] out = new int[bestN];
        int m = 0;
        for (int q = 0; q < w * h && m < bestN; q++) if (label[q] == best && best != 0) out[m++] = q;
        return out;
    }

    /** The 8-connected patches of set cells: each cell's patch (1..; 0 unset) into {@code lab}, and each patch's size. */
    static int[] patches(boolean[] set, int w, int h, int[] lab) {
        return patches(set, w, h, lab, 1);
    }

    /** The same, cells up to {@code rad} apart (across and down) joined. */
    static int[] patches(boolean[] set, int w, int h, int[] lab, int rad) {
        int[] stack = new int[w * h];
        int[] size = new int[16];
        int next = 0;
        for (int s = 0; s < w * h; s++) {
            if (!set[s] || lab[s] != 0) continue;
            next++;
            if (next >= size.length) size = Arrays.copyOf(size, size.length * 2);
            int sp = 0, cnt = 0;
            stack[sp++] = s;
            lab[s] = next;
            while (sp > 0) {
                int q = stack[--sp];
                cnt++;
                int x = q % w, y = q / w;
                for (int dy = -rad; dy <= rad; dy++) {
                    for (int dx = -rad; dx <= rad; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                        int r = yy * w + xx;
                        if (set[r] && lab[r] == 0) {
                            lab[r] = next;
                            stack[sp++] = r;
                        }
                    }
                }
            }
            size[next] = cnt;
        }
        return size;
    }

    /**
     * A frame developed in 2x2 blocks (one red, two green, one blue each, the colours put at the block's middle):
     * linear sRGB times {@code toMain} (the stack's units), hot pixels taken out; {@code clip} gets the blocks made
     * from a burnt sample.
     */
    static float[][] develop(MoonStack.Frame f, SensorSpec spec, double toMain, boolean[] clip) {
        int w = f.w, h = f.h;
        boolean[] sat = new boolean[w * h];
        float[] bal = MoonStack.balanced(f, spec, sat);
        // a small patch of burnt samples is a hot pixel or two (the crescent burns in a big patch, a colour's samples
        // two apart joined): mended from its colour's samples round it, and not burnt; then the hot pixels short of
        // the white level
        int[] lab = new int[w * h];
        int[] size = patches(sat, w, h, lab, 2);
        boolean[] was = sat.clone();
        for (int k = 0; k < lab.length; k++) {
            if (lab[k] == 0 || size[lab[k]] >= 4 * HOT_PATCH) continue;
            sat[k] = false;
            int x = k % w, y = k / w;
            double sm = 0;
            int nm = 0;
            for (int dy = -2; dy <= 2; dy += 2) {
                for (int dx = -2; dx <= 2; dx += 2) {
                    int xx = x + dx, yy = y + dy;
                    if ((dx == 0 && dy == 0) || xx < 0 || yy < 0 || xx >= w || yy >= h || was[yy * w + xx]) continue;
                    sm += bal[yy * w + xx];
                    nm++;
                }
            }
            if (nm > 0) bal[k] = (float) (sm / nm);
        }
        dehot(bal, sat, w, h);
        int bw = w / 2, bh = h / 2;
        float[][] cam = new float[3][bw * bh];
        // each colour at the block's middle: a colour with one sample in the block (red, blue) from its own four
        // nearest (9:3:3:1), so the colours sit together (else red and blue, a pixel apart, fringe every edge);
        // one with two (green), their mean, which sits there already
        for (int bj = 0; bj < bh; bj++) {
            for (int bi = 0; bi < bw; bi++) {
                int q = bj * bw + bi;
                boolean cl = false;
                float gs = 0;
                int gn = 0;
                for (int d = 0; d < 4; d++) {
                    int ox = d & 1, oy = d >> 1;
                    int x = 2 * bi + ox, y = 2 * bj + oy;
                    int k = y * w + x;
                    int ch = spec.cfa[((y + f.y0) & 1) * 2 + ((x + f.x0) & 1)];
                    if (ch == 1) {
                        gs += bal[k];
                        gn++;
                        cl |= sat[k];
                        continue;
                    }
                    // the neighbouring blocks' samples towards the middle, across and down (clamped at the edges)
                    int bx = Math.max(0, Math.min(bw - 1, ox == 0 ? bi + 1 : bi - 1));
                    int by = Math.max(0, Math.min(bh - 1, oy == 0 ? bj + 1 : bj - 1));
                    int kx = y * w + 2 * bx + ox, ky = (2 * by + oy) * w + x, kxy = (2 * by + oy) * w + 2 * bx + ox;
                    cam[ch][q] = (9 * bal[k] + 3 * bal[kx] + 3 * bal[ky] + bal[kxy]) / 16;
                    cl |= sat[k] || sat[kx] || sat[ky] || sat[kxy];
                }
                cam[1][q] = gn > 0 ? gs / gn : 0;
                clip[q] = cl;
            }
        }
        float[][] rgb = MoonStack.toSrgb(cam, spec);
        MoonStack.mul(rgb, (float) toMain);
        return rgb;
    }

    /**
     * Hot pixels out, on the sensor's own samples: one more than six times the noise above all four of its colour's
     * nearest (two pixels off; the lens blurs everything real over more than that) is one, and gets their average.
     */
    static void dehot(float[] v, boolean[] sat, int w, int h) {
        float[] d = new float[(w - 2) * h];
        int m = 0;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w - 2; i++) {
                int k = j * w + i;
                if (!sat[k] && !sat[k + 2]) d[m++] = Math.abs(v[k] - v[k + 2]);
            }
        }
        if (m < 100) return;
        float[] dd = Arrays.copyOf(d, m);
        Arrays.sort(dd);
        double sigma = dd[m / 2] * 1.4826 / Math.sqrt(2);
        if (!(sigma > 0)) return;
        float thr = (float) (6 * sigma);
        float[] src = v.clone();
        for (int j = 2; j < h - 2; j++) {
            for (int i = 2; i < w - 2; i++) {
                int k = j * w + i;
                if (sat[k]) continue;
                float a = src[k - 2], b = src[k + 2], c = src[k - 2 * w], e = src[k + 2 * w];
                float mx = Math.max(Math.max(a, b), Math.max(c, e));
                if (src[k] - mx > thr) v[k] = (a + b + c + e) * 0.25f;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // the merge

    /**
     * The glow's frames merged into the stack {@code main}: {@code gains} each frame's light against the stack's
     * frames' (from its capture result; null or missing: {@code askedGain}). A result that isn't {@code ok} says why
     * in its log (too faint to see, no crescent, too few frames).
     */
    public static Result merge(List<MoonStack.Frame> frames, List<Double> gains, double askedGain, MoonStack.Result main, SensorSpec spec,
                               Progress pr) {
        Result res = new Result();
        res.total = frames == null ? 0 : frames.size();
        StringBuilder log = new StringBuilder("earthshine:");
        if (main == null || main.r == null || !(main.unit > 0) || main.disk == null || res.total < MIN_FRAMES) {
            res.log = log.append(" nothing to merge (").append(res.total).append(" frames)").toString();
            return res;
        }
        int n = frames.size();
        // 1. the burnt crescent in every frame, and every frame's light
        Burnt[] bt = new Burnt[n];
        double[] gain = new double[n];
        for (int i = 0; i < n; i++) {
            if (pr.cancelled()) throw new CancelledException();
            bt[i] = burnt(frames.get(i), spec);
            Double gi = gains != null && i < gains.size() ? gains.get(i) : null;
            gain[i] = gi != null && gi > 1 ? gi : askedGain;
            if (i % 8 == 0) pr.update("The Earth's glow: finding the crescent", 0.1f * (i + 1) / n);
        }
        double[] areas = new double[n];
        for (int i = 0; i < n; i++) areas[i] = bt[i].ok ? bt[i].area : 0;
        double aMed = MoonStack.median(areas), gMed = MoonStack.median(gain);
        // the frames that agree with the most: a cloud, or the exposure not yet through, shrinks the burnt part; the
        // hand's blur spreads it (a shaken frame's crescent burns a third wider at the brightest glow, and is worth
        // keeping: the glow is soft)
        boolean[] use = new boolean[n];
        int used = 0;
        for (int i = 0; i < n; i++) {
            use[i] = bt[i].ok && bt[i].area >= 0.8 * aMed && bt[i].area <= 1.6 * aMed && Math.abs(gain[i] / gMed - 1) < 0.12;
            if (use[i]) used++;
        }
        res.gain = gMed;
        log.append(String.format(Locale.US, " %d frames %.0fx brighter than the stack's (%s)", n, gMed, aMed > 0 ? String.format(Locale.US,
                "the crescent burnt over %.0f blocks", aMed) : "nothing burnt"));
        if (used < MIN_FRAMES || aMed < 12) {
            res.log = log.append("; too few frames with the crescent (").append(used).append("), nothing merged").toString();
            return res;
        }
        // the reference: of the middle half of those kept (the least turned from the rest), the one whose crescent burnt
        // most like the most (the frames are lined up by their burnt crescents' middles, which a shaken frame's
        // spreading moves a little: the average lies where a typical frame's does)
        int ref = -1, seen = 0;
        double refOff = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (!use[i]) continue;
            int at = seen++;
            if (at < used / 4 || at > used - 1 - used / 4) continue;
            double off = Math.abs(bt[i].area - aMed);
            if (off < refOff) {
                refOff = off;
                ref = i;
            }
        }
        res.reference = ref;
        MoonStack.Frame rf = frames.get(ref);
        int gw = rf.w, gh = rf.h, np = gw * gh;
        double rkx = bt[ref].kx, rky = bt[ref].ky;

        // 2. all of them lined up on the reference by their crescents, and averaged where they aren't burnt
        float[][] sum = new float[3][np];
        float[] cnt = new float[np], clipN = new float[np];
        int done = 0;
        for (int i = 0; i < n; i++) {
            if (!use[i]) continue;
            if (pr.cancelled()) throw new CancelledException();
            MoonStack.Frame f = frames.get(i);
            int bw = f.w / 2, bh = f.h / 2;
            boolean[] clip = new boolean[bw * bh];
            float[][] dev = develop(f, spec, main.unit / gain[i], clip);
            // a point at d from the reference's crescent is at R(turn) d from this frame's (the crescent is on the Moon)
            double da = !Double.isNaN(f.upPa) && !Double.isNaN(rf.upPa) ? Math.toRadians(f.upPa - rf.upPa) : 0;
            double c = Math.cos(da), s = Math.sin(da);
            double kx = bt[i].kx, ky = bt[i].ky;
            for (int y = 0; y < gh; y++) {
                double dy = y - rky;
                for (int x = 0; x < gw; x++) {
                    double dx = x - rkx;
                    double bx = (kx + c * dx - s * dy - 0.5) / 2, by = (ky + s * dx + c * dy - 0.5) / 2;
                    int ix = (int) Math.floor(bx), iy = (int) Math.floor(by);
                    if (ix < 0 || iy < 0 || ix >= bw - 1 || iy >= bh - 1) continue;
                    int q = iy * bw + ix, k = y * gw + x;
                    float fx = (float) (bx - ix), fy = (float) (by - iy);
                    if (clip[q] || clip[q + 1] || clip[q + bw] || clip[q + bw + 1]) {
                        // by the burnt part: burnt if the nearer half of the blocks is, else from the blocks that aren't
                        float w00 = clip[q] ? 0 : (1 - fx) * (1 - fy), w10 = clip[q + 1] ? 0 : fx * (1 - fy);
                        float w01 = clip[q + bw] ? 0 : (1 - fx) * fy, w11 = clip[q + bw + 1] ? 0 : fx * fy;
                        float wsum = w00 + w10 + w01 + w11;
                        if (wsum < 0.5f) {
                            clipN[k]++;
                            continue;
                        }
                        for (int ch = 0; ch < 3; ch++) {
                            float[] v = dev[ch];
                            sum[ch][k] += (w00 * v[q] + w10 * v[q + 1] + w01 * v[q + bw] + w11 * v[q + bw + 1]) / wsum;
                        }
                        cnt[k]++;
                        continue;
                    }
                    for (int ch = 0; ch < 3; ch++) {
                        float[] v = dev[ch];
                        float a = v[q] + (v[q + 1] - v[q]) * fx, b = v[q + bw] + (v[q + bw + 1] - v[q + bw]) * fx;
                        sum[ch][k] += a + (b - a) * fy;
                    }
                    cnt[k]++;
                }
            }
            done++;
            pr.update("The Earth's glow: stacking " + done + " of " + used, 0.1f + 0.5f * done / used);
        }
        float[][] E = new float[3][np];
        float[] cf = new float[np], Y = new float[np];
        for (int k = 0; k < np; k++) {
            float m = cnt[k];
            for (int ch = 0; ch < 3; ch++) E[ch][k] = m > 0 ? sum[ch][k] / m : 0;
            cf[k] = m + clipN[k] > 0 ? clipN[k] / (m + clipN[k]) : 1;
            Y[k] = 0.2126f * E[0][k] + 0.7152f * E[1][k] + 0.0722f * E[2][k];
        }
        sum = null;
        res.used = used;
        res.mean = Y;
        res.burnt = cf;
        res.meanW = gw;
        res.meanH = gh;

        // 3. the Moon's centre on the average by its dark edge, its turn against the stack by the crescent
        pr.update("The Earth's glow: finding the Moon's dark edge", 0.62f);
        double S = main.gridS, psi = main.gridPsi, cps = Math.cos(psi), sps = Math.sin(psi);
        float[] mY = MoonStack.lum(new float[][]{main.r, main.g, main.b});
        // where the stack says the glow's frames burnt: its crescent's middle, as an offset from the centre in the
        // stack's reference crop
        double thr = 0.7 * main.unit / gMed, px = 0, py = 0, pw = 0;
        for (int j = 0; j < main.h; j++) {
            for (int i = 0; i < main.w; i++) {
                if (mY[j * main.w + i] < thr) continue;
                px += i;
                py += j;
                pw++;
            }
        }
        if (pw < 4) {
            res.log = log.append("; the stack has no crescent bright enough to burn, nothing merged").toString();
            return res;
        }
        double ox = (px / pw - main.cx) / S, oy = (py / pw - main.cy) / S;
        double vmx = cps * ox - sps * oy, vmy = sps * ox + cps * oy;
        double beta0 = !Double.isNaN(rf.upPa) && !Double.isNaN(main.refUpPa) ? Math.toRadians(rf.upPa - main.refUpPa) : 0;
        res.turnTilt = !Double.isNaN(rf.upPa) && !Double.isNaN(main.refUpPa) ? Math.toDegrees(beta0) : Double.NaN;
        double c0x = rkx - (Math.cos(beta0) * vmx - Math.sin(beta0) * vmy), c0y = rky - (Math.sin(beta0) * vmx + Math.cos(beta0) * vmy);
        double R0 = main.disk.r;
        // the burnt crescent on the average: its biggest patch (not a saturated star, nor the picture's edges)
        boolean[] burntSet = new boolean[np];
        for (int k = 0; k < np; k++) burntSet[k] = cnt[k] + clipN[k] > 0 && cf[k] >= 0.5f;
        boolean[] burntMask = new boolean[np];
        for (int q : largest(burntSet, gw, gh)) burntMask[q] = true;
        float[] dist = distance(burntMask, gw, gh);
        // the dark edge's arc: well away from the burnt part, inside the picture, with the frames there
        int m = 0, nOut = 0, nNear = 0, nThin = 0;
        double[] ct = new double[360], st = new double[360];
        double clear = Math.max(12, ARC_CLEAR * R0);
        for (int a = 0; a < 360; a++) {
            double ux = Math.cos(Math.toRadians(a)), uy = Math.sin(Math.toRadians(a));
            double x = c0x + ux * R0, y = c0y + uy * R0;
            if (x < 16 || y < 16 || x > gw - 17 || y > gh - 17) {
                nOut++;
                continue;
            }
            int k = (int) Math.round(y) * gw + (int) Math.round(x);
            if (dist[k] < clear) nNear++;
            else if (cnt[k] < 0.5 * used) nThin++;
            if (dist[k] < clear || cnt[k] < 0.5 * used) continue;
            ct[m] = ux;
            st[m] = uy;
            m++;
        }
        if (m < 40) {
            res.log = log.append(String.format(Locale.US, "; only %d degrees of the dark edge in the picture (%d out of it, %d by the burnt part, "
                    + "%d with too few frames; the centre guessed at %.1f,%.1f in %dx%d, the crescent's middle at %.1f,%.1f, %.1f,%.1f from the centre "
                    + "in the stack), nothing merged", m, nOut, nNear, nThin, c0x, c0y, gw, gh, rkx, rky, vmx, vmy)).toString();
            return res;
        }
        double[] best = {c0x, c0y, limb(Y, gw, gh, c0x, c0y, R0, ct, st, m, null)};
        search(Y, gw, gh, best, R0, ct, st, m, 10, 1);
        // the radius too: the stack's comes from the bright edge, which the limb's own brightening can push out a
        // little on a thin crescent, and on a short arc a wrong radius moves the centre
        double R = R0;
        double[] b3 = best.clone();
        for (double rr = 0.93 * R0; rr <= 1.05 * R0 + 1e-9; rr += 0.5) {
            double[] c = {best[0], best[1], 0};
            search(Y, gw, gh, c, rr, ct, st, m, 2.5, 0.5);
            if (c[2] > b3[2]) {
                b3 = c;
                R = rr;
            }
        }
        best = b3;
        double Rbest = R;
        for (double rr = Rbest - 0.5; rr <= Rbest + 0.5 + 1e-9; rr += 0.125) {
            double[] c = {b3[0], b3[1], 0};
            search(Y, gw, gh, c, rr, ct, st, m, 0.75, 0.125);
            if (c[2] > best[2]) {
                best = c;
                R = rr;
            }
        }
        refine(Y, gw, gh, best, R, ct, st, m);
        double cx = best[0], cy = best[1];
        double[] per = new double[m];
        double edge = limb(Y, gw, gh, cx, cy, R, ct, st, m, per);
        double sd = 0;
        for (int t = 0; t < m; t++) sd += (per[t] - edge) * (per[t] - edge);
        sd = Math.sqrt(sd / Math.max(1, m - 1));
        res.edgeSnr = sd > 0 ? edge / (sd / Math.sqrt(m)) : 0;
        res.cx = cx;
        res.cy = cy;
        res.radius = R;
        log.append(String.format(Locale.US, "; %d merged; the dark edge over %d degrees (its contrast %.3g, %.1f times its noise), the centre "
                + "%.2f,%.2f (%.2f,%.2f from the crescent's guess), radius %.2f (the stack's %.2f)", used, m, edge, res.edgeSnr, cx, cy,
                cx - c0x, cy - c0y, R, R0));
        if (!(res.edgeSnr >= 5) || !(edge > 0)) {
            res.log = log.append("; the glow is too faint to see the Moon's dark edge, nothing merged").toString();
            return res;
        }
        // 4. lined up on the stack: roughly by the centres and the phone's tilt (or the crescent's shape); then exactly
        // by the burnt crescent's bleed (the stack's centre, found on a thin crescent's bright edge alone, can be a
        // pixel or so off the true one the dark edge gives); then the glare and the sky taken off
        double tx = cx, ty = cy, beta = beta0;
        boolean turnFree = Double.isNaN(res.turnTilt);
        if (turnFree) {
            double[] reg = register(cf, burntMask, dist, gw, gh, cx, cy, mY, main, 0);
            if (reg != null && reg[3] >= 0.6) beta = reg[2];
        }
        // the stack's centre came from its bright edge alone, its radius with it: on a thin crescent's short edge a
        // radius a little off moves the centre a pixel or more along the crescent's middle. The dark edge's radius is
        // the true one: the bright edge fitted again with it, the stack put by that centre
        double[] lc = limbCentre(mY, main.w, main.h, main.cx, main.cy, R * S, S);
        if (lc != null && lc[3] < LIMB_SPREAD * S) {
            double ux = (cps * lc[0] - sps * lc[1]) / S, uy = (sps * lc[0] + cps * lc[1]) / S;
            tx = cx - (Math.cos(beta) * ux - Math.sin(beta) * uy);
            ty = cy - (Math.sin(beta) * ux + Math.cos(beta) * uy);
            log.append(String.format(Locale.US, "; the stack's bright edge with the dark edge's radius: its centre %.2f,%.2f off the stack's "
                    + "(%d points, %.2f px apart from the circle)", lc[0] / S, lc[1] / S, (int) lc[2], lc[3] / S));
        }
        pr.update("The Earth's glow: taking off the glare", 0.7f);
        int[] pts = new int[np];
        int npt = 0;
        double sky2 = Math.pow(1.04 * R, 2);
        for (int y = 3; y < gh - 3; y += 2) {
            for (int x = 3; x < gw - 3; x += 2) {
                int k = y * gw + x;
                double dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy < sky2 || clipN[k] > 0 || cnt[k] < 0.5 * used) continue;
                pts[npt++] = k;
            }
        }
        if (npt < 200) {
            res.log = log.append("; too little sky around the Moon to measure the glare, nothing merged").toString();
            return res;
        }
        pts = Arrays.copyOf(pts, npt);
        int W = main.w, H = main.h, NP = W * H;
        float[][] mrgb = {main.r, main.g, main.b};
        // the stack's bright light: what bleeds in the glow's frames
        double white = main.unit / gMed;
        // from a share of the glow's white level up, but never down in the stack's noise (where the brightest glow's
        // white level is), in the patches that burn in the glow's frames
        double loAt = Math.max(SPILL_LOW * white, FLOOR_LOW * MoonStack.RL_FLOOR), hiAt = Math.max(SPILL_HIGH * white, FLOOR_HIGH * MoonStack.RL_FLOOR);
        float[][] hi = bright(mrgb, mY, W, H, loAt, hiAt, white);
        float[] hiY = MoonStack.lum(hi);
        // the glow's colours are as blurred as their development makes them: a colour with one sample a block (red,
        // blue: from its four nearest, a spread of 0.75 px squared each way) more than one with two (green, 0.25)
        double[] extra = devBlur(spec);
        Spill sp = spill(Y, cf, cnt, used, dist, gw, gh, cx, cy, R, hi, extra, main, white, noiseOf(Y, pts, gw), tx, ty, beta, turnFree);
        boolean spOk = sp != null && sp.ok;
        if (spOk) {
            tx = sp.tx;
            ty = sp.ty;
            beta = sp.beta;
        }
        log.append(sp == null ? "; too little round the burnt part to fit its bleed: the centres and the tilt used" : String.format(Locale.US,
                "; the burnt crescent's bleed fitted over %d px (%d left out): the glow's frames %.2f px blurrier than the stack (%s of its light "
                        + "at %s px), the fit %.2f of the noise (%.2f before); the stack's centre %.2f,%.2f from the true one, turned %.2f deg "
                        + "(the tilt said %s)%s", sp.n, sp.out, sp.sigma, shares(sp.share), Arrays.toString(SPILL), sp.rms, sp.rmsStart, sp.tx - cx,
                sp.ty - cy, Math.toDegrees(sp.beta), turnFree ? "nothing" : String.format(Locale.US, "%.2f", res.turnTilt),
                spOk ? "" : ": too poor a fit, the centres, the tilt and a guessed blur used"));
        // the bleed on the stack's grid, each colour
        float[][] Lc = new float[3][];
        for (int ch = 0; ch < 3; ch++) {
            if (!spOk) {
                Lc[ch] = MoonStack.blur(hi[ch], W, H, Math.sqrt(SPILL_GUESS * SPILL_GUESS + extra[ch]) * S);
                continue;
            }
            float[] acc = new float[NP];
            for (int i = 0; i < SPILL.length; i++) {
                if (sp.share[i] == 0) continue;
                float[] bl = MoonStack.blur(hi[ch], W, H, Math.sqrt(SPILL[i] * SPILL[i] + extra[ch]) * S);
                for (int k = 0; k < NP; k++) acc[k] += (float) (sp.share[i] * bl[k]);
            }
            Lc[ch] = acc;
        }
        double a = 1;
        float[] LY = MoonStack.lum(Lc);
        Glare gl = glare(E, mrgb, Lc, a, main, tx, ty, beta, gw, gh, pts, cx, cy, R);
        log.append(String.format(Locale.US, "; the glare's halo (at %s px): %s; the sky fitted to %.2g over %d points", Arrays.toString(HALO),
                gl.halo, gl.noise, npt));
        res.background = gl.bgY;
        res.turn = Math.toDegrees(beta);
        double cb = Math.cos(beta), sb = Math.sin(beta);
        // the true centre and radius on the stack's grid
        {
            double dx = cx - tx, dy = cy - ty;
            double mx = cb * dx + sb * dy, my = -sb * dx + cb * dy;
            res.moonCx = main.cx + S * (cps * mx + sps * my);
            res.moonCy = main.cy + S * (-sps * mx + cps * my);
            res.moonR = R * S;
        }

        // 5. onto the stack's grid, and together: the glow's frames with the sky, the glare and the bleed taken off
        // (the night side, and what the stack's bright light doesn't hold), the stack's bright light itself
        pr.update("The Earth's glow: blending it in", 0.85f);
        float[][] e = new float[3][NP];
        float[] ecf = new float[NP], ecnt = new float[NP], eraw = new float[NP], egl = new float[NP];
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                double qx = (i - main.cx) / S, qy = (j - main.cy) / S;
                double mx = cps * qx - sps * qy, my = sps * qx + cps * qy;
                double x = tx + cb * mx - sb * my, y = ty + sb * mx + cb * my;
                int k = j * W + i;
                if (x < 0 || y < 0 || x > gw - 1 || y > gh - 1) {
                    ecf[k] = 1;
                    continue;
                }
                for (int ch = 0; ch < 3; ch++) {
                    e[ch][k] = (float) (MoonStack.sample(E[ch], gw, gh, x, y) - MoonStack.sample(gl.rest[ch], gw, gh, x, y) - a * Lc[ch][k]);
                }
                ecf[k] = MoonStack.sample(cf, gw, gh, x, y);
                ecnt[k] = MoonStack.sample(cnt, gw, gh, x, y);
                eraw[k] = MoonStack.sample(Y, gw, gh, x, y);
                egl[k] = (float) (MoonStack.sample(gl.haloY, gw, gh, x, y) + a * LY[k]);
            }
        }
        // how far each pixel's glow can be trusted: not where its frames were short (the picture's edge), nor where
        // any burnt (the rest, those dimmer there, average too dim), nor near the white level (not straight there:
        // by the glow's own average, or the stack's bright light; not by the stack's every pixel, whose noise's specks
        // reach the brightest glow's white level)
        float[] fade = new float[NP];
        for (int k = 0; k < NP; k++) {
            if (ecnt[k] < 0.25 * used) continue;
            double pg = Math.max(eraw[k], hiY[k]) * gMed / main.unit;
            fade[k] = (float) (Math.max(0, Math.min(1, 1 - ecf[k] / CF_FADE)) * Math.max(0, Math.min(1, (TRUST - pg) / FADE)));
        }
        // the glow smoothed (it was taken in 2x2 blocks, half as fine as the stack's sensor pixels and a quarter of
        // its grid's), its colour much more (the faint glow's colour noise mustn't show as coloured blotches); only
        // from the pixels it can be trusted in (the burnt part's mustn't spread)
        float[] eY = MoonStack.lum(e);
        float[] eYs = trusted(eY, fade, W, H, 1.2 * S / 2);
        // the two noises, from the sky just around the Moon
        double Rout = main.radius;
        double sigM = ringNoise(mY, W, H, main.cx, main.cy, Rout, ecf, 0.02f);
        double sigE = ringNoise(eYs, W, H, main.cx, main.cy, Rout, ecf, Float.MAX_VALUE);
        if (!(sigM > 0)) sigM = 1e-4;
        if (!(sigE > 0)) sigE = 1e-5;
        float[] eYc = trusted(eY, fade, W, H, 4.0 * S / 2), eYk = trusted(eY, fade, W, H, CHROMA * S);
        float[][] chroma = new float[3][];
        for (int ch = 0; ch < 3; ch++) {
            float[] cb3 = trusted(e[ch], fade, W, H, CHROMA * S);
            float[] cr = new float[NP];
            for (int k = 0; k < NP; k++) {
                float yy = eYk[k];
                float rat = yy > sigE ? cb3[k] / yy : 1;
                cr[k] = Math.max(0.5f, Math.min(2f, rat));
            }
            chroma[ch] = cr;
        }
        // next to the burnt part, where the glow can't be trusted (some of its frames burnt there), the night side's
        // glow filled in from round it (its smoothed colour's own), the stack's noise not left alone there, with the
        // stack's light where it's faint (its noise's few times: the crescent's last, its patch's edge); but not where
        // the stack is lit, nor where its sure light says it's clearly brighter than the two (the terminator's last
        // light, past its noise and its sharpening's floor; never a speck of its noise alone, which the sharpening
        // holds up as bright as the brightest glow's white level)
        float[] tFill = MoonStack.blur(fade, W, H, 4.0 * S / 2), tFillW = MoonStack.blur(fade, W, H, FILL_WIDE * S);
        float[] eYw = trusted(eY, fade, W, H, FILL_WIDE * S);
        float[] ms = MoonStack.blur(mY, W, H, S / 2), hiS = MoonStack.blur(hiY, W, H, S / 2);
        double sigMs = sigM / (2 * Math.sqrt(Math.PI) * (S / 2)), prior;
        {
            float[] lv = new float[NP];
            int nl = 0;
            double r2 = Math.pow(0.88 * main.radius, 2);
            for (int k = 0; k < NP; k++) {
                double dx = k % W - main.cx, dy = k / W - main.cy;
                if (fade[k] >= 0.99f && hiY[k] <= 0 && dx * dx + dy * dy < r2) lv[nl++] = eYs[k];
            }
            float[] sl = Arrays.copyOf(lv, nl);
            Arrays.sort(sl);
            prior = Math.max(3 * sigE, nl > 100 ? FILL_DOUBT * sl[nl / 2] : 0);
        }
        float[] outR = new float[NP], outG = new float[NP], outB = new float[NP];
        float[][] orgb = {outR, outG, outB};
        double wM = 1 / (sigM * sigM);
        // each pixel's shares: the stack's, the glow's, the fill's (their weights, each its trust over its noise)
        float[] am = new float[NP], ae = new float[NP], af = new float[NP], fillV = new float[NP], skyW = new float[NP];
        for (int k = 0; k < NP; k++) {
            boolean noMain = main.r[k] == 0f && main.g[k] == 0f && main.b[k] == 0f;
            double wm = noMain ? 0 : wM;
            // past the Moon's edge (and its own blur), sky: the stack's faint light there is its noise, its
            // sharpening's floor round the bright edge, its grid's corners; the glow (or the sky round it) says
            int pi = k % W, pj = k / W;
            double rOut = Math.hypot(pi - res.moonCx, pj - res.moonCy) - res.moonR, c = SKY_CLEAR * S;
            // (nearer the edge, only where the stack is faint there: its sharp edge's own tail stays)
            double faint = Math.max(0, Math.min(1, (3 * MoonStack.RL_FLOOR - ms[k]) / (2 * MoonStack.RL_FLOOR)));
            double sw0 = ramp(rOut, 0.5 * c, c) * Math.max(faint, ramp(rOut, c, 1.5 * c));
            skyW[k] = (float) sw0;
            boolean sky = sw0 > 0;
            double gU = GLARE_DOUBT * egl[k];
            double we = fade[k] > 0 ? fade[k] / (sigE * sigE + gU * gU) : 0;
            double wf = 0;
            // the fill: from close round where there's enough, else from wider
            float tn = Math.min(1f, tFill[k] / 0.3f), tf = Math.max(tFill[k], tFillW[k]);
            fillV[k] = tn * eYc[k] + (1 - tn) * eYw[k];
            if (fade[k] < 1 && (hiY[k] <= FILL_HI * sigM || sky) && tf > 0.05f) {
                double ex = hiY[k] <= 0 ? 0 : (1 - sw0) * Math.max(0, ms[k] - fillV[k] - hiS[k] - 2 * sigMs - MoonStack.RL_FLOOR);
                wf = (1 - fade[k]) * Math.min(1, tf / 0.3) / (prior * prior + ex * ex);
            }
            if (sky && (we > 0 || wf > 0)) wm *= 1 - sw0;
            double sw = wm + we + wf;
            if (sw > 0) {
                am[k] = (float) (wm / sw);
                ae[k] = (float) (we / sw);
                af[k] = (float) (wf / sw);
            } else {
                am[k] = 1;
            }
        }
        // the shares smoothed a little where they change (the stack's noise flips its pixels one way or the other
        // along the terminator: jagged, speckled), but not into the sunlit crescent, which stays the stack's own
        float[] amS = MoonStack.blur(am, W, H, MIX_SMOOTH * S), aeS = MoonStack.blur(ae, W, H, MIX_SMOOTH * S),
                afS = MoonStack.blur(af, W, H, MIX_SMOOTH * S);
        // the stack's faint parts, where it has a share (the noise there, and its sharpening's specks, would show
        // once the night side is lifted): smoothed, in the glow's colour; its sunlit parts its own. Smoothed from its
        // faint pixels alone: the bright edge's and the terminator's light, spread past them, would ring them with a
        // band brighter than the stack (and of the glow's colour there, the bleed's: purple), lifted bright
        float[] faintW = new float[NP];
        for (int k = 0; k < NP; k++) faintW[k] = (float) Math.max(0, Math.min(1, (6 * MoonStack.RL_FLOOR - mY[k]) / (4 * MoonStack.RL_FLOOR)));
        float[] mYs = trusted(mY, faintW, W, H, MAIN_SMOOTH * S);
        // the night side's pixels, for its level
        float[] night = new float[NP];
        float[] nightGlare = new float[NP];
        int nn = 0;
        double rr2 = Math.pow(0.88 * Rout, 2);
        for (int k = 0; k < NP; k++) {
            double sm = amS[k], se = aeS[k], sf = afS[k];
            if (am[k] >= 1f && mY[k] >= SUNLIT) {
                sm = 1;
                se = sf = 0;
            }
            double tot = sm + se + sf;
            if (tot > 0) {
                sm /= tot;
                se /= tot;
                sf /= tot;
            }
            double sk = skyW[k];
            double raw = Math.max(0, Math.min(1, (ms[k] - 2 * MoonStack.RL_FLOOR) / (4 * MoonStack.RL_FLOOR)));
            for (int ch = 0; ch < 3; ch++) {
                double ev = (1 - sk) * hi[ch][k] + eYs[k] * chroma[ch][k], fv = (1 - sk) * hi[ch][k] + fillV[k] * chroma[ch][k];
                // (the sky's share of the stack, come from its neighbours' smoothed shares, takes the sky's fill, grey:
                // the sky has no Moon's colour)
                double mv = (1 - sk) * (raw * mrgb[ch][k] + (1 - raw) * mYs[k] * chroma[ch][k]) + sk * fillV[k];
                orgb[ch][k] = (float) (sm * mv + se * ev + sf * fv);
            }
            int pi = k % W, pj = k / W;
            double dx = pi - main.cx, dy = pj - main.cy;
            if (dx * dx + dy * dy < rr2 && ae[k] > 0.95f && hiY[k] <= 0) {
                night[nn] = eYs[k];
                nightGlare[nn] = (float) (egl[k] / Math.max(1e-12, egl[k] + Math.max(0, eYs[k])));
                nn++;
            }
        }
        res.w = W;
        res.h = H;
        res.r = outR;
        res.g = outG;
        res.b = outB;
        // the photo's black: the merged sky's own noise, three times over
        res.black = 3 * ringNoise(MoonStack.lum(orgb), W, H, main.cx, main.cy, Rout, ecf, Float.MAX_VALUE);
        if (!(res.black > 0)) res.black = 3 * sigE;
        if (nn < 300) {
            res.log = log.append(String.format(Locale.US, "; only %d pixels of night side, nothing merged", nn)).toString();
            return res;
        }
        float[] ns = Arrays.copyOf(night, nn), gs = Arrays.copyOf(nightGlare, nn);
        Arrays.sort(ns);
        Arrays.sort(gs);
        res.level = ns[nn / 2];
        res.glare = gs[nn / 2];
        res.snr = res.level / sigE;
        log.append(String.format(Locale.US, "; the night side at %.3g of the bright parts (1 in %.0f), %.1f times its noise, the glare %.0f%% of "
                        + "it before it was taken off; the noise: the stack's %.2g, the glow's %.2g", res.level, res.level > 0 ? 1 / res.level : 0,
                res.snr, res.glare * 100, sigM, sigE));
        res.ok = res.level > 0 && res.snr >= 3;
        if (!res.ok) log.append("; too faint to show");
        res.log = log.toString();
        pr.update("The Earth's glow: done", 1f);
        return res;
    }

    // ------------------------------------------------------------------------------------------------
    // the pieces

    /**
     * Where the stack's crescent lies on the glow's burnt one: {x, y (where the stack's centre is on the glow's average),
     * turn (radians), the match}. The stack's crescent is taken as burnt as the glow's (the brightness that burns as
     * many pixels, a soft edge either side of it) and matched to the glow frames' burnt share near the crescent (their
     * correlation), coarsely then finely; null if there is too little of it.
     */
    static double[] register(float[] cf, boolean[] burnt, float[] dist, int gw, int gh, double cx, double cy, float[] mY, MoonStack.Result main,
                             double beta0) {
        int nb = 0;
        for (boolean b : burnt) if (b) nb++;
        double S = main.gridS, psi = main.gridPsi, cps = Math.cos(psi), sps = Math.sin(psi);
        float[] sorted = mY.clone();
        Arrays.sort(sorted);
        int at = sorted.length - 1 - (int) Math.round(nb * S * S);
        if (nb < 20 || at < 0) return null;
        double tB = sorted[at];
        if (!(tB > 0)) return null;
        float[] pm = new float[mY.length];
        for (int k = 0; k < pm.length; k++) pm[k] = (float) Math.max(0, Math.min(1, (mY[k] - 0.6 * tB) / (0.8 * tB)));
        int[] idx = new int[gw * gh];
        int n = 0;
        for (int k = 0; k < idx.length; k++) if (dist[k] <= 6) idx[n++] = k;
        if (n < 50) return null;
        double[] p = {cx, cy, beta0, maskCorr(cf, idx, n, gw, cx, cy, beta0, pm, main, cps, sps, S)};
        // coarse: 3 px and 4 degrees either way; then finer, twice
        double[][] steps = {{0.5, 3, Math.toRadians(0.5), Math.toRadians(4)}, {0.125, 0.5, Math.toRadians(0.125), Math.toRadians(0.5)},
                {0.03125, 0.125, Math.toRadians(0.03125), Math.toRadians(0.125)}};
        for (double[] st : steps) {
            double bx = p[0], by = p[1], bb = p[2], bv = Double.NEGATIVE_INFINITY;
            int nx = (int) Math.round(st[1] / st[0]), na = (int) Math.round(st[3] / st[2]);
            for (int a = -na; a <= na; a++) {
                double b = p[2] + a * st[2];
                for (int j = -nx; j <= nx; j++) {
                    for (int i = -nx; i <= nx; i++) {
                        double x = p[0] + i * st[0], y = p[1] + j * st[0];
                        double v = maskCorr(cf, idx, n, gw, x, y, b, pm, main, cps, sps, S);
                        if (v > bv) {
                            bv = v;
                            bx = x;
                            by = y;
                            bb = b;
                        }
                    }
                }
            }
            p = new double[]{bx, by, bb, bv};
        }
        return p;
    }

    /** The correlation of the glow's burnt share with the stack's crescent put at (x, y) and turned by {@code b}. */
    static double maskCorr(float[] cf, int[] idx, int n, int gw, double x0, double y0, double b, float[] pm, MoonStack.Result main, double cps,
                           double sps, double S) {
        double cb = Math.cos(b), sb = Math.sin(b);
        double sa = 0, sp = 0, saa = 0, spp = 0, sap = 0;
        for (int q = 0; q < n; q++) {
            int k = idx[q];
            double dx = k % gw - x0, dy = k / gw - y0;
            double mx = cb * dx + sb * dy, my = -sb * dx + cb * dy;
            double gx = main.cx + S * (cps * mx + sps * my), gy = main.cy + S * (-sps * mx + cps * my);
            double pv = gx < 0 || gy < 0 || gx > main.w - 1 || gy > main.h - 1 ? 0 : MoonStack.sample(pm, main.w, main.h, gx, gy);
            double a = cf[k];
            sa += a;
            sp += pv;
            saa += a * a;
            spp += pv * pv;
            sap += a * pv;
        }
        double ma = sa / n, mp = sp / n;
        double va = saa / n - ma * ma, vp = spp / n - mp * mp;
        return va > 0 && vp > 0 ? (sap / n - ma * mp) / Math.sqrt(va * vp) : 0;
    }

    /**
     * The stack's bright light: each colour where its (lightly smoothed) brightness is past {@code hiAt}, none of it
     * below {@code loAt}, a smooth step between, in the patches that are somewhere past {@code burnAt}: the crescent
     * (the biggest), and any other only past the stack's own specks by far and away from its edge (its sharpening
     * makes the noise on the night side and in the sky sharp and bright here and there, its edge most; the brightest
     * glow's white level is lower than they reach, and a speck taken for light would be taken off the glow as its
     * bleed: a black hole round a white dot).
     */
    static float[][] bright(float[][] rgb, float[] Yl, int w, int h, double loAt, double hiAt, double burnAt) {
        float[] ys = MoonStack.blur(Yl, w, h, 1.0);
        float[][] out = new float[3][w * h];
        // only the patches that burn in the glow's frames: the crescent, a peak in the sunrise past the terminator
        boolean[] on = new boolean[ys.length];
        for (int k = 0; k < ys.length; k++) on[k] = ys[k] > loAt;
        int[] lab = new int[ys.length];
        int[] size = patches(on, w, h, lab);
        int big = 0;
        for (int i = 1; i < size.length; i++) if (size[i] > size[big]) big = i;
        // the stack's noise off the crescent (where it has frames), and where it ends: its edge, the frames' last
        // pixels, sharpened against nothing, is its noisiest
        boolean[] none = new boolean[ys.length];
        float[] off = new float[ys.length];
        int m = 0;
        for (int k = 0; k < ys.length; k++) {
            int x = k % w, y = k / w;
            none[k] = x == 0 || y == 0 || x == w - 1 || y == h - 1 || (rgb[0][k] == 0f && rgb[1][k] == 0f && rgb[2][k] == 0f);
            if (lab[k] != big && !none[k]) off[m++] = Yl[k];
        }
        float[] toEdge = distance(none, w, h);
        double sure = burnAt;
        if (m >= 1000) {
            Arrays.sort(off, 0, m);
            sure = Math.max(burnAt, SPECK * off[(int) (SPECK_AT * (m - 1))]);
        }
        boolean[] burns = new boolean[size.length], edge = new boolean[size.length];
        for (int k = 0; k < ys.length; k++) {
            if (lab[k] == 0) continue;
            if (Yl[k] >= (lab[k] == big ? burnAt : sure)) burns[lab[k]] = true;
            if (toEdge[k] <= SPECK_EDGE) edge[lab[k]] = true;
        }
        for (int i = 1; i < size.length; i++) if (i != big && edge[i]) burns[i] = false;
        for (int k = 0; k < ys.length; k++) {
            if (lab[k] == 0 || !burns[lab[k]]) continue;
            double t = (ys[k] - loAt) / (hiAt - loAt);
            if (t <= 0) continue;
            double s = t >= 1 ? 1 : t * t * (3 - 2 * t);
            for (int c = 0; c < 3; c++) out[c][k] = (float) (s * Math.max(0, rgb[c][k]));
        }
        return out;
    }

    /** A picture on the stack's grid put onto the glow's average (the stack's centre at (tx, ty), turned by {@code beta}); 0 off it. */
    static float[] mapped(float[] img, MoonStack.Result main, double tx, double ty, double beta, int gw, int gh) {
        double S = main.gridS, cps = Math.cos(main.gridPsi), sps = Math.sin(main.gridPsi), cb = Math.cos(beta), sb = Math.sin(beta);
        float[] out = new float[gw * gh];
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                double dx = x - tx, dy = y - ty;
                // into the stack's reference crop (the turn undone), then onto its grid
                double mx = cb * dx + sb * dy, my = -sb * dx + cb * dy;
                double gx = main.cx + S * (cps * mx + sps * my), gy = main.cy + S * (-sps * mx + cps * my);
                if (gx < 0 || gy < 0 || gx > main.w - 1 || gy > main.h - 1) continue;
                out[y * gw + x] = Math.max(0, MoonStack.sample(img, main.w, main.h, gx, gy));
            }
        }
        return out;
    }

    /** A picture smoothed from its trusted pixels only (each pixel's trust its weight); 0 where none are near. */
    static float[] trusted(float[] img, float[] trust, int w, int h, double sigma) {
        float[] num = new float[img.length];
        for (int k = 0; k < img.length; k++) num[k] = img[k] * trust[k];
        float[] a = MoonStack.blur(num, w, h, sigma), b = MoonStack.blur(trust, w, h, sigma);
        float[] out = new float[img.length];
        for (int k = 0; k < img.length; k++) out[k] = b[k] > 1e-3f ? a[k] / b[k] : 0;
        return out;
    }

    /**
     * Each colour's blur from the development past the least blurred one's (sensor px squared): a colour with one
     * sample in a 2x2 block is put at its middle from its four nearest, 9:3:3:1, a spread of 0.75 each way; with two
     * (on the diagonal), their mean, 0.25.
     */
    static double[] devBlur(SensorSpec spec) {
        int[] n = new int[3];
        for (int c : spec.cfa) n[c]++;
        double[] v = new double[3];
        double least = Double.MAX_VALUE;
        for (int c = 0; c < 3; c++) {
            v[c] = n[c] >= 2 ? 0.25 : 0.75;
            least = Math.min(least, v[c]);
        }
        for (int c = 0; c < 3; c++) v[c] -= least;
        return v;
    }

    /** 0 below a, 1 above b, smoothly between. */
    static double ramp(double v, double a, double b) {
        double t = (v - a) / (b - a);
        return t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3 - 2 * t);
    }

    static String shares(double[] v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) b.append(i > 0 ? ", " : "").append(String.format(Locale.US, "%.3f", v[i]));
        return b.toString();
    }

    /** A picture's pixel noise on the given points (from neighbours' differences, robustly). */
    static double noiseOf(float[] img, int[] pts, int w) {
        float[] d = new float[pts.length];
        int m = 0;
        for (int k : pts) if (k + 1 < img.length && (k + 1) % w != 0) d[m++] = Math.abs(img[k] - img[k + 1]);
        if (m < 20) return Double.NaN;
        float[] s = Arrays.copyOf(d, m);
        Arrays.sort(s);
        return s[m / 2] * 1.4826 / Math.sqrt(2);
    }

    /** The burnt crescent's bleed, fitted (see {@link #spill}). */
    static final class Spill {
        /** Where the stack's centre lies on the glow's average, and its turn (radians). */
        double tx, ty, beta;
        /** The bleed: how much of the stack's bright light each of the SPILL spreads takes (their sum about 1). */
        double[] share;
        /** Their sum, and the spread they make together (sigma, sensor px: their shares' mean square's root). */
        double a, sigma;
        /** The fit's residual (in the noise, weighted), and where it started (the centres and the tilt). */
        double rms, rmsStart;
        /** The pixels fitted, and those left out (a star, a hot pixel). */
        int n, out;
        boolean ok;
    }

    /**
     * The burnt crescent's bleed. In the glow's frames the crescent is dozens of times the white level, and their
     * blur (the hand's, every frame its own, and the 2x2 blocks', all wider than the stack's sharpened one) spreads
     * its edge over the pixels round the burnt part, the night side's next to the terminator too, far brighter there
     * than the glow. The stack holds that light unburnt: its bright parts spread by each of the SPILL spreads, in
     * whatever shares fit (none below 0: the frames' blurs are a mix), are matched to the glow's average within NEAR
     * of the burnt part (with the glow's own level on the Moon and a sky with a slope fitted alongside), the stack
     * shifted (and, with no tilt to go by, turned) over the glow, coarsely then finely: the shares and the place that
     * fit best. Null if there's too little.
     */
    static Spill spill(float[] Y, float[] cf, float[] cnt, int used, float[] dist, int gw, int gh, double cx, double cy, double R, float[][] hi,
                       double[] extra, MoonStack.Result main, double white, double noise, double tx0, double ty0, double beta0, boolean turnFree) {
        int np = gw * gh;
        int[] idx = new int[np];
        int n = 0;
        // the pixels fitted: near the burnt part, never burnt themselves, where the stack's light spread by the widest
        // bleed stays well under the white level. Chosen by the model, not by what the pixels show: keeping only the
        // ones that came out dark would leave out the bright ones the bleed made, and fit too little of it.
        int nl0 = CORE - 1;
        float[][] w3 = new float[3][];
        for (int ch = 0; ch < 3; ch++) w3[ch] = MoonStack.blur(hi[ch], main.w, main.h, Math.sqrt(SPILL[nl0] * SPILL[nl0] + extra[ch]) * main.gridS);
        float[] wide = mapped(MoonStack.lum(w3), main, tx0, ty0, beta0, gw, gh);
        for (int y = 2; y < gh - 2; y++) {
            for (int x = 2; x < gw - 2; x++) {
                int k = y * gw + x;
                if (dist[k] <= 0 || dist[k] > NEAR || cf[k] > 0 || cnt[k] < 0.5 * used || wide[k] > REGION * white) continue;
                idx[n++] = k;
            }
        }
        if (n < 100) return null;
        int nl = SPILL.length;
        Fitter ft = new Fitter(n, nl, main);
        double s0 = noise > 0 ? noise : 1e-6;
        for (int q = 0; q < n; q++) {
            int k = idx[q], x = k % gw, y = k / gw;
            ft.F[0][q] = Math.max(0, Math.min(1, R - Math.hypot(x - cx, y - cy) + 0.5));
            ft.F[1][q] = 1;
            ft.F[2][q] = (x - cx) / R;
            ft.F[3][q] = (y - cy) / R;
            ft.v[q] = Y[k];
            // the glow's noise, and the model's own doubt (a share of the light): each pixel's weight
            ft.wt[q] = 1 / (s0 * s0 + Math.pow(0.03 * Math.max(0, Y[k]), 2));
            ft.px[q] = x;
            ft.py[q] = y;
        }
        ft.normalise();
        ft.fixed();
        float[][] L = new float[nl][];
        for (int i = 0; i < nl; i++) {
            float[][] c3 = new float[3][];
            for (int ch = 0; ch < 3; ch++) c3[ch] = MoonStack.blur(hi[ch], main.w, main.h, Math.sqrt(SPILL[i] * SPILL[i] + extra[ch]) * main.gridS);
            L[i] = MoonStack.lum(c3);
        }
        ft.at(beta0);
        double chiStart = ft.chi(L, tx0, ty0)[0];
        // coarse: 3 px either way in half pixels (and 2 degrees either way, with no tilt); the pixels far off the
        // best left out (a star, a hot pixel); then a tenth of a pixel, then a fortieth
        double[][] stages = {{0.5, 6, 0.5, 4}, {0.1, 5, 0.125, 4}, {0.025, 4, 0.03125, 4}};
        double[] best = {Double.POSITIVE_INFINITY, tx0, ty0, beta0};
        double[] fit = null;
        int out = 0;
        for (int si = 0; si < stages.length; si++) {
            double[] st = stages[si], prev = best.clone();
            best[0] = Double.POSITIVE_INFINITY;
            int nbs = turnFree ? (int) st[3] : 0;
            for (int bi = -nbs; bi <= nbs; bi++) {
                double b = prev[3] + Math.toRadians(st[2] * bi);
                ft.at(b);
                for (int j = -(int) st[1]; j <= st[1]; j++) {
                    for (int i = -(int) st[1]; i <= st[1]; i++) {
                        double x = prev[1] + st[0] * i, y = prev[2] + st[0] * j;
                        double[] c = ft.chi(L, x, y);
                        if (c[0] < best[0]) {
                            best = new double[]{c[0], x, y, b};
                            fit = c;
                        }
                    }
                }
            }
            if (fit == null || !(best[0] < Double.POSITIVE_INFINITY)) return null;
            if (si == 0) {
                ft.at(best[3]);
                out = ft.trim(L, best[1], best[2]);
                fit = ft.chi(L, best[1], best[2]);
                best[0] = fit[0];
            }
        }
        Spill sp = new Spill();
        sp.tx = best[1];
        sp.ty = best[2];
        sp.beta = best[3];
        sp.share = Arrays.copyOfRange(fit, 1, 1 + nl);
        double s2 = 0;
        for (int i = 0; i < CORE; i++) {
            sp.a += sp.share[i];
            s2 += sp.share[i] * SPILL[i] * SPILL[i];
        }
        sp.sigma = sp.a > 0 ? Math.sqrt(s2 / sp.a) : Double.NaN;
        sp.n = n - out;
        sp.out = out;
        sp.rms = Math.sqrt(Math.max(0, best[0]) * ft.wscale / Math.max(1, n - out));
        sp.rmsStart = Math.sqrt(Math.max(0, chiStart) * ft.wscale / n);
        // a fit worth trusting: the bleed about as bright as the stack's light, the place inside the search
        sp.ok = sp.a > 0.5 && sp.a < 2 && Math.abs(sp.tx - tx0) < 2.9 && Math.abs(sp.ty - ty0) < 2.9;
        return sp;
    }

    /**
     * The bleed's weighted least squares: its columns (the stack's light spread by each spread, put in place; none
     * taken below 0) and the four fixed ones (the glow's level on the Moon, the sky, its two slopes).
     */
    static final class Fitter {
        static final int NF = 4;
        final int n, nl;
        final MoonStack.Result main;
        final double[][] F;
        final double[] v, wt, px, py, gx0, gy0;
        final double[] FF = new double[NF * NF], Fv = new double[NF];
        double vv, wscale = 1, cb = 1, sb = 0;

        Fitter(int n, int nl, MoonStack.Result main) {
            this.n = n;
            this.nl = nl;
            this.main = main;
            F = new double[NF][n];
            v = new double[n];
            wt = new double[n];
            px = new double[n];
            py = new double[n];
            gx0 = new double[n];
            gy0 = new double[n];
        }

        /** The weights to a mean of 1 (the sums stay of a size). */
        void normalise() {
            double s = 0;
            for (double w : wt) s += w;
            for (int q = 0; q < n; q++) wt[q] *= n / s;
            wscale = s / n;
        }

        /** The fixed columns' sums (again after a trim). */
        void fixed() {
            Arrays.fill(FF, 0);
            Arrays.fill(Fv, 0);
            vv = 0;
            for (int q = 0; q < n; q++) {
                double w = wt[q];
                if (w == 0) continue;
                vv += w * v[q] * v[q];
                for (int a = 0; a < NF; a++) {
                    double fa = w * F[a][q];
                    Fv[a] += fa * v[q];
                    for (int b = a; b < NF; b++) FF[a * NF + b] += fa * F[b][q];
                }
            }
            for (int a = 0; a < NF; a++) for (int b = 0; b < a; b++) FF[a * NF + b] = FF[b * NF + a];
        }

        /** The turn: each pixel's place on the stack's grid with the stack's centre at 0,0 on the glow's. */
        void at(double beta) {
            cb = Math.cos(beta);
            sb = Math.sin(beta);
            double S = main.gridS, cps = Math.cos(main.gridPsi), sps = Math.sin(main.gridPsi);
            for (int q = 0; q < n; q++) {
                double mx = cb * px[q] + sb * py[q], my = -sb * px[q] + cb * py[q];
                gx0[q] = main.cx + S * (cps * mx + sps * my);
                gy0[q] = main.cy + S * (-sps * mx + cps * my);
            }
        }

        double[] offset(double tx, double ty) {
            double S = main.gridS, cps = Math.cos(main.gridPsi), sps = Math.sin(main.gridPsi);
            double u = cb * tx + sb * ty, w = -sb * tx + cb * ty;
            return new double[]{S * (cps * u + sps * w), S * (-sps * u + cps * w)};
        }

        /** The bleed's columns at pixel q for the stack's centre offset by o (into col). */
        void cols(float[][] L, int q, double[] o, double[] col) {
            double gx = gx0[q] - o[0], gy = gy0[q] - o[1];
            if (gx < 0 || gy < 0 || gx > main.w - 1 || gy > main.h - 1) {
                Arrays.fill(col, 0);
                return;
            }
            for (int i = 0; i < nl; i++) col[i] = MoonStack.sample(L[i], main.w, main.h, gx, gy);
        }

        /**
         * {chi squared, the spreads' shares (nl), the fixed columns' (4)} with the stack's centre at (tx, ty); the
         * chi squared infinite if there's no bleed.
         */
        double[] chi(float[][] L, double tx, double ty) {
            double[] o = offset(tx, ty);
            int m = nl + NF;
            double[] A = new double[m * m], b = new double[m], col = new double[nl];
            for (int q = 0; q < n; q++) {
                double w = wt[q];
                if (w == 0) continue;
                cols(L, q, o, col);
                for (int i = 0; i < nl; i++) {
                    double wl = w * col[i];
                    if (wl == 0) continue;
                    b[i] += wl * v[q];
                    for (int j = i; j < nl; j++) A[i * m + j] += wl * col[j];
                    for (int a = 0; a < NF; a++) A[i * m + nl + a] += wl * F[a][q];
                }
            }
            for (int a = 0; a < NF; a++) {
                b[nl + a] = Fv[a];
                for (int c = 0; c < NF; c++) A[(nl + a) * m + nl + c] = FF[a * NF + c];
            }
            for (int i = 0; i < m; i++) for (int j = 0; j < i; j++) A[i * m + j] = A[j * m + i];
            // the bleed's columns scaled to the sky's size, for the elimination
            double[] sc = new double[m];
            Arrays.fill(sc, 1);
            for (int i = 0; i < nl; i++) sc[i] = A[i * m + i] > 0 ? Math.sqrt(FF[NF + 1] / A[i * m + i]) : 0;
            boolean[] on = new boolean[m];
            Arrays.fill(on, true);
            for (int i = 0; i < nl; i++) on[i] = sc[i] > 0;
            double[] x = null;
            for (int guard = 0; guard <= nl; guard++) {
                int[] id = new int[m];
                int k = 0;
                for (int i = 0; i < m; i++) if (on[i]) id[k++] = i;
                double[] As = new double[k * k], bs = new double[k];
                for (int i = 0; i < k; i++) {
                    bs[i] = b[id[i]] * sc[id[i]];
                    for (int j = 0; j < k; j++) As[i * k + j] = A[id[i] * m + id[j]] * sc[id[i]] * sc[id[j]];
                }
                double[] xs = MoonStack.solve(As, bs, k);
                if (xs == null) return new double[]{Double.POSITIVE_INFINITY};
                x = new double[m];
                for (int i = 0; i < k; i++) x[id[i]] = xs[i] * sc[id[i]];
                int worst = -1;
                for (int i = 0; i < nl; i++) if (on[i] && x[i] < 0 && (worst < 0 || x[i] < x[worst])) worst = i;
                if (worst < 0) break;
                on[worst] = false;
                x[worst] = 0;
            }
            double sl = 0;
            for (int i = 0; i < nl; i++) sl += x[i];
            if (!(sl > 0)) return new double[]{Double.POSITIVE_INFINITY};
            double chi = vv;
            // the residual's square sum: v'Wv - 2 x'b + x'Ax
            for (int i = 0; i < m; i++) {
                chi -= 2 * x[i] * b[i];
                for (int j = 0; j < m; j++) chi += x[i] * A[i * m + j] * x[j];
            }
            double[] outv = new double[1 + m];
            outv[0] = chi;
            System.arraycopy(x, 0, outv, 1, m);
            return outv;
        }

        /** The pixels more than four times the fit's spread off it left out (their weight to 0); how many. */
        int trim(float[][] L, double tx, double ty) {
            double[] c = chi(L, tx, ty);
            if (c.length < 1 + nl + NF) return 0;
            double[] o = offset(tx, ty), col = new double[nl];
            float[] r = new float[n];
            double[] res = new double[n];
            for (int q = 0; q < n; q++) {
                cols(L, q, o, col);
                double model = 0;
                for (int i = 0; i < nl; i++) model += c[1 + i] * col[i];
                for (int a = 0; a < NF; a++) model += c[1 + nl + a] * F[a][q];
                res[q] = (v[q] - model) * Math.sqrt(wt[q]);
                r[q] = (float) Math.abs(res[q]);
            }
            Arrays.sort(r);
            double sig = r[n / 2] * 1.4826;
            int out = 0;
            for (int q = 0; q < n; q++) {
                if (Math.abs(res[q]) > 4 * sig) {
                    wt[q] = 0;
                    out++;
                }
            }
            fixed();
            return out;
        }
    }

    /** The sky and the glare on the glow's average. */
    static final class Glare {
        /** The halo and the sky, each colour (the stack's units; the bleed not in it). */
        float[][] rest;
        /** All of it, the bleed too (luminance), and the halo alone. */
        float[] bgY, haloY;
        double noise;
        String halo = "";
    }

    /**
     * The glare and the sky on the glow's average: the bleed ({@code Lc}, the stack's bright light spread as the
     * glow's frames spread it, scaled by {@code a}) and the halo (the stack's light, put where it lies on the glow's
     * average at (tx, ty) turned by {@code beta}, spread by the halo's five spreads) fitted on the sky's points in
     * luminance, so every colour's glare is its own light's under the same lens; each colour's sky then on its own.
     */
    static Glare glare(float[][] E, float[][] mrgb, float[][] Lc, double a, MoonStack.Result main, double tx, double ty, double beta, int gw, int gh,
                       int[] pts, double cx, double cy, double R) {
        int np = gw * gh;
        double[] wl = {0.2126, 0.7152, 0.0722};
        float[][] lg = new float[3][], src = new float[3][];
        for (int ch = 0; ch < 3; ch++) {
            lg[ch] = mapped(Lc[ch], main, tx, ty, beta, gw, gh);
            src[ch] = mapped(mrgb[ch], main, tx, ty, beta, gw, gh);
        }
        float[] srcY = MoonStack.lum(src), lgY = MoonStack.lum(lg), EY = MoonStack.lum(E);
        float[][] basis = new float[HALO.length][];
        for (int h = 0; h < HALO.length; h++) basis[h] = blurBig(srcY, gw, gh, HALO[h]);
        float[] v = new float[np];
        for (int k = 0; k < np; k++) v[k] = (float) (EY[k] - a * lgY[k]);
        double[] coef = fitSky(v, basis, pts, gw, cx, cy, R);
        Glare g = new Glare();
        g.rest = new float[3][];
        g.bgY = new float[np];
        g.haloY = new float[np];
        float[][] none = new float[0][];
        for (int ch = 0; ch < 3; ch++) {
            float[] hal = new float[np];
            for (int h = 0; h < HALO.length; h++) {
                if (coef[h] == 0) continue;
                float[] bb = blurBig(src[ch], gw, gh, HALO[h]);
                for (int k = 0; k < np; k++) hal[k] += (float) (coef[h] * bb[k]);
            }
            float[] rv = new float[np];
            for (int k = 0; k < np; k++) rv[k] = (float) (E[ch][k] - hal[k] - a * lg[ch][k]);
            double[] pc = fitSky(rv, none, pts, gw, cx, cy, R);
            float[] rest = new float[np];
            for (int k = 0; k < np; k++) {
                int x = k % gw, y = k / gw;
                rest[k] = (float) (hal[k] + pc[0] + pc[1] * (x - cx) / R + pc[2] * (y - cy) / R);
                g.bgY[k] += (float) (wl[ch] * (rest[k] + a * lg[ch][k]));
                g.haloY[k] += (float) (wl[ch] * hal[k]);
            }
            g.rest[ch] = rest;
            g.noise += wl[ch] * pc[3];
        }
        StringBuilder halo = new StringBuilder();
        for (int h = 0; h < HALO.length; h++) halo.append(h > 0 ? ", " : "").append(String.format(Locale.US, "%.2g", coef[h]));
        g.halo = halo.toString();
        return g;
    }

    /**
     * The Moon's centre on the stack's grid from its bright edge, the radius {@code r} known: along each direction from
     * the guessed centre (cx, cy), where the brightness falls halfway from the lit limb's to the sky's (unbiased by the
     * blur), and the circle of that radius through those points (least squares, the far-off ones left out; again from
     * the centre found). {dx, dy (from the guess), the points, their spread}; null if too little of the limb is lit.
     */
    static double[] limbCentre(float[] Y, int w, int h, double cx, double cy, double r, double S) {
        int na = 720;
        double[] ex = new double[na], ey = new double[na];
        double ox = 0, oy = 0, spread = 0;
        int m = 0;
        for (int round = 0; round < 2; round++) {
            double gx = cx + ox, gy = cy + oy;
            m = 0;
            for (int a = 0; a < na; a++) {
                double t = 2 * Math.PI * a / na, ux = Math.cos(t), uy = Math.sin(t);
                // the lit limb's brightness (the brightest just inside: past the terminator, which can be near), the sky's
                double top = 0, sky = 0;
                int ns = 0;
                for (double d = r - 4 * S; d <= r - 1.5 * S; d += 0.25) top = Math.max(top, MoonStack.sample(Y, w, h, gx + ux * d, gy + uy * d));
                for (double d = r + 3 * S; d <= r + 5 * S; d += 0.25, ns++) sky += MoonStack.sample(Y, w, h, gx + ux * d, gy + uy * d);
                sky /= Math.max(1, ns);
                if (!(top > LIMB_LIT) || top < 10 * Math.abs(sky)) continue;
                double mid = 0.5 * (top + sky), at = Double.NaN, prev = Double.NaN;
                // the last fall through halfway, outward (the limb is the lit part's outer edge)
                for (double d = r - 3 * S; d <= r + 3 * S; d += 0.125) {
                    double v = MoonStack.sample(Y, w, h, gx + ux * d, gy + uy * d);
                    if (prev >= mid && v < mid) at = d - 0.125 * (mid - v) / (prev - v);
                    prev = v;
                }
                if (Double.isNaN(at)) continue;
                ex[m] = gx + ux * at;
                ey[m] = gy + uy * at;
                m++;
            }
            if (m < 40) return null;
            // the centre: Gauss-Newton on the points' distances from it, those far off the circle left out
            double px = gx, py = gy;
            boolean[] keep = new boolean[m];
            Arrays.fill(keep, true);
            for (int it = 0; it < 12; it++) {
                double h00 = 0, h01 = 0, h11 = 0, g0 = 0, g1 = 0, ss = 0;
                int nk = 0;
                for (int q = 0; q < m; q++) {
                    double dx = ex[q] - px, dy = ey[q] - py, dd = Math.hypot(dx, dy);
                    if (dd < 1e-9) continue;
                    double e = dd - r;
                    if (it >= 4) keep[q] = Math.abs(e) < Math.max(0.5 * S, 3 * spread);
                    if (!keep[q]) continue;
                    double jx = -dx / dd, jy = -dy / dd;
                    h00 += jx * jx;
                    h01 += jx * jy;
                    h11 += jy * jy;
                    g0 += jx * e;
                    g1 += jy * e;
                    ss += e * e;
                    nk++;
                }
                double det = h00 * h11 - h01 * h01;
                if (nk < 30 || !(det > 1e-9)) return null;
                spread = Math.sqrt(ss / nk);
                px -= (h11 * g0 - h01 * g1) / det;
                py -= (h00 * g1 - h01 * g0) / det;
            }
            ox = px - cx;
            oy = py - cy;
        }
        return new double[]{ox, oy, m, spread};
    }

    /** How much brighter it is just inside a circle than just outside it, over the arc's directions (the average). */
    static double limb(float[] Y, int w, int h, double cx, double cy, double r, double[] ct, double[] st, int m, double[] per) {
        double s = 0;
        for (int t = 0; t < m; t++) {
            double ux = ct[t], uy = st[t], d = 0;
            for (int k = 1; k <= 3; k++) {
                d += MoonStack.sample(Y, w, h, cx + ux * (r - k), cy + uy * (r - k)) - MoonStack.sample(Y, w, h, cx + ux * (r + k), cy + uy * (r + k));
            }
            d /= 3;
            if (per != null) per[t] = d;
            s += d;
        }
        return s / m;
    }

    /** The centre (best[0], best[1]) with the most contrast within {@code span} of it, in steps of {@code step}. */
    static void search(float[] Y, int w, int h, double[] best, double r, double[] ct, double[] st, int m, double span, double step) {
        double bx = best[0], by = best[1], bv = Double.NEGATIVE_INFINITY;
        int nst = (int) Math.round(span / step);
        for (int a = -nst; a <= nst; a++) {
            for (int b = -nst; b <= nst; b++) {
                double x = best[0] + a * step, y = best[1] + b * step;
                double v = limb(Y, w, h, x, y, r, ct, st, m, null);
                if (v > bv) {
                    bv = v;
                    bx = x;
                    by = y;
                }
            }
        }
        best[0] = bx;
        best[1] = by;
        best[2] = bv;
    }

    /** A parabola through the contrast either side of the best centre, each way. */
    static void refine(float[] Y, int w, int h, double[] best, double r, double[] ct, double[] st, int m) {
        double q = 0.25;
        for (int axis = 0; axis < 2; axis++) {
            double x = best[0], y = best[1];
            double vm = limb(Y, w, h, x - (axis == 0 ? q : 0), y - (axis == 1 ? q : 0), r, ct, st, m, null);
            double v0 = limb(Y, w, h, x, y, r, ct, st, m, null);
            double vp = limb(Y, w, h, x + (axis == 0 ? q : 0), y + (axis == 1 ? q : 0), r, ct, st, m, null);
            double den = vm - 2 * v0 + vp;
            if (den < 0) {
                double off = 0.5 * (vm - vp) / den * q;
                if (Math.abs(off) <= q) best[axis] += off;
            }
        }
        best[2] = limb(Y, w, h, best[0], best[1], r, ct, st, m, null);
    }

    /** Distance (px, chamfer 3-4) from every pixel to the nearest set one; big where there is none. */
    static float[] distance(boolean[] set, int w, int h) {
        float big = 1e6f;
        float[] d = new float[w * h];
        for (int k = 0; k < d.length; k++) d[k] = set[k] ? 0 : big;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int k = y * w + x;
                float v = d[k];
                if (x > 0) v = Math.min(v, d[k - 1] + 3);
                if (y > 0) {
                    v = Math.min(v, d[k - w] + 3);
                    if (x > 0) v = Math.min(v, d[k - w - 1] + 4);
                    if (x < w - 1) v = Math.min(v, d[k - w + 1] + 4);
                }
                d[k] = v;
            }
        }
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                int k = y * w + x;
                float v = d[k];
                if (x < w - 1) v = Math.min(v, d[k + 1] + 3);
                if (y < h - 1) {
                    v = Math.min(v, d[k + w] + 3);
                    if (x < w - 1) v = Math.min(v, d[k + w + 1] + 4);
                    if (x > 0) v = Math.min(v, d[k + w - 1] + 4);
                }
                d[k] = v;
            }
        }
        for (int k = 0; k < d.length; k++) d[k] /= 3;
        return d;
    }

    /** A wide Gaussian blur, done on a coarser grid for the widest (the glare is smooth). */
    static float[] blurBig(float[] img, int w, int h, double sigma) {
        int f = Math.max(1, (int) (sigma / 8));
        if (f == 1) return MoonStack.blur(img, w, h, sigma);
        int sw = (w + f - 1) / f, sh = (h + f - 1) / f;
        float[] small = new float[sw * sh];
        float[] cnt = new float[sw * sh];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int q = (y / f) * sw + x / f;
                small[q] += img[y * w + x];
                cnt[q]++;
            }
        }
        for (int q = 0; q < small.length; q++) small[q] = cnt[q] > 0 ? small[q] / cnt[q] : 0;
        float[] sb = MoonStack.blur(small, sw, sh, sigma / f);
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            double sy = (y + 0.5) / f - 0.5;
            for (int x = 0; x < w; x++) {
                double sx = (x + 0.5) / f - 0.5;
                out[y * w + x] = MoonStack.sample(sb, sw, sh, sx, sy);
            }
        }
        return out;
    }

    /**
     * The glare's halo and the sky fitted on the sky's points: {a_1..a_5 (each spread's share, none below 0), the sky,
     * its slopes across and down (a radius), the fit's noise}. Points far off the fit (a star, a hot pixel) are left out
     * and it is fitted again.
     */
    static double[] fitSky(float[] v, float[][] basis, int[] pts, int w, double cx, double cy, double R) {
        int nb = basis.length, nu = nb + 3;
        boolean[] on = new boolean[nu];
        Arrays.fill(on, true);
        boolean[] keep = new boolean[pts.length];
        Arrays.fill(keep, true);
        double[] coef = new double[nu];
        double sigma = Double.NaN;
        for (int round = 0; round < 3; round++) {
            Arrays.fill(on, true);
            for (int guard = 0; guard < nb + 1; guard++) {
                double[] x = solveOn(v, basis, pts, keep, w, cx, cy, R, on);
                if (x == null) break;
                coef = x;
                int worst = -1;
                for (int k = 0; k < nb; k++) if (on[k] && coef[k] < 0 && (worst < 0 || coef[k] < coef[worst])) worst = k;
                if (worst < 0) break;
                on[worst] = false;
                coef[worst] = 0;
            }
            // the residuals, and what's far off them
            float[] res = new float[pts.length];
            int m = 0;
            for (int p = 0; p < pts.length; p++) {
                if (!keep[p]) continue;
                res[m++] = (float) Math.abs(v[pts[p]] - model(coef, basis, pts[p], w, cx, cy, R));
            }
            if (m < 50) break;
            float[] s = Arrays.copyOf(res, m);
            Arrays.sort(s);
            sigma = s[m / 2] * 1.4826;
            if (round == 2) break;
            for (int p = 0; p < pts.length; p++) keep[p] = Math.abs(v[pts[p]] - model(coef, basis, pts[p], w, cx, cy, R)) < 4 * sigma + 1e-12;
        }
        double[] out = Arrays.copyOf(coef, nu + 1);
        out[nu] = sigma;
        return out;
    }

    static double model(double[] coef, float[][] basis, int k, int w, double cx, double cy, double R) {
        int nb = basis.length;
        double s = 0;
        for (int b = 0; b < nb; b++) s += coef[b] * basis[b][k];
        return s + coef[nb] + coef[nb + 1] * (k % w - cx) / R + coef[nb + 2] * (k / w - cy) / R;
    }

    /** Least squares over the points kept, with only the unknowns that are on. */
    static double[] solveOn(float[] v, float[][] basis, int[] pts, boolean[] keep, int w, double cx, double cy, double R, boolean[] on) {
        int nb = basis.length, nu = nb + 3;
        int[] idx = new int[nu];
        int m = 0;
        for (int u = 0; u < nu; u++) if (on[u]) idx[m++] = u;
        double[] H = new double[m * m], g = new double[m], row = new double[nu];
        // scale the halo's columns so the system is well conditioned (they are tiny next to the sky's 1)
        double[] sc = new double[nu];
        Arrays.fill(sc, 1);
        for (int b = 0; b < nb; b++) {
            double mx = 0;
            for (int p = 0; p < pts.length; p++) mx = Math.max(mx, Math.abs(basis[b][pts[p]]));
            sc[b] = mx > 0 ? 1 / mx : 1;
        }
        int used = 0;
        for (int p = 0; p < pts.length; p++) {
            if (!keep[p]) continue;
            int k = pts[p];
            for (int b = 0; b < nb; b++) row[b] = basis[b][k] * sc[b];
            row[nb] = 1;
            row[nb + 1] = (k % w - cx) / R;
            row[nb + 2] = (k / w - cy) / R;
            for (int a = 0; a < m; a++) {
                double ra = row[idx[a]];
                g[a] += ra * v[k];
                for (int b = a; b < m; b++) H[a * m + b] += ra * row[idx[b]];
            }
            used++;
        }
        if (used < nu * 4) return null;
        for (int a = 0; a < m; a++) for (int b = 0; b < a; b++) H[a * m + b] = H[b * m + a];
        // a whisper of ridge, for spreads the sky can't tell apart
        for (int a = 0; a < m; a++) H[a * m + a] *= 1 + 1e-9;
        double[] x = MoonStack.solve(H, g, m);
        if (x == null) return null;
        double[] out = new double[nu];
        for (int a = 0; a < m; a++) out[idx[a]] = x[a] * sc[idx[a]];
        return out;
    }

    /**
     * The noise of a picture's sky just around the Moon (its spread, robustly), where nothing is burnt and it is
     * dimmer than {@code below}.
     */
    static double ringNoise(float[] img, int W, int H, double cx, double cy, double R, float[] cf, float below) {
        float[] v = new float[W * H];
        int m = 0;
        double a2 = Math.pow(1.03 * R, 2), b2 = Math.pow(1.12 * R, 2);
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                double d2 = (i - cx) * (i - cx) + (j - cy) * (j - cy);
                int k = j * W + i;
                if (d2 < a2 || d2 > b2 || cf[k] > 0 || img[k] > below) continue;
                v[m++] = img[k];
            }
        }
        if (m < 50) return Double.NaN;
        float[] s = Arrays.copyOf(v, m);
        Arrays.sort(s);
        float med = s[m / 2];
        for (int q = 0; q < m; q++) s[q] = Math.abs(s[q] - med);
        Arrays.sort(s);
        return s[m / 2] * 1.4826;
    }
}
