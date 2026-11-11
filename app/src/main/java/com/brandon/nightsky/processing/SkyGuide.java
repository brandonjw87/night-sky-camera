package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "What you're looking at": a few lines about the picture from fixed templates, filled in from where the camera
 * pointed (the sky solution of the reference frame) and when: the constellations, the brightest named stars,
 * the planets and the Moon, the Milky Way, and the best-known clusters, nebulae and galaxies. Everything named
 * is placed in the picture by the same solution that matched the stars, so nothing is guessed. Without a
 * solution but with the place and the time, it says what was straight up instead. With a solution it also places the
 * constellations' figures (2.0), the lines between their stars, for drawing on the photo with the names.
 */
public final class SkyGuide {
    /** One thing named, and where it is. */
    public static final class Item {
        /** "constellation", "star", "planet", "moon", "object" or "milky way". */
        public String kind, name;
        /** Where in the picture, as words ("at the top left"); "" when not placed. */
        public String where = "";
        public double mag = Double.NaN;
        /** Position in the picture, 0..1 from the left and from the top; NaN if not in it. */
        public double x = Double.NaN, y = Double.NaN;
        /** Constellations: share of the picture. */
        public double share = Double.NaN;
        /**
         * Constellations: where the name is drawn when that isn't (x, y), the middle of its part of the picture: inside its
         * figure (2.0). NaN for (x, y).
         */
        public double lx = Double.NaN, ly = Double.NaN;

        Item(String kind, String name) {
            this.kind = kind;
            this.name = name;
        }
    }

    /**
     * A line of a constellation's figure in the picture (2.0), from one of its stars to the next, or to where it leaves
     * the picture; bent as the lens bends it.
     */
    public static final class Line {
        /** The constellation's IAU abbreviation ("UMa"). */
        public final String c;
        /** Points along it: u, v, u, v, ... as shares of the picture (0..1 inside it). */
        public final float[] pts;
        /** Whether it starts at a star (else where the lens model stops holding, outside the picture) and ends at one. */
        public final boolean a, b;

        public Line(String c, float[] pts, boolean a, boolean b) {
            this.c = c;
            this.pts = pts;
            this.a = a;
            this.b = b;
        }
    }

    public final List<String> lines = new ArrayList<String>();
    public final List<Item> items = new ArrayList<Item>();
    /** The constellations' figures in the picture (2.0); empty when the picture wasn't placed on the sky. */
    public final List<Line> figures = new ArrayList<Line>();
    /** The constellation at the middle of the picture (full name), "" if unknown. */
    public String centre = "";
    /** Whether the picture was placed on the sky (else only what was overhead is known). */
    public boolean placed;

    private SkyGuide() {}

    /** What a saved report says about the picture (see {@link ReportCard#toJson()}). */
    static SkyGuide fromJson(com.brandon.nightsky.remote.JsonTree.Obj o) {
        SkyGuide g = new SkyGuide();
        g.centre = o.str("centre", "");
        g.placed = o.bool("placed", false);
        g.lines.addAll(o.strs("lines"));
        for (com.brandon.nightsky.remote.JsonTree.Obj x : o.objs("items")) {
            Item it = new Item(x.str("kind", ""), x.str("name", ""));
            it.where = x.str("where", "");
            it.mag = x.num("mag", Double.NaN);
            it.x = x.num("x", Double.NaN);
            it.y = x.num("y", Double.NaN);
            it.share = x.num("share", Double.NaN);
            it.lx = x.num("lx", Double.NaN);
            it.ly = x.num("ly", Double.NaN);
            g.items.add(it);
        }
        for (com.brandon.nightsky.remote.JsonTree.Obj x : o.objs("figures")) {
            double[] p = x.nums("p");
            if (p.length < 4 || p.length % 2 != 0) continue;
            float[] pts = new float[p.length];
            boolean ok = true;
            for (int k = 0; k < p.length; k++) {
                pts[k] = (float) p[k];
                ok &= !Double.isNaN(p[k]);
            }
            if (ok) g.figures.add(new Line(x.str("c", ""), pts, x.bool("a", true), x.bool("b", true)));
        }
        return g;
    }

