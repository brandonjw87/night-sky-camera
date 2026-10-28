package com.brandon.nightsky;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Taps every control on every screen it can reach, with the real capture service bound, and reports what goes wrong:
 * exceptions (in a tap, in work posted to the main thread, in the service, on other threads), taps that hold the main
 * thread, intents that would hand a file:// address to another app, and a controller that fails or stops answering.
 * A screen is a start (a page, a session's result, a screen drawn from a made-up status) and the taps from there;
 * the screens a tap opens are walked too, up to {@link #MAX_DEPTH} taps deep.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 36, qualifiers = "w412dp-h915dp-port-xhdpi")
public class Monkey {
    static final int MAX_DEPTH = 3, MAX_SCREENS = 120;
    static Application app;
    static ShadowApplication sa;
    static CaptureService svc;
    static MainActivity a;
    static org.robolectric.android.controller.ActivityController<MainActivity> ctrl;
    static org.robolectric.shadows.ShadowActivity.PermissionsRequest lastAsk;
    /** Permissions refused (monkey.deny): what asking for them leads to. */
    static boolean deny;
    static File out, root;
    static File[] pristine;
    static PrintStream log;
    static int startId, taps, screens;
    static long lastVersion = -1;
    static String lastStatus = "";
    static final List<String> problems = new ArrayList<String>();
    static final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<Throwable>());
    static final Set<String> seen = new HashSet<String>();
    static final ArrayDeque<Screen> queue = new ArrayDeque<Screen>();
    static final Set<Dialog> handled = Collections.newSetFromMap(new IdentityHashMap<Dialog, Boolean>());
    /** Starts by name; what each needs set up before it's shown. */
    static final Map<String, Start> starts = new LinkedHashMap<String, Start>();
    static String only;

    interface Start {
        void open() throws Exception;
    }

    /** How to reach a screen: a start, then the controls tapped from there (their keys). */
    static final class Screen {
        final String start;
        final List<String> path;
        final String sig;

        Screen(String start, List<String> path, String sig) {
            this.start = start;
            this.path = path;
            this.sig = sig;
        }

        String name() {
            StringBuilder sb = new StringBuilder(start);
            for (String p : path) sb.append(" > ").append(p);
            return sb.toString();
        }
    }

    @Test
    public void monkey() throws Exception {
        out = new File(System.getProperty("shots.out"), "monkey");
        out.mkdirs();
        log = new PrintStream(new FileOutputStream(new File(out, "monkey.log")), true, "UTF-8");
        only = System.getProperty("shots.only", "");
        app = RuntimeEnvironment.getApplication();
        sa = Shadows.shadowOf(app);
        deny = Boolean.getBoolean("monkey.deny");
        String[] perms = {Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.READ_MEDIA_IMAGES};
        if (deny) sa.denyPermissions(perms);
        else sa.grantPermissions(perms);
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                uncaught.add(new RuntimeException("on thread " + t.getName(), e));
            }
        });
        root = SessionController.sessionsRoot(app);
        String[] from = System.getProperty("shots.sessions").split(":");
        List<File> ps = new ArrayList<File>();
        for (String f : from) {
            File[] l = new File(f).listFiles();
            if (l != null) ps.addAll(Arrays.asList(l));
        }
        pristine = ps.toArray(new File[0]);
        restoreSessions();
        // a place, as if found before
        Locator.Place pl = new Locator.Place();
        pl.latDeg = 39.95;
        pl.lonDeg = -105.25;
        pl.timeMs = System.currentTimeMillis() - 3600_000L;
        pl.source = "gps";
        new Prefs(app).savePlace(pl);

        svc = Robolectric.buildService(CaptureService.class).create().get();
        sa.setComponentNameAndServiceForBindService(new ComponentName(app, CaptureService.class), svc.onBind(new Intent()));
        ctrl = Robolectric.buildActivity(MainActivity.class).setup();
        a = ctrl.get();
        pump(1000);
        say("bound: " + (field(a, "service") != null) + ", sessions: " + SessionStoreNames());

        defineStarts();
        for (String s : starts.keySet()) {
            if (!only.isEmpty() && !Arrays.asList(only.split(",")).contains(s)) continue;
            queue.add(new Screen(s, new ArrayList<String>(), null));
        }
        while (!queue.isEmpty() && screens < MAX_SCREENS) {
            Screen s = queue.poll();
            screens++;
            try {
                walk(s);
            } catch (Throwable e) {
                problem("walking " + s.name() + " stopped: " + trace(e));
                restart();
            }
        }
        say("");
        say("==== " + screens + " screens, " + taps + " taps, " + problems.size() + " problems");
        for (String p : problems) say("PROBLEM " + p);
        log.close();
    }

    // ---- the starts ----

    static void defineStarts() {
        starts.put("home", new Start() {
            @Override
            public void open() throws Exception {
                fresh();
                page(0);
            }
        });
        starts.put("home-advanced", new Start() {
            @Override
            public void open() throws Exception {
                fresh();
                new Prefs(app).setAdvancedOpen(true);
                page(0);
            }
        });
        starts.put("plan", new Start() {
            @Override
            public void open() throws Exception {
                fresh();
                page(1);
            }
        });
        starts.put("plan-2", new Start() {
            @Override
            public void open() throws Exception {
                planOfTwo();
            }
        });
        starts.put("home-plan", new Start() {
            @Override
            public void open() throws Exception {
                planOfTwo();
                page(0);
            }
        });
        starts.put("plan-armed", new Start() {
            @Override
            public void open() throws Exception {
                planOfTwo();
                if (!tapKey("Arm the plan")) throw new IllegalStateException("no Arm the plan");
                pump(500);
                drainActivities();
                if (!svc.plans().armed()) throw new IllegalStateException("not armed");
                page(1);
            }
        });
        starts.put("summary", new Start() {
            @Override
            public void open() throws Exception {
                fresh();
                Shots.set(a, "summaryPlan", madeNight());
                page(3);
            }
        });
        starts.put("sites", new Start() {
            @Override
            public void open() throws Exception {
                fresh();
                page(4);
            }
        });
        starts.put("focus", new Start() {
            @Override
            public void open() throws Exception {
                fresh();
                page(5);
            }
        });
        starts.put("sessions", new Start() {
            @Override
            public void open() throws Exception {
                fresh();
                page(6);
                pump(1500);
            }
        });
        starts.put("merge", new Start() {
            @Override
            public void open() throws Exception {
                fresh();
                Shots.set(a, "mergeBase", folders().get(folders().size() - 1));
                Shots.set(a, "mergePick", null);
                page(7);
                pump(1500);
            }
        });
        starts.put("result", new Start() {
            @Override
            public void open() throws Exception {
                openSession(framesFolder());
            }
        });
        starts.put("result-trails", new Start() {
            @Override
            public void open() throws Exception {
                String t = trailsFolder();
                if (t == null) throw new IllegalStateException("no trails session");
                openSession(t);
            }
        });
        starts.put("made-merged", new Start() {
            @Override
            public void open() throws Exception {
                fresh();
                List<String> fs = folders();
                com.brandon.nightsky.processing.SessionStore n1 = new com.brandon.nightsky.processing.SessionStore(new File(root, fs.get(0)));
                com.brandon.nightsky.processing.SessionStore n2 = new com.brandon.nightsky.processing.SessionStore(new File(root, fs.get(1)));
                SessionController.Status st = new SessionController.Status();
                st.phase = SessionController.Phase.DONE;
                st.headline = "The merged photo is ready";
                st.detail = "Merged 2 sessions: 24 of 24 frames (3 min 12 s).";
                SessionController.Result r = new SessionController.Result();
                r.summary = st.detail;
                r.session = n1.dir.getName();
                r.brightness = 0.5f;
                r.merge = Shots.fakeMerge(n1, n2);
                st.result = r;
                Shots.call(a, "showResult", new Class<?>[]{SessionController.Status.class}, st);
                texts(st.headline, st.detail);
            }
        });
        starts.put("made-arming", new Start() {
            @Override
            public void open() throws Exception {
                fresh();
                Shots.call(a, "showArming", new Class<?>[0]);
                texts("Lay the phone down", "Screen-down, camera to the sky. It starts once it lies still.");
            }
        });
        starts.put("made-capturing", new Start() {
            @Override
            public void open() throws Exception {
                fresh();
                Shots.call(a, "showCapturing", new Class<?>[0]);
                texts("Capturing: 2 min 10 s left", "17 frames, 3 left out (clouds).");
            }
        });
        starts.put("made-developing", new Start() {
            @Override
            public void open() throws Exception {
                fresh();
                SessionController.Status st = new SessionController.Status();
                st.phase = SessionController.Phase.DEVELOPING;
                st.headline = "Developing";
                st.detail = "Lining up 52 frames";
                Shots.call(a, "showDeveloping", new Class<?>[]{SessionController.Status.class}, st);
                texts(st.headline, st.detail);
            }
        });
        starts.put("made-failed", new Start() {
            @Override
            public void open() throws Exception {
                fresh();
                SessionController.Status st = new SessionController.Status();
                st.phase = SessionController.Phase.FAILED;
                st.headline = "Not enough storage";
                st.detail = "It needs about 2 GB free for a 5-minute session.";
                st.storageShort = true;
                st.outcome = com.brandon.nightsky.processing.NightPlan.Outcome.FAILED;
                Shots.call(a, "showFailed", new Class<?>[]{SessionController.Status.class}, st);
                texts(st.headline, st.detail);
            }
        });
        starts.put("made-reading", new Start() {
            @Override
            public void open() throws Exception {
                fresh();
                com.brandon.nightsky.processing.SkyReading r = new com.brandon.nightsky.processing.SkyReading();
                r.timeMs = System.currentTimeMillis();
                r.latDeg = 39.95;
                r.lonDeg = -105.25;
                r.frames = 3;
                r.taken = 3;
                r.meterMag = 20.41;
                r.skyMag = 20.62;
                r.spread = 0.04;
                r.nelm = com.brandon.nightsky.processing.SkyReading.nelm(20.41);
                r.valid = true;
                r.exposureS = 8;
                r.iso = 800;
                r.headline = "20.41 mag/arcsec²: a suburban sky";
                r.lines.add("Between the stars: 20.62. 3 frames, 0.04 apart.");
                r.lines.add("The Moon is down; the Sun is 34° below the horizon.");
                SkyReadings.append(app, r);
                SessionController.Status st = new SessionController.Status();
                st.phase = SessionController.Phase.DONE;
                st.headline = r.headline;
                st.reading = r;
                Shots.call(a, "showReading", new Class<?>[]{SessionController.Status.class}, st);
            }
        });
    }

    /** Tonight's plan with two sessions added from the editor (not armed). */
    static void planOfTwo() throws Exception {
        fresh();
        page(1);
        for (int k = 0; k < 2; k++) {
            if (!tapKey("+ Add a session")) throw new IllegalStateException("no + Add a session");
            if (!tapKey("Add to the plan")) throw new IllegalStateException("no Add to the plan");
        }
    }

    /** Last night's plan as it ended: one photo, one cloudy all night, one that failed. */
    static com.brandon.nightsky.processing.NightPlan madeNight() throws Exception {
        com.brandon.nightsky.processing.NightPlan p = new com.brandon.nightsky.processing.NightPlan();
        long now = System.currentTimeMillis();
        p.night = com.brandon.nightsky.processing.NightPlan.nightOf(now - 14 * 3600_000L, java.util.TimeZone.getDefault());
        p.createdMs = now - 20 * 3600_000L;
        p.latDeg = 39.95;
        p.lonDeg = -105.25;
        p.armed = true;
        p.armedMs = now - 16 * 3600_000L;
        com.brandon.nightsky.processing.NightPlan.Item a1 = new com.brandon.nightsky.processing.NightPlan.Item();
        a1.state = com.brandon.nightsky.processing.NightPlan.State.DONE;
        a1.session = framesFolder();
        a1.photo = "content://media/external/images/media/1";
        a1.outcome = "16 good frames, like a 2-minute exposure";
        a1.startMs = a1.startedMs = now - 12 * 3600_000L;
        a1.endedMs = a1.startedMs + 300_000L;
        a1.tries = 1;
        p.add(a1);
        com.brandon.nightsky.processing.NightPlan.Item a2 = new com.brandon.nightsky.processing.NightPlan.Item();
        a2.state = com.brandon.nightsky.processing.NightPlan.State.SKIPPED;
        a2.startMs = now - 11 * 3600_000L;
        a2.tries = 3;
        a2.outcome = "Cloudy until it gave up at 1:10 AM (tried 3 times)";
        p.add(a2);
        com.brandon.nightsky.processing.NightPlan.Item a3 = new com.brandon.nightsky.processing.NightPlan.Item();
        a3.state = com.brandon.nightsky.processing.NightPlan.State.FAILED;
        a3.trails = true;
        a3.startMs = now - 10 * 3600_000L;
        a3.tries = 1;
        a3.outcome = "The phone wasn't lying still";
        p.add(a3);
        p.log.add("10:08 PM: the camera opened; 140 stars of 150: clear. Started.");
        p.log.add("11:10 PM: 12 of 140 stars (9%): cloudy. Trying again at 11:20 PM.");
        Plans.save(app, p);
        return p;
    }

    static void page(int p) throws Exception {
        Shots.set(a, "page", p);
        Shots.call(a, "showHome", new Class<?>[0]);
        pump(300);
    }

    static void texts(String h, String d) throws Exception {
        SessionController.Status st = new SessionController.Status();
        st.headline = h;
        st.detail = d;
        Shots.texts(a, st);
    }

    static void clearPlan() throws Exception {
        if (svc.plans().armed()) {
            svc.onStartCommand(new Intent(app, CaptureService.class).setAction(CaptureService.ACTION_CANCEL_PLAN), 0, ++startId);
            pump(500);
        }
        new File(new File(app.getFilesDir(), "plans"), "current.json").delete();
        Shots.set(a, "editPlan", null);
        Shots.set(a, "editItem", null);
    }

    static void openSession(String folder) throws Exception {
        SessionController.Status st = svc.controller().status();
        if (st.phase == SessionController.Phase.DONE && st.result != null && folder.equals(st.result.session)) {
            pump(300);
            return;
        }
        fresh();
        svc.controller().openSession(new File(root, folder));
        long end = System.currentTimeMillis() + 180_000L;
        while (System.currentTimeMillis() < end) {
            pump(200);
            st = svc.controller().status();
            if (st.phase == SessionController.Phase.DONE && st.result != null) break;
            if (st.phase == SessionController.Phase.FAILED) throw new IllegalStateException("open failed: " + st.headline + " " + st.detail);
        }
        if (svc.controller().status().phase != SessionController.Phase.DONE) throw new IllegalStateException("open timed out");
        pump(500);
    }

    // ---- walking a screen ----

    static void walk(Screen s) throws Exception {
        if (!reach(s)) return;
        lifecycle(s);
        if (!reach(s)) return;
        String sig = signature();
        seen.add(sig);
        List<View> cs = controls();
        List<String> keys = keys(cs);
        say("");
        say("== " + s.name() + "  [" + sig + "]  " + cs.size() + " controls");
        shot(s, "0");
        Set<String> followed = new HashSet<String>(keys);
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            int variants = 1;
            for (int var = 0; var < variants; var++) {
                if (i > 0 || var > 0) {
                    if (!reach(s)) return;
                }
                List<View> now = controls();
                View v = find(now, keys(now), key, occurrence(keys, i));
                if (v == null) {
                    say(String.format("  [%d] %s: not there after reopening", i, key));
                    break;
                }
                variants = Math.max(variants, variantsOf(v));
                String what = act(v, var);
                String after = signature();
                say(String.format("  [%d%s] %s%s -> %s", i, var > 0 ? "." + var : "", key, what, after));
                shot(s, String.format("%02d%s-%s", i, var > 0 ? "v" + var : "", slug(key)));
                if (what.contains("dialog")) variants = Math.max(variants, 2);
                if (after.equals(sig)) {
                    // controls that a tap enables or adds on the same screen: tapped once each, there and then
                    List<View> more = controls();
                    List<String> mk = keys(more);
                    for (int j = 0; j < mk.size(); j++) {
                        if (followed.contains(mk.get(j))) continue;
                        followed.add(mk.get(j));
                        String w2 = act(more.get(j), 0);
                        say(String.format("    then %s%s -> %s", mk.get(j), w2, signature()));
                        shot(s, String.format("%02d-then-%s", i, slug(mk.get(j))));
                        break;
                    }
                } else if (var == 0 && s.path.size() < MAX_DEPTH && !seen.contains(after) && !after.startsWith("gone")) {
                    seen.add(after);
                    List<String> p = new ArrayList<String>(s.path);
                    p.add(key);
                    queue.add(new Screen(s.start, p, after));
                }
            }
        }
    }

    /** The screen goes off and on again, then the activity is made again (as when the phone turns or the theme changes). */
    static void lifecycle(Screen s) throws Exception {
        String before = signature();
        try {
            ctrl.pause().stop();
            pump(300);
            ctrl.restart().start().resume();
            pump(400);
        } catch (Throwable e) {
            crash("screen off and on, on " + s.name(), e);
            return;
        }
        String mid = signature();
        try {
            ctrl.recreate();
            a = ctrl.get();
            pump(800);
        } catch (Throwable e) {
            crash("activity made again, on " + s.name(), e);
            return;
        }
        String after = signature();
        say("  screen off/on -> " + (mid.equals(before) ? "same" : mid) + "; made again -> " + (after.equals(before) ? "same" : after));
        checkUncaught("lifecycle on " + s.name());
    }

    /** Opens a screen from its start: everything else closed, the controller idle (or on the photo the start wants). */
    static boolean reach(Screen s) throws Exception {
        try {
            reset();
            starts.get(s.start).open();
            pump(250);
            for (String k : s.path) {
                if (!tapKey(k)) {
                    say("  couldn't reach " + s.name() + ": no " + k);
                    return false;
                }
            }
            return true;
        } catch (Throwable e) {
            problem("opening " + s.name() + ": " + trace(e));
            restart();
            return false;
        }
    }

    /** Taps the control with this key on the screen shown (and answers any dialog with its first choice). */
    static boolean tapKey(String key) throws Exception {
        List<View> cs = controls();
        View v = find(cs, keys(cs), key, 0);
        if (v == null) return false;
        act(v, 0);
        return true;
    }

    static void reset() throws Exception {
        for (Dialog d : new ArrayList<Dialog>(ShadowDialog.getShownDialogs())) {
            if (d.isShowing()) d.dismiss();
        }
        drainActivities();
        if (field(a, "reportOverlay") != null || field(a, "edit") != null) Shots.call(a, "closeReport", new Class<?>[0]);
    }

    /** As after installing: the controller idle, the sessions as copied in, the settings cleared (but the place), no plan. */
    static void fresh() throws Exception {
        idle();
        restoreSessions();
        android.content.SharedPreferences sp = (android.content.SharedPreferences) field(new Prefs(app), "sp");
        sp.edit().clear().commit();
        Locator.Place pl = new Locator.Place();
        pl.latDeg = 39.95;
        pl.lonDeg = -105.25;
        pl.timeMs = System.currentTimeMillis() - 3600_000L;
        pl.source = "gps";
        new Prefs(app).savePlace(pl);
        clearPlan();
        Shots.set(a, "summaryPlan", null);
        Shots.set(a, "sitePicked", "");
    }

    /** The controller idle: a photo shown is put away, anything running is cancelled. */
    static void idle() throws Exception {
        long end = System.currentTimeMillis() + 240_000L;
        int asked = 0;
        while (System.currentTimeMillis() < end) {
            SessionController.Status st = svc.controller().status();
            if (st.phase == SessionController.Phase.IDLE) {
                pump(200);
                return;
            }
            if (asked++ % 20 == 0) {
                if (st.phase == SessionController.Phase.DONE || st.phase == SessionController.Phase.FAILED) svc.controller().clearResult();
                else svc.controller().cancel();
            }
            pump(100);
        }
        problem("the controller didn't go idle: " + svc.controller().status().phase + " " + svc.controller().status().headline);
    }

    static void restoreSessions() throws Exception {
        for (File p : pristine) {
            File d = new File(root, p.getName());
            File pf = new File(p, "frames"), df = new File(d, "frames");
            boolean ok = d.isDirectory() && (!pf.isDirectory() || (df.isDirectory() && count(df) == count(pf)));
            if (!ok) {
                SessionController.delete(d);
                Shots.copy(p, d);
                say("  (session " + p.getName() + " put back)");
            }
        }
    }

    static int count(File d) {
        String[] l = d.list();
        return l == null ? 0 : l.length;
    }

    // ---- acting ----

    static int variantsOf(View v) {
        if (v instanceof ZoomImageView) return 3;
        if (v instanceof FrameStrip) return 2;
        if (v instanceof SeekBar) return 2;
        if (longClick(v) && v.hasOnClickListeners()) return 2;
        return 1;
    }

    /** Does what a finger would to the control, lets everything run, then answers any dialog. Says what happened. */
    static String act(View v, int var) throws Exception {
        taps++;
        int dialogs = ShadowDialog.getShownDialogs().size();
        long t0 = System.nanoTime();
        String note = "";
        try {
            if (v instanceof SeekBar) {
                SeekBar sb = (SeekBar) v;
                drag(sb, 0.5f, var == 0 ? 0.85f : 0.05f);
            } else if (v instanceof ZoomImageView) {
                if (var == 0) drag(v, 0.4f, 0.6f);
                else if (var == 1) tap(v, 0.5f, 0.5f);
                else {
                    tap(v, 0.5f, 0.5f);
                    tap(v, 0.5f, 0.5f);
                }
            } else if (v instanceof FrameStrip) {
                tap(v, var == 0 ? 0.3f : 0.9f, 0.5f);
            } else if (var == 1 && longClick(v)) {
                v.performLongClick();
                note = " (held)";
            } else if (!v.hasOnClickListeners() && touch(v) && !(v instanceof CompoundButton)) {
                press(v);
                note = " (pressed)";
            } else {
                v.performClick();
            }
        } catch (Throwable e) {
            crash("tap " + label(v), e);
            return " CRASHED";
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (ms > 500) problem(label(v) + ": the tap held the main thread " + ms + " ms");
        pump(250);
        String asked = answerPermissions();
        String d = answerDialogs(dialogs, var);
        pump(350);
        checkUncaught(label(v));
        return note + asked + (d.isEmpty() ? "" : " dialog: " + d) + (ms > 100 ? " (" + ms + " ms)" : "");
    }

    /** Answers a permission request as the phone would: granted or refused, as the permissions are set. */
    static String answerPermissions() throws Exception {
        org.robolectric.shadows.ShadowActivity.PermissionsRequest pr = Shadows.shadowOf(a).getLastRequestedPermission();
        if (pr == null || pr == lastAsk) return "";
        lastAsk = pr;
        int[] res = new int[pr.requestedPermissions.length];
        StringBuilder sb = new StringBuilder(" asked for");
        for (int k = 0; k < res.length; k++) {
            res[k] = app.checkSelfPermission(pr.requestedPermissions[k]);
            sb.append(' ').append(pr.requestedPermissions[k].replace("android.permission.", "").toLowerCase(java.util.Locale.US))
                    .append(res[k] == 0 ? "" : " (refused)");
        }
        try {
            a.onRequestPermissionsResult(pr.requestCode, pr.requestedPermissions, res);
        } catch (Throwable e) {
            crash("permission answer " + pr.requestCode, e);
        }
        pump(300);
        return sb.toString();
    }

    /** Answers dialogs shown since: its text box filled, an item picked, then OK (or the other choice for var 1). */
    static String answerDialogs(int before, int var) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int round = 0; round < 4; round++) {
            Dialog d = null;
            List<Dialog> shown = ShadowDialog.getShownDialogs();
            for (int k = shown.size() - 1; k >= 0 && k >= before; k--) {
                if (shown.get(k).isShowing() && !handled.contains(shown.get(k))) {
                    d = shown.get(k);
                    break;
                }
            }
            if (d == null) break;
            handled.add(d);
            sb.append(sb.length() > 0 ? " / " : "").append('"').append(dialogText(d)).append('"');
            if (!(d instanceof AlertDialog)) {
                d.dismiss();
                continue;
            }
            AlertDialog ad = (AlertDialog) d;
            try {
                EditText et = findEdit(ad.getWindow().getDecorView());
                if (et != null) {
                    et.setText(var == 0 ? "Test place 1" : "");
                    sb.append(" typed '").append(et.getText()).append("'");
                }
                ListView lv = ad.getListView();
                if (lv != null && lv.getAdapter() != null && lv.getAdapter().getCount() > 0) {
                    int k = var == 0 ? 0 : lv.getAdapter().getCount() - 1;
                    sb.append(" item ").append(k).append(" '").append(lv.getAdapter().getItem(k)).append("'");
                    lv.performItemClick(lv.getAdapter().getView(k, null, lv), k, lv.getItemIdAtPosition(k));
                    pump(200);
                }
                if (ad.isShowing()) {
                    int[] order = var == 0 ? new int[]{DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEUTRAL, DialogInterface.BUTTON_NEGATIVE}
                            : new int[]{DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL, DialogInterface.BUTTON_POSITIVE};
                    for (int which : order) {
                        android.widget.Button b = ad.getButton(which);
                        if (b != null && b.getVisibility() == View.VISIBLE && b.getText().length() > 0) {
                            sb.append(" pressed '").append(b.getText()).append("'");
                            b.performClick();
                            break;
                        }
                    }
                }
            } catch (Throwable e) {
                crash("dialog " + dialogText(d), e);
            }
            pump(300);
            if (d.isShowing()) d.dismiss();
        }
        return sb.toString();
    }

    static String dialogText(Dialog d) {
        StringBuilder sb = new StringBuilder();
        texts(d.getWindow().getDecorView(), sb);
        String s = sb.toString().replace('\n', ' ');
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }

    static EditText findEdit(View v) {
        if (v instanceof EditText) return (EditText) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText e = findEdit(g.getChildAt(i));
                if (e != null) return e;
            }
        }
        return null;
    }

    static void drag(View v, float fx0, float fx1) {
        int[] xy = new int[2];
        float w = v.getWidth(), h = v.getHeight();
        long t = SystemClock.uptimeMillis();
        touch(v, MotionEvent.ACTION_DOWN, t, t, w * fx0, h * 0.5f);
        for (int k = 1; k <= 4; k++) touch(v, MotionEvent.ACTION_MOVE, t, t + 20 * k, w * (fx0 + (fx1 - fx0) * k / 4f), h * 0.5f);
        touch(v, MotionEvent.ACTION_UP, t, t + 100, w * fx1, h * 0.5f);
    }

    static void tap(View v, float fx, float fy) {
        long t = SystemClock.uptimeMillis();
        touch(v, MotionEvent.ACTION_DOWN, t, t, v.getWidth() * fx, v.getHeight() * fy);
        touch(v, MotionEvent.ACTION_UP, t, t + 40, v.getWidth() * fx, v.getHeight() * fy);
        ShadowLooper.idleMainLooper(60, TimeUnit.MILLISECONDS);
    }

    static void press(View v) throws Exception {
        long t = SystemClock.uptimeMillis();
        touch(v, MotionEvent.ACTION_DOWN, t, t, v.getWidth() / 2f, v.getHeight() / 2f);
        pump(400);
        touch(v, MotionEvent.ACTION_UP, t, t + 400, v.getWidth() / 2f, v.getHeight() / 2f);
    }

    static void touch(View v, int action, long down, long at, float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, at, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        v.dispatchTouchEvent(e);
        e.recycle();
    }

    // ---- the controls on the screen ----

    /** The view on top: lining up by hand, else an overlay (report, edit, ...), else the screen. */
    static View top() throws Exception {
        Object es = field(a, "edit");
        if (es != null && field(es, "tapView") != null) return (View) field(es, "tapView");
        Object ov = field(a, "reportOverlay");
        if (ov != null) return (View) ov;
        return a.findViewById(android.R.id.content);
    }

    static List<View> controls() throws Exception {
        layout();
        List<View> l = new ArrayList<View>();
        collect(top(), l);
        return l;
    }

    static void collect(View v, List<View> l) {
        if (v.getVisibility() != View.VISIBLE) return;
        boolean ctl = v.isEnabled() && (v.hasOnClickListeners() || v instanceof CompoundButton || v instanceof SeekBar
                || v instanceof ZoomImageView || v instanceof FrameStrip || longClick(v) || touch(v));
        if (ctl) l.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), l);
        }
    }

    static boolean longClick(View v) {
        Object li = listener(v, "mOnLongClickListener");
        return li != null;
    }

    static boolean touch(View v) {
        return listener(v, "mOnTouchListener") != null;
    }

    static Object listener(View v, String name) {
        try {
            Field f = View.class.getDeclaredField("mListenerInfo");
            f.setAccessible(true);
            Object li = f.get(v);
            if (li == null) return null;
            Field g = li.getClass().getDeclaredField(name);
            g.setAccessible(true);
            return g.get(li);
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> keys(List<View> cs) {
        List<String> k = new ArrayList<String>();
        Map<String, Integer> n = new HashMap<String, Integer>();
        for (View v : cs) k.add(key(v));
        return k;
    }

    /** What the control is called, without numbers or its state, so it can be found again. */
    static String key(View v) {
        String s = label(v).replaceAll(" \\[(on|off)\\]$", "").replaceAll("[0-9]+", "#");
        return s;
    }

    static String label(View v) {
        String s;
        if (v instanceof SeekBar) s = "slider";
        else if (v instanceof ZoomImageView) s = "picture";
        else if (v instanceof FrameStrip) s = "frame strip";
        else if (v instanceof CompoundButton) s = ((CompoundButton) v).getText() + (((CompoundButton) v).isChecked() ? " [on]" : " [off]");
        else if (v instanceof TextView) s = ((TextView) v).getText().toString();
        else {
            StringBuilder sb = new StringBuilder();
            texts(v, sb);
            s = sb.toString();
        }
        if (s.trim().isEmpty() && v.getContentDescription() != null) s = v.getContentDescription().toString();
        if (s.trim().isEmpty()) s = v.getClass().getSimpleName();
        s = s.replace('\n', ' ').trim();
        return s.length() > 48 ? s.substring(0, 48) + "…" : s;
    }

    static void texts(View v, StringBuilder sb) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) sb.append(sb.length() > 0 ? " | " : "").append(t);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) texts(g.getChildAt(i), sb);
        }
    }

    /** Which of the controls with this key it was (0 for the first). */
    static int occurrence(List<String> keys, int i) {
        int n = 0;
        for (int k = 0; k < i; k++) if (keys.get(k).equals(keys.get(i))) n++;
        return n;
    }

    static View find(List<View> cs, List<String> keys, String key, int occ) {
        int n = 0;
        for (int k = 0; k < cs.size(); k++) {
            if (keys.get(k).equals(key)) {
                if (n == occ) return cs.get(k);
                n++;
            }
        }
        return null;
    }

    /** Where the app is: the edit screen's tab, an overlay's first words, a page, or the screen's first words. */
    static String signature() throws Exception {
        Object es = field(a, "edit");
        if (es != null) return "edit:" + field(es, "tab") + (field(es, "tapView") != null ? "+taps" : "");
        Object ov = field(a, "reportOverlay");
        if (ov != null) return "overlay:" + firstWords((View) ov);
        View c = a.findViewById(android.R.id.content);
        String fw = firstWords(c);
        if (fw.startsWith("‹")) return "page:" + field(a, "page");
        SessionController.Status st = svc.controller().status();
        return "screen:" + st.phase + ":" + fw;
    }

    static String firstWords(View v) {
        StringBuilder sb = new StringBuilder();
        texts(v, sb);
        String s = sb.toString();
        int bar = s.indexOf(" | ");
        if (bar > 0) s = s.substring(0, bar);
        s = s.replaceAll("[0-9]+", "#");
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    // ---- running things ----

    /** Lets the main looper and the clock run for a while, starts what the app started, and notes the controller. */
    static void pump(long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        do {
            try {
                ShadowLooper.idleMainLooper(25, TimeUnit.MILLISECONDS);
            } catch (Throwable e) {
                crash("posted work", e);
            }
            Intent i;
            while ((i = sa.getNextStartedService()) != null) {
                if (i.getComponent() == null || !i.getComponent().getClassName().equals(CaptureService.class.getName())) continue;
                checkIntent("service", i);
                try {
                    svc.onStartCommand(i, 0, ++startId);
                } catch (Throwable e) {
                    crash("service " + i.getAction(), e);
                }
            }
            noteStatus();
            Thread.sleep(15);
        } while (System.currentTimeMillis() < end);
    }

    static void noteStatus() {
        SessionController.Status st = svc.controller().status();
        if (st.version == lastVersion) return;
        lastVersion = st.version;
        String s = st.phase + ": " + st.headline + (st.detail.isEmpty() ? "" : " / " + st.detail);
        if (s.equals(lastStatus)) return;
        lastStatus = s;
        say("      status " + (s.length() > 160 ? s.substring(0, 160) + "…" : s));
        String low = (st.headline + " " + st.detail).toLowerCase(java.util.Locale.US);
        if (low.contains("exception") || low.contains("error") || low.contains("null")) problem("status says: " + s);
    }

    static void drainActivities() throws Exception {
        Intent i;
        while ((i = Shadows.shadowOf(a).getNextStartedActivity()) != null) {
            checkIntent("activity", i);
            say("      started " + describe(i));
        }
    }

    static String describe(Intent i) {
        StringBuilder sb = new StringBuilder(String.valueOf(i.getAction()));
        if (i.getData() != null) sb.append(' ').append(i.getData());
        Bundle b = i.getExtras();
        if (b != null) sb.append(' ').append(b.keySet());
        return sb.toString();
    }

    /** A file:// address handed to another app throws FileUriExposedException on the phone. */
    static void checkIntent(String what, Intent i) {
        List<Uri> us = new ArrayList<Uri>();
        if (i.getData() != null) us.add(i.getData());
        Bundle b = i.getExtras();
        if (b != null) {
            for (String k : b.keySet()) {
                Object o = b.get(k);
                if (o instanceof Uri) us.add((Uri) o);
                if (o instanceof Intent) checkIntent(what + " " + k, (Intent) o);
            }
        }
        ClipData cd = i.getClipData();
        if (cd != null) for (int k = 0; k < cd.getItemCount(); k++) if (cd.getItemAt(k).getUri() != null) us.add(cd.getItemAt(k).getUri());
        for (Uri u : us) if ("file".equals(u.getScheme()) && !"service".equals(what)) problem(what + " intent with a file:// address: " + describe(i));
    }

    static void checkUncaught(String where) {
        synchronized (uncaught) {
            for (Throwable e : uncaught) problem("uncaught after " + where + ": " + trace(e));
            uncaught.clear();
        }
    }

    static void crash(String where, Throwable e) {
        problem("CRASH in " + where + ": " + trace(e));
        try {
            restart();
        } catch (Exception x) {
            problem("restart failed: " + trace(x));
        }
    }

    /** A new activity, as after a crash (the service carries on, as it would in its own process... near enough). */
    static void restart() throws Exception {
        say("      (restarting the activity)");
        try {
            for (Dialog d : new ArrayList<Dialog>(ShadowDialog.getShownDialogs())) if (d.isShowing()) d.dismiss();
        } catch (Throwable ignored) {
        }
        ctrl = Robolectric.buildActivity(MainActivity.class).setup();
        a = ctrl.get();
        try {
            ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            problem("restart: " + trace(e));
        }
    }

    static String trace(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String line : sw.toString().split("\n")) {
            String t = line.trim();
            boolean keep = n == 0 || t.startsWith("Caused by") || (t.startsWith("at com.brandon") && !t.contains("Monkey."));
            if (keep && n < 14) {
                sb.append(n == 0 ? "" : "\n        ").append(t);
                n++;
            }
        }
        return sb.toString();
    }

    static void problem(String p) {
        problems.add(p);
        say("  !! " + p);
    }

    static void say(String s) {
        System.out.println(s);
        if (log != null) log.println(s);
    }

    // ---- sessions ----

    static List<String> folders() {
        List<String> l = new ArrayList<String>();
        for (File p : pristine) l.add(p.getName());
        Collections.sort(l);
        return l;
    }

    static String SessionStoreNames() {
        return folders().toString();
    }

    /** The session whose frames are kept (the edit screen needs them). */
    static String framesFolder() {
        for (File p : pristine) if (count(new File(p, "frames")) > 2) return p.getName();
        return folders().get(0);
    }

    static String trailsFolder() throws Exception {
        for (File p : pristine) {
            java.util.Properties pr = new com.brandon.nightsky.processing.SessionStore(new File(root, p.getName())).readSettings();
            if ("trails".equals(pr.getProperty("mode"))) return p.getName();
        }
        return null;
    }

    // ---- drawing ----

    static void layout() {
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        View v = a.findViewById(android.R.id.content);
        v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, dm.widthPixels, dm.heightPixels);
    }

    /** The screen, a third of its size; a scrolling page whole. */
    static void shot(Screen s, String what) {
        try {
            layout();
            View top = top();
            ScrollView sv = Shots.findScroll(top);
            View draw = sv != null && sv.getChildCount() > 0 && field(a, "reportOverlay") == null ? sv.getChildAt(0) : top;
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            int w = dm.widthPixels, h = dm.heightPixels;
            if (draw != top) {
                draw.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                h = Math.max(1, Math.min(4 * dm.heightPixels, draw.getMeasuredHeight()));
                draw.layout(0, 0, w, draw.getMeasuredHeight());
            }
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.eraseColor(0xFF000000);
            draw.draw(new Canvas(b));
            Bitmap small = Bitmap.createScaledBitmap(b, w / 3, h / 3, true);
            String name = slug(s.name()) + "__" + what;
            if (name.length() > 120) name = name.substring(0, 120);
            OutputStream os = new FileOutputStream(new File(out, name + ".png"));
            small.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.close();
            layout();
        } catch (Throwable e) {
            problem("drawing " + s.name() + " " + what + ": " + trace(e));
        }
    }

    static String slug(String s) {
        String t = s.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "").toLowerCase(java.util.Locale.US);
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    static Object field(Object o, String name) throws Exception {
        return Shots.field(o, name);
    }
}
