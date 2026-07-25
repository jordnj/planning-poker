package com.planningpoker.crypto;

import java.util.Base64;

/**
 * Holds the at-rest data key for JPA attribute converters, which Hibernate
 * instantiates outside the Spring container and so cannot have dependencies
 * injected reliably. {@link com.planningpoker.config.EncryptionConfig}
 * installs the configured key during startup; if a converter is somehow
 * exercised first, it falls back to reading the environment directly rather
 * than silently writing plaintext.
 */
public final class EncryptionKeys {

    private static volatile DataEncryption dataEncryption;

    private EncryptionKeys() {
    }

    public static void install(String base64Key) {
        dataEncryption = new DataEncryption(decode(base64Key));
    }

    public static DataEncryption data() {
        DataEncryption current = dataEncryption;
        if (current == null) {
            synchronized (EncryptionKeys.class) {
                if (dataEncryption == null) {
                    String fromEnv = System.getenv("APP_DATA_KEY");
                    if (fromEnv == null || fromEnv.isBlank()) {
                        throw new IllegalStateException(
                                "At-rest encryption key is not configured. Set APP_DATA_KEY to a "
                                        + "base64-encoded 32-byte key; refusing to read or write "
                                        + "sensitive columns without it.");
                    }
                    dataEncryption = new DataEncryption(decode(fromEnv));
                }
                current = dataEncryption;
            }
        }
        return current;
    }

    private static byte[] decode(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("At-rest encryption key must not be blank");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("At-rest encryption key must be base64-encoded", e);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "At-rest encryption key must decode to exactly 32 bytes (AES-256), got " + decoded.length);
        }
        return decoded;
    }

    /** Test-only reset so a suite can install a different key. */
    static void resetForTests() {
        dataEncryption = null;
    }
}
