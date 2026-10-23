package com.brandon.nightsky;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.brandon.nightsky.processing.FocusModel;
import com.brandon.nightsky.processing.FocusSweep;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.Tenengrad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * The daylight focus calibration (F7). The main camera shows its picture while Brandon points it at something
 * far away; Calibrate then steps the focus from the lens's infinity end towards near, scoring each setting by
 * the Tenengrad sharpness of the middle third of a full-size picture (about the sensor's own pixels: a
 * downscaled preview hides focus errors), and fits the sharpest. That focus is saved with the battery's
 * temperature in the camera's {@link FocusModel}; three of them spanning 5 C or more give the line that centres
 * the night's sweep on the stars. The requests are set up as the night's are (manual focus, stabilisation,
 * noise reduction and sharpening off) with the exposure locked once the sweep starts.
 * <p>
 * Camera callbacks and the scoring run on a thread of their own; the listener hears on the main thread.
 */
final class FocusCalibrator {
    interface Listener {
        /** What it is doing (main thread). */
        void onState(String headline, String detail);

        /** The calibration is over: saved if {@code ok}; the camera stays open for another (main thread). */
        void onDone(boolean ok, String text);

        /** The camera couldn't be used (main thread). */
        void onError(String text);
    }

    /** Frames scored at each focus setting once the lens is still there. */
    static final int FRAMES = 4;
    /** The sharpest setting must beat the blurriest by this much, else the scene has too little detail. */
    static final double MIN_CONTRAST = 1.15;
    /** Side of the scored middle, as a share of the picture's (more of a smaller picture, if the full size is refused). */
    static final double FRAC = 1.0 / 3, FRAC_SMALL = 0.5;
    /** Longest wait for the lens to settle at a setting before its frames count anyway (ms). */
    static final long SETTLE_MS = 1500;
    /** Longest a whole sweep may take (ms). */
    static final long MAX_MS = 60_000;

    private final Context ctx;
    private final Prefs prefs;
    private final Handler main;
    private final Listener listener;
    private HandlerThread thread;
    private Handler h;
    private Executor executor;
    private volatile boolean closed;

    private String cameraId;
    private CameraCharacteristics cc;
    private FocusSweep.Lens lens;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface preview;
    private CaptureRequest.Builder builder;
    private StreamConfigurationMap map;
    /** The scored picture is smaller than the sensor's own pixels (the camera refused the full size with a preview). */
    private boolean small;

    // the sweep (camera thread)
    private FocusSweep sweep;
    private float target;
    private long targetMs, startMs, settledTs = Long.MAX_VALUE;
    private float settledAt = Float.NaN;
    private final double[] scores = new double[FRAMES];
    private int scored, step;
    private final List<Double> all = new ArrayList<Double>();

    FocusCalibrator(Context c, Prefs prefs, Handler main, Listener l) {
        ctx = c.getApplicationContext();
        this.prefs = prefs;
        this.main = main;
        listener = l;
    }

    /** The camera whose focus is calibrated (the one sessions use), or null. */
    static String cameraOf(Context c) {
        try {
            return CameraRig.pickCamera((CameraManager) c.getSystemService(Context.CAMERA_SERVICE));
        } catch (CameraAccessException | RuntimeException e) {
            return null;
        }
    }

    /** Opens the camera and shows its picture on {@code st}. Null if it is opening, else why it can't. */
    String start(SurfaceTexture st) {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = CameraRig.pickCamera(cm);
            if (cameraId == null) return "This phone has no back camera with RAW support.";
            cc = cm.getCameraCharacteristics(cameraId);
            Size raw = CameraRig.rawSize(cc);
            SensorSpec spec = SensorSpecFactory.build(cc, cameraId, raw, SensorSpecFactory.geometry(cc, raw));
            lens = SessionController.focusLens(cc, spec);
            if (lens == null) return "This camera's focus is fixed: there is nothing to calibrate.";
            map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return "The camera describes no picture sizes.";
            Size ys = largest43(map.getOutputSizes(ImageFormat.YUV_420_888), raw.getWidth() + 64);
            Size ps = largest43(map.getOutputSizes(SurfaceTexture.class), 1440);
            if (ys == null || ps == null) return "The camera has no suitable picture size.";
            st.setDefaultBufferSize(ps.getWidth(), ps.getHeight());
            preview = new Surface(st);
            thread = new HandlerThread("focus-calibration");
            thread.start();
            h = new Handler(thread.getLooper());
            executor = new Executor() {
                @Override
                public void execute(Runnable r) {
                    h.post(r);
                }
            };
            newReader(ys);
            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
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
                    error("The camera was taken by another app.");
                }

