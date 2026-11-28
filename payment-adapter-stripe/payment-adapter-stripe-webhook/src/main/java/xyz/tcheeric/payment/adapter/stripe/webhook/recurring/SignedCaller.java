package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import java.util.List;
import java.util.Optional;
import xyz.tcheeric.payment.adapter.stripe.webhook.gifting.Nip98Verifier;

/**
 * Who signed a wallet's request: a NIP-98 {@code Authorization: Nostr ...} event bound to one of the deployment's
 * public origins ({@code purchase.state.public-origins}), the URL and the verb. The key comes from the signature and
 * nowhere else, so a caller cannot name somebody else's plans.
 */
final class SignedCaller {

    private final Nip98Verifier verifier;
    private final List<String> publicOrigins;

    SignedCaller(Nip98Verifier verifier, List<String> publicOrigins) {
        this.verifier = verifier;
        this.publicOrigins = publicOrigins == null ? List.of()
                : publicOrigins.stream().map(String::trim).filter(origin -> !origin.isEmpty())
                        .map(origin -> origin.replaceAll("/+$", "")).toList();
    }

    /** False when no public origin is configured: nothing can be verified, so every signed route answers 503. */
    boolean configured() {
        return !publicOrigins.isEmpty();
    }

    /** The signer's lower-case hex key, or empty when the header is missing, stale, or for another URL or verb. */
    Optional<String> of(String authorization, String pathAndQuery, String method) {
        for (String origin : publicOrigins) {
            Optional<String> signer = verifier.verify(authorization, origin + pathAndQuery, method, null);
            if (signer.isPresent()) {
                return signer.map(String::toLowerCase);
            }
        }
        return Optional.empty();
    }
}
