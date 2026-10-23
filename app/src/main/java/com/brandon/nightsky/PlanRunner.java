package com.brandon.nightsky;

import com.brandon.nightsky.processing.NightPlan;
import com.brandon.nightsky.processing.Tonight;
import com.brandon.nightsky.remote.Json;

import java.util.Locale;

/**
 * Carries out an armed night plan (E2) inside the capture service, on its main thread. Arming happens while
 * the app is on screen, so the camera service may open the camera later on its own; it then waits with the
 * camera closed and no wake lock. Each start is an alarm clock that wakes the phone even from deep sleep: the
 * session runs as a scheduled one (silent unless the plan says otherwise, gives up if the phone isn't lying
 * still, starts only if the sky is clear enough), and how it ended goes back into the plan, which decides
 * what comes next. When nothing is left, the morning summary is set and the service lets go.
 */
final class PlanRunner {
    private final CaptureService svc;
    private final SessionController controller;
    /** The armed plan (null when none is). */
    private NightPlan plan;
    /** The scheduled session running now (its item's id), or null. */
    private String runningId;
    /** For the notification, the screen and the viewer. */
    private volatile String nextText = "";
    private volatile long nextAtMs;
    private volatile String viewerJson;
    private volatile int version;

    PlanRunner(CaptureService svc, SessionController controller) {
        this.svc = svc;
        this.controller = controller;
    }

    boolean armed() {
        return plan != null && plan.armed;
    }

    /** Whether one of the plan's sessions is running now. */
    boolean running() {
        return runningId != null;
    }

    /** "5-minute stack at 10:10 PM, only if clear" (empty when nothing is armed). */
    String nextText() {
        return nextText;
    }

    /** When the next start's alarm goes off (0 if none). */
    long nextAtMs() {
        return nextAtMs;
    }

    /** Bumped whenever the plan changes, so the screen knows to read it again. */
    int version() {
        return version;
    }

    /** The plan for the remote viewer, as JSON (null if there is none). Any thread. */
    String viewerJson() {
        return viewerJson;
    }

    // ------------------------------------------------------------------------------------------------

    /**
     * Arms the saved plan (the app is on screen, so the camera may be used later). Returns why it couldn't,
     * or null. Arming again after a restart picks up where the plan was.
     */
    String arm() {
        long now = System.currentTimeMillis();
        NightPlan p = Plans.load(svc);
        if (p == null || p.items.isEmpty()) return "Tonight's plan has no sessions yet.";
        Prefs prefs = new Prefs(svc);
        Locator.Place place = Locator.best(svc, prefs);
        if (place != null) {
            p.latDeg = place.latDeg;
            p.lonDeg = place.lonDeg;
        }
        Tonight t = Double.isNaN(p.latDeg) ? null
                : Tonight.compute(p.latDeg, p.lonDeg, now, p.zone, Forecasts.cached(svc, p.latDeg, p.lonDeg), Double.NaN);
        if (Plans.planningNight(now, t, p.zone).compareTo(p.night) > 0) {
            return "That plan was for an earlier night. Make tonight's plan first.";
        }
        boolean again = p.armed;
        for (NightPlan.Item it : p.items) {
            // the app or the phone stopped during a session: its frames are kept for "Develop it"
            if (it.state == NightPlan.State.RUNNING) {
                p.ended(it, NightPlan.Outcome.FAILED, "The phone or the app stopped during this session. Its frames are kept: "
                        + "develop them from the start screen", now);
            }
        }
        p.resolve(t, now);
        p.armed = true;
        p.armedMs = now;
        p.summaryDone = false;
        p.note(now, (again ? "plan armed again" : "plan armed") + (t == null ? " (the place isn't known, so only clock times can start)" : ""));
        plan = p;
        runningId = null;
        Plans.cancelNotification(svc, Plans.NOTIF_REARM);
        Plans.cancelNotification(svc, Plans.NOTIF_EVENING);
        Plans.setEveningAlarm(svc, 0);
        schedule();
        if (plan != null && plan.armed) return null;
        // nothing could start: say why from the last of the plan's notes
        return "Nothing in the plan can start any more. " + lastSkip(p);
    }

    private static String lastSkip(NightPlan p) {
        for (int i = p.items.size() - 1; i >= 0; i--) {
            String o = p.items.get(i).outcome;
            if (!o.isEmpty()) return o;
        }
        return "";
    }

