package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.Json;

import java.util.List;
import java.util.Locale;

/**
 * Everything named in a picture, for the viewer's Star Spotter and its 3D flight (2.3): the catalogue's stars in it with
 * how far away they are and what kind of star each is, the constellations' figures and names, the planets and the Moon,
 * the middle line of the Milky Way, the clusters, nebulae and galaxies, and the stars named by you. Everything is placed
 * by the picture's own sky solution (the live reference frame's while capturing, the stack's once developed), in that
 * frame's pixels, so the viewer draws it over the picture it shows by the picture's crop. The camera's pointing comes
 * too (the J2000 directions of the crop's middle and of the middles of its right and top edges): the 3D flight starts
 * from it, so its first view is the photo's.
 */
public final class SkyMap {
    /** Stars this far outside the crop are still listed (shares of it), so a name at the edge isn't cut off. */
    static final double MARGIN = 0.03;
    /** Faintest catalogue star listed. */
    static final double MAX_MAG = 6.6;

    public final SkySolver.Solution sol;
    /** The reference frame's time (UTC Julian date; NaN if unknown) and size. */
    public final double jdUtc;
    public final SessionSky sky;
    public final BrightStars cat;
    /** "live" (the live stack's reference frame) or "photo" (the developed stack). */
    public final String source;

    public SkyMap(SkySolver.Solution sol, double jdUtc, SessionSky sky, BrightStars cat, String source) {
        this.sol = sol;
        this.jdUtc = jdUtc;
        this.sky = sky;
        this.cat = cat;
        this.source = source;
    }

    /** J2000 direction of a reference-frame pixel. */
    public double[] direction(double x, double y) {
        return sol.direction(x, y);
    }

    /** Reference-frame pixel of a J2000 direction, or null if behind the camera. */
    public double[] pixel(double[] j2000) {
        return sol.pixel(j2000);
    }

