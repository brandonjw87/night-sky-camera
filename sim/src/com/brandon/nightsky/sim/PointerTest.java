package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.SkyPointer;

import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Sky Pointer (2.6, the watch): the sky it works out against an independent sum (the stars' height and compass bearing
 * from the sidereal time, the North Star at the latitude's height, the Sun's height at midsummer noon), the arm from the
 * watch's rotation matrix (which way the crown faces, magnetic north turned to true north), what it says the arm points
 * at, how it steers the arm to a thing (the arrow, the words, the quickening clicks), and the list the crown goes through.
 * <p>
 * Usage: PointerTest
 */
public final class PointerTest {
    static int failed;

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    static long utc(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    /** East-north-up from height and compass bearing (degrees). */
    static double[] enu(double altDeg, double azDeg) {
        double a = altDeg * Astro.DEG, z = azDeg * Astro.DEG;
        return new double[]{Math.cos(a) * Math.sin(z), Math.cos(a) * Math.cos(z), Math.sin(a)};
    }

    /**
     * Height and bearing (degrees) of a J2000 RA/Dec (degrees) the plain way: the mean sidereal time (IAU 1982), the hour
     * angle and the spherical triangle, no precession or nutation (a third of a degree over the 26 years since 2000).
     */
    static double[] altAz(double raDeg, double decDeg, long unixMs, double latDeg, double lonDeg) {
        double jd = unixMs / 86400000.0 + 2440587.5, d = jd - 2451545.0;
        double gmstH = 18.697374558 + 24.06570982441908 * d;
        double lst = ((gmstH * 15 + lonDeg) % 360 + 360) % 360;
        double H = (lst - raDeg) * Astro.DEG, dec = decDeg * Astro.DEG, lat = latDeg * Astro.DEG;
        double alt = Math.asin(Math.sin(lat) * Math.sin(dec) + Math.cos(lat) * Math.cos(dec) * Math.cos(H));
        double az = Math.atan2(-Math.sin(H) * Math.cos(dec), Math.sin(dec) * Math.cos(lat) - Math.cos(dec) * Math.sin(lat) * Math.cos(H));
        return new double[]{alt / Astro.DEG, ((az / Astro.DEG) % 360 + 360) % 360};
    }

    /**
     * A rotation matrix as Android gives it (rows first: the watch's axes into east, magnetic north and up) for a watch
     * whose x axis (toward the crown) points along {@code x} and whose face looks as near up as it can.
     */
    static float[] watch(double[] x) {
        double[] up = {0, 0, 1};
        // the face's direction: up, less its part along the arm
        double k = up[0] * x[0] + up[1] * x[1] + up[2] * x[2];
        double[] z = {up[0] - k * x[0], up[1] - k * x[1], up[2] - k * x[2]};
        double n = Math.sqrt(z[0] * z[0] + z[1] * z[1] + z[2] * z[2]);
        if (n < 1e-6) {
            z = new double[]{0, -1, 0};
            n = 1;
        }
        for (int i = 0; i < 3; i++) z[i] /= n;
        double[] y = {z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0]};
        return new float[]{(float) x[0], (float) y[0], (float) z[0], (float) x[1], (float) y[1], (float) z[1], (float) x[2], (float) y[2], (float) z[2]};
    }

    static int star(BrightStars cat, String name) {
        for (int i = 0; i < cat.n; i++) if (name.equals(cat.name[i])) return i;
        return -1;
    }

