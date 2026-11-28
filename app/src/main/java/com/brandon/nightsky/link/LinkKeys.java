package com.brandon.nightsky.link;

import com.brandon.nightsky.remote.Json;
import com.brandon.nightsky.remote.JsonTree;

import java.security.GeneralSecurityException;
import java.util.Locale;

/**
 * A paired phone and watch (2.5): the key they share and what's made from it. Every message between them is a JSON
 * object sealed with the key (AES-256-GCM), bound to who sent it, so a message the phone sent can't be passed off as
 * the watch's; it carries when it was sent and its own id, so it can't be played again later either ({@link ReplayGuard}).
 * The relay's two topics are named from the key: nobody without it can find them, and what's in them is sealed.
 */
public final class LinkKeys {
    public static final String PHONE = "phone", WATCH = "watch";
    /** The key itself (32 bytes): kept on the two devices and nowhere else. */
    private final byte[] key;
    /** 24 hex characters, the same on both: names the relay's topics. */
    public final String id;

    public LinkKeys(byte[] key) {
        if (key == null || key.length != 32) throw new IllegalArgumentException("a link key is 32 bytes");
        this.key = key.clone();
        id = LinkCrypto.hex(LinkCrypto.hkdf(key, null, "nightsky link id v1", 12));
    }

    /** The key as text, for keeping in the app's own settings. */
    public String toText() {
        return LinkCrypto.b64(key);
    }

    /** Keys kept with {@link #toText}; null if the text isn't one. */
    public static LinkKeys fromText(String s) {
        byte[] k = LinkCrypto.unb64(s);
        return k != null && k.length == 32 ? new LinkKeys(k) : null;
    }

    /** The relay topic the phone writes to and the watch reads. */
    public String phoneTopic() {
        return "nsk-" + id + "-p";
    }

    /** The relay topic the watch writes to and the phone reads. */
    public String watchTopic() {
        return "nsk-" + id + "-w";
    }

    /** Four digits both screens show once paired: the same on both means nobody stood in the middle. */
    public String check() {
        byte[] b = LinkCrypto.hkdf(key, null, "nightsky check v1", 4);
        long v = ((b[0] & 0xFFL) << 24 | (b[1] & 0xFFL) << 16 | (b[2] & 0xFFL) << 8 | (b[3] & 0xFFL)) % 10000L;
        return String.format(Locale.US, "%04d", v);
    }

    /** {@code json} (an object) sealed as sent by {@code from} ({@link #PHONE} or {@link #WATCH}). */
    public byte[] seal(String from, String json) {
        return LinkCrypto.seal(key, LinkCrypto.utf8(json), aad(from));
    }

    /** A sealed message as text (Base64), as the relay carries it. */
    public String sealText(String from, String json) {
        return LinkCrypto.b64(seal(from, json));
    }

    /** The message in {@code box}, which {@code from} must have sealed with this key; throws if it didn't. */
    public JsonTree.Obj open(String from, byte[] box) throws GeneralSecurityException {
        byte[] plain = LinkCrypto.open(key, box, aad(from));
        try {
            return JsonTree.object(new String(plain, LinkCrypto.UTF8));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("not a message");
        }
    }

    /** The same from the relay's text; throws if it isn't one. */
    public JsonTree.Obj openText(String from, String text) throws GeneralSecurityException {
        byte[] box = LinkCrypto.unb64(text);
        if (box == null) throw new GeneralSecurityException("not Base64");
        return open(from, box);
    }

    private byte[] aad(String from) {
        return LinkCrypto.utf8("nightsky link v1|" + from + "|" + id);
    }

    /** A new message of type {@code t}, with when it's sent and its own id; add fields, then {@code end()}. */
    public static Json message(String t) {
        return new Json().obj().put("t", t).put("ts", System.currentTimeMillis()).put("mid", LinkCrypto.hex(LinkCrypto.random(8)));
    }
}
