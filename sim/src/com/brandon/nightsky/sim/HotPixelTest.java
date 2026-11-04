package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Aligner;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.HotPixels;
import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.UnitProcessor;
import com.brandon.nightsky.processing.UnitRecord;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Hot pixels in pairs and threes (1.9). Two defective photosites side by side each stand among the other's
 * surroundings, so the single-photosite test, which takes a raised neighbour for a star's light, left both, and they
 * stacked into a coloured blotch (every robo photo had a green one where the simulated sensor has such a pair).
 * <ul>
 * <li>On a simulated session's sensor-fixed average (20 frames; 4 for a short session, judged more strictly), with
 * single defects and pairs and threes of every shape planted at known places: every single and every member of every
 * cluster in open sky is found (most of those a star's light falls on too), and next to nothing else (stars' trails, a
 * star that barely moves), with the stars as sharp as the phone's and sharper.</li>
 * <li>Through developing: the photo has no coloured blotch where the clusters are, no more colour there than on the
 * sky anywhere else.</li>
 * </ul>
 * Usage: HotPixelTest DIR
 */
public final class HotPixelTest {
    private static int failures;

    static final int SCALE = 3;
    static final int W = 4080 / SCALE, H = 3072 / SCALE;

    /** Cluster shapes: offsets of the members from the first. */
    static final int[][][] SHAPES = {
            {{0, 0}, {2, 0}}, {{0, 0}, {0, 2}}, {{0, 0}, {2, 2}}, {{0, 0}, {2, -2}},
            {{0, 0}, {1, 0}}, {{0, 0}, {0, 1}}, {{0, 0}, {1, 1}}, {{0, 0}, {1, -1}},
            {{0, 0}, {1, 0}, {0, 1}}, {{0, 0}, {2, 0}, {4, 0}}, {{0, 0}, {1, 1}, {2, 2}}, {{0, 0}, {2, 0}, {1, 1}}};
    static final String[] SHAPE_NAMES = {"same-colour pair across", "same-colour pair down", "same-colour pair diagonal",
            "same-colour pair other diagonal", "adjacent pair across", "adjacent pair down", "adjacent pair diagonal",
            "adjacent pair other diagonal", "three in an L", "three in a row (same colour)", "three on a diagonal",
            "three in a triangle"};

    static final class Planted {
        final List<Integer> singles = new ArrayList<Integer>();
        final List<Float> singleRate = new ArrayList<Float>();
        final List<int[]> clusters = new ArrayList<int[]>();
        final List<Integer> shape = new ArrayList<Integer>();
        int[] idx;
        float[] rate;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "build/hotpixels");
        dir.mkdirs();
        detection(20, 8f, 0.85, 3);
        detection(4, 12f, 0.85, 3);
        // stars as sharp as the half-size simulations draw them: a few faint ones that hardly move in 4 frames pass
        detection(20, 8f, 0.6, 3);
        detection(4, 12f, 0.6, 12);
        photo(dir);
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    static CameraModel truth() {
        CameraModel t = CameraModel.nominal(W, H, 2875.0 / SCALE);
        t.cx = W / 2.0 + 14.0 / SCALE;
        t.cy = H / 2.0 - 9.0 / SCALE;
        t.k1 = -0.045;
        t.k2 = 0.018;
        t.source = "truth";
        return t;
    }

