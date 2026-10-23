package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.FocusMeter;
import com.brandon.nightsky.processing.FocusModel;
import com.brandon.nightsky.processing.FocusSweep;
import com.brandon.nightsky.processing.FocusWatch;
import com.brandon.nightsky.processing.Parallel;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.Tenengrad;

import java.util.Locale;
import java.util.Random;
import java.util.SplittableRandom;

/**
 * Focus (A5 with F7) on synthetic frames. Stars are blurred by a disc that grows linearly away from the best
 * focus, on top of their own Gaussian size, in short noisy RAW frames; the daylight target is a sharp-edged
 * scene blurred the same way. Checks: the star meter's sizes follow the blur; full sweeps, checks and
 * predicted sweeps land within one fine step of the true focus; a calibrated temperature line predicts the
 * night's focus within one step (F7's "done when"); the daylight sweep finds the sharpest focus; the long-
 * session watch asks for a check when stars swell steadily, not for haze or one soft frame.
 * <p>
 * Usage: FocusTest
 */
public final class FocusTest {
    static int failures;
    static final int W = 3000, H = 2250;
    // a Pixel-like main camera: 6.9 mm f/1.68, 2.4 um pixels (the RAW stream's binned ones)
    static final FocusSweep.Lens LENS = FocusSweep.Lens.of(10, 0.1, true, 6.9, 1.68, 2.4);

    public static void main(String[] args) {
        System.out.println("  lens: " + LENS.describe());
        meter();
        sweeps();
        checks();
        temperature();
        zero();
        daylight();
        watch();
        noStars();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        if (failures > 0) System.exit(1);
    }

    // ------------------------------------------------------------------------------------------------
    // synthetic star frames

    static final class Sky {
        final int n;
        final double[] x, y, flux;
        double sigma0 = 0.9, skyRate = 4, readNoise = 1.6, adcPerE = 2, exposure = 2;
        int black = 64, white = 1023;
        int[] hot;

        Sky(long seed, int n) {
            this.n = n;
            Random r = new Random(seed);
            x = new double[n];
            y = new double[n];
            flux = new double[n];
            for (int i = 0; i < n; i++) {
                x[i] = 20 + r.nextDouble() * (W - 40);
                y[i] = 20 + r.nextDouble() * (H - 40);
                // brighter stars rarer: N(>F) ~ F^-0.7, from 150 to 40000 ADU in a frame
                flux[i] = Math.min(40000, 150 * Math.pow(1 - r.nextDouble(), -1 / 0.7));
            }
            hot = new int[300];
            for (int i = 0; i < hot.length; i++) hot[i] = r.nextInt(W * H);
        }

        /** A frame with the stars blurred by a disc of the given radius (RAW px). */
        short[] render(final double rho, long seed) {
            final float[] img = new float[W * H];
            // each star: a Gaussian of sigma0 at points spread evenly over the disc (a sunflower pattern)
            int k = rho < 0.3 ? 1 : Math.max(8, Math.min(96, (int) Math.ceil(4 * rho * rho)));
            double[] px = new double[k], py = new double[k];
            double golden = Math.PI * (3 - Math.sqrt(5));
            for (int q = 0; q < k; q++) {
                double rr = k == 1 ? 0 : rho * Math.sqrt((q + 0.5) / k);
                px[q] = rr * Math.cos(q * golden);
                py[q] = rr * Math.sin(q * golden);
            }
            double s = sigma0;
            int rad = (int) Math.ceil(3.5 * s) + 1;
            double inv = 1 / (2 * s * s), norm = 1 / (2 * Math.PI * s * s);
            for (int i = 0; i < n; i++) {
                double f = flux[i] / k;
                for (int q = 0; q < k; q++) {
                    double cx = x[i] + px[q], cy = y[i] + py[q];
                    int ix = (int) Math.floor(cx), iy = (int) Math.floor(cy);
                    for (int yy = iy - rad; yy <= iy + rad; yy++) {
                        if (yy < 0 || yy >= H) continue;
                        double dy = yy + 0.5 - cy;
                        for (int xx = ix - rad; xx <= ix + rad; xx++) {
                            if (xx < 0 || xx >= W) continue;
                            double dx = xx + 0.5 - cx;
                            img[yy * W + xx] += (float) (f * norm * Math.exp(-(dx * dx + dy * dy) * inv));
                        }
                    }
                }
            }
            for (int h : hot) img[h] += 400;
            final short[] raw = new short[W * H];
            final double sky = skyRate * exposure;
            final long sd = seed;
            Parallel.forRange(H, 16, new Parallel.Range() {
                @Override
                public void run(int y0, int y1) {
                    SplittableRandom r = new SplittableRandom(sd * 1000003L + y0);
                    for (int yy = y0; yy < y1; yy++) {
                        for (int xx = 0; xx < W; xx++) {
                            int c = ((yy & 1) << 1) | (xx & 1);
                            // green 1.0, red 0.55, blue 0.6 of the light
                            double gain = c == 1 || c == 2 ? 1 : c == 0 ? 0.55 : 0.6;
                            double sig = (img[yy * W + xx] + sky) * gain;
                            double var = sig * adcPerE + readNoise * readNoise;
                            double v = black + sig + Math.sqrt(var) * gauss(r);
                            raw[yy * W + xx] = (short) Math.max(0, Math.min(white, Math.round(v)));
                        }
                    }
                }
            });
            return raw;
        }
    }

