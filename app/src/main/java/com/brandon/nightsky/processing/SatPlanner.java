package com.brandon.nightsky.processing;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Satellites before a session: the passes through the picture over the next hour, whether to start after a
 * bright one that is about to cross ("The ISS crosses the frame at 10:14 PM, so I'll start after it"), the
 * quietest stretch of the session's length in the next hour, and, when satellites are kept, the bright passes
 * to count down aloud.
 */
public final class SatPlanner {
    private SatPlanner() {}

    /** Passes this bright (magnitude) are worth waiting for: the ISS, Tiangong, a fresh Starlink train. */
    static final double WAIT_MAG = 2.0;
    /** Passes this bright are counted down aloud when satellites are kept. */
    static final double COUNT_MAG = 3.0;
    /** Faintest pass counted at all: fainter ones leave no streak worth a mention. */
    static final double MAG_LIMIT = 5.5;
    /** Longest wait for a bright pass, and how long after it leaves the picture to start. */
    static final long MAX_WAIT_MS = 6 * 60_000L, AFTER_MS = 5_000L;
    /** A quieter stretch is offered when it costs at most this share of starting as planned, and starts this much later. */
    static final double QUIET_SHARE = 0.5;
    static final long QUIET_MIN_LATER_MS = 3 * 60_000L;
    /** How far ahead the quiet stretch is looked for. */
    static final long HORIZON_MS = 3600_000L;
    /** The picture of a phone lying screen-down: about a circle this wide (degrees) around the zenith. */
    public static final double ZENITH_RADIUS_DEG = 40;
    /** Passes this bright through that circle, in the dark, are listed for the night ahead. */
    static final double OUTLOOK_MAG = 2.5;
    /** The Sun this far below the horizon (degrees) counts as dark enough to take pictures. */
    static final double DARK_SUN_DEG = -6;

    /** What the satellites mean for a session starting at a given time. */
    public static final class Plan {
        /** Passes through the picture from a few minutes before the start to an hour after the session, by time. */
        public final List<SatPredict.Pass> passes = new ArrayList<SatPredict.Pass>();
        /** When the session was to start, and when it should (after any wait), UTC ms. */
        public long askedStartMs, startMs;
        /** The bright pass to wait for (null if none). */
        public SatPredict.Pass waitFor;
        /** The quietest start in the next hour (UTC ms) and its cost; the cost of starting at {@link #startMs}. */
        public long quietStartMs;
        public double quietCost = Double.NaN, startCost = Double.NaN;
        /** Whether the quiet stretch is worth offering (much quieter, and not right away). */
        public boolean offerQuiet;
        /** Passes during the session as planned, and the bright ones among them to count down when kept. */
        public final List<SatPredict.Pass> during = new ArrayList<SatPredict.Pass>(), countdown = new ArrayList<SatPredict.Pass>();
        public boolean keep;
        /** For the screen and the records; a sentence to say ("" when there's nothing worth saying). */
        public String text = "", spoken = "";
        public long millis;

        public boolean waits() {
            return waitFor != null && startMs > askedStartMs;
        }
    }

    /**
     * The plan for a session of {@code sessionSec} meant to start at {@code startMs} (UTC), with the picture
     * a circle of {@code radiusDeg} around {@code axisEnu} (east, north, up).
     */
    public static Plan plan(SatCatalog cat, SatSky.Site site, double[] axisEnu, double radiusDeg, long startMs, double sessionSec,
                            boolean keep, TimeZone zone) {
        long t0 = System.currentTimeMillis();
        Plan p = new Plan();
        p.askedStartMs = p.startMs = startMs;
        p.keep = keep;
        long sessionMs = Math.round(sessionSec * 1000);
        double jd0 = jd(startMs - 5 * 60_000L), jd1 = jd(startMs + HORIZON_MS + sessionMs);
        p.passes.addAll(merged(passes(cat.all(), site, jd0, jd1, axisEnu, radiusDeg, MAG_LIMIT)));
        if (!keep) {
            // a bright pass crossing now or within a few minutes: start after it (and after one right behind it)
            for (int k = 0; k < 3; k++) {
                SatPredict.Pass w = null;
                long until = p.startMs;
                for (SatPredict.Pass q : p.passes) {
                    if (q.magBest > WAIT_MAG) continue;
                    long out = ms(q.jdOut) + AFTER_MS;
                    if (ms(q.jdOut) <= p.startMs || ms(q.jdIn) >= p.startMs + sessionMs) continue;
                    if (out - startMs > MAX_WAIT_MS) continue;
                    if (out > until) {
                        until = out;
                        w = q;
                    }
                }
                if (w == null) break;
                p.waitFor = w;
                p.startMs = until;
            }
        }
        for (SatPredict.Pass q : p.passes) {
            if (ms(q.jdOut) <= p.startMs || ms(q.jdIn) >= p.startMs + sessionMs) continue;
            p.during.add(q);
            if (keep && q.magBest <= COUNT_MAG && ms(q.jdIn) >= p.startMs) p.countdown.add(q);
        }
        p.startCost = cost(p.passes, p.startMs, sessionMs);
        double[] quiet = SatPredict.quietestWindow(p.passes, jd(startMs), jd(startMs + HORIZON_MS), sessionSec, 30);
        if (quiet != null) {
            p.quietStartMs = ms(quiet[0]);
            p.quietCost = quiet[1];
            p.offerQuiet = !keep && p.quietCost <= QUIET_SHARE * p.startCost && p.quietStartMs - p.startMs >= QUIET_MIN_LATER_MS;
        }
        words(p, zone, sessionSec);
        p.millis = System.currentTimeMillis() - t0;
        return p;
    }

