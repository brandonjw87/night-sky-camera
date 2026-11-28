package com.brandon.nightsky.processing;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mission Replay (2.4): the whole night as one looping GIF saved to the phone, made to be shared. The frames adding up
 * into the photo (kept from the photo's video as it's made, {@link Recorder}), the photo itself, the spacecraft in it
 * marked where they are (Deep Space Network, {@link Tag}), its Deep Space Zoom targets locked in brackets, a zoom into
 * each one that "enhances" into its close-up with its name and distance, and the mission's numbers at the end; then
 * round again.
 * <p>
 * Written with {@link GifWriter}: one palette for the whole film, cut from samples of every picture in it, ordered
 * dithering (a still part of the picture keeps its pixels from frame to frame, so only what moves is stored), and a
 * held picture is one frame with a long time, not many.
 */
public final class MissionReplay {
    private MissionReplay() {}

    /** The GIF's long side (pixels). */
    public static final int SIDE = 480;
    /** At most this many pictures of the frames adding up. */
    public static final int BUILD_FRAMES = 24;
    /** How long each part shows (hundredths of a second), and how many pictures the moving parts take. */
    static final int BUILD_CS = 8, PHOTO_CS = 170, LOCK_CS = 150, ZOOM_FRAMES = 8, ZOOM_CS = 6, FADE_FRAMES = 3, FADE_CS = 7, CARD_CS = 330,
            BACK_CS = 60, END_CS = 320;
    /** The captions' colours: white, the warm yellow of distances, the brackets' green, the tag's blue. */
    static final int WHITE = 0xFFFFFFFF, WARM = 0xFFFFE3A3, LOCK = 0xFF7CFFB2, TAG = 0xFFAFD3FF;

    /** Draws on a picture: the app's with Android's Canvas, the tests' with AWT. */
    public interface Draw {
        /** A painter over {@code argb} (w x h, ARGB row after row); {@link #off} puts what it drew back into argb. */
        Painter on(int[] argb, int w, int h);

        void off(int[] argb);
    }

    /**
     * Keeps the photo video's first part, the frames adding up, as it's made (2.4): passes every picture on to
     * {@code next} (the video's encoder; null: none) and keeps every few, shrunk to the replay's size, at most
     * {@link #BUILD_FRAMES} of them.
     */
    public static final class Recorder implements PhotoVideo.Sink {
        private final PhotoVideo.Sink next;
        final int vw, vh, w, h, every;
        final List<int[]> frames = new ArrayList<int[]>();
        private int seen;

        /** For a {@code vw} x {@code vh} video whose frames adding up are {@code expected} pictures. */
        public Recorder(PhotoVideo.Sink next, int vw, int vh, int expected) {
            this.next = next;
            this.vw = vw;
            this.vh = vh;
            double s = Math.min(1.0, SIDE / (double) Math.max(vw, vh));
            w = Math.max(2, (int) Math.round(vw * s));
            h = Math.max(2, (int) Math.round(vh * s));
            every = Math.max(1, (int) Math.ceil(expected / (double) BUILD_FRAMES));
        }

        /** How much memory it keeps at most (bytes). */
        public static long heapNeeded(int vw, int vh) {
            double s = Math.min(1.0, SIDE / (double) Math.max(vw, vh));
            return (long) BUILD_FRAMES * Math.round(vw * s) * Math.round(vh * s) * 4L;
        }

        @Override
        public void frame(int[] argb, long ptsUs) throws IOException {
            if (next != null) next.frame(argb, ptsUs);
            if (seen++ % every == 0 && frames.size() < BUILD_FRAMES) frames.add(shrink(argb, vw, vh, w, h));
        }

        public int kept() {
            return frames.size();
        }
    }

    /** One Deep Space Zoom close-up in the replay: its box in the photo (middle and side, the photo's pixels), its picture, its words. */
    public static final class Shot {
        public double x, y, side;
        /** The close-up as made (square, without its card), {@code size} x {@code size}. */
        public int[] argb;
        public int size;
        public String title = "", line = "";
    }

    /**
     * Something in the photo too far away to see, for the replay's Deep Space Network frame (2.4): a spacecraft (or Pluto),
     * where it is (the photo's pixels), its name and a word on how far.
     */
    public static final class Tag {
        public double x, y;
        public String name = "", line = "";
    }

