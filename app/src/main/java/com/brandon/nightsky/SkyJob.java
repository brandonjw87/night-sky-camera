package com.brandon.nightsky;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.io.IOException;

/**
 * Every two hours while a widget is on the home screen (E3): asks for a new cloud forecast for tonight's score
 * (D1) when the old one is over an hour old, then redraws the widgets. JobScheduler, not WorkManager: the app
 * uses only the platform's own libraries. Survives restarts.
 */
public final class SkyJob extends JobService {
    static final int JOB_ID = 41;
    private static final long PERIOD_MS = 2 * 3600_000L, FLEX_MS = 30 * 60_000L;

    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo j = new JobInfo.Builder(JOB_ID, new ComponentName(c, SkyJob.class))
                .setPeriodic(PERIOD_MS, FLEX_MS)
                .setPersisted(true)
                .build();
        js.schedule(j);
    }

    static void cancel(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Prefs prefs = new Prefs(app);
                    Locator.Place place = Locator.best(app, prefs);
                    if (place != null && prefs.forecast() && Forecasts.due(app, place.latDeg, place.lonDeg)) {
                        try {
                            Forecasts.fetch(app, place.latDeg, place.lonDeg);
                        } catch (IOException | RuntimeException e) {
                            // offline, or the service is down: the widget shows tonight without (or with the older) forecast
                            android.util.Log.i("NightSky", "sky job: no forecast: " + e);
                        }
                    }
                    Glance.refreshNow(app);
                } finally {
                    jobFinished(params, false);
                }
            }
        }, "sky-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false; // the next period comes anyway
    }
}
