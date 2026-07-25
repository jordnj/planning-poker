package com.planningpoker.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryption for data at rest.
 *
 * Scope of protection: the ciphertext is what lands in PostgreSQL, so anyone
 * reading the database, a backup, a dump, or a read replica sees nothing
 * useful. The key lives only in the process environment (see
 * {@link EncryptionKeys}), never in a column, never in source, never in an
 * image. It deliberately does NOT protect against an operator who controls
 * the running application — that process must hold the key to function. The
 * fields that must be hidden even from the operator are encrypted in the
 * browser instead and reach this layer already opaque.
 *
 * A fresh 12-byte IV per value means equal plaintexts produce different
 * ciphertexts, so the database leaks no equality relationships. The GCM tag
 * makes tampering detectable rather than silently decrypting to garbage.
 */
public final class DataEncryption {

    /** Marks our envelope so pre-encryption rows stay recognizable. */
    static final String PREFIX = "v1:";

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public DataEncryption(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalArgumentException("Data encryption key must be exactly 32 bytes (AES-256)");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /** @return {@code v1:} + base64(iv || ciphertext || tag), or null for null input */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] envelope = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            // never include the plaintext in the failure path
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt}. Values without our
     * prefix are returned unchanged so a deployment that predates encryption
     * keeps reading; anything carrying the prefix must decrypt and
     * authenticate or this throws rather than returning suspect data.
     */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        byte[] envelope;
        try {
            envelope = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Stored value is not valid base64", e);
        }
        if (envelope.length <= IV_BYTES) {
            throw new IllegalStateException("Stored value is too short to contain an IV and tag");
        }
        try {
            byte[] iv = Arrays.copyOfRange(envelope, 0, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(envelope, IV_BYTES, envelope.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("Stored value failed authentication — wrong key or tampered data", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
