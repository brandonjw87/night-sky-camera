package com.brandon.nightsky;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraManager;
import android.media.MediaActionSound;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.brandon.nightsky.processing.JupiterGeo;
import com.brandon.nightsky.processing.JupiterLook;
import com.brandon.nightsky.processing.JupiterMeter;
import com.brandon.nightsky.processing.JupiterStack;
import com.brandon.nightsky.processing.MoonGeo;
import com.brandon.nightsky.processing.MoonMeter;
import com.brandon.nightsky.remote.JsonTree;
import com.brandon.nightsky.sim.JupiterSim;
import com.brandon.nightsky.watch.WatchProbe;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.SensorEventBuilder;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaActionSound;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Jupiter's moons end to end (3.2), on a simulated Pixel 10 Pro XL: the real Jupiter screen and camera code, with a
 * simulated 5x telephoto pointed at Jupiter under them ({@link SimJupiter}: whole 12-megapixel RAW frames of Jupiter
 * and its four moons, shaking in a hand, a lens to focus and a sensor brighter than the meter's guess), at a place and
 * moment where Jupiter stands well up in a dark sky. The screen says where Jupiter is and draws the line-up; the
 * camera finds Jupiter, meters it and focuses; the watch's Shoot (as the watch link calls it) takes both bursts; they
 * are stacked, the moons found and named on the photo, saved plain and named with the notebook page, and the night
 * written into the notebook, which a fresh screen reads back. A moon is tapped for its story. The screens are drawn
 * to PNG.
 * <p>
 * Usage: robo/run.sh Jupiter
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37, qualifiers = "w412dp-h915dp-port-xhdpi", shadows = {FakeMoonCamera.class})
public class Jupiter {
    static int failed;
    static File out;
    static Application app;
    JupiterActivity a;
    ActivityController<JupiterActivity> ctrl;
    MoonCamera cam;
    SimJupiter tele;
    CaptureService svc;
    int step;

