package com.brandon.nightsky.processing;

/**
 * The Moon as a globe (3.0, the Moonshot): which face of it the observer sees and how it is turned, where the Sun
 * stands over it, and where a place on it falls in a photo. From Meeus, Astronomical Algorithms (2nd ed.):
 * the libration and the position angle of the axis (ch. 53, optical and physical, from the observer's own place, so
 * the diurnal libration of up to a degree is in), the Sun's selenographic place and colongitude (53), the bright
 * limb's position angle (48.5), and the parallactic angle (14.1).
 * <p>
 * Selenographic longitudes are east-positive towards Mare Crisium, as the coordinates of the landing sites are
 * (the Mean Earth / polar axis frame of the LRO maps; Meeus's theory is within a few hundredths of a degree of it).
 * On the disk, with the Moon's north up, east (Crisium's side) is to the right.
 */
public final class MoonGeo {
    private MoonGeo() {}

    static final double DEG = Math.PI / 180;
    /** The Moon's mean radius (km). */
    public static final double RADIUS_KM = 1737.4;
    /** Inclination of the mean lunar equator to the ecliptic (Meeus 53). */
    static final double I = 1.54242 * DEG;

    /** One moment's Moon from one place. Angles in degrees. */
    public static final class View {
        public double jdUtc;
        /** Selenographic longitude and latitude of the point at the middle of the disk (the libration). */
        public double l, b;
        /** Position angle of the Moon's north pole, from celestial north through east. */
        public double p;
        /** Selenographic longitude and latitude of the point with the Sun overhead, and the Sun's colongitude. */
        public double l0, b0, colong;
        /** Position angle of the middle of the bright limb, from celestial north through east. */
        public double chi;
        /** Phase angle (Sun-Moon-observer), illuminated fraction (0..1), and whether it is waxing. */
        public double phase, illum;
        public boolean waxing;
        /** Distance (km) and angular radius from the observer. */
        public double distKm, radius;
        /** Altitude and azimuth (no refraction), and the parallactic angle: the zenith's position angle. */
        public double alt, az, q;
        /** Apparent right ascension and declination from the observer (true equator of date). */
        public double ra, dec;

        /** The Sun's altitude over a place on the Moon (degrees, negative at night there). */
        public double sunAltAt(double latDeg, double lonDeg) {
            return Math.toDegrees(Math.asin(clamp(Math.sin(b0 * DEG) * Math.sin(latDeg * DEG)
                    + Math.cos(b0 * DEG) * Math.cos(latDeg * DEG) * Math.cos((lonDeg - l0) * DEG))));
        }

        /** Where a place on the Moon is on the disk: see {@link MoonGeo#disk}. */
        public double[] disk(double latDeg, double lonDeg) {
            return MoonGeo.disk(latDeg, lonDeg, l, b);
        }

        /**
         * Where a place on the Moon is in a picture, as {x, y, z} in pixels (x right, y down) and z > 0 on the visible
         * side: the disk's centre and radius in pixels, and the position angle of the picture's up (degrees from
         * celestial north through east).
         */
        public double[] inPicture(double latDeg, double lonDeg, double cx, double cy, double rPx, double upPa) {
            double[] d = disk(latDeg, lonDeg);
            double[] u = turn(d[0], d[1], p - upPa);
            return new double[]{cx + rPx * u[0], cy - rPx * u[1], d[2]};
        }
    }

