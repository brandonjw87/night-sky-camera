package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sky Pointer (2.6, on the watch): hold your arm out at the sky and the watch says what's there; or turn the crown to
 * pick something (the Moon, a planet, a bright star, a galaxy, a black hole, Voyager 1) and it steers your arm to it,
 * buzzing faster as you close in, even when it's below the horizon (then you're pointing through the Earth).
 * <p>
 * Where the arm points comes from the watch's rotation sensor (its gyro, accelerometer and compass together): the axis
 * along the arm is the watch's own x axis, toward the crown, which is toward the hand as a watch is usually worn. That
 * is turned from magnetic north to true north by the magnetic declination there, then from east-north-up into the sky's
 * own frame (J2000) by the place and the time. A watch's compass is good to 5 or 10 degrees at best, so the pointer is
 * generous: the brighter a thing, the farther off the arm can be and still be pointing at it.
 * <p>
 * Pure Java: the watch builds it with {@link Astro}, {@link Rot}, {@link Planets}, {@link Probes}, {@link BrightStars} and
 * {@link Constellations}.
 */
public final class SkyPointer {
    private SkyPointer() {}

    /** Within this many degrees, a thing being found is found. */
    public static final double GOT_DEG = 6;
    /** The buzzing starts this close (degrees) and is quickest at {@link #GOT_DEG}: a tick every so many ms between. */
    public static final double BUZZ_FROM_DEG = 45;
    public static final long BUZZ_SLOW_MS = 1100, BUZZ_FAST_MS = 140;
    /** Stars named on the way up to this magnitude (fainter ones a watch's compass couldn't tell apart anyway). */
    public static final float FAINTEST = 4.5f;

    public enum Kind {
        STAR("star"), PLANET("planet"), MOON("moon"), SUN("sun"), DEEP("deep sky"), BLACK_HOLE("black hole"), PROBE("spacecraft"),
        POINT("point"), DWARF("dwarf planet");

        public final String word;

        Kind(String word) {
            this.word = word;
        }
    }

    /** Something to name or to find. */
    public static final class Thing {
        public final String name;
        public final Kind kind;
        /** What it is, in a few words: "the brightest star in Lyra, 25 light years away". */
        public String about = "";
        /** Visual magnitude (NaN: not to be seen: a black hole, a spacecraft). */
        public double mag = Double.NaN;
        /** J2000 direction from the Earth (unit vector; null for the Sun and the Moon, which are placed straight in {@link #enu}). */
        public double[] dir;
        /** Where it is in the sky at the moment: east, north, up (unit vector), its height and its compass direction (degrees). */
        public double[] enu;
        public double altDeg, azDeg;

        Thing(String name, Kind kind) {
            this.name = name;
            this.kind = kind;
        }

        public boolean up() {
            return altDeg > 0;
        }

        /** How far off the arm can point and still be pointing at it (degrees): the brighter, the farther. */
        public double reach() {
            switch (kind) {
                case MOON:
                case SUN:
                    return 12;
                case PLANET:
                    return mag < -1.5 ? 13 : mag < 1.5 ? 10 : 6;
                case STAR:
                    return Math.max(4, Math.min(14, 4 + 2.2 * (FAINTEST - mag)));
                case DEEP:
                    return Double.isNaN(mag) || mag > 3 ? 6 : 9;
                default:
                    return 6;
            }
        }

