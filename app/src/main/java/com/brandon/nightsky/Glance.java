package com.brandon.nightsky;

import android.content.Context;

import com.brandon.nightsky.processing.NightPlan;
import com.brandon.nightsky.processing.Tonight;

import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app at a glance, for the Quick Settings tile, the home-screen widget and the shortcuts (E3): a session
 * running, the night plan, tonight's sky (D1) and the last photo. Any thread; reads only what is already on the
 * phone (no location request, no download).
 */
final class Glance {
    /** "Tonight 82/100", or "Night Sky" when the place isn't known. */
    String title = "Night Sky";
    /** "Best 11:10 PM to 4:40 AM", or why there's no good window, or how to get one. */
    String window = "";
    /** The plan, a session running, or the last photo ("" if none of them). */
    String next = "";
    /** A few words under the tile's name ("" for none). */
    String tile = "";
    /** A session is running, or a plan is armed. */
    boolean active;
    /** Tonight's plan has sessions and isn't armed yet. */
    boolean planWaiting;
    /** The last photo's small picture (a file name in the thumbnails folder, "" if none). */
    String thumb = "";

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    private Glance() {}

    interface Callback {
        void done(Glance g);
    }

    /** Something changed (a session, the plan, the forecast): redraws the widgets and the plan's shortcut, off this thread. */
    static void refresh(Context c) {
        refresh(c, null);
    }

    /** The same, then {@code then} (on the worker thread). */
    static void refresh(Context c, final Runnable then) {
        final Context app = c.getApplicationContext();
        WORK.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshNow(app);
                } finally {
                    if (then != null) then.run();
                }
            }
        });
    }

    /** Works out the glance off this thread and hands it to {@code cb} on the main thread. */
    static void later(Context c, final Callback cb) {
        final Context app = c.getApplicationContext();
        WORK.execute(new Runnable() {
            @Override
            public void run() {
                Glance g;
                try {
                    g = now(app);
                } catch (RuntimeException e) {
                    android.util.Log.w("NightSky", "glance: " + e);
                    g = new Glance();
                }
                final Glance fg = g;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(fg);
                    }
                });
            }
        });
    }

    /** A photo is ready: its small copy is kept for the widget (off this thread), then the widgets are redrawn. */
    static void photoDone(Context c, final android.graphics.Bitmap preview, final long takenMs) {
        final Context app = c.getApplicationContext();
        WORK.execute(new Runnable() {
            @Override
            public void run() {
                String name = Plans.saveLastThumb(app, preview);
                if (!name.isEmpty()) new Prefs(app).setLastThumb(name, takenMs);
                refreshNow(app);
            }
        });
    }

    /** The same, on this thread. */
    static void refreshNow(Context c) {
        try {
            Glance g = now(c);
            Shortcuts.update(c, g);
            NightWidget.show(c, g);
        } catch (RuntimeException e) {
            android.util.Log.w("NightSky", "glance: " + e);
        }
    }

    static Glance now(Context c) {
        Context app = c.getApplicationContext();
        Glance g = new Glance();
        Prefs prefs = new Prefs(app);
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        g.thumb = prefs.lastThumb();

        // tonight's sky
        Locator.Place place = Locator.best(app, prefs);
        Tonight t = null;
        if (place != null) {
            t = Tonight.compute(place.latDeg, place.lonDeg, now, zone, Forecasts.cached(app, place.latDeg, place.lonDeg), Double.NaN);
            if (t.bestStartMs > 0) {
                g.title = String.format(Locale.US, "Tonight %d/100", t.score);
                g.window = "Best " + Tonight.clock(t.bestStartMs, zone) + " to " + Tonight.clock(t.bestEndMs, zone);
                g.tile = String.format(Locale.US, "Tonight %d · %s", t.score, Tonight.clock(t.bestStartMs, zone));
            } else {
                int dot = t.headline.indexOf(" · ");
                g.title = dot > 0 ? t.headline.substring(0, dot) : t.headline;
                g.window = dot > 0 ? capital(t.headline.substring(dot + 3)) : (t.lines.isEmpty() ? "" : t.lines.get(0));
                g.tile = String.format(Locale.US, "Tonight %d", t.maxScore);
            }
        } else {
            g.window = "Open the app and allow location to see tonight's sky";
        }

        // a session, the plan, the last photo
        CaptureService svc = CaptureService.instance;
        SessionController.Status s = svc != null ? svc.controller().status() : null;
        PlanRunner runner = svc != null ? svc.plans() : null;
        boolean running = s != null && s.phase != SessionController.Phase.IDLE && s.phase != SessionController.Phase.DONE
                && s.phase != SessionController.Phase.FAILED;
        if (running) {
            g.active = true;
            boolean capturing = s.phase == SessionController.Phase.CAPTURING || s.phase == SessionController.Phase.PAUSED;
            boolean developing = s.phase == SessionController.Phase.FINISHING || s.phase == SessionController.Phase.DEVELOPING;
            g.next = capturing ? (s.remainingMs >= 0 ? "Capturing: " + Math.max(1, Math.round(s.remainingMs / 60_000f)) + " min left" : "Capturing")
                    : developing ? "Developing the photo" : "Getting ready: lay the phone screen-down";
            g.tile = capturing ? "Capturing" : developing ? "Developing" : "Getting ready";
            return g;
        }
        if (runner != null && runner.armed()) {
            g.active = true;
            g.next = "Plan armed. Next: " + runner.nextText();
            long at = runner.nextAtMs();
            g.tile = at > 0 ? "Plan · " + Tonight.clock(at, zone) : "Plan armed";
            return g;
        }
        NightPlan p = Plans.load(app);
        if (p != null && p.armed && !p.finished()) {
            g.next = "Tonight's plan needs re-arming: open the app";
            g.tile = "Re-arm the plan";
            return g;
        }
        String night = Plans.planningNight(now, t, zone);
        if (p != null && !p.armed && p.night.equals(night)) {
            int left = 0;
            for (NightPlan.Item it : p.items) if (!it.finished()) left++;
            if (left > 0) {
                g.planWaiting = true;
                g.next = "Tonight's plan: " + left + (left == 1 ? " session" : " sessions") + ", not armed";
                return g;
            }
        }
        long last = prefs.lastResultMs();
        if (last > 0 && !g.thumb.isEmpty()) {
            g.next = "Last photo: " + day(last, now, zone) + Tonight.clock(last, zone);
        }
        return g;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** "" today, "yesterday " or "Oct 21, ". */
    private static String day(long ms, long now, TimeZone zone) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd", Locale.US);
        f.setTimeZone(zone);
        String a = f.format(new java.util.Date(ms)), today = f.format(new java.util.Date(now)),
                yesterday = f.format(new java.util.Date(now - 86_400_000L));
        if (a.equals(today)) return "";
        if (a.equals(yesterday)) return "yesterday ";
        java.text.SimpleDateFormat d = new java.text.SimpleDateFormat("MMM d, ", Locale.US);
        d.setTimeZone(zone);
        return d.format(new java.util.Date(ms));
    }
}