    static double off(double[] a, double[] b) {
        return Astro.sep(a, b) / Astro.DEG;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== Sky Pointer");
        BrightStars cat = SimRunner.loadCatalog();
        long t0 = System.nanoTime();
        SkyPointer.Catalogue c = new SkyPointer.Catalogue(cat);
        double catMs = (System.nanoTime() - t0) / 1e6;
        int stars = c.stars();
        check("the stars it names: the catalogue's to magnitude 4.5", stars > 700 && stars < 1100, stars + " stars, made in "
                + String.format(Locale.US, "%.0f ms", catMs));

        // ---- the sky against the plain sums
        long when = utc(2026, 11, 24, 3, 30);
        double lat = 51.48, lon = -0.0015;
        SkyPointer.Sky s = SkyPointer.sky(c, when, lat, lon);
        double worst = 0;
        String worstName = "";
        for (String name : new String[]{"Sirius", "Vega", "Betelgeuse", "Capella", "Deneb", "Aldebaran"}) {
            SkyPointer.Thing t = s.find(name);
            int i = star(cat, name);
            if (t == null || i < 0) {
                check(name + " in the sky", false, "not found");
                continue;
            }
            double[] rd = Astro.raDec(cat.vec(i));
            double[] aa = altAz(rd[0] / Astro.DEG, rd[1] / Astro.DEG, when, lat, lon);
            double d = off(t.enu, enu(aa[0], aa[1]));
            if (d > worst) {
                worst = d;
                worstName = name + String.format(Locale.US, " %.1f° up at %.1f° (plain sum %.1f° up at %.1f°)", t.altDeg, t.azDeg, aa[0], aa[1]);
            }
        }
        check("six bright stars where the plain sums put them, from Greenwich", worst < 0.6, String.format(Locale.US, "%.2f° off at most: %s", worst, worstName));
        // the North Star at the latitude's height, due north, from three places at three times
        double polWorst = 0, polAz = 0;
        for (double la : new double[]{51.48, 35.0, 10.0}) {
            for (long tt : new long[]{when, utc(2026, 3, 1, 22, 0), utc(2027, 7, 14, 4, 45)}) {
                SkyPointer.Thing p = SkyPointer.sky(c, tt, la, 20.0).find("Polaris");
                polWorst = Math.max(polWorst, Math.abs(p.altDeg - la));
                polAz = Math.max(polAz, Math.abs(((p.azDeg + 180) % 360) - 180));
            }
        }
        check("the North Star as high as the latitude, due north", polWorst < 0.8 && polAz < 1.8,
                String.format(Locale.US, "height off by %.2f°, bearing by %.2f° at most", polWorst, polAz));
        // the Sun at midsummer noon in Greenwich: 90 - 51.48 + 23.44 = 61.96 degrees up, due south
        SkyPointer.Thing sun = SkyPointer.sky(c, utc(2026, 6, 21, 12, 2), 51.48, 0).find("the Sun");
        check("the Sun 62° up in the south at midsummer noon", Math.abs(sun.altDeg - 61.96) < 0.3 && Math.abs(sun.azDeg - 180) < 1.5,
                String.format(Locale.US, "%.2f° up at %.1f°", sun.altDeg, sun.azDeg));

        // ---- the arm
        double[] aim = enu(30, 120);
        double[] a = SkyPointer.arm(watch(aim), false, 0);
        check("the arm along the watch's x axis (the crown toward the hand)", off(a, aim) < 0.01, String.format(Locale.US, "%.4f°", off(a, aim)));
        double[] flipped = SkyPointer.arm(watch(new double[]{-aim[0], -aim[1], -aim[2]}), true, 0);
        check("the other way round with the crown toward the elbow", off(flipped, aim) < 0.01, String.format(Locale.US, "%.4f°", off(flipped, aim)));
        // pointing at magnetic bearing 100 where magnetic north is 14 degrees east of true: true bearing 114
        double[] m = SkyPointer.arm(watch(enu(20, 100)), false, 14);
        check("magnetic north turned to true north (14° east: bearing 100 is 114)", Math.abs(SkyPointer.azDeg(m) - 114) < 0.01
                && Math.abs(SkyPointer.altDeg(m) - 20) < 0.01, String.format(Locale.US, "%.2f° up at %.2f°", SkyPointer.altDeg(m), SkyPointer.azDeg(m)));
        double[] w = SkyPointer.arm(watch(enu(20, 10)), false, -12);
        check("and west of true north (-12°: bearing 10 is 358)", Math.abs(SkyPointer.azDeg(w) - 358) < 0.01, String.format(Locale.US, "%.2f°", SkyPointer.azDeg(w)));

        // ---- what's there
        SkyPointer.Thing vega = s.find("Vega");
        SkyPointer.Look l = SkyPointer.look(s, vega.enu);
        check("pointing at Vega: Vega", l.best == vega, l.best == null ? "nothing" : l.best.name + ", " + l.best.about);
        SkyPointer.Thing moon = s.find("the Moon");
        SkyPointer.Look lm = SkyPointer.look(s, moon.enu);
        check("pointing at the Moon: the Moon, and how much of it is lit", lm.best == moon && moon.about.contains("% lit"),
                lm.best == null ? "nothing" : lm.best.name + ", " + lm.best.about);
        // a planet from 5 degrees off (a watch's compass is that good at best): still the planet
        SkyPointer.Thing jup = s.find("Jupiter");
        double[] nearJ = SkyPointer.arm(watch(enu(jup.altDeg + 3, jup.azDeg + 4 / Math.cos(jup.altDeg * Astro.DEG))), false, 0);
        SkyPointer.Look lj = SkyPointer.look(s, nearJ);
        check("5° from Jupiter: Jupiter", lj.best == jup, String.format(Locale.US, "%.1f° off: %s", off(nearJ, jup.enu),
                lj.best == null ? "nothing" : lj.best.name));
        // Sagittarius A*: hidden by the stars round it, but said
        SkyPointer.Thing sgr = s.find("Sagittarius A*");
        SkyPointer.Look ls = SkyPointer.look(s, sgr.enu);
        check("at the galaxy's black hole: it, or a star there with the black hole mentioned", ls.best == sgr || ls.alsoNear == sgr,
                (ls.best == null ? "nothing" : ls.best.name) + (ls.alsoNear != null ? " (also " + ls.alsoNear.name + ")" : ""));
        // straight down: through the Earth
        SkyPointer.Look ld = SkyPointer.look(s, new double[]{0, 0, -1});
        check("straight down: under your feet", ld.altDeg < -85 && SkyPointer.where(ld.altDeg, ld.azDeg).equals("straight under your feet"), SkyPointer.where(ld.altDeg, ld.azDeg));
        check("where in words", SkyPointer.where(62.4, 90).equals("62° up in the " + com.brandon.nightsky.processing.Probes.compass(90))
                && SkyPointer.where(-30, 225).startsWith("30° below the horizon") && SkyPointer.where(88, 10).equals("straight overhead"),
                SkyPointer.where(62.4, 90) + "; " + SkyPointer.where(-30, 225));
        // empty sky: well away from everything bright, nothing named
        double[] empty = null;
        for (int az = 0; az < 360 && empty == null; az += 5) {
            for (int al = 20; al <= 80 && empty == null; al += 5) {
                double[] e = enu(al, az);
                if (SkyPointer.look(s, e).best == null) empty = e;
            }
        }
        check("somewhere with nothing bright: nothing named", empty != null, empty == null ? "everywhere names something"
                : String.format(Locale.US, "%.0f° up at %.0f°", SkyPointer.altDeg(empty), SkyPointer.azDeg(empty)));

        // ---- finding
        SkyPointer.Thing target = s.find("Capella");
        double[] from = SkyPointer.arm(watch(enu(target.altDeg - 30, target.azDeg)), false, 0);
        SkyPointer.Guide g = SkyPointer.guide(from, target);
        check("30° under it: higher, the arrow straight up", g.words.equals("Higher") && Math.abs(((g.arrowDeg + 180) % 360) - 180) < 3 && !g.got,
                g.words + String.format(Locale.US, ", arrow %.0f°", g.arrowDeg));
        double[] left = enu(target.altDeg, target.azDeg - 25 / Math.cos(target.altDeg * Astro.DEG));
        SkyPointer.Guide gr = SkyPointer.guide(left, target);
        check("25° to its left: to the right, the arrow at 3 o'clock", gr.words.equals("To the right") && Math.abs(gr.arrowDeg - 90) < 8,
                gr.words + String.format(Locale.US, ", arrow %.0f°", gr.arrowDeg));
        SkyPointer.Guide gb = SkyPointer.guide(enu(10, target.azDeg + 180), target);
        check("pointing the other way: turn around", gb.behind && gb.words.startsWith("Turn around"), gb.words);
        SkyPointer.Guide gg = SkyPointer.guide(enu(target.altDeg + 2, target.azDeg), target);
        check("2° off: got it, with the long buzz", gg.got && gg.words.equals("Got it!") && gg.closeness > 0.98, gg.words);
        // the clicks quicken all the way in
        long last = Long.MAX_VALUE;
        boolean quicker = true;
        String ticks = "";
        for (double d : new double[]{40, 30, 20, 12, 8}) {
            SkyPointer.Guide gt = SkyPointer.guide(enu(target.altDeg - d, target.azDeg), target);
            if (gt.tickMs <= 0 || gt.tickMs >= last) quicker = false;
            last = gt.tickMs;
            ticks += (ticks.isEmpty() ? "" : ", ") + gt.tickMs;
        }
        SkyPointer.Guide far = SkyPointer.guide(enu(target.altDeg - 60, target.azDeg), target);
        check("clicks quicker the closer (none from far off)", quicker && far.tickMs == 0, ticks + " ms; at 60°: " + far.tickMs);
        // below the horizon: pointing through the Earth still finds it
        SkyPointer.Thing under = null;
        for (SkyPointer.Thing t : s.things) if (t.kind == SkyPointer.Kind.PLANET && !t.up()) under = t;
        if (under != null) {
            SkyPointer.Guide gu = SkyPointer.guide(under.enu, under);
            check(under.name + " below the horizon, found through the Earth", gu.got && gu.below, String.format(Locale.US, "%.0f° down", -under.altDeg));
        }

        // ---- the crown's list
        List<SkyPointer.Thing> list = SkyPointer.targets(s);
        Set<String> names = new HashSet<String>();
        boolean unique = true;
        int planets = 0, holes = 0, brightStars = 0;
        StringBuilder sb = new StringBuilder();
        for (SkyPointer.Thing t : list) {
            if (!names.add(t.name)) unique = false;
            if (t.kind == SkyPointer.Kind.PLANET) planets++;
            if (t.kind == SkyPointer.Kind.BLACK_HOLE) holes++;
            if (t.kind == SkyPointer.Kind.STAR) brightStars++;
            if (sb.length() < 400) sb.append(sb.length() > 0 ? ", " : "").append(t.name);
        }
        check("the crown goes: the Moon first, the Sun last, each once", !list.isEmpty() && list.get(0) == moon && list.get(list.size() - 1).name.equals("the Sun")
                && unique, list.size() + " things");
        check("the planets, Voyager 1, Webb, the black holes, Andromeda, Polaris and up to 16 bright stars on it",
                planets == 7 && names.contains("Voyager 1") && names.contains("James Webb (L2)") && holes == 3 && names.contains("Andromeda Galaxy")
                        && names.contains("Polaris") && names.contains("Pluto") && brightStars >= 6 && brightStars <= 16, sb.toString());
        boolean upFirst = true;
        boolean seenDown = false;
        for (SkyPointer.Thing t : list) {
            if (t.kind != SkyPointer.Kind.PLANET) continue;
            if (!t.up()) seenDown = true;
            else if (seenDown) upFirst = false;
        }
        check("the planets that are up come first", upFirst, "");

        // ---- quick enough for a watch (several times slower than this computer)
        for (int i = 0; i < 20; i++) SkyPointer.look(SkyPointer.sky(c, when + i * 2000L, lat, lon), vega.enu);
        long ta = System.nanoTime();
        SkyPointer.Sky s2 = null;
        for (int i = 0; i < 20; i++) s2 = SkyPointer.sky(c, when + i * 2000L, lat, lon);
        double skyMs = (System.nanoTime() - ta) / 1e6 / 20;
        ta = System.nanoTime();
        for (int i = 0; i < 200; i++) SkyPointer.look(s2, enu(i % 90, i * 7 % 360));
        double lookMs = (System.nanoTime() - ta) / 1e6 / 200;
        check("quick: the sky in under 20 ms, a look in under 1 ms", skyMs < 20 && lookMs < 1, String.format(Locale.US, "%.2f ms, %.3f ms", skyMs, lookMs));

        System.out.println(failed == 0 ? "ALL PASS" : failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
