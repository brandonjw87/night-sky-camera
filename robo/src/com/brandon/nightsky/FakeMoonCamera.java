package com.brandon.nightsky;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

/**
 * The Moonshot's camera (3.0) with no Camera2 under it: {@link MoonCamera#open} picks the lens from the cameras the
 * test gave Android ({@link MoonCamera#choose}, for real) and sets up as it would ({@link MoonCamera#setUp}, the
 * thread, the tilt sensor), then the session is "up" and the test's simulated telephoto ({@link SimTele}) feeds
 * {@link MoonCamera#frame} with its RAW frames and their capture results. Everything after the frame (finding the
 * Moon, metering, the focus sweep, the bursts, closing) is the app's own code.
 */
@Implements(MoonCamera.class)
public class FakeMoonCamera {
    @RealObject
    MoonCamera real;
    /** The camera the screen opened last, and how many times one was opened. */
    static volatile MoonCamera opened;
    static volatile int opens;

    @Implementation
    protected String open(SurfaceTexture st) {
        CameraManager cm = (CameraManager) RuntimeEnvironment.getApplication().getSystemService(Context.CAMERA_SERVICE);
        try {
            real.lens = MoonCamera.choose(cm);
        } catch (CameraAccessException e) {
            return "Could not open the camera: " + e.getMessage();
        }
        if (real.lens == null) return "This phone has no back camera that gives RAW pictures.";
        MoonCamera.Lens l = real.lens;
        real.setUp(l.cc, l.raw, l.openId + (l.physicalId != null ? "/" + l.physicalId : ""));
        real.startThread();
        real.startTilt();
        final MoonCamera m = real;
        // the session configured, as onConfigured does it
        real.h.post(new Runnable() {
            @Override
            public void run() {
                if (m.closed) return;
                m.stage = MoonCamera.Stage.SEARCHING;
                m.apply();
            }
        });
        opens++;
        opened = real;
        return null;
    }
}
