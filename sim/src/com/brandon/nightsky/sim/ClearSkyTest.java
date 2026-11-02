package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.ClearSky;
import com.brandon.nightsky.processing.Preflight;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.Stats;
import com.brandon.nightsky.processing.UnitProcessor;

import java.util.Locale;

/**
 * Haze on the first night (1.7): the clear sky's zero point worked out from the camera's own numbers (its lens and
 * its noise profile), which clear night a frame is compared with, and the pre-flight check on simulated first nights.
 *
 * <p>The simulated camera is made to match its specs for this test: its stars as bright as the lens, the noise
 * profile and a clear sky make them (the simulator's usual stars are dimmer than that, and its sensor reports no
 * noise profile, so the other tests don't use the estimate).
 * <p>
 * Usage: ClearSkyTest [units|choose|nights] (default all)
 */
public final class ClearSkyTest {
    static int failures;

    public static void main(String[] args) throws Exception {
        String only = args.length > 0 ? args[0] : "";
        if (only.isEmpty() || only.equals("units")) units();
        if (only.isEmpty() || only.equals("choose")) choose();
        if (only.isEmpty() || only.equals("nights")) nights();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        if (failures > 0) System.exit(1);
    }

    static void check(boolean ok, String what) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what);
        if (!ok) failures++;
    }

    static boolean near(double a, double b, double tol) {
        return Math.abs(a - b) <= tol;
    }

    /** A sensor like the simulator's: 10-bit, black 64, one electron per level at ISO 800, read noise 1.6 levels. */
    static SensorSpec sensor() {
        SensorSpec s = new SensorSpec();
        s.width = 4080;
        s.height = 3072;
        s.setCfaFromArrangement(0, 0, 0);
        for (int q = 0; q < 4; q++) s.black[q] = 64;
        s.white = 1023;
        s.focalLengthMm = 6.9f;
        s.fNumber = 1.68f;
        double range = s.range();
        for (int q = 0; q < 4; q++) {
            s.noiseS[q] = (float) (1 / range);
            s.noiseO[q] = (float) (1.6 * 1.6 / (range * range));
        }
        return s;
    }

    static void units() {
        System.out.println("== units");
        check(near(ClearSky.clearExtinction(0), 0.214, 1e-6), String.format(Locale.US, "a clear sky at sea level takes %.3f mag per airmass (0.214)",
                ClearSky.clearExtinction(0)));
        check(near(ClearSky.clearExtinction(2000), 0.1372, 5e-4), String.format(Locale.US, "at 2000 m %.4f (0.1372)", ClearSky.clearExtinction(2000)));
        check(near(ClearSky.airmass(90), 1.0, 1e-3) && near(ClearSky.airmass(30), 1.994, 2e-3) && ClearSky.airmass(-2) >= 38,
                String.format(Locale.US, "airmass at 90, 30 and -2 degrees: %.4f, %.4f, %.1f", ClearSky.airmass(90), ClearSky.airmass(30),
                        ClearSky.airmass(-2)));

        // worked by hand: a 4.107 mm aperture takes in 0.13249 cm²; 1000 photons/s/cm²/Å over 600 Å, a clear sky at
        // airmass 1 at sea level passing 10^(-0.4 x 0.214) = 0.8209: 65255 e/s; a quarter in the box's half-res green
        // image, 92% of the star in the box, 16 s, 1 e per level: 240138 levels; 2.5 log10 = 13.451; at ISO 800
        // that's 13.451 - 2.5 log10(16 x 8) = 8.183 per second at ISO 100
        SensorSpec s = sensor();
        // no sky noise passed: the noise isn't checked
        ClearSky.Estimate e = ClearSky.estimate(s, 16, 800, 1.0, 0, Double.NaN, Double.NaN, Double.NaN);
        System.out.println("  " + e.describe());
        check(e.ok() && near(e.zeroPoint, 13.451, 0.005) && near(e.zeroPointNorm, 8.183, 0.005) && near(e.electronsPerLevel, 1, 1e-6)
                        && Double.isNaN(e.noiseRatio),
                String.format(Locale.US, "worked example: zero point %.3f (13.451), per second at ISO 100 %.3f (8.183)", e.zeroPoint, e.zeroPointNorm));
        // the normalised zero point doesn't depend on the exposure or the ISO (the noise profile scales with the ISO)
        SensorSpec s2 = sensor();
        for (int q = 0; q < 4; q++) {
            s2.noiseS[q] *= 4;
            s2.noiseO[q] *= 16;
        }
        ClearSky.Estimate e2 = ClearSky.estimate(s2, 4, 3200, 1.0, 0, Double.NaN, Double.NaN, Double.NaN);
        check(e2.ok() && near(e2.zeroPointNorm, e.zeroPointNorm, 1e-4) && near(e2.zeroPoint, e.zeroPoint, 1e-4),
                String.format(Locale.US, "4 s at ISO 3200 (four times the gain): %.3f, the same per second at ISO 100 %.3f", e2.zeroPoint, e2.zeroPointNorm));
        // higher in the air and lower in the sky
        ClearSky.Estimate hi = ClearSky.estimate(s, 16, 800, 1.0, 2000, Double.NaN, Double.NaN, Double.NaN);
        ClearSky.Estimate low = ClearSky.estimate(s, 16, 800, 2.0, 0, Double.NaN, Double.NaN, Double.NaN);
        check(near(hi.zeroPointNorm - e.zeroPointNorm, 0.214 - 0.1372, 0.002) && near(e.zeroPointNorm - low.zeroPointNorm, 0.214, 0.002),
                String.format(Locale.US, "at 2000 m %.3f brighter, at airmass 2 %.3f dimmer", hi.zeroPointNorm - e.zeroPointNorm,
                        e.zeroPointNorm - low.zeroPointNorm));
        ClearSky.Estimate dflt = ClearSky.estimate(s, 16, 800, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        check(dflt.ok() && near(dflt.airmass, ClearSky.DEFAULT_AIRMASS, 1e-9) && near(dflt.extinction, ClearSky.clearExtinction(100) * 1.1, 1e-9),
                String.format(Locale.US, "no stars' height or site height: airmass %.2f, %.3f mag at 100 m", dflt.airmass, dflt.extinction));
        // the lens's glow measured: a clean lens scattering 10% of the core's light 4 to 20 pixels out leaves 0.98 / 1.1
        // in the box; one scattering none, 0.98
        ClearSky.Estimate g10 = ClearSky.estimate(s, 16, 800, 1.0, 0, Double.NaN, Double.NaN, 0.10);
        ClearSky.Estimate g0 = ClearSky.estimate(s, 16, 800, 1.0, 0, Double.NaN, Double.NaN, 0);
        check(near(g10.boxShare, 0.98 / 1.1, 1e-9) && near(g0.boxShare, 0.98, 1e-9) && near(e.boxShare, ClearSky.BOX_SHARE, 1e-9)
                        && near(g0.zeroPointNorm - e.zeroPointNorm, 2.5 * Math.log10(0.98 / 0.92), 1e-6),
                String.format(Locale.US, "the star box holds %.3f of the light with a 10%% glow, %.3f with none, %.2f when the glow isn't measured",
                        g10.boxShare, g0.boxShare, e.boxShare));
        // the sky's own noise checks the noise profile: 100 levels of sky in a green pixel at one electron a level, with
        // 1.6 levels of read noise, has sqrt(100 + 2.56) = 10.13 levels of noise
        ClearSky.Estimate n1 = ClearSky.estimate(s, 16, 800, 1.0, 0, 100, 10.127, Double.NaN);
        check(n1.ok() && near(n1.noiseRatio, 1, 0.001), String.format(Locale.US, "sky noise as the profile says: ratio %.3f", n1.noiseRatio));
        ClearSky.Estimate n2 = ClearSky.estimate(s, 16, 800, 1.0, 0, 100, 3 * 10.127, Double.NaN);
        check(!n2.ok() && n2.why.contains("noise") && near(n2.noiseRatio, 3, 0.01),
                "sky noise three times the profile's: no estimate (" + n2.why + ")");
        ClearSky.Estimate n3 = ClearSky.estimate(s, 16, 800, 1.0, 0, 100, 0.4 * 10.127, Double.NaN);
        check(!n3.ok(), "sky noise 0.4 of the profile's: no estimate (" + n3.why + ")");
        greenNoise();
        // what the camera doesn't say
        SensorSpec none = sensor();
        for (int q = 0; q < 4; q++) none.noiseS[q] = none.noiseO[q] = 0;
        ClearSky.Estimate a = ClearSky.estimate(none, 16, 800, 1.0, 0, 100, 10.127, Double.NaN);
        check(!a.ok() && a.why.contains("noise profile"), "no noise profile: " + a.describe());
        SensorSpec noLens = sensor();
        noLens.fNumber = 0;
        ClearSky.Estimate b = ClearSky.estimate(noLens, 16, 800, 1.0, 0, 100, 10.127, Double.NaN);
        check(!b.ok() && b.why.contains("aperture"), "no aperture: " + b.describe());
        SensorSpec silly = sensor();
        for (int q = 0; q < 4; q++) silly.noiseS[q] = 0.5f;
        ClearSky.Estimate c = ClearSky.estimate(silly, 16, 800, 1.0, 0, Double.NaN, Double.NaN, Double.NaN);
        check(!c.ok() && c.why.contains("believable"), "a noise profile of 16 electrons at full scale: " + c.describe());
        ClearSky.Estimate d = ClearSky.estimate(s, 0, 800, 1.0, 0, Double.NaN, Double.NaN, Double.NaN);
        check(!d.ok(), "no exposure: " + d.describe());
    }

    /**
     * The green pixels' noise measured under a sky full of texture: each cell's two green pixels share a level that
     * wanders by 12 levels from cell to cell (faint stars, glow, a gradient) and add their own noise of 3 levels; a few
     * bright stars and clipped pixels are scattered in. The pairs must give the 3 levels, not the texture's.
     */
    static void greenNoise() {
        SensorSpec s = sensor();
        s.width = 1000;
        s.height = 800;
        short[] raw = new short[s.width * s.height];
        java.util.Random rnd = new java.util.Random(5);
        float[] black = {64, 64, 64, 64};
        double texSum = 0;
        int cells = 0;
        for (int j = 0; j < s.height / 2; j++) {
            for (int i = 0; i < s.width / 2; i++) {
                double tex = 40 + 0.05 * i + 12 * rnd.nextGaussian();
                if (rnd.nextDouble() < 0.01) tex += 300 * rnd.nextDouble();
                texSum += tex;
                cells++;
                for (int q = 0; q < 4; q++) {
                    double v = 64 + Math.max(0, tex) + 3 * rnd.nextGaussian();
                    if (rnd.nextDouble() < 0.002) v = 1023;
                    raw[(2 * j + (q >> 1)) * s.width + 2 * i + (q & 1)] = (short) Math.max(0, Math.min(1023, Math.round(v)));
                }
            }
        }
        double[] g = ClearSky.greenNoise(raw, 1f, black, s);
        System.out.printf(Locale.US, "  green pairs: sky %.1f levels (texture's mean %.1f), noise %.2f (3, the texture's spread 12)%n", g[0],
                texSum / cells, g[1]);
        check(near(g[1], 3.0, 0.1) && near(g[0], 52.5, 2.5), "the green pairs measure the pixels' noise, not the sky's texture");
        double[] small = ClearSky.greenNoise(new short[10 * 10], 1f, black, sizedSensor(10, 10));
        check(Double.isNaN(small[0]) && Double.isNaN(small[1]), "too small a frame: not measured");
    }

    static SensorSpec sizedSensor(int w, int h) {
        SensorSpec s = sensor();
        s.width = w;
        s.height = h;
        return s;
    }

    static ClearSky.Estimate prior(double zp) {
        ClearSky.Estimate e = new ClearSky.Estimate();
        e.zeroPointNorm = zp;
        return e;
    }

    static void choose() {
        System.out.println("== choose");
        ClearSky.Baseline b = ClearSky.choose(new double[0], prior(8.2));
        check(b.ok() && b.fromSpecs && near(b.zeroPoint, 8.2, 1e-9), "no clear night yet: the specs");
        b = ClearSky.choose(new double[0], new ClearSky.Estimate());
        check(!b.ok(), "no clear night and no specs: nothing to compare with");
        b = ClearSky.choose(new double[]{7.4}, null);
        check(b.ok() && !b.fromSpecs && near(b.zeroPoint, 7.4, 1e-9), "no specs: the night measured, as before");
        b = ClearSky.choose(new double[]{8.0}, prior(8.2));
        check(!b.fromSpecs && near(b.zeroPoint, 8.0, 1e-9) && b.note.isEmpty(), "one night near the specs: that night");
        b = ClearSky.choose(new double[]{8.0, Double.NaN, 7.3}, prior(8.2));
        check(!b.fromSpecs && near(b.zeroPoint, 8.0, 1e-9), "NaNs are skipped: the clearer night");
        b = ClearSky.choose(new double[]{7.9, 7.2, 8.1, 9.9}, prior(8.2));
        check(!b.fromSpecs && near(b.zeroPoint, 8.1, 1e-9), "four nights: the second clearest (one odd reading can't decide)");
        b = ClearSky.choose(new double[]{9.6}, prior(8.2));
        check(!b.fromSpecs && near(b.zeroPoint, 9.6, 1e-9), "a night clearer than the specs say: the night (the specs were cautious)");
        b = ClearSky.choose(new double[]{6.9}, prior(8.2));
        System.out.println("    " + b.note);
        check(b.fromSpecs && near(b.zeroPoint, 8.2, 1e-9) && b.note.contains("likely hazy"),
                "the first night 1.3 below the specs (likely hazy): the specs until more nights say otherwise");
        b = ClearSky.choose(new double[]{6.9, 7.5, 6.5}, prior(8.2));
        System.out.println("    " + b.note);
        check(!b.fromSpecs && near(b.zeroPoint, 7.5, 1e-9) && b.note.contains("agrees"),
                "three nights, only the clearest near the specs: the clearest (the others were hazy)");
        b = ClearSky.choose(new double[]{7.0, 7.05, 6.95, 6.8}, prior(8.5));
        System.out.println("    " + b.note);
        check(!b.fromSpecs && near(b.zeroPoint, 7.0, 1e-9) && b.note.contains("specs that are off"),
                "four nights agreeing 1.5 below the specs: the nights (the specs are off for this camera)");
        b = ClearSky.choose(new double[]{7.0, 7.05}, prior(8.5));
        check(b.fromSpecs, "two nights agreeing 1.5 below the specs: still the specs (two hazy nights in a row happen)");
        b = ClearSky.choose(new double[]{7.0, 7.05, 6.2}, prior(8.5));
        check(b.fromSpecs, "three nights, two agreeing 1.5 below the specs: still the specs");
        b = ClearSky.choose(new double[]{9.4, 7.1, 6.9}, prior(8.2));
        System.out.println("    " + b.note);
        check(b.fromSpecs && near(b.zeroPoint, 8.2, 1e-9) && b.note.contains("1.2 magnitudes above the camera's specs")
                        && b.note.contains("next clearest (7.10) 1.1 below") && !b.note.contains("likely hazy"),
                "three nights, the clearest 1.2 above the specs and the next 1.1 below: the specs, and the log says why");
    }

    // ---------------------------------------------------------------------------------------------------------
    // whole first nights: the pre-flight check on simulated frames

    static final class Night {
        String name;
        double cloudT = 1;
        double[] learned = new double[0];
        /** The lens the camera says it has (f-number), when it isn't the one the stars came through. */
        double claimedF = 1.68;
        /** The noise profile the camera reports, times the true one. */
        double noiseScale = 1;

        Night(String name) {
            this.name = name;
        }
    }

    static Preflight.Report night(Night n, double starGain) {
        long startMs = SimRunner.DEFAULT_TIME_MS;
        double lon = SimRunner.DEFAULT_LON;
        SkySim sim = PreflightTest.makeSim("clean", startMs, lon);
        sim.starGain = starGain;
        if (n.cloudT < 1) {
            sim.cloudFrom = 0;
            sim.cloudTo = 0;
            sim.cloudT = n.cloudT;
        }
        short[] raw = new short[PreflightTest.W * PreflightTest.H];
        UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
        sim.render(0, raw, info, 7);
        Preflight.Input in = PreflightTest.makeInput("clean", sim, raw, info, startMs, lon, Double.NaN, Double.NaN);
        trueSensor(in.spec, sim);
        in.spec.fNumber = (float) n.claimedF;
        for (int q = 0; q < 4; q++) in.spec.noiseS[q] *= n.noiseScale;
        in.clearZeroPoints = n.learned;
        Preflight.Report r = Preflight.run(in);
        Preflight.Item c = r.item("clouds");
        System.out.printf(Locale.US, "== %s: transparency %.2f (%.2f mag), %d clear nights learned%n", n.name, n.cloudT,
                -2.5 * Math.log10(n.cloudT), n.learned.length);
        System.out.println("  " + (c == null ? "no clouds item" : c.level + " " + c.text));
        System.out.printf(Locale.US, "  zero point %.2f, the specs' clear sky %.2f, haze %.2f%s%n", r.zeroPointNorm, r.clearSkyZeroPoint,
                r.hazeMag, r.hazeFromSpecs ? " (against the specs)" : "");
        System.out.println("  " + r.clearSkyNote);
        return r;
    }

    /** The simulator's sensor as its camera would report it: one electron per level at ISO 700, read noise 1.6. */
    static void trueSensor(SensorSpec spec, SkySim sim) {
        double range = spec.range(), g = spec.iso / 700.0;
        for (int q = 0; q < 4; q++) {
            spec.noiseS[q] = (float) (g / range);
            spec.noiseO[q] = (float) (sim.readNoise * sim.readNoise / (range * range));
        }
    }

    /**
     * The simulator's star brightness that matches its specs: what a 6.9 mm f/1.68 lens and green pixels of
     * {@link ClearSky#GREEN_WIDTH} Å collect from a V = 0 star through a clear sky at the stars' median airmass, at one
     * electron per level, as the simulator's starGain (its stars are fluxZero 10^(-0.4 V) per second, times their
     * green share on the sensor).
     */
    static double physicalStarGain() {
        SkySim sim = PreflightTest.makeSim("clean", SimRunner.DEFAULT_TIME_MS, SimRunner.DEFAULT_LON);
        double[][] tab = sim.starTable(0);
        double[] zc = sim.zenithInCamera(), v = new double[3];
        float[] am = new float[tab.length], cgs = new float[tab.length];
        int n = 0;
        for (double[] r : tab) {
            double mag = -2.5 * Math.log10(r[2] / sim.fluxZero);
            if (mag > 6.0 || r[0] < 16 || r[1] < 16 || r[0] > sim.W - 16 || r[1] > sim.H - 16) continue;
            PreflightTest.truth.unproject(r[0], r[1], v);
            double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            double alt = Math.toDegrees(Math.asin((v[0] * zc[0] + v[1] * zc[1] + v[2] * zc[2]) / len));
            am[n] = (float) ClearSky.airmass(alt);
            cgs[n] = (float) (4 / (r[3] + 2 + r[4]));
            n++;
        }
        double airmass = Stats.median(am, n), cg = Stats.median(cgs, n);
        double apertureMm = 6.9 / 1.68, areaCm2 = Math.PI * Math.pow(apertureMm / 20, 2);
        double electrons = ClearSky.PHOTONS_V0 * ClearSky.GREEN_WIDTH * areaCm2
                * Math.pow(10, -0.4 * ClearSky.clearExtinction(100) * airmass);
        double gain = electrons / cg / sim.fluxZero;
        System.out.printf(Locale.US, "  (%d stars brighter than 6: median airmass %.3f, green share %.3f; %.0f electrons a second from V = 0: "
                + "star gain %.3f)%n", n, airmass, cg, electrons / cg, gain);
        return gain;
    }

    static void nights() throws Exception {
        System.out.println("== first nights");
        PreflightTest.init();
        double gain = physicalStarGain();

        Night clear = new Night("first night, clear");
        Preflight.Report r = night(clear, gain);
        Preflight.Item c = r.item("clouds");
        double clearZp = r.zeroPointNorm;
        check(c != null && c.level == Preflight.Level.OK && c.text.contains("going by the camera's specs") && r.hazeFromSpecs
                        && near(r.hazeMag, 0, 0.3) && near(r.zeroPointNorm, r.clearSkyZeroPoint, 0.3),
                "a clear first night reads clear against the camera's specs (haze within 0.3 of none)");

        // haze from the specs is told only from about 0.9 magnitudes (their 0.4 plus the estimate's slack)
        Night hazy = new Night("first night, 1.25 magnitudes of haze");
        hazy.cloudT = 0.316;
        r = night(hazy, gain);
        c = r.item("clouds");
        check(c != null && c.level == Preflight.Level.NOTE && c.text.startsWith("Some haze") && c.text.contains("about")
                        && r.hazeFromSpecs && near(r.hazeMag, 1.25, 0.3),
                "1.25 magnitudes of haze on the first night: \"Some haze\", about that much");
        Night faint = new Night("first night, half a magnitude of haze");
        faint.cloudT = 0.63;
        r = night(faint, gain);
        c = r.item("clouds");
        check(c != null && c.level == Preflight.Level.OK && c.text.contains("at most a little haze") && r.hazeFromSpecs
                        && near(r.hazeMag, 0.5, 0.3),
                "half a magnitude on the first night: within the estimate's slack, \"at most a little haze\"");

        Night thick = new Night("first night, two magnitudes of haze");
        thick.cloudT = 0.16;
        r = night(thick, gain);
        c = r.item("clouds");
        check(c != null && c.level == Preflight.Level.WARN && c.text.startsWith("Thin cloud or thick haze") && r.hazeFromSpecs
                        && near(r.hazeMag, 1.99, 0.3),
                "two magnitudes of haze on the first night: the thin cloud or thick haze warning");
        Preflight.Clear cl = Preflight.clearEnough(r, Double.NaN);
        check(cl.clear, "  ... but \"only if clear\" still starts (" + cl.seen + ")");

        Night murk = new Night("first night, three and a half magnitudes of haze");
        murk.cloudT = 0.04;
        r = night(murk, gain);
        cl = Preflight.clearEnough(r, Double.NaN);
        check(!cl.clear, "three and a half magnitudes: \"only if clear\" waits (" + cl.seen + ")");

        Night learnedClear = new Night("a clear night learned, a magnitude of haze tonight");
        learnedClear.cloudT = 0.4;
        learnedClear.learned = new double[]{clearZp};
        r = night(learnedClear, gain);
        c = r.item("clouds");
        check(c != null && c.level == Preflight.Level.NOTE && c.text.startsWith("A little haze") && !r.hazeFromSpecs
                        && near(r.hazeMag, 0.99, 0.2),
                "against a measured clear night: \"A little haze\", as before");

        // a hazy first night measured: the next hazy night must not read as clear
        Night hazyAgain = new Night("first night 1.5 mag hazy (learned), as hazy tonight");
        hazyAgain.cloudT = 0.25;
        Preflight.Report first = night(hazyAgain, gain);
        hazyAgain.learned = new double[]{first.zeroPointNorm};
        r = night(hazyAgain, gain);
        c = r.item("clouds");
        check(r.hazeFromSpecs && near(r.hazeMag, 1.5, 0.3) && c != null && c.level == Preflight.Level.NOTE && c.text.startsWith("Some haze")
                        && r.clearSkyNote.contains("likely hazy"),
                "a hazy night learned isn't taken for clear: the next hazy night still shows its haze");

        // the specs 1.5 magnitudes too hopeful for this camera (it says f/0.84): three clear nights settle it
        Night off = new Night("specs 1.5 mag off, one clear night learned");
        off.claimedF = 1.68 * Math.pow(10, -1.5 / 5);
        off.learned = new double[]{clearZp};
        r = night(off, gain);
        c = r.item("clouds");
        check(r.hazeFromSpecs && near(r.hazeMag, 1.5, 0.3) && c != null && c.level == Preflight.Level.NOTE,
                "specs 1.5 mag off and one clear night: some haze is shown (until three nights agree)");
        Night off3 = new Night("specs 1.5 mag off, three clear nights learned");
        off3.claimedF = off.claimedF;
        off3.learned = new double[]{clearZp, clearZp - 0.05, clearZp + 0.04};
        r = night(off3, gain);
        c = r.item("clouds");
        check(!r.hazeFromSpecs && near(r.hazeMag, 0, 0.15) && c != null && c.level == Preflight.Level.OK && r.clearSkyNote.contains("specs that are off"),
                "three clear nights agreeing: clear, against them");

        // a noise profile that doesn't match the sky's own noise: not trusted
        Night noisy = new Night("noise profile four times the sky's");
        noisy.noiseScale = 4;
        noisy.cloudT = 0.4;
        r = night(noisy, gain);
        c = r.item("clouds");
        check(Double.isNaN(r.clearSkyZeroPoint) && Double.isNaN(r.hazeMag) && c != null && c.level == Preflight.Level.OK
                        && c.text.contains("haze not measured yet") && r.clearSkyNote.contains("noise"),
                "a noise profile the sky disagrees with: no estimate, haze not measured (as before 1.7)");

        // the simulator's usual (dimmer) stars and no noise profile: the other tests see no estimate
        SkySim sim = PreflightTest.makeSim("clean", SimRunner.DEFAULT_TIME_MS, SimRunner.DEFAULT_LON);
        check(!ClearSky.estimate(sim.spec(true), 8, 700, 1, 0, Double.NaN, Double.NaN, Double.NaN).ok(),
                "the simulator's camera reports no noise profile unless a test gives it one");
    }
}
