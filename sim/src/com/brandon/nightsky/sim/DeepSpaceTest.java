package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.Constellations;
import com.brandon.nightsky.processing.DeepSpace;
import com.brandon.nightsky.processing.Probes;
import com.brandon.nightsky.processing.Planets;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.SkyGuide;
import com.brandon.nightsky.processing.SkySolver;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Deep Space Network (2.4, {@link DeepSpace}) against what's published:
 * <ul>
 * <li>the five probes where Heavens-Above's table puts them on 2026-11-22, in its constellations, with its light times;
 * <li>Voyager 1 a light-day from the Earth at 10:16 UTC on 18 November 2026 (EarthSky, from NASA);
 * <li>Pluto where in-the-sky.org put it on 23 December 2025;
 * <li>Jupiter's moons as Meeus' worked example has them (1992 December 16), west to the west and along Jupiter's equator;
 * <li>the sky guide naming a probe, L2 and Pluto in a picture of them, Jupiter's moons in order across the picture, and
 * the card saying which are in the photo, up, or down.
 * </ul>
 */
public final class DeepSpaceTest {
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        double jd = jdUtc(2026, 11, 22, 0, 0, 0);
        double tt = Astro.jdTt(jd);
        // name, RA (h, m), Dec (deg, min), light time (h), constellation: Heavens-Above, 2026-11-22
        Object[][] table = {
                {"Voyager 1", 17, 16, 12, 4, 24.01, "Oph"},
                {"Voyager 2", 20, 6, -59, -41, 20.10, "Pav"},
                {"New Horizons", 19, 16, -20, -17, 9.25, "Sgr"},
                {"Pioneer 10", 5, 14, 26, 1, 19.60, "Tau"},
                {"Pioneer 11", 18, 54, -9, -2, 16.69, "Sct"},
        };
        for (int k = 0; k < table.length; k++) {
            Object[] t = table[k];
            Probes.Seen s = Probes.seen(Probes.PROBES[k], tt);
            double dec = Math.signum((Integer) t[3] == 0 ? (Integer) t[4] : (Integer) t[3]) * (Math.abs((Integer) t[3]) + Math.abs((Integer) t[4]) / 60.0);
            double[] want = Astro.vec(((Integer) t[1] + ((Integer) t[2] + 0.5) / 60.0) * 15 * Astro.DEG, dec * Astro.DEG);
            double off = Astro.sep(s.dir, want) / Astro.DEG;
            String con = Constellations.at(s.dir);
            check(t[0] + " where the table has it, in " + t[6], s.name.equals(t[0]) && off < 0.2 && con.equals(t[6]),
                    String.format("%.3f deg off, in %s", off, con));
            check(t[0] + ": its light time", Math.abs(s.lightHours() - (Double) t[5]) < 0.03, String.format("%.3f h", s.lightHours()));
        }
        // a year on, each has moved out by its speed and swung with the Earth, but no more than a degree or so
        for (Probes.Probe p : Probes.PROBES) {
            Probes.Seen a = Probes.seen(p, tt), b = Probes.seen(p, tt + 182.6);
            double swing = Astro.sep(a.dir, b.dir) / Astro.DEG;
            check(p.name + " half a year on: farther out, and the Earth's swing only", b.sunAu > a.sunAu && swing < 2 * Math.asin(1.0 / a.distAu) / Astro.DEG + 0.05,
                    String.format("%.3f deg, %.2f -> %.2f AU", swing, a.sunAu, b.sunAu));
        }
        // EarthSky: Voyager 1 a light-day from the Earth at 10:16:07 UTC on 18 November 2026
        Probes.Seen v1 = Probes.seen(Probes.PROBES[0], Astro.jdTt(jdUtc(2026, 11, 18, 10, 16, 7)));
        check("Voyager 1 a light-day away on 18 November 2026", Math.abs(v1.lightHours() - 24) < 0.02,
                String.format("%.4f h, %.2f AU", v1.lightHours(), v1.distAu));
        check("light-time words", DeepSpace.lightWords(24.004).equals("24 hours") && DeepSpace.lightWords(9.25).equals("9 hours 15 minutes")
                && DeepSpace.lightWords(0.5).equals("30 minutes") && DeepSpace.lightWords(1.0 / 60).equals("1 minute"),
                DeepSpace.lightWords(24.004) + ", " + DeepSpace.lightWords(9.25) + ", " + DeepSpace.lightWords(0.5));

