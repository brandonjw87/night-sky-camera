package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.MeteorShowers;
import com.brandon.nightsky.processing.Rot;
import com.brandon.nightsky.processing.SatMatch;
import com.brandon.nightsky.processing.SatSky;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;

/**
 * Meteor showers and telling meteors from satellites (1.5): the Sun's longitude at known moments and the 2026
 * peaks on their dates; the shower list's sense (activity around each peak, rates, drifting radiants); zenith
 * attraction; how fast a shower's meteors cross the sky; which shower a trail points back to (members found,
 * flying the right way; meteors in random directions mostly left sporadic); where the Earth's shadow begins along
 * a line of sight; and which streaks a satellite can't have left.
 * <p>
 * Usage: MeteorTest
 */
public final class MeteorTest {
    static int failures;

    public static void main(String[] args) {
        solarLongitude();
        list();
        radiants();
        attribution();
        shadow();
        kinds();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    static double jd(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, h, mi);
        return Astro.jdUtc(c.getTimeInMillis());
    }

    /** The UTC Julian date in 2026 when the Sun reaches longitude {@code lon}. */
    static double when(double lon) {
        double j = jd(2026, 1, 1, 0, 0);
        for (int i = 0; i < 6; i++) {
            double d = lon - MeteorShowers.solarLongitude(j);
            d -= 360 * Math.floor((d + 180) / 360);
            if (i == 0 && d < 0) d += 360;
            j += d / 0.98561;
        }
        return j;
    }

    static void solarLongitude() {
        // the March equinox of 2026 (14:46 UTC): 0 of date, which is 0.366 degrees of precession short of 360 in J2000
        double eq = MeteorShowers.solarLongitude(jd(2026, 3, 20, 14, 46));
        check(Math.abs(eq - 359.634) < 0.02, String.format(Locale.US, "Sun at the 2026 March equinox: %.3f (359.634 in J2000)", eq));
        // at J2000.0 itself (2000-01-01 12:00 TT) J2000 is the frame of date: Meeus's 280.37
        double j2000 = 2451545.0 - 69.184 / 86400;
        double l0 = MeteorShowers.solarLongitude(j2000);
        check(Math.abs(l0 - 280.375) < 0.02, String.format(Locale.US, "Sun at J2000.0: %.3f (280.375)", l0));
        double a = MeteorShowers.solarLongitude(jd(2026, 6, 1, 0, 0)), b = MeteorShowers.solarLongitude(jd(2026, 6, 2, 0, 0));
        check(b - a > 0.95 && b - a < 0.97, String.format(Locale.US, "a day in June moves it %.4f degrees", b - a));
        // the 2026 peaks fall on their dates (IMO calendar)
        String[][] peaks = {{"QUA", "2026-01-03T12", "2026-01-04T00"}, {"PER", "2026-08-12T18", "2026-08-13T09"},
                {"ORI", "2026-10-21T00", "2026-10-22T12"}, {"GEM", "2026-12-14T00", "2026-12-14T18"}, {"LYR", "2026-04-22T06", "2026-04-23T00"},
                {"ETA", "2026-05-05T12", "2026-05-06T12"}, {"LEO", "2026-11-17T00", "2026-11-18T12"}};
        for (String[] p : peaks) {
            MeteorShowers.Shower s = MeteorShowers.byCode(p[0]);
            double t = when(s.peak);
            check(t >= iso(p[1]) && t <= iso(p[2]), String.format(Locale.US, "%s peak (%.2f) %s, between %s and %s", s.name, s.peak,
                    stamp(t), p[1], p[2]));
        }
    }

    static double iso(String s) {
        String[] d = s.split("[-T]");
        return jd(Integer.parseInt(d[0]), Integer.parseInt(d[1]), Integer.parseInt(d[2]), Integer.parseInt(d[3]), 0);
    }

