package com.brandon.nightsky.sim;

import com.brandon.nightsky.remote.Json;
import com.brandon.nightsky.remote.RemoteServer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * Runs the remote viewer's web server on a desktop with a pretend session (arming, countdown, capture with a
 * cloud and a bump, developing, done), so the viewer page can be tried in a browser without a phone.
 *
 * <pre>
 * java -cp build/sim com.brandon.nightsky.sim.ViewerDemo app/src/main/res/raw/viewer.html [port] [speed]
 * </pre>
 * It prints the address with the token and the pairing code. {@code speed} > 1 runs the session faster.
 */
public final class ViewerDemo implements RemoteServer.Backend {
    private final byte[] page;
    private final double speed;
    private final Random rnd = new Random(3);

    // pretend session
    private String state = "arming";
    private long stateStartMs = System.currentTimeMillis();
    private int frames = 18, done;
    private final double frameS = 16;
    private boolean muted, forced;
    private final List<double[]> units = new ArrayList<double[]>(); // t, stars, fwhm, bg, ok, transparency
    private final List<String> reasons = new ArrayList<String>();
    private int previewVersion = -1;
    private byte[] stackJpeg, latestJpeg;
    private final List<String> log = new ArrayList<String>();

    ViewerDemo(byte[] page, double speed) {
        this.page = page;
        this.speed = speed;
    }

    public static void main(String[] args) throws Exception {
        byte[] page = Files.readAllBytes(new File(args.length > 0 ? args[0] : "app/src/main/res/raw/viewer.html").toPath());
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8765;
        double speed = args.length > 2 ? Double.parseDouble(args[2]) : 4;
        ViewerDemo demo = new ViewerDemo(page, speed);
        RemoteServer server = new RemoteServer(demo);
        int p = server.start(port);
        System.out.println("viewer: http://127.0.0.1:" + p + "/?t=" + server.token());
        System.out.println("pairing code: " + server.pairCode());
        System.out.flush();
        while (true) {
            Thread.sleep(250);
            demo.step();
        }
    }

    private synchronized void setState(String s) {
        state = s;
        stateStartMs = System.currentTimeMillis();
        log.add(String.format(Locale.US, "%tT %s", System.currentTimeMillis(), s));
    }

    private double inState() {
        return (System.currentTimeMillis() - stateStartMs) / 1000.0 * speed;
    }

    synchronized void step() {
        double t = inState();
        if ("arming".equals(state) && (t > 12 || forced)) setState("countdown");
        else if ("countdown".equals(state) && t > (forced ? 1 : 5)) setState("capturing");
        else if ("capturing".equals(state)) {
            int want = (int) Math.min(frames, Math.floor(t / frameS));
            while (done < want) addUnit();
            if (done >= frames) setState("developing");
        } else if ("developing".equals(state) && t > 20) {
            setState("done");
            previewVersion++;
            stackJpeg = picture(done, 0, true);
        }
    }

    private void addUnit() {
        int i = done++;
        double t = i * frameS + frameS / 2;
        boolean cloud = i >= 7 && i <= 9, bump = i == 12;
        double T = cloud ? 0.25 + 0.1 * rnd.nextGaussian() : 0.97 + 0.03 * rnd.nextGaussian();
        int stars = (int) Math.round((cloud ? 90 : 290) + 6 * rnd.nextGaussian());
        double fwhm = (bump ? 5.6 : 3.1) + 0.12 * rnd.nextGaussian() + i * 0.01;
        double bg = 13.4 + 0.15 * i + (cloud ? 4 : 0) + 0.1 * rnd.nextGaussian();
        String why = bump ? "a bump" : (cloud ? String.format(Locale.US, "clouds (%.0f%% light)", 100 * T) : null);
        units.add(new double[]{t, stars, fwhm, bg, why == null ? 1 : 0, T});
        reasons.add(why);
        previewVersion++;
        stackJpeg = picture(i + 1, 0, false);
        latestJpeg = picture(1, i, false);
    }

