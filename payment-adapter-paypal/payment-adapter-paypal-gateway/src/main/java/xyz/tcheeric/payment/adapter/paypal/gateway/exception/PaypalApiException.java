package xyz.tcheeric.payment.adapter.paypal.gateway.exception;

/**
 * PayPal answered a call with an error, or could not be reached.
 *
 * <p>{@link #status()} is the HTTP status, or 0 when no answer came back at all. {@link #issue()} is
 * PayPal's machine-readable reason ({@code details[0].issue}, e.g. {@code ORDER_ALREADY_CAPTURED},
 * {@code INSTRUMENT_DECLINED}), or its top-level {@code name} when there are no details.
 */
public class PaypalApiException extends PaypalGatewayException {

    private final int status;
    private final String issue;
    private final String debugId;

    public PaypalApiException(String message, int status, String issue, String debugId, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.issue = issue;
        this.debugId = debugId;
    }

    public int status() {
        return status;
    }

    public String issue() {
        return issue;
    }

    /** PayPal's {@code debug_id}, what PayPal support asks for. */
    public String debugId() {
        return debugId;
    }

    /**
     * PayPal could not be reached or failed on its side: no answer, a 5xx, or a 429. The buyer, the
     * webhook or the reconciler can retry shortly. Anything else is a refusal of the request itself.
     */
    public boolean isUnavailable() {
        return status == 0 || status >= 500 || status == 429;
    }

    public boolean isRateLimited() {
        return status == 429;
    }

    public boolean isNotFound() {
        return status == 404;
    }

    public boolean hasIssue(String expected) {
        return expected.equalsIgnoreCase(issue);
    }
}
