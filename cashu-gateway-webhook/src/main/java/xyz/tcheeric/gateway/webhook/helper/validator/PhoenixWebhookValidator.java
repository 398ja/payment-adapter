package xyz.tcheeric.gateway.webhook.helper.validator;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.gateway.client.PaymentClient;
import xyz.tcheeric.gateway.client.QuoteClient;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;
import xyz.tcheeric.gateway.model.entity.enums.Direction;
import xyz.tcheeric.gateway.model.entity.enums.State;
import xyz.tcheeric.gateway.webhook.helper.PhoenixdWebhookRequest;


@Slf4j
public class PhoenixWebhookValidator extends BaseWebhookValidator {

    public PhoenixWebhookValidator(PhoenixdWebhookRequest webhookRequest) {
        super(webhookRequest);
    }

    /**
     * Validates the incoming phoenixd webhook request.
     * <p>
     * The validation performs the following steps:
     * <ul>
     *     <li>Fetch the quote identified by the webhook's external id and ensure it exists and is
     *     meant for receiving funds.</li>
     *     <li>Look up the payment linked to the quote and verify it exists.</li>
     *     <li>Confirm the provided payment hash and amount match the stored payment values.</li>
     *     <li>Verify the payment is marked as {@link State#PAID} and the webhook type is
     *     {@code payment_received}.</li>
     * </ul>
     *
     * @return the validated {@link GatewayPayment}
     * @throws IllegalArgumentException if any validation step fails
     */
    @Override
    public GatewayPayment validate() {

        PhoenixdWebhookRequest webhookRequest = (PhoenixdWebhookRequest) getWebhookRequest();

        String type = webhookRequest.getType();
        Integer amountSat = webhookRequest.getAmountSat();
        String paymentHash = webhookRequest.getPaymentHash();
        String externalId = webhookRequest.getExternalId();

        log.info("Received webhook: type={}, amountSat={}, paymentHash={}, externalId={}",
                type, amountSat, paymentHash, externalId);

        // Find the quote
        QuoteClient quoteClient = new QuoteClient();
        GatewayQuote quote = quoteClient.getByInvoiceId(externalId);

        if (quote == null) {
            log.warn("Quote not found: externalId={}", externalId);
            throw new IllegalArgumentException("Quote not found");
        }

        if (quote.getDirection() != Direction.RECEIVE) {
            log.warn("Invalid quote direction: direction={}", quote.getDirection());
            throw new IllegalArgumentException("Invalid quote direction");
        }

        // Find the payment
        PaymentClient paymentClient = new PaymentClient();
        GatewayPayment payment = paymentClient.getByQuoteId(quote.getQuoteId());

        // Validate the payment
        if (payment == null) {
            log.warn("Payment not found: lnInvoice={}", quote.getRequest());
            throw new IllegalArgumentException("Payment not found");
        }

        if (paymentHash != null && !paymentHash.equals(payment.getPaymentHash())) {
            log.warn("Payment hash mismatch: paymentHash={}, expected={}", paymentHash, payment.getPaymentHash());
            throw new IllegalArgumentException("Payment hash mismatch");
        }

        if (amountSat != null && !amountSat.equals(payment.getAmount())) {
            log.warn("Amount mismatch: amountSat={}, expected={}", amountSat, payment.getAmount());
            throw new IllegalArgumentException("Amount mismatch");
        }

        if(payment.getState() == null || payment.getState() != State.PAID) {
            log.warn("Invalid payment state: state={}", payment.getState());
            throw new IllegalArgumentException("Invalid payment state");
        }

        if (type != null && !type.equals("payment_received")) {
            log.warn("Invalid type: type={}", type);
            throw new IllegalArgumentException("Invalid type");
        }

        return payment;
    }
}
