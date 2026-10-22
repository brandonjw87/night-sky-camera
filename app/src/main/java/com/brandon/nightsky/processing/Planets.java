package com.brandon.nightsky.processing;

/**
 * Where the planets are, to an arcminute or so: JPL's Keplerian elements for approximate positions of the major
 * planets (E. M. Standish, Table 1, valid 1800 to 2050), with light time; brightness from Mallama and Hilton
 * (2018). Directions are geocentric J2000 (the frame the sky solution uses); the Moon's parallax-free
 * geocentre is a few thousandths of a degree from the observer for planets, which no picture shows.
 */
public final class Planets {
    private Planets() {}

    public static final String[] NAMES = {"Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"};
    /** Rows of {@link #EL} for {@link #NAMES}, and the Earth-Moon barycentre's. */
    private static final int[] ROW = {0, 1, 3, 4, 5, 6, 7};
    private static final int EARTH = 2;
    /** Obliquity of the ecliptic at J2000 (IAU 1976: 84381.448"). */
    private static final double EPS = 84381.448 * Astro.ARCSEC;
    /** Light time per AU, days. */
    private static final double LIGHT_DAYS_PER_AU = 499.004784 / 86400;
    /** Saturn's north pole (J2000): its rings' tilt toward the Earth changes its brightness. */
    private static final double[] SATURN_POLE = Astro.vec(40.589 * Astro.DEG, 83.537 * Astro.DEG);

    // a (AU), e, I, L, long. perihelion, long. node (degrees), then their rates per Julian century
    private static final double[][] EL = {
            {0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593,
                    0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081},
            {0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255,
                    0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418},
            {1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0,
                    0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0},
            {1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891,
                    0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343},
            {5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909,
                    -0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106},
            {9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448,
                    -0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794},
            {19.18916464, 0.04725744, 0.77263783, 313.23810451, 170.95427630, 74.01692503,
                    -0.00196176, -0.00004397, -0.00242939, 428.48202785, 0.40805281, 0.04240589},
            {30.06992276, 0.00859048, 1.77004347, -55.12002969, 44.96476227, 131.78422574,
                    0.00026291, 0.00005105, 0.00035372, 218.45945325, -0.32241464, -0.00508664},
    };

    /** A planet as seen from the Earth. */
    public static final class Seen {
        public String name;
        /** Geocentric J2000 direction (unit vector). */
        public double[] dir;
        /** Distance from the Earth and from the Sun (AU), phase angle (degrees) and visual magnitude. */
        public double distAu, sunAu, phaseDeg, mag;
    }

    /** Planet k of {@link #NAMES} at a TT Julian date. */
    public static Seen seen(int k, double jdTt) {
        double[] e = helio(EARTH, jdTt);
        double[] p = helio(ROW[k], jdTt);
        double d = dist(p, e);
        // where the planet was when the light left it
        p = helio(ROW[k], jdTt - d * LIGHT_DAYS_PER_AU);
        double[] g = {p[0] - e[0], p[1] - e[1], p[2] - e[2]};
        Seen s = new Seen();
        s.name = NAMES[k];
        s.distAu = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
        s.sunAu = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        double R = Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]);
        double ci = (s.sunAu * s.sunAu + s.distAu * s.distAu - R * R) / (2 * s.sunAu * s.distAu);
        s.phaseDeg = Math.acos(Math.max(-1, Math.min(1, ci))) / Astro.DEG;
        // ecliptic to equatorial (J2000)
        double ce = Math.cos(EPS), se = Math.sin(EPS);
        s.dir = new double[]{g[0] / s.distAu, (ce * g[1] - se * g[2]) / s.distAu, (se * g[1] + ce * g[2]) / s.distAu};
        s.mag = magnitude(k, s);
        return s;
    }

    /** All seven planets at a TT Julian date. */
    public static Seen[] all(double jdTt) {
        Seen[] s = new Seen[NAMES.length];
        for (int k = 0; k < s.length; k++) s[k] = seen(k, jdTt);
        return s;
    }

    /** Heliocentric ecliptic J2000 position (AU) of element row {@code row}. */
    static double[] helio(int row, double jdTt) {
        double t = (jdTt - Astro.J2000) / 36525;
        double[] el = EL[row];
        double a = el[0] + el[6] * t, ec = el[1] + el[7] * t;
        double inc = (el[2] + el[8] * t) * Astro.DEG, L = (el[3] + el[9] * t) * Astro.DEG;
        double peri = (el[4] + el[10] * t) * Astro.DEG, node = (el[5] + el[11] * t) * Astro.DEG;
        double w = peri - node;
        double M = Math.IEEEremainder(L - peri, 2 * Math.PI);
        double E = M + ec * Math.sin(M);
        for (int i = 0; i < 8; i++) {
            double dE = (E - ec * Math.sin(E) - M) / (1 - ec * Math.cos(E));
            E -= dE;
            if (Math.abs(dE) < 1e-12) break;
        }
        double xp = a * (Math.cos(E) - ec), yp = a * Math.sqrt(1 - ec * ec) * Math.sin(E);
        double cw = Math.cos(w), sw = Math.sin(w), co = Math.cos(node), so = Math.sin(node);
        double ci = Math.cos(inc), si = Math.sin(inc);
        return new double[]{
                (cw * co - sw * so * ci) * xp + (-sw * co - cw * so * ci) * yp,
                (cw * so + sw * co * ci) * xp + (-sw * so + cw * co * ci) * yp,
                sw * si * xp + cw * si * yp};
    }

    private static double dist(double[] a, double[] b) {
        double x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** Visual magnitude (Mallama and Hilton 2018; Saturn's rings by their tilt toward the Earth). */
    private static double magnitude(int k, Seen s) {
        double i = s.phaseDeg, r5 = 5 * Math.log10(s.sunAu * s.distAu);
        switch (k) {
            case 0:
                return -0.613 + 6.3280e-02 * i - 1.6336e-03 * i * i + 3.3644e-05 * i * i * i - 3.4265e-07 * i * i * i * i
                        + 1.6893e-09 * Math.pow(i, 5) - 3.0334e-12 * Math.pow(i, 6) + r5;
            case 1:
                if (i < 163.7) {
                    return -4.384 - 1.044e-03 * i + 3.687e-04 * i * i - 2.814e-06 * i * i * i + 8.938e-09 * i * i * i * i + r5;
                }
                return 236.05828 - 2.81914 * i + 8.39034e-03 * i * i + r5;
            case 2:
                if (i <= 50) return -1.601 + 0.02267 * i - 0.0001302 * i * i + r5;
                return -0.367 - 0.02573 * i + 0.0003445 * i * i + r5;
            case 3:
                return -9.395 - 3.7e-04 * i + 6.16e-04 * i * i + r5;
            case 4: {
                // the rings' tilt seen from the Earth: the more open, the brighter
                double sb = Math.abs(SATURN_POLE[0] * s.dir[0] + SATURN_POLE[1] * s.dir[1] + SATURN_POLE[2] * s.dir[2]);
                return -8.914 - 1.825 * sb + 0.026 * i - 0.378 * sb * Math.exp(-2.25 * i) + r5;
            }
            case 5:
                return -7.110 + 6.587e-3 * i + 1.045e-4 * i * i + r5;
            default:
                return -7.00 + r5;
        }
    }
}
