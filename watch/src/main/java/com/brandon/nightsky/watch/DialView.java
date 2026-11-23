package com.brandon.nightsky.watch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The watch's one screen (2.5), drawn for a round face: a ring round the edge for how far the session has got, the
 * phone's picture in the middle, a few lines of words and up to three buttons along the bottom. All in red on black,
 * as on the phone, so eyes stay used to the dark. What it shows is a {@link Face}; taps and the crown go to the
 * {@link Actions}.
 */
final class DialView extends View {
    static final int RED = 0xFFFF5252, RED_MID = 0xFFC62828, RED_DIM = 0xFF8E2A2A, RED_FAINT = 0xFF2E0B0B, TEXT_DIM = 0xFFB85050;

    interface Actions {
        /** A button (its id), or "tap" for anywhere else. */
        void action(String id);

        /** The crown turned: steps, positive clockwise. */
        void rotate(int steps);
    }

    static final class Button {
        final String id, label;
        boolean primary, enabled = true;

        Button(String id, String label, boolean primary) {
            this.id = id;
            this.label = label;
            this.primary = primary;
        }
    }

    /** What the screen shows. */
    static final class Face {
        /** The small line at the top: how the phone is reached ("Bluetooth · phone 64%"); {@link #linked}: a solid dot. */
        String top = "";
        boolean linked;
        String title = "", big = "", line1 = "", line2 = "", toast = "";
        /** The big line's size against the usual (the code is a bit smaller, to fit). */
        float bigScale = 1f;
        /** A big tick where the big line goes (when there's none): done. */
        boolean tick;
        /** The ring: 0..1, or -1 for none; {@link #spin}: a turning arc (waiting). */
        float progress = -1f;
        boolean spin;
        /** The phone's picture, shown in the middle behind the words (null: none). */
        Bitmap picture;
        final List<Button> buttons = new ArrayList<Button>();
    }

    private Face face = new Face();
    private Actions actions;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final List<RectF> hit = new ArrayList<RectF>();
    private final ColorMatrixColorFilter redFilter;
    private float scrollAcc;
    /** Whether {@link #text} draws a dark halo behind the words (over the picture). */
    private boolean shadeText;
    private long spinStart = System.currentTimeMillis();