    /**
     * Ends the plan: nothing more starts. A session capturing now is developed with what it has; one still
     * getting ready is called off; one developing finishes.
     */
    void cancel(String why) {
        long now = System.currentTimeMillis();
        if (plan == null) {
            // armed on disk only (after a restart, before re-arming)
            NightPlan p = Plans.load(svc);
            if (p != null && p.armed) {
                p.cancelAll(now, why);
                finishPlan(p, now);
            }
            return;
        }
        plan.cancelAll(now, why);
        Plans.cancelStartAlarm(svc);
        nextText = "";
        nextAtMs = 0;
        if (runningId != null) {
            SessionController.Phase ph = controller.status().phase;
            if (ph == SessionController.Phase.CAPTURING || ph == SessionController.Phase.PAUSED) controller.finishNow();
            else if (ph.ordinal() < SessionController.Phase.CAPTURING.ordinal()) controller.cancel();
            Plans.save(svc, plan);
            publish();
            return; // its end is recorded in onIdle, which then finishes the plan
        }
        finish();
    }

    /** The start alarm went off (the phone is awake while this runs). */
    void onAlarm() {
        long now = System.currentTimeMillis();
        if (plan == null) {
            NightPlan p = Plans.load(svc);
            if (p != null && p.armed && !p.finished()) {
                p.note(now, "the start alarm found the plan not loaded: waiting to be re-armed");
                Plans.save(svc, p);
                Plans.notifyRearm(svc, "The app was restarted while the plan was armed.");
            }
            return;
        }
        if (!plan.armed || runningId != null) return;
        SessionController.Phase ph = controller.status().phase;
        if (ph != SessionController.Phase.IDLE && ph != SessionController.Phase.DONE && ph != SessionController.Phase.FAILED) {
            // a session started by hand: the plan's start waits for its end (onIdle schedules again)
            plan.note(now, "a session started by hand is running: the plan waits for it to end");
            Plans.save(svc, plan);
            publish();
            return;
        }
        NightPlan.Next n = plan.next(now);
        if (n == null || n.atMs > now + 60_000L) {
            // early (the clock changed) or nothing left: set the alarm again, or finish
            schedule();
            return;
        }
        start(n.item, now);
    }

    private void start(NightPlan.Item it, long now) {
        plan.started(it, now);
        runningId = it.id;
        nextText = it.what() + (it.tries > 1 ? ", try " + it.tries : "");
        nextAtMs = 0;
        Plans.save(svc, plan);
        publish();
        svc.startScheduled(settingsFor(plan, it, now, new Prefs(svc)));
    }

    /** The session's settings: the plan's item, the saved settings for the rest. */
    static SessionController.Settings settingsFor(NightPlan p, NightPlan.Item it, long now, Prefs prefs) {
        SessionController.Settings s = new SessionController.Settings();
        s.trails = it.trails;
        s.brightness = prefs.brightness();
        s.saveDng = prefs.saveDng();
        s.preflight = prefs.preflight();
        s.keepSatellites = prefs.keepSatellites();
        s.rejection = prefs.rejection();
        s.blend = prefs.blend();
        // the plan sets its own times, so "collect clear minutes" and quick developing stay off; a saved site still
        // gives "only if clear" its share of stars
        s.sounds = p.sounds;
        s.scheduled = true;
        s.onlyIfClear = it.onlyIfClear;
        s.minBatteryPct = p.minBatteryPct;
        s.stopAtDawn = true;
        s.placeTimeoutMs = 5 * 60_000L;
        // a repeat of an earlier session (the same stars a sidereal day later): offered for merging with it (G1)
        s.repeatOf = it.repeatOf;
        s.origin = String.format(Locale.US, "night plan of %s: %s, %s (try %d)", p.night, it.what(), p.describe(it), Math.max(1, it.tries));
        // the first try starts at the planned time; a later one as soon as the sky is checked
        boolean first = it.tries <= 1 && it.startMs > now;
        long start = first ? it.startMs : now;
        if (first) {
            s.startAtMs = it.startMs;
            s.startWhy = "as planned";
        }
        int dur = it.durationSec;
        if (it.endMs > 0) {
            s.endAtMs = it.endMs;
            if (it.trails) dur = (int) Math.max(60, (it.endMs - start) / 1000L);
        }
        s.durationSec = Math.max(60, Math.min(Plans.MAX_SESSION_SEC, dur));
        return s;
    }

