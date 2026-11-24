package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * X-Ray Vision (2.5): the invisible universe in the photo. Black holes, neutron stars, the clouds of stars that exploded,
 * stars with planets, the famous patches of sky NASA's telescopes stared at, and the messages sent to and maybe from the
 * stars: none of them shows as more than a faint star (most not even that), but each is somewhere in the sky, and when
 * the photo is placed on the sky they are marked where they are, with what they are in a sentence or two.
 * <p>
 * The picture is the photo turned into an X-ray film's colours, dark blues to cyan, with a symbol for each: a black
 * hole's orange ring round its shadow (as the Event Horizon Telescope saw them), a neutron star's two beams, an exploded
 * star's shells, a planet's orbit round its star, a field's outline, a message's radio rings.
 * <p>
 * Positions are J2000 (ICRS), from OpenNGC for the nebulae and galaxies, the app's star catalogue for the bright planet
 * hosts, and the objects' published positions for the rest (Wikipedia's infoboxes, the NASA Exoplanet Archive, Gaia's
 * papers); the Wow! signal's from the 2025 re-analysis of the Ohio SETI data (its positive horn). Proper motion is left
 * out: the fastest, Barnard's Star, has moved 4.5 arc minutes since 2000, under a tenth of a degree.
 */
public final class XRayVision {
    private XRayVision() {}

    /** What a thing is, in words for one and for several. */
    public enum Kind {
        BLACK_HOLE("black hole", "black holes"), GIANT("giant black hole", "giant black holes"),
        NEUTRON_STAR("neutron star", "neutron stars"), REMNANT("exploded star", "exploded stars"), CRASH("star crash", "star crashes"),
        PLANETS("star with planets", "stars with planets"), FIELD("famous patch of sky", "famous patches of sky"),
        MESSAGE("message across space", "messages across space");

        public final String one, many;

        Kind(String one, String many) {
            this.one = one;
            this.many = many;
        }
    }

    /** One of them: where it is (J2000), how big (degrees; 0: a point), how far, and what to say about it. */
    public static final class Thing {
        public final String name;
        public final Kind kind;
        public final double raDeg, decDeg;
        /** An exploded star's radius, or a field's half side (degrees); 0 for a point. */
        public final double sizeDeg;
        /** Light years (NaN: not a distance that means anything, as for a field or a signal). */
        public final double lightYears;
        /** The short line beside its name on the picture, and its story. */
        public final String line, story;
        /** How many times a second it spins (a pulsar's beat, to hear it); 0 for none. */
        public final double spinsPerSecond;
        public final double[] dir;

        Thing(String name, Kind kind, double raDeg, double decDeg, double sizeDeg, double lightYears, double spins, String line,
              String story) {
            this.name = name;
            this.kind = kind;
            this.raDeg = raDeg;
            this.decDeg = decDeg;
            this.sizeDeg = sizeDeg;
            this.lightYears = lightYears;
            this.spinsPerSecond = spins;
            this.line = line;
            this.story = story;
            dir = Astro.vec(raDeg * Astro.DEG, decDeg * Astro.DEG);
        }
    }

    /** Right ascension of h m s, in degrees. */
    static double ra(double h, double m, double s) {
        return 15 * (h + m / 60 + s / 3600);
    }

    /** Declination of (sign) d m s, in degrees; {@code south} for the southern sky (so -0 d works). */
    static double dec(boolean south, double d, double m, double s) {
        double v = d + m / 60 + s / 3600;
        return south ? -v : v;
    }

    private static final boolean N = false, S = true;

