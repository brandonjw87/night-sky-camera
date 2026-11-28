package com.brandon.nightsky.sim;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for the ntfy relay (2.5), on this computer, speaking the part of its API the watch link uses: POST to a
 * topic publishes, GET topic/json streams (an "open" line, the cached messages since "since", then each new one, and
 * keepalives), poll=1 returns the cached ones and closes. It keeps ntfy.sh's rules too: a request bucket of 60 filled
 * one every 5 s (HTTP 429 with Retry-After when empty, if {@link #limit} is on) and 4096 bytes a message at most (413:
 * the real one would make it an attachment, which the link never reads). It can also drop every open stream, as a
 * relay restarting or a phone changing networks does.
 */
public final class MockNtfy {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Msg {
        public final String id, topic, text;
        public final long time;

        Msg(String id, String topic, String text, long time) {
            this.id = id;
            this.topic = topic;
            this.text = text;
            this.time = time;
        }
    }

    private final HttpServer server;
    private final List<Msg> all = new CopyOnWriteArrayList<Msg>();
    private final Map<String, List<Stream>> streams = new ConcurrentHashMap<String, List<Stream>>();
    private final AtomicInteger ids = new AtomicInteger();
    /** Publishing and opening a stream one at a time, so a stream never misses or repeats a message. */
    private final Object order = new Object();
    public final AtomicInteger requests = new AtomicInteger(), publishes = new AtomicInteger(), refused = new AtomicInteger();
    /** Keep ntfy.sh's request limit (per server here: one visitor). */
    public volatile boolean limit = true;
    /** Keepalive lines this often (ms). */
    public volatile long keepaliveMs = 2000;
    /** Down: every request answered 503, as when the relay (or the way to it) is out. */
    public volatile boolean down;
    private double tokens = 60;
    private long tokensAt = System.currentTimeMillis();

    private static final class Stream {
        final OutputStream os;
        volatile boolean dead;

        Stream(OutputStream os) {
            this.os = os;
        }

        synchronized boolean write(String line) {
            if (dead) return false;
            try {
                os.write((line + "\n").getBytes(UTF8));
                os.flush();
                return true;
            } catch (IOException e) {
                dead = true;
                return false;
            }
        }
    }

    public MockNtfy() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                try {
                    serve(ex);
                } finally {
                    ex.close();
                }
            }
        });
        server.start();
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void stop() {
        dropStreams();
        server.stop(0);
    }

    /** Every open stream cut, as when the relay restarts. */
    public void dropStreams() {
        for (List<Stream> l : streams.values()) {
            for (Stream s : l) {
                s.dead = true;
                try {
                    s.os.close();
                } catch (IOException ignored) {
                }
            }
            l.clear();
        }
    }

    /** The messages published to {@code topic}, oldest first. */
    public List<Msg> messages(String topic) {
        List<Msg> out = new ArrayList<Msg>();
        for (Msg m : all) if (m.topic.equals(topic)) out.add(m);
        return out;
    }

    /** How many streams are open on {@code topic}. */
    public int listeners(String topic) {
        List<Stream> l = streams.get(topic);
        int n = 0;
        if (l != null) for (Stream s : l) if (!s.dead) n++;
        return n;
    }

    private synchronized boolean take() {
        long now = System.currentTimeMillis();
        tokens = Math.min(60, tokens + (now - tokensAt) / 5000.0);
        tokensAt = now;
        if (!limit) return true;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }

    private void serve(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        if (down) {
            send(ex, 503, "{\"error\":\"down\"}");
            return;
        }
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        if (!take()) {
            refused.incrementAndGet();
            ex.getResponseHeaders().add("Retry-After", "5");
            send(ex, 429, "{\"code\":42901,\"http\":429,\"error\":\"limit reached: too many requests\"}");
            return;
        }
        String[] parts = path.split("/");
        if ("POST".equals(ex.getRequestMethod()) || "PUT".equals(ex.getRequestMethod())) {
            if (parts.length != 2 || !parts[1].matches("[-_A-Za-z0-9]{1,64}")) {
                send(ex, 400, "{\"error\":\"invalid topic\"}");
                return;
            }
            byte[] body = read(ex.getRequestBody());
            if (body.length > 4096) {
                send(ex, 413, "{\"error\":\"message too large\"}");
                return;
            }
            Msg m = new Msg(String.format("m%011d", ids.incrementAndGet()), parts[1], new String(body, UTF8), System.currentTimeMillis() / 1000);
            String line = json(m);
            synchronized (order) {
                all.add(m);
                publishes.incrementAndGet();
                List<Stream> l = streams.get(m.topic);
                if (l != null) for (Stream s : l) s.write(line);
            }
            send(ex, 200, line);
            return;
        }
        if (parts.length != 3 || !"json".equals(parts[2])) {
            send(ex, 404, "{\"error\":\"not found\"}");
            return;
        }
        String topic = parts[1];
        if ("1".equals(q.get("poll"))) {
            List<Msg> back = since(topic, q.get("since"));
            StringBuilder b = new StringBuilder();
            for (Msg m : back) b.append(json(m)).append('\n');
            send(ex, 200, b.toString());
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "application/x-ndjson; charset=utf-8");
        ex.sendResponseHeaders(200, 0);
        Stream s = new Stream(ex.getResponseBody());
        List<Stream> l = streams.get(topic);
        if (l == null) {
            streams.putIfAbsent(topic, new CopyOnWriteArrayList<Stream>());
            l = streams.get(topic);
        }
        synchronized (order) {
            s.write("{\"id\":\"o" + ids.incrementAndGet() + "\",\"time\":" + System.currentTimeMillis() / 1000 + ",\"event\":\"open\",\"topic\":\"" + topic + "\"}");
            for (Msg m : since(topic, q.get("since"))) s.write(json(m));
            l.add(s);
        }
        while (!s.dead) {
            try {
                Thread.sleep(keepaliveMs);
            } catch (InterruptedException e) {
                break;
            }
            s.write("{\"id\":\"k" + ids.incrementAndGet() + "\",\"time\":" + System.currentTimeMillis() / 1000 + ",\"event\":\"keepalive\",\"topic\":\"" + topic + "\"}");
        }
        l.remove(s);
    }

    private List<Msg> since(String topic, String since) {
        List<Msg> out = new ArrayList<Msg>();
        if (since == null) return out;
        long fromTime = -1;
        String fromId = null;
        if ("all".equals(since)) fromTime = 0;
        else if (since.matches("\\d+[smh]")) {
            long n = Long.parseLong(since.substring(0, since.length() - 1));
            char u = since.charAt(since.length() - 1);
            fromTime = System.currentTimeMillis() / 1000 - n * (u == 's' ? 1 : u == 'm' ? 60 : 3600);
        } else if (since.matches("\\d+")) fromTime = Long.parseLong(since);
        else fromId = since;
        boolean after = false;
        for (Msg m : all) {
            if (!m.topic.equals(topic)) continue;
            if (fromId != null) {
                if (after) out.add(m);
                if (m.id.equals(fromId)) after = true;
            } else if (m.time >= fromTime) {
                out.add(m);
            }
        }
        return out;
    }

    private static String json(Msg m) {
        return "{\"id\":\"" + m.id + "\",\"time\":" + m.time + ",\"event\":\"message\",\"topic\":\"" + m.topic + "\",\"message\":\""
                + esc(m.text) + "\"}";
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(UTF8);
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            OutputStream os = ex.getResponseBody();
            os.write(b);
            os.close();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    private static Map<String, String> query(String raw) throws IOException {
        Map<String, String> m = new HashMap<String, String>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int e = kv.indexOf('=');
            if (e < 0) m.put(URLDecoder.decode(kv, "UTF-8"), "");
            else m.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
        }
        return m;
    }
}
