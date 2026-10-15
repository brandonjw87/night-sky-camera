package com.brandon.nightsky.processing;

/**
 * Time, Earth orientation, and the Sun and Moon, to the accuracy a 70"-per-pixel camera needs (a few
 * arcseconds for the frames' orientation, a few arcminutes for the Moon). Formulas from Meeus, Astronomical
 * Algorithms (2nd ed.): sidereal time (12), nutation (22, the largest terms), precession (21, IAU 1976), the
 * Sun (25) and the Moon (47, the largest terms).
 * <p>
 * Frames: J2000 is the mean equator and equinox of 2000. "Of date" is the true equator and equinox at the time.
 * Earth-fixed (ECEF) ignores polar motion (under 0.5"). Topocentric ENU is east, north, up at the observer.
 * Matrices are row-major double[9] as in {@link Rot}; a matrix maps vectors of one frame to the other.
 */
public final class Astro {
    private Astro() {}

    public static final double DEG = Math.PI / 180;
    public static final double ARCSEC = DEG / 3600;
    /** TT - UTC (s): 32.184 s plus 37 leap seconds (no new ones announced as of 2026). */
    public static final double TT_MINUS_UTC = 69.184;
    /** Julian date of J2000.0 (TT). */
    public static final double J2000 = 2451545.0;
    public static final double EARTH_RADIUS_KM = 6378.137;
    static final double EARTH_FLATTENING = 1 / 298.257223563;
    public static final double AU_KM = 149597870.7;

    /** Julian date (UTC) of a Unix time in milliseconds. */
    public static double jdUtc(long unixMs) {
        return 2440587.5 + unixMs / 86400000.0;
    }

    /** Julian date (UTC) of a Unix time in seconds (fractional). */
    public static double jdUtc(double unixSec) {
        return 2440587.5 + unixSec / 86400.0;
    }

    /** Terrestrial time (Julian date) for a UTC Julian date. */
    public static double jdTt(double jdUtc) {
        return jdUtc + TT_MINUS_UTC / 86400.0;
    }

    /** Julian centuries of TT since J2000. */
    static double centuries(double jdTt) {
        return (jdTt - J2000) / 36525.0;
    }

    // ------------------------------------------------------------------------------------------------
    // Earth's rotation, precession and nutation

    /** Greenwich mean sidereal time (radians, 0..2pi) at a UT1 Julian date (UTC is close enough: under 1 s). */
    public static double gmst(double jdUt1) {
        double d = jdUt1 - J2000, t = d / 36525.0;
        double deg = 280.46061837 + 360.98564736629 * d + 0.000387933 * t * t - t * t * t / 38710000.0;
        return norm(deg * DEG);
    }

    /** Greenwich apparent sidereal time (radians): GMST plus the equation of the equinoxes. */
    public static double gast(double jdUtc) {
        double[] n = nutation(jdTt(jdUtc));
        return norm(gmst(jdUtc) + n[0] * Math.cos(n[2]));
    }

    /** Local apparent sidereal time (radians) at east longitude lon (radians). */
    public static double last(double jdUtc, double lonRad) {
        return norm(gast(jdUtc) + lonRad);
    }

    /** Mean obliquity of the ecliptic (radians). */
    static double meanObliquity(double jdTt) {
        double t = centuries(jdTt);
        double s = 84381.448 - 46.8150 * t - 0.00059 * t * t + 0.001813 * t * t * t;
        return s * ARCSEC;
    }

