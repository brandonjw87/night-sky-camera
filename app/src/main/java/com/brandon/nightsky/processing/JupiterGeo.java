package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Jupiter tonight (3.2, Galileo's notebook): where it stands in the sky, how big and how bright it is, which way its
 * equator lies, and its four big moons beside it as seen from here: each in front of Jupiter, behind it, in its
 * shadow, or throwing its own shadow on it. Then the night's happenings (a moon crossing Jupiter's face, hidden
 * behind it, eclipsed, its shadow crossing), the nights ahead, and the nights Galileo first drew them, January 1610,
 * as the same sums give them.
 * <p>
 * The moons: J. Meeus, Astronomical Algorithms (2nd ed.), chapter 44's low-accuracy method (good to a few hundredths
 * of Jupiter's radius, so to a few minutes on the happenings' times), from the Earth and, with the phase angle taken
 * out, from the Sun: where Jupiter's shadow falls and where the moons' shadows land.
 */
public final class JupiterGeo {
    private JupiterGeo() {}

    public static final String[] NAMES = DeepSpace.MOONS;
    /** The moons' radii (km), their orbits' periods (days), and their brightness at 1 AU from the Sun and the Earth. */
    public static final double[] MOON_KM = {1821.6, 1560.8, 2634.1, 2410.3};
    public static final double[] PERIOD_DAYS = {1.769, 3.551, 7.155, 16.689};
    static final double[] V10 = {-1.68, -1.41, -2.09, -1.05};
    /** The farthest each strays from Jupiter (its radii), a little over the orbit's size. */
    public static final double[] REACH = {6.0, 9.6, 15.2, 26.8};
    public static final double JUPITER_KM = DeepSpace.JUPITER_KM, FLAT = DeepSpace.JUPITER_FLAT;

    /** One of the four as seen from here. */
    public static final class Moon {
        public int k;
        public String name;
        /** From the Earth: Jupiter's radii from its middle, x to the west along its equator, y to its north. */
        public double x, y;
        /** On the sky: arcseconds east and north of Jupiter's middle. */
        public double east, north;
        /** Nearer us than Jupiter is (else beyond it). */
        public boolean front;
        /** As the Sun sees it (for the shadows): its place, and whether it's on the Sun's side of Jupiter. */
        public double xs, ys;
        public boolean sunSide;
        public double mag;

        /** Crossing in front of Jupiter's face (lost in its light). */
        public boolean transit() {
            return front && onDisc(x, y);
        }

        /** Hidden behind Jupiter. */
        public boolean hidden() {
            return !front && onDisc(x, y);
        }

        /** In Jupiter's shadow: dark, gone from the picture. */
        public boolean eclipsed() {
            return !sunSide && onDisc(xs, ys);
        }

        /** Its shadow is on Jupiter's face (a black dot, too small for a phone). */
        public boolean shadowOnJupiter() {
            return sunSide && onDisc(xs, ys);
        }

        /** Lit and not behind Jupiter: there to be seen, if far enough from Jupiter's glare. */
        public boolean showing() {
            return !hidden() && !eclipsed() && !transit();
        }

        /** Its distance from Jupiter's middle on the sky, in Jupiter's radii. */
        public double dist() {
            return Math.hypot(x, y);
        }
    }

    static boolean onDisc(double x, double y) {
        return x * x + (y / FLAT) * (y / FLAT) < 1;
    }

    /** Jupiter from here at a moment. */
    public static final class View {
        public long unixMs;
        public double jdUtc, jdTt;
        /** Height above the horizon (degrees, with the air's bending), compass direction (degrees from north through east). */
        public double alt, az;
        /** The parallactic angle (degrees): the way to the zenith at Jupiter, as a position angle. */
        public double q;
        /** Distance (AU) from us and from the Sun, brightness, the Sun-Jupiter-Earth angle (degrees). */
        public double distAu, sunAu, mag, phaseDeg;
        /** Jupiter's equatorial radius on the sky (arcseconds). */
        public double radiusArcsec;
        /** Its north pole's position angle on the sky (degrees from north through east). */
        public double poleDeg;
        /** Its J2000 direction. */
        public double[] dir;
        /** Its angle from the Sun (degrees), and whether it's east of the Sun (an evening sky's) or west (a morning's). */
        public double elongDeg;
        public boolean evening;
        public Moon[] moons;