    /** The catalogue: the most striking first within each kind. */
    static final Thing[] ALL = {
            // ---- black holes made by stars
            new Thing("Cygnus X-1", Kind.BLACK_HOLE, ra(19, 58, 21.68), dec(N, 35, 12, 5.8), 0, 7200, 0,
                    "black hole · 21 times the Sun's mass",
                    "The first black hole ever found, in 1971. It is eating gas from the blue giant star it circles every 5.6 days, "
                            + "and that star is the faint one you can see right here in your photo. Stephen Hawking bet it wasn't a black "
                            + "hole, and lost. NASA's Chandra X-ray telescope watches the gas glow as it falls in."),
            new Thing("Gaia BH1", Kind.BLACK_HOLE, ra(17, 28, 41.10), dec(S, 0, 34, 51.5), 0, 1560, 0,
                    "the nearest black hole known · 9 Suns",
                    "The closest black hole we know of, found in 2022 by the Gaia space telescope from the way a star like the Sun "
                            + "swings around it. It isn't eating anything, so it gives off no light at all: completely invisible."),
            new Thing("Gaia BH3", Kind.BLACK_HOLE, ra(19, 39, 18.71), dec(N, 14, 55, 54.0), 0, 1930, 0,
                    "black hole · 33 times the Sun's mass",
                    "The heaviest black hole made from a star ever found in our galaxy (2024). Gaia spotted an old star circling "
                            + "something huge that it couldn't see."),
            new Thing("V404 Cygni", Kind.BLACK_HOLE, ra(20, 24, 3.83), dec(N, 33, 52, 2.2), 0, 7800, 0,
                    "black hole · 9 times the Sun's mass",
                    "In June 2015 it suddenly woke up and became one of the brightest X-ray lights in the sky for two weeks, while "
                            + "NASA's Swift satellite watched. In 2024 astronomers found it has a second star far out: a black hole "
                            + "with two suns."),
            new Thing("A0620-00", Kind.BLACK_HOLE, ra(6, 22, 44.5), dec(S, 0, 20, 45), 0, 3500, 0,
                    "black hole · 6.6 times the Sun's mass",
                    "One of the closest black holes. In 1975 it flared up into the brightest X-ray source in the sky. A small "
                            + "orange star whirls round it every 7 hours 45 minutes."),
            new Thing("GRS 1915+105", Kind.BLACK_HOLE, ra(19, 15, 11.55), dec(N, 10, 56, 44.8), 0, 28000, 0,
                    "black hole · 12 times the Sun's mass",
                    "It fires out jets so fast that they seem to move faster than light (a trick of their speed: nothing really "
                            + "does). It spins almost as fast as a black hole can."),
            // ---- giant black holes, in the middles of galaxies
            new Thing("Sagittarius A*", Kind.GIANT, ra(17, 45, 40.04), dec(S, 29, 0, 28.2), 0, 26000, 0,
                    "the black hole at the middle of our galaxy · 4 million Suns",
                    "The whole Milky Way turns around it. Its picture was taken in 2022 by the Event Horizon Telescope, radio "
                            + "dishes all over the Earth working as one. Stars race round it at thousands of kilometres a second."),
            new Thing("M87*", Kind.GIANT, ra(12, 30, 49.42), dec(N, 12, 23, 28.0), 0, 55e6, 0,
                    "giant black hole · 6.5 billion Suns",
                    "The first black hole ever photographed (2019): a glowing orange ring round a shadow wider than our whole "
                            + "Solar System. It sits in the middle of the galaxy M87 and fires a jet 5,000 light years long."),
            new Thing("Andromeda's black hole", Kind.GIANT, ra(0, 42, 44.33), dec(N, 41, 16, 7.5), 0, 2.5e6, 0,
                    "giant black hole · 100 million Suns",
                    "In the very middle of the Andromeda Galaxy, the farthest thing you can see with just your eyes, sits a black "
                            + "hole at least 25 times heavier than the one at the middle of our own galaxy."),
            new Thing("3C 273", Kind.GIANT, ra(12, 29, 6.70), dec(N, 2, 3, 8.6), 0, 2.4e9, 0,
                    "quasar · a black hole of nearly a billion Suns",
                    "The first quasar ever found (1963): a giant black hole eating so fast that the gas swirling into it outshines "
                            + "its whole galaxy. It is the brightest quasar in the sky; a backyard telescope can see it."),
            new Thing("TON 618", Kind.GIANT, ra(12, 28, 24.9), dec(N, 31, 28, 38), 0, 10.8e9, 0,
                    "one of the biggest black holes known · 40 billion Suns",
                    "A quasar so far away that its light set off before the Earth was made. Its black hole is so big that its "
                            + "edge, the event horizon, is about 25 times wider than Neptune's whole orbit."),
            // ---- neutron stars: what's left of big stars that exploded, a city's width and heavier than the Sun
            new Thing("Crab Pulsar", Kind.NEUTRON_STAR, ra(5, 34, 31.95), dec(N, 22, 0, 52.2), 0, 6500, 29.6,
                    "neutron star · spins 30 times a second",
                    "What's left of a star whose explosion Chinese astronomers saw in the year 1054: it shone in daylight for 23 "
                            + "days. A ball the size of a city and heavier than the Sun, spinning 30 times a second inside the glowing "
                            + "Crab Nebula."),
            new Thing("PSR B1919+21", Kind.NEUTRON_STAR, ra(19, 21, 44.82), dec(N, 21, 53, 2.3), 0, 1000, 1 / 1.3373,
                    "the first pulsar ever found · ticks every 1.34 s",
                    "Found by Jocelyn Bell Burnell in 1967. Its radio ticks were so regular that it was nicknamed LGM-1, for "
                            + "Little Green Men, until it turned out to be a spinning dead star."),
            new Thing("Hulse–Taylor pulsar", Kind.NEUTRON_STAR, ra(19, 15, 27.99), dec(N, 16, 6, 27.4), 0, 21000, 16.94,
                    "two dead stars in a spiral · spins 17 times a second",
                    "Two neutron stars circling each other every 7 hours 45 minutes, slowly spiralling closer as they shake "
                            + "ripples out of space itself, gravitational waves, just as Einstein predicted. A Nobel Prize in 1993."),
            new Thing("Geminga", Kind.NEUTRON_STAR, ra(6, 33, 54.15), dec(N, 17, 46, 12.9), 0, 800, 4.22,
                    "neutron star · spins 4 times a second",
                    "Its name means \"it's not there\" in the dialect of Milan: for years it shone in gamma rays but nobody could "
                            + "find it in ordinary light. One of the closest neutron stars to us."),
            new Thing("Vela Pulsar", Kind.NEUTRON_STAR, ra(8, 35, 20.66), dec(S, 45, 10, 35.2), 0, 960, 11.19,
                    "neutron star · spins 11 times a second",
                    "Left by a star that blew up about 11,000 years ago. To NASA's Fermi gamma-ray telescope it's one of the "
                            + "brightest things in the sky, and every few years it suddenly spins a little faster: a starquake."),
            new Thing("Scorpius X-1", Kind.NEUTRON_STAR, ra(16, 19, 55.07), dec(S, 15, 38, 24.8), 0, 9000, 0,
                    "neutron star · the first X-ray star",
                    "The first X-ray source found beyond the Solar System, by a rocket in 1962 (a Nobel Prize). After the Sun it "
                            + "is the brightest X-ray light in our sky: a neutron star tearing gas off the star beside it."),
            new Thing("SGR 1806-20", Kind.NEUTRON_STAR, ra(18, 8, 39.32), dec(S, 20, 24, 39.5), 0, 50000, 0,
                    "magnetar · the strongest magnet known",
                    "A neutron star with a magnetic field about a thousand trillion times the Earth's. On 27 December 2004 a "
                            + "starquake on it sent out the brightest flash ever seen from beyond our Solar System, all the way from "
                            + "the far side of the galaxy."),
            // ---- the clouds left by stars that exploded (supernovae)
            new Thing("Cassiopeia A", Kind.REMNANT, ra(23, 23, 24), dec(N, 58, 48, 54), 0.04, 11000, 0,
                    "exploded star · its light reached us around 1680",
                    "One of the youngest exploded-star clouds in our galaxy. NASA's Chandra X-ray telescope found the neutron "
                            + "star left at its centre in its very first pictures (1999), and the Webb telescope photographed its "
                            + "glowing shell in 2023."),
            new Thing("Tycho's Supernova", Kind.REMNANT, ra(0, 25, 17), dec(N, 64, 8, 37), 0.07, 9000, 0,
                    "exploded star · seen in 1572",
                    "In November 1572 a new star appeared here, brighter than Venus. The astronomer Tycho Brahe measured it and "
                            + "proved that the sky beyond the Moon can change. Its shell is still growing today."),
            new Thing("Kepler's Supernova", Kind.REMNANT, ra(17, 30, 40.8), dec(S, 21, 29, 11), 0.03, 20000, 0,
                    "exploded star · seen in 1604",
                    "The last exploding star seen in our galaxy with just the eyes, in October 1604. Johannes Kepler watched it "
                            + "for a whole year."),
            new Thing("Veil Nebula", Kind.REMNANT, ra(20, 51, 0), dec(N, 30, 40, 0), 1.5, 2400, 0,
                    "exploded star · 6 full Moons wide",
                    "The leftovers of a star that blew up 10,000 to 20,000 years ago, still spreading out: six full Moons would "
                            + "fit across it. Hubble photographed its glowing threads in 2015."),
            new Thing("SN 1006", Kind.REMNANT, ra(15, 2, 55), dec(S, 41, 56, 0), 0.25, 7200, 0,
                    "exploded star · seen in 1006",
                    "The brightest exploding star in recorded history: in the spring of 1006 you could see by its light at "
                            + "night, and people in China, Japan, Egypt, Iraq and Switzerland wrote it down."),
            // ---- a crash of neutron stars, felt as gravitational waves
            new Thing("GW170817", Kind.CRASH, ra(13, 9, 48.09), dec(S, 23, 22, 53.3), 0, 130e6, 0,
                    "two neutron stars crashed here",
                    "On 17 August 2017 detectors on the Earth felt the ripples in space from two dead stars crashing together, "
                            + "and then about 70 telescopes, NASA's among them, saw the blast. The crash made gold and platinum "
                            + "weighing several times as much as the whole Earth."),
            // ---- stars with planets (the brightest from the app's own star catalogue)
            new Thing("51 Pegasi", Kind.PLANETS, 344.36625, 20.76889, 0, 51, 0,
                    "the first planet around a star like the Sun",
                    "In 1995 a planet was found circling this Sun-like star, the first ever (a Nobel Prize). It's a giant half "
                            + "as heavy as Jupiter, so close to its star that a year there lasts only 4 days."),
            new Thing("Upsilon Andromedae", Kind.PLANETS, 24.19917, 41.40556, 0, 44, 0,
                    "star with 3 planets",
                    "The first star like the Sun found with a whole family of planets (1999): three giants, a solar system "
                            + "of Jupiters."),
            new Thing("55 Cancri", Kind.PLANETS, 133.14917, 28.33083, 0, 41, 0,
                    "star with 5 planets",
                    "Five planets circle it. One, 55 Cancri e, is a scorching lava world where a year lasts 18 hours, and in "
                            + "2024 the Webb telescope found it has a thick atmosphere."),
            new Thing("Epsilon Eridani", Kind.PLANETS, 53.23250, -9.45833, 0, 10.5, 0,
                    "star with a planet · 10.5 light years away",
                    "The closest star like the Sun known to have a planet, with dusty belts like our asteroid belt round it: a "
                            + "young solar system."),
            new Thing("Pollux", Kind.PLANETS, 116.32875, 28.02611, 0, 34, 0,
                    "the brightest star with a planet",
                    "The brightest star known to have a planet: Thestias, a giant at least twice as heavy as Jupiter, found in "
                            + "2006."),
            new Thing("Errai", Kind.PLANETS, 354.83667, 77.63250, 0, 45, 0,
                    "star with a planet · a future North Star",
                    "One of the first planets ever found round another star: there were hints of it in 1988, proved in 2003. "
                            + "And as the Earth's axis slowly wobbles, in about a thousand years Errai will be our North Star."),
            new Thing("Tau Boötis", Kind.PLANETS, 206.81542, 17.45667, 0, 51, 0,
                    "star with a giant planet",
                    "A planet six times as heavy as Jupiter races round it every 3.3 days, so close that the star's surface "
                            + "turns in step with it."),
            new Thing("47 Ursae Majoris", Kind.PLANETS, 164.86667, 40.43028, 0, 46, 0,
                    "star with 3 planets",
                    "Its first planet, Taphao Thong, was one of the very first found (1996): a giant a bit like Jupiter, circling "
                            + "its star twice as far out as the Earth circles the Sun."),
            new Thing("70 Virginis", Kind.PLANETS, 202.10750, 13.77889, 0, 59, 0,
                    "star with a giant planet",
                    "One of the first planets found, in 1996: a giant seven times as heavy as Jupiter whose year lasts 116 days."),
            new Thing("HD 189733", Kind.PLANETS, ra(20, 0, 43.71), dec(N, 22, 42, 39.1), 0, 64, 0,
                    "star with a blue planet where it rains glass",
                    "Its planet is deep blue like the Earth, but no place to visit: Hubble found it's over 1,000 °C, and it "
                            + "rains glass there, sideways, in winds of thousands of kilometres an hour."),
            new Thing("HD 209458", Kind.PLANETS, ra(22, 3, 10.77), dec(N, 18, 53, 3.5), 0, 157, 0,
                    "star with a planet that has a tail",
                    "The first planet seen passing in front of its star (1999), and the first whose air was found (by Hubble, "
                            + "2001). It's so close to its star that its air is boiling away into a tail, like a comet's."),
            new Thing("TRAPPIST-1", Kind.PLANETS, ra(23, 6, 29.28), dec(S, 5, 2, 28.6), 0, 41, 0,
                    "star with 7 Earth-sized planets",
                    "A tiny red star, far too faint for your photo, with seven rocky planets the size of the Earth, three of "
                            + "them where water could be liquid. NASA's Spitzer found them in 2017 and the Webb telescope studies them now."),
            new Thing("Kepler-186", Kind.PLANETS, ra(19, 54, 36.66), dec(N, 43, 57, 18.0), 0, 580, 0,
                    "star with 5 planets",
                    "Its planet Kepler-186f (2014) was the first planet the size of the Earth found at the right distance from "
                            + "its star for liquid water."),
            new Thing("Kepler-452", Kind.PLANETS, ra(19, 44, 0.89), dec(N, 44, 16, 39.2), 0, 1400, 0,
                    "star with Earth's older cousin",
                    "Its planet Kepler-452b, found in 2015, was called Earth's older cousin: a bit bigger than the Earth, with a "
                            + "year of 385 days round a star much like the Sun."),
            new Thing("Kepler-16", Kind.PLANETS, ra(19, 16, 18.18), dec(N, 51, 45, 26.8), 0, 245, 0,
                    "a planet with two suns",
                    "Its planet Kepler-16b circles two stars at once, so it has two sunsets, just like Luke Skywalker's home "
                            + "planet Tatooine."),
            new Thing("Barnard's Star", Kind.PLANETS, ra(17, 57, 48.50), dec(N, 4, 41, 36.2), 0, 6, 0,
                    "star with 4 small planets · 6 light years away",
                    "The fastest-moving star in our sky, and one of the closest. In 2024 and 2025 astronomers found four small "
                            + "rocky planets circling it."),
            // ---- famous patches of sky
            new Thing("Kepler's field", Kind.FIELD, ra(19, 22, 40), dec(N, 44, 30, 0), 5.12, Double.NaN, 0,
                    "NASA's planet hunt, 2009 to 2013",
                    "For four years NASA's Kepler telescope stared at this patch of sky, watching 150,000 stars for the tiny "
                            + "dips in light when planets pass in front of them. The Kepler mission found more than 2,600 planets."),
            new Thing("Hubble Deep Field", Kind.FIELD, ra(12, 36, 49.4), dec(N, 62, 12, 58), 0, Double.NaN, 0,
                    "Hubble's first deep look, 1995",
                    "In 1995 Hubble stared for 10 days at this tiny, empty-looking spot, smaller than a grain of sand held at "
                            + "arm's length. It found about 3,000 galaxies, each with billions of stars."),
            new Thing("Hubble Ultra Deep Field", Kind.FIELD, ra(3, 32, 39.0), dec(S, 27, 47, 29), 0, Double.NaN, 0,
                    "Hubble's deepest picture",
                    "About 10,000 galaxies in a speck of sky a tenth as wide as the full Moon. Some of their light set off 13 "
                            + "billion years ago, when the universe was young."),
            new Thing("Pillars of Creation", Kind.FIELD, ra(18, 18, 48.17), dec(S, 13, 48, 26.0), 0, 6500, 0,
                    "where new stars are born",
                    "Towering clouds of gas and dust where new stars are forming, made famous by Hubble in 1995 and photographed "
                            + "again by Webb in 2022. The tallest is about 4 light years high."),
            // ---- messages across space
            new Thing("The Arecibo message", Kind.MESSAGE, ra(16, 41, 41.63), dec(N, 36, 27, 40.7), 0, 25000, 0,
                    "a message we sent, to the cluster M13",
                    "On 16 November 1974 the giant Arecibo radio telescope beamed a picture message at this ball of 300,000 "
                            + "stars: numbers, DNA, a little figure of a person and the telescope itself. It will get there in about "
                            + "25,000 years."),
            new Thing("The Wow! signal", Kind.MESSAGE, ra(19, 25, 2), dec(S, 26, 57, 0), 0, Double.NaN, 0,
                    "a message from space?",
                    "On 15 August 1977 Ohio's Big Ear radio telescope caught a 72-second signal from about here, so strong that "
                            + "the astronomer Jerry Ehman circled it on the printout and wrote \"Wow!\". It has never been heard "
                            + "again, and nobody knows for sure what it was."),
    };