    static double gauss(SplittableRandom r) {
        double u = r.nextDouble(), v = r.nextDouble();
        return Math.sqrt(-2 * Math.log(Math.max(u, 1e-300))) * Math.cos(2 * Math.PI * v);
    }

    static SensorSpec spec() {
        SensorSpec s = new SensorSpec();
        s.width = W;
        s.height = H;
        s.white = 1023;
        return s;
    }

    /** Disc radius (RAW px) at a focus value with the best focus at f0. */
    static double rho(double f, double f0) {
        return 0.5 * LENS.blurPerUnit * Math.abs(f - f0);
    }

    static FocusMeter.Reading read(Sky sky, double f, double f0, long seed) {
        short[] raw = sky.render(rho(f, f0), seed);
        return FocusMeter.measure(raw, 1f, spec(), new float[]{64, 64, 64, 64});
    }

    // ------------------------------------------------------------------------------------------------

    static void meter() {
        Sky sky = new Sky(3, 1600);
        double prev = 0, h0 = 0;
        double[] rhos = {0, 1, 2, 3, 4.5, 6, 8};
        StringBuilder sb = new StringBuilder();
        boolean mono = true;
        long ms = 0;
        double[] h2 = new double[rhos.length];
        for (int i = 0; i < rhos.length; i++) {
            FocusMeter.Reading r = FocusMeter.measure(sky.render(rhos[i], 10 + i), 1f, spec(), new float[]{64, 64, 64, 64});
            ms = Math.max(ms, r.millis);
            sb.append(String.format(Locale.US, " %.1f->%.2f(%d)", rhos[i], r.hfr, r.stars));
            if (i == 0) h0 = r.hfr;
            else if (!(r.hfr > prev)) mono = false;
            prev = r.hfr;
            h2[i] = r.hfr * r.hfr;
        }
        System.out.println("  disc radius -> HFR (stars):" + sb + String.format(Locale.US, "; at most %d ms a frame", ms));
        double expect = Math.sqrt(sky.sigma0 * sky.sigma0 + 1 / 12.0) * Math.sqrt(Math.PI / 2);
        check(Math.abs(h0 - expect) < 0.15 * expect, String.format(Locale.US, "sharp stars measure %.2f px (a Gaussian of their size: %.2f)", h0, expect));
        check(mono, "the stars' size grows with the blur");
        // a disc of radius rho adds about (2 rho / 3)^2 to the HFR^2
        double slope = (h2[rhos.length - 1] - h2[3]) / (Math.pow(rhos[rhos.length - 1], 2) - Math.pow(rhos[3], 2));
        check(slope > 0.3 && slope < 0.6, String.format(Locale.US, "HFR^2 grows by %.2f per px^2 of disc radius (a uniform disc: 0.44)", slope));
    }

