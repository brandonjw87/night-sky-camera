package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.DeepSpace;
import com.brandon.nightsky.processing.JupiterGeo;
import com.brandon.nightsky.processing.Rot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Jupiter's sums (3.2): the four moons as the 2.4 card has them; Galileo's seven nights of January 1610 as his
 * drawings and A. Longhin's naming of them (arXiv 2503.12543) have them, in order east to west; the shadows ahead of
 * the moons before Jupiter's opposition and behind them after, eclipses and hidings in the right order and of the
 * right lengths; where a moon lands in a picture whose up is the sky's (against a camera pointed for real); the line
 * of letters; rising, setting and the best time to look.
 * <p>
 * Usage: JupiterGeoTest
 */
public final class JupiterGeoTest {
    static int failed;

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    /** Galileo's nights as Longhin names the little stars he drew, east to west (Jupiter left out). */
    static final String[][] LONGHIN = {
            {"Callisto", "Io", "Ganymede"},
            {"Io", "Europa", "Ganymede"},
            {"Callisto", "Europa"},
            {"Ganymede", "Callisto"},
            {"Ganymede", "Callisto", "Europa"},
            {"Europa", "Ganymede", "Io", "Callisto"},
            {"Io", "Europa", "Ganymede", "Callisto"},
    };

    public static void main(String[] args) {
        System.out.println("== the moons, as the 2.4 card has them");
        double worst = 0;
        for (int d = 0; d < 400; d++) {
            double jd = 2461000.5 + d * 0.37;
            DeepSpace.Moon[] dm = DeepSpace.jupiterMoons(jd);
            JupiterGeo.Moon[] jm = JupiterGeo.moons(jd);
            for (int k = 0; k < 4; k++) worst = Math.max(worst, Math.hypot(dm[k].x - jm[k].x, dm[k].y - jm[k].y));
        }
        check("the same places", worst < 1e-9, String.valueOf(worst));

        System.out.println("== Galileo's nights, January 1610");
        int countsOk = 0;
        for (int i = 0; i < JupiterGeo.GALILEO_DAY.length; i++) {
            JupiterGeo.View v = JupiterGeo.galileo(i);
            StringBuilder sb = new StringBuilder();
            for (JupiterGeo.Moon m : v.moons) sb.append(String.format(Locale.US, " %s %+.1f", m.name.substring(0, 2), m.x));
            // his stars in order: each named one further west than the one before (two closer than half a radius were one star to him)
            String[] names = LONGHIN[i];
            boolean order = true;
            for (int n = 1; n < names.length; n++) {
                double a = moon(v, names[n - 1]).x, b = moon(v, names[n]).x;
                if (!(b > a - 0.5)) order = false;
            }
            // his east and west: the named ones on the sides he drew them
            String drew = JupiterGeo.GALILEO_DREW[i];
            int east = drew.indexOf('O') / 2, west = names.length - east;
            boolean sides = true;
            for (int n = 0; n < names.length; n++) if ((n < east) != (moon(v, names[n]).x < 0)) sides = false;
            check(String.format(Locale.US, "January 1610: the %dth, Galileo's %s, the sums' %s", JupiterGeo.GALILEO_DAY[i], drew,
                    JupiterGeo.line(v.moons, 41, false).trim()), order && sides && Math.abs(v.alt) < 90 && v.alt > 10, sb.toString().trim());
            // and how many he could have seen: none nearer than 2 radii, none past 25 (his telescope's small view), two within 1 radius one
            int[] seen = counted(v);
            if (seen[0] == east && seen[1] == west) countsOk++;
        }
        check("as many little stars each side as he drew, on six of the seven nights (the 12th's Io, between two, he missed)", countsOk >= 6,
                countsOk + " of 7");

        System.out.println("== shadows and eclipses");
        TimeZone utc = TimeZone.getTimeZone("UTC");
        long nov = MoonshotTest.utc(2026, 11, 26, 23, 0), apr = MoonshotTest.utc(2027, 4, 14, 0, 0);
        List<JupiterGeo.Event> before = JupiterGeo.events(nov, nov + 4 * 86_400_000L), after = JupiterGeo.events(apr, apr + 4 * 86_400_000L);
        double[] lead = leads(before), lag = leads(after);
        check("before opposition (November 2026) Io's shadow crosses ahead of Io", lead[0] > 40 && lead[0] < 110, String.format(Locale.US, "%.0f min ahead", lead[0]));
        check("after it (April 2027) behind it", lag[0] < -30 && lag[0] > -110, String.format(Locale.US, "%.0f min", lag[0]));
        check("before opposition Io goes dark in the shadow before it goes behind Jupiter", lead[1] > 30, String.format(Locale.US, "%.0f min", lead[1]));
        check("after it, behind Jupiter first", lag[1] < -30, String.format(Locale.US, "%.0f min", lag[1]));
        double[] len = new double[4];
        int[] nLen = new int[4];
        for (JupiterGeo.Event e : before) {
            if (e.kind != JupiterGeo.TRANSIT || e.startMs == Long.MIN_VALUE || e.endMs == Long.MAX_VALUE) continue;
            len[e.moon] += (e.endMs - e.startMs) / 60000.0;
            nLen[e.moon]++;
        }
        check("Io crosses Jupiter in about two and a quarter hours", nLen[0] > 0 && Math.abs(len[0] / nLen[0] - 135) < 15, String.format(Locale.US, "%.0f min", len[0] / Math.max(1, nLen[0])));
        if (nLen[1] > 0) check("Europa in about three", Math.abs(len[1] / nLen[1] - 172) < 20, String.format(Locale.US, "%.0f min", len[1] / nLen[1]));
        String[] some = new String[Math.min(4, before.size())];
        for (int i = 0; i < some.length; i++) some[i] = before.get(i).words(utc);
        System.out.println("  " + String.join("\n  ", some));
        int ecl = 0;
        // the sums' own time, kept as they first came out (a phone in the US east that night: 10:13 PM on the 28th)
        long n29 = MoonshotTest.utc(2026, 11, 29, 3, 13);
        for (JupiterGeo.Event e : before) if (e.kind == JupiterGeo.ECLIPSE && e.moon == 2 && Math.abs(e.startMs - n29) < 5 * 60_000L) ecl++;
        check("Ganymede goes into Jupiter's shadow at 03:13 UTC on 29 November 2026, for three and a half hours", ecl == 1, "");

        System.out.println("== in a picture");
        double worstPx = 0, worstRel = 0;
        long t0 = MoonshotTest.utc(2026, 12, 20, 4, 0);
        for (int n = 0; n < 6; n++) {
            long t = t0 + n * 37 * 3_600_000L;
            JupiterGeo.View v = JupiterGeo.view(t, 39, -77);
            if (v.alt < 5) continue;
            DeepSpace.Moon[] dm = DeepSpace.jupiterMoons(v.jdTt);
            double[] M = Astro.j2000ToEnu(v.jdUtc, 39 * Astro.DEG, -77 * Astro.DEG);
            double[] f = enu(M, v.dir);
            // the camera: pointed at Jupiter, the zenith straight up in its picture (so the picture's up is the parallactic angle)
            double[] up = {-f[2] * f[0], -f[2] * f[1], 1 - f[2] * f[2]};
            norm(up);
            double[] right = {f[1] * up[2] - f[2] * up[1], f[2] * up[0] - f[0] * up[2], f[0] * up[1] - f[1] * up[0]};
            double scale = 17.0;
            for (int k = 0; k < 4; k++) {
                double[] m = enu(M, dm[k].dir);
                double z = dot(m, f);
                double px = dot(m, right) / z / (scale * Astro.ARCSEC), py = -dot(m, up) / z / (scale * Astro.ARCSEC);
                double[] got = JupiterGeo.inPicture(v.moons[k], v.q, scale);
                double e = Math.hypot(got[0] - px, got[1] - py);
                worstPx = Math.max(worstPx, e);
                worstRel = Math.max(worstRel, e / Math.max(1, Math.hypot(px, py)));
            }
        }
        check("each moon where a camera pointed with the zenith up sees it", worstRel < 0.03 && worstPx < 0.4,
                String.format(Locale.US, "%.2f px, %.1f%% of its distance", worstPx, 100 * worstRel));

        System.out.println("== the line, and the night");
        JupiterGeo.View g13 = JupiterGeo.galileo(5);
        String l = JupiterGeo.line(g13.moons, 41, false);
        check("the 13th as letters: Europa east, then Jupiter, Ganymede, Io and Callisto", l.indexOf('E') < l.indexOf('O') && l.indexOf('O') < l.indexOf('G')
                && l.indexOf('G') < l.indexOf('I') && l.indexOf('I') < l.indexOf('C') && l.indexOf('O') == 20, "[" + l + "]");
        long rise = JupiterGeo.next(nov, 39, -77, true), set = JupiterGeo.next(nov, 39, -77, false);
        double aR = JupiterGeo.altOnly(rise, 39, -77), aS = JupiterGeo.altOnly(set, 39, -77);
        check("rising and setting found (Jupiter 0 degrees up then)", rise > nov && set > rise && Math.abs(aR) < 0.2 && Math.abs(aS) < 0.2,
                JupiterGeo.clock(rise, utc) + ", " + JupiterGeo.clock(set, utc) + " UTC");
        long best = JupiterGeo.best(nov, 39, -77, 14);
        check("the best time: 25 degrees up in the dark", best > rise && JupiterGeo.altOnly(best, 39, -77) >= 25 && JupiterGeo.sunAlt(best, 39, -77) < -8,
                JupiterGeo.clock(best, utc) + " UTC");
        JupiterGeo.View vb = JupiterGeo.view(best, 39, -77);
        check("Jupiter's size and brightness then (39 arcseconds across, magnitude -2.2, in the morning sky)", Math.abs(2 * vb.radiusArcsec - 38.7) < 1.5
                && Math.abs(vb.mag + 2.2) < 0.3 && !vb.evening, String.format(Locale.US, "%.1f\", %.2f", 2 * vb.radiusArcsec, vb.mag));
        System.out.println(failed == 0 ? "ALL PASS" : failed + " FAILED");
        if (failed > 0) System.exit(1);
    }

