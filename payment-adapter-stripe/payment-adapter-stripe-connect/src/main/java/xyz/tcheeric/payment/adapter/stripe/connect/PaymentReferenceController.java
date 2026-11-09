package xyz.tcheeric.payment.adapter.stripe.connect;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.payment.adapter.stripe.connect.config.StripeConnectProperties;
import xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectException;
import xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification;

/**
 * Card reference verification, beside the account endpoints and addressed the same way: by the
 * stall's pubkey, which merchant-service fills from the authenticated session and never from the
 * browser.
 */
@RestController
@RequestMapping("/api/v1/stripe/connect")
public class PaymentReferenceController {

    private final PaymentReferenceVerifier verifier;
    private final StripeConnectProperties connectProperties;

    public PaymentReferenceController(PaymentReferenceVerifier verifier, StripeConnectProperties connectProperties) {
        this.verifier = verifier;
        this.connectProperties = connectProperties;
    }

    @GetMapping("/accounts/{merchantPubkey}/payments/{reference}/verification")
    public PaymentReferenceVerification verify(@PathVariable String merchantPubkey, @PathVariable String reference) {
        if (!connectProperties.isEnabled()) {
            throw StripeConnectException.connectDisabled();
        }
        if (merchantPubkey == null || !merchantPubkey.matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "merchant pubkey must be 64 lowercase hex");
        }
        return verifier.verify(merchantPubkey, reference);
    }
}
