package com.brandon.nightsky.watch;

import android.app.Activity;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.graphics.Bitmap;

import com.brandon.nightsky.link.Pairing;
import com.brandon.nightsky.remote.JsonTree;

import java.lang.reflect.Field;

/** The watch app as the tests see it (2.5): its client's state, the screen it shows and draws, without its insides being public. */
public final class WatchProbe {
    private WatchProbe() {}

    /** The relay to use (the tests' own), Bluetooth on or off, and no buzzing (only counted). */
    public static void setup(String relay, boolean bluetooth) {
        WatchClient.relayOverride = relay;
        WatchClient.noBluetooth = !bluetooth;
        Buzz.off = true;
    }

    public static void bluetooth(Context c, boolean on) {
        WatchClient.noBluetooth = !on;
        if (on) WatchClient.get(c).startBt();
    }

    private static WatchClient client(Context c) {
        return WatchClient.get(c);
    }

    public static boolean paired(Context c) {
        return client(c).paired();
    }

    /** The code on the screen ("" while there's none). */
    public static String code(Context c) {
        Pairing.Watch p = client(c).pairing;
        return p != null && !client(c).paired() ? p.code : "";
    }

    public static String check(Context c) {
        return client(c).checkDigits();
    }

    /** The phone's state as the watch last heard it ("" for none). */
    public static String state(Context c) {
        JsonTree.Obj s = client(c).status();
        return s != null ? s.str("state", "") : "";
    }

    public static String statusField(Context c, String k) {
        JsonTree.Obj s = client(c).status();
        return s != null ? s.str(k, "") : "";
    }

    public static boolean statusBool(Context c, String k) {
        JsonTree.Obj s = client(c).status();
        return s != null && s.bool(k, false);
    }

    public static String via(Context c) {
        return client(c).via();
    }

    public static boolean fresh(Context c) {
        return client(c).fresh();
    }

    public static String toast(Context c) {
        WatchClient w = client(c);
        return System.currentTimeMillis() < w.toastUntil ? w.toast : "";
    }

    public static boolean picture(Context c) {
        return client(c).picture != null;
    }

    public static boolean btUp(Context c) {
        return client(c).btUp();
    }

    public static boolean relayUp(Context c) {
        return client(c).relayUp();
    }

    public static boolean active(Context c) {
        return client(c).active();
    }

    public static void end(Context c) {
        client(c).end();
    }

    /** The Bluetooth socket the watch has open to the phone, or null. */
    public static BluetoothSocket socket(Context c) throws Exception {
        Object conn = get(client(c), "conn");
        return conn == null ? null : (BluetoothSocket) get(conn, "s");
    }

    /** How many times it buzzed, and the last. */
    public static int buzzes() {
        return Buzz.count;
    }

    public static String lastBuzz() {
        return Buzz.last;
    }

    /** What the screen shows, in words: "title | big | line1 | line2 | [buttons] | toast | top". */
    public static String face(Activity a) {
        DialView.Face f = ((WatchActivity) a).face(System.currentTimeMillis());
        StringBuilder b = new StringBuilder();
        b.append(f.title).append(" | ").append(f.big).append(" | ").append(f.line1).append(" | ").append(f.line2).append(" | [");
        for (int i = 0; i < f.buttons.size(); i++) {
            DialView.Button x = f.buttons.get(i);
            b.append(i > 0 ? ", " : "").append(x.label).append(x.enabled ? "" : " (off)");
        }
        b.append("] | ").append(f.toast).append(" | ").append(f.top).append(f.linked ? " (linked)" : "");
        if (f.progress >= 0) b.append(String.format(java.util.Locale.US, " | ring %.2f", f.progress));
        if (f.spin) b.append(" | spinning");
        if (f.tick) b.append(" | tick");
        if (f.picture != null) b.append(" | picture");
        return b.toString();
    }

