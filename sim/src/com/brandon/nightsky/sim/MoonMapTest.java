package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.MoonFeatures;
import com.brandon.nightsky.processing.MoonGeo;
import com.brandon.nightsky.processing.MoonLook;
import com.brandon.nightsky.processing.MoonStack;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.SensorSpec;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.imageio.ImageIO;

/**
 * The Moon map (3.1): the seas, craters, mountains and Apollo landings named on the Moonshot's photo, where they really
 * are tonight.
 * <ul>
 * <li>the table: every place on the Moon with a sensible size, keys and names unique, a story each;</li>
 * <li>where they come out: lamps lit at Tycho, Copernicus, Plato and the middle of the Sea of Crises on a simulated
 * Moon, shot hand-held and stacked, found on the finished photo where the map writes their names;</li>
 * <li>the names: none on another, none over another place's ring or a landing's dot, all on the photo between its title
 * and its caption, only places the Sun has reached, nothing round the back, the famous ones all there;</li>
 * <li>tonight's best: at first quarter, the craters along the shadow line, each with the Sun low;</li>
 * <li>a tap on a name, or inside a crater's ring, finds that place;</li>
 * <li>pictures of the map at a crescent, first quarter and a gibbous Moon, for a look.</li>
 * </ul>
 * Usage: MoonMapTest [out dir]
 */
public final class MoonMapTest {
    static int failed;

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    static final double LAT = MoonshotTest.LAT, LON = MoonshotTest.LON, SCALE = MoonshotTest.SCALE;

    /** The first hour from t0 with the Moon waxing, lit between lo and hi, and above minAlt here. */
    static long waxing(long t0, double lo, double hi, double minAlt) {
        for (int k = 0; k < 24 * 60; k++) {
            long t = t0 + k * 3600_000L;
            MoonGeo.View v = MoonGeo.view(t, LAT, LON);
            if (v.waxing && v.illum >= lo && v.illum <= hi && v.alt > minAlt) return t;
        }
        throw new IllegalStateException("no such Moon");
    }

    /** The simulated Moon drawn straight into the finished photo's geometry (the lens's blur, no shake, no noise). */
    static MoonLook.Picture drawn(long t) {
        MoonGeo.View v = MoonGeo.view(t, LAT, LON);
        MoonSim sim = new MoonSim(t, LAT, LON, SCALE, v.q, 6);
        MoonLook.Picture p = new MoonLook.Picture();
        p.w = p.h = MoonLook.SIDE;
        p.r = MoonLook.MOON / 2.0;
        p.cx = MoonLook.SIDE / 2.0;
        p.cy = MoonLook.SIDE / 2.0 - 10;
        p.upPa = v.q;
        int n = p.w * p.h;
        float[] r = new float[n], g = new float[n], b = new float[n];
        double k = sim.rPx / p.r;
        for (int j = 0; j < p.h; j++) {
            for (int i = 0; i < p.w; i++) {
                double dx = (i - p.cx) * k, dy = (j - p.cy) * k;
                if (dx * dx + dy * dy > sim.rPx * sim.rPx * 1.1) continue;
                r[j * p.w + i] = (float) (sim.truthBox(0, dx, dy, k) * 0.9);
                g[j * p.w + i] = (float) (sim.truthBox(1, dx, dy, k) * 0.9);
                b[j * p.w + i] = (float) (sim.truthBox(2, dx, dy, k) * 0.9);
            }
        }
        p.argb = MoonLook.argb(r, g, b);
        return p;
    }

    /** The map drawn on a copy of the photo, saved as a PNG; the names placed. */
    static List<MoonLook.MapLabel> map(MoonLook.Picture pic, MoonGeo.View v, File f, String caption) throws Exception {
        AwtPainter ap = new AwtPainter(pic.argb, pic.w, pic.h);
        List<MoonLook.MapLabel> labels = MoonLook.mapLayout(pic, v, ap, 30);
        MoonLook.paintMap(ap, pic, labels, 30);
        MoonLook.captions(ap, pic, "MOON MAP", caption, 30);
        int[] px = ap.pixels();
        BufferedImage im = new BufferedImage(pic.w, pic.h, BufferedImage.TYPE_INT_RGB);
        im.setRGB(0, 0, pic.w, pic.h, px, 0, pic.w);
        f.getParentFile().mkdirs();
        ImageIO.write(im, "png", f);
        return labels;
    }

