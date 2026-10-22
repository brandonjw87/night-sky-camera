package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The check before a session starts: one test frame at the session's settings, read for what would spoil the
 * session while there is still time to fix it.
 * <ul>
 * <li>Lens: starlight scattered into a halo around the stars means a smudged or fogged lens ({@link HaloMeter}),
 * against what this camera's clean lens does.</li>
 * <li>Obstructions: something much darker than the sky, or with sharp edges, along the frame's edges (branches,
 * a roof), named by its compass side.</li>
 * <li>Clouds: catalogue stars that a clear sky would show and this one doesn't ({@link SkySolver} says which
 * stars should be where).</li>
 * <li>Sun and Moon: twilight, and the Moon in or near the frame during the session.</li>
 * <li>Clipping, storage and battery.</li>
 * </ul>
 * Each check gives a line for the screen and, when it matters, a few words for the spoken summary. Nothing is
 * changed by the check itself: it says what it found and suggests, the person decides.
 */
public final class Preflight {
    public enum Level { OK, NOTE, WARN, STOP }

    public static final class Item {
        public final String key;
        public final Level level;
        /** For the screen and the records. */
        public final String text;
        /** A few words for the spoken summary ("" for none). */
        public final String spoken;

        Item(String key, Level level, String text, String spoken) {
            this.key = key;
            this.level = level;
            this.text = text;
            this.spoken = spoken == null ? "" : spoken;
        }
    }

    public static final class Input {
        public short[] raw;
        public float invScale = 1f;
        /** Black level per CFA position for this frame; null for the sensor's. */
        public float[] black;
        public SensorSpec spec;
        /** The test frame's exposure (s) and ISO. */
        public double exposureS;
        public int iso;
        /** The session's frames: exposure (s) and ISO; and the session's length (s). */
        public double planExposureS;
        public int planIso;
        public double sessionSeconds = 300;
        public boolean trails;
        /** Place, clock and compass; null if none of it is known. */
        public SessionSky sky;
        /** Camera timestamp (ns) of the start of the test frame, on the clock of {@link #sky}. */
        public long timestampNs;
        /** "Up" in the phone's axes (Android sensor convention: x right, y to the top, z out of the screen), or null. */
        public float[] up;
        public BrightStars catalog;
        /** Halo ratio of this camera's clean lens (NaN: not learned yet). */
        public double haloBaseline = Double.NaN;
        /** Zero point of a clear night, per second at ISO 100 (NaN: not learned yet). */
        public double clearZeroPoint = Double.NaN;
        /** Free and needed storage (bytes; negative if unknown). */
        public long freeBytes = -1, needBytes = -1;
        public int batteryPct = -1;
        public float batteryTempC = Float.NaN;
        public boolean charging;
        /** Battery used per hour of capture (%), NaN for the default. */
        public double drainPctPerHour = Double.NaN;
        /** Satellites' orbital elements (null: none on the phone), and whether their streaks stay in the picture. */
        public SatCatalog satellites;
        public boolean keepSatellites;
        /** From the test frame to the session's start (ms): the summary, the countdown and settling. */
        public long startDelayMs = 15000;
        public TimeZone zone = TimeZone.getDefault();
    }

    public static final class Report {
        public final List<Item> items = new ArrayList<Item>();
        public int stars;
        public double haloRatio = Double.NaN, haloLimit = Double.NaN;
        public int haloStars;
        /** Catalogue stars a clear sky would show here, and how many of them showed. */
        public int expected, seen;
        public double seenFraction = Double.NaN;
        /** Where the hidden stars are (degrees east of north), NaN if nowhere in particular. */
        public double cloudAzimuthDeg = Double.NaN;
        /** Blocked share of the frame, and where (degrees east of north, or NaN). */
        public double obstructedFraction;
        public double obstructionAzimuthDeg = Double.NaN;
        public String obstructionSide = "";
        /** Blocked cells of the frame ({@link #CELL_PX} pixels each, row by row), or null. */
        public boolean[] obstructedCells;
        public int cellCols, cellRows;
        public double sunAltDeg = Double.NaN, moonAltDeg = Double.NaN, moonIllum = Double.NaN;
        /** The Moon's least angle from the frame during the session (0 = in it), degrees; NaN if unknown. */
        public double moonFromFrameDeg = Double.NaN;
        /** When astronomical night starts (Unix ms), if it hasn't yet; 0 otherwise. */
        public long darkAtMs;
        /** Per colour (R, G, B): sky background and clipped share at the session's settings (fractions of range). */
        public final float[] background = new float[3], clipped = new float[3];
        public int clippedStars;
        /** Zero point of the frame's clearest part, per second at ISO 100 (shading-corrected); NaN if unknown. */
        public double zeroPointNorm = Double.NaN;
        /** Magnitudes the clearest stars lose against a clear night (haze, thin cloud, dew); NaN if unknown. */
        public double hazeMag = Double.NaN;
        /** Zero point of the test frame itself (2.5 log10 of a star's flux plus its V magnitude); NaN if unknown. */
        public double zeroPointFrame = Double.NaN;
        /**
         * Sky brightness over the central 20 degrees, magnitudes per square arcsecond (green, close to V): between the
         * stars, and with them as a handheld meter (SQM-L) reads it. NaN without the stars' zero point.
         */
        public double skyMag = Double.NaN, skyMagWithStars = Double.NaN;
        /** The faintest catalogue star matched in the frame (V magnitude); NaN without a solution. */
        public double faintestMag = Double.NaN;
        /** The test frame's exposure (s) and ISO. */
        public double exposureS;
        public int iso;
        public SkySolver.Solution solution;
        public double jdUtc = Double.NaN;
        public double latDeg = Double.NaN, lonDeg = Double.NaN;
        public String placeSource = "";
        /** The place the stars and "up" give (NaN if they don't), whether or not it was used. */
        public double starLatDeg = Double.NaN, starLonDeg = Double.NaN;
        /** The satellites crossing the picture, and whether to start after a bright one; null if not checked. */
        public SatPlanner.Plan satellites;
        public long millis;

        public boolean hasWarnings() {
            for (Item it : items) if (it.level == Level.WARN || it.level == Level.STOP) return true;
            return false;
        }

        public Item item(String key) {
            for (Item it : items) if (it.key.equals(key)) return it;
            return null;
        }

        public Item stop() {
            for (Item it : items) if (it.level == Level.STOP) return it;
            return null;
        }

        /**
         * The spoken summary: "Ready. Clear sky, lens clean, about three hours of battery." when all is well,
         * else "Check: ..." with the two most important problems.
         */
        public String spoken() {
            List<String> warn = new ArrayList<String>();
            for (String k : PRIORITY) {
                Item it = item(k);
                if (it != null && (it.level == Level.WARN || it.level == Level.STOP) && !it.spoken.isEmpty()) warn.add(it.spoken);
            }
            if (!warn.isEmpty()) {
                StringBuilder sb = new StringBuilder("Check: ");
                for (int i = 0; i < Math.min(2, warn.size()); i++) {
                    if (i > 0) sb.append(' ');
                    sb.append(sentence(warn.get(i)));
                }
                return sb.toString();
            }
            List<String> ok = new ArrayList<String>();
            for (String k : READY_ORDER) {
                Item it = item(k);
                if (it != null && !it.spoken.isEmpty()) ok.add(it.spoken);
            }
            if (ok.isEmpty()) return "Ready.";
            StringBuilder sb = new StringBuilder("Ready. ");
            for (int i = 0; i < ok.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(i == 0 ? capital(ok.get(i)) : ok.get(i));
            }
            return sb.append('.').toString();
        }

