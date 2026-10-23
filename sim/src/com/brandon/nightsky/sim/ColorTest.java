package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.ColorCalibration;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The colour calibration's fit (F4) on synthetic stars: Ballesteros' formula at its known points, then stars with
 * the catalogue's own spread of colour indices, measured through a wrong white balance with noise and a share of
 * outliers (blends, variables, mismatches), must give back the true gains within 3%. Too few stars, or a cast no
 * camera has, keep the fixed balance.
 * <p>
 * Usage: ColorTest [stars.csv]
 */
public final class ColorTest {
    static int failures;

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "/home/claude/night-sky-camera/app/src/main/assets/stars.csv";
        BrightStars cat = BrightStars.load(new FileInputStream(path));
        formula();
        float[] bvs = colours(cat);
        System.out.printf(Locale.US, "  %d catalogue stars with a colour index to draw from%n", bvs.length);
        Random rnd = new Random(11);
        double worst = 0;
        double[][] casts = {{1, 1}, {1.12, 0.88}, {0.85, 1.2}, {1.3, 0.75}, {0.95, 1.05}};
        for (double[] c : casts) {
            for (int trial = 0; trial < 20; trial++) {
                ColorCalibration.Result r = ColorCalibration.fit(stars(rnd, bvs, 60, c[0], c[1], 0.10, 40));
                double fr = r.gainR / r.gainG, fb = r.gainB / r.gainG;
                double e = Math.max(Math.abs(fr * c[0] - 1), Math.abs(fb * c[1] - 1));
                worst = Math.max(worst, e);
                if (!r.applied) check(false, "cast " + c[0] + "/" + c[1] + " trial " + trial + " not applied: " + r.reason);
            }
        }
        check(worst < 0.03, String.format(Locale.US, "60 stars, 10%% outliers, 5 casts x 20 trials: gains within %.2f%% of the truth (3%% allowed)",
                100 * worst));
        // few, faint stars: used only when they pin each gain down to 1.5%, so that 95% of the gains used are within
        // 3% (a handful of stars can't promise every one is)
        for (int[] fs : new int[][]{{18, 25}, {30, 20}, {25, 40}, {40, 30}}) {
            double[] errs = new double[400];
            int applied = 0;
            for (int trial = 0; trial < 200; trial++) {
                ColorCalibration.Result r = ColorCalibration.fit(stars(rnd, bvs, fs[0], 1.1, 0.9, 0.05, fs[1]));
                if (!r.applied) continue;
                errs[applied++] = Math.abs(r.gainR / r.gainG * 1.1 - 1);
                errs[applied++] = Math.abs(r.gainB / r.gainG * 0.9 - 1);
            }
            java.util.Arrays.sort(errs, 0, applied);
            double p95 = applied > 0 ? errs[(int) (0.95 * (applied - 1))] : 0;
            check(p95 < 0.03, String.format(Locale.US, "%d stars at signal to noise %d: used in %d of 200 trials; 95%% of their gains "
                    + "within %.2f%%", fs[0], fs[1], applied / 2, 100 * p95));
        }
        // what keeps the fixed balance
        ColorCalibration.Result few = ColorCalibration.fit(stars(rnd, bvs, 12, 1.1, 0.9, 0, 40));
        check(!few.applied && few.reason.contains("12 stars"), "12 stars keep the fixed balance: " + few.reason);
        ColorCalibration.Result wild = ColorCalibration.fit(stars(rnd, bvs, 60, 2.2, 0.4, 0, 40));
        check(!wild.applied && wild.reason.contains("more than a camera"), "a cast no camera has is refused: " + wild.reason);
        List<ColorCalibration.Star> junk = stars(rnd, bvs, 60, 1, 1, 0, 40);
        for (ColorCalibration.Star s : junk) {
            // colours that have nothing to do with the stars' (a bad match)
            s.r *= Math.exp(0.6 * rnd.nextGaussian());
            s.b *= Math.exp(0.6 * rnd.nextGaussian());
        }
        ColorCalibration.Result bad = ColorCalibration.fit(junk);
        check(!bad.applied, "colours that scatter wildly are refused: " + bad.reason);
        ColorCalibration.Result ok = ColorCalibration.fit(stars(rnd, bvs, 40, 1.05, 0.95, 0.1, 40));
        System.out.println("  " + ok.describe(5500));
        double y = 0.2126 * ok.gainR + 0.7152 * ok.gainG + 0.0722 * ok.gainB;
        check(Math.abs(y - 1) < 1e-9, "white's brightness is kept (luminance of the gains " + y + ")");
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        if (failures > 0) System.exit(1);
    }

    static void formula() {
        double sun = ColorCalibration.kelvin(0.65), vega = ColorCalibration.kelvin(0.0), arcturus = ColorCalibration.kelvin(1.23);
        System.out.printf(Locale.US, "  Ballesteros: Sun-like (B-V 0.65) %.0f K, Vega (0.00) %.0f K, Arcturus (1.23) %.0f K%n", sun, vega, arcturus);
        check(Math.abs(sun - 5778) < 5 && Math.abs(ColorCalibration.WHITE_K - sun) < 1e-9, "a Sun-like star is 5778 K, the white");
        check(Math.abs(vega - 10125) < 10 && Math.abs(arcturus - 4251) < 10, "Vega about 10100 K, Arcturus about 4250 K");
        double[] w = ColorCalibration.expected(ColorCalibration.WHITE_K);
        check(Math.abs(w[0] - 1) < 1e-9 && Math.abs(w[2] - 1) < 1e-9, "the white star is expected white");
        double[] v = ColorCalibration.expected(vega), a = ColorCalibration.expected(arcturus);
        check(v[2] > 1.3 && v[0] < 0.85, String.format(Locale.US, "Vega expected blue-white (red %.2f, blue %.2f of green)", v[0], v[2]));
        check(a[0] > 1.15 && a[2] < 0.75, String.format(Locale.US, "Arcturus expected orange (red %.2f, blue %.2f of green)", a[0], a[2]));
    }

    static float[] colours(BrightStars cat) {
        float[] v = new float[cat.n];
        int k = 0;
        for (int i = 0; i < cat.n; i++) if (!Float.isNaN(cat.bv[i]) && cat.bv[i] >= -0.3 && cat.bv[i] <= 1.9) v[k++] = cat.bv[i];
        return java.util.Arrays.copyOf(v, k);
    }

    /**
     * Stars of random catalogue colours, measured through a white balance that multiplies red by {@code castR} and
     * blue by {@code castB} (so the right gains are their inverses), each channel's light known to about 1 / snr,
     * with the real stars' own scatter about a blackbody and a share of outliers.
     */
    static List<ColorCalibration.Star> stars(Random rnd, float[] bvs, int n, double castR, double castB, double outliers, double snr) {
        List<ColorCalibration.Star> l = new ArrayList<ColorCalibration.Star>();
        for (int i = 0; i < n; i++) {
            ColorCalibration.Star s = new ColorCalibration.Star();
            s.bv = bvs[rnd.nextInt(bvs.length)];
            s.t = ColorCalibration.kelvin(s.bv);
            s.vmag = 3 + 3 * rnd.nextDouble();
            double[] e = ColorCalibration.expected(s.t);
            double flux = 1000 * Math.pow(10, -0.4 * (s.vmag - 3));
            // real stars stray from a blackbody by a few per cent
            double r = e[0] * Math.exp(0.035 * rnd.nextGaussian()), b = e[2] * Math.exp(0.035 * rnd.nextGaussian());
            if (rnd.nextDouble() < outliers) {
                // a blend with a star of another colour, a variable, a wrong match
                r *= Math.exp(0.4 * (rnd.nextBoolean() ? 1 : -1) * (0.5 + rnd.nextDouble()));
                b *= Math.exp(0.4 * (rnd.nextBoolean() ? 1 : -1) * (0.5 + rnd.nextDouble()));
            }
            double sn = snr * (0.7 + 0.6 * rnd.nextDouble());
            s.g = flux * (1 + rnd.nextGaussian() / sn);
            s.r = flux * r * castR * (1 + rnd.nextGaussian() / sn);
            s.b = flux * b * castB * (1 + rnd.nextGaussian() / sn);
            s.sg = flux / sn;
            s.sr = flux * r * castR / sn;
            s.sb = flux * b * castB / sn;
            l.add(s);
        }
        return l;
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  PASS: " : "  FAIL: ") + what);
        if (!ok) failures++;
    }
}
