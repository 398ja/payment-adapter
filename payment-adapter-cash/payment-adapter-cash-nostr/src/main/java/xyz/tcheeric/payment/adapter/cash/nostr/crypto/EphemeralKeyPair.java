package xyz.tcheeric.payment.adapter.cash.nostr.crypto;

import lombok.extern.slf4j.Slf4j;
import nostr.base.PublicKey;
import nostr.id.Identity;

/**
 * Ephemeral key pair for NIP-XX Cash Payments.
 * Wraps nostr-java Identity to provide disposable keys per invoice.
 * Keys should be disposed after the invoice reaches a terminal state.
 */
@Slf4j
public class EphemeralKeyPair {

    private final PublicKey publicKey;
    private volatile String privateKeyHex;
    private volatile boolean disposed;

    private EphemeralKeyPair(PublicKey publicKey, String privateKeyHex) {
        this.publicKey = publicKey;
        this.privateKeyHex = privateKeyHex;
        this.disposed = false;
    }

    /**
     * Generate a new random ephemeral key pair.
     *
     * @return new EphemeralKeyPair
     */
    public static EphemeralKeyPair generate() {
        Identity identity = Identity.generateRandomIdentity();
        PublicKey pubKey = identity.getPublicKey();
        String privKeyHex = identity.getPrivateKey().toString();
        log.debug("Generated ephemeral key pair: pubkey={}", pubKey);
        return new EphemeralKeyPair(pubKey, privKeyHex);
    }

    /**
     * Reconstruct an EphemeralKeyPair from stored hex values.
     *
     * @param pubkeyHex  hex-encoded public key
     * @param privkeyHex hex-encoded private key
     * @return reconstructed EphemeralKeyPair
     */
    public static EphemeralKeyPair fromHex(String pubkeyHex, String privkeyHex) {
        PublicKey pubKey = new PublicKey(pubkeyHex);
        return new EphemeralKeyPair(pubKey, privkeyHex);
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public String getPublicKeyHex() {
        return publicKey.toString();
    }

    /**
     * Get the private key hex. Throws if disposed.
     *
     * @return hex-encoded private key
     * @throws IllegalStateException if key has been disposed
     */
    public String getPrivateKeyHex() {
        if (disposed) {
            throw new IllegalStateException("Ephemeral key pair has been disposed");
        }
        return privateKeyHex;
    }

    /**
     * Dispose of the private key material.
     * After calling this, the private key is no longer accessible.
     */
    public void dispose() {
        if (!disposed) {
            this.privateKeyHex = null;
            this.disposed = true;
            log.debug("Disposed ephemeral key pair: pubkey={}", publicKey);
        }
    }

    public boolean isDisposed() {
        return disposed;
    }
}