        /** One line per check, for the screen. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (Item it : items) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(it.level == Level.OK ? "✓ " : it.level == Level.NOTE ? "• " : "! ").append(it.text);
            }
            return sb.toString();
        }

        public String toJson() {
            Json j = new Json().obj();
            j.put("summary", spoken());
            j.put("millis", millis);
            j.arr("items");
            for (Item it : items) {
                j.obj().put("check", it.key).put("level", it.level.name().toLowerCase(Locale.US)).put("text", it.text);
                if (!it.spoken.isEmpty()) j.put("spoken", it.spoken);
                j.end();
            }
            j.end();
            j.put("stars", stars);
            j.put("haloRatio", haloRatio).put("haloLimit", haloLimit).put("haloStars", haloStars);
            j.put("expectedStars", expected).put("seenStars", seen).put("seenFraction", seenFraction);
            j.put("cloudAzimuthDeg", cloudAzimuthDeg);
            j.put("obstructedFraction", obstructedFraction).put("obstructionAzimuthDeg", obstructionAzimuthDeg);
            j.put("obstructionSide", obstructionSide);
            if (obstructedCells != null) {
                StringBuilder sb = new StringBuilder();
                for (boolean b : obstructedCells) sb.append(b ? '1' : '0');
                j.put("obstructedCells", sb.toString()).put("cellCols", cellCols).put("cellRows", cellRows).put("cellPx", CELL_PX);
            }
            j.put("sunAltDeg", sunAltDeg).put("moonAltDeg", moonAltDeg).put("moonIllum", moonIllum);
            j.put("moonFromFrameDeg", moonFromFrameDeg);
            if (darkAtMs > 0) j.put("darkAtMs", darkAtMs);
            j.arr("background");
            for (float v : background) j.val(v);
            j.end().arr("clipped");
            for (float v : clipped) j.val(v);
            j.end();
            j.put("clippedStars", clippedStars);
            j.put("zeroPointNorm", zeroPointNorm).put("hazeMag", hazeMag);
            j.put("skyMag", skyMag).put("skyMagWithStars", skyMagWithStars);
            if (solution != null) {
                double[] c = solution.direction(solution.model.cx, solution.model.cy);
                double[] rd = Astro.raDec(c);
                j.obj("pointing").put("raDeg", rd[0] / Astro.DEG).put("decDeg", rd[1] / Astro.DEG)
                        .put("matched", solution.matched).put("rmsPx", solution.rmsPx).end();
            }
            j.put("latDeg", latDeg).put("lonDeg", lonDeg).put("placeSource", placeSource);
            j.put("starLatDeg", starLatDeg).put("starLonDeg", starLonDeg);
            if (satellites != null) {
                SatPlanner.Plan p = satellites;
                j.obj("satellites").put("text", p.text).put("askedStartMs", p.askedStartMs).put("startMs", p.startMs)
                        .put("keep", p.keep).put("startCost", p.startCost).put("millis", p.millis);
                if (p.waitFor != null) j.put("waitFor", p.waitFor.sat.displayName());
                if (p.quietStartMs != 0) j.put("quietStartMs", p.quietStartMs).put("quietCost", p.quietCost).put("offerQuiet", p.offerQuiet);
                j.arr("passes");
                for (SatPredict.Pass q : p.passes) {
                    j.obj().put("name", q.sat.displayName()).put("norad", q.sat.norad).put("inMs", SatPlanner.ms(q.jdIn))
                            .put("outMs", SatPlanner.ms(q.jdOut)).put("mag", Math.round(q.magBest * 10) / 10.0).end();
                }
                j.end().end();
            }
            return j.end().toString();
        }
    }

    /** "Only if clear" (E2): whether a scheduled session should start, and what the test frame saw. */
    public static final class Clear {
        public final boolean clear;
        /** "12 of 140 stars that should show, 9%" */
        public final String seen;

        Clear(boolean clear, String seen) {
            this.clear = clear;
            this.seen = seen;
        }
    }

    /**
     * Whether the sky is clear enough for a scheduled start: at least a third of the stars a clear night shows
     * here. That is the catalogue's stars for this field and exposure, times the share this site shows on its
     * clear nights ({@code siteShare}, NaN if unknown, for light pollution or a hazy horizon), so a site that
     * never shows them all isn't always "cloudy". A frame whose star patterns can't be found isn't clear: its
     * "stars" are mostly noise (an overcast sky or a covered lens still gives a few hundred faint spots).
     */
    public static Clear clearEnough(Report rep, double siteShare) {
        double share = Double.isNaN(siteShare) ? 1 : Math.max(0.3, Math.min(1, siteShare));
        double need = share / 3;
        if (rep.expected >= 12 && !Double.isNaN(rep.seenFraction)) {
            String seen = String.format(Locale.US, "%d of %d stars that should show, %d%%", rep.seen, rep.expected,
                    Math.round(100 * rep.seenFraction));
            if (!Double.isNaN(rep.hazeMag) && rep.hazeMag >= 2.5) {
                return new Clear(false, seen + "; even the clearest are " + String.format(Locale.US, "%.1f", rep.hazeMag)
                        + " magnitudes dimmer than on a clear night");
            }
            return new Clear(rep.seenFraction >= need, seen);
        }
        if (rep.solution == null) {
            if (rep.stars < 15) return new Clear(false, "only " + rep.stars + " stars: overcast, or something covers the lens");
            Item c = rep.item("clouds");
            if (c == null || c.level == Level.WARN || c.level == Level.STOP) {
                return new Clear(false, "no star patterns among " + rep.stars + " faint spots: cloud, haze or a covered lens");
            }
            // no catalogue to check against: a frame full of stars counts as clear
            return new Clear(rep.stars >= 60, rep.stars + " stars (not checked against the catalogue)");
        }
        // solved, but too few catalogue stars to test (a small patch of open sky): the stars found decide
        return new Clear(rep.stars >= 30, rep.stars + " stars");
    }

    /** Which problems the spoken summary names first. */
    static final String[] PRIORITY = {"storage", "sun", "clouds", "lens", "obstruction", "moon", "battery", "clipping"};
    /** What "Ready." goes on to say. */
    static final String[] READY_ORDER = {"clouds", "lens", "moon", "battery"};

    /** Background map: blocks of this many raw pixels; obstructions are decided per cell of CELL_PX. */
    static final int BLOCK = 32, CELL_PX = 192;
    /** Smallest blocked area, as a share of the frame, worth a mention, and worth a spoken warning. */
    static final double MIN_OBSTRUCTION = 0.008, WARN_OBSTRUCTION = 0.015;
    /** Before this camera's clean halo is known: a clean phone lens is taken to scatter this much. */
    static final double DEFAULT_CLEAN_HALO = 0.15;
    /** A place older than this (minutes) gives way to the stars' place if that is more than FAR_PLACE_DEG away. */
    static final double STALE_PLACE_MIN = 24 * 60, FAR_PLACE_DEG = 3;
    /** Battery used per hour of capture when not yet measured (%), and what developing takes (%). */
    static final double DEFAULT_DRAIN = 20, DEVELOP_PCT = 3;

    private Preflight() {}

