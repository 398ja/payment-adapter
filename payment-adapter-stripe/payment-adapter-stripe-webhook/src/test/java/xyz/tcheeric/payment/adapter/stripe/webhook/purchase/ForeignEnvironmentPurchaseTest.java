package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.PaidPurchase;

/**
 * A paid Checkout Session another deployment created is not a debt here (staging 2026-10-28/29).
 *
 * <p>A developer's local gifting site ran on staging's Stripe test key and charged a stall's
 * connected account 55 times. Staging's Connect webhook reports every payment on that account, and
 * this listener recorded each as OWED for a stall with no card sales: GBP 315 of debts staging never
 * sold, and CardPaidNotIssued firing for a week. Each session now carries {@code imani_env}; a
 * deployment records only its own, and an untagged session as before.
 */
class ForeignEnvironmentPurchaseTest {

    static final String STALL = "a".repeat(64);
    static final String BUYER = "b".repeat(64);
    static final String ACCOUNT = "acct_1Stall";

    StripePurchaseRepository purchases;
    ConnectedStripeAccountRepository accounts;

    @BeforeEach
    void setUp() {
        purchases = mock(StripePurchaseRepository.class);
        when(purchases.findByEventId(any())).thenReturn(Optional.empty());
        when(purchases.findByCheckoutSessionId(any())).thenReturn(Optional.empty());
        accounts = mock(ConnectedStripeAccountRepository.class);
        ConnectedStripeAccount account = new ConnectedStripeAccount();
        account.setMerchantPubkey(STALL);
        account.setStripeAccountId(ACCOUNT);
        when(accounts.findByStripeAccountId(ACCOUNT)).thenReturn(Optional.of(account));
    }

    RecordingPurchaseListener listener(String environment) {
        return new RecordingPurchaseListener(purchases, accounts).withEnvironment(environment);
    }

    static PaidPurchase paid(Map<String, String> metadata) {
        return new PaidPurchase("evt_1", "cs_test_1", "pi_1", ACCOUNT, 500L, "gbp", metadata, false);
    }

    static Map<String, String> tagged(String environment) {
        Map<String, String> m = new HashMap<>();
        m.put("buyer_pubkey", BUYER);
        m.put(StripeGatewayProperties.ENVIRONMENT_METADATA_KEY, environment);
        return m;
    }

    StripePurchase saved() {
        ArgumentCaptor<StripePurchase> row = ArgumentCaptor.forClass(StripePurchase.class);
        verify(purchases).save(row.capture());
        return row.getValue();
    }

    @Test
    void aSessionFromAnotherEnvironmentIsAcknowledgedAndNeverRecorded() {
        // The staging incident: the local dev gifting site's sessions, tagged by its own instance.
        listener("staging").onPurchasePaid(paid(tagged("dev")));

        verify(purchases, never()).save(any());
        // Not even looked up: a foreign session must not touch this deployment's rows at all.
        verify(purchases, never()).findByEventId(any());
        verifyNoInteractions(accounts);
    }

    @Test
    void ourOwnSessionIsRecordedOwedAsBefore() {
        listener("staging").onPurchasePaid(paid(tagged("staging")));

        StripePurchase row = saved();
        assertEquals(StripePurchase.Status.OWED, row.getStatus());
        assertEquals(STALL, row.getStallPubkey());
        assertEquals(BUYER, row.getRecipientPubkey());
    }

    @Test
    void theComparisonIgnoresCaseAndSpaces() {
        listener(" Staging ").onPurchasePaid(paid(tagged("STAGING ")));

        assertEquals(StripePurchase.Status.OWED, saved().getStatus());
    }

    @Test
    void anUntaggedSessionIsStillRecorded() {
        // Sessions created before this release carry no tag, and a real buyer's payment must never be
        // dropped for a missing one. Only a tag that names ANOTHER deployment is refused.
        listener("staging").onPurchasePaid(paid(Map.of("buyer_pubkey", BUYER)));

        assertEquals(StripePurchase.Status.OWED, saved().getStatus());
    }

    @Test
    void aBlankTagIsTreatedAsUntagged() {
        listener("staging").onPurchasePaid(paid(tagged("  ")));

        assertEquals(StripePurchase.Status.OWED, saved().getStatus());
    }

    @Test
    void aDeploymentWithNoEnvironmentRecordsEverySessionAsBefore() {
        // STRIPE_ENVIRONMENT unset: nothing changes for a deployment that has not opted in.
        listener(null).onPurchasePaid(paid(tagged("dev")));
        assertEquals(StripePurchase.Status.OWED, saved().getStatus());
    }

    @Test
    void theSharedRuleNamesTheForeignEnvironmentOnly() {
        assertEquals("dev", RecordingPurchaseListener.foreignEnvironment("staging", tagged("dev")));
        assertNull(RecordingPurchaseListener.foreignEnvironment("staging", tagged("staging")));
        assertNull(RecordingPurchaseListener.foreignEnvironment("staging", Map.of()));
        assertNull(RecordingPurchaseListener.foreignEnvironment("staging", null));
        assertNull(RecordingPurchaseListener.foreignEnvironment(null, tagged("dev")));
        assertNull(RecordingPurchaseListener.foreignEnvironment(" ", tagged("dev")));
    }
}
