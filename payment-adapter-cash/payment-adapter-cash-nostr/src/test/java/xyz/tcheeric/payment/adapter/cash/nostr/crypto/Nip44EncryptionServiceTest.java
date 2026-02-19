package xyz.tcheeric.payment.adapter.cash.nostr.crypto;

import nostr.base.PublicKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Nip44EncryptionServiceTest {

    private Nip44EncryptionService service;

    @BeforeEach
    void setUp() {
        service = new Nip44EncryptionService();
    }

    // Verifies that encrypting and decrypting a message produces the original plaintext
    @Test
    void testEncryptDecryptRoundtrip() {
        EphemeralKeyPair sender = EphemeralKeyPair.generate();
        EphemeralKeyPair recipient = EphemeralKeyPair.generate();

        String plaintext = "{\"ref\":\"abc123\",\"amount\":1500}";

        String encrypted = service.encrypt(plaintext, sender.getPrivateKeyHex(), recipient.getPublicKey());
        assertNotNull(encrypted);
        assertFalse(encrypted.isEmpty());
        assertNotEquals(plaintext, encrypted);

        String decrypted = service.decrypt(encrypted, recipient.getPrivateKeyHex(), sender.getPublicKey());
        assertEquals(plaintext, decrypted);
    }

    // Verifies that encrypting with same key pair (self-encryption) works
    @Test
    void testSelfEncryption() {
        EphemeralKeyPair keyPair = EphemeralKeyPair.generate();

        String plaintext = "self-encrypted message";

        String encrypted = service.encrypt(plaintext, keyPair.getPrivateKeyHex(), keyPair.getPublicKey());
        String decrypted = service.decrypt(encrypted, keyPair.getPrivateKeyHex(), keyPair.getPublicKey());

        assertEquals(plaintext, decrypted);
    }

    // Verifies that different keys produce different ciphertext
    @Test
    void testDifferentKeysProduceDifferentCiphertext() {
        EphemeralKeyPair sender = EphemeralKeyPair.generate();
        EphemeralKeyPair recipient1 = EphemeralKeyPair.generate();
        EphemeralKeyPair recipient2 = EphemeralKeyPair.generate();

        String plaintext = "test message";

        String encrypted1 = service.encrypt(plaintext, sender.getPrivateKeyHex(), recipient1.getPublicKey());
        String encrypted2 = service.encrypt(plaintext, sender.getPrivateKeyHex(), recipient2.getPublicKey());

        assertNotEquals(encrypted1, encrypted2);
    }

    // Verifies that empty string encryption is rejected by NIP-44
    @Test
    void testEmptyStringEncryptionThrows() {
        EphemeralKeyPair sender = EphemeralKeyPair.generate();
        EphemeralKeyPair recipient = EphemeralKeyPair.generate();

        assertThrows(RuntimeException.class, () ->
                service.encrypt("", sender.getPrivateKeyHex(), recipient.getPublicKey()));
    }

    // Verifies that a long payload encrypts and decrypts correctly
    @Test
    void testLongPayloadEncryption() {
        EphemeralKeyPair sender = EphemeralKeyPair.generate();
        EphemeralKeyPair recipient = EphemeralKeyPair.generate();

        String plaintext = "{\"ref\":\"abc123\",\"amount\":1500,\"memo\":\"" + "A".repeat(140) + "\"}";

        String encrypted = service.encrypt(plaintext, sender.getPrivateKeyHex(), recipient.getPublicKey());
        String decrypted = service.decrypt(encrypted, recipient.getPrivateKeyHex(), sender.getPublicKey());

        assertEquals(plaintext, decrypted);
    }
}
