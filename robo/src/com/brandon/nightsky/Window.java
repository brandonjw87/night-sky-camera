package com.brandon.nightsky;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.BlackLevelPattern;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.JupiterGeo;
import com.brandon.nightsky.processing.RehearsalSky;
import com.brandon.nightsky.processing.Rot;
import com.brandon.nightsky.processing.SkyWindow;
import com.brandon.nightsky.processing.StarLock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.SensorEventBuilder;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowCameraCharacteristics;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;
import org.robolectric.shadows.StreamConfigurationMapBuilder;

import java.io.File;
import java.nio.ShortBuffer;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * The Sky Window end to end (3.3), on a simulated Pixel 10 Pro XL held up to a simulated night sky: the real window and
 * camera code, with the phone's rotation sensors and its main camera simulated under them. The phone points one way;
 * its compass says it points 12 degrees anticlockwise of that (the gyro, as on a real phone, knows the turns but not
 * north); the camera's RAW frames are the real sky drawn as an eighth of a second at ISO 3200 shows it
 * ({@link RehearsalSky}). The window draws the sky where the compass says; the frames show the stars; the star lock must
 * find them and move every label onto its star, to a tenth of a degree. Then: a star tapped for its card, Jupiter (or
 * the Moon) found with the arrow and the 5x's frame, the hand-off to Jupiter's moons, time travel, X-ray, red, the camera
 * off and on, looking through the Earth, a pause, the window opened from the Jupiter screen and going back to it, and day.
 * The screens are drawn to PNG.
 * <p>
 * Usage: robo/run.sh Window
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37, qualifiers = "w412dp-h915dp-port-xhdpi", shadows = {FakeSkyCamera.class, FakeMoonCamera.class})
public class Window {
    static int failed;
    static File out;
    static Application app;
    SkyWindowActivity a;
    ActivityController<SkyWindowActivity> ctrl;
    ShadowSensorManager ssm;
    Sensor game, rv;
    Locator.Place place;
    BrightStars cat;
    /** The compass's error (degrees: it says the phone points this far anticlockwise of the truth), and the gyro's own north. */
    static final double COMPASS = 12, GYRO_NORTH = 40;
    int seed = 1;

