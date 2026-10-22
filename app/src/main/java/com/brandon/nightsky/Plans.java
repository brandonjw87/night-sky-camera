package com.brandon.nightsky;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.brandon.nightsky.processing.NightPlan;
import com.brandon.nightsky.processing.Tonight;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Night plans on the phone (E2): the plan being made, armed or just run, kept in the app's files with the past
 * nights' plans and a small picture of each scheduled session; the alarms that start the sessions and bring the
 * morning summary; and the notifications that need Brandon (re-arm after a restart, tonight's plan not armed,
 * the morning summary). {@link NightPlan} decides what happens; this keeps it and tells Android.
 */
final class Plans {
    static final String CHANNEL = "plans";
    static final int NOTIF_REARM = 21, NOTIF_MORNING = 22, NOTIF_EVENING = 23;
    /** MainActivity extra: which screen to open ("plan" or "summary"), and "re-arm the plan now". */
    static final String EXTRA_SCREEN = "screen", EXTRA_REARM = "rearm";
    /** The longest a scheduled session runs. */
    static final int MAX_SESSION_SEC = NightPlan.MAX_SESSION_SEC;
    /** Past nights' plans kept for their summaries. */
    private static final int KEEP_NIGHTS = 30;
    private static final Object LOCK = new Object();

    private Plans() {}

