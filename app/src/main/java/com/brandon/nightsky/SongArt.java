package com.brandon.nightsky;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.brandon.nightsky.processing.StarSong;

import java.util.List;
import java.util.Locale;

/**
 * Hear the Stars on the phone (2.3): the photo as its song plays, drawn for the screen ({@link SongView}) and for the
 * song's video ({@link SongVideo}) alike. A line sweeps across the photo from left to right and the part still to come
 * waits in the dark; each star rings as the line reaches it, a ring in its own colour (blue stars high, red stars low,
 * as they sound) that grows and fades; the planets, the Moon, the Milky Way and the stars named by you say their names.
 * At the end, how many stars sang.
 */
final class SongArt {
    /** How long a ring lasts (s), by kind; a name stays a second longer. */
    static double life(StarSong.Note n) {
        if ("moon".equals(n.kind)) return 3.0;
        if ("planet".equals(n.kind) || "object".equals(n.kind) || "mine".equals(n.kind)) return 2.4;
        return 1.2 + 0.8 * n.gain;
    }

    private final List<StarSong.Note> notes;
    private final int stars;
    private final Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private static final Typeface BOLD = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    private static final Typeface PLAIN = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL);

    SongArt(List<StarSong.Note> notes) {
        this.notes = notes;
        int k = 0;
        for (StarSong.Note n : notes) if ("star".equals(n.kind) || "mine".equals(n.kind)) k++;
        stars = k;
        ring.setStyle(Paint.Style.STROKE);
    }

    /** How long the song lasts (s). */
    static double seconds() {
        return StarSong.seconds();
    }

    /**
     * The picture {@code t} seconds into the song: {@code bmp} drawn into {@code dst} on {@code c} (which is cleared to
     * black first), sizes in {@code px} pixels a dp, with the title and the time if {@code captions}.
     */
    void draw(Canvas c, Bitmap bmp, RectF dst, double t, float px, boolean captions) {
        c.drawColor(Color.BLACK);
        if (bmp != null && !bmp.isRecycled()) c.drawBitmap(bmp, null, dst, photo);
        double sweep = StarSong.SWEEP_S;
        float share = (float) Math.max(0, Math.min(1, t / sweep));
        float x = dst.left + share * dst.width();
        boolean sweeping = t < sweep + 0.6;
        // the part still to come, waiting in the dark (a soft edge where the line is)
        if (t < sweep) {
            float edge = 28 * px;
            fill.setShader(new LinearGradient(x, 0, x + edge, 0, 0x00000000, 0xA6000000, Shader.TileMode.CLAMP));
            r.set(x, dst.top, Math.min(dst.right, x + edge), dst.bottom);
            c.drawRect(r, fill);
            fill.setShader(null);
            if (x + edge < dst.right) {
                fill.setColor(0xA6000000);
                r.set(x + edge, dst.top, dst.right, dst.bottom);
                c.drawRect(r, fill);
            }
        }
        // the Milky Way going by: a soft glow along the line
        for (StarSong.Note n : notes) {
            if (!"milky".equals(n.kind) || t < n.t || t > n.t + n.dur) continue;
            float a = (float) Math.sin(Math.PI * (t - n.t) / n.dur);
            float g = 70 * px;
            fill.setShader(new LinearGradient(x - g, 0, x + g, 0, new int[]{0x00FFFFFF, Color.argb(Math.round(70 * a), 220, 225, 255), 0x00FFFFFF},
                    null, Shader.TileMode.CLAMP));
            r.set(Math.max(dst.left, x - g), dst.top, Math.min(dst.right, x + g), dst.bottom);
            c.drawRect(r, fill);
            fill.setShader(null);
        }
        // the line itself: a glow and a bright core
        if (sweeping) {
            float fade = t < sweep ? 1f : (float) Math.max(0, 1 - (t - sweep) / 0.6);
            float g = 14 * px;
            fill.setShader(new LinearGradient(x - g, 0, x + g, 0, new int[]{0x00BFE8FF, Color.argb(Math.round(110 * fade), 191, 232, 255), 0x00BFE8FF},
                    null, Shader.TileMode.CLAMP));
            r.set(x - g, dst.top, x + g, dst.bottom);
            c.drawRect(r, fill);
            fill.setShader(null);
            fill.setColor(Color.argb(Math.round(235 * fade), 230, 246, 255));
            r.set(x - 0.9f * px, dst.top, x + 0.9f * px, dst.bottom);
            c.drawRect(r, fill);
        }
        // each note: a flash where the line meets it, a ring that grows and fades, a name for the special ones
        text.setTypeface(BOLD);
        text.setTextSize(13 * px);
        for (StarSong.Note n : notes) {
            if ("milky".equals(n.kind)) continue;
            double age = t - n.t;
            double life = life(n);
            if (age < 0 || age > life + 1.0) continue;
            float cx = dst.left + (float) n.u * dst.width(), cy = dst.top + (float) n.v * dst.height();
            int col = colour(n);
            if (age < life) {
                double e = age / life;
                float grow = (float) (1 - Math.pow(1 - e, 3));
                float size = (float) (0.6 + 0.8 * Math.min(1, n.gain)) * ("moon".equals(n.kind) ? 2.2f : 1f);
                ring.setColor(col);
                ring.setAlpha((int) Math.round(255 * Math.pow(1 - e, 1.5)));
                ring.setStrokeWidth((float) ((2.6 * (1 - e) + 0.8) * px));
                c.drawCircle(cx, cy, (6 + 30 * grow) * size * px, ring);
                if (age < 0.25) {
                    float k = (float) (1 - age / 0.25);
                    fill.setColor(col);
                    fill.setAlpha(Math.round(200 * k));
                    c.drawCircle(cx, cy, (4 + 6 * (float) Math.min(1, n.gain)) * size * px * k + px, fill);
                    fill.setColor(Color.WHITE);
                    fill.setAlpha(Math.round(255 * k));
                    c.drawCircle(cx, cy, (1.5f + 2.5f * (float) Math.min(1, n.gain)) * px * k + 0.5f * px, fill);
                }
            }
            String name = label(n);
            if (!name.isEmpty()) {
                double end = life + 1.0;
                float a = (float) Math.min(1, Math.min(age / 0.15, (end - age) / 0.6));
                if (a > 0) {
                    text.setColor(col);
                    text.setAlpha(Math.round(255 * a));
                    text.setShadowLayer(3 * px, 0, 0, Color.argb(Math.round(230 * a), 0, 0, 0));
                    float tx = cx + 12 * px, ty = cy - 10 * px;
                    float tw = text.measureText(name);
                    if (tx + tw > dst.right - 4 * px) tx = cx - 12 * px - tw;
                    if (ty < dst.top + 16 * px) ty = cy + 22 * px;
                    c.drawText(name, tx, ty, text);
                    text.clearShadowLayer();
                }
            }
        }
        if (!captions) return;
        // the title at the start, the count at the end, the time all along
        float m = 12 * px;
        if (t < 5) {
            float a = (float) Math.max(0, Math.min(1, Math.min(t / 0.4, (5 - t) / 0.8)));
            shadowed(Color.WHITE, a, 20 * px, true);
            c.drawText("Hear the Stars", dst.left + m, dst.top + m + 20 * px, text);
            shadowed(0xFFBFE8FF, a, 13 * px, false);
            c.drawText("Blue stars sing high, red stars sing low", dst.left + m, dst.top + m + 40 * px, text);
            text.clearShadowLayer();
        }
        if (t > sweep + 0.3 && stars > 0) {
            float a = (float) Math.max(0, Math.min(1, (t - sweep - 0.3) / 0.6));
            String big = String.format(Locale.US, "%d star%s sang!", stars, stars == 1 ? "" : "s");
            shadowed(Color.WHITE, a, 30 * px, true);
            float w = text.measureText(big);
            c.drawText(big, dst.centerX() - w / 2, dst.centerY(), text);
            text.clearShadowLayer();
        }
        shadowed(0xCCFFFFFF, 1, 12 * px, false);
        String clock = String.format(Locale.US, "%s / %s", clock(Math.min(t, seconds())), clock(seconds()));
        c.drawText(clock, dst.right - m - text.measureText(clock), dst.bottom - m, text);
        text.clearShadowLayer();
    }

    private void shadowed(int colour, float alpha, float size, boolean bold) {
        text.setTypeface(bold ? BOLD : PLAIN);
        text.setTextSize(size);
        text.setColor(colour);
        text.setAlpha(Math.round(Color.alpha(colour) * alpha));
        text.setShadowLayer(size / 5, 0, 0, Color.argb(Math.round(220 * alpha), 0, 0, 0));
    }

    private static String clock(double s) {
        int k = (int) Math.floor(Math.max(0, s));
        return String.format(Locale.US, "%d:%02d", k / 60, k % 60);
    }

    /** The name said with a note: the planets, the Moon, galaxies and nebulae, the brightest stars, and yours (with a heart). */
    static String label(StarSong.Note n) {
        if (n.name == null || n.name.isEmpty()) return "";
        if ("mine".equals(n.kind)) return "♥ " + n.name;
        if ("star".equals(n.kind)) return n.mag < 1.6 ? n.name : "";
        return n.name;
    }

    /** A note's colour: a star's from its own (B-V, blue to red), the others' by what they are. */
    static int colour(StarSong.Note n) {
        if ("planet".equals(n.kind)) return 0xFFFFC85A;
        if ("moon".equals(n.kind)) return 0xFFE6E6F0;
        if ("object".equals(n.kind)) return 0xFFBE8CFF;
        if ("mine".equals(n.kind)) return 0xFFFF8FC6;
        return starColour(n.bv);
    }

    private static final double[] BV = {-0.3, 0.0, 0.3, 0.6, 0.9, 1.3, 1.8};
    private static final int[] BV_RGB = {0x6E96FF, 0x96B9FF, 0xE1EBFF, 0xFFF0C8, 0xFFD282, 0xFFA050, 0xFF6946};

    /** A star's colour from its B-V (a star of unknown colour as the Sun). */
    static int starColour(double bv) {
        if (!(bv == bv)) bv = 0.65;
        if (bv <= BV[0]) return 0xFF000000 | BV_RGB[0];
        for (int i = 1; i < BV.length; i++) {
            if (bv <= BV[i]) {
                double f = (bv - BV[i - 1]) / (BV[i] - BV[i - 1]);
                int a = BV_RGB[i - 1], b = BV_RGB[i];
                int rr = (int) Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
                int gg = (int) Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
                int bb = (int) Math.round((a & 255) * (1 - f) + (b & 255) * f);
                return Color.rgb(rr, gg, bb);
            }
        }
        return 0xFF000000 | BV_RGB[BV_RGB.length - 1];
    }
}
