package com.brandon.nightsky;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.OpenableColumns;

import com.brandon.nightsky.processing.LiveStats;
import com.brandon.nightsky.processing.Preflight;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.UnitProcessor;
import com.brandon.nightsky.remote.Json;
import com.brandon.nightsky.remote.RemoteServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Connects the remote viewer's web server to the running session: status, the two pictures, the commands and
 * the finished files. Pictures are made on the viewer's own low-priority threads from what the session has
 * already prepared, and a request that arrives while one is being made gets the previous one.
 */
final class RemoteHost implements RemoteServer.Backend {
    static final int PORT = 8765;
    /** After the photo is done, the viewer stays up this long after the last time someone used it... */
    static final long IDLE_STOP_MS = 30 * 60_000L;
    /** ...but never longer than this after the session ended. */
    static final long MAX_KEEP_MS = 2 * 60 * 60_000L;
    /** A viewer counts as watching when the page asked for news this recently. */
    static final long WATCHING_MS = 5 * 60_000L;

    /** One address the phone can be reached at. */
    static final class Address {
        final String ip, label;
        final boolean wifi, hotspot;

        Address(String ip, String label, boolean wifi, boolean hotspot) {
            this.ip = ip;
            this.label = label;
            this.wifi = wifi;
            this.hotspot = hotspot;
        }
    }

    private final Context ctx;
    private final CaptureService service;
    private final SessionController controller;
    private final RemoteServer server;
    private volatile byte[] page;

    private final ReentrantLock stackLock = new ReentrantLock(), latestLock = new ReentrantLock();
    private volatile RemoteServer.Picture stackPic, latestPic;
    private volatile int stackVersion = Integer.MIN_VALUE, latestIndex = Integer.MIN_VALUE;
    private volatile int[][] hist;
    private volatile float[] histClipped;

    private long batteryReadMs;
    private int batteryLevel = -1;
    private float batteryTempC = Float.NaN;
    private boolean charging;

    private final Object netLock = new Object();
    private long cellularReadMs;
    private Set<String> cellularIfs = new HashSet<String>();

    RemoteHost(CaptureService service, SessionController controller) {
        this.service = service;
        this.ctx = service.getApplicationContext();
        this.controller = controller;
        server = new RemoteServer(this);
        server.setLocalFilter(new RemoteServer.LocalFilter() {
            @Override
            public boolean allowLocal(InetAddress local) {
                return !onMobileData(local);
            }
        });
    }

    /** Starts listening; false if no port could be opened. */
    boolean start() {
        try {
            server.start(PORT);
            return true;
        } catch (IOException e) {
            android.util.Log.w("NightSky", "viewer could not start", e);
            return false;
        }
    }

    void stop() {
        server.stop();
    }

    boolean running() {
        return server.running();
    }

    int port() {
        return server.port();
    }

    String token() {
        return server.token();
    }

    String pairCode() {
        return server.pairCode();
    }

    long lastViewerMs() {
        return server.lastViewerMs();
    }

    long lastActiveMs() {
        return server.lastActiveMs();
    }

    /** Someone has the viewer open right now (its page asked for news in the last few minutes). */
    boolean watching() {
        long last = server.lastViewerMs();
        return last > 0 && System.currentTimeMillis() - last < WATCHING_MS;
    }

    /** Address with the secret token, for the QR code. */
    String linkFor(String ip) {
        return "http://" + ip + ":" + port() + "/?t=" + token();
    }

    /** Address to type (the start page then asks for the pairing code). */
    String typedFor(String ip) {
        return "http://" + ip + ":" + port();
    }

    // ------------------------------------------------------------------------------------------------
    // RemoteServer.Backend
    // ------------------------------------------------------------------------------------------------

