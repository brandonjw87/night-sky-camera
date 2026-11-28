package com.brandon.nightsky.watch;

import android.content.Context;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.SkyPointer;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * Sky Pointer (2.6) on the watch: hold your arm out at the sky and it says what's there; Find, and the crown picks
 * something to find (the Moon, a planet, Voyager 1, a black hole), and the watch steers the arm to it, clicking faster
 * as it gets close, like a Geiger counter, and buzzing when it's there. The sky maths is {@link SkyPointer}'s; this is
 * the sensor, the place (the phone's, as it last said), and the screen.
 */
final class SkyScreen implements SensorEventListener {
    /** The sky is placed again this often (it turns a degree in 4 minutes), and the arm smoothed over about this long. */
    private static final long SKY_MS = 2000;
    private static final float SMOOTH = 0.2f;
    /** Out of the Sky Pointer after this long without a tap or a turn of the crown (the screen is kept on in it). */
    static final long IDLE_MS = 4 * 60_000L;

    private final Context ctx;
    private final WatchClient client;
    private final Runnable changed;
    private final Handler h = new Handler(Looper.getMainLooper());
    private SensorManager sm;
    private Sensor rotation;

    private static volatile SkyPointer.Catalogue catalogue;
    private static volatile boolean loading;

    boolean on, finding;
    private SkyPointer.Sky sky;
    private long skyAt;
    private List<SkyPointer.Thing> targets;
    private String target = "";
    /** Where the arm points (east, north, up), smoothed; null until the sensor has spoken. */
    private double[] arm;
    /** The compass's own idea of how far off it may be (degrees; NaN: it doesn't say), and whether it says it's unsure. */
    private float headingDeg = Float.NaN;
    private boolean unsure;
    private long openedAt, touchedAt;
    private boolean foundBuzzed;
    private long nextClick;
    private String note = "";
    private long noteUntil;
    /** The screen is redrawn at most this often while the arm moves (the sensor speaks 50 times a second). */
    private static final long DRAW_MS = 50;
    private long drawnAt, findingSince;
    /** The compass's declination where the phone last was (east of true north, degrees), worked out once a minute. */
    private double declination;
    private long declinationAt;
    private double[] declinationFor;

    SkyScreen(Context c, WatchClient client, Runnable changed) {
        this.ctx = c.getApplicationContext();
        this.client = client;
        this.changed = changed;
    }

    /** The stars, read once from the app (on a worker: it takes a moment). */
    static void loadStars(final Context c) {
        if (catalogue != null || loading) return;
        loading = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                BrightStars cat = null;
                try {
                    InputStream in = c.getAssets().open("stars.csv");
                    cat = BrightStars.load(in);
                } catch (Exception ignored) {
                    // no stars: the planets, the Moon and the rest still work
                }
                catalogue = new SkyPointer.Catalogue(cat);
                loading = false;
            }
        }, "sky-stars").start();
    }

    void open() {
        on = true;
        finding = false;
        openedAt = touchedAt = System.currentTimeMillis();
        foundBuzzed = false;
        loadStars(ctx);
        start();
    }

    void close() {
        on = false;
        stop();
    }

    /** The sensor on (while the screen shows the pointer). */
    void start() {
        if (!on) return;
        if (sm == null) sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        if (sm != null && rotation == null) rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (sm != null && rotation != null) sm.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME);
        h.removeCallbacks(clicker);
        h.post(clicker);
    }

    void stop() {
        if (sm != null) sm.unregisterListener(this);
        h.removeCallbacks(clicker);
    }

    boolean idle(long now) {
        return on && now - touchedAt > IDLE_MS;
    }

    // ------------------------------------------------------------------------------------------------
    // The sensor

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        float[] R = new float[9];
        SensorManager.getRotationMatrixFromVector(R, e.values);
        // the compass's estimated accuracy, where the sensor gives one (radians)
        if (e.values.length > 4 && e.values[4] > 0) headingDeg = (float) Math.toDegrees(e.values[4]);
        point(SkyPointer.arm(R, client.crownToElbow(), declination(System.currentTimeMillis())));
    }

    /** Magnetic north's angle from true north where the phone last was (0 where that isn't known). */
    private double declination(long now) {
        double[] place = client.place();
        if (place == null) return 0;
        if (declinationFor == null || now - declinationAt > 60_000L || declinationFor[0] != place[0] || declinationFor[1] != place[1]) {
            declinationFor = place;
            declinationAt = now;
            try {
                declination = new GeomagneticField((float) place[0], (float) place[1], 0f, now).getDeclination();
            } catch (RuntimeException e) {
                declination = 0;
            }
        }
        return declination;
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
        unsure = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW;
    }

    /** A new reading of where the arm points (east, north, up). */
    void point(double[] a) {
        if (arm == null) {
            arm = a.clone();
        } else {
            double x = arm[0] + SMOOTH * (a[0] - arm[0]), y = arm[1] + SMOOTH * (a[1] - arm[1]), z = arm[2] + SMOOTH * (a[2] - arm[2]);
            double n = Math.sqrt(x * x + y * y + z * z);
            if (n > 1e-6) arm = new double[]{x / n, y / n, z / n};
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - drawnAt >= DRAW_MS) {
            drawnAt = now;
            changed.run();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // The sky

    private SkyPointer.Sky sky(long now) {
        double[] place = client.place();
        SkyPointer.Catalogue c = catalogue;
        if (place == null || c == null) return null;
        if (sky == null || now - skyAt > SKY_MS || sky.latDeg != place[0] || sky.lonDeg != place[1]) {
            sky = SkyPointer.sky(c, now, place[0], place[1]);
            skyAt = now;
            targets = SkyPointer.targets(sky);
        }
        return sky;
    }

    private SkyPointer.Thing target() {
        if (targets == null || targets.isEmpty()) return null;
        for (SkyPointer.Thing t : targets) if (t.name.equals(target)) return t;
        target = targets.get(0).name;
        return targets.get(0);
    }

    /** The clicks while finding: quicker the closer, a buzz on it. */
    private final Runnable clicker = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            long wait = 250;
            if (on && finding && arm != null) {
                SkyPointer.Thing t = target();
                if (t != null && sky != null) {
                    SkyPointer.Guide g = SkyPointer.guide(arm, t);
                    if (g.got) {
                        if (!foundBuzzed) Buzz.found(ctx);
                        foundBuzzed = true;
                    } else {
                        if (g.offDeg > SkyPointer.GOT_DEG + 4) foundBuzzed = false;
                        if (g.tickMs > 0 && now >= nextClick) {
                            Buzz.click(ctx);
                            nextClick = now + g.tickMs;
                        }
                    }
                    if (g.tickMs > 0) wait = Math.max(40, Math.min(wait, nextClick - now));
                }
            }
            if (on) h.postDelayed(this, wait);
        }
    };

    // ------------------------------------------------------------------------------------------------
    // What the screen shows

    void face(DialView.Face f, long now) {
        f.top = "Sky Pointer" + (!Float.isNaN(headingDeg) ? String.format(Locale.US, " · compass ±%d°", Math.round(headingDeg)) : "");
        f.linked = arm != null && !unsure;
        double[] place = client.place();
        if (place == null) {
            f.title = "Sky Pointer";
            f.line1 = "Open Night Sky on the phone once,";
            f.line2 = "so the watch knows where you are";
            f.buttons.add(new DialView.Button("sky-back", "Back", true));
            return;
        }
        SkyPointer.Sky s = sky(now);
        if (s == null || arm == null) {
            f.title = "Sky Pointer";
            f.spin = true;
            f.line1 = s == null ? "Reading the stars..." : rotation == null && sm != null ? "This watch has no compass sensor" : "Hold your arm out at the sky";
            f.buttons.add(new DialView.Button("sky-back", "Back", true));
            return;
        }
        if (finding) find(f, s, now);
        else look(f, s, now);
        if (now < noteUntil) {
            f.toast = note;
        } else if (unsure || (!Float.isNaN(headingDeg) && headingDeg > 25)) {
            f.toast = "Compass unsure: wave it in a figure 8";
        } else if (f.toast.isEmpty() && now - openedAt < 8000) {
            f.toast = "Crown by your elbow? Tap to flip";
        }
    }

    private void look(DialView.Face f, SkyPointer.Sky s, long now) {
        SkyPointer.Look l = SkyPointer.look(s, arm);
        boolean down = l.altDeg < -2;
        f.title = down ? "Down through the Earth" : "Pointing at";
        if (l.best != null) {
            f.big = l.best.name;
            f.line1 = l.best.about;
        } else {
            f.big = "Empty sky";
            f.line1 = "Nothing bright there: try the Moon or a star";
        }
        f.bigScale = f.big.length() <= 7 ? 0.62f : f.big.length() <= 11 ? 0.5f : 0.4f;
        f.line2 = SkyPointer.where(l.altDeg, l.azDeg);
        if (l.alsoNear != null) f.toast = l.alsoNear.name + " is out that way too";
        f.buttons.add(new DialView.Button("sky-find", "Find", true));
        f.buttons.add(new DialView.Button("sky-back", "Back", false));
    }

    private void find(DialView.Face f, SkyPointer.Sky s, long now) {
        SkyPointer.Thing t = target();
        if (t == null) {
            finding = false;
            look(f, s, now);
            return;
        }
        SkyPointer.Guide g = SkyPointer.guide(arm, t);
        f.top = "Sky Pointer · Find";
        f.title = t.name;
        if (now - findingSince < 6000) f.toast = "Turn the crown to find something else";
        f.progress = g.closeness;
        if (g.got) {
            f.tick = true;
            f.line1 = t.up() ? "Got it!" : "Got it! (through the Earth)";
            f.line2 = t.about;
        } else {
            f.arrow = (float) g.arrowDeg;
            f.line1 = g.words + String.format(Locale.US, " · %d° to go", Math.round(g.offDeg));
            f.line2 = t.up() ? "It's " + SkyPointer.where(t.altDeg, t.azDeg) : "It's below the horizon: point through the Earth";
        }
        f.buttons.add(new DialView.Button("sky-look", "What's there", false));
        f.buttons.add(new DialView.Button("sky-back", "Back", true));
    }

    // ------------------------------------------------------------------------------------------------
    // Taps and the crown

    /** True if it was the pointer's. */
    boolean action(String id) {
        touchedAt = System.currentTimeMillis();
        switch (id) {
            case "sky-find":
                finding = true;
                foundBuzzed = false;
                findingSince = touchedAt;
                return true;
            case "sky-look":
                finding = false;
                return true;
            case "sky-back":
                close();
                return true;
            case "tap": {
                boolean b = !client.crownToElbow();
                client.crownToElbow(b);
                arm = null;
                say(b ? "Flipped: crown toward your elbow" : "Back to: crown toward your hand");
                return true;
            }
            default:
                return false;
        }
    }

    void rotate(int steps) {
        touchedAt = System.currentTimeMillis();
        if (!finding || targets == null || targets.isEmpty()) return;
        int at = 0;
        for (int i = 0; i < targets.size(); i++) if (targets.get(i).name.equals(target)) at = i;
        int n = targets.size();
        target = targets.get(((at + steps) % n + n) % n).name;
        foundBuzzed = false;
        nextClick = 0;
    }

    private void say(String s) {
        note = s;
        noteUntil = System.currentTimeMillis() + 3500;
    }

    /** For the tests: what it's finding ("" when it isn't). */
    String finding() {
        return finding ? target : "";
    }

    /** For the tests: pick what to find by name; false if it isn't in the list. */
    boolean find(String name) {
        sky(System.currentTimeMillis());
        if (targets == null) return false;
        for (SkyPointer.Thing t : targets) {
            if (t.name.equals(name)) {
                finding = true;
                target = name;
                foundBuzzed = false;
                findingSince = System.currentTimeMillis();
                return true;
            }
        }
        return false;
    }

    /** For the tests: where a thing is now (east, north, up), or null. */
    double[] where(String name) {
        SkyPointer.Sky s = sky(System.currentTimeMillis());
        SkyPointer.Thing t = s != null ? s.find(name) : null;
        return t != null ? t.enu : null;
    }

    static boolean starsReady() {
        return catalogue != null;
    }
}