    /** Galactic to J2000 equatorial: rows are the directions of l = 0, l = 90 and the north galactic pole. */
    private static final double[] GAL = {
            -0.0548755604, -0.8734370902, -0.4838350155,
            0.4941094279, -0.4448296300, 0.7469822445,
            -0.8676661490, -0.1980763734, 0.4559837762};

    /** J2000 direction of galactic longitude and latitude (radians). */
    static double[] galactic(double l, double b) {
        double cl = Math.cos(l) * Math.cos(b), sl = Math.sin(l) * Math.cos(b), sb = Math.sin(b);
        return new double[]{
                cl * GAL[0] + sl * GAL[3] + sb * GAL[6],
                cl * GAL[1] + sl * GAL[4] + sb * GAL[7],
                cl * GAL[2] + sl * GAL[5] + sb * GAL[8]};
    }

    /** Galactic longitude (radians, 0..2pi) of a J2000 direction. */
    static double galacticLongitude(double[] v) {
        double x = GAL[0] * v[0] + GAL[1] * v[1] + GAL[2] * v[2];
        double y = GAL[3] * v[0] + GAL[4] * v[1] + GAL[5] * v[2];
        return Astro.norm(Math.atan2(y, x));
    }

    // name, RA (deg), Dec (deg), V magnitude, kind: g galaxy, o open cluster, c globular cluster, n nebula, s star
    // cloud; the best known first (the picture lists the first three it holds)
    private static final Object[][] OBJECTS = {
            {"the Andromeda Galaxy (M31)", 10.685, 41.269, 3.4, 'g'},
            {"the Triangulum Galaxy (M33)", 23.462, 30.660, 5.7, 'g'},
            {"the Pleiades (M45)", 56.85, 24.117, 1.6, 'o'},
            {"the Hyades", 66.75, 15.87, 0.5, 'o'},
            {"the Orion Nebula (M42)", 83.822, -5.391, 4.0, 'n'},
            {"the Double Cluster", 35.1, 57.13, 4.3, 'o'},
            {"the Alpha Persei Cluster", 51.75, 49.87, 1.2, 'o'},
            {"the Beehive Cluster (M44)", 130.1, 19.67, 3.7, 'o'},
            {"M35", 92.225, 24.333, 5.3, 'o'},
            {"M41", 101.5, -20.73, 4.5, 'o'},
            {"M47", 114.15, -14.48, 4.2, 'o'},
            {"the Rosette Nebula", 98.0, 4.95, 4.8, 'n'},
            {"the California Nebula", 60.8, 36.4, 6.0, 'n'},
            {"the Heart Nebula", 38.3, 61.45, 6.5, 'n'},
            {"Bode's Galaxy (M81)", 148.888, 69.065, 6.9, 'g'},
            {"the Whirlpool Galaxy (M51)", 202.47, 47.195, 8.4, 'g'},
            {"the Pinwheel Galaxy (M101)", 210.8, 54.35, 7.9, 'g'},
            {"the Coma Star Cluster", 186.25, 26.0, 1.8, 'o'},
            {"M3", 205.548, 28.377, 6.2, 'c'},
            {"M5", 229.638, 2.08, 5.6, 'c'},
            {"M4", 245.897, -26.526, 5.6, 'c'},
            {"the Hercules Cluster (M13)", 250.423, 36.46, 5.8, 'c'},
            {"M92", 259.28, 43.136, 6.4, 'c'},
            {"the Butterfly Cluster (M6)", 265.08, -32.25, 4.2, 'o'},
            {"the Ptolemy Cluster (M7)", 268.46, -34.79, 3.3, 'o'},
            {"the Lagoon Nebula (M8)", 270.904, -24.38, 6.0, 'n'},
            {"the Sagittarius Star Cloud (M24)", 274.2, -18.48, 4.6, 's'},
            {"M22", 279.1, -23.9, 5.1, 'c'},
            {"the Wild Duck Cluster (M11)", 282.77, -6.27, 5.8, 'o'},
            {"the Coathanger", 291.35, 20.18, 3.6, 'o'},
            {"the Dumbbell Nebula (M27)", 299.9, 22.72, 7.5, 'n'},
            {"the North America Nebula", 314.82, 44.52, 4.0, 'n'},
            {"M39", 322.9, 48.43, 4.6, 'o'},
            {"Omega Centauri", 201.697, -47.48, 3.9, 'c'},
            {"47 Tucanae", 6.024, -72.08, 4.1, 'c'},
            {"the Large Magellanic Cloud", 80.89, -69.756, 0.9, 'g'},
            {"the Small Magellanic Cloud", 13.187, -72.83, 2.7, 'g'},
            {"the Carina Nebula", 161.265, -59.87, 1.0, 'n'},
    };

