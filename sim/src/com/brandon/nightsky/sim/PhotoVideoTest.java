package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Aligner;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.PhotoVideo;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.Rot;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.UnitProcessor;
import com.brandon.nightsky.processing.UnitRecord;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The photo's video (1.8) on simulated nights: the frames adding up (deep sky, with a satellite, a plane, a meteor, a
 * cloud and a partly cloudy frame) and the trails drawing themselves, rendered as the photo, ending on the photo.
 * <p>
 * Usage: PhotoVideoTest outDir [scale=2] [frames=24]. With ffmpeg on the path the videos are also written as MP4 (for
 * looking at; the app encodes its own).
 */
public final class PhotoVideoTest {
    static int failed;

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "photovideo-out");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 24;
        out.mkdirs();
        String only = System.getenv("PV_ONLY");
        if (only == null || only.equals("deep")) deep(new File(out, "deep"), scale, frames);
        if (only == null || only.equals("trails")) trails(new File(out, "trails"), scale, Math.max(8, frames * 2 / 3));
        System.out.println(failed == 0 ? "ALL PASS" : failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    // ------------------------------------------------------------------------------------------------

    static final class Night {
        SkySim sim;
        CameraModel truth;
        SensorSpec spec;
        SessionStore store;
        int W, H;
    }

    static Night night(File dir, int scale, int frames, boolean events) throws Exception {
        Night n = new Night();
        n.W = 4080 / scale;
        n.H = 3072 / scale;
        n.truth = CameraModel.nominal(n.W, n.H, 2875.0 / scale);
        n.truth.cx = n.W / 2.0 + 14.0 / scale;
        n.truth.cy = n.H / 2.0 - 9.0 / scale;
        n.truth.k1 = -0.045;
        n.truth.k2 = 0.018;
        n.truth.source = "truth";
        n.sim = new SkySim(n.W, n.H, n.truth, 42, null, SimRunner.DEFAULT_TIME_MS, SimRunner.DEFAULT_LON);
        n.sim.psfSigma = 0.85 / Math.sqrt(scale);
        if (events) {
            n.sim.satelliteFrame = frames / 4;
            n.sim.planeFrame = frames / 2;
            n.sim.meteorFrame = (frames * 2) / 3;
            n.sim.cloudFrom = frames / 3;
            n.sim.cloudTo = frames / 3 + 1;
            n.sim.partialFrom = (frames * 3) / 4;
            n.sim.partialTo = (frames * 3) / 4 + 1;
        }
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
        for (int i = 0; i < frames; i++) {
            short[] buf = up.obtainBuffer(60000);
            UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
            n.sim.render(i, buf, info, 7);
            up.submit(buf, info);
        }
        up.finish();
        return n;
    }

    static final Progress QUIET = new Progress() {
        long last;

        @Override
        public void update(String stage, float fraction) {
            long now = System.currentTimeMillis();
            if (now - last > 5000 || fraction >= 1f) {
                last = now;
                System.out.printf(Locale.US, "    %s %.0f%%%n", stage, fraction * 100);
            }
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    /** Keeps what the checks need of every frame, writes some as PNG and all to ffmpeg when there is one. */
    static final class Collector implements PhotoVideo.Sink {
        final int w, h;
        final File dir;
        final List<Long> pts = new ArrayList<Long>();
        final List<int[]> kept = new ArrayList<int[]>();
        final List<Integer> keptIndex = new ArrayList<Integer>();
        final int[] keep;
        Process ff;
        OutputStream ffIn;

        Collector(File dir, int w, int h, int[] keep, File mp4) {
            this.dir = dir;
            this.w = w;
            this.h = h;
            this.keep = keep;
            if (mp4 != null) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgra",
                            "-s", w + "x" + h, "-r", Integer.toString(PhotoVideo.FPS), "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                            "-crf", "18", mp4.getAbsolutePath());
                    pb.redirectErrorStream(true);
                    pb.redirectOutput(new File(dir, "ffmpeg.log"));
                    ff = pb.start();
                    ffIn = ff.getOutputStream();
                } catch (IOException e) {
                    ff = null;
                }
            }
        }

        @Override
        public void frame(int[] argb, long ptsUs) throws IOException {
            if (argb.length != w * h) throw new IOException("frame of " + argb.length + " pixels, not " + w + "x" + h);
            int i = pts.size();
            pts.add(ptsUs);
            for (int k : keep) {
                if (k == i || (k < 0 && i == -k)) {
                    kept.add(argb.clone());
                    keptIndex.add(i);
                    writePng(argb, w, h, new File(dir, String.format(Locale.US, "frame%03d.png", i)));
                }
            }
            if (ffIn != null) {
                byte[] b = new byte[w * h * 4];
                for (int p = 0; p < argb.length; p++) {
                    int v = argb[p];
                    b[4 * p] = (byte) v;
                    b[4 * p + 1] = (byte) (v >> 8);
                    b[4 * p + 2] = (byte) (v >> 16);
                    b[4 * p + 3] = (byte) 0xFF;
                }
                ffIn.write(b);
            }
        }

        int[] get(int index) {
            int k = keptIndex.indexOf(index);
            return k >= 0 ? kept.get(k) : null;
        }

        void close() throws Exception {
            if (ffIn != null) {
                ffIn.close();
                ff.waitFor();
            }
        }
    }

    static boolean haveFfmpeg() {
        if (System.getenv("PV_NOMP4") != null) return false;
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            java.io.InputStream in = p.getInputStream();
            byte[] b = new byte[4096];
            while (in.read(b) > 0) {
                // drained, so it can finish
            }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------

    static void deep(File dir, int scale, int frames) throws Exception {
        System.out.println("== deep sky: " + frames + " frames at 1/" + scale + " size, with a satellite, a plane, a meteor and clouds");
        dir.mkdirs();
        Night n = night(dir, scale, frames, true);
        PostProcessor pp = new PostProcessor(n.store, n.spec);
        // Keep meteors: the meteor kept in the photo, to see it come in with its frame (the plane is still taken out)
        pp.keepMeteors = true;
        PostProcessor.Output o = pp.run(false, QUIET);
        System.out.println("  " + o.summary);
        check("developing keeps what the video needs", o.video != null && !o.video.trails() && o.video.frames() == o.unitsUsed,
                o.video == null ? "none" : o.video.frames() + " frames of " + o.unitsUsed + " used");
        Renderer.Params p = new Renderer.Params();
        // the whole photo, as it is
        PhotoVideo v = PhotoVideo.of(o.video, o.image, o.analysis, p, "full", null, PhotoVideo.MAX_SIDE);
        check("a video for the photo", v != null, v == null ? "none" : v.width() + "x" + v.height());
        if (v == null) return;
        int build = (int) Math.round(2.4 * PhotoVideo.FPS);
        int total = v.frameCount();
        int[] keep = {0, 1, 4, 8, 16, 24, 36, 48, 60, build - 1, build + 7, total - 1};
        Collector c = new Collector(dir, v.width(), v.height(), keep, haveFfmpeg() ? new File(dir, "deep.mp4") : null);
        long t0 = System.currentTimeMillis();
        v.build(c, QUIET);
        long t1 = System.currentTimeMillis();
        int[] photo = Master.render(o.image, o.analysis, p);
        v.finish(photo, o.analysis.cw, o.analysis.ch, c);
        c.close();
        System.out.println("  " + v.note());
        System.out.printf(Locale.US, "  build %d ms, finish %d ms%n", t1 - t0, System.currentTimeMillis() - t1);
        check("every frame, 1/30 s apart", c.pts.size() == total && ptsOk(c.pts), c.pts.size() + " of " + total);
        check("the still is the last frame", v.stillUs() == c.pts.get(c.pts.size() - 1), v.stillUs() + " us");
        // the noise melts away: one frame, about 5, about 14, all of them
        double n0 = noise(c.get(0), c.w, c.h), n1 = noise(c.get(36), c.w, c.h), n2 = noise(c.get(60), c.w, c.h),
                n3 = noise(c.get(build - 1), c.w, c.h);
        check("the sky's noise falls as the frames add up", n0 > 1.5 * n1 && n1 > 1.3 * n2 && n2 > 1.05 * n3,
                String.format(Locale.US, "%.2f, %.2f, %.2f, %.2f levels", n0, n1, n2, n3));
        // ends on the photo
        int[] still = c.get(total - 1);
        int[] lastBuild = c.get(build - 1);
        double d = meanAbs(lastBuild, still);
        check("the frames added up look like the photo", d < 6, String.format(Locale.US, "mean difference %.2f levels", d));
        // stars where the photo has them, from the first frame on
        double[] off = starOffsets(c.get(0), still, c.w, c.h, 12);
        check("the first frame's stars are where the photo's are", off[0] < 0.6 && off[1] >= 6,
                String.format(Locale.US, "median offset %.2f px over %d stars", off[0], (int) off[1]));
        // the plane taken out as in the photo: not in any picture after its two frames (the sim's satellite crosses
        // at 10 degrees a second, so it's taken for a possible meteor and kept with Keep meteors, like the meteor)
        int pf = frames / 2;
        double con = Math.max(streakContrast(n, o, v, c, pf, 0.1, 0.9, 0.5, 0.55), streakContrast(n, o, v, c, pf + 1, 0.5, 0.55, 0.9, 0.2));
        check("the plane taken out of the frames adding up", con < 4, String.format(Locale.US, "contrast along its path %.2f levels", con));
        // the kept meteor: not in the first frame's picture, there from its frame on and in the photo
        int mf = (frames * 2) / 3;
        double before = lineContrast(n, o, v, c.get(0), c.w, mf, 0.55, 0.30, 0.62, 0.42);
        double after = lineContrast(n, o, v, c.get(build - 1), c.w, mf, 0.55, 0.30, 0.62, 0.42);
        double inPhoto = lineContrast(n, o, v, still, c.w, mf, 0.55, 0.30, 0.62, 0.42);
        check("a kept meteor comes in with its frame and stays", o.meteorsKept && before < 4 && after > 15 && Math.abs(after - inPhoto) < 0.35 * inPhoto,
                String.format(Locale.US, "contrast along it %.1f levels in the first picture, %.1f once its frame is in, %.1f in the photo", before,
                        after, inPhoto));
        // turned north up and cropped 16:9: the framing as the photo's, ending on the framed photo
        double[] north = {0.42, -0.9};
        PhotoVideo vf = PhotoVideo.of(o.video, o.image, o.analysis, p, "16:9", north, PhotoVideo.MAX_SIDE);
        File df = new File(dir, "framed");
        df.mkdirs();
        Collector cf = new Collector(df, vf.width(), vf.height(), new int[]{0, build - 1, vf.frameCount() - 1}, null);
        vf.build(cf, QUIET);
        com.brandon.nightsky.processing.Framing f = com.brandon.nightsky.processing.Framing.of("16:9", north, o.analysis.cw, o.analysis.ch);
        vf.finish(f.apply(photo), f.outW, f.outH, cf);
        double ratio = vf.width() / (double) vf.height();
        check("framed: 16:9 and turned, ending on the framed photo", Math.abs(ratio - 16 / 9.0) < 0.03
                        && meanAbs(cf.get(build - 1), cf.get(vf.frameCount() - 1)) < 7,
                String.format(Locale.US, "%dx%d, last picture %.2f levels from the photo", vf.width(), vf.height(),
                        meanAbs(cf.get(build - 1), cf.get(vf.frameCount() - 1))));
    }

    static void trails(File dir, int scale, int frames) throws Exception {
        System.out.println("== star trails: " + frames + " frames at 1/" + scale + " size");
        dir.mkdirs();
        Night n = night(dir, scale, frames, false);
        PostProcessor pp = new PostProcessor(n.store, n.spec);
        PostProcessor.Output o = pp.run(true, QUIET);
        System.out.println("  " + o.summary);
        check("star trails keep what the video needs", o.video != null && o.video.trails() && o.video.frames() == o.unitsUsed,
                o.video == null ? "none" : o.video.frames() + " frames");
        Renderer.Params p = new Renderer.Params();
        PhotoVideo v = PhotoVideo.of(o.video, o.image, o.analysis, p, "full", null, PhotoVideo.MAX_SIDE);
        if (v == null) {
            check("a video for the trails", false, "none");
            return;
        }
        int build = (int) Math.round(3.0 * PhotoVideo.FPS);
        int total = v.frameCount();
        int[] keep = {0, build / 4, build / 2, (3 * build) / 4, build - 1, total - 1};
        Collector c = new Collector(dir, v.width(), v.height(), keep, haveFfmpeg() ? new File(dir, "trails.mp4") : null);
        long t0 = System.currentTimeMillis();
        v.build(c, QUIET);
        long t1 = System.currentTimeMillis();
        int[] photo = Master.render(o.image, o.analysis, p);
        v.finish(photo, o.analysis.cw, o.analysis.ch, c);
        c.close();
        System.out.println("  " + v.note());
        System.out.printf(Locale.US, "  build %d ms, finish %d ms%n", t1 - t0, System.currentTimeMillis() - t1);
        check("every frame, 1/30 s apart", c.pts.size() == total && ptsOk(c.pts), c.pts.size() + " of " + total);
        // the trails grow: more of the picture lit above the sky as the frames come
        double[] lit = new double[keep.length - 1];
        for (int i = 0; i < lit.length; i++) lit[i] = litShare(c.get(keep[i]), c.w, c.h);
        boolean grows = true;
        for (int i = 1; i < lit.length; i++) grows &= lit[i] > lit[i - 1] * 1.05;
        check("the trails draw themselves", grows && lit[lit.length - 1] > 3 * lit[0], Arrays.toString(round(lit)));
        double d = meanAbs(c.get(build - 1), c.get(total - 1));
        check("the trails drawn look like the photo", d < 6, String.format(Locale.US, "mean difference %.2f levels", d));
    }

    // ------------------------------------------------------------------------------------------------

    static boolean ptsOk(List<Long> pts) {
        for (int i = 0; i < pts.size(); i++) {
            if (pts.get(i) != Math.round(i * 1e6 / PhotoVideo.FPS)) return false;
        }
        return true;
    }

    static double lum(int v) {
        return 0.299 * ((v >> 16) & 0xFF) + 0.587 * ((v >> 8) & 0xFF) + 0.114 * (v & 0xFF);
    }

    /** Robust noise of a picture in display levels: the spread of differences of pixels two apart. */
    static double noise(int[] px, int w, int h) {
        double[] d = new double[(w / 2) * (h / 4) + 8];
        int n = 0;
        for (int y = 0; y < h; y += 4) {
            for (int x = 0; x + 2 < w; x += 2) d[n++] = lum(px[y * w + x + 2]) - lum(px[y * w + x]);
        }
        double[] a = Arrays.copyOf(d, n);
        Arrays.sort(a);
        double med = a[n / 2];
        for (int i = 0; i < n; i++) a[i] = Math.abs(a[i] - med);
        Arrays.sort(a);
        return 1.4826 * a[n / 2] / Math.sqrt(2);
    }

    static double meanAbs(int[] a, int[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += Math.abs(lum(a[i]) - lum(b[i]));
        return s / a.length;
    }

    /** Share of the picture well above its median (the sky): trails and stars. */
    static double litShare(int[] px, int w, int h) {
        double[] l = new double[px.length];
        for (int i = 0; i < px.length; i++) l[i] = lum(px[i]);
        double[] s = l.clone();
        Arrays.sort(s);
        double med = s[s.length / 2];
        int lit = 0;
        for (double v : l) if (v > med + 40) lit++;
        return lit / (double) px.length;
    }

    static double[] round(double[] v) {
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) r[i] = Math.round(v[i] * 1e5) / 1e5;
        return r;
    }

    /**
     * The brightest stars of {@code ref} (local maxima well above the sky, away from the edges), and how far their
     * centroids in {@code px} are from those in {@code ref}: {median offset, stars measured}.
     */
    static double[] starOffsets(int[] px, int[] ref, int w, int h, int count) {
        List<int[]> peaks = new ArrayList<int[]>();
        double[] l = new double[ref.length];
        for (int i = 0; i < ref.length; i++) l[i] = lum(ref[i]);
        for (int y = 8; y < h - 8; y++) {
            for (int x = 8; x < w - 8; x++) {
                double v = l[y * w + x];
                if (v < 200) continue;
                boolean max = true;
                for (int dy = -3; dy <= 3 && max; dy++) for (int dx = -3; dx <= 3; dx++) if ((dx != 0 || dy != 0) && l[(y + dy) * w + x + dx] >= v) {
                    max = false;
                    break;
                }
                if (max) peaks.add(new int[]{x, y, (int) v});
            }
        }
        java.util.Collections.sort(peaks, new java.util.Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return b[2] - a[2];
            }
        });
        List<Double> offs = new ArrayList<Double>();
        for (int i = 0; i < Math.min(count, peaks.size()); i++) {
            int[] pk = peaks.get(i);
            double[] a = centroid(ref, w, pk[0], pk[1]), b = centroid(px, w, pk[0], pk[1]);
            if (a == null || b == null) continue;
            offs.add(Math.hypot(a[0] - b[0], a[1] - b[1]));
        }
        if (offs.isEmpty()) return new double[]{99, 0};
        java.util.Collections.sort(offs);
        return new double[]{offs.get(offs.size() / 2), offs.size()};
    }

    static double[] centroid(int[] px, int w, int cx, int cy) {
        double bg = 1e9;
        for (int dy = -5; dy <= 5; dy++) for (int dx = -5; dx <= 5; dx++) if (Math.abs(dx) == 5 || Math.abs(dy) == 5) bg = Math.min(bg, lum(px[(cy + dy) * w + cx + dx]));
        double s = 0, sx = 0, sy = 0;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                double v = Math.max(0, lum(px[(cy + dy) * w + cx + dx]) - bg);
                s += v;
                sx += v * dx;
                sy += v * dy;
            }
        }
        return s > 50 ? new double[]{cx + sx / s, cy + sy / s} : null;
    }

    /**
     * How much brighter the pictures kept are along a streak's path than beside it (display levels, the largest over
     * the pictures): the streak's line in its frame's own pixels, from (fx0 W, fy0 H) to (fx1 W, fy1 H), taken to the
     * stack's through that frame's rotation, then to the video's.
     */
    static double streakContrast(Night n, PostProcessor.Output o, PhotoVideo v, Collector c, int frame, double fx0, double fy0,
                                 double fx1, double fy1) {
        double worst = 0;
        for (int[] px : c.kept) worst = Math.max(worst, lineContrast(n, o, v, px, c.w, frame, fx0, fy0, fx1, fy1));
        return worst;
    }

    /**
     * How much brighter than beside it a picture of the video is along a straight line drawn in frame {@code frame}
     * from (fx0 W, fy0 H) to (fx1 W, fy1 H) (levels, the mean along it): where a plane's or a meteor's light was.
     */
    static double lineContrast(Night n, PostProcessor.Output o, PhotoVideo v, int[] px, int w, int frame, double fx0, double fy0,
                               double fx1, double fy1) {
        double[] R = null;
        for (PostProcessor.FrameReport f : o.frames) if (f.index == frame && f.R != null) R = f.R;
        if (R == null || px == null) return 0;
        // the grid's scale as the video makes it (the whole photo, no turn)
        double s = Math.min(1.0, PhotoVideo.MAX_SIDE / (double) Math.max(o.analysis.cw, o.analysis.ch));
        int gw = (int) Math.round(o.analysis.cw * s), gh = (int) Math.round(o.analysis.ch * s);
        double sxg = o.analysis.cw / (double) gw, syg = o.analysis.ch / (double) gh;
        int cutX = (gw - v.width()) / 2, cutY = (gh - v.height()) / 2;
        List<double[]> path = new ArrayList<double[]>();
        double[] b = new double[3], r = new double[3], q = new double[2];
        double x0 = n.W * fx0, y0 = n.H * fy0, x1 = n.W * fx1, y1 = n.H * fy1;
        double[] Rt = Rot.transpose(R);
        for (int i = 0; i <= 400; i++) {
            double t = i / 400.0;
            o.model.unproject(x0 + t * (x1 - x0), y0 + t * (y1 - y0), b);
            Rot.apply(Rt, b[0], b[1], b[2], r);
            if (!o.model.project(r[0], r[1], r[2], q)) continue;
            double vx = (q[0] - o.analysis.cx0) / sxg - 0.5 - cutX, vy = (q[1] - o.analysis.cy0) / syg - 0.5 - cutY;
            if (vx >= 8 && vy >= 8 && vx < v.width() - 8 && vy < v.height() - 8) path.add(new double[]{vx, vy});
        }
        if (path.size() < 50) return 0;
        double ux = x1 - x0, uy = y1 - y0, ul = Math.hypot(ux, uy);
        double nxp = -uy / ul, nyp = ux / ul;
        double on = 0, beside = 0;
        for (double[] pt : path) {
            on += lum(px[(int) Math.round(pt[1]) * w + (int) Math.round(pt[0])]);
            beside += 0.5 * (lum(px[(int) Math.round(pt[1] + 5 * nyp) * w + (int) Math.round(pt[0] + 5 * nxp)])
                    + lum(px[(int) Math.round(pt[1] - 5 * nyp) * w + (int) Math.round(pt[0] - 5 * nxp)]));
        }
        return (on - beside) / path.size();
    }

    static void writePng(int[] argb, int w, int h, File f) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        ImageIO.write(img, "png", f);
    }
}
