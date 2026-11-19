package com.agrocti.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Salted PBKDF2-HMAC-SHA256 password hashing (only the JDK is needed). */
public final class PasswordHasher {

    private static final int ITERATIONS = 65_536;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() { }

    /** 16 random bytes as 32 hex characters. */
    public static String newSalt() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** 256-bit hash as 64 hex characters. */
    public static String hash(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), HexFormat.of().parseHex(salt), ITERATIONS, 256);
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            spec.clearPassword();
            return HexFormat.of().formatHex(h);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available in this JDK", e);
        }
    }

    /** Compares in constant time, so the time taken does not reveal how much of the hash matched. */
    public static boolean matches(String password, String salt, String expectedHash) {
        return MessageDigest.isEqual(hash(password, salt).getBytes(), expectedHash.getBytes());
    }

    /** Random token for session cookies. */
    public static String newToken() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