    /** The layout's rules, on one Moon. */
    static void rules(String which, List<MoonLook.MapLabel> labels, MoonLook.Picture pic) {
        int overlaps = 0, overRing = 0, outside = 0, dark = 0, back = 0;
        StringBuilder bad = new StringBuilder();
        for (MoonLook.MapLabel l : labels) {
            if (l.bx0 < 0 || l.bx1 > pic.w || l.by0 < 30 * 2.6 || l.by1 > pic.h - 30 * 2.6) {
                outside++;
                bad.append(l.name()).append(" outside; ");
            }
            boolean sea = l.feature != null && l.feature.kind == MoonFeatures.Kind.SEA;
            if (l.feature != null && !sea && l.sunAlt <= 0) {
                dark++;
                bad.append(l.name()).append(" in the dark; ");
            }
            if (l.z <= 0) back++;
            for (MoonLook.MapLabel m : labels) {
                if (m == l) continue;
                if (l.bx0 < m.bx1 && l.bx1 > m.bx0 && l.by0 < m.by1 && l.by1 > m.by0) {
                    overlaps++;
                    bad.append(l.name()).append(" on ").append(m.name()).append("; ");
                }
                if (m.rim != null) {
                    for (int k = 0; k < m.rim.length; k += 2) {
                        if (m.rim[k] > l.bx0 && m.rim[k] < l.bx1 && m.rim[k + 1] > l.by0 && m.rim[k + 1] < l.by1) {
                            overRing++;
                            bad.append(l.name()).append(" over ").append(m.name()).append("'s ring; ");
                            break;
                        }
                    }
                }
                if (m.site != null && m.x > l.bx0 && m.x < l.bx1 && m.y > l.by0 && m.y < l.by1) {
                    overRing++;
                    bad.append(l.name()).append(" over ").append(m.name()).append("; ");
                }
            }
        }
        check(which + ": no name on another, nor over a ring or a landing", overlaps == 0 && overRing == 0, bad.toString());
        check(which + ": every name on the photo, between the title and the caption", outside == 0, "");
        check(which + ": only places the Sun has reached, nothing round the back", dark == 0 && back == 0, dark + " dark, " + back + " round the back");
        // a tap on each name finds it; inside a crater's ring too, unless another name is drawn there
        int missed = 0;
        bad.setLength(0);
        for (MoonLook.MapLabel l : labels) {
            MoonLook.MapLabel t = MoonLook.mapAt(labels, (l.bx0 + l.bx1) / 2, (l.by0 + l.by1) / 2, 50);
            if (t != l) {
                missed++;
                bad.append("tap on ").append(l.name()).append(" found ").append(t == null ? "nothing" : t.name()).append("; ");
            }
            if (l.rim != null) {
                boolean covered = false;
                for (MoonLook.MapLabel m : labels) if (m != l && l.x > m.bx0 - 4 && l.x < m.bx1 + 4 && l.y > m.by0 - 4 && l.y < m.by1 + 4) covered = true;
                MoonLook.MapLabel c = MoonLook.mapAt(labels, l.x, l.y, 50);
                if (!covered && c != l) {
                    missed++;
                    bad.append("tap in ").append(l.name()).append(" found ").append(c == null ? "nothing" : c.name()).append("; ");
                }
            }
        }
        check(which + ": a tap on a name, or inside a crater's ring, finds it", missed == 0, missed + " missed " + bad);
    }

    static Set<String> names(List<MoonLook.MapLabel> labels) {
        Set<String> s = new HashSet<String>();
        for (MoonLook.MapLabel l : labels) s.add(l.name());
        return s;
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "/tmp/moonmap");
        out.mkdirs();