    static void sweeps() {
        Random rnd = new Random(5);
        int within = 0, n = 0;
        double worst = 0;
        int maxFrames = 0;
        for (int trial = 0; trial < 14; trial++) {
            double f0 = trial == 0 ? 0 : trial == 1 ? LENS.max * 0.9 : 0.05 + rnd.nextDouble() * 0.6;
            Sky sky = new Sky(100 + trial, 700 + rnd.nextInt(1400));
            sky.sigma0 = 0.75 + 0.6 * rnd.nextDouble();
            FocusSweep s = FocusSweep.full(LENS, FocusSweep.Kind.STARS, "test");
            int k = 0;
            while (!s.done()) {
                double f = s.next();
                if (Double.isNaN(f)) break;
                FocusMeter.Reading r = read(sky, f, f0, 1000L * trial + k++);
                s.add(f, r.hfr, r.noise, r.stars, r.ok());
            }
            FocusSweep.Result res = s.result();
            double e = Math.abs(res.focus - f0);
            n++;
            if (res.ok && e <= LENS.fine) within++;
            worst = Math.max(worst, res.ok ? e : 9);
            maxFrames = Math.max(maxFrames, res.frames);
            System.out.printf(Locale.US, "    full sweep: best %.3f, found %.3f (%+.3f) in %d frames, %s; stars %.2f px vs %.2f at 0%n", f0, res.focus,
                    res.focus - f0, res.frames, res.how, res.value, res.atZero);
        }
        check(within == n, String.format(Locale.US, "full sweeps: %d of %d within one fine step (%.3f) of the best focus; worst %.3f; at most %d frames",
                within, n, LENS.fine, worst, maxFrames));
        check(maxFrames <= 18, "a full sweep takes at most 18 frames (about 30 seconds of 2-second frames)");
    }

    static void checks() {
        Sky sky = new Sky(7, 1500);
        double f0 = 0.3;
        // the known focus right: two frames and done
        FocusSweep s = FocusSweep.check(LENS, 0.3, LENS.slope, "test");
        run(s, sky, f0, 50);
        FocusSweep.Result r = s.result();
        check(r.ok && r.frames == 2 && Math.abs(r.focus - f0) < 1e-9 && r.check != null && r.check.holds,
                "a focus that still holds is kept after two frames: " + (r.check != null ? r.check.describe() : r.how));
        // moved by two and a half steps: the check sees it and a fine sweep finds it
        double moved = 0.3 + 2.5 * LENS.fine;
        s = FocusSweep.check(LENS, 0.3, LENS.slope, "test");
        run(s, sky, moved, 60);
        r = s.result();
        check(r.ok && r.check != null && !r.check.holds && Math.abs(r.focus - moved) <= LENS.fine, String.format(Locale.US,
                "a focus that moved %.3f is caught (%s) and found again at %.3f (%d frames)", moved - 0.3,
                r.check != null ? r.check.describe() : "no check", r.focus, r.frames));
        // the two-point estimate itself, with a slope a third off
        FocusSweep.Check c = FocusSweep.twoPoint(0.3, 0.2, Math.sqrt(1 + Math.pow(LENS.slope * (0.2 - 0.33), 2)), 0.01, 0.4,
                Math.sqrt(1 + Math.pow(LENS.slope * (0.4 - 0.33), 2)), 0.01, LENS.slope * 1.3, LENS.fine);
        check(Math.abs(c.best - 0.33) < 0.5 * LENS.fine, "the two-point estimate with the slope 30% off: " + c.describe());
    }

    static void run(FocusSweep s, Sky sky, double f0, long seed) {
        int k = 0;
        while (!s.done()) {
            double f = s.next();
            if (Double.isNaN(f)) break;
            FocusMeter.Reading r = read(sky, f, f0, seed * 100 + k++);
            s.add(f, r.hfr, r.noise, r.stars, r.ok());
        }
    }