    @Override
    public byte[] page() {
        byte[] p = page;
        if (p != null) return p;
        InputStream in = null;
        try {
            in = ctx.getResources().openRawResource(R.raw.viewer);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(32 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            p = bo.toByteArray();
            page = p;
            return p;
        } catch (IOException e) {
            return "<!doctype html><title>Night Sky</title><p>The viewer page is missing.".getBytes();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public String status() {
        SessionController.Status s = controller.status();
        SessionController.Phase ph = s.phase;
        boolean capturing = ph == SessionController.Phase.CAPTURING || ph == SessionController.Phase.PAUSED;
        boolean arming = ph.ordinal() >= SessionController.Phase.STARTING.ordinal() && ph.ordinal() <= SessionController.Phase.COUNTDOWN.ordinal();
        boolean ended = ph == SessionController.Phase.IDLE || ph == SessionController.Phase.DONE || ph == SessionController.Phase.FAILED;
        Json j = new Json().obj();
        j.put("app", "Night Sky " + BuildInfo.VERSION);
        j.put("state", stateOf(ph)).put("phase", ph.name());
        PlanRunner plan = service.plans();
        if (ended && plan.armed() && !plan.running()) {
            // between a night plan's sessions
            j.put("headline", "Night plan armed").put("detail", "Next: " + plan.nextText());
        } else {
            j.put("headline", s.headline).put("detail", s.detail);
        }
        j.put("mode", s.trails ? "trails" : "deep");
        j.put("progress", s.progress);
        j.put("frame", Math.min(s.planned, s.done + (ph == SessionController.Phase.CAPTURING ? 1 : 0))).put("frames", s.planned);
        j.put("elapsedMs", s.elapsedMs).put("remainingMs", s.remainingMs);
        if (s.frameExpNs > 0) j.put("exposureS", s.frameExpNs / 1e9).put("iso", s.iso);
        j.put("previewVersion", controller.preview() != null ? s.previewVersion : -1);
        j.put("latestVersion", s.latestVersion);
        j.put("muted", s.muted).put("forced", s.forced);
        j.put("nowMs", System.currentTimeMillis());

        // per-frame summary
        List<LiveStats.Unit> units = controller.liveUnits(0);
        int used = 0, rejected = 0;
        String lastReject = null;
        for (LiveStats.Unit u : units) {
            if (u.reason == null) used++;
            else {
                rejected++;
                lastReject = u.reason;
            }
        }
        j.put("unitsCount", units.size()).put("used", used).put("rejected", rejected);
        if (lastReject != null) j.put("lastReject", lastReject);
        if (!units.isEmpty()) {
            LiveStats.Unit last = units.get(units.size() - 1);
            j.put("stars", last.stars);
            if (last.hfr > 0) {
                // a Gaussian star's full width at half maximum is twice its half-flux radius
                j.put("fwhmPx", 2 * last.hfr);
                SensorSpec sp = controller.liveSpec();
                if (sp != null && sp.model != null && sp.model.fx > 0) j.put("fwhmArcsec", 2 * last.hfr * 206264.8 / sp.model.fx);
            }
            if (!Float.isNaN(last.transparency)) j.put("transparency", last.transparency);
            j.put("background", last.background);
            j.put("lastFrameMs", last.wallMs);
        }
        UnitProcessor.Latest l = controller.latestFrame();
        if (l != null && l.clipped != null) {
            j.arr("clip").val(l.clipped[0]).val(l.clipped[1]).val(l.clipped[2]).end();
        }
        int[][] h = hist;
        if (h != null) {
            j.obj("hist");
            j.arr("r");
            for (int v : h[0]) j.val(v);
            j.end().arr("g");
            for (int v : h[1]) j.val(v);
            j.end().arr("b");
            for (int v : h[2]) j.val(v);
            j.end();
            float[] hc = histClipped;
            if (hc != null) j.arr("clipped").val(hc[0]).val(hc[1]).val(hc[2]).end();
            j.end();
        }

        // the phone
        readBattery();
        j.obj("battery");
        if (batteryLevel >= 0) j.put("level", batteryLevel);
        if (!Float.isNaN(batteryTempC)) j.put("tempC", batteryTempC);
        j.put("charging", charging).end();
        j.put("thermal", thermal());
        try {
            j.put("freeBytes", new StatFs(SessionController.sessionsRoot(ctx).getAbsolutePath()).getAvailableBytes());
        } catch (IllegalArgumentException ignored) {
        }
        j.put("stopsInMin", IDLE_STOP_MS / 60_000L);

        j.obj("can");
        j.put("startNow", arming && !s.forced);
        j.put("cancel", arming);
        j.put("stop", capturing);
        // during a session, and for a while after one (stacked with its photo; G1)
        j.put("addTime", capturing || controller.canAddAfter());
        j.put("mute", s.soundsOn && !ended);
        j.put("arm", ended && service.canArmFromViewer());
        j.end();

        String pj = plan.viewerJson();
        if (pj != null) j.raw("plan", pj);

        if (s.result != null) {
            j.obj("result").put("summary", s.result.summary).put("name", s.result.name);
            if (s.result.reportJson != null) j.raw("report", s.result.reportJson);
            j.end();
        }
        Preflight.Report pf = s.preflight;
        if (pf != null) {
            j.obj("preflight").put("summary", pf.spoken()).put("warnings", pf.hasWarnings());
            j.arr("items");
            for (Preflight.Item it : pf.items) {
                j.obj().put("check", it.key).put("level", it.level.name().toLowerCase(Locale.US)).put("text", it.text).end();
            }
            j.end().end();
        }
        return j.end().toString();
    }

    static String stateOf(SessionController.Phase p) {
        switch (p) {
            case STARTING:
            case PLACE:
            case STEADY:
                return "arming";
            case FOCUS:
                return "focusing";
            case PREFLIGHT:
                return "checking";
            case COUNTDOWN:
                return "countdown";
            case CAPTURING:
                return "capturing";
            case PAUSED:
                return "paused";
            case FINISHING:
            case DEVELOPING:
                return "developing";
            case DONE:
                return "done";
            case FAILED:
                return "failed";
            default:
                return "idle";
        }
    }

    @Override
    public String units(int from) {
        List<LiveStats.Unit> units = controller.liveUnits(from);
        Json j = new Json().arr();
        int i = from;
        for (LiveStats.Unit u : units) {
            j.obj().put("i", i++).put("t", u.t).put("stars", u.stars);
            if (u.hfr > 0) j.put("fwhm", 2 * u.hfr);
            j.put("bg", u.background).put("noise", u.noise);
            if (!Float.isNaN(u.transparency)) j.put("T", u.transparency);
            j.put("ok", u.reason == null);
            if (u.reason != null) j.put("why", u.reason);
            j.end();
        }
        return j.end().toString();
    }

    @Override
    public RemoteServer.Picture stack() {
        Bitmap bmp = controller.preview();
        int v = controller.status().previewVersion;
        RemoteServer.Picture cur = stackPic;
        if (bmp == null) return cur;
        if (cur != null && v == stackVersion) return cur;
        // somebody is already making it: hand out the previous one rather than wait
        if (!stackLock.tryLock()) return cur;
        try {
            if (stackPic != null && v == stackVersion) return stackPic;
            byte[] jpeg = compress(bmp, 88);
            histogram(bmp);
            stackPic = new RemoteServer.Picture(jpeg, v);
            stackVersion = v;
            return stackPic;
        } catch (Throwable t) {
            // out of memory while developing, a recycled bitmap: keep showing the last picture
            return cur;
        } finally {
            stackLock.unlock();
        }
    }

    @Override
    public RemoteServer.Picture latest() {
        UnitProcessor.Latest l = controller.latestFrame();
        RemoteServer.Picture cur = latestPic;
        if (l == null) return cur;
        // no new pictures once capture is over: developing needs the memory
        SessionController.Phase ph = controller.status().phase;
        if (cur != null && ph != SessionController.Phase.CAPTURING && ph != SessionController.Phase.PAUSED) return cur;
        if (cur != null && l.index == latestIndex) return cur;
        if (!latestLock.tryLock()) return cur;
        try {
            if (latestPic != null && l.index == latestIndex) return latestPic;
            RgbImage img = l.image;
            Renderer.Analysis a = controller.liveAnalysis();
            RgbImage src;
            if (a != null && a.cx0 >= 0 && a.cy0 >= 0 && a.cx0 + a.cw <= img.width && a.cy0 + a.ch <= img.height) {
                // the same crop and stretch as the live stack, so the two look alike
                RgbImage view = new RgbImage(img.width, img.height, img.r, img.g, img.b);
                view.x0 = a.cx0;
                view.y0 = a.cy0;
                view.x1 = a.cx0 + a.cw;
                view.y1 = a.cy0 + a.ch;
                src = view.binned(1);
            } else {
                SensorSpec sp = controller.liveSpec();
                if (sp == null) return cur;
                a = Renderer.analyze(img, sp);
                src = img.binned(1);
            }
            Renderer.Params p = new Renderer.Params();
            p.brightness = controller.sessionBrightness();
            int[] px = Renderer.renderSmall(src, a, p, 1);
            Bitmap b = Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888);
            byte[] jpeg = compress(b, 85);
            b.recycle();
            latestPic = new RemoteServer.Picture(jpeg, l.index);
            latestIndex = l.index;
            return latestPic;
        } catch (Throwable e) {
            return cur;
        } finally {
            latestLock.unlock();
        }
    }

    private static byte[] compress(Bitmap b, int quality) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(256 * 1024);
        b.compress(Bitmap.CompressFormat.JPEG, quality, bo);
        return bo.toByteArray();
    }

    /** 64-bin histogram of each channel of the displayed picture, and the share of pixels at full brightness. */
    private void histogram(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] row = new int[w];
        int[][] out = new int[3][64];
        long[] full = new long[3];
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int c = row[x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
                out[0][r >> 2]++;
                out[1][g >> 2]++;
                out[2][bl >> 2]++;
                if (r >= 254) full[0]++;
                if (g >= 254) full[1]++;
                if (bl >= 254) full[2]++;
            }
        }
        float n = Math.max(1f, (float) w * h);
        histClipped = new float[]{full[0] / n, full[1] / n, full[2] / n};
        hist = out;
    }

    @Override
    public String action(String name, Map<String, String> args) {
        String msg;
        if ("start-now".equals(name)) msg = controller.remoteStartNow();
        else if ("stop".equals(name)) msg = controller.remoteStop();
        else if ("cancel".equals(name)) msg = controller.remoteCancel();
        else if ("add-time".equals(name)) msg = controller.canAddAfter() ? service.addAfterFromViewer() : controller.remoteAddTime(5 * 60);
        else if ("mute".equals(name)) msg = controller.remoteMute(true);
        else if ("unmute".equals(name)) msg = controller.remoteMute(false);
        else if ("arm".equals(name)) msg = service.armFromViewer();
        else return null;
        return new Json().obj().put("message", msg).end().toString();
    }

    @Override
    public List<RemoteServer.Download> downloads() {
        List<RemoteServer.Download> out = new ArrayList<RemoteServer.Download>();
        SessionController.Result r = controller.status().result;
        if (r == null) return out;
        add(out, "jpeg", r.jpeg, r.name + ".jpg", "image/jpeg");
        add(out, "dng", r.dng, r.name + ".dng", "image/x-adobe-dng");
        add(out, "log", r.log, r.name + "_log.txt", "text/plain; charset=utf-8");
        return out;
    }

    private void add(List<RemoteServer.Download> out, String id, Uri uri, String fallbackName, String type) {
        if (uri == null) return;
        String name = fallbackName;
        long size = -1;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) name = n;
                if (!c.isNull(1)) size = c.getLong(1);
            } else {
                return; // deleted since
            }
        } catch (RuntimeException e) {
            return;
        } finally {
            if (c != null) c.close();
        }
        out.add(new RemoteServer.Download(id, name, type, size, uri));
    }

    @Override
    public InputStream open(RemoteServer.Download d) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        try {
            return cr.openInputStream((Uri) d.handle);
        } catch (SecurityException e) {
            throw new IOException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // The phone and its networks
    // ------------------------------------------------------------------------------------------------

    private synchronized void readBattery() {
        long now = System.currentTimeMillis();
        if (now - batteryReadMs < 10_000) return;
        batteryReadMs = now;
        BatteryInfo b = BatteryInfo.read(ctx);
        batteryLevel = b.pct;
        batteryTempC = b.tempC;
        charging = b.charging;
    }

    private String thermal() {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return null;
        switch (pm.getCurrentThermalStatus()) {
            case PowerManager.THERMAL_STATUS_NONE: return "normal";
            case PowerManager.THERMAL_STATUS_LIGHT: return "warm";
            case PowerManager.THERMAL_STATUS_MODERATE: return "hot";
            case PowerManager.THERMAL_STATUS_SEVERE: return "very hot";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "critical";
            case PowerManager.THERMAL_STATUS_EMERGENCY:
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "overheating";
            default: return null;
        }
    }

    /**
     * True if {@code local} (the phone's address a connection arrived on) belongs to mobile data. The viewer
     * is for the local network: some carriers hand out private addresses that other subscribers can reach.
     */
    boolean onMobileData(InetAddress local) {
        if (local == null || local.isLoopbackAddress()) return false;
        String name = null;
        try {
            NetworkInterface ni = NetworkInterface.getByInetAddress(local);
            if (ni != null) name = ni.getName();
        } catch (IOException | RuntimeException ignored) {
        }
        if (name == null) return false;
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("v4-rmnet") || name.startsWith("pdp")
                || name.startsWith("seth") || name.startsWith("clat")) {
            return true;
        }
        synchronized (netLock) {
            long now = System.currentTimeMillis();
            if (now - cellularReadMs > 15_000L) {
                cellularReadMs = now;
                Set<String> names = new HashSet<String>();
                ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    try {
                        for (Network n : cm.getAllNetworks()) {
                            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                            if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue;
                            LinkProperties lp = cm.getLinkProperties(n);
                            if (lp != null && lp.getInterfaceName() != null) names.add(lp.getInterfaceName());
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
                cellularIfs = names;
            }
            return cellularIfs.contains(name);
        }
    }

    /**
     * Private IPv4 addresses of this phone: its Wi-Fi first, then its hotspots (its own, or the viewer's
     * hotspot started by the app), then anything else such as USB tethering.
     */
    static List<Address> addresses(Context c) {
        String wifiIf = null;
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp != null) wifiIf = lp.getInterfaceName();
                }
            } catch (RuntimeException ignored) {
            }
        }
        List<Address> wifi = new ArrayList<Address>(), hot = new ArrayList<Address>(), other = new ArrayList<Address>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return other;
            for (NetworkInterface ni : Collections.list(en)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    if (!a.isSiteLocalAddress() && !a.isLinkLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (name.equals(wifiIf)) {
                        wifi.add(new Address(ip, "Wi-Fi", true, false));
                    } else if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap")
                            || name.matches("wlan[1-9]\\d*")) {
                        hot.add(new Address(ip, "Hotspot", false, true));
                    } else if (!name.startsWith("rmnet") && !name.startsWith("ccmni") && !name.startsWith("dummy")) {
                        other.add(new Address(ip, name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm")
                                ? "USB" : name, false, false));
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        List<Address> all = new ArrayList<Address>(wifi);
        all.addAll(hot);
        all.addAll(other);
        return all;
    }

    /**
     * A warning when the Wi-Fi network the phone is on has no password (anyone on it could watch the
     * traffic), or null.
     */
    static String wifiWarning(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                if (!(nc.getTransportInfo() instanceof WifiInfo)) return null;
                int sec = ((WifiInfo) nc.getTransportInfo()).getCurrentSecurityType();
                if (sec == WifiInfo.SECURITY_TYPE_OPEN || sec == WifiInfo.SECURITY_TYPE_OWE) {
                    return "This Wi-Fi has no password, so others on it could see the viewer. Use your own Wi-Fi or the hotspot below if you can.";
                }
                return null;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "viewer on port %d", port());
    }
}
