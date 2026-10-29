package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.DeepLines;
import com.brandon.nightsky.processing.SatMatch;
import com.brandon.nightsky.processing.StreakEvidence;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The deep line search (1.6) on maps of unit noise: how high the best line in pure noise scores (what the bar,
 * {@link SatMatch#deepSigma}, must clear: none of the maps may reach the bar for a session of as many frames, and
 * on average they must stay under what the bar's model says), and how faint a line it finds at the bar of a Meteors
 * session ({@link #SESSION_FRAMES} frames), by its strength per cell and length, with its ends.
 * <p>
 * Usage: DeepLinesTest [maps=12] [w=510] [h=384] [seed=1]
 */
public final class DeepLinesTest {
    static int failures;
    /** The Meteors preset's frames: the bar the faint lines are looked for at. */
    static final int SESSION_FRAMES = 225;

    public static void main(String[] args) {
        int maps = args.length > 0 ? Integer.parseInt(args[0]) : 12;
        int w = args.length > 1 ? Integer.parseInt(args[1]) : 510, h = args.length > 2 ? Integer.parseInt(args[2]) : 384;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 1;
        Random rnd = new Random(seed);
        DeepLines dl = new DeepLines(w, h);
        // 1) noise alone: the best line's score
        double sum = 0, top = 0;
        long t0 = System.currentTimeMillis();
        for (int k = 0; k < maps; k++) {
            float[] z = noise(rnd, w, h);
            List<DeepLines.Line> got = dl.find(z, 4.5, 8, 1, 1.5);
            double s = got.isEmpty() ? 0 : got.get(0).score;
            sum += s;
            top = Math.max(top, s);
            System.out.printf(Locale.US, "  noise map %d: best line %.2f sigma%s%n", k, s,
                    got.isEmpty() ? "" : String.format(Locale.US, " over %d cells", got.get(0).cells));
        }
        long per = (System.currentTimeMillis() - t0) / Math.max(1, maps);
        System.out.printf(Locale.US, "noise: best line %.2f sigma on average, %.2f at most (%d ms a map)%n", sum / maps, top, per);
        double bar = SatMatch.deepSigma(maps, w * h);
        check(top < bar, String.format(Locale.US, "no line in noise reaches the bar for %d frames, %.2f sigma (best %.2f)", maps, bar, top));
        // the bar's model: the best line's typical score (Gumbel: its mode plus 0.58 of the tail's scale)
        double model = SatMatch.DEEP_MODE + SatMatch.DEEP_TAIL * (Math.log(w * h / SatMatch.DEEP_REF_CELLS) + 0.5772);
        check(sum / maps <= model + 0.1, String.format(Locale.US, "noise's best lines average %.2f, the bar's model %.2f", sum / maps, model));

        // 2) lines of a given strength per cell and length, anywhere at any angle
        double[] strengths = {0.5, 0.75, 1.0, 1.5};
        int[] lengths = {12, 25, 50, 100};
        double threshold = SatMatch.deepSigma(SESSION_FRAMES, w * h);
        System.out.printf(Locale.US, "found at %.1f sigma, of %d each (strength per cell x length in cells):%n", threshold, maps);
        for (double mu : strengths) {
            StringBuilder row = new StringBuilder(String.format(Locale.US, "  %.2f per cell:", mu));
            for (int L : lengths) {
                int found = 0, ends = 0;
                for (int k = 0; k < maps; k++) {
                    float[] z = noise(rnd, w, h);
                    double[] line = inject(rnd, z, w, h, mu, L);
                    List<DeepLines.Line> got = dl.find(z, threshold, 8, 3, 1.5);
                    for (DeepLines.Line l : got) {
                        if (on(l, line)) {
                            found++;
                            if (endsWithin(l, line, 3)) ends++;
                            break;
                        }
                    }
                }
                row.append(String.format(Locale.US, "  %3d cells %2d/%d (ends %2d, expected %4.1f sigma)", L, found, maps, ends, mu * Math.sqrt(L)));
            }
            System.out.println(row);
        }

        // 3) the sensor's banding: rows of the sensor (turned a little against the map, as a frame against the
        //    reference) each a little off, enough to make lines far above the bar; taken out, none is left, and a
        //    faint trail across them is still found
        int bandedFalse = 0, cleanFalse = 0, kept = 0, tries = Math.max(4, maps / 2);
        double worst = 0;
        for (int k = 0; k < tries; k++) {
            double turn = (k % 2 == 0 ? 1 : -1) * (0.3 + 2.0 * k / tries) * Math.PI / 180;
            float[] col = new float[w * h], row = new float[w * h];
            float[] z = banded(rnd, w, h, turn, 0.45, col, row);
            float[] copy = z.clone();
            List<DeepLines.Line> raw = dl.find(copy, threshold, 8, 1, 1.5);
            if (!raw.isEmpty()) bandedFalse++;
            float[] clean = z.clone();
            DeepLines.unband(clean, w, h, col, row);
            List<DeepLines.Line> got = dl.find(clean.clone(), threshold, 8, 1, 1.5);
            if (!got.isEmpty()) {
                cleanFalse++;
                worst = Math.max(worst, got.get(0).score);
            } else {
                double best = 0;
                List<DeepLines.Line> any = dl.find(clean.clone(), 4.5, 8, 1, 1.5);
                if (!any.isEmpty()) best = any.get(0).score;
                worst = Math.max(worst, best);
            }
            // a trail across the bands (at least 20 degrees off the sensor's rows and columns)
            double[] line;
            do {
                z = banded(rnd, w, h, turn, 0.45, col, row);
                line = inject(rnd, z, w, h, 1.5, 100);
            } while (offAxis(line, turn) < 20);
            DeepLines.unband(z, w, h, col, row);
            for (DeepLines.Line l : dl.find(z, threshold, 8, 3, 1.5)) {
                if (on(l, line)) {
                    kept++;
                    break;
                }
            }
        }
        System.out.printf(Locale.US, "banding: lines past the bar in %d of %d banded maps; after taking it out in %d (best %.2f sigma); "
                + "a 1.5 x 100 trail across it found in %d%n", bandedFalse, tries, cleanFalse, worst, kept);
        check(bandedFalse == tries, "the banding makes lines past the bar (so the test means something)");
        check(cleanFalse == 0, String.format(Locale.US, "taken out, no line in the banding reaches the bar, %.2f sigma (best %.2f)", threshold, worst));
        check(kept >= tries - 1, "a faint trail across the banding is still found once it's taken out (" + kept + " of " + tries + ")");
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
    }

    /**
     * Unit noise plus a sensor's row banding: the sensor turned by {@code turn} (radians) against the map, each of its
     * rows of pixels (8 to a cell) off by its own amount (sigma {@code band} per cell's worth of rows: a cell's offset is
     * the mean of the 8 rows it covers), and where each cell lay on the sensor into {@code col} and {@code row}.
     */
    static float[] banded(Random rnd, int w, int h, double turn, double band, float[] col, float[] row) {
        float[] z = noise(rnd, w, h);
        double c = Math.cos(turn), s = Math.sin(turn);
        int px = StreakEvidence.DEEP;
        double cx0 = w / 2.0, cy0 = h / 2.0;
        int span = (int) Math.ceil((w + h) * px * 1.5) + 64, mid = span / 2;
        double[] rows = new double[span];
        // each pixel row's offset, so that a cell's (the mean over 8) has sigma band
        double per = band * Math.sqrt(px);
        for (int i = 0; i < span; i++) rows[i] = per * rnd.nextGaussian();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = x - cx0, dy = y - cy0;
                double u = c * dx + s * dy, v = -s * dx + c * dy;
                col[y * w + x] = (float) u;
                row[y * w + x] = (float) v;
                // the 8 pixel rows the cell covers
                double sum = 0;
                for (int k = 0; k < px; k++) {
                    int r = mid + (int) Math.floor(v * px - px / 2.0 + k + 0.5);
                    sum += rows[Math.max(0, Math.min(span - 1, r))];
                }
                z[y * w + x] += (float) (sum / px);
            }
        }
        return z;
    }

    /** How far (degrees) a line {x0, y0, x1, y1} is from the sensor's rows and columns, turned by {@code turn}. */
    static double offAxis(double[] t, double turn) {
        double a = Math.toDegrees(Math.atan2(t[3] - t[1], t[2] - t[0]) - turn);
        a = ((a % 90) + 90) % 90;
        return Math.min(a, 90 - a);
    }

    static float[] noise(Random rnd, int w, int h) {
        float[] z = new float[w * h];
        for (int i = 0; i < z.length; i++) z[i] = (float) rnd.nextGaussian();
        return z;
    }

    /**
     * A trail of {@code mu} per cell (if all its light fell in one cell) over {@code L} cells, at a random place and
     * angle: a Gaussian across (sigma 1.3 px in cells of 8 px), its light shared between the cells it falls in.
     * Returns its ends {x0, y0, x1, y1} (cell coordinates).
     */
    static double[] inject(Random rnd, float[] z, int w, int h, double mu, int L) {
        while (true) {
            double ang = rnd.nextDouble() * Math.PI, ux = Math.cos(ang), uy = Math.sin(ang);
            double x0 = 2 + rnd.nextDouble() * (w - 4), y0 = 2 + rnd.nextDouble() * (h - 4);
            double x1 = x0 + ux * L, y1 = y0 + uy * L;
            if (x1 < 2 || y1 < 2 || x1 > w - 3 || y1 > h - 3) continue;
            double sig = 1.3 / 8;
            // light per unit length: mu per cell's length of a trail running through the cell
            int steps = L * 16;
            float[] add = new float[w * h];
            for (int k = 0; k < steps; k++) {
                double t = (k + 0.5) / steps;
                double px = x0 + (x1 - x0) * t, py = y0 + (y1 - y0) * t;
                // across: the share in each cell from a Gaussian across the trail
                double nx = -uy, ny = ux;
                for (int q = -6; q <= 6; q++) {
                    double off = q * sig * 0.5;
                    double wt = Math.exp(-0.5 * (off / sig) * (off / sig));
                    int cx = (int) Math.floor(px + nx * off + 0.5), cy = (int) Math.floor(py + ny * off + 0.5);
                    if (cx < 0 || cy < 0 || cx >= w || cy >= h) continue;
                    add[cy * w + cx] += (float) wt;
                }
            }
            // normalise: the whole trail's light is mu * L
            double tot = 0;
            for (float a : add) tot += a;
            for (int i = 0; i < z.length; i++) if (add[i] > 0) z[i] += (float) (add[i] / tot * mu * L);
            return new double[]{x0, y0, x1, y1};
        }
    }

    /** Whether a found line lies along the injected one (within 2 cells, overlapping it by half its length). */
    static boolean on(DeepLines.Line l, double[] t) {
        double dx = t[2] - t[0], dy = t[3] - t[1], len = Math.hypot(dx, dy);
        double ux = dx / len, uy = dy / len;
        double[][] pts = {{l.x0, l.y0}, {l.x1, l.y1}};
        double a = Double.MAX_VALUE, b = -Double.MAX_VALUE;
        for (double[] p : pts) {
            double px = p[0] - t[0], py = p[1] - t[1];
            double along = px * ux + py * uy, across = Math.abs(-px * uy + py * ux);
            if (across > 2.5) return false;
            a = Math.min(a, along);
            b = Math.max(b, along);
        }
        double overlap = Math.min(b, len) - Math.max(a, 0);
        return overlap >= 0.5 * len;
    }

    static boolean endsWithin(DeepLines.Line l, double[] t, double tol) {
        double d1 = Math.hypot(l.x0 - t[0], l.y0 - t[1]) + Math.hypot(l.x1 - t[2], l.y1 - t[3]);
        double d2 = Math.hypot(l.x0 - t[2], l.y0 - t[3]) + Math.hypot(l.x1 - t[0], l.y1 - t[1]);
        double[] e = d1 <= d2 ? new double[]{Math.hypot(l.x0 - t[0], l.y0 - t[1]), Math.hypot(l.x1 - t[2], l.y1 - t[3])}
                : new double[]{Math.hypot(l.x0 - t[2], l.y0 - t[3]), Math.hypot(l.x1 - t[0], l.y1 - t[1])};
        return e[0] <= tol && e[1] <= tol;
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  PASS: " : "  FAIL: ") + what);
        if (!ok) failures++;
    }
}
