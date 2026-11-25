package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.MoonFinder;
import com.brandon.nightsky.processing.MoonGeo;
import com.brandon.nightsky.processing.MoonMeter;
import com.brandon.nightsky.processing.SensorSpec;

import java.nio.ShortBuffer;
import java.util.Locale;
import java.util.Random;

/**
 * The Moonshot camera's own steps (3.0), off the phone: finding the Moon in a whole telephoto frame (with a street
 * light brighter than it elsewhere in the picture), following it, the crop around it, and the exposure settling on
 * the Moon's bright parts from a first guess that is far off (a camera whose ISO isn't the textbook's), from too
 * dark and from clipped.
 * <p>
 * Usage: MoonCamTest
 */
public final class MoonCamTest {
    static int failed;

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    public static void main(String[] args) {
        SensorSpec spec = MoonshotTest.spec();
        int W = 1360, H = 1020;
        System.out.println("== finding the Moon in a whole frame");
        long t = MoonshotTest.find(MoonshotTest.utc(2026, 12, 1, 0, 0), 0.55, 0.75, 30);
        MoonGeo.View v = MoonGeo.view(t, MoonshotTest.LAT, MoonshotTest.LON);
        MoonSim sim = new MoonSim(t, MoonshotTest.LAT, MoonshotTest.LON, MoonshotTest.SCALE, v.q, 4);
        double mx = 900.3, my = 400.7;
        short[] raw = sim.frame(W, H, 0, 0, mx, my, 0, 0.3, 0.9, new Random(3));
        // a street light: small, and clipped
        for (int j = -3; j <= 3; j++) for (int i = -3; i <= 3; i++) raw[(900 + j) * W + 200 + i] = (short) 1023;
        ShortBuffer buf = ShortBuffer.wrap(raw);
        long t0 = System.nanoTime();
        MoonFinder.Spot s = MoonFinder.find(buf, W, W, H, spec, sim.rPx, null, 0);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.println("  " + s + String.format(Locale.US, " [%.1f ms]", ms));
        // a gibbous Moon's lit part: its middle is off the disk's towards the Sun, its extent the full width one way
        check("found the Moon, not the street light", s.ok && Math.hypot(s.x - mx, s.y - my) < 0.5 * sim.rPx,
                String.format(Locale.US, "at %.0f,%.0f, the Moon at %.0f,%.0f", s.x, s.y, mx, my));
        check("its size about right", Math.abs(s.r / sim.rPx - 1) < 0.25, String.format(Locale.US, "%.1f against %.1f", s.r, sim.rPx));
        check("its bright parts measured", s.level > 0.2 && s.level < 0.98 && s.clipped < 0.001, String.format(Locale.US, "%.3f", s.level));
        MoonFinder.Spot s2 = MoonFinder.find(buf, W, W, H, spec, sim.rPx, s, (int) (3 * sim.rPx));
        check("followed from where it was", s2.ok && Math.hypot(s2.x - s.x, s2.y - s.y) < 4, s2.toString());
        int side = MoonFinder.side(sim.rPx);
        int[] c = MoonFinder.corner(s.x, s.y, side, W, H);
        check("the crop even-placed, inside, the Moon in it", c[0] % 2 == 0 && c[1] % 2 == 0 && c[0] >= 0 && c[0] + side <= W
                && mx - sim.rPx > c[0] && mx + sim.rPx < c[0] + side && my - sim.rPx > c[1] && my + sim.rPx < c[1] + side, side + " px at " + c[0] + "," + c[1]);
        check("quick enough for 30 frames a second", ms < 15, String.format(Locale.US, "%.1f ms", ms));
        // a frame with no Moon: just the light
        short[] dark = new short[W * H];
        Random r = new Random(4);
        for (int k = 0; k < dark.length; k++) dark[k] = (short) (64 + Math.round(1.5 * r.nextGaussian()));
        for (int j = -3; j <= 3; j++) for (int i = -3; i <= 3; i++) dark[(500 + j) * W + 700 + i] = (short) 1023;
        check("no Moon: none found (the light is too small)", !MoonFinder.find(ShortBuffer.wrap(dark), W, W, H, spec, sim.rPx, null, 0).ok, "");

        System.out.println("== the exposure");
        MoonMeter.Limits lim = new MoonMeter.Limits();
        lim.minIso = 40;
        lim.maxIso = 6400;
        lim.minNs = 20_000;
        lim.maxNs = 200_000_000L;
        MoonMeter.Exposure e0 = MoonMeter.start(v.phase, v.alt, 2.8, lim);
        System.out.println(String.format(Locale.US, "  first guess for a %.0f%% Moon %.0f degrees up: %s", v.illum * 100, v.alt, e0));
        double full = MoonMeter.brightness(0, 90), quarter = MoonMeter.brightness(90, 90), low = MoonMeter.brightness(0, 10);
        check("a quarter Moon's bright parts about a third of a full Moon's", quarter / full > 0.2 && quarter / full < 0.45,
                String.format(Locale.US, "%.2f", quarter / full));
        check("10 degrees up: dimmed by the air (5.6 airmasses)", low / full < 0.6 && low / full > 0.35, String.format(Locale.US, "%.2f", low / full));
        MoonMeter.Exposure ef = MoonMeter.start(0, 60, 2.8, lim);
        check("a full Moon: about 1/650 s at ISO 100 (2.4 times the Looney 11 light, for the RAW's top third)", ef.light() > 0.08 && ef.light() < 0.25, ef.toString());
        // a camera twice as sensitive as the textbook, and one at half: each settles within 5 frames
        for (double cam : new double[]{2.3, 0.45}) {
            for (double startScale : new double[]{1, 0.05, 20}) {
                MoonMeter.Exposure e = new MoonMeter.Exposure(e0.ns, e0.iso).scaled(startScale, lim);
                int n = 0;
                double level = 0, clip = 0;
                for (; n < 12; n++) {
                    double trueLevel = cam * 32.9 * MoonMeter.brightness(v.phase, v.alt) * e.light() / (2.8 * 2.8);
                    level = Math.min(1, trueLevel);
                    clip = trueLevel > 1.0 ? 0.2 : trueLevel > 0.95 ? 0.01 : 0;
                    if (MoonMeter.settled(level, clip)) break;
                    e = MoonMeter.adjust(e, level, clip, lim);
                }
                check(String.format(Locale.US, "a camera %.2fx the textbook, starting %sx: settled", cam, startScale == 1 ? "1" : String.valueOf(startScale)),
                        n <= 6 && MoonMeter.settled(level, clip), String.format(Locale.US, "%d steps, %s, level %.2f", n, e, level));
            }
        }
        MoonMeter.Exposure dim = MoonMeter.split(5, lim);
        check("a lot of light needed: the ISO first, then the time to 1/125 s at most", dim.ns <= MoonMeter.LONG_NS && dim.iso >= MoonMeter.ISO_CAP, dim.toString());
        MoonMeter.Exposure bright = MoonMeter.split(0.02, lim);
        check("little light needed: the lowest ISO, shorter than 1/1000 s", bright.iso == lim.minIso && bright.ns < MoonMeter.SHORT_NS, bright.toString());
        System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
        if (failed > 0) System.exit(1);
    }
}