    /** The catalogue, in its order. */
    public static List<Thing> all() {
        List<Thing> l = new ArrayList<Thing>();
        Collections.addAll(l, ALL);
        return l;
    }

    /** One of them for a photo: in it (where), up in the sky (how high, which way), or not. */
    public static final class Spot {
        public final Thing what;
        public boolean inPhoto, up;
        /** Where in the photo: shares of its crop across and down (NaN: not in it). */
        public double u = Double.NaN, v = Double.NaN;
        public double altDeg = Double.NaN, azDeg = Double.NaN;
        /** A field's corners, or a point on an exploded star's edge (north of it), in the same shares; null: none. */
        public double[][] outline;
        /** "Cygnus X-1: in your photo, at the top left", and its story with how far and how long its light took. */
        public String head = "", words = "";

        Spot(Thing what) {
            this.what = what;
        }
    }

    /**
     * The things of the catalogue for a photo placed on the sky by {@code sol} (null: none, so none is in it), its crop
     * (x0, y0, w, h) of the frame the solution maps, taken at {@code jdUtc} from {@code sky}'s place (null or not located:
     * only whether each is in the photo). Those in the photo first (in the catalogue's order), then those up in the sky;
     * the rest are left out. {@code zone} says the year the photo was taken in, for when the light set off.
     */
    public static List<Spot> find(SkySolver.Solution sol, double jdUtc, SessionSky sky, int x0, int y0, int w, int h, TimeZone zone) {
        List<Spot> in = new ArrayList<Spot>(), up = new ArrayList<Spot>();
        SkyGuide.Frame f = sol != null && sol.model != null && w > 0 && h > 0 ? new SkyGuide.Frame(sol, x0, y0, w, h) : null;
        double[] enu = sky != null && sky.located() && !Double.isNaN(jdUtc)
                ? Astro.j2000ToEnu(jdUtc, sky.latDeg * Astro.DEG, sky.lonDeg * Astro.DEG) : null;
        int year = yearOf(jdUtc, zone);
        for (Thing t : ALL) {
            Spot s = new Spot(t);
            double[] uv = f != null ? f.near(t.dir) : null;
            if (uv != null && SkyGuide.inside(uv)) {
                s.inPhoto = true;
                s.u = uv[0];
                s.v = uv[1];
                s.outline = outline(f, t);
            }
            if (enu != null) {
                double[] e = new double[3];
                Rot.apply(enu, t.dir[0], t.dir[1], t.dir[2], e);
                s.altDeg = Astro.altitude(e) / Astro.DEG;
                s.azDeg = Astro.azimuth(e) / Astro.DEG;
                s.up = s.altDeg > 0;
            }
            if (!s.inPhoto && !s.up) continue;
            s.head = t.name + ": " + (s.inPhoto ? "in your photo, " + SkyGuide.where(s.u, s.v)
                    : String.format(Locale.US, "up in your sky, %.0f° high in the %s", s.altDeg, Probes.compass(s.azDeg)));
            s.words = words(t, year);
            (s.inPhoto ? in : up).add(s);
        }
        in.addAll(up);
        return in;
    }

