package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.SkySolver;
import com.brandon.nightsky.processing.XRayVision;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * X-Ray Vision (2.5): the catalogue's positions against the app's own star catalogue (the planet hosts it has) and
 * OpenNGC (the nebulae, clusters and galaxies), each where it should be in a photo of Cygnus (Cygnus X-1 a known
 * number of pixels from the centre, Kepler's field as a square of its size, the Veil as a ring of its size), what's up
 * and what isn't from a place, the words, a pulsar's beat at its real rate, and the picture drawn (written as a PNG to
 * look at when an output folder is given).
 * <p>
 * Usage: XRayTest [outDir]
 */
public final class XRayTest {
    static int failed;

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    public static void main(String[] args) throws Exception {
        File out = args.length > 0 ? new File(args[0]) : null;
        if (out != null) out.mkdirs();
        System.out.println("== X-Ray Vision");
        List<XRayVision.Thing> all = XRayVision.all();
        check("46 things in the catalogue", all.size() == 46, all.size() + "");

        // ---- the positions: the planet hosts the app's star catalogue has, and OpenNGC's objects
        BrightStars cat = SimRunner.loadCatalog();
        String[][] hosts = {{"51 Pegasi", "Helvetios"}, {"Pollux", "Pollux"}, {"Errai", "Errai"}, {"Epsilon Eridani", "Ran"}};
        double worst = 0;
        String worstName = "";
        for (String[] hh : hosts) {
            XRayVision.Thing t = thing(all, hh[0]);
            int k = -1;
            for (int i = 0; i < cat.n; i++) if (hh[1].equals(cat.name[i])) k = i;
            double sep = t == null || k < 0 ? 999 : Astro.sep(t.dir, new double[]{cat.dir[3 * k], cat.dir[3 * k + 1], cat.dir[3 * k + 2]}) / Astro.DEG * 3600;
            if (sep > worst) {
                worst = sep;
                worstName = hh[0];
            }
        }
        check("the planet hosts are where the star catalogue has them", worst < 2, String.format(Locale.US, "%.1f arcsec at most (%s)", worst, worstName));
        // OpenNGC (J2000): the Crab Nebula NGC 1952, M13 NGC 6205, the Eagle Nebula NGC 6611, M31's centre NGC 224, M87 NGC 4486
        Object[][] ngc = {
                {"Crab Pulsar", "05:34:31.97", "+22:00:52.1", 2.0}, {"The Arecibo message", "16:41:41.63", "+36:27:40.7", 1.0},
                {"Pillars of Creation", "18:18:48.17", "-13:48:26.0", 1.0}, {"Andromeda's black hole", "00:42:44.35", "+41:16:08.6", 2.0},
                {"M87*", "12:30:49.42", "+12:23:28.0", 1.0}, {"GW170817", "13:09:47.70", "-23:23:02.0", 15.0},
        };
        worst = 0;
        for (Object[] o : ngc) {
            XRayVision.Thing t = thing(all, (String) o[0]);
            double[] d = Astro.vec(hms((String) o[1]) * Astro.DEG, dms((String) o[2]) * Astro.DEG);
            double sep = t == null ? 999 : Astro.sep(t.dir, d) / Astro.DEG * 3600;
            if (sep > (Double) o[3]) check(o[0] + " where OpenNGC has it", false, String.format(Locale.US, "%.1f arcsec", sep));
            worst = Math.max(worst, sep);
        }
        check("the nebulae, clusters and galaxies where OpenNGC has them (GW170817 is beside its galaxy's middle)", worst < 15,
                String.format(Locale.US, "%.1f arcsec at most", worst));

        // ---- a photo of Cygnus from Ohio on a November evening, the phone's main camera
        CameraModel lens = CameraModel.nominal(4080, 3072, 2875);
        SkySolver.Solution sol = FiguresTest.solution(lens, 300, 38, 15);
        SessionSky here = new SessionSky();
        here.latDeg = 39.9;
        here.lonDeg = -83.0;
        TimeZone ny = TimeZone.getTimeZone("America/New_York");
        double jd = jdUtc(2026, 11, 23, 23, 30, 0);
        List<XRayVision.Spot> spots = XRayVision.find(sol, jd, here, 0, 0, 4080, 3072, ny);
        int in = XRayVision.inPhoto(spots);
        for (XRayVision.Spot s : spots) {
            System.out.println("    " + s.head);
            if (s.inPhoto) System.out.println("      " + s.words);
        }
        XRayVision.Spot cyg = spot(spots, "Cygnus X-1"), kep = spot(spots, "Kepler's field"), veil = spot(spots, "Veil Nebula");
        check("Cygnus X-1, Kepler's field and the Veil are in a photo of Cygnus", cyg != null && cyg.inPhoto && kep != null && kep.inPhoto
                && veil != null && veil.inPhoto, in + " in the photo");
        boolean firstIn = true, seenUp = false;
        for (XRayVision.Spot s : spots) {
            if (!s.inPhoto) seenUp = true;
            else if (seenUp) firstIn = false;
            if (!s.inPhoto && !s.up) firstIn = false;
        }
        check("those in the photo first, then those up in the sky, nothing below the horizon", firstIn && spots.size() > in, spots.size() - in + " up");
        if (cyg != null) {
            double[] p = sol.pixel(XRayVision.all().get(0).dir);
            check("Cygnus X-1 placed where the photo shows it", p != null && Math.abs(cyg.u * 4080 - p[0]) < 0.5 && Math.abs(cyg.v * 3072 - p[1]) < 0.5,
                    p == null ? "" : String.format(Locale.US, "(%.0f, %.0f)", p[0], p[1]));
        }
        if (kep != null && kep.outline != null) {
            double side = Math.hypot((kep.outline[1][0] - kep.outline[0][0]) * 4080, (kep.outline[1][1] - kep.outline[0][1]) * 3072);
            // 10.24 degrees across at the lens' 71.7 arc seconds a pixel near the middle
            double want = 10.24 * 3600 / (206265.0 / 2875);
            check("Kepler's field drawn its size, about 10 degrees across", Math.abs(side - want) < 0.12 * want,
                    String.format(Locale.US, "%.0f px, about %.0f expected", side, want));
        } else {
            check("Kepler's field has its outline", false, "");
        }
        if (veil != null && veil.outline != null) {
            double r = Math.hypot((veil.outline[0][0] - veil.u) * 4080, (veil.outline[0][1] - veil.v) * 3072);
            double want = 1.5 * 3600 / (206265.0 / 2875);
            check("the Veil's ring its size, 3 degrees across", Math.abs(r - want) < 0.12 * want, String.format(Locale.US, "%.0f px radius", r));
        } else {
            check("the Veil has its ring", false, "");
        }
        // the Arecibo message's M13 is up in the west on a November evening from Ohio, Sagittarius A* has set
        XRayVision.Spot m13 = spot(spots, "The Arecibo message"), sgr = spot(spots, "Sagittarius A*");
        check("M13 up in the west, Sagittarius A* below the horizon (left out)", m13 != null && m13.up && m13.azDeg > 240 && m13.azDeg < 320
                && sgr == null, m13 == null ? "M13 missing" : String.format(Locale.US, "M13 %.0f deg high at %.0f", m13.altDeg, m13.azDeg));
        String sum = XRayVision.summary(spots);
        System.out.println("  summary: " + sum);
        check("the summary counts each kind", sum.contains("black holes") && sum.contains("stars with planets") && sum.contains("an exploded star")
                || sum.contains("exploded stars"), sum);
        // words: how far, and when the light set off
        check("Cygnus X-1's words", cyg != null && cyg.words.contains("7,200 light years away") && cyg.words.contains("before the pyramids"),
                cyg == null ? "" : cyg.words);
        check("the light-time words", XRayVision.distance(2.5e6).equals("2.5 million light years")
                && wordsOf(all, "51 Pegasi").contains("left it in 1975") && wordsOf(all, "TON 618").contains("before the Sun and the Earth")
                && wordsOf(all, "GW170817").contains("dinosaurs walked") && wordsOf(all, "Gaia BH1").contains("around the year 470")
                && wordsOf(all, "3C 273").contains("before there were any animals"), wordsOf(all, "Gaia BH1"));
        // no photo placed on the sky: nothing in it, the sky's things only
        List<XRayVision.Spot> none = XRayVision.find(null, jd, here, 0, 0, 4080, 3072, ny);
        check("with no photo placed, only what's up", XRayVision.inPhoto(none) == 0 && !none.isEmpty(), none.size() + " up");

        // ---- a pulsar's beat: the Crab's 30 clicks a second
        short[] crab = XRayVision.beat(29.6, 2.0, 44100);
        int clicks = 0;
        boolean loud = false;
        for (int i = 1; i < crab.length; i++) {
            if (Math.abs(crab[i]) > 3000 && Math.abs(crab[i - 1]) <= 3000 && (clicks == 0 || i > lastClick + 200)) {
                clicks++;
                lastClick = i;
            }
            if (Math.abs(crab[i]) > 8000) loud = true;
        }
        check("the Crab Pulsar's beat: about 30 clicks a second", Math.abs(clicks - 58) <= 3 && loud, clicks + " clicks in 2 s");

        // ---- the picture
        int w = 1600, h = 1200;
        int[] px = sky(cat, sol, w, h);
        XRayVision.tint(px);
        AwtPainter p = new AwtPainter(px, w, h);
        XRayVision.Drawn d = XRayVision.paint(p, w, h, spots, new XRayVision.Place() {
            @Override
            public double[] at(double u, double v) {
                return new double[]{u * 1600, v * 1200};
            }
        });
        int[] pic = p.pixels();
        check("every thing in the photo marked, most of them named", d.marks == in && d.labels >= Math.min(in, 8),
                d.marks + " marks, " + d.labels + " names");
        // Cygnus X-1's mark: the black hole's shadow at its middle, its orange ring round it
        if (cyg != null) {
            int cx = (int) Math.round(cyg.u * w), cy = (int) Math.round(cyg.v * h);
            int mid = pic[cy * w + cx];
            boolean ring = false;
            for (int r = 8; r < 40 && !ring; r++) {
                int c = pic[cy * w + Math.min(w - 1, cx + r)];
                ring = ((c >> 16) & 255) > 200 && ((c >> 8) & 255) > 120 && (c & 255) < 140;
            }
            check("Cygnus X-1 drawn as a black shadow with an orange ring", (mid & 0xFFFFFF) == 0 && ring, String.format("%06x", mid & 0xFFFFFF));
        }
        if (out != null) {
            PhotoVideoTest.writePng(pic, w, h, new File(out, "xray.png"));
            System.out.println("  picture: " + new File(out, "xray.png"));
            // the same over another part of the sky: Orion and Taurus, winter's
            SkySolver.Solution win = FiguresTest.solution(lens, 80, 15, -10);
            List<XRayVision.Spot> ws = XRayVision.find(win, jdUtc(2027, 1, 15, 4, 0, 0), here, 0, 0, 4080, 3072, ny);
            int[] wp = sky(cat, win, w, h);
            XRayVision.tint(wp);
            AwtPainter p2 = new AwtPainter(wp, w, h);
            XRayVision.paint(p2, w, h, ws, new XRayVision.Place() {
                @Override
                public double[] at(double u, double v) {
                    return new double[]{u * 1600, v * 1200};
                }
            });
            PhotoVideoTest.writePng(p2.pixels(), w, h, new File(out, "xray-winter.png"));
            System.out.println("  winter: " + XRayVision.summary(ws));
        }
        System.out.println(failed == 0 ? "ALL PASS" : failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static int lastClick;

    /** A photo of the sky the solution points at, w x h: the catalogue's stars as soft dots, a faint glow, a little grain. */
    static int[] sky(BrightStars cat, SkySolver.Solution sol, int w, int h) {
        float[] v = new float[w * h];
        java.util.Random rnd = new java.util.Random(5);
        for (int i = 0; i < v.length; i++) v[i] = 18 + 5 * (float) rnd.nextGaussian();
        double sx = w / 4080.0, sy = h / 3072.0;
        for (int i = 0; i < cat.n; i++) {
            double[] q = sol.pixel(new double[]{cat.dir[3 * i], cat.dir[3 * i + 1], cat.dir[3 * i + 2]});
            if (q == null) continue;
            double x = q[0] * sx, y = q[1] * sy;
            if (x < -4 || y < -4 || x > w + 4 || y > h + 4) continue;
            double amp = 255 * Math.pow(10, -0.4 * (cat.vmag[i] - 1.0));
            double sig = 0.9 + Math.max(0, 3.5 - cat.vmag[i]) * 0.5;
            for (int yy = (int) (y - 4 * sig); yy <= y + 4 * sig; yy++) {
                for (int xx = (int) (x - 4 * sig); xx <= x + 4 * sig; xx++) {
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                    double r2 = ((xx - x) * (xx - x) + (yy - y) * (yy - y)) / (2 * sig * sig);
                    v[yy * w + xx] += (float) (amp * Math.exp(-r2));
                }
            }
        }
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = (int) Math.max(0, Math.min(255, 255 * Math.pow(v[i] / 255.0, 0.6)));
            px[i] = 0xFF000000 | c << 16 | c << 8 | c;
        }
        return px;
    }

    static XRayVision.Thing thing(List<XRayVision.Thing> all, String name) {
        for (XRayVision.Thing t : all) if (t.name.equals(name)) return t;
        return null;
    }

    static XRayVision.Spot spot(List<XRayVision.Spot> spots, String name) {
        for (XRayVision.Spot s : spots) if (s.what.name.equals(name)) return s;
        return null;
    }

    /** The words for a photo taken in 2026, as the card says them. */
    static String wordsOf(List<XRayVision.Thing> all, String name) {
        XRayVision.Thing t = thing(all, name);
        return t == null ? "" : XRayVision.words(t, 2026);
    }

    static double hms(String s) {
        String[] p = s.split(":");
        return 15 * (Integer.parseInt(p[0]) + Integer.parseInt(p[1]) / 60.0 + Double.parseDouble(p[2]) / 3600);
    }

    static double dms(String s) {
        boolean neg = s.startsWith("-");
        String[] p = s.replace("+", "").replace("-", "").split(":");
        double v = Integer.parseInt(p[0]) + Integer.parseInt(p[1]) / 60.0 + Double.parseDouble(p[2]) / 3600;
        return neg ? -v : v;
    }

    static double jdUtc(int y, int mo, int d, int h, int mi, int s) {
        java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        return c.getTimeInMillis() / 86400000.0 + 2440587.5;
    }
}
