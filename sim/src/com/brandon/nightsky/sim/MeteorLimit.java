package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.MeteorShowers;
import com.brandon.nightsky.processing.SatMatch;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * How faint a meteor the camera catches (1.5), measured: synthetic sessions on the Geminids' peak night with a
 * Geminid in every other frame, each at its brightest of a magnitude from a ladder (0 to 4), at the speed across
 * the sky its speed in the air gives it where it is. Counts which were found in their frame and which were called
 * meteors, by magnitude as it would be for a meteor crossing at 10 degrees a second (a faster one spreads the same
 * light thinner along the picture, as a fainter slow one would), and finds the magnitude at which half are caught.
 * The Tonight card's camera rate ({@code Tonight.CAMERA_MAG_DARK}, {@code CAMERA_MAG_SUBURB}) comes from it. A
 * measurement, not a pass/fail test.
 * <p>
 * Usage: MeteorLimit outDir [scale=2] [frames=37] [sessions=4] [seed=1] [sky=1]
 * <p>
 * sky scales the sky's glow per pixel (the simulator's pixel gets as much at any scale, so at full size its sky is
 * a suburban one, about 20.4 magnitudes per square arcsecond as the app measures it, and at half size a dark site's,
 * about 21.9; at full size, 0.25 makes it a dark site's).
 */
public final class MeteorLimit {
    static final double[] LADDER = {0, 0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4};

    /** One meteor drawn, and what became of it. */
    static final class Try {
        double mag, speedDegS, durationSec, density, lengthPx;
        boolean found, called;

        /** Its magnitude as a meteor at 10 degrees a second would have to be to leave as thin a trail. */
        double at10() {
            return mag + 2.5 * Math.log10(speedDegS / 10);
        }
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "meteor-limit");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 37;
        int sessions = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        long seed = args.length > 4 ? Long.parseLong(args[4]) : 1;
        double skyFactor = args.length > 5 ? Double.parseDouble(args[5]) : 1;
        out.mkdirs();
        BrightStars catalog = SimRunner.loadCatalog();
        long startMs = MeteorSessionTest.geminidNight();
        MeteorShowers.Shower gem = MeteorShowers.byCode("GEM");
        List<Try> all = new ArrayList<Try>();
        int rung = 0;
        for (int k = 0; k < sessions; k++) {
            SkySim sim = SatSessionTest.newSim(scale, startMs, catalog);
            sim.planeFrame = frames / 3;
            for (int c = 0; c < 3; c++) sim.skyRate[c] *= skyFactor;
            Random rnd = new Random(seed * 1000 + k);
            List<MeteorSessionTest.Truth> truth = new ArrayList<MeteorSessionTest.Truth>();
            // every other frame (odd ones in every other session), clear of the plane and the session's ends
            for (int f = 2 + k % 2; f < frames - 2; f += 2) {
                if (f >= sim.planeFrame - 1 && f <= sim.planeFrame + 2) continue;
                MeteorSessionTest.Truth t = MeteorSessionTest.member(sim, f, gem, rnd, frames, LADDER[rung++ % LADDER.length]);
                if (t == null) continue;
                truth.add(t);
                sim.meteors.add(t.m);
            }
            sim.steadyNoise = true;
            SatSessionTest.Result r = SatSessionTest.run(new File(out, "s" + k), sim, frames, seed + k, startMs, catalog, null, true, 0, 30, false);
            for (MeteorSessionTest.Truth t : truth) {
                Try a = new Try();
                a.mag = t.m.peakMag;
                a.speedDegS = t.m.speedDegS;
                a.durationSec = t.m.durationSec;
                a.density = t.m.peakDensity[t.frame];
                double[][] path = MeteorSessionTest.truthPath(r, t);
                a.lengthPx = path == null ? 0 : SatSessionTest.pathLength(path);
                SatMatch.Streak s = path == null ? null : SatSessionTest.streakOn(r.out.streaks, t.frame, path);
                a.found = s != null;
                a.called = s != null && "meteor".equals(s.kind) && s.sat == null && MeteorSessionTest.logged(r, s) != null;
                if (path != null) all.add(a);
                System.out.printf(Locale.US, "  frame %2d  mag %+.1f  %4.1f deg/s for %.2f s  %4.0f px  density %6.1f  as at 10 deg/s %+.2f: %s%n",
                        t.frame, a.mag, a.speedDegS, a.durationSec, a.lengthPx, a.density, a.at10(),
                        a.called ? "a meteor" : a.found ? "found, called " + (s.kind.isEmpty() ? "unknown" : s.kind) : "not found");
            }
        }
        // by magnitude as drawn, and as at 10 degrees a second
        System.out.println("-- by magnitude as drawn");
        table(all, false);
        System.out.println("-- by magnitude as at 10 deg/s");
        double half = table(all, true);
        System.out.printf(Locale.US, "half caught at magnitude %+.2f (as at 10 deg/s; scale %d, sky x%.2f)%n", half, scale, skyFactor);
    }

    /** Found and called by half-magnitude bins; the magnitude where the called fraction falls through a half. */
    static double table(List<Try> all, boolean at10) {
        double lo = -0.25, step = 0.5;
        int bins = 11;
        int[] n = new int[bins], found = new int[bins], called = new int[bins];
        for (Try a : all) {
            double m = at10 ? a.at10() : a.mag;
            int b = (int) Math.floor((m - lo) / step);
            if (b < 0 || b >= bins) continue;
            n[b]++;
            if (a.found) found[b]++;
            if (a.called) called[b]++;
        }
        double half = Double.NaN, prevM = Double.NaN, prevF = Double.NaN;
        for (int b = 0; b < bins; b++) {
            if (n[b] == 0) continue;
            double m = lo + (b + 0.5) * step, fr = called[b] / (double) n[b];
            System.out.printf(Locale.US, "  mag %+.1f: %2d drawn, %2d found, %2d called meteors (%3.0f%%)%n", m, n[b], found[b], called[b], 100 * fr);
            if (Double.isNaN(half) && !Double.isNaN(prevF) && prevF >= 0.5 && fr < 0.5)
                half = prevM + (m - prevM) * (prevF - 0.5) / (prevF - fr);
            prevM = m;
            prevF = fr;
        }
        return half;
    }
}
