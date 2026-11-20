package com.brandon.nightsky.processing;

import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.SplittableRandom;

/**
 * A rehearsal's sky (2.1): a clear night drawn over the real camera's own frames while the phone lies on a table with
 * its camera in the dark, so that a whole session (focusing, the sky check, the frames, developing and saving) runs on
 * the real phone, indoors, by day, and comes out with a picture whose every star is known.
 *
 * <p>The sky is the real one at the real time and place, seen the way the phone lies (screen-up, it is taken as turned
 * over: its camera to the zenith, its top edge where the compass says): the catalogue's stars to magnitude 6.5 where they
 * are, and random fainter ones fixed on the sky down to {@link #MAG_LIMIT}, all turning with the Earth through each
 * exposure. Their light is worked out the way {@link ClearSky} expects it of this lens (photons from a star of V = 0
 * through the aperture, dimmed by a clear sky's air at their height), in each photosite's own colour from this camera's
 * calibration matrices ({@link ColorMath#cameraRgb}), spread by the lens's blur (sharp at {@link #bestFocus}, a blur
 * disc growing {@link #blurPerUnit} pixels a diopter away from it, softer towards the corners, with a clean lens's
 * faint glow) and dimmed by the lens's own vignetting as its shading map says. The sky glows as a suburb's edge does
 * ({@link #SKY_MAG} at the zenith, brighter low down and towards a town), with the real Moon's light scattered into it
 * (Krisciunas and Schaefer) and its disc where it is, and the Milky Way where it is. All of it lands as electrons, with
 * their shot noise, converted to raw levels as the frame's own noise profile says, on top of the frame the camera took:
 * its black level, read noise, dark current and hot pixels are the real ones.
 *
 * <p>Drawn in bands of rows, a band at a time on each worker, so it needs no frame-sized buffer of its own.
 */
public final class RehearsalSky {
    /** The sky's own brightness at the zenith with no Moon (V magnitudes per square arcsecond): a suburb's dark edge. */
    public static final double SKY_MAG = 20.6;
    /** The Milky Way's light where the band is middling bright (V magnitudes per square arcsecond). */
    static final double MILKY_WAY_MAG = 21.6;
    /** The catalogue's stars to this magnitude; random ones fixed on the sky from there down to {@link #MAG_LIMIT}. */
    static final double CATALOGUE_MAG = 6.5;
    public static final double MAG_LIMIT = 10.0;
    /** The stars' blur at the best focus (Gaussian sigma at the frame's middle, RAW pixels), growing by this share squared to the corners. */
    public static final double PSF_SIGMA = 0.8, PSF_EDGE = 0.35;
    /** A clean lens's glow: this share of the light of stars brighter than {@link #HALO_MAG}, this wide (sigma, pixels). */
    static final double HALO_SHARE = 0.03, HALO_SIGMA = 6, HALO_MAG = 8;
    /** The sky's light is worked out every GRID pixels and interpolated between. */
    static final int GRID = 32;
    /** Rows drawn at a time by one worker. */
    static final int BAND = 32;
    /** The colours (kelvin) of the town's glow, the moonlit sky and the Milky Way's starlight. */
    static final double GLOW_K = 3800, MOON_K = 9000, MILKY_WAY_K = 4800;
    /** The Moon's radius (degrees). */
    static final double MOON_RADIUS_DEG = 0.26;

    /** What a rehearsal's sky is drawn from. */
    public static final class Setup {
        public SensorSpec spec;
        /** The lens model the frames are drawn through (the session's). */
        public CameraModel model;
        public BrightStars catalog;
        /** Where the sky is seen from (degrees, metres) and how that place was known ("gps", "saved", "time zone"). */
        public double latDeg, lonDeg, altM = 100;
        public String placeSource = "";
        /** Camera bearings to east-north-up: where the phone, as the sky sees it, points. */
        public double[] camToEnu;
        /** When the sky starts (UTC, ms): the random stars are placed around the field then. */
        public long startUtcMs;
        public long seed;
        /** The focus at which the stars are sharpest (NaN: a lens with a fixed focus), and the blur disc a focus unit away (px). */
        public double bestFocus = Double.NaN, blurPerUnit;
        /** The sky's brightness at the zenith with no Moon (V magnitudes per square arcsecond). */
        public double skyMag = SKY_MAG;
        /** How the phone lay: screen-up (turned over for the sky), its top edge's heading, its tilt (degrees). */
        public boolean screenUp;
        public double headingDeg = Double.NaN, tiltDeg = Double.NaN;
    }

    /** One frame to draw on. */
    public static final class Frame {
        /** When its exposure started (UTC, ms), how long it was (s), and at what ISO. */
        public double utcStartMs;
        public double exposureS;
        public int iso;
        /** Where the lens was (focus units; NaN: as drawn at best focus). */
        public double focus = Double.NaN;
        /** The frame's black level per CFA site (raw levels). */
        public final float[] black = new float[4];
        /** Electrons a raw level, from the frame's noise profile (NaN: worked out from the ISO). */
        public double electronsPerLevel = Double.NaN;
        /** Seeds the frame's noise. */
        public long noiseSeed;
    }

    /** What one frame got. */
    public static final class Drawn {
        /** The real frame's own light before the sky was drawn (green, median above black, raw levels), and per second. */
        public double leakLevels = Double.NaN, leakPerSec = Double.NaN;
        /** The sky's green level drawn at the frame's middle (raw levels above black, before vignetting). */
        public double skyLevels;
        /** Stars drawn (at least one photosite's worth), the electrons a raw level used, and the time it took (ms). */
        public int stars;
        public double electronsPerLevel;
        public long millis;
        /** The Moon's disc drawn in the frame. */
        public boolean moon;
    }

