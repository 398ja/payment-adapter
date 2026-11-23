package xyz.tcheeric.payment.adapter.paypal.connect;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import xyz.tcheeric.payment.adapter.paypal.connect.config.PaypalConnectProperties;
import xyz.tcheeric.payment.adapter.paypal.connect.exception.PaypalConnectException;
import xyz.tcheeric.payment.adapter.paypal.connect.model.PaypalConnectAccountRequest;
import xyz.tcheeric.payment.adapter.paypal.connect.model.PaypalConnectAccountResponse;
import xyz.tcheeric.payment.adapter.paypal.connect.model.PaypalConnectWebhookResponse;
import xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification;

/**
 * {@code StripeConnectController} and {@code PaymentReferenceController}, for PayPal: the same
 * routes under {@code /api/v1/paypal/connect}, the same bodies.
 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "paypal.connect", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/paypal/connect")
public class PaypalConnectController {

    private final PaypalConnectService service;
    private final PaypalPaymentReferenceVerifier verifier;
    private final PaypalConnectProperties properties;

    public PaypalConnectController(PaypalConnectService service, PaypalPaymentReferenceVerifier verifier,
            PaypalConnectProperties properties) {
        this.service = service;
        this.verifier = verifier;
        this.properties = properties;
    }

    @PostMapping("/accounts")
    public PaypalConnectAccountResponse createOrResume(@RequestBody PaypalConnectAccountRequest request) {
        return service.createOrResume(pubkey(request.merchantPubkey()), request.returnUrl());
    }

    @PostMapping("/accounts/{merchantPubkey}/refresh")
    public PaypalConnectAccountResponse refresh(@PathVariable String merchantPubkey,
            @RequestBody PaypalConnectAccountRequest request) {
        return service.refresh(pubkey(merchantPubkey), request.returnUrl());
    }

    @GetMapping("/accounts/{merchantPubkey}")
    public PaypalConnectAccountResponse getStatus(@PathVariable String merchantPubkey) {
        return service.getStatus(pubkey(merchantPubkey));
    }

    @DeleteMapping("/accounts/{merchantPubkey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable String merchantPubkey) {
        service.disconnect(pubkey(merchantPubkey));
    }

    @GetMapping("/accounts/{merchantPubkey}/payments/{reference}/verification")
    public PaymentReferenceVerification verify(@PathVariable String merchantPubkey, @PathVariable String reference) {
        if (!properties.isEnabled()) {
            throw PaypalConnectException.connectDisabled();
        }
        return verifier.verify(pubkey(merchantPubkey), reference);
    }

    /** The raw bytes, not a String: PayPal signs the CRC32 of exactly what it sent. */
    @PostMapping("/webhooks")
    public PaypalConnectWebhookResponse handleWebhook(HttpServletRequest request) throws IOException {
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        return service.handleWebhook(body, request::getHeader);
    }

    private static String pubkey(String merchantPubkey) {
        if (merchantPubkey == null || !merchantPubkey.matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "merchant pubkey must be 64 lowercase hex");
        }
        return merchantPubkey;
    }
}
