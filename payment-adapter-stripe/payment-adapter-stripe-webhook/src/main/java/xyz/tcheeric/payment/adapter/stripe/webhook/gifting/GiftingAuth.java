package xyz.tcheeric.payment.adapter.stripe.webhook.gifting;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import xyz.tcheeric.nap.core.AclDecision;
import xyz.tcheeric.nap.core.NapErrorCode;
import xyz.tcheeric.nap.core.SessionRecord;
import xyz.tcheeric.nap.core.SessionStore;
import xyz.tcheeric.nap.server.IssueChallengeInput;
import xyz.tcheeric.nap.server.IssueChallengeResult;
import xyz.tcheeric.nap.server.NapServer;
import xyz.tcheeric.nap.server.NapServerOptions;
import xyz.tcheeric.nap.server.VerifyCompletionInput;
import xyz.tcheeric.nap.server.VerifyCompletionOutcome;
import xyz.tcheeric.nap.server.store.InMemoryChallengeStore;
import xyz.tcheeric.nap.server.store.InMemorySessionStore;

/**
 * Signing in to the gifting site: a NAP session, customers only (imani-wallet#410).
 *
 * <p>The page's cross-origin signing grant dies when the page leaves for Stripe, so it is used
 * once to complete a NAP login here; from then on the browser carries an HttpOnly cookie that
 * survives the top-level navigation back (SameSite=Lax). Mirrors the subscription site's
 * {@code services/subscription/auth.ts}.
 *
 * <p>A stall (or a key whose role cannot be read) is refused AT LOGIN; the role is re-read when a
 * draft is created, as before. Sessions are in memory: a restart signs everyone out.
 */
public class GiftingAuth {

    public static final String SESSION_COOKIE = "imani_gifting_session";
    public static final String COOKIE_PATH = "/api/v1/gifting";
    public static final String COMPLETE_PATH = "/api/v1/gifting/auth/complete";
    public static final int IDLE_TTL_SECONDS = 30 * 60;
    public static final int ABSOLUTE_TTL_SECONDS = 2 * 60 * 60;
    static final String ROLE_CUSTOMER = "customer";
    static final String STALL_REFUSED = GiftingService.STALL_BUYER;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NapServer nap;
    private final SessionStore sessions;
    private final Clock clock;
    private final String completeUrl;
    private final boolean secureCookie;

    /** What a controller sends: status, body, and an optional Set-Cookie value. */
    public record Reply(HttpStatus status, Object body, String setCookie) {
    }

    public GiftingAuth(String publicOrigin, BuyerRoleReader.Reader roles, Clock clock) {
        this(publicOrigin, roles, clock, NapServerOptions.DEFAULT_MIN_AUTH_RESPONSE_MILLIS, NapServerOptions.DEFAULT_RESPONSE_JITTER_MILLIS);
    }

    GiftingAuth(String publicOrigin, BuyerRoleReader.Reader roles, Clock clock, int minResponseMillis, int jitterMillis) {
        String origin = publicOrigin == null ? "" : publicOrigin.trim().replaceAll("/+$", "");
        this.completeUrl = origin + COMPLETE_PATH;
        this.secureCookie = origin.startsWith("https://");
        this.clock = clock;
        this.sessions = new InMemorySessionStore(clock);
        this.nap = NapServer.create(NapServerOptions.builder()
                .challengeStore(new InMemoryChallengeStore(clock))
                .sessionStore(sessions)
                .aclResolver((npub, pubkey) -> customersOnly(roles, pubkey))
                .clock(clock)
                .sessionTtlSeconds(IDLE_TTL_SECONDS)
                .sessionIdleTtlSeconds(IDLE_TTL_SECONDS)
                .sessionAbsoluteTtlSeconds(ABSOLUTE_TTL_SECONDS)
                .minAuthResponseMillis(minResponseMillis)
                .responseJitterMillis(jitterMillis)
                .build());
    }

