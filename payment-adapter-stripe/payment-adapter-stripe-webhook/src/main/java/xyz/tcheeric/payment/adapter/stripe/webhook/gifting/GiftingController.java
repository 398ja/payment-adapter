package xyz.tcheeric.payment.adapter.stripe.webhook.gifting;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/gifting}: the Purchase Draft API behind gifting.imani.casa (imani-wallet#410 §3).
 *
 * <p>Public like {@code /api/v1/checkout/purchase}: a draft is an invitation to pay a stall that
 * already takes cards, and it moves no money. Rate limited per client IP (a service limit, not a
 * purchase cap: G6). The body is read raw so NIP-98's {@code payload} hash covers the exact bytes.
 */
@RestController
@RequestMapping("/api/v1/gifting")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "purchase.gifting", name = "enabled", havingValue = "true")
public class GiftingController {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final GiftingService service;
    private final Nip98Verifier nip98;
    private final RateLimiter perIp;
    /** The public origin requests are signed against, e.g. https://gifting.imani.casa. */
    private final String publicOrigin;
    /** The Purchase Issuer's x-only pubkey, hex: what the page seals ℓ to (G4). */
    private final String issuerPubkey;
    /** NAP login (cookie sessions). Absent only in tests that predate it. */
    private GiftingAuth auth;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAuth(GiftingAuth auth) {
        this.auth = auth;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GiftingController(GiftingService service,
            @org.springframework.beans.factory.annotation.Value("${purchase.gifting.public-origin:https://gifting.imani.casa}") String publicOrigin,
            @org.springframework.beans.factory.annotation.Value("${purchase.gifting.issuer-pubkey:}") String issuerPubkey,
            @org.springframework.beans.factory.annotation.Value("${purchase.gifting.drafts-per-minute:10}") int draftsPerMinute) {
        this(service, new Nip98Verifier(), new RateLimiter(draftsPerMinute, draftsPerMinute / 60.0, java.time.Clock.systemUTC()),
                publicOrigin, issuerPubkey == null ? "" : issuerPubkey.trim().toLowerCase());
    }

    public GiftingController(GiftingService service, Nip98Verifier nip98, RateLimiter perIp, String publicOrigin,
            String issuerPubkey) {
        this.service = service;
        this.nip98 = nip98;
        this.perIp = perIp;
        this.publicOrigin = publicOrigin == null ? "" : publicOrigin.replaceAll("/+$", "");
        this.issuerPubkey = issuerPubkey;
    }

    @PostMapping("/drafts")
    public ResponseEntity<?> create(@RequestBody(required = false) byte[] body,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Cookie", required = false) String cookie, HttpServletRequest http) {
        if (!perIp.tryAcquire(clientIp(http))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(java.util.Map.of("error", GiftingService.RATE_LIMITED));
        }
        if (body == null || body.length == 0 || body.length > 16 * 1024) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "a gift is required"));
        }
        GiftingService.DraftRequest req;
        try {
            req = MAPPER.readValue(new String(body, StandardCharsets.UTF_8), GiftingService.DraftRequest.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "that is not a gift"));
        }
        // The session cookie is preferred; a per-request NIP-98 header still works.
        String buyer = auth == null ? null : auth.buyer(cookie).orElse(null);
        if (buyer == null && authorization != null) {
            Optional<String> signer = nip98.verify(authorization, publicOrigin + "/api/v1/gifting/drafts", "POST", body);
            if (signer.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(java.util.Map.of("error", "Sign in again to continue."));
            }
            buyer = signer.get();
        }
        GiftingService.Answer a = service.create(req, buyer);
        return ResponseEntity.status(a.status()).body(a.body());
    }

    @PostMapping("/auth/init")
    public ResponseEntity<?> authInit(@RequestBody(required = false) byte[] body, HttpServletRequest http) {
        if (!perIp.tryAcquire(clientIp(http))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(java.util.Map.of("error", GiftingService.RATE_LIMITED));
        }
        return reply(auth().init(body == null ? new byte[0] : body, clientIp(http)));
    }

    @PostMapping("/auth/complete")
    public ResponseEntity<?> authComplete(@RequestBody(required = false) byte[] body,
            @RequestHeader(value = "Authorization", required = false) String authorization, HttpServletRequest http) {
        if (body != null && body.length > 16 * 1024) {
            return ResponseEntity.badRequest().build();
        }
        return reply(auth().complete(authorization, body, clientIp(http)));
    }

    @GetMapping("/auth/session")
    public ResponseEntity<?> authSession(@RequestHeader(value = "Cookie", required = false) String cookie) {
        return reply(auth().session(cookie));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<?> authLogout(@RequestHeader(value = "Cookie", required = false) String cookie) {
        return reply(auth().logout(cookie));
    }

    private GiftingAuth auth() {
        if (auth == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return auth;
    }

    private static ResponseEntity<?> reply(GiftingAuth.Reply r) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(r.status()).header("Cache-Control", "no-store");
        if (r.setCookie() != null) {
            b.header("Set-Cookie", r.setCookie());
        }
        return b.body(r.body());
    }

    @GetMapping("/drafts/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return parse(id).map(u -> {
            GiftingService.Answer a = service.get(u);
            return ResponseEntity.status(a.status()).body(a.body());
        }).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @DeleteMapping("/drafts/{id}")
    public ResponseEntity<?> invalidate(@PathVariable String id) {
        parse(id).ifPresent(service::invalidate);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/issuer-key")
    public ResponseEntity<?> issuerKey() {
        if (issuerPubkey == null || !issuerPubkey.matches("^[0-9a-f]{64}$")) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(java.util.Map.of("error", "email gifts are not available yet"));
        }
        return ResponseEntity.ok(java.util.Map.of("pubkey", issuerPubkey));
    }

    private static Optional<UUID> parse(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The edge puts the client first in X-Forwarded-For; without one, the socket's address. */
    static String clientIp(HttpServletRequest http) {
        String xff = http.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
