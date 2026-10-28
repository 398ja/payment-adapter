package xyz.tcheeric.payment.adapter.stripe.webhook.gifting;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.PurchaseDraftRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.StallReadinessClient;

/**
 * Wiring for the gifting site's Purchase Drafts (imani-wallet#410). Off unless
 * {@code purchase.gifting.enabled=true} AND Stripe is on, so no existing deployment changes.
 */
@Configuration
@ConditionalOnProperty(prefix = "purchase.gifting", name = "enabled", havingValue = "true")
public class GiftingConfiguration {

    @Value("${purchase.gifting.return-url:https://gifting.imani.casa/return}")
    private String returnUrl;

    @Value("${purchase.max-amount-minor:1000000}")
    private long maxAmountMinor;

    @Bean
    GiftingService giftingService(PurchaseDraftRepository drafts, ConnectedStripeAccountRepository accounts,
            StallReadinessClient readiness, StripeCheckoutService checkout) {
        return new GiftingService(drafts, accounts, readiness, checkout, new GiftLookups.WellKnownResolver(),
                new GiftLookups.DnsMailDomainChecker(), Clock.systemUTC(), maxAmountMinor, returnUrl);
    }
}
