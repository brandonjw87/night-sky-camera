package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.SatCatalog;
import com.brandon.nightsky.processing.SatData;
import com.brandon.nightsky.processing.SatPlanner;
import com.brandon.nightsky.processing.SatPredict;
import com.brandon.nightsky.processing.SatSky;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;

/**
 * The satellite plan before a session (wait for a bright pass, count it down when kept, the quietest stretch of
 * the next hour, docked ships merged, speed with a Starlink-sized catalogue) and the CelesTrak cache (at most
 * every 2 hours a group, never again by itself after a 403, backing off after errors, kept with its fetch time).
 */
public final class SatPlanTest {
    static final TimeZone ZONE = TimeZone.getTimeZone("America/New_York");
    static int failures;

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "satplan-out");
        out.mkdirs();
        long startMs = SatSessionTest.darkStart();
        SatSky.Site site = new SatSky.Site(Math.toRadians(SatSessionTest.LAT), Math.toRadians(SatSessionTest.LON), 0);
        planCases(site, startMs);
        speed(site, startMs);
        cache(new File(out, "cache"));
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    // ------------------------------------------------------------------------------------------------

    static void planCases(SatSky.Site site, long startMs) {
        Random rnd = new Random(5);
        double[] zenith = {0, 0, 1};
        double radius = 40;
        double jd0 = Astro.jdUtc(startMs / 1000.0);
        List<SatSessionTest.Pass> passes = new ArrayList<SatSessionTest.Pass>();
        // the ISS 90 s after the planned start, 12 degrees from the zenith, with a crew ship docked
        SatSessionTest.Pass iss = SatSessionTest.planAt(SatSessionTest.ISS, "ISS (ZARYA)", 25544, jd0 + 90 / 86400.0,
                SatSessionTest.offset(zenith, Math.toRadians(12), 1.0), rnd, site);
        check(iss != null, "an ISS pass over the zenith was made");
        if (iss == null) return;
        passes.add(iss);
        passes.add(SatSessionTest.copyPass(iss, "SOYUZ MS-28", 66001));
        // a busy first ten minutes: six fainter passes, then a few over the rest of the hour
        int id = 72001;
        for (int k = 0; k < 6; k++) {
            SatSessionTest.Pass p = SatSessionTest.planAt(SatSessionTest.CBERS, "CBERS 2 " + (char) ('A' + k), id++,
                    jd0 + (60 + 90 * k) / 86400.0, SatSessionTest.offset(zenith, Math.toRadians(5 + 5 * k), 0.7 * k), rnd, site);
            if (p != null) passes.add(p);
        }
        for (int k = 0; k < 4; k++) {
            SatSessionTest.Pass p = SatSessionTest.planAt(SatSessionTest.DELTA, "DELTA 1 DEB " + (char) ('A' + k), id++,
                    jd0 + (1500 + 600 * k) / 86400.0, SatSessionTest.offset(zenith, Math.toRadians(10 + 6 * k), 2.0 + k), rnd, site);
            if (p != null) passes.add(p);
        }
        for (SatSessionTest.Pass p : passes) p.data = SatSessionTest.elements(p, p.epochJd, 0, 0);
        SatCatalog cat = SatSessionTest.catalogue(passes, jd0);
        System.out.println("catalogue: " + cat.size() + " satellites");

        SatPlanner.Plan plan = SatPlanner.plan(cat, site, zenith, radius, startMs, 300, false, ZONE);
        System.out.println("plan (" + plan.millis + " ms): " + plan.text);
        for (SatPredict.Pass q : plan.passes) {
            System.out.printf(Locale.US, "  pass %-14s in %+6.0f s, out %+6.0f s, mag %5.1f%n", q.sat.displayName(),
                    (q.jdIn - jd0) * 86400, (q.jdOut - jd0) * 86400, q.magBest);
        }
        int issPasses = 0;
        SatPredict.Pass issPass = null;
        for (SatPredict.Pass q : plan.passes) {
            if (Math.abs(q.jdIn - jd0 - 90 / 86400.0) < 300 / 86400.0 && (q.sat.norad == 25544 || q.sat.norad == 66001)) {
                issPasses++;
                issPass = q;
            }
        }
        check(issPasses == 1 && issPass.sat.norad == 25544, "the docked ship's pass merged into the ISS's (" + issPasses + " left)");
        check(plan.waits() && plan.waitFor != null && plan.waitFor.sat.norad == 25544, "waits for the ISS");
        if (issPass != null) {
            long after = Math.round((issPass.jdOut - 2440587.5) * 86400_000.0) + 5000;
            check(Math.abs(plan.startMs - after) <= 1000 && plan.startMs - startMs <= 6 * 60_000L,
                    String.format(Locale.US, "starts 5 s after it leaves (%+.1f s from that), %.0f s later than asked",
                            (plan.startMs - after) / 1000.0, (plan.startMs - startMs) / 1000.0));
        }
        check(plan.text.startsWith("The ISS (magnitude -3.9) crosses the frame at") && plan.text.contains("so I'll start after it"),
                "says it: " + plan.text);
        check(plan.spoken.startsWith("The ISS crosses the frame at") && plan.spoken.endsWith("so I'll start after it."),
                "speaks it: " + plan.spoken);
        check(plan.offerQuiet && plan.quietStartMs >= startMs + 9 * 60_000L,
                String.format(Locale.US, "offers a quieter start %.1f min later (cost %.2f against %.2f)",
                        (plan.quietStartMs - startMs) / 60000.0, plan.quietCost, plan.startCost));

        SatPlanner.Plan keep = SatPlanner.plan(cat, site, zenith, radius, startMs, 300, true, ZONE);
        System.out.println("keeping: " + keep.text + " / " + keep.spoken);
        check(!keep.waits() && keep.startMs == startMs, "keeping satellites: no wait");
        check(!keep.countdown.isEmpty() && keep.countdown.get(0).sat.norad == 25544, "keeping satellites: the ISS is counted down");
        check(!keep.offerQuiet, "keeping satellites: no quieter start offered");

        // a bright pass too far ahead to wait for
        SatPlanner.Plan later = SatPlanner.plan(cat, site, zenith, radius, startMs - 10 * 60_000L, 300, false, ZONE);
        System.out.println("ten minutes earlier: " + later.text);
        check(!later.waits(), "no wait for a pass ten minutes off");
        // the home screen's outlook: the same plan for a phone put down now, and the night's bright passes
        SatPlanner.Outlook o = SatPlanner.outlook(cat, site, startMs - 60_000L, 300, false, ZONE);
        System.out.println("outlook (" + o.millis + " ms): " + o.next.text + " / " + o.text);
        boolean issListed = false;
        for (SatPredict.Pass q : o.bright) issListed |= q.sat.norad == 25544;
        check(o.next.waits() && o.next.waitFor.sat.norad == 25544, "outlook: a phone put down now waits for the ISS");
        check(issListed && o.text.startsWith("Bright passes overhead in the next 24 hours: ") && o.text.contains("the ISS at 7:")
                && o.text.contains(" tomorrow at "), "outlook lists the ISS, and says which passes are tomorrow");
        // none at all
        SatPlanner.Plan none = SatPlanner.plan(cat, site, new double[]{0, 0.996, 0.087}, 5, startMs, 300, false, ZONE);
        System.out.println("low in the north: " + none.text);
        check(none.during.isEmpty() && none.text.startsWith("No satellite"), "says when nothing crosses");
    }

    /** A Starlink-sized catalogue: 6000 satellites in 72 planes. */
    static void speed(SatSky.Site site, long startMs) {
        StringBuilder b = new StringBuilder("[");
        double jd0 = Astro.jdUtc(startMs / 1000.0);
        int n = 0;
        for (int plane = 0; plane < 72; plane++) {
            for (int k = 0; k < 84; k++, n++) {
                if (n > 0) b.append(",\n");
                b.append(String.format(Locale.US, "{\"OBJECT_NAME\":\"STARLINK-%d\",\"NORAD_CAT_ID\":%d,\"EPOCH\":\"%s\",\"MEAN_MOTION\":15.06,"
                                + "\"ECCENTRICITY\":0.00012,\"INCLINATION\":53.05,\"RA_OF_ASC_NODE\":%.4f,\"ARG_OF_PERICENTER\":90,"
                                + "\"MEAN_ANOMALY\":%.4f,\"BSTAR\":1e-4,\"MEAN_MOTION_DOT\":5e-6,\"MEAN_MOTION_DDOT\":0}",
                        1000 + n, 44000 + n, SatSessionTest.iso(jd0 - 1), plane * 5.0, (k * 360.0 / 84 + plane * 2.3) % 360));
            }
        }
        b.append("]");
        SatCatalog cat = new SatCatalog();
        long t0 = System.currentTimeMillis();
        int read = cat.add(b.toString(), "starlink", jd0);
        long parse = System.currentTimeMillis() - t0;
        SatPlanner.Plan p = SatPlanner.plan(cat, site, new double[]{0, 0, 1}, 40, startMs, 300, false, ZONE);
        System.out.printf(Locale.US, "speed: %d Starlinks read in %d ms; an hour of passes in %d ms: %d passes%n", read, parse, p.millis,
                p.passes.size());
        check(read == 6048 && p.millis < 20000, "an hour of a Starlink-sized catalogue in under 20 s here");
    }

    // ------------------------------------------------------------------------------------------------

    /** Stands in for CelesTrak: answers per URL, counting requests. */
    static final class Fake implements SatData.Fetcher {
        final Map<String, Integer> codes = new HashMap<String, Integer>();
        final Map<String, Integer> asked = new HashMap<String, Integer>();
        String body = "[]";

        @Override
        public SatData.Response get(String url) throws IOException {
            asked.put(url, asked.containsKey(url) ? asked.get(url) + 1 : 1);
            Integer c = codes.get(url);
            if (c != null && c == -1) throw new IOException("no network");
            int code = c != null ? c : 200;
            return new SatData.Response(code, code == 200 ? body.getBytes(StandardCharsets.UTF_8) : null);
        }

        int asked(String url) {
            Integer n = asked.get(url);
            return n == null ? 0 : n;
        }
    }

    static void cache(File dir) throws Exception {
        deleteAll(dir);
        Fake f = new Fake();
        long t = 1_790_000_000_000L;
        double jd = Astro.jdUtc(t / 1000.0);
        f.body = "[{\"OBJECT_NAME\":\"ISS (ZARYA)\",\"NORAD_CAT_ID\":25544,\"EPOCH\":\"" + SatSessionTest.iso(jd - 0.5)
                + "\",\"MEAN_MOTION\":15.5,\"ECCENTRICITY\":0.0004,\"INCLINATION\":51.64,\"RA_OF_ASC_NODE\":100,"
                + "\"ARG_OF_PERICENTER\":30,\"MEAN_ANOMALY\":10,\"BSTAR\":2.5e-4,\"MEAN_MOTION_DOT\":7.5e-5,\"MEAN_MOTION_DDOT\":0}]";
        String visual = SatData.GROUPS[0].url, starlink = SatData.GROUPS[2].url, sup = SatData.GROUPS[3].url;
        SatData d = new SatData(dir, f);
        String log = d.refresh(t, false, false);
        System.out.println("first: " + log);
        check(f.asked(visual) == 1 && f.asked(starlink) == 0 && f.asked(sup) == 0, "small groups only off Wi-Fi");
        check(d.catalog(jd) != null && d.catalog(jd).get(25544) != null, "cached and read back");
        d.refresh(t + 3600_000L, true, false);
        check(f.asked(visual) == 1 && f.asked(starlink) == 1, "within 2 hours: only the never-fetched large groups");
        d.refresh(t + 2 * 3600_000L + 1000, true, false);
        check(f.asked(visual) == 2 && f.asked(starlink) == 1, "after 2 hours: the small groups again, the large one not yet");
        // a 403: never again by itself
        f.codes.put(visual, 403);
        d.refresh(t + 5 * 3600_000L, true, false);
        check(f.asked(visual) == 3 && d.refused(), "a 403 is noted");
        d.refresh(t + 30 * 3600_000L, true, false);
        d.refresh(t + 300 * 3600_000L, true, false);
        check(f.asked(visual) == 3, "after a 403: not asked again by itself, days later");
        check(d.catalog(jd) != null, "the old copy is still used after a 403");
        System.out.println("status: " + d.status(t + 300 * 3600_000L));
        f.codes.remove(visual);
        d.refresh(t + 301 * 3600_000L, true, true);
        check(f.asked(visual) == 4 && !d.refused(), "asked again when Brandon asks");
        // errors: back off, doubling
        f.codes.put(sup, -1);
        long t2 = t + 400 * 3600_000L;
        int before = f.asked(sup);
        d.refresh(t2, true, false);
        d.refresh(t2 + 10 * 60_000L, true, false);
        check(f.asked(sup) == before + 1, "after an error: no new try within 15 minutes");
        d.refresh(t2 + 16 * 60_000L, true, false);
        check(f.asked(sup) == before + 2, "tried again after 15 minutes");
        d.refresh(t2 + 16 * 60_000L + 20 * 60_000L, true, false);
        check(f.asked(sup) == before + 2, "then waits 30 minutes");
        d.refresh(t2 + 16 * 60_000L + 31 * 60_000L, true, false);
        check(f.asked(sup) == before + 3, "and tries again after them");
        // a broken answer keeps the good copy
        f.codes.remove(sup);
        String good = f.body;
        f.body = "<html>maintenance</html>";
        d.refresh(t + 500 * 3600_000L, true, false);
        check(d.catalog(jd) != null && d.catalog(jd).get(25544) != null, "a broken answer doesn't replace the cache");
        f.body = good;
        System.out.println("status: " + d.status(t + 500 * 3600_000L));
    }

    static void deleteAll(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File c : l) deleteAll(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
