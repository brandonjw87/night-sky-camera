package com.brandon.nightsky;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.BlackLevelPattern;
import android.media.MediaActionSound;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.brandon.nightsky.processing.MoonGeo;
import com.brandon.nightsky.processing.MoonLook;
import com.brandon.nightsky.processing.MoonMeter;
import com.brandon.nightsky.processing.MoonStack;
import com.brandon.nightsky.processing.TransitFinder;
import com.brandon.nightsky.remote.JsonTree;
import com.brandon.nightsky.sim.MoonSim;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.SensorEventBuilder;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowCameraCharacteristics;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaActionSound;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;
import org.robolectric.shadows.StreamConfigurationMapBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The Moonshot end to end (3.0), on a simulated Pixel 10 Pro XL: the real Moonshot screen and camera code, with a
 * simulated 5x telephoto under them ({@link SimTele}: whole 12-megapixel RAW frames of a simulated Moon, shaking in a
 * hand, a lens to focus and a sensor brighter than the meter's guess) and the phone's three back cameras described to
 * Android as a Pixel describes them, so the app picks the telephoto itself.
 * <p>
 * It finds the Moon, meters it, focuses on it and says it's ready; the watch's Shoot (from the watch's side of the
 * link, as the watch link calls it) takes a burst; it is stacked, shown and saved twice; a landing site is tapped.
 * Then the Transit Hunter: a crossing elsewhere (the map), then the ISS crossing the Moon right here in twenty
 * seconds; the watch's Catch arms it; the watch counts it down (its buzzes); the phone beeps and shoots by itself four
 * seconds before; the station crosses the simulated Moon in the frames at that moment; the stack finds it, names it
 * the ISS and draws its track. Then the Earth's glow (3.1): the simulated Moon made a crescent, the switch on, the
 * burst and the glow's brighter frames, the glow merged into the photo, saved four ways, its story, the Moon map
 * naming the night side. The screens and the watch's faces are drawn to PNG, and it ends by leaving the screen.
 * <p>
 * Usage: robo/run.sh Moon
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 37, qualifiers = "w412dp-h915dp-port-xhdpi", shadows = {FakeMoonCamera.class})
public class Moon {
    static int failed;
    static File out;
    static Application app;
    MoonActivity a;
    ActivityController<MoonActivity> ctrl;
    MoonCamera cam;
    SimTele tele;
    Locator.Place place;
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
    public void moonshot() throws Exception {
        long started = System.currentTimeMillis();
        out = new File(System.getProperty("shots.out", "build/robo"), "moon");
        //noinspection ResultOfMethodCallIgnored
        out.mkdirs();
        app = RuntimeEnvironment.getApplication();
        ShadowApplication sa = Shadows.shadowOf(app);
        sa.grantPermissions(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
        FakeMediaStore.install(new File(out, "media"));
        WatchProbe.setup(null, false);

        // a place where the Moon is well up now
        long now = System.currentTimeMillis();
        place = new Locator.Place();
        place.latDeg = 39.0;
        double bestLon = 0, bestAlt = -90;
        // the Moon rising in the east (or "robo/run.sh Moon west": setting in the west), so a run's sky doesn't flip
        // between the two with the clock
        boolean west = "west".equals(System.getProperty("shots.only", ""));
        for (double lon = -180; lon < 180; lon += 1) {
            MoonGeo.View v = MoonGeo.view(now, 39.0, lon);
            double alt = v.alt;
            if ((v.az > 180) != west) continue;
            if (Math.abs(alt - 50) < Math.abs(bestAlt - 50)) {
                bestAlt = alt;
                bestLon = lon;
            }
        }
        place.lonDeg = bestLon;
        place.altM = 120;
        place.accuracyM = 8;
        place.timeMs = now - 5 * 60_000L;
        place.source = "gps";
        new Prefs(app).savePlace(place);
        MoonGeo.View v0 = MoonGeo.view(now, place.latDeg, place.lonDeg);
        say(String.format(Locale.US, "place %.1f, %.1f: the Moon %.0f° up in the %s, %.0f%% lit", place.latDeg, place.lonDeg, v0.alt,
                MoonActivity.compass(v0.az), v0.illum * 100));

        // the Transit Hunter's list, as worked out from the orbits: two crossings elsewhere in the next days
        List<TransitFinder.Transit> elsewhere = new ArrayList<TransitFinder.Transit>();
        elsewhere.add(crossing(now + 2 * 86_400_000L + 3_600_000L, "the ISS", TransitFinder.ISS, 14.2, 45, 5.3, v0));
        elsewhere.add(crossing(now + 6 * 86_400_000L, "Tiangong", TransitFinder.TIANGONG, 37.0, 270, 12.0, v0));
        transits(elsewhere);

        // the phone's back cameras, as a Pixel 10 Pro XL describes them: main, ultrawide, the 5x telephoto
        CameraManager cm = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
        Shadows.shadowOf(cm).addCamera("0", camera(6.9f, 1.68f, 9.792f, 7.3728f, 10f));
        Shadows.shadowOf(cm).addCamera("2", camera(2.0f, 1.7f, 5.6f, 4.2f, 0f));
        Shadows.shadowOf(cm).addCamera("3", camera((float) SimTele.FOCAL_MM, (float) SimTele.F_NUMBER, (float) (SimTele.W * SimTele.PITCH_UM / 1000),
                (float) (SimTele.H * SimTele.PITCH_UM / 1000), 3f));
        SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        ShadowSensorManager ssm = Shadows.shadowOf(sm);
        Sensor grav = ShadowSensor.newInstance(Sensor.TYPE_GRAVITY);
        ssm.addSensor(grav);

        // ---- the screen opens, the camera with it
        say("== the Moonshot opens");
        ctrl = Robolectric.buildActivity(MoonActivity.class).setup();
        a = ctrl.get();
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
        // the Earth's glow (3.1) is offered only on a crescent, and tonight's Moon (the clock's) may be any phase: the
        // first shots are made as on a fuller Moon, and the glow gets a crescent of its own below
        glowAt(0.9);
        check("the screen is the Moonshot the watch reaches", MoonActivity.current == a, "");
        TextureView tv = (TextureView) field(a, "texture");
        TextureView.SurfaceTextureListener stl = tv.getSurfaceTextureListener();
        check("the camera waits for the picture's surface", stl != null && field(a, "camera") == null, "");
        if (stl == null) throw new AssertionError("no surface listener");
        stl.onSurfaceTextureAvailable(null, 1080, 1440);
        cam = (MoonCamera) field(a, "camera");
        check("the camera opened", cam != null && FakeMoonCamera.opened == cam, String.valueOf(field(a, "watchWords")));
        if (cam == null) throw new AssertionError("no camera");
        check("it chose the 5x telephoto of the three", cam.lens != null && "3".equals(cam.lens.openId) && cam.lens.eqMm > 95 && cam.lens.eqMm < 115,
                cam.lens == null ? "none" : cam.lens.describe());
        check("its pixels: 17 arcseconds each, the Moon's radius as many as tonight's Moon needs", Math.abs(cam.arcsecPerPx - 17.0) < 0.2
                && Math.abs(cam.rExp - v0.radius * 3600 / 17.0) < 2, String.format(Locale.US, "%.2f\"/px, %.1f px", cam.arcsecPerPx, cam.rExp));
        check("its lens focuses (a short sweep from infinity)", cam.focusLens != null && Math.abs(cam.focusLens.max - 0.4) < 1e-6,
                cam.focusLens == null ? "fixed" : String.format(Locale.US, "to %.2f in %.3f", cam.focusLens.max, cam.focusLens.fine));
        check("the tilt sensor is listened to", ssm.hasListener((android.hardware.SensorEventListener) field(cam, "tilt")), "");
        // the phone held tilted 12 degrees
        float[] g = {(float) (9.8 * Math.sin(Math.toRadians(12))), (float) (9.8 * Math.cos(Math.toRadians(12))), 0f};
        ssm.sendSensorEventToListeners(SensorEventBuilder.newBuilder().setSensor(grav).setValues(g).setTimestamp(1).build());
        double upPa = MoonGeo.upAngle(cam.moon.q, new double[]{g[0], g[1], g[2]}, MoonGeo.sensorUp(cam.spec.sensorOrientation));
        check("the picture's up from the tilt", Math.abs(MoonGeo.norm180(cam.upPa() - upPa)) < 1e-6, String.format(Locale.US, "%.1f°", cam.upPa()));
        long simMs = Math.round((cam.moon.jdUtc - 2440587.5) * 86_400_000.0);
        MoonSim sim = new MoonSim(simMs, place.latDeg, place.lonDeg, cam.arcsecPerPx, upPa, 4);
        sim.smoothBlur = true;
        tele = new SimTele(cam, sim, 1.6);
        say(String.format(Locale.US, "the simulated Moon: radius %.1f px, %.0f%% lit; first exposure %s, focus %.2f; the lens sharpest at %.2f",
                sim.rPx, sim.view.illum * 100, cam.exposure, cam.focus, SimTele.BEST_FOCUS));

        // ---- finding, metering, focusing
        say("== finding, metering and focusing");
        final MoonCamera.Stage[] last = {null};
        final int[] firstFound = {-1};
        final double[] foundOff = {Double.NaN}, maxClip = {0};
        final List<MoonCamera.Stage> seen = new ArrayList<MoonCamera.Stage>();
        int toReady = frames(600, new Until() {
            @Override
            public boolean done() {
                MoonCamera.Stage s = cam.stage;
                if (s != last[0]) {
                    seen.add(s);
                    say(String.format(Locale.US, "  frame %4d  %-9s %s · focus %.3f%s", tele.n, s, cam.exposure, cam.focus,
                            cam.spot != null ? String.format(Locale.US, " · Moon at %.0f,%.0f level %.2f clipped %.3f", cam.spot.x, cam.spot.y,
                                    cam.spot.level, cam.spot.clipped) : ""));
                    last[0] = s;
                }
                if (firstFound[0] < 0 && cam.spot != null && cam.spot.ok) {
                    firstFound[0] = tele.n;
                    foundOff[0] = Math.hypot(cam.spot.x - tele.lastMx, cam.spot.y - tele.lastMy);
                }
                if (s == MoonCamera.Stage.METERING && cam.spot != null) maxClip[0] = Math.max(maxClip[0], cam.spot.clipped);
                return s == MoonCamera.Stage.READY;
            }
        });
        check("found the Moon in the whole 12-megapixel frame, where it is", firstFound[0] >= 0 && firstFound[0] <= 3 && foundOff[0] < 0.3 * sim.rPx,
                String.format(Locale.US, "frame %d, %.1f px off", firstFound[0], foundOff[0]));
        check("went through looking, metering and focusing to ready", seen.contains(MoonCamera.Stage.METERING) && seen.contains(MoonCamera.Stage.FOCUSING)
                && cam.stage == MoonCamera.Stage.READY, seen.toString());
        check("ready within 15 seconds of frames", toReady > 0 && toReady <= 450, toReady + " frames");
        check("the brighter-than-guessed sensor clipped at first, then the meter brought it down", maxClip[0] > 0.003, String.format(Locale.US, "%.3f", maxClip[0]));
        check("metered: the bright parts at the target, nothing clipped", cam.spot != null && Math.abs(cam.spot.level / MoonMeter.TARGET - 1) < 0.2
                && cam.spot.clipped <= 0.003, cam.spot == null ? "none" : String.format(Locale.US, "level %.3f (target %.2f), clipped %.4f, %s", cam.spot.level,
                MoonMeter.TARGET, cam.spot.clipped, cam.exposure));
        check("focused where the lens is sharpest", Math.abs(cam.focus - SimTele.BEST_FOCUS) <= 0.015,
                String.format(Locale.US, "%.3f (sharpest %.2f)", cam.focus, SimTele.BEST_FOCUS));
        frames(10, null);
        Button shutter = (Button) field(a, "shutter");
        TextView status = (TextView) field(a, "status"), details = (TextView) field(a, "details");
        ImageView closeUp = (ImageView) field(a, "closeUp");
        check("the screen: ready, the shutter on, the close-up showing", shutter.isEnabled() && status.getText().toString().startsWith("Ready")
                && closeUp.getDrawable() != null, status.getText() + " / " + details.getText());
        check("it says which lens and what exposure", details.getText().toString().contains("telephoto") && details.getText().toString().contains("ISO"),
                details.getText().toString());
        screen("1_ready");

        // ---- the watch: the Moon screen, and its Shoot
        say("== the watch's Moon screen, and its Shoot");
        svc = Robolectric.buildService(CaptureService.class).create().get();
        String st = watchStatus();
        JsonTree.Obj moon = JsonTree.object(st).obj("moon");
        check("the watch hears the Moonshot open and ready", moon != null && moon.bool("open", false) && moon.bool("ready", false)
                && "READY".equals(moon.str("stage", "")), moon == null ? "no moon" : moon.toString());
        check("and the Moon tonight: phase, height, lit side", moon != null && moon.has("phase") && moon.has("alt") && moon.has("limb"),
                moon == null ? "" : moon.str("phase", "") + ", " + moon.num("alt", -99) + "°, limb " + moon.num("limb", -1));
        check("no crossing here soon: nothing to catch", moon != null && !moon.bool("canCatch", true), "");
        String w = WatchProbe.moonWords(st, System.currentTimeMillis());
        say("  watch: " + w);
        check("the watch's face: the phase, how much is lit, Shoot", w.contains("[Shoot, Back]") && w.contains("%") && w.contains("picture"), w);
        save(WatchProbe.moonDraw(app, st, System.currentTimeMillis(), 454), "watch_1_ready");
        glowAt(0.9);
        check("on a fuller Moon the Earth's glow isn't offered", ((View) field(a, "glowToggle")).getVisibility() == View.GONE, "");
        String said = fromWatch("shoot");
        check("the watch's Shoot: the phone shoots", said.startsWith("Shooting the Moon"), said);
        check("with no glow after the burst", cam.glowWant == 0, String.valueOf(cam.glowWant));
        check("with the shutter's sound", ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK) == 1,
                ShadowMediaActionSound.getPlayCount(MediaActionSound.SHUTTER_CLICK) + "");

        // ---- the burst, the stack, the photo
        say("== the burst and the stack");
        int burstFrames = frames(400, new Until() {
            @Override
            public boolean done() {
                return cam.burst == null && cam.stage == MoonCamera.Stage.READY && field(a, "job") != null;
            }
        });
        check("150 frames in five seconds", burstFrames > 140 && burstFrames < 170, burstFrames + " frames");
        final boolean[] shotStacking = {false};
        long t0 = System.currentTimeMillis();
        frames(20000, new Until() {
            @Override
            public boolean done() throws Exception {
                View bar = (View) field(a, "bar");
                if (!shotStacking[0] && bar.getVisibility() == View.VISIBLE && ((android.widget.ProgressBar) bar).getProgress() > 300) {
                    shotStacking[0] = true;
                    screen("2_stacking");
                }
                return field(a, "job") == null;
            }
        });
        say(String.format(Locale.US, "  stacked in %.1f s (while the camera kept streaming)", (System.currentTimeMillis() - t0) / 1000.0));
        frames(900, new Until() {
            @Override
            public boolean done() {
                return field(a, "savedMap") != null;
            }
        });
        MoonStack.Result r = (MoonStack.Result) field(a, "result");
        check("the photo is made", r != null && r.r != null, String.valueOf(field(a, "watchWords")));
        if (r == null) throw new AssertionError("no photo");
        say("  " + r.log);
        check("the sharpest frames kept of 150", r.total == 150 && r.used >= 30 && r.used <= 120, r.used + " of " + r.total);
        check("stacked and sharpened: its edge sharper than one frame's", r.edgeSharp < r.edgeSingle,
                String.format(Locale.US, "%.2f px against %.2f", r.edgeSharp, r.edgeSingle));
        MoonGeo.View vr = (MoonGeo.View) field(a, "view");
        check("the sky's up at the top (the tilt undone)", Math.abs(MoonGeo.norm180(r.upPa - vr.q)) < 0.5,
                String.format(Locale.US, "%.1f° against %.1f°", r.upPa, vr.q));
        MoonLook.Picture base = (MoonLook.Picture) field(a, "base");
        litSide(base, vr);
        check("nothing crossed it", r.crossing == null, r.crossing == null ? "" : r.crossing.toString());
        TextView title = firstText((ViewGroup) field(a, "resultBox"));
        check("the screen: \"Your Moon\"", title != null && title.getText().toString().equals("Your Moon"), title == null ? "" : title.getText().toString());
        savedThrice(1);
        screen("3_result");
        whole("3_result_page");
        save(bitmapOf(base), "3_photo_plain");
        // a landing tapped: its story
        @SuppressWarnings("unchecked")
        List<MoonLook.Label> labels = (List<MoonLook.Label>) field(a, "labels");
        MoonLook.Label apollo11 = null;
        for (MoonLook.Label l : labels) if (l.site.name.contains("Apollo 11")) apollo11 = l;
        check("the landings labelled, Apollo 11 among them", labels.size() >= 6 && apollo11 != null, labels.size() + " labels");
        if (apollo11 != null) {
            call(a, "tapped", new Class<?>[]{float.class, float.class}, (float) apollo11.x, (float) apollo11.y);
            String story = ((TextView) field(a, "storyText")).getText().toString();
            check("Apollo 11 tapped: its story", story.contains("Apollo 11") && story.contains("Tonight the Sun is"), story.substring(0, Math.min(90, story.length())));
        }
        // every label, and the mineral colours
        Button every = button((ViewGroup) field(a, "resultBox"), "Every one"), mineral = button((ViewGroup) field(a, "resultBox"), "Mineral colours");
        if (every != null) every.performClick();
        int all = ((List<?>) field(a, "labels")).size();
        check("\"Every one\": more places labelled", all > labels.size(), all + " against " + labels.size());
        if (mineral != null) mineral.performClick();
        save((Bitmap) field(a, "shown"), "3_photo_every_mineral");
        // the Moon map (3.1): the seas, craters and mountains named on the Moon itself
        Button moonMap = button((ViewGroup) field(a, "resultBox"), "Moon map");
        check("a Moon map button", moonMap != null, "");
        if (moonMap != null) {
            moonMap.performClick();
            @SuppressWarnings("unchecked")
            List<MoonLook.MapLabel> names = (List<MoonLook.MapLabel>) field(a, "mapLabels");
            int seas = 0, craters = 0, apollo = 0;
            MoonLook.MapLabel crater = null;
            for (MoonLook.MapLabel l : names) {
                if (l.site != null) apollo++;
                else if (l.feature.kind == com.brandon.nightsky.processing.MoonFeatures.Kind.SEA) seas++;
                else if (l.feature.kind == com.brandon.nightsky.processing.MoonFeatures.Kind.CRATER) {
                    craters++;
                    if (crater == null || l.feature.rank < crater.feature.rank) crater = l;
                }
            }
            check("the Moon map: seas, craters and the Apollo landings named", seas >= 5 && craters >= 10 && apollo == 6,
                    seas + " seas, " + craters + " craters, " + apollo + " landings of " + names.size() + " names");
            String hint = ((TextView) field(a, "storyText")).getText().toString();
            check("the card: how to get a story, and tonight's best", hint.contains("Tap a sea, a crater or a mountain range"), hint);
            save((Bitmap) field(a, "shown"), "3_photo_moon_map");
            if (crater != null) {
                call(a, "tapped", new Class<?>[]{float.class, float.class}, (float) ((crater.bx0 + crater.bx1) / 2), (float) ((crater.by0 + crater.by1) / 2));
                String story = ((TextView) field(a, "storyText")).getText().toString();
                check("a crater's name tapped: its story", story.startsWith(crater.feature.name + ", a crater") && story.contains("Tonight the Sun is"),
                        story.substring(0, Math.min(90, story.length())));
            }
            Button sh = button((ViewGroup) field(a, "resultBox"), "Share");
            if (sh != null) {
                sh.performClick();
                Intent sent = sent(Shadows.shadowOf(a).getNextStartedActivity());
                check("Share with the Moon map sends the map", sent != null && field(a, "savedMap") != null
                        && field(a, "savedMap").equals(sent.getParcelableExtra(Intent.EXTRA_STREAM)), String.valueOf(sent));
            }
        }
        Button famous = button((ViewGroup) field(a, "resultBox"), "Landings");
        if (famous != null) famous.performClick();
        if (mineral != null) mineral.performClick();
        String back = ((TextView) field(a, "storyText")).getText().toString();
        check("back to the landings: the card says so", back.contains("Tap a landing site"), back);
        st = watchStatus();
        w = WatchProbe.moonWords(st, System.currentTimeMillis());
        check("the watch hears the photo is ready", w.contains("Your Moon is ready"), w);

        // ---- the Transit Hunter: a crossing elsewhere, on the map
        say("== the Transit Hunter");
        String body = ((TextView) field(a, "transitBody")).getText().toString();
        check("the card lists the crossings near here", body.contains("14 km north-east") && body.contains("Tiangong"), body.substring(0, Math.min(120, body.length())));
        Button mapIt = (Button) field(a, "mapIt");
        check("the nearest strip on the map", mapIt.getVisibility() == View.VISIBLE && mapIt.getText().toString().contains("14 km north-east"),
                mapIt.getText().toString());
        mapIt.performClick();
        Intent map = Shadows.shadowOf(a).getNextStartedActivity();
        check("opens the map at the strip", map != null && map.getData() != null && "geo".equals(map.getData().getScheme()),
                map == null ? "nothing" : String.valueOf(map.getData()));

        // ---- the ISS crossing the Moon right here in twenty seconds
        final long when = System.currentTimeMillis() + 22_000L;
        List<TransitFinder.Transit> soon = new ArrayList<TransitFinder.Transit>();
        TransitFinder.Transit iss = crossing(when, "the ISS", TransitFinder.ISS, 0.4, 135, 0.2, v0);
        soon.add(iss);
        soon.addAll(elsewhere);
        transits(soon);
        call(a, "showTransits", new Class<?>[0]);
        final Button[] catchIt = {null};
        frames(300, new Until() {
            @Override
            public boolean done() {
                catchIt[0] = (Button) field(a, "catchIt");
                return catchIt[0] != null && catchIt[0].getVisibility() == View.VISIBLE;
            }
        });
        check("\"Catch the ISS\" offered", catchIt[0] != null && catchIt[0].getVisibility() == View.VISIBLE
                && catchIt[0].getText().toString().startsWith("Catch the ISS at"), catchIt[0] == null ? "" : catchIt[0].getText().toString());
        st = watchStatus();
        moon = JsonTree.object(st).obj("moon");
        JsonTree.Obj next = moon != null ? moon.obj("next") : null;
        check("the watch hears it: here, when, and it can be caught", moon != null && moon.bool("canCatch", false) && next != null
                && next.bool("here", false) && next.lng("at", 0) == when, next == null ? "no next" : next.toString());
        w = WatchProbe.moonWords(st, System.currentTimeMillis());
        say("  watch: " + w);
        check("the watch counts down to it, with Catch", w.contains("crosses the Moon") && w.contains("Catch"), w);
        save(WatchProbe.moonDraw(app, st, System.currentTimeMillis(), 454), "watch_2_catch");
        said = fromWatch("catch");
        check("the watch's Catch: the phone will shoot it", said.startsWith("The phone shoots the ISS at"), said);
        st = watchStatus();
        moon = JsonTree.object(st).obj("moon");
        check("the watch hears it armed", moon != null && moon.lng("armedAt", 0) == when && "the ISS".equals(moon.str("armedName", "")),
                moon == null ? "" : moon.toString());
        w = WatchProbe.moonWords(st, System.currentTimeMillis());
        say("  watch: " + w);
        check("the watch's countdown: the phone shoots by itself", w.contains("The phone shoots by itself") && w.contains(":"), w);
        save(WatchProbe.moonDraw(app, st, System.currentTimeMillis(), 454), "watch_3_countdown");
        // the watch's buzzes, as its clock would tick through the countdown
        int b0 = WatchProbe.buzzes();
        for (long t = when - 70_000; t <= when + 3000; t += 250) WatchProbe.moonTick(app, st, t);
        check("the watch buzzes at a minute, ten seconds, three, two, one and now", WatchProbe.buzzes() - b0 == 6 && "transit-now".equals(WatchProbe.lastBuzz()),
                (WatchProbe.buzzes() - b0) + " buzzes, the last " + WatchProbe.lastBuzz());
        // the phone counts down and shoots by itself
        final boolean[] shotCountdown = {false};
        final long[] burstAt = {0};
        frames(3000, new Until() {
            @Override
            public boolean done() throws Exception {
                long left = when - System.currentTimeMillis();
                if (!shotCountdown[0] && left < 12_000) {
                    shotCountdown[0] = true;
                    screen("4_countdown");
                }
                if (cam.burst != null && tele.crossNs == Long.MIN_VALUE) {
                    burstAt[0] = cam.burst.startMs;
                    // the station crosses at that moment in the frames from here on: the next frame (taken now, on the
                    // frames' clock) is the burst's first
                    tele.crossNs = android.os.SystemClock.elapsedRealtimeNanos() + (when - cam.burst.startMs) * 1_000_000L;
                    say(String.format(Locale.US, "  the burst started %.2f s before the crossing", (when - burstAt[0]) / 1000.0));
                }
                return tele.crossNs != Long.MIN_VALUE;
            }
        });
        int beeps = ShadowMediaActionSound.getPlayCount(MediaActionSound.FOCUS_COMPLETE);
        check("the countdown beeped once a second, ten to five, then the start sound", beeps >= 4 && beeps <= 6
                && ShadowMediaActionSound.getPlayCount(MediaActionSound.START_VIDEO_RECORDING) == 1,
                beeps + " beeps, " + ShadowMediaActionSound.getPlayCount(MediaActionSound.START_VIDEO_RECORDING) + " start");
        check("it shot by itself, about four seconds before", burstAt[0] > 0 && when - burstAt[0] <= 4300 && when - burstAt[0] >= 2500,
                burstAt[0] == 0 ? "never" : (when - burstAt[0]) + " ms before");
        int transitFrames = frames(600, new Until() {
            @Override
            public boolean done() {
                return cam.burst == null && field(a, "job") != null;
            }
        });
        check("270 frames, nine seconds", transitFrames > 260 && transitFrames < 290, transitFrames + " frames");
        say(String.format(Locale.US, "  the station was over the lit Moon in %d frames (%d to %d)", tele.stationFrames, tele.stationFirst, tele.stationLast));
        tele.crossNs = Long.MIN_VALUE;
        t0 = System.currentTimeMillis();
        frames(30000, new Until() {
            @Override
            public boolean done() {
                return field(a, "job") == null;
            }
        });
        say(String.format(Locale.US, "  stacked in %.1f s", (System.currentTimeMillis() - t0) / 1000.0));
        frames(900, new Until() {
            @Override
            public boolean done() {
                return field(a, "savedLabelled") != null && FakeMediaStore.rows.size() >= 6;
            }
        });
        r = (MoonStack.Result) field(a, "result");
        say("  " + (r != null ? r.log : "no result"));
        MoonStack.Crossing c = r != null ? r.crossing : null;
        check("the crossing found in the frames", c != null, String.valueOf(field(a, "watchWords")));
        if (c != null) {
            check("in the frames where the station was over the lit Moon", c.frames.length >= 0.6 * tele.stationFrames && c.frames.length <= tele.stationFrames + 3,
                    c.frames.length + " of " + tele.stationFrames);
            long mid = burstAt[0] + Math.round((c.firstS + c.lastS) / 2 * 1000);
            check("at the moment it was due", Math.abs(mid - when) < 600, (mid - when) + " ms off");
        }
        check("named: the ISS", "ISS".equals(field(a, "crossingLabel")), String.valueOf(field(a, "crossingLabel")));
        title = firstText((ViewGroup) field(a, "resultBox"));
        check("the screen: \"Your Moon, with ISS crossing it\"", title != null && title.getText().toString().equals("Your Moon, with ISS crossing it"),
                title == null ? "" : title.getText().toString());
        String story = ((TextView) field(a, "storyText")).getText().toString();
        check("its story", story.contains("International Space Station") && story.contains("610 km"), story.substring(0, Math.min(80, story.length())));
        savedThrice(2);
        screen("5_transit_result");
        whole("5_transit_result_page");
        st = watchStatus();
        w = WatchProbe.moonWords(st, System.currentTimeMillis());
        check("the watch hears it", w.contains("with ISS crossing it"), w);
        moon = JsonTree.object(st).obj("moon");
        check("the crossing over: nothing left to catch", moon != null && !moon.bool("canCatch", true) && !w.contains("Catch")
                && ((Button) field(a, "catchIt")).getVisibility() != View.VISIBLE, w);
        save(WatchProbe.moonDraw(app, st, System.currentTimeMillis(), 454), "watch_4_done");

        // ---- the result's other controls, as a person would try them (the monkey doesn't come in here)
        say("== the result's other controls");
        ViewGroup box = (ViewGroup) field(a, "resultBox");
        Button oneFrame = button(box, "One frame"), stackedB = button(box, "Stacked"), noLabels = button(box, "No labels"),
                landings = button(box, "Landings"), share = button(box, "Share"), again = button(box, "Shoot again");
        check("every control there", oneFrame != null && stackedB != null && noLabels != null && landings != null && share != null
                && again != null, "");
        if (oneFrame != null && stackedB != null && noLabels != null && landings != null && share != null && again != null) {
            int[] stackedPx = pixels((Bitmap) field(a, "shown"));
            oneFrame.performClick();
            int[] onePx = pixels((Bitmap) field(a, "shown"));
            check("One frame shows a single frame, not the stack", onePx != null && stackedPx != null && !java.util.Arrays.equals(onePx, stackedPx), "");
            stackedB.performClick();
            check("Stacked shows the stack again", java.util.Arrays.equals(pixels((Bitmap) field(a, "shown")), stackedPx), "");
            noLabels.performClick();
            share.performClick();
            Intent sent = sent(Shadows.shadowOf(a).getNextStartedActivity());
            check("Share with No labels sends the plain photo", sent != null && Intent.ACTION_SEND.equals(sent.getAction())
                    && field(a, "savedPlain") != null && field(a, "savedPlain").equals(sent.getParcelableExtra(Intent.EXTRA_STREAM)), String.valueOf(sent));
            landings.performClick();
            share.performClick();
            sent = sent(Shadows.shadowOf(a).getNextStartedActivity());
            check("Share with Landings sends the labelled one", sent != null && field(a, "savedLabelled") != null
                    && field(a, "savedLabelled").equals(sent.getParcelableExtra(Intent.EXTRA_STREAM)), String.valueOf(sent));
            again.performClick();
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
            check("Shoot again: back to the live picture, ready to shoot", box.getVisibility() == View.GONE && shutter.isEnabled(),
                    status.getText().toString());
        }
        ((Button) field(a, "refocus")).performClick();
        int k = frames(900, new Until() {
            @Override
            public boolean done() {
                return cam.stage == MoonCamera.Stage.READY;
            }
        });
        check("Focus again: focused again", k > 0 && Math.abs(cam.focus - SimTele.BEST_FOCUS) < 0.02,
                String.format(Locale.US, "%d frames, focus %.3f", k, cam.focus));

        earthsGlow(upPa, simMs, shutter, status);

        // ---- leaving
        say("== leaving the screen");
        Button done = button((ViewGroup) a.getWindow().getDecorView(), "Done");
        if (done != null) done.performClick();
        check("Done closes the screen", done != null && a.isFinishing(), "");
        ctrl.pause();
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        check("the camera closed with it", cam.closed && field(a, "camera") == null, "");
        check("the watch can't reach it any more", MoonActivity.current == null && fromWatch("shoot").startsWith("Open the Moonshot"), "");
        st = watchStatus();
        moon = JsonTree.object(st).obj("moon");
        check("the watch hears it closed", moon != null && !moon.bool("open", true), "");
        ctrl.stop().destroy();
        check("the camera's frame code never threw", tele.failure == null, tele.failure == null ? "" : tele.failure.toString());
        say(String.format(Locale.US, "%d frames, %.0f s", tele.n, (System.currentTimeMillis() - started) / 1000.0));
        say(failed == 0 ? "ALL PASS" : failed + " FAILED");
        if (failed > 0) throw new AssertionError(failed + " checks failed");
    }

