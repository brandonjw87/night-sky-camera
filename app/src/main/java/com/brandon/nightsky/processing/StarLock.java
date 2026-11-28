package com.brandon.nightsky.processing;

import java.util.Locale;

/**
 * Star lock (3.3, the Sky Window): where the camera really points, from the stars in its own picture, and so how far
 * off the phone's sensors are.
 * <p>
 * A phone knows which way it points from its rotation sensor: the gyro for turns, gravity for up, the compass for north.
 * Up is good to a degree or two; north is not: a compass is pulled off by a car, a railing, the phone's own magnets, often
 * by five or ten degrees and sometimes by twenty, which is more than the gap between two stars of a constellation. So the
 * labels of a sky app sit beside the stars they name. Here the camera's frame is solved like any other ({@link SkySolver}:
 * the pairs of its brightest stars looked up among the catalogue's), but around where the sensors say it points rather
 * than around the zenith, and the answer is turned into the correction that takes the sensors' idea of the camera's
 * pointing to the stars' ({@link Fix#C}, a rotation of east-north-up). Applied to every reading after it, the labels sit
 * on the stars, to a fraction of a degree, until the compass wanders again and the next frame locks again.
 * <p>
 * A lock is believed only if it is a good fit to enough stars, if it turns the sky by no more than the compass could be
 * off ({@link #SEARCH_DEG}), and if it hardly tilts it: gravity knows up far better than the compass knows north, so a
 * lock that says the phone was tilted {@link #MAX_TILT_DEG} degrees more than gravity said is a wrong one.
 */
public final class StarLock {
    private StarLock() {}

    /** How far from where the sensors say the camera may really point (degrees): a compass near a car can be 20 off. */
    public static final double SEARCH_DEG = 25;
    /** The fewest stars that make a lock, and the worst fit (RAW pixels, root mean square) a lock may have. */
    public static final int MIN_STARS = 9;
    public static final double MAX_RMS_PX = 2.5;
    /** Gravity knows up to a degree or two: a lock that tilts the sky by more than this is a wrong one (degrees). */
    public static final double MAX_TILT_DEG = 6;
    /**
     * A hand-held frame's stars are short trails, all alike: they are found and used up to this elongated (a plane's or a
     * satellite's streak is longer); and a speck sharper than this half-flux radius (RAW pixels) is a hot pixel, not a star.
     */
    public static final float MAX_ELONG = 6f, MIN_HFR = 0.8f;

    /** A star detector for the window's frames: trails kept, hot pixels left out. */
    public static StarDetector detector(SensorSpec spec) {
        StarDetector d = new StarDetector(spec);
        d.maxElong = MAX_ELONG;
        d.minHfr = MIN_HFR;
        return d;
    }

    /** A lock: how the sensors' pointing is turned into the stars'. */
    public static final class Fix {
        /** Takes where the sensors said the camera pointed to where the stars say it did (east-north-up, row-major 3 x 3). */
        public double[] C;
        /** Stars matched, and how well (RAW pixels, root mean square). */
        public int stars;
        public double rmsPx;
        /** How far off the sensors were (degrees): in all; around the horizon (the compass; positive: the camera pointed
         * farther east, clockwise, than they said); and in tilt. */
        public double offDeg, headingDeg, tiltDeg;
        /** When the frame was taken (UTC ms, the middle of its exposure). */
        public long utcMs;
        /** The frame's own solution (camera bearings to J2000). */
        public SkySolver.Solution solution;

        /** "23 stars; the compass was 7° off". */
        public String describe() {
            return String.format(Locale.US, "%d stars, %.2f px; the sensors were %.1f° off (compass %+.1f°, tilt %.1f°)", stars, rmsPx,
                    offDeg, headingDeg, tiltDeg);
        }
    }

