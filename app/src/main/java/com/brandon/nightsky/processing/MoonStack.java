package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Lucky imaging for the Moon (3.0, the Moonshot): a burst of short RAW frames from the 5x telephoto turned into one
 * photo sharper and cleaner than any of them, the way planetary imagers do it (AutoStakkert, RegiStax, PSS), fitted
 * to a phone's camera.
 * <p>
 * The Pixel's telephoto puts about 16" on each (binned) pixel while its optics blur a star to about 18": the Moon is
 * undersampled, and the air's seeing (a few arcseconds) hardly matters. What does is the hand: each frame is shaken
 * a different fraction of a pixel, and some are smeared. So, as Google's Super Res Zoom and the astronomers' drizzle
 * both do, the shaking is put to work:
 * <ol>
 * <li>each frame is scored (how much fine detail it holds) and the sharpest is the reference;</li>
 * <li>every frame is aligned to it to a few hundredths of a pixel: shift, turn and scale, and its brightness, by
 * Lucas-Kanade on the whole disk (twice: the second time against the average of the best, which is less noisy);</li>
 * <li>the best of them (40% by default) are merged straight from their RAW pixels onto a grid twice as fine
 * (drizzle): every red, green and blue sample lands where it really was, so the colours come out without demosaicking
 * and the detail between the pixels comes back from the many shaken copies;</li>
 * <li>the result is sharpened by Richardson-Lucy deconvolution with the blur measured on the Moon's own bright
 * edge.</li>
 * </ol>
 * The result is turned so its up is the sky's up (from the gravity sensor), ready for {@link MoonGeo}'s labels.
 * Pure Java, for the phone and the simulator.
 */
public final class MoonStack {
    private MoonStack() {}

    /** One frame: a crop of the RAW buffer around the Moon, at an even place (so the colour filter lines up). */
    public static final class Frame {
        public final short[] raw;
        public final int w, h, x0, y0;
        public final long tNs;
        /** The crop's up as a position angle (degrees from celestial north through east), NaN if unknown. */
        public double upPa = Double.NaN;

        public Frame(short[] raw, int w, int h, int x0, int y0, long tNs) {
            this.raw = raw;
            this.w = w;
            this.h = h;
            this.x0 = x0;
            this.y0 = y0;
            this.tNs = tNs;
        }
    }

    public static final class Options {
        /** How much finer the result's grid is than the sensor's. */
        public int scale = 2;
        /** Share of the frames merged (the sharpest), and the fewest. */
        public double keep = 0.5;
        public int minKeep = 12;
        /** The Moon's radius in the crop's pixels as the lens and the ephemeris predict it (NaN: found freely). */
        public double expectedRadius = Double.NaN;
        /** The result's up as a position angle (degrees): the sky's up (the parallactic angle); NaN: the reference's. */
        public double outUpPa = Double.NaN;
        /** The bright limb's middle as a position angle (degrees, the ephemeris's chi); NaN: found from the pictures. */
        public double brightLimbPa = Double.NaN;
        public boolean deconvolve = true;
        public int rlIterations = 20;
        /** The sharpening's blur as a share of the blur measured on the bright limb. */
        public double rlSigma = 1.0;
        /** The merge's two kernels' widths (sigma, in the sensor's pixels). */
        public double fineKernel = 0.36, coarseKernel = 0.85;
    }

    /** The Moon's disk found in a picture. */
    public static final class Disk {
        public double cx, cy, r;
        /** Edge points on the circle, and how many were tried. */
        public int inliers, edges;
        /** Direction from the centre to the middle of the lit part, counterclockwise from the picture's up (degrees). */
        public double litDir = Double.NaN;
        /** Lit share of the disk's area. */
        public double litFrac;
        /** The Moon's brightness over the picture's noise (an empty sky has a few). */
        public double contrast;
        public boolean ok;

        @Override
        public String toString() {
            return String.format(Locale.US, "centre %.2f,%.2f radius %.2f (%d of %d edge points), lit %.0f%% towards %.1f deg", cx, cy, r,
                    inliers, edges, litFrac * 100, litDir);
        }
    }

    public static final class Result {
        public int w, h;
        /** Linear sRGB, 1 at the Moon's bright parts (99.5th percentile). */
        public float[] r, g, b;
        /** The best single frame through the same geometry, as the stack is: what one photo would have given. */
        public float[] r1, g1, b1;
        public double cx, cy, radius;
        /** The result's up as a position angle (degrees), NaN if unknown. */
        public double upPa = Double.NaN;
        public int used, total, reference;
        public double[] quality;
        public boolean[] kept;
        /** Each frame's alignment: shift x, y (px), turn (degrees), scale, against the reference. */
        public double[][] motion;
        /** The bright limb's blur (the edge's sigma, in the result's pixels): one frame, the stack, sharpened. */
        public double edgeSingle = Double.NaN, edgeStack = Double.NaN, edgeSharp = Double.NaN;
        public Disk disk;
        public String log = "";
    }

    // ------------------------------------------------------------------------------------------------
    // the pieces

    /** Black taken off, scaled to 0..1 of the white level and divided by the neutral's colour: grey comes out grey. */
    static float[] balanced(Frame f, SensorSpec spec, boolean[] sat) {
        float[] out = new float[f.w * f.h];
        float[] inv = new float[4];
        float[] blk = new float[4];
        for (int p = 0; p < 4; p++) {
            float range = spec.white - spec.black[p];
            float n = spec.camNeutral[spec.cfa[p]];
            inv[p] = 1f / (range * (n > 0 ? n : 1));
            blk[p] = spec.black[p];
        }
        int satAt = (int) spec.white - 2;
        for (int j = 0; j < f.h; j++) {
            int py = ((j + f.y0) & 1) * 2;
            for (int i = 0; i < f.w; i++) {
                int k = j * f.w + i, p = py + ((i + f.x0) & 1);
                int v = f.raw[k] & 0xffff;
                if (sat != null) sat[k] = v >= satAt;
                out[k] = (v - blk[p]) * inv[p];
            }
        }
        return out;
    }