    interface Until {
        boolean done() throws Exception;
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : ": " + detail));
        if (!ok) failed++;
    }

    static void say(String s) {
        System.out.println(s);
    }

    @Test
    public void galileosNotebook() throws Exception {
        long started = System.currentTimeMillis();
        out = new File(System.getProperty("shots.out", "build/robo"), "jupiter");
        //noinspection ResultOfMethodCallIgnored
        out.mkdirs();
        Moon.out = out;
        app = RuntimeEnvironment.getApplication();
        ShadowApplication sa = Shadows.shadowOf(app);
        sa.grantPermissions(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
        FakeMediaStore.install(new File(out, "media"));
        WatchProbe.setup(null, false);

        // a place where Jupiter stands 35 to 60 degrees up in a dark sky now
        long now = System.currentTimeMillis();
        Locator.Place place = new Locator.Place();
        place.latDeg = 39.0;
        double bestLon = Double.NaN, bestAlt = -90;
        for (double lon = -180; lon < 180; lon += 1) {
            if (JupiterGeo.sunAlt(now, 39.0, lon) > -12) continue;
            double alt = JupiterGeo.altOnly(now, 39.0, lon);
            if (Double.isNaN(bestLon) || Math.abs(alt - 48) < Math.abs(bestAlt - 48)) {
                bestAlt = alt;
                bestLon = lon;
            }
        }
        check("somewhere Jupiter is up in a dark sky now", !Double.isNaN(bestLon) && bestAlt > 20, String.format(Locale.US, "%.0f° up", bestAlt));
        if (Double.isNaN(bestLon)) throw new AssertionError("Jupiter isn't up in the dark anywhere at 39°N now");
        place.lonDeg = bestLon;
        place.altM = 120;
        place.accuracyM = 8;
        place.timeMs = now - 5 * 60_000L;
        place.source = "gps";
        new Prefs(app).savePlace(place);
        JupiterGeo.View v0 = JupiterGeo.view(now, place.latDeg, place.lonDeg);
        say(String.format(Locale.US, "place %.1f, %.1f: Jupiter %.0f° up in the %s, magnitude %.1f, %.1f\" across; line-up %s", place.latDeg, place.lonDeg,
                v0.alt, MoonActivity.compass(v0.az), v0.mag, 2 * v0.radiusArcsec, JupiterGeo.line(v0.moons, 41, false).trim()));

        // the phone's back cameras, as a Pixel 10 Pro XL describes them: main, ultrawide, the 5x telephoto
        CameraManager cm = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
        Shadows.shadowOf(cm).addCamera("0", Moon.camera(6.9f, 1.68f, 9.792f, 7.3728f, 10f));
        Shadows.shadowOf(cm).addCamera("2", Moon.camera(2.0f, 1.7f, 5.6f, 4.2f, 0f));
        Shadows.shadowOf(cm).addCamera("3", Moon.camera((float) SimTele.FOCAL_MM, (float) SimTele.F_NUMBER, (float) (SimTele.W * SimTele.PITCH_UM / 1000),
                (float) (SimTele.H * SimTele.PITCH_UM / 1000), 3f));
        SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        ShadowSensorManager ssm = Shadows.shadowOf(sm);
        Sensor grav = ShadowSensor.newInstance(Sensor.TYPE_GRAVITY);
        ssm.addSensor(grav);

        // ---- the screen opens: where Jupiter is, the line-up, the night's happenings, the notebook
        say("== Jupiter's moons opens");
        ctrl = Robolectric.buildActivity(JupiterActivity.class).setup();
        a = ctrl.get();
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
        check("the screen is the one the watch reaches", JupiterActivity.current == a, "");
        String tonight = ((TextView) Moon.field(a, "tonight")).getText().toString();
        check("it says where Jupiter is", tonight.startsWith("Jupiter is") && tonight.contains("degrees up in the"), tonight);
        ImageView lineUp = (ImageView) Moon.field(a, "lineUp");
        check("tonight's line-up drawn", lineUp.getDrawable() != null, "");
        String ev = ((TextView) Moon.field(a, "events")).getText().toString();
        say("  " + ev.replace("\n", " / "));
        check("the night's happenings said", ev.startsWith("In the next 12 hours"), ev.substring(0, Math.min(60, ev.length())));
        waitFor(new Until() {
            @Override
            public boolean done() {
                return ((ImageView) Moon.field(a, "notebook")).getDrawable() != null;
            }
        }, 20_000);
        check("the notebook page drawn (Galileo's nights and the nights to come)", ((ImageView) Moon.field(a, "notebook")).getDrawable() != null, "");
        String st0 = ((TextView) Moon.field(a, "storiesText")).getText().toString();
        check("a story showing: Jupiter's own, with its distance tonight", st0.startsWith("Jupiter is the biggest planet") && st0.contains("million km"),
                st0.substring(0, Math.min(50, st0.length())));

        TextureView tv = (TextureView) Moon.field(a, "texture");
        TextureView.SurfaceTextureListener stl = tv.getSurfaceTextureListener();
        if (stl == null) throw new AssertionError("no surface listener");
        stl.onSurfaceTextureAvailable(null, 1080, 1440);
        cam = (MoonCamera) Moon.field(a, "camera");
        check("the camera opened, in its Jupiter mode", cam != null && FakeMoonCamera.opened == cam && cam.jupiter, String.valueOf(Moon.field(a, "watchWords")));
        if (cam == null) throw new AssertionError("no camera");
        check("it chose the 5x telephoto", cam.lens != null && "3".equals(cam.lens.openId), cam.lens == null ? "none" : cam.lens.describe());
        check("Jupiter's radius as tonight's Jupiter needs (17\" pixels)", Math.abs(cam.rExp - v0.radiusArcsec / cam.arcsecPerPx) < 0.05,
                String.format(Locale.US, "%.2f px", cam.rExp));
        MoonMeter.Exposure guess = JupiterMeter.start(v0.mag, v0.alt, SimTele.F_NUMBER, cam.arcsecPerPx, cam.limits);
        check("its first exposure: Jupiter's meter's guess", Math.abs(cam.exposure.light() / guess.light() - 1) < 0.05, cam.exposure.toString());
        // the phone held tilted 8 degrees
        float[] g = {(float) (9.8 * Math.sin(Math.toRadians(-8))), (float) (9.8 * Math.cos(Math.toRadians(8))), 0f};
        ssm.sendSensorEventToListeners(SensorEventBuilder.newBuilder().setSensor(grav).setValues(g).setTimestamp(1).build());
        double upPa = MoonGeo.upAngle(cam.planet.q, new double[]{g[0], g[1], g[2]}, MoonGeo.sensorUp(cam.spec.sensorOrientation));
        check("the picture's up from the tilt and Jupiter's sky", Math.abs(MoonGeo.norm180(cam.upPa() - upPa)) < 1e-6, String.format(Locale.US, "%.1f°", cam.upPa()));
        JupiterSim sim = new JupiterSim(cam.planet, cam.arcsecPerPx, upPa, 1.6, 0.03);
        sim.air = Math.pow(10, -0.4 * 0.2 * (MoonMeter.airmass(cam.planet.alt) - 1));
        // a sensor 1.8 times what the meter's textbook guess expects: the meter has to find it
        sim.sensitivity = 1.8 * guessTruth(sim, cam, guess);
        tele = new SimJupiter(cam, sim);

        // ---- finding, metering, focusing
        say("== finding, metering and focusing");
        final MoonCamera.Stage[] last = {null};
        final int[] firstFound = {-1};
        final double[] foundOff = {Double.NaN};
        final List<MoonCamera.Stage> seen = new ArrayList<MoonCamera.Stage>();
        int toReady = frames(900, new Until() {
            @Override
            public boolean done() {
                MoonCamera.Stage s = cam.stage;
                if (s != last[0]) {
                    seen.add(s);
                    say(String.format(Locale.US, "  frame %4d  %-9s %s · focus %.3f%s", tele.n, s, cam.exposure, cam.focus,
                            cam.spot != null ? String.format(Locale.US, " · Jupiter at %.1f,%.1f level %.2f clipped %.3f", cam.spot.x, cam.spot.y,
                                    cam.spot.level, cam.spot.clipped) : ""));
                    last[0] = s;
                }
                if (firstFound[0] < 0 && cam.spot != null && cam.spot.ok) {
                    firstFound[0] = tele.n;
                    foundOff[0] = Math.hypot(cam.spot.x - tele.lastJx, cam.spot.y - tele.lastJy);
                }
                return s == MoonCamera.Stage.READY;
            }
        });
        check("found Jupiter in the whole 12-megapixel frame, where it is", firstFound[0] >= 0 && firstFound[0] <= 3 && foundOff[0] < 1,
                String.format(Locale.US, "frame %d, %.2f px off", firstFound[0], foundOff[0]));
        check("went through looking, metering and focusing to ready", seen.contains(MoonCamera.Stage.METERING) && seen.contains(MoonCamera.Stage.FOCUSING)
                && cam.stage == MoonCamera.Stage.READY, seen.toString());
        check("ready within 20 seconds of frames", toReady > 0 && toReady <= 600, toReady + " frames");
        double lv = 0;
        for (int k = 0; k < 8; k++) {
            frames(1, null);
            if (cam.spot != null) lv = Math.max(lv, cam.spot.level);
        }
        check("metered: Jupiter's brightest pixel near the target in the sharpest frames", Math.abs(lv / JupiterMeter.TARGET - 1) < 0.3,
                String.format(Locale.US, "%.2f (target %.2f), %s", lv, JupiterMeter.TARGET, cam.exposure));
        check("focused where the lens is sharpest", Math.abs(cam.focus - SimTele.BEST_FOCUS) <= 0.025,
                String.format(Locale.US, "%.3f (sharpest %.2f)", cam.focus, SimTele.BEST_FOCUS));
        Button shutter = (Button) Moon.field(a, "shutter");
        TextView status = (TextView) Moon.field(a, "status");
        ImageView closeUp = (ImageView) Moon.field(a, "closeUp");
        check("the screen: ready, the shutter on, the close-up showing", shutter.isEnabled() && status.getText().toString().startsWith("Ready")
                && closeUp.getDrawable() != null, status.getText().toString());
        screen("1_ready");

        // ---- the watch's Shoot: both bursts
        say("== the watch's Shoot, and both bursts");
        svc = Robolectric.buildService(CaptureService.class).create().get();
        String st = watchStatus();
        JsonTree.Obj moon = JsonTree.object(st).obj("moon");
        check("the watch hears Jupiter's screen open and ready", moon != null && moon.bool("open", false) && moon.bool("ready", false)
                && "jupiter".equals(moon.str("target", "")), moon == null ? "no moon" : moon.toString());
        String said = fromWatch("shoot");
        check("the watch's Shoot: the phone shoots Jupiter", said.startsWith("Shooting Jupiter"), said);
        check("with the shutter's sound", ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK) == 1, "");
        check("the moons' frames wanted after Jupiter's", cam.glowWant == MoonCamera.MOONS, String.valueOf(cam.glowWant));
        final MoonMeter.Exposure[] moonsExp = {null};
        final String[] words = {"", ""};
        int burst = frames(MoonCamera.JUPITER_BURST + MoonCamera.MOONS + MoonCamera.GLOW_WAIT + 60, new Until() {
            @Override
            public boolean done() {
                if (cam.stage == MoonCamera.Stage.GLOW && moonsExp[0] == null) moonsExp[0] = cam.exposure;
                String w = ((TextView) Moon.field(a, "status")).getText().toString();
                if (w.startsWith("Shooting Jupiter")) words[0] = w;
                if (w.startsWith("Now its moons")) words[1] = w;
                return cam.burst == null && cam.stage == MoonCamera.Stage.READY && Moon.field(a, "job") != null;
            }
        });
        check("90 frames of Jupiter, then the moons' 240 (and a few while the exposure changed)", burst >= 330 && burst <= 330 + MoonCamera.GLOW_WAIT + 15,
                burst + " frames");
        check("the moons' frames 16 times the light", moonsExp[0] != null && Math.abs(moonsExp[0].light() / cam.exposure.light() - JupiterMeter.MOONS_GAIN) < 1,
                moonsExp[0] == null ? "none" : moonsExp[0] + " against " + cam.exposure);
        check("the screen said so while they came", words[0].contains("of 90") && words[1].contains("of 240"), words[0] + " / " + words[1]);
        long t0 = System.currentTimeMillis();
        frames(20000, new Until() {
            @Override
            public boolean done() {
                return Moon.field(a, "job") == null;
            }
        });
        say(String.format(Locale.US, "  stacked in %.1f s (while the camera kept streaming)", (System.currentTimeMillis() - t0) / 1000.0));
        frames(900, new Until() {
            @Override
            public boolean done() {
                return Moon.field(a, "savedPage") != null;
            }
        });

        // ---- the photo
        JupiterStack.Result r = (JupiterStack.Result) Moon.field(a, "result");
        check("the photo is made", r != null, String.valueOf(Moon.field(a, "watchWords")));
        if (r == null) throw new AssertionError("no photo");
        say("  " + r.log.replace("\n", "\n  "));
        int showing = 0, named = 0, wrong = 0;
        JupiterGeo.View vr = (JupiterGeo.View) Moon.field(a, "view");
        for (int k = 0; k < 4; k++) {
            JupiterGeo.Moon m = vr.moons[k];
            if (!m.showing() || m.dist() < 4) continue;
            showing++;
            if (r.status[k] == JupiterStack.FOUND) {
                named++;
                if (Math.hypot(r.moon[k].jx - m.x, r.moon[k].jy - m.y) > 0.6) wrong++;
            }
        }
        check("the moons clear of Jupiter's glare found and named where they are", named >= showing - 1 && wrong == 0,
                named + " of " + showing + " found" + (wrong > 0 ? ", " + wrong + " in the wrong place" : ""));
        for (int k = 0; k < 4; k++) {
            JupiterGeo.Moon m = vr.moons[k];
            boolean ok = m.hidden() ? r.status[k] == JupiterStack.HIDDEN : m.transit() ? r.status[k] == JupiterStack.CROSSING
                    : m.eclipsed() ? r.status[k] == JupiterStack.DARK : true;
            if (!ok) check("  " + m.name + "'s reason", false, JupiterStack.statusWord(r.status[k]));
        }
        TextView title = Moon.firstText((ViewGroup) Moon.field(a, "resultBox"));
        check("the screen: \"Your Jupiter, with ...\"", title != null && title.getText().toString().startsWith("Your Jupiter"),
                title == null ? "" : title.getText().toString());
        String lines = ((TextView) Moon.field(a, "moonsText")).getText().toString();
        say("  " + lines.replace("\n", "\n  "));
        check("each moon's line: where it is, or why it isn't there", lines.split("\n").length == 4 && lines.contains("Jupiter-widths"), "");
        JupiterLook.Picture pic = (JupiterLook.Picture) Moon.field(a, "pic");
        int labels = 0;
        JupiterLook.Label first = null;
        for (JupiterLook.Label l : pic.labels) if (l.moon >= 0 && l.seen) {
            labels++;
            if (first == null) first = l;
        }
        check("every moon found named on the photo", labels == r.found(), labels + " names");
        // its saves: plain, named, the notebook page
        Uri plain = (Uri) Moon.field(a, "savedPlain"), nm = (Uri) Moon.field(a, "savedNamed"), pg = (Uri) Moon.field(a, "savedPage");
        File fp = plain != null ? FakeMediaStore.file(plain) : null, fn = nm != null ? FakeMediaStore.file(nm) : null, fg = pg != null ? FakeMediaStore.file(pg) : null;
        check("saved three times: plain, named, the notebook page", FakeMediaStore.rows.size() == 3 && fp != null && fn != null && fg != null
                && fp.length() > 20_000 && fn.length() > 20_000 && fg.length() > 50_000, FakeMediaStore.rows.size() + " saved");
        if (fp != null && fn != null && fg != null) {
            String np = FakeMediaStore.name(plain), nn = FakeMediaStore.name(nm), ng = FakeMediaStore.name(pg);
            check("named for the moment", np != null && np.startsWith("Jupiter_") && nn != null && nn.endsWith("_named.jpg") && ng != null
                    && ng.startsWith("Galileos_notebook_"), np + ", " + nn + ", " + ng);
            Bitmap bp = BitmapFactory.decodeFile(fp.getPath()), bn = BitmapFactory.decodeFile(fn.getPath()), bg = BitmapFactory.decodeFile(fg.getPath());
            check("whole JPEGs, the photo twice as wide as tall", bp != null && bn != null && bg != null && bp.getWidth() == JupiterLook.W
                    && bp.getHeight() == JupiterLook.H, bp == null ? "none" : bp.getWidth() + " x " + bp.getHeight());
            Moon.save(bn, "saved_named");
            Moon.save(bg, "saved_notebook");
        }
        screen("2_result");
        whole("2_result_page");
        // a moon tapped: its story
        if (first != null) {
            Moon.call(a, "tapped", new Class<?>[]{float.class, float.class}, (float) first.x, (float) first.y);
            String story = ((TextView) Moon.field(a, "storyText")).getText().toString();
            check(JupiterGeo.NAMES[first.moon] + " tapped: where it is and its story", story.startsWith(JupiterGeo.NAMES[first.moon] + ": there")
                    && story.contains("goes round"), story.substring(0, Math.min(80, story.length())));
        }
        // the watch hears the photo is ready
        JsonTree.Obj m2 = JsonTree.object(watchStatus()).obj("moon");
        check("the watch hears the photo is ready", m2 != null && m2.str("done", "").startsWith("Your Jupiter"), m2 == null ? "" : m2.str("done", ""));

        // ---- the notebook: the night written, and read back by a fresh screen
        say("== the notebook");
        @SuppressWarnings("unchecked")
        List<JupiterLook.Entry> nights = (List<JupiterLook.Entry>) Moon.field(a, "nights");
        check("tonight in the notebook", nights.size() == 1, nights.size() + " nights");
        waitFor(new Until() {
            @Override
            public boolean done() {
                return new File(app.getFilesDir(), JupiterActivity.NIGHTS).exists();
            }
        }, 10_000);
        List<JupiterLook.Entry> back = JupiterActivity.loadNights(app);
        boolean same = back.size() == 1;
        if (same) {
            for (int k = 0; k < 4; k++) {
                if (back.get(0).seen[k] != nights.get(0).seen[k] || Math.abs(back.get(0).x[k] - nights.get(0).x[k]) > 0.01) same = false;
            }
        }
        check("kept in its file, and read back the same", same, back.size() + " read back");
        // stories: each chip
        Button[] chips = (Button[]) Moon.field(a, "storyChips");
        boolean allStories = true;
        for (Button c : chips) {
            c.performClick();
            String s = ((TextView) Moon.field(a, "storiesText")).getText().toString();
            if (s.length() < 200) allStories = false;
        }
        check("all nine stories there", chips.length == 9 && allStories, chips.length + " chips");
        chips[6].performClick();
        String romer = ((TextView) Moon.field(a, "storiesText")).getText().toString();
        check("Rømer's story with tonight's light-minutes", romer.contains("1676") && romer.contains("minutes to reach you"), "");

        // ---- leaving
        ctrl.pause().stop().destroy();
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        check("leaving the screen closes the camera", cam.closed && JupiterActivity.current == null, "");
        // a fresh screen shows the night on its page
        ActivityController<JupiterActivity> c2 = Robolectric.buildActivity(JupiterActivity.class).setup();
        @SuppressWarnings("unchecked")
        List<JupiterLook.Entry> n2 = (List<JupiterLook.Entry>) Moon.field(c2.get(), "nights");
        check("a fresh screen has tonight in its notebook", n2.size() == 1, n2.size() + " nights");
        c2.pause().stop().destroy();

        say(String.format(Locale.US, "== %d frames in %.0f s", tele.n, (System.currentTimeMillis() - started) / 1000.0));
        System.out.println(failed == 0 ? "ALL PASS" : failed + " FAILED");
        if (failed > 0) throw new AssertionError(failed + " checks failed");
    }

    /**
     * The sim's sensitivity that puts Jupiter's brightest pixel, sharp, where the meter's first guess aims: so a
     * multiple of it is a sensor that many times brighter than the guess.
     */
    static double guessTruth(JupiterSim sim, MoonCamera cam, MoonMeter.Exposure guess) {
        double e = sim.eV0 * Math.pow(10, -0.4 * sim.view.mag) * sim.air * guess.ns * 1e-9 * sim.peakShare(0.3);
        double perDn = sim.e100 * 100.0 / guess.iso;
        double level = e / perDn / (sim.white - sim.black);
        return JupiterMeter.TARGET / Math.max(1e-9, level);
    }

    int frames(int max, Until until) throws Exception {
        for (int k = 1; k <= max; k++) {
            if (!tele.frame()) throw new AssertionError("the camera closed");
            if (tele.failure != null) throw new AssertionError("the camera's frame code threw: " + tele.failure, tele.failure);
            ShadowLooper.idleMainLooper(step++ % 3 == 2 ? 34 : 33, TimeUnit.MILLISECONDS);
            tele.barrier();
            if (until != null && until.done()) return k;
        }
        return until == null ? max : -1;
    }

    /** Lets the screen's own threads work (no camera frames), up to {@code ms}. */
    void waitFor(Until until, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
            if (until.done()) return;
            Thread.sleep(20);
        }
    }

    String fromWatch(final String what) throws Exception {
        final String[] got = {null};
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                got[0] = MoonActivity.fromWatch(what);
            }
        }, "watch-command");
        t.start();
        for (int k = 0; k < 400 && t.isAlive(); k++) {
            if (JupiterActivity.current != null && cam != null && !cam.closed) frames(1, null);
            else ShadowLooper.idleMainLooper(33, TimeUnit.MILLISECONDS);
        }
        t.join(5000);
        return got[0] == null ? "(no answer)" : got[0];
    }

    String watchStatus() throws Exception {
        WatchLink wl = svc.watch();
        Method m = WatchLink.class.getDeclaredMethod("status", SessionController.Status.class);
        m.setAccessible(true);
        String[] s = (String[]) m.invoke(wl, svc.controller().status());
        return s[0];
    }

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
        Moon.save(b, name);
    }

    /** The whole scrolling page. */
    void whole(String name) throws Exception {
        View c = a.findViewById(android.R.id.content);
        ScrollView sv = Moon.findScroll(c);
        if (sv == null) return;
        View child = sv.getChildAt(0);
        int w = a.getResources().getDisplayMetrics().widthPixels;
        child.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = Math.max(1, child.getMeasuredHeight());
        child.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF000000);
        child.draw(new Canvas(b));
        Moon.save(b, name);
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        View v = a.findViewById(android.R.id.content);
        v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, dm.widthPixels, dm.heightPixels);
    }

    @SuppressWarnings("unused")
    static Intent sent(Intent started) {
        return Moon.sent(started);
    }
}
