package com.brandon.nightsky;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.util.Size;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

/**
 * The camera, simulated: {@link CameraRig} as the session controller drives it, with {@link SimCamera} taking the
 * exposures (a simulated night sky) instead of Camera2. Callbacks come on the rig's handler, as the real ones do.
 */
@Implements(CameraRig.class)
public class FakeRig {
    @RealObject
    CameraRig real;
    Handler handler;
    CameraRig.Listener listener;
    volatile boolean opened, closed, sessionUp;
    volatile float focus;
    volatile int generation;
    /** Captures asked of this capture session, results it gave, and captures since the camera was opened. */
    volatile int sessionCaptures, sessionResults, openCaptures;

    @Implementation
    protected static String pickCamera(CameraManager m) {
        return SimCamera.get() != null && SimCamera.get().present ? "0" : null;
    }

    @Implementation
    protected static Size rawSize(CameraCharacteristics c) {
        SimCamera s = SimCamera.get();
        return s == null ? null : new Size(s.W, s.H);
    }

    private void wire() {
        if (handler != null) return;
        try {
            java.lang.reflect.Field h = CameraRig.class.getDeclaredField("handler");
            h.setAccessible(true);
            handler = (Handler) h.get(real);
            java.lang.reflect.Field l = CameraRig.class.getDeclaredField("listener");
            l.setAccessible(true);
            listener = (CameraRig.Listener) l.get(real);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Implementation
    protected void open(String id, CameraCharacteristics c, Size rawSize) {
        wire();
        opened = true;
        sessionCaptures = 0;
        sessionResults = 0;
        SimCamera.get().opened(this);
        sessionUp(250);
    }

    void sessionUp(long ms) {
        final int g = generation;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (closed || g != generation) return;
                SimCamera s = SimCamera.get();
                if (s.failOpen != null) {
                    String w = s.failOpen;
                    if (s.failOpenAgain > 0) s.failOpenAgain--;
                    else s.failOpen = null;
                    listener.onCameraError(w);
                    return;
                }
                sessionUp = true;
                listener.onReady();
            }
        }, ms);
    }

    @Implementation
    protected boolean ready() {
        return opened && !closed && sessionUp;
    }

    @Implementation
    protected void setFocus(float d) {
        focus = Math.max(0f, Math.min(SimCamera.MIN_FOCUS, d));
    }

    @Implementation
    protected float focus() {
        return focus;
    }

    @Implementation
    protected boolean capture(int seq, long exposureNs, int iso) {
        if (!ready()) return false;
        sessionCaptures++;
        openCaptures++;
        SimCamera.get().capture(this, seq, exposureNs, iso, focus, generation, sessionCaptures, openCaptures);
        return true;
    }

    @Implementation
    protected int sessionCaptures() {
        return sessionCaptures;
    }

    @Implementation
    protected int sessionResults() {
        return sessionResults;
    }

    @Implementation
    protected void abort() {
        if (!sessionUp) return;
        SimCamera.get().abort(this);
    }

    @Implementation
    protected void restartSession() {
        if (closed) return;
        sessionUp = false;
        generation++;
        sessionCaptures = 0;
        sessionResults = 0;
        SimCamera.get().dropAll(this);
        SimCamera.get().note("a new capture session");
        sessionUp(300);
    }

    @Implementation
    protected void close() {
        closed = true;
        sessionUp = false;
        generation++;
        SimCamera.get().dropAll(this);
    }
}
