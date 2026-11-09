package xyz.tcheeric.payment.adapter.stripe.connect.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Whether a card reference names a real, paid PaymentIntent on the asking stall's own connected
 * account.
 *
 * <p>{@code result} is one of {@code verified}, {@code mismatch}, {@code not_found},
 * {@code not_succeeded} and {@code not_connected}. Unreachable Stripe is an HTTP error, never a
 * result here, so a caller cannot mistake "could not ask" for an answer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentReferenceVerification(
        @JsonProperty("result") String result,
        @JsonProperty("payment_intent_id") String paymentIntentId,
        @JsonProperty("amount_minor") Long amountMinor,
        @JsonProperty("currency") String currency,
        @JsonProperty("livemode") Boolean livemode,
        @JsonProperty("detail") String detail) {

    public static final String VERIFIED = "verified";
    public static final String MISMATCH = "mismatch";
    public static final String NOT_FOUND = "not_found";
    public static final String NOT_SUCCEEDED = "not_succeeded";
    public static final String NOT_CONNECTED = "not_connected";

    public static PaymentReferenceVerification of(String result, String id, String detail) {
        return new PaymentReferenceVerification(result, id, null, null, null, detail);
    }

    public boolean isFinal() {
        // A succeeded PaymentIntent never changes amount, currency or account, so these answers
        // can be cached forever. not_succeeded may yet succeed; not_connected may yet connect.
        return VERIFIED.equals(result) || MISMATCH.equals(result) || NOT_FOUND.equals(result);
    }
}