    /** F7's done-when: after a daylight calibration, the night's sweep lands within one step of the prediction. */
    static void temperature() {
        Random rnd = new Random(9);
        // this lens: best focus 0.22 at 20 C, moving -0.004 per C (focus shifts nearer infinity as it warms)
        double a = 0.22, b = -0.004;
        FocusModel m = new FocusModel();
        double[] temps = {14, 31, 22};
        for (int i = 0; i < temps.length; i++) {
            FocusModel.Cal c = new FocusModel.Cal();
            c.tempC = temps[i];
            // the daylight sweep's own error: a third of a step
            c.focus = a + b * (temps[i] - 20) + LENS.fine / 3 * rnd.nextGaussian();
            c.timeMs = 1_700_000_000_000L + i * 86_400_000L;
            m.addDay(c);
        }
        FocusModel.Line l = m.line();
        check(l != null && Math.abs(l.b - b) < 0.002, "three daylight points spanning 17 C give the line: " + (l != null ? l.describe() : "none"));
        FocusModel two = FocusModel.parse(m.encode());
        check(two.day.size() == 3 && two.line() != null && Math.abs(two.line().b - l.b) < 1e-6, "the calibration survives being saved and read back");
        FocusModel close = new FocusModel();
        for (double t : new double[]{20, 21, 23}) {
            FocusModel.Cal c = new FocusModel.Cal();
            c.focus = a;
            c.tempC = t;
            close.addDay(c);
        }
        check(close.line() == null, "three points within 3 C make no line (it needs 5 C)");
        int ok = 0, n = 0;
        int frames = 0;
        for (int night = 0; night < 8; night++) {
            double t = 4 + 30 * rnd.nextDouble();
            // tonight's true best focus: on the line, give or take a little that daylight can't see
            double f0 = Math.max(0, a + b * (t - 20) + 0.01 * rnd.nextGaussian());
            FocusSweep s = FocusSweep.night(LENS, m, t);
            Sky sky = new Sky(300 + night, 900 + rnd.nextInt(800));
            run(s, sky, f0, 400 + night);
            FocusSweep.Result r = s.result();
            n++;
            double fromPred = Math.abs(r.focus - s.predicted);
            if (r.ok && fromPred <= LENS.fine) ok++;
            frames = Math.max(frames, r.frames);
            System.out.printf(Locale.US, "    night at %.0f C: predicted %.3f, true %.3f, found %.3f in %d frames (%s)%n", t, s.predicted, f0, r.focus,
                    r.frames, r.how);
        }
        check(ok == n, String.format(Locale.US, "after calibrating, %d of %d nights' sweeps land within one step (%.3f) of the prediction", ok, n,
                LENS.fine));
        check(frames <= 11, "a predicted sweep takes at most 11 frames (" + frames + ")");
        // without a line, the last night's focus is checked with two frames
        FocusModel nightOnly = new FocusModel();
        FocusModel.Cal last = new FocusModel.Cal();
        last.focus = 0.25;
        last.slope = LENS.slope;
        nightOnly.addNight(last);
        FocusSweep s = FocusSweep.night(LENS, nightOnly, 18);
        check("check".equals(s.mode), "with only a night's focus: " + s.why);
        check("full".equals(FocusSweep.night(LENS, new FocusModel(), 18).mode), "knowing nothing: a full sweep");
    }

    /**
     * A5's done-when: the session's sweep ends with a frame at the lens's own infinity (0) when it found the best
     * focus away from it, so the log has both measured sizes, the chosen focus's the smaller.
     */
    static void zero() {
        double f0 = 0.24;
        Sky sky = new Sky(77, 1200);
        FocusModel m = new FocusModel();
        for (double t : new double[]{10, 20, 30}) {
            FocusModel.Cal c = new FocusModel.Cal();
            c.focus = f0;
            c.tempC = t;
            m.addDay(c);
        }
        FocusSweep s = FocusSweep.night(LENS, m, 18);
        s.measureZero = true;
        run(s, sky, f0, 700);
        FocusSweep.Result r = s.result();
        java.util.List<FocusSweep.Point> pts = s.points();
        check(r.ok && Math.abs(pts.get(pts.size() - 1).focus) < 1e-9 && r.zeroMeasured && r.atZero > r.value && Math.abs(r.focus - f0) <= LENS.fine,
                String.format(Locale.US, "a predicted sweep ends at 0: stars %.2f px at %.3f against %.2f px at 0, measured (%d frames)", r.value,
                        r.focus, r.atZero, r.frames));
        // a check that holds: three frames, and the stars' size at the focus from the two check frames alone
        FocusModel nightOnly = new FocusModel();
        FocusModel.Cal last = new FocusModel.Cal();
        last.focus = f0;
        last.slope = LENS.slope;
        nightOnly.addNight(last);
        s = FocusSweep.night(LENS, nightOnly, 18);
        s.measureZero = true;
        run(s, sky, f0, 800);
        r = s.result();
        double sharp = 1.1774 * sky.sigma0;
        check(r.ok && r.check != null && r.check.holds && r.frames == 3 && r.zeroMeasured && r.atZero > 1.15 * r.value
                        && Math.abs(r.value - sharp) < 0.2 * sharp, String.format(Locale.US,
                "a check that holds, then 0: stars %.2f px (sharp: %.2f) against %.2f px at 0 (%d frames)", r.value, sharp, r.atZero, r.frames));
        // the best focus at 0 already: no extra frame
        s = FocusSweep.around(LENS, FocusSweep.Kind.STARS, 0.0, 3, "predicted", "test");
        s.measureZero = true;
        run(s, sky, 0.0, 900);
        r = s.result();
        check(r.ok && r.zeroMeasured && r.frames == 4, "a sweep that measured 0 takes no extra frame (" + r.frames + " frames)");
    }

