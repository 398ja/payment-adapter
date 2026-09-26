package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The surface the issuer talks to, and the thing standing in front of it.
 *
 * <p>Two concerns, tested together because they fail together: an endpoint that
 * lists every stall's sales and closes debts, and the only authentication this
 * service has.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchaseControllerTest {

    private static final String TOKEN = "s3cret-token";

    @Mock private StripePurchaseRepository purchases;
    @Mock private PurchaseDischargeService discharges;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    private PurchaseController controller;

    @BeforeEach
    void setUp() {
        controller = new PurchaseController(purchases, discharges);
    }

    private static StripePurchase owed(String eventId) {
        StripePurchase purchase = new StripePurchase();
        purchase.setEventId(eventId);
        purchase.setPaymentRequestId("0123456789abcdef0123456789abcdef");
        purchase.setConnectedAccountId("acct_1");
        purchase.setPaymentIntentId("pi_1");
        purchase.setRecipientPubkey("a".repeat(64));
        purchase.setAmountMinor(2500L);
        purchase.setCurrency("gbp");
        purchase.setStatus(StripePurchase.Status.OWED);
        return purchase;
    }

    @Test
    void listsWhatIsOwedWithoutLeakingThePaymentThatFundedIt() {
        // The issuer mints coupons. Handing it the Stripe session or charge
        // would invite code that acts on payments it has no business touching.
        when(purchases.findClaimable(eq(StripePurchase.Status.OWED), eq(StripePurchase.Status.ISSUING), any()))
                .thenReturn(List.of(owed("evt_1")));

        List<PurchaseController.OwedPurchase> result = controller.owed();

        assertEquals(1, result.size());
        assertEquals("evt_1", result.get(0).eventId());
        assertEquals("0123456789abcdef0123456789abcdef", result.get(0).paymentRequestId());
        // No session and no charge: the record has no field for them. The one
        // payment handle is the intent, carried as an opaque warrant reference.
        assertEquals("pi_1", result.get(0).processorReference());
    }

    @Test
    void boundsTheBatchSoAWorkerDoesNotStallOnABusyDay() {
        List<StripePurchase> many = java.util.stream.IntStream.range(0, 250)
                .mapToObj(i -> owed("evt_" + i))
                .toList();
        when(purchases.findClaimable(any(), any(), any())).thenReturn(many);

        assertEquals(100, controller.owed().size());
    }

    @Test
    void aClaimIsOkOnlyForTheWinnerAndConflictForEveryoneElse() {
        // imani-wallet#96: the issuer mints only on 200. A 409 must never be
        // read as "try minting anyway".
        when(discharges.claim("evt_1", "w1")).thenReturn(PurchaseDischargeService.ClaimResult.CLAIMED);
        when(discharges.claim("evt_1", "w2")).thenReturn(PurchaseDischargeService.ClaimResult.NOT_CLAIMABLE);
        when(discharges.claim("evt_x", null)).thenReturn(PurchaseDischargeService.ClaimResult.UNKNOWN_PURCHASE);

        assertEquals(HttpStatus.OK,
                controller.claim("evt_1", new PurchaseController.ClaimRequest("w1")).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,
                controller.claim("evt_1", new PurchaseController.ClaimRequest("w2")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.claim("evt_x", null).getStatusCode());
    }

    @Test
    void answersOkWhenTheGatewayConfirms() {
        when(discharges.discharge(anyString(), anyString(), any()))
                .thenReturn(PurchaseDischargeService.Result.DISCHARGED);

        ResponseEntity<Void> result = controller.discharge("evt_1",
                new PurchaseController.DischargeRequest("v1", "b".repeat(64)));

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void answersConflictWhenTheGatewaySeesNoSuchSend() {
        // The issuer claimed something that did not happen. Not a server fault,
        // so not a 5xx: a caller retrying this forever would never succeed.
        when(discharges.discharge(anyString(), anyString(), any()))
                .thenReturn(PurchaseDischargeService.Result.REFUSED);

        assertEquals(HttpStatus.CONFLICT, controller.discharge("evt_1",
                new PurchaseController.DischargeRequest("v1", null)).getStatusCode());
    }

    @Test
    void answersServiceUnavailableWhenTheGatewayCannotBeAsked() {
        // Retryable, and distinct from CONFLICT on purpose: one means come
        // back, the other means you are wrong.
        when(discharges.discharge(anyString(), anyString(), any()))
                .thenReturn(PurchaseDischargeService.Result.UNVERIFIABLE);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.discharge("evt_1",
                new PurchaseController.DischargeRequest("v1", null)).getStatusCode());
    }

    @Test
    void refusesADischargeThatNamesNoCoupon() {
        // A discharge with no voucher id is one nobody could trace afterwards,
        // which defeats recording it at all.
        assertEquals(HttpStatus.BAD_REQUEST, controller.discharge("evt_1",
                new PurchaseController.DischargeRequest(" ", null)).getStatusCode());
        verify(discharges, never()).discharge(anyString(), anyString(), any());
    }

    @Test
    void acceptsAFailureReportWithoutClosingAnything() {
        ResponseEntity<Void> result = controller.failure("evt_1",
                new PurchaseController.FailureRequest("gateway down", false, null, "w1", null));

        // 202, not 200: the report is accepted and nothing was resolved.
        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode());
        verify(discharges).recordFailure("evt_1", "gateway down", false, null, "w1", false);
    }

    @Test
    void theFilterRefusesEverythingWhenNoTokenIsConfigured() throws Exception {
        // Refusing, not allowing. A deployment that forgot to configure this
        // fails loudly rather than serving every stall's sales to anyone.
        when(request.getRequestURI()).thenReturn("/api/v1/purchases/owed");

        new PurchaseApiTokenFilter(null).doFilter(request, response, chain);

        verify(response).sendError(anyInt(), anyString());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void theFilterRefusesAWrongOrMissingToken() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/purchases/owed");
        PurchaseApiTokenFilter filter = new PurchaseApiTokenFilter(TOKEN);

        when(request.getHeader("Authorization")).thenReturn(null);
        filter.doFilter(request, response, chain);

        when(request.getHeader("Authorization")).thenReturn("Bearer wrong");
        filter.doFilter(request, response, chain);

        when(request.getHeader("Authorization")).thenReturn(TOKEN);
        filter.doFilter(request, response, chain);

        verify(response, org.mockito.Mockito.times(3)).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void theFilterAdmitsTheRightToken() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/purchases/owed");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + TOKEN);

        new PurchaseApiTokenFilter(TOKEN).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void theFilterIsNotFooledByEncodingOrContextPaths() throws Exception {
        // The guard reads the RAW uri, which carries percent-encoding and any
        // context path. A request Spring routes to the controller by a path the
        // naive startsWith did not recognise would have skipped the token
        // entirely - reading every stall's outstanding sales unauthenticated.
        PurchaseApiTokenFilter filter = new PurchaseApiTokenFilter(TOKEN);
        when(request.getHeader("Authorization")).thenReturn(null);

        String[] disguises = {
            "/api/v1/%70urchases/owed",       // encoded 'p'
            "/api/v1/%2570urchases/owed",     // doubly encoded
            "/api/v1//purchases/owed",        // doubled slash
            "/api/v1/purchases",              // bare, no trailing slash
        };

        for (String uri : disguises) {
            when(request.getRequestURI()).thenReturn(uri);
            filter.doFilter(request, response, chain);
        }

        verify(response, org.mockito.Mockito.times(disguises.length))
                .sendError(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void theFilterProtectsBehindAContextPath() throws Exception {
        // Deployed under a context path the uri is prefixed, and the guard
        // would not recognise its own surface.
        when(request.getRequestURI()).thenReturn("/adapter/api/v1/purchases/owed");
        when(request.getContextPath()).thenReturn("/adapter");
        when(request.getHeader("Authorization")).thenReturn(null);

        new PurchaseApiTokenFilter(TOKEN).doFilter(request, response, chain);

        verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void theFilterRefusesRatherThanGuessesOnMalformedEncoding() throws Exception {
        // Cannot be classified, so it is protected. A 401 on something harmless
        // costs a retry; the other direction serves debts to anyone.
        when(request.getRequestURI()).thenReturn("/api/v1/purchases/%zz");
        when(request.getHeader("Authorization")).thenReturn(null);

        new PurchaseApiTokenFilter(TOKEN).doFilter(request, response, chain);

        verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void theFilterLeavesTheWebhookAlone() throws Exception {
        // The webhook authenticates by Stripe signature and holds no bearer
        // token. Guarding it here would break the thing that feeds purchases.
        when(request.getRequestURI()).thenReturn("/webhook/stripe");

        new PurchaseApiTokenFilter(TOKEN).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).sendError(anyInt());
    }
}
