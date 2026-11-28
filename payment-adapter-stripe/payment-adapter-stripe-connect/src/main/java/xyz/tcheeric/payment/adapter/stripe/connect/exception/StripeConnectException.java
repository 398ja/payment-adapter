package xyz.tcheeric.payment.adapter.stripe.connect.exception;

import org.springframework.http.HttpStatus;

public class StripeConnectException extends RuntimeException {

    private final StripeConnectExceptionCode code;
    private final HttpStatus status;

    public StripeConnectException(StripeConnectExceptionCode code, String message) {
        this(code, HttpStatus.BAD_REQUEST, message, null);
    }

    public StripeConnectException(StripeConnectExceptionCode code, String message, Throwable cause) {
        this(code, HttpStatus.BAD_REQUEST, message, cause);
    }

    public StripeConnectException(StripeConnectExceptionCode code, HttpStatus status, String message) {
        this(code, status, message, null);
    }

    public StripeConnectException(StripeConnectExceptionCode code, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public StripeConnectExceptionCode getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static StripeConnectException connectDisabled() {
        return new StripeConnectException(
                StripeConnectExceptionCode.CONNECT_DISABLED,
                HttpStatus.SERVICE_UNAVAILABLE,
                "Stripe Connect is disabled");
    }

    public static StripeConnectException accountNotFound(String stripeAccountId, Throwable cause) {
        return new StripeConnectException(
                StripeConnectExceptionCode.ACCOUNT_NOT_FOUND,
                HttpStatus.NOT_FOUND,
                "Stripe account not found: " + stripeAccountId,
                cause);
    }

    public static StripeConnectException ownershipMismatch(String stripeAccountId) {
        return new StripeConnectException(
                StripeConnectExceptionCode.OWNERSHIP_MISMATCH,
                HttpStatus.CONFLICT,
                "Stripe account ownership mismatch: " + stripeAccountId);
    }

    public static StripeConnectException oauthDisabled() {
        return new StripeConnectException(
                StripeConnectExceptionCode.OAUTH_DISABLED,
                HttpStatus.SERVICE_UNAVAILABLE,
                "Connecting an existing Stripe account is not configured");
    }

    /** One answer for unknown, expired and already-used state: telling them apart would help only an attacker. */
    public static StripeConnectException oauthStateInvalid() {
        return new StripeConnectException(
                StripeConnectExceptionCode.OAUTH_STATE_INVALID,
                HttpStatus.BAD_REQUEST,
                "The connection request is invalid, expired or already used. Start again.");
    }

    public static StripeConnectException oauthCodeRejected(Throwable cause) {
        return new StripeConnectException(
                StripeConnectExceptionCode.OAUTH_CODE_REJECTED,
                HttpStatus.BAD_REQUEST,
                "Stripe rejected the authorization code. Start again.",
                cause);
    }

    public static StripeConnectException merchantAlreadyLinked(String message) {
        return new StripeConnectException(
                StripeConnectExceptionCode.MERCHANT_ALREADY_LINKED,
                HttpStatus.CONFLICT,
                message);
    }

    public static StripeConnectException apiError(String message, Throwable cause) {
        return new StripeConnectException(
                StripeConnectExceptionCode.STRIPE_API_ERROR,
                HttpStatus.BAD_GATEWAY,
                message,
                cause);
    }
}
