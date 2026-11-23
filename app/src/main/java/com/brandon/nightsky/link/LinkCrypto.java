package com.brandon.nightsky.link;

import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The watch link's cryptography (2.5), all of it from the platform: P-256 key agreement for pairing, HKDF-SHA256 for
 * the keys made from it, AES-256-GCM for every message and HMAC-SHA256 for the pairing's confirmations. The phone and
 * the watch use the same class. Plain Java, so it runs in the desktop tests too.
 */
public final class LinkCrypto {
    private LinkCrypto() {}

    public static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SecureRandom RNG = new SecureRandom();
    /** AES-GCM's nonce and tag sizes (bytes). */
    public static final int NONCE = 12, TAG = 16;

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static byte[] utf8(String s) {
        return s.getBytes(UTF8);
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) d.update(p);
            return d.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key.length == 0 ? new byte[32] : key, "HmacSHA256"));
            for (byte[] p : parts) {
                // each part's length first, so two different splits of the same bytes never give the same MAC
                m.update(new byte[]{(byte) (p.length >>> 24), (byte) (p.length >>> 16), (byte) (p.length >>> 8), (byte) p.length});
                m.update(p);
            }
            return m.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** RFC 5869's HKDF with SHA-256: {@code len} bytes from {@code ikm}. */
    public static byte[] hkdf(byte[] ikm, byte[] salt, String info, int len) {
        return hkdf(ikm, salt, utf8(info), len);
    }

    /** The same with {@code info} as bytes. */
    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int len) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(salt == null || salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
            byte[] prk = m.doFinal(ikm);
            m.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] out = new byte[len], t = new byte[0], inf = info;
            int at = 0;
            for (int i = 1; at < len; i++) {
                m.update(t);
                m.update(inf);
                m.update((byte) i);
                t = m.doFinal();
                int n = Math.min(t.length, len - at);
                System.arraycopy(t, 0, out, at, n);
                at += n;
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code plain} sealed with AES-256-GCM: a fresh 12-byte nonce, then the ciphertext and its tag. */
    public static byte[] seal(byte[] key, byte[] plain, byte[] aad) {
        try {
            byte[] nonce = random(NONCE);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG * 8, nonce));
            if (aad != null) c.updateAAD(aad);
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[NONCE + ct.length];
            System.arraycopy(nonce, 0, out, 0, NONCE);
            System.arraycopy(ct, 0, out, NONCE, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What {@link #seal} sealed; throws if the box was made with another key or has been changed in any way. */
    public static byte[] open(byte[] key, byte[] box, byte[] aad) throws GeneralSecurityException {
        if (box == null || box.length < NONCE + TAG) throw new GeneralSecurityException("too short");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG * 8, box, 0, NONCE));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(box, NONCE, box.length - NONCE);
    }

    /** A new P-256 key pair. */
    public static KeyPair ecKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), RNG);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A public key as it travels (X.509 SubjectPublicKeyInfo). */
    public static byte[] encode(PublicKey k) {
        return k.getEncoded();
    }

    /** A P-256 public key that travelled; throws if it isn't one. */
    public static PublicKey decode(byte[] x509) throws GeneralSecurityException {
        if (x509 == null || x509.length < 64 || x509.length > 200) throw new GeneralSecurityException("not a public key");
        PublicKey k = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(x509));
        if (!(k instanceof java.security.interfaces.ECPublicKey)
                || ((java.security.interfaces.ECPublicKey) k).getParams().getCurve().getField().getFieldSize() != 256) {
            throw new GeneralSecurityException("not a P-256 key");
        }
        return k;
    }

    /** The shared secret of {@code mine} and {@code theirs} (ECDH). */
    public static byte[] agree(PrivateKey mine, PublicKey theirs) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine);
        ka.doPhase(theirs, true);
        return ka.generateSecret();
    }

    /** Whether two MACs are equal, taking the same time whatever they hold. */
    public static boolean same(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String hex(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[2 * i] = HEX[(b[i] >> 4) & 15];
            c[2 * i + 1] = HEX[b[i] & 15];
        }
        return new String(c);
    }

    public static String b64(byte[] b) {
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    /** Base64 text back to bytes; null if it isn't Base64. */
    public static byte[] unb64(String s) {
        if (s == null) return null;
        try {
            return java.util.Base64.getDecoder().decode(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