    private static String looks(char kind) {
        switch (kind) {
            case 'g':
                return "a faint oval glow";
            case 'o':
                return "a tight group of stars";
            case 'c':
                return "a fuzzy ball of stars";
            case 'n':
                return "a soft glow, pink in a deep stack";
            default:
                return "a dense patch of stars";
        }
    }

    /**
     * The guide for a picture: the crop (x0, y0, w, h) of the stack, in the reference frame's pixels, which the
     * solution maps to the sky. {@code jdUtc} is the time of the reference frame; {@code sky} gives the place
     * (may be null, or without a place).
     */
    public static SkyGuide describe(SkySolver.Solution sol, double jdUtc, SessionSky sky, BrightStars catalog,
                                    int x0, int y0, int w, int h) {
        SkyGuide g = new SkyGuide();
        boolean timed = !Double.isNaN(jdUtc);
        boolean located = sky != null && sky.located();
        if (sol != null && w > 0 && h > 0) {
            g.placed = true;
            g.picture(sol, jdUtc, timed, located ? sky : null, catalog, x0, y0, w, h);
        } else if (timed && located) {
            g.overhead(jdUtc, sky, catalog);
        } else {
            g.lines.add("Where the camera pointed isn't known: too few stars matched the catalogue, and the place or time is missing.");
        }
        return g;
    }