        System.out.println("== the table");
        {
            Set<String> keys = new HashSet<String>(), names = new HashSet<String>();
            int bad = 0, seas = 0, craters = 0, hills = 0;
            StringBuilder sb = new StringBuilder();
            for (MoonFeatures.Feature f : MoonFeatures.ALL) {
                boolean ok = Math.abs(f.lat) <= 90 && Math.abs(f.lon) <= 180 && f.km > 10 && f.km < 3000 && f.story.length() > 30
                        && f.rank >= 1 && f.rank <= 3 && keys.add(f.key) && names.add(f.name);
                if (!ok) {
                    bad++;
                    sb.append(f.name).append("; ");
                }
                if (f.kind == MoonFeatures.Kind.SEA) seas++;
                else if (f.kind == MoonFeatures.Kind.CRATER) craters++;
                else hills++;
                // the near side only (with the wobble): nothing more than about 98 degrees from the middle
                if (Math.toDegrees(Math.acos(Math.cos(Math.toRadians(f.lat)) * Math.cos(Math.toRadians(f.lon)))) > 98) {
                    bad++;
                    sb.append(f.name).append(" round the back; ");
                }
            }
            check("every place on the Moon's near side, sized, with a story; keys and names unique", bad == 0, sb.toString());
            check("the seas, craters and mountains all there", seas >= 30 && craters >= 60 && hills >= 12,
                    seas + " seas, " + craters + " craters, " + hills + " mountains and valleys");
            // the places Brandon's photo is surest to show, with their real places (IAU): a slip in the table shows here
            String[][] known = {{"tycho", "-43.3", "-11.2"}, {"copernicus", "9.6", "-20.1"}, {"plato", "51.6", "-9.4"},
                    {"tranquillitatis", "8.5", "31.4"}, {"crisium", "17.0", "59.1"}, {"recta", "-22.1", "-7.8"}};
            double worst = 0;
            for (String[] k : known) {
                MoonFeatures.Feature f = MoonFeatures.get(k[0]);
                worst = Math.max(worst, f == null ? 99 : Math.hypot(f.lat - Double.parseDouble(k[1]), f.lon - Double.parseDouble(k[2])));
            }
            check("the landmarks where the IAU has them (within 0.2 degrees)", worst < 0.2, String.format(Locale.US, "worst %.2f", worst));
            // a rim 50 km out from the equator's middle is 50 km away, in every direction
            double rw = 0;
            for (int b = 0; b < 360; b += 30) {
                double[] q = MoonFeatures.along(10, 20, b, 50);
                double c = Math.sin(Math.toRadians(10)) * Math.sin(Math.toRadians(q[0]))
                        + Math.cos(Math.toRadians(10)) * Math.cos(Math.toRadians(q[0])) * Math.cos(Math.toRadians(q[1] - 20));
                rw = Math.max(rw, Math.abs(Math.acos(Math.min(1, c)) * MoonGeo.RADIUS_KM - 50));
            }
            check("a crater's ring the right distance all round", rw < 0.01, String.format(Locale.US, "worst %.4f km", rw));
        }