    // ------------------------------------------------------------------------------------------------
    // daylight

    static void daylight() {
        int sw = 640, sh = 480;
        Random r = new Random(21);
        // a distant town: buildings, windows, a skyline, fine texture
        float[] scene = new float[sw * sh];
        for (int i = 0; i < scene.length; i++) scene[i] = 140;
        for (int b = 0; b < 160; b++) {
            int x0 = r.nextInt(sw), w = 4 + r.nextInt(60), y0 = sh / 3 + r.nextInt(sh / 2), hgt = 5 + r.nextInt(sh - y0);
            float v = 40 + r.nextInt(180);
            for (int y = y0; y < Math.min(sh, y0 + hgt); y++) for (int x = x0; x < Math.min(sw, x0 + w); x++) scene[y * sw + x] = v;
            for (int win = 0; win < w * hgt / 60; win++) {
                int wx = x0 + r.nextInt(Math.max(1, w - 2)), wy = y0 + r.nextInt(Math.max(1, hgt - 2));
                for (int y = wy; y < Math.min(sh, wy + 2); y++) for (int x = wx; x < Math.min(sw, wx + 2); x++) scene[y * sw + x] = 230;
            }
        }
        int fails = 0, n = 0;
        double worst = 0;
        double fineDay = LENS.fine / 2;
        for (int trial = 0; trial < 8; trial++) {
            double f0 = 0.04 + 0.6 * r.nextDouble();
            FocusSweep s = FocusSweep.full(LENS, FocusSweep.Kind.DAYLIGHT, "test");
            int k = 0;
            while (!s.done()) {
                double f = s.next();
                if (Double.isNaN(f)) break;
                // three frames a step, each shaken a little, averaged
                double sum = 0, sum2 = 0;
                for (int q = 0; q < 3; q++) {
                    float[] img = blurred(scene, sw, sh, 0.8, rho(f, f0), r.nextDouble() - 0.5, r.nextDouble() - 0.5, r, 1.5);
                    double v = Tenengrad.score(img, sw, sh, 0.6) * (1 + 0.02 * r.nextGaussian());
                    sum += v;
                    sum2 += v * v;
                }
                double mean = sum / 3, sd = Math.sqrt(Math.max(0, sum2 / 3 - mean * mean));
                s.add(f, mean, sd / Math.sqrt(3), 0, mean > 0);
                k++;
            }
            FocusSweep.Result res = s.result();
            double e = Math.abs(res.focus - f0);
            n++;
            if (!res.ok || e > LENS.fine) fails++;
            worst = Math.max(worst, e);
            System.out.printf(Locale.US, "    daylight: best %.3f, found %.3f (%+.3f) in %d steps, %s%n", f0, res.focus, res.focus - f0, k, res.how);
        }
        check(fails == 0, String.format(Locale.US, "daylight sweeps: all %d within one night step (%.3f) of the sharpest focus; worst %.3f", n,
                LENS.fine, worst));
    }