        // L2: straight away from the Sun
        Probes.Seen l2 = Probes.l2(tt);
        double[] sun = Astro.sunOfDate(tt);
        double anti = Astro.sep(l2.dir, new double[]{-sun[0], -sun[1], -sun[2]}) / Astro.DEG;
        check("L2 is opposite the Sun", anti < 0.6, String.format("%.2f deg (the Sun's place is of date)", anti));

        // Pluto: in-the-sky.org, 23 December 2025: RA 20h21m, Dec -23 21', 36.26 AU from the Earth
        Planets.Seen pl = Planets.pluto(Astro.jdTt(jdUtc(2025, 12, 23, 0, 0, 0)));
        double plOff = Astro.sep(pl.dir, Astro.vec((20 + 21.5 / 60) * 15 * Astro.DEG, -(23 + 21 / 60.0) * Astro.DEG)) / Astro.DEG;
        check("Pluto where in-the-sky.org had it", plOff < 0.5 && Math.abs(pl.distAu - 36.26) < 0.05,
                String.format("%.3f deg off, %.3f AU, magnitude %.1f", plOff, pl.distAu, pl.mag));
        check("Pluto too faint for a phone", pl.mag > 13.8 && pl.mag < 15, String.format("%.2f", pl.mag));

        // Jupiter's moons: Meeus, example 44.a, 1992 December 16 at 0h UT (JDE 2448972.50068)
        DeepSpace.Moon[] m = DeepSpace.jupiterMoons(2448972.50068);
        double[][] meeus = {{-3.44, 0.21}, {7.44, 0.25}, {1.24, 0.65}, {7.08, 1.10}};
        double worst = 0;
        StringBuilder got = new StringBuilder();
        for (int k = 0; k < 4; k++) {
            worst = Math.max(worst, Math.max(Math.abs(m[k].x - meeus[k][0]), Math.abs(m[k].y - meeus[k][1])));
            got.append(String.format(" %s %+.2f %+.2f", m[k].name, m[k].x, m[k].y));
        }
        check("Jupiter's moons as in Meeus' example", worst < 0.015, got.toString().trim());
        // on the sky: west of Jupiter has the smaller right ascension, and they line up along its equator (near the ecliptic)
        double jt = Astro.jdTt(jd);
        DeepSpace.Moon[] mm = DeepSpace.jupiterMoons(jt);
        Planets.Seen jup = Planets.seen(3, jt);
        boolean westOk = true;
        double worstLine = 0;
        double[] eclN = {0, -Math.sin(84381.448 * Astro.ARCSEC), Math.cos(84381.448 * Astro.ARCSEC)};
        for (DeepSpace.Moon q : mm) {
            double[] rdJ = Astro.raDec(jup.dir), rdM = Astro.raDec(q.dir);
            double dra = Math.IEEEremainder(rdM[0] - rdJ[0], 2 * Math.PI);
            if (Math.abs(q.x) > 2) westOk &= (q.x > 0) == (dra < 0);
            // how far off the ecliptic's line through Jupiter the moon is, in Jupiter widths, against how far out it is
            double[] off = {q.dir[0] - jup.dir[0], q.dir[1] - jup.dir[1], q.dir[2] - jup.dir[2]};
            double along = Math.abs(off[0] * eclN[0] + off[1] * eclN[1] + off[2] * eclN[2]);
            double ang = Math.sqrt(off[0] * off[0] + off[1] * off[1] + off[2] * off[2]);
            if (Math.abs(q.x) > 3) worstLine = Math.max(worstLine, along / ang);
        }
        check("moons to the west are west of Jupiter", westOk, "");
        check("the moons line up along Jupiter's equator, near the ecliptic", worstLine < Math.sin(6 * Astro.DEG),
                String.format("%.1f deg at most", Math.asin(worstLine) / Astro.DEG));
        double cal = Astro.sep(mm[3].dir, jup.dir) / Astro.ARCSEC;
        double calWant = Math.hypot(mm[3].x, mm[3].y) * 71492 / (jup.distAu * 149597870.7) / Astro.ARCSEC;
        check("Callisto's distance from Jupiter on the sky", Math.abs(cal - calWant) < 0.5 && cal < 26.6 * 71492 / (jup.distAu * 149597870.7) / Astro.ARCSEC,
                String.format("%.1f arcsec", cal));

