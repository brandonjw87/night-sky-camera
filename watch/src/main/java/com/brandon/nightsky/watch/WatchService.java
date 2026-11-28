package com.brandon.nightsky.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.brandon.nightsky.remote.JsonTree;

/**
 * Follows the phone's session with the watch's screen off (2.5): the link stays up, an ongoing notification shows
 * where it has got, and when the photo is ready (or something went wrong) the watch buzzes and says so. It stops once
 * the session is over, the phone has been quiet for 10 minutes, or after 3 hours whatever happens.
 * <p>
 * 3.0: and a space station's crossing the Moonshot waits for, counted down and buzzed to the second ({@link MoonFace}).
 */
public final class WatchService extends Service implements WatchClient.Listener {
    static volatile boolean running;
    private static final String CH_SESSION = "session", CH_DONE = "done";
    private static final int ID_ONGOING = 7101, ID_DONE = 7102;
    private static final long MAX_MS = 3 * 3600_000L, QUIET_MS = 10 * 60_000L;

    private final Handler h = new Handler(Looper.getMainLooper());
    private WatchClient client;
    private PowerManager.WakeLock wake;
    private long startedMs, notifiedMs;
    private String notified = "";
    private boolean ending;

    /** Starts following (from the screen, while it's on: a service like this can't start from the background). */
    static void start(Context c) {
        if (running) return;
        try {
            c.startForegroundService(new Intent(c, WatchService.class));
        } catch (RuntimeException e) {
            // not allowed now: the session is followed only while the screen is on
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        startedMs = System.currentTimeMillis();
        client = WatchClient.get(this);
        channels();
        Notification n = ongoing("Night Sky", "Following the phone", -1f);
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(ID_ONGOING, n);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            // the watch sleeps hard with the screen off: awake enough to hear the phone and buzz on time
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nightsky:watch");
            wake.setReferenceCounted(false);
            wake.acquire(MAX_MS);
        }
        client.listen(this);
        client.begin(WatchActivity.visible);
        h.postDelayed(check, 15_000L);
        h.post(beat);
    }