    /** The scene blurred by a Gaussian of sigma and a disc of radius rho, shifted, with noise. */
    static float[] blurred(float[] src, int w, int h, double sigma, double rho, double sx, double sy, Random r, double noise) {
        int k = rho < 0.3 ? 1 : Math.max(8, Math.min(64, (int) Math.ceil(4 * rho * rho)));
        double golden = Math.PI * (3 - Math.sqrt(5));
        int rad = (int) Math.ceil(3 * sigma + rho) + 1;
        int side = 2 * rad + 1;
        double[] ker = new double[side * side];
        double tot = 0;
        for (int q = 0; q < k; q++) {
            double rr = k == 1 ? 0 : rho * Math.sqrt((q + 0.5) / k);
            double cx = rr * Math.cos(q * golden) + sx, cy = rr * Math.sin(q * golden) + sy;
            for (int j = 0; j < side; j++) {
                for (int i = 0; i < side; i++) {
                    double dx = i - rad - cx, dy = j - rad - cy;
                    double v = Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma));
                    ker[j * side + i] += v;
                    tot += v;
                }
            }
        }
        for (int q = 0; q < ker.length; q++) ker[q] /= tot;
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double s = 0;
                for (int j = 0; j < side; j++) {
                    int yy = Math.min(h - 1, Math.max(0, y + j - rad));
                    for (int i = 0; i < side; i++) {
                        double kv = ker[j * side + i];
                        if (kv < 1e-5) continue;
                        int xx = Math.min(w - 1, Math.max(0, x + i - rad));
                        s += kv * src[yy * w + xx];
                    }
                }
                out[y * w + x] = (float) (s + noise * r.nextGaussian());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------

    static void watch() {
        Random r = new Random(31);
        // steady stars for an hour: never
        FocusWatch w = new FocusWatch();
        w.reset(0);
        String due = null;
        for (int i = 0; i < 225; i++) {
            double t = i * 16;
            w.add(t, 1.2 * (1 + 0.04 * r.nextGaussian()), 1);
            String d = w.due(t, 3600);
            if (d != null && due == null) due = d;
        }
        check(due == null, "steady stars for an hour: no check (" + due + ")");
        // the focus creeps from minute 20: stars 25% bigger by minute 50
        w = new FocusWatch();
        w.reset(0);
        due = null;
        double at = -1;
        for (int i = 0; i < 225; i++) {
            double t = i * 16, grow = t < 1200 ? 0 : Math.min(0.25, 0.25 * (t - 1200) / 1800);
            w.add(t, 1.2 * (1 + grow) * (1 + 0.03 * r.nextGaussian()), 1);
            String d = w.due(t, 3600);
            if (d != null && due == null) {
                due = d;
                at = t;
            }
        }
        check(due != null && at > 1200 && at < 3000, String.format(Locale.US, "creeping focus: a check at minute %.0f: %s", at / 60, due));
        // the same growth with the sky dimming (thin cloud): no check
        w = new FocusWatch();
        w.reset(0);
        due = null;
        String haze = "";
        for (int i = 0; i < 225; i++) {
            double t = i * 16, grow = t < 1200 ? 0 : Math.min(0.25, 0.25 * (t - 1200) / 1800);
            w.add(t, 1.2 * (1 + grow) * (1 + 0.03 * r.nextGaussian()), 1 - 2 * grow);
            String d = w.due(t, 3600);
            if (d != null && due == null) due = d;
            if (!w.haze().isEmpty()) haze = w.haze();
        }
        check(due == null && !haze.isEmpty(), "stars swelling as the sky dims: no check (" + haze + ")");
        // one soft patch (a bump, a gust) then back: no check
        w = new FocusWatch();
        w.reset(0);
        due = null;
        for (int i = 0; i < 225; i++) {
            double t = i * 16;
            double h = (i >= 140 && i < 147) ? 1.6 : 1.2 * (1 + 0.03 * r.nextGaussian());
            w.add(t, h, 1);
            String d = w.due(t, 3600);
            if (d != null && due == null) due = d;
        }
        check(due == null, "one soft patch of frames: no check (" + due + ")");
        // a 20-minute session: never
        w = new FocusWatch();
        w.reset(0);
        due = null;
        for (int i = 0; i < 75; i++) {
            double t = i * 16;
            w.add(t, 1.2 * (1 + 0.5 * t / 1200), 1);
            String d = w.due(t, 1200);
            if (d != null) due = d;
        }
        check(due == null, "a 20-minute session is never interrupted");
    }

    static void noStars() {
        Sky sky = new Sky(41, 0);
        FocusSweep s = FocusSweep.full(LENS, FocusSweep.Kind.STARS, "test");
        run(s, sky, 0.3, 90);
        FocusSweep.Result r = s.result();
        check(!r.ok && r.frames == 2, "an overcast sky: given up after two frames (" + r.how + ")");
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  PASS: " : "  FAIL: ") + what);
        if (!ok) failures++;
    }
}
