package com.brandon.nightsky;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import com.brandon.nightsky.processing.ReportCard;

/**
 * The report's frames as a strip of bars, one per frame in time order: the height is the frame's score, bright
 * red for a good frame, dim for one under 50, an outline for one left out. Touching a bar picks that frame.
 */
final class FrameStrip extends View {
    interface Listener {
        void onFrame(ReportCard.Frame f);
    }

    private ReportCard.Frame[] frames = new ReportCard.Frame[0];
    private int selected = -1;
    private Listener listener;
    private final Paint good = new Paint(), weak = new Paint(), out = new Paint(), sel = new Paint(), base = new Paint();

    FrameStrip(Context c) {
        super(c);
        good.setColor(MainActivity.RED);
        weak.setColor(MainActivity.RED_DIM);
        out.setColor(MainActivity.RED_DIM);
        out.setStyle(Paint.Style.STROKE);
        out.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density));
        sel.setColor(MainActivity.RED_SEL);
        base.setColor(MainActivity.RED_DARK);
    }

    void setFrames(ReportCard.Frame[] f, Listener l) {
        frames = f != null ? f : new ReportCard.Frame[0];
        listener = l;
        selected = -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = frames.length;
        if (n == 0) return;
        float w = getWidth(), h = getHeight();
        float slot = w / n, gap = slot > 6 ? Math.max(1f, slot * 0.15f) : 0f;
        c.drawRect(0, h - 2, w, h, base);
        for (int i = 0; i < n; i++) {
            ReportCard.Frame f = frames[i];
            float x0 = i * slot + gap / 2, x1 = (i + 1) * slot - gap / 2;
            if (i == selected) c.drawRect(i * slot, 0, (i + 1) * slot, h, sel);
            if (!f.used) {
                float top = h * 0.75f;
                c.drawRect(x0 + 1, top, x1 - 1, h - 1, out);
                continue;
            }
            float top = h - Math.max(3f, h * f.score / 100f);
            c.drawRect(x0, top, x1, h, f.score >= 50 ? good : weak);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int n = frames.length;
        if (n == 0) return false;
        if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
            int i = (int) (e.getX() / (getWidth() / (float) n));
            i = Math.max(0, Math.min(n - 1, i));
            if (i != selected) {
                selected = i;
                invalidate();
                if (listener != null) listener.onFrame(frames[i]);
            }
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        return e.getAction() == MotionEvent.ACTION_UP || super.onTouchEvent(e);
    }
}