        // the sky guide: a picture centred on Voyager 1 names it; one on L2 names it; Pluto too
        BrightStars cat = SimRunner.loadCatalog();
        CameraModel lens = CameraModel.nominal(4080, 3072, 2875);
        Probes.Seen at = Probes.seen(Probes.PROBES[0], tt);
        double[] rd = Astro.raDec(at.dir);
        SkySolver.Solution sol = FiguresTest.solution(lens, rd[0] / Astro.DEG + 3, rd[1] / Astro.DEG - 2, 20);
        SkyGuide g = SkyGuide.describe(sol, jd, null, cat, 0, 0, 4080, 3072);
        SkyGuide.Item it = find(g, "spacecraft", "Voyager 1");
        check("the guide names Voyager 1 in a picture of it", it != null && !Double.isNaN(it.x) && line(g, "Voyager 1"), g.lines.toString());
        double[] l2rd = Astro.raDec(l2.dir);
        SkyGuide g2 = SkyGuide.describe(FiguresTest.solution(lens, l2rd[0] / Astro.DEG, l2rd[1] / Astro.DEG, 0), jd, null, cat, 0, 0, 4080, 3072);
        check("the guide names L2 in a picture of it", find(g2, "spacecraft", "L2, Webb's home") != null, g2.lines.toString());
        Planets.Seen plNow = Planets.pluto(tt);
        double[] prd = Astro.raDec(plNow.dir);
        SkyGuide g3 = SkyGuide.describe(FiguresTest.solution(lens, prd[0] / Astro.DEG - 4, prd[1] / Astro.DEG, -30), jd, null, cat, 0, 0, 4080, 3072);
        check("the guide names Pluto in a picture of it, and New Horizons beside it", find(g3, "dwarf planet", "Pluto") != null
                && find(g3, "spacecraft", "New Horizons") != null, g3.lines.toString());
        // Jupiter in the picture: its moons named in order across it
        double[] jrd = Astro.raDec(jup.dir);
        SkyGuide g4 = SkyGuide.describe(FiguresTest.solution(lens, jrd[0] / Astro.DEG, jrd[1] / Astro.DEG, 0), jd, null, cat, 0, 0, 4080, 3072);
        String moons = "";
        for (String l : g4.lines) if (l.contains("Ganymede")) moons = l;
        check("Jupiter's moons named across the picture", !moons.isEmpty(), moons);
        System.out.println("  " + moons);

        // the card, from a place: which are in the photo, up, or below the horizon
        SessionSky here = new SessionSky();
        here.latDeg = 39.9;
        here.lonDeg = -83.0;
        List<DeepSpace.Contact> card = DeepSpace.network(sol, jdUtc(2026, 11, 22, 23, 30, 0), here, 0, 0, 4080, 3072, TimeZone.getTimeZone("America/New_York"));
        boolean first = !card.isEmpty() && card.get(0).inPhoto && card.get(0).what.name.equals("Voyager 1");
        int ups = 0, downs = 0;
        for (DeepSpace.Contact c : card) {
            System.out.println("    " + c.head);
            System.out.println("      " + c.words);
            if (c.up) ups++;
            else downs++;
        }
        check("the card: Voyager 1 in the photo, first", first, "");
        check("the card: all seven, some up and some down", card.size() == 7 && ups > 0 && downs > 0, ups + " up, " + downs + " down");
        check("the card: a message's arrival time", card.get(0).words.contains("arrives at") && card.get(0).words.contains("the next day"),
                card.get(0).words);

