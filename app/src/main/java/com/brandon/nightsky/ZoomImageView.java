package com.brandon.nightsky;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * Image view with pinch zoom, drag and double-tap zoom, for looking at stars up close. The edit screen (G3) also
 * takes taps on the picture, paints over it with one finger (two fingers then zoom and move it), and draws marks
 * over it.
 */
final class ZoomImageView extends ImageView {
    /** A tap on the picture, at (x, y) in its pixels. */
    interface TapListener {
        void onTap(float x, float y);
    }

    /** One finger painting over the picture: where, and the brush's radius, in its pixels; {@code start} on touching down. */
    interface PaintListener {
        void onPaint(float x, float y, float radius, boolean start);
    }

    /** Drawn over the picture, in its pixels; {@code scale} screen pixels per picture pixel. */
    interface Overlay {
        void draw(Canvas c, float scale);
    }

    private final Matrix m = new Matrix();
    private final float[] v = new float[9];
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private float fit = 1f;
    private int bw, bh;
    private boolean keepView;
    private TapListener tap;
    private PaintListener painter;
    private float brushPx;
    private Overlay overlay;
    /** The gesture has had two fingers down (it zooms and moves, it doesn't paint); and the pinch's last centre. */
    private boolean multi;
    private float focusX, focusY;

    ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                focusX = d.getFocusX();
                focusY = d.getFocusY();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                // while painting, two fingers move the picture as they zoom it
                if (painter != null) m.postTranslate(d.getFocusX() - focusX, d.getFocusY() - focusY);
                focusX = d.getFocusX();
                focusY = d.getFocusY();
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                m.postTranslate(-dx, -dy);
                clamp();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tap == null) return false;
                float[] p = toPicture(e.getX(), e.getY());
                tap.onTap(p[0], p[1]);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float s = scale();
                if (s > fit * 1.5f) reset();
                else zoomBy(fit * 4f / s, e.getX(), e.getY());
                return true;
            }
        });
    }

    void setTapListener(TapListener l) {
        tap = l;
    }

    /** One finger paints with a brush {@code brushScreenPx} across on the screen (null: one finger moves the picture). */
    void setPainter(PaintListener l, float brushScreenPx) {
        painter = l;
        brushPx = brushScreenPx;
    }

    void setOverlay(Overlay o) {
        overlay = o;
        invalidate();
    }

    /** Screen pixels per picture pixel. */
    float zoomScale() {
        return scale();
    }

    /** A point on the view as a place on the picture (its pixels). */
    float[] toPicture(float x, float y) {
        Matrix inv = new Matrix();
        m.invert(inv);
        float[] p = {x, y};
        inv.mapPoints(p);
        return p;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (overlay == null || bw == 0) return;
        c.save();
        c.concat(m);
        overlay.draw(c, scale());
        c.restore();
    }

    /**
     * Shows a bitmap. With {@code keep} and the same size as before, the current zoom and position stay (for
     * re-rendered versions of the same picture).
     */
    void show(Bitmap b, boolean keep) {
        boolean same = b != null && b.getWidth() == bw && b.getHeight() == bh;
        keepView = keep && same;
        bw = b != null ? b.getWidth() : 0;
        bh = b != null ? b.getHeight() : 0;
        super.setImageBitmap(b);
        if (!keepView) reset();
        else setImageMatrix(m);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        reset();
    }

    private void reset() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || bw == 0 || bh == 0) return;
        fit = Math.min(w / (float) bw, h / (float) bh);
        m.reset();
        m.postScale(fit, fit);
        m.postTranslate((w - bw * fit) / 2f, (h - bh * fit) / 2f);
        setImageMatrix(m);
    }

    private float scale() {
        m.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    private void zoomBy(float f, float px, float py) {
        float s = scale();
        float target = Math.max(fit, Math.min(fit * 12f, s * f));
        f = target / s;
        m.postScale(f, f, px, py);
        clamp();
    }

    private void clamp() {
        RectF r = new RectF(0, 0, bw, bh);
        m.mapRect(r);
        int w = getWidth(), h = getHeight();
        float dx = 0, dy = 0;
        if (r.width() <= w) dx = (w - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < w) dx = w - r.right;
        if (r.height() <= h) dy = (h - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < h) dy = h - r.bottom;
        m.postTranslate(dx, dy);
        setImageMatrix(m);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) multi = false;
        if (e.getPointerCount() > 1) multi = true;
        if (painter != null) {
            if (!multi && (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE)) {
                float[] p = toPicture(e.getX(), e.getY());
                painter.onPaint(p[0], p[1], brushPx / 2f / scale(), a == MotionEvent.ACTION_DOWN);
            }
            scaler.onTouchEvent(e);
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        if (e.getPointerCount() > 1 || scale() > fit * 1.01f) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }
}
