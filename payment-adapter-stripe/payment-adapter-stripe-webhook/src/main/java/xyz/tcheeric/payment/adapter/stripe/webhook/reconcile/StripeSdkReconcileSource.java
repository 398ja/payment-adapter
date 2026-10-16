package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.Session;
import com.stripe.model.checkout.SessionCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.checkout.SessionListParams;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

/**
 * {@link StripeReconcileSource} over stripe-java. Every call goes through
 * {@link StripeGatewayProperties#requestOptions()} (the api-base guard) and names the connected
 * account. Bounded timeouts: nobody waits on this, but a hung read must not hold the run's lease.
 */
@RequiredArgsConstructor
public class StripeSdkReconcileSource implements StripeReconcileSource {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_NETWORK_RETRIES = 2;

    private final StripeGatewayProperties properties;

    @Override
    public SessionPage completeSessions(String accountId, Instant from, Instant until, String startingAfter,
            int limit) {
        SessionListParams.Builder params = SessionListParams.builder()
                .setStatus(SessionListParams.Status.COMPLETE)
                .setLimit((long) limit)
                .setCreated(SessionListParams.Created.builder()
                        .setGte(from.getEpochSecond())
                        .setLte(until.getEpochSecond())
                        .build());
        if (startingAfter != null) {
            params.setStartingAfter(startingAfter);
        }
        try {
            SessionCollection page = Session.list(params.build(), options(accountId));
            List<CompletedSession> sessions = page.getData().stream().map(StripeSdkReconcileSource::toSession).toList();
            return new SessionPage(sessions, Boolean.TRUE.equals(page.getHasMore()));
        } catch (StripeException e) {
            throw failure("list Checkout Sessions of " + accountId, e);
        }
    }

    @Override
    public PaymentState paymentState(String accountId, String paymentIntentId) {
        try {
            PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId,
                    PaymentIntentRetrieveParams.builder().addExpand("latest_charge").build(), options(accountId));
            Charge charge = intent.getLatestChargeObject();
            if (charge == null) {
                return new PaymentState(null, orZero(intent.getAmount()), 0L, false);
            }
            long amount = orZero(charge.getAmount());
            long refunded = Boolean.TRUE.equals(charge.getRefunded())
                    ? Math.max(amount, orZero(charge.getAmountRefunded()))
                    : orZero(charge.getAmountRefunded());
            return new PaymentState(charge.getId(), amount, refunded, Boolean.TRUE.equals(charge.getDisputed()));
        } catch (StripeException e) {
            throw failure("read PaymentIntent " + paymentIntentId + " on " + accountId, e);
        }
    }

    private RequestOptions options(String accountId) {
        return properties.requestOptions()
                .setApiKey(properties.getSecretKey())
                .setStripeAccount(accountId)
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .setMaxNetworkRetries(MAX_NETWORK_RETRIES)
                .build();
    }

    private static CompletedSession toSession(Session session) {
        Map<String, String> metadata = session.getMetadata() == null ? Map.of() : session.getMetadata();
        return new CompletedSession(session.getId(),
                Instant.ofEpochSecond(orZero(session.getCreated())),
                session.getPaymentStatus(), session.getPaymentIntent(), session.getAmountTotal(),
                session.getCurrency(), metadata, Boolean.TRUE.equals(session.getLivemode()));
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private static StripeReconcileException failure(String what, StripeException e) {
        return new StripeReconcileException("Could not " + what + ": " + e.getMessage(), e,
                e instanceof RateLimitException);
    }
}
