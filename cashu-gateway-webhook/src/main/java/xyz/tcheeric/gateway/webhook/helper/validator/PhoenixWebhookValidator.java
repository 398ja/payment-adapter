package xyz.tcheeric.gateway.webhook.helper.validator;

import lombok.extern.java.Log;
import xyz.tcheeric.gateway.client.PaymentClient;
import xyz.tcheeric.gateway.client.QuoteClient;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;
import xyz.tcheeric.gateway.model.entity.enums.Direction;
import xyz.tcheeric.gateway.model.entity.enums.State;
import xyz.tcheeric.gateway.webhook.helper.PhoenixdWebhookRequest;

import java.util.logging.Level;

@Log
public class PhoenixWebhookValidator extends BaseWebhookValidator {

    public PhoenixWebhookValidator(PhoenixdWebhookRequest webhookRequest) {
        super(webhookRequest);
    }

    public GatewayPayment validate() {

        PhoenixdWebhookRequest webhookRequest = (PhoenixdWebhookRequest) getWebhookRequest();

        String type = webhookRequest.getType();
        Integer amountSat = webhookRequest.getAmountSat();
        String paymentHash = webhookRequest.getPaymentHash();
        String externalId = webhookRequest.getExternalId();

        log.log(Level.INFO, "Received webhook: type={0}, amountSat={1}, paymentHash={2}, externalId={3}",
                new Object[]{type, amountSat, paymentHash, externalId});

        // Find the quote
        QuoteClient quoteClient = new QuoteClient();
        GatewayQuote quote = quoteClient.getByInvoiceId(externalId);

        if (quote == null) {
            log.log(Level.WARNING, "Quote not found: externalId={0}", externalId);
            throw new IllegalArgumentException("Quote not found");
        }

        if (quote.getDirection() != Direction.RECEIVE) {
            log.log(Level.WARNING, "Invalid quote direction: direction={0}", quote.getDirection());
            throw new IllegalArgumentException("Invalid quote direction");
        }

        // Find the payment
        PaymentClient paymentClient = new PaymentClient();
        GatewayPayment payment = paymentClient.getByQuoteId(quote.getQuoteId());

        // Validate the payment
        if (payment == null) {
            log.log(Level.WARNING, "Payment not found: lnInvoice={0}", quote.getRequest());
            throw new IllegalArgumentException("Payment not found");
        }

        if (paymentHash != null && !paymentHash.equals(payment.getPaymentHash())) {
            log.log(Level.WARNING, "Payment hash mismatch: paymentHash={0}, expected={1}",
                    new Object[]{paymentHash, payment.getPaymentHash()});
            throw new IllegalArgumentException("Payment hash mismatch");
        }

        if (amountSat != null && !amountSat.equals(payment.getAmount())) {
            log.log(Level.WARNING, "Amount mismatch: amountSat={0}, expected={1}",
                    new Object[]{amountSat, payment.getAmount()});
            throw new IllegalArgumentException("Amount mismatch");
        }

        if(payment.getState() == null || payment.getState() != State.PAID) {
            log.log(Level.WARNING, "Invalid payment state: state={0}", payment.getState());
            throw new IllegalArgumentException("Invalid payment state");
        }

        if (type != null && !type.equals("payment_received")) {
            log.log(Level.WARNING, "Invalid type: type={0}", type);
            throw new IllegalArgumentException("Invalid type");
        }

        return payment;
    }
}
