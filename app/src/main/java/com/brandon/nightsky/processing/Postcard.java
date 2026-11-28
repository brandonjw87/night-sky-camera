package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Postcard from Another World (2.6): the night sky as it looks from a planet round another star, and our Sun in it, on a
 * postcard. The planet is one whose star is in the photo (else the one highest in the sky that night, else Proxima b,
 * the nearest): the stars of the app's catalogue are moved to where they'd be seen from there (each star's direction
 * and distance from the Sun, less the planet's), each made brighter or fainter by how much nearer or farther it is, and
 * the Sun put among them at its own brightness (absolute magnitude 4.83). The constellations' lines are drawn between
 * their stars' new places: the far ones keep their shapes, the near ones fall apart.
 * <p>
 * Distances are the catalogue's (Hipparcos, via HYG); a star without one borrows a close neighbour's (one star of a
 * double), else is taken as 1,500 light years off, about where Hipparcos stops telling. Proper motion is left out.
 */
public final class Postcard {
    private Postcard() {}

    /** The Sun's absolute magnitude (V). */
    static final double SUN_ABS = 4.83;
    /** Light years in a parsec. */
    static final double LY_PER_PC = 3.26156;
    /** A star with no distance in the catalogue (and no close neighbour with one) is taken as this far (light years). */
    static final double FAR = 1500;
    /** Voyager 1's speed away from the Sun (km/s), for how long it'd take to get there. */
    static final double VOYAGER_KMS = 16.9;

    /** Somewhere to send a postcard from: a planet, its star, how far, and what it's known for. */
    public static final class Dest {
        public final String planet, star, about;
        public final double raDeg, decDeg, lightYears;
        public final double[] dir;

        public Dest(String planet, String star, String about, double raDeg, double decDeg, double lightYears) {
            this.planet = planet;
            this.star = star;
            this.about = about;
            this.raDeg = raDeg;
            this.decDeg = decDeg;
            this.lightYears = lightYears;
            dir = Astro.vec(raDeg * Astro.DEG, decDeg * Astro.DEG);
        }
    }

    /** The planets (by their IAU names where they have one) of X-Ray Vision's stars with planets, in its order. */
    private static final String[][] PLANET = {
            {"51 Pegasi", "Dimidium"}, {"Upsilon Andromedae", "Saffar"}, {"55 Cancri", "Janssen"}, {"Epsilon Eridani", "AEgir"},
            {"Pollux", "Thestias"}, {"Errai", "Tadmor"}, {"Tau Boötis", "Tau Boötis b"}, {"47 Ursae Majoris", "Taphao Thong"},
            {"70 Virginis", "70 Virginis b"}, {"HD 189733", "HD 189733 b"}, {"HD 209458", "Osiris"}, {"TRAPPIST-1", "TRAPPIST-1e"},
            {"Kepler-186", "Kepler-186f"}, {"Kepler-452", "Kepler-452b"}, {"Kepler-16", "Kepler-16b"}, {"Barnard's Star", "Barnard's Star b"},
    };

    /** Proxima Centauri's planet: the nearest planet to the Solar System, where the postcard comes from when nothing else is up. */
    public static final Dest PROXIMA = new Dest("Proxima b", "Proxima Centauri", "the nearest planet to the Solar System",
            217.42896, -62.67949, 4.2465);

    private static List<Dest> all;

    /** Every place a postcard can come from: X-Ray Vision's stars with planets, then Proxima b. */
    public static synchronized List<Dest> all() {
        if (all != null) return all;
        List<Dest> l = new ArrayList<Dest>();
        for (XRayVision.Thing t : XRayVision.all()) {
            if (t.kind != XRayVision.Kind.PLANETS) continue;
            String planet = t.name + " b";
            for (String[] p : PLANET) if (p[0].equals(t.name)) planet = p[1];
            l.add(new Dest(planet, t.name, t.line, t.raDeg, t.decDeg, t.lightYears));
        }
        l.add(PROXIMA);
        return all = java.util.Collections.unmodifiableList(l);
    }

    /** The destination X-Ray Vision's star of {@code name} has (null if none). */
    public static Dest of(String star) {
        for (Dest d : all()) if (d.star.equals(star)) return d;
        return null;
    }

    /**
     * The line under the planet's name, from X-Ray Vision's words for its star: "A planet of Upsilon Andromedae, a star
     * with 3 planets.", "A planet of Epsilon Eridani, 10.5 light years away.", "A planet of 51 Pegasi: the first planet
     * around a star like the Sun."
     */
    public static String aboutLine(Dest d) {
        String a = d.about.replace(" \u00b7 ", ", ");
        if (d.star.equals(d.planet)) return a.isEmpty() ? "" : Character.toUpperCase(a.charAt(0)) + a.substring(1) + ".";
        String one = "star with a planet, ";
        if (a.startsWith(one)) return "A planet of " + d.star + ", " + a.substring(one.length()) + ".";
        if (a.startsWith("star ")) return "A planet of " + d.star + ", a " + a + ".";
        return "A planet of " + d.star + ": " + a + ".";
    }

    /** Why the postcard comes from where it does. */
    public enum Why {IN_PHOTO, UP, NEAREST}

    /**
     * Where the postcard comes from, for a photo whose X-Ray Vision {@code spots} are known (null: none): the star with
     * planets in the photo nearest its middle, else the one highest in the sky that night, else Proxima b. Returns the
     * place, and why ({@code why[0]}).
     */
    public static Dest choose(List<XRayVision.Spot> spots, Why[] why) {
        Dest best = null;
        double score = Double.MAX_VALUE;
        if (spots != null) {
            for (XRayVision.Spot s : spots) {
                if (!s.inPhoto || s.what.kind != XRayVision.Kind.PLANETS) continue;
                double d = Math.hypot(s.u - 0.5, s.v - 0.5);
                Dest c = of(s.what.name);
                if (c != null && d < score) {
                    score = d;
                    best = c;
                }
            }
            if (best != null) {
                if (why != null) why[0] = Why.IN_PHOTO;
                return best;
            }
            double high = 0;
            for (XRayVision.Spot s : spots) {
                if (!s.up || s.what.kind != XRayVision.Kind.PLANETS || !(s.altDeg > high)) continue;
                Dest c = of(s.what.name);
                if (c != null) {
                    high = s.altDeg;
                    best = c;
                }
            }
            if (best != null) {
                if (why != null) why[0] = Why.UP;
                return best;
            }
        }
        if (why != null) why[0] = Why.NEAREST;
        return PROXIMA;
    }

    /** The sky from a destination: every star where it's seen from there, how bright, and what's become of what we know. */
    public static final class View {
        public final Dest to;
        /** Per catalogue star: its direction from there (3 per star, J2000 axes), magnitude (NaN: left out, their own sun). */
        public final double[] dir;
        public final float[] mag;
        /** The Sun from there: direction, magnitude, and the constellation it's in as we name the sky's parts. */
        public final double[] sun;
        public double sunMag;
        public String sunIn = "";
        /** Their brightest star (catalogue index; -1 if none) and its magnitude there. */
        public int brightest = -1;
        public double brightestMag = Double.NaN;
        /** The constellations (IAU abbreviations) whose shapes change most and least, among the well-known ones. */
        public String changedMost = "", changedLeast = "";
        public double changeMost, changeLeast;
        /** The catalogue star that is their own sun (-1: not in the catalogue). */
        public int ownSun = -1;

        View(Dest to, int n) {
            this.to = to;
            dir = new double[3 * n];
            mag = new float[n];
            sun = new double[]{-to.dir[0], -to.dir[1], -to.dir[2]};
        }
    }

    /**
     * How far a catalogue star is (light years): its own distance, a close neighbour's, or {@link #FAR}. Alpha Centauri's
     * pair to three figures (the catalogue's 4.3 puts them 0.18 light years from Proxima instead of 0.21).
     */
    static double distance(BrightStars cat, int i) {
        if ("Cen".equals(cat.con[i]) && cat.letter[i].startsWith("alpha")) return 4.367;
        float ly = cat.ly[i];
        if (ly > 0) return ly;
        double best = FAR, near = Math.cos(0.08 * Astro.DEG);
        for (int k = 0; k < cat.n; k++) {
            if (k == i || !(cat.ly[k] > 0)) continue;
            double d = cat.dir[3 * i] * cat.dir[3 * k] + cat.dir[3 * i + 1] * cat.dir[3 * k + 1] + cat.dir[3 * i + 2] * cat.dir[3 * k + 2];
            if (d > near) {
                near = d;
                best = cat.ly[k];
            }
        }
        return best;
    }

    /** The famous constellations, whose shapes people know (for what changes most and least). */
    static final String[] FAMOUS = {"Ori", "UMa", "Cas", "Cyg", "Leo", "Sco", "Tau", "Gem", "Lyr", "Cru", "Peg", "And", "Per", "Boo",
            "Vir", "Sgr", "Aql", "CMa", "Aur", "UMi", "Cen", "Dra", "Her", "Cep"};

    /** The sky from {@code to}, with the catalogue {@code cat}. */
    public static View view(Dest to, BrightStars cat) {
        int n = cat.n;
        View v = new View(to, n);
        double L = to.lightYears;
        double[] d = to.dir;
        double[] dist = new double[n];
        for (int i = 0; i < n; i++) dist[i] = distance(cat, i);
        // their own sun, if the catalogue has it: within a few arc minutes, at about its distance
        double own = Math.cos(0.1 * Astro.DEG);
        for (int i = 0; i < n; i++) {
            double c = cat.dir[3 * i] * d[0] + cat.dir[3 * i + 1] * d[1] + cat.dir[3 * i + 2] * d[2];
            if (c > own && Math.abs(dist[i] - L) < 0.25 * L) {
                own = c;
                v.ownSun = i;
            }
        }
        double best = 99;
        for (int i = 0; i < n; i++) {
            if (i == v.ownSun) {
                v.mag[i] = Float.NaN;
                continue;
            }
            double r = dist[i];
            double qx = r * cat.dir[3 * i] - L * d[0], qy = r * cat.dir[3 * i + 1] - L * d[1], qz = r * cat.dir[3 * i + 2] - L * d[2];
            double rr = Math.sqrt(qx * qx + qy * qy + qz * qz);
            if (rr < 1e-6) {
                v.mag[i] = Float.NaN;
                continue;
            }
            v.dir[3 * i] = qx / rr;
            v.dir[3 * i + 1] = qy / rr;
            v.dir[3 * i + 2] = qz / rr;
            v.mag[i] = (float) (cat.vmag[i] + 5 * Math.log10(rr / r));
            if (v.mag[i] < best) {
                best = v.mag[i];
                v.brightest = i;
            }
        }
        v.brightestMag = v.brightest >= 0 ? v.mag[v.brightest] : Double.NaN;
        v.sunMag = SUN_ABS + 5 * Math.log10(L / LY_PER_PC / 10);
        v.sunIn = BrightStars.constellation(Constellations.at(v.sun));
        changes(v, cat, dist);
        return v;
    }

    /** How much each famous constellation's shape changes from there: the share its lines' lengths change by, on average. */
    private static void changes(View v, BrightStars cat, double[] dist) {
        double most = -1, least = Double.MAX_VALUE;
        for (String abbr : FAMOUS) {
            Figures.Figure f = Figures.of(abbr);
            double c = f != null ? change(v, cat, f) : Double.NaN;
            if (Double.isNaN(c)) continue;
            if (c > most) {
                most = c;
                v.changedMost = abbr;
                v.changeMost = c;
            }
            if (c < least) {
                least = c;
                v.changedLeast = abbr;
                v.changeLeast = c;
            }
        }
    }

    /**
     * How much a constellation's shape changes from there: the mean of |ln(new length / old length)| over its lines
     * (0: the same shape; 0.7: lines half or twice as long). NaN when one of its stars isn't in the catalogue.
     */
    static double change(View v, BrightStars cat, Figures.Figure f) {
        double sum = 0;
        int segs = 0;
        for (double[][] run : f.runs) {
            int prev = -1;
            for (double[] star : run) {
                int k = corner(cat, star);
                if (k < 0 || Float.isNaN(v.mag[k])) return Double.NaN;
                if (prev >= 0 && prev != k) {
                    double was = Astro.sep(cat.vec(prev), cat.vec(k));
                    double now = Astro.sep(new double[]{v.dir[3 * prev], v.dir[3 * prev + 1], v.dir[3 * prev + 2]},
                            new double[]{v.dir[3 * k], v.dir[3 * k + 1], v.dir[3 * k + 2]});
                    if (was > 1e-4) {
                        sum += Math.abs(Math.log(now / was));
                        segs++;
                    }
                }
                prev = k;
            }
        }
        return segs == 0 ? Double.NaN : sum / segs;
    }

    /** The figures' corners matched to the catalogue once (a corner's direction to its star's index). */
    private static java.util.Map<double[], Integer> corners;
    private static BrightStars cornersOf;

    private static synchronized int corner(BrightStars cat, double[] dir) {
        if (cornersOf != cat || corners == null) {
            corners = new java.util.IdentityHashMap<double[], Integer>();
            cornersOf = cat;
        }
        Integer k = corners.get(dir);
        if (k == null) {
            k = nearest(cat, dir);
            corners.put(dir, k);
        }
        return k;
    }

    /** The catalogue star a figure's corner is (within 0.05°; -1 if none). */
    static int nearest(BrightStars cat, double[] dir) {
        int best = -1;
        double c0 = Math.cos(0.05 * Astro.DEG);
        for (int i = 0; i < cat.n && i < 3000; i++) {
            double c = cat.dir[3 * i] * dir[0] + cat.dir[3 * i + 1] * dir[1] + cat.dir[3 * i + 2] * dir[2];
            if (c > c0) {
                c0 = c;
                best = i;
            }
        }
        return best;
    }

    /** "5.8" for a magnitude, with a proper minus sign for the bright ones. */
    static String magWords(double m) {
        String s = String.format(Locale.US, "%.1f", m);
        return s.startsWith("-") ? "−" + s.substring(1) : s;
    }

    /** How the Sun looks from there, in words a child gets. */
    static String sunLooks(double m) {
        if (m < -1) return "one of the brightest stars in the sky";
        if (m < 1.5) return "a bright star";
        if (m < 4) return "an ordinary star";
        if (m < 6.3) return "a faint star, only just visible on a dark night";
        return "far too faint to see without a telescope";
    }

    /** The year the Sun's light seen there tonight left it. */
    static int lightLeft(double ly, int year) {
        return (int) Math.round(year - ly);
    }

    /** How long Voyager 1 would take to get there, in words ("900,000 years"). */
    static String voyagerYears(double ly) {
        double years = ly * 9.4607e12 / VOYAGER_KMS / 3.15576e7;
        long r = years >= 1e6 ? Math.round(years / 1e5) * 100000 : years >= 1e5 ? Math.round(years / 1e4) * 10000 : Math.round(years / 1e3) * 1000;
        if (r >= 1e6) {
            String m = String.format(Locale.US, "%.1f", r / 1e6);
            if (m.endsWith(".0")) m = m.substring(0, m.length() - 2);
            return m + " million years";
        }
        return String.format(Locale.US, "%,d years", r);
    }

    /** The postcard's message, as if written there. */
    public static String message(View v, BrightStars cat, int year) {
        StringBuilder b = new StringBuilder("Wish you were here! ");
        b.append(String.format(Locale.US, "From here our Sun is %s (magnitude %s) in the part of the sky you call %s.", sunLooks(v.sunMag),
                magWords(v.sunMag), v.sunIn));
        double ly = v.to.lightYears;
        if (ly < 2000) {
            int left = lightLeft(ly, year);
            b.append(String.format(Locale.US, " The sunlight I see tonight left the Sun in %s.", left > 0 ? Integer.toString(left)
                    : (-left + 1) + " BC"));
        }
        if (v.brightest >= 0) {
            int i = v.brightest;
            String named = cat.label(i);
            if (!named.isEmpty()) {
                b.append(String.format(Locale.US, " The brightest star here is %s (magnitude %s).", named, magWords(v.brightestMag)));
            } else {
                b.append(String.format(Locale.US, " The brightest star here is one you can hardly see from home, in %s: here it's magnitude %s.",
                        BrightStars.constellation(Constellations.at(cat.vec(i))), magWords(v.brightestMag)));
            }
        }
        if (!v.changedMost.isEmpty() && !v.changedLeast.isEmpty() && !v.changedMost.equals(v.changedLeast) && v.changeMost > 0.05) {
            b.append(String.format(Locale.US, " %s looks all wrong from here, but %s %s.", BrightStars.constellation(v.changedMost),
                    BrightStars.constellation(v.changedLeast), v.changeLeast < 0.02 ? "hasn't changed a bit" : "is still easy to spot"));
        } else if (!v.changedLeast.isEmpty()) {
            b.append(" The constellations look almost the same as from home: I'm not that far away.");
        }
        b.append(" Even Voyager 1 would take ").append(voyagerYears(ly)).append(" to get here.");
        return b.toString();
    }

    /** "24 NOV 2026" for the postmark. */
    static String postmark(double jdUtc, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone != null ? zone : TimeZone.getTimeZone("UTC"), Locale.US);
        if (!Double.isNaN(jdUtc)) c.setTimeInMillis(Math.round((jdUtc - 2440587.5) * 86400000.0));
        String[] m = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
        return c.get(Calendar.DAY_OF_MONTH) + " " + m[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR);
    }

    static int yearOf(double jdUtc, TimeZone zone) {
        return XRayVision.yearOf(jdUtc, zone);
    }

    // ------------------------------------------------------------------------------------------------
    // The postcard: cream card, the sky on the left, the message and a stamp of the photo on the right

    /** Where things go on a w x h postcard (3:2 works best: 1800 x 1200). */
    public static final class Layout {
        public final int w, h;
        public final float unit, skyX0, skyY0, skyX1, skyY1, stampX0, stampY0, stampX1, stampY1;
        /** The sky's half-width (degrees), stereographic. */
        public final double halfDeg = 52;

        Layout(int w, int h) {
            this.w = w;
            this.h = h;
            unit = Math.min(w / 1800f, h / 1200f);
            skyX0 = 54 * unit;
            skyY0 = 54 * unit;
            skyY1 = h - 54 * unit;
            skyX1 = skyX0 + (skyY1 - skyY0) * 1.0f;
            stampX1 = w - 60 * unit;
            stampY0 = 56 * unit;
            stampX0 = stampX1 - 210 * unit;
            stampY1 = stampY0 + 250 * unit;
        }
    }

    public static Layout layout(int w, int h) {
        return new Layout(w, h);
    }

    static final int PAPER = 0xFFF4EAD2, PAPER_EDGE = 0xFFE2D2AC, INK = 0xFF2B2A4A, INK_LIGHT = 0xFF5A577A, RED_INK = 0xFFB8322F,
            BLUE_INK = 0xFF1F4E8C, SKY_TOP = 0xFF05081A, SKY_BOTTOM = 0xFF0B1230, LINES = 0x6688AAFF, NAMES = 0xAAB9C8FF;

    /** The card's pixels before anything is drawn on: paper, the sky's dark window, and the stamp's picture ({@code photo}, ARGB pw x ph; null: none). */
    public static int[] background(Layout l, int[] photo, int pw, int ph) {
        int w = l.w, h = l.h;
        int[] px = new int[w * h];
        java.util.Random rnd = new java.util.Random(1977);
        float cx = w / 2f, cy = h / 2f, far = (float) Math.hypot(cx, cy);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float d = (float) Math.hypot(x - cx, y - cy) / far;
                float k = 1 - 0.1f * d * d;
                int grain = rnd.nextInt(7) - 3;
                int r = clamp((int) (((PAPER >> 16) & 255) * k) + grain), g = clamp((int) (((PAPER >> 8) & 255) * k) + grain),
                        b = clamp((int) ((PAPER & 255) * k) + grain);
                px[y * w + x] = 0xFF000000 | r << 16 | g << 8 | b;
            }
        }
        // the sky's window
        int x0 = Math.round(l.skyX0), x1 = Math.round(l.skyX1), y0 = Math.round(l.skyY0), y1 = Math.round(l.skyY1);
        for (int y = y0; y < y1; y++) {
            float t = (y - y0) / (float) Math.max(1, y1 - y0);
            int r = Math.round(((SKY_TOP >> 16) & 255) * (1 - t) + ((SKY_BOTTOM >> 16) & 255) * t);
            int g = Math.round(((SKY_TOP >> 8) & 255) * (1 - t) + ((SKY_BOTTOM >> 8) & 255) * t);
            int b = Math.round((SKY_TOP & 255) * (1 - t) + (SKY_BOTTOM & 255) * t);
            for (int x = x0; x < x1; x++) px[y * w + x] = 0xFF000000 | r << 16 | g << 8 | b;
        }
        // the stamp: white paper, the photo inside its margin
        int sx0 = Math.round(l.stampX0), sx1 = Math.round(l.stampX1), sy0 = Math.round(l.stampY0), sy1 = Math.round(l.stampY1);
        for (int y = sy0; y < sy1; y++) for (int x = sx0; x < sx1; x++) px[y * w + x] = 0xFFFBF8F0;
        if (photo != null && pw > 0 && ph > 0) {
            int m = Math.round(16 * l.unit);
            int ix0 = sx0 + m, ix1 = sx1 - m, iy0 = sy0 + m, iy1 = sy1 - m - Math.round(30 * l.unit);
            float bw = ix1 - ix0, bh = iy1 - iy0;
            float scale = Math.max(bw / pw, bh / ph);
            float ox = (pw - bw / scale) / 2, oy = (ph - bh / scale) / 2;
            for (int y = iy0; y < iy1; y++) {
                for (int x = ix0; x < ix1; x++) {
                    int sx = Math.min(pw - 1, Math.max(0, (int) (ox + (x - ix0 + 0.5f) / scale)));
                    int sy = Math.min(ph - 1, Math.max(0, (int) (oy + (y - iy0 + 0.5f) / scale)));
                    px[y * w + x] = 0xFF000000 | photo[sy * pw + sx];
                }
            }
        }
        return px;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    /** A star's colour from its B-V colour index (blue-white to orange). */
    static int colour(float bv) {
        if (Float.isNaN(bv)) return 0xFFFFF6EC;
        float[] at = {-0.3f, 0f, 0.3f, 0.6f, 0.9f, 1.2f, 1.6f, 2.0f};
        int[] c = {0x9BB0FF, 0xCAD7FF, 0xF8F7FF, 0xFFF4EA, 0xFFE2BE, 0xFFCC99, 0xFFB46B, 0xFFA350};
        if (bv <= at[0]) return 0xFF000000 | c[0];
        for (int i = 1; i < at.length; i++) {
            if (bv <= at[i]) {
                float t = (bv - at[i - 1]) / (at[i] - at[i - 1]);
                int r = Math.round(((c[i - 1] >> 16) & 255) * (1 - t) + ((c[i] >> 16) & 255) * t);
                int g = Math.round(((c[i - 1] >> 8) & 255) * (1 - t) + ((c[i] >> 8) & 255) * t);
                int b = Math.round((c[i - 1] & 255) * (1 - t) + (c[i] & 255) * t);
                return 0xFF000000 | r << 16 | g << 8 | b;
            }
        }
        return 0xFF000000 | c[c.length - 1];
    }

    /** A constellation whose lines change length by more than this (mean |ln|) has fallen apart: not drawn. */
    static final double FALLEN_APART = 0.4;

    /** What was drawn. */
    public static final class Drawn {
        public int stars, lines, names, figures;
        /** The Sun's place on the card (NaN if it's off the sky's window, which it never should be). */
        public float sunX = Float.NaN, sunY = Float.NaN;
    }

    /** Projects J2000 direction {@code v} onto the sky's window, looking at the Sun; null when it's outside the window. */
    static float[] project(Layout l, double[] c, double[] up, double[] right, double x, double y, double z) {
        double cz = x * c[0] + y * c[1] + z * c[2];
        if (cz < -0.5) return null;
        double k = 2 / (1 + cz);
        double px = (x * right[0] + y * right[1] + z * right[2]) * k, py = (x * up[0] + y * up[1] + z * up[2]) * k;
        double max = 2 * Math.tan(Math.toRadians(l.halfDeg) / 2);
        float half = (l.skyX1 - l.skyX0) / 2, mx = (l.skyX0 + l.skyX1) / 2, my = (l.skyY0 + l.skyY1) / 2;
        float sx = (float) (mx + px / max * half), sy = (float) (my - py / max * half);
        if (sx < l.skyX0 || sx > l.skyX1 || sy < l.skyY0 || sy > l.skyY1) return null;
        return new float[]{sx, sy};
    }

    /**
     * The postcard on a card made by {@link #background}: the sky from there (stars by brightness and colour, the
     * constellations' lines and names, the Sun ringed), "Greetings from", the message, the stamp's edge and postmark, and
     * the address.
     */
    public static Drawn paint(Painter p, Layout l, View v, BrightStars cat, double jdUtc, TimeZone zone, String why) {
        Drawn d = new Drawn();
        float u = l.unit;
        double[] c = v.sun, north = {0, 0, 1};
        double dot = north[0] * c[0] + north[1] * c[1] + north[2] * c[2];
        double[] up = {north[0] - dot * c[0], north[1] - dot * c[1], north[2] - dot * c[2]};
        double un = Math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]);
        if (un < 1e-6) up = new double[]{1, 0, 0};
        else for (int i = 0; i < 3; i++) up[i] /= un;
        double[] right = {c[1] * up[2] - c[2] * up[1], c[2] * up[0] - c[0] * up[2], c[0] * up[1] - c[1] * up[0]};
        // the constellations' lines, then their names: those that still look like something (far away, they fall apart)
        List<Figures.Figure> kept = new ArrayList<Figures.Figure>();
        for (Figures.Figure f : Figures.all()) {
            double ch = change(v, cat, f);
            if (!Double.isNaN(ch) && ch < FALLEN_APART) kept.add(f);
        }
        d.figures = kept.size();
        for (Figures.Figure f : kept) {
            for (double[][] run : f.runs) {
                float[] prev = null;
                for (double[] star : run) {
                    int k = corner(cat, star);
                    float[] q = k >= 0 && !Float.isNaN(v.mag[k]) ? project(l, c, up, right, v.dir[3 * k], v.dir[3 * k + 1], v.dir[3 * k + 2]) : null;
                    if (q != null && prev != null && Math.hypot(q[0] - prev[0], q[1] - prev[1]) < 420 * u) {
                        p.line(prev[0], prev[1], q[0], q[1], 1.3f * u, LINES);
                        d.lines++;
                    }
                    prev = q;
                }
            }
        }
        List<float[]> taken = new ArrayList<float[]>();
        for (Figures.Figure f : kept) {
            // the name where the figure's stars are now: their middle
            double sx = 0, sy = 0, sz = 0;
            int n = 0;
            for (double[][] run : f.runs) {
                for (double[] star : run) {
                    int k = corner(cat, star);
                    if (k < 0 || Float.isNaN(v.mag[k])) continue;
                    sx += v.dir[3 * k];
                    sy += v.dir[3 * k + 1];
                    sz += v.dir[3 * k + 2];
                    n++;
                }
            }
            if (n < 2) continue;
            float[] q = project(l, c, up, right, sx / n, sy / n, sz / n);
            if (q == null) continue;
            String name = BrightStars.constellation(f.abbr).toUpperCase(Locale.US);
            float size = 15 * u, tw = p.width(name, size, false);
            float[] box = {q[0] - tw / 2 - 4 * u, q[1] - size - 2 * u, q[0] + tw / 2 + 4 * u, q[1] + 4 * u};
            if (box[0] < l.skyX0 + 4 * u || box[2] > l.skyX1 - 4 * u || box[1] < l.skyY0 + 4 * u || box[3] > l.skyY1 - 4 * u) continue;
            if (overlaps(taken, box)) continue;
            p.text(name, q[0] - tw / 2, q[1], size, false, NAMES);
            taken.add(box);
            d.names++;
        }
        // the stars, faint first
        Integer[] order = new Integer[cat.n];
        int m = 0;
        for (int i = 0; i < cat.n; i++) if (!Float.isNaN(v.mag[i]) && v.mag[i] < 6.2f) order[m++] = i;
        final float[] mag = v.mag;
        java.util.Arrays.sort(order, 0, m, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Float.compare(mag[b], mag[a]);
            }
        });
        List<int[]> named = new ArrayList<int[]>();
        for (int j = 0; j < m; j++) {
            int i = order[j];
            float[] q = project(l, c, up, right, v.dir[3 * i], v.dir[3 * i + 1], v.dir[3 * i + 2]);
            if (q == null) continue;
            star(p, q[0], q[1], v.mag[i], colour(cat.bv[i]), u);
            d.stars++;
            if (v.mag[i] < 1.6f && !cat.name[i].isEmpty()) named.add(new int[]{i, Math.round(q[0]), Math.round(q[1])});
        }
        // the brightest stars' names (as we call them), brightest first
        for (int j = named.size() - 1, shown = 0; j >= 0 && shown < 7; j--) {
            int[] s = named.get(j);
            String name = cat.name[s[0]];
            float size = 17 * u, tw = p.width(name, size, true);
            float x = s[1] + 9 * u, y = s[2] - 7 * u;
            float[] box = {x - 2 * u, y - size, x + tw + 2 * u, y + 4 * u};
            if (box[2] > l.skyX1 - 4 * u || box[1] < l.skyY0 + 4 * u || overlaps(taken, box)) continue;
            p.text(name, x, y, size, true, 0xFFE8EEFF);
            taken.add(box);
            shown++;
        }
        // the Sun: a star like any other, ringed, and named
        float[] s = project(l, c, up, right, c[0], c[1], c[2]);
        if (s != null) {
            d.sunX = s[0];
            d.sunY = s[1];
            star(p, s[0], s[1], (float) v.sunMag, 0xFFFFF4E0, u);
            p.ring(s[0], s[1], 22 * u, 2.4f * u, 0xFFFFD166);
            p.line(s[0] + 16 * u, s[1] + 16 * u, s[0] + 60 * u, s[1] + 60 * u, 2.2f * u, 0xFFFFD166);
            String sun = "THE SUN", sub = "home, " + distanceWords(v.to.lightYears) + " away";
            p.text(sun, s[0] + 64 * u, s[1] + 76 * u, 24 * u, true, 0xFFFFE08A);
            p.text(sub, s[0] + 64 * u, s[1] + 100 * u, 17 * u, false, 0xFFFFE9B8);
        }
        p.text("The sky from " + v.to.planet + ", looking back at the Sun", l.skyX0 + 18 * u, l.skyY1 - 20 * u, 18 * u, false, 0xCCDCE6FF);
        // the stamp's perforations, its words, and the postmark over its corner
        float pr = 7 * u;
        for (float x = l.stampX0; x <= l.stampX1 + 0.5f; x += 2.4f * pr) {
            p.disc(x, l.stampY0, pr, PAPER);
            p.disc(x, l.stampY1, pr, PAPER);
        }
        for (float y = l.stampY0; y <= l.stampY1 + 0.5f; y += 2.4f * pr) {
            p.disc(l.stampX0, y, pr, PAPER);
            p.disc(l.stampX1, y, pr, PAPER);
        }
        int year = yearOf(jdUtc, zone);
        String st = "EARTH " + year;
        p.text(st, (l.stampX0 + l.stampX1) / 2 - p.width(st, 19 * u, true) / 2, l.stampY1 - 18 * u, 19 * u, true, BLUE_INK);
        float pmx = l.stampX0 - 44 * u, pmy = l.stampY1 - 8 * u, pmr = 66 * u;
        p.ring(pmx, pmy, pmr, 3 * u, 0x99332F4F);
        p.ring(pmx, pmy, pmr - 14 * u, 1.5f * u, 0x99332F4F);
        String date = postmark(jdUtc, zone), place = "SOLAR SYSTEM";
        p.text(date, pmx - p.width(date, 18 * u, true) / 2, pmy + 7 * u, 18 * u, true, 0xAA332F4F);
        p.text(place, pmx - p.width(place, 13 * u, false) / 2, pmy - 26 * u, 13 * u, false, 0xAA332F4F);
        // the postmark's wavy lines, out to the left over the card
        for (int k = 0; k < 4; k++) {
            float y = pmy - 30 * u + k * 20 * u;
            for (float x = pmx - pmr - 150 * u; x < pmx - pmr - 8 * u; x += 18 * u) {
                p.line(x, y, x + 9 * u, y + 5 * u, 2 * u, 0x77332F4F);
                p.line(x + 9 * u, y + 5 * u, x + 18 * u, y, 2 * u, 0x77332F4F);
            }
        }
        // the card's own heading, as old postcards have
        float tx = l.skyX1 + 50 * u, wide = l.w - 60 * u - tx;
        p.text("P O S T C A R D", tx, 112 * u, 34 * u, true, INK);
        p.text("SENT BY STARLIGHT \u00b7 " + distanceWords(v.to.lightYears).toUpperCase(Locale.US), tx, 146 * u, 17 * u, false, RED_INK);
        // "Greetings from", the planet, what it is
        float gy = l.stampY1 + 78 * u;
        p.text("Greetings from", tx, gy, 38 * u, false, RED_INK);
        float big = 78 * u;
        String planet = v.to.planet + "!";
        while (big > 34 * u && p.width(planet, big, true) > wide) big *= 0.92f;
        p.text(planet, tx, gy + 12 * u + big, big, true, RED_INK);
        float y = gy + 12 * u + big + 42 * u;
        String about = aboutLine(v.to);
        for (String t : XRayVision.wrap(p, about, 22 * u, wide)) {
            p.text(t, tx, y, 22 * u, false, INK_LIGHT);
            y += 29 * u;
        }
        if (!why.isEmpty()) {
            for (String t : XRayVision.wrap(p, why, 20 * u, wide)) {
                p.text(t, tx, y, 20 * u, true, BLUE_INK);
                y += 27 * u;
            }
        }
        // the message
        y += 24 * u;
        String msg = message(v, cat, year);
        float ms = 25 * u;
        List<String> lines = XRayVision.wrap(p, msg, ms, wide);
        float addressTop = l.h - 250 * u;
        while (y + lines.size() * ms * 1.36f > addressTop - 20 * u && ms > 16 * u) {
            ms *= 0.94f;
            lines = XRayVision.wrap(p, msg, ms, wide);
        }
        for (String t : lines) {
            p.text(t, tx, y, ms, false, INK);
            y += ms * 1.36f;
        }
        // the address, on ruled lines
        String[] to = {"To: Planet Earth", "Third planet from the Sun", "Orion Arm, the Milky Way"};
        float ay = addressTop + 40 * u;
        for (String t : to) {
            p.text(t, tx + 30 * u, ay, 26 * u, false, BLUE_INK);
            p.line(tx + 20 * u, ay + 12 * u, l.w - 70 * u, ay + 12 * u, 1.2f * u, 0x88807A66);
            ay += 56 * u;
        }
        return d;
    }

    /** "51 light years", "4.2 light years". */
    static String distanceWords(double ly) {
        if (ly < 10) return String.format(Locale.US, "%.1f light years", ly);
        return String.format(Locale.US, "%,d light years", Math.round(ly));
    }

    private static boolean overlaps(List<float[]> taken, float[] box) {
        for (float[] o : taken) if (box[0] < o[2] && box[2] > o[0] && box[1] < o[3] && box[3] > o[1]) return true;
        return false;
    }

    /** A star of magnitude {@code m}: a soft glow and a core, bigger for brighter. */
    private static void star(Painter p, float x, float y, float m, int rgb, float u) {
        float r = (float) Math.max(0.9, Math.min(9.5, 1.15 * (6.6 - m))) * u;
        if (m < 2.5f) p.disc(x, y, r * 2.4f, (Math.min(140, (int) (40 + 25 * (2.5f - m))) << 24) | (rgb & 0xFFFFFF));
        int alpha = m < 5 ? 255 : (int) Math.max(110, 255 - (m - 5) * 110);
        p.disc(x, y, r, (alpha << 24) | (rgb & 0xFFFFFF));
    }
}