    /**
     * Singles, and clusters of every shape at weak and strong rates, on a grid well apart (and away from the edges), in
     * the sim's own range of dark rates (2 to 450 ADU/s: from a few times the noise of one frame to clipped).
     */
    static Planted plant(long seed, int singles) {
        Planted p = new Planted();
        Random r = new Random(seed);
        Set<Integer> used = new HashSet<Integer>();
        List<Integer> idx = new ArrayList<Integer>();
        List<Float> rate = new ArrayList<Float>();
        int step = 56, k = 0;
        for (int gy = 40; gy < H - 40; gy += step) {
            for (int gx = 40; gx < W - 40; gx += step) {
                int s = k % SHAPES.length;
                // weak, middling and strong clusters in turn
                int band = (k / SHAPES.length) % 3;
                k++;
                int x0 = gx + r.nextInt(9) - 4, y0 = gy + r.nextInt(9) - 4;
                int[] members = new int[SHAPES[s].length];
                for (int m = 0; m < members.length; m++) {
                    int x = x0 + SHAPES[s][m][0], y = y0 + SHAPES[s][m][1];
                    members[m] = y * W + x;
                    used.add(members[m]);
                    idx.add(members[m]);
                    float lo = band == 0 ? 8f : band == 1 ? 30f : 150f, hi = band == 0 ? 30f : band == 1 ? 150f : 450f;
                    rate.add(lo + (hi - lo) * r.nextFloat());
                }
                p.clusters.add(members);
                p.shape.add(s);
            }
        }
        // singles between the clusters, at least 6 px from any other defect
        while (p.singles.size() < singles) {
            int x = 20 + r.nextInt(W - 40), y = 20 + r.nextInt(H - 40);
            boolean clear = true;
            for (int dy = -6; dy <= 6 && clear; dy++) {
                for (int dx = -6; dx <= 6; dx++) {
                    if (used.contains((y + dy) * W + x + dx)) {
                        clear = false;
                        break;
                    }
                }
            }
            if (!clear) continue;
            int i = y * W + x;
            used.add(i);
            p.singles.add(i);
            idx.add(i);
            rate.add(r.nextFloat() < 0.1f ? 150f + 300f * r.nextFloat() : 2f + 40f * r.nextFloat());
            p.singleRate.add(rate.get(rate.size() - 1));
        }
        p.idx = new int[idx.size()];
        p.rate = new float[idx.size()];
        for (int i = 0; i < p.idx.length; i++) {
            p.idx[i] = idx.get(i);
            p.rate[i] = rate.get(i);
        }
        return p;
    }

    static SkySim sim(Planted p) {
        SkySim s = new SkySim(W, H, truth(), 42, null, SimRunner.DEFAULT_TIME_MS, SimRunner.DEFAULT_LON);
        s.psfSigma = 0.85 / Math.sqrt(SCALE);
        s.setHotPixels(p.idx, p.rate);
        return s;
    }

    // ------------------------------------------------------------------------------------------------

    /** The session's sensor-fixed average, the black level taken off, as the develop step makes it. */
    static float[] mean(SkySim s, int frames) {
        double[] sum = new double[W * H];
        short[] raw = new short[W * H];
        for (int i = 0; i < frames; i++) {
            UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
            s.render(i, raw, info, 7 + i);
            for (int j = 0; j < raw.length; j++) sum[j] += (raw[j] & 0xFFFF) - s.black;
        }
        float[] mean = new float[W * H];
        for (int j = 0; j < mean.length; j++) mean[j] = (float) (sum[j] / frames);
        return mean;
    }

    /** A photosite's excess over the median of its eight same-colour surroundings. */
    static float excess(float[] m, int j) {
        float[] v = {m[j - 2], m[j + 2], m[j - 2 * W], m[j + 2 * W], m[j - 2 * W - 2], m[j - 2 * W + 2], m[j + 2 * W - 2], m[j + 2 * W + 2]};
        Arrays.sort(v);
        return m[j] - 0.5f * (v[3] + v[4]);
    }

