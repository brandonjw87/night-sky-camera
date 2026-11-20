package com.brandon.nightsky;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.brandon.nightsky.processing.SessionStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The stop recorder (2.1): why the app last stopped when nobody chose to stop it.
 * <p>
 * A crash in the app's own code is written down as it happens, with what was running and where it went wrong. Any other
 * end (Android closing it for memory, a freeze Android calls "not responding", a crash below the app's code, a force
 * stop) is read back on the next launch from Android's own record of how the process ended (Android 11 and later), with
 * what the app had told Android it was doing then: a few words kept with the process as each step starts. The home
 * screen shows the newest stop once, with its details to share, and the session it stopped gets a line in its log.
 * <p>
 * Endings that are part of normal life are left out: an update, the app closing itself, and Android tidying away an
 * app that wasn't doing anything.
 */
final class StopRecorder {
    private StopRecorder() {}

    static final String CRASH_FILE = "last_crash.txt";
    private static final String PREFS = "stops";
    /** Android keeps at most this many bytes with the process. */
    private static final int SUMMARY_MAX = 128;
    /** Older stops than this aren't shown (days). */
    private static final long MAX_AGE_MS = 14L * 24 * 3600_000L;
    /** An ANR's trace in the details: its start, which is the main thread's stack (bytes). */
    private static final int TRACE_MAX = 24 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static Context app;
    private static Thread.UncaughtExceptionHandler previous;
    private static volatile String session = "idle", plan = "";
    private static volatile SessionStore sessionLog;
    private static String summary = "";

