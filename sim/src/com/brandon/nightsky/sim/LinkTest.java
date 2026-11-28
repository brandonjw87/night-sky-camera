package com.brandon.nightsky.sim;

import com.brandon.nightsky.link.Frames;
import com.brandon.nightsky.link.LinkCrypto;
import com.brandon.nightsky.link.LinkKeys;
import com.brandon.nightsky.link.Pairing;
import com.brandon.nightsky.link.Relay;
import com.brandon.nightsky.link.ReplayGuard;
import com.brandon.nightsky.remote.JsonTree;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The watch link's parts (2.5), off the phone: the cryptography against RFC 5869's test vector and against tampering;
 * pairing with the right code, the wrong one and a meddler; sealed messages that only open for the right sender, and
 * that can't be played twice; framing; and the relay client against a stand-in ntfy server ({@link MockNtfy}): streams,
 * a dropped stream picked up without a message lost or heard twice, polls, the request limit, and a whole pairing
 * done through it the way the phone and the watch do it.
 */
public final class LinkTest {
    private static int fails;

    public static void main(String[] args) throws Exception {
        crypto();
        pairing();
        sealing();
        replay();
        frames();
        MockNtfy relay = new MockNtfy();
        try {
            relayStream(relay);
            relayLimit(relay);
            pairOverRelay(relay);
        } finally {
            relay.stop();
        }
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  PASS: " : "  FAIL: ") + what);
        if (!ok) fails++;
    }

