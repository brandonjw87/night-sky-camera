package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.MoonGeo;
import com.brandon.nightsky.processing.MoonLook;
import com.brandon.nightsky.processing.MoonSites;
import com.brandon.nightsky.processing.MoonStack;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.SensorSpec;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

import javax.imageio.ImageIO;

/**
 * The Moonshot's stack (3.0) on a simulated Moon: a hand-held burst from the 5x telephoto (a shaking, turning,
 * sometimes smeared Moon, a passing cloud, shot and read noise, a Bayer filter), stacked, and checked against the
 * Moon it was drawn from: the disk found where it is, every frame's shake measured to a few hundredths of a pixel,
 * the clouded frames left out, the stack truer to the real surface than the best single frame, and the sharpened
 * stack truer again; then a crescent and a full Moon.
 * <p>
 * Usage: MoonshotTest [out dir] (pictures of each step for a look)
 */
public final class MoonshotTest {
    static int failed;

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    public static long utc(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    static final double LAT = 39.0, LON = -77.0, SCALE = 17.0;

    /** The first hour from t0 when the Moon is lit between lo and hi and above minAlt here. */
    public static long find(long t0, double lo, double hi, double minAlt) {
        for (int k = 0; k < 24 * 90; k++) {
            long t = t0 + k * 3600_000L;
            MoonGeo.View v = MoonGeo.view(t, LAT, LON);
            if (v.illum >= lo && v.illum <= hi && v.alt > minAlt) return t;
        }
        throw new IllegalStateException("no such Moon");
    }

    /** A burst: the frames, and the truth about each (the Moon's centre in its crop, its turn). */
    static final class Burst {
        final List<MoonStack.Frame> frames = new ArrayList<MoonStack.Frame>();
        final List<double[]> where = new ArrayList<double[]>();
        final List<Integer> clouded = new ArrayList<Integer>();
    }

    /**
     * A hand-held burst at 30 frames a second: the hand's slow wander and the sky's drift, its tremor (8-12 Hz), a
     * slow roll, the blur of each frame (most a little, some more, a few badly), and a cloud over a few.
     */
    static Burst shoot(MoonSim sim, int n, int crop, long seed, int clouds) {
        Random r = new Random(seed);
        Burst b = new Burst();
        double X = 2040.3, Y = 1530.7, rot = 0;
        double px = X, py = Y;
        for (int i = 0; i < n; i++) {
            double t = i / 30.0;
            X += 0.8 / 30 + 0.12 * r.nextGaussian();
            Y += 0.3 / 30 + 0.12 * r.nextGaussian();
            double mx = X + 0.35 * Math.sin(2 * Math.PI * 9.1 * t + 0.4) + 0.2 * Math.sin(2 * Math.PI * 11.3 * t + 1.3);
            double my = Y + 0.3 * Math.sin(2 * Math.PI * 8.7 * t + 2.1) + 0.25 * Math.sin(2 * Math.PI * 12.1 * t);
            rot += 0.012 * r.nextGaussian();
            double u = r.nextDouble();
            double blur = u < 0.6 ? 0.2 + 0.3 * r.nextDouble() : u < 0.9 ? 0.6 + 0.4 * r.nextDouble() : 1.2 + 0.6 * r.nextDouble();
            double gain = 0.9 * (1 + 0.01 * r.nextGaussian());
            boolean cloud = clouds > 0 && i % (n / clouds) == n / clouds / 2;
            if (cloud) gain *= 0.45;
            // the crop follows the Moon (where it was a frame ago), at even places so the colours line up
            int x0 = 2 * (int) Math.round((px - crop / 2.0) / 2), y0 = 2 * (int) Math.round((py - crop / 2.0) / 2);
            short[] raw = sim.frame(crop, crop, x0, y0, mx, my, rot, blur, gain, r, t);
            MoonStack.Frame f = new MoonStack.Frame(raw, crop, crop, x0, y0, (long) (t * 1e9));
            f.upPa = sim.upPa - rot;
            b.frames.add(f);
            b.where.add(new double[]{mx - x0, my - y0, rot, blur, gain});
            if (cloud) b.clouded.add(i);
            px = mx;
            py = my;
        }
        return b;
    }

    public static SensorSpec spec() {
        SensorSpec s = new SensorSpec();
        s.width = 4080;
        s.height = 3072;
        ColorMath.compute(s, 5500);
        return s;
    }

    /** The real surface (no lens, no shake) in the result's geometry: luminance. */
    static float[] truthIn(MoonSim sim, MoonStack.Result res, Burst b, boolean lens) {
        int W = res.w;
        MoonStack.Frame rf = b.frames.get(res.reference);
        double[] rw = b.where.get(res.reference);
        double S = 2;
        double psi = Math.toRadians(rf.upPa - res.upPa), c = Math.cos(psi), s = Math.sin(psi);
        double a = -rw[2] * Math.PI / 180, ca = Math.cos(a), sa = Math.sin(a);
        float[] out = new float[W * W];
        for (int j = 0; j < W; j++) {
            for (int i = 0; i < W; i++) {
                double ox = (i - res.cx) / S, oy = (j - res.cy) / S;
                // back to the reference crop: x = rc + M^T o
                double x = res.disk.cx + c * ox - s * oy, y = res.disk.cy + s * ox + c * oy;
                double dx = x - rw[0], dy = y - rw[1];
                double tx = dx * ca + dy * sa, ty = -dx * sa + dy * ca;
                double v = 0;
                double[] wts = {0.2126, 0.7152, 0.0722};
                for (int ch = 0; ch < 3; ch++)
                    v += wts[ch] * (lens ? sim.truthBox(ch, tx, ty, 1 / S) : sim.truthSharp(ch, tx, ty, 1 / S));
                out[j * W + i] = (float) v;
            }
        }
        return out;
    }

    static float[] lum(float[] r, float[] g, float[] b) {
        float[] y = new float[r.length];
        for (int k = 0; k < y.length; k++) y[k] = 0.2126f * r[k] + 0.7152f * g[k] + 0.0722f * b[k];
        return y;
    }

    static float[] blur(float[] img, int w, double sigma) {
        int rad = (int) Math.ceil(3 * sigma);
        double[] k = new double[2 * rad + 1];
        double sum = 0;
        for (int i = -rad; i <= rad; i++) sum += k[i + rad] = Math.exp(-i * i / (2 * sigma * sigma));
        float[] t = new float[img.length], o = new float[img.length];
        int h = img.length / w;
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) {
                double v = 0;
                for (int d = -rad; d <= rad; d++) v += k[d + rad] * img[j * w + Math.max(0, Math.min(w - 1, i + d))];
                t[j * w + i] = (float) (v / sum);
            }
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) {
                double v = 0;
                for (int d = -rad; d <= rad; d++) v += k[d + rad] * t[Math.max(0, Math.min(h - 1, j + d)) * w + i];
                o[j * w + i] = (float) (v / sum);
            }
        return o;
    }

    /**
     * How truly a picture holds the surface's detail: the correlation of its fine detail (what a blur of 3 pixels
     * takes away) with the truth's, over the lit disk away from its edge.
     */
    static double detail(float[] img, float[] truth, MoonStack.Result res) {
        int W = res.w;
        float[] a = blur(img, W, 3), b = blur(truth, W, 3);
        float top = 0;
        for (float v : truth) top = Math.max(top, v);
        double sab = 0, saa = 0, sbb = 0;
        for (int j = 0; j < W; j++)
            for (int i = 0; i < W; i++) {
                int k = j * W + i;
                double dx = i - res.cx, dy = j - res.cy;
                if (dx * dx + dy * dy > Math.pow(0.92 * res.radius, 2) || truth[k] < 0.15 * top) continue;
                double x = img[k] - a[k], y = truth[k] - b[k];
                sab += x * y;
                saa += x * x;
                sbb += y * y;
            }
        return sab / Math.sqrt(saa * sbb + 1e-30);
    }

    /** The noise of the sky just outside the Moon, as a share of the Moon's brightness. */
    static double skyNoise(float[] Y, MoonStack.Result res) {
        int W = res.w;
        double s = 0, s2 = 0;
        int n = 0;
        for (int j = 0; j < W; j++)
            for (int i = 0; i < W; i++) {
                double d = Math.hypot(i - res.cx, j - res.cy);
                if (d < res.radius + 8 || d > res.radius + 16) continue;
                double v = Y[j * W + i];
                s += v;
                s2 += v * v;
                n++;
            }
        if (n < 10) return Double.NaN;
        double m = s / n;
        return Math.sqrt(Math.max(0, s2 / n - m * m));
    }

    static void png(File f, int W, float[]... imgs) throws Exception {
        BufferedImage im = new BufferedImage(W * imgs.length, W, BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < imgs.length; p++) {
            float[] y = imgs[p];
            float top = 0;
            for (float v : y) top = Math.max(top, v);
            float[] sorted = y.clone();
            java.util.Arrays.sort(sorted);
            float hi = sorted[(int) (0.998 * (sorted.length - 1))];
            if (hi <= 0) hi = top > 0 ? top : 1;
            for (int j = 0; j < W; j++)
                for (int i = 0; i < W; i++) {
                    double v = Math.max(0, Math.min(1, y[j * W + i] / hi));
                    int g = (int) Math.round(255 * Math.pow(v, 1 / 2.2));
                    im.setRGB(p * W + i, j, (g << 16) | (g << 8) | g);
                }
        }
        f.getParentFile().mkdirs();
        ImageIO.write(im, "png", f);
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "/tmp/moonshot");
        SensorSpec spec = spec();

        System.out.println("== a waxing gibbous Moon, hand-held: 120 frames");
        long t = find(utc(2026, 12, 1, 0, 0), 0.55, 0.75, 30);
        MoonGeo.View v0 = MoonGeo.view(t, LAT, LON);
        double upPa = v0.q + 12;
        long t0 = System.currentTimeMillis();
        MoonSim sim = new MoonSim(t, LAT, LON, SCALE, upPa, 6);
        System.out.println(String.format(Locale.US, "  the Moon %.0f%% lit, %.0f degrees up, %.1f px across; drawn in %d ms", v0.illum * 100, v0.alt,
                2 * sim.rPx, System.currentTimeMillis() - t0));
        Burst b = shoot(sim, 120, 160, 7, 4);
        MoonStack.Options opt = new MoonStack.Options();
        opt.expectedRadius = sim.rPx;
        opt.outUpPa = v0.q;
        opt.brightLimbPa = v0.chi;
        opt.deconvolve = false;
        t0 = System.currentTimeMillis();
        MoonStack.Result st = MoonStack.stack(b.frames, spec, opt, Progress.NONE);
        long ms = System.currentTimeMillis() - t0;
        System.out.println("  " + st.log + " [" + ms + " ms]");
        opt.deconvolve = true;
        MoonStack.Result sh = MoonStack.stack(b.frames, spec, opt, Progress.NONE);
        System.out.println("  " + sh.log);

        double[] rw = b.where.get(st.reference);
        double dc = Math.hypot(st.disk.cx - rw[0], st.disk.cy - rw[1]);
        check("the disk found where the Moon is", dc < 0.5, String.format(Locale.US, "off by %.2f px; radius %.2f against %.2f", dc, st.disk.r, sim.rPx));
        check("its size within 3%", Math.abs(st.disk.r / sim.rPx - 1) < 0.03, String.format(Locale.US, "%.2f", st.disk.r / sim.rPx));
        // every kept frame's shake against the truth
        double se = 0, re = 0;
        int ne = 0;
        for (int i = 0; i < b.frames.size(); i++) {
            if (!st.kept[i]) continue;
            double[] w = b.where.get(i);
            double ex = st.motion[i][0] - (w[0] - rw[0]), ey = st.motion[i][1] - (w[1] - rw[1]);
            se += ex * ex + ey * ey;
            double er = st.motion[i][2] + (w[2] - rw[2]);
            re += er * er;
            ne++;
        }
        double rms = Math.sqrt(se / Math.max(1, ne)), rrms = Math.sqrt(re / Math.max(1, ne));
        check("every merged frame lined up within 0.05 px", rms < 0.05, String.format(Locale.US, "rms %.4f px over %d frames", rms, ne));
        check("and its turn within 0.04 degrees", rrms < 0.04, String.format(Locale.US, "rms %.4f deg", rrms));
        boolean cloudsOut = true;
        for (int i : b.clouded) if (st.kept[i]) cloudsOut = false;
        check("the clouded frames left out", cloudsOut, b.clouded.size() + " clouded");
        double meanBlurKept = 0, meanBlurAll = 0;
        int nk = 0;
        for (int i = 0; i < b.frames.size(); i++) {
            meanBlurAll += b.where.get(i)[3];
            if (st.kept[i]) {
                meanBlurKept += b.where.get(i)[3];
                nk++;
            }
        }
        meanBlurKept /= Math.max(1, nk);
        meanBlurAll /= b.frames.size();
        check("the sharpest chosen", meanBlurKept < 0.8 * meanBlurAll, String.format(Locale.US, "their blur %.2f px, all frames' %.2f", meanBlurKept, meanBlurAll));

        float[] truth = truthIn(sim, st, b, false), lens = truthIn(sim, st, b, true);
        float[] y1 = lum(st.r1, st.g1, st.b1), ys = lum(st.r, st.g, st.b), yd = lum(sh.r, sh.g, sh.b);
        double d1 = detail(y1, truth, st), ds = detail(ys, truth, st), dd = detail(yd, truth, st), dl = detail(lens, truth, st);
        System.out.println(String.format(Locale.US, "  the surface's detail held: one frame %.3f, stacked %.3f, sharpened %.3f (a perfect lens-limited picture %.3f)",
                d1, ds, dd, dl));
        check("the stack holds more of the surface than the best frame", ds > d1 + 0.03, String.format(Locale.US, "%.3f against %.3f", ds, d1));
        check("the sharpened stack more again", dd > ds + 0.02, String.format(Locale.US, "%.3f against %.3f", dd, ds));
        double n1 = skyNoise(y1, st), ns = skyNoise(ys, st);
        check("the noise down by more than 3 times", ns < n1 / 3, String.format(Locale.US, "sky noise %.4f to %.4f", n1, ns));
        check("the bright edge sharper after sharpening", sh.edgeSharp < sh.edgeStack * 0.85,
                String.format(Locale.US, "%.2f px stacked, %.2f sharpened, %.2f one frame", sh.edgeStack, sh.edgeSharp, sh.edgeSingle));
        check("the stack quick enough for a phone (under 20 s here)", ms < 20000, ms + " ms");
        png(new File(out, "gibbous.png"), st.w, truth, y1, ys, yd);

        System.out.println("== a crescent, low");
        {
            long tc = find(utc(2026, 12, 1, 0, 0), 0.08, 0.2, 12);
            MoonGeo.View vc = MoonGeo.view(tc, LAT, LON);
            MoonSim cs = new MoonSim(tc, LAT, LON, SCALE, vc.q - 30, 6);
            Burst cb = shoot(cs, 60, 160, 11, 0);
            // nothing known of its lit side: found from the picture
            MoonStack.Options co = new MoonStack.Options();
            co.expectedRadius = cs.rPx;
            co.outUpPa = vc.q;
            MoonStack.Result cr = MoonStack.stack(cb.frames, spec, co, Progress.NONE);
            System.out.println(String.format(Locale.US, "  the Moon %.0f%% lit, %.0f degrees up: ", vc.illum * 100, vc.alt) + cr.log);
            double[] cw = cb.where.get(cr.reference);
            double cd = Math.hypot(cr.disk.cx - cw[0], cr.disk.cy - cw[1]);
            check("the whole disk found from its thin lit edge", cr.disk.ok && cd < 1.5 && Math.abs(cr.disk.r / cs.rPx - 1) < 0.03,
                    String.format(Locale.US, "off by %.2f px, radius %.2f against %.2f", cd, cr.disk.r, cs.rPx));
            // the lit side points where the Sun is (the bright limb's angle, in the result turned to the sky)
            double litPa = MoonGeo.norm360(cr.disk.litDir + cb.frames.get(cr.reference).upPa);
            double dChi = Math.abs(MoonGeo.norm180(litPa - vc.chi));
            check("its lit side towards the Sun", dChi < 10, String.format(Locale.US, "lit towards %.1f, the bright limb at %.1f", litPa, vc.chi));
            float[] ct = truthIn(cs, cr, cb, false);
            png(new File(out, "crescent.png"), cr.w, ct, lum(cr.r1, cr.g1, cr.b1), lum(cr.r, cr.g, cr.b));
        }

        System.out.println("== full, high");
        {
            long tf = find(utc(2026, 12, 1, 0, 0), 0.985, 1.0, 35);
            MoonGeo.View vf = MoonGeo.view(tf, LAT, LON);
            MoonSim fs = new MoonSim(tf, LAT, LON, SCALE, vf.q + 70, 6);
            Burst fb = shoot(fs, 60, 160, 13, 0);
            MoonStack.Options fo = new MoonStack.Options();
            fo.expectedRadius = fs.rPx;
            fo.outUpPa = vf.q;
            fo.brightLimbPa = vf.chi;
            MoonStack.Result fr = MoonStack.stack(fb.frames, spec, fo, Progress.NONE);
            System.out.println(String.format(Locale.US, "  the Moon %.1f%% lit, %.0f degrees up: ", vf.illum * 100, vf.alt) + fr.log);
            double[] fw = fb.where.get(fr.reference);
            double fd = Math.hypot(fr.disk.cx - fw[0], fr.disk.cy - fw[1]);
            check("the disk found", fr.disk.ok && fd < 0.5, String.format(Locale.US, "off by %.2f px", fd));
            float[] ft = truthIn(fs, fr, fb, false);
            double f1 = detail(lum(fr.r1, fr.g1, fr.b1), ft, fr), fdd = detail(lum(fr.r, fr.g, fr.b), ft, fr);
            check("the sharpened stack truer than one frame", fdd > f1 + 0.03, String.format(Locale.US, "%.3f against %.3f", fdd, f1));
            png(new File(out, "full.png"), fr.w, ft, lum(fr.r1, fr.g1, fr.b1), lum(fr.r, fr.g, fr.b));
        }

        System.out.println("== the landing sites where they really are: lamps at Apollo 11, 15 and 16, found in the stacked picture");
        {
            String[] keys = {"apollo11", "apollo15", "apollo16"};
            double[][] lamps = new double[keys.length][];
            for (int i = 0; i < keys.length; i++) {
                MoonSites.Site st0 = MoonSites.get(keys[i]);
                lamps[i] = new double[]{st0.lat, st0.lon, 1.0, 8};
            }
            // a turned phone: its up 40 degrees from the sky's
            MoonSim ls = new MoonSim(t, LAT, LON, SCALE, v0.q - 40, 6, lamps);
            Burst lb = shoot(ls, 60, 160, 17, 0);
            MoonStack.Options lo = new MoonStack.Options();
            lo.expectedRadius = ls.rPx;
            lo.outUpPa = v0.q;
            lo.brightLimbPa = v0.chi;
            lo.deconvolve = false;
            MoonStack.Result lr = MoonStack.stack(lb.frames, spec, lo, Progress.NONE);
            float[] Y = lum(lr.r, lr.g, lr.b);
            double worst = 0;
            StringBuilder sb = new StringBuilder();
            for (String k : keys) {
                MoonSites.Site st0 = MoonSites.get(k);
                double[] at = ls.view.inPicture(st0.lat, st0.lon, lr.cx, lr.cy, lr.radius, lr.upPa);
                // the lamp's light around where the labels put it
                double sx = 0, sy = 0, sw = 0;
                for (int j = (int) at[1] - 9; j <= (int) at[1] + 9; j++)
                    for (int i = (int) at[0] - 9; i <= (int) at[0] + 9; i++) {
                        if (i < 0 || j < 0 || i >= lr.w || j >= lr.h) continue;
                        double v = Y[j * lr.w + i] - 1.2;
                        if (v <= 0) continue;
                        sx += v * i;
                        sy += v * j;
                        sw += v;
                    }
                double err = sw > 0 ? Math.hypot(sx / sw - at[0], sy / sw - at[1]) : 99;
                worst = Math.max(worst, err);
                sb.append(String.format(Locale.US, "%s %.2f px  ", st0.name, err));
            }
            // where the bright spots are
            for (int j = 1; j < lr.h - 1; j++)
                for (int i = 1; i < lr.w - 1; i++) {
                    float v = Y[j * lr.w + i];
                    if (v > 1.3 && v >= Y[j * lr.w + i - 1] && v >= Y[j * lr.w + i + 1] && v >= Y[(j - 1) * lr.w + i] && v >= Y[(j + 1) * lr.w + i])
                        System.out.println(String.format(Locale.US, "    a bright spot at %d,%d (%.2f)", i, j, v));
                }
            for (String k : keys) {
                MoonSites.Site st0 = MoonSites.get(k);
                double[] at = ls.view.inPicture(st0.lat, st0.lon, lr.cx, lr.cy, lr.radius, lr.upPa);
                System.out.println(String.format(Locale.US, "    %s predicted at %.1f,%.1f (z %.2f), the Sun %.1f deg up there", st0.name, at[0], at[1], at[2],
                        ls.view.sunAltAt(st0.lat, st0.lon)));
            }
            check("each lamp where its label points (within 1.5 px of the stacked picture's 2x pixels)", worst < 1.5, sb.toString().trim());
            // no tilt known (no gravity sensor reading): the lit side turns the picture, north up
            for (MoonStack.Frame f : lb.frames) f.upPa = Double.NaN;
            MoonStack.Options no = new MoonStack.Options();
            no.expectedRadius = ls.rPx;
            no.outUpPa = 0;
            no.brightLimbPa = v0.chi;
            no.deconvolve = false;
            MoonStack.Result nr = MoonStack.stack(lb.frames, spec, no, Progress.NONE);
            float[] Yn = lum(nr.r, nr.g, nr.b);
            double worstN = 0;
            for (String k : keys) {
                MoonSites.Site st0 = MoonSites.get(k);
                double[] at = ls.view.inPicture(st0.lat, st0.lon, nr.cx, nr.cy, nr.radius, nr.upPa);
                double sx = 0, sy = 0, sw = 0;
                for (int j = (int) at[1] - 9; j <= (int) at[1] + 9; j++)
                    for (int i = (int) at[0] - 9; i <= (int) at[0] + 9; i++) {
                        if (i < 0 || j < 0 || i >= nr.w || j >= nr.h) continue;
                        double v = Yn[j * nr.w + i] - 1.2;
                        if (v <= 0) continue;
                        sx += v * i;
                        sy += v * j;
                        sw += v;
                    }
                worstN = Math.max(worstN, sw > 0 ? Math.hypot(sx / sw - at[0], sy / sw - at[1]) : 99);
            }
            check("with no tilt known, the lit side turns it right (within 2 px)", worstN < 2 && nr.log.contains("from the lit side"),
                    String.format(Locale.US, "worst %.2f px; up at %.1f", worstN, nr.upPa));
            // the finished photo, labelled
            MoonLook.Picture pic = MoonLook.enlarge(lr.r, lr.g, lr.b, lr.w, lr.h, lr.cx, lr.cy, lr.radius, lr.upPa);
            List<MoonLook.Label> labels;
            AwtPainter ap = new AwtPainter(pic.argb, pic.w, pic.h);
            labels = MoonLook.layout(pic, ls.view, MoonLook.LABELS_FAMOUS, ap, 30);
            float fs = MoonLook.fit(ap, pic, labels, 30);
            labels = MoonLook.layout(pic, ls.view, MoonLook.LABELS_FAMOUS, ap, fs);
            MoonLook.paint(ap, pic, labels, fs);
            MoonLook.captions(ap, pic, "MOONSHOT", MoonLook.caption(ls.view, lr, "1 Dec 2026, 22:00", "39.0 N, 77.0 W"), 30);
            int[] px = ap.pixels();
            BufferedImage im = new BufferedImage(pic.w, pic.h, BufferedImage.TYPE_INT_RGB);
            im.setRGB(0, 0, pic.w, pic.h, px, 0, pic.w);
            ImageIO.write(im, "png", new File(out, "labelled.png"));
            int apollo = 0;
            boolean apart = true;
            for (MoonLook.Label l : labels) {
                if (l.site.apollo()) apollo++;
                for (MoonLook.Label m : labels) if (m != l && m.left == l.left && Math.abs(m.ty - l.ty) < fs * 1.3) apart = false;
            }
            check("all six Apollo landings labelled", apollo == 6, labels.size() + " labels");
            check("no two names on top of each other", apart, "");
            // the mineral colours keep each pixel's brightness
            float[][] mc = MoonLook.mineral(lr.r, lr.g, lr.b, lr.w, lr.h, 4);
            double dy = 0;
            for (int k = 0; k < Y.length; k++) {
                double y2 = 0.2126 * mc[0][k] + 0.7152 * mc[1][k] + 0.0722 * mc[2][k];
                if (Y[k] > 0.1) dy = Math.max(dy, Math.abs(y2 - Y[k]) / Y[k]);
            }
            check("the mineral colours keep the brightness", dy < 0.03, String.format(Locale.US, "worst %.4f", dy));
            int[] mp = MoonLook.argb(mc[0], mc[1], mc[2]);
            BufferedImage mi = new BufferedImage(lr.w, lr.h, BufferedImage.TYPE_INT_RGB);
            mi.setRGB(0, 0, lr.w, lr.h, mp, 0, lr.w);
            ImageIO.write(mi, "png", new File(out, "mineral.png"));
        }

        System.out.println("== no Moon: a burst of empty sky");
        {
            List<MoonStack.Frame> empty = new ArrayList<MoonStack.Frame>();
            Random r = new Random(5);
            for (int i = 0; i < 20; i++) {
                short[] raw = new short[160 * 160];
                for (int k = 0; k < raw.length; k++) raw[k] = (short) (64 + Math.round(2 * r.nextGaussian()));
                empty.add(new MoonStack.Frame(raw, 160, 160, 0, 0, i));
            }
            MoonStack.Result er = MoonStack.stack(empty, spec, new MoonStack.Options(), Progress.NONE);
            check("says so, without a picture", er != null && er.r == null && !er.disk.ok, er == null ? "null" : er.log);
        }
        System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
        if (failed > 0) System.exit(1);
    }
}
