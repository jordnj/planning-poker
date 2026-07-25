package com.planningpoker.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataEncryptionTest {

    private final DataEncryption enc = new DataEncryption("unit-test-key-32-bytes-exactly!!".getBytes(StandardCharsets.UTF_8));

    @Test
    void roundTripsValuesIncludingUnicodeAndEmpty() {
        for (String plaintext : new String[]{"Sprint 42", "8", "☕", "", "  ", "a".repeat(10_000),
                "As a user\nI want\r\nline breaks"}) {
            assertThat(enc.decrypt(enc.encrypt(plaintext))).isEqualTo(plaintext);
        }
    }

    @Test
    void nullStaysNull() {
        assertThat(enc.encrypt(null)).isNull();
        assertThat(enc.decrypt(null)).isNull();
    }

    @Test
    void ciphertextDoesNotContainThePlaintext() {
        String ciphertext = enc.encrypt("Sprint 42 retrospective");
        assertThat(ciphertext).doesNotContain("Sprint").doesNotContain("retrospective");
        assertThat(ciphertext).startsWith("v1:");
    }

    @Test
    void equalPlaintextsProduceDifferentCiphertexts() {
        // a fresh IV per value, so the database leaks no equality relationships:
        // an admin cannot tell that two participants voted the same card
        String a = enc.encrypt("8");
        String b = enc.encrypt("8");
        assertThat(a).isNotEqualTo(b);
        assertThat(enc.decrypt(a)).isEqualTo(enc.decrypt(b)).isEqualTo("8");
    }

    @Test
    void tamperedCiphertextIsRejectedRatherThanDecrypted() {
        String ciphertext = enc.encrypt("13");
        byte[] envelope = Base64.getDecoder().decode(ciphertext.substring("v1:".length()));
        envelope[envelope.length - 1] ^= 0x01; // flip a bit in the GCM tag
        String tampered = "v1:" + Base64.getEncoder().encodeToString(envelope);

        assertThatThrownBy(() -> enc.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed authentication");
    }

    @Test
    void aDifferentKeyCannotRead() {
        DataEncryption other = new DataEncryption("another-key-32-bytes-exactly!!!!".getBytes(StandardCharsets.UTF_8));
        String ciphertext = enc.encrypt("Sprint 42");
        assertThatThrownBy(() -> other.decrypt(ciphertext))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unprefixedValuesPassThroughSoPreEncryptionRowsStillRead() {
        assertThat(enc.decrypt("legacy plaintext")).isEqualTo("legacy plaintext");
    }

    @Test
    void keyMustBe256Bits() {
        assertThatThrownBy(() -> new DataEncryption("too-short".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }
}
