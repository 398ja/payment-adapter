package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.gifting.Nip98Verifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** imani-wallet#277: the refund-state read, its two credentials, and the state mapping. */
class PurchaseStateControllerTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final long NOW = 1_800_000_000L;
    static final byte[] STALL_SK = HexFormat.of().parseHex("7f".repeat(32));
    static final byte[] OTHER_SK = HexFormat.of().parseHex("3c".repeat(32));
    static final String ORIGIN = "https://wallet.imani.casa";
    static final String READ = "read-only-token";
    static final String ISSUER = "issuer-token";

    final StripePurchaseRepository purchases = mock(StripePurchaseRepository.class);
    PurchaseStateController controller;
    String stall;

    @BeforeEach
    void setUp() throws Exception {
        stall = HexFormat.of().formatHex(Schnorr.genPubKey(STALL_SK));
        controller = controller(READ);
    }

    PurchaseStateController controller(String readToken) {
        return new PurchaseStateController(purchases,
                new Nip98Verifier(Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC)),
                List.of(ORIGIN + "/"), readToken, ISSUER, "operator-token");
    }

    static String nip98(byte[] sk, String url, String method, long createdAt) throws Exception {
        String pubkey = HexFormat.of().formatHex(Schnorr.genPubKey(sk));
        ArrayNode tags = MAPPER.createArrayNode();
        tags.add(MAPPER.createArrayNode().add("u").add(url));
        tags.add(MAPPER.createArrayNode().add("method").add(method));
        ArrayNode canonical = MAPPER.createArrayNode().add(0).add(pubkey).add(createdAt).add(27235);
        canonical.add(tags);
        canonical.add("");
        byte[] id = MessageDigest.getInstance("SHA-256")
                .digest(MAPPER.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
        ObjectNode event = MAPPER.createObjectNode();
        event.put("id", HexFormat.of().formatHex(id));
        event.put("pubkey", pubkey);
        event.put("created_at", createdAt);
        event.put("kind", 27235);
        event.set("tags", tags);
        event.put("content", "");
        event.put("sig", HexFormat.of().formatHex(Schnorr.sign(id, sk, new byte[32])));
        return "Nostr " + Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(event));
    }

    static StripePurchase purchase(long paid, Long refunded, boolean disputed) {
        StripePurchase p = new StripePurchase();
        p.setPaymentIntentId("pi_3Abc");
        p.setVoucherId("8027e345");
        p.setAmountMinor(paid);
        p.setCurrency("GBP");
        p.setRefundedAmountMinor(refunded);
        p.setDisputed(disputed);
        p.setStatus(StripePurchase.Status.DISCHARGED);
        p.setConnectedAccountId("acct_secret");
        p.setBuyerEmail("buyer@example.invalid");
        return p;
    }

    String stallUrl() {
        return ORIGIN + PurchaseStateController.PREFIX + "/stall/" + stall;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStallReadsItsOwnSalesWithNip98() throws Exception {
        when(purchases.findTop200ByStallPubkeyOrderByCreatedAtDesc(stall))
                .thenReturn(List.of(purchase(1000, 300L, false)));

        ResponseEntity<?> r = controller.byStall(stall, nip98(STALL_SK, stallUrl(), "GET", NOW));

        assertEquals(200, r.getStatusCode().value());
        PurchaseStateController.PurchaseState s = ((List<PurchaseStateController.PurchaseState>) r.getBody()).get(0);
        assertEquals("8027e345", s.voucher_id());
        assertEquals("pi_3Abc", s.ref());
        assertEquals(300L, s.refunded_amount_minor());
        assertEquals(700L, s.backed_minor());
        assertEquals("PARTLY_REFUNDED", s.status());
        assertEquals("gbp", s.currency());
        String json = MAPPER.findAndRegisterModules().writeValueAsString(s);
        assertFalse(json.contains("acct_") || json.contains("example.invalid"), json);
    }

    @Test
    void anotherKeyIsRefusedEvenWithAValidProof() throws Exception {
        ResponseEntity<?> r = controller.byStall(stall, nip98(OTHER_SK, stallUrl(), "GET", NOW));

        assertEquals(403, r.getStatusCode().value());
        verify(purchases, never()).findTop200ByStallPubkeyOrderByCreatedAtDesc(any());
    }

    @Test
    void noProofAStaleProofAnotherUrlOrTheIssuerTokenIs401() throws Exception {
        assertEquals(401, controller.byStall(stall, null).getStatusCode().value());
        assertEquals(401, controller.byStall(stall, "Bearer " + ISSUER).getStatusCode().value());
        assertEquals(401, controller.byStall(stall, nip98(STALL_SK, stallUrl(), "GET", NOW - 600))
                .getStatusCode().value());
        assertEquals(401, controller.byStall(stall, nip98(STALL_SK,
                "https://evil.example" + PurchaseStateController.PREFIX + "/stall/" + stall, "GET", NOW))
                .getStatusCode().value());
        assertEquals(401, controller.byStall(stall, nip98(STALL_SK, stallUrl(), "POST", NOW)).getStatusCode().value());
    }

    @Test
    void aMalformedStallIs400() {
        assertEquals(400, controller.byStall("nope", null).getStatusCode().value());
    }

    @Test
    void theGatewayReadsByRefWithItsOwnToken() {
        when(purchases.findByPaymentIntentId("pi_3Abc")).thenReturn(Optional.of(purchase(1000, 1000L, false)));

        ResponseEntity<?> r = controller.byRef("pi_3Abc", "Bearer " + READ);

        assertEquals(200, r.getStatusCode().value());
        PurchaseStateController.PurchaseState s = (PurchaseStateController.PurchaseState) r.getBody();
        assertEquals("REFUNDED", s.status());
        assertEquals(0L, s.backed_minor());
    }

    @Test
    void theIssuerTokenNeverReadsByRef() {
        assertEquals(401, controller.byRef("pi_3Abc", "Bearer " + ISSUER).getStatusCode().value());
        assertEquals(401, controller.byRef("pi_3Abc", null).getStatusCode().value());
    }

    @Test
    void anUnsetOrReusedReadTokenRefusesEverything() {
        assertEquals(503, controller(null).byRef("pi_3Abc", "Bearer x").getStatusCode().value());
        assertEquals(503, controller(ISSUER).byRef("pi_3Abc", "Bearer " + ISSUER).getStatusCode().value());
    }

    @Test
    void unknownRefIs404AndNonPiIs400() {
        when(purchases.findByPaymentIntentId(any())).thenReturn(Optional.empty());
        assertEquals(404, controller.byRef("pi_unknown", "Bearer " + READ).getStatusCode().value());
        assertEquals(400, controller.byRef("ch_123", "Bearer " + READ).getStatusCode().value());
    }

    @Test
    void stateMapping() {
        assertEquals("PAID", PurchaseStateController.PurchaseState.of(purchase(1000, null, false)).status());
        assertEquals(1000L, PurchaseStateController.PurchaseState.of(purchase(1000, null, false)).backed_minor());
        PurchaseStateController.PurchaseState over = PurchaseStateController.PurchaseState.of(purchase(1000, 1200L, false));
        assertEquals("REFUNDED", over.status());
        assertEquals(1000L, over.refunded_amount_minor());
        PurchaseStateController.PurchaseState disputed = PurchaseStateController.PurchaseState.of(purchase(1000, 300L, true));
        assertEquals("DISPUTED", disputed.status());
        assertEquals(0L, disputed.backed_minor());
    }

    @Test
    void aHolderReadsOnlyStatusAndAmountsByRef() throws Exception {
        when(purchases.findByPaymentIntentId("pi_3Abc")).thenReturn(Optional.of(purchase(1000, 300L, false)));

        ResponseEntity<?> r = controller.forHolder("pi_3Abc", null);

        assertEquals(200, r.getStatusCode().value());
        PurchaseStateController.HolderState s = (PurchaseStateController.HolderState) r.getBody();
        assertEquals("PARTLY_REFUNDED", s.status());
        assertEquals(700L, s.backed_minor());
        String json = MAPPER.findAndRegisterModules().writeValueAsString(s);
        assertFalse(json.contains("8027e345") || json.contains("acct_") || json.contains("example.invalid"), json);
    }

    @Test
    void aHolderGets404ForUnknownAndMalformedAlike() {
        when(purchases.findByPaymentIntentId(any())).thenReturn(Optional.empty());
        assertEquals(404, controller.forHolder("pi_nope", null).getStatusCode().value());
        assertEquals(404, controller.forHolder("ch_nope", null).getStatusCode().value());
    }

    @Test
    void theHolderReadIsRateLimited() {
        when(purchases.findByPaymentIntentId(any())).thenReturn(Optional.empty());
        int limited = 0;
        for (int i = 0; i < 40; i++) {
            if (controller.forHolder("pi_x", null).getStatusCode().value() == 429) {
                limited++;
            }
        }
        assertEquals(true, limited > 0);
    }
}