    /** Luminance from the balanced mosaic: the 3x3 binomial low-pass, which takes the colour filter's pattern out. */
    static float[] luma(float[] bal, int w, int h) {
        float[] t = new float[w * h], out = new float[w * h];
        for (int j = 0; j < h; j++) {
            int row = j * w;
            for (int i = 0; i < w; i++) {
                float a = bal[row + Math.max(0, i - 1)], b = bal[row + i], c = bal[row + Math.min(w - 1, i + 1)];
                t[row + i] = (a + 2 * b + c) * 0.25f;
            }
        }
        for (int j = 0; j < h; j++) {
            int up = Math.max(0, j - 1) * w, row = j * w, dn = Math.min(h - 1, j + 1) * w;
            for (int i = 0; i < w; i++) out[row + i] = (t[up + i] + 2 * t[row + i] + t[dn + i]) * 0.25f;
        }
        return out;
    }

    /** Separable Gaussian blur (edges clamped). */
    static float[] blur(float[] img, int w, int h, double sigma) {
        if (sigma <= 0.05) return img.clone();
        int rad = Math.max(1, (int) Math.ceil(3 * sigma));
        float[] k = new float[2 * rad + 1];
        double sum = 0;
        for (int i = -rad; i <= rad; i++) sum += k[i + rad] = (float) Math.exp(-i * i / (2 * sigma * sigma));
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        float[] t = new float[w * h], out = new float[w * h];
        for (int j = 0; j < h; j++) {
            int row = j * w;
            for (int i = 0; i < w; i++) {
                float s = 0;
                for (int d = -rad; d <= rad; d++) {
                    int x = i + d;
                    x = x < 0 ? 0 : x >= w ? w - 1 : x;
                    s += k[d + rad] * img[row + x];
                }
                t[row + i] = s;
            }
        }
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                float s = 0;
                for (int d = -rad; d <= rad; d++) {
                    int y = j + d;
                    y = y < 0 ? 0 : y >= h ? h - 1 : y;
                    s += k[d + rad] * t[y * w + i];
                }
                out[j * w + i] = s;
            }
        }
        return out;
    }

    static float sample(float[] img, int w, int h, double x, double y) {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x > w - 1.001) x = w - 1.001;
        if (y > h - 1.001) y = h - 1.001;
        int ix = (int) x, iy = (int) y;
        float fx = (float) (x - ix), fy = (float) (y - iy);
        int k = iy * w + ix;
        float a = img[k], b = img[k + 1], c = img[k + w], d = img[k + w + 1];
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
    }

    /** Percentile (0..1) of a float array's values (a sample of up to 20000). */
    static float percentile(float[] v, double q) {
        int n = v.length, step = Math.max(1, n / 20000);
        float[] s = new float[(n + step - 1) / step];
        int m = 0;
        for (int i = 0; i < n; i += step) s[m++] = v[i];
        Arrays.sort(s, 0, m);
        return s[Math.max(0, Math.min(m - 1, (int) Math.round(q * (m - 1))))];
    }

    /** The noise of a picture: from the differences of pixels two apart (their median: the Moon's edges hardly move it). */
    static double noise(float[] L, int w, int h) {
        int n = w * h, step = Math.max(1, n / 30000);
        float[] d = new float[n / step + 1];
        int m = 0;
        for (int k = 0; k < n; k += step) if (k % w < w - 2) d[m++] = Math.abs(L[k] - L[k + 2]);
        if (m == 0) return 0;
        Arrays.sort(d, 0, m);
        return d[m / 2] * 1.4826 / Math.sqrt(2);
    }

    /** Finds the Moon's disk with nothing known of where its lit side is. */
    public static Disk findDisk(float[] L, int w, int h, double rExp) {
        return findDisk(L, w, h, rExp, Double.NaN);
    }

    /**
     * Finds the Moon's disk: the lit part's edge, each point of it placed where the brightness is halfway between the
     * Moon's and the sky's (unbiased by the blur), a circle through them that keeps the sky outside. Only the bright
     * limb is the Moon's true edge: the terminator is the shadow's, so the circle is fitted to the limb's side, within
     * 75 degrees of the lit side's middle ({@code litDirDeg}: counterclockwise from the picture's up, from the
     * ephemeris and the gravity sensor; NaN: found from the picture, a first fit giving the direction).
     */
    public static Disk findDisk(float[] L, int w, int h, double rExp, double litDirDeg) {
        Disk d = new Disk();
        float bg = percentile(L, 0.05), hi = percentile(L, 0.998);
        double nz = noise(L, w, h);
        d.contrast = nz > 0 ? (hi - bg) / nz : (hi > bg ? 1e9 : 0);
        // a Moon is hundreds of times brighter than the noise; an empty sky is not
        if (!(hi > bg + 1e-4) || d.contrast < 25) return d;
        float t = bg + 0.3f * (hi - bg);
        // the lit part's centroid and area
        double sx = 0, sy = 0;
        int area = 0;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                if (L[j * w + i] > t) {
                    area++;
                    sx += i;
                    sy += j;
                }
            }
        }
        if (area < 20) return d;
        double mx = sx / area, my = sy / area;
        // edge points: where the lit part ends, then moved along the downhill direction to the halfway brightness
        List<double[]> pts = new ArrayList<double[]>();
        double[] prof = new double[17];
        for (int j = 1; j < h - 1; j++) {
            for (int i = 1; i < w - 1; i++) {
                int k = j * w + i;
                if (L[k] <= t) continue;
                boolean edge = L[k - 1] <= t || L[k + 1] <= t || L[k - w] <= t || L[k + w] <= t;
                if (!edge) continue;
                double gx = (L[k + 1] - L[k - 1]) * 0.5, gy = (L[k + w] - L[k - w]) * 0.5, gn = Math.hypot(gx, gy);
                if (gn < 1e-6) continue;
                double ux = -gx / gn, uy = -gy / gn;
                for (int s = 0; s < 17; s++) prof[s] = sample(L, w, h, i + ux * (s - 8) * 0.5, j + uy * (s - 8) * 0.5);
                double inner = (prof[0] + prof[1] + prof[2]) / 3, outer = (prof[14] + prof[15] + prof[16]) / 3;
                if (inner - outer < 0.5 * (t - bg)) continue;
                double mid = 0.5 * (inner + outer), at = Double.NaN;
                for (int s = 1; s < 17; s++) {
                    if (prof[s - 1] >= mid && prof[s] < mid) {
                        at = (s - 1 + (prof[s - 1] - mid) / (prof[s - 1] - prof[s]) - 8) * 0.5;
                        break;
                    }
                }
                if (Double.isNaN(at)) continue;
                pts.add(new double[]{i + ux * at, j + uy * at, ux, uy, gn});
            }
        }
        d.edges = pts.size();
        if (pts.size() < 8) return d;
        double[] c = fitCircle(pts, rExp, w, h, Double.NaN);
        if (c == null) return d;
        double dir = litDirDeg;
        if (Double.isNaN(dir) && Math.hypot(mx - c[0], my - c[1]) > 0.004 * c[2])
            dir = MoonGeo.norm360(Math.toDegrees(Math.atan2(-(mx - c[0]), -(my - c[1]))));
        if (!Double.isNaN(dir)) {
            double[] c2 = fitCircle(pts, rExp, w, h, dir);
            if (c2 != null) c = c2;
        }
        d.cx = c[0];
        d.cy = c[1];
        d.r = c[2];
        d.inliers = (int) c[3];
        d.ok = d.inliers >= 8 && d.r > 3 && d.inliers >= 0.25 * Math.PI * d.r;
        // the lit part: its share of the disk and its direction from the centre
        double lx = mx - d.cx, ly = my - d.cy;
        d.litFrac = Math.min(1, area / (Math.PI * d.r * d.r));
        if (Math.hypot(lx, ly) > 0.004 * d.r) d.litDir = MoonGeo.norm360(Math.toDegrees(Math.atan2(-lx, -ly)));
        else if (!Double.isNaN(litDirDeg)) d.litDir = litDirDeg;
        return d;
    }

    /**
     * RANSAC, then least squares (geometric, Gauss-Newton), the radius softly held near {@code rExp}: {cx, cy, r,
     * inliers}, from the edge points whose outward direction is within 75 degrees of {@code dirDeg} (all if NaN).
     */
    private static double[] fitCircle(List<double[]> all, double rExp, int w, int h, double dirDeg) {
        List<double[]> pts = all;
        if (!Double.isNaN(dirDeg)) {
            pts = new ArrayList<double[]>();
            for (double[] p : all) {
                double a = Math.toDegrees(Math.atan2(-p[2], -p[3]));
                if (Math.abs(MoonGeo.norm180(a - dirDeg)) < 75) pts.add(p);
            }
            if (pts.size() < 8) return null;
        }
        double rLo = Double.isNaN(rExp) ? 4 : rExp * 0.8, rHi = Double.isNaN(rExp) ? Math.max(w, h) : rExp * 1.2;
        java.util.Random rnd = new java.util.Random(12345);
        double[] best = null;
        int bestN = 0, n = pts.size();
        for (int it = 0; it < 400; it++) {
            double[] circ = circle3(pts.get(rnd.nextInt(n)), pts.get(rnd.nextInt(n)), pts.get(rnd.nextInt(n)));
            if (circ == null || circ[2] < rLo || circ[2] > rHi) continue;
            int in = 0;
            for (double[] p : pts) if (onLimb(p, circ, 1.5)) in++;
            if (in > bestN) {
                bestN = in;
                best = circ;
            }
        }
        if (best == null) return null;
        double cx = best[0], cy = best[1], r = best[2];
        int inliers = 0;
        for (int round = 0; round < 3; round++) {
            List<double[]> in = new ArrayList<double[]>();
            for (double[] p : pts) if (onLimb(p, new double[]{cx, cy, r}, round == 0 ? 1.5 : 0.8)) in.add(p);
            if (in.size() < 6) break;
            for (int gn = 0; gn < 8; gn++) {
                double[] H = new double[9], g = new double[3];
                for (double[] p : in) {
                    double dx = p[0] - cx, dy = p[1] - cy, dist = Math.hypot(dx, dy);
                    if (dist < 1e-9) continue;
                    double e = dist - r;
                    double[] J = {-dx / dist, -dy / dist, -1};
                    for (int u = 0; u < 3; u++) {
                        g[u] += J[u] * e;
                        for (int v = 0; v < 3; v++) H[u * 3 + v] += J[u] * J[v];
                    }
                }
                if (!Double.isNaN(rExp)) {
                    // a soft prior: the radius known to about 3%
                    double wr = in.size() / Math.pow(0.03 * rExp, 2) * 0.01;
                    H[8] += wr;
                    g[2] += wr * (r - rExp);
                }
                double[] step = solve(H, g, 3);
                if (step == null) break;
                cx -= step[0];
                cy -= step[1];
                r -= step[2];
                if (Math.abs(step[0]) + Math.abs(step[1]) + Math.abs(step[2]) < 1e-4) break;
            }
            inliers = in.size();
        }
        return new double[]{cx, cy, r, inliers};
    }

    /** A point on the bright limb of this circle: near it, with the sky (downhill) outward. */
    private static boolean onLimb(double[] p, double[] c, double tol) {
        double dx = p[0] - c[0], dy = p[1] - c[1], dist = Math.hypot(dx, dy);
        if (Math.abs(dist - c[2]) > tol || dist < 1e-9) return false;
        return (p[2] * dx + p[3] * dy) / dist > 0.5;
    }

    private static double[] circle3(double[] a, double[] b, double[] c) {
        double ax = a[0], ay = a[1], bx = b[0], by = b[1], cx = c[0], cy = c[1];
        double d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by));
        if (Math.abs(d) < 1e-9) return null;
        double a2 = ax * ax + ay * ay, b2 = bx * bx + by * by, c2 = cx * cx + cy * cy;
        double ux = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / d;
        double uy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d;
        return new double[]{ux, uy, Math.hypot(ax - ux, ay - uy)};
    }

    /** Solves H x = g (n x n, row-major) by Gaussian elimination with pivoting; null if singular. */
    static double[] solve(double[] H, double[] g, int n) {
        double[] a = H.clone(), b = g.clone();
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(a[r * n + c]) > Math.abs(a[piv * n + c])) piv = r;
            if (Math.abs(a[piv * n + c]) < 1e-12) return null;
            if (piv != c) {
                for (int k = 0; k < n; k++) {
                    double t = a[c * n + k];
                    a[c * n + k] = a[piv * n + k];
                    a[piv * n + k] = t;
                }
                double t = b[c];
                b[c] = b[piv];
                b[piv] = t;
            }
            for (int r = c + 1; r < n; r++) {
                double f = a[r * n + c] / a[c * n + c];
                for (int k = c; k < n; k++) a[r * n + k] -= f * a[c * n + k];
                b[r] -= f * b[c];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int k = r + 1; k < n; k++) s -= a[r * n + k] * x[k];
            x[r] = s / a[r * n + r];
        }
        return x;
    }

    /** How much fine detail a frame holds: the energy of its high frequencies over the lit part, for its brightness. */
    static double quality(float[] L, int w, int h) {
        float[] bl = blur(L, w, h, 1.2);
        float hi = percentile(L, 0.995), lo = percentile(L, 0.05);
        float t = lo + 0.15f * (hi - lo);
        double e = 0, s = 0;
        for (int k = 0; k < L.length; k++) {
            if (L[k] <= t) continue;
            double d = L[k] - bl[k];
            e += d * d;
            s += (double) L[k] * L[k];
        }
        return s > 0 ? e / s : 0;
    }

    /** A frame's alignment to the reference: a point x of the reference is at W(x) in the frame. */
    static final class Warp {
        double tx, ty, th, sc, gain = 1, off;
        double cx, cy;
        double rms = Double.NaN;
        boolean ok = true;

        /** Reference to frame. */
        void fwd(double x, double y, double[] o) {
            double c = Math.cos(th) * (1 + sc), s = Math.sin(th) * (1 + sc), dx = x - cx, dy = y - cy;
            o[0] = c * dx - s * dy + cx + tx;
            o[1] = s * dx + c * dy + cy + ty;
        }

        /** Frame to reference. */
        void inv(double x, double y, double[] o) {
            double dx = x - cx - tx, dy = y - cy - ty, k = 1 / (1 + sc), c = Math.cos(th), s = Math.sin(th);
            o[0] = (c * dx + s * dy) * k + cx;
            o[1] = (-s * dx + c * dy) * k + cy;
        }
    }

    /**
     * Lucas-Kanade (Gauss-Newton) for shift, turn, scale, gain and offset: the frame {@code F} (with its gradients)
     * warped onto the reference {@code R} over the mask's pixels.
     */
    static Warp align(float[] R, float[] F, float[] Fx, float[] Fy, int w, int h, int[] mask, Warp init) {
        Warp p = new Warp();
        p.cx = init.cx;
        p.cy = init.cy;
        p.tx = init.tx;
        p.ty = init.ty;
        p.th = init.th;
        p.sc = init.sc;
        double[] o = new double[2];
        for (int it = 0; it < 12; it++) {
            double[] H = new double[36], g = new double[6];
            double ss = 0;
            int n = 0;
            double c = Math.cos(p.th), s = Math.sin(p.th), k = 1 + p.sc;
            for (int m : mask) {
                int x = m % w, y = m / w;
                p.fwd(x, y, o);
                if (o[0] < 1 || o[1] < 1 || o[0] > w - 2 || o[1] > h - 2) continue;
                double fv = sample(F, w, h, o[0], o[1]);
                double gx = p.gain * sample(Fx, w, h, o[0], o[1]), gy = p.gain * sample(Fy, w, h, o[0], o[1]);
                double e = p.gain * fv + p.off - R[m];
                double dx = x - p.cx, dy = y - p.cy;
                double[] J = {gx, gy,
                        gx * k * (-s * dx - c * dy) + gy * k * (c * dx - s * dy),
                        gx * (c * dx - s * dy) + gy * (s * dx + c * dy),
                        fv, 1};
                for (int u = 0; u < 6; u++) {
                    g[u] += J[u] * e;
                    for (int v = u; v < 6; v++) H[u * 6 + v] += J[u] * J[v];
                }
                ss += e * e;
                n++;
            }
            if (n < 50) {
                p.ok = false;
                return p;
            }
            for (int u = 0; u < 6; u++) for (int v = 0; v < u; v++) H[u * 6 + v] = H[v * 6 + u];
            double[] d = solve(H, g, 6);
            if (d == null) {
                p.ok = false;
                return p;
            }
            p.tx -= d[0];
            p.ty -= d[1];
            p.th -= d[2];
            p.sc -= d[3];
            p.gain -= d[4];
            p.off -= d[5];
            p.rms = Math.sqrt(ss / n);
            if (Math.abs(d[0]) < 1e-3 && Math.abs(d[1]) < 1e-3 && Math.abs(d[2]) < 2e-5 && Math.abs(d[3]) < 2e-5) break;
        }
        if (Math.abs(p.tx - init.tx) > 6 || Math.abs(p.ty - init.ty) > 6 || Math.abs(p.th) > 0.1 || Math.abs(p.sc) > 0.05
                || !(p.gain > 0.5 && p.gain < 2)) p.ok = false;
        return p;
    }

    static float[][] gradients(float[] F, int w, int h) {
        float[] gx = new float[w * h], gy = new float[w * h];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int k = j * w + i;
                gx[k] = (F[j * w + Math.min(w - 1, i + 1)] - F[j * w + Math.max(0, i - 1)]) * 0.5f;
                gy[k] = (F[Math.min(h - 1, j + 1) * w + i] - F[Math.max(0, j - 1) * w + i]) * 0.5f;
            }
        }
        return new float[][]{gx, gy};
    }

    /** The reference's pixels the alignment uses: the disk and a rim of sky around it, away from the crop's edges. */
    static int[] mask(int w, int h, Disk d, float[] L) {
        int[] m = new int[w * h];
        int n = 0;
        double r = d.r + 4;
        for (int j = 3; j < h - 3; j++) {
            for (int i = 3; i < w - 3; i++) {
                double dx = i - d.cx, dy = j - d.cy;
                if (dx * dx + dy * dy <= r * r) m[n++] = j * w + i;
            }
        }
        return Arrays.copyOf(m, n);
    }

    /** The lit part's centroid (brightness-weighted, over the threshold). */
    static double[] centroid(float[] L, int w, int h) {
        float bg = percentile(L, 0.05), hi = percentile(L, 0.995);
        float t = bg + 0.3f * (hi - bg);
        double sx = 0, sy = 0, s = 0;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                float v = L[j * w + i] - t;
                if (v <= 0) continue;
                sx += v * i;
                sy += v * j;
                s += v;
            }
        }
        return s > 0 ? new double[]{sx / s, sy / s, s} : new double[]{w / 2.0, h / 2.0, 0};
    }

    // ------------------------------------------------------------------------------------------------
    // the merge

    /** Where the merge puts things: the reference's pixels to the result's. */
    static final class Grid {
        int W, H;
        double S, psi, rcx, rcy, oc;

        void out(double x, double y, double[] o) {
            double dx = x - rcx, dy = y - rcy, c = Math.cos(psi), s = Math.sin(psi);
            o[0] = oc + S * (dx * c + dy * s);
            o[1] = oc + S * (-dx * s + dy * c);
        }
    }

    /** Drizzles frames' RAW samples onto the grid: {r, g, b} (balanced camera colour), each from both kernels blended. */
    /**
     * The Moon's own colour in the balanced mosaic: each channel's mean over the lit disk as a share of green's (from
     * one frame), so that every sample can stand for the brightness.
     */
    static double[] moonColour(float[] bal, float[] L, Frame f, SensorSpec spec, Disk d) {
        float top = percentile(L, 0.995);
        double[] sum = new double[3];
        int[] n = new int[3];
        for (int j = 0; j < f.h; j++) {
            int py = ((j + f.y0) & 1) * 2;
            for (int i = 0; i < f.w; i++) {
                int k = j * f.w + i;
                double dx = i - d.cx, dy = j - d.cy;
                if (L[k] < 0.3f * top || dx * dx + dy * dy > 0.9 * d.r * d.r) continue;
                int c = spec.cfa[py + ((i + f.x0) & 1)];
                sum[c] += bal[k];
                n[c]++;
            }
        }
        double g = n[1] > 0 ? sum[1] / n[1] : 1;
        double[] mc = new double[3];
        for (int c = 0; c < 3; c++) {
            double m = n[c] > 0 && g > 0 ? sum[c] / n[c] / g : 1;
            mc[c] = m > 0.2 && m < 5 ? m : 1;
        }
        return mc;
    }

    static float[][] drizzle(List<float[]> bals, List<boolean[]> sats, List<Frame> frames, List<Warp> warps, SensorSpec spec, Grid gr,
                             double fineK, double coarseK, double[] mc) {
        int W = gr.W, H = gr.H, np = W * H;
        final double s1 = fineK * gr.S, s2 = coarseK * gr.S;
        final double k1 = -1 / (2 * s1 * s1), k2 = -1 / (2 * s2 * s2);
        // four layers: red, green, blue, and every sample whatever its colour (the frames are white-balanced, so the grey
        // Moon's samples all agree: the brightness gets every sample's detail; the colours come from their own, smoothed)
        double[][] a1 = new double[4][np], w1 = new double[4][np], a2 = new double[4][np], w2 = new double[4][np];
        double[] ref = new double[2], o = new double[2];
        int R = (int) Math.ceil(2.5 * s2);
        // the kernels by squared distance, tabulated (exp is the slow part)
        final int TAB = 4096;
        final double d2max = (R + 1.0) * (R + 1.0), tabStep = d2max / (TAB - 1);
        final double cut1 = 4.5 * s1 * s1;
        float[] e1 = new float[TAB], e2 = new float[TAB];
        for (int t = 0; t < TAB; t++) {
            double d2 = t * tabStep;
            e1[t] = d2 < cut1 ? (float) Math.exp(d2 * k1) : 0f;
            e2[t] = (float) Math.exp(d2 * k2);
        }
        double inv = 1 / tabStep;
        for (int fi = 0; fi < frames.size(); fi++) {
            Frame f = frames.get(fi);
            float[] bal = bals.get(fi);
            boolean[] sat = sats.get(fi);
            Warp wp = warps.get(fi);
            for (int j = 0; j < f.h; j++) {
                int py = ((j + f.y0) & 1) * 2;
                for (int i = 0; i < f.w; i++) {
                    int k = j * f.w + i;
                    if (sat[k]) continue;
                    int ch = spec.cfa[py + ((i + f.x0) & 1)];
                    wp.inv(i, j, ref);
                    gr.out(ref[0], ref[1], o);
                    int ox = (int) Math.round(o[0]), oy = (int) Math.round(o[1]);
                    if (ox < -R || oy < -R || ox >= W + R || oy >= H + R) continue;
                    double v = bal[k], vl = v / mc[ch];
                    double[] A1 = a1[ch], W1 = w1[ch], A2 = a2[ch], W2 = w2[ch];
                    double[] L1 = a1[3], M1 = w1[3], L2 = a2[3], M2 = w2[3];
                    for (int y = Math.max(0, oy - R); y <= Math.min(H - 1, oy + R); y++) {
                        double dy = y - o[1], dy2 = dy * dy;
                        for (int x = Math.max(0, ox - R); x <= Math.min(W - 1, ox + R); x++) {
                            double dx = x - o[0], d2 = dx * dx + dy2;
                            int ti = (int) (d2 * inv + 0.5);
                            if (ti >= TAB) continue;
                            int q = y * W + x;
                            float e = e1[ti];
                            if (e > 0) {
                                A1[q] += e * v;
                                W1[q] += e;
                                L1[q] += e * vl;
                                M1[q] += e;
                            }
                            float ee = e2[ti];
                            A2[q] += ee * v;
                            W2[q] += ee;
                            L2[q] += ee * vl;
                            M2[q] += ee;
                        }
                    }
                }
            }
        }
        float[][] layer = new float[4][np];
        for (int c = 0; c < 4; c++) {
            for (int q = 0; q < np; q++) {
                double fine = w1[c][q] > 1e-9 ? a1[c][q] / w1[c][q] : 0, coarse = w2[c][q] > 1e-9 ? a2[c][q] / w2[c][q] : 0;
                // trust the fine kernel where it has a few samples' worth of weight
                double t = w1[c][q] / (w1[c][q] + 0.6);
                layer[c][q] = (float) (t * fine + (1 - t) * coarse);
            }
        }
        // the colours: each channel's share of the brightness, smoothed alike (so no channel's own sharpness or noise
        // shows as coloured fringes), times the brightness from every sample
        float[] Ls = blur(layer[3], W, H, 1.5 * gr.S / 2);
        float top = percentile(Ls, 0.99);
        float[][] out = new float[3][];
        for (int c = 0; c < 3; c++) {
            float[] cs = blur(layer[c], W, H, 1.5 * gr.S / 2);
            float[] oc = new float[np];
            for (int q = 0; q < np; q++) {
                float l = Ls[q];
                float ratio = l > 1e-4f ? cs[q] / l : 1f;
                if (ratio < 0) ratio = 0;
                if (ratio > 4) ratio = 4;
                // the sky and the Moon's night side: too dark to have a colour (its noise would show as coloured dots)
                float fade = top > 0 ? (l / top - 0.01f) / 0.04f : 1f;
                if (fade < 1) {
                    float own = c == 0 ? (float) mc[0] : c == 1 ? 1f : (float) mc[2];
                    fade = Math.max(0, fade);
                    ratio = own + (ratio - own) * fade;
                }
                oc[q] = layer[3][q] * ratio;
            }
            out[c] = oc;
        }
        return out;
    }

    /**
     * One frame developed the ordinary way, for the "before": bilinear demosaicking (each colour from its nearest
     * samples), then cubic resampling onto the result's grid through the same geometry as the stack.
     */
    static float[][] ordinary(float[] bal, Frame f, Warp wp, SensorSpec spec, Grid gr) {
        int w = f.w, h = f.h;
        float[][] full = new float[3][w * h];
        float[] sum = new float[3];
        int[] cnt = new int[3];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                sum[0] = sum[1] = sum[2] = 0;
                cnt[0] = cnt[1] = cnt[2] = 0;
                int own = spec.cfa[((j + f.y0) & 1) * 2 + ((i + f.x0) & 1)];
                for (int dy = -1; dy <= 1; dy++) {
                    int y = j + dy;
                    if (y < 0 || y >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int x = i + dx;
                        if (x < 0 || x >= w) continue;
                        int c = spec.cfa[((y + f.y0) & 1) * 2 + ((x + f.x0) & 1)];
                        if (c == own && (dx != 0 || dy != 0)) continue;
                        sum[c] += bal[y * w + x];
                        cnt[c]++;
                    }
                }
                for (int c = 0; c < 3; c++) full[c][j * w + i] = cnt[c] > 0 ? sum[c] / cnt[c] : 0;
            }
        }
        int W = gr.W, H = gr.H;
        float[][] out = new float[3][W * H];
        double c = Math.cos(gr.psi), s = Math.sin(gr.psi);
        double[] o = new double[2];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double ox = (x - gr.oc) / gr.S, oy = (y - gr.oc) / gr.S;
                wp.fwd(gr.rcx + c * ox - s * oy, gr.rcy + s * ox + c * oy, o);
                for (int ch = 0; ch < 3; ch++) out[ch][y * W + x] = cubic(full[ch], w, h, o[0], o[1]);
            }
        }
        return out;
    }

    /** Catmull-Rom bicubic sampling (edges clamped). */
    static float cubic(float[] img, int w, int h, double x, double y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double fx = x - ix, fy = y - iy;
        double[] wx = cr(fx), wy = cr(fy);
        double v = 0;
        for (int b = 0; b < 4; b++) {
            int yy = Math.max(0, Math.min(h - 1, iy - 1 + b));
            double row = 0;
            for (int a = 0; a < 4; a++) row += wx[a] * img[yy * w + Math.max(0, Math.min(w - 1, ix - 1 + a))];
            v += wy[b] * row;
        }
        return (float) v;
    }

    private static double[] cr(double t) {
        double t2 = t * t, t3 = t2 * t;
        return new double[]{-0.5 * t3 + t2 - 0.5 * t, 1.5 * t3 - 2.5 * t2 + 1, -1.5 * t3 + 2 * t2 + 0.5 * t, 0.5 * t3 - 0.5 * t2};
    }

    /** The bright limb's blur: the sigma of the edge's derivative across it, the median over the lit edge (result's px). */
    public static double edgeSigma(float[] Y, int W, int H, double cx, double cy, double r) {
        float top = percentile(Y, 0.995);
        List<Double> sig = new ArrayList<Double>();
        for (int a = 0; a < 180; a++) {
            double th = a * Math.PI / 90, ux = Math.cos(th), uy = Math.sin(th);
            int n = 49;
            double[] prof = new double[n];
            for (int i = 0; i < n; i++) {
                double rr = r - 6 + i * 0.25;
                prof[i] = sample(Y, W, H, cx + ux * rr, cy + uy * rr);
            }
            if (prof[0] < 0.25 * top || prof[n - 1] > 0.08 * top || cx + ux * (r + 7) < 0 || cy + uy * (r + 7) < 0
                    || cx + ux * (r + 7) > W - 1 || cy + uy * (r + 7) > H - 1) continue;
            double s = 0, sm = 0, s2 = 0;
            for (int i = 1; i < n; i++) {
                double dv = prof[i - 1] - prof[i];
                if (dv <= 0) continue;
                double rr = i - 0.5;
                s += dv;
                sm += dv * rr;
                s2 += dv * rr * rr;
            }
            if (s <= 0) continue;
            double mean = sm / s, var = s2 / s - mean * mean;
            sig.add(Math.sqrt(Math.max(0, var)) * 0.25);
        }
        if (sig.size() < 5) return Double.NaN;
        java.util.Collections.sort(sig);
        return sig.get(sig.size() / 2);
    }

    /** Richardson-Lucy with a Gaussian blur, damped (each step's correction held to 0.7..1.4x). */
    static float[] richardsonLucy(float[] Y, int W, int H, double sigma, int iters, float floor) {
        float[] u = Y.clone();
        for (int k = 0; k < u.length; k++) u[k] = Math.max(floor, u[k]);
        for (int it = 0; it < iters; it++) {
            float[] bu = blur(u, W, H, sigma);
            float[] ratio = new float[u.length];
            for (int k = 0; k < u.length; k++) ratio[k] = Math.max(floor, Y[k]) / Math.max(floor, bu[k]);
            float[] c = blur(ratio, W, H, sigma);
            for (int k = 0; k < u.length; k++) u[k] *= Math.max(0.7f, Math.min(1.4f, c[k]));
        }
        return u;
    }

    // ------------------------------------------------------------------------------------------------

    /** The whole of it: frames in (each with its RAW crop), the Moon out. Null if no Moon was found. */
    public static Result stack(List<Frame> frames, SensorSpec spec, Options opt, Progress pr) {
        int n = frames.size();
        if (n == 0) return null;
        StringBuilder log = new StringBuilder();
        int w = frames.get(0).w, h = frames.get(0).h;
        List<float[]> bals = new ArrayList<float[]>(), lums = new ArrayList<float[]>();
        List<boolean[]> sats = new ArrayList<boolean[]>();
        double[] q = new double[n];
        double[][] cent = new double[n][];
        for (int i = 0; i < n; i++) {
            if (pr.cancelled()) throw new CancelledException();
            Frame f = frames.get(i);
            boolean[] sat = new boolean[f.w * f.h];
            float[] b = balanced(f, spec, sat);
            float[] L = luma(b, f.w, f.h);
            bals.add(b);
            sats.add(sat);
            lums.add(L);
            q[i] = quality(L, w, h);
            cent[i] = centroid(L, w, h);
            pr.update("Scoring the frames", (i + 1f) / n * 0.2f);
        }
        // frames whose lit part is much fainter or smaller than usual are clouded or half out of the crop
        double[] lit = new double[n];
        for (int i = 0; i < n; i++) lit[i] = cent[i][2];
        double[] litSorted = lit.clone();
        Arrays.sort(litSorted);
        double litMed = litSorted[n / 2];
        int ref = -1;
        for (int i = 0; i < n; i++) {
            if (lit[i] < 0.7 * litMed || lit[i] > 1.3 * litMed) continue;
            if (ref < 0 || q[i] > q[ref]) ref = i;
        }
        if (ref < 0) ref = 0;
        double refUp = frames.get(ref).upPa;
        double litDir = !Double.isNaN(opt.brightLimbPa) && !Double.isNaN(refUp) ? MoonGeo.norm360(opt.brightLimbPa - refUp) : Double.NaN;
        Disk disk = findDisk(lums.get(ref), w, h, opt.expectedRadius, litDir);
        log.append(String.format(Locale.US, "moon stack: %d frames %dx%d; reference %d (sharpness %.3g, the median %.3g); disk %s",
                n, w, h, ref, q[ref], median(q), disk));
        if (!disk.ok) {
            log.append("; no Moon found");
            Result bad = new Result();
            bad.log = log.toString();
            bad.total = n;
            bad.disk = disk;
            return bad;
        }
        // align everything to the reference, then again to the average of the best
        int[] msk = mask(w, h, disk, lums.get(ref));
        List<Warp> warps = new ArrayList<Warp>();
        float[] R = lums.get(ref);
        for (int pass = 0; pass < 2; pass++) {
            warps.clear();
            for (int i = 0; i < n; i++) {
                if (pr.cancelled()) throw new CancelledException();
                Warp init = new Warp();
                init.cx = disk.cx;
                init.cy = disk.cy;
                init.tx = cent[i][0] - cent[ref][0];
                init.ty = cent[i][1] - cent[ref][1];
                float[] F = lums.get(i);
                float[][] g = gradients(F, w, h);
                Warp wp = align(R, F, g[0], g[1], w, h, msk, init);
                warps.add(wp);
                pr.update(pass == 0 ? "Lining the frames up" : "Lining them up again, finer", 0.2f + 0.25f * pass + (i + 1f) / n * 0.25f);
            }
            if (pass == 1) break;
            // the average of the sharpest tenth (at least 5), warped onto the reference: a cleaner reference
            Integer[] order = byQuality(q);
            int m = Math.max(5, Math.min(n, n / 10));
            float[] avg = new float[w * h];
            int used = 0;
            double[] o = new double[2];
            for (int k = 0; k < n && used < m; k++) {
                int i = order[k];
                Warp wp = warps.get(i);
                if (!wp.ok) continue;
                float[] F = lums.get(i);
                for (int j = 0; j < h; j++) {
                    for (int x = 0; x < w; x++) {
                        wp.fwd(x, j, o);
                        avg[j * w + x] += (float) (wp.gain * sample(F, w, h, o[0], o[1]) + wp.off);
                    }
                }
                used++;
            }
            if (used >= 3) {
                for (int k = 0; k < avg.length; k++) avg[k] /= used;
                R = avg;
            }
        }
        // the sharpest that lined up, and not clouded
        Integer[] order = byQuality(q);
        int want = Math.max(opt.minKeep, (int) Math.round(opt.keep * n));
        boolean[] kept = new boolean[n];
        List<Frame> useF = new ArrayList<Frame>();
        List<float[]> useB = new ArrayList<float[]>();
        List<boolean[]> useS = new ArrayList<boolean[]>();
        List<Warp> useW = new ArrayList<Warp>();
        int bad = 0, cloudy = 0;
        for (int k = 0; k < n && useF.size() < want; k++) {
            int i = order[k];
            if (!warps.get(i).ok) {
                bad++;
                continue;
            }
            if (lit[i] < 0.7 * litMed || lit[i] > 1.3 * litMed) {
                cloudy++;
                continue;
            }
            kept[i] = true;
            useF.add(frames.get(i));
            useB.add(bals.get(i));
            useS.add(sats.get(i));
            useW.add(warps.get(i));
        }
        double[] shifts = new double[n];
        for (int i = 0; i < n; i++) shifts[i] = Math.hypot(warps.get(i).tx, warps.get(i).ty);
        log.append(String.format(Locale.US, "; aligned (rms %.4f on the reference), shaken %.1f px at the most, %.1f typical; %d didn't line up, "
                        + "%d clouded or cut off; merging the %d sharpest", warps.get(ref).rms, max(shifts), median(shifts), bad, cloudy,
                useF.size()));
        // the result's grid: the disk in the middle, turned so its up is the sky's
        Grid gr = new Grid();
        gr.S = opt.scale;
        gr.rcx = disk.cx;
        gr.rcy = disk.cy;
        gr.psi = !Double.isNaN(opt.outUpPa) && !Double.isNaN(refUp) ? Math.toRadians(refUp - opt.outUpPa) : 0;
        int half = (int) Math.ceil(gr.S * (disk.r + Math.max(8, 0.12 * disk.r)));
        gr.W = gr.H = 2 * half + 1;
        gr.oc = half;
        pr.update("Stacking the sharpest", 0.72f);
        double[] mc = moonColour(bals.get(ref), lums.get(ref), frames.get(ref), spec, disk);
        float[][] cam = drizzle(useB, useS, useF, useW, spec, gr, opt.fineKernel, opt.coarseKernel, mc);
        float[][] cam1 = ordinary(bals.get(ref), frames.get(ref), warps.get(ref), spec, gr);
        Result res = new Result();
        res.w = gr.W;
        res.h = gr.H;
        res.cx = gr.oc;
        res.cy = gr.oc;
        res.radius = disk.r * gr.S;
        res.upPa = !Double.isNaN(opt.outUpPa) && !Double.isNaN(refUp) ? opt.outUpPa : refUp;
        res.used = useF.size();
        res.total = n;
        res.reference = ref;
        res.quality = q;
        res.kept = kept;
        res.disk = disk;
        res.motion = new double[n][];
        for (int i = 0; i < n; i++) {
            Warp wp = warps.get(i);
            res.motion[i] = new double[]{wp.tx, wp.ty, Math.toDegrees(wp.th), wp.sc};
        }
        float[][] rgb = toSrgb(cam, spec), rgb1 = toSrgb(cam1, spec);
        // brightness: the bright parts at 1 (both by the stack's scale, so they compare)
        float[] Y = lum(rgb);
        float top = percentileIn(Y, gr.W, gr.oc, res.radius, 0.995);
        float scale = top > 1e-6f ? 1 / top : 1;
        mul(rgb, scale);
        mul(rgb1, scale);
        Y = lum(rgb);
        float[] Y1 = lum(rgb1);
        res.edgeSingle = edgeSigma(Y1, gr.W, gr.H, gr.oc, gr.oc, res.radius);
        res.edgeStack = edgeSigma(Y, gr.W, gr.H, gr.oc, gr.oc, res.radius);
        if (opt.deconvolve && !Double.isNaN(res.edgeStack)) {
            pr.update("Sharpening", 0.85f);
            double sig = Math.max(0.5, Math.min(3.0, opt.rlSigma * res.edgeStack));
            float floor = 0.002f;
            float[] Ys = richardsonLucy(Y, gr.W, gr.H, sig, opt.rlIterations, floor);
            for (int c = 0; c < 3; c++) {
                for (int k = 0; k < Y.length; k++) {
                    float y0 = Math.max(floor, Y[k]);
                    rgb[c][k] = rgb[c][k] * (Math.max(floor, Ys[k]) / y0);
                }
            }
            res.edgeSharp = edgeSigma(lum(rgb), gr.W, gr.H, gr.oc, gr.oc, res.radius);
            log.append(String.format(Locale.US, "; sharpened (Richardson-Lucy, sigma %.2f, %d steps)", sig, opt.rlIterations));
        }
        res.r = rgb[0];
        res.g = rgb[1];
        res.b = rgb[2];
        res.r1 = rgb1[0];
        res.g1 = rgb1[1];
        res.b1 = rgb1[2];
        log.append(String.format(Locale.US, "; the bright edge's blur: one frame %.2f px, stacked %.2f, sharpened %.2f (of %d)", res.edgeSingle,
                res.edgeStack, res.edgeSharp, gr.W));
        res.log = log.toString();
        pr.update("Done", 1f);
        return res;
    }

    static Integer[] byQuality(final double[] q) {
        Integer[] o = new Integer[q.length];
        for (int i = 0; i < q.length; i++) o[i] = i;
        Arrays.sort(o, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(q[b], q[a]);
            }
        });
        return o;
    }

    static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s.length == 0 ? Double.NaN : s[s.length / 2];
    }

    static double max(double[] v) {
        double m = 0;
        for (double x : v) m = Math.max(m, x);
        return m;
    }

    static float[][] toSrgb(float[][] cam, SensorSpec spec) {
        int np = cam[0].length;
        float[] m = spec.camToSrgb, nn = spec.camNeutral;
        float[][] o = new float[3][np];
        for (int k = 0; k < np; k++) {
            float r = cam[0][k] * nn[0], g = cam[1][k] * nn[1], b = cam[2][k] * nn[2];
            o[0][k] = m[0] * r + m[1] * g + m[2] * b;
            o[1][k] = m[3] * r + m[4] * g + m[5] * b;
            o[2][k] = m[6] * r + m[7] * g + m[8] * b;
        }
        return o;
    }

    static float[] lum(float[][] rgb) {
        int np = rgb[0].length;
        float[] y = new float[np];
        for (int k = 0; k < np; k++) y[k] = 0.2126f * rgb[0][k] + 0.7152f * rgb[1][k] + 0.0722f * rgb[2][k];
        return y;
    }

    static void mul(float[][] rgb, float s) {
        for (float[] c : rgb) for (int k = 0; k < c.length; k++) c[k] *= s;
    }

    /** Percentile of the values within radius r of the middle of a square W x W image. */
    static float percentileIn(float[] Y, int W, double oc, double r, double q) {
        float[] v = new float[Y.length];
        int n = 0;
        for (int j = 0; j < W; j++) {
            for (int i = 0; i < W; i++) {
                double dx = i - oc, dy = j - oc;
                if (dx * dx + dy * dy <= r * r) v[n++] = Y[j * W + i];
            }
        }
        if (n == 0) return 1;
        float[] s = Arrays.copyOf(v, n);
        Arrays.sort(s);
        return s[Math.min(n - 1, (int) Math.round(q * (n - 1)))];
    }
}
