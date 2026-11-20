package com.brandon.nightsky;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.view.View;

import com.brandon.nightsky.processing.SessionStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowActivityManager;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.List;

/**
 * The stop recorder (2.1), around the real app: a crash written down as it happens (and in its session's log), Android's
 * own records of a process that ended (memory, a freeze, a force stop, an app that wasn't doing anything, an update),
 * what the home screen shows of each, sharing the details, and putting it away for good.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 36, qualifiers = "w412dp-h915dp-port-xhdpi")
public class Stops {
    static final Charset UTF8 = Charset.forName("UTF-8");
    static int failed;
    static String folder;

    @Test
    public void stops() throws Exception {
        Monkey.out = new File(System.getProperty("shots.out"), "stops");
        Monkey.out.mkdirs();
        Monkey.log = new PrintStream(new FileOutputStream(new File(Monkey.out, "stops.log")), true, "UTF-8");
        Monkey.app = RuntimeEnvironment.getApplication();
        Monkey.sa = Shadows.shadowOf(Monkey.app);
        Monkey.sa.grantPermissions(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_COARSE_LOCATION);
        Monkey.root = SessionController.sessionsRoot(Monkey.app);
        Monkey.svc = Robolectric.buildService(CaptureService.class).create().get();
        Monkey.sa.setComponentNameAndServiceForBindService(new ComponentName(Monkey.app, CaptureService.class), Monkey.svc.onBind(new Intent()));

        // the application started the recorder: a crash anywhere in the process is written down
        Thread.UncaughtExceptionHandler h = Thread.getDefaultUncaughtExceptionHandler();
        check(h != null && h.getClass().getName().startsWith(StopRecorder.class.getName()), "the application installed the crash handler");

        // a session that was capturing when it crashed
        File dir = new File(Monkey.root, "session_20261120_231500");
        new File(dir, "frames").mkdirs();
        new File(dir, "stars").mkdirs();
        folder = dir.getName();
        SessionStore st = new SessionStore(dir);
        st.log("capture: frame 12 of 19");
        StopRecorder.session("capturing frame 12 of 19|" + folder, st);
        h.uncaughtException(new Thread("develop"), new OutOfMemoryError("Failed to allocate a 104857616 byte allocation"));
        // then the process ends, and Android writes its own record of the crash a moment later
        ShadowActivityManager sam = Shadows.shadowOf((ActivityManager) Monkey.app.getSystemService(ActivityManager.class));
        sam.addApplicationExitInfo(ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder().setProcessName(Monkey.app.getPackageName())
                .setReason(ApplicationExitInfo.REASON_CRASH).setImportance(125).setTimestamp(System.currentTimeMillis() + 40L)
                .setProcessStateSummary(("capturing frame 12 of 19|" + folder).getBytes(UTF8)).setDescription("crash").build());
        StopRecorder.session("idle", null);
        File crash = new File(Monkey.app.getFilesDir(), StopRecorder.CRASH_FILE);
        check(crash.isFile(), "the crash is written down as it happens");
        check(st.readLog().contains("the app crashed: java.lang.OutOfMemoryError"), "and noted in its session's log");

        // the next launch shows it, once, with its details to share
        open();
        String card = card();
        check(card.contains("Last time, Night Sky stopped unexpectedly"), "the home screen says the app stopped");
        check(card.contains("It crashed (java.lang.OutOfMemoryError: Failed to allocate"), "and why: " + card);
        check(card.contains("while capturing frame 12 of 19"), "and what it was doing");
        check(card.contains("needs less"), "with a hint for memory");
        String shared = share();
        check(shared.contains("Night Sky " + BuildInfo.VERSION + " stopped: it crashed"), "the details say the version and why");
        check(shared.contains("at com.brandon.nightsky.Stops.stops"), "and where it went wrong (the stack)");
        check(shared.contains("Doing: capturing frame 12 of 19 (" + folder + ")"), "and what it was doing, in which session");
        check(shared.contains("Android's record of it: reason crash (4)"), "Android's record of the same crash is in its details");
        check(!shared.contains("an earlier stop"), "and isn't a second stop");
        check(st.readLog().contains("stop recorder: the app stopped at"), "the session's log gets the stop");
        open();
        check(card().contains("Last time, Night Sky stopped"), "it's shown again until put away");
        dismiss();
        check(!crash.isFile(), "put away, the crash file goes");
        open();
        check(card().isEmpty(), "and it isn't shown again, nor Android's record of it");

        // Android's records of how the process ended, each after the last one put away
        long now = System.currentTimeMillis() + 10_000L;
        // not doing anything: tidied away for memory, or ended by an update, is part of normal life
        sam.addApplicationExitInfo(exit(ApplicationExitInfo.REASON_LOW_MEMORY, 400, "idle", now + 1000L));
        sam.addApplicationExitInfo(exit(ApplicationExitInfo.REASON_PACKAGE_UPDATED, 125, "idle", now + 2000L));
        sam.addApplicationExitInfo(exit(ApplicationExitInfo.REASON_EXIT_SELF, 100, "idle", now + 3000L));
        open();
        check(card().isEmpty(), "an idle app tidied away, an update and the app closing itself aren't shown");

        // closed for memory in the middle of developing a rehearsal
        sam.addApplicationExitInfo(exit(ApplicationExitInfo.REASON_LOW_MEMORY, 125, "a rehearsal, developing|" + folder,
                now + 4000L));
        open();
        card = card();
        check(card.contains("Android closed it to free memory, while a rehearsal, developing."), "memory: " + card);
        shared = share();
        check(shared.contains("reason low memory (3)") && shared.contains("importance 125"), "Android's record is in the details");
        check(st.readLog().contains("Android closed it to free memory"), "the session's log gets it too");
        dismiss();

        // a freeze (ANR) with Android's trace, even while idle; and a version before 2.1, which said nothing
        String trace = "\"main\" prio=5 tid=1 Blocked\n  at com.brandon.nightsky.MainActivity.showHomePage(MainActivity.java:600)\n";
        sam.addApplicationExitInfo(ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder().setProcessName(Monkey.app.getPackageName())
                .setReason(ApplicationExitInfo.REASON_ANR).setImportance(100).setTimestamp(now + 6000L)
                .setProcessStateSummary("idle".getBytes(UTF8)).setDescription("user request after error: Input dispatching timed out")
                .setTraceInputStream(new ByteArrayInputStream(trace.getBytes(UTF8))).build());
        sam.addApplicationExitInfo(ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder().setProcessName(Monkey.app.getPackageName())
                .setReason(ApplicationExitInfo.REASON_SIGNALED).setStatus(9).setImportance(125).setTimestamp(now + 5000L).build());
        sam.addApplicationExitInfo(ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder().setProcessName(Monkey.app.getPackageName())
                .setReason(ApplicationExitInfo.REASON_SIGNALED).setStatus(9).setImportance(400).setTimestamp(now + 5500L).build());
        open();
        card = card();
        check(card.contains("It stopped responding, and Android closed it"), "an ANR: " + card);
        shared = share();
        check(shared.contains("Where it hung") && shared.contains("MainActivity.showHomePage"), "with Android's trace in the details");
        check(shared.contains("an earlier stop") && shared.contains("Android stopped it (signal 9)"),
                "the earlier stop (a version that said nothing, as a foreground service) is in the details");
        check(!shared.contains("importance 400"), "but not one that ended while it was cached");
        dismiss();
        open();
        check(card().isEmpty(), "all put away together");

        // what's kept with the process: never more than Android keeps, never half a character
        StringBuilder big = new StringBuilder("a");
        for (int i = 0; i < 100; i++) big.append('é');
        byte[] b = StopRecorder.cut(big.toString(), 128);
        check(b.length <= 128 && new String(b, UTF8).equals(big.substring(0, b.length == 127 ? 64 : 0)), "cut to " + b.length
                + " bytes between characters");

        Monkey.say("");
        Monkey.say("==== " + failed + " problems");
        Monkey.log.close();
        if (failed > 0) throw new AssertionError(failed + " problems");
    }

    static ApplicationExitInfo exit(int reason, int importance, String summary, long when) {
        return ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder().setProcessName(Monkey.app.getPackageName()).setReason(reason)
                .setImportance(importance).setTimestamp(when).setProcessStateSummary(summary.getBytes(UTF8)).setPss(2_150_000L)
                .setRss(2_480_000L).build();
    }

    /** The app opened from its icon, as a new activity, on the home screen. */
    static void open() throws Exception {
        if (Monkey.ctrl != null) {
            Monkey.ctrl.pause().stop().destroy();
            Monkey.pump(200);
        }
        Monkey.ctrl = Robolectric.buildActivity(MainActivity.class).setup();
        Monkey.a = Monkey.ctrl.get();
        Monkey.pump(800);
    }

    /** The stop card's words ("" if there's none). */
    static String card() throws Exception {
        View c = cardView();
        if (c == null) return "";
        StringBuilder sb = new StringBuilder();
        Monkey.texts(c, sb);
        return sb.toString();
    }

    static View cardView() throws Exception {
        for (View v : Monkey.controls()) {
            if (!"Share the details".equals(Monkey.label(v))) continue;
            View p = (View) v.getParent();
            return p != null ? (View) p.getParent() : null;
        }
        return null;
    }

    /** Taps Share the details: the text it hands to the share sheet. */
    static String share() throws Exception {
        Monkey.drainActivities();
        if (!Monkey.tapKey("Share the details")) {
            check(false, "no Share the details");
            return "";
        }
        Monkey.pump(200);
        Intent i = Shadows.shadowOf(Monkey.a).getNextStartedActivity();
        Intent inner = i != null ? (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT) : null;
        String text = inner != null ? inner.getStringExtra(Intent.EXTRA_TEXT) : null;
        check(text != null && Intent.ACTION_SEND.equals(inner.getAction()) && "text/plain".equals(inner.getType()), "shared as text");
        Monkey.say("    shared:\n" + text);
        return text != null ? text : "";
    }

    static void dismiss() throws Exception {
        check(Monkey.tapKey("Dismiss"), "Dismiss is there");
        Monkey.pump(200);
        check(card().isEmpty(), "Dismiss takes the card away");
    }

    static void check(boolean ok, String what) {
        Monkey.say((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failed++;
    }
}