    public final int width, height;
    final int[] cfa = new int[4];
    final ShadingMap shading;
    final CameraModel model;
    final float white;
    public final double latDeg, lonDeg, altM;
    public final String placeSource;
    final double lat, lon;
    final double[] camToEnu, enuToCam;
    public final long startUtcMs, seed;
    public final double bestFocus, blurPerUnit, skyMag;
    public final boolean screenUp;
    public final double headingDeg, tiltDeg;
    /** Electrons a second a green photosite gets from a star of V = 0 above the air (all its light on that photosite). */
    public final double e0;
    /** A pixel's patch of sky at the frame's middle (square arcseconds), and what a clear sky takes off per airmass (mag). */
    public final double pixelArcsec2, extinction;
    /** Where the town's glow is brightest (radians east of north). */
    final double townAz;
    /** The stars: J2000 unit vectors, V magnitudes and their colour on the sensor (red and blue over green). */
    private final double[] sd;
    private final float[] smag, scr, scb;
    public final int stars, catalogueStars;
    private final double[] glowRgb, moonRgb, mwRgb;
    /** Electrons a raw level at ISO 100, from the camera's noise profile when built (NaN if it gave none). */
    final double electrons100;

    private RehearsalSky(Setup s) {
        SensorSpec sp = s.spec;
        width = sp.width;
        height = sp.height;
        System.arraycopy(sp.cfa, 0, cfa, 0, 4);
        shading = sp.shading;
        model = s.model != null ? s.model : sp.model;
        white = sp.white;
        latDeg = s.latDeg;
        lonDeg = s.lonDeg;
        altM = Double.isNaN(s.altM) ? 100 : s.altM;
        placeSource = s.placeSource == null ? "" : s.placeSource;
        lat = latDeg * Astro.DEG;
        lon = lonDeg * Astro.DEG;
        camToEnu = s.camToEnu.clone();
        enuToCam = Rot.transpose(camToEnu);
        startUtcMs = s.startUtcMs;
        seed = s.seed;
        bestFocus = s.bestFocus;
        blurPerUnit = s.blurPerUnit;
        skyMag = s.skyMag;
        screenUp = s.screenUp;
        headingDeg = s.headingDeg;
        tiltDeg = s.tiltDeg;
        double apertureMm = sp.focalLengthMm > 0 && sp.fNumber > 0 ? sp.focalLengthMm / sp.fNumber : 6.9 / 1.68;
        double areaCm2 = Math.PI * Math.pow(apertureMm / 20, 2);
        e0 = ClearSky.PHOTONS_V0 * ClearSky.GREEN_WIDTH * areaCm2;
        double pix = 206264.806 / model.fx;
        pixelArcsec2 = pix * pix;
        extinction = ClearSky.clearExtinction(altM);
        SplittableRandom rnd = new SplittableRandom(seed ^ 0x5DEECE66DL);
        townAz = 2 * Math.PI * rnd.nextDouble();
        electrons100 = electronsAt100(sp);
        glowRgb = ColorMath.cameraRgb(sp, GLOW_K);
        moonRgb = ColorMath.cameraRgb(sp, MOON_K);
        mwRgb = ColorMath.cameraRgb(sp, MILKY_WAY_K);

        // the field: the camera's axis on the sky at the start, out to the frame's corners and the sky's turn in an hour
        double[] axis = new double[3];
        Rot.apply(camToJ2000(startUtcMs), 0, 0, 1, axis);
        double[] c0 = new double[3], c1 = new double[3];
        model.unproject(model.cx, model.cy, c0);
        double half = 0;
        double[][] corners = {{0, 0}, {width, 0}, {0, height}, {width, height}};
        for (double[] c : corners) {
            model.unproject(c[0], c[1], c1);
            half = Math.max(half, Astro.sep(c0, c1));
        }
        double radius = Math.min(Math.PI / 2, half + 16 * Astro.DEG);
        int[] real = s.catalog != null ? s.catalog.within(axis, radius, CATALOGUE_MAG) : new int[0];
        double cosR = Math.cos(radius), sr = 2 * Math.PI * (1 - cosR);
        // stars fainter than the catalogue: N(< m) grows as 10^(0.42 m), some 26 000 a steradian to magnitude 10
        double perSr = 0.21 * 330000 / 2.68;
        double mMin = s.catalog != null ? CATALOGUE_MAG : 2.0;
        int nRandom = (int) Math.round(perSr * sr * (Math.pow(10, 0.42 * (MAG_LIMIT - 10)) - Math.pow(10, 0.42 * (mMin - 10))));
        int n = real.length + nRandom;
        sd = new double[3 * n];
        smag = new float[n];
        scr = new float[n];
        scb = new float[n];
        // camera colours by temperature, from 2400 K to 30000 K in steps of 2%
        int nt = 130;
        double[][] tab = new double[nt][];
        for (int k = 0; k < nt; k++) tab[k] = ColorMath.cameraRgb(sp, 2400 * Math.pow(1.02, k));
        for (int i = 0; i < real.length; i++) {
            int c = real[i];
            System.arraycopy(s.catalog.dir, 3 * c, sd, 3 * i, 3);
            smag[i] = s.catalog.vmag[c];
            float bv = s.catalog.bv[c];
            double t = !Float.isNaN(bv) ? ColorCalibration.kelvin(bv) : s.catalog.kelvin[c] > 0 ? s.catalog.kelvin[c] : 5800;
            colour(tab, i, t);
        }
        // the random stars, uniform in the field's cap
        double[] e1 = perpendicular(axis), e2 = cross(axis, e1);
        double a = Math.pow(10, 0.42 * mMin), b = Math.pow(10, 0.42 * MAG_LIMIT);
        for (int i = real.length; i < n; i++) {
            double cz = cosR + (1 - cosR) * rnd.nextDouble(), ph = 2 * Math.PI * rnd.nextDouble(), sz = Math.sqrt(Math.max(0, 1 - cz * cz));
            double u = sz * Math.cos(ph), v = sz * Math.sin(ph);
            for (int k = 0; k < 3; k++) sd[3 * i + k] = cz * axis[k] + u * e1[k] + v * e2[k];
            smag[i] = (float) (Math.log10(a + (b - a) * rnd.nextDouble()) / 0.42);
            // mostly F, G and K stars, some hot A and B ones and cool M ones
            double w = rnd.nextDouble();
            double t = w < 0.08 ? 8000 + 8000 * rnd.nextDouble() : w < 0.13 ? 3000 + 900 * rnd.nextDouble()
                    : 5600 * Math.exp(0.18 * gaussian(rnd));
            colour(tab, i, t);
        }
        stars = n;
        catalogueStars = real.length;
    }