    static String stamp(double jd) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis((long) ((jd - 2440587.5) * 86400000L));
        return String.format(Locale.US, "%tF %<tH:%<tM UTC", c);
    }

    static void list() {
        Set<String> codes = new HashSet<String>();
        boolean sane = true;
        for (MeteorShowers.Shower s : MeteorShowers.ALL) {
            sane &= codes.add(s.code) && s.code.length() == 3;
            sane &= s.active(s.peak) && s.active(s.begin) && s.active(s.end) && !s.active(s.begin - 1) && !s.active(s.end + 1);
            sane &= s.speed > 11 && s.speed < 73 && s.r >= 2 && s.r <= 3.5 && s.zhr > 0 && s.spread > 0;
            sane &= Math.abs(s.zhr(s.peak) - s.zhr) < 1e-9;
            if (!sane) {
                check(false, "the list: " + s.code);
                return;
            }
        }
        check(true, MeteorShowers.ALL.length + " showers, each active from its start through its peak to its end, and not outside");
        MeteorShowers.Shower gem = MeteorShowers.byCode("GEM");
        check(Math.abs(gem.zhr(gem.peak - 1) - 150 * Math.pow(10, -0.39)) < 1e-6 && Math.abs(gem.zhr(gem.peak + 1) - 150 * Math.pow(10, -0.81)) < 1e-6,
                String.format(Locale.US, "Geminids rise slowly and fall fast: %.0f a day before, %.0f a day after", gem.zhr(gem.peak - 1), gem.zhr(gem.peak + 1)));
        MeteorShowers.Shower gno = MeteorShowers.byCode("GNO");
        check(gno.active(359) && gno.active(5) && !gno.active(10), "gamma-Normids: active across longitude 0");
        double jPer = when(140.0);
        check(MeteorShowers.active(jPer).get(0).code.equals("PER"), "the Perseids lead on their night: " + MeteorShowers.active(jPer));
        check(MeteorShowers.active(jd(2026, 6, 10, 6, 0)).isEmpty(), "nothing on the list on the 10th of June");
        int majors = 0;
        for (MeteorShowers.Shower s : MeteorShowers.ALL) if (s.major()) majors++;
        check(majors >= 9 && majors <= 14, majors + " major showers");
    }

    static void radiants() {
        MeteorShowers.Shower per = MeteorShowers.byCode("PER");
        double[] rd = Astro.raDec(per.radiant(per.peak - 10));
        check(Math.abs(rd[0] / Astro.DEG - 34.5) < 1e-6 && Math.abs(rd[1] / Astro.DEG - 56.8) < 1e-6,
                String.format(Locale.US, "Perseid radiant drifts: RA %.1f, Dec %.1f ten days before the peak", rd[0] / Astro.DEG, rd[1] / Astro.DEG));
        // zenith attraction: Schiaparelli's formula, by hand for a slow and a fast shower 60 degrees from the zenith
        double[] zen = {0, 0, 1};
        double[] rad = {Math.sin(60 * Astro.DEG), 0, Math.cos(60 * Astro.DEG)};
        double slow = 60 - Astro.sep(MeteorShowers.apparentRadiant(rad, zen, 20), zen) / Astro.DEG;
        double fast = 60 - Astro.sep(MeteorShowers.apparentRadiant(rad, zen, 71), zen) / Astro.DEG;
        double vg = Math.sqrt(400 - 11.1 * 11.1);
        double expect = 2 * Math.atan((20 - vg) / (20 + vg) * Math.tan(30 * Astro.DEG)) / Astro.DEG;
        check(Math.abs(slow - expect) < 1e-6 && slow > 5.5 && fast < 0.5 && fast > 0.3,
                String.format(Locale.US, "zenith attraction 60 degrees out: %.2f degrees for 20 km/s meteors, %.2f for 71", slow, fast));
        double[] up = MeteorShowers.apparentRadiant(zen, zen, 20);
        check(Astro.sep(up, zen) < 1e-9, "none at the zenith");
        // a Perseid 90 degrees from its radiant, overhead: 59 km/s at 103 km
        double w = MeteorShowers.angularSpeed(59, 90, 90);
        check(Math.abs(w - 59 / (85 + 0.3 * 59) / Astro.DEG) < 0.01, String.format(Locale.US, "a Perseid overhead crosses %.1f degrees a second", w));
        check(MeteorShowers.angularSpeed(59, 10, 90) < 0.2 * w && MeteorShowers.angularSpeed(59, 90, 20) < 0.4 * w,
                "slower near its radiant, and low in the sky");
    }

    static double[] zenith(double jd, double lat, double lon) {
        double[] m = Rot.transpose(Astro.j2000ToEnu(jd, lat * Astro.DEG, lon * Astro.DEG));
        double[] z = new double[3];
        Rot.apply(m, 0, 0, 1, z);
        return z;
    }

    static double[] along(double[] p, double[] dir, double ang) {
        double c = Math.cos(ang), s = Math.sin(ang);
        double[] o = {p[0] * c + dir[0] * s, p[1] * c + dir[1] * s, p[2] * c + dir[2] * s};
        double l = Math.sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2]);
        return new double[]{o[0] / l, o[1] / l, o[2] / l};
    }

    static double[] tangent(double[] p, double[] u) {
        double d = u[0] * p[0] + u[1] * p[1] + u[2] * p[2];
        double[] t = {u[0] - d * p[0], u[1] - d * p[1], u[2] - d * p[2]};
        double l = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
        return new double[]{t[0] / l, t[1] / l, t[2] / l};
    }

    static double[] random(double[] p, Random r) {
        return tangent(p, new double[]{r.nextGaussian(), r.nextGaussian(), r.nextGaussian()});
    }

    static double[] inCap(double[] z, double capDeg, Random r) {
        double ang = Math.acos(1 - r.nextDouble() * (1 - Math.cos(capDeg * Astro.DEG)));
        return along(z, random(z, r), ang);
    }

    static double[] jitter(double[] p, double sig, Random r) {
        return along(p, random(p, r), Math.abs(r.nextGaussian()) * sig);
    }

    static void attribution() {
        // members of a shower at its peak: from near its radiant (its spread), 2-20 degrees long, ends good to 1.5 px
        // at 0.02 degrees a pixel, in a 40-degree field around the zenith; either end given first
        String[][] cases = {{"GEM", "2026-12-14T07", "40", "-75"}, {"PER", "2026-08-13T07", "40", "-75"}, {"ORI", "2026-10-21T09", "40", "-75"},
                {"QUA", "2026-01-03T10", "40", "-75"}, {"ETA", "2026-05-06T18", "-30", "150"}};
        Random rnd = new Random(1);
        double pxDeg = 0.02;
        for (String[] c : cases) {
            double jd = iso(c[1]);
            double[] z = zenith(jd, Double.parseDouble(c[2]), Double.parseDouble(c[3]));
            MeteorShowers.Shower sh = MeteorShowers.byCode(c[0]);
            double lon = MeteorShowers.solarLongitude(jd);
            double[] rad = MeteorShowers.apparentRadiant(sh.radiant(lon), z, sh.speed);
            int n = 0, ok = 0, right = 0;
            while (n < 1000) {
                double[] start = inCap(z, 40, rnd);
                double[] r0 = jitter(rad, sh.spread * Astro.DEG, rnd);
                double d = Astro.sep(r0, start);
                if (d < 3 * Astro.DEG || d > 95 * Astro.DEG) continue;
                double[] t = tangent(start, new double[]{start[0] - r0[0], start[1] - r0[1], start[2] - r0[2]});
                double len = 2 + rnd.nextDouble() * 18;
                double[] end = along(start, t, len * Astro.DEG);
                double[] a = jitter(start, 1.5 * pxDeg * Astro.DEG, rnd), b = jitter(end, 1.5 * pxDeg * Astro.DEG, rnd);
                boolean swap = rnd.nextBoolean();
                MeteorShowers.Match m = MeteorShowers.attribute(swap ? b : a, swap ? a : b, len / pxDeg, jd, z);
                n++;
                if (m.shower == sh) {
                    ok++;
                    if (m.reversed == swap) right++;
                }
            }
            int fa = 0;
            for (int i = 0; i < 2000; i++) {
                double[] start = inCap(z, 40, rnd);
                double len = 2 + rnd.nextDouble() * 18;
                double[] end = along(start, random(start, rnd), len * Astro.DEG);
                if (MeteorShowers.attribute(start, end, len / pxDeg, jd, z).shower != null) fa++;
            }
            check(ok >= 0.9 * n && right == ok && fa <= 0.25 * 2000, String.format(Locale.US, "%s night: %d of %d members named (all flying the "
                    + "right way: %b), %.1f%% of random meteors given to a shower", sh.name, ok, n, right == ok, 100.0 * fa / 2000));
        }
        // a quiet night: no shower to give them to
        double jd = jd(2026, 3, 1, 7, 0);
        double[] z = zenith(jd, 40, -75);
        int fa = 0;
        for (int i = 0; i < 500; i++) {
            double[] start = inCap(z, 40, rnd);
            double[] end = along(start, random(start, rnd), 10 * Astro.DEG);
            if (MeteorShowers.attribute(start, end, 500, jd, z).shower != null) fa++;
        }
        check(fa == 0, "the 1st of March: every meteor sporadic");
        // a radiant below the horizon sends none: the eta-Aquariids from Sydney in the evening
        double jdDay = jd(2026, 5, 6, 9, 30);
        double[] zd = zenith(jdDay, -34, 151);
        MeteorShowers.Shower eta = MeteorShowers.byCode("ETA");
        double[] rad = MeteorShowers.apparentRadiant(eta.radiant(MeteorShowers.solarLongitude(jdDay)), zd, eta.speed);
        boolean below = Astro.sep(rad, zd) > 92 * Astro.DEG;
        double[] start = along(rad, random(rad, rnd), 60 * Astro.DEG);
        double[] t = tangent(start, new double[]{start[0] - rad[0], start[1] - rad[1], start[2] - rad[2]});
        double[] end = along(start, t, 10 * Astro.DEG);
        check(below && MeteorShowers.attribute(start, end, 500, jdDay, zd).shower == null, "a radiant below the horizon names no meteor");
    }

    static void shadow() {
        // the zenith from 40 N through two nights; a cylinder of the Earth's radius: R (1 / cos(depression) - 1)
        double worst = 0;
        int nights = 0;
        for (double start : new double[]{jd(2026, 6, 21, 0, 0), jd(2026, 12, 21, 0, 0)}) {
            for (int h = 0; h < 24; h++) {
                SatSky.Moment m = SatSky.moment(start + h / 24.0, 40 * Astro.DEG, -105 * Astro.DEG, 0);
                double[] z = new double[3];
                m.toJ2000(new double[]{0, 0, 1}, z);
                double[] e = SatSky.shadowExit(m, z);
                if (m.sunAltDeg > 0) {
                    worst = Math.max(worst, Math.abs(e[1]) + Math.abs(e[0]));
                    continue;
                }
                nights++;
                // the observer sits a little inside the equatorial radius at 40 N
                double r0 = 6369.3, dep = -m.sunAltDeg * Astro.DEG;
                double expect = Astro.EARTH_RADIUS_KM / Math.cos(dep) - Astro.EARTH_RADIUS_KM;
                double got = e[1];
                worst = Math.max(worst, Math.abs(got - expect) / Math.max(50, expect) * 100 > 3 ? 1e9 : 0);
                if (Math.abs((e[0] + r0) - (got + Astro.EARTH_RADIUS_KM)) > 15) worst = 1e9;
            }
        }
        check(worst == 0 && nights >= 20, "shadow height straight up: the cylinder's, to 3%, for " + nights + " night hours; none by day");
        SatSky.Moment mid = SatSky.moment(jd(2026, 12, 21, 7, 0), 40 * Astro.DEG, -105 * Astro.DEG, 0);
        double[] z = new double[3];
        mid.toJ2000(new double[]{0, 0, 1}, z);
        double[] e = SatSky.shadowExit(mid, z);
        double w = Math.toDegrees(SatSky.fastest(e[1]) / e[0]);
        check(e[1] > 15000 && w < 0.03, String.format(Locale.US, "a winter midnight: the shadow reaches %.0f km, where nothing crosses faster "
                + "than %.3f degrees a second", e[1], w));
        SatSky.Moment eve = SatSky.moment(jd(2026, 6, 21, 4, 0), 40 * Astro.DEG, -105 * Astro.DEG, 0);
        eve.toJ2000(new double[]{0, 0, 1}, z);
        e = SatSky.shadowExit(eve, z);
        w = Math.toDegrees(SatSky.fastest(e[1]) / e[0]);
        check(e[1] > 150 && e[1] < 250 && w > 2, String.format(Locale.US, "a June dusk: %.0f km, and %.1f degrees a second", e[1], w));
    }

    /** Frames for {@link SatMatch#satellitePossible}: n frames of 8 s, 0.1 s apart, a 2000 x 1500 picture at 0.02 degrees a pixel. */
    static final class Frames implements SatMatch.Frames {
        int n = 10;
        double gapAfter = -1, shadow;

        @Override
        public int count() {
            return n;
        }

        @Override
        public double start(int f) {
            return f * 8.1 + (gapAfter >= 0 && f > gapAfter ? 20 : 0);
        }

        @Override
        public double exposure(int f) {
            return 8;
        }

        @Override
        public double inside(int f, double x, double y) {
            return Math.min(Math.min(x, 2000 - x), Math.min(y, 1500 - y));
        }

        @Override
        public double shadowSpeed(int f, double x, double y) {
            return shadow;
        }

        @Override
        public double degrees(double x0, double y0, double x1, double y1) {
            return Math.hypot(x1 - x0, y1 - y0) * 0.02;
        }
    }

    static SatMatch.Streak streak(int plan, double x0, double y0, double x1, double y1) {
        SatMatch.Streak s = new SatMatch.Streak();
        s.plan = plan;
        s.sx0 = s.x0 = (float) x0;
        s.sy0 = s.y0 = (float) y0;
        s.sx1 = s.x1 = (float) x1;
        s.sy1 = s.y1 = (float) y1;
        s.halfWidth = 10;
        return s;
    }

    static void kinds() {
        Frames fr = new Frames();
        check(!SatMatch.satellitePossible(streak(5, 800, 600, 1100, 800), fr), "both ends in open sky, mid-session, no shadow there: a meteor");
        check(SatMatch.satellitePossible(streak(5, 5, 600, 300, 800), fr) == false, "one end at the edge, one in open sky: still a meteor");
        check(SatMatch.satellitePossible(streak(5, 5, 600, 1995, 900), fr) == false, "edge to edge, 40 degrees in 8 s: too fast for a satellite");
        check(SatMatch.satellitePossible(streak(5, 5, 300, 300, 5), fr), "edge to edge across a corner: a satellite could");
        check(SatMatch.satellitePossible(streak(0, 5, 600, 300, 800), fr), "the session's first frame: a satellite there as it began");
        check(SatMatch.satellitePossible(streak(9, 5, 600, 300, 800), fr), "its last: one there as it ended");
        check(!SatMatch.satellitePossible(streak(0, 800, 600, 1100, 800), fr), "the first frame, both ends in open sky: one end only can be the start");
        fr.gapAfter = 5;
        check(SatMatch.satellitePossible(streak(6, 5, 600, 300, 800), fr), "after a gap in the frames: as good as a first frame");
        fr.gapAfter = -1;
        fr.shadow = 0.5;
        check(SatMatch.satellitePossible(streak(5, 5, 600, 150, 700), fr), "into the Earth's shadow there: 3.5 degrees in 8 s, and one as high "
                + "as the shadow could go 0.5 a second");
        check(!SatMatch.satellitePossible(streak(5, 5, 600, 600, 800), fr), "but not 12.6 degrees: too fast for a satellite as high as the shadow");
        check(!SatMatch.satellitePossible(streak(5, 800, 600, 900, 700), fr), "and not both ends: it goes into or out of the shadow once");
    }

    static void check(boolean ok, String what) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what);
        if (!ok) failures++;
    }
}
