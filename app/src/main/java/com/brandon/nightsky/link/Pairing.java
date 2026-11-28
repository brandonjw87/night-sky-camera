package com.brandon.nightsky.link;

import com.brandon.nightsky.remote.Json;
import com.brandon.nightsky.remote.JsonTree;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Pairing a watch with the phone (2.5). The watch shows six digits; they're typed on the phone. Each side makes a new
 * P-256 key pair and a random nonce; they swap the public halves where the code says to meet (the relay topic named
 * from it, or a Bluetooth connection); each works out the same secret from its own private half and the other's
 * public one (ECDH), and from that secret and the code, two keys: one to prove to the other that it knows the code
 * (HMAC over everything swapped), one for the link from then on. Somebody watching sees only public keys; somebody
 * who doesn't know the code can't pass the check; and the four check digits both screens show at the end are only
 * the same when nobody stood in the middle.
 * <pre>
 *   watch -> phone   pair-hello {pid, pub W, nonce nW, name}         (repeated until answered)
 *   phone -> watch   pair-ok    {pid, to, pub P, nonce nP, mac P, ...}
 *   watch -> phone   pair-done  {pid, from, mac W}                    (repeated until the phone is heard on the link)
 * </pre>
 */
public final class Pairing {
    private Pairing() {}

    private static final SecureRandom RNG = new SecureRandom();
    /** How long a code is good for after it was made (ms). */
    public static final long CODE_LIFE_MS = 15 * 60_000L;

    /** Six random digits. */
    public static String newCode() {
        return String.format(java.util.Locale.US, "%06d", RNG.nextInt(1_000_000));
    }

