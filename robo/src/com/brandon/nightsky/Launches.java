package com.brandon.nightsky;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The ways into the app other than its icon: the shortcuts, the tile (over the lock screen), the plan's notifications
 * (to the plan, the morning summary, re-arming), and the same arriving while the app is already open on a page, a photo
 * or a session in progress. Each is opened as a new activity and as a new intent to a running one.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 36, qualifiers = "w412dp-h915dp-port-xhdpi")
public class Launches {
    @Test
    public void launches() throws Exception {
        Monkey.out = new File(System.getProperty("shots.out"), "launches");
        Monkey.out.mkdirs();
        Monkey.log = new PrintStream(new FileOutputStream(new File(Monkey.out, "launches.log")), true, "UTF-8");
        Monkey.app = RuntimeEnvironment.getApplication();
        Monkey.sa = Shadows.shadowOf(Monkey.app);
        Monkey.sa.grantPermissions(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES);
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                Monkey.uncaught.add(new RuntimeException("on thread " + t.getName(), e));
            }
        });
        Monkey.root = SessionController.sessionsRoot(Monkey.app);
        List<File> ps = new ArrayList<File>();
        for (String f : System.getProperty("shots.sessions").split(":")) {
            File[] l = new File(f).listFiles();
            if (l != null) ps.addAll(Arrays.asList(l));
        }
        Monkey.pristine = ps.toArray(new File[0]);
        Monkey.svc = Robolectric.buildService(CaptureService.class).create().get();
        Monkey.sa.setComponentNameAndServiceForBindService(new ComponentName(Monkey.app, CaptureService.class), Monkey.svc.onBind(new Intent()));
        Monkey.ctrl = Robolectric.buildActivity(MainActivity.class).setup();
        Monkey.a = Monkey.ctrl.get();
        Monkey.pump(800);
        Monkey.defineStarts();

        String[] ids = {"plan", "armplan", "summary", "arm", "stack5", "trails30", "quick", "reading", "nonsense"};
        // a new activity for each way in
        for (String id : ids) {
            fromStart("home");
            open("new, shortcut " + id, launch(id, false));
        }
        fromStart("home");
        open("new, tile over the lock screen", launch("arm", true));
        for (String sc : new String[]{"plan", "summary"}) {
            fromStart(sc.equals("summary") ? "summary" : "plan-2");
            open("new, notification to the " + sc, notification(sc, false));
        }
        fromStart("plan-2");
        armedThenLost();
        open("new, notification to re-arm", notification("plan", true));

        // the same arriving at an open app, on each kind of screen
        String[] on = {"home", "plan-2", "sessions", "result", "made-developing", "made-capturing"};
        for (String st : on) {
            for (String id : new String[]{"plan", "summary", "stack5", "reading"}) {
                fromStart(st);
                again("open on " + st + ", shortcut " + id, launch(id, false));
            }
            fromStart(st);
            again("open on " + st + ", notification to the plan", notification("plan", false));
        }
        Monkey.say("");
        Monkey.say("==== " + Monkey.problems.size() + " problems");
        for (String p : Monkey.problems) Monkey.say("PROBLEM " + p);
        Monkey.log.close();
    }

    static Intent launch(String id, boolean overLock) {
        return new Intent(MainActivity.ACTION_LAUNCH, MainActivity.launchUri(id)).setClass(Monkey.app, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OVER_LOCK, overLock).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static Intent notification(String screen, boolean rearm) {
        return new Intent(Monkey.app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(Plans.EXTRA_SCREEN, screen)
                .putExtra(Plans.EXTRA_REARM, rearm);
    }

    static void fromStart(String name) throws Exception {
        Monkey.reset();
        Monkey.starts.get(name).open();
        Monkey.pump(300);
    }

    /** The plan saved as armed but the service doesn't know (the app was closed by the system): it needs re-arming. */
    static void armedThenLost() throws Exception {
        if (!Monkey.tapKey("Arm the plan")) throw new IllegalStateException("no Arm the plan");
        Monkey.pump(500);
        Monkey.drainActivities();
        com.brandon.nightsky.processing.NightPlan p = Plans.load(Monkey.app);
        Monkey.say("  armed: " + Monkey.svc.plans().armed() + ", saved armed: " + (p != null && p.armed));
        // a new service, as after the process was killed
        Monkey.svc = Robolectric.buildService(CaptureService.class).create().get();
        Monkey.sa.setComponentNameAndServiceForBindService(new ComponentName(Monkey.app, CaptureService.class), Monkey.svc.onBind(new Intent()));
    }

    /** As a new activity (the app wasn't open). */
    static void open(String what, Intent i) throws Exception {
        Monkey.say("");
        Monkey.say("== " + what);
        try {
            Monkey.ctrl.pause().stop().destroy();
        } catch (Throwable ignored) {
        }
        try {
            Monkey.ctrl = Robolectric.buildActivity(MainActivity.class, i).setup();
            Monkey.a = Monkey.ctrl.get();
        } catch (Throwable e) {
            Monkey.crash("opening " + what, e);
            return;
        }
        after(what);
    }

    /** As a new intent to the activity that's open. */
    static void again(String what, Intent i) throws Exception {
        Monkey.say("");
        Monkey.say("== " + what + "  (was " + Monkey.signature() + ")");
        try {
            // as Android does it (Robolectric's newIntent doesn't pause first here): paused, the new intent, resumed
            Monkey.ctrl.pause();
            java.lang.reflect.Method m = android.app.Activity.class.getDeclaredMethod("onNewIntent", Intent.class);
            m.setAccessible(true);
            m.invoke(Monkey.a, i);
            Monkey.ctrl.resume();
        } catch (java.lang.reflect.InvocationTargetException e) {
            Monkey.crash("new intent " + what, e.getCause());
            return;
        } catch (Throwable e) {
            Monkey.crash("new intent " + what, e);
            return;
        }
        after(what);
    }

    static void after(String what) throws Exception {
        Monkey.pump(1200);
        String asked = Monkey.answerPermissions();
        String d = Monkey.answerDialogs(0, 0);
        Monkey.pump(600);
        Monkey.drainActivities();
        Monkey.checkUncaught(what);
        String toast = ShadowToast.getTextOfLatestToast();
        Monkey.say("  -> " + Monkey.signature() + asked + (d.isEmpty() ? "" : " dialog: " + d)
                + (toast != null ? " toast: \"" + toast + "\"" : "") + ", plan armed: " + Monkey.svc.plans().armed());
        ShadowToast.reset();
        Monkey.shot(new Monkey.Screen("launch", new ArrayList<String>(), null), Monkey.slug(what));
    }
}