    static byte[] hex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static void crypto() throws Exception {
        // RFC 5869, test case 1
        byte[] okm = LinkCrypto.hkdf(hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), hex("000102030405060708090a0b0c"),
                hex("f0f1f2f3f4f5f6f7f8f9"), 42);
        check(LinkCrypto.hex(okm).equals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
                "HKDF-SHA256 gives RFC 5869's test vector");
        byte[] key = LinkCrypto.random(32);
        byte[] box = LinkCrypto.seal(key, LinkCrypto.utf8("the Crab Pulsar"), LinkCrypto.utf8("aad"));
        check("the Crab Pulsar".equals(new String(LinkCrypto.open(key, box, LinkCrypto.utf8("aad")), LinkCrypto.UTF8)), "a sealed box opens");
        byte[] bent = box.clone();
        bent[bent.length - 5] ^= 1;
        check(throwsOnOpen(key, bent, "aad"), "a box changed by one bit doesn't open");
        check(throwsOnOpen(key, box, "other"), "a box doesn't open for another purpose (AAD)");
        check(throwsOnOpen(LinkCrypto.random(32), box, "aad"), "a box doesn't open with another key");
        byte[] again = LinkCrypto.seal(key, LinkCrypto.utf8("the Crab Pulsar"), LinkCrypto.utf8("aad"));
        check(!java.util.Arrays.equals(box, again), "the same words sealed twice look different (a fresh nonce each time)");
        java.security.KeyPair a = LinkCrypto.ecKeyPair(), b = LinkCrypto.ecKeyPair();
        byte[] s1 = LinkCrypto.agree(a.getPrivate(), LinkCrypto.decode(LinkCrypto.encode(b.getPublic())));
        byte[] s2 = LinkCrypto.agree(b.getPrivate(), LinkCrypto.decode(LinkCrypto.encode(a.getPublic())));
        check(java.util.Arrays.equals(s1, s2) && s1.length == 32, "both sides of P-256 key agreement get the same 32-byte secret");
        boolean refused;
        try {
            LinkCrypto.decode(LinkCrypto.random(91));
            refused = false;
        } catch (GeneralSecurityException e) {
            refused = true;
        }
        check(refused, "random bytes aren't taken as a public key");
    }

    static boolean throwsOnOpen(byte[] key, byte[] box, String aad) {
        try {
            LinkCrypto.open(key, box, LinkCrypto.utf8(aad));
            return false;
        } catch (GeneralSecurityException e) {
            return true;
        }
    }

    static void pairing() throws Exception {
        String code = Pairing.newCode();
        check(code.matches("\\d{6}") && "482913".equals(Pairing.digits("482 913")) && Pairing.digits("48291") == null
                && Pairing.digits("482a13") == null && "482 913".equals(Pairing.shown("482913")), "codes: six digits, typed with spaces or dashes");
        Pairing.Watch w = new Pairing.Watch(code);
        Pairing.Phone p = new Pairing.Phone(code);
        String hello = w.hello("Pixel Watch 3");
        String ok = p.answer(JsonTree.object(hello), "Pixel 10 Pro XL", "https://ntfy.sh");
        check(ok != null && "Pixel Watch 3".equals(p.watchName), "the phone answers the hello of the watch with its code");
        check(ok != null && ok.equals(p.answer(JsonTree.object(hello), "Pixel 10 Pro XL", "https://ntfy.sh")), "the same hello again gets the same answer");
        String done = w.answer(JsonTree.object(ok));
        check(done != null && w.keys() != null && "Pixel 10 Pro XL".equals(w.phoneName), "the watch checks the phone's answer");
        LinkKeys pk = p.done(JsonTree.object(done));
        check(pk != null && pk.id.equals(w.keys().id) && pk.check().equals(w.keys().check()) && pk.id.length() == 24,
                "the phone checks the watch's proof: both have the same key (" + (pk != null ? pk.id + ", check " + pk.check() : "none") + ")");
        // the wrong code: never even the same meeting place
        Pairing.Phone wrong = new Pairing.Phone(code.equals("111111") ? "222222" : "111111");
        check(wrong.answer(JsonTree.object(hello), "x", "y") == null, "a phone with the wrong code doesn't answer");
        // a meddler who changes the phone's key in the answer can't make the watch accept it
        Pairing.Watch w2 = new Pairing.Watch(code);
        Pairing.Phone p2 = new Pairing.Phone(code);
        JsonTree.Obj ans = JsonTree.object(p2.answer(JsonTree.object(w2.hello("w")), "p", "r"));
        ans.map.put("pub", LinkCrypto.b64(LinkCrypto.encode(LinkCrypto.ecKeyPair().getPublic())));
        check(w2.answer(ans) == null && w2.keys() == null, "an answer with a swapped key is refused");
        // ...nor a meddler who knows no code and answers with a key of its own and a made-up proof
        Pairing.Watch w3 = new Pairing.Watch(code);
        Pairing.Phone fake = new Pairing.Phone(code);
        JsonTree.Obj fa = JsonTree.object(fake.answer(JsonTree.object(w3.hello("w")), "p", "r"));
        fa.map.put("mac", LinkCrypto.b64(LinkCrypto.random(32)));
        check(w3.answer(fa) == null, "an answer with a wrong proof is refused");
        // a proof from another watch's exchange is refused by the phone
        check(p2.done(JsonTree.object(done)) == null, "the phone refuses a proof that isn't from its own exchange");
        // answers for another watch's hello are ignored
        Pairing.Watch w4 = new Pairing.Watch(code);
        check(w4.answer(JsonTree.object(ok)) == null, "a watch ignores the answer to another watch's hello");
    }

    static void sealing() throws Exception {
        LinkKeys k = new LinkKeys(LinkCrypto.random(32));
        LinkKeys same = LinkKeys.fromText(k.toText());
        check(same != null && same.id.equals(k.id) && k.phoneTopic().equals("nsk-" + k.id + "-p") && k.watchTopic().matches("nsk-[0-9a-f]{24}-w"),
                "keys kept as text come back the same; topic names from them");
        String js = LinkKeys.message("cmd").put("cmd", "stop").end().toString();
        String box = k.sealText(LinkKeys.WATCH, js);
        JsonTree.Obj m = same.openText(LinkKeys.WATCH, box);
        check("stop".equals(m.str("cmd", "")) && m.lng("ts", 0) > 0 && m.str("mid", "").length() == 16, "a watch's command opens on the phone");
        boolean refused;
        try {
            same.openText(LinkKeys.PHONE, box);
            refused = false;
        } catch (GeneralSecurityException e) {
            refused = true;
        }
        check(refused, "a watch's message can't pass as the phone's");
        try {
            new LinkKeys(LinkCrypto.random(32)).openText(LinkKeys.WATCH, box);
            refused = false;
        } catch (GeneralSecurityException e) {
            refused = true;
        }
        check(refused, "another pairing's key can't open it");
        check(k.sealText(LinkKeys.PHONE, js).length() < 400, "a command is small enough for the relay (" + box.length() + " characters)");
    }

    static void replay() {
        ReplayGuard g = new ReplayGuard(5 * 60_000L);
        long now = System.currentTimeMillis();
        check(g.fresh("a1", now - 1000, now), "a new message is taken");
        check(!g.fresh("a1", now - 1000, now + 2000), "the same message again isn't");
        check(!g.fresh("a2", now - 6 * 60_000L, now), "a message from six minutes ago isn't");
        check(!g.fresh("a3", now + 6 * 60_000L, now), "nor one from a clock six minutes ahead");
        check(!g.fresh("", now, now), "nor one without an id");
    }

    static void frames() throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Frames.write(bo, Frames.SEALED, new byte[]{1, 2, 3});
        Frames.write(bo, Frames.PAIRING, LinkCrypto.utf8("{}"));
        ByteArrayInputStream in = new ByteArrayInputStream(bo.toByteArray());
        byte[] a = Frames.read(in), b = Frames.read(in);
        boolean eof;
        try {
            Frames.read(in);
            eof = false;
        } catch (EOFException e) {
            eof = true;
        }
        check(a.length == 4 && a[0] == Frames.SEALED && a[3] == 3 && b[0] == Frames.PAIRING && eof, "frames come back as written, then the end");
        boolean bad;
        try {
            Frames.read(new ByteArrayInputStream(new byte[]{0x7f, 0, 0, 0, 1}));
            bad = false;
        } catch (java.io.IOException e) {
            bad = true;
        }
        check(bad, "a frame claiming 2 GB is refused");
    }

    static void relayStream(MockNtfy srv) throws Exception {
        srv.limit = false;
        Relay r = new Relay(srv.base() + "/", null);
        final List<String> got = new CopyOnWriteArrayList<String>();
        final List<Boolean> states = new CopyOnWriteArrayList<Boolean>();
        r.publish("nsk-test-a", "before");
        Relay.Subscription s = r.subscribe("nsk-test-a", null, new Relay.Listener() {
            @Override
            public void message(String id, String text) {
                got.add(text);
            }

            @Override
            public void state(boolean open, String why) {
                states.add(open);
            }
        });
        waitFor(s, true, 5000);
        for (int i = 1; i <= 3; i++) r.publish("nsk-test-a", "one " + i);
        waitSize(got, 3, 5000);
        check(got.equals(java.util.Arrays.asList("one 1", "one 2", "one 3")), "a subscription hears what's published from then on, in order: " + got);
        // the relay drops every stream: published meanwhile, heard once after the subscription comes back
        srv.dropStreams();
        Thread.sleep(300);
        r.publish("nsk-test-a", "while away 1");
        r.publish("nsk-test-a", "while away 2");
        waitSize(got, 5, 15000);
        r.publish("nsk-test-a", "after");
        waitSize(got, 6, 5000);
        check(got.size() == 6 && got.get(3).equals("while away 1") && got.get(4).equals("while away 2") && got.get(5).equals("after"),
                "after a drop it picks up where it left off, nothing lost or heard twice: " + got);
        check(states.contains(false) && states.get(states.size() - 1), "it said it lost the relay, then that it was back");
        s.close();
        List<String[]> polled = r.poll("nsk-test-a", "all");
        check(polled.size() == 7 && "before".equals(polled.get(0)[1]), "a poll gives every message kept (" + polled.size() + ")");
        boolean big;
        try {
            r.publish("nsk-test-a", new String(new char[5000]).replace('\0', 'x'));
            big = false;
        } catch (java.io.IOException e) {
            big = true;
        }
        check(big, "a message over 4 KB is refused (the link never sends one)");
    }

    static void relayLimit(MockNtfy srv) throws Exception {
        srv.limit = true;
        Relay r = new Relay(srv.base(), null);
        int sent = 0;
        long wait = 0;
        for (int i = 0; i < 80; i++) {
            try {
                r.publish("nsk-test-b", "n" + i);
                sent++;
            } catch (Relay.TooFast e) {
                wait = e.waitMs;
                break;
            }
        }
        check(sent >= 50 && sent <= 61 && wait >= 5000, "the relay's limit is felt as TooFast with how long to wait (" + sent + " sent, wait "
                + wait / 1000 + " s)");
        srv.limit = false;
    }

    /** A whole pairing through the relay, as the phone and the watch do it, with the hello repeated until answered. */
    static void pairOverRelay(MockNtfy srv) throws Exception {
        srv.limit = false;
        final Relay r = new Relay(srv.base(), null);
        final String code = Pairing.newCode();
        final Pairing.Watch w = new Pairing.Watch(code);
        final Pairing.Phone p = new Pairing.Phone(code);
        final String topic = Pairing.topic(code);
        final LinkKeys[] phoneKeys = {null};
        final List<String> log = Collections.synchronizedList(new ArrayList<String>());
        // the watch: says hello every 300 ms; answers the phone
        Relay.Subscription ws = r.subscribe(topic, null, new Relay.Listener() {
            @Override
            public void message(String id, String text) {
                JsonTree.Obj m = JsonTree.object(text);
                if (!"pair-ok".equals(m.str("t", ""))) return;
                String done = w.answer(m);
                if (done == null) return;
                log.add("watch: checked the phone's answer");
                try {
                    r.publish(topic, done);
                } catch (java.io.IOException e) {
                    log.add("watch: " + e);
                }
            }

            @Override
            public void state(boolean open, String why) {
            }
        });
        Thread hello = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (w.keys() == null) {
                        r.publish(topic, w.hello("Pixel Watch 3"));
                        Thread.sleep(300);
                    }
                } catch (Exception ignored) {
                }
            }
        });
        hello.start();
        Thread.sleep(900); // the code is typed on the phone a moment later
        Relay.Subscription ps = r.subscribe(topic, "60s", new Relay.Listener() {
            @Override
            public void message(String id, String text) {
                JsonTree.Obj m = JsonTree.object(text);
                if ("pair-hello".equals(m.str("t", ""))) {
                    String ok = p.answer(m, "Pixel 10 Pro XL", r.base());
                    if (ok == null) return;
                    try {
                        r.publish(topic, ok);
                    } catch (java.io.IOException e) {
                        log.add("phone: " + e);
                    }
                } else if ("pair-done".equals(m.str("t", "")) && phoneKeys[0] == null) {
                    phoneKeys[0] = p.done(m);
                    log.add("phone: checked the watch's proof");
                }
            }

            @Override
            public void state(boolean open, String why) {
            }
        });
        long end = System.currentTimeMillis() + 10_000;
        while ((phoneKeys[0] == null || w.keys() == null) && System.currentTimeMillis() < end) Thread.sleep(50);
        hello.join(2000);
        ws.close();
        ps.close();
        check(phoneKeys[0] != null && w.keys() != null && phoneKeys[0].id.equals(w.keys().id),
                "paired through the relay: the same key on both (" + log + ", " + srv.messages(topic).size() + " messages)");
        // and the link works: the watch's command, sealed, through the relay to the phone
        if (phoneKeys[0] != null && w.keys() != null) {
            LinkKeys wk = w.keys(), pk = phoneKeys[0];
            r.publish(wk.watchTopic(), wk.sealText(LinkKeys.WATCH, LinkKeys.message("cmd").put("cmd", "stop").end().toString()));
            List<String[]> l = r.poll(pk.watchTopic(), "all");
            JsonTree.Obj m = l.isEmpty() ? null : pk.openText(LinkKeys.WATCH, l.get(0)[1]);
            check(m != null && "stop".equals(m.str("cmd", "")), "the paired watch's sealed Stop reaches the phone through the relay");
            boolean opaque = !l.isEmpty() && !l.get(0)[1].contains("stop");
            check(opaque, "the relay only ever sees it sealed");
        }
    }

    static void waitFor(Relay.Subscription s, boolean open, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (s.isOpen() != open && System.currentTimeMillis() < end) Thread.sleep(20);
    }

    static void waitSize(List<?> l, int n, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (l.size() < n && System.currentTimeMillis() < end) Thread.sleep(20);
    }
}
