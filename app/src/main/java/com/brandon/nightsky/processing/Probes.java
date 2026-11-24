package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The farthest things people have made, where they are (2.4's Deep Space Network, on its own since 2.6 so the watch's Sky
 * Pointer can find them too): the five spacecraft leaving the Solar System, Voyager 1 and 2, New Horizons, Pioneer 10
 * and 11, each placed from its position on 2026-11-22 (Heavens-Above's table of spacecraft escaping the Solar System:
 * direction from the Earth, distances from the Earth and the Sun, speed) and moved straight out from the Sun at its speed;
 * L2, the point the James Webb Space Telescope loops around; and Pluto. Only {@link Astro} and {@link Planets} needed.
 */
public final class Probes {
    private Probes() {}

    /** Light time per AU (s), and the AU (km). */
    static final double LIGHT_S_PER_AU = 499.004784, AU_KM = 149597870.7;
    /** The table's moment: 2026-11-22 0h UTC (TT Julian date). */
    static final double EPOCH_TT = 2461366.5 + 69.2 / 86400;

    /** One of the spacecraft leaving the Solar System, as listed at {@link #EPOCH_TT}. */
    public static final class Probe {
        public final String name;
        /** Launch year, whether it still sends messages home, and what to say about it. */
        public final int launched;
        public final boolean talking;
        public final String fact;
        /** Direction from the Sun (J2000 unit vector) and distance from it (AU) at the epoch; speed (AU per year, km/s). */
        final double[] out;
        final double sunAu0, auPerYear, kmPerS;

        Probe(String name, int launched, boolean talking, double raH, double raM, double decD, double decM, double earthAu,
              double sunAu, double kmPerS, double auPerYear, String fact) {
            this.name = name;
            this.launched = launched;
            this.talking = talking;
            this.fact = fact;
            this.sunAu0 = sunAu;
            this.auPerYear = auPerYear;
            this.kmPerS = kmPerS;
            // where it was from the Sun: the Earth's place plus the way to it
            double dec = Math.signum(decD == 0 ? decM : decD) * (Math.abs(decD) + Math.abs(decM) / 60);
            double[] d = Astro.vec((raH + raM / 60) * 15 * Astro.DEG, dec * Astro.DEG);
            double[] e = earth(EPOCH_TT);
            double[] p = {e[0] + earthAu * d[0], e[1] + earthAu * d[1], e[2] + earthAu * d[2]};
            double n = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            out = new double[]{p[0] / n, p[1] / n, p[2] / n};
        }
    }

    /**
     * The five, from Heavens-Above's table for 2026-11-22: right ascension and declination (J2000) seen from the Earth,
     * distance from the Earth and the Sun (AU), speed (km/s and AU a year).
     */
    public static final Probe[] PROBES = {
            new Probe("Voyager 1", 1977, true, 17, 16, 12, 4, 173.245, 172.477, 16.917, 3.569,
                    "The farthest thing people have ever made. In November 2026 it became the first spacecraft a whole light-day "
                            + "from the Earth. It carries a golden record of sounds and music from the Earth, in case anyone finds it."),
            new Probe("Voyager 2", 1977, true, 20, 6, -59, -41, 144.975, 144.488, 15.255, 3.218,
                    "The only spacecraft ever to visit Uranus and Neptune. In 2018 it left the Sun's bubble of wind and crossed into "
                            + "the space between the stars. It carries a golden record too."),
            new Probe("New Horizons", 2006, true, 19, 16, -20, -17, 66.743, 66.099, 13.560, 2.861,
                    "It flew past Pluto in 2015 and sent back the first close-up pictures of it, with a giant heart-shaped plain "
                            + "of ice. In 2019 it flew past Arrokoth, the farthest world anyone has explored."),
            new Probe("Pioneer 10", 1972, false, 5, 14, 26, 1, 141.416, 142.338, 11.862, 2.502,
                    "The first spacecraft to fly past Jupiter, in 1973. Its last faint message came in 2003. It carries a "
                            + "golden plaque showing two people and a map to the Sun."),
            new Probe("Pioneer 11", 1973, false, 18, 54, -9, -2, 120.381, 119.707, 11.122, 2.346,
                    "The first spacecraft to fly past Saturn, in 1979. It went quiet in 1995 and is drifting toward the "
                            + "constellation Aquila, the Eagle."),
    };

    /** L2's distance from the Earth (AU): 1.5 million km. */
    static final double L2_AU = 1.5e6 / AU_KM;

