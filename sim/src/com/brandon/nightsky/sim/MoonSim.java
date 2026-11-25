package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.MoonGeo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A simulated Moon (3.0, the Moonshot) as the Pixel's 5x telephoto would see it at a time and place: the face turned
 * and lit as {@link MoonGeo} says (libration, the axis's tilt, the phase, the Sun's height over every point), its
 * seas where the real ones are, bright rayed craters (Tycho, Copernicus, Kepler, Aristarchus), thousands of craters
 * with walls the low Sun shades along the terminator, and the Moon's own photometry (Lommel-Seeliger, which keeps
 * the full Moon as bright at its edge as in its middle).
 * <p>
 * The Moon is drawn once, finely ({@code os} samples a sensor pixel) and blurred as the f/2.8 lens blurs (about
 * 18"); frames are then taken from it as the sensor would: wherever the shaking hand has put the Moon, turned a
 * little, blurred a little more or less (the hand, the air), each pixel the light that falls on its whole area,
 * through a Bayer filter, with the light's shot noise and the read noise, as 10-bit RAW counts.
 */
public final class MoonSim {
    public final MoonGeo.View view;
    /** The sensor rows' up as a position angle (degrees), and the Moon's radius in sensor pixels. */
    public final double upPa, rPx;
    final int os, T;
    /** Blurs of the drawn Moon (extra sigma in sensor pixels) and their summed-area tables: [blur][channel]. */
    final double[] blurs = {0, 0.5, 1.0, 1.6, 2.4};
    final double[][][] sat;
    /** The drawn Moon itself, unblurred: {r, g, b}, T x T, and its summed-area tables. */
    final float[][] truth;
    final double[][] satTruth;
    /** The camera's colour: raw counts per unit of sRGB light (green = 1), as ColorMath's fallback assumes. */
    public final double[] camRgb = {0.5, 1, 0.65};
    public int black = 64, white = 1023;
    /** Electrons for a value of 1 (the bright highlands of a full Moon), and per raw count. */
    public double electrons = 1600, ePerDn = 2.4, readE = 1.6;
    /** The Earth's glow on the night side, as a share of the day side's brightness. */
    public double earthshine = 0.0015;

    /**
     * @param arcsecPerPx the sensor's pixel scale
     * @param upPa        the sensor rows' up as a position angle (degrees from north through east)
     * @param os          samples per sensor pixel across
     */
    public MoonSim(long unixMs, double latDeg, double lonDeg, double arcsecPerPx, double upPa, int os) {
        this(unixMs, latDeg, lonDeg, arcsecPerPx, upPa, os, null);
    }

    /** Beacons: lamps on the Moon {lat, lon, radius (degrees), brightness}, to see where places come out in the pictures. */
    final double[][] beacons;

    public MoonSim(long unixMs, double latDeg, double lonDeg, double arcsecPerPx, double upPa, int os, double[][] beacons) {
        this.beacons = beacons;
        view = MoonGeo.view(unixMs, latDeg, lonDeg);
        this.upPa = upPa;
        this.os = os;
        rPx = view.radius * 3600 / arcsecPerPx;
        T = 2 * (int) Math.ceil(os * rPx * 1.12) + 2;
        truth = draw();
        satTruth = new double[3][];
        for (int c = 0; c < 3; c++) satTruth[c] = summed(truth[c], T);
        // the lens: 18" across at half height
        double lensSigma = 18.0 / 2.355 / arcsecPerPx;
        sat = new double[blurs.length][][];
        for (int b = 0; b < blurs.length; b++) {
            double s = Math.sqrt(lensSigma * lensSigma + blurs[b] * blurs[b]) * os;
            sat[b] = new double[3][];
            for (int c = 0; c < 3; c++) sat[b][c] = summed(blur(truth[c], T, s), T);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // the Moon's face

    static final double D = Math.PI / 180;

    /** Seas: lat, lon, radius (degrees), darkness (0..1), blueness (-1 red .. 1 blue: titanium-rich lavas are bluer). */
    static final double[][] MARIA = {
            {18, -57, 15, 0.85, 0.4}, {5, -48, 11, 0.8, 0.5}, {30, -47, 11, 0.85, 0.3}, {-2, -40, 9, 0.75, 0.4}, // Procellarum
            {33, -16, 16, 0.9, -0.3}, {44, -31, 4.5, 0.75, -0.2}, // Imbrium, Sinus Iridum
            {28, 17.5, 10.5, 0.9, -0.5}, {8.5, 31.4, 11, 0.95, 1.0}, {17, 59, 8.5, 0.9, 0.2}, // Serenitatis, Tranquillitatis, Crisium
            {-7.8, 51.3, 9, 0.8, 0.3}, {-15.2, 35.5, 5.5, 0.8, 0.2}, {-21.3, -16.6, 10, 0.7, 0.2}, // Fecunditatis, Nectaris, Nubium
            {-24.4, -38.6, 6.4, 0.8, 0.2}, {56, -30, 6, 0.6, 0}, {57, 0, 6, 0.6, 0}, {57, 25, 5.5, 0.55, 0}, // Humorum, Frigoris
            {13.3, 3.6, 4, 0.75, 0}, {-10, -23, 5, 0.7, 0.3}, {7.5, -31, 6.5, 0.7, 0.3}, {2.4, 1.7, 3, 0.6, 0}, // Vaporum, Cognitum, ...
            {1.3, 87.5, 5.5, 0.8, 0}, {13.3, 86.1, 4.5, 0.75, 0}, {-38.9, 93, 6, 0.6, 0}, // Smythii, Marginis, Australe
            {51.6, -9.4, 1.65, 0.7, 0}, {-5.2, -68.6, 2.9, 0.8, 0}, // Plato's and Grimaldi's dark floors
    };

    /** Bright young craters with rays: lat, lon, radius (degrees), ray length (degrees), brightness. */
    static final double[][] RAYED = {
            {-43.3, -11.2, 1.4, 45, 0.55}, {9.6, -20.1, 1.55, 25, 0.45}, {8.1, -38.0, 1.0, 15, 0.4},
            {23.7, -47.4, 0.75, 8, 0.9}, {16.1, 46.8, 0.5, 10, 0.35},
    };

    /** Named big craters for the relief: lat, lon, radius (degrees). */
    static final double[][] BIG = {
            {-58.4, -14.4, 3.8}, {-9.3, -1.9, 2.5}, {-13.4, -2.8, 1.9}, {-18.2, -1.9, 1.6}, {-11.4, 26.4, 1.65},
            {9.6, -20.1, 1.55}, {-43.3, -11.2, 1.4}, {51.6, -9.4, 1.65}, {-5.2, -68.6, 2.9}, {-51.0, -0.5, 2.3},
            {-35.0, 2.0, 1.5}, {29.5, 10.0, 1.4},
    };

    private final List<double[]> craters = new ArrayList<double[]>();
    /** The craters a point is inside (set by slope). */
    private final List<double[]> inside = new ArrayList<double[]>();
    private List<double[]>[] cells;
    private static final int CELL = 6;

    @SuppressWarnings("unchecked")
    private void makeCraters() {
        Random r = new Random(1969);
        for (double[] b : BIG) craters.add(crater(b[0], b[1], b[2]));
        for (int i = 0; i < 3500; i++) {
            double z = 2 * r.nextDouble() - 1, lon = r.nextDouble() * 360 - 180, lat = Math.asin(z) / D;
            // sizes: N(>r) ~ 1/r^2 from 0.2 to 2.5 degrees (6 to 75 km)
            double rad = 0.2 / Math.sqrt(1 - r.nextDouble() * (1 - 0.2 * 0.2 / (2.5 * 2.5)));
            // fewer on the seas (younger ground)
            if (mare(lat, lon)[0] > 0.4 && r.nextDouble() < 0.75) continue;
            craters.add(crater(lat, lon, rad));
        }
        int nl = 180 / CELL, nn = 360 / CELL;
        cells = new List[nl * nn];
        for (int k = 0; k < cells.length; k++) cells[k] = new ArrayList<double[]>();
        for (double[] c : craters) {
            double reach = c[3] * 1.8 / D;
            double lat = c[4], lon = c[5];
            for (int a = (int) Math.floor((lat - reach + 90) / CELL); a <= (int) Math.floor((lat + reach + 90) / CELL); a++) {
                if (a < 0 || a >= nl) continue;
                double cl = Math.cos(Math.min(89, Math.abs(lat) + reach) * D);
                double lr = Math.min(180, reach / Math.max(0.05, cl));
                for (int o = (int) Math.floor((lon - lr + 180) / CELL); o <= (int) Math.floor((lon + lr + 180) / CELL); o++) {
                    int oo = ((o % nn) + nn) % nn;
                    cells[a * nn + oo].add(c);
                }
            }
        }
    }

    /** A crater: its centre's unit vector, angular radius (radians), lat and lon. */
    private static double[] crater(double lat, double lon, double radDeg) {
        double cl = Math.cos(lat * D);
        return new double[]{cl * Math.cos(lon * D), cl * Math.sin(lon * D), Math.sin(lat * D), radDeg * D, lat, lon};
    }

    /** {darkness 0..1, blueness} of the seas at a place. */
    static double[] mare(double lat, double lon) {
        double dark = 0, blue = 0, wsum = 0;
        double cl = Math.cos(lat * D);
        double px = cl * Math.cos(lon * D), py = cl * Math.sin(lon * D), pz = Math.sin(lat * D);
        for (double[] m : MARIA) {
            double ml = Math.cos(m[0] * D);
            double dot = px * ml * Math.cos(m[1] * D) + py * ml * Math.sin(m[1] * D) + pz * Math.sin(m[0] * D);
            double d = Math.acos(Math.max(-1, Math.min(1, dot))) / D;
            // ragged shores: the distance wobbled by a slow noise on the sphere
            d += m[2] * 0.35 * (2 * noise3(px * 5 + 17, py * 5 + 3, pz * 5 - 11) - 1);
            double t = 1 - d / m[2];
            if (t <= 0) continue;
            double s = Math.min(1, t * 3);
            s = s * s * (3 - 2 * s);
            double v = s * m[3] * (0.85 + 0.3 * noise3(px * 11 - 5, py * 11 + 7, pz * 11 + 2));
            if (v > dark) dark = v;
            blue += v * m[4];
            wsum += v;
        }
        return new double[]{dark, wsum > 0 ? blue / wsum : 0};
    }

    /** The height field's slope at a point (unit vector p): its gradient in the tangent plane, as a 3-vector. */
    private void slope(double px, double py, double pz, double lat, double lon, double[] g) {
        g[0] = g[1] = g[2] = g[3] = 0;
        inside.clear();
        int nl = 180 / CELL, nn = 360 / CELL;
        int a = Math.max(0, Math.min(nl - 1, (int) Math.floor((lat + 90) / CELL)));
        int o = ((int) Math.floor((lon + 180) / CELL) % nn + nn) % nn;
        for (double[] c : cells[a * nn + o]) {
            double dot = px * c[0] + py * c[1] + pz * c[2];
            if (dot < Math.cos(c[3] * 1.8)) continue;
            double d = Math.acos(Math.min(1, dot)), r = d / c[3];
            // depth 0.12 of the diameter; a rim a fifth of the depth that falls away outside
            double depth = 0.24 * c[3], rim = 0.2 * depth, dhdr;
            if (r < 1) {
                dhdr = 2 * depth * r;
                g[3] += depth * (r * r - 1) + rim;
                inside.add(c);
            } else {
                double e = Math.exp(-(r - 1) * (r - 1) / 0.12);
                dhdr = -rim * 2 * (r - 1) / 0.12 * e;
                g[3] += rim * e;
            }
            double dh = dhdr / c[3];
            // the direction away from the crater's centre in the tangent plane
            double ux = px - dot * c[0], uy = py - dot * c[1], uz = pz - dot * c[2];
            double un = Math.sqrt(ux * ux + uy * uy + uz * uz);
            if (un < 1e-12) continue;
            // that direction is (p - dot c): away from c means towards p
            g[0] += dh * (c[0] - dot * px) / un * -1;
            g[1] += dh * (c[1] - dot * py) / un * -1;
            g[2] += dh * (c[2] - dot * pz) / un * -1;
        }
    }

    /** Small-scale texture: value noise on the sphere, a few octaves, about +-6%. */
    private static double texture(double x, double y, double z) {
        double s = 0, amp = 0.06, f = 24;
        for (int k = 0; k < 4; k++) {
            s += amp * (noise3(x * f, y * f, z * f) - 0.5) * 2;
            amp *= 0.55;
            f *= 2.1;
        }
        return s;
    }

    private static double noise3(double x, double y, double z) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = x - ix, fy = y - iy, fz = z - iz;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        fz = fz * fz * (3 - 2 * fz);
        double v = 0;
        for (int c = 0; c < 8; c++) {
            int dx = c & 1, dy = (c >> 1) & 1, dz = (c >> 2) & 1;
            double w = (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy) * (dz == 1 ? fz : 1 - fz);
            v += w * hash(ix + dx, iy + dy, iz + dz);
        }
        return v;
    }

    private static double hash(int x, int y, int z) {
        long h = x * 374761393L + y * 668265263L + z * 2147483647L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= h >>> 16;
        return (h & 0xffffff) / (double) 0xffffff;
    }

    private float[][] draw() {
        makeCraters();
        float[][] out = new float[3][T * T];
        double phi = view.p - upPa;
        double l = view.l * D, b = view.b * D;
        // the disk's axes on the Moon: towards the observer, east and north there
        double[] ez = {Math.cos(b) * Math.cos(l), Math.cos(b) * Math.sin(l), Math.sin(b)};
        double[] ex = {-Math.sin(l), Math.cos(l), 0};
        double[] ey = {-Math.sin(b) * Math.cos(l), -Math.sin(b) * Math.sin(l), Math.cos(b)};
        double sl = view.b0 * D, so = view.l0 * D;
        double[] sun = {Math.cos(sl) * Math.cos(so), Math.cos(sl) * Math.sin(so), Math.sin(sl)};
        double phaseFactor = Math.pow(10, -0.4 * 0.016 * view.phase);
        double half = T / 2.0, scale = os * rPx;
        double[] g = new double[4];
        for (int j = 0; j < T; j++) {
            for (int i = 0; i < T; i++) {
                double u = (i - half + 0.5) / scale, v = -(j - half + 0.5) / scale;
                double rr = u * u + v * v;
                if (rr >= 1) continue;
                double[] xy = MoonGeo.turn(u, v, -phi);
                double x = xy[0], y = xy[1], z = Math.sqrt(Math.max(0, 1 - x * x - y * y));
                double px = x * ex[0] + y * ey[0] + z * ez[0], py = x * ex[1] + y * ey[1] + z * ez[1], pz = x * ex[2] + y * ey[2] + z * ez[2];
                double lat = Math.asin(Math.max(-1, Math.min(1, pz))) / D, lon = Math.atan2(py, px) / D;
                double[] m = mare(lat, lon);
                double alb = (1 - 0.48 * m[0]) * (1 + texture(px, py, pz));
                for (double[] rc : RAYED) {
                    double cl = Math.cos(rc[0] * D);
                    double cx = cl * Math.cos(rc[1] * D), cy = cl * Math.sin(rc[1] * D), cz = Math.sin(rc[0] * D);
                    double dot = px * cx + py * cy + pz * cz, d = Math.acos(Math.max(-1, Math.min(1, dot))) / D;
                    if (d > rc[3]) continue;
                    double halo = Math.exp(-Math.pow(d / (rc[2] * 2.2), 2));
                    // the rays: a dozen directions, fading with distance
                    double bx = (pz - dot * cz), by = (px * cy - py * cx);
                    double bearing = Math.atan2(by, bx), ray = 0;
                    Random rr2 = new Random((long) (rc[0] * 1000 + rc[1]));
                    for (int k = 0; k < 12; k++) {
                        double dir = rr2.nextDouble() * 2 * Math.PI, wdt = 0.04 + 0.05 * rr2.nextDouble();
                        double db = Math.abs(((bearing - dir) % (2 * Math.PI) + 3 * Math.PI) % (2 * Math.PI) - Math.PI);
                        ray += Math.exp(-db * db / (wdt * wdt)) * (0.5 + 0.5 * rr2.nextDouble());
                    }
                    alb += rc[4] * (halo + 0.45 * Math.min(1, ray) * Math.exp(-d / (rc[3] * 0.45)));
                }
                slope(px, py, pz, lat, lon, g);
                double nx = px - g[0], ny = py - g[1], nz = pz - g[2], nn = Math.sqrt(nx * nx + ny * ny + nz * nz);
                nx /= nn;
                ny /= nn;
                nz /= nn;
                double mu0 = nx * sun[0] + ny * sun[1] + nz * sun[2];
                // the Sun's height over the smooth sphere here: past the terminator only what stands high is lit
                double mus = px * sun[0] + py * sun[1] + pz * sun[2];
                if (mus < -Math.sqrt(2 * Math.max(0, g[3]))) mu0 = 0;
                // inside a crater, the shadow of its sunward wall
                if (mu0 > 0 && !inside.isEmpty()) {
                    double shx = sun[0] - mus * px, shy = sun[1] - mus * py, shz = sun[2] - mus * pz;
                    double shn = Math.sqrt(shx * shx + shy * shy + shz * shz);
                    double tanE = shn > 1e-9 ? mus / shn : 1e9;
                    for (double[] c : inside) {
                        if (shn < 1e-9) break;
                        double dot = px * c[0] + py * c[1] + pz * c[2];
                        // the point within the crater, in its radii, and the way to the Sun
                        double qx = (px - dot * c[0]) / c[3], qy = (py - dot * c[1]) / c[3], qz = (pz - dot * c[2]) / c[3];
                        double qs = (qx * shx + qy * shy + qz * shz) / shn, qq = qx * qx + qy * qy + qz * qz;
                        double t = -qs + Math.sqrt(Math.max(0, qs * qs - qq + 1));
                        if (mus <= 0 || 0.24 * qq + tanE * t < 0.24) {
                            mu0 = 0;
                            break;
                        }
                    }
                }
                double mu = Math.max(0.02, nx * ez[0] + ny * ez[1] + nz * ez[2]);
                double lum = mu0 > 0 ? alb * 2 * mu0 / (mu0 + mu) * phaseFactor : 0;
                // the Earth's glow on the night side (and under the day too, unseen there)
                lum += earthshine * alb;
                if (beacons != null) {
                    for (double[] bc : beacons) {
                        double cl = Math.cos(bc[0] * D);
                        double dot = px * cl * Math.cos(bc[1] * D) + py * cl * Math.sin(bc[1] * D) + pz * Math.sin(bc[0] * D);
                        double d = Math.acos(Math.max(-1, Math.min(1, dot))) / D;
                        if (d < 3 * bc[2]) lum += bc[3] * Math.exp(-(d / bc[2]) * (d / bc[2]));
                    }
                }
                double blue = m[1] * m[0];
                int k = j * T + i;
                out[0][k] = (float) (lum * (1.0 - 0.04 * blue) * 1.0);
                out[1][k] = (float) (lum * 0.975);
                out[2][k] = (float) (lum * (0.93 + 0.06 * blue));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // the camera

    static float[] blur(float[] img, int n, double sigma) {
        int rad = Math.max(1, (int) Math.ceil(3 * sigma));
        double[] k = new double[2 * rad + 1];
        double s = 0;
        for (int i = -rad; i <= rad; i++) s += k[i + rad] = Math.exp(-i * i / (2 * sigma * sigma));
        for (int i = 0; i < k.length; i++) k[i] /= s;
        float[] t = new float[n * n], o = new float[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double v = 0;
                for (int d = -rad; d <= rad; d++) {
                    int x = i + d;
                    if (x >= 0 && x < n) v += k[d + rad] * img[j * n + x];
                }
                t[j * n + i] = (float) v;
            }
        }
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double v = 0;
                for (int d = -rad; d <= rad; d++) {
                    int y = j + d;
                    if (y >= 0 && y < n) v += k[d + rad] * t[y * n + i];
                }
                o[j * n + i] = (float) v;
            }
        }
        return o;
    }

    static double[] summed(float[] img, int n) {
        double[] s = new double[(n + 1) * (n + 1)];
        for (int j = 0; j < n; j++) {
            double row = 0;
            for (int i = 0; i < n; i++) {
                row += img[j * n + i];
                s[(j + 1) * (n + 1) + i + 1] = s[j * (n + 1) + i + 1] + row;
            }
        }
        return s;
    }

    /** The summed-area table at a fractional corner (bilinear: exact for the box integral of the pixels). */
    private double at(double[] s, double x, double y) {
        int n = T + 1;
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x > T) x = T;
        if (y > T) y = T;
        int ix = Math.min(T - 1, (int) x), iy = Math.min(T - 1, (int) y);
        double fx = x - ix, fy = y - iy;
        double a = s[iy * n + ix], b = s[iy * n + ix + 1], c = s[(iy + 1) * n + ix], d = s[(iy + 1) * n + ix + 1];
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
    }

    /**
     * The light (value 1: full-Moon highlands) on a sensor pixel's whole area, for colour channel c, the Moon's
     * centre at (mx, my) in sensor pixels, the frame turned by {@code rotDeg} (counterclockwise as seen) and blurred
     * by {@code blurPx} more (sensor pixels, the nearest of the drawn blurs).
     */
    public double light(int c, double X, double Y, double mx, double my, double rotDeg, double blurPx) {
        int bi = 0;
        for (int k = 1; k < blurs.length; k++) if (Math.abs(blurs[k] - blurPx) < Math.abs(blurs[bi] - blurPx)) bi = k;
        double dx = X - mx, dy = Y - my;
        // the frame turned counterclockwise as seen (y down): the Moon in it turned the same way
        double a = -rotDeg * D, cs = Math.cos(a), sn = Math.sin(a);
        double rx = dx * cs + dy * sn, ry = -dx * sn + dy * cs;
        double tx = T / 2.0 + rx * os, ty = T / 2.0 + ry * os, h = os / 2.0;
        double[] s = sat[bi][c];
        double sum = at(s, tx + h, ty + h) - at(s, tx - h, ty + h) - at(s, tx + h, ty - h) + at(s, tx - h, ty - h);
        return sum / (os * os);
    }

    /** One frame's RAW crop (w x h at x0, y0 on the sensor, RGGB from the sensor's corner), the light scaled by gain. */
    public short[] frame(int w, int h, int x0, int y0, double mx, double my, double rotDeg, double blurPx, double gain, Random rnd) {
        return frame(w, h, x0, y0, mx, my, rotDeg, blurPx, gain, rnd, 0);
    }

    /**
     * A space station in front of the Moon (the Transit Hunter's test), or null: {x, y (sensor px from the Moon's
     * centre, in the Moon's frame) at time 0, vx, vy (px/s), its truss's angle (degrees), px per 100 m}.
     */
    public double[] station;

    /**
     * The station's opacity at (rx, ry) (px from the Moon's centre, in the Moon's frame) at {@code t} seconds, blurred by
     * {@code b} px: the ISS's shape in soft pieces (its 109 m truss, the solar arrays at its ends, the modules across it).
     */
    public double stationAt(double rx, double ry, double t, double b) {
        if (station == null) return 0;
        double cx = station[0] + station[2] * t, cy = station[1] + station[3] * t;
        if (Math.abs(rx - cx) > 12 || Math.abs(ry - cy) > 12) return 0;
        double a = Math.toRadians(station[4]), ca = Math.cos(a), sa = Math.sin(a);
        double dx = rx - cx, dy = ry - cy;
        double u = dx * ca + dy * sa, v = -dx * sa + dy * ca;
        double s = station[5];
        double occ = piece(u, v, 0, 1.09 * s, 0.08 * s, 0.9, b) + piece(u, v, -0.45 * s, 0.25 * s, 0.73 * s, 0.6, b)
                + piece(u, v, 0.45 * s, 0.25 * s, 0.73 * s, 0.6, b) + piece(u, v, 0, 0.12 * s, 0.6 * s, 0.9, b);
        return Math.min(0.97, occ);
    }

    /** A soft rectangle (len along u, wid along v, centred at u = cu) of the given opacity, blurred by b. */
    static double piece(double u, double v, double cu, double len, double wid, double opacity, double b) {
        double su = Math.sqrt(len * len / 12 + b * b), sv = Math.sqrt(wid * wid / 12 + b * b);
        double amp = opacity * len * wid / (2 * Math.PI * su * sv);
        double du = u - cu;
        return amp * Math.exp(-(du * du / (2 * su * su) + v * v / (2 * sv * sv)));
    }

    /** One frame at {@code tSec} (for the station's place). */
    public short[] frame(int w, int h, int x0, int y0, double mx, double my, double rotDeg, double blurPx, double gain, Random rnd, double tSec) {
        short[] out = new short[w * h];
        int[] cfa = {0, 1, 1, 2};
        double a = -rotDeg * D, cs = Math.cos(a), sn = Math.sin(a), sb = Math.sqrt(blurPx * blurPx + 0.25);
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int X = x0 + i, Y = y0 + j;
                int c = cfa[(Y & 1) * 2 + (X & 1)];
                double e = light(c, X, Y, mx, my, rotDeg, blurPx) * camRgb[c] * electrons * gain;
                if (station != null) {
                    double dx = X - mx, dy = Y - my;
                    e *= 1 - stationAt(dx * cs + dy * sn, -dx * sn + dy * cs, tSec, sb);
                }
                e += Math.sqrt(Math.max(0, e) + readE * readE) * rnd.nextGaussian();
                long dn = Math.round(e / ePerDn + black);
                out[j * w + i] = (short) Math.max(0, Math.min(white, dn));
            }
        }
        return out;
    }

    /** The drawn Moon (no lens blur) at a place relative to its centre (sensor pixels, the sensor rows' frame), channel c. */
    public double truth(int c, double dx, double dy) {
        double tx = T / 2.0 + dx * os, ty = T / 2.0 + dy * os;
        int ix = (int) Math.floor(tx), iy = (int) Math.floor(ty);
        if (ix < 0 || iy < 0 || ix >= T - 1 || iy >= T - 1) return 0;
        double fx = tx - ix, fy = ty - iy;
        float[] t = truth[c];
        return (t[iy * T + ix] * (1 - fx) + t[iy * T + ix + 1] * fx) * (1 - fy) + (t[(iy + 1) * T + ix] * (1 - fx) + t[(iy + 1) * T + ix + 1] * fx) * fy;
    }

    /** The drawn Moon (no lens blur) averaged over a box of side {@code px} sensor pixels around a place (channel c). */
    public double truthSharp(int c, double dx, double dy, double px) {
        double[] s = satTruth[c];
        double tx = T / 2.0 + dx * os, ty = T / 2.0 + dy * os, h = px * os / 2.0;
        double sum = at(s, tx + h, ty + h) - at(s, tx - h, ty + h) - at(s, tx + h, ty - h) + at(s, tx - h, ty - h);
        return sum / (4 * h * h);
    }

    /** The Moon as the lens delivers it (its blur, no shake) averaged over a box of side {@code px} sensor pixels (channel c). */
    public double truthBox(int c, double dx, double dy, double px) {
        double[] s = sat[0][c];
        double tx = T / 2.0 + dx * os, ty = T / 2.0 + dy * os, h = px * os / 2.0;
        double sum = at(s, tx + h, ty + h) - at(s, tx - h, ty + h) - at(s, tx + h, ty - h) + at(s, tx - h, ty - h);
        return sum / (4 * h * h);
    }
}
