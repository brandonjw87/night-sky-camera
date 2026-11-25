package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.MoonGeo;
import com.brandon.nightsky.processing.MoonSites;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The Moon as a globe (3.0): Meeus's worked examples (47.a, the Moon's place; 53.a, its libration and axis), the Sun
 * over the six Apollo sites at the moments they landed against the elevations NASA published, the bright limb against
 * where the Sun's point falls on the disk (two independent ways to the same direction, at every phase), new and full
 * Moons, the way the picture's up is read from the gravity sensor, and the landing sites' list.
 * <p>
 * Usage: MoonGeoTest
 */
public final class MoonGeoTest {
    static int failed;

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    static long utc(int y, int mo, int d, int h, int mi, int s) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        return c.getTimeInMillis();
    }

    static double angDiff(double a, double b) {
        double d = ((a - b) % 360 + 540) % 360 - 180;
        return Math.abs(d);
    }

    public static void main(String[] args) {
        System.out.println("== Meeus 47.a: the Moon on 1992 April 12, 0h TD");
        {
            double[] m = Astro.moonOfDate(2448724.5);
            double lam = Math.toDegrees(m[4]), bet = Math.toDegrees(m[5]);
            check("apparent longitude 133.1673", Math.abs(lam - 133.167265) < 0.003, String.format(Locale.US, "%.5f", lam));
            check("latitude -3.2291", Math.abs(bet + 3.229126) < 0.002, String.format(Locale.US, "%.5f", bet));
            check("distance 368410 km", Math.abs(m[3] - 368409.7) < 10, String.format(Locale.US, "%.1f", m[3]));
        }

        System.out.println("== Meeus 53.a: its libration and axis, from the book's own inputs");
        {
            double[] lbp = MoonGeo.librate(2448724.5, 133.167265, -3.229126, 134.688470, 0.004610, 23.440636);
            check("libration in longitude -1.23", Math.abs(lbp[0] + 1.23) < 0.02, String.format(Locale.US, "%.3f", lbp[0]));
            check("libration in latitude +4.20", Math.abs(lbp[1] - 4.20) < 0.02, String.format(Locale.US, "%.3f", lbp[1]));
            check("position angle of the axis 15.08", Math.abs(lbp[2] - 15.08) < 0.05, String.format(Locale.US, "%.3f", lbp[2]));
        }

        System.out.println("== Meeus 53.a: the Sun over the Moon then (seen from the Earth's centre, near enough)");
        {
            // 1992 April 12, 0h TD is 23:58:59 UT on the 11th
            long t = utc(1992, 4, 11, 23, 59, 1);
            MoonGeo.View v = MoonGeo.view(t, 0, 0);
            check("the Sun's selenographic longitude 67.89", angDiff(v.l0, 67.89) < 0.1, String.format(Locale.US, "%.3f", v.l0));
            check("its latitude +1.46", Math.abs(v.b0 - 1.46) < 0.05, String.format(Locale.US, "%.3f", v.b0));
            check("colongitude 22.11", angDiff(v.colong, 22.11) < 0.1, String.format(Locale.US, "%.3f", v.colong));
        }

        System.out.println("== the Sun over each Apollo site as it landed, against NASA's published elevations");
        {
            Object[][] landings = {
                    {"apollo11", utc(1969, 7, 20, 20, 17, 40), 10.8},
                    {"apollo12", utc(1969, 11, 19, 6, 54, 35), 5.1},
                    {"apollo14", utc(1971, 2, 5, 9, 18, 11), 10.3},
                    {"apollo15", utc(1971, 7, 30, 22, 16, 29), 12.2},
                    // Apollo 16 was planned for 11.9 degrees, then landed 5 h 42 min late (an engine problem in orbit):
                    // the Sun climbs 0.508 degrees an hour there
                    {"apollo16", utc(1972, 4, 21, 2, 23, 35), 11.9 + 5.7 * 0.508},
                    {"apollo17", utc(1972, 12, 11, 19, 54, 57), 13.0},
            };
            for (Object[] l : landings) {
                MoonSites.Site s = MoonSites.get((String) l[0]);
                MoonGeo.View v = MoonGeo.view((Long) l[1], 0, 0);
                double h = v.sunAltAt(s.lat, s.lon);
                check(s.name + ": the Sun " + String.format(Locale.US, "%.1f", (Double) l[2]) + " degrees up", Math.abs(h - (Double) l[2]) < 1.2,
                        String.format(Locale.US, "%.2f", h));
            }
        }

        System.out.println("== the bright limb (48.5) against the Sun's point on the disk (53), through a month");
        {
            long t0 = utc(2026, 11, 1, 3, 0, 0);
            double worst = 0;
            int n = 0;
            for (int d = 0; d < 30; d++) {
                long t = t0 + d * 86400_000L + (d % 5) * 3600_000L;
                MoonGeo.View v = MoonGeo.view(t, 39.0, -77.0);
                if (v.illum > 0.97 || v.illum < 0.03) continue;
                double[] u = MoonGeo.turn(v.disk(v.b0, v.l0)[0], v.disk(v.b0, v.l0)[1], v.p);
                double pa = Math.toDegrees(Math.atan2(-u[0], u[1]));
                worst = Math.max(worst, angDiff(pa, v.chi));
                n++;
            }
            check("they agree within half a degree on " + n + " days", n > 20 && worst < 0.5, String.format(Locale.US, "worst %.3f", worst));
        }

        System.out.println("== new and full");
        {
            MoonGeo.View nw = MoonGeo.view(utc(2024, 4, 8, 18, 21, 0), 32.0, -97.0);
            MoonGeo.View fl = MoonGeo.view(utc(2024, 4, 23, 23, 49, 0), 32.0, -97.0);
            check("the eclipse of 2024 April 8: a new Moon", nw.illum < 0.001, String.format(Locale.US, "%.5f", nw.illum));
            check("2024 April 23: full", fl.illum > 0.995, String.format(Locale.US, "%.5f", fl.illum));
            check("full: the Sun over the middle of the disk", Math.abs(fl.l0 - fl.l) < 6 && Math.abs(fl.b0 - fl.b) < 6,
                    String.format(Locale.US, "sun %.1f,%.1f  centre %.1f,%.1f", fl.l0, fl.b0, fl.l, fl.b));
            check("the Moon's radius about a quarter of a degree", fl.radius > 0.24 && fl.radius < 0.285, String.format(Locale.US, "%.4f", fl.radius));
        }

        System.out.println("== the picture's up from the gravity sensor");
        {
            double q = 20;
            check("upright: up is the zenith", angDiff(MoonGeo.upAngle(q, new double[]{0, 9.8, 0}, new double[]{0, 1, 0}), q) < 1e-9, "");
            check("turned a quarter left: up is 90 degrees counterclockwise of the zenith",
                    angDiff(MoonGeo.upAngle(q, new double[]{9.8, 0, 0}, new double[]{0, 1, 0}), q + 90) < 1e-9, "");
            check("the sensor's rows (orientation 90) on an upright phone: a quarter clockwise",
                    angDiff(MoonGeo.upAngle(q, new double[]{0, 9.8, 0}, MoonGeo.sensorUp(90)), q - 90) < 1e-9, "");
            check("pointed straight up: no answer", Double.isNaN(MoonGeo.upAngle(q, new double[]{0, 0, -9.8}, new double[]{0, 1, 0})), "");
        }

        System.out.println("== the landing sites");
        {
            int near = 0, far = 0;
            for (MoonSites.Site s : MoonSites.ALL) {
                if (Math.abs(s.lon) < 90) near++;
                else far++;
            }
            check("at least 30 on the near side", near >= 30, near + " near, " + far + " far");
            check("Apollo 11 at Tranquility Base", Math.abs(MoonSites.get("apollo11").lat - 0.67416) < 1e-6, "");
            boolean stories = true;
            for (MoonSites.Site s : MoonSites.ALL) if (s.story.length() < 40 || s.label.isEmpty()) stories = false;
            check("every site has a label and a story", stories, "");
        }
        System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
        if (failed > 0) System.exit(1);
    }
}
