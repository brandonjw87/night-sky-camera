package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The Moonshot's finished photo (3.0): the stacked Moon's tones (a soft shoulder so the brightest craters keep their
 * detail), its mineral colours if asked (the seas' titanium blue and iron orange, there all along, too faint to see
 * until the stack's clean colour is stretched), the Moon enlarged to fill a square photo, and every landing site
 * labelled where it really is on it tonight: a dot on the spot, a line out to its name beside the Moon, NASA-map
 * style, bright if the Sun is up there, hollow if it is night.
 * <p>
 * Pure Java: the app draws with Android's Canvas, the tests with AWT ({@link MissionReplay.Draw}).
 */
public final class MoonLook {
    private MoonLook() {}

    /** The finished photo's side (px), and the Moon's diameter in it. */
    public static final int SIDE = 1440, MOON = 900;
    /** Which sites get labels; or the Moon map (3.1): the seas, craters and mountains named on the Moon itself. */
    public static final int LABELS_NONE = 0, LABELS_FAMOUS = 1, LABELS_ALL = 2, LABELS_MAP = 3;
    /** The famous ones (besides all six Apollo landings): the firsts, the rovers, the newest. */
    static final String[] FAMOUS = {"luna2", "luna9", "luna17", "luna21", "surveyor3", "change3", "change5", "chandrayaan3", "slim", "im1",
            "blueghost1"};

    // ------------------------------------------------------------------------------------------------
    // tones

    /** Linear light to the display: straight up to 0.8, then a shoulder that reaches 1 gently. */
    static double shoulder(double x) {
        if (x <= 0.8) return Math.max(0, x);
        return 0.8 + 0.2 * (1 - Math.exp(-(x - 0.8) / 0.2));
    }

    /**
     * The Earth's glow's tones (3.1): the shoulder, plus a lift for the night side, the glow's own brightness
     * ({@code level}) shown at about a third of the way up (sRGB) and the lift fading out up the sunlit tones, so the
     * crescent keeps its look and its shadows, lit by the Earth too, come out of the black with the rest of the night.
     * The lift starts past {@code black} (the sky's noise, three times; a soft knee), so the sky stays black.
     */
    static double glowTone(double y, double level, double black) {
        double yy = Math.max(0, y);
        if (black > 0) yy = yy < 2 * black ? yy * yy / (4 * black) : yy - black;
        double lift = GLOW_LIFT * (1 - Math.exp(-yy * Math.log(2) / level));
        return shoulder(y) + lift * Math.max(0, 1 - y / GLOW_FADE);
    }

    /** A value held between the nearest four pixels' least and most (at x, y; clamped at the edges). */
    static float clampNear(float[] img, int w, int h, double x, double y, float v) {
        int ix = Math.max(0, Math.min(w - 2, (int) Math.floor(x))), iy = Math.max(0, Math.min(h - 2, (int) Math.floor(y)));
        int k = iy * w + ix;
        float a = img[k], b = img[k + 1], c = img[k + w], d = img[k + w + 1];
        float lo = Math.min(Math.min(a, b), Math.min(c, d)), hi = Math.max(Math.max(a, b), Math.max(c, d));
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** The glow's lift at its most (linear light), and the sunlit brightness it is gone by. */
    static final double GLOW_LIFT = 0.14, GLOW_FADE = 0.3;

    static int srgb8(double v) {
        v = v <= 0 ? 0 : v >= 1 ? 1 : v;
        double e = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) Math.round(e * 255);
    }

    /**
     * The Moon's colours stretched {@code boost} times (1: as it is) about the highlands' own colour, which is made
     * grey: the colour is smoothed first (the stack's colour noise must not be stretched with it).
     */
    public static float[][] mineral(float[] r, float[] g, float[] b, int w, int h, double boost) {
        int n = r.length;
        float[] y = new float[n];
        for (int k = 0; k < n; k++) y[k] = 0.2126f * r[k] + 0.7152f * g[k] + 0.0722f * b[k];
        // the highlands' colour: the brightest fifth of the lit disk
        float[] sorted = y.clone();
        java.util.Arrays.sort(sorted);
        float lo = sorted[(int) (0.8 * (n - 1))], top = sorted[(int) (0.995 * (n - 1))];
        double sr = 0, sg = 0, sb = 0;
        for (int k = 0; k < n; k++) {
            if (y[k] < lo || y[k] > top || y[k] < 0.05f) continue;
            sr += r[k];
            sg += g[k];
            sb += b[k];
        }
        double nr = sg > 0 ? sr / sg : 1, nb = sg > 0 ? sb / sg : 1;
        // each pixel's colour as ratios to its brightness, after the neutral, smoothed
        float[] cr = new float[n], cb = new float[n];
        for (int k = 0; k < n; k++) {
            float yy = Math.max(1e-4f, y[k]);
            cr[k] = (float) (r[k] / nr / yy - 1);
            cb[k] = (float) (b[k] / nb / yy - 1);
        }
        cr = MoonStack.blur(cr, w, h, 1.6);
        cb = MoonStack.blur(cb, w, h, 1.6);
        float[][] out = new float[3][n];
        for (int k = 0; k < n; k++) {
            double yy = y[k];
            // where it is too dark to have a colour, none
            double s = boost * Math.min(1, Math.max(0, (yy - 0.02) / 0.08));
            double rr = 1 + s * cr[k], bb = 1 + s * cb[k];
            // keep the brightness: g follows from Y = 0.2126 r + 0.7152 g + 0.0722 b
            double gg = (1 - 0.2126 * rr - 0.0722 * bb) / 0.7152;
            out[0][k] = (float) (yy * Math.max(0, rr));
            out[1][k] = (float) (yy * Math.max(0, gg));
            out[2][k] = (float) (yy * Math.max(0, bb));
        }
        return out;
    }

    /** ARGB of linear light (1: the bright parts), through the shoulder and sRGB. */
    public static int[] argb(float[] r, float[] g, float[] b) {
        return argb(r, g, b, 0, 0);
    }

    /** ARGB, with the Earth's glow's tones if its {@code level} is given (0: the plain ones), black from {@code black}. */
    public static int[] argb(float[] r, float[] g, float[] b, double level, double black) {
        int n = r.length;
        int[] out = new int[n];
        boolean glow = level > 0;
        for (int k = 0; k < n; k++) {
            // the shoulder on the brightness, the colour kept
            double y = 0.2126 * r[k] + 0.7152 * g[k] + 0.0722 * b[k];
            double f = glow ? (y > 1e-9 ? glowTone(y, level, black) / y : 0) : y > 1e-6 ? shoulder(y) / y : 1;
            out[k] = 0xFF000000 | (srgb8(r[k] * f) << 16) | (srgb8(g[k] * f) << 8) | srgb8(b[k] * f);
        }
        return out;
    }

    /**
     * The live close-up of a RAW crop (the burst's own pixels, before stacking): each 2x2 colour block one pixel,
     * white-balanced, the Moon's bright parts ({@code level}, share of the range) near white. ARGB, (w/2) x (h/2).
     */
    public static int[] closeUp(short[] raw, int w, int h, int x0, int y0, SensorSpec spec, double level) {
        int ow = w / 2, oh = h / 2;
        int[] out = new int[ow * oh];
        double range = Math.max(1, spec.white - spec.black[0]);
        double gain = 0.85 / Math.max(0.02, level);
        double[] wb = new double[3];
        for (int c = 0; c < 3; c++) wb[c] = gain / range / Math.max(0.05, spec.camNeutral[c]);
        int ex = x0 & 1, ey = y0 & 1;
        for (int j = 0; j < oh; j++) {
            for (int i = 0; i < ow; i++) {
                double[] v = new double[3];
                int[] n = new int[3];
                for (int d = 0; d < 4; d++) {
                    int x = 2 * i + (d & 1), y = 2 * j + (d >> 1);
                    int p = (((y + ey) & 1) << 1) | ((x + ex) & 1);
                    int c = spec.cfa[p];
                    v[c] += ((raw[y * w + x] & 0xffff) - spec.black[p]) * wb[c];
                    n[c]++;
                }
                int r = srgb8(n[0] > 0 ? v[0] / n[0] : 0), g = srgb8(n[1] > 0 ? v[1] / n[1] : 0), b = srgb8(n[2] > 0 ? v[2] / n[2] : 0);
                out[j * ow + i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // the square photo

    /** The finished photo and where the Moon is in it. */
    public static final class Picture {
        public int[] argb;
        public int w, h;
        /** The Moon's centre and radius (px), and the photo's up as a position angle (degrees). */
        public double cx, cy, r, upPa;
        public List<Label> labels = new ArrayList<Label>();
    }

    /**
     * The stacked Moon ({@code w} x {@code h}, its centre and radius in those pixels) enlarged so it is {@link #MOON} px
     * across in a {@link #SIDE} px square of black sky (cubic resampling), its tones finished.
     */
    public static Picture enlarge(float[] r, float[] g, float[] b, int w, int h, double cx, double cy, double radius, double upPa) {
        return enlarge(r, g, b, w, h, cx, cy, radius, upPa, 0, 0);
    }

    /** Enlarged, with the Earth's glow's tones if its {@code level} is given (3.1), black from {@code black}. */
    public static Picture enlarge(float[] r, float[] g, float[] b, int w, int h, double cx, double cy, double radius, double upPa,
                                  double level, double black) {
        Picture p = new Picture();
        p.w = p.h = SIDE;
        p.r = MOON / 2.0;
        p.cx = SIDE / 2.0;
        p.cy = SIDE / 2.0 - 10;
        p.upPa = upPa;
        double k = radius / p.r;
        float[][] big = new float[3][SIDE * SIDE];
        float[][] src = {r, g, b};
        for (int j = 0; j < SIDE; j++) {
            for (int i = 0; i < SIDE; i++) {
                double x = cx + (i - p.cx) * k, y = cy + (j - p.cy) * k;
                if (x < -2 || y < -2 || x > w + 1 || y > h + 1) continue;
                for (int c = 0; c < 3; c++) {
                    float v = MoonStack.cubic(src[c], w, h, x, y);
                    // with the glow's lift the faint tones show: the cubic's ring round the bright edge (its dips
                    // below the nearest four pixels, black specks once cut at 0) held to them
                    if (level > 0) v = clampNear(src[c], w, h, x, y, v);
                    big[c][j * SIDE + i] = Math.max(0, v);
                }
            }
        }
        p.argb = argb(big[0], big[1], big[2], level, black);
        return p;
    }

    // ------------------------------------------------------------------------------------------------
    // the labels

    /** A landing site on the photo and where its name goes. */
    public static final class Label {
        public final MoonSites.Site site;
        /** The spot on the photo (px), and whether it faces us tonight and has the Sun up. */
        public final double x, y;
        public final boolean facing, sunUp;
        /** The Sun's height there (degrees). */
        public final double sunAlt;
        /** The name's column (left or right of the Moon) and its baseline (px). */
        public boolean left;
        public double ty;
        public String text;

        Label(MoonSites.Site s, double x, double y, boolean facing, double sunAlt) {
            this.site = s;
            this.x = x;
            this.y = y;
            this.facing = facing;
            this.sunAlt = sunAlt;
            this.sunUp = sunAlt > 0;
        }
    }

    /** Whether a site is labelled at this level. */
    static boolean chosen(MoonSites.Site s, int level) {
        if (level == LABELS_ALL) return true;
        if (level == LABELS_NONE) return false;
        if (s.apollo()) return true;
        for (String k : FAMOUS) if (k.equals(s.key)) return true;
        return false;
    }

    /**
     * Where each chosen site is on the photo, and where its name goes: in a column beside the Moon on the side the site
     * is on, kept from crowding (each pushed down or up from its neighbours), nearest its own height.
     */
    public static List<Label> layout(Picture pic, MoonGeo.View v, int level, Painter p, float size) {
        List<Label> all = new ArrayList<Label>();
        for (MoonSites.Site s : MoonSites.ALL) {
            if (!chosen(s, level)) continue;
            double[] q = v.inPicture(s.lat, s.lon, pic.cx, pic.cy, pic.r, pic.upPa);
            if (q[2] <= 0.02) continue; // round the back tonight
            Label l = new Label(s, q[0], q[1], true, v.sunAltAt(s.lat, s.lon));
            l.left = q[0] < pic.cx;
            l.text = s.label + " \u00b7 " + s.year;
            all.add(l);
        }
        List<Label> left = new ArrayList<Label>(), right = new ArrayList<Label>();
        for (Label l : all) (l.left ? left : right).add(l);
        // keep the columns about even: the middle ones may cross over
        balance(left, right, pic);
        spread(left, pic, size);
        spread(right, pic, size);
        return all;
    }

    private static void balance(List<Label> left, List<Label> right, Picture pic) {
        Comparator<Label> byMiddle = new Comparator<Label>() {
            @Override
            public int compare(Label a, Label b) {
                return Double.compare(Math.abs(a.x - pic.cx), Math.abs(b.x - pic.cx));
            }
        };
        while (left.size() > right.size() + 3) {
            Collections.sort(left, byMiddle);
            Label l = left.remove(0);
            l.left = false;
            right.add(l);
        }
        while (right.size() > left.size() + 3) {
            Collections.sort(right, byMiddle);
            Label l = right.remove(0);
            l.left = true;
            left.add(l);
        }
    }

    /** Places a column's names at their sites' heights, then pushes them apart to a line each, within the photo. */
    private static void spread(List<Label> col, Picture pic, float size) {
        if (col.isEmpty()) return;
        Collections.sort(col, new Comparator<Label>() {
            @Override
            public int compare(Label a, Label b) {
                return Double.compare(a.y, b.y);
            }
        });
        double line = size * 1.35, top = size * 3.2, bottom = pic.h - size * 3.2;
        int n = col.size();
        double[] y = new double[n];
        for (int i = 0; i < n; i++) y[i] = col.get(i).y + size * 0.35;
        // a few rounds: down from the top, up from the bottom, so the crowd centres on its sites
        for (int round = 0; round < 40; round++) {
            boolean moved = false;
            for (int i = 1; i < n; i++) {
                if (y[i] - y[i - 1] < line) {
                    double mid = (y[i] + y[i - 1]) / 2;
                    y[i - 1] = mid - line / 2;
                    y[i] = mid + line / 2;
                    moved = true;
                }
            }
            if (y[0] < top) {
                y[0] = top;
                moved = true;
            }
            for (int i = 1; i < n; i++) if (y[i] < y[i - 1] + line) y[i] = y[i - 1] + line;
            if (y[n - 1] > bottom) {
                y[n - 1] = bottom;
                for (int i = n - 2; i >= 0; i--) if (y[i] > y[i + 1] - line) y[i] = y[i + 1] - line;
            }
            if (!moved) break;
        }
        for (int i = 0; i < n; i++) col.get(i).ty = y[i];
    }

    /** The colours: crewed landings gold, robots white, the impacts and crashes grey. */
    static int colour(Label l) {
        if (l.site.kind == MoonSites.Kind.CREW) return 0xFFFFD54F;
        if (l.site.kind == MoonSites.Kind.LANDER) return 0xFFFFFFFF;
        return 0xFFB0BEC5;
    }

    /** The labels' text size: {@code size}, or less if the longest name wouldn't fit beside the Moon. */
    public static float fit(Painter p, Picture pic, List<Label> labels, float size) {
        double room = pic.w / 2.0 - pic.r - size * 0.8 - size * 0.6;
        float s = size;
        for (Label l : labels) {
            float tw = p.width(l.text, size, l.site.apollo());
            if (tw > room) s = (float) Math.min(s, size * room / tw);
        }
        return s;
    }

    /** Draws the labels: each site's dot, a line out to the side, its name in the column. */
    public static void paint(Painter p, Picture pic, List<Label> labels, float size) {
        float dot = Math.max(3f, size * 0.22f), stroke = Math.max(1.5f, size * 0.07f);
        for (Label l : labels) {
            int c = colour(l);
            int dim = (c & 0x00FFFFFF) | 0xB0000000;
            float tw = p.width(l.text, size, l.site.apollo());
            double gap = size * 0.8;
            double colX = l.left ? pic.cx - pic.r - gap : pic.cx + pic.r + gap;
            float tx = (float) (l.left ? colX - tw : colX);
            float ly = (float) (l.ty - size * 0.35);
            // the leader: straight from the dot to its name
            double ex = l.left ? colX + size * 0.3 : colX - size * 0.3;
            p.line((float) l.x, (float) l.y, (float) ex, ly, stroke, dim);
            if (l.sunUp) p.disc((float) l.x, (float) l.y, dot, c);
            else p.ring((float) l.x, (float) l.y, dot, stroke, c);
            p.text(l.text, tx, (float) l.ty, size, l.site.apollo(), c);
        }
    }

    /** A landing site's story for the card under the photo, with the Sun's height there tonight. */
    public static String siteStory(MoonSites.Site s, double sunAlt) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.name).append(" \u00b7 ").append(s.date).append(" \u00b7 ").append(s.who).append("\n").append(s.place).append("\n\n");
        if (!s.crew.isEmpty()) sb.append(s.crew).append(".\n\n");
        sb.append(s.story);
        sb.append(String.format(Locale.US, "\n\nTonight the Sun is %s there%s.", sunAlt > 0 ? String.format(Locale.US, "%.0f\u00b0 up", sunAlt)
                : "down: it's night", sunAlt > 0 && sunAlt < 12 ? ", low enough for long shadows" : ""));
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------
    // the Moon map (3.1): the seas, craters, mountains and Apollo landings named on the Moon itself

    /** The map's colours: the seas blue, the craters white, the mountains and valleys sand, the Apollo landings gold. */
    static final int SEA_BLUE = 0xFF8FD3FF, CRATER_WHITE = 0xFFFFFFFF, CRATER_DIM = 0xFFE3E8EB, MOUNTAIN_SAND = 0xFFFFCC80, APOLLO_GOLD = 0xFFFFD54F;
    /** The Sun's height (degrees) under which a place's shadows are long: it is at its best tonight. */
    public static final double LOW_SUN = 12;

    /** A name on the Moon map: a sea, a crater, mountains, a valley, or an Apollo landing. */
    public static final class MapLabel {
        /** The feature, or null for an Apollo landing ({@link #site}). */
        public final MoonFeatures.Feature feature;
        public final MoonSites.Site site;
        /** The place on the photo (px), how squarely it faces us (1: the disk's middle), the Sun's height there. */
        public final double x, y, z, sunAlt;
        /** A crater's rim on the photo, a closed line {x0, y0, x1, y1, ...}; null for the rest. */
        public double[] rim;
        /** The name's lines, size, weight and colour; each line's left end, the first line's baseline. */
        public String[] lines;
        public float size;
        public boolean bold;
        public int colour;
        public double[] lineX;
        public double ty;
        /** The name's box on the photo. */
        public double bx0, by0, bx1, by1;
        /** The Sun is low there tonight (shadows long): seen at its best. */
        public boolean best;

        MapLabel(MoonFeatures.Feature f, MoonSites.Site s, double x, double y, double z, double sunAlt) {
            this.feature = f;
            this.site = s;
            this.x = x;
            this.y = y;
            this.z = z;
            this.sunAlt = sunAlt;
        }

        public String name() {
            return feature != null ? feature.name : site.label;
        }

        int rank() {
            return feature != null ? feature.rank : 1;
        }
    }

    /** The order names are placed in: the big seas, the Apollo landings, the famous places, then the rest by rank. */
    static int order(MapLabel l) {
        boolean sea = l.feature != null && l.feature.kind == MoonFeatures.Kind.SEA;
        if (l.site != null) return 1;
        if (sea) return l.rank() == 1 ? 0 : l.rank() == 2 ? 3 : 5;
        return l.rank() == 1 ? 2 : l.rank() == 2 ? 4 : 6;
    }

    /**
     * The Moon map's names on the photo: each sea, crater, mountain range and valley that faces us and has the Sun up
     * (a crater needs the Sun up to show at all), and the six Apollo landings, each name placed where it doesn't land
     * on another (the most famous first; the rest only where there's room). {@code size} as for the landings' labels.
     */
    public static List<MapLabel> mapLayout(Picture pic, MoonGeo.View v, Painter p, float size) {
        List<MapLabel> all = new ArrayList<MapLabel>();
        for (MoonFeatures.Feature f : MoonFeatures.ALL) {
            double[] q = v.inPicture(f.lat, f.lon, pic.cx, pic.cy, pic.r, pic.upPa);
            double sun = v.sunAltAt(f.lat, f.lon);
            boolean sea = f.kind == MoonFeatures.Kind.SEA;
            // a sea shows if it is lit and not squashed into the edge; the rest need the Sun up and to face us more
            if (sea ? q[2] < 0.08 || sun < -2 : q[2] < 0.15 || sun <= 0.5) continue;
            MapLabel l = new MapLabel(f, null, q[0], q[1], q[2], sun);
            l.best = !sea && sun < LOW_SUN && q[2] > 0.3;
            if (f.kind == MoonFeatures.Kind.CRATER) l.rim = rim(f, v, pic);
            all.add(l);
        }
        for (MoonSites.Site s : MoonSites.ALL) {
            if (!s.apollo()) continue;
            double[] q = v.inPicture(s.lat, s.lon, pic.cx, pic.cy, pic.r, pic.upPa);
            if (q[2] <= 0.02) continue;
            all.add(new MapLabel(null, s, q[0], q[1], q[2], v.sunAltAt(s.lat, s.lon)));
        }
        // the order: by group, then the biggest first
        Collections.sort(all, new Comparator<MapLabel>() {
            @Override
            public int compare(MapLabel a, MapLabel b) {
                int c = Integer.compare(order(a), order(b));
                if (c != 0) return c;
                double ka = a.feature != null ? a.feature.km : 0, kb = b.feature != null ? b.feature.km : 0;
                return Double.compare(kb, ka);
            }
        });
        for (MapLabel l : all) style(l, p, size, pic);
        // kept clear from the start: the landings' dots and the famous craters' rings (no sea's name drawn over them)
        List<MapLabel> reserved = new ArrayList<MapLabel>();
        for (MapLabel l : all) if (l.site != null || (l.rim != null && l.feature.rank == 1)) reserved.add(l);
        List<MapLabel> placed = new ArrayList<MapLabel>();
        double top = size * 2.7, bottom = pic.h - size * 2.7, left = size * 0.4, right = pic.w - size * 0.4;
        for (MapLabel l : all) {
            // a smaller crater's ring mustn't run under a name already there
            if (l.rim != null && !reserved.contains(l) && ringUnder(l, placed)) continue;
            if (place(l, placed, reserved, p, top, bottom, left, right)) placed.add(l);
        }
        // a reserved place whose name found no room: its ring or dot stays off the map
        return placed;
    }

    /** Whether a crater's ring runs under a placed name's box. */
    private static boolean ringUnder(MapLabel l, List<MapLabel> placed) {
        for (MapLabel o : placed) {
            for (int k = 0; k < l.rim.length; k += 2) {
                if (l.rim[k] > o.bx0 - 2 && l.rim[k] < o.bx1 + 2 && l.rim[k + 1] > o.by0 - 2 && l.rim[k + 1] < o.by1 + 2) return true;
            }
        }
        return false;
    }

    /** The name's size, weight, colour and lines (a long sea name on two lines). */
    private static void style(MapLabel l, Painter p, float size, Picture pic) {
        String name = l.name();
        if (l.site != null) {
            l.size = size * 0.6f;
            l.bold = true;
            l.colour = APOLLO_GOLD;
        } else if (l.feature.kind == MoonFeatures.Kind.SEA) {
            l.size = size * (l.feature.rank == 1 ? 0.74f : 0.6f);
            l.colour = SEA_BLUE;
        } else if (l.feature.kind == MoonFeatures.Kind.CRATER) {
            l.size = size * (l.feature.rank == 1 ? 0.6f : 0.54f);
            l.colour = l.feature.rank == 1 ? CRATER_WHITE : CRATER_DIM;
        } else {
            l.size = size * 0.56f;
            l.colour = MOUNTAIN_SAND;
        }
        float w = p.width(name, l.size, l.bold);
        double across = l.feature != null ? l.feature.km / MoonGeo.RADIUS_KM * pic.r : 0;
        String[] words = name.split(" ");
        if (l.feature != null && l.feature.kind == MoonFeatures.Kind.SEA && words.length >= 3 && w > Math.max(1.2 * across, 4 * size)) {
            // two lines, split where they come out most even
            int best = 1;
            double bestD = 1e9;
            for (int k = 1; k < words.length; k++) {
                double d = Math.abs(p.width(join(words, 0, k), l.size, l.bold) - p.width(join(words, k, words.length), l.size, l.bold));
                if (d < bestD) {
                    bestD = d;
                    best = k;
                }
            }
            l.lines = new String[]{join(words, 0, best), join(words, best, words.length)};
        } else {
            l.lines = new String[]{name};
        }
    }

    private static String join(String[] w, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int k = from; k < to; k++) sb.append(k > from ? " " : "").append(w[k]);
        return sb.toString();
    }

    /** A crater's rim on the photo, a little outside the real one so the ring doesn't hide it: 32 points, or null round the edge. */
    static double[] rim(MoonFeatures.Feature f, MoonGeo.View v, Picture pic) {
        int n = 32;
        double[] out = new double[2 * n];
        // at least a few pixels round, for the small bright ones
        double km = Math.max(f.km / 2 * 1.15, 6.0 / pic.r * MoonGeo.RADIUS_KM);
        for (int k = 0; k < n; k++) {
            double[] ll = MoonFeatures.along(f.lat, f.lon, 360.0 * k / n, km);
            double[] q = v.inPicture(ll[0], ll[1], pic.cx, pic.cy, pic.r, pic.upPa);
            if (q[2] < 0) return null;
            out[2 * k] = q[0];
            out[2 * k + 1] = q[1];
        }
        return out;
    }

    /**
     * Places a name: a sea's or a mountain range's on the place itself (or a little above or below it), a crater's,
     * valley's or landing's beside it (right, left, above, below, then the corners), where it overlaps no name already
     * placed nor a placed crater's ring, within the photo between its title and caption. False if there's no room.
     */
    private static boolean place(MapLabel l, List<MapLabel> placed, List<MapLabel> reserved, Painter p, double top, double bottom, double left,
                                 double right) {
        int n = l.lines.length;
        double lh = l.size * 1.12;
        double[] w = new double[n];
        double wMax = 0;
        for (int k = 0; k < n; k++) {
            w[k] = p.width(l.lines[k], l.size, l.bold);
            wMax = Math.max(wMax, w[k]);
        }
        double h = l.size * 0.95 + (n - 1) * lh;
        boolean centred = l.feature != null && (l.feature.kind == MoonFeatures.Kind.SEA || l.feature.kind == MoonFeatures.Kind.MOUNTAINS);
        // what the name sits beside: the crater's ring, or a small spot
        double ax0 = l.x, ay0 = l.y, ax1 = l.x, ay1 = l.y;
        if (l.rim != null) {
            for (int k = 0; k < l.rim.length; k += 2) {
                ax0 = Math.min(ax0, l.rim[k]);
                ax1 = Math.max(ax1, l.rim[k]);
                ay0 = Math.min(ay0, l.rim[k + 1]);
                ay1 = Math.max(ay1, l.rim[k + 1]);
            }
        } else {
            double s = l.size * (l.site != null ? 0.3 : 0.25);
            ax0 = l.x - s;
            ax1 = l.x + s;
            ay0 = l.y - s;
            ay1 = l.y + s;
        }
        double gap = l.size * 0.25;
        List<double[]> tries = new ArrayList<double[]>();
        if (centred) {
            for (double dy : new double[]{0, -0.9, 0.9, -1.8, 1.8}) tries.add(new double[]{l.x - wMax / 2, l.y - h / 2 + dy * lh});
        } else {
            double my = (ay0 + ay1) / 2;
            tries.add(new double[]{ax1 + gap, my - h / 2}); // right
            tries.add(new double[]{ax0 - gap - wMax, my - h / 2}); // left
            tries.add(new double[]{l.x - wMax / 2, ay0 - gap - h}); // above
            tries.add(new double[]{l.x - wMax / 2, ay1 + gap}); // below
            tries.add(new double[]{ax1, ay0 - h}); // the corners
            tries.add(new double[]{ax0 - wMax, ay0 - h});
            tries.add(new double[]{ax1, ay1});
            tries.add(new double[]{ax0 - wMax, ay1});
        }
        for (double[] t : tries) {
            double x0 = t[0], y0 = t[1], x1 = x0 + wMax, y1 = y0 + h;
            if (x0 < left || x1 > right || y0 < top || y1 > bottom) continue;
            if (clashes(x0, y0, x1, y1, placed, l) || clashes(x0, y0, x1, y1, reserved, l)) continue;
            l.bx0 = x0;
            l.by0 = y0;
            l.bx1 = x1;
            l.by1 = y1;
            l.lineX = new double[n];
            for (int k = 0; k < n; k++) l.lineX[k] = x0 + (wMax - w[k]) / 2;
            l.ty = y0 + l.size * 0.8;
            return true;
        }
        return false;
    }

    /** Whether a box overlaps a placed name, a placed (or reserved) crater's ring or landing's dot. */
    private static boolean clashes(double x0, double y0, double x1, double y1, List<MapLabel> placed, MapLabel self) {
        double pad = 3;
        for (MapLabel o : placed) {
            if (o == self) continue;
            if (o.bx1 > o.bx0 && x0 < o.bx1 + pad && x1 > o.bx0 - pad && y0 < o.by1 + pad && y1 > o.by0 - pad) return true;
            if (o.rim != null) {
                // the ring's points: none inside the box
                for (int k = 0; k < o.rim.length; k += 2) {
                    if (o.rim[k] > x0 - 1 && o.rim[k] < x1 + 1 && o.rim[k + 1] > y0 - 1 && o.rim[k + 1] < y1 + 1) return true;
                }
            } else if (o.site != null) {
                double r = o.size * 0.35;
                if (o.x + r > x0 && o.x - r < x1 && o.y + r > y0 && o.y - r < y1) return true;
            }
        }
        // its own crater's ring stays clear of its name too
        if (self.rim != null) {
            for (int k = 0; k < self.rim.length; k += 2) {
                if (self.rim[k] > x0 && self.rim[k] < x1 && self.rim[k + 1] > y0 && self.rim[k + 1] < y1) return true;
            }
        }
        return false;
    }

    /** Draws the map: each crater's dashed ring, each landing's dot, then every name. */
    public static void paintMap(Painter p, Picture pic, List<MapLabel> labels, float size) {
        float stroke = Math.max(1.2f, size * 0.05f);
        for (MapLabel l : labels) {
            if (l.rim != null) {
                int n = l.rim.length / 2;
                int c = (l.best ? 0xD0000000 : 0x90000000) | (l.colour & 0x00FFFFFF);
                for (int k = 0; k < n; k += 2) {
                    int j = (k + 1) % n;
                    p.line((float) l.rim[2 * k], (float) l.rim[2 * k + 1], (float) l.rim[2 * j], (float) l.rim[2 * j + 1], stroke, c);
                }
            }
            if (l.site != null) {
                float dot = Math.max(3f, size * 0.15f);
                if (l.sunAlt > 0) p.disc((float) l.x, (float) l.y, dot, l.colour);
                else p.ring((float) l.x, (float) l.y, dot, stroke, l.colour);
            }
        }
        for (MapLabel l : labels) {
            double lh = l.size * 1.12;
            for (int k = 0; k < l.lines.length; k++) {
                float x = (float) l.lineX[k], y = (float) (l.ty + k * lh);
                // a dark halo first: light names on the bright highlands still read
                float d = Math.max(1f, l.size / 16f);
                for (int h = 0; h < 4; h++) {
                    p.text(l.lines[k], x + (h == 0 ? -d : h == 1 ? d : 0), y + (h == 2 ? -d : h == 3 ? d : 0), l.size, l.bold, 0x70000000);
                }
                p.text(l.lines[k], x, y, l.size, l.bold, l.colour);
            }
        }
    }

    /**
     * The name a tap (px) means: one whose name was tapped, else the place nearest it within {@code reach} (inside a
     * crater's ring counts as on it; a sea only near its middle, so the craters in it stay easy to pick); null if none.
     */
    public static MapLabel mapAt(List<MapLabel> labels, double x, double y, double reach) {
        MapLabel best = null;
        double bd = Double.MAX_VALUE;
        for (MapLabel l : labels) {
            double d;
            if (x >= l.bx0 - 4 && x <= l.bx1 + 4 && y >= l.by0 - 4 && y <= l.by1 + 4) d = 0;
            else {
                d = Math.hypot(x - l.x, y - l.y);
                if (l.rim != null) {
                    // inside the ring: the smallest ring wins (a crater inside a bigger one)
                    double rr = ringRadius(l);
                    d = d <= rr ? 0.001 * rr : d - rr;
                }
                if (d > reach) continue;
                // the craters before the seas they sit in
                if (l.feature != null && l.feature.kind == MoonFeatures.Kind.SEA) d += 0.5;
            }
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        return best;
    }

    /** A crater's ring's smallest radius on the photo (it is squashed towards the edge). */
    static double ringRadius(MapLabel l) {
        double r = Double.MAX_VALUE;
        for (int k = 0; k < l.rim.length; k += 2) r = Math.min(r, Math.hypot(l.rim[k] - l.x, l.rim[k + 1] - l.y));
        return r;
    }

    /** "the Apennine Mountains", "the Alps", "Copernicus": a feature's name inside a sentence. */
    public static String named(MoonFeatures.Feature f) {
        if (f.kind == MoonFeatures.Kind.CRATER || f.name.startsWith("Mons ")) return f.name;
        if (f.name.startsWith("The ")) return "the " + f.name.substring(4);
        return "the " + f.name;
    }

    /** A map name's story for the card under the photo: what it is, its story, and how it looks tonight. */
    public static String mapStory(MapLabel l) {
        if (l.site != null) return siteStory(l.site, l.sunAlt);
        MoonFeatures.Feature f = l.feature;
        StringBuilder sb = new StringBuilder(f.what()).append("\n\n").append(f.story);
        if (f.kind != MoonFeatures.Kind.SEA) {
            if (l.best) sb.append(String.format(Locale.US, "\n\nTonight the Sun is only %.0f\u00b0 up there, so its shadows are long: it's at its best.",
                    Math.max(1, l.sunAlt)));
            else sb.append(String.format(Locale.US, "\n\nTonight the Sun is %.0f\u00b0 up there%s.", l.sunAlt,
                    l.sunAlt > 50 ? ": high, so there are hardly any shadows" : ""));
        }
        return sb.toString();
    }

    /** What to look for tonight: the places on the map along the shadow line, where the low Sun shows their shape best. */
    public static String bestTonight(List<MapLabel> labels, MoonGeo.View v) {
        List<MapLabel> best = new ArrayList<MapLabel>();
        for (MapLabel l : labels) if (l.best) best.add(l);
        Collections.sort(best, new Comparator<MapLabel>() {
            @Override
            public int compare(MapLabel a, MapLabel b) {
                int c = Integer.compare(a.feature.rank, b.feature.rank);
                return c != 0 ? c : Double.compare(b.feature.km, a.feature.km);
            }
        });
        if (best.isEmpty()) {
            if (v.illum > 0.9) return "Near full Moon the Sun is high over everything we see, so there are hardly any shadows: the best night "
                    + "for the bright rays around Tycho, Copernicus and Kepler.";
            return "";
        }
        StringBuilder sb = new StringBuilder("Best tonight, along the shadow line where the Sun is low and the shadows are long: ");
        int n = Math.min(4, best.size());
        for (int k = 0; k < n; k++) {
            sb.append(k == 0 ? "" : k == n - 1 ? " and " : ", ").append(named(best.get(k).feature));
        }
        return sb.append(".").toString();
    }

    /**
     * A crossing marked beside its silhouettes (3.0, the Transit Hunter): a gold arrow alongside the track, the way it
     * flew, and what it was, how long it took and how many times it was caught.
     */
    public static void track(Painter p, Picture pic, MoonStack.Result r, String name, float size) {
        if (r.crossing == null || r.crossingAt == null || r.crossingAt.length < 2) return;
        double k = pic.r / r.radius;
        double[] a = r.crossingAt[0], b = r.crossingAt[r.crossingAt.length - 1];
        double ax = pic.cx + (a[0] - r.cx) * k, ay = pic.cy + (a[1] - r.cy) * k, bx = pic.cx + (b[0] - r.cx) * k, by = pic.cy + (b[1] - r.cy) * k;
        double dx = bx - ax, dy = by - ay, len = Math.hypot(dx, dy);
        if (len < 1) return;
        double ux = dx / len, uy = dy / len, nx = -uy, ny = ux;
        // beside the track, on its side away from the Moon's middle
        if (nx * ((ax + bx) / 2 - pic.cx) + ny * ((ay + by) / 2 - pic.cy) < 0) {
            nx = -nx;
            ny = -ny;
        }
        double off = size * 1.1;
        float x0 = (float) (ax + nx * off - ux * size * 0.5), y0 = (float) (ay + ny * off - uy * size * 0.5);
        float x1 = (float) (bx + nx * off + ux * size * 0.5), y1 = (float) (by + ny * off + uy * size * 0.5);
        int gold = 0xFFFFD54F;
        float stroke = Math.max(2f, size * 0.09f);
        p.line(x0, y0, x1, y1, stroke, gold);
        double hs = size * 0.45;
        p.line(x1, y1, (float) (x1 - ux * hs + nx * hs * 0.6), (float) (y1 - uy * hs + ny * hs * 0.6), stroke, gold);
        p.line(x1, y1, (float) (x1 - ux * hs - nx * hs * 0.6), (float) (y1 - uy * hs - ny * hs * 0.6), stroke, gold);
        String s = String.format(Locale.US, "%s · %.2f s · caught %d times", name, r.crossing.lastS - r.crossing.firstS, r.crossing.frames.length);
        float fs = size * 0.8f, tw = p.width(s, fs, true);
        double tx = (ax + bx) / 2 + nx * (off + size * 1.3) - tw / 2, ty = (ay + by) / 2 + ny * (off + size * 1.3) + fs * 0.35;
        tx = Math.max(size * 0.5, Math.min(pic.w - tw - size * 0.5, tx));
        ty = Math.max(size * 3.2, Math.min(pic.h - size * 3, ty));
        // on a dark plate: gold on the bright Moon doesn't read by itself
        float pad = fs * 0.45f;
        p.gradient((float) tx - pad, (float) ty - fs * 1.05f, (float) (tx + tw) + pad, (float) ty + fs * 0.42f, 0xB0000000, 0xB0000000);
        p.text(s, (float) tx, (float) ty, fs, true, gold);
    }

    /** The photo's title and caption: across the top and the bottom. */
    public static void captions(Painter p, Picture pic, String title, String caption, float size) {
        p.gradient(0, 0, pic.w, size * 2.6f, 0xC0000000, 0x00000000);
        p.gradient(0, pic.h - size * 2.6f, pic.w, pic.h, 0x00000000, 0xC0000000);
        float tw = p.width(title, size * 1.15f, true);
        p.text(title, (pic.w - tw) / 2f, size * 1.6f, size * 1.15f, true, 0xFFFFFFFF);
        float cw = p.width(caption, size * 0.78f, false);
        float cs = size * 0.78f;
        if (cw > pic.w - 2 * size) {
            cs *= (pic.w - 2 * size) / cw;
            cw = p.width(caption, cs, false);
        }
        p.text(caption, (pic.w - cw) / 2f, pic.h - size * 1.0f, cs, false, 0xFFCFD8DC);
    }

    /** The caption under the photo: what it is, when, where from, how many frames. */
    public static String caption(MoonGeo.View v, MoonStack.Result r, String when, String where) {
        String phase = phaseName(v);
        return String.format(Locale.US, "%s, %.0f%% lit · %s%s · %d of %d frames stacked", phase, v.illum * 100, when,
                where.isEmpty() ? "" : " · " + where, r.used, r.total);
    }

    /** "Waxing crescent", "First quarter", "Full Moon"... */
    public static String phaseName(MoonGeo.View v) {
        double k = v.illum;
        if (k < 0.02) return "New Moon";
        if (k > 0.98) return "Full Moon";
        if (Math.abs(k - 0.5) < 0.04) return v.waxing ? "First quarter" : "Last quarter";
        if (k < 0.5) return v.waxing ? "Waxing crescent" : "Waning crescent";
        return v.waxing ? "Waxing gibbous" : "Waning gibbous";
    }

    /** The site nearest a tap on the photo (px), within {@code reach}; null if none. */
    public static Label at(List<Label> labels, double x, double y, double reach, Painter p, Picture pic, float size) {
        Label best = null;
        double bd = reach;
        for (Label l : labels) {
            double d = Math.hypot(x - l.x, y - l.y);
            // or its name
            float tw = p.width(l.text, size, l.site.apollo());
            double colX = l.left ? pic.cx - pic.r - size * 0.8 - tw : pic.cx + pic.r + size * 0.8;
            if (x >= colX - size * 0.3 && x <= colX + tw + size * 0.3 && y >= l.ty - size && y <= l.ty + size * 0.4) d = 0;
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        return best;
    }
}
