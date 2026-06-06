package xyz.tcheeric.payment.adapter.core.common;

import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.entities.annotation.Supports;

import java.time.Instant;
import java.util.Arrays;

public interface Gateway {

    /**
     * Create a quote for minting a certain amount
     * @param amount
     * @param description
     * @return
     */
    String createMintQuote(Integer amount, String description);

    /**
     * Create a quote for melting a certain amount
     * @param amount
     * @param request
     * @param description
     * @return
     */
    String createMeltQuote(Integer amount, String request, String description);

    /**
     * Create a quote for melting, based on the request
     * @param request
     * @return
     */
    String createMeltQuote(String request);

    /**
     * Get the request for a payment
     * @param quoteId
     * @return
     */
    String getRequest(String quoteId);

    /**
     * Check the payment status
     * @param request
     * @return
     */
    boolean checkPaymentStatus(String request);

    /**
     * Get the payment preimage
     * @param request
     * @return
     */
    String getPaymentPreimage(String request);

    /**
     * Make the payment (melt)
     * @param request
     * @return
     */
    String pay(String request);

    /**
     * Get the amount of a quote
     * @param quoteId
     * @return
     */
    Integer getAmount(String quoteId);

    /**
     * Get the expiry of a payment
     * @param quoteId
     * @return
     */
    Integer getPaymentExpiry(String quoteId);

    /**
     * Get the creation timestamp of a quote.
     *
     * <p>Spec 041 (cashu-mint REQ-MINT-3): the mint enforces strict quote
     * expiry by computing {@code createdAt + getPaymentExpiry(quoteId)}
     * and rejecting requests past that instant. The default implementation
     * returns {@code null} so legacy gateway impls that haven't been
     * updated continue to compile; callers MUST treat null as "creation
     * time unknown — skip enforcement" and fall through to the existing
     * permissive behaviour.
     *
     * @param quoteId the quote identifier
     * @return the {@link Instant} when the quote was created, or
     *         {@code null} when the gateway does not track creation
     *         time for this quote
     */
    default Instant getCreatedAt(String quoteId) {
        return null;
    }

    /**
     * Get the fee reserve of a payment
     * @param request
     * @return
     */
    Integer getFeeReserve(String request);

    String getName();

    /**
     * Get the payment type this gateway handles
     * @return the PaymentType
     */
    PaymentType getPaymentType();

    /**
     * Get the unique identifier for this gateway instance
     * @return the gateway ID (e.g., "phoenixd", "dummy")
     */
    String getGatewayId();

    /**
     * Test if the gateway supports the given payment method
     * @param method
     * @return
     */
    default boolean supports(PaymentMethod method) {
        Supports supports = this.getClass().getAnnotation(Supports.class);
        if (supports != null) {
            return Arrays.asList(supports.value()).contains(method);
        }
        return false;
    }
}
