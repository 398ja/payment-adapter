package xyz.tcheeric.payment.adapter.core.rest.repository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.http.HttpStatus;

import xyz.tcheeric.payment.adapter.core.model.entity.gifting.PurchaseDraft;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.PurchaseDraftRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.GiftDataRetention;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseController;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseDischargeService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * imani-wallet#570: once a gift is done, no table keeps the buyer's or the recipient's email.
 * Against a real database, because the property is the queries'.
 */
@DataJpaTest
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
class GiftDataRetentionTest {

    private static final Instant NOW = Instant.parse("2027-06-01T12:00:00Z");
    private static final Duration DAY = Duration.ofDays(1);

    @Autowired private StripePurchaseRepository purchases;
    @Autowired private PurchaseDraftRepository drafts;
    @Autowired private TestEntityManager em;

    private GiftDataRetention retention() {
        return new GiftDataRetention(purchases, drafts, Duration.ofDays(30), Duration.ofDays(400),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PurchaseDraft draft(Instant expiresAt, Instant consumedAt) {
        PurchaseDraft d = new PurchaseDraft();
        d.setId(UUID.randomUUID());
        d.setStallPubkey("a".repeat(64));
        d.setAmountMinor(2000L);
        d.setCurrency("eur");
        d.setDeliveryKind("email");
        d.setRecipientEmail("bob@example.com");
        d.setBuyerEmail("ann@example.com");
        d.setMessage("Happy birthday");
        d.setSenderName("Ann");
        d.setSealedSecret("sealed");
        d.setCreatedAt(expiresAt.minus(Duration.ofMinutes(30)));
        d.setExpiresAt(expiresAt);
        d.setConsumedAt(consumedAt);
        em.persist(d);
        em.flush();
        return d;
    }

    private StripePurchase purchase(String eventId, PurchaseDraft d, Instant createdAt, String giftKind, Long codeExpiresAt) {
        StripePurchase p = new StripePurchase();
        p.setEventId(eventId);
        p.setCheckoutSessionId("cs_" + eventId);
        p.setPaymentRequestId(UUID.randomUUID().toString());
        p.setConnectedAccountId("acct_1");
        p.setAmountMinor(2000L);
        p.setCurrency("eur");
        p.setStatus(StripePurchase.Status.DISCHARGED);
        p.setDraftId(d.getId());
        p.setDeliveryKind("email");
        p.setGiftKind(giftKind);
        p.setCodeExpiresAt(codeExpiresAt);
        p.setRecipientEmail("bob@example.com");
        p.setBuyerEmail("ann@example.com");
        p.setGiftMessage("Happy birthday");
        p.setSenderName("Ann");
        p.setCreatedAt(createdAt);
        p.setUpdatedAt(createdAt);
        em.persist(p);
        em.flush();
        return p;
    }

    private void assertNoPersonalData(UUID draftId, String eventId) {
        em.clear();
        PurchaseDraft d = drafts.findById(draftId).orElseThrow();
        assertThat(d.getBuyerEmail()).isNull();
        assertThat(d.getRecipientEmail()).isNull();
        assertThat(d.getMessage()).isNull();
        assertThat(d.getSenderName()).isNull();
        assertThat(d.getSealedSecret()).isNull();
        assertThat(d.getAmountMinor()).isEqualTo(2000L);
        if (eventId != null) {
            StripePurchase p = purchases.findByEventId(eventId).orElseThrow();
            assertThat(p.getBuyerEmail()).isNull();
            assertThat(p.getRecipientEmail()).isNull();
            assertThat(p.getGiftMessage()).isNull();
            assertThat(p.getSenderName()).isNull();
            assertThat(p.getAmountMinor()).isEqualTo(2000L);
            assertThat(p.getCheckoutSessionId()).isEqualTo("cs_" + eventId);
        }
    }

    @Test
    void scrubAllClearsThePurchaseAndItsDraft() {
        PurchaseDraft d = draft(NOW.minus(DAY), NOW.minus(DAY));
        purchase("evt_claimed", d, NOW.minus(DAY), null, null);
        PurchaseController controller = new PurchaseController(purchases, mock(PurchaseDischargeService.class),
                mock(xyz.tcheeric.payment.adapter.stripe.webhook.purchase.OperatorResolution.class));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(controller, "setDrafts", drafts);

        assertThat(controller.scrub("evt_claimed", true).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertNoPersonalData(d.getId(), "evt_claimed");
        // Idempotent: the tracker's retry of a scrub that landed is fine.
        assertThat(controller.scrub("evt_claimed", true).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void scrubWithoutAllKeepsTheDraftsAddresses() {
        PurchaseDraft d = draft(NOW.minus(DAY), NOW.minus(DAY));
        purchase("evt_seal", d, NOW.minus(DAY), null, null);
        PurchaseController controller = new PurchaseController(purchases, mock(PurchaseDischargeService.class),
                mock(xyz.tcheeric.payment.adapter.stripe.webhook.purchase.OperatorResolution.class));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(controller, "setDrafts", drafts);
        controller.scrub("evt_seal", false);
        em.clear();
        assertThat(drafts.findById(d.getId()).orElseThrow().getRecipientEmail()).isEqualTo("bob@example.com");
    }

    @Test
    void abandonedDraftsLosePersonalFieldsAfterTheWindowOnly() {
        PurchaseDraft old = draft(NOW.minus(Duration.ofDays(31)), null);
        PurchaseDraft recent = draft(NOW.minus(Duration.ofDays(29)), null);
        PurchaseDraft paid = draft(NOW.minus(Duration.ofDays(31)), NOW.minus(Duration.ofDays(31)));

        assertThat(retention().run().abandonedDrafts()).isEqualTo(1);
        assertNoPersonalData(old.getId(), null);
        assertThat(drafts.findById(recent.getId()).orElseThrow().getRecipientEmail()).isEqualTo("bob@example.com");
        assertThat(drafts.findById(paid.getId()).orElseThrow().getRecipientEmail()).isEqualTo("bob@example.com");
    }

    @Test
    void backstopScrubsVoucherGiftsByClaimWindowAndCodeGiftsByExpiry() {
        PurchaseDraft v1 = draft(NOW.minus(Duration.ofDays(500)), NOW.minus(Duration.ofDays(500)));
        purchase("evt_old_voucher", v1, NOW.minus(Duration.ofDays(415)), null, null);
        PurchaseDraft v2 = draft(NOW.minus(Duration.ofDays(410)), NOW.minus(Duration.ofDays(410)));
        purchase("evt_young_voucher", v2, NOW.minus(Duration.ofDays(410)), null, null);
        PurchaseDraft c1 = draft(NOW.minus(Duration.ofDays(600)), NOW.minus(Duration.ofDays(600)));
        purchase("evt_old_code", c1, NOW.minus(Duration.ofDays(600)), "code", NOW.minus(Duration.ofDays(401)).getEpochSecond());
        PurchaseDraft c2 = draft(NOW.minus(Duration.ofDays(600)), NOW.minus(Duration.ofDays(600)));
        purchase("evt_live_code", c2, NOW.minus(Duration.ofDays(600)), "code", NOW.minus(Duration.ofDays(10)).getEpochSecond());

        assertThat(retention().run().backstopPurchases()).isEqualTo(2);
        assertNoPersonalData(v1.getId(), "evt_old_voucher");
        assertNoPersonalData(c1.getId(), "evt_old_code");
        assertThat(purchases.findByEventId("evt_young_voucher").orElseThrow().getBuyerEmail()).isEqualTo("ann@example.com");
        assertThat(purchases.findByEventId("evt_live_code").orElseThrow().getBuyerEmail()).isEqualTo("ann@example.com");
        // A second run finds nothing left.
        assertThat(retention().run()).isEqualTo(new GiftDataRetention.Result(0, 0, 0));
    }

    @Test
    void backfillClearsDraftsOfPurchasesScrubbedBefore570() {
        PurchaseDraft d = draft(NOW.minus(DAY), NOW.minus(DAY));
        StripePurchase p = purchase("evt_scrubbed_before", d, NOW.minus(DAY), null, null);
        p.setBuyerEmail(null);
        p.setRecipientEmail(null);
        p.setGiftMessage(null);
        p.setSenderName(null);
        p.setScrubbedAt(NOW.minus(DAY));
        em.merge(p);
        em.flush();
        PurchaseDraft open = draft(NOW.minus(DAY), NOW.minus(DAY));
        purchase("evt_still_open", open, NOW.minus(DAY), null, null);

        assertThat(retention().run().backfilledDrafts()).isEqualTo(1);
        assertNoPersonalData(d.getId(), "evt_scrubbed_before");
        assertThat(drafts.findById(open.getId()).orElseThrow().getRecipientEmail()).isEqualTo("bob@example.com");
    }
}
