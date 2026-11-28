package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.JupiterGeo;
import com.brandon.nightsky.processing.JupiterLook;
import com.brandon.nightsky.processing.JupiterStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Galileo's notebook's pictures (3.2) from simulated nights: the photo (every moon found named by its light, the
 * others where the sums put them with why, two moons in one dot named together; the moons clear on a black sky,
 * Jupiter's glare dimmed), tonight's
 * line-up, and the notebook page (Galileo's nights, yours, the nights to come). Writes them as PNG to the folder
 * given.
 * <p>
 * Usage: java ... com.brandon.nightsky.sim.JupiterLookTest [outDir]
 */
public final class JupiterLookTest {
    static int failed;

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : ": " + detail));
        if (!ok) failed++;
    }

    public static void main(String[] args) throws Exception {
        File out = args.length > 0 ? new File(args[0]) : null;
        if (out != null) out.mkdirs();
        TimeZone z = TimeZone.getTimeZone("America/New_York");

        long[] nights = {JupiterShotTest.ALL4, JupiterShotTest.DARK, JupiterShotTest.IONEAR, JupiterShotTest.BLEND};
        double[] turns = {25, -40, 70, -15};
        String[] names = {"all4", "dark", "ionear", "blend"};
        JupiterStack.Result first = null;
        JupiterSim firstSim = null;
        for (int n = 0; n < nights.length; n++) {
            System.out.println("== the photo: " + names[n]);
            JupiterSim sim = JupiterShotTest.night(nights[n], turns[n], n == 0 ? new double[][]{{-150, 240, 7.0}, {330, -40, 7.5}} : null);
            JupiterShotTest.Shot s = JupiterShotTest.shoot(sim, 3 + n, true, 1);
            JupiterStack.Result r = JupiterShotTest.stackOf(sim, s, true);
            if (n == 0) {
                first = r;
                firstSim = sim;
            }
            long t0 = System.nanoTime();
            JupiterLook.Picture pic = JupiterLook.photo(r);
            AwtPainter p = new AwtPainter(pic.argb, pic.w, pic.h);
            JupiterLook.label(p, pic, r, 34);
            JupiterLook.captions(p, pic.w, pic.h, "JUPITER'S MOONS", JupiterLook.caption(r, sim.view, "Dec 30, 2026, 9:00 PM", "39.0°N, 77.0°W"), 34);
            int[] px = p.pixels();
            System.out.println(String.format(Locale.US, "  drawn in %.0f ms, %.1f px per stack px", (System.nanoTime() - t0) / 1e6, pic.k));
            // the sky dark, the moons found bright
            double[] sky = new double[pic.w * pic.h / 64];
            int ns = 0;
            for (int j = 120; j < pic.h - 120; j += 8) {
                for (int i = 0; i < pic.w; i += 8) {
                    if (Math.hypot(i - pic.cx, j - pic.cy) < 6 * pic.rJ) continue;
                    boolean near = false;
                    for (JupiterStack.Light l : r.lights) {
                        double[] q = JupiterLook.onPicture(pic, r, l.x, l.y);
                        if (Math.hypot(i - q[0], j - q[1]) < 90) near = true;
                    }
                    if (!near) sky[ns++] = luma(pic.argb[j * pic.w + i]);
                }
            }
            java.util.Arrays.sort(sky, 0, ns);
            double skyMed = sky[ns / 2], sky99 = sky[(int) (ns * 0.99)];
            check("the sky black, its noise faint", skyMed < 12 && sky99 < 60, String.format(Locale.US, "median %.1f, 99th %.1f of 255", skyMed, sky99));
            int labelled = 0;
            for (int k = 0; k < 4; k++) {
                if (r.status[k] != JupiterStack.FOUND) continue;
                double[] q = JupiterLook.onPicture(pic, r, r.moon[k].x, r.moon[k].y);
                double best = 0;
                for (int dj = -6; dj <= 6; dj++) {
                    for (int di = -6; di <= 6; di++) {
                        int i = (int) Math.round(q[0]) + di, j = (int) Math.round(q[1]) + dj;
                        if (i >= 0 && j >= 0 && i < pic.w && j < pic.h) best = Math.max(best, luma(pic.argb[j * pic.w + i]));
                    }
                }
                // (the faintest, Callisto at sixth magnitude, a soft grey: clear on the black)
                check("  " + JupiterGeo.NAMES[k] + " shows clearly on the photo", best > 90, String.format(Locale.US, "%.0f of 255 (snr %.0f)", best, r.moon[k].snr));
                for (JupiterLook.Label l : pic.labels) if (l.moon == k) labelled++;
            }
            check("every moon found is named", labelled == r.found(), labelled + " of " + r.found());
            int notes = 0;
            for (JupiterLook.Label l : pic.labels) {
                if (l.moon >= 0 && !l.seen) notes++;
                boolean inside = l.tx >= 0 && l.ty >= 0 && l.tx < pic.w && l.ty <= pic.h;
                if (!inside) check("  a label off the photo", false, l.text + " at " + Math.round(l.tx) + "," + Math.round(l.ty));
            }
            int notFound = 0;
            for (int k = 0; k < 4; k++) if (r.status[k] != JupiterStack.FOUND && r.status[k] != JupiterStack.OUTSIDE) notFound++;
            check("every moon not found says why", notes == notFound, notes + " of " + notFound);
            if (out != null) JupiterShotTest.write(new File(out, "look_" + names[n] + ".png"), px, pic.w, pic.h);
        }

        System.out.println("== tonight's line-up");
        int lw = 1080, lh = JupiterLook.lineUpHeight(lw);
        int[] blackPx = new int[lw * lh];
        java.util.Arrays.fill(blackPx, 0xFF000000);
        for (int n = 0; n < nights.length; n++) {
            AwtPainter lp = new AwtPainter(blackPx, lw, lh);
            JupiterLook.lineUp(lp, lw, lh, JupiterGeo.view(nights[n], 39, -77), 40);
            if (out != null) JupiterShotTest.write(new File(out, "lineup_" + names[n] + ".png"), lp.pixels(), lw, lh);
        }

        System.out.println("== the notebook page");
        List<JupiterLook.Section> secs = new ArrayList<JupiterLook.Section>();
        JupiterLook.Section g = new JupiterLook.Section("Galileo Galilei, Padua, January 1610", false);
        for (int i = 0; i < JupiterGeo.GALILEO_DAY.length; i++) g.nights.add(JupiterLook.galileo(i));
        secs.add(g);
        JupiterLook.Section mine = new JupiterLook.Section("Your nights", false);
        mine.nights.add(JupiterLook.fromPhoto(first, firstSim.view, JupiterShotTest.ALL4));
        secs.add(mine);
        JupiterLook.Section next = new JupiterLook.Section("The nights to come (from the sums)", true);
        long t0 = System.nanoTime();
        next.nights.addAll(JupiterLook.coming(JupiterShotTest.ALL4 + 3_600_000L, 39, -77, 5));
        System.out.println(String.format(Locale.US, "  the nights to come worked out in %.0f ms", (System.nanoTime() - t0) / 1e6));
        check("five nights to come", next.nights.size() == 5, next.nights.size() + "");
        long prev = 0;
        boolean spaced = true;
        for (JupiterLook.Entry e : next.nights) {
            if (prev != 0 && (e.t - prev < 18 * 3_600_000L || e.t - prev > 30 * 3_600_000L)) spaced = false;
            prev = e.t;
            double alt = JupiterGeo.view(e.t, 39, -77).alt, sun = JupiterGeo.sunAlt(e.t, 39, -77);
            if (alt < 12 || sun > -8) spaced = false;
        }
        check("a night apart, each with Jupiter up in a dark sky", spaced, "");
        secs.add(next);
        int pw = 1440, ph = JupiterLook.pageHeight(pw, secs);
        AwtPainter pp = new AwtPainter(JupiterLook.paper(pw, ph), pw, ph);
        JupiterLook.page(pp, pw, secs, z);
        // Galileo's seven nights as he drew them: the stars he saw (east to west) where the page puts them
        int agree = 0;
        for (int i = 0; i < JupiterGeo.GALILEO_DAY.length; i++) {
            JupiterLook.Entry e = g.nights.get(i);
            int east = 0, west = 0;
            for (int k = 0; k < 4; k++) if (e.seen[k]) {
                if (e.x[k] < 0) east++;
                else west++;
            }
            String d = JupiterGeo.GALILEO_DREW[i];
            int o = d.indexOf('O');
            int dEast = 0, dWest = 0;
            for (int c = 0; c < d.length(); c++) if (d.charAt(c) == '*') {
                if (c < o) dEast++;
                else dWest++;
            }
            if (east == dEast && west == dWest) agree++;
            else System.out.println(String.format(Locale.US, "  Jan %d: page %d east %d west, Galileo drew %s", JupiterGeo.GALILEO_DAY[i], east, west, d));
        }
        check("the page's Galileo nights as he drew them (east and west)", agree >= 6, agree + " of 7");
        if (out != null) JupiterShotTest.write(new File(out, "notebook.png"), pp.pixels(), pw, ph);
        System.out.println(failed == 0 ? "ALL PASS" : failed + " FAILED");
        if (failed > 0) System.exit(1);
    }

    static double luma(int c) {
        return 0.2126 * ((c >> 16) & 255) + 0.7152 * ((c >> 8) & 255) + 0.0722 * (c & 255);
    }
}
