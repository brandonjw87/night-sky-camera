package com.brandon.flashintro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

/** The round buttons of the shooting bar, drawn in code in the bar's colours: the shutter, slow motion and sound. */
final class ControlButton extends View {
    static final int SHUTTER = 0, SLOW = 1, SOUND = 2;

    private final int kind;
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private RadialGradient gold;
    private Colors colors = Colors.STANDARD;
    private boolean on;
    private boolean replay;

    ControlButton(Context c, int kind) {
        super(c);
        this.kind = kind;
        dp = c.getResources().getDisplayMetrics().density;
        setClickable(true);
        setFocusable(true);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    /** Slow motion and sound: lit gold when on. */
    void setOn(boolean v) {
        on = v;
        invalidate();
    }

    /** Shutter: a replay arrow instead of the play triangle. */
    void setReplay(boolean v) {
        replay = v;
        invalidate();
    }

    void setColors(Colors c) {
        colors = c;
        makeGold(getWidth(), getHeight());
        invalidate();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        makeGold(w, h);
    }

    private void makeGold(int w, int h) {
        float r = Math.min(w, h) / 2f;
        if (r <= 0) return;
        gold = new RadialGradient(w / 2f - r * 0.32f, h / 2f - r * 0.44f, r * 1.25f,
                new int[]{colors.goldHi, colors.gold, colors.goldLo}, new float[]{0f, 0.46f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
        if (isPressed()) c.scale(0.93f, 0.93f, cx, cy);
        paint.setShader(null);
        if (kind == SHUTTER) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3 * dp);
            paint.setColor(colors.gold);
            c.drawCircle(cx, cy, r - 1.5f * dp, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(gold);
            c.drawCircle(cx, cy, r - 7 * dp, paint);
            paint.setShader(null);
            paint.setColor(colors.goldInk);
            float s = r * 0.36f;
            if (!replay) {
                path.reset();
                path.moveTo(cx - s * 0.62f, cy - s);
                path.lineTo(cx + s, cy);
                path.lineTo(cx - s * 0.62f, cy + s);
                path.close();
                c.drawPath(path, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.6f * dp);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                oval.set(cx - s, cy - s, cx + s, cy + s);
                c.drawArc(oval, -60f, 290f, false, paint);
                // arrow head on the top-right end, pointing on round the turn (up and to the left)
                float ax = cx + 0.5f * s - 0.087f * s, ay = cy - 0.866f * s - 0.05f * s, k = 0.55f * s;
                path.reset();
                path.moveTo(ax + 0.342f * k, ay + 0.940f * k);
                path.lineTo(ax, ay);
                path.lineTo(ax + 0.985f * k, ay - 0.174f * k);
                c.drawPath(path, paint);
                paint.setStrokeCap(Paint.Cap.BUTT);
            }
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colors.glass);
        c.drawCircle(cx, cy, r - dp, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp);
        paint.setColor(on ? colors.gold : colors.line);
        c.drawCircle(cx, cy, r - dp, paint);
        int fg = on ? colors.gold : colors.ink;
        if (kind == SLOW) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fg);
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
            Paint.FontMetrics fm = paint.getFontMetrics();
            c.drawText("0.25×", cx, cy - (fm.ascent + fm.descent) / 2f, paint);
            return;
        }
        // speaker: the preview page's icon, a 24-unit box drawn 22 dp wide on the 48 dp button
        float u = r * (22f / 48f) / 12f, ox = cx - 12f * u, oy = cy - 12f * u;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fg);
        path.reset();
        path.moveTo(ox + 4f * u, oy + 9.2f * u);
        path.lineTo(ox + 7.6f * u, oy + 9.2f * u);
        path.lineTo(ox + 12.4f * u, oy + 5f * u);
        path.lineTo(ox + 12.4f * u, oy + 19f * u);
        path.lineTo(ox + 7.6f * u, oy + 14.8f * u);
        path.lineTo(ox + 4f * u, oy + 14.8f * u);
        path.close();
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f * u);
        paint.setStrokeCap(Paint.Cap.ROUND);
        if (on) {
            wave(c, ox + 11.934f * u, oy + 12f * u, 5f * u, 42.85f);
            wave(c, ox + 12.039f * u, oy + 12f * u, 8.6f * u, 44.24f);
        } else {
            c.drawLine(ox + 15.8f * u, oy + 9.4f * u, ox + 20.8f * u, oy + 14.6f * u, paint);
            c.drawLine(ox + 20.8f * u, oy + 9.4f * u, ox + 15.8f * u, oy + 14.6f * u, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** A sound wave: an arc of radius {@code rad} round (x, y), {@code half} degrees either side of straight right. */
    private void wave(Canvas c, float x, float y, float rad, float half) {
        oval.set(x - rad, y - rad, x + rad, y + rad);
        c.drawArc(oval, -half, 2f * half, false, paint);
    }
}