    /** For the home screen: a session starting in a minute, and the bright passes over the day ahead. */
    public static final class Outlook {
        /** The plan for a phone put down straight away (looking at the zenith). */
        public Plan next;
        /** Bright passes through the picture while it is dark, by time (the stations and the bright satellites;
         *  Starlinks are faint once in their working orbits). */
        public final List<SatPredict.Pass> bright = new ArrayList<SatPredict.Pass>();
        /** The bright passes in words ("" if none). */
        public String text = "";
        public long millis;
    }

    /** What the satellites look like for a phone put down now and looking straight up, and over the next day. */
    public static Outlook outlook(SatCatalog cat, SatSky.Site site, long nowMs, double sessionSec, boolean keep, TimeZone zone) {
        long t0 = System.currentTimeMillis();
        Outlook o = new Outlook();
        double[] up = {0, 0, 1};
        o.next = plan(cat, site, up, ZENITH_RADIUS_DEG, nowMs + 60_000L, sessionSec, keep, zone);
        List<SatCatalog.Sat> few = new ArrayList<SatCatalog.Sat>();
        for (SatCatalog.Sat x : cat.all()) if (!x.source.contains("starlink")) few.add(x);
        for (SatPredict.Pass p : merged(passes(few, site, jd(nowMs), jd(nowMs + 24 * 3600_000L), up, ZENITH_RADIUS_DEG, OUTLOOK_MAG))) {
            double sun = Math.toDegrees(Astro.altitude(Astro.sunEnu(ms(p.jdBest), site.latRad, site.lonRad)));
            if (sun <= DARK_SUN_DEG) o.bright.add(p);
        }
        SimpleDateFormat clock = new SimpleDateFormat("h:mm a", Locale.US);
        clock.setTimeZone(zone);
        List<String> parts = new ArrayList<String>();
        for (int i = 0; i < Math.min(5, o.bright.size()); i++) {
            SatPredict.Pass p = o.bright.get(i);
            parts.add(String.format(Locale.US, "%s at %s (magnitude %.1f)", the(p.sat.displayName()), clock.format(new Date(ms(p.jdIn))),
                    p.magBest));
        }
        if (o.bright.size() > 5) parts.add((o.bright.size() - 5) + " more");
        if (!parts.isEmpty()) o.text = "Bright passes overhead in the next 24 hours: " + SkyGuide.list(parts) + ".";
        o.millis = System.currentTimeMillis() - t0;
        return o;
    }

    /**
     * {@link SatPredict#passes} over several threads (each with its own copy of the site: a site's moments aren't
     * for sharing), by time of entry.
     */
    public static List<SatPredict.Pass> passes(final List<SatCatalog.Sat> sats, final SatSky.Site site, final double jd0,
                                               final double jd1, final double[] axisEnu, final double radiusDeg, final double magLimit) {
        final int chunks = Math.max(1, Math.min(32, sats.size() / 50));
        @SuppressWarnings("unchecked") final List<SatPredict.Pass>[] parts = new List[chunks];
        Parallel.forRange(chunks, 1, new Parallel.Range() {
            @Override
            public void run(int a, int b) {
                for (int c = a; c < b; c++) {
                    List<SatCatalog.Sat> mine = sats.subList(c * sats.size() / chunks, (c + 1) * sats.size() / chunks);
                    SatSky.Site s = new SatSky.Site(site.latRad, site.lonRad, site.hKm);
                    parts[c] = SatPredict.passes(mine, s, jd0, jd1, axisEnu, radiusDeg, magLimit);
                }
            }
        });
        List<SatPredict.Pass> all = new ArrayList<SatPredict.Pass>();
        for (List<SatPredict.Pass> l : parts) if (l != null) all.addAll(l);
        Collections.sort(all, new Comparator<SatPredict.Pass>() {
            @Override
            public int compare(SatPredict.Pass a, SatPredict.Pass b) {
                return Double.compare(a.jdIn, b.jdIn);
            }
        });
        return all;
    }

