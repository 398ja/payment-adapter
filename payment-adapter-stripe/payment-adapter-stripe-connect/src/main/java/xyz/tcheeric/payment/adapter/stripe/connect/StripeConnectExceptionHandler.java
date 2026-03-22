package xyz.tcheeric.payment.adapter.stripe.connect;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectException;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectErrorResponse;

@RestControllerAdvice(assignableTypes = StripeConnectController.class)
public class StripeConnectExceptionHandler {

    @ExceptionHandler(StripeConnectException.class)
    public ResponseEntity<StripeConnectErrorResponse> handleStripeConnectException(StripeConnectException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new StripeConnectErrorResponse(e.getCode().name(), e.getMessage()));
    }
}
