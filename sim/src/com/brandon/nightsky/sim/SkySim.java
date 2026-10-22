package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.Parallel;
import com.brandon.nightsky.processing.Rot;
import com.brandon.nightsky.processing.SatSky;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.Sgp4;
import com.brandon.nightsky.processing.ShadingMap;
import com.brandon.nightsky.processing.UnitProcessor;

import java.util.Random;
import java.util.SplittableRandom;

/**
 * Synthetic night sky as the phone's main camera would record it lying screen-down: a realistic star field
 * turning about the celestial pole, lens distortion and vignetting, sky glow with a light-pollution
 * gradient, shot/read/dark noise, hot pixels, 10-bit RAW quantisation, plus optional satellites, a plane,
 * a meteor, passing clouds and a bump.
 */
public final class SkySim {
    public static final double OMEGA = 7.2921159e-5;

    public final int W, H;
    public final CameraModel truth;
    public double latitudeDeg = 40, headingDeg = 25, tiltDeg = 2.5;
    public double frameExp = 8.0, gap = 0.06;
    public int black = 64, white = 1023;
    public double readNoise = 1.6, darkRate = 0.25;
    /** Sky glow per channel in ADU/s at the frame centre (camera raw). */
    public double[] skyRate = {0.55, 0.95, 0.55};
    public double gradient = 0.35;
    public double fluxZero = 2.2e4;
    public double psfSigma = 0.85;
    public double magLimit = 10.3;
    public int substeps = 4;

    /**
     * Satellites from orbital elements (real sky only): drawn wherever SGP4 puts them while the Sun lights them,
     * as bright as {@link SatSky#magnitude} makes them.
     */
    public static final class OrbitSat {
        public final Sgp4 orbit;
        /** Magnitude at 1000 km, half lit. */
        public final double stdMag;
        /** Debug: per frame, how far it travelled across the picture (pixels) and its brightest line density (signal per pixel of length). */
        public final double[] pathPx, peakDensity;

        public OrbitSat(Sgp4 orbit, double stdMag, int frames) {
            this.orbit = orbit;
            this.stdMag = stdMag;
            pathPx = new double[frames];
            peakDensity = new double[frames];
        }
    }

    public final java.util.List<OrbitSat> orbitSats = new java.util.ArrayList<OrbitSat>();

    // events
    public int satelliteFrame = -1, planeFrame = -1, meteorFrame = -1;
    /** Noise that stays the same where the picture changes (for comparing two renders of one sky). */
    public boolean steadyNoise;
    public int cloudFrom = -1, cloudTo = -1;
    public double cloudT = 0.25;
    public int partialFrom = -1, partialTo = -1;
    /** Where the partial cloud's edge is (share of the width from the left) and how soft (share of the width). */
    public double partialEdge = 0.45, partialWidth = 0.08;
    public int bumpFrame = -1;
    public double bumpDeg = 0.25;
    /** Star brightness factor (below 1 for a sparse field: haze, a bright town, a short exposure). */
    public double starGain = 1;
    /** A tree branch over the left edge: a dark silhouette with small gaps of sky and a few lit leaves. */
    public boolean branch;
    /** A smudged or fogged lens: this share of every star's light spread into a glow of this size (pixels). */
    public double haloFrac, haloSigma = 10;
    /** Dew forming on the lens: from frame fogFrom on, the glow's share grows by fogPerFrame a frame, up to fogMax more. */
    public int fogFrom = -1;
    public double fogPerFrame = 0.04, fogMax = 0.5;
    /** The focus drifting: from frame focusFrom on, a blur (pixels, added in quadrature) growing by focusPerFrame a frame, up to focusMax. */
    public int focusFrom = -1;
    public double focusPerFrame = 0.08, focusMax = 1.5;
    /**
     * The sensor's own pattern (ADU), fixed on the sensor: per photosite (white), in blotches about ten pixels
     * across, per row and per column. Row banding: different in every frame.
     */
    public double fpnSigma, fpnBlotch, rowFixedSigma, colFixedSigma, rowBandSigma;
    private float[] fpn, rowFixed, colFixed;
    private float[] branchT;
    private int[] glints;

    private double[] sx, sy, sz;
    private float[] flux, cr, cg, cb;
    private int nStars;
    private int[] hotIdx;
    private float[] hotRate;
    private final double[] pole;
    private double[] cam0, camBump;
    private final double[] A; // camera raw response to linear sRGB (rows R, G, B)

    /**
     * The real sky: the bright-star catalogue (to magnitude 6.5) at this time and place, plus random fainter
     * stars; null for a random field.
     */
    private final BrightStars catalog;
    /** UTC (Unix ms) at the start of frame 0, and the observer's east longitude, for the real sky. */
    public final long startUnixMs;
    public final double longitudeDeg;
    /** J2000 to ENU at the start of frame 0 (real sky only). */
    private double[] m0;

    public SkySim(int width, int height, CameraModel truth, long seed) {
        this(width, height, truth, seed, null, 0L, 0);
    }

