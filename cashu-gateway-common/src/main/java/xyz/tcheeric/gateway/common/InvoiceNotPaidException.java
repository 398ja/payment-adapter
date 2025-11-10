package xyz.tcheeric.gateway.common;

/**
 * Signals that the Lightning invoice backing a quote has not been paid yet.
 * <p>
 * Gateways throw this exception so upstream components (cashu-mint, CLI clients)
 * can translate it into a structured NUT-04 error instead of treating it as an
 * unexpected infrastructure failure.
 */
public final class InvoiceNotPaidException extends RuntimeException {

    private final String quoteId;

    public InvoiceNotPaidException(String quoteId, String message) {
        super(message);
        this.quoteId = quoteId;
    }

    public InvoiceNotPaidException(String quoteId, String message, Throwable cause) {
        super(message, cause);
        this.quoteId = quoteId;
    }

    public String getQuoteId() {
        return quoteId;
    }
}