    /**
     * The controller has nothing running any more (developed, failed, gave up or was called off). Records how a
     * scheduled session went and sets the next start. Returns true if the plan keeps the service waiting.
     */
    boolean onIdle(SessionController.Status s) {
        if (plan == null) return false;
        long now = System.currentTimeMillis();
        if (runningId != null) {
            NightPlan.Item it = plan.find(runningId);
            runningId = null;
            if (it != null && it.state == NightPlan.State.RUNNING) {
                NightPlan.Outcome o = s.outcome != null ? s.outcome : NightPlan.Outcome.FAILED;
                String detail = s.outcomeDetail != null && !s.outcomeDetail.isEmpty() ? s.outcomeDetail : s.headline;
                if (o == NightPlan.Outcome.DONE && s.result != null) {
                    it.photo = s.result.jpeg != null ? s.result.jpeg.toString() : "";
                    it.session = s.result.session;
                    it.spoken = s.result.report != null ? s.result.report.spoken : "";
                    it.thumb = Plans.saveThumb(svc, it.session, controller.preview());
                }
                plan.ended(it, o, detail, now);
            }
        }
        if (!plan.armed) {
            finish();
            return false;
        }
        schedule();
        return armed();
    }

    /** Sets the alarm for what comes next, or finishes the plan when nothing does. */
    private void schedule() {
        long now = System.currentTimeMillis();
        NightPlan.Next n = plan.next(now);
        if (n == null) {
            if (runningId != null) return;
            finish();
            return;
        }
        long at = Math.max(n.atMs, now + 1000);
        boolean exact = Plans.setStartAlarm(svc, at);
        NightPlan.Item it = n.item;
        String when = Tonight.clock(it.state == NightPlan.State.WAITING ? it.nextTryMs : it.startMs, plan.zone);
        nextText = it.state == NightPlan.State.WAITING
                ? it.what() + ": trying again at " + when + (it.lastWait.isEmpty() ? "" : " (" + it.lastWait + ")")
                : it.what() + " at " + when + (it.onlyIfClear ? ", only if clear" : "");
        nextAtMs = at;
        if (!exact) plan.note(now, "exact alarms aren't allowed: the start may be a few minutes late");
        Plans.save(svc, plan);
        publish();
        svc.showPlanWaiting(nextText, Tonight.clock(at, plan.zone));
    }

    /** Nothing more to run: the morning summary is set and the plan is put away. */
    private void finish() {
        long now = System.currentTimeMillis();
        NightPlan p = plan;
        plan = null;
        runningId = null;
        nextText = "";
        nextAtMs = 0;
        Plans.cancelStartAlarm(svc);
        if (p != null) finishPlan(p, now);
    }

    private void finishPlan(NightPlan p, long now) {
        p.armed = false;
        boolean ran = false;
        for (NightPlan.Item it : p.items) ran |= it.tries > 0;
        if (ran) {
            Tonight t = Double.isNaN(p.latDeg) ? null : Tonight.compute(p.latDeg, p.lonDeg, now, p.zone, null, Double.NaN);
            long at = NightPlan.morningMs(t, now, p.zone);
            Plans.setMorningAlarm(svc, at);
            p.note(now, "plan over: " + p.summaryTitle().replace("Last night: ", "") + "; the summary comes at " + Tonight.clock(at, p.zone));
        } else {
            p.summaryDone = true;
            p.note(now, "plan over: nothing ran");
        }
        Plans.save(svc, p);
        viewerJson = json(p, "", 0);
        version++;
        Glance.refresh(svc);
    }

    private void publish() {
        viewerJson = plan != null ? json(plan, nextText, nextAtMs) : null;
        version++;
        Glance.refresh(svc);
    }

    /** What the viewer shows of the plan. */
    static String json(NightPlan p, String next, long nextAtMs) {
        Json j = new Json().obj();
        j.put("armed", p.armed).put("night", p.night);
        j.put("title", p.armed ? "Tonight's plan" : p.summaryTitle());
        if (!next.isEmpty()) j.put("next", next);
        if (nextAtMs > 0) j.put("wakesMs", nextAtMs);
        j.arr("items");
        for (NightPlan.Item it : p.items) {
            long t = it.startedMs > 0 ? it.startedMs : it.startMs;
            j.obj().put("what", it.what()).put("when", t > 0 ? Tonight.clock(t, p.zone) : "")
                    .put("state", it.state.name().toLowerCase(Locale.US))
                    .put("text", it.outcome.isEmpty() ? p.describe(it) : it.outcome).end();
        }
        j.end();
        return j.end().toString();
    }
}