    private void picture(SkySolver.Solution sol, double jdUtc, boolean timed, SessionSky sky, BrightStars catalog,
                         int x0, int y0, int w, int h) {
        final Frame f = new Frame(sol, x0, y0, w, h);
        // constellations by their share of the picture, and where their part of it is
        final int gx = 40, gy = 30;
        Map<String, double[]> area = new LinkedHashMap<String, double[]>();
        for (int j = 0; j < gy; j++) {
            for (int i = 0; i < gx; i++) {
                double u = (i + 0.5) / gx, v = (j + 0.5) / gy;
                String c = Constellations.at(f.dir(u, v));
                double[] a = area.get(c);
                if (a == null) area.put(c, a = new double[3]);
                a[0]++;
                a[1] += u;
                a[2] += v;
            }
        }
        String mid = Constellations.at(f.dir(0.5, 0.5));
        centre = BrightStars.constellation(mid);
        List<Item> cons = new ArrayList<Item>();
        for (Map.Entry<String, double[]> e : area.entrySet()) {
            double[] a = e.getValue();
            Item it = new Item("constellation", BrightStars.constellation(e.getKey()));
            it.share = a[0] / (gx * gy);
            it.x = a[1] / a[0];
            it.y = a[2] / a[0];
            it.where = where(it.x, it.y);
            cons.add(it);
        }
        Collections.sort(cons, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Double.compare(b.share, a.share);
            }
        });
        items.addAll(cons);
        figures(f, cons);
        List<String> around = new ArrayList<String>();
        for (Item it : cons) {
            if (it.name.equals(centre) || it.share < 0.05 || around.size() >= 5) continue;
            around.add(it.name + " " + it.where);
        }
        lines.add("The picture is centred on " + centre + (around.isEmpty() ? "." : ", with " + list(around) + "."));

        // the brightest named stars in it
        if (catalog != null) {
            List<Item> stars = new ArrayList<Item>();
            for (int k = 0; k < catalog.n; k++) {
                if (catalog.vmag[k] > 3.5f || catalog.name[k].isEmpty()) continue;
                double[] uv = f.near(catalog.vec(k));
                if (uv == null || !inside(uv)) continue;
                Item it = new Item("star", catalog.name[k]);
                it.mag = catalog.vmag[k];
                it.x = uv[0];
                it.y = uv[1];
                it.where = where(uv[0], uv[1]);
                stars.add(it);
            }
            Collections.sort(stars, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return Double.compare(a.mag, b.mag);
                }
            });
            List<String> names = new ArrayList<String>();
            for (int k = 0; k < stars.size() && k < 5; k++) {
                Item it = stars.get(k);
                items.add(it);
                names.add(it.name + " (" + spot(it.x, it.y) + ")");
            }
            if (!names.isEmpty()) {
                lines.add((names.size() == 1 ? "The brightest named star is " : "The brightest named stars are ") + list(names) + ".");
            }
        }

        if (timed) {
            double jdTt = Astro.jdTt(jdUtc);
            // planets in the picture, or just outside it
            List<String> in = new ArrayList<String>(), near = new ArrayList<String>();
            for (Planets.Seen p : Planets.all(jdTt)) {
                double[] uv = f.near(p.dir, WIDE);
                if (uv == null) continue;
                boolean inPic = inside(uv);
                boolean nearPic = !inPic && uv[0] > -0.15 && uv[0] < 1.15 && uv[1] > -0.15 && uv[1] < 1.15;
                if (!inPic && !nearPic) continue;
                if (p.mag > 9) continue;
                Item it = new Item("planet", p.name);
                it.mag = p.mag;
                if (inPic) {
                    it.x = uv[0];
                    it.y = uv[1];
                }
                it.where = where(uv[0], uv[1]);
                items.add(it);
                if (inPic) in.add(p.name + " (" + brightness(p.mag) + ", " + spot(uv[0], uv[1]) + ")");
                else near.add(p.name + " (past the " + edgeName(uv) + " edge)");
            }
            if (in.size() == 1) lines.add(in.get(0).replaceFirst(" \\(", " is in the picture (") + ".");
            else if (!in.isEmpty()) lines.add("Planets in the picture: " + list(in) + ".");
            if (!near.isEmpty()) {
                lines.add(list(near) + (near.size() == 1 ? " is" : " are") + " just outside the picture.");
            }
            moon(f, jdUtc, sky);
        }

        milkyWay(f);

        // clusters, nebulae and galaxies
        List<Object[]> objs = new ArrayList<Object[]>();
        for (Object[] o : OBJECTS) {
            double[] uv = f.near(Astro.vec((Double) o[1] * Astro.DEG, (Double) o[2] * Astro.DEG));
            if (uv == null || !inside(uv) || edge(uv) < 0.03) continue;
            objs.add(new Object[]{o, uv});
        }
        List<String> look = new ArrayList<String>();
        for (int k = 0; k < objs.size() && k < 3; k++) {
            Object[] o = (Object[]) objs.get(k)[0];
            double[] uv = (double[]) objs.get(k)[1];
            Item it = new Item("object", (String) o[0]);
            it.mag = (Double) o[3];
            it.x = uv[0];
            it.y = uv[1];
            it.where = where(uv[0], uv[1]);
            items.add(it);
            look.add(it.name + " " + it.where + " (" + looks((Character) o[4]) + ")");
        }
        if (!look.isEmpty()) lines.add("Look for " + list(look) + ".");
    }

    /**
     * How much further than {@link Frame#reach} things just outside the picture are looked for (a planet past an edge, the
     * Milky Way beside the picture): the lens model still holds there for any lens a phone has.
     */
    static final double WIDE = 1.4;

    /** Longest step along a figure's line between two of its stars (radians): the lens bends it no more than that shows. */
    static final double FIGURE_STEP = 2 * Astro.DEG;
    /** How far past the picture a figure's line is kept (shares of the picture), so it runs off the edge. */
    static final double FIGURE_MARGIN = 0.05;
    /** How far in from the edges a constellation's name point must be to be used (shares of the picture). */
    static final double NAME_INSET = 0.04;

    /**
     * The constellations' figures in the picture: each line between two stars, followed along the sky's great circle in
     * steps of {@link #FIGURE_STEP} and kept where the lens model holds and near the picture; and for the constellations
     * {@code cons} whose figure shows, their names placed inside it rather than at the middle of their part of the picture.
     */
    private void figures(Frame f, List<Item> cons) {
        Map<String, Item> byAbbr = new LinkedHashMap<String, Item>();
        for (Item it : cons) byAbbr.put(it.name, it);
        for (Figures.Figure fig : Figures.all()) {
            int before = figures.size();
            double su = 0, sv = 0;
            int seen = 0;
            for (double[][] run : fig.runs) {
                for (int k = 0; k + 1 < run.length; k++) segment(f, fig.abbr, run[k], run[k + 1]);
                for (double[] star : run) {
                    double[] uv = f.near(star);
                    if (uv == null || !inside(uv)) continue;
                    su += uv[0];
                    sv += uv[1];
                    seen++;
                }
            }
            Item it = byAbbr.get(BrightStars.constellation(fig.abbr));
            if (it == null || figures.size() == before || seen == 0) continue;
            double[] lab = f.near(fig.label);
            if (lab != null && edge(lab) >= NAME_INSET) {
                it.lx = lab[0];
                it.ly = lab[1];
            } else {
                // its stars in the picture (counted once per line they're on)
                it.lx = su / seen;
                it.ly = sv / seen;
            }
        }
    }

    /** One line of a figure, from star {@code a} to star {@code b}: the parts of it near the picture. */
    private void segment(Frame f, String c, double[] a, double[] b) {
        double ang = Astro.sep(a, b);
        if (!(ang > 1e-9) || ang > Math.PI - 1e-6) return;
        int n = Math.max(1, (int) Math.ceil(ang / FIGURE_STEP));
        double[][] uv = new double[n + 1][];
        double sinA = Math.sin(ang);
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            double wa = Math.sin((1 - t) * ang) / sinA, wb = Math.sin(t * ang) / sinA;
            double[] d = {wa * a[0] + wb * b[0], wa * a[1] + wb * b[1], wa * a[2] + wb * b[2]};
            uv[i] = f.near(d);
        }
        for (int i = 0; i <= n; ) {
            if (uv[i] == null) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 <= n && uv[j + 1] != null) j++;
            run(c, uv, i, j, i == 0, j == n);
            i = j + 1;
        }
    }

    /** Points i0..i1 of a line (all where the lens model holds), cut to near the picture, as a {@link Line} if it shows. */
    private void run(String c, double[][] uv, int i0, int i1, boolean fromStar, boolean toStar) {
        double lo = -FIGURE_MARGIN, hi = 1 + FIGURE_MARGIN;
        int first = -1, last = -1;
        for (int i = i0; i <= i1; i++) {
            double[] p = uv[i];
            if (p[0] >= lo && p[0] <= hi && p[1] >= lo && p[1] <= hi) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) {
            // no point near the picture: it may still cross it between two points
            boolean crosses = false;
            for (int i = i0; i < i1 && !crosses; i++) crosses = crossesPicture(uv[i], uv[i + 1]);
            if (!crosses) return;
            first = i0;
            last = i1;
        }
        // one point past each end, so the line reaches the edge
        int s = Math.max(i0, first - 1), e = Math.min(i1, last + 1);
        if (e <= s) return;
        float[] pts = new float[2 * (e - s + 1)];
        for (int i = s; i <= e; i++) {
            pts[2 * (i - s)] = (float) uv[i][0];
            pts[2 * (i - s) + 1] = (float) uv[i][1];
        }
        figures.add(new Line(c, pts, fromStar && s == i0, toStar && e == i1));
    }

    /** Whether the straight line from p to q crosses the picture (0..1 each way). */
    static boolean crossesPicture(double[] p, double[] q) {
        // Liang-Barsky clipping against the unit square
        double t0 = 0, t1 = 1, dx = q[0] - p[0], dy = q[1] - p[1];
        double[] pp = {-dx, dx, -dy, dy}, qq = {p[0], 1 - p[0], p[1], 1 - p[1]};
        for (int k = 0; k < 4; k++) {
            if (pp[k] == 0) {
                if (qq[k] < 0) return false;
                continue;
            }
            double t = qq[k] / pp[k];
            if (pp[k] < 0) {
                if (t > t1) return false;
                if (t > t0) t0 = t;
            } else {
                if (t < t0) return false;
                if (t < t1) t1 = t;
            }
        }
        return t0 <= t1;
    }

    /** The Moon: in the picture, or up and where, with its phase. */
    private void moon(Frame f, double jdUtc, SessionSky sky) {
        double jdTt = Astro.jdTt(jdUtc);
        double[] m = Astro.moonOfDate(jdTt);
        double illum = Astro.moonIllumination(jdTt);
        double[] dir;
        double alt = Double.NaN;
        if (sky != null) {
            double[] enu = Astro.topocentric(m, m[3], jdUtc, sky.latDeg * Astro.DEG, sky.lonDeg * Astro.DEG);
            alt = Astro.altitude(enu) / Astro.DEG;
            double[] M = Astro.j2000ToEnu(jdUtc, sky.latDeg * Astro.DEG, sky.lonDeg * Astro.DEG);
            dir = new double[3];
            Rot.apply(Rot.transpose(M), enu[0], enu[1], enu[2], dir);
        } else {
            dir = new double[3];
            Rot.apply(Rot.transpose(Astro.j2000ToDate(jdTt)), m[0], m[1], m[2], dir);
        }
        double[] uv = f.near(dir);
        String phase = String.format(Locale.US, "%.0f%% lit", 100 * illum);
        Item it = new Item("moon", "the Moon");
        it.mag = -12.7 + 2.5 * Math.log10(1 / Math.max(0.005, illum));
        if (uv != null && inside(uv)) {
            it.x = uv[0];
            it.y = uv[1];
            it.where = where(uv[0], uv[1]);
            items.add(it);
            lines.add("The Moon (" + phase + ") is in the picture " + it.where + ", so expect glare around it.");
        } else if (!Double.isNaN(alt) && alt > 0) {
            double off = Astro.sep(dir, f.dir(0.5, 0.5)) / Astro.DEG;
            it.where = String.format(Locale.US, "%.0f degrees from the middle of the picture", off);
            items.add(it);
            lines.add(String.format(Locale.US, "The Moon was up (%s, %.0f degrees above the horizon), outside the picture.", phase, alt));
        }
    }

    /** The Milky Way: where the galactic plane crosses the picture, and through which constellations. */
    private void milkyWay(Frame f) {
        List<double[]> pts = new ArrayList<double[]>();
        int bestStart = -1, bestLen = 0, runStart = -1, runLen = 0;
        double[][] uvs = new double[361][];
        for (int l = 0; l <= 360; l++) {
            double[] uv = f.near(galactic(l * Astro.DEG, 0));
            uvs[l] = uv != null && inside(uv) ? uv : null;
        }
        // the longest run of degrees inside, allowing for the wrap at 360
        for (int k = 0; k < 720; k++) {
            int l = k % 360;
            if (uvs[l] != null) {
                if (runLen == 0) runStart = k;
                runLen++;
                if (runLen > bestLen && runLen <= 360) {
                    bestLen = runLen;
                    bestStart = runStart;
                }
            } else {
                runLen = 0;
            }
        }
        if (bestLen >= 2) {
            List<String> through = new ArrayList<String>();
            for (int k = bestStart; k < bestStart + bestLen; k++) {
                int l = k % 360;
                pts.add(uvs[l]);
                String c = BrightStars.constellation(Constellations.at(galactic(l * Astro.DEG, 0)));
                if (!through.contains(c)) through.add(c);
            }
            double[] a = pts.get(0), b = pts.get(pts.size() - 1);
            String from = edgeName(a), to = edgeName(b);
            Item it = new Item("milky way", "the Milky Way");
            it.where = from.equals(to) ? "across the " + from : "from the " + from + " to the " + to;
            items.add(it);
            while (through.size() > 4) through.remove(through.size() - 1);
            lines.add("The Milky Way runs " + it.where + " of the picture, through " + list(through) + ".");
            double[] core = f.near(galactic(0, 0));
            if (core != null && inside(core)) {
                lines.add("The bright centre of our galaxy, in Sagittarius, is " + where(core[0], core[1]) + ".");
            }
            return;
        }
        // not in the picture: near it, or looking out of the galaxy
        double minB = Double.MAX_VALUE;
        for (int j = 0; j <= 4; j++) {
            for (int i = 0; i <= 4; i++) {
                minB = Math.min(minB, Math.abs(Astro.galacticLatitude(f.dir(i / 4.0, j / 4.0))));
            }
        }
        minB /= Astro.DEG;
        if (minB < 15) {
            double best = Double.MAX_VALUE;
            double[] near = null;
            for (int l = 0; l < 360; l++) {
                double[] uv = f.near(galactic(l * Astro.DEG, 0), WIDE);
                if (uv == null) continue;
                double d = Math.max(Math.max(-uv[0], uv[0] - 1), Math.max(-uv[1], uv[1] - 1));
                if (d < best) {
                    best = d;
                    near = uv;
                }
            }
            if (near != null) lines.add("The Milky Way is just outside the picture, past the " + edgeName(near) + " edge.");
        } else if (minB > 40) {
            lines.add("This part of the sky looks out of our galaxy, away from the Milky Way: fewer stars, and the "
                    + "direction to find faint galaxies.");
        }
    }

    /** Without a solution: what was straight up from the place at the time. */
    private void overhead(double jdUtc, SessionSky sky, BrightStars catalog) {
        double lat = sky.latDeg * Astro.DEG, lon = sky.lonDeg * Astro.DEG;
        double[] M = Astro.j2000ToEnu(jdUtc, lat, lon);
        double[] up = new double[3];
        Rot.apply(Rot.transpose(M), 0, 0, 1, up);
        centre = BrightStars.constellation(Constellations.at(up));
        // constellations within 25 degrees of the zenith, by share of that cap
        Map<String, Integer> count = new LinkedHashMap<String, Integer>();
        int total = 0;
        for (int i = 0; i < 400; i++) {
            // a spiral of points over the cap
            double r = 25 * Astro.DEG * Math.sqrt((i + 0.5) / 400), a = i * 2.39996;
            double e = Math.sin(r) * Math.sin(a), n = Math.sin(r) * Math.cos(a), u = Math.cos(r);
            double[] d = new double[3];
            Rot.apply(Rot.transpose(M), e, n, u, d);
            String c = BrightStars.constellation(Constellations.at(d));
            Integer k = count.get(c);
            count.put(c, k == null ? 1 : k + 1);
            total++;
        }
        List<String> others = new ArrayList<String>();
        for (Map.Entry<String, Integer> en : count.entrySet()) {
            if (!en.getKey().equals(centre) && en.getValue() >= 0.06 * total && others.size() < 4) others.add(en.getKey());
        }
        Item it = new Item("constellation", centre);
        items.add(it);
        lines.add("The camera looked straight up, into " + centre + (others.isEmpty() ? "" : ", with " + list(others) + " around it")
                + " (the stars couldn't be matched, so this is from the place and time).");
        if (catalog != null) {
            int[] near = catalog.within(up, 30 * Astro.DEG, 2.5);
            List<String> names = new ArrayList<String>();
            for (int k : near) if (!catalog.name[k].isEmpty() && names.size() < 4) names.add(catalog.name[k]);
            if (!names.isEmpty()) lines.add("Bright stars high overhead: " + list(names) + ".");
        }
        List<String> pl = new ArrayList<String>();
        for (Planets.Seen p : Planets.all(Astro.jdTt(jdUtc))) {
            if (p.mag < 6 && Astro.sep(p.dir, up) < 35 * Astro.DEG) pl.add(p.name);
        }
        if (!pl.isEmpty()) lines.add(list(pl) + (pl.size() == 1 ? " was" : " were") + " high overhead.");
        if (Math.abs(Astro.galacticLatitude(up)) < 20 * Astro.DEG) lines.add("The Milky Way passed overhead.");
    }

    /** The picture's mapping to the sky. */
    static final class Frame {
        final SkySolver.Solution sol;
        final int x0, y0, w, h;

        /**
         * How far from the lens' axis its model is trusted, as the tangent of the angle: a little past the picture's
         * farthest corner or edge (the distortion's polynomial can turn back further out, and would put the sky behind
         * the camera's view in the picture).
         */
        final double reach;
        /** Camera bearings to J2000 (the solution's) and back. */
        private final double[] Rt;

        Frame(SkySolver.Solution sol, int x0, int y0, int w, int h) {
            this.sol = sol;
            this.x0 = x0;
            this.y0 = y0;
            this.w = w;
            this.h = h;
            double r = 0;
            double[] b = new double[3];
            for (int j = 0; j <= 2; j++) {
                for (int i = 0; i <= 2; i++) {
                    if (i == 1 && j == 1) continue;
                    sol.model.unproject(x0 + i * w / 2.0, y0 + j * h / 2.0, b);
                    if (b[2] > 1e-6) r = Math.max(r, Math.hypot(b[0] / b[2], b[1] / b[2]));
                }
            }
            reach = r > 0 ? r * 1.08 : Double.MAX_VALUE;
            Rt = Rot.transpose(sol.R);
        }

        /**
         * {@link #uv} of a J2000 direction where the lens model holds ({@link #reach}), which takes in the picture and a
         * little around it; null further out.
         */
        double[] near(double[] j2000) {
            return near(j2000, 1);
        }

        /** {@link #near} out to {@code reachFactor} times {@link #reach}. */
        double[] near(double[] j2000, double reachFactor) {
            double[] b = new double[3];
            Rot.apply(Rt, j2000[0], j2000[1], j2000[2], b);
            if (b[2] <= 1e-6 || Math.hypot(b[0] / b[2], b[1] / b[2]) > reach * reachFactor) return null;
            return uv(j2000);
        }

        double[] dir(double u, double v) {
            return sol.direction(x0 + u * w, y0 + v * h);
        }

        /** {u, v} of a J2000 direction (0..1 inside the picture), or null if behind the camera. */
        double[] uv(double[] j2000) {
            double[] p = sol.pixel(j2000);
            if (p == null) return null;
            return new double[]{(p[0] - x0) / w, (p[1] - y0) / h};
        }
    }

    static boolean inside(double[] uv) {
        return uv[0] >= 0 && uv[0] <= 1 && uv[1] >= 0 && uv[1] <= 1;
    }

    /** Distance to the nearest edge, in shares of the picture (negative outside). */
    static double edge(double[] uv) {
        return Math.min(Math.min(uv[0], 1 - uv[0]), Math.min(uv[1], 1 - uv[1]));
    }

    /** The edge nearest to a point inside, or the one it is past outside. */
    static String edgeName(double[] uv) {
        double l = uv[0], r = 1 - uv[0], t = uv[1], b = 1 - uv[1];
        double m = Math.min(Math.min(l, r), Math.min(t, b));
        if (m == l) return "left";
        if (m == r) return "right";
        if (m == t) return "top";
        return "bottom";
    }

    /** Where a point is in the picture, as a phrase: "at the top left", "near the centre", "at the left edge". */
    public static String where(double u, double v) {
        if (u < 0 || u > 1 || v < 0 || v > 1) {
            return "just outside the picture, past the " + edgeName(new double[]{u, v}) + " edge";
        }
        if (Math.abs(u - 0.5) < 0.17 && Math.abs(v - 0.5) < 0.17) return "near the centre";
        String vs = v < 1 / 3.0 ? "top" : v > 2 / 3.0 ? "bottom" : "";
        String hs = u < 1 / 3.0 ? "left" : u > 2 / 3.0 ? "right" : "";
        boolean nearEdge = u < 0.06 || u > 0.94 || v < 0.06 || v > 0.94;
        if (!vs.isEmpty() && !hs.isEmpty()) {
            boolean corner = (u < 0.12 || u > 0.88) && (v < 0.12 || v > 0.88);
            return (corner ? "in the " : "at the ") + vs + " " + hs + (corner ? " corner" : "");
        }
        if (nearEdge) return "at the " + (vs.isEmpty() ? hs : vs) + " edge";
        if (!vs.isEmpty()) return "near the " + vs;
        if (!hs.isEmpty()) return "on the " + hs;
        return "near the centre";
    }

    /** The same in a word or two, for lists: "top left", "centre", "left edge". */
    public static String spot(double u, double v) {
        String w = where(u, v);
        for (String p : new String[]{"in the ", "at the ", "near the ", "on the "}) {
            if (w.startsWith(p)) return w.substring(p.length());
        }
        return w;
    }

    static String brightness(double mag) {
        if (mag < -3) return "dazzling";
        if (mag < -1.5) return "very bright";
        if (mag < 1) return "bright";
        if (mag < 6) return String.format(Locale.US, "magnitude %.1f", mag);
        return String.format(Locale.US, "faint, magnitude %.1f", mag);
    }

    /** "a", "a and b", "a, b and c". */
    static String list(List<String> s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.size(); i++) {
            if (i > 0) sb.append(i == s.size() - 1 ? " and " : ", ");
            sb.append(s.get(i));
        }
        return sb.toString();
    }
}
