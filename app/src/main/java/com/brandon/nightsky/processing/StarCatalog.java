package com.brandon.nightsky.processing;

import java.util.Arrays;
import java.util.List;

/**
 * The session's stars in reference-frame coordinates: every aligned frame's detections rotated back onto the
 * reference frame and grouped, keeping the ones seen in enough frames. A star too faint for the reference
 * frame alone shows up in a good share of the frames, while noise doesn't repeat, so this is the list a stack
 * of the session would give, at the cost of a few thousand points. Matching against it finds more stars in
 * sparse and partly cloudy frames, and its averaged positions are steadier than one frame's: its noise is a
 * frame's over the square root of the frames a star is typically seen in, so the alignment weighs its
 * positions accordingly.
 */
public final class StarCatalog {
    private StarCatalog() {}

    /**
     * @param lists     star lists of the frames
     * @param rotations rotation taking reference bearings to each frame's bearings, null where not aligned
     * @param minFrames a star must be seen in at least this many frames
     * @param joinPx    detections of different frames within this many pixels are the same star (allow for
     *                  centroid noise and the alignment's own error)
     * @return the catalog, brightest first, in reference-frame pixel coordinates
     */
    public static StarList build(CameraModel model, List<StarList> lists, List<double[]> rotations, int width,
                                 int height, int minFrames, float joinPx) {
        int total = 0, frames = 0, nn = 0;
        float[] noise = new float[lists.size()];
        for (int f = 0; f < lists.size(); f++) {
            if (lists.get(f) == null || rotations.get(f) == null) continue;
            total += lists.get(f).count;
            if (lists.get(f).noise > 0) noise[nn++] = lists.get(f).noise;
            frames++;
        }
        StarList out = new StarList(64);
        if (total == 0 || frames == 0) return out;
        float frameNoise = nn > 0 ? Stats.median(noise, nn) : 0f;
        float[] groups = new float[Math.max(1, total)];
        int ng = 0;
        float[] xs = new float[total], ys = new float[total], fl = new float[total], hf = new float[total], el = new float[total];
        boolean[] sat = new boolean[total];
        int[] frame = new int[total];
        double[] b = new double[3], p = new double[2];
        int k = 0;
        for (int f = 0; f < lists.size(); f++) {
            StarList s = lists.get(f);
            double[] R = rotations.get(f);
            if (s == null || R == null) continue;
            for (int i = 0; i < s.count; i++) {
                model.unproject(s.x[i], s.y[i], b);
                // reference bearing = R^T * frame bearing
                double bx = R[0] * b[0] + R[3] * b[1] + R[6] * b[2];
                double by = R[1] * b[0] + R[4] * b[1] + R[7] * b[2];
                double bz = R[2] * b[0] + R[5] * b[1] + R[8] * b[2];
                if (!model.project(bx, by, bz, p)) continue;
                if (p[0] < 0 || p[1] < 0 || p[0] >= width || p[1] >= height) continue;
                xs[k] = (float) p[0];
                ys[k] = (float) p[1];
                fl[k] = s.flux[i];
                hf[k] = s.hfr[i];
                el[k] = s.elong[i];
                sat[k] = s.saturated[i];
                frame[k] = f;
                k++;
            }
        }
        int n = k;
        Aligner.Grid grid = new Aligner.Grid(xs, ys, n, width, height, 4f);
        // brightest first, so each group forms around a well-measured detection
        long[] order = new long[n];
        for (int i = 0; i < n; i++) order[i] = ((long) Float.floatToIntBits(Math.max(0f, fl[i])) << 32) | i;
        Arrays.sort(order);
        boolean[] taken = new boolean[n];
        int[] near = new int[Math.max(64, 4 * lists.size())];
        int[] best = new int[lists.size()];
        float[] bestD = new float[lists.size()];
        float[] tf = new float[lists.size()], th = new float[lists.size()], te = new float[lists.size()];
        for (int q = n - 1; q >= 0; q--) {
            int seed = (int) (order[q] & 0xFFFFFFFFL);
            if (taken[seed]) continue;
            int m = grid.within(xs[seed], ys[seed], joinPx, near);
            // the nearest detection of each frame
            Arrays.fill(best, -1);
            for (int j = 0; j < m; j++) {
                int o = near[j];
                if (taken[o]) continue;
                float dx = xs[o] - xs[seed], dy = ys[o] - ys[seed];
                float d = dx * dx + dy * dy;
                int f = frame[o];
                if (best[f] < 0 || d < bestD[f]) {
                    best[f] = o;
                    bestD[f] = d;
                }
            }
            int c = 0;
            for (int f = 0; f < best.length; f++) if (best[f] >= 0) c++;
            if (c < minFrames) continue;
            double sx = 0, sy = 0, sw = 0;
            c = 0;
            boolean anySat = false;
            for (int f = 0; f < best.length; f++) {
                int o = best[f];
                if (o < 0) continue;
                taken[o] = true;
                // weight by flux: bright detections have the better centroids
                double w = Math.max(1e-3, fl[o]);
                sx += w * xs[o];
                sy += w * ys[o];
                sw += w;
                tf[c] = fl[o];
                th[c] = hf[o];
                te[c] = el[o];
                anySat |= sat[o];
                c++;
            }
            out.add((float) (sx / sw), (float) (sy / sw), Stats.median(tf, c), Stats.median(th, c), Stats.median(te, c), anySat);
            groups[ng++] = c;
        }
        out.sortAndTrim(Math.max(out.count, 1));
        if (frameNoise > 0 && ng > 0) out.noise = frameNoise / (float) Math.sqrt(Math.max(1f, Stats.median(groups, ng)));
        return out;
    }
}