    // ------------------------------------------------------------------------------------------------
    // Files

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "plans");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File current(Context c) {
        return new File(dir(c), "current.json");
    }

    /** The plan being made, armed or last run; null if there is none (or it can't be read). */
    static NightPlan load(Context c) {
        synchronized (LOCK) {
            File f = current(c);
            if (!f.exists()) return null;
            try {
                return NightPlan.fromJson(readText(f));
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("NightSky", "the night plan couldn't be read", e);
                return null;
            }
        }
    }

    static void save(Context c, NightPlan p) {
        synchronized (LOCK) {
            writeText(current(c), p.toJson());
        }
    }

    /** Moves the current plan to the past nights (kept for its summary and repeats) and starts with none. */
    static void archive(Context c, NightPlan p) {
        synchronized (LOCK) {
            boolean ran = false;
            for (NightPlan.Item it : p.items) ran |= it.tries > 0;
            if (ran) {
                File f = new File(dir(c), "night_" + p.night + ".json");
                for (int k = 2; f.exists(); k++) f = new File(dir(c), "night_" + p.night + "_" + k + ".json");
                writeText(f, p.toJson());
            }
            //noinspection ResultOfMethodCallIgnored
            current(c).delete();
            File[] old = pastFiles(c);
            for (int i = KEEP_NIGHTS; i < old.length; i++) {
                deleteThumbs(c, old[i]);
                //noinspection ResultOfMethodCallIgnored
                old[i].delete();
            }
        }
    }

    /** Past nights' plan files, newest first. */
    private static File[] pastFiles(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null) return new File[0];
        List<File> out = new ArrayList<File>();
        for (File f : fs) if (f.getName().startsWith("night_") && f.getName().endsWith(".json")) out.add(f);
        File[] a = out.toArray(new File[0]);
        Arrays.sort(a, new Comparator<File>() {
            @Override
            public int compare(File x, File y) {
                return y.getName().compareTo(x.getName());
            }
        });
        return a;
    }

    /** The newest past night's plan (null if none). */
    static NightPlan lastArchived(Context c) {
        synchronized (LOCK) {
            for (File f : pastFiles(c)) {
                try {
                    return NightPlan.fromJson(readText(f));
                } catch (IOException | RuntimeException ignored) {
                }
            }
            return null;
        }
    }

    /** Marks the newest past night's plan as summarised (its card leaves the home screen). */
    static void archivedSummaryDone(Context c) {
        synchronized (LOCK) {
            for (File f : pastFiles(c)) {
                try {
                    NightPlan p = NightPlan.fromJson(readText(f));
                    p.summaryDone = true;
                    writeText(f, p.toJson());
                    return;
                } catch (IOException | RuntimeException ignored) {
                }
            }
        }
    }

    private static void deleteThumbs(Context c, File planFile) {
        try {
            NightPlan p = NightPlan.fromJson(readText(planFile));
            for (NightPlan.Item it : p.items) {
                if (!it.thumb.isEmpty()) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(thumbDir(c), it.thumb).delete();
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /**
     * The night to plan: the one under way if it's dark already, else the coming one. Without the sky worked
     * out (no place yet), the night of the day, or the one under way before 6 AM.
     */
    static String planningNight(long nowMs, Tonight t, TimeZone zone) {
        if (t != null) return NightPlan.nightOf(t.fromMs, zone);
        Calendar c = Calendar.getInstance(zone, Locale.US);
        c.setTimeInMillis(nowMs);
        if (c.get(Calendar.HOUR_OF_DAY) < 6) return NightPlan.nightOf(nowMs, zone);
        return NightPlan.nightOf(nowMs + 12 * 3600_000L, zone);
    }

    /**
     * The plan to edit for {@code night}: the saved one if it is for that night, else a new one (a finished or
     * stale plan goes to the past nights first; an armed one is never replaced).
     */
    static NightPlan forNight(Context c, String night, Locator.Place place, long nowMs) {
        NightPlan p = load(c);
        if (p != null && (p.night.equals(night) || p.armed)) return p;
        if (p != null) archive(c, p);
        NightPlan n = new NightPlan();
        n.night = night;
        n.createdMs = nowMs;
        n.zone = TimeZone.getDefault();
        if (place != null) {
            n.latDeg = place.latDeg;
            n.lonDeg = place.lonDeg;
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------
    // Pictures

    private static File thumbDir(Context c) {
        File d = new File(dir(c), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Saves a small copy of a scheduled session's picture; its file name, or "" if it couldn't. */
    static String saveThumb(Context c, String session, Bitmap preview) {
        if (preview == null || session == null || session.isEmpty()) return "";
        try {
            int w = 480, h = Math.max(1, Math.round(w * preview.getHeight() / (float) preview.getWidth()));
            Bitmap small = Bitmap.createScaledBitmap(preview, w, h, true);
            String name = session + ".jpg";
            OutputStream o = new FileOutputStream(new File(thumbDir(c), name));
            try {
                small.compress(Bitmap.CompressFormat.JPEG, 85, o);
            } finally {
                o.close();
            }
            if (small != preview) small.recycle();
            return name;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            android.util.Log.w("NightSky", "no picture for the plan: " + e);
            return "";
        }
    }

    static Bitmap thumb(Context c, String name) {
        if (name == null || name.isEmpty()) return null;
        File f = new File(thumbDir(c), name);
        return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }

    /**
     * The night's sessions side by side for the morning notification: each one's picture (or a dark tile saying
     * why there isn't one) with its time underneath.
     */
    static Bitmap collage(Context c, NightPlan p) {
        List<NightPlan.Item> items = new ArrayList<NightPlan.Item>();
        for (NightPlan.Item it : p.items) if (it.tries > 0 || it.state == NightPlan.State.SKIPPED) items.add(it);
        if (items.isEmpty()) return null;
        int n = Math.min(4, items.size());
        int tw = 300, th = 225, cap = 40, gap = 8;
        Bitmap b = Bitmap.createBitmap(n * tw + (n - 1) * gap, th + cap, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(0xFF000000);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(MainActivity.RED);
        text.setTextSize(26);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        dim.setColor(MainActivity.RED_DIM);
        dim.setTextSize(24);
        Paint tile = new Paint();
        tile.setColor(MainActivity.RED_DARK);
        for (int i = 0; i < n; i++) {
            NightPlan.Item it = items.get(i);
            int x = i * (tw + gap);
            RectF r = new RectF(x, 0, x + tw, th);
            Bitmap t = thumb(c, it.thumb);
            if (t != null) {
                // fill the tile, cropping the middle
                float s = Math.max(tw / (float) t.getWidth(), th / (float) t.getHeight());
                int sw = Math.round(tw / s), sh = Math.round(th / s);
                int sx = (t.getWidth() - sw) / 2, sy = (t.getHeight() - sh) / 2;
                cv.drawBitmap(t, new Rect(sx, sy, sx + sw, sy + sh), r, null);
                t.recycle();
            } else {
                cv.drawRoundRect(r, 14, 14, tile);
                String why = it.state == NightPlan.State.SKIPPED ? "Skipped" : it.state == NightPlan.State.FAILED ? "Failed"
                        : it.state == NightPlan.State.CANCELLED ? "Cancelled" : "No picture";
                cv.drawText(why, x + 16, th / 2f + 9, text);
            }
            long when = it.startedMs > 0 ? it.startedMs : it.startMs;
            String label = (when > 0 ? Tonight.clock(when, p.zone) + "  " : "") + (it.trails ? "Trails" : "Stack");
            cv.drawText(label, x + 4, th + 30, dim);
        }
        return b;
    }

    // ------------------------------------------------------------------------------------------------
    // Alarms

    private static PendingIntent receiver(Context c, String action, int code) {
        Intent i = new Intent(c, PlanReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent openApp(Context c, String screen, boolean rearm, int code) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_SCREEN, screen)
                .putExtra(EXTRA_REARM, rearm);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * The next start: an alarm clock, which Android keeps to the minute even when the phone has been idle for
     * hours (it shows as the next alarm in the status bar while a plan is armed). Without the exact-alarm
     * permission, the nearest Android allows. Returns false in that case.
     */
    static boolean setStartAlarm(Context c, long atMs) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
        PendingIntent pi = receiver(c, PlanReceiver.ACTION_START, 31);
        try {
            if (am.canScheduleExactAlarms()) {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(atMs, openApp(c, "plan", false, 32)), pi);
                return true;
            }
        } catch (SecurityException e) {
            android.util.Log.w("NightSky", "exact alarm refused", e);
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
        return false;
    }

    static void cancelStartAlarm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(receiver(c, PlanReceiver.ACTION_START, 31));
    }

    /** The morning summary, at about this time (it needn't be to the minute). */
    static void setMorningAlarm(Context c, long atMs) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, receiver(c, PlanReceiver.ACTION_MORNING, 33));
    }

    /** "Tonight's plan isn't armed yet", at about this time; 0 cancels it. */
    static void setEveningAlarm(Context c, long atMs) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = receiver(c, PlanReceiver.ACTION_EVENING, 34);
        if (atMs <= 0) am.cancel(pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
    }

    /**
     * Sets the evening reminder for a saved plan that isn't armed: an hour before its first start (or at
     * sunset), so there is time to arm it and put the phone out. Cancels it otherwise.
     */
    static void remindToArm(Context c, NightPlan p, Tonight t, long nowMs) {
        if (p == null || p.armed || p.finished() || p.items.isEmpty()) {
            setEveningAlarm(c, 0);
            return;
        }
        long first = Long.MAX_VALUE;
        for (NightPlan.Item it : p.items) if (it.state == NightPlan.State.PLANNED && it.startMs > 0) first = Math.min(first, it.startMs);
        long at = first == Long.MAX_VALUE ? 0 : first - 3600_000L;
        if (t != null && t.sunsetMs > 0) at = at == 0 ? t.sunsetMs : Math.min(at, t.sunsetMs);
        if (at <= nowMs + 60_000L) {
            setEveningAlarm(c, 0);
            return;
        }
        setEveningAlarm(c, at);
    }

    // ------------------------------------------------------------------------------------------------
    // Notifications

    static void channel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Night plans", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Re-arming a plan after a restart, a plan not armed in the evening, and the morning summary");
        ch.enableVibration(false);
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    private static Notification.Builder builder(Context c) {
        channel(c);
        return new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notification).setColor(MainActivity.RED).setAutoCancel(true);
    }

    /** "Tap to re-arm tonight's plan": a camera service can't start by itself after a restart. */
    static void notifyRearm(Context c, String why) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.notify(NOTIF_REARM, builder(c)
                .setContentTitle("Tap to re-arm tonight's plan")
                .setContentText(why)
                .setStyle(new Notification.BigTextStyle().bigText(why + " Android doesn't let the camera start by itself "
                        + "after that, so the plan waits for one tap. The phone can stay where it is."))
                .setContentIntent(openApp(c, "plan", true, 35))
                .setCategory(Notification.CATEGORY_REMINDER)
                .build());
    }

    static void notifyEvening(Context c, NightPlan p) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        int n = 0;
        String first = "";
        for (NightPlan.Item it : p.items) {
            if (it.finished()) continue;
            if (n++ == 0) first = it.what() + (it.startMs > 0 ? " at " + Tonight.clock(it.startMs, p.zone) : "");
        }
        if (n == 0) return;
        String text = n == 1 ? first + "." : first + " and " + (n - 1) + " more.";
        nm.notify(NOTIF_EVENING, builder(c)
                .setContentTitle("Tonight's plan isn't armed yet")
                .setContentText(text + " Tap to arm it.")
                .setContentIntent(openApp(c, "plan", false, 36))
                .setCategory(Notification.CATEGORY_REMINDER)
                .build());
    }

    /** One notification for the whole night: every session's picture and how it went. */
    static void notifyMorning(Context c, NightPlan p) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        List<String> lines = p.summaryLines();
        StringBuilder all = new StringBuilder();
        for (String l : lines) all.append(all.length() == 0 ? "" : "\n").append(l);
        Notification.Builder b = builder(c)
                .setContentTitle(p.summaryTitle())
                .setContentText(lines.isEmpty() ? "" : lines.get(0))
                .setContentIntent(openApp(c, "summary", false, 37))
                .setSilent(true)
                .setCategory(Notification.CATEGORY_STATUS);
        Bitmap pic = null;
        try {
            pic = collage(c, p);
        } catch (RuntimeException | OutOfMemoryError e) {
            android.util.Log.w("NightSky", "no collage: " + e);
        }
        if (pic != null) {
            b.setLargeIcon(pic);
            b.setStyle(new Notification.BigPictureStyle().bigPicture(pic).setSummaryText(all.toString()));
        } else {
            b.setStyle(new Notification.BigTextStyle().bigText(all.toString()));
        }
        nm.notify(NOTIF_MORNING, b.build());
    }

    static void cancelNotification(Context c, int id) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(id);
    }

    // ------------------------------------------------------------------------------------------------

    static String readText(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(256, f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    /** Writes the whole file or nothing: a half-written plan would lose the night. */
    static void writeText(File f, String text) {
        File tmp = new File(f.getPath() + ".tmp");
        try {
            FileOutputStream o = new FileOutputStream(tmp);
            try {
                o.write(text.getBytes(StandardCharsets.UTF_8));
                o.getFD().sync();
            } finally {
                o.close();
            }
            if (!tmp.renameTo(f)) throw new IOException("rename failed");
        } catch (IOException e) {
            android.util.Log.e("NightSky", "could not save " + f.getName(), e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
