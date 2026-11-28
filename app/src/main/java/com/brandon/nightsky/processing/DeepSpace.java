package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Deep Space Network (2.4): where the farthest things people have made are in the sky, and the far worlds around them.
 * <ul>
 * <li>The five spacecraft leaving the Solar System: Voyager 1 and 2, New Horizons, Pioneer 10 and 11. Each is placed from
 * its position on 2026-11-22 (Heavens-Above's table of spacecraft escaping the Solar System: direction from the Earth,
 * distances from the Earth and the Sun, speed), moved straight out from the Sun at its speed. Out there the Sun's pull
 * barely bends their paths, so the direction from the Sun holds to a tenth of a degree for years either side; the
 * direction from the Earth then swings back and forth with the Earth's own trip around the Sun (by up to a degree, for
 * New Horizons), which is worked out exactly. None of them can be seen, of course: what's placed is where they are.
 * <li>L2, the point the James Webb Space Telescope loops around, 1.5 million km from the Earth straight away from the Sun.
 * Webb itself is somewhere on a loop around it so wide that it can be 30 degrees away, so the point is named, not Webb.
 * <li>Pluto (in {@link Planets}).
 * <li>Jupiter's four big moons, Io, Europa, Ganymede and Callisto, where they are beside it: J. Meeus, Astronomical
 * Algorithms (2nd ed.), chapter 44's low-accuracy method, good to a few hundredths of Jupiter's width; set on the sky
 * by Jupiter's pole (IAU), so the moons line up along its equator as they do in a telescope.
 * </ul>
 */
public final class DeepSpace {
    private DeepSpace() {}

    // ------------------------------------------------------------------------------------------------
    // Jupiter's moons
    // ------------------------------------------------------------------------------------------------

    public static final String[] MOONS = {"Io", "Europa", "Ganymede", "Callisto"};
    /** Jupiter's north pole (IAU, J2000), equatorial radius (km) and polar radius as a share of it. */
    static final double[] JUPITER_POLE = Astro.vec(268.057 * Astro.DEG, 64.495 * Astro.DEG);
    static final double JUPITER_KM = 71492, JUPITER_FLAT = 0.9351;

    /** One of Jupiter's big moons as seen from the Earth. */
    public static final class Moon {
        public String name;
        /** Its place beside Jupiter in Jupiter's widths from its middle: x to the west along Jupiter's equator, y to its north. */
        public double x, y;
        /** In front of Jupiter (nearer us than it), else behind. */
        public boolean front;
        /** Its J2000 direction. */
        public double[] dir;

        /** Whether it's in front of or behind Jupiter's disc (so lost in it, or hidden). */
        public boolean onDisc() {
            return x * x + (y / JUPITER_FLAT) * (y / JUPITER_FLAT) < 1;
        }
    }

    /** Jupiter's four big moons at a TT Julian date (Meeus, chapter 44, the low-accuracy method). */
    public static Moon[] jupiterMoons(double jdTt) {
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
        double[] u = {deg(u1 + c1), deg(u2 + c2), deg(u3 + c3), deg(u4 + c4)};
        double[] rr = {r1, r2, r3, r4};
        double sDe = Math.sin(De * Astro.DEG);
        // on the sky: Jupiter's direction, its pole's way across the sky (its north), and its west
        Planets.Seen jup = Planets.seen(3, jdTt);
        double[] dj = jup.dir;
        double pd = JUPITER_POLE[0] * dj[0] + JUPITER_POLE[1] * dj[1] + JUPITER_POLE[2] * dj[2];
        double[] n = {JUPITER_POLE[0] - pd * dj[0], JUPITER_POLE[1] - pd * dj[1], JUPITER_POLE[2] - pd * dj[2]};
        double nn = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        for (int i = 0; i < 3; i++) n[i] /= nn;
        double[] w = {dj[1] * n[2] - dj[2] * n[1], dj[2] * n[0] - dj[0] * n[2], dj[0] * n[1] - dj[1] * n[0]};
        double ang = JUPITER_KM / (jup.distAu * Probes.AU_KM);
        Moon[] out = new Moon[4];
        for (int k = 0; k < 4; k++) {
            Moon m = new Moon();
            m.name = MOONS[k];
            m.x = rr[k] * Math.sin(u[k]);
            m.y = -rr[k] * Math.cos(u[k]) * sDe;
            m.front = Math.cos(u[k]) > 0;
            double[] v = new double[3];
            for (int i = 0; i < 3; i++) v[i] = dj[i] + ang * (m.x * w[i] + m.y * n[i]);
            double vn = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            m.dir = new double[]{v[0] / vn, v[1] / vn, v[2] / vn};
            out[k] = m;
        }
        return out;
    }

    /** Degrees to radians, in 0..2pi. */
    private static double deg(double x) {
        return Astro.norm(x * Astro.DEG);
    }

    // ------------------------------------------------------------------------------------------------
    // The card: each of them in the photo, up in the sky, or below the horizon
    // ------------------------------------------------------------------------------------------------

    /** One line of the Deep Space Network card. */
    public static final class Contact {
        public final Probes.Seen what;
        /** Where it was: in the photo (and where in it), up in the sky (how high, and which way), or below the horizon. */
        public boolean inPhoto, up;
        public double u = Double.NaN, v = Double.NaN, altDeg = Double.NaN, azDeg = Double.NaN;
        /** "Voyager 1: in your photo", and the rest in a sentence or two. */
        public String head = "", words = "";