    /** The Moon at a Unix time (ms), seen from a place on the ground (degrees, east-positive; height ignored). */
    public static View view(long unixMs, double latDeg, double lonDeg) {
        double jd = Astro.jdUtc(unixMs);
        double jdTt = Astro.jdTt(jd);
        double lat = latDeg * DEG, lon = lonDeg * DEG;
        double[] nut = Astro.nutation(jdTt);
        double eps = nut[2], dpsi = nut[0];
        // the Moon from the observer, true equator and equinox of date
        double[] m = Astro.moonOfDate(jdTt);
        double gast = Astro.gast(jd);
        double[] o = Astro.observerEcef(lat, lon, 0);
        // Earth-fixed to of-date: turn back by the sidereal time
        double cg = Math.cos(gast), sg = Math.sin(gast);
        double ox = cg * o[0] - sg * o[1], oy = sg * o[0] + cg * o[1], oz = o[2];
        double vx = m[0] * m[3] - ox, vy = m[1] * m[3] - oy, vz = m[2] * m[3] - oz;
        double dist = Math.sqrt(vx * vx + vy * vy + vz * vz);
        vx /= dist;
        vy /= dist;
        vz /= dist;
        View v = new View();
        v.jdUtc = jd;
        v.distKm = dist;
        v.radius = Math.toDegrees(Math.asin(RADIUS_KM / dist));
        double ra = Math.atan2(vy, vx);
        if (ra < 0) ra += 2 * Math.PI;
        double dec = Math.asin(clamp(vz));
        v.ra = ra;
        v.dec = dec;
        // ecliptic of date (apparent)
        double ey = Math.cos(eps) * vy + Math.sin(eps) * vz, ez = -Math.sin(eps) * vy + Math.cos(eps) * vz;
        double lamT = Math.atan2(ey, vx), betT = Math.asin(clamp(ez));
        double[] lbp = librate(jdTt, lamT / DEG, betT / DEG, ra / DEG, dpsi / DEG, eps / DEG);
        v.l = lbp[0];
        v.b = lbp[1];
        v.p = lbp[2];
        // the Sun: where it stands over the Moon, from the Sun's line to the Moon
        double[] s = Astro.sunOfDate(jdTt);
        double sx = s[0] * s[3], sy = s[1] * s[3], sz = s[2] * s[3];
        double hx = m[0] * m[3] - sx, hy = m[1] * m[3] - sy, hz = m[2] * m[3] - sz;
        double hn = Math.sqrt(hx * hx + hy * hy + hz * hz);
        hx /= hn;
        hy /= hn;
        hz /= hn;
        double hey = Math.cos(eps) * hy + Math.sin(eps) * hz, hez = -Math.sin(eps) * hy + Math.cos(eps) * hz;
        double[] sun = librate(jdTt, Math.atan2(hey, hx) / DEG, Math.asin(clamp(hez)) / DEG, ra / DEG, dpsi / DEG, eps / DEG);
        v.l0 = sun[0];
        v.b0 = sun[1];
        v.colong = norm360(90 - sun[0]);
        // the bright limb (Meeus 48.5), with the Sun's direction from the observer (its parallax is 9")
        double sRa = Math.atan2(s[1], s[0]), sDec = Math.asin(clamp(s[2]));
        v.chi = norm360(Math.toDegrees(Math.atan2(Math.cos(sDec) * Math.sin(sRa - ra),
                Math.sin(sDec) * Math.cos(dec) - Math.cos(sDec) * Math.sin(dec) * Math.cos(sRa - ra))));
        // phase: the angle at the Moon between the Sun and the observer
        double mx = vx * dist, my = vy * dist, mz = vz * dist; // observer to Moon
        double tsx = sx - ox - mx, tsy = sy - oy - my, tsz = sz - oz - mz; // Moon to Sun
        double tsn = Math.sqrt(tsx * tsx + tsy * tsy + tsz * tsz);
        double cosPh = -(mx * tsx + my * tsy + mz * tsz) / (dist * tsn);
        v.phase = Math.toDegrees(Math.acos(clamp(cosPh)));
        v.illum = (1 + cosPh) / 2;
        v.waxing = Astro.moonWaxing(jdTt);
        // altitude, azimuth and the parallactic angle
        double[] enu = new double[3];
        double[] eToU = Astro.ecefToEnu(lat, lon);
        // of-date to Earth-fixed, then east-north-up
        double fx = cg * vx + sg * vy, fy = -sg * vx + cg * vy;
        Rot.apply(eToU, fx, fy, vz, enu);
        v.alt = Math.toDegrees(Astro.altitude(enu));
        v.az = Math.toDegrees(Astro.azimuth(enu));
        double h = gast + lon - ra;
        v.q = Math.toDegrees(Math.atan2(Math.sin(h), Math.tan(lat) * Math.cos(dec) - Math.sin(dec) * Math.cos(h)));
        return v;
    }

