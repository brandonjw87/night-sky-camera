package com.brandon.nightsky.processing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Deep Space Zoom (2.4): close-ups of the galaxies, nebulae, clusters and planets in a photo, made sharper than the photo
 * itself by drizzling the frames again at twice the resolution, the way the Hubble Deep Field was made.
 * <p>
 * The sky drifts across the sensor while the frames are taken, so each patch of sky lands on the photosites at many
 * different sub-pixel places. The stack puts every frame's light into pixels of the sensor's own size; the close-up
 * puts it into pixels half that size, each colour from its own photosites only, as a drop three quarters of a pixel
 * wide around each one (Fruchter and Hook's drizzle; Bayer drizzle, as DeepSkyStacker and Siril do it). Every sample is
 * lined up, matched to the session's sky, weighted, kept out of clouds, streak corridors and the ground exactly as the
 * stack did it ({@link SharpStacker}, through {@link PhotoVideo}'s reading of the same plans), and left out where it
 * stands far above the finished stack (a cosmic ray, a plane the stack's rejection took out). Where too few drops fell
 * the frames' interpolated light fills in, as the stack does it; where a star clipped, the stack's own light stays.
 * <p>
 * Its stars are then taken apart from the glow and drawn back in ({@link #redrawStars}, as the image makers behind
 * NASA's pictures separate them): each one measured (its place, its light in each colour), its pixels filled in with
 * the sky or the glow around it, and drawn back as a clean round star with the same light, smaller around a galaxy or a
 * nebula so the glow stands out; faint stars' colours, which the noise makes up, pulled towards white. The close-up is
 * then developed as the photo was: its
 * background (the photo's, the 2.4 light-pollution surface with it), colours, tone curve and the sky's noise taken out
 * at its own scale; and enlarged to {@link #OUT} pixels with a Lanczos filter held between its neighbours (no rings).
 * Each comes with what it is, how far away, how big next to the Moon and a fact; planets with their moons named.
 * <p>
 * What gets a close-up: the catalogue's objects in the picture whose light stands out of the sky's noise
 * ({@link #MIN_SNR}), and Jupiter, Saturn, Uranus and Neptune when they're in it; the best known and the biggest first,
 * at most {@link #MOST}, none overlapping another.
 */
public final class DeepZoom {
    private DeepZoom() {}

    /** The most close-ups a photo gets. */
    public static final int MOST = 3;
    /** A close-up's side as saved (pixels). */
    public static final int OUT = 1080;
    /** The drizzle's drops: this share of a stack pixel wide around each photosite. */
    static final float PIXFRAC = 0.75f;
    /** Output pixels per stack pixel, each way, when the box is drizzled. */
    static final int ZOOM = 2;
    /**
     * A box is at least MIN_BOX stack pixels wide (the close-up enlarges the drizzle at most 3.5 times: past that a star is
     * a blur, however it's done) and at most MAX_SHARE of the crop's shorter side; up to MAX_DRIZZLE it's drizzled at
     * {@link #ZOOM}x, wider ones are cut from the stack as they are.
     */
    static final int MIN_BOX = 154, MAX_DRIZZLE = 360;
    static final double MAX_SHARE = 0.6;
    /** A frame's sample this many times its noise above the stack, plus this share of the light above the sky there, is an outlier. */
    static final float CLIP_K = 6f, CLIP_SHARE = 1.5f;
    /** Fewer frames than this at a pixel: the stack's light takes over in proportion. */
    static final int FULL_FRAMES = 4;
    /** An object must stand this many times its noise over the sky around it to be worth a close-up (clusters and planets too). */
    static final double MIN_SNR = 5;
    /** The full Moon's width on the sky (degrees), the close-ups' scale. */
    static final double MOON_DEG = 0.52;
    /** The lens shading's gains are worked out every GAIN_STEP output pixels and interpolated between. */
    private static final int GAIN_STEP = 8;
    private static final float KR = 0.2126f, KG = 0.7152f, KB = 0.0722f;
    private static final int STEP = PhotoVideo.STEP;
    private static final int NV = PhotoVideo.NV, N_SC = PhotoVideo.N_SC, N_W = PhotoVideo.N_W, N_T = PhotoVideo.N_T,
            N_OFF = PhotoVideo.N_OFF, N_SG = PhotoVideo.N_SG;

    // name (as SkyGuide's OBJECTS has it), its size and the close-up's field (degrees), and something to know about it
    static final Object[][] FACTS = {
            {"the Andromeda Galaxy (M31)", 3.2, 4.6, "A whole galaxy of about a trillion stars, the farthest thing you can see without a "
                    + "telescope. It's coming our way at 110 km a second and will meet the Milky Way in about 4.5 billion years."},
            {"the Triangulum Galaxy (M33)", 1.0, 2.2, "A spiral galaxy of about 40 billion stars, one of the farthest things a sharp eye "
                    + "can see on a very dark night."},
            {"the Pleiades (M45)", 2.0, 3.2, "Young stars born about 100 million years ago, when dinosaurs were alive. The blue haze "
                    + "around them is a cloud of dust they're passing through. In Japan they're called Subaru."},
            {"the Hyades", 5.5, 7.5, "The closest star cluster to us. Bright orange Aldebaran isn't one of them: it's less than half as "
                    + "far, right in front."},
            {"the Orion Nebula (M42)", 1.1, 2.2, "A star nursery 24 light-years across, where hundreds of new stars are being born "
                    + "right now. Its middle hides four young stars called the Trapezium."},
            {"the Double Cluster", 1.0, 2.2, "Two clusters of young, hot stars side by side, each only about 13 million years old."},
            {"the Alpha Persei Cluster", 3.0, 4.5, "Its brightest star, Mirfak, is a supergiant about 5,000 times brighter than the Sun."},
            {"the Beehive Cluster (M44)", 1.6, 2.6, "Galileo was the first to see its stars one by one, through his telescope in 1609. "
                    + "Before that it was just a little cloud."},
            {"M35", 0.5, 1.4, "A cluster of a few hundred stars. The fainter little cluster beside it is six times farther away."},
            {"M41", 0.6, 1.4, "Aristotle may have written about it in 325 BC, which would make it the faintest thing anyone noticed in "
                    + "ancient times."},
            {"M47", 0.5, 1.4, "A loose cluster of bright blue-white stars, about 78 million years old."},
            {"the Rosette Nebula", 1.3, 2.4, "A flower of glowing hydrogen 130 light-years across, blown open by the hot young stars in "
                    + "its middle."},
            {"the California Nebula", 2.5, 3.6, "Shaped like California, and lit up by one very hot star, Menkib, shining beside it."},
            {"the Heart Nebula", 2.0, 3.0, "Its red glow is hydrogen gas lit up by a cluster of young stars in its middle."},
            {"Bode's Galaxy (M81)", 0.45, 1.4, "A spiral galaxy of about 250 billion stars. Its neighbour M82, the Cigar Galaxy, is "
                    + "making new stars ten times faster than the Milky Way."},
            {"the Whirlpool Galaxy (M51)", 0.2, 1.0, "The first galaxy anyone saw was a spiral, through Lord Rosse's giant telescope "
                    + "in 1845. It's pulling on a smaller galaxy at the end of one arm."},
            {"the Pinwheel Galaxy (M101)", 0.5, 1.2, "A spiral almost twice as wide as the Milky Way: 170,000 light-years across."},
            {"the Coma Star Cluster", 4.5, 6.0, "One of the nearest clusters, so spread out that it fills a patch of sky nine Moons wide."},
            {"M3", 0.3, 1.0, "A ball of about half a million stars, some of the oldest anywhere: around 11 billion years old."},
            {"M5", 0.4, 1.0, "A ball of over 100,000 stars, about 13 billion years old, nearly as old as the universe."},
            {"M4", 0.6, 1.2, "One of the closest balls of stars. It's full of white dwarfs, the cooled-down cores of dead stars."},
            {"the Hercules Cluster (M13)", 0.33, 1.0, "A ball of several hundred thousand stars. In 1974 the Arecibo radio telescope "
                    + "sent a message to it, which will arrive in about 25,000 years."},
            {"M92", 0.2, 1.0, "One of the oldest things in the Milky Way: its stars are about 13 billion years old."},
            {"the Butterfly Cluster (M6)", 0.4, 1.2, "Its stars make the shape of a butterfly with open wings."},
            {"the Ptolemy Cluster (M7)", 1.3, 2.4, "Ptolemy wrote about it around the year 130, almost 1,900 years ago."},
            {"the Lagoon Nebula (M8)", 1.5, 2.8, "A star nursery three times as wide as the full Moon in our sky, with a dark lane of "
                    + "dust like a lagoon through it."},
            {"the Sagittarius Star Cloud (M24)", 1.5, 3.0, "Not a cluster: a window through the dust to a spiral arm 10,000 "
                    + "light-years away, packed with stars."},
            {"M22", 0.5, 1.2, "One of the first balls of stars ever found, in 1665, and one of the closest."},
            {"the Wild Duck Cluster (M11)", 0.25, 1.0, "About 2,900 stars; its brightest make a V like a flock of ducks flying."},
            {"the Coathanger", 1.0, 2.0, "A chance line-up: its stars are at very different distances and only look like a coat hanger "
                    + "from here."},
            {"the Dumbbell Nebula (M27)", 0.13, 0.8, "A dying star puffed off its outer layers into this glowing cloud. The Sun will do "
                    + "the same, billions of years from now."},
            {"the North America Nebula", 2.0, 3.4, "Shaped like North America. The dark 'Gulf of Mexico' is a cloud of dust in front of "
                    + "the glowing gas."},
            {"M39", 0.5, 1.6, "A loose cluster of about 30 stars, close enough that they look spread out."},
            {"Omega Centauri", 0.6, 1.4, "The biggest ball of stars in the Milky Way, about 10 million of them. It may be the core of a "
                    + "small galaxy the Milky Way swallowed."},
            {"47 Tucanae", 0.5, 1.3, "The second brightest ball of stars in the sky, with a dense, bright middle."},
            {"the Large Magellanic Cloud", 10.0, 12.0, "A small galaxy going around the Milky Way. In 1987 a star in it exploded, the "
                    + "brightest supernova seen in nearly 400 years."},
            {"the Small Magellanic Cloud", 5.0, 7.0, "A small galaxy going around the Milky Way, slowly being pulled apart by it."},
            {"the Carina Nebula", 2.0, 3.2, "Four times as big as the Orion Nebula. Its star Eta Carinae is 100 times heavier than the Sun "
                    + "and will one day explode."},
    };

    /** What a planet's close-up says about it, by Planets' index (Jupiter, Saturn, Uranus, Neptune). */
    private static final String[] PLANET_FACTS = {
            null, null, null,
            "Galileo found its four big moons in January 1610: the first things anyone saw going around another world. Watch them "
                    + "move from one night to the next.",
            "Its rings are too small to see here. Its biggest moon, Titan, has rain, rivers and lakes, but of methane instead of water.",
            "The first planet found with a telescope, in 1781. It rolls around the Sun on its side.",
            "So far away that one trip around the Sun takes it 165 years. It was found in 1846 by working out where it had to be.",
    };

    /** Planets that get a close-up, by Planets' index. */
    private static final int[] PLANETS = {3, 4, 5, 6};

    /** Something in the photo worth a close-up, and where its box is. */
    public static final class Target {
        public final String name;
        /** g galaxy, o open cluster, c globular cluster, n nebula, s star cloud, p planet. */
        public final char kind;
        public final double mag, distLy, sizeDeg, fieldDeg;
        public final String fact;
        /** Where it is in the stack (px), its scale there (px per degree), and how far it stands out of the sky's noise. */
        public double px, py, pxPerDeg, snr = Double.NaN;
        /** A planet's distance from us (AU); NaN for the rest. */
        public double distAu = Double.NaN;
        /** Points to name in the close-up (a planet and its moons): where they are in the stack (px), and their names. */
        final List<double[]> markPx = new ArrayList<double[]>();
        final List<String> markNames = new ArrayList<String>();
        /** Jupiter's big moons (2.4), for the close-up's diagram of them; null for anything else. */
        DeepSpace.Moon[] moons;
        Box box;

        Target(String name, char kind, double mag, double distLy, double sizeDeg, double fieldDeg, String fact) {
            this.name = name;
            this.kind = kind;
            this.mag = mag;
            this.distLy = distLy;
            this.sizeDeg = sizeDeg;
            this.fieldDeg = fieldDeg;
            this.fact = fact;
        }

        /** Adds a point to name in the close-up (a planet, a moon of it), at J2000 direction {@code j2000}. */
        public void mark(double[] j2000, String name, SkySolver.Solution sol) {
            double[] p = sol.pixel(j2000);
            if (p == null) return;
            markPx.add(p);
            markNames.add(name);
        }

        /** Whether its box is drizzled from the frames (else cut from the stack). */
        public boolean drizzled() {
            return box != null && box.s > 1;
        }

        /** The box's width in the stack (px). */
        public int side() {
            return box != null ? box.side : 0;
        }

        /** The order close-ups are chosen in: the best known and biggest first; lower first. */
        double score() {
            if (kind == 'p') return mag - 3;
            return mag - 1.2 * Math.log(Math.max(0.25, sizeDeg) / 0.5) / Math.log(2);
        }
    }

    /** One close-up: its picture and what it says. */
    public static final class CloseUp {
        public final String name;
        public final char kind;
        /** The picture, {@link #size} pixels each way, ARGB row after row. */
        public int[] argb;
        public int size;
        /** The title ("The Andromeda Galaxy (M31)"), what it is, how far, how big, the fact, and how it was made. */
        public String title = "", what = "", distance = "", width = "", fact = "", how = "";
        /** The full Moon's width in the picture (px), for the scale. */
        public float moonPx;
        /** Points named in the picture: x, y (px) each, and their names. */
        public float[] marks = new float[0];
        public String[] markNames = new String[0];
        /**
         * Jupiter's big moons (2.4), for the diagram of them on its close-up: their places beside it along its equator (in
         * Jupiter's widths, + to the right as the close-up shows them), names, and whether each is beside Jupiter (0), in
         * front of it (1) or behind it (2). Empty for anything else.
         */
        public float[] moonX = new float[0];
        public String[] moonNames = new String[0];
        public byte[] moonState = new byte[0];
        /**
         * For the log and the tests: how the stars' sizes came out (Gaussian sigma in stack pixels: the drizzle's, the
         * stack's at the same place and scale, and after the stars were made smaller), and the share of the object's own
         * light the smaller stars kept (NaN: not made smaller).
         */
        public double starSigma = Double.NaN, stackStarSigma = Double.NaN, smallerStarSigma = Double.NaN, objectKept = Double.NaN;
        /** How many stars were taken out and drawn back in ({@link #redrawStars}). */
        public int starsRedrawn;
        public String note = "";

        CloseUp(String name, char kind) {
            this.name = name;
            this.kind = kind;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // What gets a close-up
    // ------------------------------------------------------------------------------------------------

    /**
     * The photo's close-ups, best first (at most {@link #MOST}): its plate solution {@code sol} (null: none), the time of
     * its reference frame ({@code jdUtc}, NaN: unknown, then no planets), the stack and its analysis, the ground kept still
     * (null: none), and whether the photo is turned north up (the close-ups are too). Why each candidate wasn't taken goes
     * into {@code notes}.
     */
    public static List<Target> plan(SkySolver.Solution sol, double jdUtc, RgbImage stack, Renderer.Analysis a, GroundMap ground,
                                    boolean northUp, List<String> notes) {
        return plan(sol, jdUtc, stack, a, ground, northUp, MOST, notes);
    }

    /** {@link #plan} with up to {@code most} close-ups (the tests look at every candidate). */
    public static List<Target> plan(SkySolver.Solution sol, double jdUtc, RgbImage stack, Renderer.Analysis a, GroundMap ground,
                                    boolean northUp, int most, List<String> notes) {
        List<Target> out = new ArrayList<Target>();
        if (sol == null || stack == null || a == null || a.cw < 2 * MIN_BOX || a.ch < 2 * MIN_BOX) {
            if (notes != null) notes.add(sol == null ? "the photo isn't placed on the sky" : "the photo is too small");
            return out;
        }
        List<Target> cand = new ArrayList<Target>();
        for (Object[] o : SkyGuide.OBJECTS) {
            Object[] f = facts((String) o[0]);
            if (f == null) continue;
            Target t = new Target((String) o[0], (Character) o[4], (Double) o[3], (Double) o[5], (Double) f[1], (Double) f[2], (String) f[3]);
            if (place(t, Astro.vec((Double) o[1] * Astro.DEG, (Double) o[2] * Astro.DEG), sol, a)) cand.add(t);
        }
        if (!Double.isNaN(jdUtc)) {
            double jdTt = Astro.jdTt(jdUtc);
            for (int k : PLANETS) {
                Planets.Seen s = Planets.seen(k, jdTt);
                // Jupiter's four big moons (2.4): each named where it is, and any in front of Jupiter or behind it said so
                DeepSpace.Moon[] moons = k == 3 ? DeepSpace.jupiterMoons(jdTt) : new DeepSpace.Moon[0];
                StringBuilder fact = new StringBuilder(PLANET_FACTS[k]);
                for (DeepSpace.Moon m : moons) {
                    if (m.onDisc()) fact.append(" Tonight ").append(m.name).append(m.front ? " is in front of Jupiter, lost in its glare." : " is hidden behind it.");
                }
                Target t = new Target(s.name, 'p', s.mag, Double.NaN, 0.05, 0.5, fact.toString());
                t.distAu = s.distAu;
                if (!place(t, s.dir, sol, a)) continue;
                t.mark(s.dir, s.name, sol);
                for (DeepSpace.Moon m : moons) if (!m.onDisc()) t.mark(m.dir, m.name, sol);
                if (moons.length > 0) t.moons = moons;
                cand.add(t);
            }
        }
        Collections.sort(cand, new Comparator<Target>() {
            @Override
            public int compare(Target p, Target q) {
                return Double.compare(p.score(), q.score());
            }
        });
        int widest = Math.max(1, (int) (MAX_SHARE * Math.min(a.cw, a.ch))) & ~1;
        for (Target t : cand) {
            if (out.size() >= most) break;
            int side = (int) Math.round(t.fieldDeg * t.pxPerDeg);
            side = Math.max(MIN_BOX, Math.min(widest, side)) & ~1;
            double[] up = northUp ? north(sol, t) : null;
            Box b = fit(t.px, t.py, side, side <= MAX_DRIZZLE ? ZOOM : 1, up, a);
            if (b == null) {
                if (notes != null) notes.add(t.name + ": too near the picture's edge");
                continue;
            }
            float gs = groundShare(b, ground, stack);
            if (gs > 0.2f) {
                if (notes != null) notes.add(String.format(Locale.US, "%s: %.0f%% of its box is the ground", t.name, 100 * gs));
                continue;
            }
            boolean overlaps = false;
            for (Target o : out) overlaps |= overlap(o.box, b) > 0.25;
            if (overlaps) {
                if (notes != null) notes.add(t.name + ": inside another close-up");
                continue;
            }
            if (t.kind != 'p') {
                double r = Math.max(2.5, 0.5 * t.sizeDeg * t.pxPerDeg);
                t.snr = snr(stack, a, t.px, t.py, Math.min(r, 0.35 * side), t.kind == 'o' || t.kind == 'c');
                if (t.snr < MIN_SNR) {
                    if (notes != null) notes.add(String.format(Locale.US, "%s: too faint here (%.1f sigma over the sky)", t.name, t.snr));
                    continue;
                }
            }
            t.box = b;
            out.add(t);
        }
        return out;
    }

    private static Object[] facts(String name) {
        for (Object[] f : FACTS) if (f[0].equals(name)) return f;
        return null;
    }

    /** Puts t where the solution has it in the stack, with its scale there; false if it isn't well inside the crop. */
    private static boolean place(Target t, double[] dir, SkySolver.Solution sol, Renderer.Analysis a) {
        double[] p = sol.pixel(dir);
        if (p == null || Double.isNaN(p[0]) || Double.isNaN(p[1])) return false;
        double mx = 0.03 * a.cw, my = 0.03 * a.ch;
        if (p[0] < a.cx0 + mx || p[1] < a.cy0 + my || p[0] > a.cx0 + a.cw - mx || p[1] > a.cy0 + a.ch - my) return false;
        // the lens model holds out to a little past the picture: a point mapped back must come out where it went in
        double[] back = sol.direction(p[0], p[1]);
        if (Astro.sep(back, dir) > 0.05 * Astro.DEG) return false;
        double[] e = tangents(dir);
        double d = 0.25 * Astro.DEG, s = 0;
        int n = 0;
        for (int k = 0; k < 2; k++) {
            double[] v = new double[3];
            for (int i = 0; i < 3; i++) v[i] = Math.cos(d) * dir[i] + Math.sin(d) * e[3 * k + i];
            double[] q = sol.pixel(v);
            if (q == null) continue;
            s += Math.hypot(q[0] - p[0], q[1] - p[1]) / 0.25;
            n++;
        }
        if (n == 0) return false;
        t.px = p[0];
        t.py = p[1];
        t.pxPerDeg = s / n;
        return t.pxPerDeg > 0;
    }

    /** Unit vectors towards north (0..2) and east (3..5) on the sky at direction v. */
    static double[] tangents(double[] v) {
        double[] rd = Astro.raDec(v);
        double ra = rd[0], dec = rd[1];
        return new double[]{-Math.sin(dec) * Math.cos(ra), -Math.sin(dec) * Math.sin(ra), Math.cos(dec),
                -Math.sin(ra), Math.cos(ra), 0};
    }

    /** North's direction in the stack at t (unit, y down), or null if it can't be found. */
    private static double[] north(SkySolver.Solution sol, Target t) {
        double[] dir = sol.direction(t.px, t.py);
        double[] e = tangents(dir);
        double d = 0.2 * Astro.DEG;
        double[] v = new double[3];
        for (int i = 0; i < 3; i++) v[i] = Math.cos(d) * dir[i] + Math.sin(d) * e[i];
        double[] q = sol.pixel(v);
        if (q == null) return null;
        double nx = q[0] - t.px, ny = q[1] - t.py, n = Math.hypot(nx, ny);
        return n > 0 ? new double[]{nx / n, ny / n} : null;
    }

    /**
     * The box of {@code side} stack px around (x, y), turned so {@code up} (null: the stack's own up) is up, moved in as far
     * as the crop needs, but never so far that the object is off its middle by more than a quarter of the box; null then.
     */
    static Box fit(double x, double y, int side, int s, double[] up, Renderer.Analysis a) {
        Box b = new Box(x, y, side, s, up);
        double hx = 0.5 * side * (Math.abs(b.rx) + Math.abs(b.dx)), hy = 0.5 * side * (Math.abs(b.ry) + Math.abs(b.dy));
        double x0 = a.cx0 + hx + 1, x1 = a.cx0 + a.cw - hx - 1, y0 = a.cy0 + hy + 1, y1 = a.cy0 + a.ch - hy - 1;
        if (x0 > x1 || y0 > y1) return null;
        double cx = Math.max(x0, Math.min(x1, x)), cy = Math.max(y0, Math.min(y1, y));
        if (Math.hypot(cx - x, cy - y) > 0.25 * side) return null;
        return new Box(cx, cy, side, s, up);
    }

    /** The share of the box on the ground or next to it (0 without a ground map). */
    private static float groundShare(Box b, GroundMap ground, RgbImage stack) {
        if (ground == null) return 0f;
        int on = 0, n = 0;
        for (int j = 0; j < 16; j++) {
            for (int i = 0; i < 16; i++) {
                double X = b.x((i + 0.5) / 16 * b.n, (j + 0.5) / 16 * b.n), Y = b.y((i + 0.5) / 16 * b.n, (j + 0.5) / 16 * b.n);
                if (X < 0 || Y < 0 || X >= stack.width || Y >= stack.height) continue;
                n++;
                if (ground.nearAt((float) X, (float) Y)) on++;
            }
        }
        return n > 0 ? on / (float) n : 0f;
    }

    /** How much of the smaller box's bounding rectangle the two boxes' share. */
    private static double overlap(Box p, Box q) {
        double[] a = p.bounds(), b = q.bounds();
        double w = Math.min(a[2], b[2]) - Math.max(a[0], b[0]), h = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
        if (w <= 0 || h <= 0) return 0;
        double sa = (a[2] - a[0]) * (a[3] - a[1]), sb = (b[2] - b[0]) * (b[3] - b[1]);
        return w * h / Math.min(sa, sb);
    }

    /**
     * How far the light within r px of (x, y) stands over the sky around it (a ring outside it), in its noise. A glow
     * (a galaxy, a nebula, a star cloud: {@code stars} false) by its median against the ring's, so the stars in front of
     * it don't count; a cluster ({@code stars} true) by all its light against the ring's mean, the ring's stars and all,
     * so a cluster must hold more stars than the sky around it. The noise: the stack's luminance noise per pixel
     * ({@link Renderer.Analysis#noiseY}), half again for the interpolation's correlation, and the ring's own uncertainty.
     * NaN when the ring is too small (an object bigger than the picture).
     */
    static double snr(RgbImage img, Renderer.Analysis a, double x, double y, double r, boolean stars) {
        float[] m = a.m;
        float yr = KR * m[0] + KG * m[3] + KB * m[6], yg = KR * m[1] + KG * m[4] + KB * m[7], yb = KR * m[2] + KG * m[5] + KB * m[8];
        double r1 = 1.5 * r + 2, r2 = 2.2 * r + 4;
        int x0 = Math.max(a.cx0, (int) Math.floor(x - r2)), x1 = Math.min(a.cx0 + a.cw - 1, (int) Math.ceil(x + r2));
        int y0 = Math.max(a.cy0, (int) Math.floor(y - r2)), y1 = Math.min(a.cy0 + a.ch - 1, (int) Math.ceil(y + r2));
        int cap = (x1 - x0 + 1) * (y1 - y0 + 1);
        if (cap <= 0) return Double.NaN;
        float[] ring = new float[cap], in = new float[cap];
        int nr = 0, ni = 0;
        double ringSum = 0, ringSq = 0;
        for (int py = y0; py <= y1; py++) {
            for (int px = x0; px <= x1; px++) {
                double d = Math.hypot(px + 0.5 - x, py + 0.5 - y);
                int i = py * img.width + px;
                float l = yr * img.r[i] + yg * img.g[i] + yb * img.b[i];
                if (d <= r) {
                    in[ni++] = l;
                } else if (d >= r1 && d <= r2) {
                    ring[nr++] = l;
                    ringSum += l;
                    ringSq += (double) l * l;
                }
            }
        }
        if (nr < 30 || ni < 4) return Double.NaN;
        double pix = 1.5 * Math.max(1e-6, a.noiseY);
        if (!stars) {
            float medIn = Stats.median(in, ni), medRing = Stats.median(ring, nr);
            float sig = Stats.madSigma(ring, nr, medRing);
            double e = 1.2533 * pix / Math.sqrt(ni), f = 1.2533 * sig / Math.sqrt(nr);
            return (medIn - medRing) / Math.sqrt(e * e + f * f);
        }
        double mean = ringSum / nr, sd = Math.sqrt(Math.max(0, ringSq / nr - mean * mean));
        double sum = 0;
        for (int k = 0; k < ni; k++) sum += in[k] - mean;
        double ringErr = ni * sd / Math.sqrt(nr);
        return sum / Math.sqrt(ni * pix * pix + ringErr * ringErr);
    }

    // ------------------------------------------------------------------------------------------------
    // The box
    // ------------------------------------------------------------------------------------------------

    /** A close-up's box in the stack, and how its picture's pixels map onto it. */
    static final class Box {
        /** The middle (stack px), the side (stack px), output pixels per stack pixel and the picture's side (s x side). */
        final double cx, cy;
        final int side, s, n;
        /** The picture's right and down in the stack (unit vectors). */
        final double rx, ry, dx, dy;

        Box(double cx, double cy, int side, int s, double[] up) {
            this.cx = cx;
            this.cy = cy;
            this.side = side;
            this.s = s;
            n = side * s;
            double nx = up != null ? up[0] : 0, ny = up != null ? up[1] : -1;
            rx = -ny;
            ry = nx;
            dx = -nx;
            dy = -ny;
        }

        /** Stack x and y of the picture's point (i, j) (pixel centres at + 0.5). */
        double x(double i, double j) {
            return cx + ((i - 0.5 * n) * rx + (j - 0.5 * n) * dx) / s;
        }

        double y(double i, double j) {
            return cy + ((i - 0.5 * n) * ry + (j - 0.5 * n) * dy) / s;
        }

        /** The picture's point (i, j) of stack point (x, y). */
        double[] at(double x, double y) {
            double ex = x - cx, ey = y - cy;
            return new double[]{0.5 * n + s * (ex * rx + ey * ry), 0.5 * n + s * (ex * dx + ey * dy)};
        }

        /** The stack's rectangle around the box: x0, y0, x1, y1. */
        double[] bounds() {
            double hx = 0.5 * side * (Math.abs(rx) + Math.abs(dx)), hy = 0.5 * side * (Math.abs(ry) + Math.abs(dy));
            return new double[]{cx - hx, cy - hy, cx + hx, cy + hy};
        }

        boolean turned() {
            return Math.abs(rx - 1) > 1e-9 || Math.abs(dy - 1) > 1e-9;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Making a close-up
    // ------------------------------------------------------------------------------------------------

    /** About how much memory a close-up of t takes while it's made (bytes). */
    public static long heapNeeded(Target t, int frameWidth) {
        if (t.box == null) return 0;
        long np = (long) t.box.n * t.box.n;
        long work = t.drizzled() ? np * (10 * 4 + 2 + 4 * 4) : np * 3 * 4;
        long band = (long) (t.box.side * 1.5 + 80) * frameWidth * 6;
        return work + np * (3 * 4 + 1) * 2 + 2L * OUT * OUT * 4 + (t.drizzled() ? band : 0);
    }

    /**
     * The close-up of t: drizzled from the frames {@code src} as the stack {@code stack} was made from them (or cut from the
     * stack when t's box is too big to drizzle, or src is null), developed as the photo with analysis {@code a} and settings
     * {@code p} was.
     */
    public static CloseUp make(Target t, PhotoVideo.Source src, RgbImage stack, Renderer.Analysis a, Renderer.Params p, Progress progress)
            throws IOException {
        long t0 = System.currentTimeMillis();
        Box b = t.box;
        CloseUp cu = new CloseUp(t.name, t.kind);
        boolean drizzle = b.s > 1 && src != null && !src.trails() && src.model != null && src.plans != null && !src.plans.isEmpty();
        Box used = drizzle ? b : new Box(b.cx, b.cy, b.side, b.s, b.turned() ? new double[]{-b.dx, -b.dy} : null);
        long[] counts = new long[3];
        RgbImage img = drizzle ? drizzle(src, stack, a, used, progress, counts) : fromStack(stack, a, used);
        // how the stars' sizes came out against the stack's own at the same place and scale
        RgbImage up = drizzle ? fromStack(stack, a, used) : null;
        cu.starSigma = starSigma(img, a) / used.s;
        cu.stackStarSigma = up != null ? starSigma(up, a) / used.s : cu.starSigma;
        up = null;
        // the stars taken apart from the glow and drawn back clean and round: smaller around a galaxy or a nebula
        boolean glow = t.kind == 'g' || t.kind == 'n';
        double[] o = used.at(t.px, t.py);
        double or = 0.5 * t.sizeDeg * t.pxPerDeg * used.s;
        double rIn = Math.min(Math.max(3, or), 0.35 * used.n);
        double before = glow ? objectLight(img, a, o[0], o[1], rIn) : 0;
        float sgImg = cu.starSigma > 0 ? (float) (cu.starSigma * used.s) : 1.2f * used.s;
        // a galaxy's middle (and a ball of stars', too crowded to tell apart) stays as it is
        double keep = glow ? Math.max(4 * sgImg, 0.15 * or) : t.kind == 'c' ? 0.3 * or : 0;
        int[] left = new int[4];
        byte[] notStar = new byte[used.n * used.n];
        int redrawn = redrawStars(img, a, sgImg, glow ? SHRINK_GLOW : SHRINK, o[0], o[1], keep, left, notStar);
        cu.starsRedrawn = redrawn;
        cu.smallerStarSigma = starSigma(img, a) / used.s;
        if (glow) cu.objectKept = before > 0 ? objectLight(img, a, o[0], o[1], rIn) / before : Double.NaN;
        String reduced = String.format(Locale.US, "%d stars drawn again at %.0f%% of their size (%d of them beside light kept as it was; left "
                + "as they were: %d clipped, %d wider than a star, %d in its middle)", redrawn, 100 * (glow ? SHRINK_GLOW : SHRINK), left[3],
                left[0], left[1], left[2]);
        Renderer.Analysis ra = regionAnalysis(a, used, img);
        float[] nz = noise(img, ra);
        float noise = nz[0];
        float ns = Math.max(1f, a.noiseY > 0 ? noise / a.noiseY : 1f);
        // the colours' own noise: the drizzle's red and blue drops are fewer than green's
        ra.noiseS = Math.max(a.noiseS, nz[1] / ns);
        ra.noiseP = Math.max(a.noiseP, nz[2] / ns);
        Renderer.Params q = closeUpParams(p);
        // the stars drawn again are as sharp as they'll be
        if (redrawn > 0) q.sharp = 0f;
        // matched with its own star images: enlarged, they're bigger than the photo's
        double sg = cu.smallerStarSigma > 0 ? cu.smallerStarSigma : cu.starSigma;
        float psf = (float) (sg > 0 ? sg * used.s : 1.2 * used.s);
        Denoise.Result dn = q.lumaNr > 0 || q.sharp > 0 ? Denoise.enlarged(img, ra, ns, psf, notStar) : null;
        notStar = null;
        int[] px = Renderer.renderRegion(img, ra, a, q, ns, dn);
        img = null;
        cu.argb = resample(px, used.n, used.n, OUT, OUT);
        cu.size = OUT;
        double k = OUT / (double) used.side;
        cu.moonPx = (float) (MOON_DEG * t.pxPerDeg * k);
        if (!t.markPx.isEmpty()) marks(cu, t, used, k / used.s);
        if (t.moons != null) moons(cu, t.moons);
        words(cu, t, src != null ? src.frames() : 0, drizzle);
        cu.note = String.format(Locale.US, "%s: %s %d px box at (%.0f, %.0f)%s, %s; stars sigma %.2f px (the stack's %.2f)%s%s, "
                        + "noise %.4f (%.1fx the photo's), %d ms", t.name, drizzle ? "drizzled at " + used.s + "x from the frames," : "cut from the stack,",
                used.side, used.cx, used.cy, used.turned() ? " turned north up" : "",
                drizzle ? String.format(Locale.US, "%d frame samples, %d left out", counts[0], counts[1]) : "no drizzle",
                cu.starSigma, cu.stackStarSigma, Double.isNaN(t.snr) ? "" : String.format(Locale.US, ", %.0f sigma over the sky", t.snr),
                reduced.isEmpty() ? "" : ", " + reduced, noise, ns, System.currentTimeMillis() - t0);
        return cu;
    }

    /** The photo's settings for a close-up: the same, without the stars' glow (it's the photo's, sized for the whole picture). */
    static Renderer.Params closeUpParams(Renderer.Params p) {
        Renderer.Params q = new Renderer.Params();
        q.brightness = p.brightness;
        q.saturation = p.saturation;
        q.lumaNr = p.lumaNr;
        q.detail = p.detail;
        q.glow = 0f;
        q.sharp = p.sharp;
        q.removeGradient = p.removeGradient;
        q.stretch = p.stretch;
        q.ev = p.ev;
        q.black = p.black;
        q.look = p.look;
        return q;
    }

    /** The marked points (a planet and its moons) where they are in the close-up, k output pixels per picture pixel of the box. */
    private static void marks(CloseUp cu, Target t, Box b, double k) {
        cu.marks = new float[2 * t.markPx.size()];
        cu.markNames = t.markNames.toArray(new String[0]);
        for (int i = 0; i < t.markPx.size(); i++) {
            double[] p = t.markPx.get(i);
            double[] o = b.at(p[0], p[1]);
            cu.marks[2 * i] = (float) (o[0] * k);
            cu.marks[2 * i + 1] = (float) (o[1] * k);
        }
    }

    /**
     * Jupiter's moons for the close-up's diagram (2.4): which way along the close-up their line runs, from the moon farthest
     * out (marked where it is: right of Jupiter or left of it), and each one's place along it.
     */
    static void moons(CloseUp cu, DeepSpace.Moon[] moons) {
        if (cu.marks.length < 2) return;
        float flip = 1;
        double far = 0;
        for (DeepSpace.Moon m : moons) {
            for (int i = 1; i < cu.markNames.length && 2 * i + 1 < cu.marks.length; i++) {
                if (!cu.markNames[i].equals(m.name) || Math.abs(m.x) <= far) continue;
                far = Math.abs(m.x);
                flip = (cu.marks[2 * i] - cu.marks[0]) * m.x >= 0 ? 1 : -1;
            }
        }
        cu.moonX = new float[moons.length];
        cu.moonNames = new String[moons.length];
        cu.moonState = new byte[moons.length];
        for (int i = 0; i < moons.length; i++) {
            cu.moonX[i] = (float) (flip * moons[i].x);
            cu.moonNames[i] = moons[i].name;
            cu.moonState[i] = (byte) (!moons[i].onDisc() ? 0 : moons[i].front ? 1 : 2);
        }
    }

    /** What the close-up says: its title, what it is, how far, how big, its fact and how it was made. */
    private static void words(CloseUp cu, Target t, int frames, boolean drizzled) {
        cu.title = capital(t.name);
        cu.what = what(t.kind);
        if (t.kind == 'p') {
            double km = t.distAu * 1.495978707e8, min = t.distAu * 499.004784 / 60;
            cu.distance = String.format(Locale.US, "%s km away: its light took %s to get here", big(km), minutes(min));
        } else {
            cu.distance = distance(t.distLy);
        }
        double field = t.side() / Math.max(1e-6, t.pxPerDeg);
        cu.width = String.format(Locale.US, "%.1f degrees of sky, %s full Moons side by side", field, moons(field / MOON_DEG));
        cu.fact = t.fact != null ? t.fact : "";
        cu.how = drizzled ? String.format(Locale.US, "Drizzled at %dx from %d frames, the trick the Hubble Deep Field used", ZOOM, frames)
                : String.format(Locale.US, "Cut from the %d-frame stack at full detail", frames);
    }

    static String what(char kind) {
        switch (kind) {
            case 'g':
                return "a galaxy";
            case 'o':
                return "a star cluster";
            case 'c':
                return "a ball of stars (a globular cluster)";
            case 'n':
                return "a nebula, a cloud of glowing gas";
            case 's':
                return "a star cloud";
            default:
                return "a planet";
        }
    }

    /** How far away, and when the light set off ("444 light-years away: this light left it around the year 1582"). */
    static String distance(double ly) {
        if (!(ly > 0)) return "";
        int year = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).get(java.util.Calendar.YEAR);
        String far = ly >= 1e6 ? String.format(Locale.US, "%s million light-years away", trim(ly / 1e6))
                : String.format(Locale.US, "%s light-years away", big(ly));
        if (ly >= 3e5) {
            return far + ": this light left it " + (ly >= 1e6 ? trim(ly / 1e6) + " million" : big(ly)) + " years ago, before there were any people";
        }
        if (ly >= 1e4) return far + ": this light left it " + big(ly) + " years ago, in the Ice Age";
        long when = Math.round(year - ly);
        return far + ": this light left it around " + (when > 0 ? "the year " + when : (-when) + " BC");
    }

    private static String trim(double v) {
        return v >= 10 ? String.format(Locale.US, "%.0f", v) : String.format(Locale.US, "%.1f", v).replace(".0", "");
    }

    /** A number in words a child reads easily: "2,600", "12 million", "778 million". */
    static String big(double v) {
        if (v >= 1e9) return trim(v / 1e9) + " billion";
        if (v >= 1e6) return trim(v / 1e6) + " million";
        long r = v >= 1000 ? Math.round(v / 100.0) * 100 : Math.round(v);
        return String.format(Locale.US, "%,d", r);
    }

    private static String minutes(double min) {
        if (min >= 90) return String.format(Locale.US, "%.1f hours", min / 60).replace(".0 ", " ");
        return String.format(Locale.US, "%.0f minutes", min);
    }

    private static String moons(double n) {
        if (n < 1.5) return "about 1";
        return n < 10 ? String.format(Locale.US, "about %.0f", n) : String.format(Locale.US, "about %d", Math.round(n));
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------------------------------------
    // The card
    // ------------------------------------------------------------------------------------------------

    /**
     * Draws a close-up's words onto its picture (already in {@code p}'s canvas, {@link CloseUp#size} each way): its name and
     * what it is at the top, how far, how big and its fact along the bottom over a dark fade, the full Moon's size as a
     * circle for scale, and a planet's moons named where they are.
     */
    public static void card(CloseUp cu, Painter p) {
        float S = cu.size, m = 0.045f * S;
        int white = 0xFFFFFFFF, soft = 0xFFCFD8E8, warm = 0xFFFFE3A3, blue = 0xFFAFD3FF, grey = 0xFFA4B0C2;
        p.gradient(0, 0, S, 0.2f * S, 0xB0000000, 0x00000000);
        p.gradient(0, 0.58f * S, S, S, 0x00000000, 0xD8000000);
        float ts = 0.05f * S;
        // a long name gets smaller to fit
        float tw = p.width(cu.title, ts, true);
        if (tw > S - 2 * m) ts *= (S - 2 * m) / tw;
        p.text(cu.title, m, m + ts, ts, true, white);
        p.text(cu.what, m, m + ts + 0.042f * S, 0.03f * S, false, soft);
        String brand = "Night Sky \u00b7 Deep Space Zoom";
        float bs = 0.022f * S;
        if (p.width(brand, bs, false) < S - 2 * m - p.width(cu.title, ts, true) - 0.04f * S || cu.title.length() < 18) {
            p.text(brand, S - m - p.width(brand, bs, false), m + 0.6f * ts, bs, false, 0x99FFFFFF);
        }
        marks(cu, p, S, m);
        moonDiagram(cu, p, S, m);
        // along the bottom, from the last line up
        float y = S - m;
        p.text(cu.how, m, y, 0.022f * S, false, grey);
        y -= 0.022f * S * 1.9f;
        float fs = 0.028f * S;
        boolean moon = cu.moonPx >= 10 && cu.moonPx <= 0.22f * S;
        float textW = S - 2 * m - (moon ? cu.moonPx + 0.06f * S : 0);
        List<String> fact = wrap(cu.fact, textW, fs, false, p);
        for (int i = fact.size() - 1; i >= 0; i--) {
            p.text(fact.get(i), m, y, fs, false, white);
            y -= fs * 1.3f;
        }
        y -= fs * 0.3f;
        float ds = 0.028f * S;
        List<String> dist = wrap(cu.distance, textW, ds, true, p);
        for (int i = dist.size() - 1; i >= 0; i--) {
            p.text(dist.get(i), m, y, ds, true, warm);
            y -= ds * 1.3f;
        }
        float ws = 0.025f * S;
        List<String> wid = wrap(cu.width, textW, ws, false, p);
        for (int i = wid.size() - 1; i >= 0; i--) {
            p.text(wid.get(i), m, y, ws, false, blue);
            y -= ws * 1.3f;
        }
        if (moon) {
            float r = cu.moonPx / 2, cx = S - m - r, cy = S - m - 0.05f * S - r;
            p.disc(cx, cy, r, 0x30FFFFFF);
            p.ring(cx, cy, r, Math.max(1.5f, 0.002f * S), 0xCCFFFFFF);
            String l = "Full Moon";
            float ls = 0.022f * S;
            p.text(l, cx - p.width(l, ls, false) / 2, cy + r + ls * 1.4f, ls, false, soft);
        }
    }

    /**
     * The planet ringed and named where it is; and its moons (2.4) far enough out from it to tell apart in small rings of
     * their own (the diagram above names them all, in the same order across the picture).
     */
    static void marks(CloseUp cu, Painter p, float S, float m) {
        int n = Math.min(cu.marks.length / 2, cu.markNames.length);
        if (n == 0) return;
        float lw = Math.max(1.5f, 0.0025f * S);
        int ring = 0xCC9FE3FF;
        float px = cu.marks[0], py = cu.marks[1], pr = 0.03f * S, ns = 0.026f * S;
        if (px < 0 || py < 0 || px >= S || py >= S) return;
        p.ring(px, py, pr, lw, ring);
        String name = cu.markNames[0];
        float nw = p.width(name, ns, true), lx = px + pr + 0.01f * S;
        if (lx + nw > S - m) lx = px - pr - 0.01f * S - nw;
        p.text(name, lx, py - pr, ns, true, 0xFFE7F6FF);
        float mr = Math.max(4f, 0.01f * S);
        for (int i = 1; i < n; i++) {
            float x = cu.marks[2 * i], y = cu.marks[2 * i + 1];
            if (Math.hypot(x - px, y - py) > pr + 2.2f * mr && x >= 0 && y >= 0 && x < S && y < S) p.ring(x, y, mr, lw, ring);
        }
    }

    /**
     * Jupiter's moons tonight as a telescope shows them (2.4): a line with Jupiter in the middle and each moon where it is
     * along it, named, the same way round as the close-up. A phone's pixel is wider than most of their distances from
     * Jupiter, so the diagram says what the photo can't quite.
     */
    static void moonDiagram(CloseUp cu, Painter p, float S, float m) {
        int n = cu.moonNames.length;
        if (n == 0) return;
        float y0 = 0.255f * S, cx = S / 2f, unit = (S / 2f - m - 0.03f * S) / 27f, jr = 0.012f * S, dr = Math.max(2.5f, 0.0045f * S);
        p.gradient(0, 0.17f * S, S, 0.33f * S, 0x70000000, 0x00000000);
        p.text("Its big moons tonight, as a telescope shows them:", m, 0.2f * S, 0.022f * S, false, 0xFFA4B0C2);
        p.line(m, y0, S - m, y0, Math.max(1f, 0.0012f * S), 0x40CFE8FF);
        p.disc(cx, y0, jr, 0xFFF3E3C8);
        // the names above and below in turn where they'd run into each other
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) ord[i] = i;
        final float[] xs = cu.moonX;
        Arrays.sort(ord, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Float.compare(xs[a], xs[b]);
            }
        });
        float ns = 0.022f * S, aboveEnd = -1e9f, belowEnd = -1e9f;
        List<String> hidden = new ArrayList<String>();
        for (int i : ord) {
            if (cu.moonState[i] == 2) {
                hidden.add(cu.moonNames[i]);
                continue;
            }
            float x = cx + xs[i] * unit;
            p.disc(x, y0, dr, cu.moonState[i] == 1 ? 0xFF2A2F3A : 0xFFFFFFFF);
            if (cu.moonState[i] == 1) p.ring(x, y0, dr, Math.max(1f, 0.0012f * S), 0xFFFFFFFF);
            String name = cu.moonNames[i] + (cu.moonState[i] == 1 ? " (in front)" : "");
            float w = p.width(name, ns, false), left = Math.max(m, Math.min(S - m - w, x - w / 2)), gap = 0.012f * S;
            // above where there's room, else below, else on the side with more room, moved along
            boolean up = left > aboveEnd + gap || !(left > belowEnd + gap) && aboveEnd <= belowEnd;
            left = Math.max(left, (up ? aboveEnd : belowEnd) + gap);
            if (up) {
                p.text(name, left, y0 - dr - 0.012f * S, ns, false, 0xFFE7F6FF);
                aboveEnd = left + w;
            } else {
                p.text(name, left, y0 + dr + 0.012f * S + ns * 0.8f, ns, false, 0xFFE7F6FF);
                belowEnd = left + w;
            }
        }
        if (!hidden.isEmpty()) {
            String h = list(hidden) + (hidden.size() == 1 ? " is" : " are") + " behind Jupiter";
            p.text(h, S - m - p.width(h, 0.02f * S, false), y0 + 0.055f * S, 0.02f * S, false, 0xFFA4B0C2);
        }
    }

    private static String list(List<String> s) {
        if (s.size() == 1) return s.get(0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.size(); i++) b.append(i == 0 ? "" : i == s.size() - 1 ? " and " : ", ").append(s.get(i));
        return b.toString();
    }

    /** {@code text} broken into lines no wider than {@code width}, at whole words. */
    static List<String> wrap(String text, float width, float size, boolean bold, Painter p) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.isEmpty()) return out;
        StringBuilder line = new StringBuilder();
        for (String w : text.split(" ")) {
            String t = line.length() == 0 ? w : line + " " + w;
            if (line.length() > 0 && p.width(t, size, bold) > width) {
                out.add(line.toString());
                line.setLength(0);
                line.append(w);
            } else {
                line.setLength(0);
                line.append(t);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // The drizzle
    // ------------------------------------------------------------------------------------------------

    /**
     * The box drizzled from the frames at {@code b.s}x: per output pixel and colour, the weighted mean of the drops of that
     * colour's photosites that fall on it (each frame's own weight and normalisation, as the stack had them), moved
     * towards the frames' interpolated light where too few drops fell, and the stack's own light where it clipped or a
     * streak was kept. In the stack's units (the lens shading's correction in), with the stack's clip flags.
     * {@code counts}: samples taken and left out.
     */
    static RgbImage drizzle(final PhotoVideo.Source src, final RgbImage stack, final Renderer.Analysis a, final Box b, final Progress progress,
                            final long[] counts) throws IOException {
        final int n = b.n, np = n * n;
        final int W = src.spec.width, H = src.spec.height;
        final float[] s2 = new float[np * 3], s3 = new float[np * 3], s4 = new float[np * 3], s5 = new float[np];
        final short[] cnt = new short[np];
        final int[] planes = planes(src.spec);
        final int pR = planes[0], pG1 = planes[1], pG2 = planes[2], pB = planes[3];
        final Gains gains = new Gains(src.spec.shading, b, pR, pG1, pG2, pB);
        // the finished stack before the lens shading's correction, and the sky's level, at every output pixel
        final float[] fin = new float[np * 3], bgG = new float[np];
        Parallel.forRange(n, 8, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] v = new float[3], g = new float[3];
                for (int j = j0; j < j1; j++) {
                    for (int i = 0; i < n; i++) {
                        double X = b.x(i + 0.5, j + 0.5), Y = b.y(i + 0.5, j + 0.5);
                        stackAt(stack, X, Y, v);
                        gains.at(i, j, g);
                        int k = (j * n + i) * 3;
                        fin[k] = v[0] / g[0];
                        fin[k + 1] = v[1] / g[1];
                        fin[k + 2] = v[2] / g[2];
                        bgG[j * n + i] = src.target != null ? src.target.level(1, X, Y) : 0f;
                    }
                }
            }
        });
        final ChromaShift.Model cm = src.chroma;
        final boolean ca = cm != null && cm.largest() > 0 && cm.largest() <= 2.5;
        final float caX = ca ? cm.cx : 0, caY = ca ? cm.cy : 0, caInvR2 = ca ? 1f / (cm.halfDiagonal * cm.halfDiagonal) : 0;
        final float caAr = ca ? cm.aR : 0, caBr = ca ? cm.bR : 0, caAb = ca ? cm.aB : 0, caBb = ca ? cm.bB : 0;
        final double[] nodeB = WarpGrid.nodeBearings(src.model, W, H, STEP);
        final int nx = WarpGrid.nodesX(W, STEP), ny = WarpGrid.nodesY(H, STEP);
        final float[] nodes = new float[nx * ny * NV];
        final long[] maskBits = new long[(np + 63) >> 6], keepBits = new long[(np + 63) >> 6];
        final float minT = src.minTransparency;
        // a drop's overlap with an output pixel (1/s px wide) as a share of the output pixel
        final float a2 = b.s * b.s, half = 0.5f * (1f / b.s + PIXFRAC), lim = Math.min(1f / b.s, PIXFRAC);
        final double[] bb = b.bounds();
        short[] raw = new short[0];
        float[] d = new float[0];
        final long[] used = new long[2];
        int f = 0;
        for (final SharpStacker.Plan p : src.plans) {
            f++;
            final WarpGrid g = WarpGrid.build(src.model, nodeB, W, H, STEP, p.R);
            PhotoVideo.nodeValues(src, p, g, nx, ny, nodes);
            final boolean anyMask = corridors(p.masks, b, maskBits, false);
            corridors(p.keep, b, keepBits, true);
            // the frame's rows the box maps to: around the box's edge (the map is smooth, so its extremes are there)
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            float[] w2 = new float[2];
            for (int e = 0; e <= 64; e++) {
                double t = e / 64.0;
                double[][] pts = {{bb[0] + t * (bb[2] - bb[0]), bb[1]}, {bb[0] + t * (bb[2] - bb[0]), bb[3]},
                        {bb[0], bb[1] + t * (bb[3] - bb[1])}, {bb[2], bb[1] + t * (bb[3] - bb[1])}};
                for (double[] q : pts) {
                    if (!warpAt(g, q[0], q[1], w2)) continue;
                    lo = Math.min(lo, w2[1]);
                    hi = Math.max(hi, w2[1]);
                }
            }
            if (lo > hi) continue;
            int rs = Math.max(0, (int) Math.floor(lo) - 8) & ~1, re = Math.min(H, (int) Math.ceil(hi) + 9);
            if (re - rs < 8) continue;
            int need = (re - rs) * W;
            if (raw.length < need) {
                raw = new short[need];
                d = new float[need];
            }
            final FrameStore fs = p.frames != null ? p.frames : src.store;
            final HotPixels hp = p.hot != null ? p.hot : src.hot;
            PhotoVideo.load(W, fs, hp, p.unit, p.invScale, p.black, p.pattern, rs, re, raw, d);
            final float[] dd = d;
            final int frs = rs, fre = re;
            Parallel.forRange(n, 4, new Parallel.Range() {
                @Override
                public void run(int j0, int j1) {
                    float[] nv = new float[NV], mxy = new float[2], t = new float[2];
                    long took = 0, left = 0;
                    for (int j = j0; j < j1; j++) {
                        for (int i = 0; i < n; i++) {
                            int pix = j * n + i;
                            if (anyMask && bit(maskBits, pix)) continue;
                            double X = b.x(i + 0.5, j + 0.5), Y = b.y(i + 0.5, j + 0.5);
                            if (!warpAt(g, X, Y, mxy)) continue;
                            float mx = mxy[0], my = mxy[1];
                            if (!(mx >= 4f && my >= 4f && mx < W - 4f && my < H - 4f)) continue;
                            if (my - 4f < frs || my + 4f > fre) continue;
                            nodesAt(nodes, nx, ny, X, Y, nv);
                            if (nv[N_T] < minT) continue;
                            float w = nv[N_W];
                            if (!(w > 0)) continue;
                            if (src.ground != null && src.ground.nearAt(mx, my)) continue;
                            float rxp = mx, ryp = my, bxp = mx, byp = my;
                            if (ca) {
                                float ux = mx - caX, uy = my - caY, q = (ux * ux + uy * uy) * caInvR2;
                                float kr = caAr + caBr * q, kb = caAb + caBb * q;
                                rxp += ux * kr;
                                ryp += uy * kr;
                                bxp += ux * kb;
                                byp += uy * kb;
                            }
                            float sc = nv[N_SC];
                            // the frame's light here, interpolated from each colour's photosites
                            float r = bilinear(dd, frs, W, pR, rxp, ryp);
                            float gg = bilinear(dd, frs, W, pG1, mx, my);
                            if (pG2 != pG1) gg = 0.5f * (gg + bilinear(dd, frs, W, pG2, mx, my));
                            float bl = bilinear(dd, frs, W, pB, bxp, byp);
                            float v0 = r * sc + nv[N_OFF], v1 = gg * sc + nv[N_OFF + 1], v2 = bl * sc + nv[N_OFF + 2];
                            int k3 = pix * 3;
                            float cen = fin[k3 + 1];
                            if (v1 - cen > CLIP_K * nv[N_SG] + CLIP_SHARE * Math.max(0f, cen - bgG[pix])) {
                                left++;
                                continue;
                            }
                            took++;
                            s4[k3] += w * v0;
                            s4[k3 + 1] += w * v1;
                            s4[k3 + 2] += w * v2;
                            s5[pix] += w;
                            if (cnt[pix] < Short.MAX_VALUE) cnt[pix]++;
                            // the drops: each colour's nearest photosite, by how much of the output pixel its drop covers
                            drop(dd, frs, W, pR, rxp, ryp, half, lim, a2, t);
                            if (t[1] > 0) {
                                s2[k3] += w * t[1] * (t[0] * sc + nv[N_OFF]);
                                s3[k3] += w * t[1];
                            }
                            drop(dd, frs, W, pG1, mx, my, half, lim, a2, t);
                            if (t[1] > 0) {
                                s2[k3 + 1] += w * t[1] * (t[0] * sc + nv[N_OFF + 1]);
                                s3[k3 + 1] += w * t[1];
                            }
                            if (pG2 != pG1) {
                                drop(dd, frs, W, pG2, mx, my, half, lim, a2, t);
                                if (t[1] > 0) {
                                    s2[k3 + 1] += w * t[1] * (t[0] * sc + nv[N_OFF + 1]);
                                    s3[k3 + 1] += w * t[1];
                                }
                            }
                            drop(dd, frs, W, pB, bxp, byp, half, lim, a2, t);
                            if (t[1] > 0) {
                                s2[k3 + 2] += w * t[1] * (t[0] * sc + nv[N_OFF + 2]);
                                s3[k3 + 2] += w * t[1];
                            }
                        }
                    }
                    synchronized (used) {
                        used[0] += took;
                        used[1] += left;
                    }
                }
            });
            if (progress != null) {
                if (progress.cancelled()) throw new CancelledException();
                progress.update("Deep Space Zoom", f / (float) src.plans.size());
            }
        }
        counts[0] = used[0];
        counts[1] = used[1];
        // the drizzle's mean where enough drops fell, the interpolated mean where they didn't, the stack where neither
        final RgbImage out = new RgbImage(n, n);
        out.clip = new byte[np];
        out.bracketed = stack.bracketed;
        out.x0 = 0;
        out.y0 = 0;
        out.x1 = n;
        out.y1 = n;
        Parallel.forRange(n, 8, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] g = new float[3], v = new float[3], ci = new float[3];
                for (int j = j0; j < j1; j++) {
                    for (int i = 0; i < n; i++) {
                        int pix = j * n + i, k3 = pix * 3;
                        double X = b.x(i + 0.5, j + 0.5), Y = b.y(i + 0.5, j + 0.5);
                        gains.at(i, j, g);
                        int c0 = cnt[pix];
                        float sw = s5[pix];
                        for (int c = 0; c < 3; c++) {
                            float val;
                            if (c0 > 0 && sw > 0) {
                                val = s4[k3 + c] / sw;
                                float w3 = s3[k3 + c];
                                if (w3 > 0) {
                                    float eff = w3 / sw * c0;
                                    float t = Math.min(1f, eff * 0.5f);
                                    val += t * (s2[k3 + c] / w3 - val);
                                }
                                if (c0 < FULL_FRAMES) val = fin[k3 + c] + (c0 / (float) FULL_FRAMES) * (val - fin[k3 + c]);
                            } else {
                                val = fin[k3 + c];
                            }
                            v[c] = val * g[c];
                        }
                        // where the stack clipped (or a short frame gave a bright star its light) and in a kept streak:
                        // the stack's own light
                        float cf = clipAt(stack, X, Y);
                        StarHalo.clipInvAt(a, (float) (X - a.cx0), (float) (Y - a.cy0), ci);
                        float hot = Math.max(fin[k3] * g[0] * ci[0], Math.max(fin[k3 + 1] * g[1] * ci[1], fin[k3 + 2] * g[2] * ci[2]));
                        float tk = Math.max(smooth(hot, 0.75f, 0.95f), smooth(cf / 255f, 0.02f, 0.2f));
                        if (bit(keepBits, pix)) tk = 1f;
                        if (tk > 0f) {
                            for (int c = 0; c < 3; c++) v[c] += tk * (fin[k3 + c] * g[c] - v[c]);
                        }
                        out.r[pix] = v[0];
                        out.g[pix] = v[1];
                        out.b[pix] = v[2];
                        out.clip[pix] = (byte) Math.round(cf);
                    }
                }
            }
        });
        return out;
    }

    /** The box cut from the stack (bilinear between its pixels, the clip flags of the nearest), at the box's scale. */
    static RgbImage fromStack(final RgbImage stack, Renderer.Analysis a, final Box b) {
        final int n = b.n;
        final RgbImage out = new RgbImage(n, n);
        out.clip = new byte[n * n];
        out.bracketed = stack.bracketed;
        out.x1 = n;
        out.y1 = n;
        Parallel.forRange(n, 8, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] v = new float[3];
                for (int j = j0; j < j1; j++) {
                    for (int i = 0; i < n; i++) {
                        double X = b.x(i + 0.5, j + 0.5), Y = b.y(i + 0.5, j + 0.5);
                        stackAt(stack, X, Y, v);
                        int pix = j * n + i;
                        out.r[pix] = v[0];
                        out.g[pix] = v[1];
                        out.b[pix] = v[2];
                        out.clip[pix] = (byte) Math.round(clipAt(stack, X, Y));
                    }
                }
            }
        });
        return out;
    }

    /** The stack's light at continuous point (x, y), bilinear between pixel centres. */
    static void stackAt(RgbImage s, double x, double y, float[] out) {
        double fx = x - 0.5, fy = y - 0.5;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = (float) (fx - x0), ty = (float) (fy - y0);
        int xa = Stats.clamp(x0, 0, s.width - 1), xb = Stats.clamp(x0 + 1, 0, s.width - 1);
        int ya = Stats.clamp(y0, 0, s.height - 1), yb = Stats.clamp(y0 + 1, 0, s.height - 1);
        int i00 = ya * s.width + xa, i01 = ya * s.width + xb, i10 = yb * s.width + xa, i11 = yb * s.width + xb;
        for (int c = 0; c < 3; c++) {
            float[] ch = s.channel(c);
            float top = ch[i00] + tx * (ch[i01] - ch[i00]), bot = ch[i10] + tx * (ch[i11] - ch[i10]);
            out[c] = top + ty * (bot - top);
        }
    }

    /** The stack's clip flags (0..255) at continuous point (x, y), bilinear between pixel centres, so a clipped core stays round. */
    static float clipAt(RgbImage s, double x, double y) {
        if (s.clip == null) return 0f;
        double fx = x - 0.5, fy = y - 0.5;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = (float) (fx - x0), ty = (float) (fy - y0);
        int xa = Stats.clamp(x0, 0, s.width - 1), xb = Stats.clamp(x0 + 1, 0, s.width - 1);
        int ya = Stats.clamp(y0, 0, s.height - 1), yb = Stats.clamp(y0 + 1, 0, s.height - 1);
        float a = s.clip[ya * s.width + xa] & 0xFF, b = s.clip[ya * s.width + xb] & 0xFF;
        float c = s.clip[yb * s.width + xa] & 0xFF, d = s.clip[yb * s.width + xb] & 0xFF;
        float top = a + tx * (b - a), bot = c + tx * (d - c);
        return top + ty * (bot - top);
    }

    /** The planes of red, the two greens and blue in the sensor's 2 x 2 pattern. */
    static int[] planes(SensorSpec spec) {
        int r = -1, g1 = -1, g2 = -1, b = -1;
        for (int p = 0; p < 4; p++) {
            int c = spec.cfa[p];
            if (c == 0 && r < 0) r = p;
            else if (c == 2 && b < 0) b = p;
            else if (c == 1) {
                if (g1 < 0) g1 = p;
                else g2 = p;
            }
        }
        int pg1 = g1 < 0 ? 1 : g1;
        return new int[]{r < 0 ? 0 : r, pg1, g2 < 0 ? pg1 : g2, b < 0 ? 3 : b};
    }

    /** The frame position of reference point (x, y), bilinear between the warp grid's nodes; false where it doesn't map. */
    static boolean warpAt(WarpGrid g, double x, double y, float[] out) {
        double gx = x / g.step, gy = y / g.step;
        int ix = (int) Math.floor(gx), iy = (int) Math.floor(gy);
        if (ix < 0 || iy < 0 || ix > g.nx - 2 || iy > g.ny - 2) return false;
        float tx = (float) (gx - ix), ty = (float) (gy - iy);
        int k = iy * g.nx + ix;
        float a = g.mx[k], b = g.mx[k + 1], c = g.mx[k + g.nx], d = g.mx[k + g.nx + 1];
        if (a < -1e5f || b < -1e5f || c < -1e5f || d < -1e5f) return false;
        float e = g.my[k], f = g.my[k + 1], h = g.my[k + g.nx], q = g.my[k + g.nx + 1];
        out[0] = (a + tx * (b - a)) + ty * ((c + tx * (d - c)) - (a + tx * (b - a)));
        out[1] = (e + tx * (f - e)) + ty * ((h + tx * (q - h)) - (e + tx * (f - e)));
        return true;
    }

    /** A frame's normalisation at reference point (x, y), bilinear between the nodes ({@link PhotoVideo#nodeValues}). */
    static void nodesAt(float[] a, int nx, int ny, double x, double y, float[] out) {
        double gx = x / STEP, gy = y / STEP;
        int ix = Math.max(0, Math.min(nx - 2, (int) Math.floor(gx))), iy = Math.max(0, Math.min(ny - 2, (int) Math.floor(gy)));
        float tx = (float) (gx - ix), ty = (float) (gy - iy);
        int k00 = (iy * nx + ix) * NV, k01 = k00 + NV, k10 = k00 + nx * NV, k11 = k10 + NV;
        for (int v = 0; v < NV; v++) {
            float top = a[k00 + v] + tx * (a[k01 + v] - a[k00 + v]), bot = a[k10 + v] + tx * (a[k11 + v] - a[k10 + v]);
            out[v] = top + ty * (bot - top);
        }
    }

    /** Plane p at frame point (x, y), bilinear between its photosites (each at its own pixel's centre). */
    static float bilinear(float[] d, int rs, int W, int p, float x, float y) {
        int ox = p & 1, oy = p >> 1;
        float fx = (x - 0.5f - ox) * 0.5f, fy = (y - 0.5f - oy) * 0.5f;
        int iu = (int) Math.floor(fx), iv = (int) Math.floor(fy);
        float ax = fx - iu, ay = fy - iv;
        int i = (2 * iv + oy - rs) * W + 2 * iu + ox;
        float d00 = d[i], d01 = d[i + 2], d10 = d[i + 2 * W], d11 = d[i + 2 * W + 2];
        return (1f - ay) * (d00 + ax * (d01 - d00)) + ay * (d10 + ax * (d11 - d10));
    }

    /**
     * The drop of plane p's photosite nearest frame point (x, y): its value into t[0], and into t[1] the share of the output
     * pixel (1/s stack px wide, centred there) that its drop ({@link #PIXFRAC} px wide) covers.
     */
    static void drop(float[] d, int rs, int W, int p, float x, float y, float half, float lim, float a2, float[] t) {
        int ox = p & 1, oy = p >> 1;
        float fx = (x - 0.5f - ox) * 0.5f, fy = (y - 0.5f - oy) * 0.5f;
        int iu = Math.round(fx), iv = Math.round(fy);
        float cx = 2 * iu + ox + 0.5f, cy = 2 * iv + oy + 0.5f;
        float wx = Math.min(lim, half - Math.abs(x - cx)), wy = Math.min(lim, half - Math.abs(y - cy));
        if (wx <= 0 || wy <= 0) {
            t[1] = 0;
            return;
        }
        t[0] = d[(2 * iv + oy - rs) * W + 2 * iu + ox];
        t[1] = wx * wy * a2;
    }

    /**
     * Sets the bits of the box's output pixels within the corridors {@code c} (x0, y0, x1, y1, half width each, stack
     * coordinates); false (and, unless {@code add}, nothing set) when there are none.
     */
    static boolean corridors(float[] c, Box b, long[] bits, boolean add) {
        if (!add) Arrays.fill(bits, 0L);
        if (c == null || c.length < 5) return false;
        boolean any = false;
        double reach = 0.75 / b.s;
        int n = b.n;
        for (int s = 0; s + 4 < c.length; s += 5) {
            double ax = c[s], ay = c[s + 1], bx = c[s + 2], by = c[s + 3], hw = c[s + 4] + reach;
            double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy, hw2 = hw * hw;
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    double px = b.x(i + 0.5, j + 0.5) - ax, py = b.y(i + 0.5, j + 0.5) - ay;
                    double t = l2 > 0 ? Math.max(0, Math.min(1, (px * dx + py * dy) / l2)) : 0;
                    double ex = px - t * dx, ey = py - t * dy;
                    if (ex * ex + ey * ey > hw2) continue;
                    int pix = j * n + i;
                    bits[pix >> 6] |= 1L << (pix & 63);
                    any = true;
                }
            }
        }
        return any;
    }

    private static boolean bit(long[] bits, int pix) {
        return (bits[pix >> 6] & (1L << (pix & 63))) != 0;
    }

    private static float smooth(float x, float a, float b) {
        float t = (x - a) / (b - a);
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * (3 - 2 * t);
    }

    /** The lens shading's gains per colour over a box's picture, worked out every GAIN_STEP pixels and interpolated between. */
    static final class Gains {
        final int gn, n;
        final float[][] g;

        Gains(ShadingMap sh, Box b, int pR, int pG1, int pG2, int pB) {
            n = b.n;
            if (sh == null || sh.isUnity()) {
                gn = 0;
                g = null;
                return;
            }
            gn = n / GAIN_STEP + 2;
            g = new float[3][gn * gn];
            for (int j = 0; j < gn; j++) {
                for (int i = 0; i < gn; i++) {
                    double X = b.x(i * GAIN_STEP + 0.5, j * GAIN_STEP + 0.5), Y = b.y(i * GAIN_STEP + 0.5, j * GAIN_STEP + 0.5);
                    int k = j * gn + i;
                    g[0][k] = sh.gain(pR, X, Y);
                    g[1][k] = pG2 != pG1 ? 0.5f * (sh.gain(pG1, X, Y) + sh.gain(pG2, X, Y)) : sh.gain(pG1, X, Y);
                    g[2][k] = sh.gain(pB, X, Y);
                }
            }
        }

        void at(int i, int j, float[] out) {
            if (g == null) {
                out[0] = out[1] = out[2] = 1f;
                return;
            }
            int a = Math.min(gn - 2, i / GAIN_STEP), c = Math.min(gn - 2, j / GAIN_STEP);
            float tx = (i - a * GAIN_STEP) / (float) GAIN_STEP, ty = (j - c * GAIN_STEP) / (float) GAIN_STEP;
            int k = c * gn + a;
            for (int q = 0; q < 3; q++) {
                float[] v = g[q];
                float top = v[k] + tx * (v[k + 1] - v[k]), bot = v[k + gn] + tx * (v[k + gn + 1] - v[k + gn]);
                out[q] = top + ty * (bot - top);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Developing it as the photo
    // ------------------------------------------------------------------------------------------------

    /**
     * The photo's analysis for a box's picture: the same colours, clip levels and tone (the curve comes from the photo's
     * analysis itself), its background (the photo's quadratic, refitted over the box exactly, and the 2.4 surface sampled
     * at the box's grid), colour windows as much bigger as the picture is. The sky's noise is the photo's: the picture's
     * own goes to the render as a scale.
     */
    static Renderer.Analysis regionAnalysis(Renderer.Analysis a, Box b, RgbImage img) {
        Renderer.Analysis r = Renderer.noisier(a, 1f);
        int n = b.n;
        r.cx0 = 0;
        r.cy0 = 0;
        r.cw = n;
        r.ch = n;
        r.sky = null;
        r.bgNote = "";
        // the quadratic: exact over the box, from 25 of its points (a turn and a shift keep a quadratic a quadratic)
        double[][] A = new double[6][6];
        double[][] rhs = new double[3][6];
        double[] t = new double[6], T = new double[6];
        for (int j = 0; j <= 4; j++) {
            for (int i = 0; i <= 4; i++) {
                double u = -1 + 0.5 * i, v = -1 + 0.5 * j;
                double X = b.x((u + 1) * 0.5 * n, (v + 1) * 0.5 * n), Y = b.y((u + 1) * 0.5 * n, (v + 1) * 0.5 * n);
                double U = (X - a.cx0) / a.cw * 2 - 1, V = (Y - a.cy0) / a.ch * 2 - 1;
                Renderer.terms(u, v, t);
                Renderer.terms(U, V, T);
                for (int p = 0; p < 6; p++) {
                    for (int q = 0; q < 6; q++) A[p][q] += t[p] * t[q];
                    for (int c = 0; c < 3; c++) {
                        double f = 0;
                        for (int k = 0; k < 6; k++) f += a.poly[c][k] * T[k];
                        rhs[c][p] += t[p] * f;
                    }
                }
            }
        }
        r.poly = new double[3][];
        for (int c = 0; c < 3; c++) {
            double[] s = Renderer.solve(copy(A), rhs[c].clone());
            r.poly[c] = s != null ? s : new double[6];
        }
        // the grids: the clip levels' and the residual surface's, at the box's grid nodes
        int CGX = Renderer.CGX, CGY = Renderer.CGY;
        r.clipInv = a.clipInv != null ? new float[a.clipInv.length] : null;
        r.bgRes = a.bgRes != null ? new float[a.bgRes.length] : null;
        float[] o = new float[3];
        for (int j = 0; j < CGY; j++) {
            for (int i = 0; i < CGX; i++) {
                double xr = i * (double) n / (CGX - 1), yr = j * (double) n / (CGY - 1);
                float xc = (float) (b.x(xr, yr) - a.cx0), yc = (float) (b.y(xr, yr) - a.cy0);
                if (r.clipInv != null) {
                    StarHalo.clipInvAt(a, xc, yc, o);
                    for (int c = 0; c < 3; c++) r.clipInv[(c * CGY + j) * CGX + i] = o[c];
                }
                if (r.bgRes != null) {
                    gridAt(a.bgRes, a, xc, yc, o);
                    for (int c = 0; c < 3; c++) r.bgRes[(c * CGY + j) * CGX + i] = o[c];
                }
            }
        }
        r.colorSigma = a.colorSigma * b.s;
        if (a.colorWindows != null) {
            r.colorWindows = new float[3];
            for (int c = 0; c < 3; c++) r.colorWindows[c] = a.colorWindows[c] * b.s;
        }
        return r;
    }

    private static double[][] copy(double[][] m) {
        double[][] c = new double[m.length][];
        for (int i = 0; i < m.length; i++) c[i] = m[i].clone();
        return c;
    }

    /** A grid like {@link Renderer.Analysis#bgRes} ([(c * CGY + j) * CGX + i], nodes over the crop) at crop point (x, y). */
    static void gridAt(float[] grid, Renderer.Analysis a, float x, float y, float[] out) {
        int CGX = Renderer.CGX, CGY = Renderer.CGY;
        float fx = Math.min(CGX - 1.001f, Math.max(0f, x / a.cw * (CGX - 1))), fy = Math.min(CGY - 1.001f, Math.max(0f, y / a.ch * (CGY - 1)));
        int i = (int) fx, j = (int) fy;
        float tx = fx - i, ty = fy - j;
        for (int c = 0; c < 3; c++) {
            int o = c * CGY * CGX;
            float p = grid[o + j * CGX + i], q = grid[o + j * CGX + i + 1], r = grid[o + (j + 1) * CGX + i], s = grid[o + (j + 1) * CGX + i + 1];
            float top = p + tx * (q - p), bot = r + tx * (s - r);
            out[c] = top + ty * (bot - top);
        }
    }

    /**
     * A box's picture's noise as {@link Renderer#analyze} measures a photo's: of the luminance, and of the colour along and
     * across the stars' locus, from differences of pixels 4 apart along every 4th row (the drizzle's drops tie neighbours
     * together; 4 apart they're independent).
     */
    static float[] noise(RgbImage img, Renderer.Analysis a) {
        float[] m = a.m;
        int w = img.width, h = img.height;
        int cap = ((h + 3) / 4) * Math.max(1, w / 4);
        float[] d = new float[cap], ds = new float[cap], dp = new float[cap];
        int k = 0;
        for (int y = 0; y < h; y += 4) {
            for (int x = 0; x + 4 < w && k < cap; x += 4) {
                int i = y * w + x, j = i + 4;
                float r1 = m[0] * img.r[i] + m[1] * img.g[i] + m[2] * img.b[i], g1 = m[3] * img.r[i] + m[4] * img.g[i] + m[5] * img.b[i];
                float b1 = m[6] * img.r[i] + m[7] * img.g[i] + m[8] * img.b[i];
                float r2 = m[0] * img.r[j] + m[1] * img.g[j] + m[2] * img.b[j], g2 = m[3] * img.r[j] + m[4] * img.g[j] + m[5] * img.b[j];
                float b2 = m[6] * img.r[j] + m[7] * img.g[j] + m[8] * img.b[j];
                float y1 = KR * r1 + KG * g1 + KB * b1, y2 = KR * r2 + KG * g2 + KB * b2;
                float dr = (r1 - y1) - (r2 - y2), db = (b1 - y1) - (b2 - y2);
                d[k] = y1 - y2;
                ds[k] = a.axisR * dr + a.axisB * db;
                dp[k] = -a.axisB * dr + a.axisR * db;
                k++;
            }
        }
        if (k < 16) return new float[]{a.noiseY, a.noiseS, a.noiseP};
        float rt2 = (float) Math.sqrt(2);
        return new float[]{Math.max(1e-6f, Stats.madSigma(d, k, 0f) / rt2), Math.max(1e-6f, Stats.madSigma(ds, k, 0f) / rt2),
                Math.max(1e-6f, Stats.madSigma(dp, k, 0f) / rt2)};
    }

    /** {@link #noise}'s luminance noise alone. */
    static float lumaNoise(RgbImage img, Renderer.Analysis a) {
        float[] m = a.m;
        float yr = KR * m[0] + KG * m[3] + KB * m[6], yg = KR * m[1] + KG * m[4] + KB * m[7], yb = KR * m[2] + KG * m[5] + KB * m[8];
        int w = img.width, h = img.height;
        float[] dif = new float[((h + 3) / 4) * Math.max(1, w / 4)];
        int k = 0;
        for (int y = 0; y < h; y += 4) {
            for (int x = 0; x + 4 < w && k < dif.length; x += 4) {
                int i = y * w + x, j = i + 4;
                dif[k++] = yr * (img.r[j] - img.r[i]) + yg * (img.g[j] - img.g[i]) + yb * (img.b[j] - img.b[i]);
            }
        }
        if (k < 16) return a.noiseY;
        float med = Stats.median(dif, k);
        return Math.max(1e-6f, Stats.madSigma(dif, k, med) / (float) Math.sqrt(2));
    }

    /** The light within r px of (x, y) over the picture's median, in luminance. */
    static double objectLight(RgbImage img, Renderer.Analysis a, double x, double y, double r) {
        float[] m = a.m;
        float yr = KR * m[0] + KG * m[3] + KB * m[6], yg = KR * m[1] + KG * m[4] + KB * m[7], yb = KR * m[2] + KG * m[5] + KB * m[8];
        int w = img.width, h = img.height;
        float[] all = new float[(w * h + 7) / 8];
        int k = 0;
        for (int i = 0; i < w * h && k < all.length; i += 8) all[k++] = yr * img.r[i] + yg * img.g[i] + yb * img.b[i];
        float med = Stats.median(all, k);
        double sum = 0;
        for (int py = Math.max(0, (int) (y - r)); py < Math.min(h, (int) Math.ceil(y + r)); py++) {
            for (int px = Math.max(0, (int) (x - r)); px < Math.min(w, (int) Math.ceil(x + r)); px++) {
                if (Math.hypot(px + 0.5 - x, py + 0.5 - y) > r) continue;
                int i = py * w + px;
                sum += yr * img.r[i] + yg * img.g[i] + yb * img.b[i] - med;
            }
        }
        return sum;
    }

    // ------------------------------------------------------------------------------------------------
    // Stars drawn again
    // ------------------------------------------------------------------------------------------------

    /** A star: the band-pass (the light smoothed at a star image's size less it smoothed three times wider) FIND times its noise. */
    static final float FIND = 3f;
    /**
     * A star's colour: its own (the nearest a star's can be, a blackbody's) from COLOUR_SNR1 times its light's noise,
     * white under COLOUR_SNR0, a mix between.
     */
    static final float COLOUR_SNR0 = 10f, COLOUR_SNR1 = 50f;
    /** The blackbodies a star's colour is drawn from: LOCUS_N temperatures from LOCUS_T0 to LOCUS_T1 kelvin. */
    private static final int LOCUS_N = 96;
    private static final double LOCUS_T0 = 2000, LOCUS_T1 = 25000;
    /** Light more than WIDE times what a star image that bright holds within 3 sigma of its middle isn't a star. */
    static final float WIDE = 1.5f;
    /** A galaxy's or a nebula's stars are drawn back SHRINK_GLOW times their size (its glow stands out), others' SHRINK. */
    static final float SHRINK_GLOW = 0.7f, SHRINK = 0.9f;
    /** The drizzle's drops spread a frame's noise over neighbouring pixels: a fit's noise is about this much a white noise's. */
    private static final float CORR = 1.5f;
    /**
     * The fainter stars beside brighter ones are looked for again with the brighter ones' light taken out, except where
     * that light stands NEAR times over the noise (a bright star's own middle: what's left of it there can't be told
     * from a star).
     */
    static final float NEAR = 3f;

    /**
     * The picture's stars taken apart from the glow and drawn back in (2.4), the way the image makers behind NASA's
     * pictures work on a nebula without its stars: each star found (the band-pass {@link #FIND} times its noise), its place
     * and its light in each colour fitted with a Gaussian the picture's star size {@code sigma} over the sky around it (a
     * plane fitted to a ring around it, the brighter neighbours left out), its pixels out to where it falls under half
     * the noise filled in with that plane, and drawn back as a round Gaussian {@code shrink} times as wide holding the
     * same light, in the colour of the blackbody nearest its own (a star's colour is its temperature's), pulled towards
     * white as far as the noise could have made it ({@link #COLOUR_SNR0}).
     * <p>
     * The fainter stars beside brighter ones are looked for a second time with the brighter ones' light taken out
     * ({@link #NEAR}): the band-pass of a bright star dips below the sky around it, and a faint neighbour in that dip
     * would otherwise go unfound and lose half its light to the bright one's filled-in disc. Left as they are, their
     * light kept: stars that clipped in some frame ({@code left[0]}), lights wider than a star image (a galaxy's middle,
     * a knot of nebula; {@code left[1]}), and any within {@code keepR} px of ({@code ox}, {@code oy}) ({@code left[2]});
     * the second and third marked in {@code glow} (a byte per pixel, for the noise reduction: light that isn't a star's).
     * A star whose disc reaches into that light has only its own Gaussian taken out there, not the sky filled in
     * ({@code left[3]} counts them). Stars partly outside the picture are fitted on the part inside. Returns how many
     * were drawn again.
     */
    static int redrawStars(RgbImage img, Renderer.Analysis a, float sigma, float shrink, double ox, double oy, double keepR, int[] left,
                           byte[] glow) {
        final int w = img.width, h = img.height, np = w * h;
        // light that stays as it is: no star drawn again fills its disc over it
        byte[] keep = new byte[np];
        // the object's own middle
        if (keepR > 0) {
            paint(glow, w, h, ox, oy, keepR);
            paint(keep, w, h, ox, oy, keepR);
        }
        if (!(sigma > 0.3f)) return 0;
        Redraw rd = new Redraw(img, a, sigma, keep, glow, left);
        final float[] R = img.r, G = img.g, B = img.b;
        float[] L = new float[np];
        for (int i = 0; i < np; i++) L[i] = rd.yr * R[i] + rd.yg * G[i] + rd.yb * B[i];
        float[] tmp = new float[np];
        float[] D = bandPass(L, tmp, w, h, sigma);
        // the band-pass's noise over the whole picture (the stars are few enough not to move a median)
        float[] smp = new float[(np + 3) / 4];
        int k = 0;
        for (int i = 0; i < np && k < smp.length; i += 4) smp[k++] = D[i];
        float dMed = Stats.median(smp, k);
        float dSig = Math.max(1e-6f, Stats.madSigma(smp, k, dMed));
        smp = null;
        rd.dMed = dMed;
        rd.find = dMed + FIND * dSig;
        List<double[]> stars = new ArrayList<double[]>();
        List<double[]> seen = new ArrayList<double[]>();
        rd.find(D, L, R, G, B, null, ox, oy, keepR, stars, seen);
        // the fainter ones beside them, found again with the light of those found taken out
        if (!stars.isEmpty()) {
            float[] Mr = new float[np], Mg = new float[np], Mb = new float[np];
            for (double[] st : stars) rd.model(st, Mr, Mg, Mb, 1.0);
            // (the luminance from here on is theirs taken out too)
            float[] R2 = new float[np], G2 = new float[np], B2 = new float[np];
            byte[] skip = keep.clone();
            float near = NEAR * rd.noise;
            for (int i = 0; i < np; i++) {
                R2[i] = R[i] - Mr[i];
                G2[i] = G[i] - Mg[i];
                B2[i] = B[i] - Mb[i];
                float mL = rd.yr * Mr[i] + rd.yg * Mg[i] + rd.yb * Mb[i];
                L[i] -= mL;
                if (mL > near) skip[i] = 1;
            }
            Mr = Mg = Mb = null;
            for (double[] s : seen) paint(skip, w, h, s[0], s[1], rd.pr + 1);
            D = bandPass(L, tmp, w, h, sigma);
            rd.find(D, L, R2, G2, B2, skip, ox, oy, keepR, stars, null);
        }
        D = null;
        L = null;
        tmp = null;
        // their pixels filled in with the sky around them; where their discs reach into light that stays, only their own
        // light (the Gaussian fitted to them) taken out of it, never below their sky
        int drawn = 0;
        for (double[] st : stars) {
            double cx = st[0], cy = st[1], rr = st[7];
            int x0 = Math.max(0, (int) Math.floor(cx - rr)), x1 = Math.min(w - 1, (int) Math.ceil(cx + rr));
            int y0 = Math.max(0, (int) Math.floor(cy - rr)), y1 = Math.min(h - 1, (int) Math.ceil(cy + rr));
            boolean beside = false;
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    double ex = x + 0.5 - cx, ey = y + 0.5 - cy, d2 = ex * ex + ey * ey;
                    if (d2 > rr * rr) continue;
                    int j = y * w + x;
                    float pr = (float) (st[8] + st[9] * ex + st[10] * ey);
                    float pg = (float) (st[11] + st[12] * ex + st[13] * ey);
                    float pb = (float) (st[14] + st[15] * ex + st[16] * ey);
                    if (keep[j] == 0) {
                        R[j] = pr;
                        G[j] = pg;
                        B[j] = pb;
                    } else {
                        double P = Math.exp(-d2 / rd.s2);
                        R[j] = Math.max(pr, (float) (R[j] - st[2] * P));
                        G[j] = Math.max(pg, (float) (G[j] - st[3] * P));
                        B[j] = Math.max(pb, (float) (B[j] - st[4] * P));
                        beside = true;
                    }
                }
            }
            if (beside) left[3]++;
            drawn++;
        }
        // and drawn back, round, with the same light
        double sd = shrink * sigma, sd2 = 2 * sd * sd, scale = 1.0 / (shrink * shrink);
        int rdr = (int) Math.ceil(4 * sd) + 1;
        float[] m = a.m;
        float[] inv = inverse(m), white = neutral(m);
        for (double[] st : stars) {
            double cx = st[0], cy = st[1], aL = st[5];
            float t = smooth((float) st[6], COLOUR_SNR0, COLOUR_SNR1);
            // its own colour as far as its light stands clear of the noise (the blackbody's nearest it), white under that
            float[] own = t > 0 ? starColour(m, inv, st[2], st[3], st[4]) : white;
            double[] amp = new double[3];
            for (int c = 0; c < 3; c++) amp[c] = scale * aL * (t * own[c] + (1 - t) * white[c]);
            int x0 = Math.max(0, (int) Math.floor(cx) - rdr), x1 = Math.min(w - 1, (int) Math.floor(cx) + rdr);
            int y0 = Math.max(0, (int) Math.floor(cy) - rdr), y1 = Math.min(h - 1, (int) Math.floor(cy) + rdr);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    double ex = x + 0.5 - cx, ey = y + 0.5 - cy;
                    double P = Math.exp(-(ex * ex + ey * ey) / sd2);
                    if (P < 1e-4) continue;
                    int j = y * w + x;
                    R[j] += (float) (amp[0] * P);
                    G[j] += (float) (amp[1] * P);
                    B[j] += (float) (amp[2] * P);
                }
            }
        }
        return drawn;
    }

    /** The light smoothed at a star image's size {@code sigma} less it smoothed three times wider. */
    private static float[] bandPass(float[] L, float[] tmp, int w, int h, float sigma) {
        float[] D = L.clone(), wide = L.clone();
        gaussBlur(D, tmp, w, h, sigma);
        gaussBlur(wide, tmp, w, h, 3 * sigma);
        for (int i = 0; i < D.length; i++) D[i] -= wide[i];
        return D;
    }

    /** Sets {@code mask} within {@code r} px of (cx, cy). */
    static void paint(byte[] mask, int w, int h, double cx, double cy, double r) {
        int ya = Math.max(0, (int) Math.floor(cy - r)), yb = Math.min(h - 1, (int) Math.ceil(cy + r));
        int xa = Math.max(0, (int) Math.floor(cx - r)), xb = Math.min(w - 1, (int) Math.ceil(cx + r));
        double r2 = r * r;
        for (int y = ya; y <= yb; y++) {
            double ey = y + 0.5 - cy;
            for (int x = xa; x <= xb; x++) {
                double ex = x + 0.5 - cx;
                if (ex * ex + ey * ey <= r2) mask[y * w + x] = 1;
            }
        }
    }

    /** Whether {@code mask} is set anywhere within {@code r} px of (cx, cy). */
    static boolean touches(byte[] mask, int w, int h, double cx, double cy, double r) {
        int ya = Math.max(0, (int) Math.floor(cy - r)), yb = Math.min(h - 1, (int) Math.ceil(cy + r));
        int xa = Math.max(0, (int) Math.floor(cx - r)), xb = Math.min(w - 1, (int) Math.ceil(cx + r));
        double r2 = r * r;
        for (int y = ya; y <= yb; y++) {
            double ey = y + 0.5 - cy;
            for (int x = xa; x <= xb; x++) {
                double ex = x + 0.5 - cx;
                if (ex * ex + ey * ey <= r2 && mask[y * w + x] != 0) return true;
            }
        }
        return false;
    }

    /** {@link #redrawStars}'s picture, star size and what it keeps. */
    private static final class Redraw {
        final int w, h;
        final float sigma, noise, yr, yg, yb;
        /** The peak's neighbourhood, the fit's reach, the aperture, and the sky ring's inner and outer radii. */
        final int pr, rFit, rAp, rIn, rOut;
        final double s2;
        final byte[] keep, glow, clip;
        final int[] left;
        final float[] ring;
        final int[] ringAt;
        /** The band-pass's median and the level a star stands over it. */
        float dMed, find;

        Redraw(RgbImage img, Renderer.Analysis a, float sigma, byte[] keep, byte[] glow, int[] left) {
            w = img.width;
            h = img.height;
            this.sigma = sigma;
            float[] m = a.m;
            yr = KR * m[0] + KG * m[3] + KB * m[6];
            yg = KR * m[1] + KG * m[4] + KB * m[7];
            yb = KR * m[2] + KG * m[5] + KB * m[8];
            noise = lumaNoise(img, a);
            pr = Math.max(1, Math.round(sigma));
            rFit = (int) Math.ceil(2.5 * sigma);
            rAp = (int) Math.ceil(3 * sigma);
            rIn = rAp + 1;
            rOut = rIn + Math.max(3, (int) Math.ceil(2 * sigma));
            s2 = 2.0 * sigma * sigma;
            this.keep = keep;
            this.glow = glow;
            this.left = left;
            clip = img.clip;
            ring = new float[(2 * rOut + 1) * (2 * rOut + 1)];
            ringAt = new int[ring.length];
        }

        /**
         * The stars standing out of band-pass {@code D} (of luminance {@code L}; colours {@code R}, {@code G}, {@code B}),
         * none where {@code skip} is set (null: anywhere): the ones to draw again added to {@code stars} (x, y, amplitude in
         * r, g, b, in luminance, signal to noise, disc radius, the sky's plane in r, g and b: three numbers each), every one
         * found to {@code seen} (null: none kept), the rest counted in {@code left} and their light kept.
         */
        void find(float[] D, float[] L, float[] R, float[] G, float[] B, byte[] skip, double ox, double oy, double keepR,
                  List<double[]> stars, List<double[]> seen) {
            // out to the picture's edges: a star partly outside it is fitted on the part inside
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    float v = D[i];
                    if (v < find || skip != null && skip[i] != 0) continue;
                    boolean top = true;
                    for (int dy = -pr; dy <= pr && top; dy++) {
                        if (y + dy < 0 || y + dy >= h) continue;
                        for (int dx = -pr; dx <= pr; dx++) {
                            if (dx == 0 && dy == 0 || x + dx < 0 || x + dx >= w) continue;
                            float q = D[i + dy * w + dx];
                            if (q > v || (q == v && (dy < 0 || (dy == 0 && dx < 0)))) {
                                top = false;
                                break;
                            }
                        }
                    }
                    if (!top) continue;
                    // its middle: the band-pass's light around the peak
                    double sw = 0, sx = 0, sy = 0;
                    for (int dy = -pr; dy <= pr; dy++) {
                        if (y + dy < 0 || y + dy >= h) continue;
                        for (int dx = -pr; dx <= pr; dx++) {
                            if (x + dx < 0 || x + dx >= w) continue;
                            double q = Math.max(0, D[i + dy * w + dx] - dMed);
                            sw += q;
                            sx += q * dx;
                            sy += q * dy;
                        }
                    }
                    if (!(sw > 0)) continue;
                    double cx = x + 0.5 + sx / sw, cy = y + 0.5 + sy / sw;
                    if (seen != null) seen.add(new double[]{cx, cy});
                    if (keepR > 0 && Math.hypot(cx - ox, cy - oy) < keepR) {
                        left[2]++;
                        continue;
                    }
                    // the sky around it: a ring, the brighter pixels (neighbours) left out, fitted as a plane in each colour
                    int n = 0;
                    for (int dy = -rOut; dy <= rOut; dy++) {
                        if (y + dy < 0 || y + dy >= h) continue;
                        for (int dx = -rOut; dx <= rOut; dx++) {
                            int d2 = dx * dx + dy * dy;
                            if (d2 < rIn * rIn || d2 > rOut * rOut || x + dx < 0 || x + dx >= w) continue;
                            int j = i + dy * w + dx;
                            ring[n] = L[j];
                            ringAt[n++] = j;
                        }
                    }
                    float[] rs = Arrays.copyOf(ring, n);
                    float rMed = Stats.median(rs, n);
                    float lim = rMed + 3 * noise;
                    double[] A = new double[6];
                    double[][] bv = new double[3][3];
                    int used = 0;
                    for (int t = 0; t < n; t++) {
                        if (ring[t] > lim) continue;
                        int j = ringAt[t];
                        double ex = (j % w) + 0.5 - cx, ey = (j / w) + 0.5 - cy;
                        A[0] += 1;
                        A[1] += ex;
                        A[2] += ey;
                        A[3] += ex * ex;
                        A[4] += ex * ey;
                        A[5] += ey * ey;
                        for (int c = 0; c < 3; c++) {
                            float val = c == 0 ? R[j] : c == 1 ? G[j] : B[j];
                            bv[c][0] += val;
                            bv[c][1] += val * ex;
                            bv[c][2] += val * ey;
                        }
                        used++;
                    }
                    if (used < 12) continue;
                    double[][] plane = new double[3][];
                    boolean ok = true;
                    for (int c = 0; c < 3 && ok; c++) {
                        plane[c] = solve3(A[0], A[1], A[2], A[3], A[4], A[5], bv[c]);
                        ok = plane[c] != null;
                    }
                    if (!ok) continue;
                    // its light in each colour: a Gaussian's height fitted over the plane; what's within 3 sigma, for its width
                    double sp2 = 0, ap = 0, apModel = 0;
                    double[] amp = new double[3];
                    boolean clipped = false;
                    int nAp = 0;
                    for (int dy = -rAp; dy <= rAp; dy++) {
                        if (y + dy < 0 || y + dy >= h) continue;
                        for (int dx = -rAp; dx <= rAp; dx++) {
                            if (x + dx < 0 || x + dx >= w) continue;
                            int j = i + dy * w + dx;
                            double ex = (j % w) + 0.5 - cx, ey = (j / w) + 0.5 - cy, d2 = ex * ex + ey * ey;
                            if (d2 > rAp * rAp) continue;
                            double P = Math.exp(-d2 / s2);
                            double sr = R[j] - (plane[0][0] + plane[0][1] * ex + plane[0][2] * ey);
                            double sg = G[j] - (plane[1][0] + plane[1][1] * ex + plane[1][2] * ey);
                            double sb = B[j] - (plane[2][0] + plane[2][1] * ex + plane[2][2] * ey);
                            ap += yr * sr + yg * sg + yb * sb;
                            apModel += P;
                            nAp++;
                            if (d2 <= rFit * rFit) {
                                amp[0] += sr * P;
                                amp[1] += sg * P;
                                amp[2] += sb * P;
                                sp2 += P * P;
                                if (clip != null && clip[j] != 0) clipped = true;
                            }
                        }
                    }
                    if (clipped) {
                        left[0]++;
                        keepAround(L, cx, cy, rMed, Math.min(64, 6 * rOut));
                        continue;
                    }
                    // too little of it inside the picture to fit
                    if (sp2 < 0.25 * Math.PI * sigma * sigma) continue;
                    for (int c = 0; c < 3; c++) amp[c] /= sp2;
                    double aL = yr * amp[0] + yg * amp[1] + yb * amp[2];
                    if (!(aL > 0)) continue;
                    // wider than a star image: more light within 3 sigma than a star this bright holds, past the noise
                    if (ap > WIDE * aL * apModel + 3 * CORR * noise * Math.sqrt(nAp)) {
                        left[1]++;
                        paint(glow, w, h, cx, cy, rIn);
                        keepAround(L, cx, cy, rMed, 3 * rOut);
                        continue;
                    }
                    double snr = aL * Math.sqrt(sp2) / (CORR * noise);
                    // out to where it falls under half the noise
                    double rr = sigma * Math.sqrt(2 * Math.log(Math.max(1.0, 2 * aL / noise))) + 1;
                    rr = Math.max(rFit, Math.min(rOut, rr));
                    stars.add(new double[]{cx, cy, amp[0], amp[1], amp[2], aL, snr, rr, plane[0][0], plane[0][1], plane[0][2],
                            plane[1][0], plane[1][1], plane[1][2], plane[2][0], plane[2][1], plane[2][2]});
                }
            }
        }

        /**
         * The light around (cx, cy) kept as it is: out to where its rings' median comes within half the noise of the
         * faintest ring's (the sky around it), and two pixels more; at least the sky ring's inner radius, at most
         * {@code rMax} px. {@code bg}: the sky ring's median, the level when no ring is fainter.
         */
        void keepAround(float[] L, double cx, double cy, float bg, int rMax) {
            int ya = Math.max(0, (int) Math.floor(cy - rMax - 1)), yb = Math.min(h - 1, (int) Math.ceil(cy + rMax + 1));
            int xa = Math.max(0, (int) Math.floor(cx - rMax - 1)), xb = Math.min(w - 1, (int) Math.ceil(cx + rMax + 1));
            int[] count = new int[rMax + 1];
            for (int y = ya; y <= yb; y++) {
                for (int x = xa; x <= xb; x++) {
                    int r = (int) Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
                    if (r <= rMax) count[r]++;
                }
            }
            int[] at = new int[rMax + 2];
            for (int r = 0; r <= rMax; r++) at[r + 1] = at[r] + count[r];
            float[] all = new float[at[rMax + 1]];
            int[] fill = Arrays.copyOf(at, rMax + 1);
            for (int y = ya; y <= yb; y++) {
                for (int x = xa; x <= xb; x++) {
                    int r = (int) Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
                    if (r <= rMax) all[fill[r]++] = L[y * w + x];
                }
            }
            float[] med = new float[rMax + 1];
            float[] buf = new float[all.length];
            float low = bg;
            int last = rIn;
            for (int r = rIn; r <= rMax; r++) {
                int k = count[r];
                if (k < 8) break;
                System.arraycopy(all, at[r], buf, 0, k);
                med[r] = Stats.median(buf, k);
                low = Math.min(low, med[r]);
                last = r;
            }
            int reach = last;
            for (int r = rIn; r <= last; r++) {
                if (med[r] < low + 0.5f * noise) {
                    reach = r;
                    break;
                }
            }
            paint(keep, w, h, cx, cy, reach + 2);
        }

        /** Adds star {@code st}'s Gaussian, {@code k} times its light, to the colours' pictures. */
        void model(double[] st, float[] Mr, float[] Mg, float[] Mb, double k) {
            double cx = st[0], cy = st[1];
            int r = (int) Math.ceil(4 * sigma) + 1;
            int x0 = Math.max(0, (int) Math.floor(cx) - r), x1 = Math.min(w - 1, (int) Math.floor(cx) + r);
            int y0 = Math.max(0, (int) Math.floor(cy) - r), y1 = Math.min(h - 1, (int) Math.floor(cy) + r);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    double ex = x + 0.5 - cx, ey = y + 0.5 - cy;
                    double P = k * Math.exp(-(ex * ex + ey * ey) / s2);
                    int j = y * w + x;
                    Mr[j] += (float) (st[2] * P);
                    Mg[j] += (float) (st[3] * P);
                    Mb[j] += (float) (st[4] * P);
                }
            }
        }
    }

    /**
     * The camera colour of the blackbody nearest the camera colour (r, g, b) as the matrix {@code m} (camera to linear
     * sRGB; {@code inv} its inverse) shows them, by the logarithms of red and blue against green, with a luminance of 1.
     */
    static float[] starColour(float[] m, float[] inv, double r, double g, double b) {
        double sr = m[0] * r + m[1] * g + m[2] * b, sg = m[3] * r + m[4] * g + m[5] * b, sb = m[6] * r + m[7] * g + m[8] * b;
        double fl = 1e-3 * Math.max(1e-9, Math.abs(KR * sr + KG * sg + KB * sb));
        double u = Math.log(Math.max(fl, sr) / Math.max(fl, sg)), v = Math.log(Math.max(fl, sb) / Math.max(fl, sg));
        float[][] l = locus();
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int k = 0; k < l.length; k++) {
            double du = u - Math.log(l[k][0] / l[k][1]), dv = v - Math.log(l[k][2] / l[k][1]);
            double d = du * du + dv * dv;
            if (d < bd) {
                bd = d;
                best = k;
            }
        }
        float[] c = l[best];
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = inv[i * 3] * c[0] + inv[i * 3 + 1] * c[1] + inv[i * 3 + 2] * c[2];
        float lum = (KR * m[0] + KG * m[3] + KB * m[6]) * out[0] + (KR * m[1] + KG * m[4] + KB * m[7]) * out[1]
                + (KR * m[2] + KG * m[5] + KB * m[8]) * out[2];
        if (lum > 0) for (int i = 0; i < 3; i++) out[i] /= lum;
        return out;
    }

    private static volatile float[][] locus;

    /** Blackbodies' linear sRGB colours ({@link #LOCUS_N} of them, LOCUS_T0 to LOCUS_T1 kelvin), each with a luminance of 1. */
    static float[][] locus() {
        float[][] l = locus;
        if (l != null) return l;
        l = new float[LOCUS_N][];
        for (int k = 0; k < LOCUS_N; k++) {
            double t = LOCUS_T0 * Math.pow(LOCUS_T1 / LOCUS_T0, k / (LOCUS_N - 1.0));
            double[] xyz = ColorMath.whiteXyz(t);
            double r = 3.2404542 * xyz[0] - 1.5371385 * xyz[1] - 0.4985314 * xyz[2];
            double g = -0.9692660 * xyz[0] + 1.8760108 * xyz[1] + 0.0415560 * xyz[2];
            double b = 0.0556434 * xyz[0] - 0.2040259 * xyz[1] + 1.0572252 * xyz[2];
            r = Math.max(0.01, r);
            g = Math.max(0.01, g);
            b = Math.max(0.01, b);
            double lum = KR * r + KG * g + KB * b;
            l[k] = new float[]{(float) (r / lum), (float) (g / lum), (float) (b / lum)};
        }
        locus = l;
        return l;
    }

    /** The inverse of the 3 x 3 matrix {@code m} (row after row); the identity if it has none. */
    static float[] inverse(float[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-12) return new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        double[] r = {e * i - f * h, -(b * i - c * h), b * f - c * e, -(d * i - f * g), a * i - c * g, -(a * f - c * d), d * h - e * g,
                -(a * h - b * g), a * e - b * d};
        float[] out = new float[9];
        for (int k = 0; k < 9; k++) out[k] = (float) (r[k] / det);
        return out;
    }

    /** The camera colour the matrix {@code m} (camera to linear sRGB) makes white, with a luminance of 1. */
    static float[] neutral(float[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], hh = m[7], i = m[8];
        double det = a * (e * i - f * hh) - b * (d * i - f * g) + c * (d * hh - e * g);
        if (Math.abs(det) < 1e-12) return new float[]{1, 1, 1};
        // the inverse's rows, each summed (the inverse times 1, 1, 1)
        double x = ((e * i - f * hh) - (b * i - c * hh) + (b * f - c * e)) / det;
        double y = (-(d * i - f * g) + (a * i - c * g) - (a * f - c * d)) / det;
        double z = ((d * hh - e * g) - (a * hh - b * g) + (a * e - b * d)) / det;
        float yr = KR * m[0] + KG * m[3] + KB * m[6], yg = KR * m[1] + KG * m[4] + KB * m[7], yb = KR * m[2] + KG * m[5] + KB * m[8];
        double lum = yr * x + yg * y + yb * z;
        if (!(lum > 0)) return new float[]{1, 1, 1};
        return new float[]{(float) (x / lum), (float) (y / lum), (float) (z / lum)};
    }

    /** The plane c0 + c1 x + c2 y through the normal equations (sums 1, x, y, xx, xy, yy; right side b); null if singular. */
    static double[] solve3(double n, double sx, double sy, double sxx, double sxy, double syy, double[] b) {
        double[][] M = {{n, sx, sy}, {sx, sxx, sxy}, {sy, sxy, syy}};
        double det = M[0][0] * (M[1][1] * M[2][2] - M[1][2] * M[2][1]) - M[0][1] * (M[1][0] * M[2][2] - M[1][2] * M[2][0])
                + M[0][2] * (M[1][0] * M[2][1] - M[1][1] * M[2][0]);
        if (Math.abs(det) < 1e-12) return null;
        double[] out = new double[3];
        for (int col = 0; col < 3; col++) {
            double[][] C = {M[0].clone(), M[1].clone(), M[2].clone()};
            for (int r = 0; r < 3; r++) C[r][col] = b[r];
            double dc = C[0][0] * (C[1][1] * C[2][2] - C[1][2] * C[2][1]) - C[0][1] * (C[1][0] * C[2][2] - C[1][2] * C[2][0])
                    + C[0][2] * (C[1][0] * C[2][1] - C[1][1] * C[2][0]);
            out[col] = dc / det;
        }
        return out;
    }

    /** A Gaussian blur of sigma s in place (tmp as large as the picture). */
    static void gaussBlur(final float[] v, final float[] tmp, final int w, final int h, float s) {
        final int r = Math.max(1, (int) Math.ceil(3 * s));
        final float[] k = Renderer.Band.gaussKernel(r, s);
        Parallel.forRange(h, 16, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int y = j0; y < j1; y++) {
                    int o = y * w;
                    for (int x = 0; x < w; x++) {
                        float acc = 0;
                        for (int d = -r; d <= r; d++) acc += k[d + r] * v[o + Math.max(0, Math.min(w - 1, x + d))];
                        tmp[o + x] = acc;
                    }
                }
            }
        });
        Parallel.forRange(w, 16, new Parallel.Range() {
            @Override
            public void run(int i0, int i1) {
                for (int x = i0; x < i1; x++) {
                    for (int y = 0; y < h; y++) {
                        float acc = 0;
                        for (int d = -r; d <= r; d++) acc += k[d + r] * tmp[Math.max(0, Math.min(h - 1, y + d)) * w + x];
                        v[y * w + x] = acc;
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------------
    // Star sizes
    // ------------------------------------------------------------------------------------------------

    /**
     * The picture's star images' size: the median Gaussian sigma (px, from the second moments of the light over the sky
     * within 3 sigma) of its brighter single stars, unclipped, alone within 8 px; NaN with fewer than 5 of them.
     */
    static float starSigma(RgbImage img, Renderer.Analysis a) {
        int w = img.width, h = img.height;
        float[] m = a.m;
        float yr = KR * m[0] + KG * m[3] + KB * m[6], yg = KR * m[1] + KG * m[4] + KB * m[7], yb = KR * m[2] + KG * m[5] + KB * m[8];
        float[] L = new float[w * h];
        for (int i = 0; i < L.length; i++) L[i] = yr * img.r[i] + yg * img.g[i] + yb * img.b[i];
        float noise = lumaNoise(img, a);
        // the sky: the median of each 32 x 32 block
        int bs = 32, bw = (w + bs - 1) / bs, bh = (h + bs - 1) / bs;
        float[] sky = new float[bw * bh], buf = new float[bs * bs];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                int k = 0;
                for (int y = by * bs; y < Math.min(h, by * bs + bs); y++) for (int x = bx * bs; x < Math.min(w, bx * bs + bs); x++) buf[k++] = L[y * w + x];
                sky[by * bw + bx] = k > 0 ? Stats.median(buf, k) : 0f;
            }
        }
        int R = 8;
        List<Float> sig = new ArrayList<Float>();
        for (int y = R; y < h - R; y++) {
            for (int x = R; x < w - R; x++) {
                int i = y * w + x;
                float s0 = sky[(y / bs) * bw + x / bs];
                float pk = L[i] - s0;
                if (pk < 30 * noise) continue;
                boolean top = true;
                for (int dy = -R; dy <= R && top; dy++) {
                    for (int dx = -R; dx <= R; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        float o = L[i + dy * w + dx];
                        if (o > L[i] || (o == L[i] && (dy < 0 || dy == 0 && dx < 0))) {
                            top = false;
                            break;
                        }
                        // a second star nearby
                        if (Math.abs(dx) + Math.abs(dy) > 4 && o - s0 > 0.3f * pk && localMax(L, w, h, x + dx, y + dy)) {
                            top = false;
                            break;
                        }
                    }
                }
                if (!top) continue;
                if (img.clip != null && (img.clip[i] & 0xFF) > 5) continue;
                double sw = 0, sxx = 0, syy = 0, sx = 0, sy = 0;
                int rr = 5;
                for (int dy = -rr; dy <= rr; dy++) {
                    for (int dx = -rr; dx <= rr; dx++) {
                        float v = L[i + dy * w + dx] - s0;
                        if (v < 3 * noise) continue;
                        sw += v;
                        sx += v * dx;
                        sy += v * dy;
                        sxx += v * dx * dx;
                        syy += v * dy * dy;
                    }
                }
                if (sw <= 0) continue;
                double mx = sx / sw, my = sy / sw;
                double vx = sxx / sw - mx * mx, vy = syy / sw - my * my;
                double s2 = 0.5 * (vx + vy);
                if (s2 > 0.05) sig.add((float) Math.sqrt(s2));
            }
        }
        if (sig.size() < 5) return Float.NaN;
        float[] v = new float[sig.size()];
        for (int i = 0; i < v.length; i++) v[i] = sig.get(i);
        return Stats.median(v, v.length);
    }

    private static boolean localMax(float[] L, int w, int h, int x, int y) {
        if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) return false;
        float c = L[y * w + x];
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) if ((dx != 0 || dy != 0) && L[(y + dy) * w + x + dx] > c) return false;
        return true;
    }

    // ------------------------------------------------------------------------------------------------
    // Enlarging
    // ------------------------------------------------------------------------------------------------

    /**
     * {@code src} (w x h ARGB) resampled to W x H with a Lanczos-3 filter (stretched when making it smaller), rows then
     * columns, each value held between the two source pixels nearest it so bright stars get no dark rings.
     */
    public static int[] resample(int[] src, int w, int h, int W, int H) {
        if (w == W && h == H) return src.clone();
        float[][] c = new float[3][w * h];
        for (int i = 0; i < w * h; i++) {
            int p = src[i];
            c[0][i] = (p >> 16) & 0xFF;
            c[1][i] = (p >> 8) & 0xFF;
            c[2][i] = p & 0xFF;
        }
        Filter fx = new Filter(w, W), fy = new Filter(h, H);
        float[][] mid = new float[3][W * h];
        for (int q = 0; q < 3; q++) {
            for (int y = 0; y < h; y++) fx.apply(c[q], y * w, 1, mid[q], y * W, 1);
        }
        int[] out = new int[W * H];
        float[][] fin = new float[3][W * H];
        for (int q = 0; q < 3; q++) {
            for (int x = 0; x < W; x++) fy.apply(mid[q], x, W, fin[q], x, W);
        }
        for (int i = 0; i < W * H; i++) {
            int r = Math.max(0, Math.min(255, Math.round(fin[0][i]))), g = Math.max(0, Math.min(255, Math.round(fin[1][i])));
            int b = Math.max(0, Math.min(255, Math.round(fin[2][i])));
            out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return out;
    }

    /** One axis of {@link #resample}: per output sample, its source samples and weights, and the two nearest. */
    private static final class Filter {
        final int n, from;
        final int[] first, count, near0, near1;
        final float[] weights;
        final int stride;

        Filter(int from, int to) {
            n = to;
            double scale = to / (double) from;
            double support = scale >= 1 ? 3 : 3 / scale;
            double kscale = scale >= 1 ? 1 : scale;
            stride = (int) Math.ceil(2 * support) + 2;
            first = new int[to];
            count = new int[to];
            near0 = new int[to];
            near1 = new int[to];
            weights = new float[to * stride];
            for (int i = 0; i < to; i++) {
                double c = (i + 0.5) / scale - 0.5;
                int a = (int) Math.floor(c - support) + 1, b = (int) Math.floor(c + support);
                double sum = 0;
                int k = 0;
                for (int s = a; s <= b && k < stride; s++, k++) {
                    double x = (s - c) * kscale;
                    double wv = lanczos(x, 3);
                    weights[i * stride + k] = (float) wv;
                    sum += wv;
                }
                for (int e = 0; e < k; e++) weights[i * stride + e] /= (float) sum;
                first[i] = a;
                count[i] = k;
                int f = (int) Math.floor(c);
                near0[i] = Math.max(0, Math.min(from - 1, f));
                near1[i] = Math.max(0, Math.min(from - 1, f + 1));
                if (scale < 1) {
                    // making it smaller: held within what the filter's middle covers
                    near0[i] = Math.max(0, Math.min(from - 1, (int) Math.floor(c - 0.5 / scale + 0.5)));
                    near1[i] = Math.max(0, Math.min(from - 1, (int) Math.floor(c + 0.5 / scale + 0.5)));
                }
            }
            this.from = from;
        }

        void apply(float[] src, int off, int step, float[] dst, int doff, int dstep) {
            for (int i = 0; i < n; i++) {
                double acc = 0;
                int a = first[i];
                for (int k = 0; k < count[i]; k++) {
                    int s = Math.max(0, Math.min(from - 1, a + k));
                    acc += weights[i * stride + k] * src[off + s * step];
                }
                float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
                for (int s = near0[i]; s <= near1[i]; s++) {
                    float v = src[off + s * step];
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
                dst[doff + i * dstep] = (float) Math.max(lo, Math.min(hi, acc));
            }
        }
    }

    static double lanczos(double x, int a) {
        if (x == 0) return 1;
        if (x <= -a || x >= a) return 0;
        double px = Math.PI * x;
        return a * Math.sin(px) * Math.sin(px / a) / (px * px);
    }
}
