package xyz.tcheeric.payment.adapter.paypal.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedPaypalAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.PaypalCheckoutRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedPaypalWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalOrdersClient;
import xyz.tcheeric.payment.adapter.paypal.gateway.webhook.PaypalWebhookVerifier;
import xyz.tcheeric.payment.adapter.paypal.webhook.reconcile.PaypalPaymentStateReader;
import xyz.tcheeric.payment.adapter.paypal.webhook.reconcile.PaypalPurchaseReconciler;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.ProcessorLivePaymentCheck;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.ParkedRows;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StaleRows;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeLivePaymentCheck;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.webhook.core.WebhookRegistry;

/**
 * PayPal's half of the purchase pipeline, on only with {@code paypal.enabled}: the counterpart of
 * {@code StripeWebhookHandlerConfiguration} and {@code StripeReconcileConfiguration} together.
 *
 * <ul>
 *   <li>the payments webhook at {@code /webhook/paypal}, registered with the {@link WebhookRegistry};</li>
 *   <li>the capture service the webhook, the buyer's return and the reconciler share;</li>
 *   <li>the operator's live check for PayPal rows ({@link ProcessorLivePaymentCheck}), so an operator
 *       retry of a PayPal purchase reads PayPal and never Stripe;</li>
 *   <li>the reconciler, and the shared stale and parked sweeps scoped to {@code processor='paypal'}.</li>
 * </ul>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "paypal", name = "enabled", havingValue = "true")
public class PaypalWebhookConfiguration {

    /**
     * The ledger knows this deployment by {@code stripe.environment}, and reads any order naming
     * another as foreign: recorded nowhere, refused to nobody. An order stamped with a different
     * {@code paypal.environment} would be a paid purchase silently dropped. Refuse to start instead.
     */
    static void requireSameEnvironment(PaypalGatewayProperties paypal, String stripeEnvironment) {
        String ledger = stripeEnvironment == null || stripeEnvironment.isBlank() ? null
                : stripeEnvironment.trim().toLowerCase(Locale.ROOT);
        String stamped = paypal.resolvedEnvironment();
        if (ledger == null ? stamped != null : !ledger.equals(stamped)) {
            throw new IllegalStateException("paypal.environment ('" + stamped + "') must equal stripe.environment ('"
                    + ledger + "'). The purchase ledger reads an order naming any other environment as another "
                    + "deployment's and records nothing, so every paid PayPal purchase would be dropped. Set "
                    + "PAYPAL_ENVIRONMENT to the same value as STRIPE_ENVIRONMENT, or leave it unset to inherit it.");
        }
    }

    @Bean
    PaypalPaymentStateReader paypalPaymentStateReader(PaypalOrdersClient orders) {
        return new PaypalPaymentStateReader(orders);
    }

    @Bean
    PaypalCaptureService paypalCaptureService(PaypalGatewayProperties properties,
            @Value("${stripe.environment:}") String stripeEnvironment, PaypalCheckoutRepository checkouts,
            ConnectedPaypalAccountRepository accounts, PaypalOrdersClient orders, StripePurchaseListener listener,
            PaypalPaymentStateReader reader, ObjectMapper objectMapper) {
        requireSameEnvironment(properties, stripeEnvironment);
        return new PaypalCaptureService(checkouts, accounts, orders, listener, reader, objectMapper);
    }

    @Bean
    PaypalWebhookHandler paypalWebhookHandler(PaypalGatewayProperties properties,
            @Qualifier("paypalWebhookVerifier") PaypalWebhookVerifier verifier,
            ProcessedPaypalWebhookEventRepository events, PaypalCheckoutRepository checkouts,
            StripePurchaseRepository purchases, PaypalCaptureService captures, StripePurchaseListener listener,
            ObjectProvider<MeterRegistry> registry) {
        if (!verifier.isConfigured()) {
            log.warn("No PayPal webhook id configured (paypal.webhook-id); /webhook/paypal will refuse every delivery");
        }
        PaypalWebhookHandler handler = new PaypalWebhookHandler(verifier, events, checkouts, purchases, captures,
                listener, properties.resolvedEnvironment(), properties.isLive());
        registry.ifAvailable(handler::setMeterRegistry);
        WebhookRegistry.getInstance().register(handler);
        log.info("Registered PaypalWebhookHandler with WebhookRegistry at /webhook/{}", PaypalWebhookHandler.PAYMENT_TYPE);
        return handler;
    }

    /** The operator's live read for PayPal rows: Stripe's check, reading PayPal. */
    @Bean
    ProcessorLivePaymentCheck paypalLivePaymentCheck(PaypalPaymentStateReader reader, StripePurchaseListener listener) {
        return new ProcessorLivePaymentCheck(StripePurchase.PROCESSOR_PAYPAL, new StripeLivePaymentCheck(reader, listener));
    }

    @Bean
    PaypalPurchaseReconciler paypalPurchaseReconciler(PaypalCheckoutRepository checkouts, PaypalOrdersClient orders,
            PaypalCaptureService captures, PaypalPaymentStateReader reader, StripePurchaseRepository purchases,
            StripePurchaseListener listener, PlatformTransactionManager transactionManager,
            @Value("${purchase.reconcile.enabled:true}") boolean enabled,
            @Value("${paypal.reconcile.lookback:P30D}") Duration lookback,
            @Value("${purchase.reconcile.grace:PT10M}") Duration grace,
            @Value("${paypal.reconcile.abandon-after:PT6H}") Duration abandonAfter,
            @Value("${paypal.reconcile.batch-size:50}") int batchSize,
            @Value("${purchase.reconcile.max-stripe-calls-per-run:50}") int maxCalls,
            @Value("${purchase.reconcile.issuing-stale-after:PT1H}") Duration issuingStaleAfter,
            @Value("${purchase.reconcile.issued-stale-after:P14D}") Duration issuedStaleAfter,
            @Value("${purchase.reconcile.stale-batch-size:20}") int staleBatch,
            @Value("${purchase.reconcile.parked-batch-size:20}") int parkedBatch) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        return new PaypalPurchaseReconciler(checkouts, orders, captures,
                new StaleRows(reader, purchases, listener, transactions, issuingStaleAfter, issuedStaleAfter,
                        Math.max(1, staleBatch), StripePurchase.PROCESSOR_PAYPAL),
                new ParkedRows(new StripeLivePaymentCheck(reader, listener), purchases, Math.max(1, parkedBatch),
                        StripePurchase.PROCESSOR_PAYPAL),
                new PaypalPurchaseReconciler.Settings(enabled, lookback, grace, abandonAfter, Math.max(1, batchSize),
                        Math.max(1, maxCalls)));
    }
}