    DialView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        // focused for the crown, but never greyed over to show it (the whole screen is the one view)
        setDefaultFocusHighlightEnabled(false);
        setBackgroundColor(0xFF000000);
        // the picture in red: its brightness (a little boosted: a night photo is mostly dark) into the red channel, a
        // little into green and blue so it isn't flat
        ColorMatrix cm = new ColorMatrix(new float[]{
                0.45f, 0.85f, 0.20f, 0, 0,
                0.07f, 0.12f, 0.03f, 0, 0,
                0.06f, 0.10f, 0.03f, 0, 0,
                0, 0, 0, 1, 0,
        });
        redFilter = new ColorMatrixColorFilter(cm);
    }

    void setActions(Actions a) {
        actions = a;
    }

    void show(Face f) {
        face = f;
        invalidate();
    }

    Face face() {
        return face;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestFocus();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f, R = Math.min(w, h) / 2f;
        c.drawColor(0xFF000000);
        Face f = face;
        // the picture: a disc in the middle, faded into the black at its edge
        if (f.picture != null && !f.picture.isRecycled()) {
            float pr = R * 0.70f;
            Bitmap b = f.picture;
            float s = Math.max(2 * pr / b.getWidth(), 2 * pr / b.getHeight());
            BitmapShader sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate(cx - b.getWidth() * s / 2, cy - b.getHeight() * s / 2);
            sh.setLocalMatrix(m);
            p.reset();
            p.setAntiAlias(true);
            p.setShader(sh);
            p.setColorFilter(redFilter);
            p.setAlpha(215);
            c.drawCircle(cx, cy, pr, p);
            p.reset();
            p.setAntiAlias(true);
            p.setShader(new RadialGradient(cx, cy, pr, new int[]{0x00000000, 0x00000000, 0xC0000000, 0xFF000000},
                    new float[]{0f, 0.55f, 0.88f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, pr + 1, p);
            p.setShader(null);
        }
        // the ring
        float stroke = Math.max(4f, R * 0.035f), rr = R - stroke * 1.2f;
        r.set(cx - rr, cy - rr, cx + rr, cy + rr);
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(RED_FAINT);
        c.drawCircle(cx, cy, rr, p);
        if (f.spin) {
            float a = ((System.currentTimeMillis() - spinStart) % 1600) / 1600f * 360f;
            p.setColor(RED_MID);
            c.drawArc(r, a - 90, 70, false, p);
            postInvalidateDelayed(40);
        } else if (f.progress >= 0) {
            p.setColor(RED);
            c.drawArc(r, -90, Math.max(1f, 360f * Math.min(1f, f.progress)), false, p);
        }
        // the words
        p.reset();
        p.setAntiAlias(true);
        p.setTextAlign(Paint.Align.CENTER);
        if (!f.top.isEmpty()) {
            float ty = cy - R * 0.66f;
            float size = R * 0.075f;
            p.setTypeface(Typeface.DEFAULT);
            p.setTextSize(size);
            String t = fit(f.top, chord(R, cy - ty) - R * 0.25f);
            float tw = p.measureText(t);
            p.setColor(f.linked ? TEXT_DIM : RED_DIM);
            c.drawText(t, cx + size * 0.55f, ty, p);
            // a dot before it: solid when the phone is heard, a ring while it isn't
            p.setColor(f.linked ? RED : RED_DIM);
            p.setStyle(f.linked ? Paint.Style.FILL : Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, size * 0.12f));
            c.drawCircle(cx - tw / 2 - size * 0.15f, ty - size * 0.33f, size * 0.28f, p);
            p.setStyle(Paint.Style.FILL);
        }
        boolean hasButtons = !f.buttons.isEmpty();
        // over the picture, the words get a dark halo so they stay readable on its stars
        shadeText = f.picture != null && !f.picture.isRecycled();
        float y = cy - R * 0.40f;
        if (!f.title.isEmpty()) {
            p.setTypeface(Typeface.DEFAULT_BOLD);
            text(c, f.title, cx, y, R * 0.105f, chord(R, cy - y) - R * 0.12f, RED);
        }
        if (!f.big.isEmpty()) {
            float by = cy - R * 0.07f + R * 0.10f * (1 - f.bigScale);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            text(c, f.big, cx, by, R * 0.25f * f.bigScale, chord(R, R * 0.2f) - R * 0.10f, RED);
        }
        if (f.big.isEmpty() && f.tick) {
            // a tick drawn, not typed: no font needed for it
            float tx = cx, ty = cy - R * 0.16f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(R * 0.055f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(RED);
            Path tk = new Path();
            tk.moveTo(tx - R * 0.13f, ty);
            tk.lineTo(tx - R * 0.04f, ty + R * 0.09f);
            tk.lineTo(tx + R * 0.15f, ty - R * 0.11f);
            c.drawPath(tk, p);
            p.setStyle(Paint.Style.FILL);
        }
        p.setTypeface(Typeface.DEFAULT);
        float ly = cy + R * (hasButtons ? 0.14f : 0.20f);
        if (!f.line1.isEmpty()) text(c, f.line1, cx, ly, R * 0.082f, chord(R, ly - cy) - R * 0.14f, TEXT_DIM);
        ly += R * 0.115f;
        if (!f.line2.isEmpty()) text(c, f.line2, cx, ly, R * 0.072f, chord(R, ly - cy) - R * 0.16f, RED_DIM);
        shadeText = false;
        // the buttons
        hit.clear();
        int n = f.buttons.size();
        float byc = cy + R * 0.53f;
        for (int i = 0; i < n; i++) {
            Button b = f.buttons.get(i);
            RectF box;
            if (n == 1) {
                box = new RectF(cx - R * 0.44f, byc - R * 0.12f, cx + R * 0.44f, byc + R * 0.12f);
            } else if (n == 2) {
                float x = cx + (i == 0 ? -1 : 1) * R * 0.33f;
                box = new RectF(x - R * 0.30f, byc - R * 0.115f, x + R * 0.30f, byc + R * 0.115f);
            } else {
                float x = cx + (i - 1) * R * 0.43f, rad = R * 0.155f, yy = byc - (i == 1 ? 0 : R * 0.04f);
                box = new RectF(x - rad, yy - rad, x + rad, yy + rad);
            }
            hit.add(box);
            float corner = box.height() / 2;
            p.setStyle(Paint.Style.FILL);
            p.setColor(!b.enabled ? 0xFF140505 : b.primary ? RED : 0xFF240909);
            c.drawRoundRect(box, corner, corner, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, R * 0.012f));
            p.setColor(!b.enabled ? RED_FAINT : b.primary ? RED : RED_MID);
            c.drawRoundRect(box, corner, corner, p);
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            float size = n == 3 ? R * 0.075f : R * 0.085f;
            text(c, b.label, box.centerX(), box.centerY() + size * 0.36f, size, box.width() - R * 0.05f,
                    !b.enabled ? RED_DIM : b.primary ? 0xFF000000 : RED);
        }
        if (!f.toast.isEmpty()) {
            p.setTypeface(Typeface.DEFAULT);
            float ty = cy + R * (hasButtons ? 0.80f : 0.62f);
            List<String> lines = wrap(f.toast, R * 0.068f, chord(R, ty - cy + R * 0.08f) - R * 0.18f);
            for (int i = 0; i < lines.size() && i < 2; i++) {
                text(c, lines.get(i), cx, ty + i * R * 0.085f, R * 0.068f, Float.MAX_VALUE, TEXT_DIM);
            }
        }
    }

    /** The circle's width at {@code dy} from its middle. */
    private static float chord(float R, float dy) {
        float d = Math.min(Math.abs(dy), R * 0.98f);
        return 2f * (float) Math.sqrt(R * R - d * d);
    }

    /** One line centred at (x, y): shrunk to fit {@code room} down to three quarters of {@code size}, then cut short. */
    private void text(Canvas c, String s, float x, float y, float size, float room, int color) {
        p.setTextSize(size);
        float w = p.measureText(s);
        if (w > room) {
            p.setTextSize(Math.max(size * 0.75f, size * room / w));
            s = fit(s, room);
        }
        p.setColor(color);
        if (shadeText) p.setShadowLayer(p.getTextSize() * 0.35f, 0, 0, 0xF0000000);
        c.drawText(s, x, y, p);
        if (shadeText) p.clearShadowLayer();
    }

    /** {@code s} cut to fit {@code room} at the paint's size, with an ellipsis. */
    private String fit(String s, float room) {
        if (p.measureText(s) <= room) return s;
        int n = s.length();
        while (n > 1 && p.measureText(s.substring(0, n) + "…") > room) n--;
        return s.substring(0, n).trim() + "…";
    }

    private List<String> wrap(String s, float size, float room) {
        p.setTextSize(size);
        List<String> out = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String t = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && p.measureText(t) > room) {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(t);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        if (out.size() > 2) out.set(1, fit(out.get(1) + " " + out.get(2), room));
        return out;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() != MotionEvent.ACTION_UP || actions == null) return true;
        Face f = face;
        for (int i = 0; i < hit.size() && i < f.buttons.size(); i++) {
            RectF b = new RectF(hit.get(i));
            // a finger is bigger than the drawing: a little room round each button
            b.inset(-getWidth() * 0.02f, -getWidth() * 0.02f);
            if (b.contains(e.getX(), e.getY())) {
                if (f.buttons.get(i).enabled) actions.action(f.buttons.get(i).id);
                return true;
            }
        }
        actions.action("tap");
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_SCROLL && e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) && actions != null) {
            // the crown: a step for each notch's worth of turn
            scrollAcc += -e.getAxisValue(MotionEvent.AXIS_SCROLL);
            int steps = (int) scrollAcc;
            if (steps != 0) {
                scrollAcc -= steps;
                actions.rotate(steps);
            }
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    /** The screen drawn into a bitmap {@code side} pixels square (for the tests' pictures). */
    Bitmap draw(int side) {
        measure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY));
        layout(0, 0, side, side);
        Bitmap b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        draw(c);
        // the round face: what's outside it isn't on the watch
        Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        mask.setColor(0xFF202020);
        Path outside = new Path();
        outside.setFillType(Path.FillType.EVEN_ODD);
        outside.addRect(0, 0, side, side, Path.Direction.CW);
        outside.addCircle(side / 2f, side / 2f, side / 2f, Path.Direction.CW);
        c.drawPath(outside, mask);
        return b;
    }
}