    /** How long the Deep Space Network frame shows (centiseconds). */
    static final int NETWORK_CS = 260;

    /** {@link #make} without a Deep Space Network frame. */
    public static String make(Recorder rec, int[] photo, int pw, int ph, List<Shot> shots, String[] end, Draw draw, OutputStream os,
                              Progress progress) throws IOException {
        return make(rec, photo, pw, ph, shots, null, end, draw, os, progress);
    }

    /**
     * Makes the replay and writes it to {@code os} as a GIF: the build {@code rec} kept (null or empty: none), the
     * photo {@code photo} ({@code pw} x {@code ph}, as saved), the spacecraft in it {@code tags} (null or empty: no Deep
     * Space Network frame), the close-ups {@code shots} and the end card's lines ({@code end}: a headline, then smaller
     * ones). Returns what it made, for the log.
     */
    public static String make(Recorder rec, int[] photo, int pw, int ph, List<Shot> shots, List<Tag> tags, String[] end, Draw draw,
                              OutputStream os, Progress progress) throws IOException {
        if (tags == null) tags = new ArrayList<Tag>();
        long t0 = System.currentTimeMillis();
        double s = Math.min(1.0, SIDE / (double) Math.max(pw, ph));
        final int W = Math.max(2, (int) Math.round(pw * s)), H = Math.max(2, (int) Math.round(ph * s));
        int[] small = shrink(photo, pw, ph, W, H);
        List<int[]> build = new ArrayList<int[]>();
        if (rec != null) for (int[] f : rec.frames) build.add(rec.w == W && rec.h == H ? f : resize(f, rec.w, rec.h, W, H));
        // the close-ups as the replay shows them: the frame's height, square
        int sq = Math.min(W, H);
        List<int[]> cards = new ArrayList<int[]>();
        for (Shot sh : shots) cards.add(resize(sh.argb, sh.size, sh.size, sq, sq));
        // the palette, from all of it
        int[] samples = new int[600000];
        int n = 0;
        n = sample(small, 2, samples, n);
        for (int[] f : build) n = sample(f, 9, samples, n);
        for (int[] c : cards) n = sample(c, 3, samples, n);
        GifWriter.Palette pal = GifWriter.Palette.cut(samples, n, 255, new int[]{0x000000, 0xFFFFFF, WARM & 0xFFFFFF, LOCK & 0xFFFFFF,
                TAG & 0xFFFFFF});
        samples = null;
        GifWriter gif = new GifWriter(os, W, H, pal.rgb, 0);
        Film film = new Film(gif, pal, W, H, draw);
        int total = build.size() + 2 + shots.size() * (ZOOM_FRAMES + FADE_FRAMES + 2) + 1 + (tags.isEmpty() ? 0 : 1), done = 0;
        int[] f = new int[W * H];
        // the frames adding up
        for (int i = 0; i < build.size(); i++) {
            System.arraycopy(build.get(i), 0, f, 0, f.length);
            film.caption(f, "MISSION REPLAY", "Stacking the frames", (i + 1) / (double) build.size());
            film.add(f, BUILD_CS);
            done = tick(progress, ++done, total);
        }
        // the photo
        System.arraycopy(small, 0, f, 0, f.length);
        film.caption(f, "MISSION REPLAY", "The photo", Double.NaN);
        film.add(f, PHOTO_CS);
        done = tick(progress, ++done, total);
        double k = W / (double) pw;
        if (!tags.isEmpty()) {
            // the spacecraft in it, too far to see: where each one is
            int[] net = small.clone();
            film.caption(net, "DEEP SPACE NETWORK", tags.size() == 1 ? tags.get(0).name + " is in this photo"
                    : tags.size() + " far travellers in this photo", Double.NaN);
            film.tags(net, tags, k);
            film.add(net, NETWORK_CS);
            done = tick(progress, ++done, total);
        }
        if (!shots.isEmpty()) {
            // its targets locked
            int[] locked = small.clone();
            film.brackets(locked, shots, k, 0, 0, 1.0);
            film.caption(locked, "DEEP SPACE ZOOM", shots.size() == 1 ? "1 target locked" : shots.size() + " targets locked", Double.NaN);
            film.add(locked, LOCK_CS);
            done = tick(progress, ++done, total);
            for (int t = 0; t < shots.size(); t++) {
                Shot sh = shots.get(t);
                // the view: the whole photo, then the box filling the frame's height (its sides as far as the frame's shape)
                double vh1 = sh.side, vw1 = sh.side * W / (double) H;
                double cx = Math.max(vw1 / 2, Math.min(pw - vw1 / 2, sh.x)), cy = Math.max(vh1 / 2, Math.min(ph - vh1 / 2, sh.y));
                if (vw1 > pw) cx = pw / 2.0;
                if (vh1 > ph) cy = ph / 2.0;
                // where the target sits in the frame: from where the whole photo has it to where the last view does
                double fx0 = sh.x * W / pw, fy0 = sh.y * H / ph;
                double fx1 = (sh.x - (cx - vw1 / 2)) * W / vw1, fy1 = (sh.y - (cy - vh1 / 2)) * H / vh1;
                int[] last = null;
                for (int j = 1; j <= ZOOM_FRAMES; j++) {
                    double u = j / (double) ZOOM_FRAMES;
                    double e = u * u * (3 - 2 * u);
                    // the view's size shrinks geometrically, the target kept in sight as it moves to its last place
                    double vw = pw * Math.pow(vw1 / pw, e), vh = ph * Math.pow(vh1 / ph, e);
                    double fx = fx0 + (fx1 - fx0) * e, fy = fy0 + (fy1 - fy0) * e;
                    double x0 = sh.x - fx * vw / W, y0 = sh.y - fy * vh / H;
                    x0 = Math.max(Math.min(0, pw - vw), Math.min(Math.max(0, pw - vw), x0));
                    y0 = Math.max(Math.min(0, ph - vh), Math.min(Math.max(0, ph - vh), y0));
                    view(photo, pw, ph, x0, y0, vw / W, f, W, H);
                    // the last view as it is, for the close-up to come up over
                    if (j == ZOOM_FRAMES) last = f.clone();
                    film.brackets(f, java.util.Collections.singletonList(sh), W / vw, x0, y0, 1.0);
                    film.caption(f, "DEEP SPACE ZOOM", "Zooming in: " + sh.title, Double.NaN);
                    film.add(f, ZOOM_CS);
                    done = tick(progress, ++done, total);
                }
                int[] seen = f.clone();
                // enhance: the close-up comes up in the middle, the zoomed photo dimmed around it
                int[] card = film.card(last, cards.get(t), sq, sh);
                int[] mix = new int[f.length];
                for (int j = 1; j <= FADE_FRAMES; j++) {
                    blend(seen, card, (int) Math.round(256.0 * j / (FADE_FRAMES + 1)), mix);
                    film.add(mix, FADE_CS);
                    done = tick(progress, ++done, total);
                }
                film.add(card, CARD_CS);
                done = tick(progress, ++done, total);
                if (t < shots.size() - 1) {
                    // back to the photo for the next
                    film.add(locked, BACK_CS);
                    done = tick(progress, ++done, total);
                }
            }
        }
        // the mission's numbers
        int[] fin = new int[f.length];
        for (int i = 0; i < f.length; i++) fin[i] = dim(small[i], 90);
        film.end(fin, end);
        film.add(fin, END_CS);
        gif.finish();
        if (progress != null) progress.update("Mission Replay", 1f);
        return String.format(Locale.US, "%dx%d GIF, %d frames (%d of the frames adding up, %d close-ups), %.1f s a loop, %d colours, %d KB, %d ms", W, H,
                gif.frames(), build.size(), shots.size(), film.cs / 100.0, pal.rgb.length, gif.bytes() >> 10, System.currentTimeMillis() - t0);
    }

