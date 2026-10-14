package com.brandon.nightsky;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;

/**
 * Keeps a session alive while the screen is off and the phone lies face-down: a foreground service that
 * owns the {@link SessionController}, holds the CPU awake, turns the screen off while the proximity sensor
 * is covered, and shows progress in a silent notification.
 */
public final class CaptureService extends Service implements SessionController.Host {
    static final String ACTION_START = "com.brandon.nightsky.START";
    static final String ACTION_FINISH = "com.brandon.nightsky.FINISH";
    static final String ACTION_CANCEL = "com.brandon.nightsky.CANCEL";
    static final String ACTION_RECOVER = "com.brandon.nightsky.RECOVER";
    static final String EXTRA_TRAILS = "trails", EXTRA_DURATION = "durationSec", EXTRA_BRIGHTNESS = "brightness",
            EXTRA_SOUNDS = "sounds", EXTRA_DNG = "dng", EXTRA_DIR = "dir";

    private static final String CHANNEL = "capture";
    private static final int NOTIF_ID = 7;

    final class LocalBinder extends Binder {
        CaptureService service() {
            return CaptureService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SessionController controller;
    private NotificationManager nm;
    private PowerManager.WakeLock cpuLock, proximityLock;
    private boolean foreground;
    private long lastNotifyMs;
    private SessionController.Phase lastPhase;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_desc));
        ch.enableVibration(false);
        ch.setSound(null, null);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        controller = new SessionController(this, new Prefs(this), this);
    }

    SessionController controller() {
        return controller;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(a)) {
            SessionController.Settings s = new SessionController.Settings();
            s.trails = intent.getBooleanExtra(EXTRA_TRAILS, false);
            s.durationSec = intent.getIntExtra(EXTRA_DURATION, 300);
            s.brightness = intent.getFloatExtra(EXTRA_BRIGHTNESS, 0.5f);
            s.sounds = intent.getBooleanExtra(EXTRA_SOUNDS, true);
            s.saveDng = intent.getBooleanExtra(EXTRA_DNG, true);
            if (!goForeground(true)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            acquireLocks(true);
            controller.start(s);
        } else if (ACTION_RECOVER.equals(a)) {
            String dir = intent.getStringExtra(EXTRA_DIR);
            if (dir == null || !goForeground(false)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            acquireLocks(false);
            controller.recover(new File(dir));
        } else if (ACTION_FINISH.equals(a)) {
            controller.finishNow();
        } else if (ACTION_CANCEL.equals(a)) {
            controller.cancel();
        }
        return START_NOT_STICKY;
    }

    private boolean goForeground(boolean camera) {
        Notification n = build(camera ? "Getting ready" : "Developing", "", -1, camera);
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        if (camera) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        try {
            startForeground(NOTIF_ID, n, type);
            foreground = true;
            return true;
        } catch (RuntimeException e) {
            android.util.Log.e("NightSky", "startForeground failed", e);
            return false;
        }
    }

    private void acquireLocks(boolean proximity) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (cpuLock == null) {
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nightsky:session");
            cpuLock.setReferenceCounted(false);
        }
        cpuLock.acquire(4 * 60 * 60 * 1000L);
        if (proximity && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            if (proximityLock == null) {
                proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "nightsky:screen");
                proximityLock.setReferenceCounted(false);
            }
            proximityLock.acquire(4 * 60 * 60 * 1000L);
        }
    }

    /** True while the screen is switched off by the proximity sensor during a session. */
    boolean proximityScreenOff() {
        return proximityLock != null && proximityLock.isHeld();
    }

    private void releaseProximity() {
        if (proximityLock != null && proximityLock.isHeld()) {
            // keep the screen dark until the phone is lifted
            proximityLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
        }
    }

    private void releaseLocks() {
        releaseProximity();
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
    }

    // ---- SessionController.Host (controller thread) ----

    @Override
    public void onStatus(final SessionController.Status s) {
        long now = SystemClock.elapsedRealtime();
        if (s.phase == lastPhase && now - lastNotifyMs < 2000) return;
        lastPhase = s.phase;
        lastNotifyMs = now;
        if (!foreground) return;
        boolean capturing = s.phase.ordinal() <= SessionController.Phase.PAUSED.ordinal();
        nm.notify(NOTIF_ID, build(s.headline, s.detail, s.progress, capturing));
    }

    @Override
    public void onCaptureEnded() {
        main.post(new Runnable() {
            @Override
            public void run() {
                releaseProximity();
            }
        });
    }

    @Override
    public void onIdle() {
        main.post(new Runnable() {
            @Override
            public void run() {
                releaseLocks();
                if (foreground) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    foreground = false;
                }
                SessionController.Status s = controller.status();
                if (s.phase == SessionController.Phase.DONE || s.phase == SessionController.Phase.FAILED) {
                    nm.notify(NOTIF_ID + 1, build(s.headline, s.detail, -1, false));
                }
            }
        });
    }

    private Notification build(String title, String text, float progress, boolean capturing) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title == null || title.isEmpty() ? getString(R.string.app_name) : title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(foreground && progress != -2)
                .setColor(0xFFE53935);
        if (progress >= 0) b.setProgress(1000, Math.round(progress * 1000), false);
        if (capturing) {
            Intent stop = new Intent(this, CaptureService.class).setAction(ACTION_FINISH);
            PendingIntent ps = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, "Stop and develop", ps).build());
        }
        return b.build();
    }

    /**
     * Android 15+ limits data-sync services to 6 hours a day: stop cleanly rather than be stopped. Frames already
     * saved stay on disk, and the start screen offers to develop them.
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        releaseLocks();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        controller.shutdown();
        super.onDestroy();
    }
}
