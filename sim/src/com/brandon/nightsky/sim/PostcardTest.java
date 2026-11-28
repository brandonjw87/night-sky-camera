package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.Postcard;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.SkySolver;
import com.brandon.nightsky.processing.XRayVision;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Postcard from Another World (2.6): the sky moved to another star against what's known of it (from Alpha Centauri's
 * neighbour Proxima the Sun is a magnitude 0.4 star in Cassiopeia and Alpha Centauri A outshines everything; from 51
 * Pegasi the Sun is a faint star and 51 Pegasi itself is left out; from next door nothing moves), where the postcard
 * comes from for a photo, its message, and the card (a PNG to look at when an output folder is given).
 * <p>
 * Usage: PostcardTest [outDir]
 */
public final class PostcardTest {
    static int failed;

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    public static void main(String[] args) throws Exception {
        File out = args.length > 0 ? new File(args[0]) : null;
        if (out != null) out.mkdirs();
        System.out.println("== Postcard from Another World");
        BrightStars cat = SimRunner.loadCatalog();
        List<Postcard.Dest> all = Postcard.all();
        check("17 places: X-Ray Vision's 16 stars with planets and Proxima b", all.size() == 17 && all.get(16) == Postcard.PROXIMA
                && Postcard.of("51 Pegasi").planet.equals("Dimidium"), all.size() + "");

        // ---- from Proxima b: the Sun in Cassiopeia at magnitude 0.4, Alpha Centauri A a dazzling neighbour
        Postcard.View px = Postcard.view(Postcard.PROXIMA, cat);
        check("from Proxima the Sun is magnitude 0.4", Math.abs(px.sunMag - 0.40) < 0.05, String.format(Locale.US, "%.2f", px.sunMag));
        check("in Cassiopeia", px.sunIn.equals("Cassiopeia"), px.sunIn);
        check("Alpha Centauri A is their brightest star, about magnitude -6.6", px.brightest >= 0
                && "Rigil Kentaurus".equals(cat.name[px.brightest]) && Math.abs(px.brightestMag + 6.6) < 0.2,
                px.brightest < 0 ? "" : cat.label(px.brightest) + String.format(Locale.US, " %.1f", px.brightestMag));
        System.out.println("    most changed " + px.changedMost + String.format(Locale.US, " %.3f", px.changeMost) + ", least " + px.changedLeast
                + String.format(Locale.US, " %.4f", px.changeLeast));
        // ---- from 51 Pegasi: the Sun faint, 51 Pegasi itself (the catalogue's Helvetios) their sun, left out
        Postcard.View peg = Postcard.view(Postcard.of("51 Pegasi"), cat);
        check("from 51 Pegasi the Sun is a faint star, magnitude 5.8", Math.abs(peg.sunMag - 5.8) < 0.1, String.format(Locale.US, "%.2f", peg.sunMag));
        check("51 Pegasi is their sun, left out of their night sky", peg.ownSun >= 0 && "Helvetios".equals(cat.name[peg.ownSun])
                && Float.isNaN(peg.mag[peg.ownSun]), peg.ownSun < 0 ? "not found" : cat.label(peg.ownSun));
        // the Sun is where 51 Pegasi isn't: straight opposite
        double opp = Astro.sep(peg.sun, Postcard.of("51 Pegasi").dir) / Astro.DEG;
        check("the Sun opposite the star in the sky", Math.abs(opp - 180) < 1e-6, String.format(Locale.US, "%.4f deg", opp));
        // ---- from next door (a millionth of a light year): nothing moves, nothing changes
        Postcard.View home = Postcard.view(new TestDest().d, cat);
        double moved = 0, dimmed = 0;
        for (int i = 0; i < cat.n; i++) {
            if (Float.isNaN(home.mag[i])) continue;
            moved = Math.max(moved, Astro.sep(cat.vec(i), new double[]{home.dir[3 * i], home.dir[3 * i + 1], home.dir[3 * i + 2]}));
            dimmed = Math.max(dimmed, Math.abs(home.mag[i] - cat.vmag[i]));
        }
        check("from next door nothing moves or dims", moved < 1e-6 && dimmed < 1e-4, String.format(Locale.US, "%.2g rad, %.2g mag", moved, dimmed));

        // ---- where the postcard comes from: a photo of Cygnus from Ohio (Kepler-16, Kepler-186 and Kepler-452 are in it)
        CameraModel lens = CameraModel.nominal(4080, 3072, 2875);
        SkySolver.Solution sol = FiguresTest.solution(lens, 300, 38, 15);
        SessionSky here = new SessionSky();
        here.latDeg = 39.9;
        here.lonDeg = -83.0;
        TimeZone ny = TimeZone.getTimeZone("America/New_York");
        double jd = XRayTest.jdUtc(2026, 11, 23, 23, 30, 0);
        List<XRayVision.Spot> spots = XRayVision.find(sol, jd, here, 0, 0, 4080, 3072, ny);
        Postcard.Why[] why = new Postcard.Why[1];
        Postcard.Dest d = Postcard.choose(spots, why);
        check("a photo of Cygnus: a planet whose star is in it", why[0] == Postcard.Why.IN_PHOTO && d.star.startsWith("Kepler") || d.star.equals("HD 189733"),
                d.planet + " (" + why[0] + ")");
        // the same night, the photo not placed on the sky: the highest one up
        List<XRayVision.Spot> upOnly = XRayVision.find(null, jd, here, 0, 0, 4080, 3072, ny);
        Postcard.Dest du = Postcard.choose(upOnly, why);
        double high = -90;
        String highest = "";
        for (XRayVision.Spot s : upOnly) {
            if (s.what.kind == XRayVision.Kind.PLANETS && s.altDeg > high) {
                high = s.altDeg;
                highest = s.what.name;
            }
        }
        check("not placed: the one highest in the sky", why[0] == Postcard.Why.UP && du.star.equals(highest), du.planet + ", " + highest);
        Postcard.Dest dn = Postcard.choose(null, why);
        check("nothing known: Proxima b", why[0] == Postcard.Why.NEAREST && dn == Postcard.PROXIMA, dn.planet);

        // ---- the message
        Postcard.View view = Postcard.view(d, cat);
        String msg = Postcard.message(view, cat, 2026);
        System.out.println("  " + d.planet + ": " + msg);
        check("the message says how the Sun looks and where", msg.startsWith("Wish you were here!") && msg.contains("magnitude")
                && msg.contains("Voyager 1 would take"), "");
        String pm = Postcard.message(px, cat, 2026);
        System.out.println("  Proxima b: " + pm);
        check("Proxima's: the Sun bright in Cassiopeia, light that left in 2022", pm.contains("a bright star (magnitude 0.4) in the part of the sky you call Cassiopeia")
                && pm.contains("left the Sun in 2022") && pm.contains("Rigil Kentaurus (magnitude \u22126.7)"), "");

        // ---- the line under the planet's name, as a sentence whatever X-Ray Vision's words for its star
        String ua = Postcard.aboutLine(Postcard.of("Upsilon Andromedae")), ee = Postcard.aboutLine(Postcard.of("Epsilon Eridani"));
        String pg = Postcard.aboutLine(Postcard.of("51 Pegasi")), pr = Postcard.aboutLine(Postcard.PROXIMA);
        System.out.println("  " + ua + " / " + ee + " / " + pg + " / " + pr);
        check("the line under the name reads as a sentence", ua.equals("A planet of Upsilon Andromedae, a star with 3 planets.")
                && ee.equals("A planet of Epsilon Eridani, 10.5 light years away.")
                && pg.equals("A planet of 51 Pegasi: the first planet around a star like the Sun.")
                && pr.equals("A planet of Proxima Centauri: the nearest planet to the Solar System."), "");
        boolean sentences = true;
        for (Postcard.Dest x : all) {
            String a = Postcard.aboutLine(x);
            if (!a.startsWith("A planet of " + x.star) || !a.endsWith(".") || a.contains("\u00b7") || a.contains(", star ")) sentences = false;
        }
        check("and so do all 17", sentences, "");

        // ---- the card
        int w = 1800, h = 1200;
        int[] photo = XRayTest.sky(cat, sol, 1200, 900);
        Postcard.Layout l = Postcard.layout(w, h);
        int[] bg = Postcard.background(l, photo, 1200, 900);
        AwtPainter p = new AwtPainter(bg, w, h);
        Postcard.Drawn dr = Postcard.paint(p, l, view, cat, jd, ny, "Its star is in your photo!");
        int[] pic = p.pixels();
        check("the Sun ringed on the sky's window", !Float.isNaN(dr.sunX) && dr.sunX > l.skyX0 && dr.sunX < l.skyX1 && dr.sunY > l.skyY0
                && dr.sunY < l.skyY1, String.format(Locale.US, "(%.0f, %.0f)", dr.sunX, dr.sunY));
        check("stars drawn; from 580 light years most constellations have fallen apart", dr.stars > 500 && dr.figures < 40,
                dr.stars + " stars, " + dr.figures + " constellations, " + dr.lines + " lines, " + dr.names + " names");
        if (out != null) {
            PhotoVideoTest.writePng(pic, w, h, new File(out, "postcard.png"));
            System.out.println("  card: " + new File(out, "postcard.png"));
            Postcard.Drawn d2;
            AwtPainter p2 = new AwtPainter(Postcard.background(l, photo, 1200, 900), w, h);
            d2 = Postcard.paint(p2, l, px, cat, jd, ny, "");
            check("from Proxima nearly every constellation keeps its shape", d2.figures > 75 && d2.lines > 100 && d2.names > 10,
                    d2.figures + " constellations, " + d2.lines + " lines, " + d2.names + " names");
            PhotoVideoTest.writePng(p2.pixels(), w, h, new File(out, "postcard-proxima.png"));
            System.out.println("  proxima: " + d2.stars + " stars");
        }
        System.out.println(failed == 0 ? "ALL PASS" : failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** A place a millionth of a light year away (nothing should change). */
    static final class TestDest {
        final Postcard.Dest d = new Postcard.Dest("Next door", "Next door", "", 279.23, 38.78, 1e-6);
    }
}
