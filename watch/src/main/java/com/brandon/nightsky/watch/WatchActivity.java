package com.brandon.nightsky.watch;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import com.brandon.nightsky.link.Pairing;
import com.brandon.nightsky.remote.JsonTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Night Sky on the watch (2.5): the phone's session on the wrist. It pairs with the phone by a code, then shows where
 * the session has got (the ring), what the phone is doing and the buttons that make sense then: Start (the crown picks
 * the setup), Start now and Cancel while it gets ready, +5 min, Stop and Mute while it captures, and Again when it's
 * done. Tapping the middle shows the connections and Unpair.
 */
public final class WatchActivity extends Activity implements WatchClient.Listener, DialView.Actions {
    /** On screen now (the service then leaves the link to it). */
    static volatile boolean visible;
    /** How long a button that needs a second tap waits for it. */
    private static final long CONFIRM_MS = 4000;
    /** The crown's setup is sent once it has stopped turning this long, and shown until the phone has it. */
    private static final long PICK_SEND_MS = 700, PICK_HOLD_MS = 8000;
    private static final Pattern COUNTDOWN = Pattern.compile("Starting in (\\d+)");

    private WatchClient client;
    private DialView dial;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean more;
    private String confirm = "";
    private long confirmUntil;
    private String pick = "";
    private long pickMs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        client = WatchClient.get(this);
        dial = new DialView(this);
        dial.setActions(this);
        setContentView(dial);
        askPermissions();
    }

    /** Bluetooth (to reach the phone without the internet) and notifications (to say the photo is ready). */
    private void askPermissions() {
        List<String> want = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            want.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            want.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!want.isEmpty()) requestPermissions(want.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        client.startBt();
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        client.listen(this);
        client.begin(true);
        h.removeCallbacks(clock);
        h.post(clock);
    }

    @Override
    protected void onStop() {
        visible = false;
        h.removeCallbacks(clock);
        client.unlisten(this);
        if (!pick.isEmpty()) {
            // the crown's choice goes now rather than never
            h.removeCallbacks(sendPick);
            sendPick.run();
        }
        if (WatchService.running || following()) {
            // the service follows the session with the screen off, and buzzes when the photo is ready
            WatchService.start(this);
            client.begin(false);
        } else {
            client.end();
        }
        super.onStop();
    }

    @Override
    public void changed() {
        refresh();
    }

    private final Runnable clock = new Runnable() {
        @Override
        public void run() {
            refresh();
            h.postDelayed(this, 1000);
        }
    };

    private final Runnable sendPick = new Runnable() {
        @Override
        public void run() {
            if (!pick.isEmpty()) client.send("preset", pick);
        }
    };

    /** A session is under way on the phone (from getting ready to developing). */
    static boolean active(String state) {
        return "arming".equals(state) || "focusing".equals(state) || "checking".equals(state) || "countdown".equals(state)
                || "capturing".equals(state) || "paused".equals(state) || "developing".equals(state);
    }

    private boolean following() {
        JsonTree.Obj s = client.status();
        return client.paired() && s != null && !client.via().isEmpty() && active(s.str("state", ""));
    }

    // ------------------------------------------------------------------------------------------------
    // What the screen shows

    void refresh() {
        DialView.Face f = face(System.currentTimeMillis());
        dial.show(f);
        // while a session runs, the service is started now, while the screen is on (it can't start from the background)
        if (visible && following() && !WatchService.running) WatchService.start(this);
        // the screen stays on through the countdown, so the button to press is still there
        JsonTree.Obj s = client.status();
        boolean keepOn = s != null && "countdown".equals(s.str("state", "")) && client.fresh();
        if (keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    DialView.Face face(long now) {
        DialView.Face f = new DialView.Face();
        if (now >= confirmUntil) confirm = "";
        if (!client.paired()) {
            more = false;
            pairing(f, now);
        } else if (now < client.checkUntil && !client.check.isEmpty()) {
            check(f);
        } else if (more) {
            more(f);
        } else {
            JsonTree.Obj s = client.status();
            String via = client.via();
            if (s == null || via.isEmpty()) looking(f);
            else session(f, s, via, now);
        }
        if (now < client.toastUntil && !client.toast.isEmpty()) f.toast = client.toast;
        return f;
    }

    /** The connections in a few words, for the top line before the phone is heard. */
    private String links() {
        boolean bt = client.btUp(), net = client.relayUp();
        return bt && net ? "Bluetooth + internet" : bt ? "Bluetooth" : net ? "Internet" : "No connection yet";
    }

    private void pairing(DialView.Face f, long now) {
        Pairing.Watch p = client.pairing;
        f.top = links();
        f.linked = client.btUp() || client.relayUp();
        f.title = "Pair with your phone";
        if (p == null) {
            f.spin = true;
            f.line1 = "Making a code...";
            return;
        }
        f.big = Pairing.shown(p.code);
        f.bigScale = 0.84f;
        f.line1 = "Type it in Night Sky on the phone";
        f.line2 = "Settings, Pixel Watch, Pair";
        // the ring: how long the code has left
        long made = client.codeMadeMs();
        if (made > 0) f.progress = Math.max(0f, 1f - (now - made) / (float) (Pairing.CODE_LIFE_MS - 60_000L));
        f.buttons.add(new DialView.Button("newcode", "New code", false));
        if (!client.btUp() && !client.relayUp() && !client.relayState.isEmpty()) {
            f.toast = "No internet on the watch yet. Bluetooth works once the phone is near.";
        }
    }

    private void check(DialView.Face f) {
        String phone = client.phoneName();
        f.top = links();
        f.linked = true;
        f.title = "Paired" + (phone.isEmpty() ? "!" : " with " + phone);
        f.big = client.check;
        f.bigScale = 0.9f;
        f.line1 = "The phone shows the same";
        f.line2 = "4 digits";
        f.progress = 1f;
        f.buttons.add(new DialView.Button("ok", "OK", true));
    }

    private void looking(DialView.Face f) {
        f.top = links();
        f.linked = false;
        f.spin = true;
        f.title = "Night Sky";
        f.line1 = "Looking for the phone...";
        f.line2 = "Open Night Sky on the phone";
        f.buttons.add(new DialView.Button("more", "More", false));
        if (!WatchClient.btAllowed(this) && !client.relayUp()) {
            f.toast = "Bluetooth isn't allowed for Night Sky: allow it in the watch's Settings, Apps";
        } else if (!client.btUp() && !client.relayUp() && client.relayState.startsWith("Can't")) {
            f.toast = "No internet on the watch, and the phone isn't near";
        }
    }

    private void more(DialView.Face f) {
        String phone = client.phoneName();
        f.top = "Night Sky watch " + BuildInfo.VERSION;
        f.linked = client.btUp() || client.relayUp();
        f.title = "Paired with " + (phone.isEmpty() ? "your phone" : phone);
        f.line1 = "Bluetooth: " + (client.btUp() ? "connected" : WatchClient.btAllowed(this) ? "not in reach" : "not allowed");
        f.line2 = "Internet: " + (client.relayUp() ? "connected" : "not connected");
        f.big = "";
        f.buttons.add(new DialView.Button("back", "Back", true));
        boolean sure = "unpair".equals(confirm);
        f.buttons.add(new DialView.Button("unpair", sure ? "Sure?" : "Unpair", sure));
        f.toast = "Check digits " + client.checkDigits() + ", as on the phone";
    }

    private void session(DialView.Face f, JsonTree.Obj s, String via, long now) {
        String state = s.str("state", "idle");
        boolean fresh = client.fresh();
        long age = now - client.statusAtMs;
        int battery = s.integer("battery", -1);
        if (fresh) {
            f.top = via + (battery >= 0 ? " · phone " + battery + "%" : "");
        } else {
            f.top = "Last heard " + Math.max(1, age / 60_000L) + " min ago";
        }
        f.linked = fresh;
        String headline = s.str("headline", ""), detail = s.str("detail", "");
        boolean pictureTime = "capturing".equals(state) || "paused".equals(state) || "developing".equals(state) || "done".equals(state);
        if (pictureTime && client.picture != null) f.picture = client.picture;
        if ("idle".equals(state) || "done".equals(state) || "failed".equals(state)) {
            over(f, s, state, headline, detail, now);
        } else if ("capturing".equals(state) || "paused".equals(state)) {
            capturing(f, s, state, headline, detail, now);
        } else if ("developing".equals(state)) {
            // the phone works through its steps (each with its own progress): a turning ring, and the step
            f.title = headline.isEmpty() ? "Developing" : headline;
            f.spin = true;
            f.line1 = detail;
            f.line2 = "Buzzes when it's ready";
        } else {
            // getting ready: placing, holding still, focusing, the check, the countdown
            f.spin = true;
            Matcher m = COUNTDOWN.matcher(headline);
            if ("countdown".equals(state) && m.matches()) {
                f.title = "Starting in";
                f.big = m.group(1);
            } else {
                f.title = headline.isEmpty() ? "Getting ready" : headline;
            }
            f.line1 = detail;
            f.buttons.add(new DialView.Button("start-now", "Start now", true));
            f.buttons.add(new DialView.Button("cancel", "Cancel", false));
        }
    }

    private void capturing(DialView.Face f, JsonTree.Obj s, String state, String headline, String detail, long now) {
        boolean paused = "paused".equals(state);
        f.progress = Math.max(0f, Math.min(1f, (float) s.num("progress", 0)));
        f.title = paused ? "Paused" : "Capturing";
        long left = s.lng("remainingMs", 0) - (paused ? 0 : now - client.statusAtMs);
        f.big = clock(left);
        f.bigScale = left >= 3600_000L ? 0.8f : 1f;
        int frame = s.integer("frame", 0), frames = s.integer("frames", 0);
        f.line1 = paused ? (detail.isEmpty() ? headline : detail) : frames > 0 ? "Frame " + frame + " of " + frames : "";
        f.line2 = paused ? "" : s.str("setup", "");
        DialView.Button add = new DialView.Button("add-time", "+5 min", false);
        add.enabled = s.bool("canAdd", true);
        f.buttons.add(add);
        boolean sure = "stop".equals(confirm);
        f.buttons.add(new DialView.Button("stop", sure ? "Sure?" : "Stop", sure));
        boolean muted = s.bool("muted", false);
        f.buttons.add(new DialView.Button(muted ? "unmute" : "mute", muted ? "Sound" : "Mute", false));
    }

    /** Ready, done or failed: Start (or Again), with the crown to pick the setup. */
    private void over(DialView.Face f, JsonTree.Obj s, String state, String headline, String detail, long now) {
        List<String[]> presets = presets(s);
        String current = s.str("preset", "");
        if (!pick.isEmpty() && (pick.equals(current) || now - pickMs > PICK_HOLD_MS)) pick = "";
        String shown = pick.isEmpty() ? current : pick;
        String name = nameOf(presets, shown);
        String setup = s.str("setup", "");
        boolean canStart = s.bool("canStart", false);
        DialView.Button start;
        if ("done".equals(state)) {
            f.title = "Done!";
            f.progress = 1f;
            String x = s.str("xray", ""), lapse = s.str("lapse", "");
            f.line1 = headline;
            f.line2 = !x.isEmpty() ? "X-ray: " + x : !lapse.isEmpty() ? "Time lapse ready too" : "In Photos on the phone";
            if (!pick.isEmpty()) f.line2 = "Next: " + name;
            start = new DialView.Button("start", "Again", true);
            if (s.bool("canAdd", false)) {
                f.buttons.add(start);
                f.buttons.add(new DialView.Button("add-time", "+5 min", false));
            } else {
                f.buttons.add(start);
            }
        } else if ("failed".equals(state)) {
            f.title = "That didn't work";
            f.line1 = headline;
            f.line2 = !pick.isEmpty() ? "Next: " + name : detail;
            start = new DialView.Button("start", "Try again", true);
            f.buttons.add(start);
        } else {
            f.title = "Ready";
            int dot = setup.indexOf(" · ");
            // the preset's name big, its settings under it; with none, the mode big and the length under it
            f.big = !name.isEmpty() ? name : dot > 0 ? setup.substring(0, dot) : "Night Sky";
            f.bigScale = 0.58f;
            f.line1 = !pick.isEmpty() ? "Setting up the phone..." : !name.isEmpty() || dot < 0 ? setup : setup.substring(dot + 3);
            f.line2 = presets.size() > 1 ? "Turn the crown to change" : "";
            start = new DialView.Button("start", "Start", true);
            f.buttons.add(start);
        }
        start.enabled = canStart;
        if (!canStart && f.toast.isEmpty()) f.toast = s.str("why", "Open Night Sky on the phone to start from the watch");
    }

    /** The phone's presets: {id, name} each. */
    static List<String[]> presets(JsonTree.Obj s) {
        List<String[]> out = new ArrayList<String[]>();
        List<Object> l = s.list("presets");
        if (l == null) return out;
        for (Object o : l) {
            if (!(o instanceof List)) continue;
            List<?> pair = (List<?>) o;
            if (pair.size() < 2 || !(pair.get(0) instanceof String) || !(pair.get(1) instanceof String)) continue;
            out.add(new String[]{(String) pair.get(0), (String) pair.get(1)});
        }
        return out;
    }

    private static String nameOf(List<String[]> presets, String id) {
        if (id == null || id.isEmpty()) return "";
        for (String[] p : presets) if (p[0].equals(id)) return p[1];
        return "";
    }

    /** m:ss, or h:mm:ss from an hour. */
    static String clock(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        long hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
        return hh > 0 ? String.format(Locale.US, "%d:%02d:%02d", hh, mm, ss) : String.format(Locale.US, "%d:%02d", mm, ss);
    }

    // ------------------------------------------------------------------------------------------------
    // Taps and the crown

    @Override
    public void action(String id) {
        long now = System.currentTimeMillis();
        JsonTree.Obj s = client.status();
        switch (id) {
            case "newcode":
                client.newCode();
                break;
            case "ok":
                client.checkSeen();
                break;
            case "more":
                more = true;
                break;
            case "back":
                more = false;
                break;
            case "unpair":
                if (twice(id, now)) {
                    more = false;
                    client.unpair();
                    client.say("Unpaired. A new code is coming.");
                }
                break;
            case "stop":
                if (twice(id, now)) client.send("stop", null);
                break;
            case "start": {
                h.removeCallbacks(sendPick);
                String preset = !pick.isEmpty() ? pick : s != null ? s.str("preset", "") : "";
                client.send("start", preset.isEmpty() ? null : preset);
                pick = "";
                break;
            }
            case "tap":
                // the middle: the connections and Unpair, and back
                if (client.paired() && !(now < client.checkUntil)) more = !more;
                break;
            default:
                // start-now, cancel, add-time, mute, unmute: straight to the phone
                client.send(id, null);
                break;
        }
        if (!"tap".equals(id)) Buzz.tick(this);
        refresh();
    }

    /** True on the second tap of a button that asks "Sure?" first. */
    private boolean twice(String id, long now) {
        if (id.equals(confirm) && now < confirmUntil) {
            confirm = "";
            return true;
        }
        confirm = id;
        confirmUntil = now + CONFIRM_MS;
        return false;
    }

    @Override
    public void rotate(int steps) {
        JsonTree.Obj s = client.status();
        if (s == null || more || client.via().isEmpty() || !client.paired()) return;
        String state = s.str("state", "");
        if (!"idle".equals(state) && !"done".equals(state) && !"failed".equals(state)) return;
        List<String[]> presets = presets(s);
        int n = presets.size();
        if (n < 2) return;
        String now = !pick.isEmpty() ? pick : s.str("preset", "");
        int at = -1;
        for (int i = 0; i < n; i++) if (presets.get(i)[0].equals(now)) at = i;
        if (at < 0) at = steps > 0 ? -1 : 0;
        at = ((at + steps) % n + n) % n;
        pick = presets.get(at)[0];
        pickMs = System.currentTimeMillis();
        h.removeCallbacks(sendPick);
        h.postDelayed(sendPick, PICK_SEND_MS);
        Buzz.tick(this);
        refresh();
    }
}