    /** The sky for a rehearsal, from where and when it is seen and how the camera is set up. */
    public static RehearsalSky build(Setup s) {
        if (s.spec == null || s.camToEnu == null || s.spec.width <= 0 || s.spec.height <= 0) {
            throw new IllegalArgumentException("a rehearsal's sky needs the sensor and where the camera points");
        }
        if (s.model == null && s.spec.model == null) throw new IllegalArgumentException("a rehearsal's sky needs the lens model");
        return new RehearsalSky(s);
    }

    private void colour(double[][] tab, int i, double t) {
        double k = Math.log(Stats.clamp(t, 2400, 2400 * Math.pow(1.02, tab.length - 1)) / 2400) / Math.log(1.02);
        int k0 = Math.min(tab.length - 2, (int) k);
        double f = k - k0;
        scr[i] = (float) (tab[k0][0] + f * (tab[k0 + 1][0] - tab[k0][0]));
        scb[i] = (float) (tab[k0][2] + f * (tab[k0 + 1][2] - tab[k0][2]));
    }

    /** Electrons a raw level at ISO 100 from the camera's noise profile (green sites), NaN if it gives none. */
    static double electronsAt100(SensorSpec sp) {
        double s = 0;
        int n = 0;
        for (int p = 0; p < 4; p++) {
            if (sp.cfa[p] != 1 || !(sp.noiseS[p] > 0)) continue;
            s += sp.noiseS[p];
            n++;
        }
        double range = sp.range();
        if (n == 0 || !(range > 0) || sp.iso <= 0) return Double.NaN;
        return sp.iso / 100.0 / (s / n * range);
    }

    /** Electrons a raw level for a frame: its own noise profile's, else the camera's scaled to its ISO, else a phone's. */
    double electronsPerLevel(Frame f) {
        if (f.electronsPerLevel > 0) return f.electronsPerLevel;
        int iso = Math.max(1, f.iso);
        if (electrons100 > 0) return electrons100 * 100.0 / iso;
        // a 2.4 um phone pixel, about 6 electrons a level at ISO 100 on a 10-bit scale
        return 6.0 * 100.0 / iso * 1023.0 / Math.max(64, white);
    }

    // ------------------------------------------------------------------------------------------------
    // Where things are

    /** Camera bearings to J2000 at a time (UTC, ms). */
    public double[] camToJ2000(double utcMs) {
        return Rot.mul(Rot.transpose(Astro.j2000ToEnu(Astro.jdUtc(utcMs / 1000.0), lat, lon)), camToEnu);
    }

    /** J2000 to camera bearings at a time (UTC, ms). */
    double[] j2000ToCam(double utcMs) {
        return Rot.mul(enuToCam, Astro.j2000ToEnu(Astro.jdUtc(utcMs / 1000.0), lat, lon));
    }

    /** The blur disc's diameter (px) the lens adds at a focus, 0 at the best focus or with a fixed one. */
    public double defocusPx(double focus) {
        if (Double.isNaN(bestFocus) || Double.isNaN(focus) || !(blurPerUnit > 0)) return 0;
        return blurPerUnit * Math.abs(focus - bestFocus);
    }

    /** The stars' Gaussian blur (sigma, px) at the frame's middle at a focus. */
    public double sigmaAt(double focus) {
        double d = defocusPx(focus) / 4;
        return Math.sqrt(PSF_SIGMA * PSF_SIGMA + d * d);
    }

    /**
     * The stars' size (px) at the frame's middle at a focus, as the focus meter measures it: the mean radius of their
     * light, which for a Gaussian of sigma s, averaged over a photosite, is sqrt(pi / 2) sqrt(s^2 + 1/12).
     */
    public double hfrAt(double focus) {
        double s = sigmaAt(focus);
        return Math.sqrt(Math.PI / 2) * Math.sqrt(s * s + 1 / 12.0);
    }

    // ------------------------------------------------------------------------------------------------
    // The sky's light

    /** The Moon now: {ENU direction x, y, z, illuminated fraction, altitude (degrees)}. */
    double[] moon(double utcMs) {
        long ms = (long) utcMs;
        double[] m = Astro.moonEnu(ms, lat, lon);
        double k = Astro.moonIllumination(Astro.jdTt(Astro.jdUtc(ms)));
        return new double[]{m[0], m[1], m[2], k, Astro.altitude(m) / Astro.DEG};
    }

