package com.brandon.nightsky.processing;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Galileo's notebook's pictures (3.2). The stacked photo of Jupiter and its four moons, wide (the moons lie along a
 * line), Jupiter's glare dimmed so a moon close to it shows, each moon named where it is, or where the sums put it
 * with why it isn't there (behind Jupiter, crossing it, in its shadow, lost in its glare). Tonight's line-up from the
 * sums, drawn the same way. And the notebook page: Galileo's seven nights of January 1610, yours, and the nights to
 * come, each a line as he wrote them: east (Ori., for Oriens) on the left, west (Occ., Occidens) on the right,
 * Jupiter an O and its moons little stars.
 * <p>
 * Pure Java: the app draws with Android's Canvas, the tests with AWT ({@link Painter}).
 */
public final class JupiterLook {
    private JupiterLook() {}

    /** The photo's size (px): twice as wide as it is tall. */
    public static final int W = 1440, H = 720;
    /** Each moon's colour for its name: Io's sulphur, Europa's ice, Ganymede's tan, Callisto's grey-brown. */
    public static final int[] COLOURS = {0xFFFFE082, 0xFFE1F5FE, 0xFFFFCC80, 0xFFD7CCC8};
    static final int GOLD = 0xFFFFD54F, GREY = 0xFFB0BEC5, DIM = 0xFF90A4AE;
    /** The most the photo enlarges the stack (picture px per result px): more is only bigger blur. */
    static final double KMAX = 6;

    /** A finished picture and where things are on it. */
    public static final class Picture {
        public int[] argb;
        public int w, h;
        /** Jupiter's middle (px), picture px per result px, Jupiter's equatorial radius on the picture (px). */
        public double cx, cy, k, rJ;
        /** The labels drawn on it, for taps. */
        public final List<Label> labels = new ArrayList<Label>();
    }

    /** A moon's (or a star's) name on the photo. */
    public static final class Label {
        /** Which moon (0..3), or -1 for a star. */
        public int moon = -1;
        /** The light (or where it would be) on the picture, and the words' left end and baseline (px). */
        public double x, y, tx, ty;
        public float size;
        public String text = "", note = "";
        /** Found on the photo (else drawn where the sums put it). */
        public boolean seen;
    }

    // ------------------------------------------------------------------------------------------------
    // the photo