    /** The labels of the buttons on the screen now. */
    public static String buttons(Activity a) {
        DialView.Face f = ((WatchActivity) a).face(System.currentTimeMillis());
        StringBuilder b = new StringBuilder();
        for (DialView.Button x : f.buttons) b.append(b.length() > 0 ? "," : "").append(x.id);
        return b.toString();
    }

    /** The screen drawn {@code side} pixels across, as the watch would show it. */
    public static Bitmap draw(Activity a, int side) throws Exception {
        WatchActivity w = (WatchActivity) a;
        w.refresh();
        DialView d = (DialView) get(w, "dial");
        return d.draw(side);
    }

    // ---- the Moon screen (3.0)

    /** The Moon screen as the watch would make it from the phone's status message (JSON), in words as {@link #face(Activity)}. */
    public static String moonWords(String status, long now) {
        DialView.Face f = moonFace(status, now);
        StringBuilder b = new StringBuilder();
        b.append(f.title).append(" | ").append(f.big).append(" | ").append(f.line1).append(" | ").append(f.line2).append(" | [");
        for (int i = 0; i < f.buttons.size(); i++) {
            DialView.Button x = f.buttons.get(i);
            b.append(i > 0 ? ", " : "").append(x.label).append(x.enabled ? "" : " (off)");
        }
        b.append("] | ").append(f.top);
        if (f.progress >= 0) b.append(String.format(java.util.Locale.US, " | ring %.2f", f.progress));
        if (f.picture != null) b.append(" | picture");
        return b.toString();
    }

    /** That screen drawn {@code side} pixels across. */
    public static Bitmap moonDraw(Context c, String status, long now, int side) {
        DialView d = new DialView(c);
        d.show(moonFace(status, now));
        return d.draw(side);
    }

    /** The watch's tick for the countdown's buzzes at {@code now}: whether a countdown runs. */
    public static boolean moonTick(Context c, String status, long now) {
        JsonTree.Obj s = JsonTree.object(status);
        return MoonFace.tick(c, s != null ? s.obj("moon") : null, now);
    }

    /** The time the watch counts down to (0: none). */
    public static long moonTarget(String status, long now) {
        JsonTree.Obj s = JsonTree.object(status);
        return MoonFace.target(s != null ? s.obj("moon") : null, now);
    }

    private static DialView.Face moonFace(String status, long now) {
        DialView.Face f = new DialView.Face();
        MoonFace.face(f, JsonTree.object(status), "Bluetooth", true, now, 0);
        return f;
    }

    // ---- the Sky Pointer (2.6)

    /** Where the watch thinks it is (the phone's place, as last said), or null. */
    public static double[] place(Context c) {
        return client(c).place();
    }

    public static boolean skyOn(Activity a) throws Exception {
        return sky(a).on;
    }

    /** The stars read (the watch reads them once, on a worker). */
    public static boolean skyStars() {
        return SkyScreen.starsReady();
    }

    /** Where a thing is in the watch's sky now (east, north, up), or null. */
    public static double[] skyWhere(Activity a, String name) throws Exception {
        return sky(a).where(name);
    }

    /** What it's finding ("" when it isn't). */
    public static String skyFinding(Activity a) throws Exception {
        return sky(a).finding();
    }

    /** A reading of the rotation sensor, as Android gives the Sky Pointer one. */
    public static void skyEvent(Activity a, android.hardware.SensorEvent e) throws Exception {
        sky(a).onSensorChanged(e);
    }

    /** Whether the Sky Pointer has the rotation sensor on (while its screen shows). */
    public static boolean skyListening(Activity a, org.robolectric.shadows.ShadowSensorManager ssm) throws Exception {
        return ssm.hasListener(sky(a));
    }

    public static boolean crownToElbow(Context c) {
        return client(c).crownToElbow();
    }

    private static SkyScreen sky(Activity a) throws Exception {
        return (SkyScreen) get(a, "sky");
    }

    private static Object get(Object o, String name) throws Exception {
        Class<?> k = o.getClass();
        while (k != null) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                k = k.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