                @Override
                public void onError(CameraDevice d, int e) {
                    d.close();
                    if (device == d) device = null;
                    error("The camera stopped (error " + e + ").");
                }
            }, h);
            return null;
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            stop();
            return "Could not open the camera: " + e.getMessage();
        }
    }

    private void newReader(Size ys) {
        if (reader != null) reader.close();
        reader = ImageReader.newInstance(ys.getWidth(), ys.getHeight(), ImageFormat.YUV_420_888, 3);
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img != null && !closed) image(img);
                } catch (IllegalStateException ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }
        }, h);
    }

    /** The largest size about 4:3 no wider than {@code maxW} (the binned sensor's own pixels), else the largest. */
    private static Size largest43(Size[] sizes, int maxW) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null, any = null;
        for (Size s : sizes) {
            long a = (long) s.getWidth() * s.getHeight();
            if (any == null || a > (long) any.getWidth() * any.getHeight()) any = s;
            if (Math.abs(s.getWidth() * 3 - s.getHeight() * 4) > s.getHeight() / 20 || s.getWidth() > maxW) continue;
            if (best == null || a > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best != null ? best : any;
    }

    private void createSession() {
        if (closed || device == null) return;
        try {
            List<OutputConfiguration> outs = Arrays.asList(new OutputConfiguration(preview), new OutputConfiguration(reader.getSurface()));
            device.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            if (closed) {
                                s.close();
                                return;
                            }
                            session = s;
                            try {
                                buildRequest();
                                repeat();
                            } catch (CameraAccessException | RuntimeException e) {
                                error("Could not start the camera's picture: " + e.getMessage());
                                return;
                            }
                            state("Point it at something far away", "Then tap Calibrate and keep the phone still for about 15 seconds.");
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            Size half = largest43(map.getOutputSizes(ImageFormat.YUV_420_888), 2112);
                            if (!small && half != null && half.getWidth() < reader.getWidth()) {
                                // a full-size picture with the preview is too much for this camera: a smaller one, more of it scored
                                small = true;
                                android.util.Log.i("NightSky", "focus calibration: full-size frames refused; using " + half);
                                newReader(half);
                                createSession();
                                return;
                            }
                            error("The camera refused the picture streams.");
                        }
                    }));
        } catch (CameraAccessException | RuntimeException e) {
            error("Could not start the camera: " + e.getMessage());
        }
    }

    /** The preview, set up as the night's frames are: manual focus, no stabilisation, sharpening or noise reduction. */
    private void buildRequest() throws CameraAccessException {
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(preview);
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, startFocus());
        if (CameraRig.has(cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION), CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        }
        if (CameraRig.has(cc.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES), CaptureRequest.NOISE_REDUCTION_MODE_OFF)) {
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
        }
        if (CameraRig.has(cc.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES), CaptureRequest.EDGE_MODE_OFF)) {
            b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
        }
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        Range<Float> zr = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (zr != null && zr.contains(1f)) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f);
        builder = b;
    }

    /** Where the preview focuses before the sweep: the line's prediction for now, else the night's focus, else infinity. */
    private float startFocus() {
        FocusModel m = FocusModel.parse(prefs.focusModel(cameraId));
        FocusModel.Line l = m.line();
        double t = BatteryInfo.read(ctx).tempC;
        if (l != null && !Double.isNaN(t)) return (float) Math.max(0, l.predict(t));
        FocusModel.Cal last = m.lastNight();
        return last != null ? (float) last.focus : 0f;
    }

    private void repeat() throws CameraAccessException {
        session.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult r) {
                if (!closed) result(req, r);
            }
        }, h);
    }

    /** Starts a sweep (if the picture is showing and none is running). */
    void calibrate() {
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (closed || session == null || sweep != null) return;
                sweep = FocusSweep.full(lens, FocusSweep.Kind.DAYLIGHT, "daylight calibration");
                all.clear();
                step = 0;
                startMs = SystemClock.elapsedRealtime();
                // the exposure stays put from here on (the score allows for small changes anyway)
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true);
                builder.addTarget(reader.getSurface());
                moveTo(sweep.next());
            }
        });
    }

    private void moveTo(double f) {
        target = (float) f;
        scored = 0;
        settledTs = Long.MAX_VALUE;
        settledAt = Float.NaN;
        targetMs = SystemClock.elapsedRealtime();
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, target);
        try {
            repeat();
        } catch (CameraAccessException | RuntimeException e) {
            endSweep();
            done(false, "The camera stopped during the calibration: " + e.getMessage());
            return;
        }
        step++;
        state("Calibrating: keep the phone still", String.format(Locale.US, "Step %d of about %d: focus %.2f", step,
                step + Math.max(0, sweep.planned() - 1), f));
    }

    /** A frame's result: once one shows the lens still at the setting, frames from it on are scored. */
    private void result(CaptureRequest req, TotalCaptureResult r) {
        if (sweep == null || settledTs != Long.MAX_VALUE) return;
        Float asked = req.get(CaptureRequest.LENS_FOCUS_DISTANCE);
        if (asked == null || Math.abs(asked - target) > 1e-6) return;
        Float at = r.get(CaptureResult.LENS_FOCUS_DISTANCE);
        Integer ls = r.get(CaptureResult.LENS_STATE);
        boolean still = ls == null || ls == CaptureResult.LENS_STATE_STATIONARY;
        boolean there = at == null || Math.abs(at - target) <= 0.25 * lens.fine;
        long now = SystemClock.elapsedRealtime();
        if (still && there || now - targetMs > SETTLE_MS) {
            Long ts = r.get(CaptureResult.SENSOR_TIMESTAMP);
            settledTs = ts != null ? ts : 0;
            settledAt = at != null ? at : target;
        }
        if (now - startMs > MAX_MS) {
            endSweep();
            done(false, "The calibration took too long: the camera kept the frames slow. Try again in brighter light.");
        }
    }

    private void image(Image img) {
        if (sweep == null || img.getTimestamp() < settledTs || scored >= FRAMES) return;
        Image.Plane y = img.getPlanes()[0];
        scores[scored++] = Tenengrad.score(y.getBuffer(), img.getWidth(), img.getHeight(), y.getRowStride(), y.getPixelStride(),
                small ? FRAC_SMALL : FRAC);
        if (scored < FRAMES) return;
        double mean = 0;
        for (double s : scores) mean += s;
        mean /= FRAMES;
        double var = 0;
        for (double s : scores) var += (s - mean) * (s - mean);
        double se = Math.sqrt(var / (FRAMES - 1) / FRAMES);
        all.add(mean);
        android.util.Log.i("NightSky", String.format(Locale.US, "focus calibration: %.3f (at %.3f): sharpness %.4g +/- %.2g", target, settledAt,
                mean, se));
        sweep.add(settledAt, mean, se, 0, mean > 0);
        if (sweep.done()) finish();
        else moveTo(sweep.next());
    }

    /** The sweep is over: the sharpest focus, checked for enough detail and a far enough target, saved. */
    private void finish() {
        FocusSweep s = sweep;
        FocusSweep.Result r = s.result();
        endSweep();
        double hi = 0, lo = Double.MAX_VALUE;
        for (double v : all) {
            if (!(v > 0)) continue;
            hi = Math.max(hi, v);
            lo = Math.min(lo, v);
        }
        double contrast = lo > 0 && lo < Double.MAX_VALUE ? hi / lo : 0;
        android.util.Log.i("NightSky", "focus calibration: " + s.pointsLine() + "; " + r.how);
        if (!r.ok) {
            done(false, "Nothing to focus on: " + r.how + ". Point it at something with sharp edges, such as a distant building or a ridge.");
            return;
        }
        if (contrast < MIN_CONTRAST) {
            done(false, String.format(Locale.US, "The picture hardly changed with the focus (x%.2f): point it at something far away with "
                    + "sharp edges, not the sky or a plain wall, and try again.", contrast));
            return;
        }
        if (r.focus > lens.max - lens.fine) {
            done(false, String.format(Locale.US, "Sharpest at %.2f, the near end of the sweep: the target must be farther away (500 m or more).",
                    r.focus));
            return;
        }
        double t = BatteryInfo.read(ctx).tempC;
        FocusModel m = FocusModel.parse(prefs.focusModel(cameraId));
        FocusModel.Cal c = new FocusModel.Cal();
        c.focus = r.focus;
        c.tempC = t;
        c.timeMs = System.currentTimeMillis();
        c.contrast = contrast;
        m.addDay(c);
        prefs.setFocusModel(cameraId, m.encode());
        // the preview shows the focus found
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, (float) r.focus);
        try {
            repeat();
        } catch (CameraAccessException | RuntimeException ignored) {
        }
        done(true, String.format(Locale.US, "Sharpest at %.3f%s with the phone at %s (%d steps, sharpness x%.1f from blurriest to sharpest). ",
                r.focus, Double.isNaN(r.sigma) ? "" : String.format(Locale.US, " (+/-%.3f)", r.sigma),
                Double.isNaN(t) ? "an unknown temperature" : String.format(Locale.US, "%.0f C", t), r.frames, contrast) + m.describe(t));
    }

    /** Back to the plain preview: the frames no longer scored, the exposure free again. */
    private void endSweep() {
        sweep = null;
        if (builder == null) return;
        builder.removeTarget(reader.getSurface());
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        if (session != null && !closed) {
            try {
                repeat();
            } catch (CameraAccessException | RuntimeException ignored) {
            }
        }
    }

    void stop() {
        closed = true;
        final Handler hh = h;
        if (hh == null) {
            releaseNow();
            return;
        }
        hh.post(new Runnable() {
            @Override
            public void run() {
                releaseNow();
            }
        });
    }

    private void releaseNow() {
        sweep = null;
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
        if (preview != null) {
            preview.release();
            preview = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    private void state(final String head, final String detail) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!closed) listener.onState(head, detail);
            }
        });
    }

    private void done(final boolean ok, final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!closed) listener.onDone(ok, text);
            }
        });
    }

    private void error(final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!closed) listener.onError(text);
            }
        });
    }
}
