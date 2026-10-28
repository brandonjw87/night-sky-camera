package com.brandon.nightsky;

import android.Manifest;
import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.os.SystemClock;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.View;

import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraFacts;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.NightPlan;
import com.brandon.nightsky.processing.Tonight;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.SensorEventBuilder;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowCameraCharacteristics;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;
import org.robolectric.shadows.ShadowStatFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Whole sessions through the real capture code: the app as Brandon uses it (Start on the home screen, the phone put
 * down screen-up... screen-down), the session controller's own state machine, focus sweep, pre-flight check,
 * countdown, capture, live stacking and developing, with the camera simulated ({@link FakeRig}, {@link SimCamera}:
 * the real stars at this time and place) and the phone's motion sensors fed as it lies on the ground.
 * <p>
 * The fake clock runs {@code night.speed} times as fast as the real one (it waits for an exposure still being drawn,
 * and keeps to real time while the phone is busy measuring or stacking), so the app's timeouts see what they would.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 36, qualifiers = "w412dp-h915dp-port-xhdpi", shadows = {FakeRig.class},
        instrumentedPackages = {"com.brandon.nightsky.CameraRig"})
public class Night {
    static final long STEP_MS = 50;
    static double speed;
    static ShadowSensorManager ssm;
    static Sensor gravity, gyro, accel, rotation;
    /** The phone lies screen-down (else it's held), and how much it shakes (rad/s). */
    static volatile boolean down;
    static volatile double shake = 0.3;
    static double headingMag;
    static Locator.Place place;
    static BrightStars catalog;
    static final Random rnd = new Random(5);
    static final List<String> summary = new ArrayList<String>();
    static String scenario = "";

    @Test
    public void night() throws Exception {
        speed = Double.parseDouble(System.getProperty("night.speed", "2"));
        setup();
        for (String sc : System.getProperty("night.only", "base").split(",")) {
            scenario = sc.trim();
            if (scenario.isEmpty()) continue;
            long t0 = System.currentTimeMillis();
            try {
                Night.class.getDeclaredMethod(scenario).invoke(null);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Monkey.problem(scenario + " stopped: " + Monkey.trace(e.getCause()));
            }
            Monkey.checkUncaught(scenario);
            summary.add(String.format(Locale.US, "%-10s %s (%.0f s real)", scenario, outcome(), (System.currentTimeMillis() - t0) / 1000.0));
        }
        Monkey.say("");
        Monkey.say("==== scenarios");
        for (String s : summary) Monkey.say("  " + s);
        Monkey.say("==== " + Monkey.problems.size() + " problems");
        for (String p : Monkey.problems) Monkey.say("PROBLEM " + p);
        Monkey.log.close();
    }

    // ---- the scenarios ----

    /** Start on the home screen with the settings as they come (5 minutes, deep sky), put the phone down, wait. */
    static void base() throws Exception {
        begin("a 5-minute deep-sky session, as the app comes");
        tap("Start");
        hold(2500);
        putDown();
        follow(20 * 60_000L);
        report();
        Monkey.say("  result screen: " + labels());
    }

    /** Stopped from the phone after a few frames: what there is is developed. */
    static void stop() throws Exception {
        begin("3 minutes, stopped on the phone after 4 frames");
        minutes(3);
        startAndPutDown();
        untilFrames(4);
        Monkey.say("  capturing screen: " + labels());
        longPress("Hold to stop");
        follow(15 * 60_000L);
        report();
    }

    /** Picked up for a few seconds mid-session (to look), then put back. */
    static void pickup() throws Exception {
        begin("3 minutes, picked up for 6 s after 3 frames, then put back");
        minutes(3);
        startAndPutDown();
        untilFrames(3);
        Monkey.say("  " + clock() + ": picked up");
        hold(6000);
        Monkey.say("  " + clock() + ": now " + outcome());
        putDown();
        follow(15 * 60_000L);
        report();
    }

    /** Knocked mid-session (still screen-down): the view shifts a quarter of a degree. */
    static void knock() throws Exception {
        begin("3 minutes, knocked after 3 frames (the view shifts 0.25 deg)");
        minutes(3);
        startAndPutDown();
        untilFrames(3);
        Monkey.say("  " + clock() + ": knocked");
        SimCamera.get().bumped = true;
        shake = 0.8;
        run(300);
        shake = 0.0015;
        follow(15 * 60_000L);
        report();
    }

    /** The camera stops answering for a while: the watchdog restarts the capture session. */
    static void stall() throws Exception {
        begin("3 minutes, the camera stalls after 3 frames (a new capture session cures it)");
        minutes(3);
        startAndPutDown();
        untilFrames(3);
        Monkey.say("  " + clock() + ": the camera stalls");
        SimCamera.get().stall = true;
        follow(20 * 60_000L);
        report();
    }

    /** The camera stops answering for good: restarted, reopened, then given up with what there is. */
    static void stallhard() throws Exception {
        begin("3 minutes, the camera stalls for good after 3 frames");
        minutes(3);
        startAndPutDown();
        untilFrames(3);
        Monkey.say("  " + clock() + ": the camera stalls for good");
        SimCamera.get().stallSticky = true;
        SimCamera.get().stall = true;
        follow(20 * 60_000L);
        report();
        SimCamera.get().stallSticky = false;
        SimCamera.get().stall = false;
    }

    /** Three frames fail in a row mid-session. */
    static void fail() throws Exception {
        begin("3 minutes, 3 frames fail after 3");
        minutes(3);
        startAndPutDown();
        untilFrames(3);
        SimCamera.get().failNext = 3;
        follow(15 * 60_000L);
        report();
    }

    /** Two frames lose their image (the result comes, the image doesn't). */
    static void lose() throws Exception {
        begin("3 minutes, 2 frames lose their image after 3");
        minutes(3);
        startAndPutDown();
        untilFrames(3);
        SimCamera.get().loseNext = 2;
        follow(15 * 60_000L);
        report();
    }

    /** The first long exposures fail outright: the session falls back to shorter frames. */
    static void longfail() throws Exception {
        begin("3 minutes, the first 2 session frames fail (long exposures refused)");
        minutes(3);
        startAndPutDown();
        untilPhase(SessionController.Phase.CAPTURING);
        SimCamera.get().failNext = 2;
        follow(15 * 60_000L);
        report();
    }

    /** Even the longest exposure the camera admits to fails every time: the session halves it. */
    static void deadlong() throws Exception {
        begin("3 minutes, every exposure of 12 s or more fails");
        minutes(3);
        SimCamera.get().failAtLeastNs = 12_000_000_000L;
        startAndPutDown();
        follow(15 * 60_000L);
        report();
    }

    /** The battery runs down to 4% after 4 frames: the session stops and develops what it has. */
    static void battery() throws Exception {
        begin("3 minutes, the battery falls to 4% after 4 frames");
        minutes(3);
        setBattery(80);
        startAndPutDown();
        untilFrames(4);
        setBattery(4);
        follow(15 * 60_000L);
        report();
        setBattery(80);
    }

