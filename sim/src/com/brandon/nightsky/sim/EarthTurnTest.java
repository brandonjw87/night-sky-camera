package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.AnimatedWebp;
import com.brandon.nightsky.processing.EarthTurn;
import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.PhotoVideo;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.Rot;
import com.brandon.nightsky.processing.SharpStacker;
import com.brandon.nightsky.processing.SkyTurn;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The Earth's turn (2.2) on a simulated night: the turn the frames measured (its axis against the simulator's pole,
 * its rate against the Earth's, the latitude it gives), and the clip: every picture where the stars truly were at its
 * moment, the pole where the simulator's is, arcs drawn, the loop closing on its first picture, and the clip's sizes
 * as an MP4, an animated WebP (made here from still WebPs, as the phone makes it) and a GIF.
 * <p>
 * Usage: EarthTurnTest outDir [scale=3] [frames=40]. The sizes and the WebP's check need ffmpeg and python3 with
 * Pillow; without them those checks are skipped.
 */
public final class EarthTurnTest {
    static int failed;

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "earthturn-out");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 40;
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
        System.out.println("== the Earth's turn: " + frames + " frames at 1/" + scale + " size");
        PhotoVideoTest.Night n = PhotoVideoTest.night(dir, scale, frames, false);
        PostProcessor pp = new PostProcessor(n.store, n.spec);
        PostProcessor.Output o = pp.run(false, PhotoVideoTest.QUIET);
        System.out.println("  " + o.summary);
        List<SharpStacker.Plan> plans = o.video != null ? o.video.plans() : null;
        check("developing keeps the frames' turns", plans != null && plans.size() == o.unitsUsed && o.unitsUsed >= frames - 1,
                plans == null ? "none" : plans.size() + " of " + o.unitsUsed);
        if (plans == null) return;

        // --- the turn ---------------------------------------------------------------------------------------------
        SkyTurn turn = SkyTurn.fit(plans);
        check("a turn is measured", turn != null, "");
        if (turn == null) return;
        System.out.println("  " + turn.describe());
        System.out.println("  " + turn.words());
        // the truth: the simulator's rotation from the reference frame to the last frame, whose vector is axis x angle
        int ref = o.referenceUnit, last = frames - 1;
        double[] rv = Rot.toRotVec(n.sim.trueRotation(ref, last));
        double rl = Math.sqrt(rv[0] * rv[0] + rv[1] * rv[1] + rv[2] * rv[2]);
        double sgn = (n.sim.frameStart(last) > n.sim.frameStart(ref)) ? 1 : -1;
        double[] trueAxis = {sgn * rv[0] / rl, sgn * rv[1] / rl, sgn * rv[2] / rl};
        double axErr = Math.toDegrees(Math.acos(Math.min(1, Math.abs(dot(trueAxis, turn.axis)))));
        check("the axis is the simulator's celestial pole", axErr < 0.5 && dot(trueAxis, turn.axis) > 0,
                String.format(Locale.US, "%.3f deg off", axErr));
        double ratio = turn.rateRatio();
        check("the rate is the Earth's", Math.abs(ratio - 1) < 0.004, String.format(Locale.US, "%.5f of it, %.4f deg an hour", ratio,
                turn.degPerHour()));
        double day = turn.siderealDaySeconds();
        check("a day by the stars of 23 h 56 min", Math.abs(day - 86164.1) < 6 * 60, SkyTurn.hms(day));
        double lat = turn.latitudeDeg();
        check("the latitude, within the phone's tilt", Math.abs(lat - n.sim.latitudeDeg) < n.sim.tiltDeg + 0.7,
                String.format(Locale.US, "%.2f deg (the simulator's %.1f, the phone tilted %.1f)", lat, n.sim.latitudeDeg, n.sim.tiltDeg));
        check("the frames sit on one steady turn", Math.toDegrees(turn.rmsAngle) * 3600 < 8,
                String.format(Locale.US, "%.2f arcsec rms", Math.toDegrees(turn.rmsAngle) * 3600));
        check("too little to measure: none", SkyTurn.fit(plans.subList(0, 2)) == null, "");

        // --- the clip ---------------------------------------------------------------------------------------------
        Renderer.Params p = new Renderer.Params();
        int[] photo = Master.render(o.image, o.analysis, p);
        int cw = o.analysis.cw, ch = o.analysis.ch;
        EarthTurn et = EarthTurn.of(photo, cw, ch, o.analysis.cx0, o.analysis.cy0, 1.0, o.model, turn, null, EarthTurn.MAX_SIDE);
        check("a clip for the photo", et != null, et == null ? "none" : et.width() + "x" + et.height());
        if (et == null) return;
        int W = et.width(), H = et.height();
        check("its size a multiple of 16, most of the photo", W % 16 == 0 && H % 16 == 0 && W >= 0.8 * Math.min(cw, EarthTurn.MAX_SIDE),
                W + "x" + H + " of a " + cw + "x" + ch + " photo");
        int turnFrames = (int) Math.round(4.0 * EarthTurn.FPS);
        int total = et.frameCount();
        Keeper all = new Keeper(W, H, new int[]{0, turnFrames / 2, turnFrames - 1, turnFrames + 2, total - 1}, dir);
        long t0 = System.currentTimeMillis();
        et.build(all, null, PhotoVideoTest.QUIET);
        long ms = System.currentTimeMillis() - t0;
        System.out.println("  " + et.note());
        check("every picture, 1/30 s apart", all.count == total && all.ptsOk, all.count + " of " + total);
        // without the arcs, so the stars' own centroids can be measured where the simulator put them
        EarthTurn bare = EarthTurn.of(photo, cw, ch, o.analysis.cx0, o.analysis.cy0, 1.0, o.model, turn, null, EarthTurn.MAX_SIDE);
        bare.arcLight = 0f;
        Keeper k = new Keeper(W, H, new int[]{0, turnFrames / 2, turnFrames - 1}, null);
        bare.build(k, null, null);
        double[] s0 = offsets(bare, n, k.get(0), 0, W, H), s1 = offsets(bare, n, k.get(turnFrames - 1), last, W, H);
        int mid = Math.round((frames - 1) / 2f);
        // the middle picture is half-way through the session: the frame taken then
        double[] sm = offsets(bare, n, k.get(turnFrames / 2), mid, W, H, (turnFrames / 2) / (double) (turnFrames - 1), turn, frames);
        check("the first picture's stars where the first frame had them", s0[0] < 0.5 && s0[1] >= 15,
                String.format(Locale.US, "median %.2f px over %d stars", s0[0], (int) s0[1]));
        check("the last picture's stars where the last frame had them", s1[0] < 0.5 && s1[1] >= 15,
                String.format(Locale.US, "median %.2f px over %d stars", s1[0], (int) s1[1]));
        check("half-way, where they were half-way through", sm[0] < 0.6 && sm[1] >= 15,
                String.format(Locale.US, "median %.2f px over %d stars", sm[0], (int) sm[1]));
        double moved = travel(bare, n, 0, last);
        check("the stars travel across the clip", moved > 3, String.format(Locale.US, "%.1f clip px on average", moved));
        // the pole where the simulator's is
        double[] pole = et.poleInClip();
        double[] truePole = truePoleInClip(et, n, ref);
        check("the pole marked where the simulator's is", !Double.isNaN(pole[0]) && Math.hypot(pole[0] - truePole[0], pole[1] - truePole[1])
                        < 0.004 * Math.hypot(W, H) + 3, String.format(Locale.US, "at %.1f, %.1f; the simulator's %.1f, %.1f", pole[0], pole[1],
                truePole[0], truePole[1]));
        // arcs: with them the last picture has more light above the sky than without
        double arcs = light(all.get(turnFrames - 1)), none = light(k.get(turnFrames - 1)), first = light(all.get(0)), bare0 = light(k.get(0));
        check("the arcs build up as it plays", et.arcs() >= 20 && arcs > none * 1.01 && Math.abs(first - bare0) < 0.002 * bare0,
                String.format(Locale.US, "%d stars; light above the sky %.0f with the arcs at the end, %.0f without; %.0f and %.0f at the start",
                        et.arcs(), arcs, none, first, bare0));
        // the loop: the last picture fades into the first
        double loop = PhotoVideoTest.meanAbs(all.get(total - 1), all.get(0)), jump = PhotoVideoTest.meanAbs(all.get(turnFrames - 1), all.get(0));
        check("the loop closes on the first picture", loop < 0.25 * jump + 0.5, String.format(Locale.US, "the last picture %.2f levels from "
                + "the first (the end of the turn %.2f)", loop, jump));
        check("quick enough", ms < 60000, ms + " ms for " + total + " pictures");
        System.out.printf(Locale.US, "  memory: about %d MB beyond the photo%n", et.heapNeeded() >> 20);

        // --- what it weighs: MP4, animated WebP, GIF ----------------------------------------------------------------
        if (!PhotoVideoTest.haveFfmpeg()) {
            System.out.println("  (no ffmpeg: the sizes aren't measured)");
            return;
        }
        File frameDir = new File(dir, "frames");
        frameDir.mkdirs();
        // the phone's MP4: H.264 at its bit rate (Mp4Writer: about 0.12 bits a pixel at 30 fps, floor 2 Mb/s)
        double bps = EarthTurn.bitRate(W, H);
        File mp4 = new File(dir, "earth.mp4"), gif = new File(dir, "earth.gif"), webp = new File(dir, "earth.webp");
        ffmpegOut(all.raw, W, H, EarthTurn.FPS, Arrays.asList("-c:v", "libx264", "-pix_fmt", "yuv420p", "-b:v", Long.toString((long) bps),
                "-movflags", "+faststart"), mp4);
        // the animated picture as the phone makes it: smaller, every other picture, each a still WebP
        int sh = EarthTurn.shareFactor(W, H), hw = W / sh, hh = H / sh;
        AnimatedWebp aw = new AnimatedWebp(hw, hh);
        List<int[]> halves = new ArrayList<int[]>();
        for (int i = 0; i < all.frames.size(); i += EarthTurn.SHARE_EVERY) {
            int[] half = EarthTurn.shrink(all.frames.get(i), W, H, sh);
            halves.add(half);
            byte[] still = stillWebp(half, hw, hh, EarthTurn.SHARE_QUALITY, frameDir);
            aw.add(still, EarthTurn.SHARE_EVERY * 1000 / EarthTurn.FPS);
        }
        FileOutputStream fo = new FileOutputStream(webp);
        try {
            aw.writeTo(fo);
        } finally {
            fo.close();
        }
        // the same as a GIF, the classic way (its own palette, the same size and pace)
        ffmpegOut(halves, hw, hh, EarthTurn.FPS / EarthTurn.SHARE_EVERY, Arrays.asList("-vf", "split[a][b];[a]palettegen=stats_mode=full[p];[b][p]paletteuse",
                "-loop", "0"), gif);
        System.out.printf(Locale.US, "  sizes: MP4 %dx%d %d KB; animated WebP %dx%d %d KB (%d frames); GIF %dx%d %d KB%n", W, H, mp4.length() >> 10,
                hw, hh, webp.length() >> 10, aw.frames(), hw, hh, gif.length() >> 10);
        check("the MP4 is the smallest", mp4.length() > 0 && mp4.length() < webp.length() && mp4.length() < gif.length() / 3,
                String.format(Locale.US, "%d KB vs WebP %d KB, GIF %d KB", mp4.length() >> 10, webp.length() >> 10, gif.length() >> 10));
        check("the animated WebP is a fraction of the GIF", webp.length() > 0 && webp.length() < gif.length() / 2,
                String.format(Locale.US, "%.1f times smaller", gif.length() / (double) Math.max(1, webp.length())));
        String info = python("from PIL import Image; im=Image.open('" + webp.getAbsolutePath() + "'); print(im.format, im.size[0], im.size[1], "
                + "im.n_frames, im.info.get('loop'), im.info.get('duration')); im.seek(im.n_frames-1); im.convert('RGB').save('"
                + new File(dir, "webp-last.png").getAbsolutePath() + "')");
        if (info == null) {
            System.out.println("  (no python3 with Pillow: the WebP isn't read back)");
        } else {
            String[] f = info.trim().split("\\s+");
            boolean ok = f.length >= 6 && f[0].equals("WEBP") && f[1].equals(Integer.toString(hw)) && f[2].equals(Integer.toString(hh))
                    && f[3].equals(Integer.toString(aw.frames())) && f[4].equals("0");
            check("the animated WebP reads back: every frame, looping for ever", ok, info.trim());
        }
    }

    /** The picture's light above its sky (linear, summed). */
    static double light(int[] px) {
        double[] l = new double[px.length];
        for (int i = 0; i < px.length; i++) l[i] = lin(px[i]);
        double[] s = l.clone();
        Arrays.sort(s);
        double med = s[s.length / 2], sum = 0;
        for (double v : l) sum += Math.max(0, v - med);
        return sum;
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** The simulator's north pole (in the reference frame's camera) in clip pixels. */
    static double[] truePoleInClip(EarthTurn et, PhotoVideoTest.Night n, int ref) {
        // a fixed point of every true rotation from the reference: the axis; take the visible end (z > 0)
        double[] rv = Rot.toRotVec(n.sim.trueRotation(ref, Math.max(ref + 5, 0)));
        double l = Math.sqrt(rv[0] * rv[0] + rv[1] * rv[1] + rv[2] * rv[2]);
        double[] a = {rv[0] / l, rv[1] / l, rv[2] / l};
        if (a[2] < 0) a = new double[]{-a[0], -a[1], -a[2]};
        double[] p = new double[2];
        n.truth.project(a[0], a[1], a[2], p);
        double[] c0 = et.sensorAt(0, 0), c1 = et.sensorAt(1, 0);
        double k = c1[0] - c0[0];
        return new double[]{(p[0] - c0[0]) / k, (p[1] - c0[1]) / k};
    }

    static double[] offsets(EarthTurn et, PhotoVideoTest.Night n, int[] px, int frame, int W, int H) {
        return offsets(et, n, px, frame, W, H, Double.NaN, null, 0);
    }

    /**
     * The brightest true stars of a frame (or, with {@code u}, of the moment that far through the session, from the
     * frame then), and how far the picture's centroids are from them: {median, stars measured}.
     */
    static double[] offsets(EarthTurn et, PhotoVideoTest.Night n, int[] px, int frame, int W, int H, double u, SkyTurn turn, int frames) {
        double[][] st = n.sim.starTable(frame);
        double[] c0 = et.sensorAt(0, 0), c1 = et.sensorAt(1, 0);
        double k = c1[0] - c0[0];
        double shiftX = 0, shiftY = 0;
        Integer[] idx = new Integer[st.length];
        for (int i = 0; i < st.length; i++) idx[i] = i;
        final double[][] s = st;
        Arrays.sort(idx, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(s[b][2], s[a][2]);
            }
        });
        List<Double> offs = new ArrayList<Double>();
        for (int q = 0; q < idx.length && offs.size() < 40; q++) {
            double[] r = st[idx[q]];
            double x = (r[0] - c0[0]) / k, y = (r[1] - c0[1]) / k;
            if (!Double.isNaN(u)) {
                // the frame's star moved on (or back) to the picture's moment along the true turn: small, but measured
                double tf = n.sim.frameStart(frame), tu = u * (n.sim.frameStart(frames - 1) - n.sim.frameStart(0));
                double dtFrames = (tu - tf) / (n.sim.frameExp + n.sim.gap);
                if (Math.abs(dtFrames) > 0.6) continue;
            }
            if (x < 8 || y < 8 || x >= W - 8 || y >= H - 8) continue;
            double[] c = centroid(px, W, (int) Math.round(x - 0.5), (int) Math.round(y - 0.5));
            if (c == null) continue;
            // isolated stars only: no brighter neighbour within 6 px
            boolean alone = true;
            for (int z = 0; z < q; z++) {
                double[] o = st[idx[z]];
                if (Math.hypot((o[0] - r[0]) / k, (o[1] - r[1]) / k) < 6) {
                    alone = false;
                    break;
                }
            }
            if (!alone) continue;
            offs.add(Math.hypot(c[0] - (x - 0.5), c[1] - (y - 0.5)));
        }
        if (offs.isEmpty()) return new double[]{99, 0};
        java.util.Collections.sort(offs);
        return new double[]{offs.get(offs.size() / 2), offs.size()};
    }

    /** How far the brightest true stars moved from one frame to another, in clip pixels. */
    static double travel(EarthTurn et, PhotoVideoTest.Night n, int a, int b) {
        double[][] sa = n.sim.starTable(a), sb = n.sim.starTable(b);
        double[] c0 = et.sensorAt(0, 0), c1 = et.sensorAt(1, 0);
        double k = c1[0] - c0[0];
        double sum = 0;
        int m = 0;
        for (double[] p : sa) {
            double best = 1e9;
            for (double[] q : sb) if (Math.abs(q[2] - p[2]) < 1e-6 * p[2]) best = Math.min(best, Math.hypot(q[0] - p[0], q[1] - p[1]));
            if (best < 1e8) {
                sum += best;
                m++;
            }
            if (m >= 50) break;
        }
        return m > 0 ? sum / m / k : 0;
    }

    /** Centroid around (cx, cy), pixel centres at integers, above the ring's lowest; null at the edge. */
    static double[] centroid(int[] px, int w, int cx, int cy) {
        int h = px.length / w;
        if (cx < 5 || cy < 5 || cx >= w - 5 || cy >= h - 5) return null;
        // the brightest pixel within 2 first
        int bx = cx, by = cy;
        double bv = -1;
        for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
            double v = lin(px[(cy + dy) * w + cx + dx]);
            if (v > bv) {
                bv = v;
                bx = cx + dx;
                by = cy + dy;
            }
        }
        if (bx < 5 || by < 5 || bx >= w - 5 || by >= h - 5) return null;
        double bg = 1e9;
        for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) if (Math.abs(dx) == 4 || Math.abs(dy) == 4) bg = Math.min(bg, lin(px[(by + dy) * w + bx + dx]));
        double s = 0, sx = 0, sy = 0;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                double v = Math.max(0, lin(px[(by + dy) * w + bx + dx]) - bg);
                s += v;
                sx += v * (bx + dx);
                sy += v * (by + dy);
            }
        }
        if (s <= 0.02) return null;
        return new double[]{sx / s, sy / s};
    }

    static double lin(int v) {
        double l = PhotoVideoTest.lum(v) / 255.0;
        return l <= 0.04045 ? l / 12.92 : Math.pow((l + 0.055) / 1.055, 2.4);
    }

    /** Keeps some pictures (and, for the sizes, all of them). */
    static final class Keeper implements PhotoVideo.Sink {
        final int w, h;
        final int[] keep;
        final File dir;
        final List<int[]> frames = new ArrayList<int[]>();
        final List<int[]> raw = frames;
        int count;
        boolean ptsOk = true;

        Keeper(int w, int h, int[] keep, File dir) {
            this.w = w;
            this.h = h;
            this.keep = keep;
            this.dir = dir;
        }

        @Override
        public void frame(int[] argb, long ptsUs) throws IOException {
            if (ptsUs != Math.round(count * 1e6 / EarthTurn.FPS)) ptsOk = false;
            if (argb.length != w * h) throw new IOException("a picture of " + argb.length + " pixels");
            frames.add(argb.clone());
            if (dir != null) {
                for (int k : keep) if (k == count) PhotoVideoTest.writePng(argb, w, h, new File(dir, String.format(Locale.US, "earth%03d.png", count)));
            }
            count++;
        }

        int[] get(int i) {
            return frames.get(i);
        }
    }

    static void ffmpegOut(List<int[]> frames, int w, int h, int fps, List<String> args, File out) throws Exception {
        List<String> cmd = new ArrayList<String>(Arrays.asList("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgra", "-s",
                w + "x" + h, "-r", Integer.toString(fps), "-i", "-"));
        cmd.addAll(args);
        cmd.add(out.getAbsolutePath());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(new File(out.getPath() + ".log")).start();
        OutputStream os = p.getOutputStream();
        byte[] b = new byte[w * h * 4];
        for (int[] f : frames) {
            for (int i = 0; i < f.length; i++) {
                int v = f[i];
                b[4 * i] = (byte) v;
                b[4 * i + 1] = (byte) (v >> 8);
                b[4 * i + 2] = (byte) (v >> 16);
                b[4 * i + 3] = (byte) 0xFF;
            }
            os.write(b);
        }
        os.close();
        p.waitFor();
    }

    /** A still WebP of one picture (ffmpeg's libwebp standing in for the phone's own encoder). */
    static byte[] stillWebp(int[] argb, int w, int h, int quality, File tmpDir) throws Exception {
        File f = new File(tmpDir, "still.webp");
        List<int[]> one = new ArrayList<int[]>();
        one.add(argb);
        ffmpegOut(one, w, h, 1, Arrays.asList("-c:v", "libwebp", "-lossless", "0", "-quality", Integer.toString(quality), "-frames:v", "1"), f);
        InputStream in = new java.io.FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[1 << 16];
            int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    static String python(String code) {
        try {
            Process p = new ProcessBuilder("python3", "-c", code).redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            int code0 = p.waitFor();
            return code0 == 0 ? bo.toString("UTF-8") : null;
        } catch (Exception e) {
            return null;
        }
    }
}
