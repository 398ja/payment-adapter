package xyz.tcheeric.payment.adapter.stripe.webhook.gifting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import nostr.crypto.bech32.Bech32;
import nostr.crypto.bech32.Bech32Prefix;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/** imani-wallet#410: the gifting site's NAP login, so a signed-in buyer survives the trip to Stripe. */
class GiftingAuthTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String ORIGIN = "https://gifting.imani.casa";
    static final String COMPLETE = ORIGIN + "/api/v1/gifting/auth/complete";
    static final byte[] SK = HexFormat.of().parseHex("5e".repeat(32));

    final MutableClock clock = new MutableClock(Instant.now());
    final AtomicReference<BuyerRoleReader.Standing> standing = new AtomicReference<>(BuyerRoleReader.Standing.CUSTOMER);
    final GiftingAuth auth = new GiftingAuth(ORIGIN, pk -> standing.get(), clock, 0, 0);

    static String pubkey() throws Exception {
        return HexFormat.of().formatHex(Schnorr.genPubKey(SK));
    }

    /** Runs init then complete as the SPA does, answering complete's reply. */
    GiftingAuth.Reply login() throws Exception {
        String npub = Bech32.toBech32(Bech32Prefix.NPUB, pubkey());
        GiftingAuth.Reply init = auth.init(("{\"npub\":\"" + npub + "\"}").getBytes(StandardCharsets.UTF_8), "1.2.3.4");
        assertEquals(HttpStatus.OK, init.status());
        JsonNode challenge = MAPPER.valueToTree(init.body());
        assertEquals(COMPLETE, challenge.path("auth_url").asText());
        byte[] body = MAPPER.writeValueAsBytes(Map.of("challenge_id", challenge.path("challenge_id").asText()));
        return auth.complete(proof(challenge, body), body, "1.2.3.4");
    }

    String proof(JsonNode challenge, byte[] body) throws Exception {
        long createdAt = clock.instant().getEpochSecond();
        ArrayNode tags = MAPPER.createArrayNode();
        tags.add(MAPPER.createArrayNode().add("u").add(COMPLETE));
        tags.add(MAPPER.createArrayNode().add("method").add("POST"));
        tags.add(MAPPER.createArrayNode().add("payload").add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))));
        tags.add(MAPPER.createArrayNode().add("challenge").add(challenge.path("challenge").asText()));
        tags.add(MAPPER.createArrayNode().add("challenge_id").add(challenge.path("challenge_id").asText()));
        ArrayNode canonical = MAPPER.createArrayNode().add(0).add(pubkey()).add(createdAt).add(27235);
        canonical.add(tags);
        canonical.add("");
        byte[] id = MessageDigest.getInstance("SHA-256").digest(MAPPER.writeValueAsBytes(canonical));
        ObjectNode event = MAPPER.createObjectNode();
        event.put("id", HexFormat.of().formatHex(id));
        event.put("pubkey", pubkey());
        event.put("created_at", createdAt);
        event.put("kind", 27235);
        event.set("tags", tags);
        event.put("content", "");
        event.put("sig", HexFormat.of().formatHex(Schnorr.sign(id, SK, new byte[32])));
        return "Nostr " + Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(event));
    }

    static String cookieHeader(GiftingAuth.Reply reply) {
        return reply.setCookie().split(";")[0];
    }

    // A customer signs in and gets a session cookie; the token is never in the body.
    @Test
    void aCustomerSignsInWithAnHttpOnlyLaxCookie() throws Exception {
        GiftingAuth.Reply r = login();
        assertEquals(HttpStatus.OK, r.status());
        assertEquals(pubkey(), ((Map<?, ?>) r.body()).get("pubkey"));
        String cookie = r.setCookie();
        assertTrue(cookie.startsWith("imani_gifting_session="));
        assertTrue(cookie.contains("; Path=/api/v1/gifting"));
        assertTrue(cookie.contains("; HttpOnly"));
        assertTrue(cookie.contains("; SameSite=Lax"));
        assertTrue(cookie.contains("; Secure"));
        assertFalse(String.valueOf(r.body()).contains(cookieHeader(r).split("=", 2)[1]));
        assertEquals(Map.of("pubkey", pubkey()), auth.session(cookieHeader(r)).body());
    }

    // A plain-http local origin drops Secure so the cookie still works.
    @Test
    void plainHttpOriginsGetNoSecureFlag() {
        assertFalse(new GiftingAuth("http://localhost:5173", pk -> standing.get(), clock, 0, 0).cookie("x", 1).contains("Secure"));
    }

    // A stall's key is refused at login, and so is a key whose role cannot be read.
    @Test
    void aStallOrAnUncheckedKeyIsRefusedAtLogin() throws Exception {
        standing.set(BuyerRoleReader.Standing.MERCHANT);
        GiftingAuth.Reply stall = login();
        assertEquals(HttpStatus.FORBIDDEN, stall.status());
        assertNull(stall.setCookie());
        standing.set(BuyerRoleReader.Standing.UNKNOWN);
        assertEquals(HttpStatus.FORBIDDEN, login().status());
    }

    // Logout revokes the session server-side and clears the cookie.
    @Test
    void logoutRevokesTheSession() throws Exception {
        String cookie = cookieHeader(login());
        GiftingAuth.Reply out = auth.logout(cookie);
        assertTrue(out.setCookie().contains("Max-Age=0"));
        assertEquals(HttpStatus.UNAUTHORIZED, auth.session(cookie).status());
        assertTrue(auth.buyer(cookie).isEmpty());
    }

    // Idle for 30 minutes ends a session; activity keeps it alive up to the 2 h absolute cap.
    @Test
    void sessionsLapseWhenIdleAndAtTheAbsoluteCap() throws Exception {
        String idle = cookieHeader(login());
        clock.advance(Duration.ofMinutes(31));
        assertEquals(HttpStatus.UNAUTHORIZED, auth.session(idle).status());

        String active = cookieHeader(login());
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMinutes(20));
            assertEquals(HttpStatus.OK, auth.session(active).status(), "after " + (i + 1) * 20 + " min");
        }
        clock.advance(Duration.ofMinutes(21));
        assertEquals(HttpStatus.UNAUTHORIZED, auth.session(active).status());
    }

    // No cookie, or an unknown one, is not signed in.
    @Test
    void noOrForgedCookieIsNotSignedIn() {
        assertEquals(HttpStatus.UNAUTHORIZED, auth.session(null).status());
        assertEquals(HttpStatus.UNAUTHORIZED, auth.session("imani_gifting_session=forged").status());
    }

    // The draft endpoint takes the buyer from the session cookie, with no NIP-98 header.
    @Test
    void aDraftIsCreatedAsTheCookiesBuyer() throws Exception {
        String cookie = cookieHeader(login());
        GiftingService service = mock(GiftingService.class);
        when(service.create(any(), any())).thenReturn(new GiftingService.Answer(HttpStatus.CREATED, Map.of()));
        GiftingController controller = new GiftingController(service, new Nip98Verifier(),
                new RateLimiter(100, 100, clock), ORIGIN, "");
        controller.setAuth(auth);
        byte[] body = "{\"stallPubkey\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        var response = controller.create(body, null, "other=1; " + cookie, new MockHttpServletRequest());
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(service).create(any(), eq(pubkey()));
    }

    // Without a cookie the draft is a guest's, as before.
    @Test
    void withoutACookieTheDraftIsAGuests() {
        GiftingService service = mock(GiftingService.class);
        when(service.create(any(), any())).thenReturn(new GiftingService.Answer(HttpStatus.CREATED, Map.of()));
        GiftingController controller = new GiftingController(service, new Nip98Verifier(),
                new RateLimiter(100, 100, clock), ORIGIN, "");
        controller.setAuth(auth);
        assertNotNull(controller.create("{}".getBytes(), null, null, new MockHttpServletRequest()));
        verify(service).create(any(), eq(null));
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
