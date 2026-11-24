package com.brandon.nightsky;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;

import com.brandon.nightsky.link.Frames;
import com.brandon.nightsky.link.LinkCrypto;
import com.brandon.nightsky.link.LinkKeys;
import com.brandon.nightsky.link.Pairing;
import com.brandon.nightsky.link.Relay;
import com.brandon.nightsky.link.ReplayGuard;
import com.brandon.nightsky.processing.Preset;
import com.brandon.nightsky.remote.Json;
import com.brandon.nightsky.remote.JsonTree;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The Pixel Watch link, the phone's end (2.5). The watch app shows what the session is doing and runs it (start, start
 * now, stop, five more minutes, sounds) over whichever way reaches it:
 * <ul>
 * <li>Bluetooth, straight to the phone (an RFCOMM connection of its own beside the watch's usual one): quick, and the
 * live picture comes with it; it works at a dark site with no signal at all.</li>
 * <li>The internet, through a relay ({@link Relay}): wherever the watch has a connection of any kind (through the
 * phone, Wi-Fi or its own LTE), however far it is from the phone. Status and controls, no pictures.</li>
 * </ul>
 * Both carry the same messages, every one sealed with the key the two agreed when they were paired ({@link Pairing},
 * {@link LinkKeys}), so a stranger can neither read nor send them. It runs while the capture service does: whenever
 * the app is open or a session runs. Over the relay it sends nothing unless a watch is listening (it says hello
 * every minute or so), and never more often than every few seconds.
 */
final class WatchLink {
    /** The Bluetooth service the watch connects to (its own UUID: nothing else answers to it). */
    static final UUID BT_UUID = UUID.fromString("8e2f6c1a-9d3b-4f57-a1c4-5b7e0d2c9f31");
    static final String BT_NAME = "Night Sky watch link";
    /** A watch on the relay counts as watching for this long after its last hello. */
    static final long LEASE_MS = 150_000L;
    /** Messages older than this (or from a clock that far ahead) are refused. */
    static final long FRESH_MS = 5 * 60_000L;
    /**
     * Relay sends: at most one this often, and while capturing, only this often (the relay allows 12 a minute), or
     * less often in a long session, so that its capture takes about {@link #RELAY_CAPTURE_SENDS} (ntfy.sh also counts
     * messages a day: a 90-minute time lapse followed over the internet costs about as many as a 5-minute photo).
     */
    static final long RELAY_MIN_GAP_MS = 4_000L, RELAY_CAPTURE_GAP_MS = 15_000L;
    static final int RELAY_CAPTURE_SENDS = 40;
    /** Over Bluetooth: the status at least this often, a new picture at most this often. */
    static final long BT_STATUS_MS = 5_000L, BT_PREVIEW_MS = 8_000L;
    /** The live picture's longer side, for the watch's screen. */
    static final int PREVIEW_SIDE = 400;

    private final CaptureService service;
    private final SessionController controller;
    private final Prefs prefs;
    private final Context ctx;
    private final ExecutorService work = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "nightsky-watch");
            t.setDaemon(true);
            return t;
        }
    });
    private final ReplayGuard guard = new ReplayGuard(FRESH_MS);
    private volatile boolean running;
    private volatile LinkKeys keys;
    private volatile Relay relay;
    private Relay.Subscription sub;
    private Thread pump;
    private volatile long leaseUntil, lastHeardMs;
    private volatile String lastVia = "";
    private long relaySentMs;
    private String relaySig = "", relayPhase = "";
    private volatile boolean relayAsked;
    private volatile boolean relayBusy;
    private String lastPhase = "";
    private int seq;
    // Bluetooth
    private volatile BluetoothServerSocket btServer;
    private volatile long btTriedMs;
    private volatile String btWhy = "";
    private final CopyOnWriteArrayList<Conn> conns = new CopyOnWriteArrayList<Conn>();
    // the live picture, made once for each new version
    private volatile int previewMadeOf = Integer.MIN_VALUE;
    private volatile byte[] previewJpeg;
    private volatile int previewW, previewH;
    // pairing
    private volatile Pairing.Phone pairing;
    private Relay.Subscription pairSub;
    private volatile String pairText = "";
    private long pairSentMs;
    /** For the tests: where the relay is (null: the one in the settings, else ntfy.sh). */
    static volatile String relayOverride;

    WatchLink(CaptureService service, SessionController controller) {
        this.service = service;
        this.controller = controller;
        this.ctx = service.getApplicationContext();
        this.prefs = new Prefs(ctx);
        keys = LinkKeys.fromText(prefs.watchKey());
    }

    // ------------------------------------------------------------------------------------------------
    // Running

    /** Starts listening for the watch, if one is paired. */
    synchronized void start() {
        running = true;
        if (keys != null) begin();
    }

    synchronized void stop() {
        running = false;
        cancelPairing();
        end();
        work.shutdownNow();
    }

    private synchronized void begin() {
        relay = new Relay(relayOverride != null ? relayOverride : prefs.watchRelay(), null);
        if (sub != null) sub.close();
        final LinkKeys k = keys;
        if (k != null) {
            sub = relay.subscribe(k.watchTopic(), null, new Relay.Listener() {
                @Override
                public void message(String id, String text) {
                    onRelay(k, text);
                }

                @Override
                public void state(boolean open, String why) {
                }
            });
        }
        startPump();
        startBt();
    }

    private synchronized void end() {
        if (sub != null) sub.close();
        sub = null;
        stopBt();
        Thread p = pump;
        pump = null;
        if (p != null) p.interrupt();
    }

    private synchronized void startPump() {
        if (pump != null) return;
        pump = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running && pump == Thread.currentThread()) {
                    try {
                        tick(System.currentTimeMillis());
                    } catch (RuntimeException e) {
                        android.util.Log.w("NightSky", "watch link", e);
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "nightsky-watch-pump");
        pump.setDaemon(true);
        pump.start();
    }

    // ------------------------------------------------------------------------------------------------
    // What the screen shows

    boolean paired() {
        return keys != null;
    }

    /** A watch said something in the last few minutes (it's open, or following a session). */
    boolean watching() {
        return keys != null && System.currentTimeMillis() - lastHeardMs < 3 * 60_000L;
    }

    long lastHeardMs() {
        return lastHeardMs;
    }

    /** One line for the settings: who's paired, when it was last heard and how; or how pairing is going. */
    String line() {
        if (pairing != null || !pairText.isEmpty()) return pairText;
        if (keys == null) return "";
        String name = prefs.watchName().isEmpty() ? "your watch" : prefs.watchName();
        StringBuilder b = new StringBuilder("Paired with " + name + " (check " + keys.check() + ")");
        long ago = System.currentTimeMillis() - lastHeardMs;
        if (lastHeardMs > 0) {
            b.append(ago < 90_000L ? ". Connected over " + lastVia : String.format(Locale.US, ". Last heard %s ago over %s", ago(ago), lastVia));
        }
        if (!btWhy.isEmpty()) b.append(". ").append(btWhy);
        return b.toString();
    }

    private static String ago(long ms) {
        long m = ms / 60_000L;
        return m < 60 ? m + " min" : m / 60 + " h";
    }

    // ------------------------------------------------------------------------------------------------
    // Pairing

    /** Starts pairing with the watch showing {@code typed}; returns why it can't, or null. */
    synchronized String pair(String typed) {
        String code = Pairing.digits(typed);
        if (code == null) return "Type the six digits your watch shows";
        cancelPairing();
        final Pairing.Phone p = new Pairing.Phone(code);
        pairing = p;
        pairText = "Looking for the watch showing " + Pairing.shown(code) + "...";
        if (relay == null) relay = new Relay(relayOverride != null ? relayOverride : prefs.watchRelay(), null);
        // what the watch sent in the last minute too: it says hello every few seconds
        pairSub = relay.subscribe(Pairing.topic(code), "60s", new Relay.Listener() {
            @Override
            public void message(String id, String text) {
                try {
                    onPairing(p, JsonTree.object(text), null);
                } catch (IllegalArgumentException ignored) {
                }
            }

            @Override
            public void state(boolean open, String why) {
                if (!open && pairing == p && pairText.startsWith("Looking")) {
                    pairText = "Looking for the watch showing " + Pairing.shown(p.code) + " (no internet on the phone: " + why
                            + "; Bluetooth still works)...";
                }
            }
        });
        startPump();
        startBt();
        return null;
    }

    synchronized void cancelPairing() {
        if (pairSub != null) pairSub.close();
        pairSub = null;
        pairing = null;
        pairText = "";
    }

    /** Forgets the watch: it has to be paired again. The watch is told, so it shows a new code at once. */
    synchronized void unpair() {
        final LinkKeys k = keys;
        cancelPairing();
        prefs.clearWatch();
        keys = null;
        lastHeardMs = 0;
        if (k == null) {
            end();
            return;
        }
        final String js = LinkKeys.message("unpair").end().toString();
        final List<Conn> open = new ArrayList<Conn>(conns);
        final Relay r = relay;
        try {
            work.execute(new Runnable() {
                @Override
                public void run() {
                    for (Conn c : open) if (c.authed) c.send(Frames.SEALED, k.seal(LinkKeys.PHONE, js));
                    if (r != null) {
                        try {
                            r.publish(k.phoneTopic(), k.sealText(LinkKeys.PHONE, js));
                        } catch (IOException ignored) {
                            // no internet: a watch out of Bluetooth's reach keeps looking until it's unpaired there too
                        }
                    }
                    synchronized (WatchLink.this) {
                        if (keys == null) end();
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            end();
        }
    }

    /** The watch was unpaired on the watch (it said so, sealed with the key): forgotten here too. */
    private synchronized void unpairedByWatch() {
        if (keys == null) return;
        prefs.clearWatch();
        keys = null;
        lastHeardMs = 0;
        pairText = "The watch was unpaired on the watch. Pair again here whenever you like.";
        end();
    }

    /** A pairing message, from the relay or (via) a Bluetooth connection. */
    private void onPairing(Pairing.Phone p, JsonTree.Obj m, Conn via) {
        if (pairing != p) return;
        String t = m.str("t", "");
        if ("pair-hello".equals(t)) {
            final String reply = p.answer(m, Build.MODEL, relay != null ? relay.base() : Relay.DEFAULT);
            if (reply == null) return;
            pairText = "Found " + (p.watchName.isEmpty() ? "the watch" : p.watchName) + ". Finishing...";
            if (via != null) {
                via.send(Frames.PAIRING, LinkCrypto.utf8(reply));
            } else {
                long now = System.currentTimeMillis();
                synchronized (this) {
                    // the watch repeats its hello: the same answer, now and then
                    if (now - pairSentMs < 8_000L) return;
                    pairSentMs = now;
                }
                final String topic = Pairing.topic(p.code);
                work.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            relay.publish(topic, reply);
                        } catch (IOException e) {
                            pairText = "Found the watch, but the relay didn't take the answer: " + e.getMessage();
                        }
                    }
                });
            }
        } else if ("pair-done".equals(t)) {
            LinkKeys k = p.done(m);
            if (k != null) paired(k, p.watchName, via);
        }
    }

    private synchronized void paired(LinkKeys k, String name, Conn via) {
        if (pairSub != null) pairSub.close();
        pairSub = null;
        pairing = null;
        prefs.setWatch(k.toText(), name);
        keys = k;
        lastHeardMs = System.currentTimeMillis();
        lastVia = via != null ? "Bluetooth" : "the internet";
        pairText = "Paired with " + (name.isEmpty() ? "your watch" : name) + ". Check: " + k.check() + " (your watch shows the same)";
        // the watch is looking now: it hears the phone at once
        leaseUntil = System.currentTimeMillis() + LEASE_MS;
        relayAsked = true;
        if (via != null) {
            via.watching = true;
            via.authed = true;
        }
        if (running) begin();
    }

    /** A code was typed and the watch hasn't answered yet. */
    boolean pairingNow() {
        return pairing != null;
    }

    /** Bluetooth was just allowed: the next second tries it again. */
    void retryBluetooth() {
        btTriedMs = 0;
        btWhy = "";
    }

    /** The pairing line has been read: from now on the screen shows the usual one. */
    void pairingSeen() {
        if (pairing == null) pairText = "";
    }

    // ------------------------------------------------------------------------------------------------
    // Messages from the watch

    private void onRelay(LinkKeys k, String text) {
        JsonTree.Obj m;
        try {
            m = k.openText(LinkKeys.WATCH, text);
        } catch (GeneralSecurityException e) {
            return; // not from the watch: never mind
        }
        handle(m, null);
    }

    /** A message the watch sealed (over the relay, or {@code via} Bluetooth). */
    private void handle(final JsonTree.Obj m, final Conn via) {
        long now = System.currentTimeMillis();
        final String mid = m.str("mid", "");
        if (!guard.fresh(mid, m.lng("ts", 0), now)) return;
        lastHeardMs = now;
        lastVia = via != null ? "Bluetooth" : "the internet";
        String t = m.str("t", "");
        if ("hello".equals(t)) {
            if (via != null) {
                via.watching = true;
                via.wantsPreview = m.bool("preview", true);
                via.statusMs = 0;
            } else {
                leaseUntil = now + LEASE_MS;
                relayAsked = true;
            }
        } else if ("bye".equals(t)) {
            if (via != null) via.watching = false;
            else leaseUntil = 0;
        } else if ("unpair".equals(t)) {
            unpairedByWatch();
        } else if ("cmd".equals(t)) {
            if (via == null) {
                leaseUntil = now + LEASE_MS;
                relayAsked = true;
            }
            work.execute(new Runnable() {
                @Override
                public void run() {
                    String msg;
                    try {
                        msg = command(m.str("cmd", ""), m);
                    } catch (RuntimeException e) {
                        msg = "The phone couldn't do that: " + e.getMessage();
                    }
                    reply(mid, msg, via);
                    // what the command changed (5 more minutes, muted) goes out next, not after the usual wait
                    if (via == null) relayAsked = true;
                }
            });
        }
    }

    /** Runs one of the watch's commands (on the link's own thread); what to tell the watch. */
    private String command(String cmd, JsonTree.Obj m) {
        switch (cmd) {
            case "start":
                return service.startFromWatch(m.str("preset", ""));
            case "start-now":
                return controller.remoteStartNow("the watch");
            case "stop":
                return controller.remoteStop("the watch");
            case "cancel":
                return controller.remoteCancel();
            case "add-time": {
                SessionController.Phase p = controller.status().phase;
                if (p == SessionController.Phase.CAPTURING || p == SessionController.Phase.PAUSED) return controller.remoteAddTime(5 * 60, "the watch");
                return service.addAfterFromWatch();
            }
            case "mute":
                return controller.remoteMute(true);
            case "unmute":
                return controller.remoteMute(false);
            case "preset":
                return service.presetFromWatch(m.str("preset", ""));
            default:
                return "The phone doesn't know \"" + cmd + "\": update Night Sky on the phone";
        }
    }

    private void reply(String mid, String message, Conn via) {
        LinkKeys k = keys;
        if (k == null) return;
        String js = LinkKeys.message("reply").put("re", mid).put("message", message == null ? "" : message).end().toString();
        if (via != null) {
            via.send(Frames.SEALED, k.seal(LinkKeys.PHONE, js));
            via.statusMs = 0;
        } else {
            publish(k, js, false);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // What the phone tells the watch

    /** Once a second: the status to whoever is watching, the live picture over Bluetooth, and pairing's time limit. */
    private void tick(long now) {
        Pairing.Phone p = pairing;
        if (p != null && p.expired(now)) {
            cancelPairing();
            pairText = "No watch answered. Check the code on the watch (it changes every 15 minutes) and pair again.";
        }
        if (btServer == null && now - btTriedMs > 30_000L) startBt();
        LinkKeys k = keys;
        if (k == null) return;
        SessionController.Status s = controller.status();
        String[] st = status(s);
        String js = st[0], sig = st[1], phase = s.phase.name();
        boolean ended = !phase.equals(lastPhase) && (s.phase == SessionController.Phase.DONE || s.phase == SessionController.Phase.FAILED)
                && !lastPhase.isEmpty();
        lastPhase = phase;
        // Bluetooth: cheap, so often
        if (!conns.isEmpty()) {
            byte[] pic = null;
            int pv = s.previewVersion;
            for (Conn c : conns) {
                if (!c.authed || !c.watching) continue;
                if (!sig.equals(c.sig) || now - c.statusMs >= BT_STATUS_MS) {
                    c.sig = sig;
                    c.statusMs = now;
                    c.send(Frames.SEALED, k.seal(LinkKeys.PHONE, js));
                }
                if (c.wantsPreview && pv != c.previewVersion && now - c.previewMs >= BT_PREVIEW_MS) {
                    if (pic == null) pic = previewMessage(k, pv);
                    if (pic != null) {
                        c.previewVersion = pv;
                        c.previewMs = now;
                        c.send(Frames.SEALED, pic);
                    }
                }
            }
        }
        // the relay: only while a watch listens there, and sparingly; the end of a session always (a watch may be
        // following it in the background)
        boolean listening = now < leaseUntil;
        boolean due;
        synchronized (this) {
            boolean capturing = s.phase == SessionController.Phase.CAPTURING || s.phase == SessionController.Phase.PAUSED;
            boolean phaseChanged = !phase.equals(relayPhase);
            long gap = now - relaySentMs;
            due = !relayBusy && gap >= RELAY_MIN_GAP_MS && ((listening && (relayAsked || (!sig.equals(relaySig)
                    && (phaseChanged || !capturing || gap >= captureGap(s))))) || (ended && now - lastHeardMs < 3 * 3600_000L));
            if (due) {
                relayAsked = false;
                relaySig = sig;
                relayPhase = phase;
            }
        }
        if (due) publish(k, js, true);
    }

    /** How often the status goes over the relay while capturing: every 15 s, or so that the capture takes about 40. */
    static long captureGap(SessionController.Status s) {
        return Math.max(RELAY_CAPTURE_GAP_MS, (s.elapsedMs + s.remainingMs) / RELAY_CAPTURE_SENDS);
    }

    /** Sends {@code js} over the relay (on the link's thread; one at a time). */
    private void publish(final LinkKeys k, final String js, final boolean status) {
        final Relay r = relay;
        if (r == null) return;
        synchronized (this) {
            relayBusy = true;
            relaySentMs = System.currentTimeMillis();
        }
        try {
            work.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        r.publish(k.phoneTopic(), k.sealText(LinkKeys.PHONE, js));
                    } catch (Relay.TooFast e) {
                        synchronized (WatchLink.this) {
                            relaySentMs = System.currentTimeMillis() + e.waitMs;
                        }
                    } catch (IOException e) {
                        // the next change tries again
                        synchronized (WatchLink.this) {
                            if (status) relaySig = "";
                        }
                    } finally {
                        synchronized (WatchLink.this) {
                            relayBusy = false;
                        }
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            relayBusy = false;
        }
    }

    /** {the status message, what of it counts as a change}. */
    private String[] status(SessionController.Status s) {
        Json j = LinkKeys.message("status");
        String state = RemoteHost.stateOf(s.phase);
        boolean over = s.phase == SessionController.Phase.IDLE || s.phase == SessionController.Phase.DONE || s.phase == SessionController.Phase.FAILED;
        boolean capturing = s.phase == SessionController.Phase.CAPTURING || s.phase == SessionController.Phase.PAUSED;
        j.put("seq", ++seq).put("state", state).put("phase", s.phase.name());
        String headline = cap(s.headline, 120), detail = cap(s.detail, 200);
        if (s.phase == SessionController.Phase.IDLE) {
            headline = "Ready";
            detail = "";
        }
        j.put("headline", headline).put("detail", detail);
        j.put("progress", (double) s.progress, 3);
        int frame = Math.min(s.planned, s.done + (s.phase == SessionController.Phase.CAPTURING ? 1 : 0));
        j.put("frame", frame).put("frames", s.planned);
        j.put("elapsedMs", s.elapsedMs).put("remainingMs", s.remainingMs);
        j.put("muted", s.muted);
        Preset now = prefs.currentPreset();
        String setup = (now.trails ? "Star trails" : now.earth ? "Earth's turn" : now.timelapse ? "Time lapse" : "Deep sky") + " · "
                + now.durationMin + " min";
        j.put("setup", setup).put("preset", prefs.preset());
        j.arr("presets");
        List<Preset> all = Presets.all(prefs);
        for (int i = 0; i < all.size() && i < 10; i++) j.arr().val(all.get(i).id).val(cap(all.get(i).name, 24)).end();
        j.end();
        boolean canStart = over && service.canStartFromWatch();
        j.put("canStart", canStart);
        if (over && !canStart) j.put("why", "Open Night Sky on the phone to start from the watch");
        j.put("canAdd", capturing || (s.phase == SessionController.Phase.DONE && controller.canAddAfter() && service.cameraForeground()));
        BatteryInfo b = BatteryInfo.read(ctx);
        if (b.pct >= 0) j.put("battery", b.pct);
        j.put("charging", b.charging);
        boolean pic = controller.preview() != null;
        j.put("pv", pic ? s.previewVersion : -1);
        SessionController.Result r = s.result;
        if (r != null && s.phase == SessionController.Phase.DONE) {
            if (!r.xrayWords.isEmpty()) j.put("xray", r.xrayWords);
            if (!r.lapseWords.isEmpty()) j.put("lapse", r.lapseWords);
            if (r.cosmicHits > 0) j.put("cosmic", r.cosmicHits);
        }
        // where the phone is, to a tenth of a degree (2.6: the watch's Sky Pointer works out this place's sky)
        Locator.Place place = prefs.savedPlace();
        if (place != null && !Double.isNaN(place.latDeg) && !Double.isNaN(place.lonDeg)) {
            j.put("lat", Math.round(place.latDeg * 10) / 10.0, 6).put("lon", Math.round(place.lonDeg * 10) / 10.0, 6);
        }
        j.put("app", BuildInfo.VERSION);
        j.end();
        String sig = state + "|" + s.phase + "|" + headline + "|" + detail + "|" + frame + "/" + s.planned + "|" + s.muted + "|" + canStart + "|"
                + prefs.preset() + "|" + setup + "|" + (pic ? s.previewVersion : -1) + "|" + Math.round(s.progress * 50);
        return new String[]{j.toString(), sig};
    }

    private static String cap(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    /** The live picture (or the photo once it's done) as a sealed message, made once for each new version. */
    private byte[] previewMessage(LinkKeys k, int version) {
        byte[] jpeg = previewJpeg;
        if (jpeg == null || previewMadeOf != version) {
            Bitmap b = controller.preview();
            if (b == null) return null;
            try {
                int w = b.getWidth(), h = b.getHeight();
                float f = Math.min(1f, PREVIEW_SIDE / (float) Math.max(w, h));
                int sw = Math.max(1, Math.round(w * f)), sh = Math.max(1, Math.round(h * f));
                Bitmap s = f < 1f ? Bitmap.createScaledBitmap(b, sw, sh, true) : b;
                ByteArrayOutputStream bo = new ByteArrayOutputStream(32 * 1024);
                s.compress(Bitmap.CompressFormat.JPEG, 80, bo);
                if (s != b) s.recycle();
                jpeg = bo.toByteArray();
                previewJpeg = jpeg;
                previewMadeOf = version;
                previewW = sw;
                previewH = sh;
            } catch (RuntimeException | OutOfMemoryError e) {
                // a recycled bitmap while developing: the next one
                return null;
            }
        }
        String js = LinkKeys.message("preview").put("v", version).put("w", previewW).put("h", previewH)
                .put("jpeg", LinkCrypto.b64(jpeg)).end().toString();
        return k.seal(LinkKeys.PHONE, js);
    }

    // ------------------------------------------------------------------------------------------------
    // Bluetooth

    /** Whether the app may use Bluetooth (Android 12 and later ask for it). */
    static boolean btAllowed(Context c) {
        return Build.VERSION.SDK_INT < 31 || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private synchronized void startBt() {
        btTriedMs = System.currentTimeMillis();
        if (btServer != null || !running || (keys == null && pairing == null)) return;
        if (!btAllowed(ctx)) {
            btWhy = "Bluetooth isn't allowed for Night Sky, so only the internet links the watch";
            return;
        }
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
        if (a == null || !a.isEnabled()) {
            btWhy = a == null ? "" : "Bluetooth is off, so only the internet links the watch";
            return;
        }
        try {
            final BluetoothServerSocket ss = a.listenUsingRfcommWithServiceRecord(BT_NAME, BT_UUID);
            btServer = ss;
            btWhy = "";
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (btServer == ss) {
                        try {
                            BluetoothSocket s = ss.accept();
                            if (btServer != ss) {
                                s.close();
                                break;
                            }
                            Conn c = new Conn(s);
                            conns.add(c);
                            c.start();
                        } catch (IOException e) {
                            break;
                        }
                    }
                    synchronized (WatchLink.this) {
                        if (btServer == ss) btServer = null;
                    }
                }
            }, "nightsky-watch-bt");
            t.setDaemon(true);
            t.start();
        } catch (IOException | SecurityException e) {
            btWhy = "Bluetooth didn't open (" + e.getMessage() + "): the internet links the watch";
        }
    }

    private synchronized void stopBt() {
        BluetoothServerSocket ss = btServer;
        btServer = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
        for (Conn c : conns) c.close();
        conns.clear();
    }

    /** One watch connected over Bluetooth. */
    private final class Conn {
        private final BluetoothSocket socket;
        private OutputStream out;
        volatile boolean authed, watching, wantsPreview = true;
        volatile long statusMs, previewMs;
        volatile int previewVersion = Integer.MIN_VALUE;
        volatile String sig = "";
        private final long openedMs = System.currentTimeMillis();

        Conn(BluetoothSocket socket) {
            this.socket = socket;
        }

        void start() {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    read();
                }
            }, "nightsky-watch-conn");
            t.setDaemon(true);
            t.start();
        }

        private void read() {
            int bad = 0;
            try {
                InputStream in = socket.getInputStream();
                out = socket.getOutputStream();
                while (running) {
                    byte[] f = Frames.read(in);
                    byte[] body = java.util.Arrays.copyOfRange(f, 1, f.length);
                    if (f[0] == Frames.PAIRING) {
                        Pairing.Phone p = pairing;
                        if (p == null) continue;
                        try {
                            onPairing(p, JsonTree.object(new String(body, LinkCrypto.UTF8)), this);
                        } catch (IllegalArgumentException ignored) {
                        }
                    } else if (f[0] == Frames.SEALED) {
                        LinkKeys k = keys;
                        JsonTree.Obj m = null;
                        if (k != null) {
                            try {
                                m = k.open(LinkKeys.WATCH, body);
                            } catch (GeneralSecurityException ignored) {
                            }
                        }
                        if (m == null) {
                            // not our watch (or an old pairing): a few tries, then the door
                            if (++bad >= 3) break;
                            continue;
                        }
                        authed = true;
                        handle(m, this);
                    }
                    if (!authed && pairing == null && System.currentTimeMillis() - openedMs > 20_000L) break;
                }
            } catch (IOException ignored) {
                // the watch went away
            } finally {
                close();
            }
        }

        void send(byte kind, byte[] body) {
            OutputStream o = out;
            if (o == null) return;
            try {
                Frames.write(o, kind, body);
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            conns.remove(this);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