    // Nutation: Meeus table 22.A, the terms of 0.0005" and more. Arguments D, M, M', F, Omega; then the
    // longitude coefficient and its rate, and the obliquity coefficient and its rate (units of 0.0001").
    private static final double[][] NUT = {
            {0, 0, 0, 0, 1, -171996, -174.2, 92025, 8.9},
            {-2, 0, 0, 2, 2, -13187, -1.6, 5736, -3.1},
            {0, 0, 0, 2, 2, -2274, -0.2, 977, -0.5},
            {0, 0, 0, 0, 2, 2062, 0.2, -895, 0.5},
            {0, 1, 0, 0, 0, 1426, -3.4, 54, -0.1},
            {0, 0, 1, 0, 0, 712, 0.1, -7, 0},
            {-2, 1, 0, 2, 2, -517, 1.2, 224, -0.6},
            {0, 0, 0, 2, 1, -386, -0.4, 200, 0},
            {0, 0, 1, 2, 2, -301, 0, 129, -0.1},
            {-2, -1, 0, 2, 2, 217, -0.5, -95, 0.3},
            {-2, 0, 1, 0, 0, -158, 0, 0, 0},
            {-2, 0, 0, 2, 1, 129, 0.1, -70, 0},
            {0, 0, -1, 2, 2, 123, 0, -53, 0},
            {2, 0, 0, 0, 0, 63, 0, 0, 0},
            {0, 0, 1, 0, 1, 63, 0.1, -33, 0},
            {2, 0, -1, 2, 2, -59, 0, 26, 0},
            {0, 0, -1, 0, 1, -58, -0.1, 32, 0},
            {0, 0, 1, 2, 1, -51, 0, 27, 0},
            {-2, 0, 2, 0, 0, 48, 0, 0, 0},
            {0, 0, -2, 2, 1, 46, 0, -24, 0},
            {2, 0, 0, 2, 2, -38, 0, 16, 0},
            {0, 0, 2, 2, 2, -31, 0, 13, 0},
            {0, 0, 2, 0, 0, 29, 0, 0, 0},
            {-2, 0, 1, 2, 2, 29, 0, -12, 0},
            {0, 0, 0, 2, 0, 26, 0, 0, 0},
            {-2, 0, 0, 2, 0, -22, 0, 0, 0},
            {0, 0, -1, 2, 1, 21, 0, -10, 0},
            {0, 2, 0, 0, 0, 17, -0.1, 0, 0},
            {2, 0, -1, 0, 1, 16, 0, -8, 0},
            {-2, 2, 0, 2, 2, -16, 0.1, 7, 0},
            {0, 1, 0, 0, 1, -15, 0, 9, 0},
            {-2, 0, 1, 0, 1, -13, 0, 7, 0},
            {0, -1, 0, 0, 1, -12, 0, 6, 0},
    };

    /** {nutation in longitude, in obliquity, true obliquity} (radians) at a TT Julian date. */
    public static double[] nutation(double jdTt) {
        double t = centuries(jdTt);
        double D = (297.85036 + 445267.111480 * t - 0.0019142 * t * t + t * t * t / 189474) * DEG;
        double M = (357.52772 + 35999.050340 * t - 0.0001603 * t * t - t * t * t / 300000) * DEG;
        double Mp = (134.96298 + 477198.867398 * t + 0.0086972 * t * t + t * t * t / 56250) * DEG;
        double F = (93.27191 + 483202.017538 * t - 0.0036825 * t * t + t * t * t / 327270) * DEG;
        double Om = (125.04452 - 1934.136261 * t + 0.0020708 * t * t + t * t * t / 450000) * DEG;
        double dpsi = 0, deps = 0;
        for (double[] r : NUT) {
            double a = r[0] * D + r[1] * M + r[2] * Mp + r[3] * F + r[4] * Om;
            dpsi += (r[5] + r[6] * t) * Math.sin(a);
            deps += (r[7] + r[8] * t) * Math.cos(a);
        }
        dpsi *= 1e-4 * ARCSEC;
        deps *= 1e-4 * ARCSEC;
        return new double[]{dpsi, deps, meanObliquity(jdTt) + deps};
    }

