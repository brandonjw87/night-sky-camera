package com.brandon.nightsky;

import android.content.Context;
import android.graphics.Bitmap;
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
import android.os.Looper;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.FocusModel;
import com.brandon.nightsky.processing.FocusSweep;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SkyWindow;
import com.brandon.nightsky.processing.StarDetector;
import com.brandon.nightsky.processing.StarList;
import com.brandon.nightsky.processing.StarLock;
import com.brandon.nightsky.processing.Stats;

import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The Sky Window's camera (3.3): the main camera, its live picture, and its RAW frames, which do two things. By night
 * each frame is made into the window's picture ({@link SkyWindow#picture}: an eighth of a second, stretched, shows the
 * stars a screen's preview loses), to be put on the screen where the phone pointed while it was exposed; and when the
 * phone is held still, its stars are found and looked up ({@link StarLock}) to learn how far off the compass is, so the
 * labels sit on the stars. By day the camera's own preview shows.
 * <p>
 * The exposure is its own ({@link SkyWindow#nextExposure}): a photo's by day, by night the longest a hand can hold at the
 * most gain the sensor's amplifier has. The lens's stabiliser is off, so that the picture points where the phone does.
 * The focus stays where the stars were sharp (the daylight calibration's prediction, else last night's), else at the
 * lens's infinity.
 * <p>
 * Threads: the camera's (its callbacks, each frame's meter, the copies), the picture's and the lock's; the listener
 * hears on the main thread. The robots replace {@link #open} and feed {@link #frame} themselves.
 */
final class SkyCamera {
    interface Listener {
        /** A frame metered, and by night made into a picture (main thread, a few times a second). */
        void onFrame(Frame f);

        /** A lock was tried on a still frame; {@code fix} is null when its stars didn't give one (main thread). */
        void onLock(StarLock.Fix fix, Frame f);

        /** It can't go on (main thread). */
        void onProblem(String why);
    }

    /** Where the phone's sensors say the camera pointed at a moment. */
    interface Pose {
        /**
         * Camera bearings to east-north-up (true north) at {@code realtimeNs} (the realtime clock), or null with no reading
         * then; {@code yawOut[0]} is the compass's offset it used (degrees), which a lock keeps.
         */
        double[] cameraToEnu(long realtimeNs, double[] yawOut);

        /** How far the phone turned between two moments (degrees; NaN unknown). */
        double turnDeg(long t0Ns, long t1Ns);

        /** A moment on the realtime clock in UTC (ms): the window keeps the time, as it keeps the bearings. */
        long utcMs(long realtimeNs);
    }

    /** One frame, as the screen needs it. */
    static final class Frame {
        /** The night picture (null by day), and its size (RAW px / {@link #SCALE}). */
        Bitmap picture;
        int pw, ph;
        /** The lens (RAW px) and the RAW size. */
        CameraModel model;
        int rawW, rawH;
        /** Where the sensors said the camera pointed mid-exposure (null: no reading), and the compass offset that used. */
        double[] camToEnu;
        double yawDeg;
        /** Mid-exposure on the realtime clock, and in UTC. */
        long midNs, utcMs;
        long exposureNs;
        int iso;
        /** How much of a night exposure it is (0 day, 1 night); its green median (levels); frames a second. */
        double night, median, fps;
        /** A lock's try: the stars found, how far the phone turned in the exposure, and how long it took. */
        int stars = -1;
        double shakeDeg = Double.NaN;
        long lockMs;

        Frame copy() {
            Frame f = new Frame();
            f.picture = picture;
            f.pw = pw;
            f.ph = ph;
            f.model = model;
            f.rawW = rawW;
            f.rawH = rawH;
            f.camToEnu = camToEnu != null ? camToEnu.clone() : null;
            f.yawDeg = yawDeg;
            f.midNs = midNs;
            f.utcMs = utcMs;
            f.exposureNs = exposureNs;
            f.iso = iso;
            f.night = night;
            f.median = median;
            f.fps = fps;
            f.stars = stars;
            f.shakeDeg = shakeDeg;
            f.lockMs = lockMs;
            return f;
        }

        /** "1/8 s at ISO 3200". */
        String exposureWords() {
            return words(exposureNs, iso);
        }
    }

    /** The picture is the RAW frame's size over this (each 2 x 2 colour cell is a pixel, 2 x 2 of them averaged). */
    static final int BIN = 2, SCALE = 2 * BIN;
    /** Night enough for the RAW picture to show (on at the first, off below the second: no flicker in twilight). */
    static final double PICTURE_ON = 0.55, PICTURE_OFF = 0.45;
    /** Night enough to look for stars. */
    static final double LOCK_NIGHT = 0.6;
    /** Still enough to look for stars: the most the phone may turn in the exposure (degrees, about 16 pixels). */
    static final double STEADY_DEG = 0.3;
    /** Between lock tries (ms): after one that worked, and after one that didn't. */
    static final long LOCK_EVERY_MS = 1500, RETRY_MS = 600;
    /** Frames a second the camera runs at most. */
    static final int FPS = 30;

    private final Context ctx;
    private final Listener listener;
    private final Pose pose;
    private final Handler main = new Handler(Looper.getMainLooper());
    HandlerThread thread, pictureThread, lockThread;
    Handler h, ph, lh;
    volatile boolean closed;

    // the camera (camera thread)
    String cameraId;
    CameraCharacteristics cc;
    Size raw;
    SensorSpec spec;
    SensorSpecFactory.Geometry geo;
    CameraModel model;
    CameraDevice device;
    CameraCaptureSession session;
    ImageReader reader;
    Surface preview;
    CaptureRequest.Builder builder;
    long minNs = 100_000L, maxNs = 1_000_000_000L, minFrameNs;
    int minIso = 50, maxIso = 3200;
    float focus;
    String focusHow = "infinity";
    boolean hasFocus;
    /** Camera timestamps to the realtime clock (0 when they are on it already). */
    long tsOffsetNs;
    int sensorOrientation = 90;
    /** Each frame's {exposure ns, ISO, rolling shutter skew ns} by its timestamp, from the capture results. */
    final Map<Long, long[]> results = Collections.synchronizedMap(new LinkedHashMap<Long, long[]>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, long[]> e) {
            return size() > 64;
        }
    });

    // the frames (camera thread)
    long reqNs = 4_000_000L;
    int reqIso = 100;
    int frameNo;
    long fpsT0;
    int fpsN;
    double fps;
    boolean pictureOn;
    long lastFrameMs;

    // the picture (picture thread) and the lock (lock thread)
    private short[] picBuf, lockBuf;
    private int[] argb;
    private final Bitmap[] bitmaps = new Bitmap[3];
    /** The bitmap the screen shows now, and the one last handed to it (-1: none). */
    private volatile int shownIdx = -1, postedIdx = -1;
    volatile boolean picBusy, lockBusy;
    volatile long lastLockMs;
    volatile boolean lastLockOk;
    private StarDetector detector;
    volatile BrightStars catalog;
    volatile double latDeg = Double.NaN, lonDeg = Double.NaN;
    /** Locks tried and found (for the screen and the robots). */
    volatile int lockTries, locks;

    SkyCamera(Context c, Listener l, Pose p) {
        ctx = c.getApplicationContext();
        listener = l;
        pose = p;
    }

    /** Where the phone is (for the lock's sky), and the stars to look them up in. Any thread. */
    void setSky(BrightStars stars, double lat, double lon) {
        catalog = stars;
        latDeg = lat;
        lonDeg = lon;
    }

    /** Starts at a night exposure (the Sun well down), rather than a day one. Before {@link #open}. */
    void startAtNight() {
        reqNs = SkyWindow.MAX_EXPOSURE_NS;
        reqIso = 3200;
    }

    // ------------------------------------------------------------------------------------------------
    // opening (replaced by the robots)

    /** Opens the main camera and shows its picture on {@code st}. Null if it is opening, else why it can't. */
    String open(SurfaceTexture st) {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            String id = CameraRig.pickCamera(cm);
            if (id == null) return "This phone has no back camera that gives RAW pictures.";
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            Size rs = CameraRig.rawSize(c);
            setUp(id, c, rs);
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
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
                try {
                    minFrameNs = map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, rs);
                } catch (IllegalArgumentException ignored) {
                    minFrameNs = 0;
                }
            }
            st.setDefaultBufferSize(ps.getWidth(), ps.getHeight());
            preview = new Surface(st);
            startThreads();
            reader = ImageReader.newInstance(rs.getWidth(), rs.getHeight(), ImageFormat.RAW_SENSOR, 4);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image img;
                    try {
                        img = r.acquireLatestImage();
                    } catch (IllegalStateException e) {
                        return;
                    }
                    if (img == null) return;
                    try {
                        if (closed) return;
                        Image.Plane pl = img.getPlanes()[0];
                        ShortBuffer sb = pl.getBuffer().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                        frame(sb, pl.getRowStride() / 2, img.getWidth(), img.getHeight(), img.getTimestamp());
                    } catch (RuntimeException e) {
                        android.util.Log.w("NightSky", "sky window frame", e);
                    } finally {
                        img.close();
                    }
                }
            }, h);
            cm.openCamera(id, new CameraDevice.StateCallback() {
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
                    problem("The camera was taken by another app.");
                }

                @Override
                public void onError(CameraDevice d, int e) {
                    d.close();
                    if (device == d) device = null;
                    problem("The camera stopped (" + CameraRig.errorWords(e) + ").");
                }
            }, h);
            return null;
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            close();
            return "Could not open the camera: " + e.getMessage();
        }
    }

    void startThreads() {
        if (thread != null) return;
        thread = new HandlerThread("sky-camera");
        thread.start();
        h = new Handler(thread.getLooper());
        pictureThread = new HandlerThread("sky-picture");
        pictureThread.start();
        ph = new Handler(pictureThread.getLooper());
        lockThread = new HandlerThread("sky-lock");
        lockThread.start();
        lh = new Handler(lockThread.getLooper());
    }

    /** What the camera is: its sensor, limits, lens, focus, and its clock. */
    void setUp(String id, CameraCharacteristics c, Size rs) {
        cameraId = id;
        cc = c;
        raw = rs;
        geo = SensorSpecFactory.geometry(c, rs);
        spec = SensorSpecFactory.build(c, id, rs, geo);
        model = spec.model != null ? spec.model : CameraModel.nominal(rs.getWidth(), rs.getHeight(), rs.getWidth() / (2 * Math.tan(Math.toRadians(35))));
        Range<Long> er = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> ir = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (er != null) {
            minNs = er.getLower();
            maxNs = er.getUpper();
        }
        if (ir != null) {
            minIso = ir.getLower();
            maxIso = ir.getUpper();
        }
        // the most gain worth having: the sensor's own amplifier (past it, the noise is multiplied with the stars)
        Integer maxAnalog = c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
        if (maxAnalog != null && maxAnalog > minIso) maxIso = Math.min(maxIso, Math.max(maxAnalog, 1600));
        reqNs = Math.max(minNs, Math.min(Math.min(maxNs, SkyWindow.MAX_EXPOSURE_NS), reqNs));
        reqIso = Math.max(minIso, Math.min(maxIso, reqIso));
        Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (so != null) sensorOrientation = so;
        Integer src = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        tsOffsetNs = src != null && src == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME ? 0
                : SystemClock.elapsedRealtimeNanos() - System.nanoTime();
        FocusSweep.Lens lens = SessionController.focusLens(c, spec);
        hasFocus = lens != null;
        focus = 0f;
        focusHow = hasFocus ? "infinity" : "fixed";
        if (hasFocus) {
            FocusModel fm = FocusModel.parse(new Prefs(ctx).focusModel(id));
            FocusModel.Line line = fm.line();
            double tempC = BatteryInfo.read(ctx).tempC;
            FocusModel.Cal last = fm.lastNight();
            if (line != null && !Double.isNaN(tempC)) {
                focus = (float) Stats.clamp(line.predict(tempC), 0, lens.max + 2 * lens.fine);
                focusHow = "predicted";
            } else if (last != null) {
                focus = (float) last.focus;
                focusHow = "last night's";
            }
        }
        int pw = rs.getWidth() / SCALE, phh = rs.getHeight() / SCALE;
        argb = new int[pw * phh];
    }

    private void createSession() {
        if (closed || device == null) return;
        try {
            List<OutputConfiguration> outs = Arrays.asList(new OutputConfiguration(preview), new OutputConfiguration(reader.getSurface()));
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
                                buildRequest();
                                apply();
                            } catch (CameraAccessException | RuntimeException e) {
                                problem("Could not start the camera's picture: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            problem("The camera refused the picture streams.");
                        }
                    }));
        } catch (CameraAccessException | RuntimeException e) {
            problem("Could not start the camera: " + e.getMessage());
        }
    }

    private void buildRequest() throws CameraAccessException {
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(preview);
        b.addTarget(reader.getSurface());
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        // the picture points where the phone does: no lens shifting under it, no cropping and warping
        if (CameraRig.has(cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION), CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        }
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        builder = b;
    }

    /** Puts the exposure and focus on the repeating request (camera thread). */
    void apply() {
        if (session == null || builder == null) return;
        try {
            long frameNs = Math.max(Math.max(minFrameNs, 1_000_000_000L / FPS), reqNs);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, reqNs);
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, reqIso);
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs);
            if (hasFocus) builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus);
            session.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult r) {
                    result(r);
                }
            }, h);
        } catch (CameraAccessException | RuntimeException e) {
            problem("The camera stopped: " + e.getMessage());
        }
    }

    /** A capture result: its frame's settings kept for the frame, the sensor's levels for the next ones. */
    void result(CaptureResult r) {
        Long ts = r.get(CaptureResult.SENSOR_TIMESTAMP);
        Long e = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
        Long skew = r.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
        if (ts != null) results.put(ts, new long[]{e != null ? e : -1, iso != null ? iso : -1, skew != null ? skew : -1});
        if (spec != null && geo != null) SensorSpecFactory.applyResult(spec, r, geo);
    }

    /** Stops the camera and its threads. */
    void close() {
        closed = true;
        final HandlerThread t = thread, pt = pictureThread, lt = lockThread;
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
                if (pt != null) pt.quitSafely();
                if (lt != null) lt.quitSafely();
            }
        };
        if (h != null) h.post(shut);
        else shut.run();
    }

    /** The screen now shows {@code b} (main thread): the picture thread won't draw into it. */
    void showing(Bitmap b) {
        for (int i = 0; i < bitmaps.length; i++) if (bitmaps[i] == b && b != null) shownIdx = i;
    }

    // ------------------------------------------------------------------------------------------------
    // every frame (camera thread)

    /** A RAW frame: {@code rowStride} in samples, {@code tNs} its camera timestamp (the start of its first row's exposure). */
    void frame(ShortBuffer rawBuf, int rowStride, int w, int hh, long tNs) {
        if (closed || spec == null) return;
        frameNo++;
        long nowMs = SystemClock.elapsedRealtime();
        if (fpsT0 == 0) fpsT0 = nowMs;
        fpsN++;
        if (nowMs - fpsT0 >= 1000) {
            fps = fpsN * 1000.0 / (nowMs - fpsT0);
            fpsT0 = nowMs;
            fpsN = 0;
        }
        long[] res = results.remove(tNs);
        long eNs = res != null && res[0] > 0 ? res[0] : reqNs;
        int iso = res != null && res[1] > 0 ? (int) res[1] : reqIso;
        long skew = res != null && res[2] > 0 ? res[2] : 0;
        long start = tNs + tsOffsetNs, end = start + eNs + skew;
        long mid = start + (eNs + skew) / 2;

        // the meter: toward a photo's exposure by day, the longest and brightest a hand can hold by night
        double median = SkyWindow.greenMedian(rawBuf, rowStride, w, hh, spec);
        float black = 0.25f * (spec.black[0] + spec.black[1] + spec.black[2] + spec.black[3]);
        double range = spec.white - black;
        long[] next = SkyWindow.nextExposure(eNs, iso, median, range, minNs, maxNs, minIso, maxIso);
        double light = (double) next[0] * next[1], had = (double) reqNs * reqIso;
        if (Math.abs(light / had - 1) > 0.03 || next[0] != reqNs && Math.abs((double) next[0] / reqNs - 1) > 0.03) {
            reqNs = next[0];
            reqIso = (int) next[1];
            apply();
        }
        double night = SkyWindow.nightness(eNs, iso, maxNs, maxIso);
        if (night >= PICTURE_ON) pictureOn = true;
        else if (night < PICTURE_OFF) pictureOn = false;

        final Frame f = new Frame();
        f.model = model;
        f.rawW = w;
        f.rawH = hh;
        f.pw = w / SCALE;
        f.ph = hh / SCALE;
        f.midNs = mid;
        f.utcMs = pose != null ? pose.utcMs(mid) : System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - mid) / 1_000_000L;
        f.exposureNs = eNs;
        f.iso = iso;
        f.night = night;
        f.median = median;
        f.fps = fps;
        double[] yaw = new double[1];
        f.camToEnu = pose != null ? pose.cameraToEnu(mid, yaw) : null;
        f.yawDeg = yaw[0];

        // the lock: a still frame, dark enough for stars, now and then
        long since = nowMs - lastLockMs;
        if (!lockBusy && night >= LOCK_NIGHT && f.camToEnu != null && catalog != null && !Double.isNaN(latDeg)
                && since >= (lastLockOk ? LOCK_EVERY_MS : RETRY_MS) && lh != null) {
            double shake = pose.turnDeg(start, end);
            if (shake < STEADY_DEG) {
                lockBuf = copy(rawBuf, rowStride, w, hh, lockBuf);
                lockBusy = true;
                final Frame lf = f.copy();
                lf.shakeDeg = shake;
                final short[] buf = lockBuf;
                lh.post(new Runnable() {
                    @Override
                    public void run() {
                        lock(buf, lf);
                    }
                });
            }
        }
        // the picture by night (when the last one is done); by day only the meter's words, five times a second
        if (pictureOn) {
            if (!picBusy && ph != null) {
                picBuf = copy(rawBuf, rowStride, w, hh, picBuf);
                picBusy = true;
                final short[] buf = picBuf;
                final double fNight = night;
                ph.post(new Runnable() {
                    @Override
                    public void run() {
                        picture(buf, f, fNight);
                    }
                });
            }
        } else if (nowMs - lastFrameMs >= 200) {
            lastFrameMs = nowMs;
            post(f);
        }
    }

    /** The frame into {@code into} (made if null or the wrong size), packed: row stride = width. */
    static short[] copy(ShortBuffer src, int rowStride, int w, int hh, short[] into) {
        if (into == null || into.length != w * hh) into = new short[w * hh];
        ShortBuffer s = src.duplicate();
        if (rowStride == w) {
            s.position(0);
            s.get(into, 0, w * hh);
        } else {
            for (int y = 0; y < hh; y++) {
                s.position(y * rowStride);
                s.get(into, y * w, w);
            }
        }
        return into;
    }

    /** Makes the night picture (picture thread). */
    private void picture(short[] buf, Frame f, double night) {
        try {
            if (closed) return;
            int pw = f.pw, phh = f.ph;
            if (argb == null || argb.length != pw * phh) argb = new int[pw * phh];
            SkyWindow.picture(ShortBuffer.wrap(buf), f.rawW, f.rawW, f.rawH, spec, BIN, gains(), night, argb);
            int k = 0;
            while (k == shownIdx || k == postedIdx) k++;
            if (bitmaps[k] == null || bitmaps[k].getWidth() != pw || bitmaps[k].getHeight() != phh) {
                bitmaps[k] = Bitmap.createBitmap(pw, phh, Bitmap.Config.ARGB_8888);
            }
            bitmaps[k].setPixels(argb, 0, pw, 0, 0, pw, phh);
            postedIdx = k;
            f.picture = bitmaps[k];
            post(f);
        } catch (RuntimeException e) {
            android.util.Log.w("NightSky", "sky window picture", e);
        } finally {
            picBusy = false;
        }
    }

    private double[] gains;

    private double[] gains() {
        if (gains == null) gains = SkyWindow.daylightGains(spec);
        return gains;
    }

    /** Looks for the frame's stars and locks on them (lock thread). */
    private void lock(short[] buf, final Frame f) {
        StarLock.Fix fix = null;
        try {
            if (closed) return;
            long t0 = SystemClock.elapsedRealtime();
            if (detector == null) detector = StarLock.detector(spec);
            StarList stars = detector.detect(buf, 1f, 400);
            f.stars = stars != null ? stars.count : 0;
            fix = StarLock.lock(stars, f.model, catalog, f.camToEnu, f.utcMs, latDeg, lonDeg);
            f.lockMs = SystemClock.elapsedRealtime() - t0;
            lockTries++;
            if (fix != null) locks++;
            android.util.Log.i("NightSky", String.format(Locale.US, "sky window lock: %d stars, %s in %d ms (turned %.2f° in the exposure)", f.stars,
                    fix != null ? fix.describe() : "no lock", f.lockMs, f.shakeDeg));
        } catch (RuntimeException | OutOfMemoryError e) {
            android.util.Log.w("NightSky", "sky window lock", e);
        } finally {
            lastLockOk = fix != null;
            lastLockMs = SystemClock.elapsedRealtime();
            lockBusy = false;
        }
        final StarLock.Fix got = fix;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!closed) listener.onLock(got, f);
            }
        });
    }

    private void post(final Frame f) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (closed) return;
                // a picture overtaken by a newer one is dropped: its bitmap may be being drawn into again
                int k = postedIdx;
                if (f.picture != null && (k < 0 || f.picture != bitmaps[k])) return;
                listener.onFrame(f);
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

    /** "1/8 s at ISO 3200", "1/2000 s at ISO 50". */
    static String words(long ns, int iso) {
        double s = ns / 1e9;
        String t = s >= 0.95 ? String.format(Locale.US, "%.0f s", s) : String.format(Locale.US, "1/%d s", Math.round(1 / s));
        return t + " at ISO " + iso;
    }
}
