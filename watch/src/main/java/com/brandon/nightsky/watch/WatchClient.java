package com.brandon.nightsky.watch;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.brandon.nightsky.link.Frames;
import com.brandon.nightsky.link.LinkCrypto;
import com.brandon.nightsky.link.LinkKeys;
import com.brandon.nightsky.link.Pairing;
import com.brandon.nightsky.link.Relay;
import com.brandon.nightsky.link.ReplayGuard;
import com.brandon.nightsky.remote.JsonTree;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The watch's end of the link to Night Sky on the phone (2.5), one for the whole app. Two ways at once:
 * <ul>
 * <li>Bluetooth straight to the phone (its own connection beside the watch's usual one): quick, with the live picture;
 * no signal needed at all.</li>
 * <li>The internet, through the relay: wherever the watch has a connection (through the phone, Wi-Fi or LTE). Status
 * and controls.</li>
 * </ul>
 * Commands go the quickest way that's up; the status is taken from whichever brings the newest. Before it's paired, it
 * shows a code and pairs both ways at once ({@link Pairing}).
 */
final class WatchClient {
    /** The phone's Bluetooth service (WatchLink.BT_UUID on the phone). */
    static final UUID BT_UUID = UUID.fromString("8e2f6c1a-9d3b-4f57-a1c4-5b7e0d2c9f31");
    /** Over the relay: hello this often while the screen is on (the phone sends only while it has heard one lately). */
    static final long RELAY_HELLO_MS = 60_000L, RELAY_HELLO_BACKGROUND_MS = 120_000L;
    /** A status older than this is shown as old; much older, the phone is taken as gone quiet. */
    static final long STALE_MS = 25_000L, GONE_MS = 4 * 60_000L;
    /** How long a command waits for the phone's answer before saying it didn't come. */
    static final long REPLY_MS = 12_000L;

    interface Listener {
        /** Something changed (called on the main thread). */
        void changed();
    }

    private static WatchClient one;

    static synchronized WatchClient get(Context c) {
        if (one == null) one = new WatchClient(c.getApplicationContext());
        return one;
    }

    private final Context ctx;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "nightsky-watch-net");
            t.setDaemon(true);
            return t;
        }
    });
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final ReplayGuard guard = new ReplayGuard(15 * 60_000L);

    private volatile LinkKeys keys;
    private volatile boolean active, foreground;
    private volatile Relay relay;
    private Relay.Subscription sub, pairSub;
    private Thread bt;
    private volatile BtConn conn;

    // what the phone said
    volatile JsonTree.Obj status;
    volatile long statusTs, statusAtMs;
    volatile String statusVia = "";
    volatile Bitmap picture;
    volatile int pictureVersion = Integer.MIN_VALUE;
    volatile String toast = "";
    volatile long toastUntil;
    private volatile String waitingFor = "";
    private volatile long waitingSince;
    private volatile long relayHelloMs, lastPublishMs;
    private volatile boolean relayOpen;
    volatile String btState = "", relayState = "";

    // pairing
    volatile Pairing.Watch pairing;
    volatile String check = "";
    volatile long checkUntil;
    private volatile long pairHelloMs, btPairHelloMs, pairDoneMs, pairedAtMs;
    private volatile String pairDone;
    /** For the tests: the relay's address (null: the one the phone gave, else ntfy.sh); Bluetooth off. */
    static volatile String relayOverride;
    static volatile boolean noBluetooth;

    private WatchClient(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("watch", Context.MODE_PRIVATE);
        keys = LinkKeys.fromText(sp.getString("key", ""));
    }

    void listen(Listener l) {
        listeners.addIfAbsent(l);
    }

    void unlisten(Listener l) {
        listeners.remove(l);
    }

    private void changed() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.changed();
            }
        });
    }

    boolean paired() {
        return keys != null;
    }

    String phoneName() {
        return sp.getString("phone", "");
    }

    /** The relay to use: the one the phone named when pairing, else ntfy.sh. */
    private Relay relay() {
        Relay r = relay;
        if (r == null) {
            String base = relayOverride != null ? relayOverride : sp.getString("relay", Relay.DEFAULT);
            r = new Relay(Relay.valid(base) ? base : Relay.DEFAULT, null);
            relay = r;
        }
        return r;
    }

    // ------------------------------------------------------------------------------------------------
    // Running

    /** The app is on screen ({@code foreground}) or following a session in the background: link up. */
    synchronized void begin(boolean foreground) {
        boolean was = active && this.foreground;
        this.foreground = foreground;
        if (active) {
            if (foreground != was) {
                // the phone sends the live picture only while the screen shows it
                final BtConn c = conn;
                if (c != null && c.authed) {
                    net.execute(new Runnable() {
                        @Override
                        public void run() {
                            c.helloSealed();
                        }
                    });
                }
            }
            if (foreground && !was) {
                hello(true);
                main.removeCallbacks(tick);
                main.post(tick);
            }
            return;
        }
        active = true;
        if (keys == null) startPairing();
        else subscribe();
        startBt();
        main.removeCallbacks(tick);
        main.postDelayed(tick, 2500);
    }

    /** Nothing needs the link: everything closed (the phone is told the watch isn't looking any more). */
    synchronized void end() {
        if (!active) return;
        active = false;
        main.removeCallbacks(tick);
        final LinkKeys k = keys;
        final BtConn c = conn;
        if (k != null && (c != null || relayOpen)) {
            final String bye = LinkKeys.message("bye").end().toString();
            net.execute(new Runnable() {
                @Override
                public void run() {
                    if (c != null) c.send(Frames.SEALED, k.seal(LinkKeys.WATCH, bye));
                    else publish(k.watchTopic(), k.sealText(LinkKeys.WATCH, bye));
                }
            });
        }
        if (sub != null) sub.close();
        sub = null;
        if (pairSub != null) pairSub.close();
        pairSub = null;
        relayOpen = false;
        Thread t = bt;
        bt = null;
        if (t != null) t.interrupt();
        if (c != null) {
            // after the goodbye
            net.execute(new Runnable() {
                @Override
                public void run() {
                    c.close();
                }
            });
        }
    }

    boolean active() {
        return active;
    }

    /** A Bluetooth connection to Night Sky on the phone is open. */
    boolean btUp() {
        return conn != null;
    }

    /** The relay's line is open (the watch has the internet). */
    boolean relayUp() {
        return relayOpen;
    }

    /** The 4 digits both ends show once paired, to compare. */
    String checkDigits() {
        LinkKeys k = keys;
        return k != null ? k.check() : "";
    }

    /** The digits shown after pairing have been read. */
    void checkSeen() {
        checkUntil = 0;
        changed();
    }

    /** When the code on screen was made. */
    long codeMadeMs() {
        return sp.getLong("codeMs", 0);
    }

    /** Whether the status is new enough to trust: Bluetooth brings one every 5 s, the internet one a minute or so. */
    boolean fresh() {
        long age = System.currentTimeMillis() - statusAtMs;
        return status != null && age < ("Bluetooth".equals(via()) ? STALE_MS : 150_000L);
    }

    private synchronized void subscribe() {
        LinkKeys k = keys;
        if (k == null) return;
        if (sub != null) sub.close();
        final LinkKeys key = k;
        // the last ten minutes too: the newest status is there even if the phone has nothing new to say
        sub = relay().subscribe(k.phoneTopic(), "10m", new Relay.Listener() {
            @Override
            public void message(String id, String text) {
                try {
                    handle(key.openText(LinkKeys.PHONE, text), "Internet");
                } catch (GeneralSecurityException ignored) {
                    // not from our phone
                }
            }

            @Override
            public void state(boolean open, String why) {
                relayOpen = open;
                relayState = open ? "" : why;
                if (open) hello(false);
                changed();
            }
        });
    }

    /** Every few seconds: hellos over the relay when Bluetooth isn't carrying the status, and the pairing's repeats. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            long now = System.currentTimeMillis();
            Pairing.Watch p = pairing;
            final BtConn bc = conn;
            if (keys == null && p != null && bc != null && now - btPairHelloMs >= 3_000L) {
                // over Bluetooth too, every few seconds: the phone answers once its code is typed
                btPairHelloMs = now;
                net.execute(new Runnable() {
                    @Override
                    public void run() {
                        bc.pairHello();
                    }
                });
            }
            if (keys == null && p != null) {
                if (p.expired(now) || now - codeMadeMs() > Pairing.CODE_LIFE_MS - 60_000L) {
                    startPairing();
                } else if (now - pairHelloMs >= 10_000L) {
                    pairHelloMs = now;
                    final String hello = p.hello(Build.MODEL);
                    final String topic = Pairing.topic(p.code);
                    net.execute(new Runnable() {
                        @Override
                        public void run() {
                            publish(topic, hello);
                        }
                    });
                }
            }
            // after pairing: the proof again until the phone is heard on the link
            String done = pairDone;
            if (done != null && keys != null && statusAtMs < pairedAtMs && now - pairDoneMs >= 5_000L && now - pairedAtMs < 120_000L) {
                sendPairDone();
            }
            hello(false);
            String w = waitingFor;
            if (!w.isEmpty() && now - waitingSince > REPLY_MS) {
                waitingFor = "";
                say("The phone didn't answer. Open Night Sky on the phone, or check its connection.");
            }
            changed();
            main.postDelayed(this, keys == null ? 1000 : 3000);
        }
    };

    /** Says hello over the relay when it's due ({@code now}: whatever the time since the last). */
    private void hello(boolean now) {
        final LinkKeys k = keys;
        if (k == null || !active) return;
        long t = System.currentTimeMillis();
        boolean btFresh = conn != null && conn.authed && t - statusAtMs < 8_000L;
        long every = foreground ? RELAY_HELLO_MS : RELAY_HELLO_BACKGROUND_MS;
        if (btFresh || (!now && t - relayHelloMs < every) || t - relayHelloMs < 4_000L) return;
        relayHelloMs = t;
        final String js = LinkKeys.message("hello").put("watching", true).put("preview", false).put("app", BuildInfo.VERSION).end().toString();
        net.execute(new Runnable() {
            @Override
            public void run() {
                publish(k.watchTopic(), k.sealText(LinkKeys.WATCH, js));
            }
        });
    }

    /** One publish on the relay (on the network thread), never more than one every 2 s. */
    private void publish(String topic, String text) {
        long wait = 2000 - (System.currentTimeMillis() - lastPublishMs);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
        lastPublishMs = System.currentTimeMillis();
        try {
            relay().publish(topic, text);
            if (relayState.startsWith("Can't")) relayState = "";
        } catch (Relay.TooFast e) {
            lastPublishMs = System.currentTimeMillis() + e.waitMs;
        } catch (IOException e) {
            relayState = "Can't reach the internet: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Commands

    /** Sends one of the phone's commands ({@code preset}: for "start" and "preset", else null). */
    void send(String cmd, String preset) {
        final LinkKeys k = keys;
        if (k == null) return;
        com.brandon.nightsky.remote.Json j = LinkKeys.message("cmd").put("cmd", cmd);
        if (preset != null) j.put("preset", preset);
        final String js = j.end().toString();
        waitingFor = cmd;
        waitingSince = System.currentTimeMillis();
        say(cmd.equals("preset") ? "" : "Sending...");
        final BtConn c = conn;
        net.execute(new Runnable() {
            @Override
            public void run() {
                if (c != null && c.authed && c.send(Frames.SEALED, k.seal(LinkKeys.WATCH, js))) return;
                publish(k.watchTopic(), k.sealText(LinkKeys.WATCH, js));
            }
        });
        changed();
    }

    /** Whether a command is on its way (its answer hasn't come). */
    boolean waiting() {
        return !waitingFor.isEmpty();
    }

    void say(String s) {
        toast = s == null ? "" : s;
        toastUntil = System.currentTimeMillis() + 4500;
        changed();
    }

    // ------------------------------------------------------------------------------------------------
    // What the phone says

    /** A message the phone sealed, over {@code via} ("Bluetooth" or "Internet"). */
    private void handle(JsonTree.Obj m, String via) {
        long now = System.currentTimeMillis();
        if (!guard.fresh(m.str("mid", ""), m.lng("ts", 0), now)) return;
        String t = m.str("t", "");
        if ("status".equals(t)) {
            long ts = m.lng("ts", 0);
            if (ts < statusTs) return;
            JsonTree.Obj was = status;
            status = m;
            statusTs = ts;
            statusAtMs = now;
            statusVia = via;
            pairDone = null;
            // where the phone is (to a tenth of a degree), kept for the Sky Pointer
            double lat = m.num("lat", Double.NaN), lon = m.num("lon", Double.NaN);
            if (!Double.isNaN(lat) && !Double.isNaN(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180
                    && ((float) lat != sp.getFloat("lat", 999f) || (float) lon != sp.getFloat("lon", 999f))) {
                sp.edit().putFloat("lat", (float) lat).putFloat("lon", (float) lon).apply();
            }
            String a = was != null ? was.str("state", "") : "", b = m.str("state", "");
            if (!a.equals(b)) {
                Buzz.on(ctx, a, b);
                // a new session: the last one's picture goes
                if (b.equals("idle") || b.equals("arming") || b.equals("focusing") || b.equals("checking") || b.equals("countdown")) {
                    picture = null;
                    pictureVersion = Integer.MIN_VALUE;
                }
            }
            changed();
        } else if ("reply".equals(t)) {
            waitingFor = "";
            say(m.str("message", ""));
        } else if ("unpair".equals(t)) {
            // the phone forgot this watch: a new code, ready to pair again
            forget();
            say("The phone unpaired this watch. Pair again with the new code whenever you like.");
        } else if ("preview".equals(t)) {
            int v = m.integer("v", -1);
            if (v == pictureVersion) return;
            byte[] jpeg = LinkCrypto.unb64(m.str("jpeg", ""));
            if (jpeg == null) return;
            Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (b == null) return;
            picture = b;
            pictureVersion = v;
            changed();
        }
    }

    /** The phone's status, if it's recent enough to show. */
    JsonTree.Obj status() {
        return status;
    }

    /** Where the phone last said it was ({latitude, longitude}, degrees, to a tenth), or null if it never has. */
    double[] place() {
        if (!sp.contains("lat") || !sp.contains("lon")) return null;
        return new double[]{sp.getFloat("lat", 0f), sp.getFloat("lon", 0f)};
    }

    /** Which way the crown faces as the watch is worn (the Sky Pointer's arm): toward the elbow, or the hand (usual). */
    boolean crownToElbow() {
        return sp.getBoolean("crownToElbow", false);
    }

    void crownToElbow(boolean b) {
        sp.edit().putBoolean("crownToElbow", b).apply();
    }

    /** How the phone's status arrives now: "Bluetooth", "Internet", or "" while it doesn't. */
    String via() {
        long now = System.currentTimeMillis();
        if (status == null || now - statusAtMs > GONE_MS) return "";
        BtConn c = conn;
        if (c != null && c.authed && now - statusAtMs < STALE_MS) return "Bluetooth";
        return statusVia;
    }

    // ------------------------------------------------------------------------------------------------
    // Pairing

    /** The code shown until the phone has it (kept a while, so leaving the app and coming back shows the same). */
    private synchronized void startPairing() {
        String code = sp.getString("code", "");
        long made = sp.getLong("codeMs", 0);
        long now = System.currentTimeMillis();
        if (code.isEmpty() || now - made > Pairing.CODE_LIFE_MS - 60_000L) {
            code = Pairing.newCode();
            sp.edit().putString("code", code).putLong("codeMs", now).apply();
        }
        final Pairing.Watch p = new Pairing.Watch(code);
        pairing = p;
        pairHelloMs = 0;
        if (pairSub != null) pairSub.close();
        pairSub = relay().subscribe(Pairing.topic(code), null, new Relay.Listener() {
            @Override
            public void message(String id, String text) {
                try {
                    onPairing(p, JsonTree.object(text), null);
                } catch (IllegalArgumentException ignored) {
                }
            }

            @Override
            public void state(boolean open, String why) {
                relayOpen = open;
                relayState = open ? "" : why;
                changed();
            }
        });
        changed();
    }

    /** A new code (the old one is forgotten). */
    synchronized void newCode() {
        sp.edit().remove("code").remove("codeMs").apply();
        if (keys == null && active) startPairing();
    }

    private void onPairing(Pairing.Watch p, JsonTree.Obj m, BtConn via) {
        if (pairing != p || keys != null) return;
        String done = p.answer(m);
        LinkKeys k = p.keys();
        if (done == null || k == null) return;
        synchronized (this) {
            if (keys != null) return;
            sp.edit().putString("key", k.toText()).putString("phone", p.phoneName).putString("relay", Relay.valid(p.relay) ? p.relay : Relay.DEFAULT)
                    .remove("code").remove("codeMs").apply();
            keys = k;
            relay = null;
            pairing = null;
            pairDone = done;
            pairedAtMs = System.currentTimeMillis();
            check = k.check();
            checkUntil = System.currentTimeMillis() + 15_000L;
            if (pairSub != null) pairSub.close();
            pairSub = null;
            if (via != null) {
                via.authed = true;
                via.send(Frames.PAIRING, LinkCrypto.utf8(done));
            }
        }
        final String topic = Pairing.topic(p.code);
        final String d = done;
        net.execute(new Runnable() {
            @Override
            public void run() {
                publish(topic, d);
            }
        });
        pairDoneMs = System.currentTimeMillis();
        Buzz.paired(ctx);
        subscribe();
        // the Bluetooth connection carries on, sealed now
        BtConn c = conn;
        if (c != null) c.helloSealed();
        changed();
    }

    private void sendPairDone() {
        final String done = pairDone;
        if (done == null) return;
        pairDoneMs = System.currentTimeMillis();
        String code = null;
        // the topic is named from the code, which is forgotten once paired: kept in the proof's pid
        try {
            code = JsonTree.object(done).str("pid", "");
        } catch (IllegalArgumentException ignored) {
        }
        if (code == null || code.isEmpty()) return;
        final String topic = "nsk-pair-" + code;
        BtConn c = conn;
        if (c != null) c.send(Frames.PAIRING, LinkCrypto.utf8(done));
        net.execute(new Runnable() {
            @Override
            public void run() {
                publish(topic, done);
            }
        });
    }

    /** Forgets the phone, telling it first (so it forgets this watch too): a new code to pair again. */
    void unpair() {
        final LinkKeys k = keys;
        final BtConn c = conn;
        if (k != null) {
            final String js = LinkKeys.message("unpair").end().toString();
            net.execute(new Runnable() {
                @Override
                public void run() {
                    boolean sent = c != null && c.authed && c.send(Frames.SEALED, k.seal(LinkKeys.WATCH, js));
                    // the relay too, unless Bluetooth took it: the phone may be listening only there
                    if (!sent) publish(k.watchTopic(), k.sealText(LinkKeys.WATCH, js));
                }
            });
        }
        // after the message (the network thread runs in order)
        net.execute(new Runnable() {
            @Override
            public void run() {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        forget();
                    }
                });
            }
        });
    }

    private synchronized void forget() {
        if (keys == null) return;
        sp.edit().remove("key").remove("phone").remove("relay").apply();
        keys = null;
        status = null;
        statusTs = 0;
        statusAtMs = 0;
        picture = null;
        pictureVersion = Integer.MIN_VALUE;
        pairDone = null;
        if (sub != null) sub.close();
        sub = null;
        relay = null;
        BtConn c = conn;
        if (c != null) c.close();
        if (active) startPairing();
        changed();
    }

    // ------------------------------------------------------------------------------------------------
    // Bluetooth

    static boolean btAllowed(Context c) {
        return Build.VERSION.SDK_INT < 31 || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    /** Starts (or restarts, after the permission was given) the Bluetooth side. */
    synchronized void startBt() {
        if (noBluetooth || !active || bt != null) return;
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                btLoop(Thread.currentThread());
            }
        }, "nightsky-watch-bt");
        t.setDaemon(true);
        bt = t;
        t.start();
    }

    private void btLoop(Thread me) {
        long backoff = 2000;
        while (active && bt == me) {
            if (!btAllowed(ctx)) {
                btState = "Bluetooth isn't allowed for Night Sky";
                changed();
                if (!sleep(10_000)) return;
                continue;
            }
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
            if (a == null || !a.isEnabled()) {
                btState = a == null ? "No Bluetooth" : "Bluetooth is off";
                if (!sleep(10_000)) return;
                continue;
            }
            boolean connected = false;
            for (BluetoothDevice d : phones(a)) {
                if (!active || bt != me) return;
                BluetoothSocket s = null;
                try {
                    s = d.createRfcommSocketToServiceRecord(BT_UUID);
                    s.connect();
                    sp.edit().putString("btPhone", d.getAddress()).apply();
                    btState = "";
                    backoff = 2000;
                    connected = true;
                    BtConn c = new BtConn(s);
                    conn = c;
                    changed();
                    c.run();
                    conn = null;
                    changed();
                    break;
                } catch (IOException | SecurityException e) {
                    btState = "Night Sky on the phone isn't answering over Bluetooth";
                    if (s != null) {
                        try {
                            s.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
            if (!connected) backoff = Math.min(30_000L, backoff * 2);
            if (!sleep(connected ? 1500 : backoff)) return;
        }
    }

    /** The bonded phones, the one that answered last time first. */
    private List<BluetoothDevice> phones(BluetoothAdapter a) {
        List<BluetoothDevice> out = new ArrayList<BluetoothDevice>();
        Set<BluetoothDevice> all;
        try {
            all = a.getBondedDevices();
        } catch (SecurityException e) {
            return out;
        }
        if (all == null) return out;
        String last = sp.getString("btPhone", "");
        for (BluetoothDevice d : all) {
            BluetoothClass c = d.getBluetoothClass();
            boolean phone = c == null || c.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE;
            if (!phone && !d.getAddress().equals(last)) continue;
            if (d.getAddress().equals(last)) out.add(0, d);
            else out.add(d);
        }
        return out;
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /** The Bluetooth connection to the phone. */
    private final class BtConn {
        private final BluetoothSocket s;
        private OutputStream out;
        volatile boolean authed;

        BtConn(BluetoothSocket s) {
            this.s = s;
        }

        void run() {
            try {
                InputStream in = s.getInputStream();
                out = s.getOutputStream();
                if (keys != null) helloSealed();
                else pairHello();
                long lastHello = System.currentTimeMillis();
                while (active) {
                    byte[] f = Frames.read(in);
                    byte[] body = java.util.Arrays.copyOfRange(f, 1, f.length);
                    if (f[0] == Frames.PAIRING) {
                        Pairing.Watch p = pairing;
                        if (p != null) {
                            try {
                                onPairing(p, JsonTree.object(new String(body, LinkCrypto.UTF8)), this);
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    } else if (f[0] == Frames.SEALED) {
                        LinkKeys k = keys;
                        if (k == null) continue;
                        try {
                            JsonTree.Obj m = k.open(LinkKeys.PHONE, body);
                            authed = true;
                            handle(m, "Bluetooth");
                        } catch (GeneralSecurityException ignored) {
                        }
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastHello > 30_000L) {
                        lastHello = now;
                        if (keys != null) helloSealed();
                    }
                }
            } catch (IOException ignored) {
                // the phone went away
            } finally {
                close();
            }
        }

        void helloSealed() {
            LinkKeys k = keys;
            if (k == null) return;
            String js = LinkKeys.message("hello").put("watching", true).put("preview", foreground).put("app", BuildInfo.VERSION).end().toString();
            send(Frames.SEALED, k.seal(LinkKeys.WATCH, js));
        }

        void pairHello() {
            Pairing.Watch p = pairing;
            if (p != null) send(Frames.PAIRING, LinkCrypto.utf8(p.hello(Build.MODEL)));
        }

        boolean send(byte kind, byte[] body) {
            OutputStream o = out;
            if (o == null) return false;
            try {
                Frames.write(o, kind, body);
                return true;
            } catch (IOException e) {
                close();
                return false;
            }
        }

        void close() {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
