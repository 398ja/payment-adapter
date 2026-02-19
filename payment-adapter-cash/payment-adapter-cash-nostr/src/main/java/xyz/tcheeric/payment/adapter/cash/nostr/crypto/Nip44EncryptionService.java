package xyz.tcheeric.payment.adapter.cash.nostr.crypto;

import lombok.extern.slf4j.Slf4j;
import nostr.base.PrivateKey;
import nostr.base.PublicKey;
import nostr.encryption.MessageCipher;
import nostr.encryption.MessageCipher44;

/**
 * Stateless service for NIP-44 encryption/decryption of cash payment payloads.
 * Wraps nostr-java's MessageCipher44 implementation.
 */
@Slf4j
public class Nip44EncryptionService {

    /**
     * Encrypt plaintext using NIP-44.
     *
     * @param plaintext  the plaintext to encrypt
     * @param senderKey  sender's private key (hex)
     * @param recipientKey recipient's public key
     * @return encrypted ciphertext
     */
    public String encrypt(String plaintext, String senderKey, PublicKey recipientKey) {
        log.debug("Encrypting payload ({} chars) for recipient={}", plaintext.length(), recipientKey);
        PrivateKey privKey = new PrivateKey(senderKey);
        MessageCipher cipher = new MessageCipher44(privKey.getRawData(), recipientKey.getRawData());
        return cipher.encrypt(plaintext);
    }

    /**
     * Decrypt ciphertext using NIP-44.
     *
     * @param ciphertext   the encrypted content
     * @param recipientKey recipient's private key (hex)
     * @param senderPubkey sender's public key
     * @return decrypted plaintext
     */
    public String decrypt(String ciphertext, String recipientKey, PublicKey senderPubkey) {
        log.debug("Decrypting payload from sender={}", senderPubkey);
        PrivateKey privKey = new PrivateKey(recipientKey);
        MessageCipher cipher = new MessageCipher44(privKey.getRawData(), senderPubkey.getRawData());
        return cipher.decrypt(ciphertext);
    }
}
