package xyz.tcheeric.payment.adapter.stripe.connect.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Whether a card reference names a real, paid PaymentIntent on the asking stall's own connected
 * account.
 *
 * <p>{@code result} is one of {@code verified}, {@code refunded}, {@code disputed},
 * {@code mismatch}, {@code not_found}, {@code not_succeeded} and {@code not_connected}.
 * {@code refunded} carries {@code refunded_minor}, the amount refunded so far in minor units.
 * Unreachable Stripe is an HTTP error, never a result here, so a caller cannot mistake "could not
 * ask" for an answer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentReferenceVerification(
        @JsonProperty("result") String result,
        @JsonProperty("payment_intent_id") String paymentIntentId,
        @JsonProperty("amount_minor") Long amountMinor,
        @JsonProperty("currency") String currency,
        @JsonProperty("livemode") Boolean livemode,
        @JsonProperty("refunded_minor") Long refundedMinor,
        @JsonProperty("detail") String detail) {

    public static final String VERIFIED = "verified";
    public static final String REFUNDED = "refunded";
    public static final String DISPUTED = "disputed";
    public static final String MISMATCH = "mismatch";
    public static final String NOT_FOUND = "not_found";
    public static final String NOT_SUCCEEDED = "not_succeeded";
    public static final String NOT_CONNECTED = "not_connected";

    public static PaymentReferenceVerification of(String result, String id, String detail) {
        return new PaymentReferenceVerification(result, id, null, null, null, null, detail);
    }
}