    /** The catalogue star nearest pixel (x, y) within {@code radiusPx}, brighter than MAX_MAG; -1 if none. */
    public int catalogueStarNear(double x, double y, double radiusPx) {
        if (cat == null) return -1;
        double[] d = sol.direction(x, y);
        // a pixel's angle near there, for the search radius on the sky
        double[] d1 = sol.direction(x + 1, y);
        double perPx = Astro.sep(d, d1);
        int[] near = cat.within(d, Math.max(perPx * radiusPx, 1e-6), MAX_MAG);
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int k : near) {
            double[] p = sol.pixel(cat.vec(k));
            if (p == null) continue;
            double dd = Math.hypot(p[0] - x, p[1] - y);
            if (dd <= radiusPx && dd < bestD) {
                bestD = dd;
                best = k;
            }
        }
        return best;
    }

    /**
     * The JSON for the picture whose crop is (x0, y0, w, h) in the reference frame's pixels, with the stars named by you
     * ({@code mine}, may be null).
     */
    public String json(int x0, int y0, int w, int h, List<NamedStars.Star> mine) {
        SkyGuide.Frame f = new SkyGuide.Frame(sol, x0, y0, w, h);
        Json j = new Json().obj();
        j.put("source", source);
        j.arr("crop").val(x0).val(y0).val(w).val(h).end();
        if (!Double.isNaN(jdUtc)) j.put("jd", jdUtc, 15);
        if (sky != null && sky.located()) j.put("lat", sky.latDeg, 6).put("lon", sky.lonDeg, 7);
        j.put("rms", sol.rmsPx, 3).put("matched", sol.matched);

        // where the camera pointed, for the 3D flight
        double[] c = f.dir(0.5, 0.5), r = f.dir(1, 0.5), u = f.dir(0.5, 0);
        j.obj("camera");
        vec(j, "c", c);
        vec(j, "r", r);
        vec(j, "u", u);
        j.put("fovX", 2 * Astro.sep(c, r) / Astro.DEG, 5).put("fovY", 2 * Astro.sep(c, u) / Astro.DEG, 5);
        j.end();

        // the catalogue's stars in the picture: [x, y, V, B-V, light-years, name, label, constellation, type, ra, dec, row]
        j.arr("stars");
        if (cat != null) {
            for (int k = 0; k < cat.n; k++) {
                if (cat.vmag[k] > MAX_MAG) continue;
                double[] uv = f.near(cat.vec(k));
                if (uv == null || !within(uv, MARGIN)) continue;
                double px = x0 + uv[0] * w, py = y0 + uv[1] * h;
                double[] rd = Astro.raDec(cat.vec(k));
                j.arr().val(Math.round(px * 10) / 10.0).val(Math.round(py * 10) / 10.0).val(cat.vmag[k], 3);
                if (Float.isNaN(cat.bv[k])) j.val((String) null);
                else j.val(cat.bv[k], 3);
                if (Float.isNaN(cat.ly[k])) j.val((String) null);
                else j.val(cat.ly[k], 4);
                String label = cat.letter[k].isEmpty() || cat.con[k].isEmpty() ? "" : cat.letter[k] + " " + BrightStars.genitive(cat.con[k]);
                j.val(cat.name[k]).val(label).val(cat.con[k]).val(cat.spect[k] == null ? "" : cat.spect[k]);
                j.val(rd[0] / Astro.DEG, 7).val(rd[1] / Astro.DEG, 7).val(k);
                j.end();
            }
        }
        j.end();

        // what the guide names: constellations (with their figures), planets, the Moon, the Milky Way, deep-sky objects
        SkyGuide g = SkyGuide.describe(sol, jdUtc, sky, cat, x0, y0, w, h);
        j.put("centre", g.centre);
        j.arr("cons");
        for (SkyGuide.Item it : g.items) {
            if (!"constellation".equals(it.kind)) continue;
            double lx = !Double.isNaN(it.lx) ? it.lx : it.x, ly = !Double.isNaN(it.lx) ? it.ly : it.y;
            j.obj().put("n", it.name).put("share", Math.round(it.share * 1000) / 1000.0);
            if (!Double.isNaN(lx)) j.put("x", x0 + lx * w, 6).put("y", y0 + ly * h, 6);
            j.end();
        }
        j.end();
        j.arr("lines");
        for (SkyGuide.Line l : g.figures) {
            j.obj().put("c", l.c).arr("p");
            for (int i = 0; i + 1 < l.pts.length; i += 2) {
                j.val(Math.round((x0 + l.pts[i] * w) * 10) / 10.0).val(Math.round((y0 + l.pts[i + 1] * h) * 10) / 10.0);
            }
            j.end().end();
        }
        j.end();

        // the figures of the constellations in the picture, whole, with their stars' distances: for the 3D flight
        j.arr("figs3d");
        if (cat != null) {
            java.util.LinkedHashSet<String> abbrs = new java.util.LinkedHashSet<String>();
            for (SkyGuide.Line l : g.figures) abbrs.add(l.c);
            for (String a : abbrs) {
                Figures.Figure fig = Figures.of(a);
                if (fig == null) continue;
                j.obj().put("c", a).put("n", BrightStars.constellation(a)).arr("r");
                for (double[][] run : fig.runs) {
                    j.arr();
                    for (double[] v : run) {
                        int[] near = cat.within(v, 0.25 * Astro.DEG, 7);
                        int k = near.length > 0 ? near[0] : -1;
                        double[] rd = Astro.raDec(v);
                        j.arr().val(Astro.norm(rd[0]) / Astro.DEG, 6).val(rd[1] / Astro.DEG, 6);
                        if (k >= 0 && !Float.isNaN(cat.ly[k])) j.val(cat.ly[k], 4);
                        else j.val((String) null);
                        if (k >= 0) j.val(cat.vmag[k], 3);
                        else j.val((String) null);
                        if (k >= 0 && !Float.isNaN(cat.bv[k])) j.val(cat.bv[k], 3);
                        else j.val((String) null);
                        j.end();
                    }
                    j.end();
                }
                j.end().end();
            }
        }
        j.end();

        j.arr("planets");
        if (!Double.isNaN(jdUtc)) {
            double jdTt = Astro.jdTt(jdUtc);
            for (Planets.Seen p : Planets.all(jdTt)) {
                double[] uv = f.near(p.dir, SkyGuide.WIDE);
                if (uv == null || !within(uv, 0.15) || p.mag > 9) continue;
                j.obj().put("n", p.name).put("m", p.mag, 3).put("au", p.distAu, 5);
                j.put("x", x0 + uv[0] * w, 6).put("y", y0 + uv[1] * h, 6).put("in", within(uv, 0));
                j.end();
            }
        }
        j.end();

        // the spacecraft leaving the Solar System, L2 and Pluto (2.4): in the picture, with how far and what to say
        j.arr("craft");
        if (!Double.isNaN(jdUtc)) {
            for (DeepSpace.Seen s : DeepSpace.all(Astro.jdTt(jdUtc))) {
                double[] uv = f.near(s.dir);
                if (uv == null || !within(uv, 0)) continue;
                j.obj().put("n", s.name).put("k", s.kind).put("au", s.distAu, 5).put("lh", s.lightHours(), 4);
                if (!Double.isNaN(s.mag)) j.put("m", s.mag, 3);
                if (!Double.isNaN(s.kmPerS)) j.put("kms", s.kmPerS, 4);
                if (s.launched > 0) j.put("launched", s.launched).put("talking", s.talking);
                j.put("fact", s.fact).put("x", x0 + uv[0] * w, 6).put("y", y0 + uv[1] * h, 6).end();
            }
        }
        j.end();

        for (SkyGuide.Item it : g.items) {
            if ("moon".equals(it.kind)) {
                j.obj("moon");
                if (!Double.isNaN(it.x)) j.put("x", x0 + it.x * w, 6).put("y", y0 + it.y * h, 6);
                j.put("where", it.where).put("m", it.mag, 3);
                if (!Double.isNaN(jdUtc)) j.put("lit", Astro.moonIllumination(Astro.jdTt(jdUtc)), 3);
                j.end();
            }
        }

        // the Milky Way's middle line (the galactic equator) through the picture, and the galaxy's centre if it shows
        j.arr("milky");
        for (int l = 0; l < 360; l++) {
            double[] uv = f.near(SkyGuide.galactic(l * Astro.DEG, 0));
            if (uv == null || !within(uv, 0.02)) continue;
            j.arr().val(l).val(Math.round((x0 + uv[0] * w) * 10) / 10.0).val(Math.round((y0 + uv[1] * h) * 10) / 10.0).end();
        }
        j.end();
        double[] core = f.near(SkyGuide.galactic(0, 0));
        if (core != null && within(core, 0)) j.arr("core").val(x0 + core[0] * w, 6).val(y0 + core[1] * h, 6).end();

        j.arr("objects");
        for (Object[] o : SkyGuide.OBJECTS) {
            double[] uv = f.near(Astro.vec((Double) o[1] * Astro.DEG, (Double) o[2] * Astro.DEG));
            if (uv == null || !within(uv, 0)) continue;
            j.obj().put("n", (String) o[0]).put("k", String.valueOf((Character) o[4])).put("m", (Double) o[3], 3);
            if (o.length > 5) j.put("ly", (Double) o[5], 4);
            j.put("x", x0 + uv[0] * w, 6).put("y", y0 + uv[1] * h, 6).end();
        }
        j.end();

        j.arr("mine");
        if (mine != null) {
            for (NamedStars.Star s : mine) {
                double[] uv = f.near(s.dir());
                if (uv == null || !within(uv, MARGIN)) continue;
                j.obj().put("id", s.id).put("n", s.name).put("by", s.by).put("when", s.whenMs);
                j.put("x", x0 + uv[0] * w, 6).put("y", y0 + uv[1] * h, 6);
                if (!Double.isNaN(s.mag)) j.put("m", s.mag, 3);
                if (s.cat >= 0 && cat != null && s.cat < cat.n) {
                    if (!Float.isNaN(cat.ly[s.cat])) j.put("ly", cat.ly[s.cat], 4);
                    if (!Float.isNaN(cat.bv[s.cat])) j.put("bv", cat.bv[s.cat], 3);
                    j.put("sp", cat.spect[s.cat]);
                }
                j.end();
            }
        }
        j.end();
        return j.end().toString();
    }

    /**
     * The spacecraft leaving the Solar System, L2 and Pluto (2.4) in the crop (x0, y0, w, h): as {@link DeepSpace#all}
     * has them, those in it only; none if the time isn't known.
     */
    public List<DeepSpace.Seen> craft(int x0, int y0, int w, int h) {
        List<DeepSpace.Seen> out = new java.util.ArrayList<DeepSpace.Seen>();
        if (Double.isNaN(jdUtc)) return out;
        SkyGuide.Frame f = new SkyGuide.Frame(sol, x0, y0, w, h);
        for (DeepSpace.Seen s : DeepSpace.all(Astro.jdTt(jdUtc))) {
            double[] uv = f.near(s.dir);
            if (uv != null && within(uv, 0)) out.add(s);
        }
        return out;
    }

    /** Something in the picture, for its song and its 3D flight: where it is (shares of the crop) and what it is. */
    public static final class Thing {
        /**
         * "star", "planet", "moon", "object" (a cluster, nebula or galaxy), "mine" (a star named by you) or "craft" (a
         * spacecraft leaving the Solar System, 2.4).
         */
        public String kind = "star";
        public String name = "";
        public double u, v, mag = Double.NaN, bv = Double.NaN, ly = Double.NaN;
        /** Its J2000 direction, and its catalogue row (-1 if none). */
        public double[] dir;
        public int row = -1;
        /** A deep-sky object's kind letter (G galaxy, N nebula, C cluster ...), else 0. */
        public char objectKind;
    }

    /**
     * The catalogue's stars brighter than {@code maxMag}, the planets, the Moon, the deep-sky objects and the stars named
     * by you ({@code mine}, may be null) in the crop (x0, y0, w, h), brightest stars first.
     */
    public List<Thing> things(int x0, int y0, int w, int h, double maxMag, List<NamedStars.Star> mine) {
        SkyGuide.Frame f = new SkyGuide.Frame(sol, x0, y0, w, h);
        List<Thing> out = new java.util.ArrayList<Thing>();
        if (cat != null) {
            for (int k = 0; k < cat.n; k++) {
                if (cat.vmag[k] > maxMag) continue;
                double[] uv = f.near(cat.vec(k));
                if (uv == null || !within(uv, 0)) continue;
                Thing t = new Thing();
                t.u = uv[0];
                t.v = uv[1];
                t.mag = cat.vmag[k];
                t.bv = Float.isNaN(cat.bv[k]) ? Double.NaN : cat.bv[k];
                t.ly = Float.isNaN(cat.ly[k]) ? Double.NaN : cat.ly[k];
                t.name = cat.name[k];
                t.dir = cat.vec(k);
                t.row = k;
                out.add(t);
            }
            java.util.Collections.sort(out, new java.util.Comparator<Thing>() {
                @Override
                public int compare(Thing a, Thing b) {
                    return Double.compare(a.mag, b.mag);
                }
            });
        }
        if (!Double.isNaN(jdUtc)) {
            double jdTt = Astro.jdTt(jdUtc);
            for (Planets.Seen p : Planets.all(jdTt)) {
                double[] uv = f.near(p.dir, SkyGuide.WIDE);
                if (uv == null || !within(uv, 0) || p.mag > 9) continue;
                Thing t = new Thing();
                t.kind = "planet";
                t.name = p.name;
                t.u = uv[0];
                t.v = uv[1];
                t.mag = p.mag;
                t.dir = p.dir;
                // light-minutes, as light-years: a planet is minutes of light away
                t.ly = p.distAu * 1.58125e-5;
                out.add(t);
            }
            double[] moon = moonDir(jdUtc, sky);
            {
                double[] uv = f.near(moon, SkyGuide.WIDE);
                if (uv != null && within(uv, 0)) {
                    Thing t = new Thing();
                    t.kind = "moon";
                    t.name = "the Moon";
                    t.u = uv[0];
                    t.v = uv[1];
                    t.mag = -10;
                    t.dir = moon;
                    t.ly = 4.06e-8;
                    out.add(t);
                }
            }
        }
        // the spacecraft leaving the Solar System (2.4): too far to see, but they sing, as radio beeps
        if (!Double.isNaN(jdUtc)) {
            for (DeepSpace.Seen s : DeepSpace.all(Astro.jdTt(jdUtc))) {
                if (!"probe".equals(s.kind)) continue;
                double[] uv = f.near(s.dir);
                if (uv == null || !within(uv, 0)) continue;
                Thing t = new Thing();
                t.kind = "craft";
                t.name = s.name;
                t.u = uv[0];
                t.v = uv[1];
                t.dir = s.dir;
                t.ly = s.distAu * 1.58125e-5;
                out.add(t);
            }
        }
        for (Object[] o : SkyGuide.OBJECTS) {
            double[] d = Astro.vec((Double) o[1] * Astro.DEG, (Double) o[2] * Astro.DEG);
            double[] uv = f.near(d);
            if (uv == null || !within(uv, 0)) continue;
            Thing t = new Thing();
            t.kind = "object";
            t.name = (String) o[0];
            t.objectKind = (Character) o[4];
            t.mag = (Double) o[3];
            if (o.length > 5) t.ly = (Double) o[5];
            t.u = uv[0];
            t.v = uv[1];
            t.dir = d;
            out.add(t);
        }
        if (mine != null) {
            for (NamedStars.Star s : mine) {
                double[] uv = f.near(s.dir());
                if (uv == null || !within(uv, 0)) continue;
                Thing t = new Thing();
                t.kind = "mine";
                t.name = s.name;
                t.u = uv[0];
                t.v = uv[1];
                t.mag = s.mag;
                t.dir = s.dir();
                t.row = s.cat;
                if (s.cat >= 0 && cat != null && s.cat < cat.n) {
                    t.bv = Float.isNaN(cat.bv[s.cat]) ? Double.NaN : cat.bv[s.cat];
                    t.ly = Float.isNaN(cat.ly[s.cat]) ? Double.NaN : cat.ly[s.cat];
                    if (Double.isNaN(t.mag)) t.mag = cat.vmag[s.cat];
                }
                out.add(t);
            }
        }
        return out;
    }

    /** Where the galaxy's centre is in the crop (x0, y0, w, h): its pixel, or null if it isn't in it. */
    public double[] corePixel(int x0, int y0, int w, int h) {
        SkyGuide.Frame f = new SkyGuide.Frame(sol, x0, y0, w, h);
        double[] core = f.near(SkyGuide.galactic(0, 0));
        if (core == null || !within(core, 0)) return null;
        return new double[]{x0 + core[0] * w, y0 + core[1] * h};
    }

    /** The Milky Way's middle line (the galactic equator) in the crop, as [u, v] shares of it, a degree apart. */
    public double[][] milkyShares(int x0, int y0, int w, int h) {
        SkyGuide.Frame f = new SkyGuide.Frame(sol, x0, y0, w, h);
        List<double[]> out = new java.util.ArrayList<double[]>();
        for (int l = 0; l < 360; l++) {
            double[] uv = f.near(SkyGuide.galactic(l * Astro.DEG, 0));
            if (uv != null && within(uv, 0)) out.add(uv);
        }
        return out.toArray(new double[0][]);
    }

    /** The Moon's J2000 direction as seen from where the session was (from the Earth's centre if that's unknown). */
    static double[] moonDir(double jdUtc, SessionSky sky) {
        double jdTt = Astro.jdTt(jdUtc);
        double[] m = Astro.moonOfDate(jdTt);
        double[] dir = new double[3];
        if (sky != null && sky.located()) {
            double[] enu = Astro.topocentric(m, m[3], jdUtc, sky.latDeg * Astro.DEG, sky.lonDeg * Astro.DEG);
            double[] M = Astro.j2000ToEnu(jdUtc, sky.latDeg * Astro.DEG, sky.lonDeg * Astro.DEG);
            Rot.apply(Rot.transpose(M), enu[0], enu[1], enu[2], dir);
        } else {
            Rot.apply(Rot.transpose(Astro.j2000ToDate(jdTt)), m[0], m[1], m[2], dir);
        }
        return dir;
    }

    private static boolean within(double[] uv, double margin) {
        return uv[0] >= -margin && uv[0] <= 1 + margin && uv[1] >= -margin && uv[1] <= 1 + margin;
    }

    private static void vec(Json j, String name, double[] v) {
        j.arr(name).val(v[0], 8).val(v[1], 8).val(v[2], 8).end();
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "%s sky map: %d stars matched, %.2f px", source, sol.matched, sol.rmsPx);
    }
}