    /**
     * The stack's photo, wide: Jupiter in the middle, every moon that's on it inside. Its light in two layers, each with
     * its own tones, laid one over the other: Jupiter's own glow (all of its light the search took off: smooth, a
     * thousand times brighter than a moon) with its brightness squeezed so it stays a ball; and everything else (the
     * moons, any star), lightly smoothed, from just over the sky's noise up, its brightest moon near white. A moon
     * close to Jupiter shows over its glow instead of drowning in it.
     */
    public static Picture photo(JupiterStack.Result r) {
        int Wr = r.w;
        double reach = 10;
        for (int k = 0; k < 4; k++) {
            JupiterStack.Light l = r.moon[k];
            double[] p = r.predicted[k];
            if (l != null) reach = Math.max(reach, Math.abs(l.x - r.cx) / r.rJ);
            else if (p != null && r.status[k] != JupiterStack.OUTSIDE) reach = Math.max(reach, Math.abs(p[0] - r.cx) / r.rJ);
        }
        double half = Math.min((reach * 1.12 + 3) * r.rJ, Wr / 2.0 - 3);
        Picture pic = new Picture();
        pic.w = W;
        pic.h = H;
        pic.cx = W / 2.0;
        pic.cy = H / 2.0;
        pic.k = Math.min(KMAX, (W / 2.0) / half);
        pic.rJ = r.rJ * pic.k;
        float[] Y = new float[Wr * Wr];
        for (int i = 0; i < Y.length; i++) Y[i] = 0.2126f * r.r[i] + 0.7152f * r.g[i] + 0.0722f * r.b[i];
        // the two layers: Jupiter's glow, and the rest (lightly smoothed; nothing inside Jupiter's glare, where it is
        // only what's left of taking the glow off)
        float[] G = new float[Wr * Wr], R = new float[Wr * Wr];
        double gMax = 0;
        for (int i = 0; i < G.length; i++) {
            double res = r.resid != null ? r.resid[i] : 0;
            G[i] = (float) Math.max(0, Y[i] - res - r.sky);
            gMax = Math.max(gMax, G[i]);
            R[i] = (float) res;
        }
        R = smooth(R, Wr, SMOOTH);
        double noise = skyNoise(R, Wr, r);
        double moonTop = 0;
        for (int k = 0; k < 4; k++) {
            JupiterStack.Light l = r.moon[k];
            if (l == null) continue;
            int ix = (int) Math.round(l.x), iy = (int) Math.round(l.y);
            if (ix >= 0 && iy >= 0 && ix < Wr && iy < Wr) moonTop = Math.max(moonTop, R[iy * Wr + ix]);
        }
        double black = BLACK_SIGMAS * noise, knee = 2 * noise;
        // near Jupiter what's left of its glow isn't all noise: the black raised as the search's own noise rises there
        double far = 0;
        double[] rn = r.ringNoise;
        if (rn != null) {
            List<Double> fs = new ArrayList<Double>();
            for (int i = 0; i < rn.length; i++) if (i * 3 > 8 * r.rJ && rn[i] > 0) fs.add(rn[i]);
            if (!fs.isEmpty()) {
                java.util.Collections.sort(fs);
                far = fs.get(fs.size() / 2);
            }
        }
        if (moonTop <= black + knee) moonTop = black + 12 * noise;
        double rTop = asinh((moonTop - black) / knee) / MOON_WHITE;
        double gWhite = GLOW_WHITE * Math.max(1e-9, gMax), gFloor = GLOW_FLOOR * gMax;
        // where the frames covered the stack well (its edges, which only some frames reached, are noisier)
        double coverMed = 0;
        float[] cover = null;
        if (r.cover != null) {
            double[] cs = r.cover.clone();
            java.util.Arrays.sort(cs);
            coverMed = cs[cs.length / 2];
            // smoothed: a hot pixel left out is one pixel's dip, not an edge
            cover = new float[cs.length];
            for (int i = 0; i < cover.length; i++) cover[i] = (float) r.cover[i];
            cover = smooth(cover, Wr, 3);
        }
        // Jupiter's colour: its own burst's, over its disc
        double[] jc = new double[3];
        double jy = 0;
        for (int j = 0; j < Wr; j++) {
            for (int i = 0; i < Wr; i++) {
                if (Math.hypot(i - r.cx, j - r.cy) > r.rJ) continue;
                int q = j * Wr + i;
                jc[0] += r.r1[q];
                jc[1] += r.g1[q];
                jc[2] += r.b1[q];
            }
        }
        jy = 0.2126 * jc[0] + 0.7152 * jc[1] + 0.0722 * jc[2];
        // (half its own, half a warm white: a phone's colours of a dot this small are only a hint)
        double[] warm = {1.03, 0.98, 0.9};
        for (int c = 0; c < 3; c++) jc[c] = Math.max(0.85, Math.min(1.15, 0.5 * (jy > 0 ? jc[c] / jy : 1) + 0.5 * warm[c]));
        float[][] C = {smooth(r.r, Wr, 1.6), smooth(r.g, Wr, 1.6), smooth(r.b, Wr, 1.6)};
        float[] Ys = smooth(Y, Wr, 1.6);
        int[] out = new int[W * H];
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                double x = r.cx + (i - pic.cx) / pic.k, y = r.cy + (j - pic.cy) / pic.k;
                if (x < 1 || y < 1 || x > Wr - 2 || y > Wr - 2) {
                    out[j * W + i] = 0xFF000000;
                    continue;
                }
                double g = Math.max(0, MoonLook.clampNear(G, Wr, Wr, x, y, MoonStack.cubic(G, Wr, Wr, x, y)));
                double tg = Math.min(1, Math.sqrt(Math.max(0, g - gFloor) / gWhite));
                double v = MoonLook.clampNear(R, Wr, Wr, x, y, MoonStack.cubic(R, Wr, Wr, x, y));
                double d = Math.hypot(x - r.cx, y - r.cy) / r.rJ;
                double raise = (far > 0 ? Math.max(1, JupiterStack.ringAt(rn, d * r.rJ) / far) : 1) * (1 + 0.6 * Math.max(0, Math.min(1, (4 - d) / 1.5)));
                double tr = Math.min(1, asinh(Math.max(0, v - black * raise) / knee) / rTop);
                // nothing of the rest inside Jupiter's glare, or where few frames reached
                tr *= Math.max(0, Math.min(1, (d - JupiterStack.GLARE_RJ + 0.3) / 0.9));
                int ix = (int) Math.round(x), iy = (int) Math.round(y), q = iy * Wr + ix;
                if (cover != null && coverMed > 0) tr *= Math.max(0, Math.min(1, (cover[q] / coverMed - 0.5) / 0.3));
                double ys = Ys[q] - r.sky;
                double sure = Math.max(0, Math.min(1, (ys / noise - 8) / 12));
                double[] rgb = new double[3];
                for (int c = 0; c < 3; c++) {
                    double ch = ys > 0 ? (C[c][q] - r.sky) / ys : 1;
                    ch = 1 + sure * 0.4 * (Math.max(0.6, Math.min(1.5, ch)) - 1);
                    double a = tg * jc[c], b = tr * ch;
                    // the layers laid one over the other as light is (screen)
                    rgb[c] = 1 - (1 - Math.min(1, a)) * (1 - Math.min(1, b));
                }
                out[j * W + i] = 0xFF000000 | (MoonLook.srgb8(rgb[0]) << 16) | (MoonLook.srgb8(rgb[1]) << 8) | MoonLook.srgb8(rgb[2]);
            }
        }
        pic.argb = out;
        return pic;
    }

    /** The rest's smoothing (stack px), where its black starts (times the sky's noise), and its brightest moon's tone. */
    static final double SMOOTH = 1.0, BLACK_SIGMAS = 3.5, MOON_WHITE = 0.85;
    /** Jupiter's glow's tones: white at this share of its brightest, the square root below, black under the floor. */
    static final double GLOW_WHITE = 0.6, GLOW_FLOOR = 0.002;

    static double asinh(double x) {
        return Math.log(x + Math.sqrt(x * x + 1));
    }

    /** A Gaussian blur of sigma {@code s} (px) on a square picture. */
    static float[] smooth(float[] img, int w, double s) {
        int rad = (int) Math.ceil(3 * s);
        double[] k = new double[2 * rad + 1];
        double sum = 0;
        for (int i = -rad; i <= rad; i++) sum += k[i + rad] = Math.exp(-i * i / (2 * s * s));
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        int h = img.length / w;
        float[] tmp = new float[img.length], out = new float[img.length];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                double v = 0;
                for (int d = -rad; d <= rad; d++) v += k[d + rad] * img[j * w + Math.max(0, Math.min(w - 1, i + d))];
                tmp[j * w + i] = (float) v;
            }
        }
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                double v = 0;
                for (int d = -rad; d <= rad; d++) v += k[d + rad] * tmp[Math.max(0, Math.min(h - 1, j + d)) * w + i];
                out[j * w + i] = (float) v;
            }
        }
        return out;
    }

    /** The robust spread of a picture's sky: beyond eight of Jupiter's radii, away from every light found. */
    static double skyNoise(float[] D, int w, JupiterStack.Result r) {
        float[] v = new float[D.length];
        int n = 0;
        double keep = Math.max(6, 4 * (r.psf > 0 ? r.psf : 2.2));
        for (int j = 2; j < w - 2; j++) {
            for (int i = 2; i < w - 2; i++) {
                if (Math.hypot(i - r.cx, j - r.cy) < 8 * r.rJ) continue;
                boolean near = false;
                for (JupiterStack.Light l : r.lights) if (Math.hypot(i - l.x, j - l.y) < keep) near = true;
                if (!near) v[n++] = D[j * w + i];
            }
        }
        if (n < 20) return 1e-4;
        float[] s = java.util.Arrays.copyOf(v, n);
        java.util.Arrays.sort(s);
        float med = s[n / 2];
        for (int i = 0; i < n; i++) s[i] = Math.abs(s[i] - med);
        java.util.Arrays.sort(s);
        return Math.max(1e-6, 1.4826 * s[n / 2]);
    }

    /** A place on the stack (result px) on the picture (px). */
    public static double[] onPicture(Picture pic, JupiterStack.Result r, double x, double y) {
        return new double[]{pic.cx + (x - r.cx) * pic.k, pic.cy + (y - r.cy) * pic.k};
    }

    /**
     * The moons named on the photo (and any star found beside them): a name under each light found, in the moon's
     * colour; for a moon that isn't there, a dotted ring where the sums put it and why, or a note by Jupiter when it
     * is behind or in front of it. {@code size}: the names' size (px). East and west are marked at the sides.
     */
    public static void label(Painter p, Picture pic, JupiterStack.Result r, float size) {
        pic.labels.clear();
        List<double[]> taken = new ArrayList<double[]>();
        // Jupiter's own space
        taken.add(new double[]{pic.cx - pic.rJ * 3, pic.cy - pic.rJ * 3, pic.cx + pic.rJ * 3, pic.cy + pic.rJ * 3});
        float note = size * 0.68f;
        double blob = Math.max(size * 0.9, 2.6 * (r.psf > 0 ? r.psf : 2.2) * pic.k);
        // near Jupiter's middle first: their labels have the least room
        Integer[] order = {0, 1, 2, 3};
        final double[] d = new double[4];
        for (int k = 0; k < 4; k++) {
            double[] at = where(r, k);
            d[k] = at == null ? 1e9 : Math.abs(at[0] - r.cx);
        }
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(d[a], d[b]);
            }
        });
        int byJupiter = 0;
        for (int k : order) {
            int st = r.status[k];
            Label l = new Label();
            l.moon = k;
            l.size = size;
            l.text = JupiterGeo.NAMES[k];
            l.seen = st == JupiterStack.FOUND;
            if (st == JupiterStack.OUTSIDE) continue;
            if (st == JupiterStack.HIDDEN || st == JupiterStack.CROSSING) {
                // by Jupiter: above it, then below
                l.x = pic.cx;
                l.y = pic.cy;
                l.note = st == JupiterStack.HIDDEN ? "behind Jupiter" : "crossing in front of Jupiter";
                String s = l.text + ": " + l.note;
                float tw = p.width(s, note, false);
                l.tx = pic.cx - tw / 2;
                l.ty = byJupiter % 2 == 0 ? pic.cy - pic.rJ * 3 - size * (0.6 + 1.2 * (byJupiter / 2)) : pic.cy + pic.rJ * 3 + size * (1.4 + 1.2 * (byJupiter / 2));
                byJupiter++;
                l.size = note;
                p.text(s, (float) l.tx, (float) l.ty, note, false, COLOURS[k]);
                p.line((float) pic.cx, (float) (l.ty < pic.cy ? l.ty + note * 0.35 : l.ty - note * 1.05), (float) pic.cx,
                        (float) (l.ty < pic.cy ? pic.cy - pic.rJ * 1.6 : pic.cy + pic.rJ * 1.6), Math.max(1.5f, size * 0.05f), 0x99000000 | (COLOURS[k] & 0xFFFFFF));
                pic.labels.add(l);
                continue;
            }
            double[] at = where(r, k);
            if (at == null) continue;
            double[] q = onPicture(pic, r, at[0], at[1]);
            l.x = q[0];
            l.y = q[1];
            if (!l.seen) {
                l.note = st == JupiterStack.DARK ? "in Jupiter's shadow" : st == JupiterStack.GLARE ? "lost in Jupiter's glare" : "not seen";
                dotted(p, (float) l.x, (float) l.y, (float) (blob * 0.8), Math.max(1.5f, size * 0.05f), 0xAA000000 | (COLOURS[k] & 0xFFFFFF));
            }
            float tw = p.width(l.text, size, true), nw = l.note.isEmpty() ? 0 : p.width(l.note, note, false);
            float bw = Math.max(tw, nw);
            double h = size * 1.1 + (l.note.isEmpty() ? 0 : note * 1.15);
            // below the light, else above, else further down
            double[][] tries = {{l.y + blob + size * 0.95, 0}, {l.y - blob - h + size * 0.95, 0}, {l.y + blob + size * 0.95 + h, 0},
                    {l.y - blob - 2 * h + size * 0.95, 0}};
            double ty = tries[0][0];
            for (double[] t : tries) {
                double y0 = t[0] - size, y1 = t[0] - size + h;
                if (y0 < size * 2.6 || y1 > pic.h - size * 2.4) continue;
                double x0 = l.x - bw / 2, x1 = l.x + bw / 2;
                if (!clash(taken, x0, y0, x1, y1)) {
                    ty = t[0];
                    break;
                }
            }
            double tx = Math.max(size * 0.4, Math.min(pic.w - bw - size * 0.4, l.x - bw / 2));
            taken.add(new double[]{tx, ty - size, tx + bw, ty - size + h});
            l.tx = tx;
            l.ty = ty;
            int col = l.seen ? COLOURS[k] : 0xCC000000 | (COLOURS[k] & 0xFFFFFF);
            p.text(l.text, (float) (tx + (bw - tw) / 2), (float) ty, size, true, col);
            if (!l.note.isEmpty()) p.text(l.note, (float) (tx + (bw - nw) / 2), (float) (ty + note * 1.15), note, false, DIM);
            pic.labels.add(l);
        }
        // the stars behind (lights far from Jupiter that aren't moons)
        for (JupiterStack.Light s : r.lights) {
            if (s.moon >= 0 || s.faint || Math.hypot(s.x - r.cx, s.y - r.cy) < 4 * r.rJ) continue;
            double[] q = onPicture(pic, r, s.x, s.y);
            if (q[0] < 0 || q[1] < 0 || q[0] > pic.w || q[1] > pic.h) continue;
            float fs = note;
            float tw = p.width("a star", fs, false);
            double ty = q[1] + blob + fs;
            if (clash(taken, q[0] - tw / 2, ty - fs, q[0] + tw / 2, ty)) ty = q[1] - blob - fs * 0.3;
            if (clash(taken, q[0] - tw / 2, ty - fs, q[0] + tw / 2, ty)) continue;
            taken.add(new double[]{q[0] - tw / 2, ty - fs, q[0] + tw / 2, ty});
            p.text("a star", (float) (q[0] - tw / 2), (float) ty, fs, false, GREY);
            Label l = new Label();
            l.x = q[0];
            l.y = q[1];
            l.tx = q[0] - tw / 2;
            l.ty = ty;
            l.size = fs;
            l.text = "a star";
            l.seen = true;
            pic.labels.add(l);
        }
        // east and west, as the sky shows them
        float es = size * 0.62f;
        p.text("EAST", size * 0.5f, (float) (pic.cy + es * 0.35), es, true, DIM);
        p.text("WEST", pic.w - size * 0.5f - p.width("WEST", es, true), (float) (pic.cy + es * 0.35), es, true, DIM);
    }

    /** Where moon k is on the stack: its light if found, else where the sums put it (null: nowhere). */
    static double[] where(JupiterStack.Result r, int k) {
        if (r.moon[k] != null) return new double[]{r.moon[k].x, r.moon[k].y};
        return r.predicted[k];
    }

    static boolean clash(List<double[]> taken, double x0, double y0, double x1, double y1) {
        double m = 0.25 * (y1 - y0);
        for (double[] t : taken) if (x0 - m < t[2] && x1 + m > t[0] && y0 < t[3] && y1 > t[1]) return true;
        return false;
    }

    /** A ring of dots. */
    static void dotted(Painter p, float cx, float cy, float rad, float stroke, int argb) {
        int n = Math.max(10, (int) (2 * Math.PI * rad / (stroke * 4)));
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            p.disc((float) (cx + rad * Math.cos(a)), (float) (cy + rad * Math.sin(a)), stroke * 0.7f, argb);
        }
    }

    /** The label nearest a tap (px), within {@code reach}: its light or its words; null if none. */
    public static Label at(Picture pic, double x, double y, double reach) {
        Label best = null;
        double bd = reach;
        for (Label l : pic.labels) {
            double dd = Math.hypot(x - l.x, y - l.y);
            if (x >= l.tx - 10 && x <= l.tx + l.text.length() * l.size * 0.6 + 10 && y >= l.ty - l.size && y <= l.ty + l.size) dd = 0;
            if (dd < bd) {
                bd = dd;
                best = l;
            }
        }
        return best;
    }

    /** The photo's title and caption, across the top and the bottom (as the Moonshot's). */
    public static void captions(Painter p, int w, int h, String title, String caption, float size) {
        p.gradient(0, 0, w, size * 2.6f, 0xC0000000, 0x00000000);
        p.gradient(0, h - size * 2.6f, w, h, 0x00000000, 0xC0000000);
        float tw = p.width(title, size * 1.15f, true);
        p.text(title, (w - tw) / 2f, size * 1.6f, size * 1.15f, true, 0xFFFFFFFF);
        float cs = size * 0.72f, cw = p.width(caption, cs, false);
        if (cw > w - 2 * size) {
            cs *= (w - 2 * size) / cw;
            cw = p.width(caption, cs, false);
        }
        p.text(caption, (w - cw) / 2f, h - size * 0.9f, cs, false, 0xFFCFD8DC);
    }

    /** The caption under the photo: when, where from, how many frames, and how old Jupiter's light is. */
    public static String caption(JupiterStack.Result r, JupiterGeo.View v, String when, String where) {
        StringBuilder sb = new StringBuilder(when);
        if (!where.isEmpty()) sb.append(" · ").append(where);
        sb.append(String.format(Locale.US, " · %d + %d frames", r.used, r.moonsUsed));
        if (v != null) sb.append(String.format(Locale.US, " · Jupiter %,.0f million km away, its light %.0f minutes old", v.distAu * Astro.AU_KM / 1e6,
                v.lightMinutes()));
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------
    // tonight's line-up, from the sums

    /**
     * Jupiter and its moons as the sums put them at a moment, on a black strip w x h: Jupiter a disc, each moon that
     * shows a dot sized by its brightness with its name, the ones that don't (behind, crossing, in the shadow) named
     * by Jupiter. East on the left.
     */
    public static void lineUp(Painter p, int w, int h, JupiterGeo.View v, float size) {
        double cx = w / 2.0, cy = h * 0.42;
        double far = 8;
        for (JupiterGeo.Moon m : v.moons) if (m.showing()) far = Math.max(far, Math.abs(m.x));
        double k = (w / 2.0 - size * 2.2) / (far * 1.05);
        double rj = Math.max(size * 0.3, Math.min(size * 0.6, k));
        // Jupiter: a creamy disc, a little flattened, with its glow
        for (int i = 6; i >= 1; i--) p.disc((float) cx, (float) cy, (float) (rj * (1 + i * 0.35)), ((int) (14 - 2 * i) << 24) | 0xFFF3E0);
        p.disc((float) cx, (float) cy, (float) rj, 0xFFFFF3E0);
        List<double[]> taken = new ArrayList<double[]>();
        taken.add(new double[]{cx - rj * 1.5, cy - rj * 1.5, cx + rj * 1.5, cy + rj * 1.5});
        int byJ = 0;
        for (JupiterGeo.Moon m : v.moons) {
            String name = JupiterGeo.NAMES[m.k];
            if (!m.showing()) {
                String s = name + (m.hidden() ? ": behind Jupiter" : m.transit() ? ": crossing in front" : ": in Jupiter's shadow");
                float fs = size * 0.62f, tw = p.width(s, fs, false);
                double ty = cy + rj + size * (1.3 + 0.95 * byJ++);
                p.text(s, (float) (cx - tw / 2), (float) ty, fs, false, COLOURS[m.k]);
                continue;
            }
            double x = cx + m.x * k, y = cy - m.y * k;
            float dot = (float) Math.max(2.5, size * 0.22 * Math.pow(10, -0.2 * (m.mag - 5)));
            p.disc((float) x, (float) y, dot * 2.2f, 0x10FFFFFF);
            p.disc((float) x, (float) y, dot * 1.6f, 0x18FFFFFF);
            p.disc((float) x, (float) y, dot, 0xFF000000 | (COLOURS[m.k] & 0xFFFFFF));
            float fs = size * 0.62f, tw = p.width(name, fs, true);
            double ty = y + dot + fs * 1.25;
            if (clash(taken, x - tw / 2, ty - fs, x + tw / 2, ty)) ty = y - dot - fs * 0.5;
            if (clash(taken, x - tw / 2, ty - fs, x + tw / 2, ty)) ty = y + dot + fs * 2.4;
            taken.add(new double[]{x - tw / 2, ty - fs, x + tw / 2, ty});
            p.text(name, (float) Math.max(2, Math.min(w - tw - 2, x - tw / 2)), (float) ty, fs, true, COLOURS[m.k]);
        }
        float es = size * 0.5f;
        p.text("EAST", size * 0.3f, h - es * 0.8f, es, true, DIM);
        p.text("WEST", w - size * 0.3f - p.width("WEST", es, true), h - es * 0.8f, es, true, DIM);
    }

    /** The line-up strip's height for a width. */
    public static int lineUpHeight(int w) {
        return Math.round(w * 0.3f);
    }

    // ------------------------------------------------------------------------------------------------
    // Galileo's notebook

    /** A night on the notebook page: when, the four's places (Jupiter's radii, x west, y north) and which were seen. */
    public static final class Entry {
        public long t;
        /** Where from ("Padua", or blank). */
        public String where = "";
        public final double[] x = new double[4], y = new double[4];
        /** Seen on the photo (or by Galileo), and there to be seen (lit, not behind or in front of Jupiter). */
        public final boolean[] seen = new boolean[4], showing = new boolean[4];
        /** Galileo's own night (0..6), or -1. */
        public int galileo = -1;
    }

    /**
     * Galileo's night i: the moons where the sums put them then, the ones he drew as seen (the others there too, too
     * close to Jupiter or to each other for his telescope; his drawing agrees with the sums: see JupiterGeoTest).
     */
    public static Entry galileo(int i) {
        JupiterGeo.View v = JupiterGeo.galileo(i);
        Entry e = fromSums(v, JupiterGeo.galileoMs(i));
        e.where = "Padua";
        e.galileo = i;
        for (int k : JupiterGeo.GALILEO_SAW[i]) e.seen[k] = true;
        return e;
    }

    /** A night as the sums put it (nothing seen yet: the moons that show are there to be seen). */
    public static Entry fromSums(JupiterGeo.View v, long t) {
        Entry e = new Entry();
        e.t = t;
        for (JupiterGeo.Moon m : v.moons) {
            e.x[m.k] = m.x;
            e.y[m.k] = m.y;
            e.showing[m.k] = m.showing();
        }
        return e;
    }

    /** A night from a photo: the moons found where they were found, the others where the sums put them. */
    public static Entry fromPhoto(JupiterStack.Result r, JupiterGeo.View v, long t) {
        Entry e = fromSums(v, t);
        for (int k = 0; k < 4; k++) {
            JupiterStack.Light l = r.moon[k];
            if (r.status[k] == JupiterStack.FOUND && l != null) {
                e.seen[k] = true;
                e.x[k] = l.jx;
                e.y[k] = l.jy;
            }
        }
        return e;
    }

    /** Paper: warm, a little uneven, darker towards the edges. ARGB, w x h. */
    public static int[] paper(int w, int h) {
        int[] out = new int[w * h];
        java.util.Random rnd = new java.util.Random(1610);
        // a few soft blotches, as old paper has
        double[][] spots = new double[14][];
        for (int i = 0; i < spots.length; i++) spots[i] = new double[]{rnd.nextDouble() * w, rnd.nextDouble() * h, 60 + rnd.nextDouble() * 220, 0.02 + 0.04 * rnd.nextDouble()};
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                double ex = Math.min(i, w - 1 - i) / (0.18 * w), ey = Math.min(j, h - 1 - j) / (0.18 * Math.min(w, h));
                double edge = Math.min(1, Math.min(ex, ey));
                double shade = 0.86 + 0.14 * Math.sqrt(Math.max(0, edge));
                for (double[] s : spots) {
                    double dd = Math.hypot(i - s[0], j - s[1]) / s[2];
                    if (dd < 1) shade -= s[3] * (1 - dd * dd);
                }
                shade += 0.025 * (rnd.nextDouble() - 0.5);
                int r = clamp8(243 * shade), g = clamp8(229 * shade), b = clamp8(199 * shade);
                out[j * w + i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }

    static int clamp8(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /** One part of the page: a heading, its nights, and whether they're written in pencil (the nights to come). */
    public static final class Section {
        public String heading;
        public final List<Entry> nights = new ArrayList<Entry>();
        public boolean pencil;

        public Section(String heading, boolean pencil) {
            this.heading = heading;
            this.pencil = pencil;
        }
    }

    /** The page's row height, margins and heading heights (px, at a 1440 px page). */
    static final int ROW = 96, TOP = 210, HEAD = 92, FOOT = 70;

    /** How tall a page of these sections is (px) at width w. */
    public static int pageHeight(int w, List<Section> sections) {
        double s = w / 1440.0;
        int rows = 0, heads = 0;
        for (Section x : sections) {
            heads++;
            rows += Math.max(1, x.nights.size());
        }
        return (int) Math.round((TOP + heads * HEAD + rows * ROW + FOOT) * s);
    }

    static final int INK = 0xFF3E2A14, FADED = 0xB04E3A24, PENCIL = 0x8C5D5D5D, RULE = 0x2A5D4037;

    /**
     * The notebook page written on paper w px wide (its height from {@link #pageHeight}): a title, then each section,
     * each night a line: its date on the left, then Ori. ... O ... Occ., the moons little stars where they were (the
     * ones there but not seen a faint pencil star), each with its initial under it.
     */
    public static void page(Painter p, int w, List<Section> sections, TimeZone z) {
        double s = w / 1440.0;
        float title = (float) (64 * s);
        String t = "Galileo's notebook";
        p.script(t, (float) ((w - p.scriptWidth(t, title)) / 2), (float) (104 * s), title, INK);
        String sub = "Ori. is east, Occ. is west, as Galileo wrote them. O is Jupiter, * a moon.";
        float subS = (float) (30 * s);
        p.script(sub, (float) ((w - p.scriptWidth(sub, subS)) / 2), (float) (160 * s), subS, FADED);
        double y = TOP * s;
        double left = 60 * s, dateW = 360 * s, x0 = left + dateW, x1 = w - 60 * s;
        double mid = (x0 + x1) / 2, half = (x1 - x0) / 2 - 90 * s;
        for (Section sec : sections) {
            y += HEAD * s;
            p.script(sec.heading, (float) left, (float) (y - 26 * s), (float) (40 * s), INK);
            p.line((float) left, (float) (y - 12 * s), (float) (w - left), (float) (y - 12 * s), (float) Math.max(1, 1.5 * s), 0x665D4037);
            if (sec.nights.isEmpty()) {
                y += ROW * s;
                p.script("None yet: shoot Jupiter and tonight goes here.", (float) (left + 20 * s), (float) (y - 34 * s), (float) (32 * s), FADED);
                continue;
            }
            for (Entry e : sec.nights) {
                y += ROW * s;
                double base = y - 30 * s;
                int ink = sec.pencil ? PENCIL : INK;
                p.line((float) left, (float) (y - 4 * s), (float) (w - left), (float) (y - 4 * s), (float) Math.max(1, s), RULE);
                String[] dl = date(e, z);
                p.script(dl[0], (float) left, (float) (dl[1].isEmpty() ? base : base - 14 * s), (float) (32 * s), ink);
                if (!dl[1].isEmpty()) p.script(dl[1], (float) (left + 12 * s), (float) (base + 18 * s), (float) (24 * s), sec.pencil ? PENCIL : FADED);
                float os = (float) (30 * s);
                p.script("Ori.", (float) x0, (float) base, os, sec.pencil ? PENCIL : FADED);
                p.script("Occ.", (float) (x1 - p.scriptWidth("Occ.", os)), (float) base, os, sec.pencil ? PENCIL : FADED);
                double cy = base - 10 * s;
                p.ring((float) mid, (float) cy, (float) (13 * s), (float) Math.max(1.5, 3 * s), ink);
                double reach = JupiterGeo.REACH[3];
                List<Double> inis = new ArrayList<Double>();
                for (int k = 0; k < 4; k++) {
                    if (!e.showing[k] && !e.seen[k]) continue;
                    double mx = mid + Math.max(-1.05, Math.min(1.05, e.x[k] / reach)) * half;
                    // a moon right by Jupiter goes just outside its O
                    if (Math.abs(mx - mid) < 22 * s) mx = mid + Math.signum(e.x[k] == 0 ? 1 : e.x[k]) * 22 * s;
                    double my = cy - Math.max(-1, Math.min(1, e.y[k] / 3)) * 8 * s;
                    int col = e.seen[k] ? ink : PENCIL;
                    star(p, (float) mx, (float) my, (float) (9 * s), (float) Math.max(1.2, 2.2 * s), col);
                    String ini = JupiterGeo.NAMES[k].substring(0, 1);
                    float is = (float) (20 * s);
                    // an initial right under another one's goes a line lower
                    int below = 0;
                    for (double o : inis) if (Math.abs(o - mx) < 16 * s) below++;
                    inis.add(mx);
                    p.script(ini, (float) (mx - p.scriptWidth(ini, is) / 2), (float) (my + (30 + 18 * below) * s), is, e.seen[k] ? FADED : PENCIL);
                }
            }
        }
    }

    /** A little star: three strokes crossing. */
    static void star(Painter p, float x, float y, float r, float stroke, int argb) {
        for (int i = 0; i < 3; i++) {
            double a = Math.PI / 2 + i * Math.PI / 3;
            float dx = (float) (r * Math.cos(a)), dy = (float) (r * Math.sin(a));
            p.line(x - dx, y - dy, x + dx, y + dy, stroke, argb);
        }
    }

    /** A night's date as the page writes it, and its time: "Jan. 7, 1610" for Galileo's, "Nov. 26, 2026" and "9:14 PM" for others. */
    static String[] date(Entry e, TimeZone z) {
        if (e.galileo >= 0) return new String[]{"Jan. " + JupiterGeo.GALILEO_DAY[e.galileo] + ", 1610", ""};
        SimpleDateFormat f = new SimpleDateFormat("MMM d, yyyy", Locale.US), c = new SimpleDateFormat("h:mm a", Locale.US);
        f.setTimeZone(z);
        c.setTimeZone(z);
        String s = f.format(new Date(e.t));
        // "Nov 26" -> "Nov. 26", as by hand (May has no dot)
        return new String[]{s.startsWith("May") ? s : s.substring(0, 3) + "." + s.substring(3), c.format(new Date(e.t))};
    }

    // ------------------------------------------------------------------------------------------------
    // the nights to come

    /**
     * The next nights here: each one's best moment to look (Jupiter well up in a dark sky), as the sums put the moons
     * then; nights it isn't up in the dark are left out. Searched from {@code from} for {@code nights} nights.
     */
    public static List<Entry> coming(long from, double lat, double lon, int nights) {
        List<Entry> out = new ArrayList<Entry>();
        // each night from local noon (by the longitude) to the next noon
        long day = 86_400_000L;
        long noon = from - Math.floorMod(from + Math.round(lon / 15 * 3_600_000L) - day / 2, day);
        for (int n = 0; n < nights + 1 && out.size() < nights; n++) {
            long start = Math.max(from, noon + n * day);
            long end = noon + (n + 1) * day;
            if (end - start < 3_600_000L) continue;
            long t = JupiterGeo.best(start, lat, lon, (end - start) / 3_600_000.0);
            if (t < 0) continue;
            out.add(fromSums(JupiterGeo.view(t, lat, lon), t));
        }
        return out;
    }

    /** "Tonight" words for a night's happenings, in a zone: each on a line ("" if nothing happens). */
    public static String happenings(long from, long to, TimeZone z) {
        StringBuilder sb = new StringBuilder();
        for (JupiterGeo.Event e : JupiterGeo.events(from, to)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.words(z)).append('.');
        }
        return sb.toString();
    }

    /** A moment's day as "Friday" or "tonight" against now (in a zone). */
    public static String dayWord(long t, long now, TimeZone z) {
        Calendar a = Calendar.getInstance(z, Locale.US), b = Calendar.getInstance(z, Locale.US);
        a.setTimeInMillis(now - 6 * 3_600_000L);
        b.setTimeInMillis(t - 6 * 3_600_000L);
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) return "tonight";
        SimpleDateFormat f = new SimpleDateFormat("EEEE", Locale.US);
        f.setTimeZone(z);
        return f.format(new Date(t));
    }
}