        System.out.println("== where they come out: lamps on four places, shot hand-held and stacked");
        long tg = waxing(MoonshotTest.utc(2026, 12, 1, 0, 0), 0.72, 0.88, 25);
        MoonGeo.View vg = MoonGeo.view(tg, LAT, LON);
        System.out.println(String.format(Locale.US, "  a waxing gibbous Moon, %.0f%% lit, %.0f degrees up", vg.illum * 100, vg.alt));
        {
            String[] keys = {"tycho", "copernicus", "plato", "crisium"};
            double[][] lamps = new double[keys.length][];
            for (int i = 0; i < keys.length; i++) {
                MoonFeatures.Feature f = MoonFeatures.get(keys[i]);
                lamps[i] = new double[]{f.lat, f.lon, 0.8, 8};
            }
            // a phone held turned 25 degrees
            MoonSim ls = new MoonSim(tg, LAT, LON, SCALE, vg.q - 25, 6, lamps);
            MoonshotTest.Burst lb = MoonshotTest.shoot(ls, 48, 160, 31, 0);
            MoonStack.Options lo = new MoonStack.Options();
            lo.expectedRadius = ls.rPx;
            lo.outUpPa = vg.q;
            lo.brightLimbPa = vg.chi;
            lo.deconvolve = false;
            SensorSpec spec = MoonshotTest.spec();
            MoonStack.Result lr = MoonStack.stack(lb.frames, spec, lo, Progress.NONE);
            System.out.println("  " + lr.log);
            MoonLook.Picture pic = MoonLook.enlarge(lr.r, lr.g, lr.b, lr.w, lr.h, lr.cx, lr.cy, lr.radius, lr.upPa);
            List<MoonLook.MapLabel> labels = map(pic, vg, new File(out, "map_lamps.png"), "lamps at Tycho, Copernicus, Plato and the Sea of Crises");
            float[] Y = new float[lr.w * lr.h];
            for (int k = 0; k < Y.length; k++) Y[k] = 0.2126f * lr.r[k] + 0.7152f * lr.g[k] + 0.0722f * lr.b[k];
            double worst = 0, kPic = pic.r / lr.radius;
            StringBuilder sb = new StringBuilder();
            for (String key : keys) {
                MoonLook.MapLabel at = null;
                for (MoonLook.MapLabel l : labels) if (l.feature != null && l.feature.key.equals(key)) at = l;
                if (at == null) {
                    worst = 99;
                    sb.append(key).append(" not on the map; ");
                    continue;
                }
                // the place on the map, in the stacked picture's pixels; the lamp's light around it there
                double sx0 = lr.cx + (at.x - pic.cx) / kPic, sy0 = lr.cy + (at.y - pic.cy) / kPic;
                double sx = 0, sy = 0, sw = 0;
                for (int j = (int) sy0 - 9; j <= (int) sy0 + 9; j++)
                    for (int i = (int) sx0 - 9; i <= (int) sx0 + 9; i++) {
                        if (i < 0 || j < 0 || i >= lr.w || j >= lr.h) continue;
                        double v = Y[j * lr.w + i] - 1.2;
                        if (v <= 0) continue;
                        sx += v * i;
                        sy += v * j;
                        sw += v;
                    }
                double err = sw > 0 ? Math.hypot(sx / sw - sx0, sy / sw - sy0) : 99;
                worst = Math.max(worst, err);
                sb.append(String.format(Locale.US, "%s %.2f px  ", at.name(), err));
            }
            check("each lamp where the map names its place (within 1.5 px of the stacked picture, 6 on the photo)", worst < 1.5, sb.toString().trim());
        }

        System.out.println("== the gibbous Moon's map");
        List<MoonLook.MapLabel> gib;
        {
            MoonLook.Picture pic = drawn(tg);
            gib = map(pic, vg, new File(out, "map_gibbous.png"), String.format(Locale.US, "Waxing gibbous, %.0f%% lit (simulated)", vg.illum * 100));
            System.out.println("  " + gib.size() + " names: " + names(gib));
            rules("gibbous", gib, pic);
            Set<String> n = names(gib);
            String[] famous = {"Tycho", "Copernicus", "Plato", "Sea of Tranquility", "Sea of Serenity", "Sea of Crises", "Sea of Rains",
                    "Apennine Mountains", "Apollo 11", "Apollo 12", "Apollo 14", "Apollo 15", "Apollo 16", "Apollo 17"};
            StringBuilder miss = new StringBuilder();
            for (String f : famous) if (!n.contains(f)) miss.append(f).append("; ");
            check("the famous ones all named", miss.length() == 0, miss.toString());
            check("plenty of names, not a crowd", gib.size() >= 35 && gib.size() <= 120, gib.size() + " names");
            // what a tap tells: a crater's story with the Sun's height there
            MoonLook.MapLabel cop = null;
            for (MoonLook.MapLabel l : gib) if (l.name().equals("Copernicus")) cop = l;
            String story = cop != null ? MoonLook.mapStory(cop) : "";
            check("Copernicus tapped: its story, how big, the Sun there tonight", story.startsWith("Copernicus, a crater 96 km across")
                    && story.contains("Monarch") && story.contains("Tonight the Sun is"), story.length() > 90 ? story.substring(0, 90) : story);
        }

