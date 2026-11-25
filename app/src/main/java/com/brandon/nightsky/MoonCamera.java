package com.brandon.nightsky;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
import android.os.Looper;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import com.brandon.nightsky.processing.FocusSweep;
import com.brandon.nightsky.processing.MoonFinder;
import com.brandon.nightsky.processing.MoonGeo;
import com.brandon.nightsky.processing.MoonLook;
import com.brandon.nightsky.processing.MoonMeter;
import com.brandon.nightsky.processing.MoonStack;
import com.brandon.nightsky.processing.SensorSpec;

import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The Moonshot's camera (3.0): the Pixel's 5x telephoto, its RAW frames at up to 30 a second, and what each frame
 * does on the way in. It finds the Moon in the whole frame, then follows it; sets the exposure on the Moon's bright
 * parts ({@link MoonMeter}); focuses by stepping the lens and keeping the sharpest ({@link FocusSweep} scoring
 * {@link MoonStack#sharpness}); and, for a burst, copies the few hundred pixels around the Moon out of every frame
 * with the phone's tilt (from the gravity sensor) at that moment, for {@link MoonStack}.
 * <p>
 * The telephoto: a camera of its own if Android lists one, else the longest lens inside the main (logical) camera
 * opened as a physical camera, else the main camera zoomed to 5x. Camera callbacks and every frame's work run on a
 * thread of their own; the listener hears on the main thread. The robots replace {@link #open} and {@link #close}
 * and feed {@link #frame} themselves.
 */
final class MoonCamera {
    interface Listener {
        /** About ten times a second while the picture shows (main thread). */
        void onLive(Live live);

        /** A burst is in (main thread). */
        void onBurst(Burst b);

        /** It can't go on (main thread). */
        void onProblem(String why);
    }

    enum Stage { OPENING, SEARCHING, METERING, FOCUSING, READY, SHOOTING }

    /** What the camera sees now. */
    static final class Live {
        Stage stage = Stage.OPENING;
        /** The Moon in the frame (frame px), and the frame's size. */
        MoonFinder.Spot spot;
        int frameW, frameH;
        /** The close-up of the Moon (ARGB, w x h) for the screen. */
        int[] closeUp;
        int closeW, closeH;
        String exposure = "", focus = "", lens = "", words = "";
        double fps;
        int got, want;
    }

    /** A burst: the frames' crops and what the stack needs to know. */
    static final class Burst {
        final List<MoonStack.Frame> frames = new ArrayList<MoonStack.Frame>();
        SensorSpec spec;
        double arcsecPerPx, rExp;
        long startMs;
        String lens = "", exposure = "";
        double fps;
        float focus;
        long exposureNs;
        int iso;
    }

    /** A lens fit for the Moon, and how to open it. */
    static final class Lens {
        String openId, physicalId;
        CameraCharacteristics cc;
        Size raw;
        double focalMm, eqMm;
        /** The main camera zoomed (no telephoto of its own could be opened). */
        boolean zoomed;

        String describe() {
            return String.format(Locale.US, "%s (%.0f mm, like a %.0f mm lens)", eqMm >= 80 ? "the telephoto" : "the main camera", focalMm, eqMm);
        }
    }

    /** Frames in a burst, and the stage's frame rate. */
    static final int BURST = 150, FPS = 30;
    /** Focus frames scored at each setting once the lens is still there. */
    static final int FOCUS_FRAMES = 3;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    HandlerThread thread;
    Handler h;
    volatile boolean closed;

    // the camera (camera thread)
    Lens lens;
    SensorSpec spec;
    SensorSpecFactory.Geometry geo;
    CameraDevice device;
    CameraCaptureSession session;
    ImageReader reader;
    Surface preview;
    CaptureRequest.Builder builder;
    MoonMeter.Limits limits = new MoonMeter.Limits();
    FocusSweep.Lens focusLens;
    long minFrameNs;
    /** Each frame's settings, by its timestamp, from the capture results. */
    final Map<Long, long[]> results = Collections.synchronizedMap(new LinkedHashMap<Long, long[]>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, long[]> e) {
            return size() > 64;
        }
    });

    // the frames (camera thread)
    Stage stage = Stage.OPENING;
    MoonFinder.Spot spot;
    MoonMeter.Exposure exposure;
    float focus = 0f;
    FocusSweep sweep;
    final List<Double> focusScores = new ArrayList<Double>();
    long focusAskedNs;
    int lost, frameNo;
    double rExp = Double.NaN, arcsecPerPx = Double.NaN;
    Burst burst;
    int burstWant;
    long lastLiveMs, fpsT0;
    int fpsN;
    double fps;
    /** The Moon tonight, here: for the first exposure, the expected size and the sky's up. */
    MoonGeo.View moon;

    // the phone's tilt
    private SensorManager sensors;
    final float[] gravity = {0, 9.8f, 0};
    private final SensorEventListener tilt = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            synchronized (gravity) {
                gravity[0] = e.values[0];
                gravity[1] = e.values[1];
                gravity[2] = e.values[2];
            }
        }

        @Override
        public void onAccuracyChanged(Sensor s, int a) {}
    };

    MoonCamera(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
    }

    /** The Moon as seen from here now (null: nowhere known), for the exposure's first guess and the picture's up. */
    void setMoon(MoonGeo.View v) {
        moon = v;
    }

    // ------------------------------------------------------------------------------------------------
    // choosing the lens

    /** The longest back lens that gives RAW frames, as a camera of its own or inside the main one; else the main camera zoomed. */
    static Lens choose(CameraManager cm) throws CameraAccessException {
        Lens best = null, zoomable = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
            int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            boolean logical = CameraRig.has(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
            if (logical) {
                for (String pid : cc.getPhysicalCameraIds()) {
                    try {
                        Lens l = consider(id, pid, cm.getCameraCharacteristics(pid));
                        if (l != null && (best == null || l.eqMm > best.eqMm + 1)) best = l;
                    } catch (IllegalArgumentException | CameraAccessException e) {
                        // a hidden camera that won't describe itself
                    }
                }
                Range<Float> zr = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zr != null && zr.getUpper() >= 4.5f && CameraRig.has(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                    Lens z = consider(id, null, cc);
                    if (z != null) {
                        z.zoomed = true;
                        if (zoomable == null) zoomable = z;
                    }
                }
            } else {
                Lens l = consider(id, null, cc);
                if (l != null && (best == null || l.eqMm > best.eqMm + 1 || (l.eqMm > best.eqMm - 1 && best.physicalId != null))) best = l;
            }
        }
        if (best != null && best.eqMm >= 80) return best;
        if (zoomable != null) return zoomable;
        return best;
    }

    /** A camera with RAW frames and its lens's reach, or null. */
    static Lens consider(String openId, String physicalId, CameraCharacteristics cc) {
        int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (!CameraRig.has(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return null;
        if (!CameraRig.has(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) return null;
        Size raw = CameraRig.rawSize(cc);
        if (raw == null) return null;
        float[] fl = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF phys = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (fl == null || fl.length == 0 || phys == null) return null;
        double f = 0;
        for (float x : fl) f = Math.max(f, x);
        double diag = Math.hypot(phys.getWidth(), phys.getHeight());
        Lens l = new Lens();
        l.openId = openId;
        l.physicalId = physicalId;
        l.cc = cc;
        l.raw = raw;
        l.focalMm = f;
        l.eqMm = diag > 0 ? f * 43.27 / diag : f;
        return l;
    }

    // ------------------------------------------------------------------------------------------------
    // opening (replaced by the robots)

    /** Opens the telephoto and shows its picture on {@code st}. Null if it is opening, else why it can't. */
    String open(SurfaceTexture st) {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            lens = choose(cm);
            if (lens == null) return "This phone has no back camera that gives RAW pictures.";
            setUp(lens.cc, lens.raw, lens.openId + (lens.physicalId != null ? "/" + lens.physicalId : ""));
            StreamConfigurationMap map = lens.cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size ps = new Size(1440, 1080);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                if (sizes != null) {
                    Size b = null;
                    for (Size s : sizes) {
                        if (Math.abs(s.getWidth() * 3 - s.getHeight() * 4) > s.getHeight() / 20 || s.getWidth() > 1920) continue;
                        if (b == null || s.getWidth() > b.getWidth()) b = s;
                    }
                    if (b != null) ps = b;
                }
                minFrameNs = map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, lens.raw);
            }
            st.setDefaultBufferSize(ps.getWidth(), ps.getHeight());
            preview = new Surface(st);
            startThread();
            reader = ImageReader.newInstance(lens.raw.getWidth(), lens.raw.getHeight(), ImageFormat.RAW_SENSOR, 8);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    while (!closed) {
                        Image img;
                        try {
                            img = r.acquireNextImage();
                        } catch (IllegalStateException e) {
                            return;
                        }
                        if (img == null) return;
                        try {
                            Image.Plane pl = img.getPlanes()[0];
                            ShortBuffer sb = pl.getBuffer().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                            frame(sb, pl.getRowStride() / 2, img.getWidth(), img.getHeight(), img.getTimestamp());
                        } catch (RuntimeException e) {
                            android.util.Log.w("NightSky", "moonshot frame", e);
                        } finally {
                            img.close();
                        }
                    }
                }
            }, h);
            startTilt();
            cm.openCamera(lens.openId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    if (closed) {
                        d.close();
                        return;
                    }
                    device = d;
                    createSession(true);
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    if (device == d) device = null;
                    problem("The camera was taken by another app.");
                }

                @Override
                public void onError(CameraDevice d, int e) {
                    d.close();
                    if (device == d) device = null;
                    problem("The camera stopped (error " + e + ").");
                }
            }, h);
            return null;
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            close();
            return "Could not open the camera: " + e.getMessage();
        }
    }

    void startThread() {
        if (thread != null) return;
        thread = new HandlerThread("moonshot-camera");
        thread.start();
        h = new Handler(thread.getLooper());
    }

    void startTilt() {
        sensors = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        if (sensors == null) return;
        Sensor g = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (g == null) g = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (g != null) sensors.registerListener(tilt, g, SensorManager.SENSOR_DELAY_GAME, h);
    }

    /** What the camera is, for the frames: the sensor's description, its limits, the pixel scale and the focus range. */
    void setUp(CameraCharacteristics cc, Size raw, String id) {
        geo = SensorSpecFactory.geometry(cc, raw);
        spec = SensorSpecFactory.build(cc, id, raw, geo);
        Range<Long> er = cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> ir = cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (er != null) {
            limits.minNs = er.getLower();
            limits.maxNs = er.getUpper();
        }
        if (ir != null) {
            limits.minIso = ir.getLower();
            limits.maxIso = ir.getUpper();
        }
        Integer maxAnalog = cc.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
        if (maxAnalog != null && maxAnalog > limits.minIso) limits.maxIso = Math.min(limits.maxIso, Math.max(maxAnalog, 1600));
        focusLens = SessionController.focusLens(cc, spec);
        if (focusLens != null) {
            // the Moon is at infinity: a short sweep from the lens's infinity end, in steps fine enough for its craters
            focusLens.fine = focusLens.calibrated ? 0.02 : Math.max(focusLens.fine / 2, 1e-3);
            focusLens.max = Math.min(focusLens.max, focusLens.calibrated ? 0.4 : 20 * focusLens.fine);
        }
        if (spec.pixelPitchUm > 0 && spec.focalLengthMm > 0) arcsecPerPx = 206264.8 * spec.pixelPitchUm * 1e-3 / spec.focalLengthMm;
        if (moon != null && !Double.isNaN(arcsecPerPx)) rExp = moon.radius * 3600 / arcsecPerPx;
        exposure = MoonMeter.start(moon != null ? moon.phase : 60, moon != null ? moon.alt : 45, spec.fNumber, limits);
        focus = 0f;
    }

    private void createSession(final boolean physical) {
        if (closed || device == null) return;
        try {
            OutputConfiguration pv = new OutputConfiguration(preview), rw = new OutputConfiguration(reader.getSurface());
            if (physical && lens.physicalId != null) {
                pv.setPhysicalCameraId(lens.physicalId);
                rw.setPhysicalCameraId(lens.physicalId);
            }
            List<OutputConfiguration> outs = Arrays.asList(pv, rw);
            Executor ex = new Executor() {
                @Override
                public void execute(Runnable r) {
                    h.post(r);
                }
            };
            device.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, ex,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            if (closed) {
                                s.close();
                                return;
                            }
                            session = s;
                            try {
                                buildRequest(physical && lens.physicalId != null);
                                stage = Stage.SEARCHING;
                                apply();
                            } catch (CameraAccessException | RuntimeException e) {
                                problem("Could not start the camera's picture: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            if (physical && lens.physicalId != null) {
                                // the telephoto inside the main camera wouldn't stream on its own: the main camera, zoomed
                                android.util.Log.i("NightSky", "moonshot: physical camera " + lens.physicalId + " refused; zooming instead");
                                lens.zoomed = true;
                                createSession(false);
                                return;
                            }
                            problem("The camera refused the picture streams.");
                        }
                    }));
        } catch (CameraAccessException | RuntimeException e) {
            problem("Could not start the camera: " + e.getMessage());
        }
    }

    private boolean physicalKeys;

    private void buildRequest(boolean physical) throws CameraAccessException {
        CaptureRequest.Builder b = physical ? device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW, Collections.singleton(lens.physicalId))
                : device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        physicalKeys = physical;
        b.addTarget(preview);
        b.addTarget(reader.getSurface());
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
        CameraCharacteristics cc = lens.cc;
        // hand-held: the lens's own stabiliser on (it steadies each frame; the stack lines the frames up)
        if (CameraRig.has(cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION), CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
        }
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        if (lens.zoomed) {
            Range<Float> zr = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (zr != null) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, Math.min(zr.getUpper(), 5f));
        }
        builder = b;
    }

    /** The frame count when the request last changed. */
    int appliedFrame;

    /** Puts the current exposure and focus on the repeating request (camera thread). */
    void apply() {
        appliedFrame = frameNo;
        if (session == null || builder == null) return;
        try {
            long frameNs = Math.max(minFrameNs, 1_000_000_000L / FPS);
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.ns);
            set(CaptureRequest.SENSOR_SENSITIVITY, exposure.iso);
            set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(frameNs, exposure.ns));
            if (focusLens != null) set(CaptureRequest.LENS_FOCUS_DISTANCE, focus);
            session.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult r) {
                    CaptureResult pr = r;
                    if (lens.physicalId != null && physicalKeys && android.os.Build.VERSION.SDK_INT >= 31) {
                        Map<String, TotalCaptureResult> m = r.getPhysicalCameraTotalResults();
                        if (m != null && m.get(lens.physicalId) != null) pr = m.get(lens.physicalId);
                    }
                    Long ts = pr.get(CaptureResult.SENSOR_TIMESTAMP);
                    Long e = pr.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Integer iso = pr.get(CaptureResult.SENSOR_SENSITIVITY);
                    Float fd = pr.get(CaptureResult.LENS_FOCUS_DISTANCE);
                    Integer ls = pr.get(CaptureResult.LENS_STATE);
                    if (ts != null) {
                        results.put(ts, new long[]{e != null ? e : -1, iso != null ? iso : -1, fd != null ? Float.floatToIntBits(fd) : -1,
                                ls != null ? ls : -1});
                    }
                    if (spec != null) SensorSpecFactory.applyResult(spec, pr, geo);
                }
            }, h);
        } catch (CameraAccessException | RuntimeException e) {
            problem("The camera stopped: " + e.getMessage());
        }
    }

    private <T> void set(CaptureRequest.Key<T> k, T v) {
        builder.set(k, v);
        if (physicalKeys) {
            try {
                builder.setPhysicalCameraKey(k, v, lens.physicalId);
            } catch (IllegalArgumentException ignored) {
                // a key the physical camera doesn't take on its own: the logical one's applies
            }
        }
    }

    /** Stops the camera and its thread. */
    void close() {
        closed = true;
        if (sensors != null) sensors.unregisterListener(tilt);
        final HandlerThread t = thread;
        Runnable shut = new Runnable() {
            @Override
            public void run() {
                try {
                    if (session != null) session.close();
                } catch (RuntimeException ignored) {
                }
                session = null;
                if (device != null) device.close();
                device = null;
                if (reader != null) reader.close();
                reader = null;
                if (preview != null) preview.release();
                preview = null;
                if (t != null) t.quitSafely();
            }
        };
        if (h != null) h.post(shut);
        else shut.run();
    }

    // ------------------------------------------------------------------------------------------------
    // the controls (any thread)

    /** Starts a burst of {@code n} frames (if the Moon is in view and focused). */
    void shoot(final int n) {
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (closed || stage != Stage.READY || spot == null) return;
                burst = new Burst();
                burst.spec = spec.copy();
                burst.arcsecPerPx = arcsecPerPx;
                burst.rExp = rExp;
                burst.startMs = System.currentTimeMillis();
                burst.lens = lens != null ? lens.describe() : "";
                burst.exposure = exposure.toString();
                burst.exposureNs = exposure.ns;
                burst.iso = exposure.iso;
                burst.focus = focus;
                burstWant = n;
                stage = Stage.SHOOTING;
            }
        });
    }

    /** Focuses again (the sweep from the start). */
    void refocus() {
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (closed || focusLens == null || stage == Stage.SHOOTING) return;
                startFocus();
            }
        });
    }

    // ------------------------------------------------------------------------------------------------
    // every frame (camera thread)

    /** A RAW frame: {@code rowStride} in samples. */
    void frame(ShortBuffer raw, int rowStride, int w, int h, long tNs) {
        if (closed) return;
        frameNo++;
        long now = SystemClock.elapsedRealtime();
        if (fpsT0 == 0) fpsT0 = now;
        fpsN++;
        if (now - fpsT0 >= 1000) {
            fps = fpsN * 1000.0 / (now - fpsT0);
            fpsT0 = now;
            fpsN = 0;
        }
        // where the Moon is: the whole frame while it is lost, else around where it was
        MoonFinder.Spot s = spot != null && lost < 5
                ? MoonFinder.find(raw, rowStride, w, h, spec, rExp, spot, (int) Math.max(60, 3 * (Double.isNaN(rExp) ? spot.r : rExp)))
                : (frameNo % 3 == 0 || stage == Stage.SEARCHING && frameNo < 3 ? MoonFinder.find(raw, rowStride, w, h, spec, rExp, null, 0) : null);
        if (s != null && s.ok) {
            spot = s;
            lost = 0;
            if (Double.isNaN(rExp) && s.r > 4) rExp = s.r;
        } else if (s != null || spot == null) {
            lost++;
        }
        long[] res = results.get(tNs);
        boolean asked = shotAsAsked(res);
        if (spot == null || lost >= 5) {
            if (stage != Stage.SHOOTING) stage = Stage.SEARCHING;
            if (stage == Stage.SHOOTING && lost > 15) {
                // the Moon left the picture: what was caught so far is the burst
                finishBurst();
            }
            live(now, null, 0, 0, w, h, "Point the phone at the Moon");
            return;
        }
        int side = MoonFinder.side(Double.isNaN(rExp) ? spot.r : rExp);
        int[] c = MoonFinder.corner(spot.x, spot.y, side, w, h);
        switch (stage) {
            case OPENING:
            case SEARCHING:
                stage = Stage.METERING;
                break;
            case METERING:
                if (asked) {
                    if (MoonMeter.settled(spot.level, spot.clipped)) {
                        if (focusLens != null && sweep == null && focusScores.isEmpty()) startFocus();
                        else stage = Stage.READY;
                    } else {
                        exposure = MoonMeter.adjust(exposure, spot.level, spot.clipped, limits);
                        apply();
                    }
                }
                break;
            case FOCUSING:
                focusFrame(raw, rowStride, w, h, c, side, res);
                break;
            case READY:
                // keep the exposure right as the Moon climbs or a haze passes
                if (asked && !MoonMeter.settled(spot.level, spot.clipped) && (spot.clipped > 0.003 || Math.abs(spot.level / MoonMeter.TARGET - 1) > 0.25)) {
                    exposure = MoonMeter.adjust(exposure, spot.level, spot.clipped, limits);
                    apply();
                }
                break;
            case SHOOTING:
                short[] crop = MoonFinder.crop(raw, rowStride, w, h, c[0], c[1], side, side);
                MoonStack.Frame f = new MoonStack.Frame(crop, side, side, c[0], c[1], tNs);
                f.upPa = upPa();
                burst.frames.add(f);
                if (burst.frames.size() >= burstWant) finishBurst();
                break;
        }
        if (now - lastLiveMs >= 100) {
            short[] crop = MoonFinder.crop(raw, rowStride, w, h, c[0], c[1], side, side);
            int[] cu = MoonLook.closeUp(crop, side, side, c[0], c[1], spec, Math.max(0.05, spot.level));
            live(now, cu, side / 2, side / 2, w, h, words());
        }
    }

    /**
     * Whether this frame was taken with the exposure last asked for: its capture result says so (to the camera's own
     * rounding: a few percent), or, with no result for it, enough frames have passed since (the camera's pipeline is a
     * few frames deep, so a frame just after the change still has the old exposure and must not be measured again).
     */
    boolean shotAsAsked(long[] res) {
        if (res != null && res[0] > 0 && res[1] > 0) {
            return Math.abs(res[0] - exposure.ns) <= Math.max(20_000L, exposure.ns * 0.03)
                    && Math.abs(res[1] - exposure.iso) <= Math.max(2, exposure.iso * 0.03);
        }
        return frameNo - appliedFrame > 6;
    }

    /** The picture's up as a position angle, from the phone's tilt now (NaN looking straight up, or the Moon unknown). */
    double upPa() {
        if (moon == null) return Double.NaN;
        double[] g;
        synchronized (gravity) {
            g = new double[]{gravity[0], gravity[1], gravity[2]};
        }
        return MoonGeo.upAngle(moon.q, g, MoonGeo.sensorUp(spec.sensorOrientation));
    }

    private void finishBurst() {
        final Burst b = burst;
        burst = null;
        stage = Stage.READY;
        if (b == null) return;
        b.fps = fps;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!closed) listener.onBurst(b);
            }
        });
    }

    private void startFocus() {
        sweep = FocusSweep.full(focusLens, FocusSweep.Kind.DAYLIGHT, "the Moon");
        focusScores.clear();
        stage = Stage.FOCUSING;
        double f = sweep.next();
        if (Double.isNaN(f)) {
            stage = Stage.READY;
            return;
        }
        focus = (float) f;
        focusAskedNs = SystemClock.elapsedRealtimeNanos();
        apply();
    }

    /** A frame while focusing: once the lens is at the setting, a few frames scored, then the next setting. */
    private void focusFrame(ShortBuffer raw, int rowStride, int w, int h, int[] c, int side, long[] res) {
        boolean there = res != null && res[2] != -1 && Math.abs(Float.intBitsToFloat((int) res[2]) - focus) < 0.004 + 0.05 * focusLens.fine
                && (res[3] == -1 || res[3] == CaptureResult.LENS_STATE_STATIONARY);
        boolean waited = SystemClock.elapsedRealtimeNanos() - focusAskedNs > 600_000_000L;
        if (!there && !waited) return;
        short[] crop = MoonFinder.crop(raw, rowStride, w, h, c[0], c[1], side, side);
        focusScores.add(MoonStack.sharpness(new MoonStack.Frame(crop, side, side, c[0], c[1], 0), spec));
        if (focusScores.size() < FOCUS_FRAMES) return;
        Double[] sc = focusScores.toArray(new Double[0]);
        Arrays.sort(sc);
        double med = sc[sc.length / 2];
        focusScores.clear();
        sweep.add(focus, med, 0, 0, med > 0);
        double next = sweep.next();
        if (sweep.done() || Double.isNaN(next)) {
            FocusSweep.Result r = sweep.result();
            if (r != null && r.ok && !Double.isNaN(r.focus)) focus = (float) Math.max(0, r.focus);
            else focus = 0f;
            sweep = null;
            focusScores.add(-1.0); // focused once: not again unless asked
            stage = Stage.READY;
            apply();
            return;
        }
        focus = (float) next;
        focusAskedNs = SystemClock.elapsedRealtimeNanos();
        apply();
    }

    private String words() {
        switch (stage) {
            case METERING:
                return "Found the Moon: setting the exposure";
            case FOCUSING:
                return "Focusing on the Moon's craters (hold steady)";
            case READY:
                return "Ready: hold steady and shoot";
            case SHOOTING:
                return "Shooting: hold steady";
            default:
                return "Looking for the Moon";
        }
    }

    private void live(long now, int[] closeUp, int cw, int chh, int fw, int fh, String words) {
        lastLiveMs = now;
        final Live l = new Live();
        l.stage = stage;
        l.spot = spot;
        l.frameW = fw;
        l.frameH = fh;
        l.closeUp = closeUp;
        l.closeW = cw;
        l.closeH = chh;
        l.exposure = exposure != null ? exposure.toString() : "";
        l.focus = focusLens == null ? "fixed focus" : stage == Stage.FOCUSING ? String.format(Locale.US, "focusing (%.2f)", focus)
                : String.format(Locale.US, "focus %.2f", focus);
        l.lens = lens != null ? lens.describe() : "";
        l.words = words;
        l.fps = fps;
        l.got = burst != null ? burst.frames.size() : 0;
        l.want = burst != null ? burstWant : 0;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!closed) listener.onLive(l);
            }
        });
    }

    private void problem(final String why) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!closed) listener.onProblem(why);
            }
        });
    }

    /** The live close-up as a bitmap (main thread). */
    static Bitmap bitmap(Live l) {
        if (l.closeUp == null || l.closeW <= 0) return null;
        return Bitmap.createBitmap(l.closeUp, l.closeW, l.closeH, Bitmap.Config.ARGB_8888);
    }
}
