package com.brandon.nightsky;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.util.Collections;
import java.util.concurrent.Executor;

/**
 * Camera2 plumbing for RAW long exposures: one RAW_SENSOR stream, fully manual requests (no auto exposure,
 * focus or white balance, focus at infinity, stabilisation and in-camera noise reduction off, lens shading
 * map reported), single captures queued by the caller. Every callback arrives on the given handler's thread.
 */
final class CameraRig {
    interface Listener {
        /** The capture session is ready for {@link #capture}. */
        void onReady();

        void onStarted(int seq, long timestampNs);

        /** A RAW image; it is closed as soon as this returns. */
        void onImage(Image image);

        void onResult(int seq, TotalCaptureResult result);

        void onFailed(int seq, boolean imageCaptured);

        void onBufferLost(int seq);

        /** The camera was lost or could not be set up; the rig is unusable. */
        void onCameraError(String what);
    }

    private final CameraManager manager;
    private final Handler handler;
    private final Listener listener;
    private final Executor executor;

    private CameraCharacteristics cc;
    private Size raw;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder builder;
    private long minFrameNs;
    private boolean closed;
    private int generation;

    CameraRig(Context c, Handler handler, Listener listener) {
        manager = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        this.handler = handler;
        this.listener = listener;
        executor = new Executor() {
            @Override
            public void execute(Runnable r) {
                CameraRig.this.handler.post(r);
            }
        };
    }

    /** The back camera with RAW and manual sensor control and the largest sensor, or null. */
    static String pickCamera(CameraManager m) throws CameraAccessException {
        String best = null;
        double bestArea = -1;
        for (String id : m.getCameraIdList()) {
            CameraCharacteristics c = m.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
            int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (!has(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) continue;
            if (!has(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) continue;
            if (rawSize(c) == null) continue;
            android.util.SizeF s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            double area = s == null ? 0 : s.getWidth() * s.getHeight();
            if (area > bestArea + 1e-6) {
                bestArea = area;
                best = id;
            }
        }
        return best;
    }

    /** Largest RAW_SENSOR output size (the camera's default, binned readout on quad-Bayer sensors). */
    static Size rawSize(CameraCharacteristics c) {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return null;
        Size[] sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR);
        if (sizes == null || sizes.length == 0) return null;
        Size best = sizes[0];
        for (Size s : sizes) if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        return best;
    }

    static boolean has(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    void open(String id, CameraCharacteristics c, Size rawSize) throws CameraAccessException {
        cc = c;
        raw = rawSize;
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        minFrameNs = map != null ? map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, rawSize) : 0;
        manager.openCamera(id, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice d) {
                if (closed) {
                    d.close();
                    return;
                }
                device = d;
                createSession();
            }

            @Override
            public void onDisconnected(CameraDevice d) {
                d.close();
                if (device == d) device = null;
                if (!closed) listener.onCameraError("the camera was taken by another app or the system");
            }

            @Override
            public void onError(CameraDevice d, int error) {
                d.close();
                if (device == d) device = null;
                if (!closed) listener.onCameraError("camera error " + error);
            }
        }, handler);
    }

    boolean ready() {
        return !closed && session != null && builder != null;
    }

    private void createSession() {
        if (closed || device == null) return;
        try {
            if (reader == null) {
                reader = ImageReader.newInstance(raw.getWidth(), raw.getHeight(), ImageFormat.RAW_SENSOR, 4);
                reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader r) {
                        Image img = null;
                        try {
                            img = r.acquireNextImage();
                            if (img != null && !closed) listener.onImage(img);
                        } catch (IllegalStateException ignored) {
                        } finally {
                            if (img != null) img.close();
                        }
                    }
                }, handler);
            }
            final int gen = ++generation;
            OutputConfiguration oc = new OutputConfiguration(reader.getSurface());
            SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    Collections.singletonList(oc), executor, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (closed || gen != generation) {
                        s.close();
                        return;
                    }
                    session = s;
                    try {
                        buildRequest();
                    } catch (CameraAccessException | RuntimeException e) {
                        listener.onCameraError("could not set up the capture: " + e.getMessage());
                        return;
                    }
                    listener.onReady();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    if (!closed && gen == generation) listener.onCameraError("the camera refused the RAW stream");
                }
            });
            device.createCaptureSession(sc);
        } catch (CameraAccessException | RuntimeException e) {
            listener.onCameraError("could not start the camera: " + e.getMessage());
        }
    }

    private void buildRequest() throws CameraAccessException {
        int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean manual = has(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
        CaptureRequest.Builder b = device.createCaptureRequest(manual ? CameraDevice.TEMPLATE_MANUAL : CameraDevice.TEMPLATE_STILL_CAPTURE);
        b.addTarget(reader.getSurface());
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
        b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        Float minFocus = cc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (minFocus != null && minFocus > 0) b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f); // infinity
        if (has(cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION), CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        }
        if (has(cc.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES), CaptureRequest.NOISE_REDUCTION_MODE_OFF)) {
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
        }
        if (has(cc.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES), CaptureRequest.EDGE_MODE_OFF)) {
            b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
        }
        if (has(cc.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES), CaptureRequest.HOT_PIXEL_MODE_OFF)) {
            b.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF);
        }
        if (has(cc.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES), CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)) {
            b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF);
        }
        if (has(cc.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES), CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)) {
            b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
        }
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        Range<Float> zr = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (zr != null && zr.contains(1f)) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f);
        builder = b;
    }

    /** Queues one exposure; false if the camera is not ready. */
    boolean capture(int seq, long exposureNs, int iso) {
        if (!ready()) return false;
        try {
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs);
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(exposureNs, minFrameNs));
            builder.setTag(seq);
            final int gen = generation;
            session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(CameraCaptureSession s, CaptureRequest r, long timestamp, long frameNumber) {
                    if (gen == generation) listener.onStarted(tag(r), timestamp);
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                    listener.onResult(tag(r), result);
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                    listener.onFailed(tag(r), failure.wasImageCaptured());
                }

                @Override
                public void onCaptureBufferLost(CameraCaptureSession s, CaptureRequest r, Surface target, long frameNumber) {
                    listener.onBufferLost(tag(r));
                }
            }, handler);
            return true;
        } catch (CameraAccessException | RuntimeException e) {
            return false;
        }
    }

    private static int tag(CaptureRequest r) {
        Object t = r.getTag();
        return t instanceof Integer ? (Integer) t : -1;
    }

    /** Drops queued and running exposures as fast as the camera allows. */
    void abort() {
        if (session == null) return;
        try {
            session.abortCaptures();
        } catch (CameraAccessException | RuntimeException ignored) {
        }
    }

    /** Throws the capture session away and builds a new one (the cure for a stalled camera); onReady follows. */
    void restartSession() {
        if (closed) return;
        CameraCaptureSession s = session;
        session = null;
        builder = null;
        if (s != null) {
            try {
                s.close();
            } catch (RuntimeException ignored) {
            }
        }
        createSession();
    }

    void close() {
        closed = true;
        generation++;
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
            }
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }
}
