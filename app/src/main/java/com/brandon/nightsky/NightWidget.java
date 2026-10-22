package com.brandon.nightsky;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;

/**
 * The home-screen widget (E3): the last photo, tonight's score and best window (D1), the night plan or the session
 * running (E2), and Arm. Arm starts a session with the saved settings (the app opens, ready to be laid down), or
 * arms tonight's plan when there is one waiting. Kept up to date by {@link SkyJob} every two hours, with the
 * forecast, and whenever a session or the plan changes.
 */
public final class NightWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        SkyJob.schedule(c);
        // worked out off the main thread; the broadcast stays open until the widgets are drawn
        final PendingResult pr = goAsync();
        Glance.refresh(c, new Runnable() {
            @Override
            public void run() {
                pr.finish();
            }
        });
    }

    @Override
    public void onEnabled(Context c) {
        SkyJob.schedule(c);
    }

    @Override
    public void onDisabled(Context c) {
        SkyJob.cancel(c);
    }

    /** Redraws every Night Sky widget with what {@code g} says (nothing happens if there are none). */
    static void show(Context c, Glance g) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            if (m == null) return;
            int[] ids = m.getAppWidgetIds(new ComponentName(c, NightWidget.class));
            if (ids != null && ids.length > 0) show(c, m, ids, g);
        } catch (RuntimeException e) {
            android.util.Log.w("NightSky", "widget: " + e);
        }
    }

    private static void show(Context c, AppWidgetManager m, int[] ids, Glance g) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_night);
        v.setTextViewText(R.id.w_title, g.title);
        v.setTextViewText(R.id.w_window, g.window);
        v.setViewVisibility(R.id.w_window, g.window.isEmpty() ? View.GONE : View.VISIBLE);
        v.setTextViewText(R.id.w_next, g.next);
        v.setViewVisibility(R.id.w_next, g.next.isEmpty() ? View.GONE : View.VISIBLE);
        Bitmap b = Plans.thumb(c, g.thumb);
        if (b != null) {
            // a small copy: a widget's pictures travel to the home screen in one parcel
            int w = 192, h = Math.max(1, Math.round(w * b.getHeight() / (float) b.getWidth()));
            Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
            if (s != b) b.recycle();
            v.setImageViewBitmap(R.id.w_thumb, s);
        } else {
            v.setImageViewResource(R.id.w_thumb, R.drawable.widget_thumb_empty);
        }
        v.setOnClickPendingIntent(R.id.w_text, launch(c, "home", 41));
        String photo = new Prefs(c).lastResultUri();
        if (photo != null && b != null) {
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(photo), "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            v.setOnClickPendingIntent(R.id.w_thumb, PendingIntent.getActivity(c, 44, view,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            v.setOnClickPendingIntent(R.id.w_thumb, launch(c, "home", 41));
        }
        if (g.active) {
            v.setTextViewText(R.id.w_arm, "Open");
            v.setOnClickPendingIntent(R.id.w_arm, launch(c, "home", 41));
        } else if (g.planWaiting) {
            v.setTextViewText(R.id.w_arm, "Arm plan");
            v.setOnClickPendingIntent(R.id.w_arm, launch(c, "armplan", 43));
        } else {
            v.setTextViewText(R.id.w_arm, "Arm");
            v.setOnClickPendingIntent(R.id.w_arm, launch(c, "arm", 42));
        }
        m.updateAppWidget(ids, v);
    }

    private static PendingIntent launch(Context c, String what, int code) {
        Intent i = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_LAUNCH).setData(MainActivity.launchUri(what))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