        Contact(Probes.Seen what) {
            this.what = what;
        }
    }

    /**
     * The card for a photo: where everything above was when it was taken ({@code jdUtc}), seen from {@code sky}'s place
     * (null or unplaced: then only whether it's in the photo), in the photo's crop (x0, y0, w, h) of the frame the
     * solution {@code sol} maps (null: no photo placed on the sky). Times are said in {@code zone}. Those in the photo first.
     */
    public static List<Contact> network(SkySolver.Solution sol, double jdUtc, SessionSky sky, int x0, int y0, int w, int h,
                                        TimeZone zone) {
        List<Contact> in = new ArrayList<Contact>(), rest = new ArrayList<Contact>();
        if (Double.isNaN(jdUtc)) return in;
        double jdTt = Astro.jdTt(jdUtc);
        SkyGuide.Frame f = sol != null && w > 0 && h > 0 ? new SkyGuide.Frame(sol, x0, y0, w, h) : null;
        double[] enu = sky != null && sky.located() ? Astro.j2000ToEnu(jdUtc, sky.latDeg * Astro.DEG, sky.lonDeg * Astro.DEG) : null;
        for (Probes.Seen s : Probes.all(jdTt)) {
            Contact c = new Contact(s);
            double[] uv = f != null ? f.near(s.dir) : null;
            if (uv != null && SkyGuide.inside(uv)) {
                c.inPhoto = true;
                c.u = uv[0];
                c.v = uv[1];
            }
            if (enu != null) {
                double[] e = new double[3];
                Rot.apply(enu, s.dir[0], s.dir[1], s.dir[2], e);
                c.altDeg = Astro.altitude(e) / Astro.DEG;
                c.azDeg = Astro.azimuth(e) / Astro.DEG;
                c.up = c.altDeg > 0;
            }
            c.head = s.name + ": " + (c.inPhoto ? "in your photo, " + SkyGuide.where(c.u, c.v)
                    : enu == null ? "not in your photo"
                    : c.up ? String.format(Locale.US, "up in your sky, %.0f° high in the %s", c.altDeg, Probes.compass(c.azDeg))
                    : "below your horizon");
            c.words = words(s, jdUtc, zone);
            (c.inPhoto ? in : rest).add(c);
        }
        in.addAll(rest);
        return in;
    }

    /** What the card says about one of them: how far, how long a message takes, and its fact. */
    static String words(Probes.Seen s, double jdUtc, TimeZone zone) {
        StringBuilder b = new StringBuilder();
        if ("l2".equals(s.kind)) {
            b.append("1.5 million km away: a radio message gets there in 5 seconds. ");
        } else {
            b.append(String.format(Locale.US, "%s km away, %.0f times farther than the Sun.", Probes.bigNumber(s.distAu * Probes.AU_KM), s.distAu));
            if ("probe".equals(s.kind)) {
                b.append(" A radio message takes ").append(lightWords(s.lightHours())).append(" to get there");
                if (zone != null) b.append(": one sent when you took this photo arrives ").append(arrival(jdUtc, s.lightHours(), zone));
                b.append(".");
                b.append(String.format(Locale.US, " Speed: %s km an hour.", Probes.bigNumber(Math.round(s.kmPerS * 3.6) * 1000.0)));
                b.append(" Launched ").append(s.launched).append(s.talking ? "; still sends messages home." : "; silent now, still flying.");
            } else {
                b.append(" Its light left it ").append(lightWords(s.lightHours())).append(" before you took this photo.");
            }
            b.append(" ");
        }
        b.append(s.fact);
        return b.toString();
    }

    /** "24 hours", "9 hours 15 minutes", "5 hours 2 minutes". */
    public static String lightWords(double hours) {
        long min = Math.round(hours * 60);
        long h = min / 60, m = min % 60;
        if (h == 0) return m + (m == 1 ? " minute" : " minutes");
        String hs = h + (h == 1 ? " hour" : " hours");
        return m == 0 ? hs : hs + " " + m + (m == 1 ? " minute" : " minutes");
    }

    /** When a message sent at {@code jdUtc} arrives, {@code hours} later: "at 9:14 pm the next day". */
    static String arrival(double jdUtc, double hours, TimeZone zone) {
        long sentMs = Math.round((jdUtc - 2440587.5) * 86400000.0);
        long atMs = sentMs + Math.round(hours * 3600000.0);
        Calendar a = Calendar.getInstance(zone, Locale.US), s = Calendar.getInstance(zone, Locale.US);
        s.setTimeInMillis(sentMs);
        a.setTimeInMillis(atMs);
        int h = a.get(Calendar.HOUR_OF_DAY), m = a.get(Calendar.MINUTE);
        String clock = String.format(Locale.US, "%d:%02d %s", h % 12 == 0 ? 12 : h % 12, m, h < 12 ? "am" : "pm");
        long days = days(a) - days(s);
        return "at " + clock + (days == 0 ? "" : days == 1 ? " the next day" : " " + days + " days later");
    }

    private static long days(Calendar c) {
        return c.get(Calendar.YEAR) * 400L + c.get(Calendar.DAY_OF_YEAR);
    }

}
