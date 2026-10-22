package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Preflight;
import com.brandon.nightsky.processing.SkyReading;
import com.brandon.nightsky.processing.UnitProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Sky readings (H3) on simulated frames: three readings of three frames each, back to back under a clean sky,
 * must agree within 0.15 mag/arcsec² and match the simulation's sky; a city sky likewise; an overcast sky gives
 * no reading; the meter offset is added; and every CSV row has the header's columns.
 * <p>
 * Usage: ReadingTest
 */
public final class ReadingTest {
    static int failures;

    public static void main(String[] args) throws Exception {
        PreflightTest.init();
        // a clean sky: three readings, back to back
        List<SkyReading> clean = new ArrayList<SkyReading>();
        SkySim sim = PreflightTest.makeSim("clean", SimRunner.DEFAULT_TIME_MS, SimRunner.DEFAULT_LON);
        double truth = PreflightTest.trueSky(sim);
        for (int k = 0; k < 3; k++) clean.add(reading("clean", sim, 3 * k, 3, 0));
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (SkyReading r : clean) {
            lo = Math.min(lo, r.meterMag);
            hi = Math.max(hi, r.meterMag);
            check(r.frames == 3, "clean: all three frames measured");
            check(r.spread < 0.15, String.format(Locale.US, "clean: frames agree within 0.15 (%.3f)", r.spread));
            check(Math.abs(r.skyMag - truth) < 0.15, String.format(Locale.US, "clean: between the stars %.2f, simulated %.2f", r.skyMag, truth));
            check(r.meterMag < r.skyMag, "clean: brighter with the stars");
            boolean moonUp = r.moonAltDeg > 0 && !(r.moonIllum < 0.02);
            check(r.valid == !moonUp, "clean: counts unless the Moon is up (" + r.why + ")");
        }
        check(hi - lo < 0.15, String.format(Locale.US, "clean: three readings agree within 0.15 (%.3f)", hi - lo));

        // a city sky, 15 times brighter: 2.9 magnitudes brighter
        SkySim city = PreflightTest.makeSim("city", SimRunner.DEFAULT_TIME_MS, SimRunner.DEFAULT_LON);
        SkyReading c = reading("city", city, 0, 3, 0);
        double cityTruth = PreflightTest.trueSky(city);
        check(c.frames == 3 && c.spread < 0.15, String.format(Locale.US, "city: three frames within 0.15 (%.3f)", c.spread));
        check(Math.abs(c.skyMag - cityTruth) < 0.15, String.format(Locale.US, "city: between the stars %.2f, simulated %.2f", c.skyMag, cityTruth));
        check(Math.abs((clean.get(0).skyMag - c.skyMag) - 2.5 * Math.log10(15)) < 0.2, "city: about 2.9 brighter than the clean sky");

        // overcast: the first frame has nothing to measure against, so the reading stops there
        SkySim oc = PreflightTest.makeSim("overcast", SimRunner.DEFAULT_TIME_MS, SimRunner.DEFAULT_LON);
        List<Preflight.Report> one = new ArrayList<Preflight.Report>();
        one.add(frame("overcast", oc, 0));
        check(!SkyReading.measured(one.get(0)), "overcast: the frame isn't measured");
        SkyReading o = SkyReading.of(one, SimRunner.DEFAULT_TIME_MS, 0, "");
        check(o.frames == 0 && !o.valid && o.headline.equals("No reading"), "overcast: no reading (" + o.why + ")");
        print("overcast", o);

        // a lost frame: the reason is kept
        SkyReading lost = SkyReading.of(new ArrayList<Preflight.Report>(), SimRunner.DEFAULT_TIME_MS, 0,
                "the test frame was lost, so the sky wasn't checked");
        check(lost.why.startsWith("the test frame was lost"), "lost: says why");

        // a meter offset is added to both values
        SkyReading shifted = SkyReading.of(lastFrames, SimRunner.DEFAULT_TIME_MS, 0.2, "");
        SkyReading plain = SkyReading.of(lastFrames, SimRunner.DEFAULT_TIME_MS, 0, "");
        check(Math.abs(shifted.meterMag - plain.meterMag - 0.2) < 1e-9 && Math.abs(shifted.skyMag - plain.skyMag - 0.2) < 1e-9,
                "offset: added to both values");
        check(shifted.lines.get(shifted.lines.size() - 1).contains("+0.20"), "offset: said");

        // the log: every row has the header's columns
        int cols = split(SkyReading.csvHeader()).size();
        for (SkyReading r : new SkyReading[]{clean.get(0), c, o, lost, shifted}) {
            String row = r.csvRow(TimeZone.getTimeZone("America/Chicago"));
            int n = split(row).size();
            check(n == cols, "csv: " + n + " columns, the header has " + cols + ": " + row);
        }
        System.out.println(SkyReading.csvHeader());
        System.out.println(clean.get(0).csvRow(TimeZone.getTimeZone("America/Chicago")));
        System.out.println(o.csvRow(TimeZone.getTimeZone("America/Chicago")));
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        if (failures > 0) System.exit(1);
    }

    /** The frames of the last reading made, for the offset check. */
    static List<Preflight.Report> lastFrames;

    /** A reading of {@code n} frames from frame {@code first} on. */
    static SkyReading reading(String name, SkySim sim, int first, int n, double offset) {
        List<Preflight.Report> reps = new ArrayList<Preflight.Report>();
        for (int i = first; i < first + n; i++) reps.add(frame(name, sim, i));
        lastFrames = reps;
        SkyReading r = SkyReading.of(reps, SimRunner.DEFAULT_TIME_MS + first * 10_000L, offset, "");
        print(name + " frames " + first + "-" + (first + n - 1), r);
        return r;
    }

    static Preflight.Report frame(String name, SkySim sim, int i) {
        short[] raw = new short[PreflightTest.W * PreflightTest.H];
        UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
        sim.render(i, raw, info, 7 + 13L * i);
        long start = SimRunner.DEFAULT_TIME_MS;
        Preflight.Input in = PreflightTest.makeInput(name, sim, raw, info, start, SimRunner.DEFAULT_LON, Double.NaN, Double.NaN);
        Preflight.Report r = Preflight.run(in);
        System.out.printf(Locale.US, "  %s frame %d: %.3f with the stars, %.3f between (%d ms)%n", name, i, r.skyMagWithStars, r.skyMag, r.millis);
        return r;
    }

    static void print(String what, SkyReading r) {
        System.out.println("== " + what + ": " + r.headline + (r.valid ? "" : " [doesn't count: " + r.why + "]"));
        for (String l : r.lines) System.out.println("   " + l);
        System.out.println("   spoken: " + r.spoken);
    }

    static void check(boolean ok, String what) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what);
        if (!ok) failures++;
    }

    /** A CSV line's fields (quotes understood). */
    static List<String> split(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder f = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    f.append('"');
                    i++;
                } else if (ch == '"') {
                    q = false;
                } else {
                    f.append(ch);
                }
            } else if (ch == '"') {
                q = true;
            } else if (ch == ',') {
                out.add(f.toString());
                f.setLength(0);
            } else {
                f.append(ch);
            }
        }
        out.add(f.toString());
        return out;
    }
}
