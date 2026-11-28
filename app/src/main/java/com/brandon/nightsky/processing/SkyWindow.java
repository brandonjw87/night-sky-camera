package com.brandon.nightsky.processing;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The Sky Window (3.3): hold the phone up and its screen is a window on the sky, the camera's picture with everything in
 * it named: the stars, the constellations' figures, the planets and the Moon, the galaxies and clusters, the space
 * stations as they fly over, the spacecraft leaving the Solar System and where they are, the black holes nobody can see,
 * the radiant of a meteor shower, and below the horizon the sky under your feet, seen through the Earth. Tap anything for
 * its story; Find steers the phone to it, and the Moon and Jupiter go straight on to the 5x telephoto from there.
 * <p>
 * This is the sums, in plain Java: where the phone points ({@link #deviceToEnu} from its rotation sensor, turned to true
 * north), where its camera and its screen point ({@link #cameraToEnu}, {@link #viewToEnu}), the readings over the last
 * seconds so a frame can be placed where the phone was while it was exposed ({@link Readings}), the screen's own pinhole
 * camera ({@link View}), what's out there at a moment ({@link #sky}), the satellites lit up now ({@link #satellites}),
 * when things rise and set ({@link #riseSet}), how to get to one ({@link #guide}), and the camera's RAW frame turned into
 * a picture for the screen ({@link #picture}). The labels sit where the sensors say until {@link StarLock} has seen the
 * stars in the camera's frame; then where the stars say.
 */
public final class SkyWindow {
    private SkyWindow() {}

    // ------------------------------------------------------------------------------------------------
    // Where the phone points

    /**
     * The phone's axes into the rear camera's bearings (row-major 3 x 3; camera x along its rows, y down its columns, z out
     * through the lens), for a camera whose pictures are turned {@code sensorOrientation} degrees clockwise to stand upright
     * on the phone held upright (Android's SENSOR_ORIENTATION, 90 on most phones).
     */
    public static double[] deviceToCamera(int sensorOrientation) {
        double t = Math.toRadians(sensorOrientation), c = Math.cos(t), s = Math.sin(t);
        // the picture's up (toward row 0) is the phone's (sin t, cos t, 0); the lens looks out of the back, along -z
        return new double[]{c, -s, 0, -s, -c, 0, 0, 0, -1};
    }

    /** The screen as a camera, the phone held in portrait: x to the screen's right, y down it, z out of the phone's back. */
    static final double[] VIEW_TO_DEVICE = {1, 0, 0, 0, -1, 0, 0, 0, -1};

    /**
     * The phone's axes into east, north and up (true north), from Android's rotation matrix {@code R} (3 x 3, row-major:
     * device axes into east, magnetic north and up, as {@code SensorManager.getRotationMatrixFromVector} gives it) and the
     * magnetic declination there (degrees east of true north).
     */
    public static double[] deviceToEnu(float[] R, double declinationDeg) {
        double[] m = new double[9];
        for (int i = 0; i < 9; i++) m[i] = R[i];
        return Rot.mul(trueNorth(declinationDeg), m);
    }

    /** Magnetic east-north-up into true east-north-up: magnetic north lies {@code declinationDeg} east of true north. */
    public static double[] trueNorth(double declinationDeg) {
        double d = declinationDeg * Astro.DEG, c = Math.cos(d), s = Math.sin(d);
        return new double[]{c, s, 0, -s, c, 0, 0, 0, 1};
    }

    /** The rear camera's bearings into east-north-up, from where the phone points. */
    public static double[] cameraToEnu(double[] deviceToEnu, int sensorOrientation) {
        return Rot.mul(deviceToEnu, Rot.transpose(deviceToCamera(sensorOrientation)));
    }

    /** The screen's camera into east-north-up, from where the phone points. */
    public static double[] viewToEnu(double[] deviceToEnu) {
        return Rot.mul(deviceToEnu, VIEW_TO_DEVICE);
    }

    /**
     * The phone's rotation readings over the last few seconds (true north), so that a camera frame can be put where the
     * phone was while it was exposed, and its steadiness told. Any thread.
     */
    public static final class Readings {
        static final int N = 512;
        private final long[] t = new long[N];
        private final double[][] r = new double[N][];
        private int head, count;

        public synchronized void add(long tNs, double[] deviceToEnu) {
            if (count > 0 && tNs <= t[(head - 1 + N) % N]) return;
            t[head] = tNs;
            r[head] = deviceToEnu.clone();
            head = (head + 1) % N;
            if (count < N) count++;
        }

        public synchronized int size() {
            return count;
        }

        /** The newest reading (null: none yet), and its time (ns). */
        public synchronized double[] latest() {
            return count == 0 ? null : r[(head - 1 + N) % N].clone();
        }

        public synchronized long latestNs() {
            return count == 0 ? Long.MIN_VALUE : t[(head - 1 + N) % N];
        }

        /** Where the phone pointed at {@code tNs}, between the readings either side (null: before the first one kept). */
        public synchronized double[] at(long tNs) {
            if (count == 0) return null;
            int newest = (head - 1 + N) % N, oldest = (head - count + N) % N;
            if (tNs >= t[newest]) return r[newest].clone();
            if (tNs < t[oldest]) return null;
            for (int k = 1; k < count; k++) {
                int i = (newest - k + N) % N, j = (i + 1) % N;
                if (t[i] <= tNs) {
                    double f = t[j] == t[i] ? 0 : (double) (tNs - t[i]) / (t[j] - t[i]);
                    return StarLock.orthonormal(Rot.lerp(r[i], r[j], f));
                }
            }
            return r[oldest].clone();
        }

        /** How far the phone turned between {@code t0Ns} and {@code t1Ns} (degrees, the most from where it was at the start). */
        public synchronized double turnDeg(long t0Ns, long t1Ns) {
            double[] a = at(t0Ns);
            if (a == null) return Double.NaN;
            double most = 0;
            double[] at = Rot.transpose(a);
            for (int k = 0; k < count; k++) {
                int i = (head - 1 - k + N) % N;
                if (t[i] < t0Ns) break;
                if (t[i] > t1Ns) continue;
                most = Math.max(most, Rot.angle(Rot.mul(r[i], at)));
            }
            double[] b = at(t1Ns);
            if (b != null) most = Math.max(most, Rot.angle(Rot.mul(b, at)));
            return most / Astro.DEG;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // The screen's camera

    /** The screen as a pinhole camera looking where the phone's back looks. */
    public static final class View {
        public final int width, height;
        /** Focal length and centre (screen pixels). */
        public final double f, cx, cy;
        /** The screen's camera into east-north-up, and back. */
        public final double[] viewToEnu, enuToView;

        public View(int width, int height, double f, double[] viewToEnu) {
            this.width = width;
            this.height = height;
            this.f = f;
            cx = width / 2.0;
            cy = height / 2.0;
            this.viewToEnu = viewToEnu.clone();
            enuToView = Rot.transpose(viewToEnu);
        }

        /** Where an east-north-up direction lands on the screen; false when it is behind the phone or edge-on. */
        public boolean project(double[] enu, double[] out) {
            double[] m = enuToView;
            double x = m[0] * enu[0] + m[1] * enu[1] + m[2] * enu[2];
            double y = m[3] * enu[0] + m[4] * enu[1] + m[5] * enu[2];
            double z = m[6] * enu[0] + m[7] * enu[1] + m[8] * enu[2];
            return projectView(x, y, z, out);
        }

        /** Where a direction in the screen camera's own axes lands; false when it is behind the phone or edge-on. */
        public boolean projectView(double x, double y, double z, double[] out) {
            if (z < 0.05) return false;
            out[0] = cx + f * x / z;
            out[1] = cy + f * y / z;
            return true;
        }

        /** The east-north-up direction through a point of the screen. */
        public double[] enuAt(double sx, double sy) {
            double x = (sx - cx) / f, y = (sy - cy) / f, n = Math.sqrt(x * x + y * y + 1);
            double[] o = new double[3];
            Rot.apply(viewToEnu, x / n, y / n, 1 / n, o);
            return o;
        }

        /** Where the phone's back points (east-north-up). */
        public double[] axis() {
            return new double[]{viewToEnu[2], viewToEnu[5], viewToEnu[8]};
        }

        /** An east-north-up direction in the screen camera's axes. */
        public double[] inView(double[] enu) {
            double[] o = new double[3];
            Rot.apply(enuToView, enu[0], enu[1], enu[2], o);
            return o;
        }

        public boolean onScreen(double x, double y, double margin) {
            return x >= -margin && y >= -margin && x <= width + margin && y <= height + margin;
        }

        /** Degrees a screen pixel spans at the middle. */
        public double degPerPx() {
            return Math.toDegrees(1 / f);
        }

        /** J2000 into the screen camera's axes, at the moment {@code toEnu} (J2000 to east-north-up) belongs to. */
        public double[] fromJ2000(double[] toEnu) {
            return Rot.mul(enuToView, toEnu);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // What's out there

    public enum Kind {
        MOON("the Moon"), SUN("the Sun"), PLANET("planet"), DWARF("dwarf planet"), STAR("star"), DEEP("deep sky"),
        BLACK_HOLE("black hole"), XRAY("unseen"), PROBE("spacecraft"), POINT("point in space"), SAT("satellite"),
        SHOWER("meteor shower");

        public final String word;

        Kind(String word) {
            this.word = word;
        }
    }

    /** Something to draw, name, find and tell about. */
    public static final class Item {
        public final String name;
        public final Kind kind;
        /** A few words beside it, and more to tell when it is tapped. */
        public String about = "", story = "";
        /** Visual magnitude (NaN: not to be seen with eyes, as a black hole). */
        public double mag = Double.NaN;
        /** Its J2000 direction from the Earth (null for the Moon, the Sun and satellites, placed straight in {@link #enu}). */
        public double[] dir;
        /** Where it is in the sky at the sky's moment: east, north, up, its height and compass direction (degrees). */
        public double[] enu;
        public double altDeg, azDeg;
        /** For a satellite: where it will be a second later (east-north-up), to draw it moving between placings. */
        public double[] enuNext;
        /** Distance in light years, or in km (NaN: unknown). */
        public double lightYears = Double.NaN, km = Double.NaN;
        /** Colour to draw it in (ARGB). */
        public int colour = 0xFFFFFFFF;
        /** Which planet ({@link Planets#NAMES}), satellite (NORAD number), or -1. */
        public int planet = -1, norad = -1;
        /** "moon" or "jupiter" when the 5x telephoto can take it from the Sky Window; "" otherwise. */
        public String shoot = "";
        /** The constellation it's in (IAU abbreviation; "" if not worked out). */
        public String constellation = "";

        public Item(String name, Kind kind) {
            this.name = name;
            this.kind = kind;
        }

        public boolean up() {
            return altDeg > 0;
        }

        /** Seen with eyes or the phone's camera (a star, a planet, the Moon, a bright cluster or galaxy, a lit satellite). */
        public boolean seen() {
            return !Double.isNaN(mag) && mag < 6.5;
        }

        /** Where it is now, in words: "38° up in the south-east". */
        public String where() {
            return SkyPointer.where(altDeg, azDeg);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Stars drawn as dots to this magnitude (fainter ones only when the window is zoomed in). */
    public static final float DOT_MAG = 6.0f;

    /** The fixed things: the stars, the deep sky and the unseen. Made once (on a worker: the stars take a moment). */
    public static final class Catalogue {
        public final BrightStars stars;
        final SkyPointer.Catalogue pointer;
        /** Stars drawn as dots (indices into {@link #stars}, brightest first), and every star's colour (ARGB). */
        public final int[] dots;
        public final int[] colours;
        /** The named stars' indices by the names the Sky Pointer gives them. */
        final java.util.Map<String, Integer> byLabel = new java.util.HashMap<String, Integer>();

        public Catalogue(BrightStars stars) {
            this.stars = stars;
            pointer = new SkyPointer.Catalogue(stars);
            int n = stars != null ? stars.n : 0;
            for (int i = 0; i < n; i++) {
                if (!(stars.vmag[i] <= SkyPointer.FAINTEST)) continue;
                String l = stars.label(i);
                if (!l.isEmpty() && !byLabel.containsKey(l)) byLabel.put(l, i);
            }
            colours = new int[n];
            int[] d = new int[n];
            int k = 0;
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            final BrightStars s = stars;
            if (s != null) {
                java.util.Arrays.sort(order, new Comparator<Integer>() {
                    @Override
                    public int compare(Integer a, Integer b) {
                        return Float.compare(s.vmag[a], s.vmag[b]);
                    }
                });
            }
            for (Integer i : order) {
                colours[i] = starColour(stars.bv[i], stars.kelvin[i]);
                if (stars.vmag[i] <= DOT_MAG + 0.5f) d[k++] = i;
            }
            dots = java.util.Arrays.copyOf(d, k);
        }
    }

    /** Everything placed in the sky at one moment from one place. */
    public static final class Sky {
        public final long utcMs;
        public final double latDeg, lonDeg;
        /** J2000 into east-north-up there and then. */
        public final double[] toEnu;
        public final List<Item> items = new ArrayList<Item>();
        /** The Sun's height (degrees): day, twilight or night. */
        public double sunAltDeg;

        Sky(long utcMs, double latDeg, double lonDeg, double[] toEnu) {
            this.utcMs = utcMs;
            this.latDeg = latDeg;
            this.lonDeg = lonDeg;
            this.toEnu = toEnu;
        }

        public double[] enu(double[] j2000) {
            double[] e = new double[3];
            Rot.apply(toEnu, j2000[0], j2000[1], j2000[2], e);
            return e;
        }

        public Item find(String name) {
            for (Item t : items) if (t.name.equals(name)) return t;
            return null;
        }

        /** Dark enough for stars (the Sun 8 degrees down). */
        public boolean dark() {
            return sunAltDeg < -8;
        }
    }

    static final int[] PLANET_COLOURS = {0xFFCFC8C0, 0xFFFFF4C8, 0xFFFF8A5C, 0xFFFFE0B0, 0xFFF4D58A, 0xFF9EE6F0, 0xFF7FA8FF};
    static final int MOON_COLOUR = 0xFFFFF8E1, SUN_COLOUR = 0xFFFFD54F, DEEP_COLOUR = 0xFFB39DDB, PROBE_COLOUR = 0xFF4DD0E1,
            HOLE_COLOUR = 0xFFE040FB, XRAY_COLOUR = 0xFF80CBC4, SAT_COLOUR = 0xFFFFFFFF, SHOWER_COLOUR = 0xFFFF8A65;

    /**
     * The sky at {@code utcMs} from latitude and longitude (degrees): the Moon, the Sun, the planets, Pluto, the five
     * spacecraft leaving the Solar System and Webb's L2, the stars to magnitude 4.5 by name, the galaxies, clusters and
     * nebulae, the black holes, the radiants of the meteor showers active then, and with {@code xray} the rest of the
     * unseen things X-Ray Vision knows (pulsars, exploded stars, stars with planets, the messages we've sent).
     */
    public static Sky sky(Catalogue c, long utcMs, double latDeg, double lonDeg, boolean xray) {
        SkyPointer.Sky ps = SkyPointer.sky(c != null ? c.pointer : null, utcMs, latDeg, lonDeg);
        Sky s = new Sky(utcMs, latDeg, lonDeg, ps.toEnu);
        double jd = Astro.jdUtc(utcMs), tt = Astro.jdTt(jd);
        s.sunAltDeg = Astro.altitude(Astro.sunEnu(utcMs, latDeg * Astro.DEG, lonDeg * Astro.DEG)) / Astro.DEG;
        for (SkyPointer.Thing t : ps.things) {
            Item it = fromPointer(t, tt, c);
            if (it != null) s.items.add(it);
        }
        // the galaxies, clusters and nebulae the photos know, where the pointer's short list hasn't them
        for (Object[] o : SkyGuide.OBJECTS) {
            double[] d = Astro.vec((Double) o[1] * Astro.DEG, (Double) o[2] * Astro.DEG);
            boolean have = false;
            for (Item t : s.items) {
                if ((t.kind == Kind.DEEP || t.kind == Kind.BLACK_HOLE) && t.dir != null && Astro.sep(t.dir, d) < 0.6 * Astro.DEG) {
                    have = true;
                    if (t.story.isEmpty()) t.story = deepFact((String) o[0]);
                    if (Double.isNaN(t.lightYears)) t.lightYears = (Double) o[5];
                }
            }
            if (have) continue;
            Item it = new Item(deepName((String) o[0]), Kind.DEEP);
            it.dir = d;
            it.mag = (Double) o[3];
            it.lightYears = (Double) o[5];
            it.about = deepKind((Character) o[4]) + ", " + lightYearsWords(it.lightYears) + " away";
            it.story = deepFact((String) o[0]);
            it.colour = DEEP_COLOUR;
            s.items.add(it);
        }
        if (xray) {
            for (XRayVision.Thing x : XRayVision.ALL) {
                boolean have = false;
                for (Item t : s.items) if (t.dir != null && t.kind != Kind.STAR && Astro.sep(t.dir, x.dir) < 0.2 * Astro.DEG) have = true;
                if (have) continue;
                Item it = new Item(x.name, x.kind == XRayVision.Kind.BLACK_HOLE || x.kind == XRayVision.Kind.GIANT ? Kind.BLACK_HOLE : Kind.XRAY);
                it.dir = x.dir;
                it.lightYears = x.lightYears;
                it.about = x.line;
                it.story = x.story;
                it.colour = it.kind == Kind.BLACK_HOLE ? HOLE_COLOUR : XRAY_COLOUR;
                s.items.add(it);
            }
        }
        // the meteor showers active now, at their radiants as seen from here (a little nearer the zenith)
        double[] zenith = new double[3];
        Rot.apply(Rot.transpose(ps.toEnu), 0, 0, 1, zenith);
        double lon = MeteorShowers.solarLongitude(jd);
        for (MeteorShowers.Shower sh : MeteorShowers.active(jd)) {
            double zhr = sh.zhr(lon);
            if (!sh.major() || zhr < 2) continue;
            Item it = new Item(sh.name, Kind.SHOWER);
            it.dir = MeteorShowers.apparentRadiant(sh.radiant(lon), zenith, sh.speed);
            double peakDays = sh.fromPeak(lon) / 0.9856;
            it.about = String.format(Locale.US, "%s fly out of here: about %d an hour under a dark sky %s", plural(sh.member),
                    Math.round(zhr), Math.abs(peakDays) < 1 ? "tonight, its peak" : peakDays < 0
                            ? String.format(Locale.US, "now, rising to its peak in %d days", Math.round(-peakDays))
                            : "now, after its peak");
            it.story = "Every meteor of the " + sh.name + " seems to fly out of this spot, the radiant, because they all travel side by "
                    + "side, like snowflakes in a car's headlights. They hit the air at " + Math.round(sh.speed) + " km a second.";
            it.colour = SHOWER_COLOUR;
            s.items.add(it);
        }
        for (Item t : s.items) place(s, t);
        return s;
    }

    private static String plural(String member) {
        return member.endsWith("s") ? member : member + "s";
    }

    private static void place(Sky s, Item t) {
        if (t.enu == null && t.dir != null) t.enu = s.enu(t.dir);
        if (t.enu == null) return;
        t.altDeg = Astro.altitude(t.enu) / Astro.DEG;
        t.azDeg = Astro.azimuth(t.enu) / Astro.DEG;
    }

    /** An item from the Sky Pointer's (the watch's) list, with what the window adds: colours, distances, stories. */
    private static Item fromPointer(SkyPointer.Thing t, double tt, Catalogue c) {
        Kind k;
        switch (t.kind) {
            case MOON: k = Kind.MOON; break;
            case SUN: k = Kind.SUN; break;
            case PLANET: k = Kind.PLANET; break;
            case DWARF: k = Kind.DWARF; break;
            case DEEP: k = Kind.DEEP; break;
            case BLACK_HOLE: k = Kind.BLACK_HOLE; break;
            case PROBE: k = Kind.PROBE; break;
            case POINT: k = Kind.POINT; break;
            default: k = Kind.STAR; break;
        }
        Item it = new Item(t.name, k);
        it.about = t.about;
        it.mag = t.mag;
        it.dir = t.dir;
        it.enu = t.enu;
        switch (k) {
            case MOON:
                it.colour = MOON_COLOUR;
                it.shoot = "moon";
                it.km = 384400;
                it.story = "The only other world people have walked on: twelve astronauts between 1969 and 1972. Its light takes 1.3 "
                        + "seconds to reach you. Shoot it with the 5x and every landing site gets its label.";
                break;
            case SUN:
                it.colour = SUN_COLOUR;
                it.km = Astro.AU_KM;
                it.story = "Never look at it, and never point the 5x at it: it can hurt your eyes and the camera. Its light takes 8 "
                        + "minutes 20 seconds to get here.";
                break;
            case PLANET: {
                for (int p = 0; p < Planets.NAMES.length; p++) if (Planets.NAMES[p].equals(t.name)) it.planet = p;
                if (it.planet >= 0) {
                    it.colour = PLANET_COLOURS[it.planet];
                    Planets.Seen ps = Planets.seen(it.planet, tt);
                    it.km = ps.distAu * Astro.AU_KM;
                    double lightMin = ps.distAu * Astro.AU_KM / 299792.458 / 60;
                    it.story = String.format(Locale.US, "Tonight it's %s km away: its light left it %s ago.", Probes.bigNumber(it.km),
                            lightMin < 90 ? Math.round(lightMin) + " minutes" : String.format(Locale.US, "%.1f hours", lightMin / 60));
                    if (it.planet == 3) {
                        it.shoot = "jupiter";
                        it.story += " Its four big moons, the ones Galileo found in 1610, show in the 5x telephoto: Shoot it to name them.";
                    }
                }
                break;
            }
            case PROBE: {
                it.colour = PROBE_COLOUR;
                for (Probes.Seen p : Probes.all(tt)) {
                    if (!p.name.equals(t.name)) continue;
                    it.km = p.distAu * Astro.AU_KM;
                    double h = p.lightHours();
                    it.story = String.format(Locale.US, "%s km away. A radio message to it takes %s to get there.%s", Probes.bigNumber(it.km),
                            h < 48 ? String.format(Locale.US, "%.1f hours", h) : String.format(Locale.US, "%.1f days", h / 24),
                            p.fact.isEmpty() ? "" : " " + p.fact);
                }
                break;
            }
            case POINT:
                it.colour = PROBE_COLOUR;
                it.km = 1.5e6;
                it.story = "Webb itself is far too small to see from here, but this is where it is: a million and a half km away, always "
                        + "on the side of the Earth away from the Sun, with its sunshield between it and the Sun's heat.";
                break;
            case DWARF:
                it.colour = 0xFFBCAAA4;
                break;
            case DEEP:
                it.colour = DEEP_COLOUR;
                break;
            case BLACK_HOLE:
                it.colour = HOLE_COLOUR;
                break;
            default: {
                // a star: its colour, distance, and when the light you see left it
                if (c != null && c.stars != null && t.dir != null) {
                    Integer ix = c.byLabel.get(t.name);
                    int i = ix != null ? ix : nearestStar(c.stars, t.dir);
                    if (i >= 0) {
                        it.colour = c.colours[i];
                        it.lightYears = c.stars.ly[i];
                        it.constellation = constellationOf(c.stars, i);
                    }
                }
                it.story = lightLeft(it.lightYears);
                break;
            }
        }
        return it;
    }

    static int nearestStar(BrightStars s, double[] d) {
        int best = -1;
        double bd = 1e-9;
        double cosMin = Math.cos(0.05 * Astro.DEG);
        for (int i = 0; i < s.n; i++) {
            double dot = s.dir[3 * i] * d[0] + s.dir[3 * i + 1] * d[1] + s.dir[3 * i + 2] * d[2];
            if (dot >= cosMin && dot > bd) {
                bd = dot;
                best = i;
            }
        }
        return best;
    }

    /** "the Andromeda Galaxy (M31)" as a label: "Andromeda Galaxy (M31)". */
    static String deepName(String n) {
        return n.startsWith("the ") ? n.substring(4) : n;
    }

    static String deepKind(char k) {
        switch (k) {
            case 'g': return "a galaxy";
            case 'o': return "a cluster of young stars";
            case 'c': return "a ball of old stars";
            case 'n': return "a glowing cloud of gas";
            default: return "a cloud of stars";
        }
    }

    static String deepFact(String name) {
        for (Object[] f : DeepZoom.FACTS) if (name.equals(f[0])) return (String) f[3];
        return "";
    }

    // ------------------------------------------------------------------------------------------------
    // How long the light has been travelling

    /** Moments to set a star's light against: the year, and what happened. */
    static final Object[][] WHEN = {
            {1969.5, "people first walked on the Moon"}, {1903.9, "the Wright brothers first flew"}, {1776.5, "America declared independence"},
            {1610.0, "Galileo first saw Jupiter's moons"}, {1492.8, "Columbus crossed the Atlantic"}, {1215.5, "King John signed the Magna Carta"},
            {1000.0, "Vikings were sailing to North America"}, {79.6, "Vesuvius buried Pompeii"}, {-44.2, "Julius Caesar was killed"},
            {-776.0, "the first Olympic Games were held"}, {-1323.0, "Tutankhamun was buried"}, {-2560.0, "the Great Pyramid was being built"},
            {-3200.0, "people were first writing things down"}, {-8000.0, "the last ice age had just ended"},
            {-40000.0, "people were painting the first cave pictures"}, {-300000.0, "the first people like us were born, in Africa"},
            {-2.5e6, "the first tools were made, long before people like us"}, {-6.6e7, "dinosaurs were still alive"},
            {-2.5e8, "the first dinosaurs were hatching"},
    };

    /** "The light you see tonight left it in 2001." or "...around 1480, about when Columbus crossed the Atlantic." */
    public static String lightLeft(double ly) {
        return lightLeft(ly, Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.YEAR));
    }

    public static String lightLeft(double ly, int nowYear) {
        if (Double.isNaN(ly) || !(ly > 0)) return "";
        double year = nowYear + 0.5 - ly;
        String when;
        if (ly < 150) when = "in " + Math.round(Math.floor(year));
        else if (year > 0) when = "around " + roundYear(year);
        else if (ly < 20000) when = "around " + roundYear(-year) + " BC";
        else when = lightYearsWords(ly).replace(" light years", " years") + " ago";
        String about = "";
        double bestD = Double.MAX_VALUE;
        for (Object[] w : WHEN) {
            double y = (Double) w[0], d = Math.abs(y - year);
            if (d <= Math.max(3, 0.12 * ly) && d < bestD) {
                bestD = d;
                about = (String) w[1];
            }
        }
        return "The light you see left it " + when + (about.isEmpty() ? "" : (bestD < 2 ? ", the year " : ", about when ") + about) + ".";
    }

    static String roundYear(double y) {
        long r = y < 1000 ? Math.round(y / 10) * 10 : y < 2000 ? Math.round(y / 10) * 10 : Math.round(y / 100) * 100;
        return String.valueOf(r);
    }

    static String lightYearsWords(double ly) {
        if (Double.isNaN(ly)) return "far";
        if (ly >= 1e9) return String.format(Locale.US, "%.1f billion light years", ly / 1e9);
        if (ly >= 1e6) return String.format(Locale.US, "%s million light years", trim(ly / 1e6));
        if (ly >= 1000) return String.format(Locale.US, "%,d light years", Math.round(ly / 100) * 100);
        return SkyPointer.lightYears((float) ly);
    }

    private static String trim(double v) {
        return v >= 10 ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.1f", v).replace(".0", "");
    }

    // ------------------------------------------------------------------------------------------------
    // Satellites

    /** Satellites named on the window to this magnitude (the space stations whatever their brightness, while lit). */
    public static final double SAT_MAG = 4.0;

    /** Of a cached list, those worth placing every second: the brightest lists' and the stations (not every Starlink). */
    public static List<SatCatalog.Sat> brightSats(List<SatCatalog.Sat> all, double jdUtc) {
        List<SatCatalog.Sat> out = new ArrayList<SatCatalog.Sat>();
        if (all == null) return out;
        for (SatCatalog.Sat s : all) {
            if (Math.abs(s.ageDays(jdUtc)) > 14) continue;
            boolean station = s.norad == 25544 || s.norad == 48274 || s.norad == 20580 || "stations".equals(s.source);
            if (station || "visual".equals(s.source) || s.stdMag <= 3.0) out.add(s);
        }
        return out;
    }

    /** The satellites lit by the Sun and up at {@code utcMs}, bright enough to see, each with where it'll be a second later. */
    public static List<Item> satellites(List<SatCatalog.Sat> sats, double latDeg, double lonDeg, long utcMs) {
        List<Item> out = new ArrayList<Item>();
        if (sats == null || sats.isEmpty()) return out;
        SatSky.Site site = new SatSky.Site(latDeg * Astro.DEG, lonDeg * Astro.DEG, 0.1);
        double jd = Astro.jdUtc(utcMs);
        SatSky.Moment m0 = site.moment(jd), m1 = site.moment(jd + 1 / 86400.0);
        SatSky.Look a = new SatSky.Look(), b = new SatSky.Look();
        for (SatCatalog.Sat s : sats) {
            if (!SatSky.look(s.orbit, s.stdMag, m0, a)) continue;
            if (!(a.altDeg > 0) || !a.sunlit) continue;
            boolean station = s.norad == 25544 || s.norad == 48274;
            if (!station && a.mag > SAT_MAG) continue;
            if (!SatSky.look(s.orbit, s.stdMag, m1, b)) continue;
            Item it = new Item(s.displayName(), Kind.SAT);
            it.norad = s.norad;
            it.enu = a.enu.clone();
            it.enuNext = b.enu.clone();
            it.mag = a.mag;
            it.km = a.rangeKm;
            it.colour = SAT_COLOUR;
            it.altDeg = a.altDeg;
            it.azDeg = a.azDeg;
            it.about = satAbout(s.norad, it.name, a.rangeKm);
            it.story = satStory(s.norad);
            out.add(it);
        }
        return out;
    }

    static String satAbout(int norad, String name, double km) {
        String d = Probes.bigNumber(Math.round(km / 10.0) * 10.0) + " km away";
        if (norad == 25544) return "the International Space Station, with people on board, " + d;
        if (norad == 48274) return "China's space station, with people on board, " + d;
        if (norad == 20580) return "the Hubble Space Telescope, " + d;
        return "a satellite catching the sunlight, " + d;
    }

    static String satStory(int norad) {
        if (norad == 25544) return "It goes round the whole Earth every 92 minutes at 28,000 km an hour, and astronauts have lived on it "
                + "since the year 2000. Wave: someone up there might be looking down.";
        if (norad == 48274) return "Tiangong means Heavenly Palace. Three astronauts at a time live on it, 390 km up.";
        if (norad == 20580) return "Since 1990 it has photographed galaxies billions of light years away. It's the size of a school bus.";
        return "You see it because the Sun still shines on it, high up, after dark down here. It moves; stars don't.";
    }

    /** A satellite where it is at {@code utcMs}, between its placing and a second later (and on, at the same rate). */
    public static double[] satAt(Item s, long placedMs, long utcMs) {
        if (s.enuNext == null) return s.enu;
        double f = (utcMs - placedMs) / 1000.0;
        double[] o = {s.enu[0] + f * (s.enuNext[0] - s.enu[0]), s.enu[1] + f * (s.enuNext[1] - s.enu[1]), s.enu[2] + f * (s.enuNext[2] - s.enu[2])};
        double n = Math.sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2]);
        if (n > 1e-9) for (int k = 0; k < 3; k++) o[k] /= n;
        return o;
    }

    /** A space station's (or Hubble's) pass over, seen lit in a dark sky. */
    public static final class Pass {
        public String name = "";
        public int norad;
        /** When it climbs past 10 degrees up lit, is highest, and goes (UTC ms), and where then (degrees). */
        public long startMs, peakMs, endMs;
        public double startAzDeg, peakAzDeg, endAzDeg, maxAltDeg = -90;
        /** Its brightest magnitude on the way. */
        public double mag = 99;

        /** "6:12 PM: 4 min, up to 54°, from the west to the north-east". */
        public String words(TimeZone tz) {
            long min = Math.max(1, Math.round((endMs - startMs) / 60000.0));
            return String.format(Locale.US, "%s: %d min, up to %d°, from the %s to the %s", clock(startMs, tz), min, Math.round(maxAltDeg),
                    Probes.compass(startAzDeg), Probes.compass(endAzDeg));
        }
    }

    /** How high a pass must climb, and how bright it must get, to be worth going out for. */
    static final double PASS_ALT = 10, PASS_MAG = 3.5;

    /**
     * The passes of the space stations and Hubble over the next {@code hours} that can be seen: lit by the Sun while the
     * sky is dark (the Sun 4 degrees down), at least {@link #PASS_ALT} up and as bright as {@link #PASS_MAG}; soonest first.
     */
    public static List<Pass> stationPasses(List<SatCatalog.Sat> sats, double latDeg, double lonDeg, long fromMs, double hours) {
        List<Pass> out = new ArrayList<Pass>();
        if (sats == null) return out;
        SatSky.Site site = new SatSky.Site(latDeg * Astro.DEG, lonDeg * Astro.DEG, 0.1);
        double lat = latDeg * Astro.DEG, lon = lonDeg * Astro.DEG;
        long end = fromMs + Math.round(hours * 3600_000L), step = 20_000L;
        // the Sun's height every five minutes, shared by the stations
        int nSun = (int) ((end - fromMs) / 300_000L) + 2;
        double[] sunAlt = new double[nSun];
        for (int k = 0; k < nSun; k++) sunAlt[k] = Astro.altitude(Astro.sunEnu(fromMs + k * 300_000L, lat, lon)) / Astro.DEG;
        SatSky.Look a = new SatSky.Look();
        for (SatCatalog.Sat s : sats) {
            if (s.norad != 25544 && s.norad != 48274 && s.norad != 20580) continue;
            Pass cur = null;
            for (long t = fromMs; t <= end; t += step) {
                int k = (int) Math.min(nSun - 2, (t - fromMs) / 300_000L);
                double fr = Math.min(1, (t - fromMs - k * 300_000L) / 300_000.0);
                double sun = sunAlt[k] + fr * (sunAlt[k + 1] - sunAlt[k]);
                boolean seen = sun < -4 && SatSky.look(s.orbit, s.stdMag, site.moment(Astro.jdUtc(t)), a) && a.altDeg > PASS_ALT && a.sunlit;
                if (seen) {
                    if (cur == null) {
                        cur = new Pass();
                        cur.name = s.displayName();
                        cur.norad = s.norad;
                        cur.startMs = t;
                        cur.startAzDeg = a.azDeg;
                    }
                    if (a.altDeg > cur.maxAltDeg) {
                        cur.maxAltDeg = a.altDeg;
                        cur.peakMs = t;
                        cur.peakAzDeg = a.azDeg;
                    }
                    cur.mag = Math.min(cur.mag, a.mag);
                    cur.endMs = t;
                    cur.endAzDeg = a.azDeg;
                } else if (cur != null) {
                    if (cur.mag <= PASS_MAG) out.add(cur);
                    cur = null;
                }
            }
            if (cur != null && cur.mag <= PASS_MAG) out.add(cur);
        }
        Collections.sort(out, new Comparator<Pass>() {
            @Override
            public int compare(Pass x, Pass y) {
                return Long.compare(x.startMs, y.startMs);
            }
        });
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // Rising and setting

    /** When something rises, is highest and sets, from a moment on (UTC ms; -1: not in the next day). */
    public static final class RiseSet {
        public long rise = -1, high = -1, set = -1;
        public double highAltDeg = Double.NaN;
        /** Up all the next day, or down all of it. */
        public boolean alwaysUp, neverUp;
    }

    /** The day after {@code fromMs}: when {@code it} rises, is highest and sets, to about half a minute. */
    public static RiseSet riseSet(Item it, long fromMs, double latDeg, double lonDeg) {
        RiseSet r = new RiseSet();
        if (it.kind == Kind.SAT) return r;
        final double h0 = it.kind == Kind.MOON || it.kind == Kind.SUN ? -0.83 : -0.57;
        long step = 10 * 60_000L;
        int n = 24 * 6;
        double prev = alt(it, fromMs, latDeg, lonDeg) - h0;
        double best = prev;
        long bestT = fromMs;
        boolean up = false, down = false;
        if (prev > 0) up = true;
        else down = true;
        for (int k = 1; k <= n; k++) {
            long t = fromMs + k * step;
            double a = alt(it, t, latDeg, lonDeg) - h0;
            if (a > 0) up = true;
            else down = true;
            if (prev <= 0 && a > 0 && r.rise < 0) r.rise = cross(it, t - step, t, h0, latDeg, lonDeg);
            if (prev > 0 && a <= 0 && r.set < 0) r.set = cross(it, t - step, t, h0, latDeg, lonDeg);
            if (a > best) {
                best = a;
                bestT = t;
            }
            prev = a;
        }
        r.alwaysUp = up && !down;
        r.neverUp = down && !up;
        if (!r.neverUp && bestT > fromMs && bestT < fromMs + n * step) {
            // the highest point, by golden-section search between the samples either side
            long lo = bestT - step, hi = bestT + step;
            for (int i = 0; i < 30 && hi - lo > 20_000L; i++) {
                long m1 = lo + (hi - lo) / 3, m2 = hi - (hi - lo) / 3;
                if (alt(it, m1, latDeg, lonDeg) < alt(it, m2, latDeg, lonDeg)) lo = m1;
                else hi = m2;
            }
            r.high = (lo + hi) / 2;
            r.highAltDeg = alt(it, r.high, latDeg, lonDeg);
        }
        return r;
    }

    private static long cross(Item it, long t0, long t1, double h0, double latDeg, double lonDeg) {
        double a0 = alt(it, t0, latDeg, lonDeg) - h0;
        for (int i = 0; i < 20 && t1 - t0 > 15_000L; i++) {
            long m = (t0 + t1) / 2;
            double am = alt(it, m, latDeg, lonDeg) - h0;
            if ((am > 0) == (a0 > 0)) {
                t0 = m;
                a0 = am;
            } else {
                t1 = m;
            }
        }
        return (t0 + t1) / 2;
    }

    /** Its height (degrees) at a moment. */
    static double alt(Item it, long utcMs, double latDeg, double lonDeg) {
        double lat = latDeg * Astro.DEG, lon = lonDeg * Astro.DEG;
        double[] e;
        if (it.kind == Kind.MOON) e = Astro.moonEnu(utcMs, lat, lon);
        else if (it.kind == Kind.SUN) e = Astro.sunEnu(utcMs, lat, lon);
        else {
            double[] d = it.dir;
            if (it.kind == Kind.PLANET && it.planet >= 0) d = Planets.seen(it.planet, Astro.jdTt(Astro.jdUtc(utcMs))).dir;
            if (d == null) return Double.NaN;
            e = new double[3];
            Rot.apply(Astro.j2000ToEnu(Astro.jdUtc(utcMs), lat, lon), d[0], d[1], d[2], e);
        }
        return Astro.altitude(e) / Astro.DEG;
    }

    // ------------------------------------------------------------------------------------------------
    // Finding

    /**
     * What Find lists: the Moon; the planets (those up first); the spacecraft and Webb's L2; the meteor showers; the black
     * holes; the galaxies, clusters and nebulae (those up first, the brightest first); the North Star and the 15 brightest
     * stars that are up; then Pluto and the Sun.
     */
    public static List<Item> targets(Sky s) {
        List<Item> out = new ArrayList<Item>();
        add(out, s, Kind.MOON, false, 1);
        add(out, s, Kind.PLANET, true, 99);
        add(out, s, Kind.PROBE, false, 99);
        add(out, s, Kind.POINT, false, 99);
        add(out, s, Kind.SHOWER, false, 99);
        add(out, s, Kind.BLACK_HOLE, false, 4);
        List<Item> deep = new ArrayList<Item>();
        for (Item t : s.items) if (t.kind == Kind.DEEP) deep.add(t);
        Collections.sort(deep, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.up() != b.up()) return a.up() ? -1 : 1;
                return Double.compare(Double.isNaN(a.mag) ? 99 : a.mag, Double.isNaN(b.mag) ? 99 : b.mag);
            }
        });
        out.addAll(deep.subList(0, Math.min(12, deep.size())));
        Item polaris = s.find("Polaris");
        if (polaris != null) out.add(polaris);
        List<Item> stars = new ArrayList<Item>();
        for (Item t : s.items) if (t.kind == Kind.STAR && t.up() && t != polaris && t.mag <= 2.6) stars.add(t);
        Collections.sort(stars, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Double.compare(a.mag, b.mag);
            }
        });
        out.addAll(stars.subList(0, Math.min(15, stars.size())));
        add(out, s, Kind.DWARF, false, 99);
        add(out, s, Kind.SUN, false, 1);
        return out;
    }

    private static void add(List<Item> out, Sky s, Kind k, boolean upFirst, int most) {
        List<Item> l = new ArrayList<Item>();
        for (Item t : s.items) if (t.kind == k) l.add(t);
        if (upFirst) {
            List<Item> up = new ArrayList<Item>(), down = new ArrayList<Item>();
            for (Item t : l) (t.up() ? up : down).add(t);
            l = up;
            l.addAll(down);
        }
        out.addAll(l.subList(0, Math.min(most, l.size())));
    }

    /** How to turn the phone to something. */
    public static final class Guide {
        /** How far off (degrees), how much higher (negative: lower) and how far right (negative: left) it is. */
        public double offDeg, upDeg, rightDeg;
        /** Which way the screen's arrow points: 0 up the screen, 90 to its right (degrees). */
        public double arrowDeg;
        /** On it (within {@code gotDeg}); on the screen at all; behind the phone; below the horizon. */
        public boolean got, onScreen, behind, below;
        /** Where it is on the screen when it's on it. */
        public double x = Double.NaN, y = Double.NaN;
        /** "Higher and to the left", "Turn around: it's behind you", "Got it!". */
        public String words = "";
    }

    /** From where the window looks ({@code v}) to {@code t} (placed at {@code enu}), got within {@code gotDeg}. */
    public static Guide guide(View v, Item t, double[] enu, double gotDeg) {
        Guide g = new Guide();
        double[] axis = v.axis(), tv = v.inView(enu);
        g.offDeg = Astro.sep(axis, enu) / Astro.DEG;
        double a0 = Astro.altitude(axis) / Astro.DEG, a1 = Astro.altitude(enu) / Astro.DEG;
        double z0 = Astro.azimuth(axis) / Astro.DEG, z1 = Astro.azimuth(enu) / Astro.DEG;
        double dAz = ((z1 - z0) % 360 + 540) % 360 - 180;
        g.upDeg = a1 - a0;
        g.rightDeg = dAz * Math.cos(0.5 * (a0 + a1) * Astro.DEG);
        g.arrowDeg = (Math.toDegrees(Math.atan2(tv[0], -tv[1])) + 360) % 360;
        double[] p = new double[2];
        if (v.projectView(tv[0], tv[1], tv[2], p) && v.onScreen(p[0], p[1], 0)) {
            g.onScreen = true;
            g.x = p[0];
            g.y = p[1];
        }
        g.got = g.offDeg <= gotDeg;
        g.below = a1 < 0;
        g.behind = !g.got && tv[2] < -0.3 && Math.abs(dAz) > 100;
        if (g.got) g.words = "Got it!";
        else if (g.behind) g.words = "Turn around: it's behind you";
        else {
            String vv = g.upDeg > 4 ? "Higher" : g.upDeg < -4 ? "Lower" : "";
            String h = g.rightDeg > 4 ? "right" : g.rightDeg < -4 ? "left" : "";
            if (!vv.isEmpty() && !h.isEmpty()) g.words = vv + " and to the " + h;
            else if (!vv.isEmpty()) g.words = vv;
            else if (!h.isEmpty()) g.words = "To the " + h;
            else g.words = "Nearly there";
        }
        if (g.below && !g.got) g.words += ": it's below the horizon, so you'll be looking through the Earth";
        return g;
    }

    // ------------------------------------------------------------------------------------------------
    // The camera's picture for the screen

    /**
     * A RAW frame as a small colour picture for the screen (ARGB, {@code (W / 2 / bin)} by {@code (H / 2 / bin)}): each 2 x 2
     * colour cell is a pixel, {@code bin} by {@code bin} of them averaged, white balanced by {@code gains} (red, green,
     * blue), then stretched: by day to look like a photo, by night ({@code night} 1) the sky's glow pushed down near black
     * and its stars brightened, so that a frame of a fraction of a second shows the stars. Returns the green median (levels
     * above black) it was stretched by.
     */
    public static double picture(ShortBuffer raw, int rowStride, int W, int H, SensorSpec spec, int bin, double[] gains, double night,
                                 int[] out) {
        final int ow = W / 2 / bin, oh = H / 2 / bin;
        int[] cfa = spec.cfa;
        // which of the 2 x 2 cell's sites are red, green and blue
        int rp = 0, bp = 3, g1 = 1, g2 = 2;
        int gi = 0;
        for (int p = 0; p < 4; p++) {
            if (cfa[p] == 0) rp = p;
            else if (cfa[p] == 2) bp = p;
            else if (gi++ == 0) g1 = p;
            else g2 = p;
        }
        float blackR = spec.black[rp], blackG = 0.5f * (spec.black[g1] + spec.black[g2]), blackB = spec.black[bp];
        float range = spec.white - blackG;
        double[] lv = new double[2];
        greenLevels(raw, rowStride, W, H, spec, lv);
        double median = lv[0];
        double m = Math.max(0, median);
        final float gr = (float) gains[0], gg = (float) gains[1], gb = (float) gains[2];
        // the noise of a binned pixel's brightness (below): a short frame's sky is mostly the sensor's own noise
        double sr = gr * lv[1] / bin, sg = gg * lv[1] / (bin * Math.sqrt(2)), sb = gb * lv[1] / bin;
        double noise = Math.sqrt(0.0625 * sr * sr + 0.25 * sg * sg + 0.0625 * sb * sb);
        // the stretch: lo..hi to 0..1, then a square root; by night black sits a little above the sky's noise, so the
        // sky is dark with a sprinkle of grain and the stars stand out of it
        double n = Math.max(0, Math.min(1, night));
        double loNight = m + NOISE_FLOOR * noise;
        double lo = n * loNight, hiDay = Math.max(2.6 * m, 0.02 * range), hiNight = loNight + Math.max(0.04 * range, 10 * noise);
        double hi = hiDay + n * (hiNight - hiDay);
        final float off = (float) lo, scale = (float) (1.0 / Math.max(1e-3, hi - lo));
        // by night the colour of what's faint is mostly noise (each channel's own, so it speckles red, green and blue):
        // it stays grey to CHROMA_FROM of the stretch and has its full colour by CHROMA_FULL, so the stars keep theirs
        final float grey = (float) n;
        final float inv = 1f / (bin * bin);
        final int[] lut = SQRT_LUT;
        final int frp = rp, fbp = bp, fg1 = g1, fg2 = g2, fbin = bin, stride = rowStride;
        final float fbR = blackR, fbG = blackG, fbB = blackB;
        final ShortBuffer src = raw;
        final int[] dst = out;
        Parallel.forRange(oh, 16, new Parallel.Range() {
            @Override
            public void run(int y0, int y1) {
                ShortBuffer raw = src.duplicate();
                pictureRows(raw, y0, y1, ow, stride, frp, fbp, fg1, fg2, fbin, fbR, fbG, fbB, gr, gg, gb, inv, off, scale, grey, lut, dst);
            }
        });
        return median;
    }

    /**
     * The IAU abbreviation of the constellation star {@code i} is in: the catalogue's, or (for the two thirds of its stars
     * that don't say) from the constellations' boundaries.
     */
    public static String constellationOf(BrightStars st, int i) {
        String c = st.con[i];
        return c != null && !c.isEmpty() ? c : Constellations.at(st.vec(i));
    }

    /** A RAW frame's green median (levels above black), from a sparse sample of 4096 of its green sites: the meter. */
    public static double greenMedian(ShortBuffer raw, int rowStride, int W, int H, SensorSpec spec) {
        double[] lv = new double[2];
        greenLevels(raw, rowStride, W, H, spec, lv);
        return lv[0];
    }

    /**
     * A RAW frame's green median (levels above black) and its spread (a robust standard deviation, from the median
     * absolute deviation: stars and hot pixels don't move it), from a sparse sample of 4096 green sites.
     */
    public static void greenLevels(ShortBuffer raw, int rowStride, int W, int H, SensorSpec spec, double[] out) {
        int[] cfa = spec.cfa;
        int g1 = 1, g2 = 2, gi = 0;
        for (int p = 0; p < 4; p++) {
            if (cfa[p] != 0 && cfa[p] != 2) {
                if (gi++ == 0) g1 = p;
                else g2 = p;
            }
        }
        float blackG = 0.5f * (spec.black[g1] + spec.black[g2]);
        int ns = 0;
        float[] sample = new float[4096];
        int stepY = Math.max(1, H / 2 / 64), stepX = Math.max(1, W / 2 / 64);
        for (int cy = stepY / 2; cy < H / 2 && ns < sample.length; cy += stepY) {
            for (int cx = stepX / 2; cx < W / 2 && ns < sample.length; cx += stepX) {
                int y = 2 * cy, x = 2 * cx;
                int a = raw.get((y + (g1 >> 1)) * rowStride + x + (g1 & 1)) & 0xFFFF;
                sample[ns++] = a - blackG;
            }
        }
        if (ns == 0) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        float med = Stats.median(sample, ns);
        for (int i = 0; i < ns; i++) sample[i] = Math.abs(sample[i] - med);
        out[0] = med;
        out[1] = 1.4826 * Stats.median(sample, ns);
    }

    /** Of the night stretch (0..1): below CHROMA_FROM something is grey, from CHROMA_FULL it has all its colour. */
    static final float CHROMA_FROM = 0.12f, CHROMA_FULL = 0.45f;
    /** By night, the most a channel may differ from the brightness, as a share of it (a red star's is about 0.4). */
    static final float MAX_SAT = 0.5f;
    /** By night, black is this many times the sky's noise above the sky. */
    static final double NOISE_FLOOR = 1.0;

    private static void pictureRows(ShortBuffer raw, int y0, int y1, int ow, int rowStride, int rp, int bp, int g1, int g2, int bin,
                                    float blackR, float blackG, float blackB, float gr, float gg, float gb, float inv, float off,
                                    float scale, float grey, int[] lut, int[] out) {
        final float chromaScale = scale / (CHROMA_FULL - CHROMA_FROM), chromaFrom = CHROMA_FROM / (CHROMA_FULL - CHROMA_FROM);
        for (int oy = y0; oy < y1; oy++) {
            for (int ox = 0; ox < ow; ox++) {
                float sr = 0, sg = 0, sb = 0;
                for (int by = 0; by < bin; by++) {
                    int y = 2 * (oy * bin + by);
                    for (int bx = 0; bx < bin; bx++) {
                        int x = 2 * (ox * bin + bx);
                        sr += raw.get((y + (rp >> 1)) * rowStride + x + (rp & 1)) & 0xFFFF;
                        sg += (raw.get((y + (g1 >> 1)) * rowStride + x + (g1 & 1)) & 0xFFFF)
                                + (raw.get((y + (g2 >> 1)) * rowStride + x + (g2 & 1)) & 0xFFFF);
                        sb += raw.get((y + (bp >> 1)) * rowStride + x + (bp & 1)) & 0xFFFF;
                    }
                }
                float r = (sr * inv - blackR) * gr, g = (sg * 0.5f * inv - blackG) * gg, b = (sb * inv - blackB) * gb;
                if (grey > 0) {
                    float y = 0.25f * r + 0.5f * g + 0.25f * b;
                    float w = (y - off) * chromaScale - chromaFrom;
                    w = w <= 0 ? 0 : w >= 1 ? 1 : w;
                    w = 1 - grey * (1 - w);
                    // and no more colourful than a star can be (a hot pixel lights one of the cell's sites alone)
                    float lim = y > 0 ? MAX_SAT * y : 0;
                    r = y + w * Math.max(-lim, Math.min(lim, r - y));
                    g = y + w * Math.max(-lim, Math.min(lim, g - y));
                    b = y + w * Math.max(-lim, Math.min(lim, b - y));
                }
                int ri = tone(r, off, scale, lut), gi2 = tone(g, off, scale, lut), bi = tone(b, off, scale, lut);
                out[oy * ow + ox] = 0xFF000000 | (ri << 16) | (gi2 << 8) | bi;
            }
        }
    }

    private static final int[] SQRT_LUT = new int[1025];

    static {
        for (int i = 0; i <= 1024; i++) SQRT_LUT[i] = (int) Math.round(255 * Math.sqrt(i / 1024.0));
    }

    private static int tone(float v, float off, float scale, int[] lut) {
        float t = (v - off) * scale;
        if (!(t > 0)) return 0;
        if (t >= 1) return 255;
        return lut[(int) (t * 1024)];
    }

    /** White balance gains (red, green, blue) for daylight on this sensor, from its calibration (1, 1, 1 if it has none). */
    public static double[] daylightGains(SensorSpec spec) {
        try {
            double[] c = ColorMath.cameraRgb(spec, 5200);
            if (c != null && c[0] > 0 && c[1] > 0 && c[2] > 0) return new double[]{c[1] / c[0], 1, c[1] / c[2]};
        } catch (RuntimeException ignored) {
            // no calibration: grey
        }
        return new double[]{1, 1, 1};
    }

    // ------------------------------------------------------------------------------------------------
    // The window's exposure

    /** The longest a window frame is exposed (ns): longer and a hand blurs the stars. */
    public static final long MAX_EXPOSURE_NS = 125_000_000L;
    /** The green median the exposure aims at by day (share of the range above black). */
    static final double DAY_TARGET = 0.16;

    /**
     * The next exposure (ns, ISO) from the last one's green median (levels above black of {@code range}): by day toward
     * {@link #DAY_TARGET}, at night the longest a hand can hold ({@link #MAX_EXPOSURE_NS}) at the most gain the sensor has
     * without going past its own amplifier; a third of the way there each frame, so it doesn't hunt.
     */
    public static long[] nextExposure(long ns, int iso, double median, double range, long minNs, long maxNs, int minIso, int maxIso) {
        long capNs = Math.min(maxNs, MAX_EXPOSURE_NS);
        double light = (double) ns * iso;
        double want = median > 0.5 ? light * Math.pow(DAY_TARGET * range / median, 0.33) : light * 2;
        double most = (double) capNs * maxIso, least = (double) minNs * minIso;
        want = Math.max(least, Math.min(most, want));
        // the exposure first (to its cap), then the gain
        long e = (long) Math.max(minNs, Math.min(capNs, want / minIso));
        int g = (int) Math.round(Math.max(minIso, Math.min(maxIso, want / e)));
        return new long[]{e, g};
    }

    /** How much of a night exposure this is (0 by day, 1 at the longest and brightest the window takes). */
    public static double nightness(long ns, int iso, long maxNs, int maxIso) {
        double most = (double) Math.min(maxNs, MAX_EXPOSURE_NS) * maxIso;
        double f = ns * (double) iso / most;
        // a quarter of the most and up: night; a fiftieth and down: day
        return Math.max(0, Math.min(1, (Math.log(f) - Math.log(0.02)) / (Math.log(0.25) - Math.log(0.02))));
    }

    // ------------------------------------------------------------------------------------------------
    // Colours

    /** A star's colour (ARGB) from its B-V index, else its temperature; white for neither. */
    public static int starColour(float bv, int kelvin) {
        double t = !Float.isNaN(bv) ? ColorCalibration.kelvin(bv) : kelvin > 0 ? kelvin : 6500;
        return kelvinArgb(t);
    }

    /** A blackbody's colour (ARGB, brightest channel at 255), softened toward white as the eye sees a star. */
    public static int kelvinArgb(double k) {
        double t = Math.max(1500, Math.min(40000, k)) / 100.0;
        double r, g, b;
        if (t <= 66) {
            r = 255;
            g = 99.4708025861 * Math.log(t) - 161.1195681661;
            b = t <= 19 ? 0 : 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        } else {
            r = 329.698727446 * Math.pow(t - 60, -0.1332047592);
            g = 288.1221695283 * Math.pow(t - 60, -0.0755148492);
            b = 255;
        }
        double mx = Math.max(r, Math.max(g, b));
        int ri = soft(r / mx), gi = soft(g / mx), bi = soft(b / mx);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    private static int soft(double v) {
        v = Math.max(0, Math.min(1, v));
        return (int) Math.round(255 * (0.45 + 0.55 * v));
    }

    // ------------------------------------------------------------------------------------------------
    // Words

    /** "9:41 PM" in the phone's time zone. */
    public static String clock(long utcMs, TimeZone tz) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("h:mm a", Locale.US);
        f.setTimeZone(tz);
        return f.format(new java.util.Date(utcMs));
    }

    /** "Rises 9:41 PM · Highest 4:12 AM, 61° up · Sets 10:43 AM", or "Up all night" / "Doesn't rise today". */
    public static String riseSetWords(RiseSet r, TimeZone tz) {
        if (r == null) return "";
        if (r.neverUp) return "It doesn't rise here in the next day.";
        StringBuilder b = new StringBuilder();
        if (r.alwaysUp) b.append("Up all day and night here");
        if (r.rise >= 0) b.append(b.length() > 0 ? " · " : "").append("Rises ").append(clock(r.rise, tz));
        if (r.high >= 0) b.append(b.length() > 0 ? " · " : "").append(String.format(Locale.US, "Highest %s, %d° up", clock(r.high, tz),
                Math.round(r.highAltDeg)));
        if (r.set >= 0) b.append(b.length() > 0 ? " · " : "").append("Sets ").append(clock(r.set, tz));
        return b.toString();
    }

    /** A time from now in words: "in 2 h 30 min", "45 min ago", "now". */
    public static String fromNow(long dMs) {
        long min = Math.round(Math.abs(dMs) / 60000.0);
        if (min == 0) return "now";
        String s = min >= 60 ? (min / 60) + " h" + (min % 60 != 0 ? " " + (min % 60) + " min" : "") : min + " min";
        return dMs > 0 ? "in " + s : s + " ago";
    }
}
