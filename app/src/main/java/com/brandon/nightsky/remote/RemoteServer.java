package com.brandon.nightsky.remote;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A tiny HTTP server for the remote viewer: one self-contained page, a JSON API, the two preview pictures and
 * the finished files, for a browser on the same local network.
 * <ul>
 * <li>Every request needs the session's secret token: it is in the address the QR code carries, and the start
 * page also trades a short pairing code (typed on a laptop) for it. Anything else gets 403.</li>
 * <li>Connections from outside private, link-local and loopback address ranges are closed unanswered, and so
 * are connections that arrive on an address the {@link LocalFilter} refuses (the phone's mobile data).</li>
 * <li>It runs on a few low-priority threads and only reads what the app has already prepared, so serving never
 * holds up the camera. A watchdog closes connections that send their request too slowly or stop reading the
 * answer, and one device can't hold more than a few connections at once.</li>
 * </ul>
 * Plain Java, so it can be tested on a desktop.
 */
public final class RemoteServer {
    public interface Backend {
        /** The viewer page (UTF-8 HTML). */
        byte[] page();

        /** JSON object with the session's state. */
        String status();

        /** JSON array of per-frame statistics, starting at frame {@code from}. */
        String units(int from);

        /** The running stack (or the finished photo) as a JPEG, or null. */
        Picture stack();

        /** The most recent frame as a JPEG, or null. */
        Picture latest();

        /** Runs a command; returns a JSON object, or null if there is no such command. */
        String action(String name, Map<String, String> args);

        /** Files that can be downloaded now (the finished photo, its RAW copy and the log). */
        List<Download> downloads();

        InputStream open(Download d) throws IOException;
    }

    /** Decides whether to serve connections that arrive on a given local address of the phone. */
    public interface LocalFilter {
        boolean allowLocal(InetAddress local);
    }

    public static final class Picture {
        public final byte[] jpeg;
        public final long version;

        public Picture(byte[] jpeg, long version) {
            this.jpeg = jpeg;
            this.version = version;
        }
    }

    public static final class Download {
        public final String id, name, type;
        public final long size;
        public final Object handle;

        public Download(String id, String name, String type, long size, Object handle) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.size = size;
            this.handle = handle;
        }
    }

    static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_HEADER = 16 * 1024, MAX_BODY = 8 * 1024;
    /** Wrong pairing codes allowed per window, and in total before pairing is locked for a while. */
    private static final int PAIR_TRIES_PER_WINDOW = 5, PAIR_TRIES_TOTAL = 20;
    private static final long PAIR_WINDOW_MS = 60_000L, PAIR_LOCK_MS = 15 * 60_000L;
    /** The whole request must arrive within this; an answer that stops moving for this long is abandoned. */
    private static final long READ_DEADLINE_MS = 20_000L, WRITE_STALL_MS = 30_000L;
    /** Connections one device may have open at once (a browser opens a few in parallel). */
    private static final int MAX_PER_ADDRESS = 6;

    private final Backend backend;
    private final String token;
    private final byte[] tokenBytes;
    private final String pairCode;
    private volatile ServerSocket socket;
    private ThreadPoolExecutor pool;
    private Thread acceptor;
    private volatile boolean running;
    private volatile long lastViewerMs, lastActiveMs, startedMs;
    private volatile int requests;
    private final ArrayDeque<Long> pairFailures = new ArrayDeque<Long>();
    private int pairFailuresTotal;
    private long pairLockedUntilMs;
    private volatile LocalFilter localFilter;
    private final Map<InetAddress, Integer> perAddress = new HashMap<InetAddress, Integer>();
    private final Map<Conn, Boolean> conns = new ConcurrentHashMap<Conn, Boolean>();
    private Thread watchdog;

    /** One open connection, as the watchdog sees it. */
    private static final class Conn {
        final Socket socket;
        final long acceptedMs;
        volatile boolean answering;
        volatile long progressMs;

        Conn(Socket s) {
            socket = s;
            acceptedMs = progressMs = System.currentTimeMillis();
        }
    }

    public RemoteServer(Backend backend) {
        this.backend = backend;
        SecureRandom rnd = new SecureRandom();
        byte[] t = new byte[16]; // 128 bits
        rnd.nextBytes(t);
        token = base64url(t);
        tokenBytes = token.getBytes(UTF8);
        pairCode = String.format(Locale.US, "%06d", rnd.nextInt(1_000_000));
    }

    public String token() {
        return token;
    }

    /** Six digits the start page accepts instead of the token (rate limited). */
    public String pairCode() {
        return pairCode;
    }

    public int port() {
        ServerSocket s = socket;
        return s != null ? s.getLocalPort() : -1;
    }

    public boolean running() {
        return running;
    }

    /** Time (ms, {@link System#currentTimeMillis}) of the last request carrying the token, or 0. Polling counts. */
    public long lastViewerMs() {
        return lastViewerMs;
    }

    /**
     * Time of the last thing a person did in the viewer (opened the page, pressed a button, paired, downloaded),
     * or 0. An open page polling by itself doesn't count, so a forgotten browser tab can't keep the phone awake.
     */
    public long lastActiveMs() {
        return lastActiveMs;
    }

    /** Refuses connections on some of the phone's own addresses (null: serve every address). */
    public void setLocalFilter(LocalFilter f) {
        localFilter = f;
    }

    public long startedMs() {
        return startedMs;
    }

    public int requests() {
        return requests;
    }

    /** Starts listening on the first free port of {@code preferred}, the next ten, or any port. */
    public synchronized int start(int preferred) throws IOException {
        if (running) return port();
        ServerSocket ss = null;
        IOException last = null;
        for (int p = preferred; p <= preferred + 10 && ss == null; p++) {
            ss = tryBind(p);
        }
        if (ss == null) {
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(0), 16);
            } catch (IOException e) {
                last = e;
                ss = null;
            }
        }
        if (ss == null) throw last != null ? last : new IOException("no port");
        socket = ss;
        running = true;
        startedMs = System.currentTimeMillis();
        pool = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(12), new ThreadFactory() {
            private int n;

            @Override
            public synchronized Thread newThread(Runnable r) {
                Thread t = new Thread(r, "viewer-" + (n++));
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY + 1);
                return t;
            }
        });
        pool.allowCoreThreadTimeOut(true);
        final ServerSocket listen = ss;
        acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(listen);
            }
        }, "viewer-accept");
        acceptor.setDaemon(true);
        acceptor.setPriority(Thread.MIN_PRIORITY + 1);
        acceptor.start();
        watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                watchLoop(listen);
            }
        }, "viewer-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        return ss.getLocalPort();
    }

    /** Closes connections that are too slow to send their request or have stopped taking the answer. */
    private void watchLoop(ServerSocket listen) {
        while (running && socket == listen) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            for (Conn c : conns.keySet()) {
                boolean late = c.answering ? now - c.progressMs > WRITE_STALL_MS : now - c.acceptedMs > READ_DEADLINE_MS;
                if (late) closeQuietly(c.socket);
            }
        }
    }

    private static ServerSocket tryBind(int port) {
        ServerSocket s = null;
        try {
            s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port), 16);
            return s;
        } catch (IOException e) {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            return null;
        }
    }

    public synchronized void stop() {
        running = false;
        ServerSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        if (watchdog != null) {
            watchdog.interrupt();
            watchdog = null;
        }
        for (Conn c : conns.keySet()) closeQuietly(c.socket);
    }

    private void acceptLoop(ServerSocket ss) {
        while (running) {
            final Socket c;
            try {
                c = ss.accept();
            } catch (IOException e) {
                if (!running) return;
                continue;
            }
            try {
                LocalFilter lf = localFilter;
                if (!allowed(c.getInetAddress()) || (lf != null && !lf.allowLocal(c.getLocalAddress()))) {
                    closeQuietly(c);
                    continue;
                }
                final InetAddress from = c.getInetAddress();
                if (!enter(from)) {
                    busy(c);
                    continue;
                }
                final Conn conn = new Conn(c);
                conns.put(conn, Boolean.TRUE);
                try {
                    pool.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                handle(conn);
                            } finally {
                                conns.remove(conn);
                                leave(from);
                            }
                        }
                    });
                } catch (RejectedExecutionException | NullPointerException e) {
                    // busy (or stopping): answer at once rather than queue without limit
                    conns.remove(conn);
                    leave(from);
                    busy(c);
                }
            } catch (Throwable t) {
                // never let one connection end the server (or, on Android, the app)
                closeQuietly(c);
            }
        }
    }

    private static void busy(Socket c) {
        try {
            c.setSoTimeout(2000);
            send(c.getOutputStream(), 503, "text/plain", "Busy, try again".getBytes(UTF8), null);
        } catch (IOException ignored) {
        }
        closeQuietly(c);
    }

    private boolean enter(InetAddress a) {
        synchronized (perAddress) {
            Integer n = perAddress.get(a);
            int k = n == null ? 0 : n;
            if (k >= MAX_PER_ADDRESS) return false;
            perAddress.put(a, k + 1);
            return true;
        }
    }

    private void leave(InetAddress a) {
        synchronized (perAddress) {
            Integer n = perAddress.get(a);
            if (n == null || n <= 1) perAddress.remove(a);
            else perAddress.put(a, n - 1);
        }
    }

    /** Private (RFC 1918), link-local, loopback and IPv6 unique-local addresses only. */
    public static boolean allowed(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 16) {
            if ((b[0] & 0xFE) == 0xFC) return true; // fc00::/7
            boolean mapped = (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
            for (int i = 0; i < 10 && mapped; i++) mapped = b[i] == 0;
            if (mapped) {
                try {
                    return allowed(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16)));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------
    // One connection: one request, then close
    // ------------------------------------------------------------------------------------------------

    private static final class Request {
        String method, path;
        final Map<String, String> query = new HashMap<String, String>();
        final Map<String, String> headers = new HashMap<String, String>();
        byte[] body = new byte[0];
    }

    private void handle(final Conn conn) {
        Socket c = conn.socket;
        try {
            c.setSoTimeout(15000);
            c.setTcpNoDelay(true);
            Request r = read(c.getInputStream());
            conn.progressMs = System.currentTimeMillis();
            conn.answering = true;
            OutputStream out = new Progress(c.getOutputStream(), conn);
            if (r == null) {
                send(out, 400, "text/plain", "Bad request".getBytes(UTF8), null);
                return;
            }
            requests++;
            route(r, out);
            out.flush();
        } catch (SocketException ignored) {
            // the viewer went away (or the watchdog closed a stuck connection)
        } catch (IOException ignored) {
        } catch (Throwable e) {
            // a bug, or out of memory while making a picture: answer, but never let it take the app down
            try {
                send(c.getOutputStream(), e instanceof OutOfMemoryError ? 503 : 500, "text/plain",
                        (e instanceof OutOfMemoryError ? "Busy, try again" : "Error: " + e).getBytes(UTF8), null);
            } catch (Throwable ignored) {
            }
        } finally {
            closeQuietly(c);
        }
    }

    /** Output that reports progress to the watchdog after every 64 KB. */
    private static final class Progress extends FilterOutputStream {
        private final Conn conn;

        Progress(OutputStream out, Conn conn) {
            super(out);
            this.conn = conn;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int n = Math.min(len, 64 * 1024);
                out.write(b, off, n);
                conn.progressMs = System.currentTimeMillis();
                off += n;
                len -= n;
            }
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
        }
    }

    private static Request read(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream(512);
        // read up to the blank line that ends the headers (\r\n\r\n, or \n\n from sloppy clients)
        int prev1 = -1, prev2 = -1;
        while (true) {
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            if (head.size() > MAX_HEADER) return null;
            if (b == '\n' && (prev1 == '\n' || (prev1 == '\r' && prev2 == '\n'))) break;
            prev2 = prev1;
            prev1 = b;
        }
        String[] lines = new String(head.toByteArray(), Charset.forName("ISO-8859-1")).split("\r?\n");
        if (lines.length == 0) return null;
        String[] rl = lines[0].split(" ");
        if (rl.length < 2) return null;
        Request r = new Request();
        r.method = rl[0].toUpperCase(Locale.US);
        String target = rl[1];
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        if (q >= 0) parseForm(target.substring(q + 1), r.query);
        for (int i = 1; i < lines.length; i++) {
            int k = lines[i].indexOf(':');
            if (k > 0) r.headers.put(lines[i].substring(0, k).trim().toLowerCase(Locale.US), lines[i].substring(k + 1).trim());
        }
        String cl = r.headers.get("content-length");
        if (cl != null) {
            int n;
            try {
                n = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (n < 0 || n > MAX_BODY) return null;
            byte[] body = new byte[n];
            int off = 0;
            while (off < n) {
                int k = in.read(body, off, n - off);
                if (k < 0) return null;
                off += k;
            }
            r.body = body;
        }
        return r;
    }

    static void parseForm(String s, Map<String, String> out) {
        for (String part : s.split("&")) {
            if (part.isEmpty()) continue;
            int e = part.indexOf('=');
            try {
                String k = URLDecoder.decode(e >= 0 ? part.substring(0, e) : part, "UTF-8");
                String v = e >= 0 ? URLDecoder.decode(part.substring(e + 1), "UTF-8") : "";
                out.put(k, v);
            } catch (IllegalArgumentException | java.io.UnsupportedEncodingException ignored) {
            }
        }
    }

    private boolean authorized(Request r) {
        String t = r.query.get("t");
        if (t == null) t = r.headers.get("x-token");
        return t != null && MessageDigest.isEqual(t.getBytes(UTF8), tokenBytes);
    }

    private void route(Request r, OutputStream out) throws IOException {
        String p = r.path;
        if ("/favicon.ico".equals(p)) {
            send(out, 404, "text/plain", new byte[0], null);
            return;
        }
        if ("/pair".equals(p) && "POST".equals(r.method)) {
            pair(r, out);
            return;
        }
        if (!authorized(r)) {
            if ("/".equals(p) || "/index.html".equals(p)) {
                // the start page: the browser shows it although the status is 403
                send(out, 403, "text/html; charset=utf-8", PairPage.HTML.getBytes(UTF8), null);
            } else {
                send(out, 403, "application/json", "{\"error\":\"forbidden\"}".getBytes(UTF8), null);
            }
            return;
        }
        long now = System.currentTimeMillis();
        lastViewerMs = now;
        if ("/".equals(p) || "/index.html".equals(p)) {
            lastActiveMs = now;
            send(out, 200, "text/html; charset=utf-8", backend.page(), null);
        } else if ("/api/status".equals(p)) {
            json(out, 200, backend.status());
        } else if ("/api/units".equals(p)) {
            int from = 0;
            try {
                String f = r.query.get("from");
                if (f != null) from = Math.max(0, Integer.parseInt(f));
            } catch (NumberFormatException ignored) {
            }
            json(out, 200, backend.units(from));
        } else if ("/stack.jpg".equals(p) || "/latest.jpg".equals(p)) {
            Picture pic = "/stack.jpg".equals(p) ? backend.stack() : backend.latest();
            if (pic == null || pic.jpeg == null) send(out, 404, "text/plain", "Not yet".getBytes(UTF8), null);
            else send(out, 200, "image/jpeg", pic.jpeg, "X-Version: " + pic.version + "\r\n");
        } else if (p.startsWith("/api/") && "POST".equals(r.method)) {
            lastActiveMs = now;
            Map<String, String> args = new HashMap<String, String>(r.query);
            String ct = r.headers.get("content-type");
            if (r.body.length > 0 && (ct == null || ct.startsWith("application/x-www-form-urlencoded"))) {
                parseForm(new String(r.body, UTF8), args);
            }
            args.remove("t");
            String res = backend.action(p.substring(5), args);
            if (res == null) json(out, 404, "{\"error\":\"no such command\"}");
            else json(out, 200, res);
        } else if ("/api/files".equals(p)) {
            Json j = new Json().arr();
            for (Download d : backend.downloads()) {
                j.obj().put("id", d.id).put("name", d.name).put("type", d.type).put("size", d.size).end();
            }
            json(out, 200, j.end().toString());
        } else if ("/file".equals(p)) {
            String id = r.query.get("id");
            Download hit = null;
            for (Download d : backend.downloads()) if (d.id.equals(id)) hit = d;
            if (hit == null) {
                send(out, 404, "text/plain", "No such file".getBytes(UTF8), null);
                return;
            }
            lastActiveMs = now;
            stream(out, hit);
        } else {
            send(out, 404, "text/plain", "Not found".getBytes(UTF8), null);
        }
    }

    private void pair(Request r, OutputStream out) throws IOException {
        Map<String, String> f = new HashMap<String, String>();
        parseForm(new String(r.body, UTF8), f);
        String code = f.get("code");
        if (code != null) code = code.replaceAll("[^0-9]", "");
        long now = System.currentTimeMillis();
        synchronized (pairFailures) {
            while (!pairFailures.isEmpty() && now - pairFailures.peekFirst() > PAIR_WINDOW_MS) pairFailures.pollFirst();
            if (pairFailuresTotal >= PAIR_TRIES_TOTAL) {
                if (now < pairLockedUntilMs) {
                    json(out, 403, "{\"error\":\"Too many wrong codes. Scan the QR code on the phone, or try again in 15 minutes.\"}");
                    return;
                }
                pairFailuresTotal = 0;
            }
            if (pairFailures.size() >= PAIR_TRIES_PER_WINDOW) {
                json(out, 429, "{\"error\":\"Too many wrong codes. Wait a minute and try again.\"}");
                return;
            }
            if (code == null || !MessageDigest.isEqual(code.getBytes(UTF8), pairCode.getBytes(UTF8))) {
                pairFailures.addLast(now);
                if (++pairFailuresTotal >= PAIR_TRIES_TOTAL) pairLockedUntilMs = now + PAIR_LOCK_MS;
                json(out, 403, "{\"error\":\"That code is not right. It is on the phone, under the address.\"}");
                return;
            }
        }
        lastViewerMs = now;
        lastActiveMs = now;
        json(out, 200, new Json().obj().put("token", token).end().toString());
    }

    private void stream(OutputStream out, Download d) throws IOException {
        InputStream in = backend.open(d);
        if (in == null) {
            send(out, 404, "text/plain", "Gone".getBytes(UTF8), null);
            return;
        }
        try {
            String safe = d.name.replaceAll("[^A-Za-z0-9._-]", "_");
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: ").append(d.type).append("\r\n");
            if (d.size >= 0) h.append("Content-Length: ").append(d.size).append("\r\n");
            h.append("Content-Disposition: attachment; filename=\"").append(safe).append("\"\r\n");
            h.append(COMMON);
            out.write(h.toString().getBytes(UTF8));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
    }

    private static final String COMMON = "Cache-Control: no-store\r\n"
            + "X-Content-Type-Options: nosniff\r\n"
            + "Referrer-Policy: no-referrer\r\n"
            + "Content-Security-Policy: default-src 'none'; img-src 'self' blob: data:; style-src 'unsafe-inline'; "
            + "script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'\r\n"
            + "Connection: close\r\n\r\n";

    private static void json(OutputStream out, int code, String body) throws IOException {
        send(out, code, "application/json; charset=utf-8", body.getBytes(UTF8), null);
    }

    static void send(OutputStream out, int code, String type, byte[] body, String extraHeaders) throws IOException {
        StringBuilder h = new StringBuilder(256);
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        if (extraHeaders != null) h.append(extraHeaders);
        h.append(COMMON);
        out.write(h.toString().getBytes(UTF8));
        out.write(body);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 429: return "Too Many Requests";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    private static void closeQuietly(Socket c) {
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    static String base64url(byte[] b) {
        final String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (; i + 2 < b.length; i += 3) {
            int v = (b[i] & 0xFF) << 16 | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF);
            sb.append(A.charAt(v >> 18 & 63)).append(A.charAt(v >> 12 & 63)).append(A.charAt(v >> 6 & 63)).append(A.charAt(v & 63));
        }
        int rest = b.length - i;
        if (rest == 1) {
            int v = (b[i] & 0xFF) << 16;
            sb.append(A.charAt(v >> 18 & 63)).append(A.charAt(v >> 12 & 63));
        } else if (rest == 2) {
            int v = (b[i] & 0xFF) << 16 | (b[i + 1] & 0xFF) << 8;
            sb.append(A.charAt(v >> 18 & 63)).append(A.charAt(v >> 12 & 63)).append(A.charAt(v >> 6 & 63));
        }
        return sb.toString();
    }
}
