package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Source;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripeReconcileCursorRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.LivePaymentCheck;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripePurchaseReconciler.ReconcilerSettings;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;

/**
 * Wires {@link StripePurchaseReconciler} (#272). Only when Stripe is enabled: with Stripe off there
 * is no key to read with and nothing to reconcile, so no bean exists at all.
 * {@code purchase.reconcile.enabled} (default true) switches the run off without a redeploy of code.
 */
@Configuration
@ConditionalOnProperty(prefix = "stripe", name = "enabled", havingValue = "true")
public class StripeReconcileConfiguration {

    @Bean
    StripeReconcileSource stripeReconcileSource(StripeGatewayProperties properties) {
        return new StripeSdkReconcileSource(properties);
    }

    /** The live read in front of an operator's retry (#286): the reconciler's read, reused. */
    @Bean
    LivePaymentCheck livePaymentCheck(StripeReconcileSource stripe, StripePurchaseListener listener) {
        return new StripeLivePaymentCheck(stripe, listener);
    }

    @Bean
    StripePurchaseReconciler stripePurchaseReconciler(
            ConnectedStripeAccountRepository accounts, StripeReconcileCursorRepository cursors,
            StripePurchaseRepository purchases, StripePurchaseListener listener, StripeReconcileSource stripe,
            PlatformTransactionManager transactionManager, ObjectProvider<MeterRegistry> registry,
            @Value("${purchase.reconcile.enabled:true}") boolean enabled,
            @Value("${purchase.reconcile.overlap:PT1H}") Duration overlap,
            @Value("${purchase.reconcile.grace:PT10M}") Duration grace,
            @Value("${purchase.reconcile.initial-lookback:P30D}") Duration initialLookback,
            @Value("${purchase.reconcile.lease:PT15M}") Duration lease,
            @Value("${purchase.reconcile.page-size:100}") int pageSize,
            @Value("${purchase.reconcile.max-stripe-calls-per-run:50}") int maxCalls,
            @Value("${purchase.reconcile.issuing-stale-after:PT1H}") Duration issuingStaleAfter,
            @Value("${purchase.reconcile.issued-stale-after:P14D}") Duration issuedStaleAfter,
            @Value("${purchase.reconcile.stale-batch-size:20}") int staleBatch,
            @Value("${purchase.reconcile.parked-batch-size:20}") int parkedBatch) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        PaymentMetrics metrics = new PaymentMetrics(registry.getIfAvailable()).preRegisterReconciled(Source.CARD);
        return new StripePurchaseReconciler(accounts, cursors, stripe,
                new LostPurchaseRecorder(stripe, purchases, listener, transactions, metrics),
                new StaleRows(stripe, purchases, listener, transactions, issuingStaleAfter, issuedStaleAfter,
                        Math.max(1, staleBatch)),
                new ParkedRows(new StripeLivePaymentCheck(stripe, listener), purchases, Math.max(1, parkedBatch)),
                transactions,
                new ReconcilerSettings(enabled, overlap, grace, initialLookback, lease, pageSize, maxCalls),
                "reconciler-" + UUID.randomUUID());
    }
}
