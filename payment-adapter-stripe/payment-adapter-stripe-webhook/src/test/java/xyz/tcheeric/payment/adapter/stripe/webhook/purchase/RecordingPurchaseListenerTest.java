package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recording the debt a paid purchase creates.
 *
 * <p>Everything here is about a customer who has paid. The failure that matters
 * is not an exception, it is a row that quietly does not exist, or one that
 * exists twice.
 */
@ExtendWith(MockitoExtension.class)
class RecordingPurchaseListenerTest {

    private static final String BUYER = "a".repeat(64);
    private static final String ACCOUNT = "acct_1MerchantXyz";

    private static final String STALL = "b".repeat(64);

    @Mock private StripePurchaseRepository purchases;
    @Mock private ConnectedStripeAccountRepository accounts;

    private RecordingPurchaseListener listener;

    @BeforeEach
    void setUp() {
        listener = new RecordingPurchaseListener(purchases, accounts);
        ConnectedStripeAccount account = new ConnectedStripeAccount();
        account.setMerchantPubkey(STALL);
        account.setStripeAccountId(ACCOUNT);
        org.mockito.Mockito.lenient()
                .when(accounts.findByStripeAccountId(ACCOUNT))
                .thenReturn(Optional.of(account));
    }

    @Test
    void resolvesTheStallPubkeyRatherThanLeavingTheStripeId() {
        // An acct_… addresses money; a pubkey addresses issuance. A row
        // carrying only the first finds no credential, so every card sale
        // would fall back to manual issuance and the feature would silently do
        // nothing.
        when(purchases.findByEventId("evt_1")).thenReturn(Optional.empty());

        listener.onPurchasePaid(paid(Map.of()));

        assertEquals(STALL, captureSaved().getStallPubkey());
    }

    @Test
    void recordsTheDebtEvenWhenTheStallIsUnknownToUs() {
        // Connect delivers events for accounts we may have lost track of. The
        // debt still exists; it just cannot be issued automatically.
        when(purchases.findByEventId("evt_1")).thenReturn(Optional.empty());
        when(accounts.findByStripeAccountId(ACCOUNT)).thenReturn(Optional.empty());

        listener.onPurchasePaid(paid(Map.of()));

        StripePurchase saved = captureSaved();
        assertNull(saved.getStallPubkey());
        assertEquals(StripePurchase.Status.OWED, saved.getStatus(), "still owed");
    }

    private StripePurchaseListener.PaidPurchase paid(Map<String, String> metadata) {
        return new StripePurchaseListener.PaidPurchase(
                "evt_1", "cs_1", "pi_1", ACCOUNT, 2500L, "gbp", metadata, true);
    }

    private StripePurchase captureSaved() {
        ArgumentCaptor<StripePurchase> captor = ArgumentCaptor.forClass(StripePurchase.class);
        verify(purchases).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void recordsThePurchaseAsOwed() {
        when(purchases.findByEventId("evt_1")).thenReturn(Optional.empty());

        listener.onPurchasePaid(paid(Map.of("buyer_pubkey", BUYER)));

        StripePurchase saved = captureSaved();
        assertEquals(StripePurchase.Status.OWED, saved.getStatus());
        assertEquals(ACCOUNT, saved.getConnectedAccountId(), "the stall that owes it");
        assertEquals(BUYER, saved.getRecipientPubkey());
        assertEquals(2500L, saved.getAmountMinor());
        assertEquals("gbp", saved.getCurrency());
        assertNull(saved.getVoucherId(), "nothing is issued yet");
    }

    @Test
    void ignoresARedeliveryRatherThanRecordingItTwice() {
        // Stripe delivers more than once. Two rows would become two coupons for
        // one payment, and a coupon cannot be un-minted.
        StripePurchase existing = new StripePurchase();
        when(purchases.findByEventId("evt_1")).thenReturn(Optional.of(existing));

        listener.onPurchasePaid(paid(Map.of("buyer_pubkey", BUYER)));

        verify(purchases, never()).save(any());
    }

    @Test
    void acceptsABuyerWithNoWallet() {
        // Not an error: this is the hosted-purchase customer, who takes
        // delivery by claim link. Refusing here would refuse the person the
        // page exists for.
        when(purchases.findByEventId("evt_1")).thenReturn(Optional.empty());

        listener.onPurchasePaid(paid(Map.of()));

        StripePurchase saved = captureSaved();
        assertNull(saved.getRecipientPubkey());
        assertEquals(StripePurchase.Status.OWED, saved.getStatus(), "still owed a coupon");
    }

    @Test
    void treatsAMalformedPubkeyAsNoRecipientRatherThanTrustingIt() {
        // Metadata is a string map. A malformed key would become a coupon sent
        // to nobody, which is worse than falling back to a claim link.
        when(purchases.findByEventId(any())).thenReturn(Optional.empty());

        for (String bad : new String[] {"not-a-key", "", "  ", "z".repeat(64), BUYER + "extra"}) {
            listener.onPurchasePaid(paid(Map.of("buyer_pubkey", bad)));
        }

        ArgumentCaptor<StripePurchase> captor = ArgumentCaptor.forClass(StripePurchase.class);
        verify(purchases, org.mockito.Mockito.times(5)).save(captor.capture());
        captor.getAllValues().forEach(saved -> assertNull(saved.getRecipientPubkey()));
    }

    @Test
    void normalisesAnUppercasePubkey() {
        when(purchases.findByEventId("evt_1")).thenReturn(Optional.empty());

        listener.onPurchasePaid(paid(Map.of("buyer_pubkey", BUYER.toUpperCase())));

        assertEquals(BUYER, captureSaved().getRecipientPubkey());
    }

    @Test
    void keepsTestModePurchasesRatherThanDroppingThem() {
        // An issuer must refuse to mint real value from a test payment, and it
        // can only refuse if it is told. Dropping these would hide a
        // misconfigured deployment.
        when(purchases.findByEventId("evt_1")).thenReturn(Optional.empty());
        StripePurchaseListener.PaidPurchase testMode = new StripePurchaseListener.PaidPurchase(
                "evt_1", "cs_1", "pi_1", ACCOUNT, 2500L, "gbp", Map.of(), false);

        listener.onPurchasePaid(testMode);

        assertTrue(!captureSaved().isLivemode());
    }
}