    /**
     * The lock from one frame's stars, or null if they don't give one: {@code stars} as {@link StarDetector} found them in
     * the frame, the lens {@code model} in the frame's pixels, where the sensors said the camera pointed while the frame
     * was exposed ({@code sensorCamToEnu}: camera bearings to east-north-up, true north), when ({@code utcMs}) and where.
     */
    public static Fix lock(StarList stars, CameraModel model, BrightStars cat, double[] sensorCamToEnu, long utcMs, double latDeg,
                           double lonDeg) {
        if (stars == null || stars.count < MIN_STARS || cat == null || model == null || sensorCamToEnu == null) return null;
        double jd = Astro.jdUtc(utcMs);
        double[] toEnu = Astro.j2000ToEnu(jd, latDeg * Astro.DEG, lonDeg * Astro.DEG);
        double[] camToJ = Rot.mul(Rot.transpose(toEnu), sensorCamToEnu);
        SkySolver.Prior pr = new SkySolver.Prior();
        pr.jdUtc = jd;
        pr.lat = latDeg * Astro.DEG;
        pr.lon = lonDeg * Astro.DEG;
        pr.axis = new double[]{camToJ[2], camToJ[5], camToJ[8]};
        pr.maxTilt = SEARCH_DEG * Astro.DEG;
        pr.maxElong = MAX_ELONG;
        SkySolver.Solution sol = SkySolver.solve(stars, stars, model, cat, pr);
        if (sol == null || sol.matched < MIN_STARS || !(sol.rmsPx <= MAX_RMS_PX)) return null;
        Fix f = fix(Rot.mul(toEnu, sol.R), sensorCamToEnu);
        if (f.offDeg > SEARCH_DEG + 3 || f.tiltDeg > MAX_TILT_DEG) return null;
        f.stars = sol.matched;
        f.rmsPx = sol.rmsPx;
        f.utcMs = utcMs;
        f.solution = sol;
        return f;
    }

    /**
     * The correction from the sensors' pointing to the true one (both camera bearings to east-north-up), split into how
     * far it turns things around the horizon and how far it tilts them.
     */
    public static Fix fix(double[] trueCamToEnu, double[] sensorCamToEnu) {
        Fix f = new Fix();
        f.C = orthonormal(Rot.mul(trueCamToEnu, Rot.transpose(sensorCamToEnu)));
        f.offDeg = Rot.angle(f.C) / Astro.DEG;
        // tilt: how far the correction moves the zenith; heading: the turn about the vertical that's left
        f.tiltDeg = Math.acos(Math.max(-1, Math.min(1, f.C[8]))) / Astro.DEG;
        f.headingDeg = headingOf(f.C);
        return f;
    }

    /**
     * How far a correction turns things clockwise around the horizon (degrees, as a compass's azimuth grows): the twist about
     * the vertical left once its tilt is taken out.
     */
    public static double headingOf(double[] C) {
        // the quaternion's twist about z: (w, 0, 0, z) normalised; a positive z turns east toward north (anticlockwise
        // from above), which takes azimuths down
        double w = 0.5 * Math.sqrt(Math.max(0, 1 + C[0] + C[4] + C[8]));
        double z = w > 1e-6 ? (C[3] - C[1]) / (4 * w) : 1;
        return -2 * Math.atan2(z, w) / Astro.DEG;
    }

    /** The nearest rotation to {@code m} (a product of rotations that has drifted from one). */
    public static double[] orthonormal(double[] m) {
        double[] x = {m[0], m[3], m[6]}, y = {m[1], m[4], m[7]};
        norm(x);
        double d = x[0] * y[0] + x[1] * y[1] + x[2] * y[2];
        y[0] -= d * x[0];
        y[1] -= d * x[1];
        y[2] -= d * x[2];
        norm(y);
        double[] z = {x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0]};
        return new double[]{x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2]};
    }

    private static void norm(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (n > 1e-12) {
            v[0] /= n;
            v[1] /= n;
            v[2] /= n;
        }
    }
}