    /**
     * Meeus 53.1-53.3: the selenographic longitude and latitude of the point facing a direction, given that direction's
     * apparent ecliptic longitude and latitude (of the Moon from the observer: the libration; of the Moon from the
     * Sun: where the Sun is overhead), and the position angle of the axis seen at right ascension {@code raDeg}.
     * Degrees in, {l, b, P} in degrees out (l in -180..180).
     */
    public static double[] librate(double jdTt, double lamDeg, double betDeg, double raDeg, double dpsiDeg, double epsDeg) {
        double t = (jdTt - Astro.J2000) / 36525.0;
        double D = norm360(297.8501921 + 445267.1114034 * t - 0.0018819 * t * t + t * t * t / 545868 - t * t * t * t / 113065000) * DEG;
        double M = norm360(357.5291092 + 35999.0502909 * t - 0.0001536 * t * t + t * t * t / 24490000) * DEG;
        double Mp = norm360(134.9633964 + 477198.8675055 * t + 0.0087414 * t * t + t * t * t / 69699 - t * t * t * t / 14712000) * DEG;
        double F = norm360(93.2720950 + 483202.0175233 * t - 0.0036539 * t * t - t * t * t / 3526000 + t * t * t * t / 863310000) * DEG;
        double Om = norm360(125.0445479 - 1934.1362891 * t + 0.0020754 * t * t + t * t * t / 467441 - t * t * t * t / 60616000) * DEG;
        double E = 1 - 0.002516 * t - 0.0000074 * t * t;
        double lam = lamDeg * DEG, bet = betDeg * DEG, dpsi = dpsiDeg * DEG, eps = epsDeg * DEG;
        // optical libration
        double W = lam - dpsi - Om;
        double A = Math.atan2(Math.sin(W) * Math.cos(bet) * Math.cos(I) - Math.sin(bet) * Math.sin(I), Math.cos(W) * Math.cos(bet));
        double l1 = norm180((A - F) / DEG);
        double b1 = Math.asin(clamp(-Math.sin(W) * Math.cos(bet) * Math.sin(I) - Math.sin(bet) * Math.cos(I))) / DEG;
        // physical libration (degrees)
        double K1 = (119.75 + 131.849 * t) * DEG, K2 = (72.56 + 20.186 * t) * DEG;
        double rho = -0.02752 * Math.cos(Mp) - 0.02245 * Math.sin(F) + 0.00684 * Math.cos(Mp - 2 * F) - 0.00293 * Math.cos(2 * F)
                - 0.00085 * Math.cos(2 * F - 2 * D) - 0.00054 * Math.cos(Mp - 2 * D) - 0.00020 * Math.sin(Mp + F)
                - 0.00020 * Math.cos(Mp + 2 * F) - 0.00020 * Math.cos(Mp - F) + 0.00014 * Math.cos(Mp + 2 * F - 2 * D);
        double sigma = -0.02816 * Math.sin(Mp) + 0.02244 * Math.cos(F) - 0.00682 * Math.sin(Mp - 2 * F) - 0.00279 * Math.sin(2 * F)
                - 0.00083 * Math.sin(2 * F - 2 * D) + 0.00069 * Math.sin(Mp - 2 * D) + 0.00040 * Math.cos(Mp + F)
                - 0.00025 * Math.sin(2 * Mp) - 0.00023 * Math.sin(Mp + 2 * F) + 0.00020 * Math.cos(Mp - F)
                + 0.00019 * Math.sin(Mp - F) + 0.00013 * Math.sin(Mp + 2 * F - 2 * D) - 0.00010 * Math.cos(Mp - 3 * F);
        double tau = 0.02520 * E * Math.sin(M) + 0.00473 * Math.sin(2 * Mp - 2 * F) - 0.00467 * Math.sin(Mp)
                + 0.00396 * Math.sin(K1) + 0.00276 * Math.sin(2 * Mp - 2 * D) + 0.00196 * Math.sin(Om)
                - 0.00183 * Math.cos(Mp - F) + 0.00115 * Math.sin(Mp - 2 * D) - 0.00096 * Math.sin(Mp - D)
                + 0.00046 * Math.sin(2 * F - 2 * D) - 0.00039 * Math.sin(Mp - F) - 0.00032 * Math.sin(Mp - M - D)
                + 0.00027 * Math.sin(2 * Mp - M - 2 * D) + 0.00023 * Math.sin(K2) - 0.00014 * Math.sin(2 * D)
                + 0.00014 * Math.cos(2 * Mp - 2 * F) - 0.00012 * Math.sin(Mp - 2 * F) - 0.00012 * Math.sin(2 * Mp)
                + 0.00011 * Math.sin(2 * Mp - 2 * M - 2 * D);
        double l2 = -tau + (rho * Math.cos(A) + sigma * Math.sin(A)) * Math.tan(b1 * DEG);
        double b2 = sigma * Math.cos(A) - rho * Math.sin(A);
        double l = norm180(l1 + l2), b = b1 + b2;
        // the axis's position angle
        double V = Om + dpsi + sigma * DEG / Math.sin(I);
        double X = Math.sin(I + rho * DEG) * Math.sin(V);
        double Y = Math.sin(I + rho * DEG) * Math.cos(V) * Math.cos(eps) - Math.cos(I + rho * DEG) * Math.sin(eps);
        double w = Math.atan2(X, Y);
        double P = Math.asin(clamp(Math.sqrt(X * X + Y * Y) * Math.cos(raDeg * DEG - w) / Math.cos(b * DEG))) / DEG;
        return new double[]{l, b, P};
    }

