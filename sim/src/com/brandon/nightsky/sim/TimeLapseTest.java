package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.TimeLapse;
import com.brandon.nightsky.processing.UnitRecord;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * The time lapse (2.5) on a simulated night with a satellite, a plane, a meteor and a passing cloud: every unit one moment
 * of the video in order, 1/30 s apart, a short night stretched to 8 seconds; the stars moving across the picture as the
 * sky turns (between the first and last pictures as far as the sky turned, and smoothly from one to the next); the sky's
 * grain lower with the units either side averaged in while the stars keep their light; star trails that grow; comet
 * tails behind the stars; and the caption drawn on every picture.
 * <p>
 * Usage: TimeLapseTest outDir [scale=3] [frames=48]. With ffmpeg each style is also written as an MP4 to look at.
 */
public final class TimeLapseTest {
    static int failed;

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "timelapse-out");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 48;
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
        System.out.println("== the time lapse: " + frames + " frames at 1/" + scale + " size, with a satellite, a plane, a meteor and a cloud");
        PhotoVideoTest.Night n = PhotoVideoTest.night(dir, scale, frames, true);
        PostProcessor pp = new PostProcessor(n.store, n.spec);
        PostProcessor.Output o = pp.run(false, PhotoVideoTest.QUIET);
        System.out.println("  " + o.summary);
        List<UnitRecord> units = n.store.readUnits();
        Renderer.Params p = new Renderer.Params();
        PhotoVideoTest.writePng(com.brandon.nightsky.processing.Master.render(o.image, o.analysis, p), o.analysis.cw, o.analysis.ch,
                new File(dir, "photo.png"));
        TimeLapse.Source src = TimeLapse.Source.of(n.spec, n.store.frames(n.W, n.H), o.video, units, o.analysis, p,
                SimRunner.DEFAULT_TIME_MS);
        check("every unit the phone held still in is in it", src.frames.size() == frames,
                src.frames.size() + " of " + frames + " units");
        check("a unit's step is the frames' (8 s and the gap)", Math.abs(src.secondsPerUnit() - 8.06) < 0.05,
                String.format(Locale.US, "%.2f s", src.secondsPerUnit()));

        // ---- the sky as it was, and the same without the neighbours' sky averaged in
        TimeLapse sky = TimeLapse.of(src, TimeLapse.Style.SKY, TimeLapse.MAX_SIDE);
        check("a time lapse for the night", sky != null, sky == null ? "none" : sky.width() + "x" + sky.height());
        if (sky == null) return;
        int total = sky.frameCount();
        int last = total - 1 - (int) Math.round(0.7 * TimeLapse.FPS);
        int mid = last / 2;
        int[] keep = {0, 1, 2, mid, last, total - 1};
        File sd = new File(dir, "sky");
        sd.mkdirs();
        final int[] captions = {0};
        TimeLapse.Overlay countCaptions = new TimeLapse.Overlay() {
            @Override
            public void draw(int[] argb, int w, int h, long wallMs, double minutes, double speedUp) {
                captions[0]++;
                // a white block bottom left, as the app's clock would be: checked on the pictures below
                for (int y = h - 12; y < h - 4; y++) for (int x = 4; x < 40; x++) argb[y * w + x] = 0xFFFFFFFF;
            }
        };
        PhotoVideoTest.Collector c = new PhotoVideoTest.Collector(sd, sky.width(), sky.height(), keep,
                PhotoVideoTest.haveFfmpeg() ? new File(sd, "sky.mp4") : null);
        long t0 = System.currentTimeMillis();
        sky.build(c, countCaptions, PhotoVideoTest.QUIET);
        c.close();
        System.out.println("  " + sky.note());
        System.out.printf(Locale.US, "  built in %d ms%n", System.currentTimeMillis() - t0);
        check("every picture, 1/30 s apart", c.pts.size() == total && ptsOk(c.pts), c.pts.size() + " of " + total);
        double wantS = Math.min(8.0, frames * 4.0 / TimeLapse.FPS);
        check("a short night stretched to 8 s, or to 4 pictures a unit if that's shorter",
                sky.durationMs() >= wantS * 1000 - 40 && total <= frames * 4 + 30,
                String.format(Locale.US, "%.1f s, %d pictures for %d units", sky.durationMs() / 1000.0, total, frames));
        check("a caption on every unit's picture", captions[0] == frames, captions[0] + " of " + frames);
        int[] f0 = c.get(0), fm = c.get(mid), fl = c.get(last);
        check("the caption is on the pictures", f0 != null && (f0[(sky.height() - 8) * sky.width() + 10] & 0xFFFFFF) == 0xFFFFFF, "");
        double speed = sky.speedUp();
        check("it says how much faster than the sky it plays", speed > 50 && speed < 300, String.format(Locale.US, "%.0f times", speed));

        // the stars move: as far as the sky turned between the first and last pictures, and little from one to the next
        int[] shiftAll = shift(f0, fl, sky.width(), sky.height(), 60);
        int[] shiftOne = shift(f0, c.get(1), sky.width(), sky.height(), 8);
        int[] shiftHalf = shift(f0, fm, sky.width(), sky.height(), 60);
        double all = Math.hypot(shiftAll[0], shiftAll[1]), half = Math.hypot(shiftHalf[0], shiftHalf[1]),
                one = Math.hypot(shiftOne[0], shiftOne[1]);
        // the sky turns 15 degrees an hour, cos(latitude) near the zenith: about 1.25 degrees in 6.4 minutes at 40 N
        double expect = Math.toRadians(15.04 / 3600 * src.secondsPerUnit() * (frames - 1)) * Math.cos(Math.toRadians(40))
                * (2875.0 / scale) / (n.W / (double) sky.width());
        check("the stars cross the picture as far as the sky turned", Math.abs(all - expect) < 0.35 * expect + 3,
                String.format(Locale.US, "%.0f px (%d, %d), about %.0f px expected", all, shiftAll[0], shiftAll[1], expect));
        check("half way, half as far", Math.abs(half - all / 2) < 0.3 * all + 2, String.format(Locale.US, "%.0f px", half));
        check("smoothly: under 2 px from one picture to the next", one <= 2, String.format(Locale.US, "%.1f px", one));

        // the sky's grain: lower with the units either side averaged in, the stars' light kept
        TimeLapse raw = TimeLapse.of(src, TimeLapse.Style.SKY, TimeLapse.MAX_SIDE).unfiltered();
        File rd = new File(dir, "raw");
        rd.mkdirs();
        // the first picture is the first unit itself (the others between two units are blends of them): with the next
        // unit's sky averaged in, about 1.4 times less grainy
        PhotoVideoTest.Collector cr = new PhotoVideoTest.Collector(rd, raw.width(), raw.height(), new int[]{0}, null);
        raw.build(cr, null, PhotoVideoTest.QUIET);
        cr.close();
        int[] r0 = cr.get(0);
        // each is shown with a look made for its own grain (the stretch's foot further out for a grainier picture), so
        // the filter shows as a picture that needs less of that: fainter stars brought out
        check("the sky's grain lower with the next unit's averaged in", sky.grain() < 0.85 * raw.grain(),
                String.format(Locale.US, "%.2f against %.2f times the photo's", sky.grain(), raw.grain()));
        double sf = starLight(f0, sky.width(), sky.height()), sr = starLight(r0, raw.width(), raw.height());
        check("the stars keep their light", sf > 0.85 * sr && sf < 1.15 * sr, String.format(Locale.US, "%.0f against %.0f", sf, sr));

        // ---- star trails growing
        TimeLapse trails = TimeLapse.of(src, TimeLapse.Style.TRAILS, TimeLapse.MAX_SIDE);
        File td = new File(dir, "trails");
        td.mkdirs();
        PhotoVideoTest.Collector ct = new PhotoVideoTest.Collector(td, trails.width(), trails.height(), new int[]{0, mid, last},
                PhotoVideoTest.haveFfmpeg() ? new File(td, "trails.mp4") : null);
        trails.build(ct, null, PhotoVideoTest.QUIET);
        ct.close();
        System.out.println("  " + trails.note());
        double l0 = brightArea(ct.get(0), trails.width(), trails.height()), lm = brightArea(ct.get(mid), trails.width(), trails.height()),
                ll = brightArea(ct.get(last), trails.width(), trails.height());
        check("the trails grow as the night goes on", lm > 1.5 * l0 && ll > 1.3 * lm,
                String.format(Locale.US, "bright pixels %.0f, %.0f, %.0f", l0, lm, ll));

        // ---- comet tails
        TimeLapse comet = TimeLapse.of(src, TimeLapse.Style.COMET, TimeLapse.MAX_SIDE);
        File cd = new File(dir, "comet");
        cd.mkdirs();
        PhotoVideoTest.Collector cc = new PhotoVideoTest.Collector(cd, comet.width(), comet.height(), new int[]{0, mid, last},
                PhotoVideoTest.haveFfmpeg() ? new File(cd, "comet.mp4") : null);
        comet.build(cc, null, PhotoVideoTest.QUIET);
        cc.close();
        System.out.println("  " + comet.note());
        double c0 = brightArea(cc.get(0), comet.width(), comet.height()), cm = brightArea(cc.get(mid), comet.width(), comet.height()),
                cl = brightArea(cc.get(last), comet.width(), comet.height());
        check("the stars grow tails", cm > 1.3 * c0, String.format(Locale.US, "bright pixels %.0f, then %.0f", c0, cm));
        check("the tails fade: shorter than the trails", cl < 0.9 * ll, String.format(Locale.US, "%.0f against the trails' %.0f", cl, ll));
        check("each style is its own memory size", trails.heapNeeded() > sky.heapNeeded() && comet.heapNeeded() > sky.heapNeeded()
                && sky.heapNeeded() < 200L << 20, (sky.heapNeeded() >> 20) + " MB, trails " + (trails.heapNeeded() >> 20) + " MB");

        // too few units: none
        TimeLapse.Source few = new TimeLapse.Source(src.spec, src.store, src.hot, src.frames.subList(0, TimeLapse.MIN_UNITS - 1),
                src.analysis, p, 0);
        check("fewer than " + TimeLapse.MIN_UNITS + " units make no time lapse", TimeLapse.of(few, TimeLapse.Style.SKY, TimeLapse.MAX_SIDE) == null, "");
    }

    static boolean ptsOk(List<Long> pts) {
        for (int i = 0; i < pts.size(); i++) {
            if (Math.abs(pts.get(i) - Math.round(i * 1e6 / TimeLapse.FPS)) > 1) return false;
        }
        return true;
    }

    static double luma(int v) {
        return 0.2126 * ((v >> 16) & 0xFF) + 0.7152 * ((v >> 8) & 0xFF) + 0.0722 * (v & 0xFF);
    }

    /**
     * How far (dx, dy) the stars in the middle of {@code a} moved in {@code b}: the shift (up to {@code r} px) that lines
     * up the two pictures' bright pixels best, over a patch in the middle.
     */
    static int[] shift(int[] a, int[] b, int w, int h, int r) {
        int pw = Math.min(w, h) / 2;
        int x0 = (w - pw) / 2, y0 = (h - pw) / 2;
        float[] la = new float[w * h], lb = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            la[i] = (float) luma(a[i]);
            lb[i] = (float) luma(b[i]);
        }
        float tha = percentile(la, 0.995f), thb = percentile(lb, 0.995f);
        // the bright pixels of a's middle, then the shift that puts the most of b's bright light on them
        int[] pts = new int[pw * pw];
        float[] val = new float[pw * pw];
        int m = 0;
        for (int y = y0; y < y0 + pw; y++) {
            for (int x = x0; x < x0 + pw; x++) {
                float va = la[y * w + x] - tha;
                if (va > 0) {
                    pts[m] = y * w + x;
                    val[m++] = va;
                }
            }
        }
        double best = -1;
        int bx = 0, by = 0;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                double s = 0;
                for (int k = 0; k < m; k++) {
                    int x = pts[k] % w + dx, y = pts[k] / w + dy;
                    if (x < 0 || y < 0 || x >= w || y >= h) continue;
                    float vb = lb[y * w + x] - thb;
                    if (vb > 0) s += val[k] * vb;
                }
                if (s > best) {
                    best = s;
                    bx = dx;
                    by = dy;
                }
            }
        }
        return new int[]{bx, by};
    }

    static float percentile(float[] v, float q) {
        float[] c = v.clone();
        java.util.Arrays.sort(c);
        return c[Math.min(c.length - 1, (int) (q * c.length))];
    }

    /** The sky's grain: the spread of differences of pixels two apart over the middle, the stars' pixels left out. */
    static double skyNoise(int[] px, int w, int h) {
        float[] l = new float[w * h];
        for (int i = 0; i < w * h; i++) l[i] = (float) luma(px[i]);
        float med = percentile(l, 0.5f);
        float[] d = new float[w * h / 2];
        int m = 0;
        for (int y = h / 4; y < 3 * h / 4; y += 2) {
            for (int x = w / 4; x + 2 < 3 * w / 4; x += 2) {
                float a = l[y * w + x], b = l[y * w + x + 2];
                if (a > med + 25 || b > med + 25) continue;
                d[m++] = b - a;
            }
        }
        float[] dd = java.util.Arrays.copyOf(d, m);
        for (int i = 0; i < m; i++) dd[i] = Math.abs(dd[i]);
        java.util.Arrays.sort(dd);
        return dd[m / 2] * 1.4826 / Math.sqrt(2);
    }

    /** The light of the brightest stars over the sky (the top 0.3% of pixels above the median). */
    static double starLight(int[] px, int w, int h) {
        float[] l = new float[w * h];
        for (int i = 0; i < w * h; i++) l[i] = (float) luma(px[i]);
        float med = percentile(l, 0.5f), th = percentile(l, 0.997f);
        double s = 0;
        for (float v : l) if (v >= th) s += v - med;
        return s;
    }

    /** Pixels well above the sky (stars and their trails). */
    static double brightArea(int[] px, int w, int h) {
        float[] l = new float[w * h];
        for (int i = 0; i < w * h; i++) l[i] = (float) luma(px[i]);
        float med = percentile(l, 0.5f);
        int k = 0;
        for (float v : l) if (v > med + 40) k++;
        return k;
    }
}
