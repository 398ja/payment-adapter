package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The fulfilment contract over real HTTP, against a stub gateway-core.
 *
 * <p>The stub speaks imani-gateway-core#131's wire contract: {@code PUT} binds
 * {@code {recipientPubkey, issuerId, amount, unit}} (201, 200 for the same
 * terms, 409 for different ones), and {@code GET} counts only sends to the
 * registered recipient of the registered issuer and unit, answering with the
 * facts. In "old" mode it is core before #131: no PUT (405) and a GET that says
 * "fulfilled" for any send carrying the id.
 *
 * <p>The real {@link RecordingPurchaseListener}, {@link GatewayFulfilmentClient}
 * and {@link PurchaseDischargeService} run end to end; only the repositories
 * are in memory.
 */
class GatewayFulfilmentContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVICE_KEY = "3".repeat(64);
    private static final String BUYER = "a".repeat(64);
    private static final String STALL = "b".repeat(64);
    private static final String SELF = "d".repeat(64);
    private static final String ACCOUNT = "acct_1MerchantXyz";

    private HttpServer server;
    private boolean old;
    private final Map<String, JsonNode> bindings = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> sends = new ArrayList<>();
    private final List<String> seen = new ArrayList<>();

    private final Map<String, StripePurchase> rows = new ConcurrentHashMap<>();
    private RecordingPurchaseListener listener;
    private PurchaseDischargeService discharges;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/atomic/fulfilment/", this::core);
        server.start();

        GatewayFulfilmentClient client = new GatewayFulfilmentClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), SERVICE_KEY, Duration.ofSeconds(5));

        StripePurchaseRepository purchases = mock(StripePurchaseRepository.class);
        when(purchases.findByEventId(any())).thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0, String.class))));
        when(purchases.save(any())).thenAnswer(i -> {
            StripePurchase row = i.getArgument(0);
            rows.put(row.getEventId(), row);
            return row;
        });
        ConnectedStripeAccountRepository accounts = mock(ConnectedStripeAccountRepository.class);
        ConnectedStripeAccount account = new ConnectedStripeAccount();
        account.setMerchantPubkey(STALL);
        account.setStripeAccountId(ACCOUNT);
        when(accounts.findByStripeAccountId(ACCOUNT)).thenReturn(Optional.of(account));

        listener = new RecordingPurchaseListener(purchases, accounts, client);
        discharges = new PurchaseDischargeService(purchases, client);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void registersWhenTheDebtIsRecordedSignedWithAPayloadHash() throws Exception {
        // The terms reach core exactly as #131 reads them, NIP-98 signed, with
        // the payload tag core checks against the body.
        StripePurchase row = buy("evt_1");

        JsonNode terms = bindings.get(row.getPaymentRequestId());
        assertNotNull(terms, "registered at recording");
        assertEquals(BUYER, terms.get("recipientPubkey").asText());
        assertEquals(STALL, terms.get("issuerId").asText());
        assertEquals(2500, terms.get("amount").asLong());
        assertEquals("GBP", terms.get("unit").asText());
        assertTrue(seen.contains("PUT payload-ok"), "the NIP-98 payload tag matches the body");
    }

    @Test
    void aRealPaymentToTheBuyerDischarges() {
        StripePurchase row = buy("evt_1");
        send(row.getPaymentRequestId(), BUYER, 2500);

        assertEquals(PurchaseDischargeService.Result.DISCHARGED, discharges.discharge("evt_1", "voucher-1", STALL));
        assertEquals(StripePurchase.Status.DISCHARGED, rows.get("evt_1").getStatus());
    }

    @Test
    void nothingPaidIsRefused() {
        buy("evt_1");

        assertEquals(PurchaseDischargeService.Result.REFUSED, discharges.discharge("evt_1", "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, rows.get("evt_1").getStatus());
    }

    @Test
    void aOneSatSendToSelfDoesNotDischarge() {
        // The attack, end to end, against the new core: a 1-sat send to oneself
        // carrying the id is not counted, so the verdict is not fulfilled.
        StripePurchase row = buy("evt_1");
        send(row.getPaymentRequestId(), SELF, 1);

        assertEquals(PurchaseDischargeService.Result.REFUSED, discharges.discharge("evt_1", "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, rows.get("evt_1").getStatus());
    }

    @Test
    void anOldCoreNeitherBreaksRecordingNorDischargesASelfSend() {
        // Safe to merge before #131 is deployed. PUT is 405: the debt is still
        // recorded. The old GET calls a 1-sat self-send "fulfilled" with no
        // facts: we do not discharge on it.
        old = true;
        StripePurchase row = buy("evt_1");
        assertEquals(StripePurchase.Status.OWED, row.getStatus(), "recorded despite the 405");
        assertTrue(seen.contains("PUT 405"));

        send(row.getPaymentRequestId(), SELF, 1);

        assertEquals(PurchaseDischargeService.Result.UNVERIFIABLE, discharges.discharge("evt_1", "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, rows.get("evt_1").getStatus());
    }

    @Test
    void sameTermsAgainIsAcceptedAndDifferentTermsAreRefused() {
        StripePurchase row = buy("evt_1");
        GatewayFulfilmentClient client = new GatewayFulfilmentClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), SERVICE_KEY, Duration.ofSeconds(5));

        assertEquals(GatewayFulfilmentClient.Registration.ALREADY_REGISTERED,
                client.register(row.getPaymentRequestId(), BUYER, STALL, 2500, "GBP"));
        assertEquals(GatewayFulfilmentClient.Registration.CONFLICT,
                client.register(row.getPaymentRequestId(), SELF, STALL, 1, "GBP"));
    }

    private StripePurchase buy(String eventId) {
        listener.onPurchasePaid(new StripePurchaseListener.PaidPurchase(
                eventId, "cs_1", "pi_1", ACCOUNT, 2500L, "gbp", Map.of("buyer_pubkey", BUYER), false));
        return rows.get(eventId);
    }

    private void send(String requestId, String recipient, long amount) {
        sends.add(Map.of("id", requestId, "recipient", recipient, "issuer", STALL, "amount", amount, "unit", "GBP"));
    }

    /** The stub core. */
    private void core(HttpExchange exchange) throws IOException {
        String id = exchange.getRequestURI().getPath().substring("/api/v1/atomic/fulfilment/".length());
        String method = exchange.getRequestMethod();
        byte[] body = exchange.getRequestBody().readAllBytes();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");

        if (auth == null || !auth.startsWith("Nostr ")) {
            reply(exchange, 401, null);
            return;
        }

        if ("PUT".equals(method)) {
            if (old) {
                seen.add("PUT 405");
                reply(exchange, 405, null);
                return;
            }
            seen.add(payloadMatches(auth, body) ? "PUT payload-ok" : "PUT payload-bad");
            JsonNode terms = MAPPER.readTree(body);
            JsonNode existing = bindings.putIfAbsent(id, terms);
            reply(exchange, existing == null ? 201 : existing.equals(terms) ? 200 : 409, null);
            return;
        }

        if (old) {
            Optional<Map<String, Object>> any = sends.stream().filter(s -> id.equals(s.get("id"))).findFirst();
            reply(exchange, 200, any.isPresent()
                    ? Map.of("fulfilled", true, "sendId", "s1", "issuerId", any.get().get("issuer"))
                    : Map.of("fulfilled", false));
            return;
        }

        JsonNode terms = bindings.get(id);
        if (terms == null) {
            reply(exchange, 200, Map.of("fulfilled", false, "amount", 0, "reason", "unregistered"));
            return;
        }
        long total = sends.stream()
                .filter(s -> id.equals(s.get("id"))
                        && terms.get("recipientPubkey").asText().equalsIgnoreCase((String) s.get("recipient"))
                        && terms.get("issuerId").asText().equalsIgnoreCase((String) s.get("issuer"))
                        && terms.get("unit").asText().equalsIgnoreCase((String) s.get("unit")))
                .mapToLong(s -> (Long) s.get("amount")).sum();
        boolean paid = total >= terms.get("amount").asLong();
        Map<String, Object> answer = new java.util.HashMap<>(Map.of(
                "fulfilled", paid, "issuerId", terms.get("issuerId").asText(), "amount", total,
                "unit", terms.get("unit").asText(), "requested", terms.get("amount").asLong()));
        if (total > 0) {
            answer.put("recipientPubkey", terms.get("recipientPubkey").asText());
            answer.put("sendId", "s1");
        }
        if (!paid) {
            answer.put("reason", total == 0 ? "unpaid" : "underpaid");
        }
        reply(exchange, 200, answer);
    }

    private static boolean payloadMatches(String auth, byte[] body) {
        try {
            JsonNode event = MAPPER.readTree(Base64.getDecoder().decode(auth.substring("Nostr ".length())));
            String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            for (JsonNode tag : event.get("tags")) {
                if ("payload".equals(tag.get(0).asText())) {
                    return expected.equals(tag.get(1).asText());
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static void reply(HttpExchange exchange, int status, Object json) throws IOException {
        byte[] bytes = json == null ? new byte[0] : MAPPER.writeValueAsString(json).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