    /** Precession (IAU 1976): J2000 mean equatorial to mean equatorial of date. */
    public static double[] precession(double jdTt) {
        double t = centuries(jdTt);
        double zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) * ARCSEC;
        double z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) * ARCSEC;
        double theta = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) * ARCSEC;
        return Rot.mul(r3(-z), Rot.mul(r2(theta), r3(-zeta)));
    }

    /** Nutation: mean equatorial of date to true equatorial of date. */
    public static double[] nutationMatrix(double jdTt) {
        double[] n = nutation(jdTt);
        double eps0 = n[2] - n[1];
        return Rot.mul(r1(-n[2]), Rot.mul(r3(-n[0]), r1(eps0)));
    }

    /** J2000 to true equator and equinox of date. */
    public static double[] j2000ToDate(double jdTt) {
        return Rot.mul(nutationMatrix(jdTt), precession(jdTt));
    }

    /** J2000 to Earth-fixed (no polar motion) at a UTC Julian date. */
    public static double[] j2000ToEcef(double jdUtc) {
        return Rot.mul(r3(gast(jdUtc)), j2000ToDate(jdTt(jdUtc)));
    }

    /** Earth-fixed to topocentric east, north, up at geodetic latitude and east longitude (radians). */
    public static double[] ecefToEnu(double lat, double lon) {
        double sl = Math.sin(lat), cl = Math.cos(lat), so = Math.sin(lon), co = Math.cos(lon);
        return new double[]{
                -so, co, 0,
                -sl * co, -sl * so, cl,
                cl * co, cl * so, sl};
    }

    /** J2000 to topocentric ENU (directions only: no parallax, no aberration, no refraction). */
    public static double[] j2000ToEnu(double jdUtc, double lat, double lon) {
        return Rot.mul(ecefToEnu(lat, lon), j2000ToEcef(jdUtc));
    }

    /** Observer position in Earth-fixed coordinates (km) on the WGS84 ellipsoid at height h (km). */
    public static double[] observerEcef(double lat, double lon, double hKm) {
        double f = EARTH_FLATTENING, e2 = f * (2 - f);
        double sl = Math.sin(lat), cl = Math.cos(lat);
        double N = EARTH_RADIUS_KM / Math.sqrt(1 - e2 * sl * sl);
        return new double[]{(N + hKm) * cl * Math.cos(lon), (N + hKm) * cl * Math.sin(lon), (N * (1 - e2) + hKm) * sl};
    }

    // ------------------------------------------------------------------------------------------------
    // Sun and Moon

    /** The Sun's geocentric direction, true equator and equinox of date (unit vector), and distance in km. */
    public static double[] sunOfDate(double jdTt) {
        double t = centuries(jdTt);
        double L0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t;
        double M = (357.52911 + 35999.05029 * t - 0.0001537 * t * t) * DEG;
        double e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t;
        double C = (1.914602 - 0.004817 * t - 0.000014 * t * t) * Math.sin(M) + (0.019993 - 0.000101 * t) * Math.sin(2 * M)
                + 0.000289 * Math.sin(3 * M);
        double om = (125.04 - 1934.136 * t) * DEG;
        double lam = (L0 + C - 0.00569 - 0.00478 * Math.sin(om)) * DEG;
        double nu = M + C * DEG;
        double R = 1.000001018 * (1 - e * e) / (1 + e * Math.cos(nu));
        double eps = meanObliquity(jdTt) + 0.00256 * DEG * Math.cos(om);
        return new double[]{Math.cos(lam), Math.cos(eps) * Math.sin(lam), Math.sin(eps) * Math.sin(lam), R * AU_KM};
    }

    // Moon: Meeus tables 47.A and 47.B, the larger terms. D, M, M', F; longitude (1e-6 deg), distance (m).
    private static final double[][] MOON_LR = {
            {0, 0, 1, 0, 6288774, -20905355},
            {2, 0, -1, 0, 1274027, -3699111},
            {2, 0, 0, 0, 658314, -2955968},
            {0, 0, 2, 0, 213618, -569925},
            {0, 1, 0, 0, -185116, 48888},
            {0, 0, 0, 2, -114332, -3149},
            {2, 0, -2, 0, 58793, 246158},
            {2, -1, -1, 0, 57066, -152138},
            {2, 0, 1, 0, 53322, -170733},
            {2, -1, 0, 0, 45758, -204586},
            {0, 1, -1, 0, -40923, -129620},
            {1, 0, 0, 0, -34720, 108743},
            {0, 1, 1, 0, -30383, 104755},
            {2, 0, 0, -2, 15327, 10321},
            {0, 0, 1, 2, -12528, 0},
            {0, 0, 1, -2, 10980, 79661},
            {4, 0, -1, 0, 10675, -34782},
            {0, 0, 3, 0, 10034, -23210},
            {4, 0, -2, 0, 8548, -21636},
            {2, 1, -1, 0, -7888, 24208},
            {2, 1, 0, 0, -6766, 30824},
            {1, 0, -1, 0, -5163, -8379},
            {1, 1, 0, 0, 4987, -16675},
            {2, -1, 1, 0, 4036, -12831},
            {2, 0, 2, 0, 3994, -10445},
            {4, 0, 0, 0, 3861, -11650},
            {2, 0, -3, 0, 3665, 14403},
            {0, 1, -2, 0, -2689, -7003},
            {2, 0, -1, 2, -2602, 0},
            {2, -1, -2, 0, 2390, 10056},
            {1, 0, 1, 0, -2348, 6322},
            {2, -2, 0, 0, 2236, -9884},
            {0, 1, 2, 0, -2120, 5751},
            {0, 2, 0, 0, -2069, 0},
            {2, -2, -1, 0, 2048, -4950},
            {2, 0, 1, -2, -1773, 4130},
            {2, 0, 0, 2, -1595, 0},
            {4, -1, -1, 0, 1215, -3958},
            {0, 0, 2, 2, -1110, 0},
            {3, 0, -1, 0, -892, 3258},
            {2, 1, 1, 0, -810, 2616},
            {4, -1, -2, 0, 759, -1897},
            {0, 2, -1, 0, -713, -2117},
            {2, 2, -1, 0, -700, 2354},
            {2, 1, -2, 0, 691, 0},
            {2, -1, 0, -2, 596, 0},
            {4, 0, 1, 0, 549, -1423},
            {0, 0, 4, 0, 537, -1117},
            {4, -1, 0, 0, 520, -1571},
            {1, 0, -2, 0, -487, -1739},
    };

    // D, M, M', F; latitude (1e-6 deg).
    private static final double[][] MOON_B = {
            {0, 0, 0, 1, 5128122},
            {0, 0, 1, 1, 280602},
            {0, 0, 1, -1, 277693},
            {2, 0, 0, -1, 173237},
            {2, 0, -1, 1, 55413},
            {2, 0, -1, -1, 46271},
            {2, 0, 0, 1, 32573},
            {0, 0, 2, 1, 17198},
            {2, 0, 1, -1, 9266},
            {0, 0, 2, -1, 8822},
            {2, -1, 0, -1, 8216},
            {2, 0, -2, -1, 4324},
            {2, 0, 1, 1, 4200},
            {2, 1, 0, -1, -3359},
            {2, -1, -1, 1, 2463},
            {2, -1, 0, 1, 2211},
            {2, -1, -1, -1, 2065},
            {0, 1, -1, -1, -1870},
            {4, 0, -1, -1, 1828},
            {0, 1, 0, 1, -1794},
            {0, 0, 0, 3, -1749},
            {0, 1, -1, 1, -1565},
            {1, 0, 0, 1, -1491},
            {0, 1, 1, 1, -1475},
            {0, 1, 1, -1, -1410},
            {0, 1, 0, -1, -1344},
            {1, 0, 0, -1, -1335},
            {0, 0, 3, 1, 1107},
            {4, 0, 0, -1, 1021},
            {4, 0, -1, 1, 833},
    };

    /**
     * The Moon's geocentric direction, true equator and equinox of date (unit vector), and distance in km;
     * then its ecliptic longitude and latitude (radians, of date).
     */
    public static double[] moonOfDate(double jdTt) {
        double t = centuries(jdTt);
        double Lp = 218.3164477 + 481267.88123421 * t - 0.0015786 * t * t + t * t * t / 538841 - t * t * t * t / 65194000;
        double D = 297.8501921 + 445267.1114034 * t - 0.0018819 * t * t + t * t * t / 545868 - t * t * t * t / 113065000;
        double M = 357.5291092 + 35999.0502909 * t - 0.0001536 * t * t + t * t * t / 24490000;
        double Mp = 134.9633964 + 477198.8675055 * t + 0.0087414 * t * t + t * t * t / 69699 - t * t * t * t / 14712000;
        double F = 93.2720950 + 483202.0175233 * t - 0.0036539 * t * t - t * t * t / 3526000 + t * t * t * t / 863310000;
        double A1 = (119.75 + 131.849 * t) * DEG, A2 = (53.09 + 479264.290 * t) * DEG, A3 = (313.45 + 481266.484 * t) * DEG;
        double E = 1 - 0.002516 * t - 0.0000074 * t * t;
        double d = D * DEG, m = M * DEG, mp = Mp * DEG, f = F * DEG, lp = Lp * DEG;
        double sl = 0, sr = 0, sb = 0;
        for (double[] r : MOON_LR) {
            double a = r[0] * d + r[1] * m + r[2] * mp + r[3] * f;
            double e = Math.abs(r[1]) == 1 ? E : Math.abs(r[1]) == 2 ? E * E : 1;
            sl += r[4] * e * Math.sin(a);
            sr += r[5] * e * Math.cos(a);
        }
        for (double[] r : MOON_B) {
            double a = r[0] * d + r[1] * m + r[2] * mp + r[3] * f;
            double e = Math.abs(r[1]) == 1 ? E : Math.abs(r[1]) == 2 ? E * E : 1;
            sb += r[4] * e * Math.sin(a);
        }
        sl += 3958 * Math.sin(A1) + 1962 * Math.sin(lp - f) + 318 * Math.sin(A2);
        sb += -2235 * Math.sin(lp) + 382 * Math.sin(A3) + 175 * Math.sin(A1 - f) + 175 * Math.sin(A1 + f)
                + 127 * Math.sin(lp - mp) - 115 * Math.sin(lp + mp);
        double[] nut = nutation(jdTt);
        double lam = norm((Lp + sl / 1e6) * DEG + nut[0]);
        double bet = sb / 1e6 * DEG;
        double dist = 385000.56 + sr / 1000;
        double eps = nut[2];
        double x = Math.cos(bet) * Math.cos(lam);
        double y = Math.cos(bet) * Math.sin(lam);
        double z = Math.sin(bet);
        double ye = Math.cos(eps) * y - Math.sin(eps) * z;
        double ze = Math.sin(eps) * y + Math.cos(eps) * z;
        return new double[]{x, ye, ze, dist, lam, bet};
    }

    /** Illuminated fraction of the Moon's disc (0 new, 1 full) at a TT Julian date. */
    public static double moonIllumination(double jdTt) {
        double[] s = sunOfDate(jdTt), m = moonOfDate(jdTt);
        double cosPsi = s[0] * m[0] + s[1] * m[1] + s[2] * m[2];
        double psi = Math.acos(Math.max(-1, Math.min(1, cosPsi)));
        double R = s[3], dl = m[3];
        double i = Math.atan2(R * Math.sin(psi), dl - R * Math.cos(psi));
        return (1 + Math.cos(i)) / 2;
    }

    /** Whether the Moon is waxing (its elongation from the Sun growing) at a TT Julian date. */
    public static boolean moonWaxing(double jdTt) {
        double[] s = sunOfDate(jdTt), m = moonOfDate(jdTt);
        double sunLon = Math.atan2(s[1] * Math.cos(nutation(jdTt)[2]) + s[2] * Math.sin(nutation(jdTt)[2]), s[0]);
        return norm(m[4] - sunLon) < Math.PI;
    }

    /**
     * Topocentric ENU direction of a body given its geocentric direction of date (unit vector) and distance
     * (km): the Moon's parallax (up to a degree) included.
     */
    public static double[] topocentric(double[] dirOfDate, double distKm, double jdUtc, double lat, double lon) {
        double[] dateToEcef = r3(gast(jdUtc));
        double[] g = new double[3];
        Rot.apply(dateToEcef, dirOfDate[0] * distKm, dirOfDate[1] * distKm, dirOfDate[2] * distKm, g);
        double[] o = observerEcef(lat, lon, 0);
        double[] v = {g[0] - o[0], g[1] - o[1], g[2] - o[2]};
        double[] enu = new double[3];
        Rot.apply(ecefToEnu(lat, lon), v[0], v[1], v[2], enu);
        double n = Math.sqrt(enu[0] * enu[0] + enu[1] * enu[1] + enu[2] * enu[2]);
        return new double[]{enu[0] / n, enu[1] / n, enu[2] / n};
    }

    /** Altitude (radians) of an ENU unit vector. */
    public static double altitude(double[] enu) {
        return Math.asin(Math.max(-1, Math.min(1, enu[2])));
    }

    /** Azimuth (radians from north through east, 0..2pi) of an ENU vector. */
    public static double azimuth(double[] enu) {
        return norm(Math.atan2(enu[0], enu[1]));
    }

    /** Sun's topocentric ENU direction at a Unix time (ms). */
    public static double[] sunEnu(long unixMs, double lat, double lon) {
        double jd = jdUtc(unixMs);
        double[] s = sunOfDate(jdTt(jd));
        return topocentric(s, s[3], jd, lat, lon);
    }

    /** Moon's topocentric ENU direction at a Unix time (ms). */
    public static double[] moonEnu(long unixMs, double lat, double lon) {
        double jd = jdUtc(unixMs);
        double[] m = moonOfDate(jdTt(jd));
        return topocentric(m, m[3], jd, lat, lon);
    }

    // ------------------------------------------------------------------------------------------------
    // Conversions

    /** Unit vector of right ascension and declination (radians). */
    public static double[] vec(double ra, double dec) {
        double c = Math.cos(dec);
        return new double[]{c * Math.cos(ra), c * Math.sin(ra), Math.sin(dec)};
    }

    /** {ra 0..2pi, dec} of a vector. */
    public static double[] raDec(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[]{norm(Math.atan2(v[1], v[0])), Math.asin(Math.max(-1, Math.min(1, v[2] / n)))};
    }

    /** Angle between two vectors (radians). */
    public static double sep(double[] a, double[] b) {
        double cx = a[1] * b[2] - a[2] * b[1], cy = a[2] * b[0] - a[0] * b[2], cz = a[0] * b[1] - a[1] * b[0];
        return Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), a[0] * b[0] + a[1] * b[1] + a[2] * b[2]);
    }

    /** Galactic latitude (radians) of a J2000 direction. */
    public static double galacticLatitude(double[] j2000) {
        // north galactic pole (J2000): RA 192.85948, Dec 27.12825
        double[] p = vec(192.85948 * DEG, 27.12825 * DEG);
        double n = Math.sqrt(j2000[0] * j2000[0] + j2000[1] * j2000[1] + j2000[2] * j2000[2]);
        return Math.asin(Math.max(-1, Math.min(1, (p[0] * j2000[0] + p[1] * j2000[1] + p[2] * j2000[2]) / n)));
    }

    static double norm(double a) {
        double t = a % (2 * Math.PI);
        return t < 0 ? t + 2 * Math.PI : t;
    }

    /** Rotation of the coordinate frame about x by a (the vector's coordinates turn by -a). */
    static double[] r1(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{1, 0, 0, 0, c, s, 0, -s, c};
    }

    static double[] r2(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, 0, -s, 0, 1, 0, s, 0, c};
    }

    static double[] r3(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, s, 0, -s, c, 0, 0, 0, 1};
    }
}
