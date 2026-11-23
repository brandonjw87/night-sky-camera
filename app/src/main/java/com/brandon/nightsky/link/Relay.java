package com.brandon.nightsky.link;

import com.brandon.nightsky.remote.JsonTree;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * The internet relay between the phone and the watch (2.5): a public ntfy server (ntfy.sh unless another is set),
 * which passes text from whoever publishes to a topic to whoever is subscribed to it. The topics are named from the
 * link's key and everything put in them is sealed with it, so the relay carries what it can't read for people it
 * can't name. Its rules are kept: a few requests a minute at most, nothing sent when nobody is listening, and a
 * refusal (429) waits as long as it's told.
 */
public final class Relay {
    public static final String DEFAULT = "https://ntfy.sh";

    /** Opens connections (the watch picks which of its networks each one uses). */
    public interface Opener {
        HttpURLConnection open(URL url) throws IOException;
    }

    public interface Listener {
        /** A message's text arrived on the topic. Called on the subscription's own thread. */
        void message(String id, String text);

        /** The subscription is open (true), or was lost and is being opened again (false, with why). */
        void state(boolean open, String why);
    }

    public static final Opener PLAIN = new Opener() {
        @Override
        public HttpURLConnection open(URL url) throws IOException {
            return (HttpURLConnection) url.openConnection();
        }
    };

    private final String base;
    private final Opener opener;

    public Relay(String base, Opener opener) {
        String b = base == null || base.trim().isEmpty() ? DEFAULT : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.opener = opener != null ? opener : PLAIN;
    }

    public String base() {
        return base;
    }

    /** Whether {@code s} is a relay address that can be used: http(s), a host, nothing after it but a path. */
    public static boolean valid(String s) {
        if (s == null) return false;
        try {
            URL u = new URL(s.trim());
            return ("https".equals(u.getProtocol()) || "http".equals(u.getProtocol())) && !u.getHost().isEmpty() && u.getQuery() == null
                    && u.getRef() == null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Thrown when the relay says to slow down: try again after {@link #waitMs}. */
    public static final class TooFast extends IOException {
        public final long waitMs;

        TooFast(long waitMs) {
            super("the relay asks to wait " + waitMs / 1000 + " s");
            this.waitMs = waitMs;
        }
    }

    /** Puts {@code text} on {@code topic}; returns its id on the relay. */
    public String publish(String topic, String text) throws IOException {
        HttpURLConnection c = opener.open(new URL(base + "/" + topic));
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(20_000);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            // no phone push for it (nobody reads these topics in an ntfy app)
            c.setRequestProperty("Firebase", "no");
            byte[] body = LinkCrypto.utf8(text);
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(body);
            } finally {
                os.close();
            }
            int code = c.getResponseCode();
            if (code == 429) throw new TooFast(retryAfter(c));
            String answer = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 300) throw new IOException("the relay said " + code + ": " + answer.trim());
            try {
                return JsonTree.object(answer.trim()).str("id", "");
            } catch (IllegalArgumentException e) {
                return "";
            }
        } finally {
            c.disconnect();
        }
    }