    /**
     * Where a place on the Moon falls on its disk seen with the libration {@code l, b}: {x, y, z} in Moon radii, x to
     * the Moon's east (right with its north up), y to its north, z towards the observer (negative: on the far side).
     */
    public static double[] disk(double latDeg, double lonDeg, double l, double b) {
        double la = latDeg * DEG, dl = (lonDeg - l) * DEG, bb = b * DEG;
        double x = Math.cos(la) * Math.sin(dl);
        double y = Math.cos(bb) * Math.sin(la) - Math.sin(bb) * Math.cos(la) * Math.cos(dl);
        double z = Math.sin(bb) * Math.sin(la) + Math.cos(bb) * Math.cos(la) * Math.cos(dl);
        return new double[]{x, y, z};
    }

    /** A direction (right, up) turned counterclockwise by {@code deg} degrees, as the sky turns seen from the ground. */
    public static double[] turn(double x, double y, double deg) {
        double c = Math.cos(deg * DEG), s = Math.sin(deg * DEG);
        return new double[]{x * c - y * s, x * s + y * c};
    }

    /**
     * The position angle (degrees from celestial north through east) of a direction in a picture, from the
     * gravity sensor: {@code up} and {@code gravity} are in the phone's frame (x right, y to its top, z out of the
     * screen; gravity pointing to the sky, as Android's gravity sensor reads it), {@code up} lying in the screen's
     * plane (the back camera looks out of the back, so the picture's plane is the screen's), and q the Moon's
     * parallactic angle (degrees). NaN if the phone points within a few degrees of the zenith or the nadir (the
     * vertical gives no direction there).
     */
    public static double upAngle(double qDeg, double[] gravity, double[] up) {
        double gx = gravity[0], gy = gravity[1], gz = gravity[2];
        double n = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (!(n > 0) || Math.hypot(gx, gy) < 0.05 * n) return Double.NaN;
        // the zenith's direction in the picture, then the angle from it to up, counterclockwise as seen
        double g = Math.toDegrees(Math.atan2(gx * up[1] - gy * up[0], gx * up[0] + gy * up[1]));
        return norm360(qDeg + g);
    }

    /**
     * The picture's up in the phone's frame for a camera whose sensor orientation is {@code deg} (Android's
     * SENSOR_ORIENTATION: the clockwise turn that puts its pictures upright in portrait): for the sensor's rows
     * as they are read out, row 0 at the top.
     */
    public static double[] sensorUp(int deg) {
        double a = deg * DEG;
        return new double[]{Math.sin(a), Math.cos(a), 0};
    }

    static double clamp(double x) {
        return Math.max(-1, Math.min(1, x));
    }

    public static double norm360(double d) {
        double r = d % 360;
        return r < 0 ? r + 360 : r;
    }

    public static double norm180(double d) {
        double r = norm360(d);
        return r >= 180 ? r - 360 : r;
    }
}