    /** The six digits in what was typed ("482 913", "482-913"), or null if it isn't six digits. */
    public static String digits(String typed) {
        if (typed == null) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < typed.length(); i++) {
            char c = typed.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
            else if (c != ' ' && c != '-' && c != ' ') return null;
        }
        return b.length() == 6 ? b.toString() : null;
    }

    /** "482 913": the code as the screens show it. */
    public static String shown(String code) {
        return code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code;
    }

    /** Where the two meet for {@code code}: the same on both. */
    public static String pairId(String code) {
        return LinkCrypto.hex(LinkCrypto.sha256(LinkCrypto.utf8("nightsky pair v1|" + code))).substring(0, 20);
    }

    /** The relay topic the two meet on. */
    public static String topic(String code) {
        return "nsk-pair-" + pairId(code);
    }

    /** A public key's short fingerprint (which watch a reply is for). */
    static String fingerprint(byte[] pub) {
        return LinkCrypto.hex(LinkCrypto.sha256(pub)).substring(0, 16);
    }

    /** The two keys made from the shared secret: [0] proves the code, [1] is the link's. */
    private static byte[][] derive(byte[] secret, String code, byte[] transcript) {
        byte[] salt = LinkCrypto.sha256(LinkCrypto.utf8("nightsky pair v1|" + code), transcript);
        byte[] okm = LinkCrypto.hkdf(secret, salt, "nightsky pair keys v1", 64);
        return new byte[][]{Arrays.copyOfRange(okm, 0, 32), Arrays.copyOfRange(okm, 32, 64)};
    }

    private static byte[] transcript(byte[] w, byte[] p, byte[] nw, byte[] np) {
        byte[] t = new byte[w.length + p.length + nw.length + np.length + 16];
        int at = 0;
        for (byte[] part : new byte[][]{w, p, nw, np}) {
            t[at++] = (byte) (part.length >>> 24);
            t[at++] = (byte) (part.length >>> 16);
            t[at++] = (byte) (part.length >>> 8);
            t[at++] = (byte) part.length;
            System.arraycopy(part, 0, t, at, part.length);
            at += part.length;
        }
        return t;
    }

    /** The watch's side: it made the code and shows it. */
    public static final class Watch {
        public final String code, pid;
        private final KeyPair kp = LinkCrypto.ecKeyPair();
        private final byte[] pub = LinkCrypto.encode(kp.getPublic()), nonce = LinkCrypto.random(16);
        private final long madeMs = System.currentTimeMillis();
        private LinkKeys link;
        private String done;
        /** What the phone said about itself once it answered: its name, and the relay it uses. */
        public String phoneName = "", relay = "";

        public Watch(String code) {
            this.code = code;
            pid = pairId(code);
        }

        /** Whether the code is too old to use (a new one is shown). */
        public boolean expired(long now) {
            return now - madeMs > CODE_LIFE_MS;
        }

        /** The hello to send (again and again, until the phone answers). */
        public String hello(String name) {
            return new Json().obj().put("t", "pair-hello").put("pid", pid).put("pub", LinkCrypto.b64(pub)).put("n", LinkCrypto.b64(nonce))
                    .put("name", name).end().toString();
        }

        /**
         * The phone's answer: the "pair-done" to send back once it checks out (the link's keys are then {@link #keys}),
         * or null if it isn't an answer to this watch's hello or doesn't check out (somebody without the code).
         */
        public String answer(JsonTree.Obj m) {
            if (m == null || !"pair-ok".equals(m.str("t", "")) || !pid.equals(m.str("pid", ""))) return null;
            if (!fingerprint(pub).equals(m.str("to", ""))) return null;
            if (done != null) return done;
            byte[] p = LinkCrypto.unb64(m.str("pub", "")), np = LinkCrypto.unb64(m.str("n", "")), mac = LinkCrypto.unb64(m.str("mac", ""));
            if (p == null || np == null || mac == null || np.length != 16) return null;
            try {
                PublicKey theirs = LinkCrypto.decode(p);
                byte[] t = transcript(pub, p, nonce, np);
                byte[][] k = derive(LinkCrypto.agree(kp.getPrivate(), theirs), code, t);
                if (!LinkCrypto.same(mac, LinkCrypto.hmac(k[0], LinkCrypto.utf8(LinkKeys.PHONE), t))) return null;
                link = new LinkKeys(k[1]);
                phoneName = m.str("name", "");
                relay = m.str("relay", "");
                done = new Json().obj().put("t", "pair-done").put("pid", pid).put("from", fingerprint(pub))
                        .put("mac", LinkCrypto.b64(LinkCrypto.hmac(k[0], LinkCrypto.utf8(LinkKeys.WATCH), t))).end().toString();
                return done;
            } catch (GeneralSecurityException | RuntimeException e) {
                return null;
            }
        }

        /** The link's keys once the phone's answer checked out (else null). */
        public LinkKeys keys() {
            return link;
        }
    }

    /** The phone's side, once the code has been typed on it. */
    public static final class Phone {
        public final String code, pid;
        private final long madeMs = System.currentTimeMillis();
        private byte[] watchPub, transcript, confirm, linkKey;
        private String reply;
        /** The watch's name, from its hello. */
        public String watchName = "";

        public Phone(String code) {
            this.code = code;
            pid = pairId(code);
        }

        /** Whether this pairing has waited too long for the watch (the code has to be typed again). */
        public boolean expired(long now) {
            return now - madeMs > CODE_LIFE_MS;
        }

        /**
         * A watch's hello: the "pair-ok" to send it, or null if it isn't for this code. A hello repeated gets the same
         * answer; a hello from another key (a watch that made a new code with the same digits) starts over with it.
         */
        public String answer(JsonTree.Obj m, String name, String relay) {
            if (m == null || !"pair-hello".equals(m.str("t", "")) || !pid.equals(m.str("pid", ""))) return null;
            byte[] w = LinkCrypto.unb64(m.str("pub", "")), nw = LinkCrypto.unb64(m.str("n", ""));
            if (w == null || nw == null || nw.length != 16) return null;
            if (reply != null && Arrays.equals(w, watchPub)) return reply;
            try {
                PublicKey theirs = LinkCrypto.decode(w);
                KeyPair kp = LinkCrypto.ecKeyPair();
                byte[] p = LinkCrypto.encode(kp.getPublic()), np = LinkCrypto.random(16);
                byte[] t = transcript(w, p, nw, np);
                byte[][] k = derive(LinkCrypto.agree(kp.getPrivate(), theirs), code, t);
                watchPub = w;
                transcript = t;
                confirm = k[0];
                linkKey = k[1];
                watchName = m.str("name", "");
                reply = new Json().obj().put("t", "pair-ok").put("pid", pid).put("to", fingerprint(w)).put("pub", LinkCrypto.b64(p))
                        .put("n", LinkCrypto.b64(np)).put("mac", LinkCrypto.b64(LinkCrypto.hmac(confirm, LinkCrypto.utf8(LinkKeys.PHONE), t)))
                        .put("name", name).put("relay", relay).end().toString();
                return reply;
            } catch (GeneralSecurityException | RuntimeException e) {
                return null;
            }
        }

        /** The watch's "pair-done": the link's keys when its proof checks out, else null. */
        public LinkKeys done(JsonTree.Obj m) {
            if (m == null || reply == null || !"pair-done".equals(m.str("t", "")) || !pid.equals(m.str("pid", ""))) return null;
            if (!fingerprint(watchPub).equals(m.str("from", ""))) return null;
            byte[] mac = LinkCrypto.unb64(m.str("mac", ""));
            if (!LinkCrypto.same(mac, LinkCrypto.hmac(confirm, LinkCrypto.utf8(LinkKeys.WATCH), transcript))) return null;
            return new LinkKeys(linkKey);
        }
    }
}