    /**
     * The sky's light (electrons a second on a photosite of each colour, before vignetting) every GRID pixels at a
     * time: {red, green, blue} grids, (W / GRID + 2) x (H / GRID + 2) each.
     */
    float[][] skyGrid(double utcMs) {
        int gw = width / GRID + 2, gh = height / GRID + 2;
        float[] r = new float[gw * gh], g = new float[gw * gh], bl = new float[gw * gh];
        double[] C = Rot.mul(Rot.transpose(Astro.j2000ToEnu(Astro.jdUtc(utcMs / 1000.0), lat, lon)), camToEnu);
        double[] mo = moon(utcMs);
        double[] moonDir = {mo[0], mo[1], mo[2]};
        double illum = mo[3], zMoon = 90 - mo[4];
        double natural = ReportCard.nanoLamberts(skyMag);
        double perNl = e0 * pixelArcsec2;
        double mwNl = ReportCard.nanoLamberts(MILKY_WAY_MAG);
        double[] bv = new double[3], e = new double[3], j = new double[3], q = new double[3];
        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                model.unproject(Math.min(width, gx * GRID), Math.min(height, gy * GRID), bv);
                Rot.apply(camToEnu, bv[0], bv[1], bv[2], e);
                double alt = Math.asin(Math.max(-1, Math.min(1, e[2])));
                double z = 90 - alt / Astro.DEG;
                double xm = airmass(z);
                // brighter low down (more air glowing in the line of sight), and more so towards the town
                double az = Math.atan2(e[0], e[1]);
                double town = Math.max(0, Math.cos(az - townAz));
                double glow = natural * (1 + 0.25 * (xm - 1) + 0.35 * (xm - 1) * town);
                double moonNl = mo[4] > 0 ? ReportCard.moonSkyNl(illum, Astro.sep(moonDir, e) / Astro.DEG, zMoon, Math.min(89, z)) : 0;
                Rot.apply(C, bv[0], bv[1], bv[2], j);
                Rot.apply(J2000_TO_GAL, j[0], j[1], j[2], q);
                double mw = mwNl * milkyWayAt(q[0], q[1], q[2]) * Math.pow(10, -0.4 * extinction * xm);
                double[] rgb = {glow * glowRgb[0] + moonNl * moonRgb[0] + mw * mwRgb[0], glow + moonNl + mw,
                        glow * glowRgb[2] + moonNl * moonRgb[2] + mw * mwRgb[2]};
                int k = gy * gw + gx;
                // nanolamberts to electrons: a sky of S magnitudes a square arcsecond is nL(0) 10^(-0.4 S), and gives a
                // pixel what a star of magnitude S would over each of its square arcseconds
                double s0 = perNl / ReportCard.nanoLamberts(0);
                r[k] = (float) (rgb[0] * s0);
                g[k] = (float) (rgb[1] * s0);
                bl[k] = (float) (rgb[2] * s0);
            }
        }
        return new float[][]{r, g, bl};
    }

    /** Airmass at a zenith distance (degrees), Kasten and Young. */
    static double airmass(double zDeg) {
        return ClearSky.airmass(90 - Math.min(89.5, Math.max(0, zDeg)));
    }

    /**
     * The sky's green light at the frame's middle part (the median over the green photosites the camera's probe
     * samples, vignetting in), in raw levels a second at an ISO: what a short probe of the sky would read.
     */
    public double greenLevelsPerSec(double utcMs, int iso, double electronsPerLevel) {
        Frame f = new Frame();
        f.iso = iso;
        f.electronsPerLevel = electronsPerLevel;
        double epl = electronsPerLevel(f);
        float[] g = skyGrid(utcMs)[1];
        int gw = width / GRID + 2;
        int gp = greenPlane();
        int n = 0;
        float[] v = new float[(width / 64 + 2) * (height / 64 + 2)];
        for (int y = height / 10; y < height - height / 10; y += 64) {
            for (int x = width / 10; x < width - width / 10; x += 64) {
                double s = interp(g, gw, x + 0.5, y + 0.5);
                double vg = shading != null ? 1 / Math.max(1e-3, shading.gain(gp, x, y)) : 1;
                v[n++] = (float) (s * vg / epl);
            }
        }
        return n > 0 ? Stats.median(v, n) : 0;
    }

    /**
     * The sky the camera's check of the sky measures (the central 20 degrees, between the stars, the lens shading
     * taken out): {as drawn (V magnitudes per square arcsecond), as the check should read it}. The check calibrates
     * it against the frame's own stars, which the air dims by {@link #extinction} per airmass and of whose light the
     * detector's box holds a little less than all: so the reading comes out that much brighter. Light the real frame
     * had of its own ({@code leakPerSec}, raw levels a second; the dark current, a crack of daylight) counts as sky.
     */
    public double[] skyReading(double utcMs, double leakPerSec, double electronsPerLevel) {
        float[] g = skyGrid(utcMs)[1];
        int gw = width / GRID + 2;
        double r = model.fx * Math.tan(Math.toRadians(10));
        int n = 0;
        float[] v = new float[4096];
        for (int y = (int) (model.cy - r); y <= model.cy + r && n < v.length; y += 16) {
            for (int x = (int) (model.cx - r); x <= model.cx + r && n < v.length; x += 16) {
                if (x < 0 || y < 0 || x >= width || y >= height) continue;
                if ((x - model.cx) * (x - model.cx) + (y - model.cy) * (y - model.cy) > r * r) continue;
                v[n++] = (float) interp(g, gw, x + 0.5, y + 0.5);
            }
        }
        if (n == 0) return new double[]{Double.NaN, Double.NaN};
        double sky = Stats.median(v, n);
        double leak = leakPerSec > 0 && electronsPerLevel > 0 ? leakPerSec * electronsPerLevel : 0;
        double drawn = -2.5 * Math.log10(sky / (e0 * pixelArcsec2));
        double withLeak = -2.5 * Math.log10((sky + leak) / (e0 * pixelArcsec2));
        double[] axis = new double[3];
        Rot.apply(camToEnu, 0, 0, 1, axis);
        double x = airmass(90 - Astro.altitude(axis) / Astro.DEG);
        // the detector's box (10 x 10 raw pixels) holds the core and a part of the drawn glow
        double inBox = Math.pow(erf(5 / (Math.sqrt(2) * HALO_SIGMA)), 2);
        double box = 1 - HALO_SHARE + HALO_SHARE * inBox;
        return new double[]{drawn, withLeak - extinction * x + 2.5 * Math.log10(box)};
    }

    private int greenPlane() {
        for (int q = 0; q < 4; q++) if (cfa[q] == 1) return q;
        return 1;
    }

    private static double interp(float[] g, int gw, double x, double y) {
        double u = x / GRID, v = y / GRID;
        int gh = g.length / gw;
        int i0 = Math.max(0, Math.min(gw - 2, (int) u)), j0 = Math.max(0, Math.min(gh - 2, (int) v));
        double tu = u - i0, tv = v - j0;
        int k = j0 * gw + i0;
        double top = g[k] + tu * (g[k + 1] - g[k]);
        double bot = g[k + gw] + tu * (g[k + gw + 1] - g[k + gw]);
        return top + tv * (bot - top);
    }

    // ------------------------------------------------------------------------------------------------
    // Drawing a frame

    /**
     * Draws the sky as the frame saw it into the camera's own raw frame ({@code raw}: width x height, unsigned 16-bit):
     * the stars moving through the exposure, the sky glow, the Moon, the Milky Way, all with their shot noise.
     */
    public Drawn draw(final short[] raw, final Frame f) {
        long t0 = System.nanoTime();
        final Drawn d = new Drawn();
        final int W = width, H = height;
        if (raw == null || raw.length < W * H) throw new IllegalArgumentException("the frame is smaller than the sensor");
        final double t = Math.max(1e-4, f.exposureS);
        final double epl = electronsPerLevel(f);
        d.electronsPerLevel = epl;
        final int gp = greenPlane();
        d.leakLevels = realLight(raw, f.black[gp], gp);
        d.leakPerSec = d.leakLevels / t;
        final double mid = f.utcStartMs + 500 * t;

        // the sky's light per colour and site, vignetting in, per second (on the GRID points)
        float[][] sky = skyGrid(mid);
        final int gw = W / GRID + 2, gh = H / GRID + 2;
        final float[][] site = new float[4][gw * gh];
        for (int q = 0; q < 4; q++) {
            float[] src = sky[cfa[q]];
            for (int gy = 0; gy < gh; gy++) {
                for (int gx = 0; gx < gw; gx++) {
                    double vg = shading != null ? 1 / Math.max(1e-3, shading.gain(q, Math.min(W, gx * GRID), Math.min(H, gy * GRID))) : 1;
                    site[q][gy * gw + gx] = (float) (src[gy * gw + gx] * vg * t);
                }
            }
        }
        d.skyLevels = interp(sky[1], gw, model.cx, model.cy) * t / epl;

        // the stars: where each is through the exposure, in sub-steps short enough that it moves under half a pixel
        double trail = 7.2921159e-5 * t * model.fx;
        final int steps = (int) Math.max(1, Math.min(12, Math.ceil(trail / 0.5)));
        double[][] Cs = new double[steps][];
        for (int k = 0; k < steps; k++) Cs[k] = j2000ToCam(f.utcStartMs + 1000 * t * (k + 0.5) / steps);
        double[] Cm = j2000ToCam(mid);
        double[] enuMid = Astro.j2000ToEnu(Astro.jdUtc(mid / 1000.0), lat, lon);
        final double defocus = defocusPx(f.focus) / 4;
        final double halfDiag = 0.5 * Math.hypot(W, H);
        int cap = 1024;
        double[] sx = new double[cap], sy = new double[cap], ss = new double[cap], se = new double[cap];
        float[] sr = new float[cap], sb = new float[cap];
        float[] sg = new float[4 * cap];
        int ns = 0, drawnStars = 0, reach = 8;
        double[] b = new double[3], p = new double[2], en = new double[3];
        for (int i = 0; i < stars; i++) {
            double x0 = sd[3 * i], y0 = sd[3 * i + 1], z0 = sd[3 * i + 2];
            Rot.apply(Cm, x0, y0, z0, b);
            if (b[2] < 0.2 || !model.project(b[0], b[1], b[2], p)) continue;
            if (p[0] < -40 || p[1] < -40 || p[0] > W + 40 || p[1] > H + 40) continue;
            Rot.apply(enuMid, x0, y0, z0, en);
            double alt = Math.asin(Math.max(-1, Math.min(1, en[2]))) / Astro.DEG;
            if (alt <= 0.5) continue;
            double electrons = e0 * Math.pow(10, -0.4 * (smag[i] + extinction * ClearSky.airmass(alt))) * t;
            if (electrons < 0.5) continue;
            double rr = Math.hypot(p[0] - model.cx, p[1] - model.cy) / halfDiag;
            double lensSig = PSF_SIGMA * (1 + PSF_EDGE * rr * rr);
            double sig = Math.sqrt(lensSig * lensSig + defocus * defocus);
            float[] vg = new float[4];
            for (int q = 0; q < 4; q++) {
                vg[q] = shading != null ? (float) (1 / Math.max(1e-3, shading.gain(q, Math.max(0, Math.min(W, p[0])),
                        Math.max(0, Math.min(H, p[1]))))) : 1f;
            }
            boolean halo = smag[i] < HALO_MAG;
            double core = halo ? 1 - HALO_SHARE : 1;
            int need = steps + (halo ? 1 : 0);
            if (ns + need > cap) {
                cap = Math.max(cap * 2, ns + need);
                sx = Arrays.copyOf(sx, cap);
                sy = Arrays.copyOf(sy, cap);
                ss = Arrays.copyOf(ss, cap);
                se = Arrays.copyOf(se, cap);
                sr = Arrays.copyOf(sr, cap);
                sb = Arrays.copyOf(sb, cap);
                sg = Arrays.copyOf(sg, 4 * cap);
            }
            boolean any = false;
            for (int k = 0; k < steps; k++) {
                Rot.apply(Cs[k], x0, y0, z0, b);
                if (b[2] < 0.2 || !model.project(b[0], b[1], b[2], p)) continue;
                if (p[0] < -8 || p[1] < -8 || p[0] > W + 8 || p[1] > H + 8) continue;
                sx[ns] = p[0];
                sy[ns] = p[1];
                ss[ns] = sig;
                se[ns] = electrons * core / steps;
                sr[ns] = scr[i];
                sb[ns] = scb[i];
                System.arraycopy(vg, 0, sg, 4 * ns, 4);
                ns++;
                any = true;
            }
            if (halo && any) {
                Rot.apply(Cm, x0, y0, z0, b);
                model.project(b[0], b[1], b[2], p);
                sx[ns] = p[0];
                sy[ns] = p[1];
                ss[ns] = HALO_SIGMA;
                se[ns] = electrons * HALO_SHARE;
                sr[ns] = scr[i];
                sb[ns] = scb[i];
                System.arraycopy(vg, 0, sg, 4 * ns, 4);
                ns++;
            }
            if (any && electrons >= 5) drawnStars++;
            if (any) reach = Math.max(reach, (int) Math.ceil(3.5 * (halo ? Math.max(sig, HALO_SIGMA) : sig)) + 2);
        }
        d.stars = drawnStars;
        // in order down the frame, for each band to find its own
        final long[] order = new long[ns];
        for (int k = 0; k < ns; k++) order[k] = ((long) Math.floor((sy[k] + 64) * 16) << 24) | k;
        Arrays.sort(order);
        final double[] fx = sx, fy = sy, fs = ss, fe = se;
        final float[] fr = sr, fb = sb, fg = sg;
        final int margin = reach;

        // the Moon's disc, where it is (it saturates the sensor)
        double[] mo = moon(mid);
        final double[] moonPx = new double[3];
        moonPx[2] = -1;
        if (mo[4] > -1 && mo[3] > 0.03) {
            double[] mc = new double[3];
            Rot.apply(enuToCam, mo[0], mo[1], mo[2], mc);
            if (mc[2] > 0.2 && model.project(mc[0], mc[1], mc[2], p) && p[0] > -50 && p[1] > -50 && p[0] < W + 50 && p[1] < H + 50) {
                moonPx[0] = p[0];
                moonPx[1] = p[1];
                moonPx[2] = MOON_RADIUS_DEG * Astro.DEG * model.fx;
                d.moon = true;
            }
        }
        final int maxLevel = (int) Math.max(1, Math.min(65535, white));
        final long frameSeed = f.noiseSeed ^ (seed * 0x9E3779B97F4A7C15L);
        final int bands = (H + BAND - 1) / BAND;
        Parallel.forRange(bands, 1, new Parallel.Range() {
            @Override
            public void run(int b0, int b1) {
                float[] lam = new float[BAND * W];
                double[] ex = new double[64], ey = new double[64];
                for (int band = b0; band < b1; band++) {
                    int y0 = band * BAND, y1 = Math.min(H, y0 + BAND);
                    // the sky's glow, interpolated between the grid's points
                    for (int y = y0; y < y1; y++) {
                        int row = (y - y0) * W;
                        double v = (y + 0.5) / GRID;
                        int j0 = Math.max(0, Math.min(gh - 2, (int) v));
                        float tv = (float) (v - j0);
                        int py = (y & 1) << 1;
                        for (int q = 0; q < 2; q++) {
                            float[] gq = site[py | q];
                            for (int x = q; x < W; x += 2) {
                                double u = (x + 0.5) / GRID;
                                int i0 = Math.min(gw - 2, (int) u);
                                float tu = (float) (u - i0);
                                int k = j0 * gw + i0;
                                float top = gq[k] + tu * (gq[k + 1] - gq[k]);
                                float bot = gq[k + gw] + tu * (gq[k + gw + 1] - gq[k + gw]);
                                lam[row + x] = top + tv * (bot - top);
                            }
                        }
                    }
                    // the stars that reach into the band
                    int lo = lowerBound(order, (long) Math.floor((Math.max(-64, y0 - margin) + 64) * 16) << 24);
                    for (int o = lo; o < order.length; o++) {
                        int k = (int) (order[o] & 0xFFFFFF);
                        double cy = fy[k], sgm = fs[k];
                        int r = (int) Math.ceil(3.5 * sgm);
                        if (cy - r - 1 > y1) {
                            if (cy - margin > y1) break;
                            continue;
                        }
                        if (cy + r + 1 < y0) continue;
                        if (ex.length < 2 * r + 3) {
                            ex = new double[2 * r + 3];
                            ey = new double[2 * r + 3];
                        }
                        spot(lam, W, y0, y1, fx[k], cy, sgm, r, fe[k], fr[k], fb[k], fg, 4 * k, ex, ey);
                    }
                    if (moonPx[2] > 0) {
                        double R = moonPx[2], mx = moonPx[0], my = moonPx[1];
                        for (int y = Math.max(y0, (int) (my - R)); y < Math.min(y1, (int) Math.ceil(my + R + 1)); y++) {
                            for (int x = Math.max(0, (int) (mx - R)); x < Math.min(W, (int) Math.ceil(mx + R + 1)); x++) {
                                double dx = x + 0.5 - mx, dy = y + 0.5 - my;
                                if (dx * dx + dy * dy <= R * R) lam[(y - y0) * W + x] += 1e7f;
                            }
                        }
                    }
                    // shot noise, in electrons, then raw levels on top of the camera's own frame
                    long st = frameSeed + 0x632BE59BD9B4E019L * (band + 1);
                    st = mixSeed(st);
                    double inv = 1 / epl;
                    for (int y = y0; y < y1; y++) {
                        int row = (y - y0) * W, base = y * W;
                        for (int x = 0; x < W; x++) {
                            float l = lam[row + x];
                            if (!(l > 0)) continue;
                            double e;
                            st ^= st << 13;
                            st ^= st >>> 7;
                            st ^= st << 17;
                            if (l >= 15) {
                                e = l + Math.sqrt(l) * GAUSS[(int) (st >>> 48)];
                                if (e < 0) e = 0;
                            } else {
                                // inversion: the first count whose cumulative probability passes a uniform draw
                                double u = (st >>> 11) * 0x1.0p-53;
                                double pk = Math.exp(-l), cum = pk;
                                int c = 0;
                                while (u > cum && c < 60) {
                                    c++;
                                    pk *= l / c;
                                    cum += pk;
                                }
                                e = c;
                                st ^= st << 13;
                                st ^= st >>> 7;
                                st ^= st << 17;
                            }
                            double dither = (st & 0xFFFF) * (1.0 / 65536);
                            int add = (int) Math.floor(e * inv + dither);
                            if (add <= 0) continue;
                            int vnew = (raw[base + x] & 0xFFFF) + add;
                            raw[base + x] = (short) Math.min(maxLevel, vnew);
                        }
                    }
                }
            }
        });
        d.millis = (System.nanoTime() - t0) / 1_000_000L;
        return d;
    }

    /** A star's light (or its glow's) as a pixel-integrated Gaussian on the photosites of one band, each in its own colour. */
    private void spot(float[] lam, int W, int y0, int y1, double px, double py, double sgm, int r, double electrons, float red,
                      float blue, float[] vig, int vo, double[] fxs, double[] fys) {
        int xa = (int) Math.floor(px) - r, xb = (int) Math.floor(px) + r;
        int ya = Math.max(y0, (int) Math.floor(py) - r), yb = Math.min(y1 - 1, (int) Math.floor(py) + r);
        if (ya > yb || xb < 0 || xa >= W) return;
        double inv = 1 / (Math.sqrt(2) * sgm);
        int nx = xb - xa + 1;
        double prev = erf((xa - px) * inv);
        for (int q = 0; q < nx; q++) {
            double nxt = erf((xa + q + 1 - px) * inv);
            fxs[q] = 0.5 * (nxt - prev);
            prev = nxt;
        }
        prev = erf((ya - py) * inv);
        for (int y = ya; y <= yb; y++) {
            double nxt = erf((y + 1 - py) * inv);
            double wy = 0.5 * (nxt - prev);
            prev = nxt;
            if (wy < 1e-7) continue;
            int row = (y - y0) * W, pr = (y & 1) << 1;
            for (int q = 0; q < nx; q++) {
                int x = xa + q;
                if (x < 0 || x >= W) continue;
                int plane = pr | (x & 1);
                int c = cfa[plane];
                double colour = c == 0 ? red : c == 2 ? blue : 1;
                lam[row + x] += (float) (electrons * fxs[q] * wy * colour * vig[vo + plane]);
            }
        }
    }

    private static int lowerBound(long[] a, long key) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (a[m] < key) lo = m + 1;
            else hi = m;
        }
        return lo;
    }

    /**
     * The camera's own light in a frame (the green photosites' median above black, a sparse grid over its middle):
     * a covered camera's dark current, or light getting in.
     */
    double realLight(short[] raw, float black, int gp) {
        int W = width, H = height, px = gp & 1, py = gp >> 1;
        float[] v = new float[(W / 16 + 1) * (H / 16 + 1)];
        int n = 0;
        for (int y = H / 10; y < H - H / 10; y += 16) {
            int yy = (y & ~1) | py;
            for (int x = W / 10; x < W - W / 10; x += 16) {
                int xx = (x & ~1) | px;
                v[n++] = (raw[yy * W + xx] & 0xFFFF) - black;
            }
        }
        return n == 0 ? Double.NaN : Stats.median(v, n);
    }

    // ------------------------------------------------------------------------------------------------
    // For the record

    /** What the sky was, for the session's settings (the rehearsal's check reads it back). */
    public void toProperties(Properties p) {
        p.setProperty("rehearsal", "true");
        p.setProperty("rehearsal.seed", Long.toString(seed));
        p.setProperty("rehearsal.lat", Double.toString(latDeg));
        p.setProperty("rehearsal.lon", Double.toString(lonDeg));
        p.setProperty("rehearsal.alt", Double.toString(altM));
        p.setProperty("rehearsal.placeSource", placeSource);
        p.setProperty("rehearsal.camToEnu", join(camToEnu));
        p.setProperty("rehearsal.startUtcMs", Long.toString(startUtcMs));
        p.setProperty("rehearsal.skyMag", Double.toString(skyMag));
        if (!Double.isNaN(bestFocus)) p.setProperty("rehearsal.bestFocus", Double.toString(bestFocus));
        p.setProperty("rehearsal.blurPerUnit", Double.toString(blurPerUnit));
        p.setProperty("rehearsal.psfSigma", Double.toString(PSF_SIGMA));
        p.setProperty("rehearsal.stars", Integer.toString(stars));
        p.setProperty("rehearsal.catalogueStars", Integer.toString(catalogueStars));
        p.setProperty("rehearsal.screenUp", Boolean.toString(screenUp));
        if (!Double.isNaN(headingDeg)) p.setProperty("rehearsal.headingDeg", Double.toString(headingDeg));
        if (!Double.isNaN(tiltDeg)) p.setProperty("rehearsal.tiltDeg", Double.toString(tiltDeg));
        double[] m = moon(startUtcMs);
        p.setProperty("rehearsal.moonIllum", Double.toString(m[3]));
        p.setProperty("rehearsal.moonAltDeg", Double.toString(m[4]));
    }

    /** For the log: the sky drawn. */
    public String describe() {
        double[] m = moon(startUtcMs);
        double[] axis = new double[3];
        Rot.apply(camToJ2000(startUtcMs), 0, 0, 1, axis);
        double[] rd = Astro.raDec(axis);
        return String.format(Locale.US, "rehearsal: a clear sky drawn over the camera's own frames, seen from %.2f, %.2f (%s): %d stars "
                        + "(%d from the catalogue, the rest random to magnitude %.1f), the sky %.1f magnitudes a square arcsecond at the "
                        + "zenith, the Moon %s; the camera pointing at RA %.2f h, Dec %+.1f deg at the start%s; %s; %.0f electrons a second "
                        + "from a star of magnitude 0", latDeg, lonDeg, placeSource.isEmpty() ? "place" : placeSource, stars, catalogueStars,
                MAG_LIMIT, skyMag, m[4] > 0 ? String.format(Locale.US, "%.0f%% lit and %.0f deg up", 100 * m[3], m[4]) : "down",
                rd[0] / Astro.DEG / 15, rd[1] / Astro.DEG, screenUp ? " (the phone screen-up, taken as turned over)" : "",
                Double.isNaN(bestFocus) ? "a lens with a fixed focus" : String.format(Locale.US, "the stars sharpest at focus %.3f (a %.1f px "
                        + "blur disc per focus unit away)", bestFocus, blurPerUnit), e0);
    }

    static String join(double[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers

    /** J2000 to galactic coordinates (rows: towards the galactic centre, l = 90 deg, the north galactic pole). */
    static final double[] J2000_TO_GAL = {-0.0548755604, -0.8734370902, -0.4838350155, 0.4941094279, -0.4448296300, 0.7469822445,
            -0.8676661490, -0.1980763734, 0.4559837762};

    /**
     * The Milky Way's light towards a galactic direction (unit vector): about 1 along the band's middle, brightest
     * towards the galactic centre and in Cygnus, as thick as the real band (thicker at the bulge), with star clouds and
     * dark lanes (the Great Rift, patchy dust near the plane).
     */
    static double milkyWayAt(double gx, double gy, double gz) {
        double l = Math.atan2(gy, gx), lDeg = Math.toDegrees(l), bDeg = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, gz))));
        double c = Math.cos(l / 2);
        double base = 0.3 + 0.7 * c * c + 0.25 * Math.exp(-sq((lDeg - 75) / 15));
        double thick = 5 + 6 * Math.exp(-sq(lDeg / 25));
        double prof = Math.exp(-0.5 * sq(bDeg / thick));
        if (prof < 1e-3) return 0;
        double clouds = 1 + 0.45 * fbm(gx, gy, gz, 7, 4, 11);
        double rift = Math.exp(-0.5 * sq((bDeg - 1.0) / 1.8)) * window(lDeg, -5, 95, 10);
        double patchy = Math.max(0, fbm(gx, gy, gz, 10, 4, 23) + 0.15);
        double dust = Math.min(0.85, 0.8 * rift * (0.6 + 0.4 * fbm(gx, gy, gz, 14, 3, 37)) + 0.55 * patchy * Math.exp(-0.5 * sq(bDeg / 4)));
        return Math.max(0, base * prof * Math.max(0.2, clouds) * (1 - dust));
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double window(double x, double a, double b, double e) {
        double lo = Math.max(0, Math.min(1, (x - (a - e)) / e)), hi = Math.max(0, Math.min(1, ((b + e) - x) / e));
        return Math.min(lo, hi);
    }

    static double fbm(double x, double y, double z, double f, int octaves, int seed) {
        double s = 0, a = 1, n = 0;
        for (int o = 0; o < octaves; o++) {
            s += a * noise3(x * f, y * f, z * f, seed + 31 * o);
            n += a;
            a *= 0.55;
            f *= 2;
        }
        return Math.max(-1, Math.min(1, 1.6 * s / n));
    }

    static double noise3(double x, double y, double z, int seed) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double fx = x - xi, fy = y - yi, fz = z - zi;
        double u = fx * fx * (3 - 2 * fx), v = fy * fy * (3 - 2 * fy), w = fz * fz * (3 - 2 * fz);
        double c000 = lattice(xi, yi, zi, seed), c100 = lattice(xi + 1, yi, zi, seed);
        double c010 = lattice(xi, yi + 1, zi, seed), c110 = lattice(xi + 1, yi + 1, zi, seed);
        double c001 = lattice(xi, yi, zi + 1, seed), c101 = lattice(xi + 1, yi, zi + 1, seed);
        double c011 = lattice(xi, yi + 1, zi + 1, seed), c111 = lattice(xi + 1, yi + 1, zi + 1, seed);
        double x00 = c000 + u * (c100 - c000), x10 = c010 + u * (c110 - c010);
        double x01 = c001 + u * (c101 - c001), x11 = c011 + u * (c111 - c011);
        double y0 = x00 + v * (x10 - x00), y1 = x01 + v * (x11 - x01);
        return y0 + w * (y1 - y0);
    }

    private static double lattice(int x, int y, int z, int seed) {
        long h = x * 374761393L + y * 668265263L + z * 2246822519L + seed * 3266489917L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= h >>> 16;
        h = (h ^ (h >>> 29)) * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        return ((h >>> 11) & 0xFFFFF) / (double) 0xFFFFF * 2 - 1;
    }

    /** erf, Abramowitz and Stegun 7.1.26 (to 1.5e-7). */
    static double erf(double x) {
        double s = x < 0 ? -1 : 1;
        x = Math.abs(x);
        if (x > 6) return s;
        double t = 1 / (1 + 0.3275911 * x);
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return s * y;
    }

    private static long mixSeed(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return z == 0 ? 0x9E3779B97F4A7C15L : z;
    }

    private static double gaussian(SplittableRandom r) {
        double u = Math.max(1e-300, r.nextDouble()), v = r.nextDouble();
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    /** Standard normal draws, looked up by 16 random bits (the noise needs their spread, not their sequence). */
    private static final float[] GAUSS = new float[65536];

    static {
        SplittableRandom r = new SplittableRandom(20261120L);
        for (int i = 0; i < GAUSS.length; i++) GAUSS[i] = (float) gaussian(r);
    }

    private static double[] perpendicular(double[] a) {
        double[] t = Math.abs(a[2]) < 0.9 ? new double[]{0, 0, 1} : new double[]{1, 0, 0};
        double[] c = cross(a, t);
        double n = Math.sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]);
        return new double[]{c[0] / n, c[1] / n, c[2] / n};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    /**
     * Where the camera points, as the sky sees a phone lying on a table: its top edge towards {@code headingTopDeg}
     * (degrees east of north, true north), tilted as "up" in its axes says ({@code up}: Android's sensor axes; NaN or
     * null: level). Camera x is the phone's bottom, camera y its left side, camera z out of the back, as
     * {@link Preflight#compassCamera} has it.
     */
    public static double[] attitude(double headingTopDeg, float[] up) {
        double[] level = Preflight.compassCamera(Double.isNaN(headingTopDeg) ? 0 : headingTopDeg, null);
        if (up == null) return level;
        double n = Math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]);
        if (!(n > 1)) return level;
        // the zenith in camera axes (as Preflight.placeFromStars has it), and the turn taking it to the camera's axis
        double[] zc = {-up[1] / n, -up[0] / n, -up[2] / n};
        double[] ez = {0, 0, 1};
        double[] ax = cross(zc, ez);
        double s = Math.sqrt(ax[0] * ax[0] + ax[1] * ax[1] + ax[2] * ax[2]), c = zc[2];
        if (s < 1e-9 || c < 0.5) return level;
        double ang = Math.atan2(s, c);
        double[] q = Rot.fromRotVec(ax[0] / s * ang, ax[1] / s * ang, ax[2] / s * ang);
        return Rot.mul(level, q);
    }
}
