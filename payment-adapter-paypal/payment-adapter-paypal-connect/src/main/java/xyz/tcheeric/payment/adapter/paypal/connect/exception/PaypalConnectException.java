package xyz.tcheeric.payment.adapter.paypal.connect.exception;

import org.springframework.http.HttpStatus;

/** The counterpart of {@code StripeConnectException}: a code, an HTTP status, and a message. */
public class PaypalConnectException extends RuntimeException {

    private final PaypalConnectExceptionCode code;
    private final HttpStatus status;

    public PaypalConnectException(PaypalConnectExceptionCode code, HttpStatus status, String message) {
        this(code, status, message, null);
    }

    public PaypalConnectException(PaypalConnectExceptionCode code, HttpStatus status, String message,
            Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public PaypalConnectExceptionCode getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static PaypalConnectException connectDisabled() {
        return new PaypalConnectException(PaypalConnectExceptionCode.CONNECT_DISABLED,
                HttpStatus.SERVICE_UNAVAILABLE, "PayPal seller onboarding is disabled");
    }

    public static PaypalConnectException accountNotFound(String what, Throwable cause) {
        return new PaypalConnectException(PaypalConnectExceptionCode.ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND,
                "PayPal seller not found: " + what, cause);
    }

    public static PaypalConnectException ownershipMismatch(String merchantId) {
        return new PaypalConnectException(PaypalConnectExceptionCode.OWNERSHIP_MISMATCH, HttpStatus.CONFLICT,
                "PayPal seller ownership mismatch: " + merchantId);
    }

    public static PaypalConnectException badSignature(String message, Throwable cause) {
        return new PaypalConnectException(PaypalConnectExceptionCode.WEBHOOK_SIGNATURE_INVALID,
                HttpStatus.BAD_REQUEST, message, cause);
    }

    public static PaypalConnectException apiError(String message, Throwable cause) {
        return new PaypalConnectException(PaypalConnectExceptionCode.PAYPAL_API_ERROR, HttpStatus.BAD_GATEWAY,
                message, cause);
    }
}
