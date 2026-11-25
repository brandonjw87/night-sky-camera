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
    /** Which sites get labels. */
    public static final int LABELS_NONE = 0, LABELS_FAMOUS = 1, LABELS_ALL = 2;
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
        int n = r.length;
        int[] out = new int[n];
        for (int k = 0; k < n; k++) {
            // the shoulder on the brightness, the colour kept
            double y = 0.2126 * r[k] + 0.7152 * g[k] + 0.0722 * b[k];
            double f = y > 1e-6 ? shoulder(y) / y : 1;
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
                for (int c = 0; c < 3; c++) big[c][j * SIDE + i] = Math.max(0, MoonStack.cubic(src[c], w, h, x, y));
            }
        }
        p.argb = argb(big[0], big[1], big[2]);
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
