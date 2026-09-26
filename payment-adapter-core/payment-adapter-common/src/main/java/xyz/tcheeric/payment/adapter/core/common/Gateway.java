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
     * Create a mint quote under an identifier the caller has already chosen.
     *
     * <p>Exists so a caller can make its own durable record of the quote <em>before</em> the
     * invoice is raised. Raising an invoice is irreversible: it is payable the moment it exists
     * and none of these gateways can withdraw it. A caller that persists after the invoice has a
     * window where a failed write leaves a payable invoice with no record behind it, so the
     * payment arrives at a mint that has already refused the request. That stranded twelve paid
     * voucher quotes on staging (cashu-mint#469). Choosing the id first lets the caller write the
     * record, and only then raise the invoice, so a failed write refuses the quote while nothing
     * is yet payable.
     *
     * <p>The default refuses rather than falling back to {@link #createMintQuote(Integer, String)}.
     * A fallback would return a gateway-generated id different from {@code quoteId}, and the
     * caller's record would then name a quote that does not exist, which is the failure this
     * method exists to prevent, moved one step later. A gateway that cannot honour the caller's
     * id must say so.
     *
     * @param quoteId the identifier to create the quote under; must be unique to the gateway
     * @param amount the amount to invoice
     * @param description the invoice description, may be {@code null}
     * @return {@code quoteId}, unchanged
     * @throws UnsupportedOperationException if this gateway cannot create a quote under a
     *     caller-supplied identifier
     */
    default String createMintQuote(String quoteId, Integer amount, String description) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " cannot create a mint quote under a caller-supplied id");
    }

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
