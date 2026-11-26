package com.brandon.nightsky;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

/**
 * The Sky Window's camera (3.3) with no Camera2 under it: {@link SkyCamera#open} picks the camera from those the test
 * gave Android ({@link CameraRig#pickCamera}, for real) and sets up as it would ({@link SkyCamera#setUp}, its threads);
 * then the test's simulated sky feeds {@link SkyCamera#frame} with RAW frames and their capture results. Everything after
 * the frame (the meter, the night picture, the star lock, closing) is the app's own code.
 */
@Implements(SkyCamera.class)
public class FakeSkyCamera {
    @RealObject
    SkyCamera real;
    /** The camera the window opened last, and how many times one was opened. */
    static volatile SkyCamera opened;
    static volatile int opens;

    @Implementation
    protected String open(SurfaceTexture st) {
        CameraManager cm = (CameraManager) RuntimeEnvironment.getApplication().getSystemService(Context.CAMERA_SERVICE);
        try {
            String id = CameraRig.pickCamera(cm);
            if (id == null) return "This phone has no back camera that gives RAW pictures.";
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            real.setUp(id, c, CameraRig.rawSize(c));
        } catch (CameraAccessException e) {
            return "Could not open the camera: " + e.getMessage();
        }
        real.startThreads();
        opens++;
        opened = real;
        return null;
    }
}
