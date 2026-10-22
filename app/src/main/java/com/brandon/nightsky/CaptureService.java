package com.brandon.nightsky;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps a session alive while the screen is off and the phone lies face-down: a foreground service that
 * owns the {@link SessionController}, holds the CPU awake, turns the screen off while the proximity sensor
 * is covered, and shows progress in a silent notification. It also runs the remote viewer ({@link RemoteHost})
 * and, if asked, a Wi-Fi hotspot for it; while a viewer is watching it stays up after the photo is done, until
 * nobody has used the viewer for {@link RemoteHost#IDLE_STOP_MS} (and at most {@link RemoteHost#MAX_KEEP_MS}).
 * <p>
 * An armed night plan ({@link PlanRunner}) keeps it in the foreground between scheduled sessions, as a camera
 * service only (no time limit), with the camera closed and no wake lock until the next start's alarm.
 */
public final class CaptureService extends Service implements SessionController.Host {
    static final String ACTION_START = "com.brandon.nightsky.START";
    static final String ACTION_FINISH = "com.brandon.nightsky.FINISH";
    static final String ACTION_CANCEL = "com.brandon.nightsky.CANCEL";
    static final String ACTION_RECOVER = "com.brandon.nightsky.RECOVER";
    static final String ACTION_STOP_VIEWER = "com.brandon.nightsky.STOP_VIEWER";
    static final String ACTION_ARM_PLAN = "com.brandon.nightsky.ARM_PLAN";
    static final String ACTION_CANCEL_PLAN = "com.brandon.nightsky.CANCEL_PLAN";
    static final String EXTRA_TRAILS = "trails", EXTRA_DURATION = "durationSec", EXTRA_BRIGHTNESS = "brightness",
            EXTRA_SOUNDS = "sounds", EXTRA_DNG = "dng", EXTRA_DIR = "dir", EXTRA_VIEWER = "viewer", EXTRA_PREFLIGHT = "preflight",
            EXTRA_KEEP_SATELLITES = "keepSatellites", EXTRA_START_AT = "startAt";

    private static final String CHANNEL = "capture";
    private static final int NOTIF_ID = 7;

    final class LocalBinder extends Binder {
        CaptureService service() {
            return CaptureService.this;
        }
    }

    /** The running service, for the plan's alarms (null when there is none). Main thread. */
    static volatile CaptureService instance;

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SessionController controller;
    private NotificationManager nm;
    private PowerManager.WakeLock cpuLock, proximityLock;
    private volatile boolean foreground, foregroundCamera;
    private long lastNotifyMs;
    private SessionController.Phase lastPhase;

    // remote viewer
    private volatile RemoteHost remote;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.LocalOnlyHotspotReservation hotspot;
    private volatile String hotspotSsid, hotspotPass, hotspotError;
    private volatile boolean hotspotSae, hotspotStarting;
    /** Bumped whenever what the arm screen shows about the viewer changes. */
    private volatile int viewerVersion;
    /** The session is over but a viewer is in use: the service stays in the foreground for it. */
    private volatile boolean keepingForViewer;
    private long endedMs;

    // night plan
    private PlanRunner plans;
    /** Why the last "Arm" didn't work, for the screen (null if it did); and when. */
    private volatile String planError;
    private volatile long planErrorMs;

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
        plans = new PlanRunner(this, controller);
        instance = this;
    }

    SessionController controller() {
        return controller;
    }

    PlanRunner plans() {
        return plans;
    }

    /** Why the last "Arm" failed, if it was in the last minute (else null). */
    String planError() {
        String e = planError;
        return e != null && System.currentTimeMillis() - planErrorMs < 60_000L ? e : null;
    }

    /** When it failed (tells two failures with the same words apart). */
    long planErrorMs() {
        return planErrorMs;
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
            s.preflight = intent.getBooleanExtra(EXTRA_PREFLIGHT, true);
            s.keepSatellites = intent.getBooleanExtra(EXTRA_KEEP_SATELLITES, false);
            s.startAtMs = intent.getLongExtra(EXTRA_START_AT, 0L);
            keepingForViewer = false;
            if (!goForeground(true)) {
                if (!plans.armed()) {
                    stopRemote();
                    stopSelf();
                }
                return START_NOT_STICKY;
            }
            acquireLocks(true);
            if (intent.getBooleanExtra(EXTRA_VIEWER, false)) startRemote();
            else if (!plans.armed()) stopRemote();
            controller.start(s);
        } else if (ACTION_RECOVER.equals(a)) {
            String dir = intent.getStringExtra(EXTRA_DIR);
            if (dir == null || !goForeground(false)) {
                if (!plans.armed()) stopSelf();
                return START_NOT_STICKY;
            }
            // developing again: the service works for the session now, not just for the viewer
            keepingForViewer = false;
            acquireLocks(false);
            controller.recover(new File(dir));
        } else if (ACTION_FINISH.equals(a)) {
            controller.finishNow();
        } else if (ACTION_CANCEL.equals(a)) {
            controller.cancel();
        } else if (ACTION_STOP_VIEWER.equals(a)) {
            stopViewer();
        } else if (ACTION_ARM_PLAN.equals(a)) {
            // the app is on screen: the only time a camera service may start, so the plan can open it later
            planError = null;
            if (!goForeground(true, true)) {
                planError = "Android didn't let the camera service start. Open the app and try again.";
                planErrorMs = System.currentTimeMillis();
                if (sessionOver() && !plans.armed()) stopSelf();
                return START_NOT_STICKY;
            }
            keepingForViewer = false;
            String err = plans.arm();
            if (err != null) {
                planError = err;
                planErrorMs = System.currentTimeMillis();
                afterPlan();
            } else if (new Prefs(this).remoteViewer()) {
                // one viewer link for the whole night: pair a laptop now, it follows every session
                startRemote();
            }
        } else if (ACTION_CANCEL_PLAN.equals(a)) {
            plans.cancel("cancelled by you");
            afterPlan();
        }
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------------------------------------
    // Night plan
    // ------------------------------------------------------------------------------------------------

    /** The plan's start alarm (from {@link PlanReceiver}, main thread). */
    void onPlanAlarm() {
        // the alarm's own wake lock ends when the receiver returns: hold the CPU while the start is decided
        acquireCpu();
        plans.onAlarm();
        if (!plans.running() && sessionOver()) {
            releaseLocks();
            afterPlan();
        }
    }

    /** A scheduled session starts (the plan's settings). */
    void startScheduled(SessionController.Settings s) {
        keepingForViewer = false;
        acquireLocks(true);
        if (new Prefs(this).remoteViewer()) startRemote();
        controller.start(s);
    }

    /** The plan waits for its next start: the notification says what and when, with a Cancel button. */
    void showPlanWaiting(String next, String wakeTime) {
        if (!foreground) return;
        Intent cancel = new Intent(this, CaptureService.class).setAction(ACTION_CANCEL_PLAN);
        PendingIntent pc = PendingIntent.getService(this, 3, cancel, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Night plan armed")
                .setContentText("Next: " + next)
                .setStyle(new Notification.BigTextStyle().bigText("Next: " + next + ". The camera opens at " + wakeTime
                        + ". Leave the phone screen-down, camera to the sky."))
                .setContentIntent(Plans.openApp(this, "plan", false, 38))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setColor(0xFFE53935)
                .addAction(new Notification.Action.Builder(null, "Cancel plan", pc).build())
                .build();
        nm.notify(NOTIF_ID, n);
    }

    /** After the plan changed: if it's over and nothing runs, let go as at the end of a session. */
    private void afterPlan() {
        if (plans.armed() || !sessionOver()) return;
        RemoteHost r = remote;
        if (r != null && r.running() && r.watching() && foreground) {
            keepingForViewer = true;
            endedMs = System.currentTimeMillis();
            releaseProximity();
            nm.notify(NOTIF_ID, viewerNotification(r));
            return;
        }
        stopRemote();
        finishIdle();
    }

    // ------------------------------------------------------------------------------------------------
    // Remote viewer
    // ------------------------------------------------------------------------------------------------

    RemoteHost remote() {
        return remote;
    }

    int viewerVersion() {
        return viewerVersion;
    }

    private void startRemote() {
        RemoteHost r = remote;
        if (r == null || !r.running()) {
            r = new RemoteHost(this, controller);
            if (!r.start()) {
                remote = null;
                viewerVersion++;
                return;
            }
            remote = r;
            viewerVersion++;
        }
        controller.setRemoteWanted(true);
        main.removeCallbacks(remoteTick);
        main.postDelayed(remoteTick, 10_000);
    }

    /** "Stop the viewer", from the notification or the result screen. */
    void stopViewer() {
        stopRemote();
        if (keepingForViewer && sessionOver()) finishIdle();
    }

    private void stopRemote() {
        main.removeCallbacks(remoteTick);
        RemoteHost r = remote;
        remote = null;
        if (r != null) {
            r.stop();
            viewerVersion++;
        }
        controller.setRemoteWanted(false);
        releaseWifiLock();
        stopHotspot();
    }

    /** True when no session is running or developing (only then may the service let go). */
    private boolean sessionOver() {
        SessionController.Phase p = controller.status().phase;
        return p == SessionController.Phase.IDLE || p == SessionController.Phase.DONE || p == SessionController.Phase.FAILED;
    }

    /** Every 10 s while the viewer runs: Wi-Fi responsiveness while someone watches, and the idle stop. */
    private final Runnable remoteTick = new Runnable() {
        @Override
        public void run() {
            RemoteHost r = remote;
            if (r == null) return;
            long now = System.currentTimeMillis();
            // between a plan's sessions the phone sleeps: no point keeping Wi-Fi on full power
            boolean planWaiting = plans.armed() && sessionOver();
            if (r.watching() && !planWaiting) acquireWifiLock();
            else releaseWifiLock();
            if (keepingForViewer && sessionOver() && !plans.armed()) {
                // only what a person did counts (an open page polling by itself doesn't), and never past the cap
                long quiet = now - Math.max(r.lastActiveMs(), endedMs);
                if (quiet > RemoteHost.IDLE_STOP_MS || now - endedMs > RemoteHost.MAX_KEEP_MS) {
                    stopRemote();
                    finishIdle();
                    return;
                }
            }
            main.postDelayed(this, 10_000);
        }
    };

    /**
     * Asks Wi-Fi for low latency while a viewer watches. Since Android 14 this only has an effect while the
     * screen is on and the app is in front (for example while arming); with the screen off the viewer still
     * works, at the radio's power-save pace, because the CPU wake lock and the foreground service keep the
     * app running.
     */
    private void acquireWifiLock() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;
        try {
            if (wifiLock == null) {
                //noinspection deprecation
                int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "nightsky:viewer");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (RuntimeException e) {
            android.util.Log.w("NightSky", "wifi lock", e);
        }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (RuntimeException ignored) {
        }
    }

    /** True if "Take another" from the viewer can open the camera (the service never left the foreground). */
    boolean canArmFromViewer() {
        return remote != null && foreground && foregroundCamera;
    }

    /** "Take another" from the viewer: a new session with the saved settings. Called from a viewer thread. */
    String armFromViewer() {
        final String[] msg = {"The phone did not answer"};
        final CountDownLatch done = new CountDownLatch(1);
        // whoever claims this first wins: the command runs, or the viewer is told to try again (and it never runs)
        final AtomicBoolean claimed = new AtomicBoolean();
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!claimed.compareAndSet(false, true)) return;
                try {
                    SessionController.Phase p = controller.status().phase;
                    if (p != SessionController.Phase.IDLE && p != SessionController.Phase.DONE && p != SessionController.Phase.FAILED) {
                        msg[0] = "A session is already running";
                        return;
                    }
                    if (!canArmFromViewer()) {
                        // a camera service can only start while the app is on screen
                        msg[0] = "Open the app on the phone to take another";
                        return;
                    }
                    Prefs prefs = new Prefs(CaptureService.this);
                    SessionController.Settings s = new SessionController.Settings();
                    s.trails = prefs.trails();
                    s.durationSec = prefs.durationMin() * 60;
                    s.brightness = prefs.brightness();
                    s.sounds = prefs.sounds();
                    s.saveDng = prefs.saveDng();
                    s.preflight = prefs.preflight();
                    s.keepSatellites = prefs.keepSatellites();
                    s.fromViewer = true;
                    keepingForViewer = false;
                    acquireLocks(true);
                    controller.setRemoteWanted(true);
                    controller.start(s);
                    msg[0] = "Starting. It begins once the phone lies still, screen-down (or tap Start now).";
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            if (!done.await(3, TimeUnit.SECONDS)) {
                if (claimed.compareAndSet(false, true)) return "The phone is busy, try again";
                done.await(10, TimeUnit.SECONDS); // already running: it is quick
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            claimed.set(true);
        }
        return msg[0];
    }

    /** SSID, passphrase and WPA3-only flag of the viewer hotspot, or null if it is off. */
    String[] hotspotInfo() {
        String s = hotspotSsid, p = hotspotPass;
        if (hotspot == null || s == null) return null;
        return new String[]{s, p, hotspotSae ? "sae" : "wpa"};
    }

    boolean hotspotStarting() {
        return hotspotStarting;
    }

    String hotspotError() {
        return hotspotError;
    }

    /** Starts a Wi-Fi network of the phone's own for the viewer (needs the nearby-devices permission). */
    void startHotspot() {
        if (hotspot != null || hotspotStarting || remote == null) return;
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;
        hotspotStarting = true;
        hotspotError = null;
        viewerVersion++;
        try {
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    hotspotStarting = false;
                    if (remote == null) {
                        r.close();
                        return;
                    }
                    hotspot = r;
                    SoftApConfiguration c = r.getSoftApConfiguration();
                    hotspotSsid = ssidOf(c);
                    hotspotPass = c.getPassphrase();
                    hotspotSae = c.getSecurityType() == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE;
                    viewerVersion++;
                }

                @Override
                public void onStopped() {
                    hotspot = null;
                    hotspotSsid = null;
                    hotspotPass = null;
                    hotspotStarting = false;
                    viewerVersion++;
                }

                @Override
                public void onFailed(int reason) {
                    hotspotStarting = false;
                    hotspotError = reason == ERROR_INCOMPATIBLE_MODE
                            ? "The phone's own hotspot is already on: join that from the other device instead."
                            : reason == ERROR_TETHERING_DISALLOWED ? "Hotspots are not allowed on this phone."
                            : "The phone could not start a hotspot (error " + reason + ").";
                    viewerVersion++;
                }
            }, main);
        } catch (SecurityException | IllegalStateException e) {
            hotspotStarting = false;
            hotspotError = "The phone could not start a hotspot: " + e.getMessage();
            viewerVersion++;
        }
    }

    void stopHotspot() {
        if (hotspot != null) {
            try {
                hotspot.close();
            } catch (RuntimeException ignored) {
            }
            hotspot = null;
            hotspotSsid = null;
            hotspotPass = null;
            viewerVersion++;
        }
    }

    private static String ssidOf(SoftApConfiguration c) {
        String s = null;
        if (Build.VERSION.SDK_INT >= 33 && c.getWifiSsid() != null) {
            s = c.getWifiSsid().toString();
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        }
        //noinspection deprecation
        if (s == null || s.isEmpty()) s = c.getSsid();
        return s;
    }

    private boolean goForeground(boolean camera) {
        // while a plan is armed the service must keep its camera type (and only that: data sync has a daily limit)
        return goForeground(camera || plans.armed(), plans.armed());
    }

    /** {@code planOnly}: a camera service without data sync, which Android lets run all night. */
    private boolean goForeground(boolean camera, boolean planOnly) {
        Notification n = build(planOnly && sessionOver() ? "Night plan" : camera ? "Getting ready" : "Developing", "", -1, camera && !planOnly);
        int type = planOnly ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        if (camera) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        try {
            startForeground(NOTIF_ID, n, type);
            foreground = true;
            foregroundCamera = camera;
            return true;
        } catch (RuntimeException e) {
            android.util.Log.e("NightSky", "startForeground failed", e);
            return false;
        }
    }

    private void acquireCpu() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (cpuLock == null) {
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nightsky:session");
            cpuLock.setReferenceCounted(false);
        }
        cpuLock.acquire(4 * 60 * 60 * 1000L);
    }

    private void acquireLocks(boolean proximity) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        acquireCpu();
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
        if (!foreground || keepingForViewer) return;
        // between a plan's sessions the plan's own notification stays
        boolean ended = s.phase == SessionController.Phase.IDLE || s.phase == SessionController.Phase.DONE
                || s.phase == SessionController.Phase.FAILED;
        if (ended && plans.armed() && !plans.running()) return;
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
                SessionController.Status s = controller.status();
                boolean keep = plans.onIdle(s);
                // a scheduled session's photo goes into the morning summary instead
                if (!s.scheduled && (s.phase == SessionController.Phase.DONE || s.phase == SessionController.Phase.FAILED)) {
                    nm.notify(NOTIF_ID + 1, build(s.headline, s.detail, -1, false));
                }
                if (keep) {
                    // the plan waits for its next start: the camera is closed, the CPU may sleep
                    releaseLocks();
                    RemoteHost rv = remote;
                    boolean watched = rv != null && rv.running() && rv.watching();
                    // the developed picture is large: let it go unless someone is looking at it (app or viewer)
                    if (s.scheduled && !watched && !MainActivity.visible) controller.clearResult();
                    return;
                }
                RemoteHost r = remote;
                if (r != null && r.running() && r.watching() && foreground) {
                    // someone is watching: stay up for the downloads and another session until the viewer is quiet
                    keepingForViewer = true;
                    endedMs = System.currentTimeMillis();
                    releaseProximity();
                    nm.notify(NOTIF_ID, viewerNotification(r));
                    return;
                }
                stopRemote();
                finishIdle();
            }
        });
    }

    /** Nothing runs any more: let go of the locks and the foreground state (not while a plan is armed). */
    private void finishIdle() {
        keepingForViewer = false;
        releaseLocks();
        if (plans.armed()) return;
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
            foregroundCamera = false;
        }
    }

    private Notification viewerNotification(RemoteHost r) {
        List<RemoteHost.Address> as = RemoteHost.addresses(this);
        String where = as.isEmpty() ? "" : "Open " + r.typedFor(as.get(0).ip) + " · ";
        Intent stop = new Intent(this, CaptureService.class).setAction(ACTION_STOP_VIEWER);
        PendingIntent ps = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Viewer still on")
                .setContentText(where + "stops " + RemoteHost.IDLE_STOP_MS / 60_000L + " min after it was last used")
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setColor(0xFFE53935)
                .addAction(new Notification.Action.Builder(null, "Stop viewer", ps).build())
                .build();
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
        stopRemote();
        finishIdle();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        stopRemote();
        releaseLocks();
        controller.shutdown();
        super.onDestroy();
    }
}
