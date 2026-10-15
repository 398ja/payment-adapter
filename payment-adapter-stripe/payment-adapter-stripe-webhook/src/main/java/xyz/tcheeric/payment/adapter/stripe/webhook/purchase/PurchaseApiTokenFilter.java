package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.extern.slf4j.Slf4j;

/**
 * The only thing standing in front of the purchase endpoints.
 *
 * <p>This service has no Spring Security, no filters, and no authentication of
 * any kind: every endpoint it serves is open to whatever can reach the port.
 * That was survivable while the endpoints were webhook receivers validating
 * their own signatures. It is not survivable for endpoints that list every
 * stall's outstanding sales and mark debts discharged.
 *
 * <h2>A token AND an unpublished port, not either alone</h2>
 *
 * <p>Compose publishes this service as {@code 8087:8080}. The actuator
 * endpoints were on that published port until somebody noticed they were
 * "protected only by a host firewall rule living outside this repository", and
 * moved them. The same reasoning applies here, in both directions:
 *
 * <ul>
 *   <li>A token alone is one leaked environment variable from full access.</li>
 *   <li>Network isolation alone is what that comment already judged too thin.</li>
 * </ul>
 *
 * <p>So the purchase paths want both, and this filter is the half that lives in
 * the repository. The deployment half is not publishing them.
 *
 * <h2>Why a filter rather than a check in each controller</h2>
 *
 * <p>A per-method check is one somebody forgets to add to the next endpoint,
 * and the failure is silent: a new route simply has no authentication. A filter
 * scoped to a path prefix covers routes that do not exist yet.
 *
 * <h2>An unset token refuses everything</h2>
 *
 * <p>Not "allows everything". A deployment that forgot to configure this gets
 * an endpoint that answers 503 to its own issuer, which is loud, rather than an
 * open one, which is silent until it matters.
 */
@Slf4j
public class PurchaseApiTokenFilter implements Filter {

    /** Everything behind this prefix needs the token. */
    public static final String PROTECTED_PREFIX = "/api/v1/purchases";

    private final byte[] expected;

    /**
     * The operator's token for {@code POST /{eventId}/resolve} (review of #280), or null.
     *
     * <p>A resolve closes a parked debt WITHOUT the gateway check ({@code discharged}), or silences
     * a refund or dispute ({@code acknowledge}). The issuer never needs either, so its token must not
     * carry them: a leaked issuer token would otherwise close any parked purchase by fiat. Unset, or
     * equal to the issuer's token, refuses every resolve (503), like an unset issuer token.
     */
    private final byte[] operator;

    public PurchaseApiTokenFilter(String token) {
        this(token, null);
    }

    public PurchaseApiTokenFilter(String token, String operatorToken) {
        this.expected = token == null || token.isBlank()
                ? null
                : token.getBytes(StandardCharsets.UTF_8);
        if (expected == null) {
            log.warn("No purchase API token configured; /api/v1/purchases will refuse every request");
        }
        byte[] op = operatorToken == null || operatorToken.isBlank()
                ? null
                : operatorToken.getBytes(StandardCharsets.UTF_8);
        if (op != null && expected != null && MessageDigest.isEqual(op, expected)) {
            log.warn("The purchase operator token equals the issuer's; /resolve will refuse every request");
            op = null;
        } else if (op == null) {
            log.warn("No purchase operator token configured; /api/v1/purchases/*/resolve will refuse every request");
        }
        this.operator = op;
    }

    /**
     * Whether this request is for the protected surface.
     *
     * <p>Deliberately errs towards protecting. Two ways the naive version was
     * wrong:
     *
     * <ul>
     *   <li><b>The URI is raw.</b> {@code getRequestURI()} returns the path as
     *       sent, including a context path and any percent-encoding, so
     *       {@code /api/v1/%70urchases/owed} does not start with the prefix
     *       while Spring still routes it to the controller. Decoding first and
     *       matching on the decoded path closes that.</li>
     *   <li><b>The prefix matched too much and too little.</b> A bare
     *       {@code startsWith} lets {@code /api/v1/purchasesX} through the
     *       guard's intent, and misses nothing only by luck. Matching the
     *       segment boundary is what was meant.</li>
     * </ul>
     *
     * <p>Any request this cannot confidently classify is treated as protected.
     * The cost of being wrong in that direction is a 401 on something harmless;
     * the other direction serves every stall's debts to anyone.
     */
    private static boolean isProtected(HttpServletRequest http) {
        String path = normalisedPath(http);
        if (path == null) {
            return true;
        }
        return path.equals(PROTECTED_PREFIX)
                || path.startsWith(PROTECTED_PREFIX + "/")
                || path.contains(PROTECTED_PREFIX + "/")
                || path.endsWith(PROTECTED_PREFIX);
    }

