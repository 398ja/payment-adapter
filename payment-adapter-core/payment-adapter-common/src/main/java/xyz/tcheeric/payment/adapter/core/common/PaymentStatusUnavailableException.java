package xyz.tcheeric.payment.adapter.core.common;

import lombok.Getter;

/**
 * Signals that a gateway could not determine whether a quote has been paid.
 * <p>
 * This is the "unknown" answer, distinct from {@link InvoiceNotPaidException}, which is a definite
 * "not paid". A gateway throws it when the lookup itself failed for a transient reason (timeout,
 * connection reset, a 5xx from the store) so a paid quote is never reported as unpaid. It is
 * deliberately not mapped to a NUT-04 error code: cashu-mint lets it surface as a 5xx, which every
 * caller treats as "try again", never as "unpaid".
 */
@Getter
public final class PaymentStatusUnavailableException extends RuntimeException {

    private final String quoteId;

    public PaymentStatusUnavailableException(String quoteId, String message, Throwable cause) {
        super(message, cause);
        this.quoteId = quoteId;
    }
}