    private static AclDecision customersOnly(BuyerRoleReader.Reader roles, String pubkey) {
        BuyerRoleReader.Standing standing = roles.standingOf(pubkey);
        if (standing == BuyerRoleReader.Standing.CUSTOMER) {
            return AclDecision.allowed(List.of(ROLE_CUSTOMER), List.of());
        }
        return AclDecision.denied(standing.name().toLowerCase(), standing == BuyerRoleReader.Standing.MERCHANT);
    }

    /** NAP §10.1: a challenge for {@code {"npub": ...}}. */
    public Reply init(byte[] body, String clientIp) {
        String npub;
        try {
            npub = MAPPER.readTree(body).path("npub").asText(null);
        } catch (Exception e) {
            npub = null;
        }
        if (npub == null) {
            return failure();
        }
        IssueChallengeResult result = nap.issueChallenge(new IssueChallengeInput(npub, completeUrl, "POST", clientIp));
        if (result instanceof IssueChallengeResult.Success ok) {
            return new Reply(HttpStatus.OK, ok.value(), null);
        }
        return failure();
    }

    /** NAP §10.2: the NIP-98 proof over {@code u = <origin>/api/v1/gifting/auth/complete}. Sets the cookie. */
    public Reply complete(String authorization, byte[] body, String clientIp) {
        VerifyCompletionOutcome outcome = nap.verifyCompletion(
                new VerifyCompletionInput(authorization, "POST", completeUrl, body == null ? new byte[0] : body, clientIp));
        if (outcome instanceof VerifyCompletionOutcome.Success ok) {
            return signedIn(ok.session());
        }
        if (outcome instanceof VerifyCompletionOutcome.Failure f && f.code() == NapErrorCode.NAP_COMPLETE_ACL_DENIED) {
            return new Reply(HttpStatus.FORBIDDEN, Map.of("error", STALL_REFUSED), null);
        }
        return failure();
    }

    private Reply signedIn(SessionRecord session) {
        // The token goes in the HttpOnly cookie only, never in the body.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("pubkey", session.principalPubkey());
        out.put("expiresAt", session.expiresAt());
        return new Reply(HttpStatus.OK, out, cookie(session.accessToken(), ABSOLUTE_TTL_SECONDS));
    }

    /** {@code {pubkey}} for a live session (sliding its idle window), or 401. */
    public Reply session(String cookieHeader) {
        return buyer(cookieHeader)
                .map(pubkey -> new Reply(HttpStatus.OK, Map.of("pubkey", pubkey), null))
                .orElseGet(() -> new Reply(HttpStatus.UNAUTHORIZED, Map.of("error", "not signed in"), null));
    }

    public Reply logout(String cookieHeader) {
        live(cookieHeader).ifPresent(s -> sessions.revokeBySessionId(s.sessionId(), now()));
        return new Reply(HttpStatus.OK, Map.of("status", "ok"), cookie("", 0));
    }

    /** The signed-in buyer's pubkey behind this Cookie header, touching the idle window. */
    public Optional<String> buyer(String cookieHeader) {
        return live(cookieHeader).map(s -> {
            long now = now();
            sessions.touch(s.sessionId(), now, Math.min(now + IDLE_TTL_SECONDS, s.absoluteExpiryAt()));
            return s.principalPubkey();
        });
    }

    private Optional<SessionRecord> live(String cookieHeader) {
        return readCookie(cookieHeader, SESSION_COOKIE)
                .flatMap(sessions::getByAccessToken)
                .filter(s -> s.isActive(now()));
    }

    String cookie(String value, int maxAge) {
        return SESSION_COOKIE + "=" + value + "; Path=" + COOKIE_PATH + "; HttpOnly; SameSite=Lax"
                + (secureCookie ? "; Secure" : "") + "; Max-Age=" + maxAge;
    }

    private static Reply failure() {
        return new Reply(HttpStatus.UNAUTHORIZED, Map.of("status", "error", "message", "Authentication failed"), null);
    }

    private long now() {
        return clock.instant().getEpochSecond();
    }

    static Optional<String> readCookie(String header, String name) {
        if (header == null) {
            return Optional.empty();
        }
        for (String part : header.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).equals(name) && eq + 1 < p.length()) {
                return Optional.of(p.substring(eq + 1));
            }
        }
        return Optional.empty();
    }
}