    public static Report run(Input in) {
        long t0 = System.currentTimeMillis();
        Report rep = new Report();
        rep.exposureS = in.exposureS;
        rep.iso = in.iso;
        SensorSpec spec = in.spec;
        int W = spec.width, H = spec.height;
        CameraModel model = spec.model != null ? spec.model
                : CameraModel.nominal(W, H, W / (2 * Math.tan(Math.toRadians(35))));
        float[] black = in.black != null ? in.black : spec.black;
        double scale = in.exposureS > 0 && in.iso > 0 && in.planExposureS > 0 && in.planIso > 0
                ? in.planExposureS * in.planIso / (in.exposureS * in.iso) : 1;

        // stars
        StarDetector det = new StarDetector(spec);
        StarList stars = det.detect(in.raw, in.invScale, 2000);
        rep.stars = stars.count;
        float noise = Math.max(0.3f, stars.noise);

        // place and time
        SessionSky sky = in.sky != null ? in.sky : new SessionSky();
        double jd = Double.NaN;
        long utcMs = 0;
        if (sky.clockValid && in.timestampNs != 0) {
            utcMs = Math.round(sky.utcMs(in.timestampNs) + in.exposureS * 500);
            jd = Astro.jdUtc(utcMs / 1000.0);
        }
        rep.jdUtc = jd;
        if (sky.located()) {
            rep.latDeg = sky.latDeg;
            rep.lonDeg = sky.lonDeg;
            rep.placeSource = sky.locationSource;
        }

        // where the frame points
        if (in.catalog != null) {
            SkySolver.Prior pr = new SkySolver.Prior();
            pr.jdUtc = jd;
            if (sky.located()) {
                pr.lat = sky.latDeg * Astro.DEG;
                pr.lon = sky.lonDeg * Astro.DEG;
            }
            SkySolver.Solution s = SkySolver.solve(stars, null, model, in.catalog, pr);
            if (s == null && pr.located()) s = SkySolver.solve(stars, null, model, in.catalog, null);
            rep.solution = s;
            if (s != null) {
                for (int k = 0; k < s.matched; k++) {
                    float v = in.catalog.vmag[s.cat[k]];
                    if (v < 30 && !(v <= rep.faintestMag)) rep.faintestMag = v;
                }
            }
            double[] p = s != null && !Double.isNaN(jd) && in.up != null ? placeFromStars(s, in.up, jd) : null;
            if (p != null) {
                rep.starLatDeg = p[0];
                rep.starLonDeg = p[1];
                // no place, or an old one far from here (the phone's last fix before a trip): the stars' place
                boolean stale = sky.located() && !(sky.locationAgeMin < STALE_PLACE_MIN)
                        && Astro.sep(Astro.vec(p[1] * Astro.DEG, p[0] * Astro.DEG),
                        Astro.vec(sky.lonDeg * Astro.DEG, sky.latDeg * Astro.DEG)) > FAR_PLACE_DEG * Astro.DEG;
                if (!sky.located() || stale) {
                    rep.latDeg = p[0];
                    rep.lonDeg = p[1];
                    rep.placeSource = stale ? "stars (the saved place was far away)" : "stars";
                }
            }
        }
        double lat = rep.latDeg * Astro.DEG, lon = rep.lonDeg * Astro.DEG;
        boolean located = !Double.isNaN(lat) && !Double.isNaN(lon);
        // camera bearings to ENU (azimuths of what the frame shows), from the solution or the compass
        double[] camToEnu = null;
        if (rep.solution != null && located && !Double.isNaN(jd)) {
            camToEnu = Rot.mul(Astro.j2000ToEnu(jd, lat, lon), rep.solution.R);
        } else if (!Double.isNaN(sky.headingTopDeg)) {
            camToEnu = compassCamera(sky.headingTopDeg, in.up);
        }

        // the background map: what blocks the sky (not the Moon's glare)
        double[] avoid = null;
        if (camToEnu != null && located && utcMs != 0) {
            double[] m = Astro.moonEnu(utcMs, lat, lon), b = new double[3], p = new double[2];
            Rot.apply(Rot.transpose(camToEnu), m[0], m[1], m[2], b);
            if (model.project(b[0], b[1], b[2], p) && p[0] > -0.5 * W && p[1] > -0.5 * H && p[0] < 1.5 * W && p[1] < 1.5 * H) {
                avoid = new double[]{p[0], p[1], 12 * Astro.DEG * model.fx};
            }
        }
        Obstruction ob = obstructions(in.raw, in.invScale, black, spec, stars, model, camToEnu, avoid);
        rep.obstructedCells = ob.cells;
        rep.cellCols = ob.cols;
        rep.cellRows = ob.rows;
        rep.obstructedFraction = ob.fraction;
        rep.obstructionAzimuthDeg = ob.azimuthDeg;
        rep.obstructionSide = ob.side;

        // checks, most important first in the summary (PRIORITY), in this order on screen
        checkStorage(in, rep);
        checkSun(rep, utcMs, lat, lon, located);
        checkLens(in, rep, stars, black, noise);
        checkClouds(in, rep, stars, model, ob, camToEnu);
        measureSky(in, rep, rep.solution != null && rep.solution.model != null ? rep.solution.model : model, black);
        checkObstruction(rep, ob);
        checkMoon(in, rep, utcMs, lat, lon, located, model, camToEnu);
        checkClipping(in, rep, stars, black, scale);
        checkBattery(in, rep);
        checkSatellites(in, rep, utcMs, lat, lon, located, model, camToEnu);
        suggestMode(in, rep);
        rep.millis = System.currentTimeMillis() - t0;
        return rep;
    }