    /** Once per process, as it starts (the application's onCreate). */
    static synchronized void install(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    crashed(t, e);
                } catch (Throwable ignored) {
                    // whatever happens here, Android's own handling goes on below
                }
                Thread.UncaughtExceptionHandler p = previous;
                if (p != null) {
                    p.uncaughtException(t, e);
                } else {
                    // no handler before this one (a desktop JVM, never Android): what the JVM would print
                    System.err.print("Exception in thread \"" + t.getName() + "\" ");
                    e.printStackTrace(System.err);
                }
            }
        });
        publish();
    }

    /**
     * What a session is doing ("idle" when nothing runs: a few words, Android keeps 128 bytes), and the session whose log
     * a crash is noted in (null: none). Any thread.
     */
    static void session(String what, SessionStore st) {
        session = what == null || what.isEmpty() ? "idle" : what;
        sessionLog = st;
        publish();
    }

    /** A night plan armed and waiting for its next session ("" when there's none). Any thread. */
    static void plan(String what) {
        plan = what == null ? "" : what;
        publish();
    }

    /** What Android is told is running now ("idle" when nothing is). */
    static synchronized String current() {
        return summary;
    }

    /** Tells Android what's running now, if that changed. */
    private static synchronized void publish() {
        String s = !"idle".equals(session) ? session : !plan.isEmpty() ? plan : "idle";
        if (s.equals(summary)) return;
        summary = s;
        Context c = app;
        if (c == null || Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.setProcessStateSummary(cut(s, SUMMARY_MAX));
        } catch (RuntimeException ignored) {
            // not kept: a stop by Android then just won't say what was running
        }
    }

    /** The UTF-8 bytes of {@code s}, at most {@code max}, cut between characters. */
    static byte[] cut(String s, int max) {
        byte[] b = s.getBytes(UTF8);
        if (b.length <= max) return b;
        int n = max;
        // back to the start of a character (UTF-8 continuation bytes are 10xxxxxx)
        while (n > 0 && (b[n] & 0xC0) == 0x80) n--;
        byte[] out = new byte[n];
        System.arraycopy(b, 0, out, 0, n);
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // A crash in the app's own code

    private static void crashed(Thread t, Throwable e) throws IOException {
        Context c = app;
        if (c == null) return;
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        Runtime rt = Runtime.getRuntime();
        pw.println("when=" + System.currentTimeMillis());
        pw.println("thread=" + t.getName());
        pw.println("doing=" + summary);
        pw.println("memory=" + ((rt.totalMemory() - rt.freeMemory()) >> 20) + " MB of the " + (rt.maxMemory() >> 20) + " MB it may use");
        pw.println("--");
        e.printStackTrace(pw);
        pw.flush();
        FileOutputStream os = new FileOutputStream(new File(c.getFilesDir(), CRASH_FILE));
        try {
            os.write(sw.toString().getBytes(UTF8));
            os.getFD().sync();
        } finally {
            os.close();
        }
        SessionStore st = sessionLog;
        if (st != null) {
            try {
                st.log("the app crashed: " + e + " (on its " + t.getName() + " thread)");
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // On the next launch

    /** A stop the home screen hasn't shown yet. */
    static final class Stop {
        long whenMs;
        /** Why, in a few words: "it crashed (java.lang.OutOfMemoryError: …)". */
        String why = "";
        /** What it was doing then ("capturing frame 12 of 19"; "" if nothing it said). */
        String during = "";
        /** What may help next time ("" if nothing to say). */
        String hint = "";
        /** Everything known about it, to share. */
        String details = "";
        /** The folder of the session it stopped ("" if none). */
        String session = "";

        /** The card's line: "Thu 20 Nov 23:41: Android closed it to free memory, while developing". */
        String line() {
            return new SimpleDateFormat("EEE d MMM HH:mm", Locale.US).format(new Date(whenMs)) + ": " + capital(why)
                    + (during.isEmpty() ? "" : ", while " + during) + ".";
        }
    }

    /**
     * The newest stop not shown yet (null if none); the earlier ones unseen are in its details. The session it stopped
     * gets a line in its log, once.
     */
    static Stop pending(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long seen = sp.getLong("seenMs", 0L), now = System.currentTimeMillis();
        List<Stop> all = new ArrayList<Stop>();
        Stop crash = readCrash(c);
        if (crash != null && crash.whenMs > seen && now - crash.whenMs < MAX_AGE_MS) all.add(crash);
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> l = am != null ? am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 16) : null;
                if (l != null) {
                    for (ApplicationExitInfo x : l) {
                        long t = x.getTimestamp();
                        if (t <= seen || now - t > MAX_AGE_MS) continue;
                        if (crash != null && x.getReason() == ApplicationExitInfo.REASON_CRASH && Math.abs(t - crash.whenMs) < 60_000L) {
                            // the crash written down as it happened: Android's record of it adds to its details. Android
                            // writes it a moment later, as the process ends, so the crash's time moves up to it: put away,
                            // the crash puts its record away too, or the next launch would show the same crash again
                            crash.details += "\n\nAndroid's record of it: " + record(x);
                            crash.whenMs = Math.max(crash.whenMs, t);
                            continue;
                        }
                        Stop s = of(x);
                        if (s != null) all.add(s);
                    }
                }
            } catch (RuntimeException ignored) {
                // Android's record can't be read: only a crash written down is shown
            }
        }
        if (all.isEmpty()) return null;
        Collections.sort(all, new Comparator<Stop>() {
            @Override
            public int compare(Stop a, Stop b) {
                return Long.compare(b.whenMs, a.whenMs);
            }
        });
        Stop newest = all.get(0);
        StringBuilder sb = new StringBuilder(header(newest)).append(newest.details);
        for (int i = 1; i < all.size(); i++) {
            Stop s = all.get(i);
            sb.append("\n\n---- an earlier stop ----\n").append(header(s)).append(s.details);
        }
        newest.details = sb.toString();
        long logged = sp.getLong("loggedMs", 0L);
        for (Stop s : all) {
            if (s.session.isEmpty() || s.whenMs <= logged) continue;
            try {
                new SessionStore(new File(SessionController.sessionsRoot(c), s.session)).log("stop recorder: the app stopped at "
                        + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(s.whenMs)) + ": " + s.why
                        + (s.during.isEmpty() ? "" : ", while " + s.during));
            } catch (RuntimeException ignored) {
            }
        }
        sp.edit().putLong("loggedMs", Math.max(logged, newest.whenMs)).apply();
        return newest;
    }

    /** Shown and put away: it, and every stop before it, isn't shown again. */
    static void dismiss(Context c, Stop s) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putLong("seenMs", Math.max(sp.getLong("seenMs", 0L), s.whenMs)).apply();
        Stop crash = readCrash(c);
        if (crash != null && crash.whenMs <= s.whenMs) {
            //noinspection ResultOfMethodCallIgnored
            new File(c.getFilesDir(), CRASH_FILE).delete();
        }
    }

    /** The details' first lines: the app, the phone, when and why. */
    private static String header(Stop s) {
        return "Night Sky " + BuildInfo.VERSION + " stopped: " + s.why + "\n"
                + "When: " + new SimpleDateFormat("EEE d MMM yyyy HH:mm:ss", Locale.US).format(new Date(s.whenMs)) + "\n"
                + "Doing: " + (s.during.isEmpty() ? "nothing it had said" : s.during) + (s.session.isEmpty() ? "" : " (" + s.session + ")") + "\n"
                + "Phone: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n";
    }

    /** The crash written down as it happened (null if none, or it can't be read). */
    private static Stop readCrash(Context c) {
        File f = new File(c.getFilesDir(), CRASH_FILE);
        if (!f.isFile()) return null;
        String text;
        try {
            text = new String(read(new FileInputStream(f), 256 * 1024), UTF8);
        } catch (IOException e) {
            return null;
        }
        Stop s = new Stop();
        int cut = text.indexOf("\n--\n");
        String head = cut >= 0 ? text.substring(0, cut) : "";
        String trace = cut >= 0 ? text.substring(cut + 4) : text;
        String doing = "", thread = "", memory = "";
        for (String l : head.split("\n")) {
            int k = l.indexOf('=');
            if (k < 0) continue;
            String key = l.substring(0, k), v = l.substring(k + 1);
            if (key.equals("when")) {
                try {
                    s.whenMs = Long.parseLong(v.trim());
                } catch (NumberFormatException ignored) {
                }
            } else if (key.equals("thread")) {
                thread = v;
            } else if (key.equals("doing")) {
                doing = v;
            } else if (key.equals("memory")) {
                memory = v;
            }
        }
        if (s.whenMs <= 0) s.whenMs = f.lastModified();
        doing(s, doing);
        String first = trace.trim();
        int nl = first.indexOf('\n');
        if (nl >= 0) first = first.substring(0, nl);
        if (first.length() > 160) first = first.substring(0, 157) + "…";
        s.why = "it crashed (" + first.trim() + ")";
        s.hint = first.contains("OutOfMemoryError") ? "It ran out of memory: a shorter session, or Quick look, needs less."
                : "Sharing the details says exactly where it went wrong.";
        s.details = "Thread: " + thread + "\nMemory: " + memory + "\n\n" + trace.trim();
        return s;
    }

    /** What Android's record says, as a stop to show (null if it's an ending that's part of normal life). */
    private static Stop of(ApplicationExitInfo x) {
        Stop s = new Stop();
        s.whenMs = x.getTimestamp();
        byte[] sum = x.getProcessStateSummary();
        doing(s, sum != null ? new String(sum, UTF8) : "");
        // doing something: what the app said, or (a version before 2.1, which said nothing) running in front of the user or as a
        // foreground service (a session, developing, a plan or the viewer)
        boolean busy = !s.during.isEmpty() || (sum == null
                && x.getImportance() <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE);
        String desc = x.getDescription() != null ? x.getDescription().trim() : "";
        String battery = "In Settings › Apps › Night Sky › Battery, choose Unrestricted: then Android leaves it running.";
        switch (x.getReason()) {
            case ApplicationExitInfo.REASON_CRASH:
                s.why = "it crashed" + (desc.isEmpty() ? "" : " (" + desc + ")");
                s.hint = "Sharing the details says where it went wrong.";
                break;
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                s.why = "it crashed inside the phone's own code (a native crash)";
                s.hint = "Sharing the details says where; if the camera was running, it may be the camera's own software.";
                break;
            case ApplicationExitInfo.REASON_ANR:
                s.why = "it stopped responding, and Android closed it";
                s.hint = "Sharing the details says where it hung.";
                break;
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                s.why = "it couldn't start";
                break;
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                s.why = "Android closed it for using too much of the phone" + (desc.isEmpty() ? "" : " (" + desc + ")");
                s.hint = battery;
                break;
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                if (!busy) return null;
                s.why = "Android closed it to free memory";
                s.hint = "If it happens again, a shorter session, or Quick look, needs less memory, and closing other apps first helps.";
                break;
            case ApplicationExitInfo.REASON_SIGNALED:
                if (!busy) return null;
                s.why = "Android stopped it (signal " + x.getStatus() + ")";
                s.hint = battery;
                break;
            case ApplicationExitInfo.REASON_OTHER:
                if (!busy) return null;
                s.why = "Android closed it" + (desc.isEmpty() ? "" : " (" + desc + ")");
                s.hint = battery;
                break;
            case ApplicationExitInfo.REASON_FREEZER:
                if (!busy) return null;
                s.why = "Android froze it in the background, then closed it";
                s.hint = battery;
                break;
            case ApplicationExitInfo.REASON_USER_REQUESTED:
                if (!busy) return null;
                s.why = "it was force-stopped (in Settings, or by a cleaning app)";
                break;
            case ApplicationExitInfo.REASON_USER_STOPPED:
                if (!busy) return null;
                s.why = "it was stopped from the phone's list of running apps";
                break;
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                if (!busy) return null;
                s.why = "a part of Android it relies on stopped";
                break;
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                if (!busy) return null;
                s.why = "a permission it uses was changed in the settings";
                break;
            case ApplicationExitInfo.REASON_UNKNOWN:
                if (!busy) return null;
                s.why = "it stopped, and Android doesn't say why";
                break;
            default:
                // an update, a package change, the app closing itself: part of normal life
                return null;
        }
        StringBuilder d = new StringBuilder("Android's record: ").append(record(x));
        if (x.getReason() == ApplicationExitInfo.REASON_ANR) {
            try {
                InputStream in = x.getTraceInputStream();
                if (in != null) {
                    String tr = new String(read(in, TRACE_MAX), UTF8);
                    d.append("\n\nWhere it hung (the start of Android's trace):\n").append(tr);
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        s.details = d.toString();
        return s;
    }

    /** Android's record, one line. */
    private static String record(ApplicationExitInfo x) {
        String desc = x.getDescription() != null ? x.getDescription() : "";
        String mem = x.getPss() > 0 || x.getRss() > 0 ? String.format(Locale.US, ", memory %d MB (RSS %d MB)", x.getPss() >> 10, x.getRss() >> 10) : "";
        return String.format(Locale.US, "reason %s (%d)%s, status %d, importance %d%s, process %s", reasonName(x.getReason()),
                x.getReason(), desc.isEmpty() ? "" : ", \"" + desc + "\"", x.getStatus(), x.getImportance(), mem, x.getProcessName());
    }

    private static String reasonName(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "exit self";
            case ApplicationExitInfo.REASON_SIGNALED: return "signaled";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low memory";
            case ApplicationExitInfo.REASON_CRASH: return "crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "initialization failure";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "permission change";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "excessive resource usage";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "user requested";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "user stopped";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "dependency died";
            case ApplicationExitInfo.REASON_OTHER: return "other";
            case ApplicationExitInfo.REASON_FREEZER: return "freezer";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE: return "package state change";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED: return "package updated";
            default: return "unknown";
        }
    }

    /** What the app had said it was doing ("what|session folder"), into {@code s} ("idle" is nothing). */
    private static void doing(Stop s, String summary) {
        String v = summary == null ? "" : summary.trim();
        int bar = v.lastIndexOf('|');
        if (bar >= 0) {
            String f = v.substring(bar + 1).trim();
            // a session's folder, as the app names them, and nothing else
            s.session = f.matches("session_[0-9_]+") ? f : "";
            v = v.substring(0, bar).trim();
        }
        s.during = v.equals("idle") ? "" : v;
    }

    private static byte[] read(InputStream in, int max) throws IOException {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (bo.size() < max && (n = in.read(buf, 0, Math.min(buf.length, max - bo.size()))) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
