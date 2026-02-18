package xyz.tcheeric.payment.adapter.cash.nostr.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EphemeralKeyPairTest {

    // Verifies that generate() creates a valid key pair with non-null keys
    @Test
    void testGenerate() {
        EphemeralKeyPair keyPair = EphemeralKeyPair.generate();

        assertNotNull(keyPair.getPublicKey());
        assertNotNull(keyPair.getPublicKeyHex());
        assertNotNull(keyPair.getPrivateKeyHex());
        assertFalse(keyPair.isDisposed());
        assertFalse(keyPair.getPublicKeyHex().isEmpty());
        assertFalse(keyPair.getPrivateKeyHex().isEmpty());
    }

    // Verifies that each generate() call produces different keys
    @Test
    void testGenerateUnique() {
        EphemeralKeyPair first = EphemeralKeyPair.generate();
        EphemeralKeyPair second = EphemeralKeyPair.generate();

        assertNotEquals(first.getPublicKeyHex(), second.getPublicKeyHex());
        assertNotEquals(first.getPrivateKeyHex(), second.getPrivateKeyHex());
    }

    // Verifies that fromHex() correctly reconstructs a key pair
    @Test
    void testFromHex() {
        EphemeralKeyPair original = EphemeralKeyPair.generate();
        String pubHex = original.getPublicKeyHex();
        String privHex = original.getPrivateKeyHex();

        EphemeralKeyPair reconstructed = EphemeralKeyPair.fromHex(pubHex, privHex);

        assertEquals(pubHex, reconstructed.getPublicKeyHex());
        assertEquals(privHex, reconstructed.getPrivateKeyHex());
        assertFalse(reconstructed.isDisposed());
    }

    // Verifies that dispose() clears the private key and prevents access
    @Test
    void testDispose() {
        EphemeralKeyPair keyPair = EphemeralKeyPair.generate();
        assertFalse(keyPair.isDisposed());

        keyPair.dispose();

        assertTrue(keyPair.isDisposed());
        // Public key remains accessible
        assertNotNull(keyPair.getPublicKey());
        assertNotNull(keyPair.getPublicKeyHex());

        // Private key throws after disposal
        assertThrows(IllegalStateException.class, keyPair::getPrivateKeyHex);
    }

    // Verifies that double dispose is safe (idempotent)
    @Test
    void testDoubleDispose() {
        EphemeralKeyPair keyPair = EphemeralKeyPair.generate();
        keyPair.dispose();
        keyPair.dispose(); // Should not throw

        assertTrue(keyPair.isDisposed());
    }
}