    /** The crossing's countdown: its buzzes, every second (a quarter of one near the end) while one is due. */
    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            JsonTree.Obj s = client.status();
            JsonTree.Obj moon = s != null ? s.obj("moon") : null;
            // the screen buzzes while it shows
            if (!WatchActivity.visible) MoonFace.tick(WatchService.this, moon, now);
            long at = MoonFace.target(moon, now);
            h.postDelayed(this, at > 0 && at - now < 15_000L && at - now > -2000 ? 250 : 1000);
        }
    };

    /** Whether the phone's Moonshot waits for a crossing still to come. */
    static boolean moonArmed(JsonTree.Obj s) {
        JsonTree.Obj moon = s != null ? s.obj("moon") : null;
        return moon != null && moon.lng("armedAt", 0) > System.currentTimeMillis() - 5000;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        update();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacks(check);
        h.removeCallbacks(beat);
        client.unlisten(this);
        if (wake != null && wake.isHeld()) wake.release();
        // the screen keeps the link if it's showing; else nothing needs it
        if (!WatchActivity.visible) client.end();
        super.onDestroy();
    }

    @Override
    public void changed() {
        update();
    }

    /** Every 15 s: whether it's still worth following. */
    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (now - startedMs > MAX_MS) {
                finish(null);
                return;
            }
            if (client.statusAtMs > 0 && now - Math.max(client.statusAtMs, startedMs) > QUIET_MS) {
                finish(new String[]{"Lost touch with the phone", "Open Night Sky on the watch to look again"});
                return;
            }
            update();
            h.postDelayed(this, 15_000L);
        }
    };

    private void update() {
        if (ending) return;
        JsonTree.Obj s = client.status();
        String state = s != null ? s.str("state", "") : "";
        if (!client.paired()) {
            finish(null);
            return;
        }
        if (s != null && client.statusTs > 0 && !WatchActivity.active(state) && !moonArmed(s) && client.statusAtMs >= startedMs - 60_000L) {
            // over: the photo is ready, or it failed (or the phone went back to ready)
            String[] last = null;
            if ("done".equals(state)) {
                String x = s.str("xray", "");
                last = new String[]{"Your photo is ready", !x.isEmpty() ? "X-ray: " + x : s.str("headline", "In Photos on the phone")};
            } else if ("failed".equals(state)) {
                last = new String[]{"That didn't work", s.str("headline", "Look at the phone")};
            }
            finish(last);
            return;
        }
        if (s == null) return;
        String title, text;
        float progress;
        if ("capturing".equals(state) || "paused".equals(state)) {
            long left = s.lng("remainingMs", 0) - ("paused".equals(state) ? 0 : System.currentTimeMillis() - client.statusAtMs);
            title = ("paused".equals(state) ? "Paused · " : "Capturing · ") + WatchActivity.clock(left) + " left";
            int frame = s.integer("frame", 0), frames = s.integer("frames", 0);
            text = frames > 0 ? "Frame " + frame + " of " + frames : s.str("setup", "");
            progress = (float) s.num("progress", 0);
        } else if (moonArmed(s)) {
            JsonTree.Obj moon = s.obj("moon");
            long left = moon.lng("armedAt", 0) - System.currentTimeMillis();
            title = MoonFace.capital(moon.str("armedName", "the ISS")) + " crosses the Moon in " + WatchActivity.clock(left);
            text = "The phone shoots it by itself: keep it on the Moon";
            progress = -1f;
        } else if ("developing".equals(state)) {
            progress = -1f;
            title = s.str("headline", "Developing");
            text = s.str("detail", "") + (s.str("detail", "").isEmpty() ? "" : ". ") + "Buzzes when it's ready";
        } else {
            title = s.str("headline", "Getting ready");
            text = s.str("detail", "");
            progress = -1f;
        }
        long now = System.currentTimeMillis();
        String key = title + "|" + text;
        // a notification at most every few seconds: the watch has better things to do
        if (key.equals(notified) || now - notifiedMs < 4_000L) return;
        notified = key;
        notifiedMs = now;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID_ONGOING, ongoing(title, text, progress));
    }

    /** Stops following; {@code last}: {title, text} to leave as a notification (when the screen isn't showing it). */
    private void finish(String[] last) {
        if (ending) return;
        ending = true;
        if (last != null && !WatchActivity.visible) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                Notification n = new Notification.Builder(this, CH_DONE)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(last[0])
                        .setContentText(last[1])
                        .setStyle(new Notification.BigTextStyle().bigText(last[1]))
                        .setAutoCancel(true)
                        .setCategory(Notification.CATEGORY_STATUS)
                        .setContentIntent(open())
                        .build();
                nm.notify(ID_DONE, n);
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification ongoing(String title, String text, float progress) {
        Notification.Builder b = new Notification.Builder(this, CH_SESSION)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(open());
        if (progress >= 0) b.setProgress(1000, Math.round(Math.max(0, Math.min(1, progress)) * 1000), false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private PendingIntent open() {
        Intent i = new Intent(this, WatchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void channels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel a = new NotificationChannel(CH_SESSION, "Session on the phone", NotificationManager.IMPORTANCE_LOW);
        a.setDescription("Where the phone's photo has got, while the watch follows it");
        a.setShowBadge(false);
        nm.createNotificationChannel(a);
        NotificationChannel b = new NotificationChannel(CH_DONE, "Photo ready", NotificationManager.IMPORTANCE_HIGH);
        b.setDescription("When the phone's photo is ready, or something went wrong");
        // the watch buzzes its own pattern for these already
        b.enableVibration(false);
        b.setSound(null, null);
        nm.createNotificationChannel(b);
    }
}
