package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Earth's Address (2.6): the pulsar map that Pioneer 10 and 11 carry on their plaques and both Voyagers on their Golden
 * Records, to show whoever finds them where the Sun is. Fourteen lines run out from the Sun, each to a pulsar (a dead
 * star that ticks like a clock), its length how far, the marks along it how fast it ticks: in binary, counted in flips
 * of a hydrogen atom (0.704 billionths of a second), the one clock any scientist anywhere would know. A fifteenth line
 * points to the middle of the galaxy.
 * <p>
 * Pulsars slow down, each at its own known rate, so the map tells the time as well as the place: the periods on it are
 * those measured around 1971. Here it's redrawn for the night of a photo, each period worked out from the pulsar's
 * timing today (the ATNF catalogue's period and slowdown), the digits that changed since the plaque in another colour,
 * and the pulsars that were in the photo, or up in the sky, picked out.
 * <p>
 * The map's numbers are Wm. Robert Johnston's reading of it ("The pulsar map", johnstonsarchive.net, Tables 1 and 6:
 * the binary periods, the lines' angles from the galactic centre's and their lengths, galactic centre = 100); positions,
 * periods, slowdowns and epochs from the ATNF Pulsar Catalogue as his Table 3 gives them. Distances are the
 * catalogue's, but parallaxes for B0950+08 (Brisken et al. 2002) where its 2002 value is far off.
 */
public final class GoldenRecord {
    private GoldenRecord() {}

    /** Hydrogen's hyperfine (21 cm) transition period: the map's unit of time, its "1" (seconds). */
    public static final double HYDROGEN_S = 7.04024183647e-10;
    /** 1971.0 (Julian date), about when the plaque's periods were measured. */
    public static final double PLAQUE_JD = 2440952.5;

    /** One of the fourteen. */
    public static final class Pulsar {
        /** Its line on the map: 1 to 14, clockwise from the galactic centre's. */
        public final int n;
        /** "PSR B0531+21", and what it's called if anything ("the Crab Pulsar"; "" if nothing). */
        public final String name, nick;
        /** Its period on the plaque, in binary, counted in {@link #HYDROGEN_S}. */
        public final String plaque;
        /** Its line's angle from the galactic centre's (degrees, counterclockwise as the map is drawn), and length (centre's = 100). */
        public final double angleDeg, length;
        /** J2000 position (degrees), galactic latitude (degrees), and the J2000 direction. */
        public final double raDeg, decDeg, bDeg;
        public final double[] dir;
        /** Its timing today: period (s) at {@link #epochJd}, and how fast it grows (s per s). */
        public final double period, pdot, epochJd;
        public final double lightYears;
        /** One sentence about it. */
        public final String line;

        Pulsar(int n, String name, String nick, String plaque, double angleDeg, double length, double raDeg, double decDeg, double bDeg,
               double period, double pdot15, double epochJd, double parsecs, String line) {
            this.n = n;
            this.name = name;
            this.nick = nick;
            this.plaque = plaque;
            this.angleDeg = angleDeg;
            this.length = length;
            this.raDeg = raDeg;
            this.decDeg = decDeg;
            this.bDeg = bDeg;
            this.period = period;
            this.pdot = pdot15 * 1e-15;
            this.epochJd = epochJd;
            this.lightYears = parsecs * 3.26156;
            this.line = line;
            dir = Astro.vec(raDeg * Astro.DEG, decDeg * Astro.DEG);
        }

        /** "PSR B0531+21" without the "PSR". */
        public String shortName() {
            return name.startsWith("PSR ") ? name.substring(4) : name;
        }
    }

    private static double ra(double h, double m, double s) {
        return 15 * (h + m / 60 + s / 3600);
    }

    private static double dec(double d, double m, double s) {
        return Math.signum(d) * (Math.abs(d) + m / 60 + s / 3600);
    }

    /** The map's fourteen, clockwise from the galactic centre's line (1 to 8 below it as drawn, 9 to 14 above). */
    static final Pulsar[] MAP = {
            new Pulsar(1, "PSR B1727-47", "", "1000110001111100100011011101010", -17, 27, ra(17, 31, 42.103), dec(-47, 44, 34.6), -7.67,
                    0.82982878524, 163.626, 2450939, 4980,
                    "It's slowing down faster than any other clock on the map except the Crab."),
            new Pulsar(2, "PSR B1451-68", "", "10110010011000101011101101111", -49, 2, ra(14, 56, 0.11), dec(-68, 43, 38.7), -8.54,
                    0.263376828611, 0.1013, 2448409, 458,
                    "One of the nearest pulsars, and the steadiest clock on the map: it has hardly slowed at all."),
            new Pulsar(3, "PSR B1240-64", "", "100000110110010110001001111000", -58, 56, ra(12, 43, 17.10), dec(-64, 23, 23.6), -1.53,
                    0.388481471997, 4.4997, 2448217, 7400,
                    "In 1971 its tick was only known roughly, so the plaque says 0.388 seconds: it's really 0.388481."),
            new Pulsar(4, "PSR B0833-45", "the Vela Pulsar", "111100011011011001010100111", -95, 15, ra(8, 35, 20.6761), dec(-45, 10, 35.76), -2.79,
                    0.08933001517, 124.88, 2451710, 287,
                    "Left by a star that exploded about 11,000 years ago. Now and then it has a starquake and suddenly spins a little faster."),
            new Pulsar(5, "PSR B0950+08", "", "10101011011001101100101000011", -129, 1, ra(9, 53, 9.316), dec(7, 55, 35.6), 43.70,
                    0.25306506819, 0.22915, 2441500, 262,
                    "One of the nearest pulsars, about 850 light years away, high above the Milky Way."),
            new Pulsar(6, "PSR B0823+26", "", "101100111011010101011110001011", -162, 2, ra(8, 26, 51.31), dec(26, 37, 25.57), 31.74,
                    0.53066079758, 1.7094, 2448383, 375,
                    "It ticks almost twice a second, well above the Milky Way's disc."),
            new Pulsar(7, "PSR B0531+21", "the Crab Pulsar", "10110011100000101010000010", -174, 18, ra(5, 34, 31.973), dec(22, 0, 52.06), -5.78,
                    0.0335223033700234, 420.5188, 2452016, 2490,
                    "Left by the star that exploded in the year 1054. It spins 30 times a second and slows down fastest of all."),
            new Pulsar(8, "PSR B0525+21", "", "100111101000110101000100111000100", -177, 11, ra(5, 28, 52.34), dec(22, 0, 0.0), -6.90,
                    3.7454972494, 40.046, 2442057, 2270,
                    "The slowest clock on the map: one tick every 3.7 seconds."),
            new Pulsar(9, "PSR B0329+54", "", "111100011111100011111000010110", 145, 7, ra(3, 32, 59.35), dec(54, 34, 43.2), -1.22,
                    0.71451866398, 2.04959, 2440621, 1030,
                    "The brightest pulsar in the northern sky."),
            new Pulsar(10, "PSR B2217+47", "", "101101100101101001000010110001", 97, 10, ra(22, 19, 48.136), dec(47, 54, 53.83), -7.60,
                    0.5384692479485, 2.76503, 2448382, 2450,
                    "It ticks almost twice a second, from far along the Milky Way."),
            new Pulsar(11, "PSR B2016+28", "", "101111001111001110011000001101", 68, 3, ra(20, 18, 3.851), dec(28, 39, 54.26), -3.98,
                    0.55795340728, 0.14936, 2440688, 1100,
                    "One of the steadiest clocks on the map."),
            new Pulsar(12, "PSR B1933+16", "", "11110010111110001110100011110", 52, 40, ra(19, 35, 47.835), dec(16, 16, 40.59), -2.09,
                    0.35873624827, 6.00354, 2442264, 7940,
                    "One of the brightest pulsars radio telescopes hear, deep in the Milky Way."),
            new Pulsar(13, "PSR B1929+10", "", "10011001011010111010010111000", 45, 1, ra(19, 32, 13.9), dec(10, 59, 31.99), -3.88,
                    0.226517820862, 1.15661, 2448381, 361,
                    "One of the nearest pulsars, about 1,200 light years away."),
            new Pulsar(14, "PSR B1642-03", "", "100000110100101010001110101100", 16, 4, ra(16, 45, 2.045), dec(-3, 17, 58.4), 26.06,
                    0.38768879135, 1.781, 2440621, 2900,
                    "High above the Milky Way's disc, like a lighthouse on a hill."),
    };

    /** The map's fourteen, in its order. */
    public static List<Pulsar> map() {
        List<Pulsar> l = new ArrayList<Pulsar>();
        java.util.Collections.addAll(l, MAP);
        return l;
    }

    /** Its period on the plaque (seconds). */
    public static double plaqueSeconds(Pulsar p) {
        return Long.parseLong(p.plaque, 2) * HYDROGEN_S;
    }

    /** Its period at {@code jd} (seconds), from its timing today: the slowdown carried on steadily (glitches aside). */
    public static double periodAt(Pulsar p, double jd) {
        return p.period + p.pdot * (jd - p.epochJd) * 86400;
    }

    /** Its period at {@code jd} as the map writes it: binary, counted in hydrogen flips. */
    public static String binaryAt(Pulsar p, double jd) {
        return Long.toBinaryString(Math.round(periodAt(p, jd) / HYDROGEN_S));
    }

    /** How much slower it ticks at {@code jd} than in 1971 (seconds). */
    public static double slowedSince1971(Pulsar p, double jd) {
        return p.pdot * (jd - PLAQUE_JD) * 86400;
    }

    /**
     * The year its plaque period says, from its timing today: when it ticked exactly as the plaque says. About 1970 for
     * all of them but B1240-64, whose period the plaque rounds.
     */
    public static double yearOfPlaque(Pulsar p) {
        double jd = p.epochJd + (plaqueSeconds(p) - p.period) / p.pdot / 86400;
        return 2000 + (jd - Astro.J2000) / 365.25;
    }

    /** How many digits of {@code now} differ from {@code was} (lined up at their right ends; extra ones all count). */
    public static int changed(String was, String now) {
        int n = Math.max(was.length(), now.length()), d = 0;
        for (int i = 0; i < n; i++) {
            int a = was.length() - 1 - i, b = now.length() - 1 - i;
            char ca = a >= 0 ? was.charAt(a) : '0', cb = b >= 0 ? now.charAt(b) : '0';
            if (ca != cb) d++;
        }
        return d;
    }

    /** All the digits on the map. */
    public static int digits() {
        int n = 0;
        for (Pulsar p : MAP) n += p.plaque.length();
        return n;
    }

    /** One of the fourteen for a photo: where it was, and its tick on the photo's night. */
    public static final class Mark {
        public final Pulsar p;
        public boolean inPhoto, up;
        /** Where in the photo (shares of its crop; NaN: not in it), and how high and which way (NaN: not known). */
        public double u = Double.NaN, v = Double.NaN, altDeg = Double.NaN, azDeg = Double.NaN;
        /** Its period on the night (s), as the map writes it, and how many of the plaque's digits changed. */
        public double seconds;
        public String now = "";
        public int changed;
        /** "PSR B0329+54: up in your sky, 62° high in the NE", and its sentence with its tick that night. */
        public String head = "", words = "";

        Mark(Pulsar p) {
            this.p = p;
        }
    }

    /**
     * The map's fourteen for a photo placed on the sky by {@code sol} (null: none of them in it) with its crop (x0, y0, w,
     * h), taken at {@code jdUtc} from {@code sky}'s place (null or not located: not known which were up): in the map's
     * order, each with its tick that night and the digits that changed.
     */
    public static List<Mark> marks(SkySolver.Solution sol, double jdUtc, SessionSky sky, int x0, int y0, int w, int h) {
        List<Mark> out = new ArrayList<Mark>();
        SkyGuide.Frame f = sol != null && sol.model != null && w > 0 && h > 0 ? new SkyGuide.Frame(sol, x0, y0, w, h) : null;
        double[] enu = sky != null && sky.located() && !Double.isNaN(jdUtc)
                ? Astro.j2000ToEnu(jdUtc, sky.latDeg * Astro.DEG, sky.lonDeg * Astro.DEG) : null;
        double jd = Double.isNaN(jdUtc) ? Astro.jdUtc(System.currentTimeMillis()) : jdUtc;
        for (Pulsar p : MAP) {
            Mark m = new Mark(p);
            double[] uv = f != null ? f.near(p.dir) : null;
            if (uv != null && SkyGuide.inside(uv)) {
                m.inPhoto = true;
                m.u = uv[0];
                m.v = uv[1];
            }
            if (enu != null) {
                double[] e = new double[3];
                Rot.apply(enu, p.dir[0], p.dir[1], p.dir[2], e);
                m.altDeg = Astro.altitude(e) / Astro.DEG;
                m.azDeg = Astro.azimuth(e) / Astro.DEG;
                m.up = m.altDeg > 0;
            }
            m.seconds = periodAt(p, jd);
            m.now = binaryAt(p, jd);
            m.changed = changed(p.plaque, m.now);
            String where = m.inPhoto ? "in your photo, " + SkyGuide.where(m.u, m.v)
                    : m.up ? String.format(Locale.US, "up in your sky, %.0f° high in the %s", m.altDeg, Probes.compass(m.azDeg))
                    : enu != null ? "below the horizon that night" : "";
            m.head = p.name + (p.nick.isEmpty() ? "" : " (" + p.nick + ")") + (where.isEmpty() ? "" : ": " + where);
            m.words = p.line + " " + tickWords(m.seconds, slowedSince1971(p, jd)) + " In " + BrightStars.constellation(Constellations.at(p.dir)) + ".";
            out.add(m);
        }
        return out;
    }

    /** "It ticks every 0.714522 s that night, 3.7 millionths of a second slower than in 1971." */
    static String tickWords(double seconds, double slowed) {
        String s = String.format(Locale.US, seconds >= 1 ? "%.5f" : seconds >= 0.1 ? "%.6f" : "%.7f", seconds);
        String by = slowed >= 1e-4 ? String.format(Locale.US, "%.2f thousandths", slowed * 1e3)
                : slowed >= 1e-6 ? String.format(Locale.US, "%.1f millionths", slowed * 1e6)
                : String.format(Locale.US, "%.2f millionths", slowed * 1e6);
        return "That night it ticked every " + s + " seconds, " + by + " of a second slower than in 1971.";
    }

    /** How many of the plaque's digits changed by the photo's night, of all of them. */
    public static int changed(List<Mark> marks) {
        int n = 0;
        for (Mark m : marks) n += m.changed;
        return n;
    }

    public static int inPhoto(List<Mark> marks) {
        int n = 0;
        for (Mark m : marks) if (m.inPhoto) n++;
        return n;
    }

    public static int up(List<Mark> marks) {
        int n = 0;
        for (Mark m : marks) if (m.up) n++;
        return n;
    }

    /** "24 November 2026": the photo's day where it was taken. */
    public static String day(double jdUtc, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone != null ? zone : TimeZone.getTimeZone("UTC"), Locale.US);
        if (!Double.isNaN(jdUtc)) c.setTimeInMillis(Math.round((jdUtc - 2440587.5) * 86400000.0));
        String[] months = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
                "November", "December"};
        return c.get(Calendar.DAY_OF_MONTH) + " " + months[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR);
    }

    /** What the map says for a photo, in a sentence or two (the result screen's, and the picture's description). */
    public static String summary(List<Mark> marks) {
        int in = inPhoto(marks), up = up(marks);
        String s = String.format(Locale.US, "%d of the map's %d digits have changed since 1971", changed(marks), digits());
        if (in > 0) s += in == 1 ? "; one of its pulsars is in your photo" : String.format(Locale.US, "; %d of its pulsars are in your photo", in);
        else if (up > 0) s += up == 1 ? "; one of its pulsars was up in your sky" : String.format(Locale.US, "; %d of its pulsars were up in your sky", up);
        return s;
    }

    /** The pulsars' ticks to hear, one after another in the map's order: the photo's first, then those up, then the rest. */
    public static List<Mark> toHear(List<Mark> marks) {
        List<Mark> l = new ArrayList<Mark>();
        for (Mark m : marks) if (m.inPhoto) l.add(m);
        for (Mark m : marks) if (!m.inPhoto && m.up) l.add(m);
        for (Mark m : marks) if (!m.inPhoto && !m.up) l.add(m);
        return l;
    }

    /** The ticks of {@code ms}, {@code each} seconds of each and {@code gap} of quiet after it (16-bit stereo, as a pulsar tour). */
    public static short[] tour(List<Mark> ms, double each, double gap, int rate) {
        int per = (int) Math.round((each + gap) * rate);
        short[] out = new short[per * ms.size() * 2];
        for (int k = 0; k < ms.size(); k++) {
            short[] m = XRayVision.beat(1 / ms.get(k).seconds, each, rate);
            int o = k * per * 2;
            for (int i = 0; i < m.length && i < per; i++) {
                out[o + 2 * i] = m[i];
                out[o + 2 * i + 1] = m[i];
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // The picture: gold on the black of space, the photo where the Sun is

    /** Where things go on a w x h picture: the Sun's place, the photo's disc round it, and how long the lines are. */
    public static final class Layout {
        public final int w, h;
        public final float cx, cy, disc, unit, lineMin, lineMax;

        Layout(int w, int h) {
            this.w = w;
            this.h = h;
            unit = Math.min(w, h) / 1600f;
            cx = w * 0.5f;
            cy = h * 0.52f;
            disc = 118 * unit;
            lineMin = 285 * unit;
            lineMax = 478 * unit;
        }

        /** How long pulsar {@code p}'s line is from the disc's edge: longer for farther (the plaque's own lengths, square-rooted to fit). */
        public float length(Pulsar p) {
            return (float) (lineMin + (lineMax - lineMin) * Math.sqrt(Math.min(1, p.length / 56.0)));
        }

        /** The line's direction on the picture (x right, y down). */
        static double[] way(Pulsar p) {
            double a = Math.toRadians(p.angleDeg);
            return new double[]{Math.cos(a), -Math.sin(a)};
        }
    }

    /** The layout of a w x h picture (square works best: 1600 x 1600). */
    public static Layout layout(int w, int h) {
        return new Layout(w, h);
    }

    static final int SPACE_TOP = 0xFF070A1C, SPACE_BOTTOM = 0xFF02030A, GOLD = 0xFFF2C14E, GOLD_DIM = 0xFFB08A3A, GOLD_LIGHT = 0xFFFFE6A3,
            NEW = 0xFF7FE8FF, WORDS = 0xFFFFF4DC, WORDS_DIM = 0xFFD9C9A3;

    /**
     * The picture's pixels before the lines and words are drawn on: the black of space with a little stardust and a gold
     * glow at the Sun's place, and {@code photo} (ARGB, pw x ph; null: none) filling the disc there, framed in gold.
     */
    public static int[] background(Layout l, int[] photo, int pw, int ph) {
        int w = l.w, h = l.h;
        int[] px = new int[w * h];
        java.util.Random rnd = new java.util.Random(1971);
        float glowR = l.disc * 3.2f;
        for (int y = 0; y < h; y++) {
            float t = y / (float) Math.max(1, h - 1);
            int r0 = mix((SPACE_TOP >> 16) & 255, (SPACE_BOTTOM >> 16) & 255, t), g0 = mix((SPACE_TOP >> 8) & 255, (SPACE_BOTTOM >> 8) & 255, t),
                    b0 = mix(SPACE_TOP & 255, SPACE_BOTTOM & 255, t);
            for (int x = 0; x < w; x++) {
                float d = (float) Math.hypot(x - l.cx, y - l.cy) / glowR;
                float g = d < 1 ? (1 - d) * (1 - d) * 0.22f : 0;
                int r = Math.min(255, (int) (r0 + 242 * g)), gg = Math.min(255, (int) (g0 + 170 * g)), b = Math.min(255, (int) (b0 + 60 * g));
                px[y * w + x] = 0xFF000000 | r << 16 | gg << 8 | b;
            }
        }
        // stardust: faint, mostly white, a few gold
        int dust = (int) (w * h / 2600f);
        for (int i = 0; i < dust; i++) {
            int x = rnd.nextInt(w), y = rnd.nextInt(h);
            float v = (float) Math.pow(rnd.nextDouble(), 3) * 0.75f + 0.08f;
            boolean gold = rnd.nextInt(5) == 0;
            add(px, w, h, x, y, v * (gold ? 255 : 220), v * (gold ? 200 : 225), v * (gold ? 120 : 240));
            if (v > 0.5f) {
                float s = v * 0.35f;
                add(px, w, h, x + 1, y, s * 200, s * 200, s * 210);
                add(px, w, h, x - 1, y, s * 200, s * 200, s * 210);
                add(px, w, h, x, y + 1, s * 200, s * 200, s * 210);
                add(px, w, h, x, y - 1, s * 200, s * 200, s * 210);
            }
        }
        if (photo != null && pw > 0 && ph > 0) disc(px, w, h, l.cx, l.cy, l.disc, photo, pw, ph);
        return px;
    }

    private static int mix(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    private static void add(int[] px, int w, int h, int x, int y, float r, float g, float b) {
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        int c = px[y * w + x];
        int rr = Math.min(255, ((c >> 16) & 255) + (int) r), gg = Math.min(255, ((c >> 8) & 255) + (int) g), bb = Math.min(255, (c & 255) + (int) b);
        px[y * w + x] = 0xFF000000 | rr << 16 | gg << 8 | bb;
    }

    /** {@code photo}'s middle square, filling a disc of radius {@code r} at (cx, cy), its edge smooth. */
    static void disc(int[] px, int w, int h, float cx, float cy, float r, int[] photo, int pw, int ph) {
        float side = Math.min(pw, ph), ox = (pw - side) / 2f, oy = (ph - side) / 2f, k = side / (2 * r);
        int y0 = Math.max(0, (int) Math.floor(cy - r - 1)), y1 = Math.min(h - 1, (int) Math.ceil(cy + r + 1));
        int x0 = Math.max(0, (int) Math.floor(cx - r - 1)), x1 = Math.min(w - 1, (int) Math.ceil(cx + r + 1));
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float d = (float) Math.hypot(x + 0.5f - cx, y + 0.5f - cy);
                float a = Math.max(0, Math.min(1, r - d + 0.5f));
                if (a <= 0) continue;
                // bilinear from the photo
                float sx = ox + (x + 0.5f - (cx - r)) * k - 0.5f, sy = oy + (y + 0.5f - (cy - r)) * k - 0.5f;
                int c = sample(photo, pw, ph, sx, sy);
                int o = px[y * w + x];
                int rr = Math.round(((c >> 16) & 255) * a + ((o >> 16) & 255) * (1 - a));
                int gg = Math.round(((c >> 8) & 255) * a + ((o >> 8) & 255) * (1 - a));
                int bb = Math.round((c & 255) * a + (o & 255) * (1 - a));
                px[y * w + x] = 0xFF000000 | rr << 16 | gg << 8 | bb;
            }
        }
    }

    private static int sample(int[] p, int w, int h, float x, float y) {
        x = Math.max(0, Math.min(w - 1.001f, x));
        y = Math.max(0, Math.min(h - 1.001f, y));
        int ix = (int) x, iy = (int) y;
        float fx = x - ix, fy = y - iy;
        int jx = Math.min(w - 1, ix + 1), jy = Math.min(h - 1, iy + 1);
        int a = p[iy * w + ix], b = p[iy * w + jx], c = p[jy * w + ix], d = p[jy * w + jx];
        int out = 0xFF000000;
        for (int s = 16; s >= 0; s -= 8) {
            float v = ((a >> s) & 255) * (1 - fx) * (1 - fy) + ((b >> s) & 255) * fx * (1 - fy) + ((c >> s) & 255) * (1 - fx) * fy
                    + ((d >> s) & 255) * fx * fy;
            out |= Math.min(255, Math.round(v)) << s;
        }
        return out;
    }

    /** What was drawn: the lines, their names, and the digits in the new colour. */
    public static final class Drawn {
        public int lines, labels, newDigits;
    }

    /**
     * The map on a picture made by {@link #background}: its title and the night's date at the top, the fifteen lines from
     * the photo's disc (each pulsar's tick along its line, the digits that changed since the plaque in blue, those in the
     * photo or up that night brighter and named so), the hydrogen atom that sets the unit, and how to read it.
     */
    public static Drawn paint(Painter p, Layout l, List<Mark> marks, double jdUtc, TimeZone zone) {
        Drawn d = new Drawn();
        float u = l.unit;
        // the title
        float title = 64 * u, sub = 25 * u;
        p.text("EARTH'S ADDRESS", 64 * u, 104 * u, title, true, GOLD_LIGHT);
        p.text("The pulsar map Pioneer 10 and 11 and both Voyagers carry out of the Solar System, redrawn for "
                + day(jdUtc, zone), 66 * u, 146 * u, sub, false, WORDS_DIM);
        // the galactic centre's line, under everything
        float gx0 = l.cx + l.disc + 6 * u, gx1 = l.w - 44 * u;
        p.line(gx0, l.cy, gx1, l.cy, 2.6f * u, GOLD);
        String gc = "to the middle of the Milky Way";
        p.text(gc, gx1 - p.width(gc, 21 * u, false), l.cy - 12 * u, 21 * u, false, WORDS_DIM);
        String gcd = "26,000 light years";
        p.text(gcd, gx1 - p.width(gcd, 18 * u, false), l.cy + 28 * u, 18 * u, false, GOLD_DIM);
        // the fourteen: dim ones first, so the bright ones lie on top
        List<float[]> taken = new ArrayList<float[]>();
        taken.add(new float[]{gx1 - p.width(gc, 21 * u, false) - 6 * u, l.cy - 36 * u, l.w, l.cy + 34 * u});
        for (int pass = 0; pass < 2; pass++) {
            for (Mark m : marks) {
                boolean bright = m.inPhoto || m.up;
                if (bright != (pass == 1)) continue;
                line(p, l, m, d);
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (Mark m : marks) {
                boolean bright = m.inPhoto || m.up;
                if (bright != (pass == 0)) continue;
                if (label(p, l, m, taken)) d.labels++;
            }
        }
        // the photo's disc: a gold frame, and what it is
        p.ring(l.cx, l.cy, l.disc + 3 * u, 4 * u, GOLD);
        p.ring(l.cx, l.cy, l.disc + 10 * u, 1.6f * u, GOLD_DIM);
        String here = "YOU ARE HERE";
        p.text(here, l.cx - p.width(here, 17 * u, true) / 2, l.cy + l.disc * 0.78f, 17 * u, true, WORDS);
        hydrogen(p, l);
        // how to read it
        float lx = 64 * u, ly = l.h - 172 * u, size = 21 * u, room = l.w * 0.6f;
        String[] how = {
                "Each line points from the Sun to a pulsar, a dead star that ticks like a clock; the longer the line, the farther away. "
                        + "The marks along it are its tick in binary (| is 1, – is 0), counted in flips of a hydrogen atom.",
                "Gold marks are as the spacecraft carry them, measured in 1971. Blue ones have changed: pulsars slow down, "
                        + "so the map tells the date as well as the place. Bright lines: pulsars in your photo, or up in your sky.",
        };
        for (String para : how) {
            for (String t : XRayVision.wrap(p, para, size, room)) {
                p.text(t, lx, ly, size, false, WORDS_DIM);
                ly += size * 1.3f;
            }
            ly += size * 0.35f;
        }
        return d;
    }

    /** One pulsar's line: plain from the disc, then its tick's digits, then plain to its end, a tick there for its height off the disc. */
    private static void line(Painter p, Layout l, Mark m, Drawn d) {
        float u = l.unit;
        double[] e = Layout.way(m.p);
        float ex = (float) e[0], ey = (float) e[1], nx = -ey, ny = ex;
        boolean bright = m.inPhoto || m.up;
        int gold = bright ? GOLD_LIGHT : 0xFFC9A04A, fresh = bright ? 0xFFB8F4FF : 0xFF5FC4DD;
        float stroke = (bright ? 2.4f : 1.7f) * u;
        float r0 = l.disc + 12 * u, r1 = l.disc + l.length(m.p), step = 8.2f * u, rd = l.disc + 22 * u;
        String now = m.now.isEmpty() ? m.p.plaque : m.now;
        float rEnd = rd + now.length() * step;
        if (bright) p.line(l.cx + ex * r0, l.cy + ey * r0, l.cx + ex * r1, l.cy + ey * r1, 9 * u, m.inPhoto ? 0x447FE8FF : 0x33F2C14E);
        p.line(l.cx + ex * r0, l.cy + ey * r0, l.cx + ex * (rd - step * 0.6f), l.cy + ey * (rd - step * 0.6f), stroke, gold);
        // the digits, from the most significant out: | for 1, a dash for 0; changed ones in blue
        int n = now.length(), pl = m.p.plaque.length();
        for (int i = 0; i < n; i++) {
            char c = now.charAt(i);
            int back = n - 1 - i, k = pl - 1 - back;
            boolean same = k >= 0 && m.p.plaque.charAt(k) == c;
            int col = same ? gold : fresh;
            if (!same) d.newDigits++;
            float r = rd + i * step, x = l.cx + ex * r, y = l.cy + ey * r;
            if (c == '1') {
                float t = 6.5f * u;
                p.line(x - nx * t, y - ny * t, x + nx * t, y + ny * t, stroke * 1.05f, col);
            } else {
                float t = 2.9f * u;
                p.line(x - ex * t, y - ey * t, x + ex * t, y + ey * t, stroke * 1.3f, col);
            }
        }
        float rs = Math.min(r1, rEnd + step * 0.4f);
        if (r1 > rs) p.line(l.cx + ex * rs, l.cy + ey * rs, l.cx + ex * r1, l.cy + ey * r1, stroke, gold);
        // its height above or below the galaxy's disc: a tick at the end, this side for north
        float z = (float) (Math.sin(Math.toRadians(m.p.bDeg)) * 46 * u), side = z >= 0 ? 1 : -1;
        float tl = 7 * u + Math.abs(z);
        float x1 = l.cx + ex * r1, y1 = l.cy + ey * r1;
        p.line(x1, y1, x1 + nx * tl * side, y1 + ny * tl * side, stroke, gold);
        if (m.inPhoto) {
            p.disc(x1, y1, 7 * u, 0x887FE8FF);
            p.disc(x1, y1, 3.6f * u, 0xFFE9FCFF);
        }
        d.lines++;
    }

    /** Its name past the end of its line (and "in your photo" or "up in your sky" under it), where it fits. */
    private static boolean label(Painter p, Layout l, Mark m, List<float[]> taken) {
        float u = l.unit;
        double[] e = Layout.way(m.p);
        float ex = (float) e[0], ey = (float) e[1];
        boolean bright = m.inPhoto || m.up;
        String name = m.p.shortName(), note = m.inPhoto ? "in your photo" : m.up ? "up in your sky" : "";
        if (!m.p.nick.isEmpty()) note = m.p.nick.replace("the ", "") + (note.isEmpty() ? "" : " · " + note);
        float big = (bright ? 22 : 19) * u, small = 16 * u;
        float bw = Math.max(p.width(name, big, true), note.isEmpty() ? 0 : p.width(note, small, false));
        float bh = big + (note.isEmpty() ? 0 : small * 1.25f);
        float r1 = l.disc + l.length(m.p);
        // past the end, a little farther out each time, then nudged to either side (two lines a few degrees apart)
        float[] nudge = {0, 1, -1, 2, -2};
        for (int attempt = 0; attempt < 6 * nudge.length; attempt++) {
            float r = r1 + (16 + (attempt % 6) * 13) * u, side = nudge[attempt / 6] * bh * 0.75f;
            float ax = l.cx + ex * r - ey * side, ay = l.cy + ey * r + ex * side;
            // beside the end, on the side the line points to
            float x0 = ex >= 0.2f ? ax : ex <= -0.2f ? ax - bw : ax - bw / 2;
            float y0 = ey >= 0.35f ? ay : ey <= -0.35f ? ay - bh : ay - bh / 2;
            float[] box = {x0 - 3 * u, y0 - 3 * u, x0 + bw + 3 * u, y0 + bh + 3 * u};
            if (box[0] < 6 * u || box[2] > l.w - 6 * u || box[1] < 170 * u || box[3] > l.h - 180 * u) continue;
            boolean free = true;
            for (float[] o : taken) {
                if (box[0] < o[2] && box[2] > o[0] && box[1] < o[3] && box[3] > o[1]) {
                    free = false;
                    break;
                }
            }
            if (!free) continue;
            p.text(name, x0, y0 + big * 0.85f, big, true, bright ? WORDS : WORDS_DIM);
            if (!note.isEmpty()) p.text(note, x0, y0 + big + small * 1.0f, small, false, m.inPhoto ? 0xFFB8F4FF : m.up ? GOLD_LIGHT : GOLD_DIM);
            taken.add(box);
            return true;
        }
        return false;
    }

    /** The key at the bottom right: a hydrogen atom flipping, which sets the map's 1, as the plaque draws it. */
    private static void hydrogen(Painter p, Layout l) {
        float u = l.unit;
        float y = l.h - 112 * u, xa = l.w - 330 * u, xb = l.w - 160 * u, r = 22 * u;
        for (int k = 0; k < 2; k++) {
            float x = k == 0 ? xa : xb;
            p.ring(x, y, r, 2 * u, GOLD);
            p.disc(x, y, 4.5f * u, GOLD_LIGHT);
            p.disc(x + r, y, 4 * u, GOLD_LIGHT);
            // the spins: both the same way, then the electron's flipped
            p.line(x - 9 * u, y - r - 14 * u, x - 9 * u, y - r - 2 * u, 2 * u, GOLD);
            float ys = k == 0 ? y - r - 14 * u : y - r - 2 * u, ye = k == 0 ? y - r - 2 * u : y - r - 14 * u;
            p.line(x + r, ys, x + r, ye, 2 * u, GOLD);
        }
        p.line(xa + r + 10 * u, y, xb - r - 10 * u, y, 2 * u, GOLD);
        float mx = (xa + xb) / 2;
        p.line(mx, y + 12 * u, mx, y + 30 * u, 2.6f * u, GOLD_LIGHT);
        String one = "| = 1 = one flip of a hydrogen atom";
        String two = "0.704 billionths of a second";
        float size = 17 * u;
        p.text(one, l.w - 44 * u - p.width(one, size, false), y + 58 * u, size, false, WORDS_DIM);
        p.text(two, l.w - 44 * u - p.width(two, size, false), y + 80 * u, size, false, GOLD_DIM);
    }
}
