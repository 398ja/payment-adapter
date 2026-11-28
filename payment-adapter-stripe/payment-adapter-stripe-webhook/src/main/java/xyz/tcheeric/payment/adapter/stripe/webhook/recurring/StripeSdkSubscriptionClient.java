package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import com.stripe.exception.StripeException;
import com.stripe.model.InvoicePayment;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.InvoicePaymentListParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionExpireParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

/** Stripe SDK implementation. The platform key signs; {@code Stripe-Account} says whose money it is. */
@Slf4j
@RequiredArgsConstructor
public class StripeSdkSubscriptionClient implements StripeSubscriptionClient {

    private final StripeGatewayProperties properties;

    @Override
    public String paymentIntentForInvoice(String connectedAccountId, String invoiceId) {
        try {
            for (InvoicePayment payment : InvoicePayment.list(
                    InvoicePaymentListParams.builder().setInvoice(invoiceId).setLimit(10L).build(),
                    options(connectedAccountId)).getData()) {
                if ("paid".equals(payment.getStatus()) && payment.getPayment() != null
                        && "payment_intent".equals(payment.getPayment().getType())
                        && payment.getPayment().getPaymentIntent() != null) {
                    return payment.getPayment().getPaymentIntent();
                }
            }
            return null;
        } catch (StripeException e) {
            log.warn("stripe_invoice_payments_failed account={} invoice={} code={} status={}",
                    connectedAccountId, invoiceId, e.getCode(), e.getStatusCode());
            throw new RecurringPlanException("could not read the payment for invoice " + invoiceId, e);
        }
    }

    @Override
    public void cancelAtPeriodEnd(String connectedAccountId, String subscriptionId) {
        try {
            Subscription subscription = new Subscription();
            subscription.setId(subscriptionId);
            subscription.update(SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(true).build(),
                    options(connectedAccountId));
        } catch (StripeException e) {
            log.warn("stripe_subscription_cancel_failed account={} subscription={} code={} status={}",
                    connectedAccountId, subscriptionId, e.getCode(), e.getStatusCode());
            throw new RecurringPlanException("could not end subscription " + subscriptionId, e);
        }
    }

    @Override
    public void expireCheckoutSession(String connectedAccountId, String checkoutSessionId) {
        try {
            Session session = Session.retrieve(checkoutSessionId, options(connectedAccountId));
            if ("open".equals(session.getStatus())) {
                session.expire(SessionExpireParams.builder().build(), options(connectedAccountId));
            } else if ("complete".equals(session.getStatus())) {
                throw new RecurringPlanException("checkout session " + checkoutSessionId + " has already completed");
            }
        } catch (StripeException e) {
            log.warn("stripe_checkout_expire_failed account={} session={} code={} status={}",
                    connectedAccountId, checkoutSessionId, e.getCode(), e.getStatusCode());
            throw new RecurringPlanException("could not expire checkout session " + checkoutSessionId, e);
        }
    }

    private RequestOptions options(String connectedAccountId) {
        return properties.requestOptions()
                .setApiKey(properties.getSecretKey())
                .setStripeAccount(connectedAccountId)
                .build();
    }
}
