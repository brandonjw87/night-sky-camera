package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.Preflight;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.UnitProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The pre-flight check on simulated test frames: a clean clear sky must pass with no warning, and a smudged
 * lens, a branch, half the sky clouded, an overcast sky, the Moon in the frame and twilight must each give
 * their own warning and no other.
 * <p>
 * Usage: PreflightTest [case ...] (clean, smudge, branch, halfcloud, somecloud, overcast, haze, moon, twilight,
 * compass, noplace, staleplace, pattern, gradient, transients, sparse, city, milkyway; default all)
 */
public final class PreflightTest {
    static final int W = 4080, H = 3072;
    static BrightStars catalog;
    static CameraModel truth;
    static int failures;

    public static void main(String[] args) throws Exception {
        catalog = SimRunner.loadCatalog();
        truth = CameraModel.nominal(W, H, 2875.0);
        truth.cx = W / 2.0 + 14.0;
        truth.cy = H / 2.0 - 9.0;
        truth.k1 = -0.045;
        truth.k2 = 0.018;
        truth.source = "truth";
        List<String> cases = new ArrayList<String>();
        for (String a : args) cases.add(a);
        if (cases.isEmpty()) {
            String[] all = {"clean", "smudge", "branch", "halfcloud", "somecloud", "overcast", "haze", "moon", "twilight", "compass",
                    "noplace", "staleplace", "pattern", "gradient", "transients", "sparse", "city", "milkyway"};
            for (String a : all) cases.add(a);
        }
        // the clean lens's halo and a clear night's zero point, as the app learns them from a good session
        double baseline = Double.NaN, clearZp = Double.NaN;
        for (String c : cases) {
            Preflight.Report r = run(c, baseline, clearZp);
            if (c.equals("clean") && Double.isNaN(baseline)) {
                baseline = r.haloRatio;
                clearZp = r.zeroPointNorm;
                System.out.printf(Locale.US, "  (learned: clean halo %.3f, clear zero point %.2f)%n", baseline, clearZp);
            }
        }
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        if (failures > 0) System.exit(1);
    }

