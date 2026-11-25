package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.MoonGeo;
import com.brandon.nightsky.processing.MoonLook;
import com.brandon.nightsky.processing.MoonStack;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.SensorSpec;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import javax.imageio.ImageIO;
import java.util.Locale;

/**
 * The Transit Hunter's photo (3.0): a hand-held burst with the ISS's silhouette crossing the simulated Moon in 0.65 s
 * (its truss, arrays and modules, 2.4 px per 100 m, as at 500 km on the telephoto). The stack must find the station
 * in the frames where it is against the lit Moon, put it where it really was to a fraction of a pixel, keep those
 * frames out of the merge and lay each silhouette on the photo, leaving the rest of the photo as it was; and must
 * find nothing in the same burst without the station.
 * <p>
 * Usage: TransitShotTest [out dir]
 */
public final class TransitShotTest {
    static int failed;

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    static double truthLum(MoonSim sim, double x, double y) {
        return 0.2126 * sim.truth(0, x, y) + 0.7152 * sim.truth(1, x, y) + 0.0722 * sim.truth(2, x, y);
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "/tmp/transitshot");
        out.mkdirs();
        SensorSpec spec = MoonshotTest.spec();
        long t = MoonshotTest.find(MoonshotTest.utc(2026, 12, 1, 0, 0), 0.55, 0.75, 30);
        MoonGeo.View v0 = MoonGeo.view(t, MoonshotTest.LAT, MoonshotTest.LON);
        MoonSim sim = new MoonSim(t, MoonshotTest.LAT, MoonshotTest.LON, MoonshotTest.SCALE, v0.q + 12, 6);
        double r = sim.rPx, cross = 0.65, mid = 3.0;
        // across the Moon's middle, a little tilted, in 0.65 s
        double vx = 2 * r / cross, vy = 0.35 * r / cross;
        sim.station = new double[]{-vx * mid - 0.05 * r, -vy * mid + 0.1 * r, vx, vy, 28, 2.4};
        System.out.println(String.format(Locale.US, "== the ISS crossing a %.0f%% lit Moon (%.0f px across) in %.2f s, hand-held: 180 frames", v0.illum * 100,
                2 * r, cross));
        MoonshotTest.Burst b = MoonshotTest.shoot(sim, 180, 160, 11, 0);
        MoonStack.Options opt = new MoonStack.Options();
        opt.expectedRadius = r;
        opt.outUpPa = v0.q;
        opt.brightLimbPa = v0.chi;
        opt.transit = true;
        long t0 = System.currentTimeMillis();
        MoonStack.Result res = MoonStack.stack(b.frames, spec, opt, Progress.NONE);
        long ms = System.currentTimeMillis() - t0;
        System.out.println("  " + res.log + " [" + ms + " ms]");
        MoonStack.Crossing c = res.crossing;
        check("the station found", c != null, "");
        // the truth: the frames where the station's middle is over the lit Moon, and where it is in the reference crop
        int expected = 0;
        double[] rw = b.where.get(res.reference);
        double a = -rw[2] * Math.PI / 180;
        // the lit Moon: brighter than a sixth of its bright parts
        double top = 0;
        for (double y = -r; y <= r; y += 1) for (double x = -r; x <= r; x += 1) if (x * x + y * y < r * r) top = Math.max(top, truthLum(sim, x, y));
        for (int i = 0; i < b.frames.size(); i++) {
            double tt = b.frames.get(i).tNs * 1e-9;
            double sx = sim.station[0] + sim.station[2] * tt, sy = sim.station[1] + sim.station[3] * tt;
            if (Math.hypot(sx, sy) > r - 3) continue;
            if (truthLum(sim, sx, sy) > top / 6) expected++;
        }
        if (c != null) {
            System.out.println("  " + c);
            check("in the frames where it's against the lit Moon", c.frames.length >= 0.8 * expected && c.frames.length <= expected + 3,
                    c.frames.length + " frames, " + expected + " with it over the lit part");
            double se = 0;
            for (int q = 0; q < c.frames.length; q++) {
                double tt = b.frames.get(c.frames[q]).tNs * 1e-9;
                double sx = sim.station[0] + sim.station[2] * tt, sy = sim.station[1] + sim.station[3] * tt;
                // the Moon's frame to the reference crop
                double x = rw[0] + sx * Math.cos(a) - sy * Math.sin(a), y = rw[1] + sx * Math.sin(a) + sy * Math.cos(a);
                se += Math.pow(c.at[q][0] - x, 2) + Math.pow(c.at[q][1] - y, 2);
            }
            double rms = Math.sqrt(se / c.frames.length);
            check("where it really was, frame by frame", rms < 0.5, String.format(Locale.US, "rms %.2f px", rms));
            double speed = Math.hypot(c.vx, c.vy), trueSpeed = Math.hypot(vx, vy);
            check("its speed", Math.abs(speed / trueSpeed - 1) < 0.03, String.format(Locale.US, "%.0f px/s against %.0f", speed, trueSpeed));
            boolean out1 = true;
            for (int f : c.frames) out1 &= !res.kept[f];
            check("its frames kept out of the merge", out1, "");
            check("dark, as a silhouette is", c.depth > 0.15, String.format(Locale.US, "%.0f%%", c.depth * 100));
        }
        // the photo: darker along the track, the same elsewhere
        opt.transit = false;
        MoonStack.Result plain = MoonStack.stack(b.frames, spec, opt, Progress.NONE);
        float[] Y = MoonshotTest.lum(res.r, res.g, res.b), Yp = MoonshotTest.lum(plain.r, plain.g, plain.b);
        if (c != null) {
            double darker = 0;
            for (double[] p : res.crossingAt) {
                int k = (int) Math.round(p[1]) * res.w + (int) Math.round(p[0]);
                darker += 1 - Y[k] / Math.max(1e-6, Yp[k]);
            }
            darker /= res.crossingAt.length;
            check("each silhouette on the photo", darker > 0.2, String.format(Locale.US, "%.0f%% darker on the track", darker * 100));
            double diff = 0, sum = 0;
            for (int j = 0; j < res.w; j++) {
                for (int i = 0; i < res.w; i++) {
                    double dx = i - res.cx, dy = j - res.cy;
                    if (dx * dx + dy * dy > res.radius * res.radius * 0.9) continue;
                    boolean nearTrack = false;
                    for (double[] p : res.crossingAt) if (Math.hypot(i - p[0], j - p[1]) < 14) nearTrack = true;
                    if (nearTrack) continue;
                    int k = j * res.w + i;
                    diff += Math.abs(Y[k] - Yp[k]);
                    sum += Yp[k];
                }
            }
            check("the rest of the photo as it was", diff / sum < 0.02, String.format(Locale.US, "%.2f%% different", diff / sum * 100));
        }
        MoonshotTest.png(new File(out, "transit.png"), res.w, Yp, Y);
        if (c != null) {
            // the finished photo, its landings and the crossing marked, as the phone saves it
            MoonLook.Picture pic = MoonLook.enlarge(res.r, res.g, res.b, res.w, res.h, res.cx, res.cy, res.radius, res.upPa);
            AwtPainter ap = new AwtPainter(pic.argb, pic.w, pic.h);
            List<MoonLook.Label> labels = MoonLook.layout(pic, v0, MoonLook.LABELS_FAMOUS, ap, 30);
            float fs = MoonLook.fit(ap, pic, labels, 30);
            labels = MoonLook.layout(pic, v0, MoonLook.LABELS_FAMOUS, ap, fs);
            MoonLook.paint(ap, pic, labels, fs);
            MoonLook.track(ap, pic, res, "ISS", fs);
            MoonLook.captions(ap, pic, "MOONSHOT", MoonLook.caption(v0, res, "1 Dec 2026, 22:00", "39.0 N, 77.0 W"), 30);
            BufferedImage im = new BufferedImage(pic.w, pic.h, BufferedImage.TYPE_INT_RGB);
            im.setRGB(0, 0, pic.w, pic.h, ap.pixels(), 0, pic.w);
            ImageIO.write(im, "png", new File(out, "transit_labelled.png"));
        }
        // no station: nothing found
        sim.station = null;
        MoonshotTest.Burst nb = MoonshotTest.shoot(sim, 180, 160, 11, 0);
        opt.transit = true;
        MoonStack.Result none = MoonStack.stack(nb.frames, spec, opt, Progress.NONE);
        check("no station, nothing found", none.crossing == null, none.crossing == null ? "" : none.crossing.toString());
        MoonshotTest.Burst nb2 = MoonshotTest.shoot(sim, 180, 160, 23, 3);
        MoonStack.Result none2 = MoonStack.stack(nb2.frames, spec, opt, Progress.NONE);
        check("nor with clouds passing and another hand", none2.crossing == null, none2.crossing == null ? "" : none2.crossing.toString());
        System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
        if (failed > 0) System.exit(1);
    }
}