    private static int tick(Progress p, int done, int total) {
        if (p != null) {
            if (p.cancelled()) throw new CancelledException();
            p.update("Mission Replay", Math.min(1f, done / (float) total));
        }
        return done;
    }

    /** The film being written: pictures mapped onto the palette and handed to the GIF, and the captions drawn on them. */
    private static final class Film {
        final GifWriter gif;
        final GifWriter.Palette pal;
        final int W, H;
        final Draw draw;
        final byte[] idx;
        int cs;

        Film(GifWriter gif, GifWriter.Palette pal, int W, int H, Draw draw) {
            this.gif = gif;
            this.pal = pal;
            this.W = W;
            this.H = H;
            this.draw = draw;
            idx = new byte[W * H];
        }

        void add(int[] argb, int delayCs) throws IOException {
            pal.index(argb, W, H, idx);
            gif.frame(idx, delayCs);
            cs += delayCs;
        }

        /** A tag at the top left, a line at the bottom over a soft dark band, and a bar for how far ({@code fraction}; NaN: none). */
        void caption(int[] f, String tag, String line, double fraction) {
            if (draw == null) return;
            Painter p = draw.on(f, W, H);
            float u = H / 360f, pad = 12 * u;
            p.gradient(0, 0, W, 34 * u, 0x90000000, 0x00000000);
            p.text(tag, pad, pad + 11 * u, 12 * u, true, TAG);
            p.gradient(0, H - 52 * u, W, H, 0x00000000, 0xC0000000);
            float size = 17 * u;
            String s = fit(p, line, W - 2 * pad, size, true);
            p.text(s, pad, H - pad - (fraction == fraction ? 8 * u : 0), size, true, WHITE);
            if (fraction == fraction) {
                float y = H - pad + 2 * u, x1 = W - pad;
                p.line(pad, y, x1, y, 3 * u, 0x50FFFFFF);
                p.line(pad, y, pad + (float) ((x1 - pad) * Math.max(0, Math.min(1, fraction))), y, 3 * u, LOCK);
            }
            draw.off(f);
        }

