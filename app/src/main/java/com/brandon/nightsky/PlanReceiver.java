package com.brandon.nightsky;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.brandon.nightsky.processing.NightPlan;

/**
 * The night plan's alarms (E2) and the phone's restarts. A start alarm goes to the capture service, which has
 * been waiting in the foreground since the plan was armed; if it isn't there any more (the phone or the app
 * restarted), Android won't let the camera start from the background, so Brandon gets a notification to re-arm
 * with one tap. The morning alarm posts the night's summary; the evening one says tonight's plan isn't armed.
 */
public final class PlanReceiver extends BroadcastReceiver {
    static final String ACTION_START = "com.brandon.nightsky.PLAN_START";
    static final String ACTION_MORNING = "com.brandon.nightsky.PLAN_MORNING";
    static final String ACTION_EVENING = "com.brandon.nightsky.PLAN_EVENING";

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        if (a == null) return;
        long now = System.currentTimeMillis();
        if (ACTION_START.equals(a)) {
            CaptureService svc = CaptureService.instance;
            if (svc != null) {
                // runs on the main thread like the service; it takes its own wake lock before this returns
                svc.onPlanAlarm();
                return;
            }
            NightPlan p = Plans.load(c);
            if (p != null && p.armed && !p.finished()) {
                p.note(now, "the start alarm found the app stopped: waiting to be re-armed");
                Plans.save(c, p);
                Plans.notifyRearm(c, "The app was stopped while the plan was armed.");
            }
        } else if (ACTION_MORNING.equals(a)) {
            NightPlan p = Plans.load(c);
            if (p == null || p.armed || p.summaryDone) return;
            Plans.notifyMorning(c, p);
        } else if (ACTION_EVENING.equals(a)) {
            NightPlan p = Plans.load(c);
            if (p == null || p.armed || p.finished()) return;
            Plans.notifyEvening(c, p);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a);
            NightPlan p = Plans.load(c);
            if (p == null) return;
            if (p.armed && !p.finished()) {
                p.note(now, boot ? "the phone restarted: waiting to be re-armed" : "the app was updated: waiting to be re-armed");
                Plans.save(c, p);
                Plans.notifyRearm(c, boot ? "The phone restarted while tonight's plan was armed."
                        : "The app was updated while tonight's plan was armed.");
            } else if (!p.armed && !p.finished() && boot) {
                // a restart forgets alarms: set the evening reminder again
                Plans.remindToArm(c, p, null, now);
            } else if (!p.armed && p.finished() && !p.summaryDone && boot) {
                // and the morning summary's
                long ended = 0;
                for (NightPlan.Item it : p.items) ended = Math.max(ended, it.endedMs);
                long at = NightPlan.morningMs(null, ended > 0 ? ended : now, p.zone);
                if (at <= now) Plans.notifyMorning(c, p);
                else Plans.setMorningAlarm(c, at);
            }
        }
    }
}