    /**
     * Where a star's light falls near the defect at {@code i}: the same session drawn without any defects, its brightest
     * photosite within two pixels of it raised over its surroundings by more than 5 times the noise.
     */
    static boolean starlit(float[] clean, float noise, int i) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                if (excess(clean, i + dy * W + dx) > 5 * noise) return true;
            }
        }
        return false;
    }

    /**
     * Singles and clusters planted on the sensor of a simulated session, found in its sensor-fixed average.
     *
     * @param psf      the stars' blur (sigma, pixels): 0.85 as the phone's own full-size frames draw them (the detector
     *                 works on those), smaller to try it on sharper stars than the phone's
     * @param maxExtra how many photosites may be taken for defects that aren't (a sharp star that hardly moves in a few
     *                 frames looks just like a cluster)
     */
    static void detection(int frames, float k, double psf, int maxExtra) {
        System.out.println("== " + frames + " frames: the sensor-fixed average, threshold " + k + " sigmas, stars blurred " + psf + " px");
        Planted p = plant(5, 600);
        SkySim s = sim(p);
        s.psfSigma = psf;
        SensorSpec spec = s.spec(true);
        float[] mean = mean(s, frames);
        // the same session without the defects: where stars' light falls on them
        SkySim c = sim(p);
        c.psfSigma = psf;
        c.setHotPixels(new int[0], new float[0]);
        float[] clean = mean(c, frames);
        float[] d = new float[4000];
        Random r = new Random(1);
        for (int q = 0; q < d.length; q++) d[q] = excess(clean, (10 + r.nextInt(H - 20)) * W + 10 + r.nextInt(W - 20));
        float[] ad = d.clone();
        Arrays.sort(ad);
        float med = ad[ad.length / 2];
        for (int q = 0; q < d.length; q++) d[q] = Math.abs(d[q] - med);
        Arrays.sort(d);
        float noise = 1.4826f * d[d.length / 2];
        long t0 = System.currentTimeMillis();
        HotPixels hp = HotPixels.detect(mean, W, H, spec.cfa, k);
        long ms = System.currentTimeMillis() - t0;
        int singlesFound = 0, singlesTotal = 0, singlesLit = 0, faint = 0, faintFound = 0;
        for (int q = 0; q < p.singles.size(); q++) {
            int i = p.singles.get(q);
            int x = i % W, y = i / W;
            if (x < 3 || y < 3 || x >= W - 3 || y >= H - 3) continue;
            if (starlit(clean, noise, i)) {
                singlesLit++;
                continue;
            }
            // the faintest are left on purpose (the threshold is there so that noise isn't taken for defects)
            if (p.singleRate.get(q) * s.frameExp < 1.5 * k * noise) {
                faint++;
                if (hp.isBad(x, y)) faintFound++;
                continue;
            }
            singlesTotal++;
            if (hp.isBad(x, y)) singlesFound++;
        }
        int[] found = new int[SHAPES.length], total = new int[SHAPES.length];
        int lit = 0, litFound = 0;
        List<String> missed = new ArrayList<String>();
        for (int q = 0; q < p.clusters.size(); q++) {
            int[] m = p.clusters.get(q);
            int s0 = p.shape.get(q);
            boolean all = true, under = false;
            for (int i : m) {
                all &= hp.isBad(i % W, i / W);
                under |= starlit(clean, noise, i);
            }
            if (under) {
                lit++;
                if (all) litFound++;
                continue;
            }
            total[s0]++;
            if (all) found[s0]++;
            else if (missed.size() < 8) missed.add(SHAPE_NAMES[s0] + " at " + (m[0] % W) + "," + (m[0] / W));
        }
        Set<Integer> planted = new HashSet<Integer>();
        for (int i : p.idx) planted.add(i);
        int extra = 0;
        StringBuilder where = new StringBuilder();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (hp.isBad(x, y) && !planted.contains(y * W + x)) {
                    if (extra < 6) where.append(' ').append(x).append(',').append(y);
                    extra++;
                }
            }
        }
        int clustersFound = 0, clustersTotal = 0;
        StringBuilder byShape = new StringBuilder();
        for (int s0 = 0; s0 < SHAPES.length; s0++) {
            clustersFound += found[s0];
            clustersTotal += total[s0];
            byShape.append(String.format(Locale.US, "%s%s %d/%d", s0 == 0 ? "" : ", ", SHAPE_NAMES[s0], found[s0], total[s0]));
        }
        System.out.printf(Locale.US, "  found %d (%d of them in clusters) in %d ms; in open sky: singles %d/%d, clusters %d/%d (%s)%n",
                hp.count(), hp.clustered(), ms, singlesFound, singlesTotal, clustersFound, clustersTotal, byShape);
        System.out.printf(Locale.US, "  under a star's light: %d singles, %d clusters (%d of them found); singles within 1.5 times the threshold: %d of %d found%n",
                singlesLit, lit, litFound, faintFound, faint);
        if (!missed.isEmpty()) System.out.println("  clusters missed in open sky: " + missed);
        System.out.println("  found where no defect was planted: " + extra + (extra > 0 ? " (" + where.toString().trim() + ")" : ""));
        check("every single defect in open sky found (but the faintest)", singlesFound >= singlesTotal - 1, singlesFound + " of " + singlesTotal);
        check("every member of every cluster in open sky found", clustersFound >= clustersTotal - 2, clustersFound + " of " + clustersTotal);
        for (int s0 = 0; s0 < SHAPES.length; s0++) {
            check("  " + SHAPE_NAMES[s0], found[s0] >= total[s0] - 1, found[s0] + " of " + total[s0]);
        }
        // a cluster under a star's light can't always be told from the star; most still are
        check("most of the clusters under a star's light found", litFound >= 0.5 * lit, litFound + " of " + lit);
        check("next to nothing taken for a defect that isn't one", extra <= maxExtra, extra + " photosites (" + maxExtra + " allowed)");
    }

    // ------------------------------------------------------------------------------------------------

    /** A short session developed: no coloured blotch where the strong clusters are. */
    static void photo(File dir) throws Exception {
        int frames = 12;
        System.out.println("== a " + frames + "-frame session developed, with the clusters on the sensor");
        Planted p = plant(9, 400);
        SkySim s = sim(p);
        SensorSpec spec = s.spec(true);
        ColorMath.compute(spec, ColorMath.DEFAULT_CCT);
        File root = new File(dir, "sessions");
        deleteTree(root);
        root.mkdirs();
        SessionStore store = SessionStore.create(root);
        UnitProcessor up = new UnitProcessor(store, spec, 1, 3, new UnitProcessor.Listener() {
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
            s.render(i, buf, info, 7);
            up.submit(buf, info);
        }
        up.finish();
        PostProcessor pp = new PostProcessor(store, spec);
        PostProcessor.Output o = pp.run(false, PhotoVideoTest.QUIET);
        System.out.println("  " + o.summary);
        check("the session developed", o.image != null && o.analysis != null, "");
        if (o.image == null || o.analysis == null) return;
        if (o.hot != null) System.out.println("  hot pixels: " + o.hot.count() + " (" + o.hot.clustered() + " in clusters)");
        Renderer.Params rp = new Renderer.Params();
        int[] px = Master.render(o.image, o.analysis, rp);
        int cw = o.analysis.cw, ch = o.analysis.ch, cx0 = o.analysis.cx0, cy0 = o.analysis.cy0;
        PhotoVideoTest.writePng(px, cw, ch, new File(dir, "photo.png"));
        float[][] chroma = smoothedChroma(px, cw, ch, 3);
        // the clusters bright enough to show (the middling and strong ones), against the sky anywhere else
        List<Float> at = new ArrayList<Float>();
        float worst = 0;
        String worstAt = "";
        List<int[]> spots = new ArrayList<int[]>();
        for (int c = 0; c < p.clusters.size(); c++) {
            int[] m = p.clusters.get(c);
            float minRate = Float.MAX_VALUE;
            for (int i : m) {
                for (int j = 0; j < p.idx.length; j++) if (p.idx[j] == i) minRate = Math.min(minRate, p.rate[j]);
            }
            int x = m[0] % W - cx0, y = m[0] / W - cy0;
            spots.add(new int[]{x, y});
            if (minRate < 30f || x < 70 || y < 70 || x >= cw - 70 || y >= ch - 70) continue;
            float b = blotch(chroma, cw, ch, x, y);
            at.add(b);
            if (b > worst) {
                worst = b;
                worstAt = (x + cx0) + "," + (y + cy0) + " (" + SHAPE_NAMES[p.shape.get(c)] + ")";
            }
        }
        // the sky between the clusters (they sit on a 56 px grid, so the middle of each square is 34 px or more from them),
        // where no cluster comes within the 30 px the measure looks over
        List<Float> control = new ArrayList<Float>();
        Random r = new Random(3);
        for (int tries = 0; control.size() < 400 && tries < 400000; tries++) {
            int x = 70 + r.nextInt(cw - 140), y = 70 + r.nextInt(ch - 140);
            boolean clear = true;
            for (int[] q : spots) {
                int dx = q[0] - x, dy = q[1] - y;
                if (dx * dx + dy * dy < 32 * 32) {
                    clear = false;
                    break;
                }
            }
            if (clear) control.add(blotch(chroma, cw, ch, x, y));
        }
        check("enough sky between the clusters to compare with", control.size() >= 100, control.size() + " places");
        if (control.size() < 100) return;
        float[] cs = toArray(control), as = toArray(at);
        Arrays.sort(cs);
        Arrays.sort(as);
        float c99 = cs[(int) (0.99 * (cs.length - 1))], cMax = cs[cs.length - 1];
        System.out.printf(Locale.US, "  colour blotch (display levels, the 7 px average's colour against the sky around): at %d clusters "
                        + "median %.1f, worst %.1f at %s; on the sky elsewhere median %.1f, 99th percentile %.1f, worst %.1f%n", as.length,
                as[as.length / 2], worst, worstAt, cs[cs.length / 2], c99, cMax);
        check("no coloured blotch where the clusters are", worst <= Math.max(cMax, 1.25f * c99), String.format(Locale.US,
                "worst %.1f against the sky's worst %.1f", worst, cMax));
        check("the clusters' colour no more than the sky's", as[as.length / 2] <= 1.5f * cs[cs.length / 2] + 1f, String.format(Locale.US,
                "median %.1f against %.1f", as[as.length / 2], cs[cs.length / 2]));
    }

    /** Per pixel, (R - G, B - G) of the display values averaged over a (2r + 1)-square. */
    static float[][] smoothedChroma(int[] px, int w, int h, int r) {
        float[] a = new float[w * h], b = new float[w * h];
        for (int i = 0; i < px.length; i++) {
            int v = px[i], R = (v >> 16) & 255, G = (v >> 8) & 255, B = v & 255;
            a[i] = R - G;
            b[i] = B - G;
        }
        return new float[][]{box(a, w, h, r), box(b, w, h, r)};
    }

    static float[] box(float[] in, int w, int h, int r) {
        float[] tmp = new float[w * h], out = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int n = 0;
                for (int k = Math.max(0, x - r); k <= Math.min(w - 1, x + r); k++, n++) s += in[y * w + k];
                tmp[y * w + x] = s / n;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int n = 0;
                for (int k = Math.max(0, y - r); k <= Math.min(h - 1, y + r); k++, n++) s += tmp[k * w + x];
                out[y * w + x] = s / n;
            }
        }
        return out;
    }

    /**
     * The strongest colour within 30 px of (x, y) (where a defect fixed on the sensor ends up in a stack lined up on the
     * stars) against the median colour of the ring 40 to 60 px out.
     */
    static float blotch(float[][] c, int w, int h, int x, int y) {
        List<Float> ra = new ArrayList<Float>(), rb = new ArrayList<Float>();
        for (int dy = -60; dy <= 60; dy += 3) {
            for (int dx = -60; dx <= 60; dx += 3) {
                int d2 = dx * dx + dy * dy;
                if (d2 < 40 * 40 || d2 > 60 * 60) continue;
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                ra.add(c[0][yy * w + xx]);
                rb.add(c[1][yy * w + xx]);
            }
        }
        float ma = median(ra), mb = median(rb), best = 0;
        for (int dy = -30; dy <= 30; dy++) {
            for (int dx = -30; dx <= 30; dx++) {
                if (dx * dx + dy * dy > 900) continue;
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                float da = c[0][yy * w + xx] - ma, db = c[1][yy * w + xx] - mb;
                best = Math.max(best, (float) Math.sqrt(da * da + db * db));
            }
        }
        return best;
    }

    static float median(List<Float> v) {
        float[] a = toArray(v);
        Arrays.sort(a);
        return a.length == 0 ? 0f : a[a.length / 2];
    }

    static float[] toArray(List<Float> v) {
        float[] a = new float[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + (detail.isEmpty() ? "" : ": " + detail));
        if (!ok) failures++;
    }
}
