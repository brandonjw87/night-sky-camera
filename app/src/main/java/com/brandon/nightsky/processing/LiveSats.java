package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Satellites while the viewer watches (2.3): the ones crossing the picture in the next minute, as paths in the live
 * stack's pixels for Mission Control to draw gliding over it (and for {@link StreakWatch} to look for in each frame), and
 * the bright ones passing anywhere over the sky in the next half hour or so (the Space Station above all), for an alarm
 * with where to look. The same predictions the develop step names its streaks by ({@link SatPredict}).
 * <p>
 * Crossings come from a coarse search of all the satellites (cached for a minute, it's the costly part) and a fine one
 * of the few near the picture; flyovers from the satellites bright enough to see with the eye (standard magnitude 4.5 or
 * brighter), which are a few hundred at most.
 */
public final class LiveSats {
    /** Satellites this bright or brighter (standard magnitude) are looked for over the whole sky. */
    static final double FLYOVER_STD_MAG = 4.5;
    /** How long a coarse search of the picture holds (seconds of the window it covers past its start). */
    static final double CANDIDATES_S = 120;

    private final List<SatCatalog.Sat> all;
    private final List<SatCatalog.Sat> bright = new ArrayList<SatCatalog.Sat>();
    public final SatSky.Site site;

    // the cached coarse search
    private double candJd0 = Double.NaN, candJd1 = Double.NaN;
    private double[] candAxis;
    private List<SatCatalog.Sat> cands = new ArrayList<SatCatalog.Sat>();

    /** A bright satellite's pass over the sky. */
    public static final class Flyover {
        public SatCatalog.Sat sat;
        /** When it comes up over {@code minAlt}, when it is brightest, when it goes (UTC Julian dates). */
        public double jdRise, jdBest, jdSet;
        public double magBest = 99, altBest, azRise, azBest, azSet;

        public String name() {
            return sat.displayName();
        }

        /** "rises in the northwest, goes over high in the south (60 degrees up), and sets in the east". */
        public String path() {
            return String.format(Locale.US, "comes up in the %s, %s in the %s (%.0f degrees up), and goes down in the %s",
                    compass(azRise), altBest > 70 ? "goes almost straight overhead" : altBest > 40 ? "climbs high" : "stays low",
                    compass(azBest), altBest, compass(azSet));
        }
    }

    public LiveSats(SatCatalog cat, SatSky.Site site) {
        this.all = cat.all();
        this.site = site;
        for (SatCatalog.Sat s : all) if (s.stdMag <= FLYOVER_STD_MAG) bright.add(s);
    }

    public int size() {
        return all.size();
    }

    /**
     * The satellites lit, brighter than {@code magLimit} and crossing within {@code padPx} of the W x H reference frame
     * between {@code jd0} and {@code jd1} (UTC Julian dates), as paths in its pixels (one track each, sampled every
     * {@link SatPredict#FINE_S}). The coarse search is reused while the window stays within the one it was made for.
     */
    public synchronized List<SatPredict.Track> crossing(SkySolver.Solution sol, int W, int H, double jd0, double jd1,
                                                        double magLimit, float padPx) {
        double[] field = SatPredict.fieldOf(sol, W, H);
        double[] axis = {field[0], field[1], field[2]};
        boolean fresh = !Double.isNaN(candJd0) && jd0 >= candJd0 && jd1 <= candJd1 && candAxis != null
                && SatPredict.dot(axis, candAxis) > Math.cos(0.5 * Astro.DEG);
        if (!fresh) {
            candJd0 = jd0;
            candJd1 = Math.max(jd1, jd0 + CANDIDATES_S / 86400);
            candAxis = axis;
            cands = SatPredict.candidates(all, site, candJd0, candJd1, axis, field[3], magLimit);
        }
        List<SatPredict.Exposure> win = new ArrayList<SatPredict.Exposure>();
        win.add(new SatPredict.Exposure(-1, jd0, (jd1 - jd0) * 86400));
        return SatPredict.tracks(cands, site, win, sol, W, H, magLimit, padPx);
    }

    /**
     * The bright satellites going over between {@code jd0} and {@code jd1} higher than {@code minAltDeg} and brighter than
     * {@code magLimit}, soonest first.
     */
    public List<Flyover> flyovers(double jd0, double jd1, double magLimit, double minAltDeg) {
        List<SatPredict.Pass> ps = SatPredict.passes(bright, site, jd0, jd1, new double[]{0, 0, 1}, 90 - minAltDeg, magLimit);
        List<Flyover> out = new ArrayList<Flyover>();
        SatSky.Look look = new SatSky.Look();
        for (SatPredict.Pass p : ps) {
            Flyover f = new Flyover();
            f.sat = p.sat;
            f.jdRise = p.jdIn;
            f.jdBest = p.jdBest;
            f.jdSet = p.jdOut;
            f.magBest = p.magBest;
            f.altBest = p.altBest;
            if (SatSky.look(p.sat.orbit, p.sat.stdMag, site.moment(p.jdIn), look)) f.azRise = look.azDeg;
            if (SatSky.look(p.sat.orbit, p.sat.stdMag, site.moment(p.jdBest), look)) f.azBest = look.azDeg;
            if (SatSky.look(p.sat.orbit, p.sat.stdMag, site.moment(p.jdOut), look)) f.azSet = look.azDeg;
            out.add(f);
        }
        Collections.sort(out, new Comparator<Flyover>() {
            @Override
            public int compare(Flyover a, Flyover b) {
                return Double.compare(a.jdRise, b.jdRise);
            }
        });
        return out;
    }

    /** A compass point for an azimuth (degrees east of north): "north", "northeast", ... */
    public static String compass(double azDeg) {
        String[] n = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};
        int k = (int) Math.floor(((azDeg % 360 + 360) % 360 + 22.5) / 45) % 8;
        return n[k];
    }
}
