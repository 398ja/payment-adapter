package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseStateControllerTest.NOW;
import static xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseStateControllerTest.nip98;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.gifting.Nip98Verifier;

/** imani-wallet#275: a stall lists its own card sales, so its wallet can file a missed record. */
class StallPurchasesControllerTest {

    static final byte[] STALL_SK = HexFormat.of().parseHex("7f".repeat(32));
    static final byte[] OTHER_SK = HexFormat.of().parseHex("3c".repeat(32));
    static final String ORIGIN = "https://wallet.imani.casa";
    static final String TOKEN = "issuer-token";

    final StripePurchaseRepository purchases = mock(StripePurchaseRepository.class);
    StallPurchasesController controller;
    String stall;

    @BeforeEach
    void setUp() throws Exception {
        stall = HexFormat.of().formatHex(Schnorr.genPubKey(STALL_SK));
        controller = new StallPurchasesController(purchases,
                new Nip98Verifier(Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC)), List.of(ORIGIN + "/"));
    }

    String url() {
        return ORIGIN + "/api/v1/purchases?stall=" + stall;
    }

    static StripePurchase sale() {
        StripePurchase p = PurchaseStateControllerTest.purchase(650, null, false);
        p.setCreatedAt(Instant.parse("2026-11-01T10:00:00Z"));
        p.setRecipientPubkey("b".repeat(64));
        p.setLivemode(false);
        return p;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStallListsItsOwnMintedSalesWithTheIndependentPaidTime() throws Exception {
        when(purchases.findTop200ByStallPubkeyAndVoucherIdIsNotNullOrderByCreatedAtDesc(stall))
                .thenReturn(List.of(sale()));

        ResponseEntity<?> r = controller.byStall(stall, nip98(STALL_SK, url(), "GET", NOW), mock(HttpServletRequest.class));

        assertEquals(200, r.getStatusCode().value());
        var s = ((List<StallPurchasesController.StallPurchase>) r.getBody()).get(0);
        assertEquals("8027e345", s.voucher_id());
        assertEquals("pi_3Abc", s.ref());
        assertEquals(650L, s.amount_minor());
        assertEquals("gbp", s.currency());
        assertEquals("DISCHARGED", s.status());
        assertEquals("PAID", s.payment());
        assertEquals(Instant.parse("2026-11-01T10:00:00Z"), s.paid_at());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(s);
        assertFalse(json.contains("acct_") || json.contains("example.invalid") || json.contains("b".repeat(64)), json);
    }

    @Test
    void anotherKeyIs403AndReadsNothing() throws Exception {
        ResponseEntity<?> r = controller.byStall(stall, nip98(OTHER_SK, url(), "GET", NOW), mock(HttpServletRequest.class));

        assertEquals(403, r.getStatusCode().value());
        verify(purchases, never()).findTop200ByStallPubkeyAndVoucherIdIsNotNullOrderByCreatedAtDesc(any());
    }

    @Test
    void noProofAStaleProofOrAProofForAnotherStallsUrlIs401() throws Exception {
        HttpServletRequest http = mock(HttpServletRequest.class);
        assertEquals(401, controller.byStall(stall, null, http).getStatusCode().value());
        assertEquals(401, controller.byStall(stall, nip98(STALL_SK, url(), "GET", NOW - 120), http).getStatusCode().value());
        String otherUrl = ORIGIN + "/api/v1/purchases?stall=" + "a".repeat(64);
        assertEquals(401, controller.byStall(stall, nip98(STALL_SK, otherUrl, "GET", NOW), http).getStatusCode().value());
        assertEquals(401, controller.byStall(stall, nip98(STALL_SK, url(), "POST", NOW), http).getStatusCode().value());
        verify(purchases, never()).findTop200ByStallPubkeyAndVoucherIdIsNotNullOrderByCreatedAtDesc(any());
    }

    @Test
    void aMalformedStallIs400() {
        assertEquals(400, controller.byStall("not-hex", null, null).getStatusCode().value());
        assertEquals(400, controller.byStall(null, null, null).getStatusCode().value());
    }

    @Test
    void noOriginConfiguredRefusesStallsButNotTheIssuer() {
        StallPurchasesController unconfigured = new StallPurchasesController(purchases, new Nip98Verifier(), List.of());
        assertEquals(503, unconfigured.byStall(stall, null, mock(HttpServletRequest.class)).getStatusCode().value());

        HttpServletRequest issuer = mock(HttpServletRequest.class);
        when(issuer.getAttribute(PurchaseApiTokenFilter.ISSUER_ADMITTED)).thenReturn(Boolean.TRUE);
        assertEquals(200, unconfigured.byStall(stall, "Bearer " + TOKEN, issuer).getStatusCode().value());
    }

    // ---------------------------------------------------------------- the filter

    HttpServletRequest request(String method, String uri, String authorization) {
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getMethod()).thenReturn(method);
        when(http.getRequestURI()).thenReturn(uri);
        when(http.getHeader("Authorization")).thenReturn(authorization);
        return http;
    }

    @Test
    void theFilterLetsOnlyTheStallGetThroughWithoutTheToken() throws Exception {
        PurchaseApiTokenFilter filter = new PurchaseApiTokenFilter(TOKEN, "op3rator");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        HttpServletRequest stallRead = request("GET", "/api/v1/purchases", "Nostr abc");
        filter.doFilter(stallRead, response, chain);
        verify(chain).doFilter(stallRead, response);
        verify(stallRead, never()).setAttribute(eq(PurchaseApiTokenFilter.ISSUER_ADMITTED), any());

        // A Nostr header opens nothing else under the prefix.
        for (String[] other : new String[][] {
                {"GET", "/api/v1/purchases/owed"}, {"POST", "/api/v1/purchases"},
                {"GET", "/api/v1/purchases/attention"}, {"POST", "/api/v1/purchases/evt/discharge"}}) {
            HttpServletRequest r = request(other[0], other[1], "Nostr abc");
            filter.doFilter(r, response, chain);
            verify(chain, never()).doFilter(eq(r), any());
        }
        verify(response, org.mockito.Mockito.atLeast(4)).sendError(anyInt());
    }

    @Test
    void theFilterMarksTheIssuerOnlyForItsOwnToken() throws Exception {
        PurchaseApiTokenFilter filter = new PurchaseApiTokenFilter(TOKEN, "op3rator");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        HttpServletRequest issuer = request("GET", "/api/v1/purchases", "Bearer " + TOKEN);
        filter.doFilter(issuer, response, chain);
        verify(issuer).setAttribute(PurchaseApiTokenFilter.ISSUER_ADMITTED, Boolean.TRUE);

        HttpServletRequest operatorReadingAttention = request("GET", "/api/v1/purchases/attention", "Bearer op3rator");
        filter.doFilter(operatorReadingAttention, response, chain);
        verify(operatorReadingAttention, never()).setAttribute(eq(PurchaseApiTokenFilter.ISSUER_ADMITTED), any());

        HttpServletRequest wrong = request("GET", "/api/v1/purchases", "Bearer nope");
        filter.doFilter(wrong, response, chain);
        verify(chain, never()).doFilter(eq(wrong), any());
    }
}