    /** A field's corners (its half side each way along right ascension and declination), or an exploded star's north edge. */
    private static double[][] outline(SkyGuide.Frame f, Thing t) {
        if (t.sizeDeg <= 0) return null;
        if (t.kind == Kind.FIELD) {
            double c = Math.max(0.05, Math.cos(t.decDeg * Astro.DEG));
            double[][] out = new double[4][];
            int[][] sgn = {{-1, 1}, {1, 1}, {1, -1}, {-1, -1}};
            for (int k = 0; k < 4; k++) {
                double[] d = Astro.vec((t.raDeg + sgn[k][0] * t.sizeDeg / c) * Astro.DEG, (t.decDeg + sgn[k][1] * t.sizeDeg) * Astro.DEG);
                double[] uv = f.near(d, 1.6);
                if (uv == null) return null;
                out[k] = uv;
            }
            return out;
        }
        double[] d = Astro.vec(t.raDeg * Astro.DEG, Math.min(89.9, t.decDeg + t.sizeDeg) * Astro.DEG);
        double[] uv = f.near(d, 1.6);
        return uv == null ? null : new double[][]{uv};
    }

    /** The year it is at {@code jdUtc} in {@code zone} (this year's if the time isn't known). */
    static int yearOf(double jdUtc, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone != null ? zone : TimeZone.getTimeZone("UTC"), Locale.US);
        if (!Double.isNaN(jdUtc)) c.setTimeInMillis(Math.round((jdUtc - 2440587.5) * 86400000.0));
        return c.get(Calendar.YEAR);
    }

    /** Its story, how far it is, and when the light in the photo set off from it. */
    public static String words(Thing t, int year) {
        StringBuilder b = new StringBuilder(t.story);
        if (!Double.isNaN(t.lightYears)) {
            b.append(' ').append(distance(t.lightYears)).append(" away. ").append(lightTime(t.lightYears, year));
        }
        return b.toString();
    }

    /** "7,200 light years", "2.5 million light years", "10.8 billion light years". */
    public static String distance(double ly) {
        if (ly >= 1e9) return trim(String.format(Locale.US, "%.1f", ly / 1e9)) + " billion light years";
        if (ly >= 1e6) return trim(String.format(Locale.US, "%.1f", ly / 1e6)) + " million light years";
        if (ly < 20) return trim(String.format(Locale.US, "%.1f", ly)) + " light years";
        return String.format(Locale.US, "%,d light years", Math.round(ly));
    }

    private static String trim(String s) {
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /**
     * When the light in the photo set off, in a way that means something: the year for the nearest, then what was happening
     * on the Earth (the pyramids, the Ice Age, the dinosaurs) for the farther ones.
     */
    static String lightTime(double ly, int year) {
        long y = Math.round(ly);
        if (ly < 150) return "The light in your photo left it in " + (year - y) + ".";
        if (ly < 2000) {
            long when = Math.round((year - ly) / 10.0) * 10;
            return "The light in your photo set off from it around the year " + when + ".";
        }
        String ago = "Its light set off " + (ly >= 1e6 ? distance(ly).replace(" light years", " years") : String.format(Locale.US, "%,d years", y))
                + " ago, ";
        if (ly < 4400) return ago + "around " + String.format(Locale.US, "%,d", Math.round((ly - year) / 100.0) * 100) + " BC.";
        if (ly < 4700) return ago + "when the pyramids were being built.";
        if (ly < 12000) return ago + "before the pyramids were built.";
        if (ly < 120000) return ago + "in the last Ice Age, when mammoths roamed the Earth.";
        if (ly < 3.5e6) return ago + "when our ancestors were making the first stone tools.";
        if (ly < 66e6) return ago + "just after the dinosaurs died out.";
        if (ly < 250e6) return ago + "when dinosaurs walked the Earth.";
        if (ly < 4.5e9) return ago + "before there were any animals or plants on the Earth.";
        return ago + "before the Sun and the Earth were even made.";
    }

    /** How many of them are in the photo. */
    public static int inPhoto(List<Spot> spots) {
        int n = 0;
        for (Spot s : spots) if (s.inPhoto) n++;
        return n;
    }

    /** "2 black holes, a neutron star and 3 stars with planets" (those in the photo), or "" for none. */
    public static String summary(List<Spot> spots) {
        List<String> parts = new ArrayList<String>();
        for (Kind k : Kind.values()) {
            int n = 0;
            for (Spot s : spots) if (s.inPhoto && s.what.kind == k) n++;
            if (n == 0) continue;
            if (n == 1) {
                String a = k == Kind.MESSAGE ? "a" : k.one.matches("^[aeiou].*") ? "an" : "a";
                parts.add(a + " " + k.one);
            } else {
                parts.add(n + " " + k.many);
            }
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) b.append(i == parts.size() - 1 ? " and " : ", ");
            b.append(parts.get(i));
        }
        return b.toString();
    }

    /** The pulsars in the photo that can be heard (their beat), fastest last. */
    public static List<Spot> beats(List<Spot> spots) {
        List<Spot> l = new ArrayList<Spot>();
        for (Spot s : spots) if (s.inPhoto && s.what.spinsPerSecond > 0) l.add(s);
        Collections.sort(l, new Comparator<Spot>() {
            @Override
            public int compare(Spot a, Spot b) {
                return Double.compare(a.what.spinsPerSecond, b.what.spinsPerSecond);
            }
        });
        return l;
    }

    /** The pulsars in the photo that can be heard, or (none in it) those up in the sky when it was taken; slowest first. */
    public static List<Spot> toHear(List<Spot> spots) {
        List<Spot> l = beats(spots);
        if (!l.isEmpty()) return l;
        for (Spot s : spots) if (s.up && s.what.spinsPerSecond > 0) l.add(s);
        Collections.sort(l, new Comparator<Spot>() {
            @Override
            public int compare(Spot a, Spot b) {
                return Double.compare(a.what.spinsPerSecond, b.what.spinsPerSecond);
            }
        });
        return l;
    }

    /** "spins 30 times a second", "ticks every 1.34 s": a pulsar's beat in words. */
    public static String spinWords(double spinsPerSecond) {
        if (spinsPerSecond >= 1.5) return "spins " + Math.round(spinsPerSecond) + " times a second";
        return String.format(Locale.US, "ticks every %.2f s", 1 / spinsPerSecond);
    }

    /** Pulsars' beats one after another, {@code each} seconds of each and {@code gap} of quiet after it, as 16-bit stereo. */
    public static short[] tour(List<Spot> pulsars, double each, double gap, int rate) {
        int per = (int) Math.round((each + gap) * rate);
        short[] out = new short[per * pulsars.size() * 2];
        for (int k = 0; k < pulsars.size(); k++) {
            short[] m = beat(pulsars.get(k).what.spinsPerSecond, each, rate);
            int o = k * per * 2;
            for (int i = 0; i < m.length && i < per; i++) {
                out[o + 2 * i] = m[i];
                out[o + 2 * i + 1] = m[i];
            }
        }
        return out;
    }

    /**
     * A pulsar's beat to hear (16-bit mono PCM at {@code rate}): a click every time its beam sweeps past, {@code seconds}
     * long, as radio astronomers play them. Fast ones become a buzz, as they do.
     */
    public static short[] beat(double spinsPerSecond, double seconds, int rate) {
        int n = (int) Math.round(seconds * rate);
        short[] pcm = new short[n];
        if (spinsPerSecond <= 0) return pcm;
        double period = rate / spinsPerSecond;
        int clickLen = (int) Math.max(8, Math.min(rate * 0.006, period * 0.45));
        for (double at = rate * 0.05; at < n; at += period) {
            int s0 = (int) at;
            for (int i = 0; i < clickLen && s0 + i < n; i++) {
                double env = Math.exp(-i / (clickLen / 4.0));
                double v = env * (0.75 * Math.sin(2 * Math.PI * 1750.0 * i / rate) + 0.25 * Math.sin(2 * Math.PI * 620.0 * i / rate));
                int x = pcm[s0 + i] + (int) Math.round(v * 14000);
                pcm[s0 + i] = (short) Math.max(-32767, Math.min(32767, x));
            }
        }
        // in and out softly, so the sound doesn't start or end with a crack
        int fade = Math.min(n / 2, rate / 50);
        for (int i = 0; i < fade; i++) {
            pcm[i] = (short) (pcm[i] * i / fade);
            pcm[n - 1 - i] = (short) (pcm[n - 1 - i] * i / fade);
        }
        return pcm;
    }

    // ------------------------------------------------------------------------------------------------
    // The picture

    /** Where a point of the photo's crop (shares across and down) lands in the picture drawn on (its pixels); null: off it. */
    public interface Place {
        double[] at(double u, double v);
    }

    /** The X-ray film's colours: dark navy for the dark sky, violet, cyan, white for the brightest. */
    private static final int[][] FILM = {{4, 6, 24}, {38, 26, 110}, {70, 150, 235}, {205, 240, 255}, {255, 255, 255}};
    private static final double[] FILM_AT = {0, 0.28, 0.6, 0.88, 1};

    /** Turns the photo's colours (ARGB, in place) into the X-ray film's, by brightness, a little dimmed so the marks stand out. */
    public static void tint(int[] argb) {
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            double t = Math.pow(i / 255.0, 0.9);
            int k = 0;
            while (k < FILM_AT.length - 2 && t > FILM_AT[k + 1]) k++;
            double f = (t - FILM_AT[k]) / (FILM_AT[k + 1] - FILM_AT[k]);
            int r = (int) Math.round((FILM[k][0] + f * (FILM[k + 1][0] - FILM[k][0])) * 0.82);
            int g = (int) Math.round((FILM[k][1] + f * (FILM[k + 1][1] - FILM[k][1])) * 0.82);
            int b = (int) Math.round((FILM[k][2] + f * (FILM[k + 1][2] - FILM[k][2])) * 0.82);
            lut[i] = 0xFF000000 | r << 16 | g << 8 | b;
        }
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            int y = (int) ((((c >> 16) & 255) * 54 + ((c >> 8) & 255) * 183 + (c & 255) * 19) >> 8);
            argb[i] = lut[Math.min(255, y)];
        }
    }

    /** The marks' colours. */
    static final int HOLE_RING = 0xFFFFA347, HOLE_GLOW = 0x55FF7A1E, HOLE_INNER = 0xFFFFE2A8, PULSAR = 0xFF7FE8FF, PULSAR_GLOW = 0x557FE8FF,
            SHELL = 0xFFFF6B5A, SHELL_IN = 0xC0FFB36B, CRASH_GOLD = 0xFFFFD166, PLANET = 0xFF8CFFB0, STAR = 0xFFFFF4C2, FIELD_LINE = 0xFFFFE066,
            RADIO = 0xFFFF7AE6, WORDS = 0xFFFFFFFF, LINE_WORDS = 0xFFCFDAFF;

    /** What was drawn: how many marks and labels. */
    public static final class Drawn {
        public int marks, labels;
    }

    /**
     * Draws the marks and their names over a w x h picture already {@link #tint tinted}: the title at the top, a key to the
     * marks at the bottom, every thing in the photo marked where {@code place} puts it; names where they fit without
     * covering each other (the most striking first).
     */
    public static Drawn paint(final Painter p, int w, int h, List<Spot> spots, Place place) {
        Drawn d = new Drawn();
        float s = Math.max(9f, Math.min(w, h) / 44f);
        // where the marks go (a dry run, nothing drawn), so the title can take the top corner with nothing under it
        Painter dry = new Painter() {
            public float width(String t, float size, boolean bold) {
                return p.width(t, size, bold);
            }

            public void text(String t, float x, float y, float size, boolean bold, int argb) {}

            public void gradient(float x0, float y0, float x1, float y1, int top, int bottom) {}

            public void ring(float cx, float cy, float r, float stroke, int argb) {}

            public void disc(float cx, float cy, float r, int argb) {}

            public void line(float x0, float y0, float x1, float y1, float stroke, int argb) {}
        };
        List<Spot> shown = new ArrayList<Spot>();
        List<float[]> at = new ArrayList<float[]>();
        for (Spot sp : spots) {
            if (!sp.inPhoto) continue;
            double[] q = place.at(sp.u, sp.v);
            if (q == null || q[0] < -s || q[1] < -s || q[0] > w + s || q[1] > h + s) continue;
            float x = (float) q[0], y = (float) q[1];
            float r = mark(dry, sp, x, y, s, place);
            float[] box = sp.what.kind == Kind.FIELD ? outlineBox(sp, place) : null;
            shown.add(sp);
            at.add(box != null ? new float[]{x, y, r, box[0], box[1], box[2], box[3]} : new float[]{x, y, r, x - r, y - r, x + r, y + r});
        }
        // the title, and what's in the photo under it in a line or two
        float title = Math.max(16f, Math.min(w, h) / 17f), pad = title * 0.55f;
        String head = "X-RAY VISION", sum = summary(spots);
        String sub = sum.isEmpty() ? "The invisible universe, as NASA's telescopes see it" : "In your photo: " + sum;
        float headW = p.width(head, title, true), room = Math.max(headW, w * 0.56f);
        float subSize = title * 0.42f;
        List<String> lines = wrap(p, sub, subSize, room);
        while (lines.size() > 2 && subSize > 9f) {
            subSize = Math.max(9f, subSize * 0.9f);
            lines = wrap(p, sub, subSize, room);
        }
        if (lines.size() == 2) {
            // two lines of about the same length, not one long one and a word left over
            float lo = p.width(sub, subSize, false) / 2, hi = room;
            for (int i = 0; i < 14; i++) {
                float mid = (lo + hi) / 2;
                if (wrap(p, sub, subSize, mid).size() <= 2) hi = mid;
                else lo = mid;
            }
            lines = wrap(p, sub, subSize, hi);
        }
        float textW = headW;
        for (String l : lines) textW = Math.max(textW, p.width(l, subSize, false));
        float textH = pad + title * 0.95f + lines.size() * subSize * 1.3f;
        float[] left = {0, 0, pad + textW + pad, textH + pad * 0.4f}, right = {w - pad - textW - pad, 0, w, left[3]};
        boolean onRight = under(at, right) < under(at, left);
        p.gradient(0, 0, w, Math.max(title * 2.6f, textH + title * 0.7f), 0xD0000010, 0x00000010);
        p.gradient(0, h - title * 1.9f, w, h, 0x00000010, 0xD0000010);
        List<float[]> taken = new ArrayList<float[]>();
        taken.add(onRight ? right : left);
        taken.add(new float[]{0, h - title * 1.2f, w, h});
        // marks first (they never give way), then names, most striking first
        java.util.Set<Kind> kinds = new java.util.LinkedHashSet<Kind>();
        for (int i = 0; i < shown.size(); i++) {
            Spot sp = shown.get(i);
            float[] a = at.get(i);
            mark(p, sp, a[0], a[1], s, place);
            taken.add(new float[]{a[0] - a[2], a[1] - a[2], a[0] + a[2], a[1] + a[2]});
            if (sp.what.kind == Kind.FIELD) edges(sp, place, s, taken);
            kinds.add(sp.what.kind);
            d.marks++;
        }
        float ty = pad + title * 0.85f;
        p.text(head, onRight ? w - pad - headW : pad, ty, title, true, WORDS);
        ty += title * 0.1f;
        for (String l : lines) {
            ty += subSize * 1.3f;
            p.text(l, onRight ? w - pad - p.width(l, subSize, false) : pad, ty, subSize, false, LINE_WORDS);
        }
        for (int i = 0; i < shown.size(); i++) {
            if (label(p, shown.get(i), at.get(i), s, w, h, taken, true) || label(p, shown.get(i), at.get(i), s, w, h, taken, false)) d.labels++;
        }
        key(p, w, h, title, s, kinds);
        return d;
    }

    /** A patch of sky's outline as small boxes along its four sides, so no name is written across them (names inside it are fine). */
    private static void edges(Spot sp, Place place, float s, List<float[]> taken) {
        if (sp.outline == null || sp.outline.length != 4) return;
        double[][] c = new double[4][];
        for (int k = 0; k < 4; k++) if ((c[k] = place.at(sp.outline[k][0], sp.outline[k][1])) == null) return;
        float e = Math.max(2f, s * 0.1f);
        for (int k = 0; k < 4; k++) {
            double[] a = c[k], b = c[(k + 1) % 4];
            int n = Math.max(1, (int) Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1]) / (s * 0.5f)));
            for (int i = 0; i <= n; i++) {
                float x = (float) (a[0] + (b[0] - a[0]) * i / n), y = (float) (a[1] + (b[1] - a[1]) * i / n);
                taken.add(new float[]{x - e, y - e, x + e, y + e});
            }
        }
    }

    /** How many of the marks at {@code at} reach into the box. */
    private static int under(List<float[]> at, float[] box) {
        int n = 0;
        for (float[] a : at) if (a[0] - a[2] < box[2] && a[0] + a[2] > box[0] && a[1] - a[2] < box[3] && a[1] + a[2] > box[1]) n++;
        return n;
    }

    /** {@code t} broken into lines at most {@code room} px wide (a word longer than that gets a line of its own). */
    static List<String> wrap(Painter p, String t, float size, float room) {
        List<String> out = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        // a number or "a" stays with the word after it ("2 neutron stars" never breaks after the 2)
        List<String> words = new ArrayList<String>();
        String glue = null;
        for (String word : t.split(" ")) {
            if (word.isEmpty()) continue;
            if (glue != null) {
                word = glue + " " + word;
                glue = null;
            }
            if (word.matches("[0-9][0-9,.]*|an?|the")) glue = word;
            else words.add(word);
        }
        if (glue != null) words.add(glue);
        for (String word : words) {
            String tried = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && p.width(tried, size, false) > room) {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(tried);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** Where a patch of sky drawn as its outline reaches in the picture (x0, y0, x1, y1), or null for a speck. */
    private static float[] outlineBox(Spot sp, Place place) {
        if (sp.outline == null || sp.outline.length != 4) return null;
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (double[] c : sp.outline) {
            double[] q = place.at(c[0], c[1]);
            if (q == null) return null;
            b[0] = Math.min(b[0], (float) q[0]);
            b[1] = Math.min(b[1], (float) q[1]);
            b[2] = Math.max(b[2], (float) q[0]);
            b[3] = Math.max(b[3], (float) q[1]);
        }
        return b;
    }

    /** One thing's mark at (x, y), {@code s} its size; how far out it reaches (px). */
    static float mark(Painter p, Spot sp, float x, float y, float s, Place place) {
        switch (sp.what.kind) {
            case BLACK_HOLE:
                hole(p, x, y, s);
                return s * 1.45f;
            case GIANT: {
                float k = s * 1.3f;
                // its jets, then the ring round its shadow
                p.line(x - k * 1.9f, y + k * 1.9f, x + k * 1.9f, y - k * 1.9f, Math.max(1.5f, k * 0.12f), 0xA0B8E6FF);
                hole(p, x, y, k);
                return k * 1.9f;
            }
            case NEUTRON_STAR: {
                double a = Math.toRadians(-60);
                float bx = (float) Math.cos(a) * s * 2.3f, by = (float) Math.sin(a) * s * 2.3f;
                p.line(x - bx, y - by, x + bx, y + by, Math.max(2f, s * 0.26f), 0x707FE8FF);
                p.line(x - bx * 0.8f, y - by * 0.8f, x + bx * 0.8f, y + by * 0.8f, Math.max(1f, s * 0.1f), PULSAR);
                p.disc(x, y, s * 0.6f, PULSAR_GLOW);
                p.disc(x, y, s * 0.3f, 0xFFF0FCFF);
                if (sp.what.line.startsWith("magnetar")) {
                    p.ring(x - s * 0.55f, y, s * 0.55f, Math.max(1f, s * 0.08f), 0xB0C8A0FF);
                    p.ring(x + s * 0.55f, y, s * 0.55f, Math.max(1f, s * 0.08f), 0xB0C8A0FF);
                }
                return s * 2.3f;
            }
            case REMNANT: {
                float r = s;
                if (sp.outline != null) {
                    double[] e = place.at(sp.outline[0][0], sp.outline[0][1]);
                    if (e != null) r = Math.max(s, (float) Math.hypot(e[0] - x, e[1] - y));
                }
                p.ring(x, y, r, Math.max(2f, s * 0.22f), SHELL);
                p.ring(x, y, r * 0.64f, Math.max(1.5f, s * 0.15f), SHELL_IN);
                p.ring(x, y, r * 1.18f, Math.max(1f, s * 0.08f), 0x60FF6B5A);
                return r * 1.18f;
            }
            case CRASH:
                for (int k = 3; k >= 1; k--) p.ring(x, y, s * (0.45f + 0.42f * k), Math.max(1f, s * 0.12f), (0xFF - 60 * k) << 24 | 0xFFD166);
                p.disc(x - s * 0.22f, y, s * 0.2f, CRASH_GOLD);
                p.disc(x + s * 0.22f, y, s * 0.2f, CRASH_GOLD);
                return s * 1.75f;
            case PLANETS: {
                p.ring(x, y, s, Math.max(1.5f, s * 0.13f), 0xD08CFFB0);
                double a = Math.toRadians(-40);
                p.disc(x + (float) Math.cos(a) * s, y + (float) Math.sin(a) * s, s * 0.25f, PLANET);
                p.disc(x, y, s * 0.18f, STAR);
                return s * 1.25f;
            }
            case FIELD: {
                if (sp.outline != null && sp.outline.length == 4) {
                    float[] px = new float[8];
                    for (int k = 0; k < 4; k++) {
                        double[] q = place.at(sp.outline[k][0], sp.outline[k][1]);
                        if (q == null) {
                            px = null;
                            break;
                        }
                        px[2 * k] = (float) q[0];
                        px[2 * k + 1] = (float) q[1];
                    }
                    if (px != null) {
                        for (int k = 0; k < 4; k++) {
                            int j = (k + 1) % 4;
                            p.line(px[2 * k], px[2 * k + 1], px[2 * j], px[2 * j + 1], Math.max(2f, s * 0.14f), 0xC0FFE066);
                        }
                        return s;
                    }
                }
                // a speck: a small square in brackets
                float k = s * 0.9f;
                float t = Math.max(1.5f, s * 0.14f);
                p.line(x - k, y - k, x - k * 0.4f, y - k, t, FIELD_LINE);
                p.line(x - k, y - k, x - k, y - k * 0.4f, t, FIELD_LINE);
                p.line(x + k, y + k, x + k * 0.4f, y + k, t, FIELD_LINE);
                p.line(x + k, y + k, x + k, y + k * 0.4f, t, FIELD_LINE);
                p.line(x + k, y - k, x + k * 0.4f, y - k, t, FIELD_LINE);
                p.line(x + k, y - k, x + k, y - k * 0.4f, t, FIELD_LINE);
                p.line(x - k, y + k, x - k * 0.4f, y + k, t, FIELD_LINE);
                p.line(x - k, y + k, x - k, y + k * 0.4f, t, FIELD_LINE);
                p.disc(x, y, s * 0.12f, FIELD_LINE);
                return k;
            }
            default: {
                for (int k = 3; k >= 1; k--) p.ring(x, y, s * 0.45f * k, Math.max(1f, s * 0.12f), (0xFF - 55 * k) << 24 | 0xFF7AE6);
                p.disc(x, y, s * 0.18f, RADIO);
                return s * 1.35f;
            }
        }
    }

    /** A black hole as the Event Horizon Telescope saw M87's and our galaxy's: a glowing orange ring round a black shadow. */
    private static void hole(Painter p, float x, float y, float s) {
        p.ring(x, y, s * 1.1f, s * 0.7f, HOLE_GLOW);
        p.ring(x, y, s * 0.92f, s * 0.42f, HOLE_RING);
        p.ring(x, y, s * 0.74f, Math.max(1f, s * 0.12f), HOLE_INNER);
        p.disc(x, y, s * 0.62f, 0xFF000000);
    }

    /**
     * Its name (and on {@code full}, its line under it) beside the mark, to the right if it fits there without covering
     * anything drawn, else to the left, below or above; a patch of sky drawn as its outline gets it above or beside the
     * outline, and only inside it when there's no room round it. False when it fits nowhere. {@code a} is the mark's
     * (x, y, reach) and the box it covers.
     */
    private static boolean label(Painter p, Spot sp, float[] a, float s, int w, int h, List<float[]> taken, boolean full) {
        float big = s * 1.05f, small = s * 0.74f;
        String name = sp.what.name, line = sp.what.line;
        float nw = p.width(name, big, true), lw = full ? p.width(line, small, false) : 0;
        float bw = Math.max(nw, lw), bh = full ? big + small * 1.35f : big * 1.1f;
        float gap = s * 0.45f;
        float x = a[0], y = a[1], r = a[2], x0 = a[3], y0 = a[4], x1 = a[5], y1 = a[6];
        float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        boolean outline = x1 - x0 > 2 * r + 1 || y1 - y0 > 2 * r + 1;
        float[][] tries = outline ? new float[][]{
                {cx - bw / 2, y0 - gap - bh}, {x1 + gap, cy - bh / 2}, {cx - bw / 2, y1 + gap}, {x0 - gap - bw, cy - bh / 2},
                {x + r + gap, y - bh / 2}, {x - bw / 2, y + r + gap},
        } : new float[][]{
                {x + r + gap, y - bh / 2}, {x - r - gap - bw, y - bh / 2}, {x - bw / 2, y + r + gap}, {x - bw / 2, y - r - gap - bh},
        };
        for (float[] t : tries) {
            float[] box = {t[0] - 2, t[1] - 2, t[0] + bw + 2, t[1] + bh + 2};
            if (box[0] < 2 || box[1] < 2 || box[2] > w - 2 || box[3] > h - 2) continue;
            boolean free = true;
            for (float[] o : taken) {
                if (box[0] < o[2] && box[2] > o[0] && box[1] < o[3] && box[3] > o[1]) {
                    free = false;
                    break;
                }
            }
            if (!free) continue;
            p.text(name, t[0], t[1] + big * 0.86f, big, true, WORDS);
            if (full) p.text(line, t[0], t[1] + big + small * 1.05f, small, false, LINE_WORDS);
            taken.add(box);
            return true;
        }
        return false;
    }

    /** The key at the bottom: a small mark and its words for each kind drawn. */
    private static void key(Painter p, int w, int h, float title, float s, java.util.Set<Kind> kinds) {
        if (kinds.isEmpty()) return;
        float size = Math.max(10f, title * 0.36f), ks = size * 0.62f;
        float y = h - title * 0.55f, x = title * 0.55f;
        for (Kind k : kinds) {
            String word = k.one;
            float need = ks * 3.2f + p.width(word, size, false) + size * 1.2f;
            if (x + need > w - title * 0.3f) break;
            Spot fake = new Spot(new Thing("", k, 0, 0, 0, Double.NaN, 0, "", ""));
            mark(p, fake, x + ks * 1.3f, y - size * 0.32f, ks, null);
            p.text(word, x + ks * 3.2f, y, size, false, LINE_WORDS);
            x += need;
        }
    }
}