    static Preflight.Report run(String name, double haloBaseline, double clearZp) {
        long startMs = name.equals("milkyway") ? 1786762800000L : SimRunner.DEFAULT_TIME_MS; // 2026-08-15 03:00 UTC
        double lon = SimRunner.DEFAULT_LON;
        if (name.equals("moon")) startMs = moonHighTime(startMs, lon);
        if (name.equals("twilight")) startMs = twilightTime(startMs, lon, -8);
        SkySim sim = new SkySim(W, H, truth, 42, catalog, startMs, lon);
        sim.psfSigma = 0.85;
        sim.frameExp = 8;
        if (name.equals("smudge")) {
            sim.haloFrac = 0.35;
            sim.haloSigma = 10;
        }
        if (name.equals("branch")) sim.branch = true;
        if (name.equals("halfcloud") || name.equals("somecloud")) {
            // cloud over the left 60% (or 30%) of the frame, thick enough to hide most stars
            sim.partialFrom = 0;
            sim.partialTo = 0;
            sim.partialEdge = name.equals("halfcloud") ? 0.6 : 0.3;
            sim.partialWidth = 0.04;
        }
        if (name.equals("overcast")) {
            sim.cloudFrom = 0;
            sim.cloudTo = 0;
            sim.cloudT = 0.02;
        }
        if (name.equals("haze")) {
            // thin cloud all over: a quarter of the light gets through
            sim.cloudFrom = 0;
            sim.cloudTo = 0;
            sim.cloudT = 0.25;
        }
        if (name.equals("twilight")) {
            // twilight glow: a much brighter sky
            for (int c = 0; c < 3; c++) sim.skyRate[c] *= 30;
        }
        if (name.equals("city")) for (int c = 0; c < 3; c++) sim.skyRate[c] *= 15;
        if (name.equals("pattern")) {
            sim.fpnSigma = 2;
            sim.fpnBlotch = 1;
            sim.rowBandSigma = 1;
            sim.rowFixedSigma = 0.5;
            sim.colFixedSigma = 0.5;
        }
        if (name.equals("gradient")) sim.gradient = 0.8;
        if (name.equals("transients")) {
            sim.satelliteFrame = 0;
            sim.planeFrame = 0;
            sim.meteorFrame = 0;
        }
        if (name.equals("sparse")) sim.starGain = 0.1;
        short[] raw = new short[W * H];
        UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
        long t0 = System.currentTimeMillis();
        sim.render(0, raw, info, 7);
        long t1 = System.currentTimeMillis();
        SensorSpec spec = sim.spec(true);
        Preflight.Input in = new Preflight.Input();
        in.raw = raw;
        in.spec = spec;
        in.black = info.black;
        in.exposureS = info.exposureNs / 1e9;
        in.iso = info.iso;
        in.planExposureS = in.exposureS;
        in.planIso = in.iso;
        in.sessionSeconds = 300;
        in.catalog = catalog;
        boolean fresh = name.equals("clean") || name.equals("noplace") || name.equals("staleplace") || name.equals("sparse");
        in.haloBaseline = fresh ? Double.NaN : haloBaseline;
        in.clearZeroPoint = fresh ? Double.NaN : clearZp;
        SessionSky sky = new SessionSky();
        if (name.equals("staleplace")) {
            // the phone's last fix is from days ago, far away (before a trip): the stars must win
            sky.latDeg = sim.latitudeDeg - 15;
            sky.lonDeg = lon + 40;
            sky.locationSource = "saved";
            sky.locationAgeMin = 3 * 24 * 60;
        } else if (!name.equals("noplace")) {
            sky.latDeg = sim.latitudeDeg;
            sky.lonDeg = lon;
            sky.locationSource = "simulation";
            sky.locationAgeMin = 5;
        }
        sky.clockWallMs = startMs;
        sky.clockTimestampNs = 1_000_000_000L;
        sky.clockValid = true;
        if (name.equals("compass")) {
            // no place: name sides with the compass only (and a branch to name)
            sky.latDeg = Double.NaN;
            sky.lonDeg = Double.NaN;
            sky.headingTopDeg = sim.headingTopDeg();
            sim.branch = true;
            sim.render(0, raw, info, 7);
            in.catalog = null;
        }
        in.sky = sky;
        in.timestampNs = info.timestampNs;
        double[] zc = sim.zenithInCamera();
        // camera x = phone -y, camera y = phone -x, camera z = phone -z
        in.up = new float[]{(float) (-zc[1] * 9.81), (float) (-zc[0] * 9.81), (float) (-zc[2] * 9.81)};
        in.freeBytes = 40_000_000_000L;
        in.needBytes = 1_500_000_000L;
        in.batteryPct = 80;
        in.batteryTempC = 18;
        Preflight.Report r = Preflight.run(in);
        System.out.printf(Locale.US, "== %s (render %d ms, check %d ms)%n", name, t1 - t0, r.millis);
        System.out.println(indent(r.describe()));
        System.out.println("  spoken: " + r.spoken());
        System.out.printf(Locale.US, "  stars %d, halo %.3f (%d stars, limit %.3f), seen %d/%d, obstructed %.1f%% at %.0f deg (%s), "
                        + "zp %.2f haze %.2f, sun %.1f, moon %.1f (%.0f%%, %.1f deg from frame), place %.2f,%.2f (%s)%n",
                r.stars, r.haloRatio, r.haloStars, r.haloLimit, r.seen, r.expected, 100 * r.obstructedFraction,
                r.obstructionAzimuthDeg, r.obstructionSide, r.zeroPointNorm, r.hazeMag, r.sunAltDeg, r.moonAltDeg,
                100 * r.moonIllum, r.moonFromFrameDeg, r.latDeg, r.lonDeg, r.placeSource);
        // expectations: which checks warn
        String want;
        switch (name) {
            case "smudge": want = "lens"; break;
            case "branch": want = "obstruction"; break;
            case "compass": want = "obstruction"; break;
            case "halfcloud": want = "clouds"; break;
            case "overcast": want = "clouds"; break;
            case "haze": want = "clouds"; break;
            case "moon": want = "moon"; break;
            case "twilight": want = "sun"; break;
            default: want = ""; break;
        }
        StringBuilder got = new StringBuilder();
        for (Preflight.Item it : r.items) {
            if (it.level == Preflight.Level.WARN || it.level == Preflight.Level.STOP) {
                if (got.length() > 0) got.append(',');
                got.append(it.key);
            }
        }
        boolean ok = got.toString().equals(want);
        if (ok && (name.equals("branch") || name.equals("compass"))) {
            // the branch hangs over the frame's left edge: the phone's top, which the sim points at headingTopDeg
            double d = Math.abs(((r.obstructionAzimuthDeg - sim.headingTopDeg()) % 360 + 540) % 360 - 180);
            ok = d < 30;
            if (!ok) System.out.printf(Locale.US, "  branch side %.0f deg, expected about %.0f%n", r.obstructionAzimuthDeg, sim.headingTopDeg());
        }
        if (ok && name.equals("somecloud")) {
            // a little cloud is named, without a warning
            Preflight.Item c = r.item("clouds");
            ok = c != null && c.level == Preflight.Level.NOTE && c.text.contains("cloud");
        }
        if (ok && (name.equals("noplace") || name.equals("staleplace"))) {
            ok = Math.abs(r.latDeg - sim.latitudeDeg) < 4 && Math.abs(r.lonDeg - lon) < 5 && r.placeSource.startsWith("stars");
        }
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": warnings [" + got + "], expected [" + want + "]");
        if (!ok) failures++;
        return r;
    }

    static String indent(String s) {
        return "  " + s.replace("\n", "\n  ");
    }

    /** A time near startMs when the Moon is high (within 15 degrees of the zenith) and at least half lit. */
    static long moonHighTime(long startMs, double lonDeg) {
        double lat = 40 * Astro.DEG, lon = lonDeg * Astro.DEG;
        long best = startMs;
        double bestAlt = -99;
        for (long t = startMs; t < startMs + 30L * 86400_000L; t += 600_000L) {
            double[] m = Astro.moonEnu(t, lat, lon);
            double alt = Astro.altitude(m) / Astro.DEG;
            double illum = Astro.moonIllumination(Astro.jdTt(Astro.jdUtc(t)));
            double sun = Astro.altitude(Astro.sunEnu(t, lat, lon)) / Astro.DEG;
            if (illum > 0.5 && sun < -18 && alt > bestAlt) {
                bestAlt = alt;
                best = t;
                if (alt > 75) break;
            }
        }
        return best;
    }

    /** The first time after startMs (going back up to a day) when the Sun is at altDeg and setting. */
    static long twilightTime(long startMs, double lonDeg, double altDeg) {
        double lat = 40 * Astro.DEG, lon = lonDeg * Astro.DEG;
        for (long t = startMs - 86400_000L; t < startMs + 86400_000L; t += 60_000L) {
            double a = Astro.altitude(Astro.sunEnu(t, lat, lon)) / Astro.DEG;
            double b = Astro.altitude(Astro.sunEnu(t + 60_000L, lat, lon)) / Astro.DEG;
            if (a >= altDeg && b < altDeg) return t;
        }
        return startMs;
    }
}
