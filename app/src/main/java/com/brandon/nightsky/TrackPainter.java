package com.brandon.nightsky;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

import com.brandon.nightsky.processing.SatOverlay;
import com.brandon.nightsky.processing.SkyGuide;

import java.util.List;

/**
 * Draws on a copy of the picture: satellites' tracks as dashed lines with their names (the ones the picture
 * confirmed bright, the ones only predicted faint), and the names of the stars, planets and constellations.
 */
final class TrackPainter {
    private TrackPainter() {}

    /**
     * A copy of {@code base} with the tracks (if {@code tracks}) and the sky's names (if {@code labels}); see
     * {@link #draw}. Returns {@code base} itself when there is nothing to draw.
     */
    static Bitmap paint(Bitmap base, int x0, int y0, double f, List<SatOverlay.Line> lines, SkyGuide sky, boolean tracks,
                        boolean labels) {
        if (!anything(lines, sky, tracks, labels)) return base;
        Bitmap b = base.copy(Bitmap.Config.ARGB_8888, true);
        draw(b, x0, y0, f, lines, sky, tracks, labels);
        return b;
    }

    /** Whether {@link #draw} would draw anything. */
    static boolean anything(List<SatOverlay.Line> lines, SkyGuide sky, boolean tracks, boolean labels) {
        return tracks && lines != null && !lines.isEmpty() || labels && sky != null && !sky.items.isEmpty();
    }

    /**
     * Draws the tracks (if {@code tracks}) and the sky's names (if {@code labels}) on {@code b} (mutable). Stack
     * pixel (x, y) is bitmap pixel ((x - x0) / f, (y - y0) / f); the sky guide's positions are shares of the
     * picture.
     */
    static void draw(Bitmap b, int x0, int y0, double f, List<SatOverlay.Line> lines, SkyGuide sky, boolean tracks,
                     boolean labels) {
        boolean anyTracks = tracks && lines != null && !lines.isEmpty();
        boolean anyLabels = labels && sky != null && !sky.items.isEmpty();
        if (!anyTracks && !anyLabels) return;
        Canvas c = new Canvas(b);
        int w = b.getWidth(), h = b.getHeight();
        float unit = Math.max(1f, Math.max(w, h) / 1000f);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        text.setTextSize(Math.max(11f, 15 * unit));
        text.setShadowLayer(2.5f * unit, 0, 0, 0xE0000000);
        if (anyLabels) drawSky(c, sky, w, h, unit, text);
        if (anyTracks) {
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(1.5f, 1.6f * unit));
            line.setStrokeCap(Paint.Cap.BUTT);
            line.setPathEffect(new DashPathEffect(new float[]{9 * unit, 7 * unit}, 0));
            line.setShadowLayer(1.5f * unit, 0, 0, 0xC0000000);
            // faint predicted ones first, so the confirmed ones are drawn over them
            for (int pass = 0; pass < 2; pass++) {
                for (SatOverlay.Line l : lines) {
                    if (l.seen != (pass == 1)) continue;
                    line.setColor(l.seen ? 0xFFFFC94D : 0x99E8E8E8);
                    Path p = new Path();
                    for (float[] part : l.parts) {
                        for (int i = 0; i + 1 < part.length; i += 2) {
                            float x = (float) ((part[i] - x0) / f), y = (float) ((part[i + 1] - y0) / f);
                            if (i == 0) p.moveTo(x, y);
                            else p.lineTo(x, y);
                        }
                    }
                    c.drawPath(p, line);
                    if (Float.isNaN(l.labelX)) continue;
                    text.setColor(l.seen ? 0xFFFFE2A0 : 0xB0E8E8E8);
                    String name = l.name + (l.seen ? "" : " (predicted)");
                    // beside the track: to its left, a little more than the text's height out
                    float off = text.getTextSize() * 1.1f;
                    float nx = -l.dirY, ny = l.dirX;
                    if (ny > 0) {
                        nx = -nx;
                        ny = -ny;
                    }
                    float lx = (float) ((l.labelX - x0) / f) + nx * off, ly = (float) ((l.labelY - y0) / f) + ny * off;
                    float tw = text.measureText(name);
                    lx = Math.max(4 * unit, Math.min(w - tw - 4 * unit, lx - tw / 2));
                    ly = Math.max(text.getTextSize() + 2 * unit, Math.min(h - 4 * unit, ly));
                    c.drawText(name, lx, ly, text);
                }
            }
        }
    }

    private static void drawSky(Canvas c, SkyGuide sky, int w, int h, float unit, Paint text) {
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1.2f, 1.2f * unit));
        ring.setColor(0xC0B8D8FF);
        float size = text.getTextSize();
        for (SkyGuide.Item it : sky.items) {
            if (Double.isNaN(it.x) || Double.isNaN(it.y) || "milky way".equals(it.kind)) continue;
            float x = (float) (it.x * w), y = (float) (it.y * h);
            if ("constellation".equals(it.kind)) {
                text.setColor(0x90FFFFFF);
                text.setTextSize(size * 0.95f);
                String n = it.name.toUpperCase(java.util.Locale.US);
                c.drawText(n, x - text.measureText(n) / 2, y, text);
                text.setTextSize(size);
                continue;
            }
            float r = 9 * unit;
            c.drawCircle(x, y, r, ring);
            text.setColor("planet".equals(it.kind) || "moon".equals(it.kind) ? 0xFFFFE6B8 : 0xFFC8E0FF);
            c.drawText(it.name, x + r + 3 * unit, y + size * 0.35f, text);
        }
    }
}
