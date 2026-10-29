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

    /**
     * Deliveries that may be sold, comma-separated. Default empty (nothing sellable, fail closed): set {@code handle,email}
     * once purchase-issuer arms gift locks (imani-wallet#410 step 3, #366).
     */
    @Value("${purchase.gifting.kinds:}")
    private String kinds;

    /**
     * gateway-customer, for the public nostr query a signed-in buyer's role is read from (customers
     * only, never a stall). Unset refuses every signed-in buyer; guests are unaffected.
     */
    @Value("${purchase.gifting.gateway-base-url:}")
    private String gatewayBaseUrl;

    /**
     * Local stacks only: handle domains resolved by an internal NIP-05 server instead of over HTTPS
     * (see {@code GiftLookups.WellKnownResolver}). Empty everywhere else.
     */
    @Value("${purchase.gifting.nip05-overrides:}")
    private String nip05Overrides;

    @Value("${purchase.gifting.mailer-url:}")
    private String mailerUrl;

    @Value("${purchase.gifting.mailer-token:}")
    private String mailerToken;

    /** The wallet origin a guest's return link must start with, e.g. https://wallet.imani.casa. */
    @Value("${purchase.gifting.wallet-origin:}")
    private String walletOrigin;

    @Value("${purchase.gifting.public-origin:https://gifting.imani.casa}")
    private String publicOrigin;

    @Bean
    GiftingAuth giftingAuth() {
        return new GiftingAuth(publicOrigin, buyerRoles(), Clock.systemUTC());
    }

    private BuyerRoleReader.Reader buyerRoles() {
        return new BuyerRoleReader(gatewayBaseUrl, java.time.Duration.ofSeconds(5)).asReader();
    }

    @Bean
    GiftingService giftingService(PurchaseDraftRepository drafts, ConnectedStripeAccountRepository accounts,
            StallReadinessClient readiness, StripeCheckoutService checkout) {
        return new GiftingService(drafts, accounts, readiness, checkout, new GiftLookups.WellKnownResolver(GiftLookups.WellKnownResolver.parseOverrides(nip05Overrides)),
                new GiftLookups.DnsMailDomainChecker(), Clock.systemUTC(), maxAmountMinor, returnUrl)
                .withEnabledKinds(java.util.Arrays.asList(kinds.split(",")))
                .withBuyerRoles(buyerRoles())
                .withReturnLinkMail(returnLinkMailer(), walletOrigin);
    }

    private ReturnLinkMailer returnLinkMailer() {
        if (mailerUrl == null || mailerUrl.isBlank() || mailerToken == null || mailerToken.isBlank()) {
            return null;
        }
        return new ReturnLinkMailer.ImaniMailer(mailerUrl.trim(), mailerToken.trim(), java.time.Duration.ofSeconds(10));
    }
}