        /** Can be seen with eyes (or the phone): a star, a planet, the Moon, a bright galaxy or cluster. */
        public boolean seen() {
            return !Double.isNaN(mag) && mag < 6.5;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // What's out there

    /** Beyond the stars: name, black hole or not, RA and Dec (J2000 degrees), magnitude (NaN: not to be seen), what it is. */
    static final Object[][] DEEP = {
            {"Andromeda Galaxy", false, 10.6847, 41.2690, 3.4, "a whole galaxy of a trillion stars, 2.5 million light years away"},
            {"Pleiades", false, 56.7500, 24.1167, 1.6, "the Seven Sisters: a family of young stars, 444 light years away"},
            {"Orion Nebula", false, 83.8221, -5.3911, 4.0, "a cloud of gas where new stars are being born"},
            {"Crab Nebula", false, 83.6331, 22.0145, 8.4, "all that's left of a star that blew up in 1054, with a pulsar spinning 30 times a second"},
            {"Hercules Cluster", false, 250.4235, 36.4613, 5.8, "300,000 stars in a ball; in 1974 we sent it a radio message"},
            {"Omega Centauri", false, 201.6970, -47.4795, 3.9, "the biggest ball of stars round our galaxy: 10 million of them"},
            {"Large Magellanic Cloud", false, 80.8942, -69.7561, 0.9, "a small galaxy circling ours, 160,000 light years away"},
            {"Small Magellanic Cloud", false, 13.1867, -72.8286, 2.7, "another little galaxy circling ours, 200,000 light years away"},
            {"Sagittarius A*", true, 266.4168, -29.0078, Double.NaN, "the black hole in the middle of our galaxy: 4 million times the Sun's mass"},
            {"Cygnus X-1", true, 299.5903, 35.2016, Double.NaN, "a black hole pulling a giant star apart, the first black hole ever found"},
            {"M87's black hole", true, 187.7059, 12.3911, Double.NaN, "the first black hole ever photographed, 55 million light years away"},
    };

    /** A few stars with more to say than their constellation. */
    static final Map<String, String> FACTS = new HashMap<String, String>();

    static {
        FACTS.put("Sirius", "the brightest star in the whole night sky, 8.6 light years away");
        FACTS.put("Polaris", "the North Star: the whole sky turns around it");
        FACTS.put("Betelgeuse", "a red supergiant that will explode one day");
        FACTS.put("Rigil Kentaurus", "the nearest star you can see: 4.4 light years away");
        FACTS.put("Vega", "it'll be the North Star in 12,000 years");
        FACTS.put("Antares", "the red heart of the Scorpion, 700 times wider than the Sun");
        FACTS.put("Aldebaran", "the red eye of Taurus, the Bull");
        FACTS.put("Deneb", "one of the brightest stars in the galaxy, 2,600 light years away");
        FACTS.put("Altair", "a star spinning so fast it's squashed flat");
        FACTS.put("Arcturus", "an orange giant, 37 light years away");
        FACTS.put("Algol", "the Demon Star: it dims every 3 days when its partner passes in front");
        FACTS.put("Mizar", "the middle of the Big Dipper's handle: look close and it's two stars");
    }

    static final String[] PLANET_ABOUT = {
            "the smallest planet, nearest the Sun",
            "the brightest planet, under clouds hot enough to melt lead",
            "the red planet, with the tallest volcano in the Solar System",
            "the biggest planet: 1,300 Earths would fit inside",
            "the planet with the rings",
            "the planet that rolls round the Sun on its side",
            "the farthest planet, with the fastest winds",
    };

    static final Map<String, String> PROBE_ABOUT = new HashMap<String, String>();

    static {
        PROBE_ABOUT.put("Voyager 1", "the farthest thing people have ever made: a light-day away");
        PROBE_ABOUT.put("Voyager 2", "the only visitor to Uranus and Neptune, now between the stars");
        PROBE_ABOUT.put("New Horizons", "it flew past Pluto in 2015");
        PROBE_ABOUT.put("Pioneer 10", "the first spacecraft to Jupiter, silent since 2003");
        PROBE_ABOUT.put("Pioneer 11", "the first to Saturn, drifting toward Aquila");
    }

    /** The stars (to {@link #FAINTEST}) and the things beyond them: fixed on the sky, so made once. */
    public static final class Catalogue {
        final List<Thing> fixed = new ArrayList<Thing>();

        public Catalogue(BrightStars cat) {
            // each constellation's brightest star, to say so
            Map<String, Integer> top = new HashMap<String, Integer>();
            if (cat != null) {
                for (int i = 0; i < cat.n; i++) {
                    String c = cat.con[i];
                    if (c == null || c.isEmpty()) continue;
                    Integer was = top.get(c);
                    if (was == null || cat.vmag[i] < cat.vmag[was]) top.put(c, i);
                }
                for (int i = 0; i < cat.n; i++) {
                    if (!(cat.vmag[i] <= FAINTEST)) continue;
                    String label = cat.label(i);
                    if (label.isEmpty()) continue;
                    Thing t = new Thing(label, Kind.STAR);
                    t.dir = cat.vec(i);
                    t.mag = cat.vmag[i];
                    String con = cat.con[i] != null && !cat.con[i].isEmpty() ? cat.con[i] : Constellations.at(t.dir);
                    String fact = FACTS.get(cat.name[i]);
                    Integer best = top.get(con);
                    String in = (best != null && best == i ? "the brightest star in " : "a star in ") + BrightStars.constellation(con);
                    float ly = cat.ly[i];
                    t.about = fact != null ? fact : !Float.isNaN(ly) && ly > 0
                            ? in + ", " + lightYears(ly) + " away" : in;
                    fixed.add(t);
                }
            }
            for (Object[] d : DEEP) {
                Thing t = new Thing((String) d[0], (Boolean) d[1] ? Kind.BLACK_HOLE : Kind.DEEP);
                t.dir = Astro.vec((Double) d[2] * Astro.DEG, (Double) d[3] * Astro.DEG);
                t.mag = (Double) d[4];
                t.about = (String) d[5];
                fixed.add(t);
            }
        }

        /** How many stars it names. */
        public int stars() {
            int n = 0;
            for (Thing t : fixed) if (t.kind == Kind.STAR) n++;
            return n;
        }
    }

    static String lightYears(float ly) {
        if (ly < 20) return String.format(Locale.US, "%.1f light years", ly);
        if (ly < 1000) return String.format(Locale.US, "%d light years", Math.round(ly));
        return String.format(Locale.US, "%,d light years", Math.round(ly / 100) * 100);
    }

    /** Everything placed in the sky at one moment from one place. */
    public static final class Sky {
        public final long unixMs;
        public final double latDeg, lonDeg;
        public final List<Thing> things = new ArrayList<Thing>();
        /** J2000 to east-north-up there and then. */
        final double[] toEnu;

        Sky(long unixMs, double latDeg, double lonDeg, double[] toEnu) {
            this.unixMs = unixMs;
            this.latDeg = latDeg;
            this.lonDeg = lonDeg;
            this.toEnu = toEnu;
        }

        public Thing find(String name) {
            for (Thing t : things) if (t.name.equals(name)) return t;
            return null;
        }

        /** A J2000 direction as east, north, up here and now. */
        public double[] enu(double[] j2000) {
            double[] e = new double[3];
            Rot.apply(toEnu, j2000[0], j2000[1], j2000[2], e);
            return e;
        }

        /** An east-north-up direction as J2000. */
        public double[] j2000(double[] enu) {
            double[] j = new double[3];
            Rot.apply(Rot.transpose(toEnu), enu[0], enu[1], enu[2], j);
            return j;
        }
    }

    /**
     * The sky at {@code unixMs} from latitude and longitude (degrees): the catalogue's stars and things beyond, the Sun,
     * the Moon, the planets, Pluto, the five spacecraft leaving the Solar System and the James Webb telescope's L2.
     */
    public static Sky sky(Catalogue c, long unixMs, double latDeg, double lonDeg) {
        double jd = Astro.jdUtc(unixMs), tt = Astro.jdTt(jd), lat = latDeg * Astro.DEG, lon = lonDeg * Astro.DEG;
        Sky s = new Sky(unixMs, latDeg, lonDeg, Astro.j2000ToEnu(jd, lat, lon));
        Thing moon = new Thing("the Moon", Kind.MOON);
        moon.enu = Astro.moonEnu(unixMs, latDeg * Astro.DEG, lonDeg * Astro.DEG);
        moon.mag = -10;
        moon.about = String.format(Locale.US, "our Moon, %d%% lit, 384,000 km away", Math.round(100 * Astro.moonIllumination(tt)));
        s.things.add(moon);
        Thing sun = new Thing("the Sun", Kind.SUN);
        sun.enu = Astro.sunEnu(unixMs, latDeg * Astro.DEG, lonDeg * Astro.DEG);
        sun.mag = -26.7;
        sun.about = "our star (never look straight at it!)";
        s.things.add(sun);
        for (int k = 0; k < Planets.NAMES.length; k++) {
            Planets.Seen p = Planets.seen(k, tt);
            Thing t = new Thing(p.name, Kind.PLANET);
            t.dir = p.dir;
            t.mag = p.mag;
            t.about = PLANET_ABOUT[k];
            s.things.add(t);
        }
        for (Probes.Seen p : Probes.all(tt)) {
            Thing t;
            if ("l2".equals(p.kind)) {
                t = new Thing("James Webb (L2)", Kind.POINT);
                t.about = "where the James Webb Space Telescope loops round, 1.5 million km away";
            } else if ("pluto".equals(p.kind)) {
                t = new Thing("Pluto", Kind.DWARF);
                t.mag = p.mag;
                t.about = "the dwarf planet New Horizons flew past in 2015";
            } else {
                t = new Thing(p.name, Kind.PROBE);
                String a = PROBE_ABOUT.get(p.name);
                t.about = a != null ? a : String.format(Locale.US, "%s km away", Probes.bigNumber(p.distAu * Probes.AU_KM));
            }
            t.dir = p.dir;
            s.things.add(t);
        }
        if (c != null) s.things.addAll(copies(c.fixed));
        for (Thing t : s.things) {
            if (t.enu == null) t.enu = s.enu(t.dir);
            t.altDeg = Astro.altitude(t.enu) / Astro.DEG;
            t.azDeg = Astro.azimuth(t.enu) / Astro.DEG;
        }
        return s;
    }

    /** The catalogue's things as new ones (each sky places its own). */
    private static List<Thing> copies(List<Thing> fixed) {
        List<Thing> out = new ArrayList<Thing>(fixed.size());
        for (Thing f : fixed) {
            Thing t = new Thing(f.name, f.kind);
            t.about = f.about;
            t.mag = f.mag;
            t.dir = f.dir;
            out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // The arm

    /**
     * Where the arm points (east, north, up, true north) from the watch's rotation matrix {@code R} (3 x 3, rows first:
     * the watch's axes into east, magnetic north and up, as Android's {@code SensorManager.getRotationMatrixFromVector}
     * gives it), the magnetic declination there (degrees east of true north), and which way the crown faces: toward the
     * hand (the watch's x axis is the arm) or toward the elbow (then it's the other way).
     */
    public static double[] arm(float[] R, boolean crownToElbow, double declinationDeg) {
        double s = crownToElbow ? -1 : 1;
        // the watch's x axis in the world: R's first column
        double e = s * R[0], n = s * R[3], u = s * R[6];
        double d = declinationDeg * Astro.DEG, c = Math.cos(d), sn = Math.sin(d);
        // magnetic north is d east of true north: turn the level part by d
        double te = e * c + n * sn, tn = -e * sn + n * c;
        double len = Math.sqrt(te * te + tn * tn + u * u);
        if (!(len > 0)) return new double[]{0, 1, 0};
        return new double[]{te / len, tn / len, u / len};
    }

    public static double altDeg(double[] enu) {
        return Astro.altitude(enu) / Astro.DEG;
    }

    public static double azDeg(double[] enu) {
        return Astro.azimuth(enu) / Astro.DEG;
    }

    /** "62° up in the east", "low in the south-west", "below the horizon, north-west". */
    public static String where(double altDeg, double azDeg) {
        String way = Probes.compass(azDeg);
        if (altDeg >= 85) return "straight overhead";
        if (altDeg >= 4) return String.format(Locale.US, "%d° up in the %s", Math.round(altDeg), way);
        if (altDeg >= -2) return "on the horizon in the " + way;
        if (altDeg <= -85) return "straight under your feet";
        return String.format(Locale.US, "%d° below the horizon, %s", Math.round(-altDeg), way);
    }

    // ------------------------------------------------------------------------------------------------
    // What's there

    /** What the arm points at. */
    public static final class Look {
        /** The thing pointed at (null: nothing near enough), and one of the wonders out of sight near it (null: none). */
        public Thing best, alsoNear;
        /** Where the arm points. */
        public double altDeg, azDeg;
        /** How far off the thing named (degrees). */
        public double offDeg = Double.NaN;
    }

    /**
     * What the arm ({@code arm}, east-north-up) points at: of everything within its reach, the nearest for its reach,
     * things to be seen ahead of the unseen (a star over a black hole beside it); and the nearest wonder out of sight
     * (a spacecraft, a black hole, a far galaxy) within 12 degrees, to mention.
     */
    public static Look look(Sky s, double[] arm) {
        Look l = new Look();
        l.altDeg = altDeg(arm);
        l.azDeg = azDeg(arm);
        double bestScore = 1, nearWonder = 12;
        for (Thing t : s.things) {
            double off = Astro.sep(arm, t.enu) / Astro.DEG;
            double score = off / t.reach() * (t.seen() ? 1 : 1.3);
            // the Sun only when it's what you're pointing at, not the sky round it
            if (t.kind == Kind.SUN && off > 8) continue;
            if (score < bestScore) {
                bestScore = score;
                l.best = t;
                l.offDeg = off;
            }
        }
        for (Thing t : s.things) {
            if (t == l.best || !(t.kind == Kind.PROBE || t.kind == Kind.BLACK_HOLE || t.kind == Kind.POINT
                    || (t.kind == Kind.DEEP && !t.seen()))) continue;
            double off = Astro.sep(arm, t.enu) / Astro.DEG;
            if (off < nearWonder) {
                nearWonder = off;
                l.alsoNear = t;
            }
        }
        return l;
    }

    // ------------------------------------------------------------------------------------------------
    // Finding

    /**
     * What the crown goes through when finding: the Moon; the planets (those up first); the spacecraft and Webb's L2; the
     * black holes; the galaxies, clusters and nebulae (those up first); the North Star and the 15 brightest stars that
     * are up; then Pluto and the Sun.
     */
    public static List<Thing> targets(Sky s) {
        List<Thing> out = new ArrayList<Thing>();
        add(out, s, Kind.MOON, false, 1);
        add(out, s, Kind.PLANET, true, 99);
        add(out, s, Kind.PROBE, false, 99);
        add(out, s, Kind.POINT, false, 99);
        add(out, s, Kind.BLACK_HOLE, false, 99);
        add(out, s, Kind.DEEP, true, 99);
        Thing polaris = s.find("Polaris");
        if (polaris != null) out.add(polaris);
        List<Thing> stars = new ArrayList<Thing>();
        for (Thing t : s.things) if (t.kind == Kind.STAR && t.up() && t != polaris && t.mag <= 2.6) stars.add(t);
        Collections.sort(stars, new Comparator<Thing>() {
            @Override
            public int compare(Thing a, Thing b) {
                return Double.compare(a.mag, b.mag);
            }
        });
        out.addAll(stars.subList(0, Math.min(15, stars.size())));
        add(out, s, Kind.DWARF, false, 99);
        add(out, s, Kind.SUN, false, 1);
        return out;
    }

    private static void add(List<Thing> out, Sky s, Kind k, boolean upFirst, int most) {
        List<Thing> l = new ArrayList<Thing>();
        for (Thing t : s.things) if (t.kind == k) l.add(t);
        if (upFirst) {
            // stable: those up first, each group in the table's order
            List<Thing> up = new ArrayList<Thing>(), down = new ArrayList<Thing>();
            for (Thing t : l) (t.up() ? up : down).add(t);
            l = up;
            l.addAll(down);
        }
        out.addAll(l.subList(0, Math.min(most, l.size())));
    }

    /** How to move the arm to a thing. */
    public static final class Guide {
        /** How far off (degrees), how much higher (negative: lower) and how far right (negative: left) it is. */
        public double offDeg, upDeg, rightDeg;
        /** Which way to move, as a clock hand on the sky: 0 higher, 90 right, 180 lower, 270 left (degrees). */
        public double arrowDeg;
        /** Pointing at it; it's behind you; it's below the horizon. */
        public boolean got, behind, below;
        /** 0 (as far off as it can be) to 1 (on it). */
        public float closeness;
        /** The ticks' spacing (ms) while closing in; 0: too far off to tick. */
        public long tickMs;
        /** "Higher and to the left", "Turn around", "Got it!". */
        public String words = "";
    }

    /** From where the arm points ({@code arm}, east-north-up) to {@code t}. */
    public static Guide guide(double[] arm, Thing t) {
        Guide g = new Guide();
        g.offDeg = Astro.sep(arm, t.enu) / Astro.DEG;
        double a0 = altDeg(arm), a1 = t.altDeg;
        double dAz = ((t.azDeg - azDeg(arm)) % 360 + 540) % 360 - 180;
        g.upDeg = a1 - a0;
        // across the sky, an azimuth's degree is smaller the higher up (nothing at all at the zenith)
        g.rightDeg = dAz * Math.cos(0.5 * (a0 + a1) * Astro.DEG);
        g.arrowDeg = (Math.toDegrees(Math.atan2(g.rightDeg, g.upDeg)) + 360) % 360;
        g.got = g.offDeg <= GOT_DEG;
        g.below = a1 < 0;
        g.behind = !g.got && Math.abs(dAz) > 120 && a0 < 70 && a1 < 70;
        g.closeness = (float) Math.max(0, Math.min(1, 1 - g.offDeg / 180));
        if (g.got) {
            g.tickMs = BUZZ_FAST_MS;
        } else if (g.offDeg < BUZZ_FROM_DEG) {
            double f = (g.offDeg - GOT_DEG) / (BUZZ_FROM_DEG - GOT_DEG);
            g.tickMs = Math.round(BUZZ_FAST_MS + f * (BUZZ_SLOW_MS - BUZZ_FAST_MS));
        }
        if (g.got) {
            g.words = "Got it!";
        } else if (g.behind) {
            g.words = "Turn around: it's behind you";
        } else {
            String v = g.upDeg > 5 ? "Higher" : g.upDeg < -5 ? "Lower" : "";
            String h = g.rightDeg > 5 ? "right" : g.rightDeg < -5 ? "left" : "";
            if (!v.isEmpty() && !h.isEmpty()) g.words = v + " and to the " + h;
            else if (!v.isEmpty()) g.words = v;
            else if (!h.isEmpty()) g.words = "To the " + h;
            else g.words = "Nearly there";
        }
        return g;
    }
}
