package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.JupiterGeo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Jupiter and its four big moons through the Pixel's 5x telephoto, simulated (3.2), for Galileo's notebook's tests
 * and robots. Jupiter is a flattened, limb-darkened disc two or three pixels across; the moons and any stars are
 * points; all go through the lens (a Moffat core, its width {@link #fwhmPx}, and a faint wide halo of scattered light
 * from Jupiter, {@link #haloShare} of its light), the hand's blur, and the sensor: photons for the exposure's time
 * (not its ISO: the ISO only amplifies), shot noise, read noise in electrons and in counts, a black level, 10 bits,
 * a sky glow, and hot pixels fixed on the sensor. RGGB from the sensor's corner, as {@link MoonSim} draws.
 * <p>
 * Brightness: {@link #eV0} electrons a second, on a green pixel's colour, for a magnitude 0 point (all of its light);
 * about 2.5 times fainter than a phone's lens really gathers, so the tests are on the hard side.
 */
public final class JupiterSim {
    public final JupiterGeo.View view;
    /** The pixel scale (arcseconds), the picture's up as a position angle (degrees), Jupiter's equatorial radius (px). */
    public final double arcsecPerPx, upPa, rPx;
    /** The lens: the core's width at half height (px) and its Moffat beta; the halo's share of the light and its width (px). */
    public final double fwhmPx, beta = 3, haloShare, haloFwhmPx = 14;
    /** Electrons a second for magnitude 0 (green), and how much more the sensor gives than that. */
    public double eV0 = 6.6e4, sensitivity = 1;
    /** Read noise (electrons), the converter's own noise (counts), electrons a count at ISO 100, the sky (electrons a second a pixel). */
    public double readE = 1.6, adcDn = 0.6, e100 = 2.4, skyE = 3;
    /** The shot noise's share (1: the real thing; 0 for a test of what's left without it). */
    public double shot = 1;
    public int black = 64, white = 1023;
    /** The air's dimming (all alike). */
    public double air = 1;
    /** The camera's colour: counts per unit of light (green = 1), and each body's own colour. */
    public final double[] camRgb = {0.5, 1, 0.65};
    static final double[] JUPITER_RGB = {1.0, 0.93, 0.78};
    static final double[][] MOON_RGB = {{1.0, 0.92, 0.70}, {1.0, 0.98, 0.95}, {0.95, 0.90, 0.85}, {0.85, 0.80, 0.75}};
    /** The four's offsets from Jupiter in the picture (px, x right, y down, at {@link #upPa}), and whether each is drawn. */
    public final double[][] moonPx = new double[4][];
    public final boolean[] drawn = new boolean[4];
    /** Stars: {dx, dy (px, the picture at upPa), magnitude}. */
    public final List<double[]> stars = new ArrayList<double[]>();
    /** Hot pixels: {sensor x, sensor y, electrons a second}. */
    public final List<double[]> hot = new ArrayList<double[]>();
    /** A street light or a plane's light far from Jupiter: {sensor x, sensor y, electrons a second in all} (null: none). */
    public double[] lamp;

    // the drawn lights: the point's and Jupiter's, through the lens and each blur, as summed-area tables on a fine grid
    static final int OS = 8, B = 12, T = 2 * B * OS;
    final double[] blurs = {0, 0.4, 0.8, 1.2, 1.6, 2.4};
    final double[][] pointSat, jupiterSat;
    final double haloNorm;

    public JupiterSim(JupiterGeo.View v, double arcsecPerPx, double upPa, double fwhmPx, double haloShare) {
        this.view = v;
        this.arcsecPerPx = arcsecPerPx;
        this.upPa = upPa;
        this.fwhmPx = fwhmPx;
        this.haloShare = haloShare;
        rPx = v.radiusArcsec / arcsecPerPx;
        for (int k = 0; k < 4; k++) {
            JupiterGeo.Moon m = v.moons[k];
            moonPx[k] = JupiterGeo.inPicture(m, upPa, arcsecPerPx);
            drawn[k] = m.showing();
        }
        pointSat = new double[blurs.length][];
        jupiterSat = new double[blurs.length][];
        // Jupiter's disc on the fine grid: flattened along its pole, darker towards its edge
        double poleInPic = Math.toRadians(v.poleDeg - upPa);
        double px = -Math.sin(poleInPic), py = -Math.cos(poleInPic);
        int dr = (int) Math.ceil(rPx * OS) + 1;
        List<double[]> disc = new ArrayList<double[]>();
        double sum = 0;
        for (int j = -dr; j <= dr; j++) {
            for (int i = -dr; i <= dr; i++) {
                double x = (i + 0.5) / OS, y = (j + 0.5) / OS;
                double along = x * px + y * py, across = x * -py + y * px;
                double e = (across / rPx) * (across / rPx) + (along / (rPx * JupiterGeo.FLAT)) * (along / (rPx * JupiterGeo.FLAT));
                if (e >= 1) continue;
                double mu = Math.sqrt(1 - e);
                double lat = Math.abs(along / (rPx * JupiterGeo.FLAT));
                double belts = 1 - 0.22 * Math.exp(-Math.pow((lat - 0.28) / 0.08, 2));
                double w = Math.pow(mu, 0.45) * belts;
                disc.add(new double[]{i, j, w});
                sum += w;
            }
        }
        for (double[] d : disc) d[2] /= sum;
        for (int b = 0; b < blurs.length; b++) {
            double[] psf = psf(blurs[b]);
            double[] jup = new double[T * T];
            for (double[] d : disc) {
                int di = (int) d[0], dj = (int) d[1];
                double w = d[2];
                for (int j = 0; j < T; j++) {
                    int sj = j - dj;
                    if (sj < 0 || sj >= T) continue;
                    for (int i = 0; i < T; i++) {
                        int si = i - di;
                        if (si < 0 || si >= T) continue;
                        jup[j * T + i] += w * psf[sj * T + si];
                    }
                }
            }
            pointSat[b] = summed(psf);
            jupiterSat[b] = summed(jup);
        }
        // the halo: a wide Moffat (beta 1.5) whose light over the sky adds to 1
        double a = haloFwhmPx / (2 * Math.sqrt(Math.pow(2, 1 / 1.5) - 1));
        haloNorm = (1.5 - 1) / (Math.PI * a * a);
        haloA = a;
    }

    final double haloA;

    /** The lens's core (Moffat) with a Gaussian blur of sigma {@code blur} px more, on the fine grid, its light adding to 1. */
    double[] psf(double blur) {
        double a = fwhmPx / (2 * Math.sqrt(Math.pow(2, 1 / beta) - 1));
        double[] p = new double[T * T];
        double s = 0;
        for (int j = 0; j < T; j++) {
            for (int i = 0; i < T; i++) {
                double x = (i + 0.5) / OS - B, y = (j + 0.5) / OS - B;
                double v = Math.pow(1 + (x * x + y * y) / (a * a), -beta);
                p[j * T + i] = v;
                s += v;
            }
        }
        for (int k = 0; k < p.length; k++) p[k] /= s;
        if (blur > 0) p = gauss(p, blur * OS);
        return p;
    }

    static double[] gauss(double[] img, double sigma) {
        int r = (int) Math.ceil(3 * sigma);
        double[] k = new double[2 * r + 1];
        double s = 0;
        for (int i = -r; i <= r; i++) s += k[i + r] = Math.exp(-i * i / (2 * sigma * sigma));
        for (int i = 0; i < k.length; i++) k[i] /= s;
        double[] tmp = new double[T * T], out = new double[T * T];
        for (int j = 0; j < T; j++) {
            for (int i = 0; i < T; i++) {
                double v = 0;
                for (int d = -r; d <= r; d++) {
                    int x = i + d;
                    if (x >= 0 && x < T) v += k[d + r] * img[j * T + x];
                }
                tmp[j * T + i] = v;
            }
        }
        for (int j = 0; j < T; j++) {
            for (int i = 0; i < T; i++) {
                double v = 0;
                for (int d = -r; d <= r; d++) {
                    int y = j + d;
                    if (y >= 0 && y < T) v += k[d + r] * tmp[y * T + i];
                }
                out[j * T + i] = v;
            }
        }
        return out;
    }

    static double[] summed(double[] img) {
        int n = T + 1;
        double[] s = new double[n * n];
        for (int j = 0; j < T; j++) {
            double row = 0;
            for (int i = 0; i < T; i++) {
                row += img[j * T + i];
                s[(j + 1) * n + i + 1] = s[j * n + i + 1] + row;
            }
        }
        return s;
    }

    /** The summed-area table at a fine-grid place (bilinear between its corners). */
    static double at(double[] s, double x, double y) {
        int n = T + 1;
        x = Math.max(0, Math.min(T, x));
        y = Math.max(0, Math.min(T, y));
        int i = (int) Math.min(T - 1, Math.floor(x)), j = (int) Math.min(T - 1, Math.floor(y));
        double fx = x - i, fy = y - j;
        double a = s[j * n + i], b = s[j * n + i + 1], c = s[(j + 1) * n + i], d = s[(j + 1) * n + i + 1];
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
    }

    /** The share of a light's whole that falls on a pixel (its corner at X, Y) when the light is at (cx, cy). */
    static double share(double[] sat, double X, double Y, double cx, double cy) {
        double x0 = (X - cx + B) * OS, y0 = (Y - cy + B) * OS;
        return at(sat, x0 + OS, y0 + OS) - at(sat, x0, y0 + OS) - at(sat, x0 + OS, y0) + at(sat, x0, y0);
    }

    int blurIndex(double blur) {
        int bi = 0;
        for (int k = 1; k < blurs.length; k++) if (Math.abs(blurs[k] - blur) < Math.abs(blurs[bi] - blur)) bi = k;
        return bi;
    }

    /** Electrons a second, for colour c, from a point of magnitude m. */
    double rate(double mag, int c, double[] rgb) {
        return eV0 * sensitivity * air * Math.pow(10, -0.4 * mag) * camRgb[c] * rgb[c];
    }

    /** An offset in the picture at {@link #upPa} turned with the frame ({@code rotDeg} counterclockwise as seen, as {@link MoonSim} turns). */
    static double[] turn(double dx, double dy, double rotDeg) {
        double a = -Math.toRadians(rotDeg), cs = Math.cos(a), sn = Math.sin(a);
        return new double[]{dx * cs - dy * sn, dx * sn + dy * cs};
    }

    /** Moon k's place on the sensor for Jupiter at (jx, jy) and the frame turned by {@code rotDeg}. */
    public double[] moonAt(int k, double jx, double jy, double rotDeg) {
        double[] o = turn(moonPx[k][0], moonPx[k][1], rotDeg);
        return new double[]{jx + o[0], jy + o[1]};
    }

    /** A star at arcseconds east and north of Jupiter. */
    public void addStar(double east, double north, double mag) {
        double[] p = JupiterGeo.inPicture(east, north, upPa, arcsecPerPx);
        stars.add(new double[]{p[0], p[1], mag});
    }

    /** Hot pixels scattered over a w x h sensor. */
    public void addHot(int n, int w, int h, long seed, double rate) {
        Random r = new Random(seed);
        for (int i = 0; i < n; i++) hot.add(new double[]{r.nextInt(w), r.nextInt(h), rate * (0.5 + r.nextDouble())});
    }

    /**
     * One frame's RAW crop: w x h at (x0, y0) on the sensor, Jupiter's middle at (jx, jy) on the sensor, the frame
     * turned {@code rotDeg}, blurred {@code blurPx} more by the hand, exposed {@code ns} at {@code iso}.
     */
    public short[] frame(int w, int h, int x0, int y0, double jx, double jy, double rotDeg, double blurPx, long ns, int iso, Random rnd) {
        double[] e = new double[w * h];
        double t = ns * 1e-9;
        int bi = blurIndex(blurPx);
        int[] cfa = {0, 1, 1, 2};
        // Jupiter
        double[] jr = new double[3];
        for (int c = 0; c < 3; c++) jr[c] = rate(view.mag, c, JUPITER_RGB) * t;
        addLight(e, w, h, x0, y0, jupiterSat[bi], jx, jy, jr, cfa);
        // its halo
        double jt = rate(view.mag, 1, new double[]{1, 1, 1}) * t * haloShare;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int X = x0 + i, Y = y0 + j;
                double dx = X + 0.5 - jx, dy = Y + 0.5 - jy;
                int c = cfa[(Y & 1) * 2 + (X & 1)];
                e[j * w + i] += jt * camRgb[c] * JUPITER_RGB[c] * haloNorm * Math.pow(1 + (dx * dx + dy * dy) / (haloA * haloA), -1.5);
            }
        }
        // the moons and the stars
        for (int k = 0; k < 4; k++) {
            if (!drawn[k]) continue;
            double[] p = moonAt(k, jx, jy, rotDeg);
            double[] r = new double[3];
            for (int c = 0; c < 3; c++) r[c] = rate(view.moons[k].mag, c, MOON_RGB[k]) * t;
            addLight(e, w, h, x0, y0, pointSat[bi], p[0], p[1], r, cfa);
        }
        for (double[] s : stars) {
            double[] o = turn(s[0], s[1], rotDeg);
            double[] r = new double[3];
            for (int c = 0; c < 3; c++) r[c] = rate(s[2], c, new double[]{1, 1, 1}) * t;
            addLight(e, w, h, x0, y0, pointSat[bi], jx + o[0], jy + o[1], r, cfa);
        }
        if (lamp != null) {
            double[] r = new double[3];
            for (int c = 0; c < 3; c++) r[c] = lamp[2] * t * camRgb[c];
            addLight(e, w, h, x0, y0, pointSat[blurIndex(2.4)], lamp[0], lamp[1], r, cfa);
        }
        // the sensor
        for (double[] hp : hot) {
            int i = (int) hp[0] - x0, j = (int) hp[1] - y0;
            if (i >= 0 && i < w && j >= 0 && j < h) e[j * w + i] += hp[2] * t;
        }
        double perDn = e100 * 100.0 / Math.max(1, iso);
        short[] out = new short[w * h];
        for (int k = 0; k < w * h; k++) {
            double el = e[k] + skyE * t;
            el += shot * Math.sqrt(Math.max(0, el)) * rnd.nextGaussian() + readE * rnd.nextGaussian();
            long dn = Math.round(el / perDn + black + adcDn * rnd.nextGaussian());
            out[k] = (short) Math.max(0, Math.min(white, dn));
        }
        return out;
    }

    /** A light's electrons onto the window (per colour {@code r}) through a drawn shape centred at (cx, cy). */
    static void addLight(double[] e, int w, int h, int x0, int y0, double[] sat, double cx, double cy, double[] r, int[] cfa) {
        int i0 = Math.max(0, (int) Math.floor(cx - B) - x0), i1 = Math.min(w - 1, (int) Math.ceil(cx + B) - x0);
        int j0 = Math.max(0, (int) Math.floor(cy - B) - y0), j1 = Math.min(h - 1, (int) Math.ceil(cy + B) - y0);
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                int X = x0 + i, Y = y0 + j;
                double s = share(sat, X, Y, cx, cy);
                if (s <= 0) continue;
                e[j * w + i] += r[cfa[(Y & 1) * 2 + (X & 1)]] * s;
            }
        }
    }

    /** The light Jupiter's brightest pixel gets (share of its whole) at a blur: for checking the meter. */
    public double peakShare(double blurPx) {
        double[] s = jupiterSat[blurIndex(blurPx)];
        double best = 0;
        for (int j = -3; j <= 3; j++) for (int i = -3; i <= 3; i++) best = Math.max(best, share(s, i - 0.5, j - 0.5, 0, 0));
        return best;
    }
}
