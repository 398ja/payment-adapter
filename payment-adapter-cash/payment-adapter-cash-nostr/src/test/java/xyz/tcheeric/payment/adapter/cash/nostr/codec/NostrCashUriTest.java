package xyz.tcheeric.payment.adapter.cash.nostr.codec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class NostrCashUriTest {

    // Verifies that encoding produces a valid nostr+cash URI with all required params
    @Test
    void testEncodeBasicUri() {
        NostrCashUri uri = NostrCashUri.builder()
                .merchantPubkey("02abc123")
                .ref("6f2c1d")
                .amount(1500)
                .fiat("USD")
                .expiresAt(1712345900L)
                .relays(List.of("wss://relay1.example", "wss://relay2.example"))
                .encryptionMode("nip44")
                .version("0.2")
                .build();

        String encoded = uri.encode();

        assertTrue(encoded.startsWith("nostr+cash://pay?"));
        assertTrue(encoded.contains("k=02abc123"));
        assertTrue(encoded.contains("ref=6f2c1d"));
        assertTrue(encoded.contains("amt=1500"));
        assertTrue(encoded.contains("fiat=USD"));
        assertTrue(encoded.contains("exp=1712345900"));
        assertTrue(encoded.contains("enc=nip44"));
        assertTrue(encoded.contains("v=0.2"));
        // Relay URLs should be URL-encoded
        assertTrue(encoded.contains("r=wss"));
    }

    // Verifies that a valid URI can be parsed back to its original values
    @Test
    void testParseValidUri() {
        String uriStr = "nostr+cash://pay?k=02abc123&ref=6f2c1d&amt=1500&fiat=USD&exp=1712345900&r=wss%3A%2F%2Frelay1.example&r=wss%3A%2F%2Frelay2.example&enc=nip44&v=0.2";

        NostrCashUri parsed = NostrCashUri.parse(uriStr);

        assertEquals("02abc123", parsed.getMerchantPubkey());
        assertEquals("6f2c1d", parsed.getRef());
        assertEquals(1500, parsed.getAmount());
        assertEquals("USD", parsed.getFiat());
        assertEquals(1712345900L, parsed.getExpiresAt());
        assertEquals(2, parsed.getRelays().size());
        assertEquals("wss://relay1.example", parsed.getRelays().get(0));
        assertEquals("nip44", parsed.getEncryptionMode());
        assertEquals("0.2", parsed.getVersion());
    }

    // Verifies encode-then-parse roundtrip preserves all fields
    @Test
    void testRoundTrip() {
        NostrCashUri original = NostrCashUri.builder()
                .merchantPubkey("02abc123def456")
                .ref("aabb11")
                .amount(500)
                .fiat("EUR")
                .expiresAt(1700000000L)
                .relays(List.of("wss://relay.test"))
                .encryptionMode("nip44")
                .version("0.2")
                .build();

        NostrCashUri parsed = NostrCashUri.parse(original.encode());

        assertEquals(original.getMerchantPubkey(), parsed.getMerchantPubkey());
        assertEquals(original.getRef(), parsed.getRef());
        assertEquals(original.getAmount(), parsed.getAmount());
        assertEquals(original.getFiat(), parsed.getFiat());
        assertEquals(original.getExpiresAt(), parsed.getExpiresAt());
        assertEquals(original.getRelays(), parsed.getRelays());
    }

    // Verifies that satoshi amounts work without a fiat tag
    @Test
    void testEncodeWithoutFiat() {
        NostrCashUri uri = NostrCashUri.builder()
                .merchantPubkey("02abc")
                .ref("aabb")
                .amount(21000)
                .expiresAt(1700000000L)
                .relays(List.of("wss://relay.test"))
                .build();

        String encoded = uri.encode();

        assertFalse(encoded.contains("fiat="));
        assertTrue(encoded.contains("amt=21000"));
    }

    // Verifies parsing fails with missing required parameter
    @Test
    void testParseMissingRequiredField() {
        String noRef = "nostr+cash://pay?k=02abc&amt=100&exp=1700000000&r=wss%3A%2F%2Frelay.test";

        assertThrows(IllegalArgumentException.class, () -> NostrCashUri.parse(noRef));
    }

    // Verifies parsing fails with invalid URI format
    @Test
    void testParseInvalidFormat() {
        assertThrows(IllegalArgumentException.class, () -> NostrCashUri.parse("https://example.com"));
        assertThrows(IllegalArgumentException.class, () -> NostrCashUri.parse(""));
        assertThrows(IllegalArgumentException.class, () -> NostrCashUri.parse(null));
    }

    // Verifies that gift wrap flag is encoded properly
    @Test
    void testEncodeGiftWrap() {
        NostrCashUri uri = NostrCashUri.builder()
                .merchantPubkey("02abc")
                .ref("aabb")
                .amount(100)
                .expiresAt(1700000000L)
                .relays(List.of("wss://relay.test"))
                .giftWrap(true)
                .build();

        assertTrue(uri.encode().contains("wrap=1"));
    }

    // Verifies that location hash is encoded and parsed
    @Test
    void testLocationHash() {
        NostrCashUri uri = NostrCashUri.builder()
                .merchantPubkey("02abc")
                .ref("aabb")
                .amount(100)
                .expiresAt(1700000000L)
                .relays(List.of("wss://relay.test"))
                .locationHash("e3b0c44298fc")
                .build();

        String encoded = uri.encode();
        assertTrue(encoded.contains("h=e3b0c44298fc"));

        NostrCashUri parsed = NostrCashUri.parse(encoded);
        assertEquals("e3b0c44298fc", parsed.getLocationHash());
    }

    // Verifies expiry detection
    @Test
    void testIsExpired() {
        NostrCashUri expired = NostrCashUri.builder()
                .merchantPubkey("02abc")
                .ref("aabb")
                .amount(100)
                .expiresAt(1000L)
                .relays(List.of("wss://relay.test"))
                .build();

        assertTrue(expired.isExpired());

        NostrCashUri notExpired = NostrCashUri.builder()
                .merchantPubkey("02abc")
                .ref("aabb")
                .amount(100)
                .expiresAt(System.currentTimeMillis() / 1000 + 3600)
                .relays(List.of("wss://relay.test"))
                .build();

        assertFalse(notExpired.isExpired());
    }

    // Verifies toString returns the encoded URI
    @Test
    void testToStringReturnsEncodedUri() {
        NostrCashUri uri = NostrCashUri.builder()
                .merchantPubkey("02abc")
                .ref("aabb")
                .amount(100)
                .expiresAt(1700000000L)
                .relays(List.of("wss://relay.test"))
                .build();

        assertEquals(uri.encode(), uri.toString());
    }
}