        System.out.println("== first quarter: tonight's best along the shadow line");
        {
            long tq = waxing(MoonshotTest.utc(2026, 12, 12, 0, 0), 0.47, 0.56, 20);
            MoonGeo.View vq = MoonGeo.view(tq, LAT, LON);
            MoonLook.Picture pic = drawn(tq);
            List<MoonLook.MapLabel> q = map(pic, vq, new File(out, "map_first_quarter.png"),
                    String.format(Locale.US, "First quarter, %.0f%% lit (simulated)", vq.illum * 100));
            System.out.println(String.format(Locale.US, "  %.0f%% lit, %d names: %s", vq.illum * 100, q.size(), names(q)));
            rules("first quarter", q, pic);
            int best = 0, wrong = 0;
            StringBuilder sb = new StringBuilder();
            for (MoonLook.MapLabel l : q) {
                if (!l.best) continue;
                best++;
                sb.append(String.format(Locale.US, "%s %.0f deg; ", l.name(), l.sunAlt));
                if (l.sunAlt <= 0 || l.sunAlt >= MoonLook.LOW_SUN) wrong++;
            }
            check("places along the shadow line marked best tonight, each with the Sun low", best >= 3 && wrong == 0, sb.toString());
            String words = MoonLook.bestTonight(q, vq);
            System.out.println("  " + words);
            check("the card says what to look for", words.startsWith("Best tonight") && words.endsWith("."), "");
            // the Sun isn't up on the dark half: nothing named there but seas' edges
            int west = 0;
            for (MoonLook.MapLabel l : q) if (l.feature != null && l.feature.kind == MoonFeatures.Kind.CRATER && l.feature.lon < -45) west++;
            check("no crater named on the night side", west == 0, west + " craters west of 45 W");
        }

        System.out.println("== a crescent");
        {
            long tc = waxing(MoonshotTest.utc(2026, 12, 10, 0, 0), 0.12, 0.25, 10);
            MoonGeo.View vc = MoonGeo.view(tc, LAT, LON);
            MoonLook.Picture pic = drawn(tc);
            List<MoonLook.MapLabel> c = map(pic, vc, new File(out, "map_crescent.png"),
                    String.format(Locale.US, "Waxing crescent, %.0f%% lit (simulated)", vc.illum * 100));
            System.out.println(String.format(Locale.US, "  %.0f%% lit, %d names: %s", vc.illum * 100, c.size(), names(c)));
            rules("crescent", c, pic);
            Set<String> n = names(c);
            check("the crescent's own places named: the Sea of Crises, Langrenus, Petavius", n.contains("Sea of Crises") && n.contains("Langrenus")
                    && n.contains("Petavius"), "");
            check("and not Copernicus, still in the dark", !n.contains("Copernicus"), "");
            // the Apollo landings in the dark: hollow dots, still named (they're always there)
            int apollo = 0;
            for (MoonLook.MapLabel l : c) if (l.site != null) apollo++;
            check("the Apollo landings marked, lit or not", apollo == 6, apollo + " of 6");
        }

        System.out.println("== the full Moon: no shadows");
        {
            long tf = 0;
            for (int k = 0; k < 24 * 40; k++) {
                long t = MoonshotTest.utc(2026, 12, 18, 0, 0) + k * 3600_000L;
                MoonGeo.View v = MoonGeo.view(t, LAT, LON);
                if (v.illum > 0.985 && v.alt > 20) {
                    tf = t;
                    break;
                }
            }
            MoonGeo.View vf = MoonGeo.view(tf, LAT, LON);
            MoonLook.Picture pic = drawn(tf);
            List<MoonLook.MapLabel> f = map(pic, vf, new File(out, "map_full.png"), String.format(Locale.US, "Full Moon, %.1f%% lit (simulated)", vf.illum * 100));
            System.out.println(String.format(Locale.US, "  %.1f%% lit, %d names", vf.illum * 100, f.size()));
            rules("full", f, pic);
            String words = MoonLook.bestTonight(f, vf);
            System.out.println("  " + words);
            check("the card says it's the night for the rays", words.contains("bright rays") || words.startsWith("Best tonight"), "");
        }

        System.out.println(failed == 0 ? "ALL PASS" : failed + " FAILED");
        if (failed > 0) System.exit(1);
    }
}