    static void setBattery(int pct) {
        Intent b = new Intent(Intent.ACTION_BATTERY_CHANGED);
        b.putExtra(android.os.BatteryManager.EXTRA_LEVEL, pct);
        b.putExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
        b.putExtra(android.os.BatteryManager.EXTRA_STATUS, android.os.BatteryManager.BATTERY_STATUS_DISCHARGING);
        b.putExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 250);
        org.robolectric.RuntimeEnvironment.getApplication().sendStickyBroadcast(b);
        Monkey.say("    battery at " + pct + "%");
    }

    /** The camera gives 4 s however long the exposure asked for. */
    static void clamp() throws Exception {
        begin("3 minutes, the camera cuts every exposure to 4 s");
        minutes(3);
        SimCamera.get().maxExposureNs = 4_000_000_000L;
        startAndPutDown();
        follow(15 * 60_000L);
        report();
    }

    /**
     * The camera gives 4 s, so four frames make one 16-second frame; volume up after 8 frames adds 5 minutes, which
     * must be whole 16-second frames too.
     */
    static void addkey() throws Exception {
        begin("1 minute, the camera cuts every exposure to 4 s, volume up after 8 frames adds 5 minutes");
        minutes(1);
        SimCamera.get().maxExposureNs = 4_000_000_000L;
        startAndPutDown();
        untilFrames(8);
        android.view.KeyEvent dn = new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP);
        android.view.KeyEvent up = new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_VOLUME_UP);
        boolean took = Monkey.a.onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_UP, dn);
        Monkey.a.onKeyUp(android.view.KeyEvent.KEYCODE_VOLUME_UP, up);
        run(1000);
        SessionController.Status st = Monkey.svc.controller().status();
        Monkey.say("    volume up taken: " + took + "; now " + st.done + " of " + st.planned + " frames");
        if (!took) Monkey.problem("addkey: volume up wasn't taken while capturing");
        if (st.planned % 4 != 0) Monkey.problem("addkey: " + st.planned + " frames planned, not whole 16-second frames");
        follow(20 * 60_000L);
        report();
    }

    /** The capture session never comes up. */
    static void camerror() throws Exception {
        begin("1 minute, the camera fails as it opens (once): it's opened again");
        minutes(1);
        SimCamera.get().failOpen = "the camera hit an error (code 4)";
        startAndPutDown();
        follow(10 * 60_000L);
        report();
    }

    /** The camera fails as it opens, and again: the session can't start, and says why in words. */
    static void camerror2() throws Exception {
        begin("the camera fails as it opens, twice");
        SimCamera.get().failOpen = "the camera hit an error (code 4)";
        SimCamera.get().failOpenAgain = 1;
        startAndPutDown();
        follow(5 * 60_000L);
        expect("code 4");
        expect("the session failed");
        Monkey.say("  failed screen: " + labels());
        report();
    }

    /** Another app takes the camera mid-session. */
    static void taken() throws Exception {
        begin("3 minutes, another app takes the camera after 3 frames");
        minutes(3);
        startAndPutDown();
        untilFrames(3);
        SimCamera.get().errorNext = "the camera was taken by another app or the system";
        follow(15 * 60_000L);
        report();
    }

    /** Cancelled while focusing, then started again and left to finish. */
    static void cancel() throws Exception {
        begin("cancelled while focusing, then a 1-minute session");
        minutes(1);
        startAndPutDown();
        untilPhase(SessionController.Phase.FOCUS);
        run(3000);
        press("Cancel");
        run(3000);
        Monkey.say("  after cancelling: " + outcome() + ", camera open: " + rigOpen());
        if (rigOpen()) Monkey.problem(scenario + ": the camera is still open after cancelling");
        hold(1000);
        tap("Start");
        hold(1500);
        putDown();
        follow(15 * 60_000L);
        report();
    }

    /** The screen goes off mid-session (the activity stops) and comes back after it's developed. */
    static void screenoff() throws Exception {
        begin("2 minutes, the screen off after 2 frames until it's done");
        minutes(2);
        startAndPutDown();
        untilFrames(2);
        Monkey.say("  " + clock() + ": screen off");
        Monkey.ctrl.pause().stop();
        untilPhase(SessionController.Phase.DONE);
        run(2000);
        Monkey.say("  " + clock() + ": screen on");
        Monkey.ctrl.restart().start().resume();
        run(1500);
        Monkey.say("  shows " + Monkey.signature());
        shot("back");
        report();
    }

    /** The activity is made again mid-session (as after the system reclaimed it). */
    static void remade() throws Exception {
        begin("2 minutes, the activity made again after 2 frames");
        minutes(2);
        startAndPutDown();
        untilFrames(2);
        Monkey.ctrl.pause().stop().destroy();
        Monkey.ctrl = Robolectric.buildActivity(MainActivity.class).setup();
        Monkey.a = Monkey.ctrl.get();
        run(1500);
        Monkey.say("  " + clock() + ": made again, shows " + Monkey.signature());
        shot("remade");
        follow(15 * 60_000L);
        report();
    }

    /**
     * The widget's quick session from a cold start; then the screen made again (as after Android reclaimed the app,
     * on the sessions page) and reopened from the recent apps, both with the intent it was first opened by: those
     * start nothing, and the page comes back.
     */
    static void replay() throws Exception {
        begin("the widget's quick session, then the screen made again and reopened from the recent apps");
        Context c = RuntimeEnvironment.getApplication();
        Intent widget = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_LAUNCH).setData(MainActivity.launchUri("quick"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        reopen(new Intent(widget), null);
        SessionController.Phase p = Monkey.svc.controller().status().phase;
        Monkey.say("  " + clock() + ": opened by the widget: " + p + ", shows " + Monkey.signature());
        if (p == SessionController.Phase.IDLE) Monkey.problem(scenario + ": the widget's quick session didn't start");
        Monkey.idle();
        run(1000);
        press("Sessions");
        String before = Monkey.signature();
        android.os.Bundle saved = new android.os.Bundle();
        Monkey.ctrl.pause().saveInstanceState(saved).stop().destroy();
        Monkey.ctrl = null;
        reopen(new Intent(widget), saved);
        p = Monkey.svc.controller().status().phase;
        String after = Monkey.signature();
        Monkey.say("  " + clock() + ": made again: " + p + ", shows " + after + " (was " + before + ")");
        if (p != SessionController.Phase.IDLE) Monkey.problem(scenario + ": made again, the widget's session started again (" + p + ")");
        if (!after.equals(before)) Monkey.problem(scenario + ": made again, the page wasn't kept: " + after + " (was " + before + ")");
        Monkey.idle();
        reopen(new Intent(widget).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), null);
        p = Monkey.svc.controller().status().phase;
        Monkey.say("  " + clock() + ": from the recent apps: " + p + ", shows " + Monkey.signature());
        if (p != SessionController.Phase.IDLE) Monkey.problem(scenario + ": from the recent apps, the widget's session started again (" + p + ")");
        Monkey.idle();
        reopen(new Intent(c, MainActivity.class).setAction(Intent.ACTION_MAIN), null);
    }

    /** A new activity in place of the current one, opened by {@code i} (with its saved state, if made again). */
    static void reopen(Intent i, android.os.Bundle saved) throws Exception {
        if (Monkey.ctrl != null) Monkey.ctrl.pause().stop().destroy();
        Monkey.ctrl = Robolectric.buildActivity(MainActivity.class, i);
        if (saved != null) Monkey.ctrl.setup(saved);
        else Monkey.ctrl.setup();
        Monkey.a = Monkey.ctrl.get();
        run(3000);
    }

    /** Star trails for 2 minutes. */
    static void trails() throws Exception {
        begin("2 minutes of star trails");
        minutes(2);
        press("Star trails");
        startAndPutDown();
        follow(15 * 60_000L);
        report();
        Monkey.idle();
        run(500);
        press("Deep sky");
    }

    /** A sky reading (how dark it is), from the home screen's Advanced section. */
    static void reading() throws Exception {
        begin("a sky reading");
        if (findLabel("Sky reading") == null) press("Advanced");
        press("Sky reading");
        hold(1500);
        putDown();
        follow(10 * 60_000L);
        SessionController.Status st = Monkey.svc.controller().status();
        Monkey.say("  reading: " + (st.reading != null ? st.reading.headline : "none"));
        Monkey.say("  screen: " + labels());
    }

    /**
     * Keep meteors, chosen in the settings: two bright meteors cross the picture during a 3-minute session. Both must
     * be in the session's meteor list, kept, and on the session report.
     */
    static void meteors() throws Exception {
        begin("3 minutes with Keep meteors chosen; two bright meteors cross the picture");
        minutes(3);
        if (findLabel("Keep meteors") == null) press("Advanced");
        press("Keep meteors");
        startAndPutDown();
        untilFrames(2);
        SimCamera cam = SimCamera.get();
        double now = (SystemClock.elapsedRealtimeNanos() - cam.t0Ns) / 1e9;
        cam.meteor(now + 20, 0.45, 0.40, 25, 9, 0.6, -1.5);
        cam.meteor(now + 55, 0.30, 0.65, 200, 12, 0.5, -1.0);
        follow(20 * 60_000L);
        report();
        File[] ss = Monkey.root.listFiles();
        File d = null;
        if (ss != null && ss.length > 0) {
            Arrays.sort(ss);
            d = ss[ss.length - 1];
        }
        File csv = d == null ? null : new File(d, "meteors.csv");
        List<String> rows = new ArrayList<String>();
        if (csv != null && csv.exists()) {
            BufferedReader r = new BufferedReader(new FileReader(csv));
            String line;
            while ((line = r.readLine()) != null) rows.add(line);
            r.close();
        }
        for (String row : rows) Monkey.say("  meteors.csv | " + row);
        int kept = 0;
        for (int k = 1; k < rows.size(); k++) {
            String[] c = rows.get(k).split(",", -1);
            if (c.length > 17 && c[17].equals("1")) kept++;
        }
        if (rows.size() != 3 || kept != 2) Monkey.problem(scenario + ": " + (rows.size() - 1) + " meteors in meteors.csv, " + kept + " kept (2 of 2 wanted)");
        String rep = d == null ? "" : read(new File(d, "report.json"));
        if (!rep.contains("\"meteors\"") || !rep.contains("All kept in the picture")) Monkey.problem(scenario + ": the report has no kept meteors");
        // and the table, in Documents/NightSky for Excel
        String log = d == null ? "" : read(new File(d, "log.txt"));
        if (!log.contains("meteors: the list saved in Documents/NightSky as ")) Monkey.problem(scenario + ": the meteor table wasn't saved");
        Monkey.idle();
        run(500);
        // as the app comes again
        if (findLabel("Take them out") == null) press("Advanced");
        press("Take them out");
    }

    static String read(File f) throws Exception {
        if (!f.exists()) return "";
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }

    /** Clouds over everything for three frames. */
    static void clouds() throws Exception {
        begin("3 minutes, clouds (a quarter of the light) over frames 3 to 5");
        minutes(3);
        startAndPutDown();
        untilFrames(2);
        SimCamera.get().transparency = 0.25;
        untilFrames(5);
        SimCamera.get().transparency = 1;
        follow(15 * 60_000L);
        report();
    }

    /** Not enough free storage for the session. */
    static void storage() throws Exception {
        begin("not enough free storage");
        ShadowStatFs.registerStats("/", 16_000_000, 40_000, 40_000);
        startAndPutDown();
        follow(5 * 60_000L);
        Monkey.say("  screen: " + labels());
        ShadowStatFs.registerStats("/", 16_000_000, 10_000_000, 10_000_000);
        report();
    }

    /** One minute, then "Add 5 minutes" from the result. */
    static void more() throws Exception {
        begin("1 minute, then 5 more added from the result");
        minutes(1);
        startAndPutDown();
        follow(15 * 60_000L);
        Monkey.say("  result screen: " + labels());
        View add = findLabel("Add 5 minutes");
        if (add == null) add = findLabel("Add");
        if (add == null) {
            Monkey.problem(scenario + ": no way to add minutes on the result: " + labels());
            return;
        }
        press(Monkey.label(add));
        hold(1500);
        putDown();
        follow(20 * 60_000L);
        report();
    }

    /**
     * Tonight's plan: a 1-minute session at a set time 2.5 minutes from now (only if clear), armed from the
     * widget's link. The start alarm wakes the service, the session runs by itself with the phone lying
     * screen-down, and how it went goes back into the plan: done with its photo, the plan over, the morning
     * summary set.
     */
    static void plan() throws Exception {
        begin("tonight's plan: a 1-minute session at a set time, armed, then started by its alarm");
        makePlan(150_000L);
        if (!armPlan()) return;
        putDown();
        if (!fireStart()) return;
        follow(15 * 60_000L);
        report();
        run(3000);
        NightPlan p = Plans.load(Monkey.app);
        NightPlan.Item it = p.items.get(0);
        Monkey.say("  plan: armed " + p.armed + ", " + it.what() + ": " + it.state + ", " + it.outcome + (it.photo.isEmpty() ? "" : ", photo " + new File(it.photo).getName()));
        for (String l : p.log) Monkey.say("  plan | " + l);
        if (p.armed) Monkey.problem(scenario + ": the plan is still armed after its only session");
        if (it.state != NightPlan.State.DONE) Monkey.problem(scenario + ": the plan's session ended " + it.state + ": " + it.outcome);
        if (it.photo.isEmpty() || it.session.isEmpty()) Monkey.problem(scenario + ": the plan didn't keep the session's photo");
        if (alarm(PlanReceiver.ACTION_START) != null) Monkey.problem(scenario + ": a start alarm is still set");
        if (alarm(PlanReceiver.ACTION_MORNING) == null) Monkey.problem(scenario + ": no morning summary alarm");
        Monkey.idle();
    }

    /**
     * The plan's session under clouds (only if clear): it doesn't start, the plan tries again 10 minutes later,
     * then it's cancelled from the notification's button.
     */
    static void plancloudy() throws Exception {
        begin("tonight's plan under clouds: it waits to try again, then is cancelled");
        makePlan(150_000L);
        if (!armPlan()) return;
        putDown();
        SimCamera.get().transparency = 0.03;
        try {
            if (!fireStart()) return;
            follow(15 * 60_000L);
        } finally {
            SimCamera.get().transparency = 1;
        }
        run(3000);
        NightPlan p = Plans.load(Monkey.app);
        NightPlan.Item it = p.items.get(0);
        ShadowAlarmManager.ScheduledAlarm again = alarm(PlanReceiver.ACTION_START);
        Monkey.say("  " + clock() + ": " + outcome() + "; plan: armed " + p.armed + ", " + it.state + ": " + it.outcome + "; next alarm in "
                + (again == null ? "none" : String.format(Locale.US, "%.1f min", (again.triggerAtTime - System.currentTimeMillis()) / 60_000.0)));
        if (it.state != NightPlan.State.WAITING) Monkey.problem(scenario + ": under clouds the plan's session is " + it.state + ": " + it.outcome);
        if (again == null || Math.abs(again.triggerAtTime - it.nextTryMs) > 1000) Monkey.problem(scenario + ": no start alarm for the next try");
        if (rigOpen()) Monkey.problem(scenario + ": the camera is open while the plan waits");
        Monkey.app.startService(new Intent(Monkey.app, CaptureService.class).setAction(CaptureService.ACTION_CANCEL_PLAN));
        run(2000);
        p = Plans.load(Monkey.app);
        it = p.items.get(0);
        Monkey.say("  " + clock() + ": cancelled: armed " + p.armed + ", " + it.state + ": " + it.outcome);
        for (String l : p.log) Monkey.say("  plan | " + l);
        if (p.armed || it.state != NightPlan.State.CANCELLED) Monkey.problem(scenario + ": cancelling left the plan " + (p.armed ? "armed" : "") + " " + it.state);
        if (alarm(PlanReceiver.ACTION_START) != null) Monkey.problem(scenario + ": a start alarm is still set after cancelling");
        Monkey.idle();
    }

    /**
     * Half an hour with the lens's focus drifting as the night cools (0.5 diopters between minutes 8 and 20): the
     * stars swell, the focus watch notices, and a two-frame check between frames finds the focus again.
     */
    static void drift() throws Exception {
        begin("30 minutes, the focus drifts 0.5 D between minutes 8 and 20");
        minutes(30);
        startAndPutDown();
        untilPhase(SessionController.Phase.CAPTURING);
        SimCamera cam = SimCamera.get();
        double f0 = cam.bestFocus;
        long t0 = SystemClock.elapsedRealtime(), end = t0 + 45 * 60_000L;
        try {
            while (SystemClock.elapsedRealtime() < end) {
                step();
                double min = (SystemClock.elapsedRealtime() - t0) / 60_000.0;
                cam.bestFocus = f0 + 0.5 * Math.max(0, Math.min(1, (min - 8) / 12));
                SessionController.Phase ph = Monkey.svc.controller().status().phase;
                if (ph != SessionController.Phase.CAPTURING && ph != SessionController.Phase.PAUSED) break;
            }
            Monkey.say(String.format(Locale.US, "  %s: capture over, the sharpest focus now %.2f", clock(), cam.bestFocus));
            follow(20 * 60_000L);
        } finally {
            cam.bestFocus = f0;
        }
        report();
    }

    // ---- the camera check (indoors, the lens against a table) and capture sessions renewed before a stop ----

    /** The check on a covered camera that does everything right. */
    static void check() throws Exception {
        begin("the camera check: covered, a camera that does everything right");
        forgetCamera();
        SimCamera.get().covered = true;
        runCheck();
    }

    /** The check on a camera that stops after 63 frames in a row until it gets a new capture session (the Pixel bug). */
    static void checkstall() throws Exception {
        begin("the camera check: covered, a camera that stops after 63 in a row (a new capture session gets it going)");
        forgetCamera();
        SimCamera.get().covered = true;
        SimCamera.get().stallAfter = 63;
        runCheck();
    }

    /** The same, but only opening the camera again gets it going. */
    static void checkreopen() throws Exception {
        begin("the camera check: covered, a camera that stops after 63 in a row until it is opened again");
        forgetCamera();
        SimCamera.get().covered = true;
        SimCamera.get().stallAfter = 63;
        SimCamera.get().stallReopen = true;
        runCheck();
    }

    /** The check with light reaching the sensor all along: it waits, then goes on without the dark. */
    static void checklit() throws Exception {
        begin("the camera check: never covered (room light reaches the sensor)");
        forgetCamera();
        SimCamera.get().lightPerS = 400;
        runCheck();
    }

    /** The check started with the camera uncovered, covered 8 seconds later. */
    static void checkcover() throws Exception {
        begin("the camera check: started uncovered, covered 8 s later");
        forgetCamera();
        SimCamera cam = SimCamera.get();
        cam.lightPerS = 400;
        openCheck();
        press("Start");
        run(8000);
        Monkey.say("  " + clock() + ": covered");
        cam.lightPerS = 0;
        cam.covered = true;
        untilCheckDone(10 * 60_000L);
        reportCheck();
    }

    /** The check on a camera that cuts long exposures to 4 seconds: sessions then plan with 4 from the start. */
    static void checkclamp() throws Exception {
        begin("the camera check: covered, a camera that cuts exposures to 4 seconds; then a 3-minute session");
        forgetCamera();
        SimCamera.get().covered = true;
        SimCamera.get().maxExposureNs = 4_000_000_000L;
        runCheck();
        // a session after it: planned with 4-second exposures from the start
        SimCamera cam = SimCamera.get();
        cam.covered = false;
        backHome();
        minutes(3);
        startAndPutDown();
        follow(20 * 60_000L);
        report();
        forgetCamera();
    }

    /** The check stopped part way by the person: nothing saved, the camera let go. */
    static void checkstop() throws Exception {
        begin("the camera check: stopped by hand during the run");
        forgetCamera();
        SimCamera.get().covered = true;
        openCheck();
        press("Start");
        run(20_000);
        press("Stop");
        run(2000);
        Monkey.say("  stopped: check " + (Monkey.field(Monkey.a, "cameraCheck") == null ? "gone" : "STILL THERE") + "; facts saved: "
                + !new Prefs(Monkey.app).cameraFacts("0").isEmpty() + "; " + screenTexts());
        if (!new Prefs(Monkey.app).cameraFacts("0").isEmpty()) Monkey.problem(scenario + ": a stopped check saved its findings");
        backHome();
        // a session right after: the camera is free
        SimCamera.get().covered = false;
        minutes(1);
        startAndPutDown();
        follow(15 * 60_000L);
        report();
    }

    /** A short session, then Share the log from its report; then from the sessions page (the log copied from its folder). */
    static void sharelog() throws Exception {
        begin("1 minute, then Share the log from its report, and again from the sessions page");
        minutes(1);
        startAndPutDown();
        follow(10 * 60_000L);
        Monkey.drainActivities();
        press("Session report");
        press("Share the log");
        shared("from the result", "plan:", "report:");
        press("Close");
        press("New photo");
        run(1000);
        press("Sessions");
        run(1500);
        press("Open");
        follow(5 * 60_000L);
        press("Session report");
        press("Share the log");
        run(1500);
        shared("from the sessions page", "plan:", "report:");
        press("Close");
        press("Sessions");
        run(500);
        backHome();
        // a session that can't start (the camera fails as it opens, twice): its failure screen shares what was logged
        SimCamera.get().failOpen = "the camera hit an error (code 4)";
        SimCamera.get().failOpenAgain = 1;
        startAndPutDown();
        follow(5 * 60_000L);
        expect("code 4");
        expect("the session failed");
        Monkey.say("  failed screen: " + labels());
        press("Share the log");
        shared("from the failure screen", "failed: ", "code 4");
        press("OK");
    }

    /** The share sheet the last tap opened: a text file, readable by the app it goes to, with the session's log in it. */
    static void shared(String where, String... has) throws Exception {
        Intent ch = Shadows.shadowOf(Monkey.a).getNextStartedActivity();
        Intent i = ch == null ? null : (Intent) ch.getParcelableExtra(Intent.EXTRA_INTENT);
        android.net.Uri u = i == null ? null : (android.net.Uri) i.getParcelableExtra(Intent.EXTRA_STREAM);
        String text = "";
        if (u != null) {
            java.io.InputStream in = Monkey.app.getContentResolver().openInputStream(u);
            if (in != null) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                text = bo.toString("UTF-8");
            }
        }
        boolean ok = i != null && Intent.ACTION_SEND.equals(i.getAction()) && "text/plain".equals(i.getType()) && u != null
                && (i.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
        for (String h : has) ok &= text.contains(h);
        Monkey.say(String.format(Locale.US, "  shared %s: %s, %s, %d characters%s", where, ch == null ? "nothing" : String.valueOf(ch.getAction()),
                u, text.length(), ok ? "" : " (NOT THE LOG)"));
        if (text.length() < 800) for (String l : text.split("\n")) Monkey.say("    | " + l);
        if (!ok) Monkey.problem(scenario + ": Share the log " + where + " didn't share the log");
    }

    /** A session on a camera that stops after 20 captures in a row, nothing learned: the watchdog finds it, then renewals. */
    static void stoplearn() throws Exception {
        begin("10 minutes, the camera stops after 20 captures in a row, nothing learned yet");
        forgetCamera();
        SimCamera.get().stallAfter = 20;
        minutes(10);
        startAndPutDown();
        follow(30 * 60_000L);
        report();
        Prefs pr = new Prefs(Monkey.app);
        Monkey.say("  learned: renew after " + pr.renewAfter("0") + " (" + pr.renewHow("0") + ")");
        if (pr.renewAfter("0") != 17) Monkey.problem(scenario + ": learned renew after " + pr.renewAfter("0") + ", not 17");
        forgetCamera();
    }

    /** A session on a camera whose stop the check found: renewed before it, the camera never stops. */
    static void renewknown() throws Exception {
        begin("5 minutes, the camera stops after 18 in a row, known: renewed every 15");
        forgetCamera();
        new Prefs(Monkey.app).noteRenew("0", 15, "session");
        SimCamera.get().stallAfter = 18;
        minutes(5);
        startAndPutDown();
        follow(20 * 60_000L);
        report();
        forgetCamera();
    }

    /**
     * A camera whose check found a stop after 33 in a row (renewals planned every 30) but that stops after 20 with a
     * night's long frames: the first stop makes the renewals sooner, and there's no second.
     */
    static void renewsooner() throws Exception {
        begin("10 minutes, renewals planned every 30, but the camera stops after 20 in a row");
        forgetCamera();
        new Prefs(Monkey.app).noteRenew("0", 30, "session");
        SimCamera.get().stallAfter = 20;
        minutes(10);
        startAndPutDown();
        follow(30 * 60_000L);
        report();
        Prefs pr = new Prefs(Monkey.app);
        Monkey.say("  learned: renew after " + pr.renewAfter("0") + " (" + pr.renewHow("0") + ")");
        if (pr.renewAfter("0") != 17) Monkey.problem(scenario + ": learned renew after " + pr.renewAfter("0") + ", not 17");
        int stalls = Integer.parseInt(newestProps().getProperty("capture.stalls", "-1"));
        if (stalls != 1) Monkey.problem(scenario + ": " + stalls + " stalls, not 1");
        forgetCamera();
    }

    /** The same, renewing by opening the camera again. */
    static void renewreopen() throws Exception {
        begin("5 minutes, the camera stops after 18 since it was opened, known: reopened every 15");
        forgetCamera();
        new Prefs(Monkey.app).noteRenew("0", 15, "reopen");
        SimCamera.get().stallAfter = 18;
        SimCamera.get().stallReopen = true;
        minutes(5);
        startAndPutDown();
        follow(20 * 60_000L);
        report();
        forgetCamera();
    }

    /** A camera whose fresh capture sessions don't come up: the renewal opens it again instead, and from then on. */
    static void renewfail() throws Exception {
        begin("5 minutes, renewed every 15, but the first new capture session fails to come up");
        forgetCamera();
        new Prefs(Monkey.app).noteRenew("0", 15, "session");
        SimCamera cam = SimCamera.get();
        minutes(5);
        startAndPutDown();
        untilPhase(SessionController.Phase.CAPTURING);
        cam.failOpen = "the camera refused the RAW stream";
        follow(20 * 60_000L);
        report();
        Prefs pr = new Prefs(Monkey.app);
        Monkey.say("  learned: renew after " + pr.renewAfter("0") + " (" + pr.renewHow("0") + ")");
        forgetCamera();
    }

    /** Forgets what earlier scenarios taught the app about the camera (exposures, stops, the check's findings). */
    static void forgetCamera() throws Exception {
        android.content.SharedPreferences sp = (android.content.SharedPreferences) Monkey.field(new Prefs(Monkey.app), "sp");
        android.content.SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith("verifiedExp|") || k.startsWith("clampedExp|") || k.startsWith("clampDelivered|") || k.startsWith("renewAfter|")
                    || k.startsWith("renewHow|") || k.startsWith("cameraFacts|")) e.remove(k);
        }
        e.remove("checkOffered");
        e.commit();
    }

    static void backHome() throws Exception {
        for (int k = 0; k < 5 && ((Integer) Monkey.field(Monkey.a, "page")) != 0; k++) {
            Monkey.a.onBackPressed();
            run(300);
        }
    }

    /** The check's page: from the home screen's offer if it's there, else Advanced settings. */
    static void openCheck() throws Exception {
        backHome();
        Shots.call(Monkey.a, "showHome", new Class<?>[0]);
        run(300);
        if (findLabel("Check the camera") != null) {
            press("Check the camera");
        } else {
            if (findLabel("Camera check") == null) press("Advanced");
            press("Camera check");
        }
    }

    static void runCheck() throws Exception {
        openCheck();
        press("Start");
        untilCheckDone(10 * 60_000L);
        reportCheck();
    }

    /** Runs until the check is over, saying each step it reaches. */
    static void untilCheckDone(long maxMs) throws Exception {
        long end = SystemClock.elapsedRealtime() + maxMs;
        String last = "";
        while (SystemClock.elapsedRealtime() < end) {
            step();
            Object cc = Monkey.field(Monkey.a, "cameraCheck");
            if (cc == null) {
                run(1000);
                return;
            }
            Object f = Shots.field(cc, "f");
            String now = Shots.field(cc, "step") + ", " + Shots.field(f, "runDone") + " of " + Shots.field(cc, "runTarget")
                    + " in the run, " + Shots.field(f, "stops") + " stops";
            if (!now.equals(last) && (!now.startsWith(last.split(",")[0]) || !Shots.field(f, "stops").toString().equals(stopsOf(last)))) {
                Monkey.say("  " + clock() + ": " + now);
            }
            last = now;
        }
        Monkey.problem(scenario + ": the check was still running after " + maxMs / 60000 + " min");
    }

    static String stopsOf(String s) {
        int k = s.lastIndexOf(", ");
        return k < 0 ? "" : s.substring(k + 2).replace(" stops", "");
    }

    /** What the check showed and saved, and what the simulated camera did. */
    static void reportCheck() throws Exception {
        shot("check");
        Monkey.say("  screen: " + screenTexts());
        Prefs pr = new Prefs(Monkey.app);
        CameraFacts f = CameraFacts.parse(pr.cameraFacts("0"));
        Monkey.say("  saved for sessions: renew after " + pr.renewAfter("0") + " (" + pr.renewHow("0") + "), exposure verified "
                + pr.verifiedExposureNs("0") / 1e9 + " s, cut to " + pr.clampDeliveredNs("0") / 1e9 + " s");
        if (f == null) {
            Monkey.say("  no findings saved");
        } else {
            for (String l : f.text().split("\n")) Monkey.say("  | " + l);
        }
        SimCamera cam = SimCamera.get();
        Monkey.say(String.format(Locale.US, "  camera: %d opens, %d captures, %d delivered, %d failed, %d lost, %d aborted", cam.opens,
                cam.captures, cam.delivered, cam.failedShots, cam.lostShots, cam.aborted));
        for (String e : cam.events) Monkey.say("    cam " + e);
    }

    /** Every text on the screen, in order. */
    static String screenTexts() throws Exception {
        List<String> out = new ArrayList<String>();
        texts(Monkey.a.findViewById(android.R.id.content), out);
        StringBuilder b = new StringBuilder();
        for (String t : out) b.append(b.length() > 0 ? " | " : "").append(t);
        return b.toString();
    }

    static void texts(View v, List<String> out) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null && t.length() > 0) out.add(t.toString().replace('\n', ' '));
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) texts(g.getChildAt(i), out);
        }
    }

    /** Tonight's plan with one 1-minute session at a set time {@code inMs} from now, only if clear, saved. */
    static NightPlan.Item makePlan(long inMs) {
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        Tonight t = Tonight.compute(place.latDeg, place.lonDeg, now, zone, Forecasts.cached(Monkey.app, place.latDeg, place.lonDeg), Double.NaN);
        NightPlan p = new NightPlan();
        p.night = Plans.planningNight(now, t, zone);
        p.createdMs = now;
        p.zone = zone;
        p.latDeg = place.latDeg;
        p.lonDeg = place.lonDeg;
        p.minBatteryPct = 10;
        NightPlan.Item it = new NightPlan.Item();
        it.rule = NightPlan.Rule.CLOCK;
        it.clockMs = now + inMs;
        it.durationSec = 60;
        it.onlyIfClear = true;
        p.add(it);
        Plans.save(Monkey.app, p);
        return it;
    }

    /** Arms the saved plan the way the widget's "arm" link does (the app opened on it); false if no start alarm came of it. */
    static boolean armPlan() throws Exception {
        ShadowAlarmManager.setCanScheduleExactAlarms(true);
        int dialogs = ShadowDialog.getShownDialogs().size();
        reopen(new Intent(Monkey.app, MainActivity.class).setAction(MainActivity.ACTION_LAUNCH).setData(MainActivity.launchUri("armplan"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null);
        String d = Monkey.answerDialogs(dialogs, 1);
        run(1000);
        ShadowAlarmManager.ScheduledAlarm a = alarm(PlanReceiver.ACTION_START);
        Monkey.say("  " + clock() + ": armed from the widget" + (d.isEmpty() ? "" : ", dialog: " + d) + " -> " + Monkey.signature()
                + "; start alarm " + (a == null ? "none" : String.format(Locale.US, "in %.0f s%s", (a.triggerAtTime - System.currentTimeMillis()) / 1000.0,
                a.getAlarmClockInfo() != null ? " (an alarm clock)" : "")));
        if (a == null) Monkey.problem(scenario + ": arming the plan set no start alarm: " + outcome());
        return a != null;
    }

    /** Waits (the phone left alone) until the start alarm is due, then delivers it as Android would. */
    static boolean fireStart() throws Exception {
        ShadowAlarmManager.ScheduledAlarm a = alarm(PlanReceiver.ACTION_START);
        if (a == null) {
            Monkey.problem(scenario + ": no start alarm to wait for");
            return false;
        }
        long end = System.currentTimeMillis() + 10 * 60_000L;
        while (System.currentTimeMillis() < a.triggerAtTime && System.currentTimeMillis() < end) step();
        Monkey.say("  " + clock() + ": the start alarm goes off");
        new PlanReceiver().onReceive(Monkey.app, new Intent(PlanReceiver.ACTION_START));
        run(500);
        return true;
    }

    /** The alarm set for one of the plan's receiver actions, or null. */
    static ShadowAlarmManager.ScheduledAlarm alarm(String action) {
        AlarmManager am = (AlarmManager) Monkey.app.getSystemService(Context.ALARM_SERVICE);
        for (ShadowAlarmManager.ScheduledAlarm s : Shadows.shadowOf(am).getScheduledAlarms()) {
            if (s.operation == null) continue;
            Intent i = Shadows.shadowOf(s.operation).getSavedIntent();
            if (i != null && action.equals(i.getAction())) return s;
        }
        return null;
    }

    // ---- driving it ----

    static void begin(String what) throws Exception {
        Monkey.say("");
        Monkey.say("######## " + scenario + ": " + what);
        down = false;
        shake = 0.3;
        problemsBefore = Monkey.problems.size();
        Monkey.idle();
        // the app reopens on the page it was left on (a plan scenario leaves it on the plan page): home first
        for (int k = 0; k < 5 && ((Integer) Monkey.field(Monkey.a, "page")) != 0; k++) {
            Monkey.a.onBackPressed();
            Monkey.pump(300);
        }
        // the sky from now, where it's the middle of the night
        SimCamera cam = new SimCamera(catalog, System.currentTimeMillis(), place.lonDeg, 25);
        SimCamera.set(cam);
        headingMag = cam.sim.headingTopDeg() - Locator.declination(place);
        lastPhase = null;
        run(500);
    }

    static int problemsBefore;

    /** Problems this scenario was meant to cause (a status that says "error") are not problems. */
    static void expect(String what) {
        for (int k = Monkey.problems.size() - 1; k >= problemsBefore; k--) {
            if (Monkey.problems.get(k).contains(what)) Monkey.problems.remove(k);
        }
    }

    /** The session length chip on the home screen. */
    static void minutes(int m) throws Exception {
        press(m + "m");
    }

    static void startAndPutDown() throws Exception {
        tap("Start");
        hold(2000);
        putDown();
    }

    static void untilFrames(final int n) throws Exception {
        until("frame " + n, new Cond() {
            @Override
            public boolean ok(SessionController.Status st) {
                return st.phase == SessionController.Phase.CAPTURING && st.done >= n;
            }
        }, 15 * 60_000L);
    }

    static void untilPhase(final SessionController.Phase p) throws Exception {
        until(p.name(), new Cond() {
            @Override
            public boolean ok(SessionController.Status st) {
                return st.phase == p;
            }
        }, 15 * 60_000L);
    }

    interface Cond {
        boolean ok(SessionController.Status st);
    }

    static void until(String what, Cond c, long maxMs) throws Exception {
        long end = SystemClock.elapsedRealtime() + maxMs;
        while (SystemClock.elapsedRealtime() < end) {
            step();
            if (c.ok(Monkey.svc.controller().status())) return;
        }
        throw new IllegalStateException("no " + what + " after " + maxMs / 60000 + " min: " + outcome());
    }

    static boolean rigOpen() throws Exception {
        Object rig = Shots.field(Monkey.svc.controller(), "rig");
        if (rig == null) return false;
        FakeRig f = Shadow.extract(rig);
        return f.opened && !f.closed;
    }

    static View findLabel(String text) throws Exception {
        List<View> cs = Monkey.controls();
        for (View v : cs) {
            String l = Monkey.label(v);
            if (l.equals(text) || l.startsWith(text)) return v;
        }
        // a control with several lines of text (the result screen's "4 frames, all used. | Session report"): any of them
        for (View v : cs) {
            for (String part : Monkey.label(v).split(" \\| ")) {
                if (part.startsWith(text)) return v;
            }
        }
        return null;
    }

    static String labels() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (View v : Monkey.controls()) sb.append(sb.length() > 0 ? " | " : "").append(Monkey.label(v));
        return sb.toString();
    }

    /** Taps the control whose label is (or starts with) {@code text}, and answers a dialog it opens with its first choice. */
    static void press(String text) throws Exception {
        View v = findLabel(text);
        if (v == null) throw new IllegalStateException("no \"" + text + "\" among: " + labels());
        int dialogs = ShadowDialog.getShownDialogs().size();
        try {
            v.performClick();
        } catch (Throwable e) {
            Monkey.crash("tap " + text, e);
            return;
        }
        run(300);
        String asked = Monkey.answerPermissions();
        String d = Monkey.answerDialogs(dialogs, 0);
        run(300);
        Monkey.say("  " + clock() + ": tapped \"" + Monkey.label(v) + "\"" + asked + (d.isEmpty() ? "" : " dialog: " + d) + " -> "
                + Monkey.signature());
    }

    /** Touches and holds the control whose label starts with {@code text}. */
    static void longPress(String text) throws Exception {
        View v = findLabel(text);
        if (v == null) throw new IllegalStateException("no \"" + text + "\" among: " + labels());
        try {
            v.performLongClick();
        } catch (Throwable e) {
            Monkey.crash("hold " + text, e);
            return;
        }
        run(600);
        Monkey.say("  " + clock() + ": held \"" + Monkey.label(v) + "\" -> " + Monkey.signature());
    }

    static void tap(String key) throws Exception {
        if (!Monkey.tapKey(key)) throw new IllegalStateException("no " + key + " on " + Monkey.signature());
        Monkey.say("  " + clock() + ": tapped " + key + " -> " + Monkey.signature());
    }

    /** The phone in the hand for a while (pointing about, not still). */
    static void hold(long ms) throws Exception {
        down = false;
        shake = 0.3;
        run(ms);
    }

    static void putDown() throws Exception {
        down = true;
        shake = 0.0015;
        Monkey.say(String.format(Locale.US, "  %s: put down screen-down", clock()));
    }

    /** Runs until the session is over (a result, a failure, idle again) or {@code maxMs} of fake time. */
    static void follow(long maxMs) throws Exception {
        long start = SystemClock.elapsedRealtime(), end = start + maxMs;
        while (SystemClock.elapsedRealtime() < end) {
            step();
            SessionController.Status st = Monkey.svc.controller().status();
            if ((st.phase == SessionController.Phase.DONE && (st.result != null || st.reading != null))
                    || st.phase == SessionController.Phase.FAILED) {
                run(1500);
                shot("end");
                // a scenario meant to fail says so with expect("the session failed")
                if (st.phase == SessionController.Phase.FAILED) {
                    Monkey.problem(scenario + ": the session failed: " + st.headline + " / " + st.detail.replace('\n', ' '));
                }
                return;
            }
            if (st.phase == SessionController.Phase.IDLE && SystemClock.elapsedRealtime() > start + 5000) {
                Monkey.say("  idle again");
                return;
            }
        }
        Monkey.problem(scenario + ": still " + Monkey.svc.controller().status().phase + " after " + maxMs / 60000 + " min");
    }

    static SessionController.Phase lastPhase;

    /** A picture of the screen each time the phase changes. */
    static void watch() {
        SessionController.Phase p = Monkey.svc.controller().status().phase;
        if (p == lastPhase) return;
        lastPhase = p;
        if (Monkey.ctrl != null && Monkey.a != null && !Monkey.a.isFinishing()) shot(p.name().toLowerCase(Locale.US));
    }

    static String clock() {
        SimCamera c = SimCamera.get();
        return c == null ? "" : String.format(Locale.US, "%6.1f s", (SystemClock.elapsedRealtimeNanos() - c.t0Ns) / 1e9);
    }

    static void run(long ms) throws Exception {
        long end = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < end) step();
    }

    /**
     * Moves the fake clock on a step: the main looper's work, the services the app started, the sensors' readings for
     * the step, the controller's status. The clock waits while an exposure that is due back is still being drawn.
     */
    static void step() throws Exception {
        SimCamera cam = SimCamera.get();
        long before = SystemClock.elapsedRealtimeNanos();
        long waited = 0;
        while (cam != null && cam.overdue(before + STEP_MS * 1_000_000L) && waited < 30_000) {
            Thread.sleep(5);
            waited += 5;
        }
        if (waited >= 30_000) Monkey.problem(scenario + ": an exposure took 30 s to draw");
        boolean busy = busy();
        try {
            ShadowLooper.idleMainLooper(STEP_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            Monkey.crash("posted work", e);
        }
        feed(before, SystemClock.elapsedRealtimeNanos());
        Intent i;
        while ((i = Monkey.sa.getNextStartedService()) != null) {
            if (i.getComponent() == null || !i.getComponent().getClassName().equals(CaptureService.class.getName())) continue;
            Monkey.checkIntent("service", i);
            try {
                Monkey.svc.onStartCommand(i, 0, ++Monkey.startId);
            } catch (Throwable e) {
                Monkey.crash("service " + i.getAction(), e);
            }
        }
        Monkey.noteStatus();
        watch();
        Thread.sleep(busy ? STEP_MS : Math.max(1, (long) (STEP_MS / speed)));
    }

    /** Whether the phone is busy with work that takes real time (measuring focus, reading the test frame, stacking). */
    static boolean busy() {
        try {
            SessionController c = Monkey.svc.controller();
            if (Boolean.TRUE.equals(Shots.field(c, "focusMeasuring"))) return true;
            Object pf = Shots.field(c, "pfStep");
            if (pf instanceof Integer && (Integer) pf == 3) return true;
            Object up = Shots.field(c, "up");
            if (up != null) {
                java.util.concurrent.BlockingQueue<?> pool = (java.util.concurrent.BlockingQueue<?>) Shots.field(up, "pool");
                if (pool.remainingCapacity() > 1) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** The motion sensors' readings from t0 to t1 (ns): screen-down and still, or held and moving. */
    static void feed(long t0, long t1) {
        int n = 4;
        float[] g = down ? new float[]{0f, 0f, -9.81f} : new float[]{0.4f, 6.9f, 6.9f};
        for (int k = 1; k <= n; k++) {
            long t = t0 + (t1 - t0) * k / n;
            double s = shake * (0.5 + rnd.nextDouble());
            double a = rnd.nextDouble() * Math.PI * 2, b = Math.acos(2 * rnd.nextDouble() - 1);
            send(gyro, t, (float) (s * Math.sin(b) * Math.cos(a)), (float) (s * Math.sin(b) * Math.sin(a)), (float) (s * Math.cos(b)));
            float jit = down ? 0.02f : 0.6f;
            send(accel, t, g[0] + jit * (float) rnd.nextGaussian(), g[1] + jit * (float) rnd.nextGaussian(), g[2] + jit * (float) rnd.nextGaussian());
        }
        send(gravity, t1, g[0], g[1], g[2]);
        if (down) {
            // screen-down with the top edge toward headingMag: a half-turn about a level axis
            double h = Math.toRadians(headingMag) / 2;
            send(rotation, t1, (float) Math.sin(h), (float) Math.cos(h), 0f, 0f, (float) Math.toRadians(8));
        }
    }

    static void send(Sensor s, long t, float... v) {
        ssm.sendSensorEventToListeners(SensorEventBuilder.newBuilder().setSensor(s).setValues(v).setTimestamp(t).build());
    }

    static void shot(String what) {
        Monkey.shot(new Monkey.Screen(scenario, new ArrayList<String>(), null), what);
    }

    // ---- what came of it ----

    static String outcome() {
        SessionController.Status st = Monkey.svc.controller().status();
        return st.phase + ": " + st.headline + (st.detail.isEmpty() ? "" : " / " + st.detail.replace('\n', ' '));
    }

    /** The newest session folder: what's in it, its log, and what the simulated camera did. */
    static void report() throws Exception {
        SessionController.Status st = Monkey.svc.controller().status();
        Monkey.say("");
        Monkey.say("  outcome: " + outcome());
        if (st.result != null) Monkey.say("  result: " + st.result.summary);
        SimCamera cam = SimCamera.get();
        Monkey.say(String.format(Locale.US, "  camera: %d opens, %d captures, %d delivered, %d failed, %d lost, %d aborted", cam.opens,
                cam.captures, cam.delivered, cam.failedShots, cam.lostShots, cam.aborted));
        for (String e : cam.events) Monkey.say("    cam " + e);
        File[] ss = Monkey.root.listFiles();
        if (ss == null || ss.length == 0) {
            Monkey.say("  no session folder");
            return;
        }
        Arrays.sort(ss);
        File d = ss[ss.length - 1];
        Monkey.say("  folder " + d.getName() + ":");
        list(d, "    ");
        File log = new File(d, "log.txt");
        if (log.exists()) {
            Monkey.say("  ---- log.txt");
            BufferedReader r = new BufferedReader(new FileReader(log));
            String line;
            while ((line = r.readLine()) != null) Monkey.say("  | " + line);
            r.close();
        }
    }

    /** The newest session folder's settings and capture record (its session.properties), empty if there's none. */
    static java.util.Properties newestProps() throws Exception {
        java.util.Properties p = new java.util.Properties();
        File[] ss = Monkey.root.listFiles();
        if (ss == null || ss.length == 0) return p;
        Arrays.sort(ss);
        File f = new File(ss[ss.length - 1], "session.properties");
        if (f.exists()) {
            FileInputStream in = new FileInputStream(f);
            p.load(in);
            in.close();
        }
        return p;
    }

    static void list(File d, String ind) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        int frames = 0;
        long bytes = 0;
        for (File f : fs) {
            if (f.isDirectory()) {
                String[] in = f.list();
                Monkey.say(ind + f.getName() + "/ (" + (in == null ? 0 : in.length) + ")");
                if (!f.getName().equals("frames") && !f.getName().equals("stars")) list(f, ind + "  ");
            } else {
                Monkey.say(String.format(Locale.US, "%s%s (%d KB)", ind, f.getName(), f.length() / 1024));
            }
        }
    }

    // ---- the phone ----

    static void setup() throws Exception {
        Monkey.out = new File(System.getProperty("shots.out"), "night");
        Monkey.out.mkdirs();
        Monkey.log = new PrintStream(new FileOutputStream(new File(Monkey.out, "night.log")), true, "UTF-8");
        Monkey.app = RuntimeEnvironment.getApplication();
        Monkey.sa = Shadows.shadowOf(Monkey.app);
        Monkey.sa.grantPermissions(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.READ_MEDIA_IMAGES);
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                Monkey.uncaught.add(new RuntimeException("on thread " + t.getName(), e));
            }
        });
        Monkey.root = SessionController.sessionsRoot(Monkey.app);
        Monkey.pristine = new File[0];
        // plenty of storage: 64 GB, 40 GB free
        ShadowStatFs.registerStats("/", 16_000_000, 10_000_000, 10_000_000);
        // the motion sensors
        SensorManager sm = (SensorManager) Monkey.app.getSystemService(Context.SENSOR_SERVICE);
        ssm = Shadows.shadowOf(sm);
        gravity = ShadowSensor.newInstance(Sensor.TYPE_GRAVITY);
        gyro = ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE);
        accel = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER);
        rotation = ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR);
        for (Sensor s : new Sensor[]{gravity, gyro, accel, rotation}) ssm.addSensor(s);
        // the stars, and a place at 40 N where it's the middle of the night now
        FileInputStream in = new FileInputStream(System.getProperty("night.stars", "app/src/main/assets/stars.csv"));
        catalog = BrightStars.load(in);
        in.close();
        place = new Locator.Place();
        place.latDeg = 40.0;
        double utcH = (System.currentTimeMillis() % 86_400_000L) / 3_600_000.0;
        double lon = -15 * utcH;
        while (lon < -180) lon += 360;
        place.lonDeg = Math.round(lon * 100) / 100.0;
        place.altM = 1600;
        place.accuracyM = 30;
        place.timeMs = System.currentTimeMillis() - 10 * 60_000L;
        place.source = "gps";
        new Prefs(Monkey.app).savePlace(place);
        // the camera
        SimCamera probe = new SimCamera(catalog, System.currentTimeMillis(), place.lonDeg, 25);
        CameraManager cm = (CameraManager) Monkey.app.getSystemService(Context.CAMERA_SERVICE);
        Shadows.shadowOf(cm).addCamera("0", characteristics(probe));
        // the app
        Monkey.svc = Robolectric.buildService(CaptureService.class).create().get();
        Monkey.sa.setComponentNameAndServiceForBindService(new ComponentName(Monkey.app, CaptureService.class), Monkey.svc.onBind(new Intent()));
        Monkey.ctrl = Robolectric.buildActivity(MainActivity.class).setup();
        Monkey.a = Monkey.ctrl.get();
        Monkey.pump(1000);
        Monkey.say(String.format(Locale.US, "place %.2f, %.2f; %d stars in the sim's field; speed %.1fx", place.latDeg, place.lonDeg,
                probe.sim.starCount(), speed));
    }

    /** What the Pixel's main camera reports, for a 2040 x 1536 RAW (binned 2x2 from the 4080 x 3072 one). */
    static CameraCharacteristics characteristics(SimCamera cam) {
        CameraCharacteristics cc = ShadowCameraCharacteristics.newCameraCharacteristics();
        ShadowCameraCharacteristics s = Shadow.extract(cc);
        CameraModel t = cam.truth();
        s.set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK);
        s.set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, new int[]{0, 1, 3});
        s.set(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE, CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME);
        s.set(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE, new Range<Integer>(50, 6400));
        s.set(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY, 800);
        s.set(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE, new Range<Long>(10_000L, 16_000_000_000L));
        s.set(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL, 1023);
        s.set(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN, new BlackLevelPattern(new int[]{64, 64, 64, 64}));
        s.set(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT, CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB);
        float[] cm = cam.sim.colorMatrixD65();
        Rational[] e = new Rational[9];
        for (int k = 0; k < 9; k++) e[k] = new Rational(Math.round(cm[k] * 10000), 10000);
        s.set(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1, new ColorSpaceTransform(e));
        s.set(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1, CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D65);
        s.set(CameraCharacteristics.SENSOR_ORIENTATION, 90);
        s.set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, new float[]{6.9f});
        s.set(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES, new float[]{1.68f});
        s.set(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE, new SizeF(9.792f, 7.3728f));
        s.set(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE, new Size(cam.W, cam.H));
        s.set(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE, new Rect(0, 0, cam.W, cam.H));
        s.set(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, new Rect(0, 0, cam.W, cam.H));
        s.set(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION, new float[]{(float) t.fx, (float) t.fy, (float) t.cx, (float) t.cy, 0f});
        s.set(CameraCharacteristics.LENS_DISTORTION, new float[]{(float) t.k1, (float) t.k2, 0f, 0f, 0f});
        s.set(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE, SimCamera.MIN_FOCUS);
        s.set(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE, 0.3f);
        s.set(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION, CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE);
        return cc;
    }
}