        public boolean up() {
            return alt > 0;
        }

        /** The light's trip from Jupiter to us (minutes). */
        public double lightMinutes() {
            return distAu * 499.004784 / 60;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // the moons

    /** The four at a TT Julian date: places from the Earth and from the Sun (not yet set on the sky). */
    public static Moon[] moons(double jdTt) {
        double d = jdTt - Astro.J2000;
        double V = deg(172.74 + 0.00111588 * d);
        double M = deg(357.529 + 0.9856003 * d);
        double N = deg(20.020 + 0.0830853 * d + 0.329 * Math.sin(V));
        double J = 66.115 + 0.9025179 * d - 0.329 * Math.sin(V);
        double A = 1.915 * Math.sin(M) + 0.020 * Math.sin(2 * M);
        double B = 5.555 * Math.sin(N) + 0.168 * Math.sin(2 * N);
        double K = deg(J + A - B);
        double R = 1.00014 - 0.01671 * Math.cos(M) - 0.00014 * Math.cos(2 * M);
        double r = 5.20872 - 0.25208 * Math.cos(N) - 0.00611 * Math.cos(2 * N);
        double D = Math.sqrt(r * r + R * R - 2 * r * R * Math.cos(K));
        double psi = Math.asin(R / D * Math.sin(K)) / Astro.DEG;
        double lam = deg(34.35 + 0.083091 * d + 0.329 * Math.sin(V) + B);
        double Ds = 3.12 * Math.sin(lam + 42.8 * Astro.DEG);
        double De = Ds - 2.22 * Math.sin(psi * Astro.DEG) * Math.cos(lam + 22 * Astro.DEG)
                - 1.30 * (r - D) / D * Math.sin(lam - 100.5 * Astro.DEG);
        double t = d - D / 173;
        double u1 = 163.8069 + 203.4058646 * t + psi - B;
        double u2 = 358.4140 + 101.2916335 * t + psi - B;
        double u3 = 5.7176 + 50.2345180 * t + psi - B;
        double u4 = 224.8092 + 21.4879800 * t + psi - B;
        double G = deg(331.18 + 50.310482 * t), H = deg(87.45 + 21.569231 * t);
        double c1 = 0.473 * Math.sin(2 * deg(u1 - u2)), c2 = 1.065 * Math.sin(2 * deg(u2 - u3));
        double c3 = 0.165 * Math.sin(G), c4 = 0.843 * Math.sin(H);
        double r1 = 5.9057 - 0.0244 * Math.cos(2 * deg(u1 - u2));
        double r2 = 9.3966 - 0.0882 * Math.cos(2 * deg(u2 - u3));
        double r3 = 14.9883 - 0.0216 * Math.cos(G);
        double r4 = 26.3627 - 0.1939 * Math.cos(H);
        double[] u = {u1 + c1, u2 + c2, u3 + c3, u4 + c4};
        double[] rr = {r1, r2, r3, r4};
        double sDe = Math.sin(De * Astro.DEG), sDs = Math.sin(Ds * Astro.DEG);
        Moon[] out = new Moon[4];
        for (int k = 0; k < 4; k++) {
            Moon m = new Moon();
            m.k = k;
            m.name = NAMES[k];
            double ue = deg(u[k]);
            m.x = rr[k] * Math.sin(ue);
            m.y = -rr[k] * Math.cos(ue) * sDe;
            m.front = Math.cos(ue) > 0;
            // the Sun's view: the angle counted from the Sun's side, not ours (the phase angle out)
            double us = deg(u[k] - psi);
            m.xs = rr[k] * Math.sin(us);
            m.ys = -rr[k] * Math.cos(us) * sDs;
            m.sunSide = Math.cos(us) > 0;
            m.mag = V10[k] + 5 * Math.log10(r * D);
            out[k] = m;
        }
        return out;
    }

    /** Degrees to radians, in 0..2pi. */
    private static double deg(double x) {
        return Astro.norm(x * Astro.DEG);
    }

    // ------------------------------------------------------------------------------------------------
    // in the sky

    /** Jupiter and its moons from a place (degrees, east longitude) at a moment. */
    public static View view(long unixMs, double latDeg, double lonDeg) {
        View v = new View();
        v.unixMs = unixMs;
        v.jdUtc = Astro.jdUtc(unixMs);
        v.jdTt = Astro.jdTt(v.jdUtc);
        Planets.Seen s = Planets.seen(3, v.jdTt);
        v.dir = s.dir;
        v.distAu = s.distAu;
        v.sunAu = s.sunAu;
        v.mag = s.mag;
        v.phaseDeg = s.phaseDeg;
        v.radiusArcsec = JUPITER_KM / (s.distAu * Astro.AU_KM) / Astro.ARCSEC;
        double lat = latDeg * Astro.DEG, lon = lonDeg * Astro.DEG;
        // up and where: of date, then the place's east, north and up
        double[] enu = new double[3];
        Rot.apply(Astro.j2000ToEnu(v.jdUtc, lat, lon), s.dir[0], s.dir[1], s.dir[2], enu);
        double alt = Astro.altitude(enu) / Astro.DEG;
        v.alt = alt + refraction(alt);
        v.az = Astro.azimuth(enu) / Astro.DEG;
        double[] toDate = Astro.j2000ToDate(v.jdTt);
        double[] dd = Rot.mulVec(toDate, s.dir);
        double ra = Math.atan2(dd[1], dd[0]), dec = Math.asin(Math.max(-1, Math.min(1, dd[2])));
        double h = Astro.gast(v.jdUtc) + lon - ra;
        v.q = Math.toDegrees(Math.atan2(Math.sin(h), Math.tan(lat) * Math.cos(dec) - Math.sin(dec) * Math.cos(h)));
        // the pole's way across the sky, of date
        double[] pd = Rot.mulVec(toDate, DeepSpace.JUPITER_POLE);
        v.poleDeg = positionAngle(dd, pd);
        // the Sun: how far, and which side
        double[] sun = Astro.sunOfDate(v.jdTt);
        double c = sun[0] * dd[0] + sun[1] * dd[1] + sun[2] * dd[2];
        v.elongDeg = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
        double sra = Math.atan2(sun[1], sun[0]);
        v.evening = Math.sin(ra - sra) > 0;
        v.moons = moons(v.jdTt);
        place(v.moons, v.radiusArcsec, v.poleDeg);
        return v;
    }

    /** Sets each moon's arcseconds east and north from its place along Jupiter's equator. */
    static void place(Moon[] ms, double radiusArcsec, double poleDeg) {
        double p = Math.toRadians(poleDeg);
        // the west along Jupiter's equator, and its north, as (east, north) on the sky
        double we = -Math.cos(p), wn = Math.sin(p), ne = Math.sin(p), nn = Math.cos(p);
        for (Moon m : ms) {
            m.east = radiusArcsec * (m.x * we + m.y * ne);
            m.north = radiusArcsec * (m.x * wn + m.y * nn);
        }
    }

    /** The position angle (degrees from north through east, 0..360) of direction {@code b}'s way across the sky at {@code a}. */
    static double positionAngle(double[] a, double[] b) {
        // the sky's east and north at a
        double ex = -a[1], ey = a[0];
        double en = Math.hypot(ex, ey);
        if (en < 1e-12) return 0;
        ex /= en;
        ey /= en;
        double nx = -a[2] * ey, ny = a[2] * ex, nz = a[0] * ey - a[1] * ex;
        double e = b[0] * ex + b[1] * ey, n = b[0] * nx + b[1] * ny + b[2] * nz;
        return MoonGeo.norm360(Math.toDegrees(Math.atan2(e, n)));
    }

    /** The air's bending (degrees) for a true altitude (Saemundsson). */
    static double refraction(double altDeg) {
        if (altDeg < -1.5) return 0;
        double a = Math.max(-1.5, altDeg);
        return 1.02 / Math.tan(Math.toRadians(a + 10.3 / (a + 5.11))) / 60;
    }

    /**
     * Where a moon is in a picture whose up has position angle {@code upPa} (degrees), at {@code arcsecPerPx}: its
     * offset (px, x right, y down) from Jupiter's middle. The picture is the sky as seen, not mirrored: north up puts
     * east on the left.
     */
    public static double[] inPicture(Moon m, double upPa, double arcsecPerPx) {
        return inPicture(m.east, m.north, upPa, arcsecPerPx);
    }

    public static double[] inPicture(double east, double north, double upPa, double arcsecPerPx) {
        double a = Math.toRadians(upPa);
        // the picture's up and right as (east, north) on the sky: up at upPa; right a quarter turn the other way from east
        double ue = Math.sin(a), un = Math.cos(a), re = -Math.cos(a), rn = Math.sin(a);
        double up = east * ue + north * un, right = east * re + north * rn;
        return new double[]{right / arcsecPerPx, -up / arcsecPerPx};
    }

    // ------------------------------------------------------------------------------------------------
    // the night's happenings

    public static final int TRANSIT = 0, SHADOW = 1, HIDDEN = 2, ECLIPSE = 3;

    /** A moon crossing Jupiter, its shadow crossing, hidden behind it, or in its shadow: from when to when. */
    public static final class Event {
        public int moon, kind;
        /** Unix ms; {@link Long#MIN_VALUE}: already happening when the search began; {@link Long#MAX_VALUE}: still at its end. */
        public long startMs, endMs;

        public String name() {
            return NAMES[moon];
        }

        /** In words, with the times in a zone: "Io crosses in front of Jupiter, 9:12 PM to 11:24 PM". */
        public String words(TimeZone z) {
            String what;
            switch (kind) {
                case TRANSIT:
                    what = name() + " crosses in front of Jupiter";
                    break;
                case SHADOW:
                    what = name() + "'s shadow crosses Jupiter (a black dot for telescopes)";
                    break;
                case HIDDEN:
                    what = name() + " is hidden behind Jupiter";
                    break;
                default:
                    what = name() + " is in Jupiter's shadow, gone dark";
                    break;
            }
            String from = startMs == Long.MIN_VALUE ? null : clock(startMs, z), to = endMs == Long.MAX_VALUE ? null : clock(endMs, z);
            if (from == null && to == null) return what + " all this time";
            if (from == null) return what + " until about " + to;
            if (to == null) return what + " from about " + from;
            return what + ", about " + from + " to " + to;
        }

        /** Whether it's on at a moment. */
        public boolean at(long ms) {
            return ms >= startMs && ms < endMs;
        }
    }

    static boolean state(Moon m, int kind) {
        switch (kind) {
            case TRANSIT:
                return m.transit();
            case SHADOW:
                return m.shadowOnJupiter();
            case HIDDEN:
                return m.hidden();
            default:
                return m.eclipsed();
        }
    }

    /** Every happening between two moments, in the order they start (each time to a few seconds of the sums). */
    public static List<Event> events(long fromMs, long toMs) {
        final long step = 120_000L;
        List<Event> out = new ArrayList<Event>();
        Event[][] open = new Event[4][4];
        boolean[][] was = null;
        long t = fromMs;
        while (true) {
            Moon[] ms = moons(Astro.jdTt(Astro.jdUtc(t)));
            boolean[][] now = new boolean[4][4];
            for (int k = 0; k < 4; k++) {
                for (int e = 0; e < 4; e++) {
                    boolean on = state(ms[k], e);
                    now[k][e] = on;
                    if (was == null) {
                        if (on) open[k][e] = event(k, e, Long.MIN_VALUE);
                    } else if (on && !was[k][e]) {
                        open[k][e] = event(k, e, edge(t - step, t, k, e));
                    } else if (!on && was[k][e] && open[k][e] != null) {
                        open[k][e].endMs = edge(t - step, t, k, e);
                        out.add(open[k][e]);
                        open[k][e] = null;
                    }
                }
            }
            was = now;
            if (t >= toMs) break;
            t = Math.min(toMs, t + step);
        }
        for (int k = 0; k < 4; k++) {
            for (int e = 0; e < 4; e++) {
                if (open[k][e] != null) {
                    open[k][e].endMs = Long.MAX_VALUE;
                    out.add(open[k][e]);
                }
            }
        }
        Collections.sort(out, new Comparator<Event>() {
            @Override
            public int compare(Event a, Event b) {
                return a.startMs < b.startMs ? -1 : a.startMs > b.startMs ? 1 : a.moon - b.moon;
            }
        });
        return out;
    }

    private static Event event(int k, int e, long start) {
        Event ev = new Event();
        ev.moon = k;
        ev.kind = e;
        ev.startMs = start;
        return ev;
    }

    /** The moment between a and b (ms) where state e of moon k changes: halved to a second. */
    private static long edge(long a, long b, int k, int e) {
        boolean sa = state(moons(Astro.jdTt(Astro.jdUtc(a)))[k], e);
        while (b - a > 1000) {
            long m = (a + b) / 2;
            boolean sm = state(moons(Astro.jdTt(Astro.jdUtc(m)))[k], e);
            if (sm == sa) a = m;
            else b = m;
        }
        return b;
    }

    // ------------------------------------------------------------------------------------------------
    // rising, setting, darkness

    /** The next moment after {@code from} that Jupiter rises (or sets) here, searched for two days; -1 if neither. */
    public static long next(long from, double lat, double lon, boolean rise) {
        long step = 600_000L;
        double prev = view(from, lat, lon).alt;
        for (long t = from + step; t <= from + 2 * 86_400_000L; t += step) {
            double a = altOnly(t, lat, lon);
            if (rise ? prev <= 0 && a > 0 : prev > 0 && a <= 0) {
                long lo = t - step, hi = t;
                while (hi - lo > 15_000) {
                    long m = (lo + hi) / 2;
                    boolean upM = altOnly(m, lat, lon) > 0;
                    if (upM == rise) hi = m;
                    else lo = m;
                }
                return hi;
            }
            prev = a;
        }
        return -1;
    }

    /** Jupiter's height (degrees, bent by the air) alone. */
    public static double altOnly(long unixMs, double latDeg, double lonDeg) {
        double jd = Astro.jdUtc(unixMs);
        Planets.Seen s = Planets.seen(3, Astro.jdTt(jd));
        double[] enu = new double[3];
        Rot.apply(Astro.j2000ToEnu(jd, latDeg * Astro.DEG, lonDeg * Astro.DEG), s.dir[0], s.dir[1], s.dir[2], enu);
        double a = Astro.altitude(enu) / Astro.DEG;
        return a + refraction(a);
    }

    /** The Sun's height (degrees). */
    public static double sunAlt(long unixMs, double latDeg, double lonDeg) {
        return Astro.altitude(Astro.sunEnu(unixMs, latDeg * Astro.DEG, lonDeg * Astro.DEG)) / Astro.DEG;
    }

    /** Dark enough for Jupiter's moons (the Sun 8 degrees down) and Jupiter 12 degrees up. */
    public static boolean goodSky(long unixMs, double lat, double lon) {
        return sunAlt(unixMs, lat, lon) < -8 && altOnly(unixMs, lat, lon) > 12;
    }

    /**
     * The best moment of a night to look, from {@code from} on (searched for {@code hours}): the first moment Jupiter
     * stands 25 degrees up in a dark sky, else its highest in a dark sky; -1 if it never stands 12 degrees up in the dark.
     */
    public static long best(long from, double lat, double lon, double hours) {
        long step = 600_000L, end = from + Math.round(hours * 3_600_000L);
        long highest = -1;
        double top = 12;
        for (long t = from; t <= end; t += step) {
            if (sunAlt(t, lat, lon) >= -8) continue;
            double a = altOnly(t, lat, lon);
            if (a >= 25) return t;
            if (a > top) {
                top = a;
                highest = t;
            }
        }
        return highest;
    }

    // ------------------------------------------------------------------------------------------------
    // a line of them, as Galileo drew it

    /**
     * The moons beside Jupiter as one line of text, east on the left as Galileo drew them: {@code O} for Jupiter, each
     * moon that shows its letter (I, E, G, C), or {@code *} when {@code stars}. {@code width} characters; moons
     * behind Jupiter, in its shadow or crossing it left out.
     */
    public static String line(Moon[] ms, int width, boolean stars) {
        char[] c = new char[width];
        java.util.Arrays.fill(c, ' ');
        int mid = width / 2;
        c[mid] = 'O';
        double reach = REACH[3];
        // nearest first, so a far moon is moved aside rather than a near one
        Moon[] order = ms.clone();
        java.util.Arrays.sort(order, new Comparator<Moon>() {
            @Override
            public int compare(Moon a, Moon b) {
                return Double.compare(Math.abs(a.x), Math.abs(b.x));
            }
        });
        for (Moon m : order) {
            if (!m.showing()) continue;
            int col = mid + (int) Math.round(m.x / reach * (mid - 1));
            if (col == mid) col += m.x < 0 ? -1 : 1;
            int dir = col < mid ? -1 : 1;
            while (col > 0 && col < width - 1 && c[col] != ' ') col += dir;
            if (col < 0 || col >= width || c[col] != ' ') continue;
            c[col] = stars ? '*' : m.name.charAt(0);
        }
        return new String(c).replaceAll("\\s+$", "");
    }

    // ------------------------------------------------------------------------------------------------
    // Galileo's first nights

    /**
     * The nights of January 1610 in Galileo's Sidereus Nuncius, in Padua: the day; the hour (UT), from the hours after
     * sunset he gave (the first hour, unless he said otherwise), as A. Longhin's analysis of the book puts them
     * (arXiv 2503.12543); and the little stars he drew, east to west, with Jupiter as O.
     */
    public static final int[] GALILEO_DAY = {7, 8, 10, 11, 12, 13, 15};
    public static final double[] GALILEO_UT = {16.73, 16.77, 16.80, 16.83, 18.85, 16.88, 18.92};
    public static final String[] GALILEO_DREW = {"* * O *", "O * * *", "* * O", "* * O", "* * O *", "* O * * *", "O * * * *"};
    /**
     * Which moons his little stars were, east to west, as Longhin names them (0 Io, 1 Europa, 2 Ganymede, 3 Callisto):
     * the others were there too, too close to Jupiter or to each other for his telescope.
     */
    public static final int[][] GALILEO_SAW = {{3, 0, 2}, {0, 1, 2}, {3, 1}, {2, 3}, {2, 3, 1}, {1, 2, 0, 3}, {0, 1, 2, 3}};
    /** What he wrote, in short. */
    public static final String[] GALILEO_SAID = {
            "Three little stars in a straight line beside Jupiter, two to the east and one to the west. He took them for faraway stars.",
            "All three to the west now, and closer together. Had Jupiter moved past them?",
            "Only two, both to the east. He guessed the third was hidden behind Jupiter.",
            "Two again, both to the east.",
            "Two at first; in the third hour of the night a third one appeared, almost touching Jupiter on the east.",
            "Four little stars for the first time: one to the east, three to the west.",
            "All four to the west, in a line. They had to be going round Jupiter.",
    };
    /** Padua. */
    public static final double PADUA_LAT = 45.4064, PADUA_LON = 11.8768;

    /** The moment of Galileo's night {@code i} (Unix ms). */
    public static long galileoMs(int i) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.clear();
        c.set(1610, Calendar.JANUARY, GALILEO_DAY[i], 0, 0, 0);
        return c.getTimeInMillis() + Math.round(GALILEO_UT[i] * 3_600_000L);
    }

