package xyz.tcheeric.payment.adapter.stripe.connect;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectAccountRequest;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectAccountResponse;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectRefreshRequest;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectWebhookResponse;

@RestController
@RequestMapping("/api/v1/stripe/connect")
public class StripeConnectController {

    private final StripeConnectService stripeConnectService;

    public StripeConnectController(StripeConnectService stripeConnectService) {
        this.stripeConnectService = stripeConnectService;
    }

    @PostMapping("/accounts")
    public StripeConnectAccountResponse createOrResume(@RequestBody StripeConnectAccountRequest request) {
        return stripeConnectService.createOrResume(
                request.merchantPubkey(),
                request.returnUrl(),
                request.refreshUrl());
    }

    @PostMapping("/accounts/{merchantPubkey}/refresh")
    public StripeConnectAccountResponse refresh(
            @PathVariable String merchantPubkey,
            @RequestBody StripeConnectRefreshRequest request) {
        return stripeConnectService.refresh(merchantPubkey, request.returnUrl(), request.refreshUrl());
    }

    @GetMapping("/accounts/{merchantPubkey}")
    public StripeConnectAccountResponse getStatus(@PathVariable String merchantPubkey) {
        return stripeConnectService.getStatus(merchantPubkey);
    }

    @DeleteMapping("/accounts/{merchantPubkey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable String merchantPubkey) {
        stripeConnectService.disconnect(merchantPubkey);
    }

    @PostMapping("/webhooks")
    public StripeConnectWebhookResponse handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String signatureHeader) {
        return stripeConnectService.handleWebhook(payload, signatureHeader);
    }
}