    /** A pretend star field: more frames, less noise. */
    private byte[] picture(int stacked, int seed, boolean finished) {
        int w = 1020, h = 768;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(18, 20, 30));
        g.fillRect(0, 0, w, h);
        Random r = new Random(11);
        Random n = new Random(seed * 7919L + stacked);
        double noise = 22 / Math.sqrt(Math.max(1, stacked));
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int v = (int) Math.max(0, Math.min(255, 22 + noise * n.nextGaussian()));
                g.setColor(new Color(v, v, Math.min(255, v + 6)));
                g.fillRect(x, y, 2, 2);
            }
        }
        double drift = finished ? 0 : seed * 0.6;
        for (int k = 0; k < 900; k++) {
            double x = r.nextDouble() * w + drift, y = r.nextDouble() * h;
            double m = Math.pow(r.nextDouble(), 3);
            int b = (int) (90 + 165 * m);
            float s = (float) (0.8 + 3.2 * m);
            g.setColor(new Color(b, b, Math.min(255, b + 10 + r.nextInt(30))));
            g.fill(new java.awt.geom.Ellipse2D.Double(x - s / 2, y - s / 2, s, s));
        }
        g.dispose();
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", bo);
            return bo.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------

    @Override
    public byte[] page() {
        return page;
    }

    @Override
    public synchronized String status() {
        Json j = new Json().obj();
        j.put("app", "Night Sky demo").put("state", state);
        boolean capturing = "capturing".equals(state) || "paused".equals(state);
        String head;
        if ("arming".equals(state)) head = "Lay the phone screen-down";
        else if ("countdown".equals(state)) head = "Starting in " + Math.max(1, 5 - (int) inState());
        else if (capturing) head = "Capturing";
        else if ("developing".equals(state)) head = "Developing";
        else head = "Your photo is ready";
        j.put("headline", head);
        j.put("detail", capturing ? String.format(Locale.US, "Frame %d of %d", Math.min(frames, done + 1), frames) : "");
        j.put("mode", "deep");
        j.put("progress", capturing ? done / (double) frames : ("done".equals(state) ? -1 : -1));
        j.put("frame", Math.min(frames, done + (capturing ? 1 : 0))).put("frames", frames);
        j.put("remainingMs", (long) ((frames - done) * frameS * 1000));
        j.put("exposureS", frameS).put("iso", 1600);
        j.put("previewVersion", stackJpeg != null ? previewVersion : -1);
        j.put("latestVersion", latestJpeg != null ? done - 1 : -1);
        j.put("muted", muted).put("forced", forced).put("nowMs", System.currentTimeMillis());
        int used = 0, rej = 0;
        String last = null;
        for (int i = 0; i < units.size(); i++) {
            if (reasons.get(i) == null) used++;
            else {
                rej++;
                last = reasons.get(i);
            }
        }
        j.put("unitsCount", units.size()).put("used", used).put("rejected", rej);
        if (last != null) j.put("lastReject", last);
        if (!units.isEmpty()) {
            double[] u = units.get(units.size() - 1);
            j.put("stars", (long) u[1]).put("fwhmPx", u[2]).put("fwhmArcsec", u[2] * 70).put("transparency", u[5]);
            j.put("background", u[3]).put("lastFrameMs", System.currentTimeMillis() - 2000);
            j.arr("clip").val(0.00002).val(0.00011).val(0.0).end();
        }
        if (stackJpeg != null) {
            j.obj("hist");
            Random r = new Random(5);
            for (String c : new String[]{"r", "g", "b"}) {
                j.arr(c);
                for (int k = 0; k < 64; k++) j.val((long) (40000 * Math.exp(-Math.pow((k - 7) / 3.0, 2)) + 300 * Math.exp(-k / 12.0) + r.nextInt(20)));
                j.end();
            }
            j.end();
        }
        j.obj("battery").put("level", 81).put("tempC", 23.5).put("charging", false).end();
        j.put("thermal", "normal").put("freeBytes", 41_200_000_000L).put("stopsInMin", 30);
        boolean arming = "arming".equals(state) || "countdown".equals(state);
        boolean ended = "done".equals(state) || "failed".equals(state) || "idle".equals(state);
        j.obj("can").put("startNow", arming && !forced).put("cancel", arming).put("stop", capturing).put("addTime", capturing)
                .put("mute", !ended).put("arm", ended).end();
        return j.end().toString();
    }

    @Override
    public synchronized String units(int from) {
        Json j = new Json().arr();
        for (int i = from; i < units.size(); i++) {
            double[] u = units.get(i);
            j.obj().put("i", i).put("t", u[0]).put("stars", (long) u[1]).put("fwhm", u[2]).put("bg", u[3]).put("T", u[5])
                    .put("ok", u[4] > 0);
            if (reasons.get(i) != null) j.put("why", reasons.get(i));
            j.end();
        }
        return j.end().toString();
    }

    @Override
    public synchronized RemoteServer.Picture stack() {
        return stackJpeg == null ? null : new RemoteServer.Picture(stackJpeg, previewVersion);
    }

    @Override
    public synchronized RemoteServer.Picture latest() {
        return latestJpeg == null ? null : new RemoteServer.Picture(latestJpeg, done - 1);
    }

    @Override
    public synchronized String action(String name, Map<String, String> args) {
        String msg;
        if ("start-now".equals(name)) {
            forced = true;
            msg = "Starting as soon as the phone is still";
        } else if ("stop".equals(name)) {
            frames = done;
            msg = "Stopped. Developing the frames taken so far.";
        } else if ("add-time".equals(name)) {
            frames += (int) Math.round(300 / frameS);
            msg = "Added 5 minutes";
        } else if ("mute".equals(name) || "unmute".equals(name)) {
            muted = "mute".equals(name);
            msg = muted ? "The phone is muted" : "The phone will beep and speak again";
        } else if ("arm".equals(name)) {
            units.clear();
            reasons.clear();
            done = 0;
            frames = 18;
            forced = false;
            stackJpeg = latestJpeg = null;
            setState("arming");
            msg = "Starting.";
        } else if ("cancel".equals(name)) {
            setState("idle");
            msg = "Cancelled";
        } else {
            return null;
        }
        log.add("action " + name);
        return new Json().obj().put("message", msg).end().toString();
    }

    @Override
    public synchronized List<RemoteServer.Download> downloads() {
        List<RemoteServer.Download> out = new ArrayList<RemoteServer.Download>();
        if (!"done".equals(state) || stackJpeg == null) return out;
        out.add(new RemoteServer.Download("jpeg", "NightSky_demo.jpg", "image/jpeg", stackJpeg.length, stackJpeg));
        byte[] l = String.join("\n", log).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.add(new RemoteServer.Download("log", "NightSky_demo_log.txt", "text/plain; charset=utf-8", l.length, l));
        return out;
    }

    @Override
    public InputStream open(RemoteServer.Download d) {
        return new ByteArrayInputStream((byte[]) d.handle);
    }
}