    /**
     * Whether this is an operator route (review of #280): any segment that reads {@code resolve}
     * once matrix parameters ({@code ;x=y}) and case are set aside. Errs towards the operator side:
     * the cost is a 401 to the issuer on a route it never uses.
     */
    private static boolean isOperatorRoute(HttpServletRequest http) {
        String path = normalisedPath(http);
        if (path == null) {
            return true;
        }
        for (String segment : path.split("/")) {
            int semi = segment.indexOf(';');
            String bare = (semi >= 0 ? segment.substring(0, semi) : segment).trim();
            if (bare.equalsIgnoreCase("resolve")) {
                return true;
            }
        }
        return false;
    }

    /** {@code GET /api/v1/purchases/attention}, exactly: the one read the operator's token also opens. */
    private static boolean isAttentionList(HttpServletRequest http) {
        String path = normalisedPath(http);
        return "GET".equals(http.getMethod())
                && (PROTECTED_PREFIX + "/attention").equals(path);
    }

    /** The decoded, slash-normalised path without the context path, or null when unclassifiable. */
    private static String normalisedPath(HttpServletRequest http) {
        String path = http.getRequestURI();
        if (path == null) {
            return null;
        }

        String context = http.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }

        try {
            // Decode repeatedly: a doubly-encoded path decodes to an encoded
            // one, and a single pass would still miss it.
            String previous;
            int guard = 0;
            do {
                previous = path;
                path = java.net.URLDecoder.decode(path, StandardCharsets.UTF_8);
            } while (!path.equals(previous) && ++guard < 4);
        } catch (IllegalArgumentException e) {
            // Malformed encoding. Cannot be classified, so it is protected.
            return null;
        }

        // Normalise so `/api/v1/purchases/../purchases/owed` and a trailing
        // slash do not read as different surfaces.
        path = path.replace('\\', '/');
        while (path.contains("//")) {
            path = path.replace("//", "/");
        }

        // Matrix parameters (`;x=y`) on ANY segment, and dot segments. Spring strips the first
        // and Tomcat resolves the second before routing, so `/api;x/v1/purchases/owed` and
        // `/api/v1/purchases;a=b/owed` reach the controller. Left in, they hid the prefix and the
        // whole guard was skipped, token or none (second review of #280).
        java.util.Deque<String> segments = new java.util.ArrayDeque<>();
        for (String segment : path.split("/", -1)) {
            int semi = segment.indexOf(';');
            String bare = semi >= 0 ? segment.substring(0, semi) : segment;
            if (bare.equals(".")) {
                continue;
            }
            if (bare.equals("..")) {
                if (segments.size() > 1) {
                    segments.removeLast();
                }
                continue;
            }
            segments.addLast(bare);
        }
        path = String.join("/", segments);
        while (path.contains("//")) {
            path = path.replace("//", "/");
        }
        return path;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;

        if (!isProtected(http)) {
            chain.doFilter(request, response);
            return;
        }

        if (normalisedPath(http) == null) {
            // Cannot be classified, so nor can it be told issuer from operator: refused outright.
            ((HttpServletResponse) response).sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        boolean operatorRoute = isOperatorRoute(http);
        byte[] wanted = operatorRoute ? operator : expected;

        if (wanted == null) {
            // Refusing, not allowing. A misconfigured deployment must fail
            // visibly rather than serve every stall's sales to anyone.
            ((HttpServletResponse) response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    operatorRoute ? "purchase operator API is not configured" : "purchase API is not configured");
            return;
        }

        String header = http.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            ((HttpServletResponse) response).sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        byte[] presented = header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);

        // Constant-time. A length-then-content comparison leaks the token one
        // byte at a time to anyone willing to measure, and this token guards
        // the ability to mark debts discharged.
        // On an operator route only the operator's token counts; the issuer's is refused. The
        // parked list (GET /attention) is read by both: the issuer's queue, the operator's console.
        boolean admitted = MessageDigest.isEqual(wanted, presented)
                || (operator != null && isAttentionList(http) && MessageDigest.isEqual(operator, presented));
        if (!admitted) {
            ((HttpServletResponse) response).sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        chain.doFilter(request, response);
    }
}