    public SkySim(int width, int height, CameraModel truth, long seed, BrightStars catalog, long startUnixMs, double lonDeg) {
        this.catalog = catalog;
        this.startUnixMs = startUnixMs;
        this.longitudeDeg = lonDeg;
        W = width;
        H = height;
        this.truth = truth;
        double lat = Math.toRadians(latitudeDeg);
        pole = new double[]{0, Math.cos(lat), Math.sin(lat)};
        double[] an = {0.70, 0.25, 0.05, 0.18, 0.72, 0.10, 0.04, 0.24, 0.72};
        double[] neutral = {0.48, 1.0, 0.66};
        A = new double[9];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) A[r * 3 + c] = an[r * 3 + c] * neutral[r];
        build(seed);
    }

    /** XYZ (D65) -> camera, for the DNG colour matrix of the simulated sensor. */
    public float[] colorMatrixD65() {
        double[] xyzToSrgb = {3.2404542, -1.5371385, -0.4985314, -0.9692660, 1.8760108, 0.0415560, 0.0556434, -0.2040259, 1.0572252};
        double[] m = Rot.mul(A, xyzToSrgb);
        float[] r = new float[9];
        for (int i = 0; i < 9; i++) r[i] = (float) m[i];
        return r;
    }

    private void build(long seed) {
        Random rnd = new Random(seed);
        double lat = Math.toRadians(latitudeDeg);
        double hd = Math.toRadians(headingDeg), tl = Math.toRadians(tiltDeg);
        double[] E = {1, 0, 0}, N = {0, 1, 0}, U = {0, 0, 1};
        // optical axis: zenith tilted toward azimuth 130 deg
        double az = Math.toRadians(130);
        double[] z = {Math.sin(tl) * Math.sin(az), Math.sin(tl) * Math.cos(az), Math.cos(tl)};
        double[] xh = {Math.cos(hd), Math.sin(hd), 0};
        double d = xh[0] * z[0] + xh[1] * z[1] + xh[2] * z[2];
        double[] x = {xh[0] - d * z[0], xh[1] - d * z[1], xh[2] - d * z[2]};
        double xn = Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
        x[0] /= xn;
        x[1] /= xn;
        x[2] /= xn;
        double[] y = {z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0]};
        cam0 = new double[]{x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2]};
        camBump = Rot.mul(Rot.fromRotVec(Math.toRadians(bumpDeg), Math.toRadians(bumpDeg * 0.4), 0), cam0);

        // stars within 62 deg of the zenith, magnitudes from a power law N(<m) ~ 10^(0.42 m); with the real sky,
        // the catalogue's stars to magnitude 6.5 where they are and random ones only below that
        double coneCos = Math.cos(Math.toRadians(62));
        double sr = 2 * Math.PI * (1 - coneCos);
        double mMin = 0.5;
        int[] real = new int[0];
        double[] enu0 = null;
        if (catalog != null) {
            m0 = Astro.j2000ToEnu(Astro.jdUtc(startUnixMs), lat, Math.toRadians(longitudeDeg));
            double[] zen = new double[3];
            Rot.apply(Rot.transpose(m0), 0, 0, 1, zen);
            real = catalog.within(zen, Math.toRadians(62), REAL_SKY_MAG);
            enu0 = new double[3 * real.length];
            for (int k = 0; k < real.length; k++) {
                double[] e = new double[3];
                Rot.apply(m0, catalog.dir[3 * real[k]], catalog.dir[3 * real[k] + 1], catalog.dir[3 * real[k] + 2], e);
                System.arraycopy(e, 0, enu0, 3 * k, 3);
            }
            mMin = REAL_SKY_MAG;
        }
        double nTotal = 0.21 * 330000 * (sr / 2.68) * (Math.pow(10, 0.42 * (magLimit - 10)) - Math.pow(10, 0.42 * (mMin - 10)));
        nStars = real.length + (int) nTotal;
        sx = new double[nStars];
        sy = new double[nStars];
        sz = new double[nStars];
        flux = new float[nStars];
        cr = new float[nStars];
        cg = new float[nStars];
        cb = new float[nStars];
        double a = Math.pow(10, 0.42 * mMin), b = Math.pow(10, 0.42 * magLimit);
        for (int i = 0; i < nStars; i++) {
            double m, t;
            if (i < real.length) {
                sx[i] = enu0[3 * i];
                sy[i] = enu0[3 * i + 1];
                sz[i] = enu0[3 * i + 2];
                m = catalog.vmag[real[i]];
                t = catalog.kelvin[real[i]] > 0 ? catalog.kelvin[real[i]] : 5600;
            } else {
                double cz = coneCos + (1 - coneCos) * rnd.nextDouble();
                double ph = 2 * Math.PI * rnd.nextDouble();
                double s = Math.sqrt(1 - cz * cz);
                sx[i] = s * Math.cos(ph);
                sy[i] = s * Math.sin(ph);
                sz[i] = cz;
                m = Math.log10(a + (b - a) * rnd.nextDouble()) / 0.42;
                // colour: temperature 3000..12000 K mapped to a linear sRGB tint
                // mostly F-G-K stars, some hot A/B and cool M stars
                double u = rnd.nextDouble();
                t = u < 0.08 ? 8000 + 8000 * rnd.nextDouble()
                        : (u < 0.13 ? 3000 + 900 * rnd.nextDouble() : 5600 * Math.exp(0.18 * rnd.nextGaussian()));
            }
            flux[i] = (float) (fluxZero * Math.pow(10, -0.4 * m));
            double[] rgb = tint(t);
            double rr = A[0] * rgb[0] + A[1] * rgb[1] + A[2] * rgb[2];
            double gg = A[3] * rgb[0] + A[4] * rgb[1] + A[5] * rgb[2];
            double bb = A[6] * rgb[0] + A[7] * rgb[1] + A[8] * rgb[2];
            double mean = (rr + 2 * gg + bb) / 4;
            cr[i] = (float) (rr / mean);
            cg[i] = (float) (gg / mean);
            cb[i] = (float) (bb / mean);
        }
        // hot pixels
        int nh = (int) (W * H * 0.0004);
        hotIdx = new int[nh];
        hotRate = new float[nh];
        for (int i = 0; i < nh; i++) {
            hotIdx[i] = rnd.nextInt(W * H);
            hotRate[i] = (float) (rnd.nextDouble() < 0.1 ? 150 + 300 * rnd.nextDouble() : 2 + 40 * rnd.nextDouble());
        }
    }

    /** Rough linear-sRGB colour of a blackbody, normalised to green = 1. */
    static double[] tint(double t) {
        double[] xyz = com.brandon.nightsky.processing.ColorMath.whiteXyz(t);
        double r = 3.2404542 * xyz[0] - 1.5371385 * xyz[1] - 0.4985314 * xyz[2];
        double g = -0.9692660 * xyz[0] + 1.8760108 * xyz[1] + 0.0415560 * xyz[2];
        double b = 0.0556434 * xyz[0] - 0.2040259 * xyz[1] + 1.0572252 * xyz[2];
        return new double[]{Math.max(0.05, r / g), 1, Math.max(0.05, b / g)};
    }

    public int starCount() {
        return nStars;
    }

    /** The catalogue is complete to about this magnitude; fainter real-sky stars are random. */
    static final double REAL_SKY_MAG = 6.5;

    /** Real sky: camera bearings of frame i (middle of its exposure) to J2000 directions; null for a random field. */
    public double[] cameraToJ2000(int i) {
        if (m0 == null) return null;
        double[] C = Rot.mul(camera(i, 0.5), sky(frameStart(i) + 0.5 * frameExp));
        return Rot.transpose(Rot.mul(C, m0));
    }

    /** The plane's dashed line in frame i, sensor pixels {xa, ya, xb, yb}; null if it isn't in that frame. */
    public double[] planeSegment(int i) {
        if (planeFrame < 0 || (i != planeFrame && i != planeFrame + 1)) return null;
        double fr = i == planeFrame ? 0 : 0.5;
        return new double[]{W * (0.1 + fr * 0.8), H * (0.9 - fr * 0.7), W * (0.1 + (fr + 0.5) * 0.8), H * (0.9 - (fr + 0.5) * 0.7)};
    }

    /** Real sky: J2000 to ENU at time t seconds after the start of frame 0 (the sim's own turning sky). */
    public double[] j2000ToEnu(double t) {
        return m0 == null ? null : Rot.mul(sky(t), m0);
    }

    /** Straight up in camera axes (x right, y down, z out of the lens), at the start. */
    public double[] zenithInCamera() {
        return new double[]{cam0[2], cam0[5], cam0[8]};
    }

    /** Compass bearing (degrees east of north) of the phone's top edge: camera -x, the phone lying face-down. */
    public double headingTopDeg() {
        double e = -cam0[0], n = -cam0[1];
        double a = Math.toDegrees(Math.atan2(e, n));
        return a < 0 ? a + 360 : a;
    }

    public double frameStart(int i) {
        return i * (frameExp + gap);
    }

    /** Camera orientation (rows = camera axes in ENU) for frame i at fraction f of its exposure. */
    private double[] camera(int i, double f) {
        if (bumpFrame >= 0 && (i > bumpFrame || (i == bumpFrame && f > 0.5))) return camBump;
        return cam0;
    }

    /** True rotation taking reference-frame bearings (frame r, mid exposure) to frame i bearings. */
    public double[] trueRotation(int r, int i) {
        double tr = frameStart(r) + frameExp / 2, ti = frameStart(i) + frameExp / 2;
        double[] Cr = camera(r, 0.25), Ci = camera(i, 0.75);
        double[] S = sky(ti - tr);
        return Rot.mul(Ci, Rot.mul(S, Rot.transpose(Cr)));
    }

    /** Sky rotation by time dt in ENU coordinates. */
    private double[] sky(double dt) {
        double a = -OMEGA * dt;
        return Rot.fromRotVec(pole[0] * a, pole[1] * a, pole[2] * a);
    }

    public float vignetting(double x, double y) {
        double dx = (x - W / 2.0) / (W / 2.0), dy = (y - H / 2.0) / (W / 2.0);
        double r2 = (dx * dx + dy * dy) / 1.5625;
        double v = 1 / (1 + 0.6 * r2);
        return (float) (v * v);
    }

    public ShadingMap shadingMap() {
        int cols = 17, rows = 13;
        float[] g = new float[4 * cols * rows];
        for (int p = 0; p < 4; p++) {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    double x = c * (W / (double) (cols - 1)), y = r * (H / (double) (rows - 1));
                    g[(p * rows + r) * cols + c] = 1f / vignetting(x, y);
                }
            }
        }
        return new ShadingMap(cols, rows, g, 0, 0, W, H);
    }

    private double transparency(int i, double x) {
        if (cloudFrom >= 0 && i >= cloudFrom && i <= cloudTo) return cloudT;
        if (partialFrom >= 0 && i >= partialFrom && i <= partialTo) {
            double e = (x - W * partialEdge) / (W * partialWidth);
            double s = 1 / (1 + Math.exp(-e));
            return 0.12 + 0.88 * s;
        }
        return 1;
    }

    /** Debug: indices of the simulated defective pixels and their dark rates (ADU/s). */
    public int[] hotPixelIndices() {
        return hotIdx.clone();
    }

    public float[] hotPixelRates() {
        return hotRate.clone();
    }

    /** Debug: every star in frame i (middle of the exposure): x, y, flux, cam R/G, cam B/G, and the electrons it
     *  puts in the frame (after cloud, vignetting and the branch) per row. */
    public double[][] starTable(int i) {
        java.util.List<double[]> out = new java.util.ArrayList<double[]>();
        double[] b = new double[3], p = new double[2];
        double[] C = Rot.mul(camera(i, 0.5), sky(frameStart(i) + 0.5 * frameExp));
        for (int k = 0; k < nStars; k++) {
            Rot.apply(C, sx[k], sy[k], sz[k], b);
            if (b[2] < 0.3 || !truth.project(b[0], b[1], b[2], p)) continue;
            if (p[0] < 0 || p[1] < 0 || p[0] >= W || p[1] >= H) continue;
            out.add(new double[]{p[0], p[1], flux[k], cr[k] / cg[k], cb[k] / cg[k], expected(i, p[0], p[1], flux[k])});
        }
        return out.toArray(new double[0][]);
    }

    /** Electrons a star of catalogue flux {@code f} (per second) at (x, y) puts into frame i. */
    public double expected(int i, double x, double y, double f) {
        double v = f * starGain * frameExp * transparency(i, x) * vignetting(x, y);
        if (branch && branchT == null) buildBranch();
        if (branchT != null && x >= 0 && y >= 0 && x < W && y < H) v *= branchT[(int) y * W + (int) x];
        return v;
    }

    /** Debug: the stars within r pixels of (x, y) at the middle of frame i (position, flux, camera colour). */
    public String starsNear(int i, double x, double y, double r) {
        StringBuilder sb = new StringBuilder();
        double[] b = new double[3], p = new double[2];
        double[] C = Rot.mul(camera(i, 0.5), sky(frameStart(i) + 0.5 * frameExp));
        for (int k = 0; k < nStars; k++) {
            Rot.apply(C, sx[k], sy[k], sz[k], b);
            if (b[2] < 0.3 || !truth.project(b[0], b[1], b[2], p)) continue;
            if (Math.hypot(p[0] - x, p[1] - y) > r) continue;
            sb.append(String.format(java.util.Locale.US, "  star %d at %.2f,%.2f flux %.0f/s cam R/G %.3f B/G %.3f%n",
                    k, p[0], p[1], flux[k], cr[k] / cg[k], cb[k] / cg[k]));
        }
        return sb.toString();
    }

    /** Transmission of the branch per pixel (1 = open sky), built once. */
    private void buildBranch() {
        branchT = new float[W * H];
        java.util.Arrays.fill(branchT, 1f);
        Random rnd = new Random(11);
        double s = W / 4080.0;
        // a limb from the left edge with a few twigs, as thick strokes
        double[][] strokes = {
                {0, 0.30 * H, 0.22 * W, 0.42 * H, 46 * s},
                {0.10 * W, 0.36 * H, 0.17 * W, 0.20 * H, 20 * s},
                {0.16 * W, 0.39 * H, 0.27 * W, 0.33 * H, 14 * s},
                {0, 0.62 * H, 0.12 * W, 0.58 * H, 30 * s},
                {0.07 * W, 0.60 * H, 0.15 * W, 0.70 * H, 12 * s}};
        for (double[] k : strokes) {
            double len = Math.hypot(k[2] - k[0], k[3] - k[1]);
            for (double t = 0; t <= 1; t += 0.5 / len) {
                double cx = k[0] + (k[2] - k[0]) * t, cy = k[1] + (k[3] - k[1]) * t;
                double r = k[4] * (1 - 0.5 * t);
                for (int y = (int) (cy - r); y <= (int) (cy + r); y++) {
                    if (y < 0 || y >= H) continue;
                    for (int x = (int) (cx - r); x <= (int) (cx + r); x++) {
                        if (x < 0 || x >= W) continue;
                        if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) branchT[y * W + x] = 0.03f;
                    }
                }
            }
        }
        // gaps between leaves: tiny holes of open sky inside the silhouette
        int holes = 0;
        for (int tries = 0; tries < 20000 && holes < 40; tries++) {
            int x = rnd.nextInt(W), y = rnd.nextInt(H);
            if (branchT[y * W + x] > 0.5f) continue;
            double r = 1.0 + 0.8 * rnd.nextDouble();
            for (int yy = (int) (y - r); yy <= (int) (y + r); yy++) {
                for (int xx = (int) (x - r); xx <= (int) (x + r); xx++) {
                    if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue;
                    if ((xx - x) * (xx - x) + (yy - y) * (yy - y) <= r * r) branchT[yy * W + xx] = 1f;
                }
            }
            holes++;
        }
        // leaves lit by a porch light: small bright static spots on the branch
        glints = new int[12];
        for (int g = 0, tries = 0; g < glints.length && tries < 20000; tries++) {
            int x = rnd.nextInt(W), y = rnd.nextInt(H);
            if (branchT[y * W + x] > 0.5f) continue;
            glints[g++] = y * W + x;
        }
    }

    /** Share of every star's light in the glow in frame i (a smudge, and dew forming). */
    public double haloAt(int i) {
        double h = haloFrac;
        if (fogFrom >= 0 && i >= fogFrom) h += Math.min(fogMax, (i - fogFrom + 1) * fogPerFrame);
        return Math.min(0.95, h);
    }

    /** Star blur (Gaussian sigma at the centre, pixels) in frame i. */
    public double psfAt(int i) {
        if (focusFrom < 0 || i < focusFrom) return psfSigma;
        double b = Math.min(focusMax, (i - focusFrom + 1) * focusPerFrame);
        return Math.sqrt(psfSigma * psfSigma + b * b);
    }

    /** Renders frame i into raw (unsigned 16-bit in a short[]), filling info. */
    public void render(final int i, final short[] raw, UnitProcessor.FrameInfo info, final long seed) {
        if (branch && branchT == null) buildBranch();
        final double haloFrac = haloAt(i), psfSigma = psfAt(i);
        final float[] img = new float[W * H];
        final double exp = frameExp;
        final int[] cfa = {0, 1, 1, 2};
        // background: sky glow with gradient, vignetting, cloud glow, dark current
        Parallel.forRange(H, 16, new Parallel.Range() {
            @Override
            public void run(int y0, int y1) {
                for (int y = y0; y < y1; y++) {
                    for (int x = 0; x < W; x++) {
                        int c = cfa[((y & 1) << 1) | (x & 1)];
                        double grad = 1 + gradient * (x / (double) W - 0.5) * 2 + 0.15 * gradient * (y / (double) H - 0.5);
                        double tr = transparency(i, x);
                        double glow = 1 + 0.8 * (1 - tr);
                        double open = branchT != null ? branchT[y * W + x] : 1;
                        img[y * W + x] = (float) ((skyRate[c] * grad * glow * vignetting(x + 0.5, y + 0.5) * open + darkRate) * exp);
                    }
                }
            }
        });
        // stars, integrated over the exposure in sub-steps
        final double[] b = new double[3], p = new double[2];
        for (int s = 0; s < substeps; s++) {
            double f = (s + 0.5) / substeps;
            double t = frameStart(i) + f * exp;
            double[] C = Rot.mul(camera(i, f), sky(t));
            for (int k = 0; k < nStars; k++) {
                Rot.apply(C, sx[k], sy[k], sz[k], b);
                if (b[2] < 0.3) continue;
                if (!truth.project(b[0], b[1], b[2], p)) continue;
                double px = p[0], py = p[1];
                if (px < -8 || py < -8 || px > W + 8 || py > H + 8) continue;
                double amp = flux[k] * starGain * exp / substeps * transparency(i, px) * vignetting(px, py) * (1 - haloFrac);
                if (branchT != null && px >= 0 && py >= 0 && px < W && py < H) amp *= branchT[(int) py * W + (int) px];
                if (amp < 0.05) continue;
                double rr = Math.hypot(px - W / 2.0, py - H / 2.0) / (0.5 * Math.hypot(W, H));
                double sg = psfSigma * (1 + 0.45 * rr * rr);
                addSpot(img, px, py, sg, amp, cfa, k);
            }
        }
        // a smudged lens: part of each star's light in a wide glow (once, at mid-exposure: the glow doesn't care
        // about a tenth of a pixel of drift)
        if (haloFrac > 0) {
            double[] C = Rot.mul(camera(i, 0.5), sky(frameStart(i) + 0.5 * exp));
            for (int k = 0; k < nStars; k++) {
                Rot.apply(C, sx[k], sy[k], sz[k], b);
                if (b[2] < 0.3 || !truth.project(b[0], b[1], b[2], p)) continue;
                double px = p[0], py = p[1];
                if (px < -40 || py < -40 || px > W + 40 || py > H + 40) continue;
                double amp = flux[k] * starGain * exp * transparency(i, px) * vignetting(px, py) * haloFrac;
                if (branchT != null && px >= 0 && py >= 0 && px < W && py < H) amp *= branchT[(int) py * W + (int) px];
                if (amp < 3) continue;
                addSpot(img, px, py, haloSigma, amp, cfa, k);
            }
        }
        // transients
        if (i == satelliteFrame) addLine(img, W * 0.08, H * 0.1, W * 0.9, H * 0.85, 1.0, 140 * exp / 8, 0);
        double[] plane = planeSegment(i);
        if (plane != null) addLine(img, plane[0], plane[1], plane[2], plane[3], 1.2, 400, 28);
        if (i == meteorFrame) addLine(img, W * 0.55, H * 0.3, W * 0.62, H * 0.42, 0.9, 260, 0);
        for (OrbitSat o : orbitSats) drawOrbitSat(img, i, o, psfSigma, haloFrac);
        // the branch's lit leaves: fixed bright spots
        if (branchT != null) {
            for (int g : glints) {
                int gx = g % W, gy = g / W;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int x = gx + dx, y = gy + dy;
                        if (x < 0 || y < 0 || x >= W || y >= H) continue;
                        img[y * W + x] += (float) (60 * exp / 8 * Math.exp(-(dx * dx + dy * dy) / (2 * 0.9 * 0.9)));
                    }
                }
            }
        }
        // hot pixels
        for (int h = 0; h < hotIdx.length; h++) img[hotIdx[h]] += (float) (hotRate[h] * exp);
        // noise, the sensor's pattern and quantisation
        if (fpn == null && (fpnSigma > 0 || fpnBlotch > 0 || rowFixedSigma > 0 || colFixedSigma > 0)) buildPattern();
        final float[] rowBand = new float[H];
        if (rowBandSigma > 0) {
            SplittableRandom rb = new SplittableRandom(seed * 7777L + i * 131L + 5);
            for (int y = 0; y < H; y++) rowBand[y] = (float) (rowBandSigma * gauss(rb));
        }
        Parallel.forRange(H, 16, new Parallel.Range() {
            @Override
            public void run(int y0, int y1) {
                SplittableRandom r = new SplittableRandom(seed * 1000003L + i * 7919L + y0);
                for (int y = y0; y < y1; y++) {
                    for (int x = 0; x < W; x++) {
                        int k = y * W + x;
                        double lam = Math.max(0, img[k]);
                        double v = (steadyNoise ? poissonOneDraw(r, lam) : poisson(r, lam)) + readNoise * gauss(r) + black + rowBand[y];
                        if (fpn != null) v += fpn[k] + rowFixed[y] + colFixed[x];
                        long q = Math.round(v);
                        if (q < 0) q = 0;
                        if (q > white) q = white;
                        raw[k] = (short) q;
                    }
                }
            }
        });
        info.timestampNs = (long) (frameStart(i) * 1e9) + 1_000_000_000L;
        info.exposureNs = (long) (exp * 1e9);
        info.iso = 700;
        // dynamic black level from optical-black pixels also carries most of the dark current
        for (int q = 0; q < 4; q++) info.black[q] = (float) (black + 0.7 * darkRate * exp);
        info.disturbed = i == bumpFrame;
        info.gyroMax = i == bumpFrame ? 0.4f : 0.002f;
        info.focus = 0;
    }

    /** The fixed pattern per photosite and the fixed row offsets, for tests ({fpn + columns, rows}); null if none. */
    public float[][] patternTruth() {
        if (fpn == null && (fpnSigma > 0 || fpnBlotch > 0 || rowFixedSigma > 0 || colFixedSigma > 0)) buildPattern();
        if (fpn == null) return null;
        float[] m = new float[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) m[y * W + x] = fpn[y * W + x] + colFixed[x];
        return new float[][]{m, rowFixed.clone()};
    }

    /** The fixed part of the sensor's pattern, built once from its own seed. */
    private void buildPattern() {
        SplittableRandom r = new SplittableRandom(90210);
        fpn = new float[W * H];
        rowFixed = new float[H];
        colFixed = new float[W];
        // blotches: white noise on a 10 px lattice, interpolated (bilinear), scaled to fpnBlotch
        int L = 10, gw = W / L + 2, gh = H / L + 2;
        float[] lat = new float[gw * gh];
        for (int k = 0; k < lat.length; k++) lat[k] = (float) gauss(r);
        double norm = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double gx = (x + 0.5) / L, gy = (y + 0.5) / L;
                int ix = (int) gx, iy = (int) gy;
                double tx = gx - ix, ty = gy - iy;
                double v = (1 - ty) * ((1 - tx) * lat[iy * gw + ix] + tx * lat[iy * gw + ix + 1])
                        + ty * ((1 - tx) * lat[(iy + 1) * gw + ix] + tx * lat[(iy + 1) * gw + ix + 1]);
                fpn[y * W + x] = (float) v;
                norm += v * v;
            }
        }
        double sb = fpnBlotch / Math.sqrt(Math.max(1e-12, norm / (W * H)));
        for (int k = 0; k < W * H; k++) fpn[k] = (float) (fpn[k] * sb + fpnSigma * gauss(r));
        for (int y = 0; y < H; y++) rowFixed[y] = (float) (rowFixedSigma * gauss(r));
        for (int x = 0; x < W; x++) colFixed[x] = (float) (colFixedSigma * gauss(r));
    }

    private void addSpot(float[] img, double px, double py, double sg, double amp, int[] cfa, int k) {
        int r = (int) Math.ceil(4 * sg);
        int x0 = (int) Math.floor(px) - r, x1 = (int) Math.floor(px) + r;
        int y0 = (int) Math.floor(py) - r, y1 = (int) Math.floor(py) + r;
        double inv = 1 / (Math.sqrt(2) * sg);
        int nx = x1 - x0 + 1, ny = y1 - y0 + 1;
        double[] fx = new double[nx], fy = new double[ny];
        for (int q = 0; q < nx; q++) {
            double a = (x0 + q - px) * inv, b = (x0 + q + 1 - px) * inv;
            fx[q] = 0.5 * (erf(b) - erf(a));
        }
        for (int q = 0; q < ny; q++) {
            double a = (y0 + q - py) * inv, b = (y0 + q + 1 - py) * inv;
            fy[q] = 0.5 * (erf(b) - erf(a));
        }
        for (int yy = 0; yy < ny; yy++) {
            int y = y0 + yy;
            if (y < 0 || y >= H) continue;
            for (int xx = 0; xx < nx; xx++) {
                int x = x0 + xx;
                if (x < 0 || x >= W) continue;
                int c = cfa[((y & 1) << 1) | (x & 1)];
                float cf = c == 0 ? cr[k] : (c == 1 ? cg[k] : cb[k]);
                img[y * W + x] += (float) (amp * fx[xx] * fy[yy] * cf);
            }
        }
    }

    /** Sunlight's colour on the sensor (red, green, blue relative to their mean), for satellites. */
    private float[] sunTint;

    /**
     * A satellite's streak in frame i: its place every 50 ms from SGP4 (topocentric J2000, then the sim's own
     * turning sky and the camera), and its light spread evenly along the straight line between those places.
     */
    private void drawOrbitSat(float[] img, int i, OrbitSat o, double psf, double halo) {
        if (m0 == null) return;
        if (sunTint == null) {
            double[] rgb = tint(5800);
            double rr = A[0] * rgb[0] + A[1] * rgb[1] + A[2] * rgb[2];
            double gg = A[3] * rgb[0] + A[4] * rgb[1] + A[5] * rgb[2];
            double bb = A[6] * rgb[0] + A[7] * rgb[1] + A[8] * rgb[2];
            double mean = (rr + 2 * gg + bb) / 4;
            sunTint = new float[]{(float) (rr / mean), (float) (gg / mean), (float) (bb / mean)};
        }
        SatSky.Site site = new SatSky.Site(Math.toRadians(latitudeDeg), Math.toRadians(longitudeDeg), 0);
        SatSky.Look look = new SatSky.Look();
        double t0 = frameStart(i), dt = 0.05;
        int steps = (int) Math.round(frameExp / dt);
        double px0 = Double.NaN, py0 = 0, f0 = 0;
        double[] enu = new double[3], b = new double[3], p = new double[2];
        int[] cfa = {0, 1, 1, 2};
        for (int k = 0; k <= steps; k++) {
            double t = t0 + k * dt;
            SatSky.Moment m = site.moment(Astro.jdUtc(startUnixMs / 1000.0 + t));
            boolean seen = SatSky.look(o.orbit, o.stdMag, m, look) && look.sunlit && look.altDeg > 0;
            double px = Double.NaN, py = 0, f = 0;
            if (seen) {
                Rot.apply(j2000ToEnu(t), look.j2000[0], look.j2000[1], look.j2000[2], enu);
                Rot.apply(camera(i, k / (double) steps), enu[0], enu[1], enu[2], b);
                if (b[2] > 0.05 && truth.project(b[0], b[1], b[2], p)) {
                    px = p[0];
                    py = p[1];
                    f = fluxZero * Math.pow(10, -0.4 * look.mag) * starGain;
                }
            }
            if (!Double.isNaN(px) && !Double.isNaN(px0)) {
                double len = Math.hypot(px - px0, py - py0);
                double flux = 0.5 * (f + f0) * dt;
                boolean inside = px > 0 && py > 0 && px < W && py < H;
                if (inside) {
                    o.pathPx[i] += len;
                    if (len > 1e-6) o.peakDensity[i] = Math.max(o.peakDensity[i], flux / len * transparency(i, px) * vignetting(px, py));
                }
                if (len < 4000 && (inside || px0 > 0 && py0 > 0 && px0 < W && py0 < H)) {
                    // the core in steps of 0.3 px; the lens's glow (as the stars have it) in steps of a pixel
                    for (int part = 0; part < (halo > 0 ? 2 : 1); part++) {
                        double share = part == 0 ? 1 - halo : halo, sg = part == 0 ? psf : haloSigma;
                        int n = Math.max(1, (int) Math.ceil(len / (part == 0 ? 0.3 : 1.0)));
                        for (int q = 0; q < n; q++) {
                            double u = (q + 0.5) / n, x = px0 + (px - px0) * u, y = py0 + (py - py0) * u;
                            if (x < -4 * sg || y < -4 * sg || x > W + 4 * sg || y > H + 4 * sg) continue;
                            double amp = flux * share / n * transparency(i, x) * vignetting(x, y);
                            if (branchT != null && x >= 0 && y >= 0 && x < W && y < H) amp *= branchT[(int) y * W + (int) x];
                            addSpotTint(img, x, y, sg, amp, cfa, sunTint);
                        }
                    }
                }
            }
            px0 = px;
            py0 = py;
            f0 = f;
        }
    }

    private void addSpotTint(float[] img, double px, double py, double sg, double amp, int[] cfa, float[] tint) {
        int r = (int) Math.ceil(4 * sg);
        int x0 = (int) Math.floor(px) - r, y0 = (int) Math.floor(py) - r;
        double inv = 1 / (Math.sqrt(2) * sg);
        for (int yy = 0; yy <= 2 * r; yy++) {
            int y = y0 + yy;
            if (y < 0 || y >= H) continue;
            double fy = 0.5 * (erf((y + 1 - py) * inv) - erf((y - py) * inv));
            for (int xx = 0; xx <= 2 * r; xx++) {
                int x = x0 + xx;
                if (x < 0 || x >= W) continue;
                double fx = 0.5 * (erf((x + 1 - px) * inv) - erf((x - px) * inv));
                img[y * W + x] += (float) (amp * fx * fy * tint[cfa[((y & 1) << 1) | (x & 1)]]);
            }
        }
    }

    /** Streak with peak brightness per pixel {@code amp}; dash > 0 makes it dotted (blinking lights). */
    private void addLine(float[] img, double xa, double ya, double xb, double yb, double sg, double amp, double dash) {
        double len = Math.hypot(xb - xa, yb - ya);
        int steps = (int) (len * 4);
        int[] cfa = {0, 1, 1, 2};
        for (int s = 0; s <= steps; s++) {
            double t = s / (double) steps;
            double along = t * len;
            if (dash > 0 && (along % dash) > 3) continue;
            double px = xa + (xb - xa) * t, py = ya + (yb - ya) * t;
            double a = amp / 4 * (dash > 0 ? 4 : 1) * 2.5 * sg;
            int r = (int) Math.ceil(3 * sg);
            for (int y = (int) py - r; y <= (int) py + r; y++) {
                if (y < 0 || y >= H) continue;
                for (int x = (int) px - r; x <= (int) px + r; x++) {
                    if (x < 0 || x >= W) continue;
                    double d2 = (x + 0.5 - px) * (x + 0.5 - px) + (y + 0.5 - py) * (y + 0.5 - py);
                    img[y * W + x] += (float) (a / (2 * Math.PI * sg * sg) * Math.exp(-d2 / (2 * sg * sg)) * (cfa[((y & 1) << 1) | (x & 1)] == 1 ? 1 : 0.8));
                }
            }
        }
    }

    static double erf(double x) {
        double t = 1 / (1 + 0.3275911 * Math.abs(x));
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return x >= 0 ? y : -y;
    }

    static double gauss(SplittableRandom r) {
        double u1 = r.nextDouble(), u2 = r.nextDouble();
        return Math.sqrt(-2 * Math.log(Math.max(u1, 1e-300))) * Math.cos(2 * Math.PI * u2);
    }

    /** Poisson by its inverse distribution: one draw whatever the level, so two renders differing in a few spots share all other noise. */
    static double poissonOneDraw(SplittableRandom r, double lam) {
        if (lam <= 0) return 0;
        if (lam > 40) return lam + Math.sqrt(lam) * gauss(r);
        double u = r.nextDouble(), p = Math.exp(-lam), F = p;
        int k = 0;
        while (u > F && k < 200) {
            k++;
            p *= lam / k;
            F += p;
        }
        return k;
    }

    static double poisson(SplittableRandom r, double lam) {
        if (lam <= 0) return 0;
        if (lam > 40) return lam + Math.sqrt(lam) * gauss(r);
        double L = Math.exp(-lam), p = 1;
        int k = 0;
        do {
            k++;
            p *= r.nextDouble();
        } while (p > L);
        return k - 1;
    }

    /** Sensor description as the phone would report it (truth model or a nominal one). */
    public SensorSpec spec(boolean calibrated) {
        SensorSpec s = new SensorSpec();
        s.width = W;
        s.height = H;
        s.setCfaFromArrangement(0, 0, 0);
        for (int q = 0; q < 4; q++) s.black[q] = black;
        s.white = white;
        if (calibrated) {
            s.model = truth.copy();
            s.model.source = "intrinsics";
        } else {
            // what we'd compute from focal length and pixel pitch: 2% off, no distortion
            CameraModel m = CameraModel.nominal(W, H, truth.fx * 1.02);
            m.source = "nominal";
            s.model = m;
        }
        s.shading = shadingMap();
        s.colorMatrix1 = colorMatrixD65();
        s.illuminant1 = 21;
        s.deviceMake = "Sim";
        s.deviceModel = "SkySim";
        s.focalLengthMm = 6.9f;
        s.fNumber = 1.68f;
        s.pixelPitchUm = 2.4f;
        s.iso = 700;
        s.frameExposureNs = (long) (frameExp * 1e9);
        return s;
    }
}
