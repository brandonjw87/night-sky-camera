package com.brandon.nightsky;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.brandon.nightsky.processing.Painter;

/** The app's {@link Painter} (2.4): Deep Space Zoom's cards and Mission Replay's captions, drawn on a bitmap with Canvas. */
final class CanvasPainter implements Painter {
    private static final Typeface PLAIN = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface HAND = Typeface.create(Typeface.SERIF, Typeface.ITALIC);
    private final Canvas c;
    private final Paint words = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    CanvasPainter(Bitmap b) {
        c = new Canvas(b);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    private void font(float size, boolean bold) {
        words.setTextSize(size);
        words.setTypeface(bold ? BOLD : PLAIN);
    }

    @Override
    public float width(String s, float size, boolean bold) {
        font(size, bold);
        return words.measureText(s);
    }

    @Override
    public void text(String s, float x, float y, float size, boolean bold, int argb) {
        font(size, bold);
        words.setColor(argb);
        float d = Math.max(1f, size / 18f);
        words.setShadowLayer(Math.max(1f, size / 8f), d, d, 0x96000000);
        c.drawText(s, x, y, words);
    }

    @Override
    public void script(String s, float x, float y, float size, int argb) {
        words.setTextSize(size);
        words.setTypeface(HAND);
        words.setColor(argb);
        words.clearShadowLayer();
        c.drawText(s, x, y, words);
    }

    @Override
    public float scriptWidth(String s, float size) {
        words.setTextSize(size);
        words.setTypeface(HAND);
        return words.measureText(s);
    }

    @Override
    public void gradient(float x0, float y0, float x1, float y1, int top, int bottom) {
        // the paint's own alpha multiplies the shader's: opaque, so the gradient's colours are as given
        fill.setColor(0xFF000000);
        fill.setShader(new LinearGradient(x0, y0, x0, y1, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(x0, y0, x1, y1, fill);
        fill.setShader(null);
    }

    @Override
    public void ring(float cx, float cy, float r, float width, int argb) {
        stroke.setStrokeWidth(width);
        stroke.setColor(argb);
        c.drawCircle(cx, cy, r, stroke);
    }

    @Override
    public void disc(float cx, float cy, float r, int argb) {
        fill.setColor(argb);
        c.drawCircle(cx, cy, r, fill);
    }

    @Override
    public void line(float x0, float y0, float x1, float y1, float width, int argb) {
        stroke.setStrokeWidth(width);
        stroke.setColor(argb);
        c.drawLine(x0, y0, x1, y1, stroke);
    }
}