    static JupiterGeo.Moon moon(JupiterGeo.View v, String name) {
        for (JupiterGeo.Moon m : v.moons) if (m.name.equals(name)) return m;
        throw new IllegalArgumentException(name);
    }

    /** The little stars Galileo could have seen each side: none within 2 radii, none past 25, two within one radius of each other one. */
    static int[] counted(JupiterGeo.View v) {
        List<JupiterGeo.Moon> seen = new ArrayList<JupiterGeo.Moon>();
        for (JupiterGeo.Moon m : v.moons) if (m.showing() && m.dist() > 2 && m.dist() < 25) seen.add(m);
        int east = 0, west = 0;
        for (int i = 0; i < seen.size(); i++) {
            boolean merged = false;
            for (int j = 0; j < i; j++) if (Math.hypot(seen.get(i).x - seen.get(j).x, seen.get(i).y - seen.get(j).y) < 1) merged = true;
            if (merged) continue;
            if (seen.get(i).x < 0) east++;
            else west++;
        }
        return new int[]{east, west};
    }

    /**
     * Io's shadow's crossing ahead of Io's own (minutes; negative: behind), and its going dark ahead of its going
     * behind Jupiter: the first pair of each that both start in the window.
     */
    static double[] leads(List<JupiterGeo.Event> ev) {
        double shadow = Double.NaN, dark = Double.NaN;
        for (JupiterGeo.Event a : ev) {
            if (a.moon != 0 || a.startMs == Long.MIN_VALUE) continue;
            for (JupiterGeo.Event b : ev) {
                if (b.moon != 0 || b.startMs == Long.MIN_VALUE || Math.abs(b.startMs - a.startMs) > 4 * 3_600_000L) continue;
                if (a.kind == JupiterGeo.SHADOW && b.kind == JupiterGeo.TRANSIT && Double.isNaN(shadow)) shadow = (b.startMs - a.startMs) / 60000.0;
                if (a.kind == JupiterGeo.ECLIPSE && b.kind == JupiterGeo.HIDDEN && Double.isNaN(dark)) dark = (b.startMs - a.startMs) / 60000.0;
            }
        }
        return new double[]{shadow, dark};
    }

    static double[] enu(double[] M, double[] v) {
        double[] o = new double[3];
        Rot.apply(M, v[0], v[1], v[2], o);
        return o;
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static void norm(double[] a) {
        double n = Math.sqrt(dot(a, a));
        for (int i = 0; i < 3; i++) a[i] /= n;
    }
}
