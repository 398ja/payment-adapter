package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The previous-key allowance of a gateway key rollover (payment-adapter#288): what it accepts, and
 * the ways it refuses to keep an old key's authority open.
 */
class PreviousGatewayKeysTest {

    private static final Instant NOW = Instant.parse("2026-10-23T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String OLD_PRIVATE = "1".repeat(64);
    private static final String OLD = pubkeyOf(OLD_PRIVATE);
    private static final String OTHER = pubkeyOf("2".repeat(64));
    private static final String CURRENT_PRIVATE = "3".repeat(64);
    private static final String UNTIL = NOW.plus(Duration.ofDays(15)).toString();

    static String pubkeyOf(String privateHex) {
        try {
            return HexFormat.of().formatHex(Schnorr.genPubKey(HexFormat.of().parseHex(privateHex)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // A configured previous key, inside its window, is recognised as this service's own.
    @Test
    void aPreviousKeyInsideItsWindowIsRecognised() {
        PreviousGatewayKeys keys = PreviousGatewayKeys.parse(OLD, UNTIL, CURRENT_PRIVATE, CLOCK);

        assertTrue(keys.inForce());
        assertTrue(keys.recognises(OLD));
        assertFalse(keys.recognises(OTHER), "a key nobody configured is still a stranger");
    }

    // Several keys, with spaces and upper case, as an operator might paste them.
    @Test
    void aListOfKeysIsTrimmedAndCaseFolded() {
        PreviousGatewayKeys keys = PreviousGatewayKeys.parse(" " + OLD.toUpperCase() + " , " + OTHER + ",",
                UNTIL, CURRENT_PRIVATE, CLOCK);

        assertTrue(keys.recognises(OLD));
        assertTrue(keys.recognises(OTHER));
    }

    // gc#255 F1: a 02- or 03-prefixed compressed key is the same authority as its x coordinate, in
    // the configuration and in core's answer alike.
    @Test
    void parityPrefixedVariantsCompareAsTheirXOnlyKey() {
        PreviousGatewayKeys fromCompressed = PreviousGatewayKeys.parse("03" + OLD, UNTIL, CURRENT_PRIVATE, CLOCK);

        assertTrue(fromCompressed.recognises(OLD));
        assertTrue(fromCompressed.recognises("02" + OLD));
        assertTrue(fromCompressed.recognises("03" + OLD.toUpperCase()));
        assertFalse(fromCompressed.recognises("04" + OLD), "04 is not a compressed key's parity byte");
        assertFalse(fromCompressed.recognises("02" + OTHER));
    }

    // A 64-hex key that merely begins with 02 is already x-only: stripping it would compare a
    // different (and shorter) value, so it is never stripped.
    @Test
    void aSixtyFourHexKeyStartingWithZeroTwoIsNotStripped() {
        String startsWith02 = "02" + OLD.substring(2);

        assertEquals(startsWith02, XOnlyKey.of(startsWith02));
        assertFalse(XOnlyKey.same(startsWith02, OLD.substring(2)));
    }

    // Once the window ends the old key is a stranger again, without anybody editing the config.
    @Test
    void anExpiredWindowRecognisesNothing() {
        PreviousGatewayKeys keys = PreviousGatewayKeys.parse(OLD, NOW.minusSeconds(1).toString(), CURRENT_PRIVATE,
                CLOCK);

        assertFalse(keys.inForce());
        assertFalse(keys.recognises(OLD));
    }

    // The end is exclusive: at exactly `until` the key no longer counts.
    @Test
    void theWindowEndsAtItsInstant() {
        PreviousGatewayKeys keys = PreviousGatewayKeys.parse(OLD, NOW.toString(), CURRENT_PRIVATE, CLOCK);

        assertFalse(keys.recognises(OLD));
    }

    // The window closes while the service runs: no restart or config edit is needed for the old
    // key to lose its authority.
    @Test
    void theWindowClosesWithoutARestart() {
        Instant[] now = {NOW};
        Clock moving = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        PreviousGatewayKeys keys = PreviousGatewayKeys.parse(OLD, NOW.plus(Duration.ofHours(1)).toString(),
                CURRENT_PRIVATE, moving);
        assertTrue(keys.recognises(OLD));

        now[0] = NOW.plus(Duration.ofHours(1));

        assertFalse(keys.recognises(OLD));
    }

    // A previous key with no end would keep a leaked key's authority for ever, so it is refused.
    @Test
    void aPreviousKeyWithoutAnEndIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PreviousGatewayKeys.parse(OLD, "", CURRENT_PRIVATE, CLOCK));
        assertThrows(IllegalArgumentException.class,
                () -> PreviousGatewayKeys.parse(OLD, null, CURRENT_PRIVATE, CLOCK));
    }

    // An end further out than any old row can still be asked about is a mistake, not a rollover.
    @Test
    void anEndBeyondTheMaximumWindowIsRefused() {
        String tooFar = NOW.plus(PreviousGatewayKeys.MAX_WINDOW).plusSeconds(1).toString();

        assertThrows(IllegalArgumentException.class,
                () -> PreviousGatewayKeys.parse(OLD, tooFar, CURRENT_PRIVATE, CLOCK));
        PreviousGatewayKeys atTheLimit = PreviousGatewayKeys.parse(OLD,
                NOW.plus(PreviousGatewayKeys.MAX_WINDOW).toString(), CURRENT_PRIVATE, CLOCK);
        assertTrue(atTheLimit.recognises(OLD));
    }

    // An end that is not an instant is refused rather than read as "no end".
    @Test
    void anUnparseableEndIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PreviousGatewayKeys.parse(OLD, "next tuesday", CURRENT_PRIVATE, CLOCK));
    }

    // Malformed entries, and an x that is not on secp256k1, are refused rather than skipped.
    @Test
    void aMalformedOrOffCurveKeyIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PreviousGatewayKeys.parse("abc", UNTIL, CURRENT_PRIVATE, CLOCK));
        assertThrows(IllegalArgumentException.class,
                () -> PreviousGatewayKeys.parse("04" + OLD, UNTIL, CURRENT_PRIVATE, CLOCK));
        // x = 5 has no y on secp256k1.
        assertThrows(IllegalArgumentException.class,
                () -> PreviousGatewayKeys.parse("0".repeat(63) + "5", UNTIL, CURRENT_PRIVATE, CLOCK));
    }

    // The current PRIVATE key pasted into the pubkey list is refused, and the message never echoes it.
    @Test
    void theCurrentPrivateKeyIsNeverAcceptedAsAPubkey() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PreviousGatewayKeys.parse(CURRENT_PRIVATE, UNTIL, CURRENT_PRIVATE, CLOCK));

        assertFalse(refused.getMessage().contains(CURRENT_PRIVATE));
        assertTrue(refused.getMessage().contains("PRIVATE"));
    }

    // Nothing configured is the ordinary case: no window, nothing recognised, no end required.
    @Test
    void nothingConfiguredRecognisesNothing() {
        PreviousGatewayKeys keys = PreviousGatewayKeys.parse("  ", "", CURRENT_PRIVATE, CLOCK);

        assertFalse(keys.inForce());
        assertFalse(keys.recognises(OLD));
        assertNull(keys.until());
        assertFalse(PreviousGatewayKeys.none().recognises(OLD));
    }
}
