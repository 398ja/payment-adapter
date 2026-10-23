package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import nostr.crypto.Point;

/**
 * The public keys this service registered purchases under BEFORE its current key, during a key
 * rollover (payment-adapter#288, option 1).
 *
 * <h2>Why</h2>
 *
 * <p>gateway-core records the NIP-98 key that registered a payment request as its
 * {@code creatorPubkey}, and {@link PurchaseDischargeService#mismatch} refuses a verdict whose terms
 * this service did not register. Rotating {@code PURCHASE_GATEWAY_PRIVATE_KEY} therefore stranded
 * every row registered under the old key: ISSUED for ever, until the 14-day re-check horizon. A
 * forced rotation after a leak must not pause card sales or strand paying customers, so the old
 * PUBLIC key may be named here and its registrations are recognised as this service's own.
 *
 * <h2>Why nothing is needed from gateway-core</h2>
 *
 * <p>The only call made about an already-registered row is {@code GET /fulfilment/{id}}, and core
 * answers it for any NIP-98 key: the verdict is not scoped to the creator. The new key reads a
 * binding the old key made. The PUT, whose {@code sameTermsAs} does include the creator, is only
 * sent for a new row or one core reports {@code unregistered}, so a binding the old key holds is
 * never re-registered, and never 409s.
 *
 * <h2>Bounded authority</h2>
 *
 * <p>Public keys only: this service never holds an old private key. A previous key is honoured only
 * until {@code until}, which is REQUIRED when any key is named and may be at most
 * {@link #MAX_WINDOW} ahead when the service starts. The window outlasts the re-check horizon by a
 * week, which is the longest any old row can still be asked about. After it, or once an operator
 * removes the key from the configuration, the old key is a stranger again. A row's own buyer,
 * amount, unit, stall and coupon are still checked against the answer as before, so a previous key
 * only ever answers "who registered these terms", never what they are.
 *
 * <h2>Only rows that already existed</h2>
 *
 * <p>A previous key only vouches for a row recorded no later than {@link #adoptedAt()}, the moment
 * this process read the rollover (its boot). Every row recorded after that was registered by the
 * CURRENT key, so core naming the old key as its creator means somebody holding the old key
 * registered it first: a leaked old key must not be able to squat rows created after the
 * rotation and have them read as ours (review of #303). A restart during the window moves this
 * cutoff later, never earlier; removing the variables ends the allowance.
 */
@Slf4j
public final class PreviousGatewayKeys {

    /** How far ahead the grace window may end: the re-check horizon (14 days) plus a week of slack. */
    static final Duration MAX_WINDOW = PurchaseDischargeService.RECHECK_HORIZON.plus(Duration.ofDays(7));

    private static final PreviousGatewayKeys NONE =
            new PreviousGatewayKeys(Set.of(), null, Instant.EPOCH, Clock.systemUTC());

    private final Set<String> keys;
    private final Instant until;
    private final Instant adoptedAt;
    private final Clock clock;

    private PreviousGatewayKeys(Set<String> keys, Instant until, Instant adoptedAt, Clock clock) {
        this.keys = keys;
        this.until = until;
        this.adoptedAt = adoptedAt;
        this.clock = clock;
    }

    /** No previous keys: only the current key's registrations are this service's. */
    public static PreviousGatewayKeys none() {
        return NONE;
    }

    /**
     * Parse the configuration, refusing anything unsafe rather than guessing.
     *
     * @param rawKeys          {@code PURCHASE_GATEWAY_PREVIOUS_PUBKEYS}: comma-separated x-only hex (a
     *                         02/03-prefixed compressed key is accepted and reduced to its x)
     * @param rawUntil         {@code PURCHASE_GATEWAY_PREVIOUS_PUBKEYS_UNTIL}: an ISO-8601 instant
     * @param currentPrivateKey the current private key, only to refuse it being pasted here by mistake
     * @throws IllegalArgumentException on a malformed or off-curve key, a missing, unparseable or
     *                                  too distant end, or the current private key named as a pubkey
     */
    public static PreviousGatewayKeys parse(String rawKeys, String rawUntil, String currentPrivateKey, Clock clock) {
        Set<String> keys = parseKeys(rawKeys, currentPrivateKey);
        if (keys.isEmpty()) {
            return new PreviousGatewayKeys(Set.of(), null, clock.instant(), clock);
        }
        Instant until = parseUntil(rawUntil, clock);
        PreviousGatewayKeys parsed = new PreviousGatewayKeys(Set.copyOf(keys), until, clock.instant(), clock);
        if (parsed.inForce()) {
            log.warn("Purchase gateway key rollover: {} previous public key(s) {} recognised until {}, "
                    + "for purchases recorded no later than {}",
                    keys.size(), keys.stream().map(XOnlyKey::prefix).toList(), until, parsed.adoptedAt);
        } else {
            log.warn("Purchase gateway key rollover window ended at {}; {} previous key(s) ignored. "
                    + "Remove PURCHASE_GATEWAY_PREVIOUS_PUBKEYS.", until, keys.size());
        }
        return parsed;
    }