    /** The messages on {@code topic} since {@code since} (an id, a Unix time in seconds, "30s", "all"), as {id, text} pairs. */
    public List<String[]> poll(String topic, String since) throws IOException {
        HttpURLConnection c = opener.open(new URL(base + "/" + topic + "/json?poll=1" + (since != null ? "&since=" + enc(since) : "")));
        List<String[]> out = new ArrayList<String[]>();
        try {
            c.setConnectTimeout(15_000);
            c.setReadTimeout(20_000);
            c.setUseCaches(false);
            int code = c.getResponseCode();
            if (code == 429) throw new TooFast(retryAfter(c));
            if (code >= 300) throw new IOException("the relay said " + code);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), LinkCrypto.UTF8));
            String line;
            while ((line = r.readLine()) != null) {
                String[] m = parse(line);
                if (m != null && m[0] != null) out.add(new String[]{m[0], m[1]});
            }
            return out;
        } finally {
            c.disconnect();
        }
    }

    /** {event, id or null, text or null, time} of one line of the relay's stream; null if it isn't one. */
    private static String[] parse(String line) {
        if (line == null || line.trim().isEmpty()) return null;
        try {
            JsonTree.Obj o = JsonTree.object(line.trim());
            String ev = o.str("event", "");
            if (!"message".equals(ev)) return new String[]{null, null, ev, Long.toString(o.lng("time", 0))};
            return new String[]{o.str("id", ""), o.str("message", ""), ev, Long.toString(o.lng("time", 0))};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Listens to {@code topic} from now on (or from {@code since}: an id, or a Unix time in seconds) until closed:
     * the relay's stream, opened again whenever it drops (soon at first, then less and less often), picking up after
     * the last message heard so none is missed or heard twice.
     */
    public Subscription subscribe(String topic, String since, Listener l) {
        Subscription s = new Subscription(topic, since, l);
        s.thread.start();
        return s;
    }

    public final class Subscription {
        private final String topic;
        private final Listener l;
        final Thread thread;
        private volatile boolean closed;
        private volatile HttpURLConnection conn;
        private volatile String since;
        private volatile boolean open;

        Subscription(String topic, String since, Listener l) {
            this.topic = topic;
            this.since = since;
            this.l = l;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "nightsky-relay");
            thread.setDaemon(true);
        }

        public boolean isOpen() {
            return open && !closed;
        }

        public String topic() {
            return topic;
        }

        public void close() {
            closed = true;
            HttpURLConnection c = conn;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (RuntimeException ignored) {
                }
            }
            thread.interrupt();
        }

        private void loop() {
            long backoff = 2000;
            while (!closed) {
                long opened = 0;
                String why;
                try {
                    String s = since;
                    HttpURLConnection c = opener.open(new URL(base + "/" + topic + "/json" + (s != null ? "?since=" + enc(s) : "")));
                    conn = c;
                    c.setConnectTimeout(15_000);
                    // the relay sends a keepalive every 45 s or so: a minute and a half without one is a dead line
                    c.setReadTimeout(100_000);
                    c.setUseCaches(false);
                    int code = c.getResponseCode();
                    if (code == 429) throw new TooFast(retryAfter(c));
                    if (code >= 300) throw new IOException("the relay said " + code);
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), LinkCrypto.UTF8));
                    String line;
                    while (!closed && (line = r.readLine()) != null) {
                        String[] m = parse(line);
                        if (m == null) continue;
                        if ("open".equals(m[2])) {
                            opened = System.currentTimeMillis();
                            open = true;
                            // after a drop with nothing heard, pick up from when this one opened
                            if (since == null || since.endsWith("s") || since.equals("all")) since = m[3];
                            l.state(true, "");
                        } else if (m[0] != null) {
                            since = m[0];
                            try {
                                l.message(m[0], m[1]);
                            } catch (RuntimeException ignored) {
                                // one message the listener choked on doesn't end the subscription
                            }
                        }
                    }
                    why = "the relay closed the connection";
                } catch (TooFast e) {
                    why = e.getMessage();
                    backoff = Math.max(backoff, Math.min(10 * 60_000L, e.waitMs));
                } catch (IOException | RuntimeException e) {
                    why = e.getMessage() != null ? e.getMessage() : e.toString();
                } finally {
                    HttpURLConnection c = conn;
                    conn = null;
                    if (c != null) c.disconnect();
                }
                boolean was = open;
                open = false;
                if (closed) break;
                if (was) l.state(false, why);
                // a connection that stayed up a while starts the waiting over
                if (opened > 0 && System.currentTimeMillis() - opened > 60_000L) backoff = 2000;
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    if (closed) break;
                }
                if (!was) l.state(false, why);
                backoff = Math.min(60_000L, backoff * 2);
            }
        }
    }

    private static long retryAfter(HttpURLConnection c) {
        String s = c.getHeaderField("Retry-After");
        try {
            return s != null ? Math.max(5_000L, Long.parseLong(s.trim()) * 1000L) : 60_000L;
        } catch (NumberFormatException e) {
            return 60_000L;
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (IOException e) {
            return s;
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0 && bo.size() < 65536) bo.write(b, 0, n);
            return new String(bo.toByteArray(), LinkCrypto.UTF8);
        } finally {
            in.close();
        }
    }
}