    /** Jupiter and its moons on Galileo's night {@code i}, from Padua. */
    public static View galileo(int i) {
        return view(galileoMs(i), PADUA_LAT, PADUA_LON);
    }

    // ------------------------------------------------------------------------------------------------
    // words

    /** "9:12 PM" in a zone. */
    public static String clock(long ms, TimeZone z) {
        Calendar c = Calendar.getInstance(z, Locale.US);
        c.setTimeInMillis(ms);
        int h = c.get(Calendar.HOUR_OF_DAY), mi = c.get(Calendar.MINUTE);
        return String.format(Locale.US, "%d:%02d %s", h % 12 == 0 ? 12 : h % 12, mi, h < 12 ? "AM" : "PM");
    }

    /** Where to look: "34 degrees up in the east-southeast". */
    public static String where(View v) {
        return String.format(Locale.US, "%.0f degrees up in the %s", Math.max(0, v.alt), compass(v.az));
    }

    static String compass(double az) {
        String[] d = {"north", "north-northeast", "northeast", "east-northeast", "east", "east-southeast", "southeast", "south-southeast",
                "south", "south-southwest", "southwest", "west-southwest", "west", "west-northwest", "northwest", "north-northwest"};
        return d[(int) Math.floor(MoonGeo.norm360(az + 11.25) / 22.5) % 16];
    }
}
