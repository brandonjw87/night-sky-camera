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
 * confirmed bright, the ones only predicted faint), and the names of the stars, planets and constellations, with the
 * constellations' figures (2.0).
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
        drawFigures(c, sky, w, h, unit);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1.2f, 1.2f * unit));
        ring.setColor(0xC0B8D8FF);
        float size = text.getTextSize();
        for (SkyGuide.Item it : sky.items) {
            if (Double.isNaN(it.x) || Double.isNaN(it.y) || "milky way".equals(it.kind)) continue;
            float x = (float) (it.x * w), y = (float) (it.y * h);
            if ("constellation".equals(it.kind)) {
                // inside its figure when it has one in the picture
                if (!Double.isNaN(it.lx) && !Double.isNaN(it.ly)) {
                    x = (float) (it.lx * w);
                    y = (float) (it.ly * h);
                }
                text.setColor(0x90FFFFFF);
                text.setTextSize(size * 0.95f);
                String n = it.name.toUpperCase(java.util.Locale.US);
                c.drawText(n, x - text.measureText(n) / 2, y, text);
                text.setTextSize(size);
                continue;
            }
            float r = 9 * unit;
            if ("spacecraft".equals(it.kind)) {
                // a spacecraft leaving the Solar System, or L2 (2.4): too far to see, so a target diamond where it is,
                // in mission-control green, with how far
                Paint dia = new Paint(ring);
                dia.setColor(0xE07CFFB2);
                Path p = new Path();
                p.moveTo(x, y - r);
                p.lineTo(x + r, y);
                p.lineTo(x, y + r);
                p.lineTo(x - r, y);
                p.close();
                c.drawPath(p, dia);
                c.drawLine(x - 1.6f * r, y, x - 1.15f * r, y, dia);
                c.drawLine(x + 1.15f * r, y, x + 1.6f * r, y, dia);
                c.drawLine(x, y - 1.6f * r, x, y - 1.15f * r, dia);
                c.drawLine(x, y + 1.15f * r, x, y + 1.6f * r, dia);
                text.setColor(0xFF9CFFC8);
                String n = it.note.isEmpty() ? it.name : it.name + " · " + it.note;
                c.drawText(n, x + 1.6f * r + 3 * unit, y + size * 0.35f, text);
                continue;
            }
            if ("dwarf planet".equals(it.kind)) {
                // Pluto (2.4): a dashed ring, since it's there but too faint to show
                Paint dash = new Paint(ring);
                dash.setColor(0xD0FFE6B8);
                dash.setPathEffect(new DashPathEffect(new float[]{3 * unit, 3 * unit}, 0));
                c.drawCircle(x, y, r, dash);
                text.setColor(0xFFFFE6B8);
                String n = it.note.isEmpty() ? it.name : it.name + " · " + it.note;
                c.drawText(n, x + r + 3 * unit, y + size * 0.35f, text);
                continue;
            }
            boolean mine = "mine".equals(it.kind);
            // a star named by you (2.3): in pink, with a heart
            ring.setColor(mine ? 0xE0FF8FC6 : 0xC0B8D8FF);
            c.drawCircle(x, y, r, ring);
            text.setColor(mine ? 0xFFFF8FC6 : "planet".equals(it.kind) || "moon".equals(it.kind) ? 0xFFFFE6B8 : 0xFFC8E0FF);
            c.drawText(mine ? "\u2665 " + it.name : it.name, x + r + 3 * unit, y + size * 0.35f, text);
        }
    }
    /**
     * The constellations' figures: thin lines from star to star along the sky (bent as the lens bends them), stopping
     * short of each star so the star itself shows; a line that leaves the picture runs off its edge.
     */
    static void drawFigures(Canvas c, SkyGuide sky, int w, int h, float unit) {
        if (sky.figures.isEmpty()) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, 1.1f * unit));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(0x80A8C8FF);
        float gap = 6 * unit;
        for (SkyGuide.Line l : sky.figures) {
            float[] xy = figurePath(l, w, h, l.a ? gap : 0f, l.b ? gap : 0f);
            if (xy == null) continue;
            Path path = new Path();
            path.moveTo(xy[0], xy[1]);
            for (int i = 2; i + 1 < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
            c.drawPath(path, p);
        }
    }

    /**
     * A figure's line in a w x h bitmap's pixels, {@code from} pixels cut off its start and {@code to} off its end
     * (along it); null when nothing is left of it.
     */
    static float[] figurePath(SkyGuide.Line l, int w, int h, float from, float to) {
        int n = l.pts.length / 2;
        if (n < 2) return null;
        float[] x = new float[n], y = new float[n];
        float total = 0;
        for (int i = 0; i < n; i++) {
            x[i] = l.pts[2 * i] * w;
            y[i] = l.pts[2 * i + 1] * h;
            if (i > 0) total += (float) Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
        }
        float end = total - to;
        if (end - from < 2f) return null;
        float[] out = new float[2 * (n + 2)];
        int k = 0;
        float at = 0;
        boolean started = false;
        for (int i = 1; i < n; i++) {
            float seg = (float) Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
            float a0 = at, a1 = at + seg;
            at = a1;
            if (seg <= 0f || a1 < from) continue;
            if (!started) {
                float t = (from - a0) / seg;
                out[k++] = x[i - 1] + t * (x[i] - x[i - 1]);
                out[k++] = y[i - 1] + t * (y[i] - y[i - 1]);
                started = true;
            }
            if (a1 >= end) {
                float t = (end - a0) / seg;
                out[k++] = x[i - 1] + t * (x[i] - x[i - 1]);
                out[k++] = y[i - 1] + t * (y[i] - y[i - 1]);
                break;
            }
            out[k++] = x[i];
            out[k++] = y[i];
        }
        if (k < 4) return null;
        float[] r = new float[k];
        System.arraycopy(out, 0, r, 0, k);
        return r;
    }
}