        /**
         * Brackets around each shot's box: the photo's pixels shown {@code k} frame pixels each from the photo's
         * ({@code x0}, {@code y0}), the name over each when {@code names} is 1.
         */
        void brackets(int[] f, List<Shot> shots, double k, double x0, double y0, double names) {
            if (draw == null) return;
            Painter p = draw.on(f, W, H);
            float u = H / 360f;
            for (Shot sh : shots) {
                float cx = (float) ((sh.x - x0) * k), cy = (float) ((sh.y - y0) * k), r = (float) (0.5 * sh.side * k);
                float arm = Math.max(5 * u, r * 0.35f), sw = 2f * u;
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sy = -1; sy <= 1; sy += 2) {
                        float px = cx + sx * r, py = cy + sy * r;
                        p.line(px, py, px - sx * arm, py, sw, LOCK);
                        p.line(px, py, px, py - sy * arm, sw, LOCK);
                    }
                }
                if (names > 0 && r < 0.4f * H) {
                    float size = 11 * u;
                    String s = sh.title;
                    float tw = p.width(s, size, true);
                    float tx = Math.max(4 * u, Math.min(W - tw - 4 * u, cx - tw / 2)), ty = cy - r - 5 * u;
                    if (ty < 16 * u) ty = cy + r + size + 4 * u;
                    p.text(s, tx, ty, size, true, LOCK);
                }
            }
            draw.off(f);
        }

        /**
         * A target diamond on each tag (2.4), with its name and how far beside it, on the side with room; the photo's
         * pixels shown {@code k} frame pixels each.
         */
        void tags(int[] f, List<Tag> tags, double k) {
            if (draw == null) return;
            Painter p = draw.on(f, W, H);
            float u = H / 360f, r = 7 * u, sw = 1.8f * u, size = 11 * u, small = 9 * u;
            for (Tag t : tags) {
                float cx = (float) (t.x * k), cy = (float) (t.y * k);
                p.line(cx, cy - r, cx + r, cy, sw, LOCK);
                p.line(cx + r, cy, cx, cy + r, sw, LOCK);
                p.line(cx, cy + r, cx - r, cy, sw, LOCK);
                p.line(cx - r, cy, cx, cy - r, sw, LOCK);
                p.line(cx - 1.8f * r, cy, cx - 1.25f * r, cy, sw, LOCK);
                p.line(cx + 1.25f * r, cy, cx + 1.8f * r, cy, sw, LOCK);
                float w = Math.max(p.width(t.name, size, true), p.width(t.line, small, false));
                boolean right = cx + 2.2f * r + w < W - 4 * u;
                float tx = right ? cx + 2.2f * r : Math.max(4 * u, cx - 2.2f * r - w);
                // clear of the captions at the top and the bottom
                float ty = Math.max(34 * u, Math.min(H - 52 * u, cy - 1 * u));
                p.text(t.name, tx, ty, size, true, LOCK);
                if (!t.line.isEmpty()) p.text(t.line, tx, ty + small + 3 * u, small, false, WARM);
            }
            draw.off(f);
        }

        /** The close-up's frame: {@code around} dimmed, the close-up ({@code sq} a side) in its middle, its name and distance under it. */
        int[] card(int[] around, int[] close, int sq, Shot sh) {
            int[] out = new int[W * H];
            for (int i = 0; i < out.length; i++) out[i] = dim(around[i], 70);
            int ox = (W - sq) / 2, oy = (H - sq) / 2;
            for (int y = 0; y < sq; y++) System.arraycopy(close, y * sq, out, (oy + y) * W + ox, sq);
            if (draw == null) return out;
            Painter p = draw.on(out, W, H);
            float u = H / 360f, pad = 12 * u;
            p.gradient(ox, oy + sq * 0.62f, ox + sq, oy + sq, 0x00000000, 0xD8000000);
            p.gradient(0, 0, W, 34 * u, 0x90000000, 0x00000000);
            p.text("DEEP SPACE ZOOM", pad, pad + 11 * u, 12 * u, true, TAG);
            float size = 24 * u;
            String t = fit(p, sh.title, sq - 2 * pad, size, true);
            float lineSize = 13 * u;
            List<String> lines = sh.line.isEmpty() ? new ArrayList<String>() : DeepZoom.wrap(sh.line, sq - 2 * pad, lineSize, false, p);
            if (lines.size() > 2) lines = lines.subList(0, 2);
            float y = oy + sq - pad - (lines.size() - 1) * lineSize * 1.25f;
            for (String l : lines) {
                p.text(l, ox + pad, y, lineSize, false, WARM);
                y += lineSize * 1.25f;
            }
            p.text(t, ox + pad, oy + sq - pad - lines.size() * lineSize * 1.25f - 4 * u, size, true, WHITE);
            draw.off(out);
            return out;
        }

        /** The end card: a headline and smaller lines, centred. */
        void end(int[] f, String[] lines) {
            if (draw == null || lines == null || lines.length == 0) return;
            Painter p = draw.on(f, W, H);
            float u = H / 360f;
            float big = 30 * u, small = 15 * u;
            float total = big * 1.3f + (lines.length - 1) * small * 1.45f;
            float y = (H - total) / 2 + big;
            String head = fit(p, lines[0], W - 24 * u, big, true);
            p.text(head, (W - p.width(head, big, true)) / 2, y, big, true, WHITE);
            y += big * 0.5f + small * 1.2f;
            for (int i = 1; i < lines.length; i++) {
                String l = fit(p, lines[i], W - 24 * u, small, i == 1);
                p.text(l, (W - p.width(l, small, i == 1)) / 2, y, small, i == 1, i == 1 ? WARM : 0xFFDDE4F0);
                y += small * 1.45f;
            }
            draw.off(f);
        }
    }

    /** {@code s} cut short with an ellipsis until it fits {@code w} at {@code size}. */
    static String fit(Painter p, String s, float w, float size, boolean bold) {
        if (p.width(s, size, bold) <= w) return s;
        String t = s;
        while (t.length() > 1 && p.width(t + "…", size, bold) > w) t = t.substring(0, t.length() - 1);
        return t.trim() + "…";
    }

    /** Every {@code step}-th pixel of {@code argb} into {@code into} from {@code n}, as far as it has room; the new count. */
    private static int sample(int[] argb, int step, int[] into, int n) {
        for (int i = (n * 7) % step; i < argb.length && n < into.length; i += step) into[n++] = argb[i] & 0xFFFFFF;
        return n;
    }

    /** The view of the photo from ({@code x0}, {@code y0}), {@code scale} photo pixels a frame pixel, bilinear, into {@code out} (W x H). */
    public static void view(int[] photo, int pw, int ph, double x0, double y0, double scale, int[] out, int W, int H) {
        for (int j = 0; j < H; j++) {
            double py = y0 + (j + 0.5) * scale - 0.5;
            for (int i = 0; i < W; i++) {
                double px = x0 + (i + 0.5) * scale - 0.5;
                out[j * W + i] = scale > 1.5 ? box(photo, pw, ph, px, py, scale) : bilinear(photo, pw, ph, px, py);
            }
        }
    }

    /** The average over the {@code scale}-wide square of photo pixels around (x, y) (shrinking: no shimmer). */
    private static int box(int[] p, int w, int h, double x, double y, double scale) {
        int r0 = (int) Math.floor(y - scale / 2 + 0.5), r1 = (int) Math.floor(y + scale / 2 + 0.5);
        int c0 = (int) Math.floor(x - scale / 2 + 0.5), c1 = (int) Math.floor(x + scale / 2 + 0.5);
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int yy = Math.max(0, r0); yy <= Math.min(h - 1, r1); yy++) {
            for (int xx = Math.max(0, c0); xx <= Math.min(w - 1, c1); xx++) {
                int v = p[yy * w + xx];
                r += (v >> 16) & 0xFF;
                g += (v >> 8) & 0xFF;
                b += v & 0xFF;
                n++;
            }
        }
        if (n == 0) return 0xFF000000;
        return 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    private static int bilinear(int[] p, int w, int h, double x, double y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double fx = x - ix, fy = y - iy;
        int x0 = Math.max(0, Math.min(w - 1, ix)), x1 = Math.max(0, Math.min(w - 1, ix + 1));
        int y0 = Math.max(0, Math.min(h - 1, iy)), y1 = Math.max(0, Math.min(h - 1, iy + 1));
        int a = p[y0 * w + x0], b = p[y0 * w + x1], c = p[y1 * w + x0], d = p[y1 * w + x1];
        int out = 0xFF000000;
        for (int sh = 16; sh >= 0; sh -= 8) {
            double top = ((a >> sh) & 0xFF) * (1 - fx) + ((b >> sh) & 0xFF) * fx;
            double bot = ((c >> sh) & 0xFF) * (1 - fx) + ((d >> sh) & 0xFF) * fx;
            out |= ((int) Math.round(top * (1 - fy) + bot * fy) & 0xFF) << sh;
        }
        return out;
    }

    /** {@code src} (w x h) shrunk to W x H, each pixel the average of what it covers. */
    public static int[] shrink(int[] src, int w, int h, int W, int H) {
        if (w == W && h == H) return src.clone();
        int[] out = new int[W * H];
        double sx = w / (double) W, sy = h / (double) H;
        if (sx < 1 || sy < 1) return resize(src, w, h, W, H);
        for (int j = 0; j < H; j++) {
            int r0 = (int) Math.floor(j * sy), r1 = Math.max(r0 + 1, (int) Math.floor((j + 1) * sy));
            for (int i = 0; i < W; i++) {
                int c0 = (int) Math.floor(i * sx), c1 = Math.max(c0 + 1, (int) Math.floor((i + 1) * sx));
                long r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = r0; y < Math.min(h, r1); y++) {
                    for (int x = c0; x < Math.min(w, c1); x++) {
                        int v = src[y * w + x];
                        r += (v >> 16) & 0xFF;
                        g += (v >> 8) & 0xFF;
                        b += v & 0xFF;
                        n++;
                    }
                }
                out[j * W + i] = n == 0 ? 0xFF000000 : 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        }
        return out;
    }

    /** {@code src} (w x h) to W x H: shrunk by averaging, or enlarged bilinearly. */
    static int[] resize(int[] src, int w, int h, int W, int H) {
        if (w == W && h == H) return src.clone();
        if (W <= w && H <= h) return shrink(src, w, h, W, H);
        int[] out = new int[W * H];
        for (int j = 0; j < H; j++) {
            double py = (j + 0.5) * h / (double) H - 0.5;
            for (int i = 0; i < W; i++) out[j * W + i] = bilinear(src, w, h, (i + 0.5) * w / (double) W - 0.5, py);
        }
        return out;
    }

    /** a and b mixed, {@code t} of 256 of b, into out. */
    static void blend(int[] a, int[] b, int t, int[] out) {
        int s = 256 - t;
        for (int i = 0; i < out.length; i++) {
            int p = a[i], q = b[i];
            int r = (((p >> 16) & 0xFF) * s + ((q >> 16) & 0xFF) * t) >> 8;
            int g = (((p >> 8) & 0xFF) * s + ((q >> 8) & 0xFF) * t) >> 8;
            int bb = ((p & 0xFF) * s + (q & 0xFF) * t) >> 8;
            out[i] = 0xFF000000 | r << 16 | g << 8 | bb;
        }
    }

    /** {@code v} at {@code keep} of 256 of its brightness. */
    static int dim(int v, int keep) {
        int r = (((v >> 16) & 0xFF) * keep) >> 8, g = (((v >> 8) & 0xFF) * keep) >> 8, b = ((v & 0xFF) * keep) >> 8;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