    // ------------------------------------------------------------------------------------------------
    // the Earth's glow (3.1)

    /** The Moon tonight as lit as {@code illum} for the screen's Earth's glow switch (shown on a crescent). */
    void glowAt(double illum) throws Exception {
        setField(a, "tonightIllum", illum);
        call(a, "glowStyle", new Class<?>[0]);
    }

    /**
     * The Earth's glow on a crescent: tonight's simulated Moon made a 15% crescent (its lit side where tonight's is,
     * the Earth's glow on its night side), the switch on, Shoot: the burst, then the glow's frames at the brightest
     * exposure the frame rate and ISO allow, then the burst's exposure back; the stack, the glow merged into it, the
     * photo with the glow shown and saved four ways, its story, the Moon map naming the night side, and the chips.
     */
    void earthsGlow(double upPa, long simMs, final Button shutter, final TextView status) throws Exception {
        say("== the Earth's glow on a crescent");
        // the glow as faint as a real one (6e-5 of the full Moon: a few thousandths of the crescent's light), a little bluer
        MoonSim cres = new MoonSim(simMs, place.latDeg, place.lonDeg, cam.arcsecPerPx, upPa, 4, null, 6e-5, new double[]{0.92, 1.0, 1.12}, 0.15);
        cres.smoothBlur = true;
        tele.sim = cres;
        glowAt(0.15);
        Button glowT = (Button) field(a, "glowToggle");
        TextView help = (TextView) field(a, "shutterHelp");
        check("on a crescent the Earth's glow is offered, on", glowT.getVisibility() == View.VISIBLE && glowT.getText().toString().contains(": on"),
                glowT.getText().toString());
        check("the shutter's help says what it takes", help.getText().toString().contains("Earth's glow"), help.getText().toString());
        glowT.performClick();
        check("the switch turns it off", glowT.getText().toString().contains(": off") && !help.getText().toString().contains("Earth's glow"),
                glowT.getText().toString());
        glowT.performClick();
        // the crescent is dimmer than tonight's Moon was: the camera puts its exposure right, still ready
        int settle = frames(400, new Until() {
            @Override
            public boolean done() {
                return cam.stage == MoonCamera.Stage.READY && cam.spot != null && cam.spot.ok && cam.spot.clipped <= 0.003
                        && Math.abs(cam.spot.level / MoonMeter.TARGET - 1) <= 0.25;
            }
        });
        check("the crescent metered", settle > 0, cam.spot == null ? "no Moon" : String.format(Locale.US, "level %.2f clipped %.4f, %s",
                cam.spot.level, cam.spot.clipped, cam.exposure));
        frames(10, null);
        screen("6_glow_ready");
        final MoonMeter.Exposure ex0 = cam.exposure;
        final int rows0 = FakeMediaStore.rows.size();
        final double[] most = {0};
        final String[] words = {"", ""};
        final MoonMeter.Exposure[] after = {null};
        shutter.performClick();
        check("Shoot: the glow wanted after the burst", cam.glowWant == MoonCamera.GLOW, String.valueOf(cam.glowWant));
        int n = frames(900, new Until() {
            @Override
            public boolean done() {
                if (cam.stage == MoonCamera.Stage.GLOW) {
                    most[0] = Math.max(most[0], cam.exposure.light() / ex0.light());
                    if (words[0].isEmpty() && status.getText().toString().startsWith("The Earth's glow:")) words[0] = status.getText().toString();
                    if (words[1].isEmpty() && shutter.getText().toString().startsWith("Earth's glow")) words[1] = shutter.getText().toString();
                }
                if (after[0] == null && cam.burst == null && cam.stage == MoonCamera.Stage.READY) after[0] = cam.exposure;
                return cam.burst == null && cam.stage == MoonCamera.Stage.READY && field(a, "job") != null;
            }
        });
        check("150 frames, then the glow's 120 (and a few while the exposure changed)", n >= 270 && n <= 270 + MoonCamera.GLOW_WAIT + 15, n + " frames");
        check("the glow's frames many times brighter", most[0] >= MoonCamera.GLOW_MIN && most[0] <= MoonCamera.GLOW_GAIN * 1.01,
                String.format(Locale.US, "%.0fx (%s, the burst's %s)", most[0], cam.exposure, ex0));
        check("then the burst's exposure back", after[0] != null && after[0].ns == ex0.ns && after[0].iso == ex0.iso,
                after[0] == null ? "never" : after[0] + " against " + ex0);
        check("the screen said so while they came", words[0].contains("of " + MoonCamera.GLOW) && words[1].contains("of " + MoonCamera.GLOW),
                words[0] + " / " + words[1]);
        long t0 = System.currentTimeMillis();
        frames(30000, new Until() {
            @Override
            public boolean done() {
                return field(a, "job") == null;
            }
        });
        say(String.format(Locale.US, "  stacked and merged in %.1f s", (System.currentTimeMillis() - t0) / 1000.0));
        frames(900, new Until() {
            @Override
            public boolean done() {
                return field(a, "savedNoGlow") != null && FakeMediaStore.rows.size() >= rows0 + 4;
            }
        });
        MoonStack.Result r = (MoonStack.Result) field(a, "result");
        say("  " + (r != null ? r.log : "no result"));
        com.brandon.nightsky.processing.Earthshine.Result es = (com.brandon.nightsky.processing.Earthshine.Result) field(a, "glowResult");
        say("  " + (es != null ? es.log : "no glow merged"));
        check("the glow merged", es != null && es.ok, es == null ? "none" : es.log.substring(Math.max(0, es.log.length() - 60)));
        if (es == null || !es.ok) return;
        check("all 120 of its frames given, most merged", es.total == MoonCamera.GLOW && es.used >= 0.75 * es.total, es.used + " of " + es.total);
        MoonLook.Picture gp = (MoonLook.Picture) field(a, "glowPic"), sp = (MoonLook.Picture) field(a, "base");
        check("the photo with the glow made", gp != null && sp != null && gp.w == sp.w && gp.cx == sp.cx && gp.r == sp.r, "");
        if (gp == null || sp == null) return;
        // the night side: black in the stack, a soft grey with the glow; the sky round it black in both
        double nightS = 0, nightG = 0, skyMax = 0;
        int nn = 0;
        List<Double> sky = new ArrayList<Double>();
        for (int y = 0; y < gp.h; y += 2) {
            for (int x = 0; x < gp.w; x += 2) {
                double d = Math.hypot(x - gp.cx, y - gp.cy) / gp.r;
                int k = y * gp.w + x;
                if (d < 0.85 && luma(sp.argb[k]) < 6) {
                    nightS += luma(sp.argb[k]);
                    nightG += luma(gp.argb[k]);
                    nn++;
                } else if (d > 1.12 && d < 1.35) {
                    sky.add(luma(gp.argb[k]));
                }
            }
        }
        java.util.Collections.sort(sky);
        double sky95 = sky.isEmpty() ? 0 : sky.get((int) (0.95 * (sky.size() - 1)));
        check("the night side: black in the stack, grey with the Earth's glow", nn > 500 && nightS / nn < 6 && nightG / nn > 25,
                String.format(Locale.US, "%.0f against %.0f of 255 over %d px", nightG / Math.max(1, nn), nightS / Math.max(1, nn), nn));
        check("the sky round it still black", sky95 < 20, String.format(Locale.US, "95%%: %.0f of 255", sky95));
        // past the Moon's true edge no rim round the sunlit crescent: nothing brighter than the stack's photo, no purple
        // (3.1's first robot runs: a purple line and a grey band there, the stack's last light lifted with the night).
        // Judged against the brightest of the stack photo's pixel and its eight neighbours (a seventh of the stack's pixel
        // either way): the sunlit limb's own light reaches past the true (dark) edge and falls steeply there, where the two
        // photos' smallest differences came to 9 and 10 of 255 against the same pixel alone (3.3's runs with the Moon in
        // the west; 4 within a pixel); a grey band or a rim lies on the flat dark sky past the limb, where a pixel's give
        // changes little
        double rimMost = -255, mostPast = 0, mostCos = 0, samePx = 0;
        int rimColoured = 0;
        double rk = r.radius / gp.r;
        // the sunlit side: from the disk's centre towards the crescent's light in the stack's photo
        double lx = 0, ly = 0;
        for (int y = 0; y < gp.h; y += 2) {
            for (int x = 0; x < gp.w; x += 2) {
                double l = luma(sp.argb[y * gp.w + x]);
                if (l > 128) {
                    lx += (x - gp.cx) * l;
                    ly += (y - gp.cy) * l;
                }
            }
        }
        double ln = Math.max(1e-9, Math.hypot(lx, ly));
        lx /= ln;
        ly /= ln;
        double[] limbMost = {-255, -255};
        for (int y = 1; y < gp.h - 1; y++) {
            for (int x = 1; x < gp.w - 1; x++) {
                double sx = r.cx + (x - gp.cx) * rk, sy = r.cy + (y - gp.cy) * rk;
                double past = Math.hypot(sx - es.moonCx, sy - es.moonCy) - es.moonR;
                if (past < 1.5 || past > 16) continue;
                int c = gp.argb[y * gp.w + x];
                double near = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) near = Math.max(near, luma(sp.argb[(y + dy) * gp.w + x + dx]));
                }
                double g = luma(c), lift = g - near;
                samePx = Math.max(samePx, g - luma(sp.argb[y * gp.w + x]));
                double cos = ((x - gp.cx) * lx + (y - gp.cy) * ly) / Math.max(1e-9, Math.hypot(x - gp.cx, y - gp.cy));
                int limb = cos > 0 ? 0 : 1;
                limbMost[limb] = Math.max(limbMost[limb], lift);
                if (lift > rimMost) {
                    rimMost = lift;
                    mostPast = past;
                    mostCos = cos;
                }
                int cr = (c >> 16) & 255, cg = (c >> 8) & 255, cb = c & 255;
                if (Math.min(cr, cb) - cg > 12 || cg - Math.max(cr, cb) > 12) rimColoured++;
            }
        }
        check("no rim past the Moon's edge: nothing brighter than in the stack's photo, no purple", rimMost < 10 && rimColoured == 0,
                String.format(Locale.US, "%.0f of 255 brighter at the most (%.1f px past the edge, %.0f° from the sunlit side; the sunlit "
                                + "limb %.0f, the night limb %.0f; against the same pixel alone %.0f), %d px coloured", rimMost, mostPast,
                        Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, mostCos)))), limbMost[0], limbMost[1], samePx, rimColoured));
        ViewGroup box = (ViewGroup) field(a, "resultBox");
        TextView title = firstText(box);
        check("the screen: \"Your Moon, with the Earth's glow\"", title != null && title.getText().toString().equals("Your Moon, with the Earth's glow"),
                title == null ? "" : title.getText().toString());
        String how = "";
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            if (v instanceof TextView && !(v instanceof Button) && ((TextView) v).getText().toString().startsWith("The ")) how = ((TextView) v).getText().toString();
        }
        check("how it was made, the glow too", how.contains("times brighter for the Earth's glow"), how);
        Button bGlow = button(box, "Earth's glow"), bStack = button(box, "Stacked"), bMap = button(box, "Moon map"), bNone = button(box, "No labels"),
                bShare = button(box, "Share");
        check("an Earth's glow chip, chosen", bGlow != null && ((Integer) field(a, "look")) == 2, String.valueOf(field(a, "look")));
        String story = ((TextView) field(a, "storyText")).getText().toString();
        check("its story on the card: Leonardo da Vinci, the Earth in the Moon's sky", story.contains("Leonardo da Vinci") && story.contains("as bright as the sunlit crescent")
                && story.contains("From the Moon tonight the Earth is"), story.substring(0, Math.min(140, story.length())));
        // saved four ways: with the glow (plain, landings, map) and the stack without it
        Uri noGlow = (Uri) field(a, "savedNoGlow"), plain = (Uri) field(a, "savedPlain");
        String nNo = noGlow != null ? FakeMediaStore.name(noGlow) : null;
        check("saved four times more: with the glow three ways, and without it", FakeMediaStore.rows.size() == rows0 + 4 && nNo != null && nNo.endsWith("_noglow.jpg")
                && plain != null, (FakeMediaStore.rows.size() - rows0) + " saved, " + nNo);
        File fg = plain != null ? FakeMediaStore.file(plain) : null, fn = noGlow != null ? FakeMediaStore.file(noGlow) : null;
        Bitmap jg = fg != null ? BitmapFactory.decodeFile(fg.getPath()) : null, jn = fn != null ? BitmapFactory.decodeFile(fn.getPath()) : null;
        check("whole JPEGs, the saved one with the glow brighter on the night side", jg != null && jn != null && jg.getWidth() == MoonLook.SIDE
                && nightMean(jg, gp, sp) > nightMean(jn, gp, sp) + 15, jg == null || jn == null ? "missing" : String.format(Locale.US, "%.0f against %.0f",
                nightMean(jg, gp, sp), nightMean(jn, gp, sp)));
        // the screen's Moon is tonight's (the clock's), the simulated one a crescent: the screen told where the Sun is on
        // the crescent, so the map names what is in the dark on it
        MoonGeo.View sv = (MoonGeo.View) field(a, "view");
        sv.l0 = cres.subSolarLon;
        sv.b0 = cres.subSolarLat;
        sv.illum = cres.litShare;
        screen("6_glow_result");
        whole("6_glow_result_page");
        save(bitmapOf(gp), "6_photo_glow");
        save(bitmapOf(sp), "6_photo_glow_stack");
        String st = watchStatus();
        String w = WatchProbe.moonWords(st, System.currentTimeMillis());
        check("the watch hears it", w.contains("with the Earth's glow, is ready"), w);
        // the Moon map with the glow: the night side's seas and bright craters named too
        if (bMap != null) {
            bMap.performClick();
            @SuppressWarnings("unchecked")
            List<MoonLook.MapLabel> names = (List<MoonLook.MapLabel>) field(a, "mapLabels");
            MoonLook.MapLabel lit = null;
            int earthlit = 0;
            for (MoonLook.MapLabel l : names) {
                if (l.earthlit) {
                    earthlit++;
                    if (lit == null || l.feature.kind == com.brandon.nightsky.processing.MoonFeatures.Kind.SEA) lit = l;
                }
            }
            check("the Moon map names the night side's seas by the Earth's glow", earthlit >= 3, earthlit + " of " + names.size() + " names");
            save((Bitmap) field(a, "shown"), "6_photo_glow_map");
            if (lit != null) {
                call(a, "tapped", new Class<?>[]{float.class, float.class}, (float) ((lit.bx0 + lit.bx1) / 2), (float) ((lit.by0 + lit.by1) / 2));
                String ls = ((TextView) field(a, "storyText")).getText().toString();
                check("a night-side name tapped: its story, seen by the Earth's glow", ls.contains("you see it by the Earth's glow"),
                        ls.substring(Math.max(0, ls.length() - 100)));
            }
        }
        // Stacked: the stack without the glow, the card without its story; No labels and Share: the photo without it
        if (bStack != null && bNone != null && bShare != null && bGlow != null) {
            int[] withGlow = pixels((Bitmap) field(a, "shown"));
            bStack.performClick();
            check("Stacked shows the stack without the glow", !java.util.Arrays.equals(pixels((Bitmap) field(a, "shown")), withGlow)
                    && !((TextView) field(a, "storyText")).getText().toString().contains("Leonardo"), "");
            bNone.performClick();
            bShare.performClick();
            Intent sent = sent(Shadows.shadowOf(a).getNextStartedActivity());
            check("Share there sends the photo without the glow", sent != null && noGlow != null && noGlow.equals(sent.getParcelableExtra(Intent.EXTRA_STREAM)),
                    String.valueOf(sent));
            bGlow.performClick();
            check("Earth's glow: back, with its story", ((TextView) field(a, "storyText")).getText().toString().contains("Leonardo"), "");
            bShare.performClick();
            sent = sent(Shadows.shadowOf(a).getNextStartedActivity());
            check("and Share sends the one with the glow", sent != null && plain != null && plain.equals(sent.getParcelableExtra(Intent.EXTRA_STREAM)),
                    String.valueOf(sent));
        }
    }

    static double luma(int c) {
        return ((c >> 16) & 255) * 0.2126 + ((c >> 8) & 255) * 0.7152 + (c & 255) * 0.0722;
    }

    /** A saved photo's mean on the night side (where the stack {@code sp} is black inside the disk of {@code gp}). */
    static double nightMean(Bitmap b, MoonLook.Picture gp, MoonLook.Picture sp) {
        double s = 0;
        int n = 0;
        for (int y = 0; y < gp.h; y += 3) {
            for (int x = 0; x < gp.w; x += 3) {
                if (Math.hypot(x - gp.cx, y - gp.cy) / gp.r < 0.85 && luma(sp.argb[y * gp.w + x]) < 6) {
                    s += luma(b.getPixel(x, y));
                    n++;
                }
            }
        }
        return n == 0 ? 0 : s / n;
    }

    // ------------------------------------------------------------------------------------------------
    // driving it

    /**
     * Frames from the telephoto (each with the screen's thread run on a frame's time, 30 a second) until {@code until}
     * says so, at most {@code max}: how many it took (-1 if it never did).
     */
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

    /** A command from the watch, as the watch link gives it (from another thread, waiting on the screen's), while the camera runs. */
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
            if (MoonActivity.current != null && cam != null && !cam.closed) frames(1, null);
            else ShadowLooper.idleMainLooper(33, TimeUnit.MILLISECONDS);
        }
        t.join(5000);
        return got[0] == null ? "(no answer)" : got[0];
    }

    /** The status message the watch link sends now (its JSON). */
    String watchStatus() throws Exception {
        WatchLink wl = svc.watch();
        Method m = WatchLink.class.getDeclaredMethod("status", SessionController.Status.class);
        m.setAccessible(true);
        String[] s = (String[]) m.invoke(wl, svc.controller().status());
        return s[0];
    }

    /** The Transit Hunter's list as if just worked out for this place. */
    void transits(List<TransitFinder.Transit> l) throws Exception {
        synchronized (Transits.class) {
            setStatic(Transits.class, "cached", l);
            setStatic(Transits.class, "cachedAt", System.currentTimeMillis());
            setStatic(Transits.class, "cachedLat", place.latDeg);
            setStatic(Transits.class, "cachedLon", place.lonDeg);
            setStatic(Transits.class, "cachedWhy", "");
        }
    }

    TransitFinder.Transit crossing(long at, String name, int norad, double km, double bearing, double miss, MoonGeo.View v) {
        TransitFinder.Transit t = new TransitFinder.Transit();
        t.norad = norad;
        t.name = name;
        t.timeMs = at;
        t.hereMs = at;
        double[] p = TransitTest_offset(place.latDeg, place.lonDeg, km, bearing);
        t.lat = p[0];
        t.lon = p[1];
        t.distKm = km;
        t.bearingDeg = bearing;
        t.halfWidthKm = 3.2;
        t.pathDeg = bearing + 90;
        t.rangeKm = 610;
        t.durationS = 0.65;
        t.hereS = miss < 1 ? 0.62 : 0;
        t.moonAlt = v.alt;
        t.moonAz = v.az;
        t.moonRadiusDeg = v.radius;
        t.illum = v.illum;
        t.moonAltLine = v.alt;
        t.sunAlt = -25;
        t.missRadii = miss;
        t.sizeArcsec = 37;
        t.ageDays = 0.4;
        return t;
    }

    static double[] TransitTest_offset(double lat, double lon, double km, double bearing) {
        double b = Math.toRadians(bearing);
        return new double[]{lat + km * Math.cos(b) / 111.2, lon + km * Math.sin(b) / (111.2 * Math.cos(Math.toRadians(lat)))};
    }

    /** A back camera as Android describes it: 12-megapixel RAW, manual controls. */
    static CameraCharacteristics camera(float focalMm, float fNumber, float sensorW, float sensorH, float minFocus) {
        CameraCharacteristics cc = ShadowCameraCharacteristics.newCameraCharacteristics();
        ShadowCameraCharacteristics s = Shadow.extract(cc);
        int w = SimTele.W, h = SimTele.H;
        s.set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK);
        s.set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, new int[]{0, 1, 3});
        s.set(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE, CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME);
        s.set(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE, new Range<Integer>(50, 6400));
        s.set(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY, 800);
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

    // ------------------------------------------------------------------------------------------------
    // checking it

    /** The lit side where the sky has it, in the photo (when the Moon is a phase with a side to tell). */
    void litSide(MoonLook.Picture p, MoonGeo.View v) {
        if (v.illum > 0.9 || v.illum < 0.08) {
            say(String.format(Locale.US, "  (the lit side not checked: %.0f%% lit has no side to tell)", v.illum * 100));
            return;
        }
        double sx = 0, sy = 0, sw = 0;
        for (int y = 0; y < p.h; y++) {
            for (int x = 0; x < p.w; x++) {
                double dx = x - p.cx, dy = y - p.cy;
                if (dx * dx + dy * dy > p.r * p.r) continue;
                int c = p.argb[y * p.w + x];
                double l = ((c >> 16) & 255) * 0.2126 + ((c >> 8) & 255) * 0.7152 + (c & 255) * 0.0722;
                sx += dx * l;
                sy += dy * l;
                sw += l;
            }
        }
        double t = Math.toRadians(v.chi - v.q);
        double ex = -Math.sin(t), ey = -Math.cos(t);
        double ang = Math.toDegrees(Math.acos((sx * ex + sy * ey) / Math.max(1e-9, Math.hypot(sx, sy))));
        check("the lit side where the sky has it", ang < 15, String.format(Locale.US, "%.1f° off", ang));
    }

    /** The photos saved so far: three a shot (plain, labelled, and the Moon map since 3.1), decodable, named. */
    void savedThrice(int shots) throws Exception {
        Uri plain = (Uri) field(a, "savedPlain"), labelled = (Uri) field(a, "savedLabelled"), map = (Uri) field(a, "savedMap");
        File fp = plain != null ? FakeMediaStore.file(plain) : null, fl = labelled != null ? FakeMediaStore.file(labelled) : null,
                fm = map != null ? FakeMediaStore.file(map) : null;
        check("saved three times more", FakeMediaStore.rows.size() == 3 * shots && fp != null && fl != null && fm != null && fp.length() > 20_000
                && fl.length() > 20_000 && fm.length() > 20_000, FakeMediaStore.rows.size() + " saved");
        if (fp == null || fl == null || fm == null) return;
        String np = FakeMediaStore.name(plain), nl = FakeMediaStore.name(labelled), nm = FakeMediaStore.name(map);
        check("named for the moment it was shot", np != null && np.startsWith("Moonshot_") && nl != null && nl.endsWith("_landings.jpg")
                && nm != null && nm.endsWith("_map.jpg"), np + ", " + nl + ", " + nm);
        Bitmap bp = BitmapFactory.decodeFile(fp.getPath()), bl = BitmapFactory.decodeFile(fl.getPath()), bm = BitmapFactory.decodeFile(fm.getPath());
        check("whole JPEGs", bp != null && bl != null && bm != null && bp.getWidth() == MoonLook.SIDE && bl.getHeight() == MoonLook.SIDE
                && bm.getWidth() == MoonLook.SIDE, bp == null ? "no plain" : bp.getWidth() + " x " + bp.getHeight());
        if (bp == null || bl == null || bm == null) return;
        int diff = 0, diffMap = 0;
        for (int y = 0; y < bp.getHeight(); y += 2) {
            for (int x = 0; x < bp.getWidth(); x += 2) {
                if (Math.abs((bp.getPixel(x, y) & 255) - (bl.getPixel(x, y) & 255)) > 40) diff++;
                if (Math.abs((bp.getPixel(x, y) & 255) - (bm.getPixel(x, y) & 255)) > 40) diffMap++;
            }
        }
        check("the labelled one has its labels", diff > bp.getWidth() * bp.getHeight() / 4 / 400, diff + " pixels differ");
        check("the Moon map has its names", diffMap > bp.getWidth() * bp.getHeight() / 4 / 400, diffMap + " pixels differ");
        save(bl, "saved_" + shots + "_labelled");
        save(bm, "saved_" + shots + "_map");
    }

    // ------------------------------------------------------------------------------------------------
    // the screens

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
        save(b, name);
    }

    /** The whole scrolling page. */
    void whole(String name) throws Exception {
        View c = a.findViewById(android.R.id.content);
        ScrollView sv = findScroll(c);
        if (sv == null) return;
        View child = sv.getChildAt(0);
        int w = a.getResources().getDisplayMetrics().widthPixels;
        child.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = Math.max(1, child.getMeasuredHeight());
        child.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF000000);
        child.draw(new Canvas(b));
        save(b, name);
        // back to the screen's own layout
        screenLayout();
    }

    void screenLayout() {
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        View v = a.findViewById(android.R.id.content);
        v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, dm.widthPixels, dm.heightPixels);
    }

    static ScrollView findScroll(View v) {
        if (v instanceof ScrollView) return (ScrollView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ScrollView s = findScroll(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    static TextView firstText(ViewGroup g) {
        if (g == null) return null;
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof TextView && !(v instanceof Button)) return (TextView) v;
        }
        return null;
    }

    /** A picture's pixels (null for none). */
    static int[] pixels(Bitmap b) {
        if (b == null) return null;
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        return px;
    }

    /** What a share sends: the intent inside Android's chooser (null for none). */
    @SuppressWarnings("deprecation")
    static Intent sent(Intent started) {
        if (started == null) return null;
        if (Intent.ACTION_CHOOSER.equals(started.getAction())) return (Intent) started.getParcelableExtra(Intent.EXTRA_INTENT);
        return started;
    }

    static Button button(ViewGroup g, String label) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof Button && ((Button) v).getText().toString().equals(label)) return (Button) v;
            if (v instanceof ViewGroup) {
                Button b = button((ViewGroup) v, label);
                if (b != null) return b;
            }
        }
        return null;
    }

    static Bitmap bitmapOf(MoonLook.Picture p) {
        Bitmap b = Bitmap.createBitmap(p.w, p.h, Bitmap.Config.ARGB_8888);
        b.setPixels(p.argb, 0, p.w, 0, 0, p.w, p.h);
        return b;
    }

    static void save(Bitmap b, String name) throws Exception {
        if (b == null) {
            say("(no picture for " + name + ")");
            return;
        }
        OutputStream os = new FileOutputStream(new File(out, name + ".png"));
        b.compress(Bitmap.CompressFormat.PNG, 100, os);
        os.close();
        say("saved " + name + ".png " + b.getWidth() + "x" + b.getHeight());
    }

    // ------------------------------------------------------------------------------------------------
    // reflection

    static Object field(Object o, String name) {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("no field " + name);
    }

    static void setField(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    static void setStatic(Class<?> c, String name, Object v) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    @SuppressWarnings("unused")
    private static String clock(long t) {
        return new SimpleDateFormat("h:mm:ss a", Locale.US).format(new Date(t));
    }

    @SuppressWarnings("unused")
    private static final Class<?>[] KEEP = {LinearLayout.class};
}
