package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.GoldenRecord;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.SkySolver;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Earth's Address (2.6): the pulsar map's numbers against what's known of it (its 14 binary periods decode to the
 * periods the plaque was made from; today's timing puts them at about 1970, but for the one the plaque rounds; its
 * angles follow the pulsars' galactic longitudes), the periods worked out for a night (the Crab's slowdown), the
 * digits that changed, which pulsars a photo of Cygnus has and which were up, and the picture (a PNG to look at when an
 * output folder is given).
 * <p>
 * Usage: RecordTest [outDir]
 */
public final class RecordTest {
    static int failed;

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    public static void main(String[] args) throws Exception {
        File out = args.length > 0 ? new File(args[0]) : null;
        if (out != null) out.mkdirs();
        System.out.println("== Earth's Address");
        List<GoldenRecord.Pulsar> map = GoldenRecord.map();
        check("14 pulsars, 413 digits", map.size() == 14 && GoldenRecord.digits() == 413, map.size() + ", " + GoldenRecord.digits());

        // ---- the plaque's binary periods are those it was made from (Johnston's Table 6, 1970/71), to a hydrogen flip
        double[] given = {0.8296830000, 0.2633767640, 0.3880000000, 0.08921874790, 0.2530650432, 0.5306595990, 0.03312964500,
                3.745490800, 0.7145186424, 0.5384673780, 0.5579533900, 0.3587354200, 0.2265170380, 0.3876887790};
        double worst = 0;
        for (int i = 0; i < 14; i++) worst = Math.max(worst, Math.abs(GoldenRecord.plaqueSeconds(map.get(i)) - given[i]));
        check("each binary period is the plaque's, within one hydrogen flip", worst < GoldenRecord.HYDROGEN_S,
                String.format(Locale.US, "%.2g s at most", worst));
        // ---- today's timing puts the plaque at about 1970, all but B1240-64, whose period the plaque rounds to 0.388 s
        double[] years = new double[13];
        int k = 0;
        String odd = "";
        for (GoldenRecord.Pulsar p : map) {
            double y = GoldenRecord.yearOfPlaque(p);
            if (Math.abs(y - 1970) > 4) odd += (odd.isEmpty() ? "" : ", ") + p.shortName();
            else years[k++] = y;
        }
        java.util.Arrays.sort(years, 0, k);
        double median = years[k / 2];
        check("the periods give the map's date, about 1970, but for B1240-64", odd.equals("B1240-64") && Math.abs(median - 1970) < 1.5,
                String.format(Locale.US, "median %.1f of %d; off: %s", median, k, odd));
        // ---- the lines' angles: a pulsar's galactic longitude, measured round from the centre's line
        worst = 0;
        String worstName = "";
        for (GoldenRecord.Pulsar p : map) {
            double l = galacticLongitude(p.dir);
            double want = l > 180 ? l - 360 : l;
            double err = Math.abs(p.angleDeg - want);
            if (err > worst) {
                worst = err;
                worstName = p.shortName();
            }
        }
        check("each line's angle is its pulsar's galactic longitude", worst < 4, String.format(Locale.US, "%.1f deg at most (%s)", worst, worstName));
        boolean order = true;
        for (int i = 1; i < 8; i++) order &= map.get(i).angleDeg < map.get(i - 1).angleDeg;
        for (int i = 9; i < 14; i++) order &= map.get(i).angleDeg < map.get(i - 1).angleDeg;
        check("clockwise from the centre's line", order && map.get(7).angleDeg < -170 && map.get(8).angleDeg > 140, "");

        // ---- a night: 24 November 2026, 23:30 UTC
        double jd = XRayTest.jdUtc(2026, 11, 24, 23, 30, 0);
        GoldenRecord.Pulsar crab = map.get(6);
        double now = GoldenRecord.periodAt(crab, jd);
        check("the Crab's period that night", Math.abs(now - 0.0338621) < 2e-6, String.format(Locale.US, "%.7f s", now));
        double slowed = GoldenRecord.slowedSince1971(crab, jd);
        check("the Crab has slowed by about 0.74 thousandths of a second since 1971", Math.abs(slowed - 7.4e-4) < 0.2e-4,
                String.format(Locale.US, "%.3g s", slowed));
        check("changed digits counted at the right end", GoldenRecord.changed("1011", "1001") == 1 && GoldenRecord.changed("111", "1000") == 4
                && GoldenRecord.changed("1010", "1010") == 0, "");

        // ---- a photo of Cygnus from Ohio, as X-Ray Vision's test takes
        CameraModel lens = CameraModel.nominal(4080, 3072, 2875);
        SkySolver.Solution sol = FiguresTest.solution(lens, 300, 38, 15);
        SessionSky here = new SessionSky();
        here.latDeg = 39.9;
        here.lonDeg = -83.0;
        TimeZone ny = TimeZone.getTimeZone("America/New_York");
        double jdp = XRayTest.jdUtc(2026, 11, 23, 23, 30, 0);
        List<GoldenRecord.Mark> marks = GoldenRecord.marks(sol, jdp, here, 0, 0, 4080, 3072);
        for (GoldenRecord.Mark m : marks) {
            System.out.println("    " + m.head);
            if (m.inPhoto || m.up) System.out.println("      " + m.words);
        }
        GoldenRecord.Mark b2016 = mark(marks, "B2016+28"), b1933 = mark(marks, "B1933+16"), b0329 = mark(marks, "B0329+54"),
                vela = mark(marks, "B0833-45");
        check("B2016+28 in the photo of Cygnus", b2016 != null && b2016.inPhoto, b2016 == null ? "" : b2016.head);
        check("B0329+54 up in the north-east, Vela below the horizon", b0329 != null && b0329.up && !b0329.inPhoto && b0329.azDeg > 0
                && b0329.azDeg < 90 && vela != null && !vela.up, b0329 == null ? "" : b0329.head);
        check("every pulsar has its tick that night", b1933 != null && b1933.words.contains("That night it ticked every 0.3587")
                && b1933.words.contains("In Aquila"), b1933 == null ? "" : b1933.words);
        int ch = GoldenRecord.changed(marks);
        check("about a third of the digits changed since 1971", ch > 100 && ch < 160, ch + " of " + GoldenRecord.digits());
        String sum = GoldenRecord.summary(marks);
        System.out.println("  summary: " + sum);
        check("the summary", sum.startsWith(ch + " of the map's 413 digits") && sum.contains("in your photo"), sum);
        check("the date in words", GoldenRecord.day(jdp, ny).equals("23 November 2026"), GoldenRecord.day(jdp, ny));
        List<GoldenRecord.Mark> hear = GoldenRecord.toHear(marks);
        check("those in the photo heard first", hear.size() == 14 && hear.get(0).inPhoto, "");
        short[] tour = GoldenRecord.tour(hear.subList(0, 2), 1.5, 0.25, 22050);
        check("their ticks", tour.length == 2 * 2 * (int) Math.round(1.75 * 22050), tour.length + " samples");
        // no photo placed, no place: still the map, nothing picked out
        List<GoldenRecord.Mark> bare = GoldenRecord.marks(null, jdp, null, 0, 0, 0, 0);
        check("with no photo placed, the map all the same", bare.size() == 14 && GoldenRecord.inPhoto(bare) == 0 && GoldenRecord.up(bare) == 0
                && GoldenRecord.changed(bare) == ch, GoldenRecord.summary(bare));

        // ---- the picture
        int w = 1600, h = 1600;
        BrightStars cat = SimRunner.loadCatalog();
        int[] photo = XRayTest.sky(cat, sol, 1200, 900);
        GoldenRecord.Layout l = GoldenRecord.layout(w, h);
        int[] px = GoldenRecord.background(l, photo, 1200, 900);
        AwtPainter p = new AwtPainter(px, w, h);
        GoldenRecord.Drawn d = GoldenRecord.paint(p, l, marks, jdp, ny);
        int[] pic = p.pixels();
        check("all 14 lines drawn, most named", d.lines == 14 && d.labels >= 12, d.lines + " lines, " + d.labels + " names");
        check("the changed digits drawn in blue", d.newDigits == ch, d.newDigits + " drawn");
        // the photo fills its disc: the middle isn't the black of space
        int mid = pic[(int) l.cy * w + (int) l.cx];
        check("the photo in the disc at the Sun's place", (mid & 0xFFFFFF) != 0 && ((mid >> 16) & 255) + ((mid >> 8) & 255) + (mid & 255) > 20,
                String.format("%06x", mid & 0xFFFFFF));
        if (out != null) {
            PhotoVideoTest.writePng(pic, w, h, new File(out, "address.png"));
            System.out.println("  picture: " + new File(out, "address.png"));
        }
        System.out.println(failed == 0 ? "ALL PASS" : failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static GoldenRecord.Mark mark(List<GoldenRecord.Mark> ms, String shortName) {
        for (GoldenRecord.Mark m : ms) if (m.p.shortName().equals(shortName)) return m;
        return null;
    }

    /** Galactic longitude (degrees, 0 to 360) of a J2000 direction (the IAU's pole and centre, J2000). */
    static double galacticLongitude(double[] d) {
        // rows: the galactic x (centre), y, z (north pole) axes in J2000
        double[][] m = {{-0.0548755604, -0.8734370902, -0.4838350155}, {0.4941094279, -0.4448296300, 0.7469822445},
                {-0.8676661490, -0.1980763734, 0.4559837762}};
        double x = m[0][0] * d[0] + m[0][1] * d[1] + m[0][2] * d[2], y = m[1][0] * d[0] + m[1][1] * d[1] + m[1][2] * d[2];
        double l = Math.toDegrees(Math.atan2(y, x));
        return l < 0 ? l + 360 : l;
    }
}
