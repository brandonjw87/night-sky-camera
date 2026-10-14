package com.brandon.nightsky;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Image view with pinch zoom, drag and double-tap zoom, for looking at stars up close. */
final class ZoomImageView extends ImageView {
    private final Matrix m = new Matrix();
    private final float[] v = new float[9];
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private float fit = 1f;
    private int bw, bh;
    private boolean keepView;

    ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
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
            public boolean onDoubleTap(MotionEvent e) {
                float s = scale();
                if (s > fit * 1.5f) reset();
                else zoomBy(fit * 4f / s, e.getX(), e.getY());
                return true;
            }
        });
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
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        if (e.getPointerCount() > 1 || scale() > fit * 1.01f) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }
}