    /**
     * The satellites that will cross the picture during the session ({@link SatPlanner}): a bright one about to
     * cross means starting after it (unless they're kept), and when kept, the bright ones are counted down.
     */
    static void checkSatellites(Input in, Report rep, long utcMs, double lat, double lon, boolean located, CameraModel model,
                                double[] camToEnu) {
        if (in.satellites == null || in.satellites.size() == 0 || !located || utcMs == 0 || camToEnu == null || in.trails) return;
        double[] axis = new double[3], b = new double[3];
        Rot.apply(camToEnu, 0, 0, 1, axis);
        // the picture as a circle: the axis to the farthest corner
        double radius = 0;
        int W = in.spec.width, H = in.spec.height;
        double[][] corners = {{0, 0}, {W, 0}, {0, H}, {W, H}};
        for (double[] c : corners) {
            model.unproject(c[0], c[1], b);
            double n = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
            radius = Math.max(radius, Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, b[2] / n)))));
        }
        double hKm = in.sky != null && !Double.isNaN(in.sky.altM) ? in.sky.altM / 1000 : 0;
        SatPlanner.Plan p = SatPlanner.plan(in.satellites, new SatSky.Site(lat, lon, hKm), axis, radius, utcMs + in.startDelayMs,
                in.sessionSeconds, in.keepSatellites, in.zone);
        rep.satellites = p;
        rep.items.add(new Item("satellites", p.waits() ? Level.NOTE : Level.OK, p.text, p.spoken));
    }

    // ------------------------------------------------------------------------------------------------
    // Place

    /**
     * Without a known place: the zenith is where "up" points, and the stars say where that is on the sky at
     * this time, which gives latitude and longitude to about a degree (the phone's level and the camera's
     * mounting).
     */
    static double[] placeFromStars(SkySolver.Solution s, float[] up, double jd) {
        double n = Math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]);
        if (!(n > 1)) return null;
        // camera x = phone -y, camera y = phone -x, camera z = phone -z (the back camera, sensor turned 90 degrees)
        double[] zc = {-up[1] / n, -up[0] / n, -up[2] / n};
        double[] zj = new double[3], zd = new double[3];
        Rot.apply(s.R, zc[0], zc[1], zc[2], zj);
        Rot.apply(Astro.j2000ToDate(Astro.jdTt(jd)), zj[0], zj[1], zj[2], zd);
        double latR = Math.asin(Math.max(-1, Math.min(1, zd[2])));
        double lonR = Math.atan2(zd[1], zd[0]) - Astro.gast(jd);
        lonR = Math.atan2(Math.sin(lonR), Math.cos(lonR));
        return new double[]{latR / Astro.DEG, lonR / Astro.DEG};
    }

    /**
     * Camera bearings to ENU from the compass: the phone lies face-down, its top edge toward headingTop; tilt
     * from "up" if known. Camera x is the phone's bottom, camera y its left side, camera z out of the back.
     */
    static double[] compassCamera(double headingTopDeg, float[] up) {
        double h = headingTopDeg * Astro.DEG;
        double[] top = {Math.sin(h), Math.cos(h), 0};
        double[] z = {0, 0, 1};
        // face-down: the phone's +x (right edge) points 90 degrees left of its top
        double[] right = {Math.sin(h - Math.PI / 2), Math.cos(h - Math.PI / 2), 0};
        double[] cx = {-top[0], -top[1], -top[2]}, cy = {-right[0], -right[1], -right[2]};
        // columns: where camera x, y, z point in ENU
        return new double[]{cx[0], cy[0], z[0], cx[1], cy[1], z[1], cx[2], cy[2], z[2]};
    }

    /** Degrees east of north of a pixel, or NaN. */
    static double azimuthOf(CameraModel m, double[] camToEnu, double x, double y) {
        if (camToEnu == null) return Double.NaN;
        double[] b = new double[3], e = new double[3];
        m.unproject(x, y, b);
        Rot.apply(camToEnu, b[0], b[1], b[2], e);
        if (Math.hypot(e[0], e[1]) < 1e-6) return Double.NaN;
        return Astro.azimuth(e) / Astro.DEG;
    }

    static final String[] COMPASS = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};

    public static String compass(double azDeg) {
        if (Double.isNaN(azDeg)) return "";
        int k = (int) Math.round(((azDeg % 360) + 360) % 360 / 45.0) % 8;
        return COMPASS[k];
    }

    // ------------------------------------------------------------------------------------------------
    // Obstructions

    static final class Obstruction {
        boolean[] cells;
        int cols, rows;
        double fraction;
        double azimuthDeg = Double.NaN;
        String side = "";
        /** Where it is in the frame (pixels), for naming it without a compass. */
        double cx = Double.NaN, cy = Double.NaN;
        /** Sky background (ADU above black, lens shading taken out) and its noise per block. */
        double sky, blockNoise;
    }

    /**
     * Blocks of 32 x 32 pixels, each the mean of the middle half of its green pixels (stars, in the top quarter,
     * don't count), with the lens shading taken out; a smooth sky is fitted to them, ignoring what doesn't fit.
     * A block is dark if it is less than half as bright as that sky and well outside its noise (something opaque
     * in front of the sky), and edged if it steps by half the sky's level to a neighbour (no sky or cloud is that
     * sharp at this scale). A cell of 6 x 6 blocks is blocked with a few dark blocks, many edged ones, or some of
     * either and few stars; blocked cells joined up and touching the frame's edge are the obstruction.
     */
    static Obstruction obstructions(final short[] raw, final float invScale, float[] black, SensorSpec spec,
                                    StarList stars, CameraModel model, double[] camToEnu, double[] avoid) {
        final int W = spec.width, H = spec.height;
        final int bw = W / BLOCK, bh = H / BLOCK;
        int gq = 1;
        for (int q = 0; q < 4; q++) {
            if (spec.cfa[q] == 1) {
                gq = q;
                break;
            }
        }
        final int gx0 = gq & 1, gy0 = gq >> 1, g = gq;
        final float bl = black[gq];
        final float[] B = new float[bw * bh], N = new float[bw * bh];
        final ShadingMap sh = spec.shading;
        Parallel.forRange(bh, 4, new Parallel.Range() {
            @Override
            public void run(int r0, int r1) {
                float[] buf = new float[(BLOCK / 2) * (BLOCK / 2)];
                for (int by = r0; by < r1; by++) {
                    for (int bx = 0; bx < bw; bx++) {
                        int n = 0;
                        for (int y = by * BLOCK + gy0; y < (by + 1) * BLOCK; y += 2) {
                            int o = y * W;
                            for (int x = bx * BLOCK + gx0; x < (bx + 1) * BLOCK; x += 2) {
                                buf[n++] = (raw[o + x] & 0xFFFF) * invScale - bl;
                            }
                        }
                        Arrays.sort(buf, 0, n);
                        int a = n / 4, b = (3 * n) / 4;
                        double sum = 0;
                        for (int k = a; k < b; k++) sum += buf[k];
                        float gain = sh != null ? sh.gain(g, (bx + 0.5) * BLOCK, (by + 0.5) * BLOCK) : 1f;
                        B[by * bw + bx] = (float) (sum / Math.max(1, b - a)) * gain;
                        // noise of that mean: the pixels' spread (interquartile range / 1.35) over sqrt(n), a little more
                        N[by * bw + bx] = (float) (1.15 * (buf[b] - buf[a]) / 1.349 / Math.sqrt(n)) * gain;
                    }
                }
            }
        });
        // smooth sky: 1, u, v, u^2, uv, v^2, r^4 by iteratively reweighted least squares (Tukey)
        int nb = bw * bh;
        double[][] basis = new double[nb][];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                double u = ((bx + 0.5) * BLOCK - W / 2.0) / (W / 2.0), v = ((by + 0.5) * BLOCK - H / 2.0) / (W / 2.0);
                double r2 = u * u + v * v;
                basis[by * bw + bx] = new double[]{1, u, v, u * u, u * v, v * v, r2 * r2};
            }
        }
        float[] tmp = B.clone();
        double med = Stats.median(tmp, nb);
        double[] coef = new double[7];
        coef[0] = med;
        double[] w = new double[nb];
        double[] M = new double[nb];
        double s = 0;
        for (int it = 0; it < 12; it++) {
            float[] res = new float[nb];
            for (int i = 0; i < nb; i++) {
                M[i] = dot(coef, basis[i]);
                res[i] = (float) Math.abs(B[i] - M[i]);
            }
            s = Math.max(1e-3, 1.4826 * Stats.median(res, nb));
            double c = 4.685 * s;
            for (int i = 0; i < nb; i++) {
                double r = (B[i] - M[i]) / c;
                w[i] = Math.abs(r) < 1 ? (1 - r * r) * (1 - r * r) : 0;
            }
            double[] next = wls(basis, B, w, 7);
            if (next == null) break;
            coef = next;
        }
        for (int i = 0; i < nb; i++) M[i] = dot(coef, basis[i]);
        Obstruction ob = new Obstruction();
        ob.blockNoise = s;
        float[] mm = new float[nb];
        for (int i = 0; i < nb; i++) mm[i] = (float) M[i];
        ob.sky = Stats.median(mm, nb);
        // dark and edged blocks
        boolean[] dark = new boolean[nb], edged = new boolean[nb];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                int i = by * bw + bx;
                if (avoid != null && Math.hypot((bx + 0.5) * BLOCK - avoid[0], (by + 0.5) * BLOCK - avoid[1]) < avoid[2]) continue;
                double sk = Math.max(M[i], 0);
                if (B[i] - M[i] < -Math.max(0.5 * sk, 6 * Math.max(N[i], s))) {
                    dark[i] = true;
                    continue;
                }
                double lvl = 0.5 * Math.max(sk, ob.sky);
                int[] nbrs = {bx > 0 ? i - 1 : -1, bx < bw - 1 ? i + 1 : -1, by > 0 ? i - bw : -1, by < bh - 1 ? i + bw : -1};
                for (int j : nbrs) {
                    if (j >= 0 && Math.abs(B[i] - B[j]) > Math.max(lvl, 8 * Math.hypot(N[i], N[j]))) {
                        edged[i] = true;
                        break;
                    }
                }
            }
        }
        boolean[] blocked = new boolean[nb];
        for (int i = 0; i < nb; i++) blocked[i] = dark[i] || edged[i];
        // cells
        int cb = CELL_PX / BLOCK;
        int cols = (bw + cb - 1) / cb, rows = (bh + cb - 1) / cb;
        int[] nDark = new int[cols * rows], nEdged = new int[cols * rows], nAll = new int[cols * rows];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                int c = (by / cb) * cols + bx / cb;
                nAll[c]++;
                if (dark[by * bw + bx]) nDark[c]++;
                if (edged[by * bw + bx]) nEdged[c]++;
            }
        }
        // stars per cell: an obstruction hides them
        int[] nStars = new int[cols * rows];
        for (int k = 0; k < stars.count; k++) {
            int cx = Math.min(cols - 1, (int) (stars.x[k] / CELL_PX)), cy = Math.min(rows - 1, (int) (stars.y[k] / CELL_PX));
            nStars[cy * cols + cx]++;
        }
        float[] counts = new float[cols * rows];
        for (int c = 0; c < counts.length; c++) counts[c] = nStars[c];
        float typical = Stats.median(counts.clone(), counts.length);
        boolean[] cell = new boolean[cols * rows];
        for (int c = 0; c < cell.length; c++) {
            if (nAll[c] == 0) continue;
            double fd = nDark[c] / (double) nAll[c], fe = nEdged[c] / (double) nAll[c];
            boolean few = typical >= 4 && nStars[c] < 0.5 * typical;
            cell[c] = fd >= 0.1 || fe >= 0.25 || (fd + fe >= 0.05 && few);
        }
        // joined-up groups that touch the frame's edge
        int[] comp = new int[cell.length];
        Arrays.fill(comp, -1);
        boolean[] keep = new boolean[cell.length];
        int[] stack = new int[cell.length];
        double total = 0, sx = 0, sy = 0;
        int ncomp = 0;
        for (int c0 = 0; c0 < cell.length; c0++) {
            if (!cell[c0] || comp[c0] >= 0) continue;
            int sp = 0, size = 0;
            boolean edge = false;
            stack[sp++] = c0;
            comp[c0] = ncomp;
            List<Integer> members = new ArrayList<Integer>();
            while (sp > 0) {
                int c = stack[--sp];
                members.add(c);
                size++;
                int x = c % cols, y = c / cols;
                if (x == 0 || y == 0 || x == cols - 1 || y == rows - 1) edge = true;
                int[] nbrs = {x > 0 ? c - 1 : -1, x < cols - 1 ? c + 1 : -1, y > 0 ? c - cols : -1, y < rows - 1 ? c + cols : -1};
                for (int nn : nbrs) {
                    if (nn >= 0 && cell[nn] && comp[nn] < 0) {
                        comp[nn] = ncomp;
                        stack[sp++] = nn;
                    }
                }
            }
            ncomp++;
            if (!edge) continue;
            for (int c : members) {
                keep[c] = true;
                double area = nAll[c];
                total += area;
                sx += area * ((c % cols) + 0.5) * CELL_PX;
                sy += area * ((c / cols) + 0.5) * CELL_PX;
            }
        }
        ob.cols = cols;
        ob.rows = rows;
        ob.cells = keep;
        ob.fraction = total / nb;
        if (total > 0) {
            ob.cx = sx / total;
            ob.cy = sy / total;
            ob.azimuthDeg = azimuthOf(model, camToEnu, ob.cx, ob.cy);
            ob.side = !Double.isNaN(ob.azimuthDeg) ? compass(ob.azimuthDeg) : frameSide(ob.cx, ob.cy, W, H);
        }
        return ob;
    }

    /** Which edge of the phone a frame position is toward, without a compass. */
    static String frameSide(double x, double y, int W, int H) {
        double dx = (x - W / 2.0) / W, dy = (y - H / 2.0) / H;
        // raw left = the phone's top, raw top = the phone's right edge (lying face-down)
        if (Math.abs(dx) >= Math.abs(dy)) return dx < 0 ? "phone's top" : "phone's bottom";
        return dy < 0 ? "phone's right" : "phone's left";
    }

    // ------------------------------------------------------------------------------------------------
    // Checks

    static void checkStorage(Input in, Report rep) {
        if (in.freeBytes < 0 || in.needBytes <= 0) return;
        double free = in.freeBytes / 1e9, need = in.needBytes / 1e9;
        if (in.freeBytes < in.needBytes) {
            rep.items.add(new Item("storage", Level.STOP, String.format(Locale.US,
                    "Storage: %.1f GB free, the session needs %.1f GB. Free some space or choose a shorter session.", free, need),
                    "there isn't enough free storage for this session"));
        } else if (in.freeBytes < 2 * in.needBytes) {
            rep.items.add(new Item("storage", Level.WARN, String.format(Locale.US,
                    "Storage is low: %.1f GB free, enough for this session (%.1f GB) but not another.", free, need),
                    "storage is low"));
        } else {
            rep.items.add(new Item("storage", Level.OK, String.format(Locale.US, "Storage: %.1f GB free (the session needs %.1f GB)",
                    free, need), ""));
        }
    }

    static void checkSun(Report rep, long utcMs, double lat, double lon, boolean located) {
        if (!located || utcMs == 0) {
            rep.items.add(new Item("sun", Level.NOTE, "Sun and Moon: not checked (place unknown)", ""));
            return;
        }
        double alt = Astro.altitude(Astro.sunEnu(utcMs, lat, lon)) / Astro.DEG;
        rep.sunAltDeg = alt;
        if (alt <= -18) {
            rep.items.add(new Item("sun", Level.OK, String.format(Locale.US, "Sun %.0f° below the horizon: full darkness", -alt), ""));
            return;
        }
        // when the sky gets fully dark, if tonight
        long dark = 0;
        double prev = alt;
        for (long t = utcMs + 120_000L; t <= utcMs + 10 * 3600_000L; t += 120_000L) {
            double a = Astro.altitude(Astro.sunEnu(t, lat, lon)) / Astro.DEG;
            if (a <= -18) {
                dark = t;
                break;
            }
            if (a > prev + 0.5 && a > -12) break; // the Sun is rising: no more dark tonight
            prev = Math.min(prev, a);
        }
        rep.darkAtMs = dark;
        String when = dark > 0 ? " Full darkness at " + clock(dark) + "." : "";
        String whenSpoken = dark > 0 ? "; it's fully dark at " + clock(dark) : "";
        if (alt > -6) {
            rep.items.add(new Item("sun", Level.WARN, String.format(Locale.US,
                    "Sun %s: the sky is too bright for stars.%s", alt > 0 ? "up" : String.format(Locale.US, "only %.0f° down", -alt), when),
                    "the sky is still too bright" + whenSpoken));
        } else if (alt > -12) {
            rep.items.add(new Item("sun", Level.WARN, String.format(Locale.US,
                    "Twilight (Sun %.0f° down): the sky is still bright.%s", -alt, when), "it's still twilight" + whenSpoken));
        } else {
            rep.items.add(new Item("sun", Level.NOTE, String.format(Locale.US,
                    "Late twilight (Sun %.0f° down): a little sky glow.%s", -alt, when), ""));
        }
    }

    /**
     * Catalogue stars a clear sky would show bright enough here (four times the faintest detection) count as
     * expected; one counts as seen if a star is detected at its place with at least half the light a clear sky
     * gives it, so thin haze passes and cloud doesn't. A clear sky's light is this camera's clear-night zero
     * point if known, else the clearest stars of the frame itself (then only patchy cloud shows).
     */
    static void checkClouds(Input in, Report rep, StarList stars, CameraModel model, Obstruction ob, double[] camToEnu) {
        SkySolver.Solution s = rep.solution;
        if (s == null) {
            if (stars.count < 15) {
                rep.items.add(new Item("clouds", Level.WARN, String.format(Locale.US,
                        "Only %d stars: overcast, or something covers the lens", stars.count),
                        "hardly any stars are visible. It may be overcast, or something covers the lens"));
            } else if (in.catalog != null) {
                // a clear sky always shows its star patterns; not finding them means few real stars among the spots
                rep.items.add(new Item("clouds", Level.WARN, String.format(Locale.US,
                        "No star patterns found among %d faint spots: the sky may be overcast or hazy, or the lens misted",
                        stars.count), "the stars are hard to see. It may be cloudy, or the lens misted"));
            } else {
                rep.items.add(new Item("clouds", Level.NOTE, "Clouds: not checked (no star catalogue)", ""));
            }
            return;
        }
        SensorSpec spec = in.spec;
        ShadingMap sh = spec.shading;
        int gq = greenSite(spec);
        double g0 = sh != null ? sh.gain(gq, model.cx, model.cy) : 1;
        double norm = 2.5 * Math.log10(Math.max(1e-9, in.exposureS * in.iso / 100.0));
        float[] zp = zeroPoints(stars, s, in.catalog, spec, model);
        double[] zs = zeroPointStats(zp);
        if (zs == null) {
            rep.items.add(new Item("clouds", Level.NOTE, "Clouds: not checked (too few stars to measure)", ""));
            return;
        }
        double zp80 = zs[0];
        rep.zeroPointFrame = zs[1];
        rep.zeroPointNorm = zs[1] - norm;
        // haze: how much dimmer than a clear night even the clearest stars here are (needs a clear night to compare)
        // (a smudged lens dims the stars' cores too: then the dimming isn't put down to haze)
        Item lens = rep.item("lens");
        boolean baseline = !Double.isNaN(in.clearZeroPoint) && (lens == null || lens.level != Level.WARN);
        double haze = baseline ? in.clearZeroPoint - rep.zeroPointNorm : Double.NaN;
        rep.hazeMag = haze;
        // patches: stars the clearest part of this frame says should show
        double zpRef = zp80;
        DetGrid grid = new DetGrid(stars, spec.width, spec.height);
        // faintest dependable detection
        float[] fl = new float[stars.count];
        for (int i = 0; i < stars.count; i++) fl[i] = stars.flux[i];
        double fLim = stars.count >= 2000 ? Stats.quantile(fl, stars.count, 0.0) : Stats.quantile(fl, stars.count, 0.05);
        // the catalogue around the frame
        double[] axis = s.direction(model.cx, model.cy);
        double[] c0 = new double[3], c1 = new double[3];
        model.unproject(model.cx, model.cy, c0);
        model.unproject(0, 0, c1);
        int[] cand = in.catalog.within(axis, Astro.sep(c0, c1) + Astro.DEG, 7.0);
        int W = spec.width, H = spec.height;
        int expected = 0, seen = 0;
        double ux = 0, uy = 0, ex = 0, ey = 0;
        int unseen = 0;
        for (int k : cand) {
            double[] p = s.pixel(in.catalog.vec(k));
            if (p == null || p[0] < 16 || p[1] < 16 || p[0] >= W - 16 || p[1] >= H - 16) continue;
            if (ob.cells != null) {
                int cx = Math.min(ob.cols - 1, (int) (p[0] / CELL_PX)), cy = Math.min(ob.rows - 1, (int) (p[1] / CELL_PX));
                if (ob.cells[cy * ob.cols + cx]) continue;
            }
            double gain = sh != null ? sh.gain(gq, p[0], p[1]) / g0 : 1;
            double clear = Math.pow(10, 0.4 * (zpRef - in.catalog.vmag[k])) / gain;
            if (clear < 4 * fLim) continue;
            expected++;
            ex += p[0];
            ey += p[1];
            int i = grid.nearest((float) p[0], (float) p[1], Math.max(3.0, 3 * s.rmsPx));
            if (i >= 0 && (stars.saturated[i] || stars.flux[i] >= 0.5 * clear)) {
                seen++;
            } else {
                unseen++;
                ux += p[0];
                uy += p[1];
            }
        }
        rep.expected = expected;
        rep.seen = seen;
        if (expected < 12) {
            rep.items.add(new Item("clouds", Level.NOTE, String.format(Locale.US,
                    "Clouds: not checked (only %d catalogue stars to test)", expected), ""));
            return;
        }
        double f = seen / (double) expected;
        rep.seenFraction = f;
        String where = "";
        if (unseen >= 5 && f < 0.85) {
            double mx = ux / unseen, my = uy / unseen;
            // clouds only "somewhere" if the hidden stars lean to one side of the stars tested
            if (Math.hypot(mx - ex / expected, my - ey / expected) > 0.08 * Math.hypot(W, H)) {
                rep.cloudAzimuthDeg = azimuthOf(model, camToEnu, mx, my);
                if (!Double.isNaN(rep.cloudAzimuthDeg)) where = " to the " + compass(rep.cloudAzimuthDeg);
            }
        }
        int pct = (int) Math.round(100 * f);
        String hz = !Double.isNaN(haze) && haze >= 0.4
                ? String.format(Locale.US, " Even the clearest stars are %.1f magnitudes dimmer than on a clear night.", haze) : "";
        if (f < 0.1 || (!Double.isNaN(haze) && haze >= 2.5)) {
            rep.items.add(new Item("clouds", Level.WARN, String.format(Locale.US,
                    "Overcast: %d of %d stars that should show do (%d%%).%s Waiting for clearer sky would help.", seen, expected,
                    pct, hz), "it's overcast; waiting for a clearer sky would help"));
        } else if (f < 0.5) {
            rep.items.add(new Item("clouds", Level.WARN, String.format(Locale.US,
                    "Partly cloudy%s: %d of %d stars that should show do (%d%%).%s The stack keeps the clear patches; "
                            + "a longer session gives it more of them.", where, seen, expected, pct, hz),
                    "it's partly cloudy" + where + ". A longer session will help"));
        } else if (!Double.isNaN(haze) && haze >= 1.3) {
            rep.items.add(new Item("clouds", Level.WARN, String.format(Locale.US,
                    "Thin cloud or thick haze all over: stars %.1f magnitudes dimmer than on a clear night (%d%% of the light)",
                    haze, Math.round(100 * Math.pow(10, -0.4 * haze))), "the sky is hazy, so fewer stars will show"));
        } else if (f < 0.85) {
            rep.items.add(new Item("clouds", Level.NOTE, String.format(Locale.US,
                    "Some cloud%s: %d of %d stars that should show do (%d%%).%s", where, seen, expected, pct, hz),
                    "some cloud" + where));
        } else if (!Double.isNaN(haze) && haze >= 0.4) {
            rep.items.add(new Item("clouds", Level.NOTE, String.format(Locale.US,
                    "A little haze: stars %.1f magnitudes dimmer than on a clear night", haze), "a little haze"));
        } else {
            rep.items.add(new Item("clouds", Level.OK, String.format(Locale.US, "Clear sky: %d of %d stars that should show do (%d%%)%s",
                    seen, expected, pct, baseline ? "" : "; haze not measured yet (no clear night to compare)"), "clear sky"));
        }
    }

    /**
     * Sky brightness over the central 20 degrees of the test frame (H3): the frame's sky
     * against the zero point of its catalogue stars, in magnitudes per square arcsecond. The stars' flux is
     * summed in the detector's half-resolution green image, whose cells are 2 x 2 pixels, so a square arcsecond
     * holds a quarter of a cell's sky over the pixel scale squared. Two values: between the stars (the median of
     * the green pixels) and with them (their mean, single hot pixels left out), which is what a handheld meter
     * reads. Lens shading is taken out as for the zero point. Nothing without the zero point.
     */
    static void measureSky(Input in, Report rep, CameraModel model, float[] black) {
        if (Double.isNaN(rep.zeroPointFrame) || !(model.fx > 0)) return;
        SensorSpec spec = in.spec;
        int W = spec.width, H = spec.height;
        double pix = 206264.806 / model.fx;
        double r = model.fx * Math.tan(Math.toRadians(10));
        ShadingMap sh = spec.shading;
        double g0 = sh != null ? sh.gain(greenSite(spec), model.cx, model.cy) : 1;
        int x0 = Math.max(1, (int) (model.cx - r)), x1 = Math.min(W - 2, (int) (model.cx + r));
        int y0 = Math.max(1, (int) (model.cy - r)), y1 = Math.min(H - 2, (int) (model.cy + r));
        if (x1 <= x0 || y1 <= y0) return;
        // green pixels in the circle, shading taken out (each one's value, for the median)
        int cap = (int) Math.min(Integer.MAX_VALUE - 8, (long) (x1 - x0 + 1) * (y1 - y0 + 1) / 2 + 2);
        float[] v = new float[cap];
        float[] gain = new float[4];
        int n = 0;
        double r2 = r * r;
        for (int y = y0; y <= y1; y++) {
            double dy = y + 0.5 - model.cy;
            for (int x = x0; x <= x1; x++) {
                int q = ((y & 1) << 1) | (x & 1);
                if (spec.cfa[q] != 1) continue;
                double dx = x + 0.5 - model.cx;
                if (dx * dx + dy * dy > r2 || n >= cap) continue;
                float g = sh != null ? (float) (sh.gain(q, x, y) / g0) : 1f;
                v[n++] = ((in.raw[y * W + x] & 0xFFFF) * in.invScale - black[q]) * g;
            }
        }
        if (n < 1000) return;
        float[] tmp = Arrays.copyOf(v, n);
        float med = Stats.median(tmp, n);
        float sig = Math.max(0.3f, Stats.madSigma(tmp, n, med));
        // the mean with the stars, a pixel far above the sky whose four (green) diagonal neighbours are near the sky
        // being a hot pixel, not a star (a star spreads into them)
        double sum = 0;
        int m = 0;
        for (int y = y0; y <= y1; y++) {
            double dy = y + 0.5 - model.cy;
            for (int x = x0; x <= x1; x++) {
                int q = ((y & 1) << 1) | (x & 1);
                if (spec.cfa[q] != 1) continue;
                double dx = x + 0.5 - model.cx;
                if (dx * dx + dy * dy > r2) continue;
                float g = sh != null ? (float) (sh.gain(q, x, y) / g0) : 1f;
                float val = ((in.raw[y * W + x] & 0xFFFF) * in.invScale - black[q]) * g;
                if (val > med + 8 * sig) {
                    float nb = Float.NEGATIVE_INFINITY;
                    for (int k = 0; k < 4; k++) {
                        int xx = x + ((k & 1) == 0 ? -1 : 1), yy = y + (k < 2 ? -1 : 1);
                        int qq = ((yy & 1) << 1) | (xx & 1);
                        nb = Math.max(nb, ((in.raw[yy * W + xx] & 0xFFFF) * in.invScale - black[qq]) * g);
                    }
                    if (nb < med + 2 * sig) val = med;
                }
                sum += val;
                m++;
            }
        }
        double mean = m > 0 ? sum / m : Double.NaN;
        double area = 4 * pix * pix;
        if (med > 0) rep.skyMag = rep.zeroPointFrame - 2.5 * Math.log10(med / area);
        if (mean > 0) rep.skyMagWithStars = rep.zeroPointFrame - 2.5 * Math.log10(mean / area);
    }

    /** The green CFA site the background and photometry use. */
    static int greenSite(SensorSpec spec) {
        for (int q = 0; q < 4; q++) if (spec.cfa[q] == 1) return q;
        return 1;
    }

    /**
     * Zero points of the solution's matched, unclipped stars: 2.5 log10(flux) + V, the flux with the lens's
     * vignetting taken out (as at the frame's centre).
     */
    public static float[] zeroPoints(StarList stars, SkySolver.Solution s, BrightStars catalog, SensorSpec spec, CameraModel model) {
        if (s == null || stars == null || catalog == null) return new float[0];
        ShadingMap sh = spec.shading;
        int gq = greenSite(spec);
        double g0 = sh != null ? sh.gain(gq, model.cx, model.cy) : 1;
        DetGrid grid = new DetGrid(stars, spec.width, spec.height);
        float[] zp = new float[s.matched];
        int n = 0;
        for (int k = 0; k < s.matched; k++) {
            int i = grid.nearest(s.mx[k], s.my[k], 1.0);
            if (i < 0 || stars.saturated[i] || !(stars.flux[i] > 0)) continue;
            double gain = sh != null ? sh.gain(gq, stars.x[i], stars.y[i]) / g0 : 1;
            zp[n++] = (float) (2.5 * Math.log10(stars.flux[i] * gain) + catalog.vmag[s.cat[k]]);
        }
        return Arrays.copyOf(zp, n);
    }

    /**
     * {the 80th percentile of the stars' zero points (where the frame is clearest), the median of those within 0.75
     * magnitudes of it (the zero point of its clear parts, what a clear night is compared by)}; null with under ten.
     */
    public static double[] zeroPointStats(float[] zp) {
        if (zp.length < 10) return null;
        double q80 = Stats.quantile(zp.clone(), zp.length, 0.8);
        float[] clear = new float[zp.length];
        int n = 0;
        for (float v : zp) if (v >= q80 - 0.75) clear[n++] = v;
        return new double[]{q80, Stats.median(clear, n)};
    }

    static void checkLens(Input in, Report rep, StarList stars, float[] black, float noise) {
        HaloMeter.Result h = HaloMeter.measure(in.raw, in.invScale, black, in.spec, stars, noise);
        rep.haloStars = h.stars;
        if (h.stars < 5) {
            rep.items.add(new Item("lens", Level.NOTE, "Lens: not checked (too few bright, unclipped stars)", ""));
            return;
        }
        rep.haloRatio = h.ratio;
        boolean learned = !Double.isNaN(in.haloBaseline);
        double clean = learned ? in.haloBaseline : DEFAULT_CLEAN_HALO;
        double limit = 1.5 * clean + 0.05;
        rep.haloLimit = limit;
        if (h.ratio > limit) {
            rep.items.add(new Item("lens", Level.WARN, String.format(Locale.US,
                    "Lens may be smudged or fogged: %.0f%% of the starlight lands in a halo around the stars "
                            + "(a clean lens: %.0f%%). Wipe it with a soft cloth.", 100 * h.ratio / (1 + h.ratio),
                    100 * clean / (1 + clean)), "the lens may be smudged or fogged. Wipe it"));
        } else {
            rep.items.add(new Item("lens", Level.OK, String.format(Locale.US, "Lens clean (halo %.2f, limit %.2f%s)",
                    h.ratio, limit, learned ? "" : ", not yet learned for this phone"), "lens clean"));
        }
    }

    static void checkObstruction(Report rep, Obstruction ob) {
        if (ob.fraction < MIN_OBSTRUCTION) return;
        int pct = (int) Math.max(1, Math.round(100 * ob.fraction));
        boolean compass = !Double.isNaN(ob.azimuthDeg);
        String where = compass ? "along the " + ob.side + " edge" : "at the " + ob.side + " side";
        rep.items.add(new Item("obstruction", ob.fraction >= WARN_OBSTRUCTION ? Level.WARN : Level.NOTE, String.format(Locale.US,
                "Something blocks the sky %s (%d%% of the frame): trees, a roof or a wall? Its stars are left out.", where, pct),
                "something blocks the sky " + (compass ? "to the " + ob.side : where)));
    }

    static void checkMoon(Input in, Report rep, long utcMs, double lat, double lon, boolean located, CameraModel model,
                          double[] camToEnu) {
        if (!located || utcMs == 0) return;
        long end = utcMs + Math.round(in.sessionSeconds * 1000);
        double[] mStart = Astro.moonEnu(utcMs, lat, lon), mEnd = Astro.moonEnu(end, lat, lon);
        double alt = Astro.altitude(mStart) / Astro.DEG, altEnd = Astro.altitude(mEnd) / Astro.DEG;
        double illum = Astro.moonIllumination(Astro.jdTt(Astro.jdUtc(utcMs)));
        rep.moonAltDeg = alt;
        rep.moonIllum = illum;
        if (alt < -1 && altEnd < -1) {
            rep.items.add(new Item("moon", Level.OK, "Moon below the horizon", ""));
            return;
        }
        // least angle from the frame during the session
        double best = Double.MAX_VALUE;
        for (int k = 0; k <= 4; k++) {
            long t = utcMs + (end - utcMs) * k / 4;
            double[] m = Astro.moonEnu(t, lat, lon);
            best = Math.min(best, angleFromFrame(model, camToEnu, in.up, m));
        }
        rep.moonFromFrameDeg = best;
        int pct = (int) Math.round(100 * illum);
        if (illum < 0.15) {
            rep.items.add(new Item("moon", Level.OK, String.format(Locale.US, "Moon up but only a thin crescent (%d%% lit)", pct), ""));
            return;
        }
        if (best <= 0) {
            rep.items.add(new Item("moon", in.trails ? Level.NOTE : Level.WARN, String.format(Locale.US,
                    "The Moon (%d%% lit) is in the frame: its glare will swamp the stars around it.%s", pct,
                    in.trails ? " It will draw a bright arc in the trails." : " Star trails mode copes better with it."),
                    in.trails ? "" : "the Moon is in the frame. Star trails would suit it better"));
        } else if (best < 12) {
            rep.items.add(new Item("moon", Level.NOTE, String.format(Locale.US,
                    "The Moon (%d%% lit) is %.0f° outside the frame: expect some glare at that edge", pct, best),
                    "the Moon is just outside the frame"));
        } else if (illum > 0.5 && Math.max(alt, altEnd) > 10) {
            rep.items.add(new Item("moon", Level.NOTE, String.format(Locale.US,
                    "Bright Moon up (%d%% lit, %.0f° high): the sky is brighter and faint stars fewer", pct, alt),
                    "bright Moon up"));
        } else {
            rep.items.add(new Item("moon", Level.OK, String.format(Locale.US, "Moon %d%% lit, %.0f° from the frame", pct, best), ""));
        }
    }

    /**
     * Angle (degrees) between an ENU direction and the frame's nearest edge, 0 if inside. Without the camera's
     * orientation, the frame is taken to be centred on "up" (or the zenith) and the answer is the least it can be.
     */
    static double angleFromFrame(CameraModel model, double[] camToEnu, float[] up, double[] enu) {
        double[] c0 = new double[3], c1 = new double[3], c2 = new double[3];
        model.unproject(model.cx, model.cy, c0);
        model.unproject(0, 0, c1);
        model.unproject(model.cx, 0, c2);
        double halfDiag = Astro.sep(c0, c1) / Astro.DEG, halfShort = Astro.sep(c0, c2) / Astro.DEG;
        if (camToEnu == null) {
            double[] zen = {0, 0, 1};
            double z = Astro.sep(zen, enu) / Astro.DEG;
            if (z <= halfShort) return 0;
            return Math.max(0, z - halfDiag);
        }
        double[] b = new double[3], p = new double[2];
        Rot.apply(Rot.transpose(camToEnu), enu[0], enu[1], enu[2], b);
        if (model.project(b[0], b[1], b[2], p) && p[0] >= 0 && p[1] >= 0 && p[0] < model.width && p[1] < model.height) return 0;
        // nearest point of the frame's border
        double best = Double.MAX_VALUE;
        double[] e = new double[3];
        int n = 64;
        for (int k = 0; k < 4 * n; k++) {
            double t = (k % n) / (double) n;
            int side = k / n;
            double x = side == 0 ? t * model.width : side == 1 ? model.width : side == 2 ? (1 - t) * model.width : 0;
            double y = side == 0 ? 0 : side == 1 ? t * model.height : side == 2 ? model.height : (1 - t) * model.height;
            model.unproject(x, y, e);
            best = Math.min(best, Astro.sep(e, b) / Astro.DEG);
        }
        return best;
    }

    static void checkClipping(Input in, Report rep, StarList stars, float[] black, double scale) {
        SensorSpec spec = in.spec;
        int W = spec.width, H = spec.height;
        short[] raw = in.raw;
        float range0 = spec.white;
        long[] clip = new long[4], count = new long[4];
        float[][] samp = new float[4][];
        int[] ns = new int[4];
        for (int q = 0; q < 4; q++) samp[q] = new float[(W / 8 + 1) * (H / 8 + 1)];
        double[] thr = new double[4];
        for (int q = 0; q < 4; q++) {
            double range = range0 - black[q];
            thr[q] = black[q] + range / Math.max(1, scale) - 1;
        }
        for (int y = 0; y < H; y++) {
            int o = y * W;
            for (int x = 0; x < W; x++) {
                int q = ((y & 1) << 1) | (x & 1);
                float v = (raw[o + x] & 0xFFFF) * in.invScale;
                count[q]++;
                if (v >= thr[q]) clip[q]++;
                if ((x & 6) == 0 && (y & 6) == 0 && ((x >> 3) & 1) == 0 && ((y >> 3) & 1) == 0) samp[q][ns[q]++] = v;
            }
        }
        float[] bg = new float[3], cl = new float[3];
        int[] nc = new int[3];
        for (int q = 0; q < 4; q++) {
            int c = spec.cfa[q];
            if (c < 0 || c > 2) continue;
            float m = Stats.median(samp[q], ns[q]);
            bg[c] += (float) ((m - black[q]) * scale / (range0 - black[q]));
            cl[c] += clip[q] / (float) Math.max(1, count[q]);
            nc[c]++;
        }
        for (int c = 0; c < 3; c++) {
            if (nc[c] > 0) {
                bg[c] /= nc[c];
                cl[c] /= nc[c];
            }
            rep.background[c] = bg[c];
            rep.clipped[c] = cl[c];
        }
        // star cores that will clip at the session's settings
        int clippedStars = 0;
        for (int i = 0; i < stars.count; i++) {
            int x = (int) stars.x[i], y = (int) stars.y[i];
            if (x < 1 || y < 1 || x >= W - 1 || y >= H - 1) continue;
            boolean c = false;
            for (int dy = -1; dy <= 1 && !c; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int q = (((y + dy) & 1) << 1) | ((x + dx) & 1);
                    if ((raw[(y + dy) * W + x + dx] & 0xFFFF) * in.invScale >= thr[q]) {
                        c = true;
                        break;
                    }
                }
            }
            if (c) clippedStars++;
        }
        rep.clippedStars = clippedStars;
        float maxBg = Math.max(bg[0], Math.max(bg[1], bg[2]));
        float maxClip = Math.max(cl[0], Math.max(cl[1], cl[2]));
        String[] names = {"red", "green", "blue"};
        int worst = bg[0] >= bg[1] && bg[0] >= bg[2] ? 0 : bg[1] >= bg[2] ? 1 : 2;
        if (maxClip > 0.002 || maxBg > 0.6f) {
            rep.items.add(new Item("clipping", Level.WARN, String.format(Locale.US,
                    "Very bright sky: the %s sky glow fills %.0f%% of the sensor's range and %.1f%% of the frame clips",
                    names[worst], 100 * maxBg, 100 * maxClip), "the sky is very bright"));
        } else if (clippedStars > 0) {
            rep.items.add(new Item("clipping", Level.NOTE, String.format(Locale.US,
                    "Sky glow at %.0f%% of range; %d of the brightest stars will clip in the middle (normal)",
                    100 * maxBg, clippedStars), ""));
        } else {
            rep.items.add(new Item("clipping", Level.OK, String.format(Locale.US, "No clipping (sky glow at %.0f%% of range)", 100 * maxBg), ""));
        }
    }

    static void checkBattery(Input in, Report rep) {
        if (in.batteryPct < 0) return;
        if (in.charging) {
            rep.items.add(new Item("battery", Level.OK, String.format(Locale.US, "Battery %d%%, charging", in.batteryPct), "charging"));
            return;
        }
        double rate = !Double.isNaN(in.drainPctPerHour) && in.drainPctPerHour > 1 ? in.drainPctPerHour : DEFAULT_DRAIN;
        boolean cold = !Float.isNaN(in.batteryTempC) && in.batteryTempC < 5;
        // a cold battery holds less and sags sooner: plan with half again the use and a bigger reserve
        double use = rate * in.sessionSeconds / 3600 * (cold ? 1.5 : 1) + DEVELOP_PCT;
        double reserve = cold ? 15 : 5;
        double hours = Math.max(0, (in.batteryPct - reserve) / (rate * (cold ? 1.5 : 1)));
        String coldNote = cold ? String.format(Locale.US, " Cold battery (%.0f °C): planning with a bigger margin.", in.batteryTempC) : "";
        if (in.batteryPct - use < reserve) {
            rep.items.add(new Item("battery", Level.WARN, String.format(Locale.US,
                    "Battery %d%%: the session needs about %.0f%%, so it may run out first (what's captured is kept).%s",
                    in.batteryPct, use, coldNote), "the battery may run out before the end"));
        } else {
            rep.items.add(new Item("battery", Level.OK, String.format(Locale.US,
                    "Battery %d%%: about %.1f hours of capture (this session uses about %.0f%%).%s", in.batteryPct, hours, use, coldNote),
                    hoursSpoken(hours) + " of battery"));
        }
    }

    static void suggestMode(Input in, Report rep) {
        Item moon = rep.item("moon");
        if (!in.trails && moon != null && moon.level == Level.WARN) {
            rep.items.add(new Item("mode", Level.NOTE, "Suggestion: star trails mode suits a sky with the Moon in it (nothing was changed)", ""));
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers

    /** Detections hashed by position, for "is there a star here?". */
    static final class DetGrid {
        private static final int CELL = 16;
        private final StarList s;
        private final int gw, gh;
        private final int[] head, next;

        DetGrid(StarList s, int W, int H) {
            this.s = s;
            gw = W / CELL + 1;
            gh = H / CELL + 1;
            head = new int[gw * gh];
            next = new int[s.count];
            Arrays.fill(head, -1);
            for (int i = 0; i < s.count; i++) {
                int c = cellOf(s.x[i], s.y[i]);
                if (c < 0) continue;
                next[i] = head[c];
                head[c] = i;
            }
        }

        private int cellOf(float x, float y) {
            int cx = (int) (x / CELL), cy = (int) (y / CELL);
            if (cx < 0 || cy < 0 || cx >= gw || cy >= gh) return -1;
            return cy * gw + cx;
        }

        int nearest(float x, float y, double r) {
            int cx = (int) (x / CELL), cy = (int) (y / CELL);
            int reach = (int) Math.ceil(r / CELL);
            double best = r * r;
            int bi = -1;
            for (int yy = cy - reach; yy <= cy + reach; yy++) {
                if (yy < 0 || yy >= gh) continue;
                for (int xx = cx - reach; xx <= cx + reach; xx++) {
                    if (xx < 0 || xx >= gw) continue;
                    for (int i = head[yy * gw + xx]; i >= 0; i = next[i]) {
                        double d = (s.x[i] - x) * (s.x[i] - x) + (s.y[i] - y) * (s.y[i] - y);
                        if (d <= best) {
                            best = d;
                            bi = i;
                        }
                    }
                }
            }
            return bi;
        }
    }

    static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    /** Weighted least squares by the normal equations (Gauss-Jordan); null if singular. */
    static double[] wls(double[][] X, float[] y, double[] w, int p) {
        double[] A = new double[p * (p + 1)];
        for (int i = 0; i < X.length; i++) {
            if (w[i] <= 0) continue;
            double[] x = X[i];
            for (int a = 0; a < p; a++) {
                double wa = w[i] * x[a];
                for (int b = 0; b < p; b++) A[a * (p + 1) + b] += wa * x[b];
                A[a * (p + 1) + p] += wa * y[i];
            }
        }
        for (int c = 0; c < p; c++) {
            int piv = c;
            for (int r = c + 1; r < p; r++) if (Math.abs(A[r * (p + 1) + c]) > Math.abs(A[piv * (p + 1) + c])) piv = r;
            if (Math.abs(A[piv * (p + 1) + c]) < 1e-12) return null;
            if (piv != c) {
                for (int k = 0; k <= p; k++) {
                    double t = A[c * (p + 1) + k];
                    A[c * (p + 1) + k] = A[piv * (p + 1) + k];
                    A[piv * (p + 1) + k] = t;
                }
            }
            double d = A[c * (p + 1) + c];
            for (int k = 0; k <= p; k++) A[c * (p + 1) + k] /= d;
            for (int r = 0; r < p; r++) {
                if (r == c) continue;
                double f = A[r * (p + 1) + c];
                if (f == 0) continue;
                for (int k = 0; k <= p; k++) A[r * (p + 1) + k] -= f * A[c * (p + 1) + k];
            }
        }
        double[] out = new double[p];
        for (int a = 0; a < p; a++) out[a] = A[a * (p + 1) + p];
        return out;
    }

    static String clock(long utcMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(utcMs);
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        return String.format(Locale.US, "%d:%02d", h, m);
    }

    static final String[] NUMBERS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve"};

    static String hoursSpoken(double h) {
        if (h < 0.75) return "under an hour";
        int r = (int) Math.round(h);
        if (r > 12) return "over twelve hours";
        return r == 1 ? "about an hour" : "about " + NUMBERS[r] + " hours";
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String sentence(String s) {
        String c = capital(s);
        return c.endsWith(".") ? c : c + ".";
    }
}