        // Mission Replay's Deep Space Network frame: the photo, the spacecraft in it marked, the end card
        int pw = 960, ph = 720;
        int[] photo = new int[pw * ph];
        java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < photo.length; i++) {
            int b = 10 + rnd.nextInt(8);
            photo[i] = 0xFF000000 | (b << 16) | (b << 8) | (b + 10);
        }
        for (int i = 0; i < 400; i++) {
            int x = rnd.nextInt(pw), y = rnd.nextInt(ph), v = 120 + rnd.nextInt(135);
            photo[y * pw + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        List<com.brandon.nightsky.processing.MissionReplay.Tag> tags = new java.util.ArrayList<com.brandon.nightsky.processing.MissionReplay.Tag>();
        for (SkyGuide.Item s : g.items) {
            if (!"spacecraft".equals(s.kind)) continue;
            com.brandon.nightsky.processing.MissionReplay.Tag t = new com.brandon.nightsky.processing.MissionReplay.Tag();
            t.x = s.x * pw;
            t.y = s.y * ph;
            t.name = s.name;
            t.line = s.note + " away";
            tags.add(t);
        }
        java.io.ByteArrayOutputStream gif = new java.io.ByteArrayOutputStream();
        com.brandon.nightsky.processing.MissionReplay.make(null, photo, pw, ph, new java.util.ArrayList<com.brandon.nightsky.processing.MissionReplay.Shot>(),
                tags, new String[]{"MISSION COMPLETE", "5 minutes of light", "Also in it: Voyager 1 and Pioneer 11", "Night Sky"},
                new DeepZoomTest.AwtDraw(), gif, null);
        javax.imageio.ImageReader gr = javax.imageio.ImageIO.getImageReadersByFormatName("gif").next();
        gr.setInput(javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(gif.toByteArray())), false);
        int frames = gr.getNumImages(true);
        check("the replay: the photo, its spacecraft marked, the end", tags.size() == 2 && frames == 3, tags.size() + " tags, " + frames + " frames");
        if (args.length > 0) {
            new java.io.File(args[0]).mkdirs();
            java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(480, 360, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int f = 0; f < frames; f++) {
                java.awt.image.BufferedImage im = gr.read(f);
                javax.imageio.metadata.IIOMetadata md = gr.getImageMetadata(f);
                org.w3c.dom.Node root = md.getAsTree("javax_imageio_gif_image_1.0");
                int left = 0, top = 0;
                for (org.w3c.dom.Node c = root.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if ("ImageDescriptor".equals(c.getNodeName())) {
                        left = Integer.parseInt(c.getAttributes().getNamedItem("imageLeftPosition").getNodeValue());
                        top = Integer.parseInt(c.getAttributes().getNamedItem("imageTopPosition").getNodeValue());
                    }
                }
                canvas.getGraphics().drawImage(im, left, top, null);
                javax.imageio.ImageIO.write(canvas, "png", new java.io.File(args[0], "network" + f + ".png"));
            }
        }

        System.out.println(failed == 0 ? "ALL PASS" : failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static SkyGuide.Item find(SkyGuide g, String kind, String name) {
        for (SkyGuide.Item it : g.items) if (kind.equals(it.kind) && name.equals(it.name)) return it;
        return null;
    }

    static boolean line(SkyGuide g, String s) {
        for (String l : g.lines) if (l.contains(s)) return true;
        return false;
    }

    static double jdUtc(int y, int mo, int d, int h, int mi, int s) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        return Astro.jdUtc(c.getTimeInMillis());
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }
}