    /**
     * One pass for objects flying together: a crew ship docked at the ISS has its own elements and crosses with
     * it. Passes of the same few seconds keep the brightest.
     */
    static List<SatPredict.Pass> merged(List<SatPredict.Pass> in) {
        List<SatPredict.Pass> out = new ArrayList<SatPredict.Pass>();
        for (SatPredict.Pass p : in) {
            SatPredict.Pass same = null;
            for (int i = out.size() - 1; i >= 0 && out.get(i).jdIn > p.jdIn - 10 / 86400.0; i--) {
                SatPredict.Pass o = out.get(i);
                if (Math.abs(o.jdIn - p.jdIn) * 86400 <= 3 && Math.abs(o.jdOut - p.jdOut) * 86400 <= 3
                        && Math.abs(o.jdBest - p.jdBest) * 86400 <= 3) {
                    same = o;
                    break;
                }
            }
            if (same == null) out.add(p);
            else if (p.magBest < same.magBest) out.set(out.indexOf(same), p);
        }
        return out;
    }

    /** What a stretch [start, start + length] costs: each pass through it counts as its light against magnitude 3. */
    static double cost(List<SatPredict.Pass> passes, long startMs, long lengthMs) {
        double c = 0;
        for (SatPredict.Pass q : passes) {
            if (ms(q.jdOut) <= startMs || ms(q.jdIn) >= startMs + lengthMs) continue;
            c += Math.pow(10, -0.4 * (q.magBest - 3));
        }
        return c;
    }

    private static void words(Plan p, TimeZone zone, double sessionSec) {
        SimpleDateFormat clock = new SimpleDateFormat("h:mm a", Locale.US), say = new SimpleDateFormat("h:mm", Locale.US);
        clock.setTimeZone(zone);
        say.setTimeZone(zone);
        StringBuilder t = new StringBuilder(), s = new StringBuilder();
        if (p.waits()) {
            SatPredict.Pass w = p.waitFor;
            String name = w.sat.displayName();
            t.append(String.format(Locale.US, "%s (magnitude %.1f) crosses the frame at %s, so I'll start after it, at %s.",
                    capital(the(name)), w.magBest, clock.format(new Date(ms(w.jdIn))), clock.format(new Date(p.startMs))));
            s.append(String.format(Locale.US, "%s crosses the frame at %s, so I'll start after it.", capital(the(name)),
                    say.format(new Date(ms(w.jdIn)))));
        } else if (p.keep && !p.countdown.isEmpty()) {
            SatPredict.Pass c = p.countdown.get(0);
            String name = c.sat.displayName();
            t.append(String.format(Locale.US, "%s (magnitude %.1f) crosses the frame at %s; I'll count it down, and its streak "
                    + "stays in the picture.", capital(the(name)), c.magBest, clock.format(new Date(ms(c.jdIn)))));
            if (p.countdown.size() > 1) t.append(String.format(Locale.US, " %d more bright passes after it.", p.countdown.size() - 1));
            s.append(String.format(Locale.US, "%s crosses at %s. I'll count it down.", capital(the(name)), say.format(new Date(ms(c.jdIn)))));
        }
        if (!p.during.isEmpty() && !(p.keep && !p.countdown.isEmpty())) {
            SatPredict.Pass b = p.during.get(0);
            for (SatPredict.Pass q : p.during) if (q.magBest < b.magBest) b = q;
            if (t.length() > 0) t.append(' ');
            t.append(String.format(Locale.US, "%s cross%s the frame during the session (brightest: %s at %s, magnitude %.1f); %s.",
                    p.during.size() == 1 ? "One satellite" : p.during.size() + " satellites", p.during.size() == 1 ? "es" : "",
                    the(b.sat.displayName()), clock.format(new Date(ms(b.jdIn))), b.magBest,
                    p.during.size() == 1 ? (p.keep ? "its streak stays in the picture" : "its streak is taken out of the stack")
                            : p.keep ? "their streaks stay in the picture" : "their streaks are taken out of the stack"));
        } else if (p.during.isEmpty()) {
            if (t.length() > 0) t.append(' ');
            t.append("No satellite bright enough to leave a streak crosses the frame during the session.");
        }
        if (p.offerQuiet) {
            t.append(String.format(Locale.US, " The quietest %s in the next hour start at %s.", minutes(sessionSec),
                    clock.format(new Date(p.quietStartMs))));
        }
        p.text = t.toString();
        p.spoken = s.toString();
    }

    /** "the ISS", "Starlink-1234": the stations take "the". */
    public static String the(String name) {
        return name.equals("ISS") || name.equals("Tiangong") || name.equals("Hubble") ? "the " + name : name;
    }

    public static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** "5 minutes", "90 seconds". */
    static String minutes(double sec) {
        if (sec < 120) return Math.round(sec) + " seconds";
        double m = sec / 60;
        return m == Math.rint(m) ? (long) m + " minutes" : String.format(Locale.US, "%.1f minutes", m);
    }

    public static double jd(long ms) {
        return Astro.jdUtc(ms / 1000.0);
    }

    public static long ms(double jd) {
        return Math.round((jd - 2440587.5) * 86400_000.0);
    }
}
