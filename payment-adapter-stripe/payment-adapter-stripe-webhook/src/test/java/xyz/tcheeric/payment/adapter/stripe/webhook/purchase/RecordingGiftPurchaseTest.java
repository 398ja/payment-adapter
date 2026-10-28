package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xyz.tcheeric.payment.adapter.core.model.entity.gifting.PurchaseDraft;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.PurchaseDraftRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;

/** imani-wallet#410: a paid gift becomes a debt carrying the draft's PINNED delivery, and only that. */
class RecordingGiftPurchaseTest {

    static final String STALL = "a".repeat(64);
    static final String BOB = "b".repeat(64);
    static final String R = "c".repeat(64);
    static final String ACCOUNT = "acct_1";

    StripePurchaseRepository purchases;
    PurchaseDraftRepository drafts;
    RecordingPurchaseListener listener;
    PurchaseDraft draft;

    @BeforeEach
    void setUp() {
        purchases = mock(StripePurchaseRepository.class);
        when(purchases.findByEventId(any())).thenReturn(Optional.empty());
        ConnectedStripeAccountRepository accounts = mock(ConnectedStripeAccountRepository.class);
        ConnectedStripeAccount account = new ConnectedStripeAccount();
        account.setMerchantPubkey(STALL);
        account.setStripeAccountId(ACCOUNT);
        when(accounts.findByStripeAccountId(ACCOUNT)).thenReturn(Optional.of(account));
        drafts = mock(PurchaseDraftRepository.class);
        listener = new RecordingPurchaseListener(purchases, accounts).withDrafts(drafts);
        draft = new PurchaseDraft();
        draft.setId(UUID.randomUUID());
        draft.setStallPubkey(STALL);
        draft.setAmountMinor(2000L);
        draft.setCurrency("EUR");
        draft.setCreatedAt(Instant.now());
        draft.setExpiresAt(Instant.now().plusSeconds(1800));
        when(drafts.findById(draft.getId())).thenReturn(Optional.of(draft));
    }

    StripePurchase record(Map<String, String> metadata, long amount) {
        listener.onPurchasePaid(new StripePurchaseListener.PaidPurchase("evt_1", "cs_1", "pi_1", ACCOUNT, amount, "eur", metadata, false));
        ArgumentCaptor<StripePurchase> saved = ArgumentCaptor.forClass(StripePurchase.class);
        verify(purchases).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void aHandleGiftIsRecordedToThePinnedKeyWithItsRefundKey() {
        draft.setDeliveryKind(PurchaseDraft.HANDLE);
        draft.setRecipientPubkey(BOB);
        draft.setRefundPubkey(R);
        draft.setMessage("Happy birthday");
        StripePurchase row = record(Map.of("draft_id", draft.getId().toString()), 2000);
        assertEquals(BOB, row.getRecipientPubkey());
        assertEquals(R, row.getRefundPubkey());
        assertEquals("handle", row.getDeliveryKind());
        assertEquals(draft.getId(), row.getDraftId());
        assertEquals("Happy birthday", row.getGiftMessage());
        assertNotNull(draft.getConsumedAt());
    }

    @Test
    void anEmailGiftHasNoRecipientKeyAndCarriesTheSealToTheDebtNotTheDraft() {
        draft.setDeliveryKind(PurchaseDraft.EMAIL);
        draft.setRecipientEmail("bob@example.com");
        draft.setRefundPubkey(R);
        draft.setSealedSecret("v1.x.y");
        StripePurchase row = record(Map.of("draft_id", draft.getId().toString()), 2000);
        assertNull(row.getRecipientPubkey());
        assertEquals("bob@example.com", row.getRecipientEmail());
        assertEquals("v1.x.y", row.getSealedSecret());
        assertNull(draft.getSealedSecret(), "the draft drops its copy once the debt holds it");
    }

    @Test
    void sessionMetadataCannotOverrideThePinnedRecipient() {
        draft.setDeliveryKind(PurchaseDraft.HANDLE);
        draft.setRecipientPubkey(BOB);
        draft.setRefundPubkey(R);
        StripePurchase row = record(Map.of("draft_id", draft.getId().toString(), "buyer_pubkey", "e".repeat(64)), 2000);
        assertEquals(BOB, row.getRecipientPubkey());
    }

    @Test
    void aDraftThatDoesNotMatchThePaymentIsRecordedWithNoRecipient() {
        draft.setDeliveryKind(PurchaseDraft.HANDLE);
        draft.setRecipientPubkey(BOB);
        StripePurchase row = record(Map.of("draft_id", draft.getId().toString()), 1);
        assertNull(row.getRecipientPubkey());
    }

    @Test
    void anUnknownDraftIsRecordedWithNoRecipient() {
        when(drafts.findById(any())).thenReturn(Optional.empty());
        StripePurchase row = record(Map.of("draft_id", UUID.randomUUID().toString(), "buyer_pubkey", BOB), 2000);
        assertNull(row.getRecipientPubkey());
    }

    @Test
    void aWalletPurchaseIsUnchanged() {
        StripePurchase row = record(Map.of("buyer_pubkey", BOB), 2000);
        assertEquals(BOB, row.getRecipientPubkey());
        assertNull(row.getDeliveryKind());
        assertNull(PurchaseController.GiftDelivery.of(row));
    }
}
