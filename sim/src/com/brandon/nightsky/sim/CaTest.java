package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.ChromaShift;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.MergeProcessor;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.Recipe;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.StarList;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The lens's colour fringes (2.0, {@link ChromaShift}): a lens that puts a star's red light a fraction of a pixel
 * further out than its green and its blue further in.
 * <ol>
 * <li>The measurement alone, on pictures of stars drawn through lenses with known fringes: the fringes come out as
 * made; a clean lens, fringes too small to matter, too few stars, stars only in the middle, a shift no lens makes and
 * a faint field are all left alone, with the reason; doubles, stars with a wrong centre in one colour, clipped stars
 * and a lens whose middle isn't the sensor's don't mislead it.
 * <li>Whole sessions through the stacker on simulated nights: a clean lens's night stacks bit for bit as it does with
 * the step switched off; a fringing lens's fringes are measured as made and are gone from the stack that follows
 * (measured again, and by this test's own plain centroids), with green untouched, each star's red and blue light and
 * the sky's noise as they were, and no stack made that wasn't needed; with the ground kept still; in a merge of two
 * nights; and not in a Quick look.
 * </ol>
 */
public final class CaTest {
    private CaTest() {}

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "ca-out");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 14;
        outDir.mkdirs();
        // CA_PART=1 or 2: only the measurement, or only the sessions
        String part = System.getenv("CA_PART");
        if (part == null || part.equals("1")) measurement();
        if (part == null || part.equals("2")) sessions(outDir, scale, frames);
        MergeTest.finish();
    }

    // ---- 1) the measurement alone ----

    /** A field of round stars as a stack would hold them, through a lens with fringes. */
    static final class Field {
        int W = 1360, H = 1024, n = 500;
        long seed = 1;
        /** The lens: red's and blue's shift at the corner (pixels, outwards positive), the cubic share, its middle off the sensor's. */
        double red, blue, cubic = 0.6, offX, offY;
        /** The stars: their peaks (log-uniform between these, in units of the sky's noise), their size, where they are. */
        double peakLo = 40, peakHi = 2500, sigma = 1.15, maxRho = 2;
        /** This share of the stars has a close companion of another colour; this share has its red centre knocked off. */
        double doubles, knocked;
        /** The brightest stars are clipped (and their red centres wrong), marked so in this way: 0 no, 1 in the list, 2 in the stack's clip map. */
        int clipped;

        RgbImage img;
        StarList stars;

        double shift(int c, double rho) {
            double s = c == 0 ? red : c == 2 ? blue : 0;
            return s * ((1 - cubic) * rho + cubic * rho * rho * rho);
        }

        Field make() {
            Random r = new Random(seed);
            img = new RgbImage(W, H);
            img.clip = new byte[W * H];
            float bg = 20f;
            for (int i = 0; i < W * H; i++) {
                img.r[i] = bg + (float) r.nextGaussian();
                img.g[i] = bg + (float) r.nextGaussian();
                img.b[i] = bg + (float) r.nextGaussian();
            }
            stars = new StarList(n);
            double hd = 0.5 * Math.hypot(W, H), lx = W / 2.0 + offX, ly = H / 2.0 + offY;
            int cell = 20, gw = W / cell + 1, gh = H / cell + 1;
            boolean[] taken = new boolean[gw * gh];
            int made = 0, tries = 0;
            while (made < n && tries++ < 200 * n) {
                double x = 16 + r.nextDouble() * (W - 32), y = 16 + r.nextDouble() * (H - 32);
                double rhoS = Math.hypot(x - W / 2.0, y - H / 2.0) / hd;
                if (rhoS > maxRho) continue;
                int gx = (int) (x / cell), gy = (int) (y / cell);
                boolean free = true;
                for (int dy = -1; dy <= 1 && free; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int qx = gx + dx, qy = gy + dy;
                        if (qx >= 0 && qy >= 0 && qx < gw && qy < gh && taken[qy * gw + qx]) free = false;
                    }
                }
                if (!free) continue;
                taken[gy * gw + gx] = true;
                double peak = peakLo * Math.pow(peakHi / peakLo, r.nextDouble());
                boolean clip = clipped > 0 && made < n / 8;
                if (clip) peak = 4000 + 3000 * r.nextDouble();
                double s = sigma * (1 + 0.3 * rhoS * rhoS);
                double fg = peak * 2 * Math.PI * s * s, fr = fg * (0.5 + 0.7 * r.nextDouble()), fb = fg * (0.4 + 0.9 * r.nextDouble());
                double ax = x - lx, ay = y - ly, rho = Math.hypot(ax, ay) / hd, ar = Math.max(1e-9, Math.hypot(ax, ay));
                double sr = shift(0, rho), sb = shift(2, rho);
                double rx = x + ax / ar * sr, ry = y + ay / ar * sr;
                if (clip || r.nextDouble() < knocked) {
                    double th = 2 * Math.PI * r.nextDouble();
                    rx += 1.2 * Math.cos(th);
                    ry += 1.2 * Math.sin(th);
                }
                spot(img.r, rx, ry, s, fr);
                spot(img.g, x, y, s, fg);
                spot(img.b, x + ax / ar * sb, y + ay / ar * sb, s, fb);
                if (r.nextDouble() < doubles) {
                    // a companion a few pixels away, redder or bluer than the star
                    double th = 2 * Math.PI * r.nextDouble(), d = 3 + 3 * r.nextDouble(), cf = 0.3 + 0.5 * r.nextDouble();
                    double qx = x + d * Math.cos(th), qy = y + d * Math.sin(th);
                    boolean reddish = r.nextBoolean();
                    spot(img.r, qx, qy, s, fg * cf * (reddish ? 1.4 : 0.3));
                    spot(img.g, qx, qy, s, fg * cf);
                    spot(img.b, qx, qy, s, fg * cf * (reddish ? 0.3 : 1.4));
                    // the detector lists it when it's bright enough to tell apart
                    if (cf > 0.5) stars.add((float) qx, (float) qy, (float) (fg * cf), (float) (1.177 * s), 1f, false);
                }
                if (clip && clipped == 2) {
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) img.clip[((int) y + dy) * W + (int) x + dx] = 40;
                }
                stars.add((float) (x + 0.3 * r.nextGaussian()), (float) (y + 0.3 * r.nextGaussian()), (float) fg, (float) (1.177 * s), 1f,
                        clip && clipped == 1);
                made++;
            }
            return this;
        }

        /** A round star of light {@code flux} at (px, py), each pixel's share of it. */
        private void spot(float[] d, double px, double py, double sg, double flux) {
            int rad = (int) Math.ceil(4.5 * sg);
            int x0 = (int) Math.floor(px) - rad, y0 = (int) Math.floor(py) - rad, m = 2 * rad + 1;
            double inv = 1 / (Math.sqrt(2) * sg);
            double[] fx = new double[m], fy = new double[m];
            for (int q = 0; q < m; q++) {
                fx[q] = 0.5 * (SkySim.erf((x0 + q + 1 - px) * inv) - SkySim.erf((x0 + q - px) * inv));
                fy[q] = 0.5 * (SkySim.erf((y0 + q + 1 - py) * inv) - SkySim.erf((y0 + q - py) * inv));
            }
            for (int j = 0; j < m; j++) {
                int y = y0 + j;
                if (y < 0 || y >= H) continue;
                for (int i = 0; i < m; i++) {
                    int x = x0 + i;
                    if (x >= 0 && x < W) d[y * W + x] += (float) (flux * fx[i] * fy[j]);
                }
            }
        }
    }

    static String fits(ChromaShift.Result r) {
        return r.red == null && r.blue == null ? "nothing fitted (" + r.why + ")" : r.numbers();
    }

    static void measurement() {
        System.out.println("== the measurement, on stars drawn through a lens of known fringes");
        // a clean lens
        Field f = new Field();
        ChromaShift.Result r = ChromaShift.measure(f.make().img, f.stars);
        System.out.println("  clean lens: " + fits(r));
        check(r.model == null && r.why == null && r.red != null && r.blue != null && Math.abs(r.red.largest) < 0.03
                && Math.abs(r.blue.largest) < 0.03, "a clean lens: nothing to take out (" + r.measured + " stars measured)");
        check(r.red.sigma < 0.02 && r.blue.sigma < 0.02 && r.red.stars >= 350, String.format(Locale.US,
                "and known to %.3f px (red) and %.3f px (blue) at the corner from %d stars", r.red.sigma, r.blue.sigma, r.red.stars));

        // red out, blue in, mostly cubic
        f = new Field();
        f.red = 0.5;
        f.blue = -0.3;
        r = ChromaShift.measure(f.make().img, f.stars);
        System.out.println("  red +0.50, blue -0.30: " + fits(r));
        check(r.model != null && r.red.used && r.blue.used && Math.abs(r.red.corner - 0.5) < 0.03 && Math.abs(r.blue.corner + 0.3) < 0.03,
                String.format(Locale.US, "red 0.50 px out and blue 0.30 px in at the corner: measured %+.3f and %+.3f", r.red.corner, r.blue.corner));
        check(Math.abs(r.red.a - 0.2) < 0.06 && Math.abs(r.red.b - 0.3) < 0.07 && Math.abs(r.blue.a + 0.12) < 0.06 && Math.abs(r.blue.b + 0.18) < 0.07,
                String.format(Locale.US, "and its shape: red %.2f r + %.2f r^3 (made 0.20, 0.30), blue %.2f r + %.2f r^3 (made -0.12, -0.18)",
                        r.red.a, r.red.b, r.blue.a, r.blue.b));
        ChromaShift.Model m = r.model;
        double hd = 0.5 * Math.hypot(f.W, f.H);
        check(m != null && Math.abs(m.cx - f.W / 2.0) < 1e-3 && Math.abs(m.cy - f.H / 2.0) < 1e-3 && Math.abs(m.halfDiagonal - hd) < 1e-2
                        && Math.abs(m.shift(0, 1) - r.red.corner) < 1e-3 && Math.abs(m.shift(2, 0.5) - r.blue.at(0.5)) < 1e-3 && m.shift(1, 1) == 0
                        && Math.abs(m.largest() - Math.abs(r.red.largest)) < 1e-3,
                "the correction handed to the stacker is that fit, about the picture's middle");
        boolean close = true;
        for (int i = 1; i <= 10 && m != null; i++) {
            double rho = i / 10.0;
            if (Math.abs(m.shift(0, rho) - f.shift(0, rho)) > 0.03 || Math.abs(m.shift(2, rho) - f.shift(2, rho)) > 0.03) close = false;
        }
        check(close, "within 0.03 px of the lens all the way from the middle to the corner");

        // other shapes
        f = new Field();
        f.red = 0.3;
        f.cubic = 0;
        f.seed = 2;
        r = ChromaShift.measure(f.make().img, f.stars);
        check(r.model != null && r.red.used && !r.blue.used && Math.abs(r.red.corner - 0.3) < 0.03 && Math.abs(r.red.b) < 0.08
                        && r.model.aB == 0 && r.model.bB == 0,
                String.format(Locale.US, "a shift that grows evenly (red 0.30 px, blue none): %+.3f px, no cubic part made up (%.2f), blue "
                        + "left alone (%+.3f px)", r.red.corner, r.red.b, r.blue.largest));
        f = new Field();
        f.blue = 0.4;
        f.cubic = 1;
        f.seed = 3;
        r = ChromaShift.measure(f.make().img, f.stars);
        check(r.model != null && r.blue.used && !r.red.used && Math.abs(r.blue.corner - 0.4) < 0.03 && Math.abs(r.blue.a) < 0.08,
                String.format(Locale.US, "a shift only near the corners (blue 0.40 px out): %+.3f px, %.2f of it linear", r.blue.corner, r.blue.a));

        // too small to matter
        f = new Field();
        f.red = 0.08;
        f.blue = -0.06;
        f.seed = 4;
        r = ChromaShift.measure(f.make().img, f.stars);
        check(r.model == null && r.why == null && Math.abs(r.red.corner - 0.08) < 0.03 && Math.abs(r.blue.corner + 0.06) < 0.03,
                String.format(Locale.US, "fringes under %.2f px are measured (%+.3f, %+.3f) and left alone", ChromaShift.MIN_SHIFT, r.red.corner,
                        r.blue.corner));

        // too few stars; stars only in the middle; a faint field
        f = new Field();
        f.red = 0.5;
        f.n = 30;
        r = ChromaShift.measure(f.make().img, f.stars);
        check(r.model == null && r.why != null && r.why.contains("too few stars"), "30 stars: " + r.why);
        f = new Field();
        f.red = 0.5;
        f.n = 60;
        f.seed = 5;
        r = ChromaShift.measure(f.make().img, f.stars);
        System.out.println("  60 stars: " + fits(r));
        check(r.model == null || (r.red != null && Math.abs(r.red.corner - 0.5) < 4 * r.red.sigma + 0.03),
                "60 stars: taken out only if they say so clearly" + (r.model != null ? String.format(Locale.US, " (they do: %+.3f +-%.3f)",
                        r.red.corner, r.red.sigma) : " (" + (r.why != null ? r.why : "not sure enough") + ")"));
        f = new Field();
        f.red = 0.5;
        f.maxRho = 0.45;
        f.n = 300;
        r = ChromaShift.measure(f.make().img, f.stars);
        check(r.model == null && r.why != null && r.why.contains("corners"), "stars only in the middle of the picture: " + r.why);
        f = new Field();
        f.red = 0.5;
        f.peakLo = 2;
        f.peakHi = 4.5;
        r = ChromaShift.measure(f.make().img, f.stars);
        check(r.model == null && r.why != null, "a field of stars too faint to place: " + r.why + " (" + r.measured + " measured)");
        // faint, but enough of them just bright enough: right, or left alone
        f = new Field();
        f.red = 0.5;
        f.peakLo = 3;
        f.peakHi = 7;
        r = ChromaShift.measure(f.make().img, f.stars);
        System.out.println("  500 faint stars, red 0.50: " + fits(r));
        check(r.model == null ? r.why != null || !r.red.used : Math.abs(r.red.corner - 0.5) < Math.max(0.06, 3 * r.red.sigma) && !r.blue.used,
                "a field of faint stars: " + (r.model == null ? "left alone" + (r.why != null ? " (" + r.why + ")" : "")
                        : String.format(Locale.US, "red taken out at %+.3f +-%.3f px, blue left alone", r.red.corner, r.red.sigma))
                        + " (" + r.measured + " stars measured)");

        // a faint field with a real fringe: not sure enough is left alone, sure enough is right
        f = new Field();
        f.red = 0.14;
        f.peakLo = 12;
        f.peakHi = 40;
        f.n = 90;
        f.seed = 6;
        r = ChromaShift.measure(f.make().img, f.stars);
        System.out.println("  90 faint stars, red 0.14: " + fits(r));
        check(r.model == null || Math.abs(r.red.corner - 0.14) < 0.06, "a small fringe on a few faint stars: "
                + (r.model == null ? "left alone" : String.format(Locale.US, "taken out at %+.3f px", r.red.corner))
                + (r.red != null ? String.format(Locale.US, " (%+.3f +-%.3f px measured)", r.red.corner, r.red.sigma) : ""));

        // a shift no lens makes
        f = new Field();
        f.red = 3.2;
        r = ChromaShift.measure(f.make().img, f.stars);
        check(r.model == null, "a 3 px shift isn't believed: " + (r.why != null ? r.why : fits(r)));

        // what mustn't mislead it
        f = new Field();
        f.red = 0.5;
        f.blue = -0.3;
        f.doubles = 0.3;
        f.seed = 7;
        r = ChromaShift.measure(f.make().img, f.stars);
        System.out.println("  with doubles: " + fits(r));
        check(r.model != null && Math.abs(r.red.corner - 0.5) < 0.04 && Math.abs(r.blue.corner + 0.3) < 0.04, String.format(Locale.US,
                "three stars in ten with a red or blue companion: still %+.3f and %+.3f px", r.red.corner, r.blue.corner));
        f = new Field();
        f.red = 0.5;
        f.blue = -0.3;
        f.knocked = 0.15;
        f.seed = 8;
        r = ChromaShift.measure(f.make().img, f.stars);
        check(r.model != null && Math.abs(r.red.corner - 0.5) < 0.04, String.format(Locale.US,
                "one star in seven with its red centre 1.2 px off: still %+.3f px (%d of %d stars kept)", r.red.corner, r.red.stars, r.measured));
        for (int how = 1; how <= 2; how++) {
            f = new Field();
            f.red = 0.5;
            f.blue = -0.3;
            f.clipped = how;
            f.seed = 9;
            r = ChromaShift.measure(f.make().img, f.stars);
            check(r.model != null && Math.abs(r.red.corner - 0.5) < 0.03 && r.measured <= f.n - f.n / 8, String.format(Locale.US,
                    "the brightest stars clipped (%s), their red centres wrong: not used (%d of %d measured), %+.3f px",
                    how == 1 ? "marked in the star list" : "marked in the stack's clip map", r.measured, f.n, r.red.corner));
        }
        f = new Field();
        f.red = 0.5;
        f.blue = -0.3;
        f.offX = 12;
        f.offY = -8;
        f.seed = 10;
        r = ChromaShift.measure(f.make().img, f.stars);
        check(r.model != null && Math.abs(r.red.corner - 0.5) < 0.03 && Math.abs(r.blue.corner + 0.3) < 0.03, String.format(Locale.US,
                "the lens's middle 14 px off the sensor's: %+.3f and %+.3f px", r.red.corner, r.blue.corner));
    }

    // ---- 2) whole sessions ----

    /** A progress that keeps what was shown. */
    static final class Stages implements Progress {
        final List<String> seen = new ArrayList<String>();

        @Override
        public synchronized void update(String stage, float fraction) {
            if (seen.isEmpty() || !seen.get(seen.size() - 1).equals(stage)) seen.add(stage);
        }

        @Override
        public boolean cancelled() {
            return false;
        }

        synchronized int count(String part) {
            int n = 0;
            for (String s : seen) if (s.contains(part)) n++;
            return n;
        }
    }

    static PostProcessor.Output develop(SessionStore s, SensorSpec spec, SkySim sim, BrightStars catalog, long start, double lon,
                                        boolean fringes, boolean pattern, boolean still, Stages st) throws Exception {
        PostProcessor pp = new PostProcessor(s, spec);
        pp.sky = MergeTest.sky(sim, catalog, start, lon);
        pp.fringes = fringes;
        pp.patternCleanup = pattern;
        pp.freezeGround = still;
        return pp.run(false, st);
    }

    static String decision(PostProcessor.Output o, String start) {
        for (String d : o.decisions) if (d.startsWith(start)) return d;
        return null;
    }

    static void sessions(File outDir, int scale, int frames) throws Exception {
        System.out.println("== whole sessions (" + frames + " frames at 1/" + scale + " size)");
        BrightStars catalog = SimRunner.loadCatalog();
        int W = 4080 / scale, H = 3072 / scale;
        CameraModel truth = CameraModel.nominal(W, H, 2875.0 / scale);
        truth.cx = W / 2.0 + 14.0 / scale;
        truth.cy = H / 2.0 - 9.0 / scale;
        truth.k1 = -0.045;
        truth.k2 = 0.018;
        truth.source = "truth";
        long start = SimRunner.DEFAULT_TIME_MS;
        double lon = SimRunner.DEFAULT_LON;
        File root = new File(outDir, "sessions");
        root.mkdirs();
        double red = 0.9 / scale, blue = -0.6 / scale;

        // a clean lens: as if the step weren't there
        SkySim clean = MergeTest.night(W, H, truth, catalog, start, lon, scale);
        SensorSpec spec = clean.spec(true);
        ColorMath.compute(spec, ColorMath.DEFAULT_CCT);
        SessionStore s0 = MergeTest.capture(clean, spec, root, frames, 7);
        Stages st = new Stages();
        PostProcessor.Output on = develop(s0, spec, clean, catalog, start, lon, true, true, false, st);
        PostProcessor.Output off = develop(s0, spec, clean, catalog, start, lon, false, true, false, new Stages());
        System.out.println("  clean lens: " + decision(on, "colour fringes"));
        check(on.fringes != null && on.fringes.model == null && on.fringesAfter == null && decision(on, "colour fringes: none worth taking out") != null,
                "a clean lens's night: measured, nothing to take out (" + (on.fringes != null ? fits(on.fringes) : "not measured") + ")");
        check(off.fringes == null && decision(off, "colour fringes") == null, "switched off: not measured, nothing said");
        check(same(on.image, off.image) && st.count("colour fringes") == 0, "and the stack is the same, bit for bit, with no stack made for it");

        // a fringing lens
        SkySim lens = MergeTest.night(W, H, truth, catalog, start, lon, scale);
        lens.caRed = red;
        lens.caBlue = blue;
        SessionStore s1 = MergeTest.capture(lens, spec, root, frames, 7);
        PostProcessor.Output left = develop(s1, spec, lens, catalog, start, lon, false, true, false, new Stages());
        Stages sc = new Stages();
        PostProcessor.Output taken = develop(s1, spec, lens, catalog, start, lon, true, true, false, sc);
        System.out.println("  fringing lens: " + decision(taken, "colour fringes"));
        ChromaShift.Result b = taken.fringes, a = taken.fringesAfter;
        check(b != null && b.model != null && Math.abs(b.red.corner - red) < 0.04 && Math.abs(b.blue.corner - blue) < 0.04,
                String.format(Locale.US, "a lens made with red %+.2f px and blue %+.2f px at the corner: measured %s", red, blue,
                        b != null && b.red != null && b.blue != null ? String.format(Locale.US, "%+.3f and %+.3f px on %d stars", b.red.corner,
                                b.blue.corner, b.measured) : "nothing"));
        check(a != null && a.red != null && a.blue != null && Math.abs(a.red.largest) < 0.04 && Math.abs(a.blue.largest) < 0.04,
                "gone from the stack that follows: " + (a != null && a.red != null && a.blue != null ? String.format(Locale.US,
                        "%.3f px (red) and %.3f px (blue) left", Math.abs(a.red.largest), Math.abs(a.blue.largest)) : "not measured again"));
        String d = decision(taken, "colour fringes: towards the corners the lens puts");
        check(d != null && d.contains("taken out as the frames were stacked") && d.contains("further out") && d.contains("further in"),
                "and the decisions say so in words");
        boolean patternAgain = sc.count("without the sensor's pattern") > 0;
        check(patternAgain ? sc.count("colour fringes") == 0 : sc.count("colour fringes") == 1, patternAgain
                ? "the stack made again for the sensor's pattern took them out: no stack made for them alone"
                : "one stack made again for them");
        // by this test's own centroids, on the outer stars
        StarList stars = taken.referenceStars;
        double[] fl = outerShift(left.image, stars, lens), ft = outerShift(taken.image, stars, lens);
        check(Math.abs(fl[0] - fl[2]) < 0.05 && Math.abs(fl[1] - fl[3]) < 0.05 && fl[4] >= 40, String.format(Locale.US,
                "left in, the outer stars' red sits %+.3f px and blue %+.3f px from green (the lens there: %+.3f and %+.3f; %d stars)",
                fl[0], fl[1], fl[2], fl[3], (int) fl[4]));
        check(Math.abs(ft[0]) < 0.04 && Math.abs(ft[1]) < 0.04, String.format(Locale.US, "taken out: %+.3f px and %+.3f px", ft[0], ft[1]));
        // nothing else changed
        check(Arrays.equals(left.image.g, taken.image.g), "green is untouched, bit for bit");
        check(left.image.x0 == taken.image.x0 && left.image.y0 == taken.image.y0 && left.image.x1 == taken.image.x1
                && left.image.y1 == taken.image.y1, "the picture's crop is the same");
        // Each star's red and blue light. The two stacks read a share of those from other photosites, so star by star
        // the light differs by what so few frames' samples make of a small star (and by which of them the outlier
        // rejection keeps): measured at 1/2 size with every sample kept, the brightest third differ by 1.2% (red) and
        // 0.8% (blue) from 14 frames and by 0.8% and 0.5% from 37, as the square root of the frames; with the
        // rejection, 1.6% and 0.9% from 14 and 0.9% and 0.6% from 37. All together the stars keep their light, but
        // for the lens's own stretch: red's picture was the larger (by 0.45 px in the 1277 from the middle to the
        // corner), and drawn in, its stars hold a little less: a tenth of a hundredth at the corners.
        for (int c = 0; c < 3; c += 2) {
            String col = c == 0 ? "red" : "blue";
            double[] ph = light(left.image, taken.image, stars, c);
            check(Math.abs(ph[0] - 1) < 0.004 && Math.abs(ph[1] - 1) < 0.005, String.format(Locale.US,
                    "the stars keep their %s light: %.4f times as much all together, %.4f for the middle star of %d", col, ph[0], ph[1], (int) ph[6]));
            check(Math.abs(ph[2] - 1) < 0.007 && Math.abs(ph[3] - 1) < 0.007, String.format(Locale.US,
                    "near the middle as towards the corners: %.4f and %.4f", ph[2], ph[3]));
            check(ph[4] < 0.03 && ph[5] < 0.04 && ph[7] < 0.12, String.format(Locale.US, "star by star, as the frames' sampling makes them differ: "
                    + "the brightest third by %.4f (none of them by more than %.3f), all of them by %.4f", ph[4], ph[7], ph[5]));
        }
        double[] nl = noise(left.image, stars), nt = noise(taken.image, stars);
        check(Math.abs(nt[0] / nl[0] - 1) < 0.03 && Math.abs(nt[2] / nl[2] - 1) < 0.03, String.format(Locale.US,
                "the sky's noise is the same: red %.4f against %.4f, blue %.4f against %.4f", nt[0], nl[0], nt[2], nl[2]));
        MergeTest.save(left, spec, new File(outDir, "fringes_left.png"));
        MergeTest.save(taken, spec, new File(outDir, "fringes_taken_out.png"));

        // without the pattern step: one stack more, for the fringes alone, and the same result
        Stages sa = new Stages();
        PostProcessor.Output alone = develop(s1, spec, lens, catalog, start, lon, true, false, false, sa);
        ChromaShift.Result aa = alone.fringesAfter;
        check(sa.count("without the lens's colour fringes") == 1 && sa.count("sensor's pattern") == 0 && aa != null && aa.red != null
                        && Math.abs(aa.red.largest) < 0.04 && Math.abs(aa.blue.largest) < 0.04,
                "with no other reason to stack again, one stack is made for the fringes: " + (aa != null && aa.red != null ? String.format(
                        Locale.US, "%.3f and %.3f px left", Math.abs(aa.red.largest), Math.abs(aa.blue.largest)) : "not measured again"));

        // a Quick look stacks once
        Recipe q = Recipe.single(s1.id(), false);
        q.quick = true;
        PostProcessor qp = new PostProcessor(s1, spec);
        q.configure(qp, s1.dir);
        Recipe full = Recipe.single(s1.id(), false);
        PostProcessor fp = new PostProcessor(s1, spec);
        full.configure(fp, s1.dir);
        check(!qp.fringes && fp.fringes, "a Quick look leaves them in (it stacks once); a full develop takes them out");

        // the ground kept still: the same lens over the branch
        SkySim br = MergeTest.night(W, H, truth, catalog, start, lon, scale);
        br.branch = true;
        br.caRed = red;
        br.caBlue = blue;
        SessionStore s2 = MergeTest.capture(br, spec, root, frames, 7);
        PostProcessor.Output g = develop(s2, spec, br, catalog, start, lon, true, true, true, new Stages());
        ChromaShift.Result ga = g.fringesAfter;
        check(decision(g, "foreground: kept still") != null && ga != null && ga.red != null && Math.abs(ga.red.largest) < 0.05
                        && Math.abs(ga.blue.largest) < 0.05,
                "with a branch in the picture and the ground kept still: " + (ga != null && ga.red != null ? String.format(Locale.US,
                        "%.3f and %.3f px left", Math.abs(ga.red.largest), Math.abs(ga.blue.largest)) : "not taken out ("
                        + decision(g, "colour fringes") + ")"));

        // two nights merged
        long start2 = start + 86_164_091L;
        SkySim n2 = MergeTest.night(W, H, truth, catalog, start2, lon, scale);
        n2.setAttitude(31, 3.6, 95);
        n2.caRed = red;
        n2.caBlue = blue;
        SessionStore s3 = MergeTest.capture(n2, spec, root, frames, 8);
        MergeProcessor.Result mr = new MergeProcessor(Arrays.asList(MergeTest.session(s1, spec, lens, catalog, start, lon, "night 1"),
                MergeTest.session(s3, spec, n2, catalog, start2, lon, "night 2"))).run(MergeTest.quiet());
        ChromaShift.Result mb = mr.out.fringes, ma = mr.out.fringesAfter;
        System.out.println("  merged: " + decision(mr.out, "colour fringes"));
        check(mr.parts.get(1).skipped == null && mb != null && mb.model != null && Math.abs(mb.red.corner - red) < 0.05
                        && Math.abs(mb.blue.corner - blue) < 0.05 && ma != null && ma.red != null && Math.abs(ma.red.largest) < 0.05
                        && Math.abs(ma.blue.largest) < 0.05,
                "two nights merged: " + (mb != null && mb.red != null && ma != null && ma.red != null ? String.format(Locale.US,
                        "%+.3f and %+.3f px measured on the merged stack, %.3f and %.3f px left", mb.red.corner, mb.blue.corner,
                        Math.abs(ma.red.largest), Math.abs(ma.blue.largest)) : "not taken out"));
        MergeProcessor mp = new MergeProcessor(Arrays.asList(MergeTest.session(s1, spec, lens, catalog, start, lon, "night 1"),
                MergeTest.session(s3, spec, n2, catalog, start2, lon, "night 2")));
        mp.fringes = false;
        MergeProcessor.Result m0 = mp.run(MergeTest.quiet());
        check(m0.out.fringes == null && decision(m0.out, "colour fringes") == null && Arrays.equals(m0.out.image.g, mr.out.image.g),
                "the merge with the step off says nothing of them, and its green is the same");
    }

    static boolean same(RgbImage a, RgbImage b) {
        return Arrays.equals(a.r, b.r) && Arrays.equals(a.g, b.g) && Arrays.equals(a.b, b.b) && a.x0 == b.x0 && a.y0 == b.y0 && a.x1 == b.x1
                && a.y1 == b.y1;
    }

    /** The stars to judge by: bright, unclipped, by themselves and well inside the picture; their indices. */
    static int[] judged(RgbImage im, StarList s, double minRho) {
        int W = im.width, H = im.height;
        double hd = 0.5 * Math.hypot(W, H);
        int[] out = new int[s.count];
        int n = 0;
        for (int i = 0; i < s.count && n < 600; i++) {
            if (s.saturated[i]) continue;
            int cx = (int) s.x[i], cy = (int) s.y[i];
            if (cx < im.x0 + 16 || cy < im.y0 + 16 || cx >= im.x1 - 16 || cy >= im.y1 - 16) continue;
            if (Math.hypot(s.x[i] - W / 2.0, s.y[i] - H / 2.0) / hd < minRho) continue;
            boolean alone = true, clip = false;
            for (int j = 0; j < s.count && alone; j++) {
                if (j != i && Math.hypot(s.x[j] - s.x[i], s.y[j] - s.y[i]) < 14 && s.flux[j] > 0.05 * s.flux[i]) alone = false;
            }
            for (int dy = -4; dy <= 4 && !clip; dy++) for (int dx = -4; dx <= 4; dx++) if (im.clip != null && im.clip[(cy + dy) * W + cx + dx] != 0) clip = true;
            if (!alone || clip) continue;
            // bright enough in green for a plain centroid: its peak well above the ring's noise
            float[] ring = ring(im.g, W, cx, cy);
            float med = median(ring), sd = 1.4826f * mad(ring, med);
            if (im.g[cy * W + cx] - med < 60 * sd) continue;
            out[n++] = i;
        }
        return Arrays.copyOf(out, n);
    }

    static float[] ring(float[] d, int W, int cx, int cy) {
        float[] ring = new float[400];
        int n = 0;
        for (int dy = -11; dy <= 11; dy++) {
            for (int dx = -11; dx <= 11; dx++) {
                int d2 = dx * dx + dy * dy;
                if (d2 >= 64 && d2 <= 121 && n < ring.length) ring[n++] = d[(cy + dy) * W + cx + dx];
            }
        }
        return Arrays.copyOf(ring, n);
    }

    static float median(float[] v) {
        float[] t = v.clone();
        Arrays.sort(t);
        return t[t.length / 2];
    }

    static float mad(float[] v, float med) {
        float[] t = new float[v.length];
        for (int i = 0; i < v.length; i++) t[i] = Math.abs(v[i] - med);
        Arrays.sort(t);
        return t[t.length / 2];
    }

    /** A plain centre of light in a 9 x 9 box over the ring's median: {x, y} (pixel centres at +0.5), or null without light. */
    static double[] centre(float[] d, int W, int cx, int cy) {
        float med = median(ring(d, W, cx, cy));
        // twice: the box centred on the first centre
        double x = cx + 0.5, y = cy + 0.5;
        for (int it = 0; it < 3; it++) {
            int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
            double s = 0, sx = 0, sy = 0;
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    double v = d[(iy + dy) * W + ix + dx] - med;
                    s += v;
                    sx += v * (ix + dx + 0.5);
                    sy += v * (iy + dy + 0.5);
                }
            }
            if (!(s > 0)) return null;
            x = sx / s;
            y = sy / s;
        }
        return new double[]{x, y};
    }

    /**
     * On the outer stars: how far red's and blue's centres sit from green's along the line from the picture's middle,
     * on average, and what the simulated lens makes there: {red, blue, lens red, lens blue, stars}.
     */
    static double[] outerShift(RgbImage im, StarList s, SkySim sim) {
        int W = im.width, H = im.height;
        int[] idx = judged(im, s, 0.6);
        double[] sum = new double[4];
        int n = 0;
        for (int i : idx) {
            int cx = (int) s.x[i], cy = (int) s.y[i];
            double[] g = centre(im.g, W, cx, cy), r = centre(im.r, W, cx, cy), b = centre(im.b, W, cx, cy);
            if (g == null || r == null || b == null) continue;
            double ux = g[0] - W / 2.0, uy = g[1] - H / 2.0, len = Math.hypot(ux, uy);
            ux /= len;
            uy /= len;
            double[] tr = sim.caAt(0, g[0], g[1]), tb = sim.caAt(2, g[0], g[1]);
            sum[0] += (r[0] - g[0]) * ux + (r[1] - g[1]) * uy;
            sum[1] += (b[0] - g[0]) * ux + (b[1] - g[1]) * uy;
            sum[2] += tr[0] * ux + tr[1] * uy;
            sum[3] += tb[0] * ux + tb[1] * uy;
            n++;
        }
        return new double[]{sum[0] / Math.max(1, n), sum[1] / Math.max(1, n), sum[2] / Math.max(1, n), sum[3] / Math.max(1, n), n};
    }

    /**
     * Each judged star's red or blue light (a 13 x 13 box over its ring's median) in b against a: {all the stars' light
     * together b over a, the middle star's b over a, the same for the stars of the inner half and of the outer half,
     * how the brightest third's ratios spread, how all of them spread, stars, the brightest third's largest change}.
     * The two stacks read a share of their red and blue from different photosites, so a faint star's light differs by
     * its noise; the bright ones show whether light was lost or made.
     */
    static double[] light(RgbImage a, RgbImage b, StarList s, int c) {
        int W = a.width, H = a.height;
        float[] pa = a.channel(c), pb = b.channel(c);
        int[] idx = judged(a, s, 0);
        int n = 0;
        double[] ratio = new double[idx.length], rho = new double[idx.length], bright = new double[idx.length];
        double sa = 0, sb = 0, hd = 0.5 * Math.hypot(W, H);
        for (int i : idx) {
            int cx = (int) s.x[i], cy = (int) s.y[i];
            double va = box(pa, W, cx, cy), vb = box(pb, W, cx, cy);
            if (!(va > 0)) continue;
            sa += va;
            sb += vb;
            ratio[n] = vb / va;
            rho[n] = Math.hypot(s.x[i] - W / 2.0, s.y[i] - H / 2.0) / hd;
            bright[n] = va;
            n++;
        }
        double[] rs = Arrays.copyOf(rho, n), bs = Arrays.copyOf(bright, n);
        Arrays.sort(rs);
        Arrays.sort(bs);
        double midRho = rs[n / 2], brightFrom = bs[2 * n / 3];
        double[] all = new double[n], in = new double[n], out = new double[n], top = new double[n];
        int ni = 0, no = 0, nt = 0;
        double worst = 0;
        for (int k = 0; k < n; k++) {
            all[k] = ratio[k];
            if (rho[k] < midRho) in[ni++] = ratio[k];
            else out[no++] = ratio[k];
            if (bright[k] >= brightFrom) {
                top[nt++] = ratio[k];
                worst = Math.max(worst, Math.abs(ratio[k] - 1));
            }
        }
        return new double[]{sb / sa, middle(all, n), middle(in, ni), middle(out, no), spread(top, nt), spread(all, n), n, worst};
    }

    private static double middle(double[] v, int n) {
        double[] c = Arrays.copyOf(v, n);
        Arrays.sort(c);
        return n == 0 ? Double.NaN : c[n / 2];
    }

    /** Half the distance between the 16th and the 84th of a hundred. */
    private static double spread(double[] v, int n) {
        double[] c = Arrays.copyOf(v, n);
        Arrays.sort(c);
        return n < 3 ? Double.NaN : 0.5 * (c[(int) (0.84 * (n - 1))] - c[(int) (0.16 * (n - 1))]);
    }

    static double box(float[] d, int W, int cx, int cy) {
        float med = median(ring(d, W, cx, cy));
        double s = 0;
        for (int dy = -6; dy <= 6; dy++) for (int dx = -6; dx <= 6; dx++) s += d[(cy + dy) * W + cx + dx] - med;
        return s;
    }

    /** The sky's noise per colour in the middle half of the picture: neighbours two pixels apart, stars left out. */
    static double[] noise(RgbImage im, StarList s) {
        int W = im.width, H = im.height;
        long[] mask = com.brandon.nightsky.processing.NoiseProbe.starMask(s, W, H);
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            float[] p = im.channel(c);
            float[] d = new float[(W / 2) * (H / 2) / 2];
            int n = 0;
            for (int y = H / 4; y < 3 * H / 4; y += 2) {
                for (int x = W / 4; x < 3 * W / 4 - 2; x += 2) {
                    if (com.brandon.nightsky.processing.NoiseProbe.masked(mask, W, x, y)
                            || com.brandon.nightsky.processing.NoiseProbe.masked(mask, W, x + 2, y)) continue;
                    if (n < d.length) d[n++] = p[y * W + x] - p[y * W + x + 2];
                }
            }
            out[c] = com.brandon.nightsky.processing.NoiseProbe.robustSigma(d, n) / Math.sqrt(2);
        }
        return out;
    }

    static void check(boolean ok, String what) {
        MergeTest.check(ok, what);
    }
}
