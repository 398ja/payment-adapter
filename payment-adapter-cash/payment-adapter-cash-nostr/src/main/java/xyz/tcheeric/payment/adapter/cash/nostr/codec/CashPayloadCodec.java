package xyz.tcheeric.payment.adapter.cash.nostr.codec;

import java.util.List;

/**
 * Codec interface for encoding/decoding cash payment payloads.
 * Works with NostrCashUri as the intermediate representation.
 */
public interface CashPayloadCodec {

    /**
     * Encode invoice data into a nostr+cash:// URI string.
     *
     * @param merchantPubkey merchant's ephemeral public key
     * @param ref            invoice reference
     * @param amount         amount in minor units
     * @param fiat           ISO 4217 currency code (null for satoshis)
     * @param expiresAt      Unix timestamp expiry
     * @param relays         relay URLs
     * @return encoded URI string
     */
    String encode(String merchantPubkey, String ref, Integer amount,
                  String fiat, Long expiresAt, List<String> relays);

    /**
     * Decode a nostr+cash:// URI string into a NostrCashUri.
     *
     * @param uriString the URI string to decode
     * @return parsed NostrCashUri
     */
    NostrCashUri decode(String uriString);

    /**
     * Default implementation using NostrCashUri.
     */
    static CashPayloadCodec defaultCodec() {
        return new CashPayloadCodec() {
            @Override
            public String encode(String merchantPubkey, String ref, Integer amount,
                                 String fiat, Long expiresAt, List<String> relays) {
                return NostrCashUri.builder()
                        .merchantPubkey(merchantPubkey)
                        .ref(ref)
                        .amount(amount)
                        .fiat(fiat)
                        .expiresAt(expiresAt)
                        .relays(relays)
                        .encryptionMode("nip44")
                        .version("0.2")
                        .build()
                        .encode();
            }

            @Override
            public NostrCashUri decode(String uriString) {
                return NostrCashUri.parse(uriString);
            }
        };
    }
}