    /** Something placed: a probe, L2, or a far world. */
    public static final class Seen {
        public String name = "";
        /** "probe", "l2" or "pluto". */
        public String kind = "probe";
        /** Direction from the Earth (J2000 unit vector), distances from the Earth and the Sun (AU), speed (km/s; NaN: none). */
        public double[] dir;
        public double distAu, sunAu = Double.NaN, kmPerS = Double.NaN;
        /** Visual magnitude (NaN: far too faint to say). */
        public double mag = Double.NaN;
        public int launched;
        public boolean talking;
        public String fact = "";

        /** How long light (and a radio message) takes to get there, in hours. */
        public double lightHours() {
            return distAu * LIGHT_S_PER_AU / 3600;
        }
    }

    /** The Earth's place from the Sun (J2000 equatorial, AU). */
    static double[] earth(double jdTt) {
        return equatorial(Planets.helio(2, jdTt));
    }

    static double[] equatorial(double[] ecl) {
        double ce = Math.cos(Planets.EPS), se = Math.sin(Planets.EPS);
        return new double[]{ecl[0], ce * ecl[1] - se * ecl[2], se * ecl[1] + ce * ecl[2]};
    }

    /** Probe {@code p} at a TT Julian date: where it was when the light now arriving left it. */
    public static Seen seen(Probe p, double jdTt) {
        double[] e = earth(jdTt);
        double r = p.sunAu0 + p.auPerYear * (jdTt - EPOCH_TT) / 365.25;
        double[] g = new double[3];
        double d = 0;
        for (int it = 0; it < 2; it++) {
            double rr = r - p.auPerYear * d * LIGHT_S_PER_AU / 86400 / 365.25;
            for (int i = 0; i < 3; i++) g[i] = rr * p.out[i] - e[i];
            d = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
        }
        Seen s = new Seen();
        s.name = p.name;
        s.dir = new double[]{g[0] / d, g[1] / d, g[2] / d};
        s.distAu = d;
        s.sunAu = r;
        s.kmPerS = p.kmPerS;
        s.launched = p.launched;
        s.talking = p.talking;
        s.fact = p.fact;
        return s;
    }

    /** L2 at a TT Julian date: straight away from the Sun, past the Earth. */
    public static Seen l2(double jdTt) {
        double[] e = earth(jdTt);
        double n = Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]);
        Seen s = new Seen();
        s.name = "L2, Webb's home";
        s.kind = "l2";
        s.dir = new double[]{e[0] / n, e[1] / n, e[2] / n};
        s.distAu = L2_AU;
        s.sunAu = n + L2_AU;
        s.launched = 2021;
        s.talking = true;
        s.fact = "The James Webb Space Telescope loops around this point, 1.5 million km away, where the Earth hides the Sun's "
                + "heat behind it. The loop is so big that Webb itself can be 30 degrees from here.";
        return s;
    }

    /** Pluto at a TT Julian date. */
    public static Seen pluto(double jdTt) {
        Planets.Seen p = Planets.pluto(jdTt);
        Seen s = new Seen();
        s.name = "Pluto";
        s.kind = "pluto";
        s.dir = p.dir;
        s.distAu = p.distAu;
        s.sunAu = p.sunAu;
        s.mag = p.mag;
        s.fact = "A dwarf planet smaller than our Moon, so far away that one trip around the Sun takes it 248 years. Too faint "
                + "for a phone to see, but it's there. New Horizons flew past it in 2015.";
        return s;
    }

    /** The five probes, L2 and Pluto at a TT Julian date. */
    public static List<Seen> all(double jdTt) {
        List<Seen> out = new ArrayList<Seen>();
        for (Probe p : PROBES) out.add(seen(p, jdTt));
        out.add(l2(jdTt));
        out.add(pluto(jdTt));
        return out;
    }

    /** 25,900,000,000 as "25.9 billion", 1,500,000 as "1.5 million", 60,900 as "60,900". */
    public static String bigNumber(double x) {
        if (x >= 1e9) return String.format(Locale.US, "%.1f billion", x / 1e9);
        if (x >= 1e6) return String.format(Locale.US, "%.1f million", x / 1e6);
        return String.format(Locale.US, "%,.0f", x);
    }

    /** A compass direction by its azimuth (degrees east of north): "north", "south-west". */
    public static String compass(double az) {
        String[] n = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        int k = (int) Math.floor(((az % 360 + 360) % 360 + 22.5) / 45) % 8;
        return n[k];
    }
}