    private static Set<String> parseKeys(String raw, String currentPrivateKey) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        String privateX = currentPrivateKey == null ? null : currentPrivateKey.trim().toLowerCase();
        Set<String> keys = new LinkedHashSet<>();
        List<String> entries = Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        for (int i = 0; i < entries.size(); i++) {
            String key = XOnlyKey.of(entries.get(i));
            if (key == null) {
                throw new IllegalArgumentException("PURCHASE_GATEWAY_PREVIOUS_PUBKEYS entry " + (i + 1)
                        + " is not a 64-hex x-only public key");
            }
            if (key.equals(privateX)) {
                throw new IllegalArgumentException("PURCHASE_GATEWAY_PREVIOUS_PUBKEYS entry " + (i + 1)
                        + " is the current PRIVATE key: name public keys only");
            }
            if (!onCurve(key)) {
                throw new IllegalArgumentException("PURCHASE_GATEWAY_PREVIOUS_PUBKEYS entry " + (i + 1)
                        + " is not a point on secp256k1");
            }
            keys.add(key);
        }
        return keys;
    }

    private static boolean onCurve(String xOnly) {
        try {
            return Point.liftX(HexFormat.of().parseHex(xOnly)) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Instant parseUntil(String raw, Clock clock) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("PURCHASE_GATEWAY_PREVIOUS_PUBKEYS needs "
                    + "PURCHASE_GATEWAY_PREVIOUS_PUBKEYS_UNTIL (an ISO-8601 instant): a previous key never "
                    + "keeps its authority without an end");
        }
        Instant until;
        try {
            until = Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("PURCHASE_GATEWAY_PREVIOUS_PUBKEYS_UNTIL is not an ISO-8601 instant "
                    + "(e.g. 2026-11-06T00:00:00Z)", e);
        }
        if (until.isAfter(clock.instant().plus(MAX_WINDOW))) {
            throw new IllegalArgumentException("PURCHASE_GATEWAY_PREVIOUS_PUBKEYS_UNTIL is more than "
                    + MAX_WINDOW.toDays() + " days ahead: no old row is asked about after the re-check horizon");
        }
        return until;
    }

    /** Whether any previous key is honoured right now. */
    public boolean inForce() {
        return !keys.isEmpty() && until != null && clock.instant().isBefore(until);
    }

    /**
     * Whether {@code creatorPubkey} is one of this service's previous keys, inside the window.
     * Compared x-only, so a parity-prefixed variant of the same key matches.
     */
    public boolean recognises(String creatorPubkey) {
        String key = XOnlyKey.of(creatorPubkey);
        return key != null && inForce() && keys.contains(key);
    }

    /**
     * Whether {@code creatorPubkey} vouches for a row recorded at {@code recordedAt}: one of the
     * previous keys, inside the window, and the row existed when the rollover was adopted. A row
     * recorded later was registered by the current key, so an old-key creator on it is a squatter.
     * An unknown recording time is refused.
     */
    public boolean recognisesFor(String creatorPubkey, Instant recordedAt) {
        return recordedAt != null && !recordedAt.isAfter(adoptedAt) && recognises(creatorPubkey);
    }

    /** When this process adopted the rollover: rows recorded later are never vouched for by an old key. */
    public Instant adoptedAt() {
        return adoptedAt;
    }

    /** When the window ends, or null when no key is configured. */
    public Instant until() {
        return until;
    }
}
