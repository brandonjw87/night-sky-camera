package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Aligner;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.StarBrackets;
import com.brandon.nightsky.processing.UnitProcessor;
import com.brandon.nightsky.processing.UnitRecord;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bright-star brackets (2.2) on a simulated night: the long frames clip every star brighter than about magnitude 4; a
 * short frame (1/16 of the exposure) after every fourth long one gives the stack's clipped cores their light. Checked
 * against the simulator's own stars: the brightest stars' light (an aperture's, against the unclipped stars' zero point)
 * and colour (red and blue over green), with and without the short frames; that the rest of the picture is untouched;
 * that the photo shows the bright stars' colours; and that a master keeps the mark.
 * <p>
 * Usage: BracketTest outDir [scale=2] [frames=24].
 */
public final class BracketTest {
    static int failed;
    static double BRIGHT = Double.parseDouble(System.getProperty("bright", "0.75"));

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "bracket-out");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 24;
        out.mkdirs();
        run(out, scale, frames);
        System.out.println(failed == 0 ? "ALL PASS" : failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    static void run(File dir, int scale, int frames) throws Exception {
        System.out.println("== bright-star brackets: " + frames + " frames at 1/" + scale + " size, a short one after every "
                + StarBrackets.EVERY);
        PhotoVideoTest.Night n = new PhotoVideoTest.Night();
        n.W = 4080 / scale;
        n.H = 3072 / scale;
        n.truth = com.brandon.nightsky.processing.CameraModel.nominal(n.W, n.H, 2875.0 / scale);
        n.truth.k1 = -0.045;
        n.truth.k2 = 0.018;
        n.truth.source = "truth";
        n.sim = new SkySim(n.W, n.H, n.truth, 7, null, SimRunner.DEFAULT_TIME_MS, SimRunner.DEFAULT_LON);
        n.sim.psfSigma = 0.85 / Math.sqrt(scale);
        n.spec = n.sim.spec(true);
        ColorMath.compute(n.spec, ColorMath.DEFAULT_CCT);
        File root = new File(dir, "sessions");
        root.mkdirs();
        n.store = SessionStore.create(root);
        UnitProcessor up = new UnitProcessor(n.store, n.spec, 1, 3, new UnitProcessor.Listener() {
            @Override
            public void onUnit(UnitProcessor src, UnitRecord rec, Aligner.Result live, int unitsDone) {
            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
            }
        });
        double shortS = n.sim.frameExp / StarBrackets.RATIO;
        int brackets = 0;
        for (int i = 0; i < frames; i++) {
            short[] buf = up.obtainBuffer(60000);
            UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
            n.sim.render(i, buf, info, 11);
            up.submit(buf, info);
            if ((i + 1) % StarBrackets.EVERY == 0) {
                short[] b = up.obtainBuffer(60000);
                UnitProcessor.FrameInfo bi = new UnitProcessor.FrameInfo();
                n.sim.renderBracket(i, shortS, b, bi, 11 + i);
                up.submitBracket(b, bi);
                brackets++;
            }
        }
        up.finish();
        List<StarBrackets.Frame> listed = StarBrackets.list(n.store, n.W, n.H);
        check("the short frames are stored with their times", listed.size() == brackets && listed.get(0).exposure > shortS * 0.99
                        && listed.get(0).exposure < shortS * 1.01, listed.size() + " of " + brackets + ", " + (listed.isEmpty() ? "-"
                        : String.format(Locale.US, "%.3f s at t=%.2f s", listed.get(0).exposure, listed.get(0).t)));

        PostProcessor plain = new PostProcessor(n.store, n.spec);
        plain.brackets = false;
        PostProcessor.Output a = plain.run(false, PhotoVideoTest.QUIET);
        PostProcessor withB = new PostProcessor(n.store, n.spec);
        PostProcessor.Output b = withB.run(false, PhotoVideoTest.QUIET);
        System.out.println("  " + b.summary);
        System.out.println("  " + (b.brackets != null ? b.brackets.describe() : "no brackets result"));
        check("the stack's clipped cores given their light", b.brackets != null && b.brackets.repaired >= 20 && b.image.bracketed
                        && !a.image.bracketed, b.brackets == null ? "none" : b.brackets.repaired + " stars, " + b.brackets.pixels + " pixels");
        check("matched on unclipped stars, close to the exposures' own ratio", b.brackets != null && b.brackets.calStars >= 10
                        && b.brackets.spread < 0.3 && Math.abs(b.brackets.scaleVsExposure - 1) < 0.1,
                b.brackets == null ? "" : String.format(Locale.US, "%d stars, %.1f%% spread, %.3f of the exposures' ratio", b.brackets.calStars,
                        100 * b.brackets.spread, b.brackets.scaleVsExposure));

        // --- against the simulator's stars ------------------------------------------------------------------------
        int ref = b.referenceUnit;
        double[][] st = n.sim.starTable(ref);
        RgbImage ia = a.image, ib = b.image;
        // the zero point from stars far from clipping: stack light per expected electron
        List<Double> zp = new ArrayList<Double>();
        float clip = n.spec.range();
        for (double[] s : st) {
            int x = (int) s[0], y = (int) s[1];
            if (!inside(ia, x, y, 12)) continue;
            double peak = s[5] / (2 * Math.PI * n.sim.psfSigma * n.sim.psfSigma);
            if (peak < 0.08 * clip || peak > 0.3 * clip || crowded(st, s, 10)) continue;
            double[] l = aperture(ia, s[0], s[1], 5);
            if (l == null) continue;
            zp.add(l[1] / green(s));
        }
        double z = median(zp);
        // the control: the same measures on stars that clip nowhere (what the truth's colour comes out as unclipped)
        List<double[]> ctl = new ArrayList<double[]>();
        for (double[] s : st) {
            int x = (int) s[0], y = (int) s[1];
            if (!inside(ia, x, y, 14) || crowded(st, s, 12)) continue;
            double peak = s[5] / (2 * Math.PI * n.sim.psfSigma * n.sim.psfSigma);
            if (peak < 0.15 * clip || peak > 0.6 * clip) continue;
            double[] l = aperture(ia, s[0], s[1], 5);
            if (l == null) continue;
            ctl.add(new double[]{l[1] / (green(s) * z), (l[0] / l[1]) / s[3], (l[2] / l[1]) / s[4]});
        }
        double[] mc = medians(ctl), dc = devs(ctl);
        System.out.printf(Locale.US, "  zero point from %d unclipped stars; control (%d stars clipping nowhere): light %.3f, red/green %.3f +- %.1f%%, "
                + "blue/green %.3f +- %.1f%%%n", zp.size(), ctl.size(), mc[0], mc[1], 100 * dc[1], mc[2], 100 * dc[2]);
        List<double[]> errA = new ArrayList<double[]>(), errB = new ArrayList<double[]>();
        for (double[] s : st) {
            int x = (int) s[0], y = (int) s[1];
            if (!inside(ia, x, y, 14) || crowded(st, s, 12)) continue;
            double peak = s[5] / (2 * Math.PI * n.sim.psfSigma * n.sim.psfSigma);
            // clipped in the long frames, not in the short ones
            if (peak < 1.6 * clip || peak > BRIGHT * clip * StarBrackets.RATIO) continue;
            double green = green(s);
            double[] la = aperture(ia, s[0], s[1], 5), lb = aperture(ib, s[0], s[1], 5);
            if (la == null || lb == null) continue;
            double tg = green * z;
            errA.add(new double[]{la[1] / tg, (la[0] / la[1]) / s[3], (la[2] / la[1]) / s[4]});
            errB.add(new double[]{lb[1] / tg, (lb[0] / lb[1]) / s[3], (lb[2] / lb[1]) / s[4]});
        }
        double[] ma = medians(errA), mb = medians(errB), da = devs(errA), db = devs(errB);
        System.out.printf(Locale.US, "  %d bright stars: without the short frames light %.3f, red/green %.3f, blue/green %.3f of the truth (colour "
                        + "errors %.1f%%, %.1f%%); with them %.3f, %.3f, %.3f (%.1f%%, %.1f%%)%n", errA.size(), ma[0], ma[1], ma[2], 100 * da[1],
                100 * da[2], mb[0], mb[1], mb[2], 100 * db[1], 100 * db[2]);
        check("enough bright stars to judge", errB.size() >= 12, errB.size() + " stars");
        check("their light comes back", Math.abs(mb[0] - 1) < 0.06 && ma[0] < mb[0] - 0.1,
                String.format(Locale.US, "%.3f of the truth with the short frames, %.3f without", mb[0], ma[0]));
        // against the control: what an unclipped star's colour comes out as
        double eb1 = mb[1] / mc[1] - 1, eb2 = mb[2] / mc[2] - 1, ea1 = ma[1] / mc[1] - 1, ea2 = ma[2] / mc[2] - 1;
        check("their colour comes back, as an unclipped star's", Math.abs(eb1) < 0.05 && Math.abs(eb2) < 0.05
                        && Math.abs(eb1) + Math.abs(eb2) < 0.5 * (Math.abs(ea1) + Math.abs(ea2)),
                String.format(Locale.US, "red/green %.3f +- %.1f%%, blue/green %.3f +- %.1f%% (without: %.3f +- %.1f%%, %.3f +- %.1f%%)", mb[1], 100 * db[1],
                        mb[2], 100 * db[2], ma[1], 100 * da[1], ma[2], 100 * da[2]));
        // the rest of the picture untouched: every pixel more than 8 px from a clipped one is the same
        long same = 0, diff = 0;
        byte[] ca = ia.clip;
        boolean[] near = new boolean[ia.width * ia.height];
        for (int y = 0; y < ia.height; y++) for (int x = 0; x < ia.width; x++) {
            if (ca[y * ia.width + x] == 0) continue;
            for (int dy = -8; dy <= 8; dy++) for (int dx = -8; dx <= 8; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && yy >= 0 && xx < ia.width && yy < ia.height) near[yy * ia.width + xx] = true;
            }
        }
        for (int i = 0; i < near.length; i++) {
            if (near[i]) continue;
            if (ia.r[i] == ib.r[i] && ia.g[i] == ib.g[i] && ia.b[i] == ib.b[i]) same++;
            else diff++;
        }
        check("the rest of the picture untouched", diff == 0, same + " pixels the same, " + diff + " differ");
        // the photo: bright stars' cores coloured (their chroma) rather than white
        Renderer.Params p = new Renderer.Params();
        int[] pa = Master.render(ia, Master.analyze(ia, n.spec, false), p), pb = Master.render(ib, Master.analyze(ib, n.spec, false), p);
        double sa = 0, sb = 0;
        int ns = 0;
        int cw = b.analysis.cw, cx0 = b.analysis.cx0, cy0 = b.analysis.cy0;
        for (double[] s : st) {
            double peak = s[5] / (2 * Math.PI * n.sim.psfSigma * n.sim.psfSigma);
            if (peak < 1.6 * clip || peak > 0.75 * clip * StarBrackets.RATIO || crowded(st, s, 12)) continue;
            // only clearly coloured stars
            if (Math.abs(s[3] - 0.48 / 1.0) < 0.05 && Math.abs(s[4] - 0.66) < 0.05) continue;
            int x = (int) s[0] - cx0, y = (int) s[1] - cy0;
            if (x < 3 || y < 3 || x >= cw - 3 || y >= b.analysis.ch - 3) continue;
            sa += chroma(pa, cw, x, y);
            sb += chroma(pb, cw, x, y);
            ns++;
        }
        check("the photo shows the bright stars' colours", ns >= 8 && sb > 1.3 * sa, String.format(Locale.US, "core colourfulness %.1f with "
                + "the short frames, %.1f without, over %d stars", sb / Math.max(1, ns), sa / Math.max(1, ns), ns));
        PhotoVideoTest.writePng(pa, cw, b.analysis.ch, new File(dir, "without.png"));
        PhotoVideoTest.writePng(pb, cw, b.analysis.ch, new File(dir, "with.png"));
        // a master keeps the mark
        Master.Meta meta = Master.meta(b, n.spec, false);
        meta.key = "t1";
        Master.write(n.store.dir, meta, ib);
        Master.Loaded back = Master.read(n.store.dir, "t1");
        check("a master keeps the mark", back != null && back.image.bracketed, back == null ? "not read back" : "");
        Master.Meta ma2 = Master.meta(a, n.spec, false);
        ma2.key = "t0";
        Master.write(n.store.dir, ma2, ia);
        Master.Loaded back0 = Master.read(n.store.dir, "t0");
        check("an unbracketed master stays unmarked", back0 != null && !back0.image.bracketed, "");
    }

    /** A star's green light as the simulator drew it, before vignetting (the stack takes the lens's shading out). */
    static double green(double[] s) {
        return s[2] * 4 / (2 + s[3] + s[4]);
    }

    static double chroma(int[] px, int w, int x, int y) {
        // the most colourful of the core's 3x3 (display levels: max - min of the channels)
        double best = 0;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int v = px[(y + dy) * w + x + dx];
            int r = (v >> 16) & 0xFF, g = (v >> 8) & 0xFF, b = v & 0xFF;
            best = Math.max(best, Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)));
        }
        return best;
    }

    static boolean inside(RgbImage im, int x, int y, int m) {
        return x >= im.x0 + m && y >= im.y0 + m && x < im.x1 - m && y < im.y1 - m;
    }

    static boolean crowded(double[][] st, double[] s, double r) {
        for (double[] o : st) {
            if (o == s) continue;
            if (Math.hypot(o[0] - s[0], o[1] - s[1]) < r && o[5] > 0.02 * s[5]) return true;
        }
        return false;
    }

    /** Light in a circle of radius r less the median of the ring r+2..r+5, per channel. */
    static double[] aperture(RgbImage im, double cx, double cy, int r) {
        int W = im.width;
        List<float[]> ring = new ArrayList<float[]>();
        double[] sum = new double[3];
        int n = 0;
        for (int y = (int) cy - r - 6; y <= (int) cy + r + 6; y++) {
            for (int x = (int) cx - r - 6; x <= (int) cx + r + 6; x++) {
                if (x < 0 || y < 0 || x >= W || y >= im.height) return null;
                double d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
                int i = y * W + x;
                if (d <= r) {
                    sum[0] += im.r[i];
                    sum[1] += im.g[i];
                    sum[2] += im.b[i];
                    n++;
                } else if (d >= r + 2 && d <= r + 5) {
                    ring.add(new float[]{im.r[i], im.g[i], im.b[i]});
                }
            }
        }
        for (int c = 0; c < 3; c++) {
            float[] v = new float[ring.size()];
            for (int k = 0; k < v.length; k++) v[k] = ring.get(k)[c];
            java.util.Arrays.sort(v);
            sum[c] -= n * v[v.length / 2];
        }
        return sum;
    }

    static double median(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> c = new ArrayList<Double>(v);
        java.util.Collections.sort(c);
        return c.get(c.size() / 2);
    }

    static double[] medians(List<double[]> v) {
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            List<Double> l = new ArrayList<Double>();
            for (double[] e : v) l.add(e[c]);
            out[c] = median(l);
        }
        return out;
    }

    /** Robust spread (1.4826 MAD) of each ratio, as a share. */
    static double[] devs(List<double[]> v) {
        double[] m = medians(v), out = new double[3];
        for (int c = 0; c < 3; c++) {
            List<Double> l = new ArrayList<Double>();
            for (double[] e : v) l.add(Math.abs(e[c] / m[c] - 1));
            out[c] = 1.4826 * median(l);
        }
        return out;
    }
}