    interface Until {
        boolean done() throws Exception;
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : ": " + detail));
        if (!ok) failed++;
    }

    /** How the phone was last held (its axes into east-north-up, the truth). */
    double[] held;

    static void say(String s) {
        System.out.println(s);
    }

    @Test
    public void skyWindow() throws Exception {
        long started = System.currentTimeMillis();
        out = new File(System.getProperty("shots.out", "build/robo"), "window");
        //noinspection ResultOfMethodCallIgnored
        out.mkdirs();
        Moon.out = out;
        app = RuntimeEnvironment.getApplication();
        ShadowApplication sa = Shadows.shadowOf(app);
        sa.grantPermissions(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);

        // a place where it's dark now, the Moon down if it can be, and Jupiter up if it can be
        long now = System.currentTimeMillis();
        double bestLon = Double.NaN, bestScore = -1e9;
        for (double lon = -180; lon < 180; lon += 1) {
            double sun = Astro.altitude(Astro.sunEnu(now, 39 * Astro.DEG, lon * Astro.DEG)) / Astro.DEG;
            if (sun > -18) continue;
            double moon = Astro.altitude(Astro.moonEnu(now, 39 * Astro.DEG, lon * Astro.DEG)) / Astro.DEG;
            double jup = JupiterGeo.altOnly(now, 39.0, lon);
            double score = (moon < -5 ? 100 : 0) + Math.min(jup, 50);
            if (score > bestScore) {
                bestScore = score;
                bestLon = lon;
            }
        }
        check("somewhere it's dark now at 39°N", !Double.isNaN(bestLon), "");
        if (Double.isNaN(bestLon)) throw new AssertionError("no dark sky anywhere at 39°N");
        place = new Locator.Place();
        place.latDeg = 39.0;
        place.lonDeg = bestLon;
        place.altM = 120;
        place.accuracyM = 8;
        place.timeMs = now - 5 * 60_000L;
        place.source = "gps";
        new Prefs(app).savePlace(place);
        double jupAlt = JupiterGeo.altOnly(now, 39.0, bestLon);
        say(String.format(Locale.US, "place 39.0, %.0f: Sun %.0f° down, Moon %.0f°, Jupiter %.0f° up", bestLon,
                -Astro.altitude(Astro.sunEnu(now, 39 * Astro.DEG, bestLon * Astro.DEG)) / Astro.DEG,
                Astro.altitude(Astro.moonEnu(now, 39 * Astro.DEG, bestLon * Astro.DEG)) / Astro.DEG, jupAlt));

        // the phone: its main camera (4080 x 3072 RAW, 6.9 mm f/1.68), the 5x telephoto, the rotation sensors
        CameraManager cm = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
        Shadows.shadowOf(cm).addCamera("0", camera(6.9f, 1.68f, 9.792f, 7.3728f, 10f, 4080, 3072));
        Shadows.shadowOf(cm).addCamera("3", Moon.camera((float) SimTele.FOCAL_MM, (float) SimTele.F_NUMBER, (float) (SimTele.W * SimTele.PITCH_UM / 1000),
                (float) (SimTele.H * SimTele.PITCH_UM / 1000), 3f));
        SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        ssm = Shadows.shadowOf(sm);
        game = ShadowSensor.newInstance(Sensor.TYPE_GAME_ROTATION_VECTOR);
        rv = ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR);
        ssm.addSensor(game);
        ssm.addSensor(rv);
        cat = SkyCatalog.get(app);

        // ---- the window opens
        say("== the window opens");
        ctrl = Robolectric.buildActivity(SkyWindowActivity.class).setup();
        a = ctrl.get();
        waitFor(new Until() {
            @Override
            public boolean done() {
                return a.sky != null && a.catalogue != null;
            }
        }, 30_000);
        check("the sky placed for here and now", a.sky != null && a.sky.items.size() > 100, a.sky == null ? "none" : a.sky.items.size() + " things");
        TextureView tv = (TextureView) Moon.field(a, "texture");
        TextureView.SurfaceTextureListener stl = tv.getSurfaceTextureListener();
        if (stl == null) throw new AssertionError("no surface listener");
        stl.onSurfaceTextureAvailable(null, 1080, 1440);
        SkyCamera cam = a.camera;
        check("the main camera opened", cam != null && FakeSkyCamera.opened == cam && "0".equals(cam.cameraId), cam == null ? "none" : cam.cameraId);
        if (cam == null) throw new AssertionError("no camera");
        check("it started at a night exposure (the Sun is down)", cam.reqNs == SkyWindow.MAX_EXPOSURE_NS, SkyCamera.words(cam.reqNs, cam.reqIso));
        waitFor(new Until() {
            @Override
            public boolean done() {
                return !Double.isNaN(a.teleW);
            }
        }, 10_000);
        check("the 5x camera's field known, for its frame", !Double.isNaN(a.teleW) && a.teleW > 5 && a.teleW < 25,
                String.format(Locale.US, "%.1f° by %.1f°", a.teleW, a.teleH));

        // ---- held up: the compass 12 degrees off, the drawing where the compass says
        say("== held up to the sky, the compass 12° off");
        double az0 = 140, alt0 = 50;
        double[] dev = hold(az0, alt0, 0);
        sensors(dev, 6);
        screen("0_before_lock");
        double before = labelError(dev);
        check("before the lock the labels are where the compass says (about 12° off)", Math.abs(before - COMPASS) < 2.5,
                String.format(Locale.US, "%.1f° off", before));
        String pill0 = a.pill.getText().toString();
        say("  pill: " + pill0);

        // ---- the camera's frames: the night picture, then the lock
        say("== the camera's frames, and the star lock");
        final SkyCamera c0 = cam;
        int n = 0;
        while (a.fix == null && n < 16) {
            frame(c0, dev);
            n++;
        }
        check("the night picture shows (the RAW frame, stretched)", a.picture != null && a.picture.picture != null,
                a.lastFrame == null ? "no frame" : String.format(Locale.US, "%s, night %.2f", a.lastFrame.exposureWords(), a.lastFrame.night));
        check("locked onto the stars within a few frames", a.fix != null, n + " frames, " + c0.lockTries + " tries");
        if (a.fix == null) {
            for (org.robolectric.shadows.ShadowLog.LogItem li : org.robolectric.shadows.ShadowLog.getLogsForTag("NightSky"))
                if (li.msg.contains("sky window")) say("  log: " + li.msg + (li.throwable != null ? " " + li.throwable : ""));
            say("  the last frame's UTC " + (a.lastFrame == null ? "none" : new java.util.Date(a.lastFrame.utcMs) + " (" + a.lastFrame.utcMs + ")")
                    + ", the robot's clock " + new java.util.Date(System.currentTimeMillis()));
            throw new AssertionError("no lock");
        }
        say("  " + a.fix.describe());
        check("the compass's error read back", Math.abs(a.fix.headingDeg - COMPASS) < 0.5, String.format(Locale.US, "%+.2f°", a.fix.headingDeg));
        screen("1_locked");
        double after = labelError(dev);
        check("after the lock every label sits on its star (within 0.1°)", after < 0.1, String.format(Locale.US, "%.3f° off", after));
        String pill1 = a.pill.getText().toString();
        check("the top says so", pill1.startsWith("★ Star lock"), pill1);
        check("the compass no longer moves the lock (the gyro carries it)", a.yawFrozen, "");

        // ---- a turn: the gyro carries the lock, the compass doesn't spoil it
        say("== a turn of 60°, the compass still off (and now 3° more)");
        double[] dev2 = hold(az0 + 60, 35, 10);
        sensors(dev2, 6, COMPASS + 3);
        double turned = labelError(dev2);
        check("after turning, the labels still on the stars", turned < 0.1, String.format(Locale.US, "%.3f° off", turned));

        // ---- a star tapped
        say("== a star tapped");
        screen(null);
        SkyWindow.View v = a.lastView;
        int bright = brightestOnScreen(v);
        check("a bright star on the screen", bright >= 0, bright >= 0 ? cat.label(bright) : "");
        if (bright >= 0) {
            double[] e = enuOf(bright);
            double[] p = new double[2];
            v.project(e, p);
            a.window.tap((float) p[0] + 4, (float) p[1] - 3);
            ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
            String title = a.cardTitle.getText().toString();
            check("its card opens", a.cardBox.getVisibility() == View.VISIBLE && !title.isEmpty(), title);
            waitFor(new Until() {
                @Override
                public boolean done() {
                    return !a.cardRise.getText().toString().startsWith("Working");
                }
            }, 10_000);
            say("  " + title + " / " + a.cardLine.getText() + " / " + a.cardAbout.getText() + " / " + a.cardStory.getText() + " / " + a.cardRise.getText());
            check("with when it rises and sets (or that it never sets)", a.cardRise.getText().length() > 8, a.cardRise.getText().toString());
            screen("2_card");
            String mid = a.caption.getText().toString().trim();
            check("the middle's caption names what's there (a star's constellation too)", !mid.endsWith(" in"), mid.isEmpty() ? "(nothing in the middle)" : mid);
            a.closeCard();
        }

        // ---- Find: Jupiter (or the Moon), the arrow, the 5x's frame, the hand-off
        SkyWindow.Item want = SkyWindowActivity.findItem(a.sky, "Jupiter");
        if (want == null || !(want.altDeg > 10)) want = SkyWindowActivity.findItem(a.sky, "the Moon");
        boolean shootable = want != null && want.altDeg > 10;
        say("== Find " + (want == null ? "nothing" : want.name + String.format(Locale.US, " (%.0f° up)", want.altDeg)));
        a.bFind.performClick();
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        check("Find's list opens", a.findBox.getVisibility() == View.VISIBLE && a.findList.getChildCount() > 10, a.findList.getChildCount() + " rows");
        TextView row = null;
        for (int k = 0; k < a.findList.getChildCount(); k++) {
            View r = a.findList.getChildAt(k);
            if (r instanceof TextView && want != null && ((TextView) r).getText().toString().startsWith(SkyWindowActivity.cap(want.name))) row = (TextView) r;
        }
        check("with a row for it", row != null, "");
        if (row != null && shootable) {
            row.performClick();
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
            check("it's being found", a.target != null && a.target.name.equals(want.name), a.target == null ? "none" : a.target.name);
            screen("3_find");
            String cap0 = a.caption.getText().toString();
            check("the way to it, in words", cap0.startsWith(SkyWindowActivity.cap(want.name) + ":") && cap0.contains("° away"), cap0);
            // turned to it
            double[] devT = hold(want.azDeg, want.altDeg, 0);
            sensors(devT, 6, COMPASS + 3);
            screen("4_got_it");
            check("got it: the buzz, and the words", a.gotTarget && a.caption.getText().toString().startsWith("Got it!"), a.caption.getText().toString());
            check("the 5x's button shows once it's in the 5x's frame", a.bShoot.getVisibility() == View.VISIBLE, a.bShoot.getText().toString());
            a.bShoot.performClick();
            Intent next = Shadows.shadowOf(a).getNextStartedActivity();
            String cls = next == null || next.getComponent() == null ? "" : next.getComponent().getClassName();
            check("Shoot opens the 5x screen for it", cls.endsWith("moon".equals(want.shoot) ? "MoonActivity" : "JupiterActivity"), cls);
            a.setTarget(null);
        } else {
            say("  (neither Jupiter nor the Moon is up here now: the arrow and the hand-off not tried)");
        }

        // ---- time travel
        say("== time travel");
        a.bTime.performClick();
        check("the time slider shows", a.timeBox.getVisibility() == View.VISIBLE, "");
        final long t0 = System.currentTimeMillis();
        a.slider.setProgress(720 + 180);
        waitFor(new Until() {
            @Override
            public boolean done() {
                return a.sky != null && Math.abs(a.sky.utcMs - (t0 + 3 * 3600_000L)) < 60_000;
            }
        }, 15_000);
        check("the sky three hours on", a.offsetMs == 3 * 3600_000L && a.sky != null && Math.abs(a.sky.utcMs - (t0 + 3 * 3600_000L)) < 60_000,
                a.timeLabel.getText().toString());
        check("its words", a.timeLabel.getText().toString().contains("in 3 h"), a.timeLabel.getText().toString());
        double moved = starsMoved(held, 3);
        check("the stars where they'll be (the drawing turned with the sky)", moved < 0.1, String.format(Locale.US, "%.3f° off", moved));
        screen("5_time_travel");
        a.slider.setProgress(720);
        waitFor(new Until() {
            @Override
            public boolean done() {
                return a.sky != null && Math.abs(a.sky.utcMs - System.currentTimeMillis()) < 60_000;
            }
        }, 15_000);
        a.bTime.performClick();

        // ---- X-ray and red
        say("== X-ray and red");
        boolean x0 = a.xray;
        a.bXray.performClick();
        waitFor(new Until() {
            @Override
            public boolean done() {
                return hasKind(SkyWindow.Kind.XRAY) == a.xray;
            }
        }, 15_000);
        check("X-ray marks the unseen", a.xray != x0 && hasKind(SkyWindow.Kind.XRAY) == a.xray, a.xray ? "on" : "off");
        if (!a.xray) a.bXray.performClick();
        a.bRed.performClick();
        check("red: the whole window red", a.red && a.root.getLayerType() == View.LAYER_TYPE_HARDWARE, "");
        screen("6_red");
        a.bRed.performClick();
        check("and back", !a.red && a.root.getLayerType() == View.LAYER_TYPE_NONE, "");
        a.bXray.performClick();

        // ---- the camera off and on
        say("== the camera off and on");
        int opens = FakeSkyCamera.opens;
        a.bCam.performClick();
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        check("camera off: closed, the drawing on its own", a.camera == null && !a.camOn && a.picture == null, "");
        screen("7_camera_off");
        a.bCam.performClick();
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        // (on the phone the TextureView's surface outlives its being hidden; here it never was, so it's handed over)
        TextureView.SurfaceTextureListener stl0 = tv.getSurfaceTextureListener();
        if (a.camera == null && stl0 != null) stl0.onSurfaceTextureAvailable(null, 1080, 1440);
        check("camera on: opened again", a.camera != null && FakeSkyCamera.opens == opens + 1, "");
        check("the lock kept meanwhile", a.fix != null, "");

        // ---- through the Earth
        say("== through the Earth");
        double[] down = hold(az0 + 180, -55, 0);
        sensors(down, 6, COMPASS + 3);
        screen("8_through_the_earth");
        String sub = a.sub.getText().toString();
        check("looking down: the sky on the other side of the Earth", sub.contains("Through the Earth"), sub);
        double under = labelError(down);
        check("and its stars where they are, under your feet", under < 0.1, String.format(Locale.US, "%.3f° off", under));

        // ---- a pause: the lock starts again
        say("== a pause");
        ctrl.pause();
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        check("paused: the camera closed", a.camera == null, "");
        ctrl.resume();
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        check("back: the lock to be found again (the gyro's north may have moved)", a.fix == null && !a.yawFrozen, "");
        stl = tv.getSurfaceTextureListener();
        if (a.camera == null && stl != null) stl.onSurfaceTextureAvailable(null, 1080, 1440);
        check("and the camera open again", a.camera != null, "");

        // ---- day: the camera's own preview, no RAW picture, the compass
        say("== by day");
        sensors(dev, 4);
        for (int k = 0; k < 14; k++) dayFrame(a.camera);
        check("by day the RAW picture gives way to the camera's preview", a.picture == null && a.lastFrame != null && a.lastFrame.night < SkyCamera.PICTURE_OFF,
                a.lastFrame == null ? "no frame" : String.format(Locale.US, "%s, night %.2f", a.lastFrame.exposureWords(), a.lastFrame.night));
        String pillDay = a.pill.getText().toString();
        check("and the top says the labels are by the compass", pillDay.startsWith("By the compass"), pillDay);
        ctrl.pause().stop().destroy();

        // ---- opened from the Jupiter screen: found, then back to it
        say("== opened from the Jupiter screen");
        Intent in = new Intent(app, SkyWindowActivity.class).putExtra(SkyWindowActivity.EXTRA_FIND, "Jupiter").putExtra(SkyWindowActivity.EXTRA_FROM, "jupiter");
        ctrl = Robolectric.buildActivity(SkyWindowActivity.class, in).setup();
        a = ctrl.get();
        waitFor(new Until() {
            @Override
            public boolean done() {
                return a.sky != null && a.target != null && !Double.isNaN(a.teleW);
            }
        }, 30_000);
        check("it's finding Jupiter from the start", a.target != null && "Jupiter".equals(a.target.name), a.target == null ? "none" : a.target.name);
        if (a.target != null && a.target.altDeg > 10) {
            double[] devJ = hold(a.target.azDeg, a.target.altDeg, 0);
            sensors(devJ, 6, 1.5);
            screen(null);
            check("in the 5x's frame (the compass nearly right)", a.bShoot.getVisibility() == View.VISIBLE, a.caption.getText().toString());
            a.bShoot.performClick();
            check("Shoot goes back to the Jupiter screen (no second one)", a.isFinishing() && Shadows.shadowOf(a).getNextStartedActivity() == null, "");
        } else {
            say("  (Jupiter not up here now: the way back not tried)");
        }
        ctrl.pause().stop().destroy();

        say(String.format(Locale.US, "==== %d problems (%.0f s)", failed, (System.currentTimeMillis() - started) / 1000.0));
        if (failed == 0) say("ALL PASS");
        if (failed > 0) throw new AssertionError(failed + " problems");
    }

    // ------------------------------------------------------------------------------------------------
    // the phone in a hand

    static double[] enu(double azDeg, double altDeg) {
        double az = Math.toRadians(azDeg), h = Math.toRadians(altDeg);
        return new double[]{Math.cos(h) * Math.sin(az), Math.cos(h) * Math.cos(az), Math.sin(h)};
    }

    /** The phone's axes into east-north-up, its back toward (az, alt), turned {@code rollDeg} clockwise. */
    static double[] hold(double azDeg, double altDeg, double rollDeg) {
        double[] b = enu(azDeg, altDeg);
        double[] z = {-b[0], -b[1], -b[2]};
        double[] up = {0, 0, 1};
        double d = b[2];
        double[] y = {up[0] - d * b[0], up[1] - d * b[1], up[2] - d * b[2]};
        double ny = Math.sqrt(y[0] * y[0] + y[1] * y[1] + y[2] * y[2]);
        if (ny < 1e-6) y = new double[]{0, 1, 0};
        else for (int i = 0; i < 3; i++) y[i] /= ny;
        double r = Math.toRadians(rollDeg);
        double[] x0 = cross(y, z);
        double[] y1 = {Math.cos(r) * y[0] + Math.sin(r) * x0[0], Math.cos(r) * y[1] + Math.sin(r) * x0[1], Math.cos(r) * y[2] + Math.sin(r) * x0[2]};
        double[] x = cross(y1, z);
        return new double[]{x[0], y1[0], z[0], x[1], y1[1], z[1], x[2], y1[2], z[2]};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    void sensors(double[] trueDev, int n) throws Exception {
        sensors(trueDev, n, COMPASS);
    }

    /**
     * {@code n} readings, 25 ms apart, of a phone held at {@code trueDev}: the gyro's (its own north {@link #GYRO_NORTH}
     * degrees off, as a gyro's is) and the compass's (magnetic, {@code compassDeg} anticlockwise of the truth).
     */
    void sensors(double[] trueDev, int n, double compassDeg) throws Exception {
        held = trueDev;
        double decl = Locator.declination(a.place);
        double[] g = Rot.mul(SkyWindowActivity.rz(GYRO_NORTH), trueDev);
        double[] m = Rot.mul(Rot.transpose(SkyWindow.trueNorth(decl)), Rot.mul(SkyWindowActivity.rz(compassDeg), trueDev));
        for (int k = 0; k < n; k++) {
            long t = SystemClock.elapsedRealtimeNanos();
            ssm.sendSensorEventToListeners(SensorEventBuilder.newBuilder().setSensor(rv).setValues(quat(m)).setTimestamp(t).build());
            ssm.sendSensorEventToListeners(SensorEventBuilder.newBuilder().setSensor(game).setValues(quat(g)).setTimestamp(t).build());
            ShadowLooper.idleMainLooper(25, TimeUnit.MILLISECONDS);
        }
    }

    /** Android's rotation vector (x, y, z, w) for a rotation matrix (row-major: device axes into the world's). */
    static float[] quat(double[] R) {
        double tr = R[0] + R[4] + R[8], w, x, y, z;
        if (tr > 0) {
            double s = Math.sqrt(tr + 1) * 2;
            w = s / 4;
            x = (R[7] - R[5]) / s;
            y = (R[2] - R[6]) / s;
            z = (R[3] - R[1]) / s;
        } else if (R[0] > R[4] && R[0] > R[8]) {
            double s = Math.sqrt(1 + R[0] - R[4] - R[8]) * 2;
            w = (R[7] - R[5]) / s;
            x = s / 4;
            y = (R[1] + R[3]) / s;
            z = (R[2] + R[6]) / s;
        } else if (R[4] > R[8]) {
            double s = Math.sqrt(1 + R[4] - R[0] - R[8]) * 2;
            w = (R[2] - R[6]) / s;
            x = (R[1] + R[3]) / s;
            y = s / 4;
            z = (R[5] + R[7]) / s;
        } else {
            double s = Math.sqrt(1 + R[8] - R[0] - R[4]) * 2;
            w = (R[3] - R[1]) / s;
            x = (R[2] + R[6]) / s;
            y = (R[5] + R[7]) / s;
            z = s / 4;
        }
        if (w < 0) {
            w = -w;
            x = -x;
            y = -y;
            z = -z;
        }
        return new float[]{(float) x, (float) y, (float) z, (float) w};
    }

    // ------------------------------------------------------------------------------------------------
    // the camera's frames

    /** One RAW frame of the sky as the camera truly points, an eighth of a second at the camera's own settings, fed in. */
    void frame(final SkyCamera cam, double[] trueDev) throws Exception {
        final int W = cam.raw.getWidth(), H = cam.raw.getHeight();
        long eNs = cam.reqNs;
        int iso = cam.reqIso;
        final long t0 = SystemClock.elapsedRealtimeNanos();
        // the readings through the exposure, the phone held still
        sensors(trueDev, (int) Math.max(2, eNs / 25_000_000L + 2), COMPASS);
        long utc = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L;
        short[] raw = new short[W * H];
        dark(raw, W, H, iso, seed++);
        RehearsalSky.Setup su = new RehearsalSky.Setup();
        su.spec = cam.spec;
        su.model = cam.model;
        su.catalog = cat;
        su.latDeg = place.latDeg;
        su.lonDeg = place.lonDeg;
        su.altM = 120;
        su.camToEnu = SkyWindow.cameraToEnu(trueDev, cam.sensorOrientation);
        su.startUtcMs = utc;
        su.seed = seed++;
        su.hours = 0.05;
        RehearsalSky.Frame f = new RehearsalSky.Frame();
        f.utcStartMs = utc;
        f.exposureS = eNs / 1e9;
        f.iso = iso;
        f.focus = Double.NaN;
        java.util.Arrays.fill(f.black, 64);
        f.electronsPerLevel = 6.0 * 100 / iso;
        f.noiseSeed = seed++;
        RehearsalSky.build(su).draw(raw, f);
        feed(cam, raw, W, H, t0, eNs, iso);
    }

    /** A bright daylight frame (a blue sky's level), fed in. */
    void dayFrame(SkyCamera cam) throws Exception {
        if (cam == null) return;
        final int W = cam.raw.getWidth(), H = cam.raw.getHeight();
        long eNs = cam.reqNs;
        int iso = cam.reqIso;
        long t0 = SystemClock.elapsedRealtimeNanos();
        ShadowLooper.idleMainLooper(40, TimeUnit.MILLISECONDS);
        // daylight: a level that fills the range at a millisecond and ISO 50, so it scales with the exposure
        double level = Math.min(959, 0.7 * 959 * (eNs / 1e6) * iso / 50.0);
        short[] raw = new short[W * H];
        java.util.Arrays.fill(raw, (short) (64 + level));
        feed(cam, raw, W, H, t0, eNs, iso);
    }

    void feed(final SkyCamera cam, short[] raw, final int W, final int H, final long t0, long eNs, int iso) throws Exception {
        cam.results.put(t0, new long[]{eNs, iso, 0});
        final ShortBuffer sb = ShortBuffer.wrap(raw);
        final boolean[] done = {false};
        cam.h.post(new Runnable() {
            @Override
            public void run() {
                cam.frame(sb, W, W, H, t0);
                synchronized (done) {
                    done[0] = true;
                    done.notifyAll();
                }
            }
        });
        synchronized (done) {
            long end = System.currentTimeMillis() + 10_000;
            while (!done[0] && System.currentTimeMillis() < end) done.wait(100);
        }
        // the picture and the lock on their own threads, then the main thread hears
        long end = System.currentTimeMillis() + 15_000;
        while ((cam.picBusy || cam.lockBusy) && System.currentTimeMillis() < end) Thread.sleep(10);
        ShadowLooper.idleMainLooper(150, TimeUnit.MILLISECONDS);
    }

    /** A covered camera's frame: the black level, read noise, a few hot pixels. */
    static void dark(short[] raw, int W, int H, int iso, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        double sig = 1.6 / (6.0 * 100 / iso);
        for (int i = 0; i < raw.length; i++) {
            double u = Math.max(1e-300, r.nextDouble()), v = r.nextDouble();
            double g = Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
            raw[i] = (short) Math.max(0, Math.min(1023, Math.round(64 + sig * g)));
        }
        SplittableRandom hot = new SplittableRandom(99);
        for (int k = 0; k < raw.length / 20000; k++) raw[hot.nextInt(raw.length)] = (short) (64 + 150 + hot.nextInt(700));
    }

    // ------------------------------------------------------------------------------------------------
    // checking

    /** How far the window's drawing is from the truth (degrees): the screen's middle as drawn against as truly seen. */
    double labelError(double[] trueDev) throws Exception {
        screen(null);
        SkyWindow.View v = a.lastView;
        if (v == null) return Double.NaN;
        double[] trueView = SkyWindow.viewToEnu(trueDev);
        return Math.toDegrees(Rot.angle(Rot.mul(Rot.transpose(trueView), v.viewToEnu)));
    }

    /** With the window's time moved on {@code hours}: how far its stars are from where they'll truly be then (degrees). */
    double starsMoved(double[] trueDev, double hours) throws Exception {
        screen(null);
        SkyWindow.View v = a.lastView;
        int i = brightestOnScreen(v);
        if (v == null) return Double.NaN;
        // the star the window draws in the middle-ish: where it truly is in hours' time, through the true view
        double worst = 0;
        long t = System.currentTimeMillis() + a.offsetMs;
        double[] toEnu = Astro.j2000ToEnu(Astro.jdUtc(t), place.latDeg * Astro.DEG, place.lonDeg * Astro.DEG);
        SkyWindow.View truth = new SkyWindow.View(v.width, v.height, v.f, SkyWindow.viewToEnu(trueDev));
        double[] p = new double[2], q = new double[2];
        for (int k = 0; k < cat.n && k < 400; k++) {
            double[] d = cat.vec(k);
            double[] e = new double[3];
            Rot.apply(toEnu, d[0], d[1], d[2], e);
            if (!truth.project(e, p) || !truth.onScreen(p[0], p[1], 0)) continue;
            // the window projects it through its own view; both have the same pinhole, so compare directions
            double[] tv = truth.inView(e), wv = v.inView(e);
            worst = Math.max(worst, Math.toDegrees(Math.acos(Math.min(1, tv[0] * wv[0] + tv[1] * wv[1] + tv[2] * wv[2]))));
        }
        return worst + (i < 0 ? 0 : 0);
    }

    int brightestOnScreen(SkyWindow.View v) {
        if (v == null) return -1;
        long t = System.currentTimeMillis() + a.offsetMs;
        double[] toEnu = Astro.j2000ToEnu(Astro.jdUtc(t), place.latDeg * Astro.DEG, place.lonDeg * Astro.DEG);
        double[] p = new double[2];
        for (int k = 0; k < cat.n; k++) {
            if (cat.label(k).isEmpty()) continue;
            double[] d = cat.vec(k);
            double[] e = new double[3];
            Rot.apply(toEnu, d[0], d[1], d[2], e);
            if (v.project(e, p) && v.onScreen(p[0], p[1], -60) && Math.hypot(p[0] - v.cx, p[1] - v.cy) > 80) return k;
        }
        return -1;
    }

    double[] enuOf(int k) {
        long t = System.currentTimeMillis() + a.offsetMs;
        double[] toEnu = Astro.j2000ToEnu(Astro.jdUtc(t), place.latDeg * Astro.DEG, place.lonDeg * Astro.DEG);
        double[] d = cat.vec(k), e = new double[3];
        Rot.apply(toEnu, d[0], d[1], d[2], e);
        return e;
    }

    boolean hasKind(SkyWindow.Kind kind) {
        SkyWindow.Sky s = a.sky;
        if (s == null) return false;
        for (SkyWindow.Item it : s.items) if (it.kind == kind) return true;
        return false;
    }

    void waitFor(Until until, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
            if (until.done()) return;
            Thread.sleep(20);
        }
    }

    /** Draws the window as it is (and so places its view); saves it if named. */
    void screen(String name) throws Exception {
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        View v = a.findViewById(android.R.id.content);
        for (int k = 0; k < 2; k++) {
            v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY));
            v.layout(0, 0, dm.widthPixels, dm.heightPixels);
            ShadowLooper.idleMainLooper();
        }
        Bitmap b = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF000000);
        v.draw(new Canvas(b));
        if (name != null) Moon.save(b, name);
    }

    // ------------------------------------------------------------------------------------------------
    // the phone's cameras

    /** A back camera with RAW and manual control, as a Pixel describes it. */
    static CameraCharacteristics camera(float focalMm, float fNumber, float sensorW, float sensorH, float minFocus, int w, int h) {
        CameraCharacteristics cc = ShadowCameraCharacteristics.newCameraCharacteristics();
        ShadowCameraCharacteristics s = Shadow.extract(cc);
        s.set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK);
        s.set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, new int[]{0, 1, 3});
        s.set(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE, CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME);
        s.set(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE, new Range<Integer>(50, 6400));
        s.set(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY, 3200);
        s.set(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE, new Range<Long>(10_000L, 1_000_000_000L));
        s.set(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL, 1023);
        s.set(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN, new BlackLevelPattern(new int[]{64, 64, 64, 64}));
        s.set(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT, CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB);
        s.set(CameraCharacteristics.SENSOR_ORIENTATION, 90);
        s.set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, new float[]{focalMm});
        s.set(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES, new float[]{fNumber});
        s.set(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE, new SizeF(sensorW, sensorH));
        s.set(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE, new Size(w, h));
        s.set(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE, new Rect(0, 0, w, h));
        s.set(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, new Rect(0, 0, w, h));
        s.set(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE, minFocus);
        s.set(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE, 0.2f);
        s.set(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION, CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE);
        s.set(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, new int[]{0, 1});
        s.set(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP, StreamConfigurationMapBuilder.newBuilder()
                .addOutputSize(ImageFormat.RAW_SENSOR, new Size(w, h)).addOutputSize(new Size(1440, 1080)).build());
        return cc;
    }
}
