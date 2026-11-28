package xyz.tcheeric.payment.adapter.stripe.gateway.service;

import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.gateway.exception.StripeCheckoutCreationException;
import xyz.tcheeric.payment.adapter.stripe.gateway.exception.StripeGatewayException;
import xyz.tcheeric.payment.adapter.stripe.gateway.exception.StripeValidationException;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutRequest;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripePlanCheckoutRequest;

@RequiredArgsConstructor
public class StripeSdkCheckoutClient implements StripeCheckoutClient {

    private final StripeGatewayProperties properties;

    /**
     * Request options for one checkout, on the right account.
     *
     * <p>The platform key always signs; {@code Stripe-Account} decides whose
     * money it is. Without it every session charges the PLATFORM, which is the
     * shape this client had and the reason a coupon purchase could not be built
     * on it: Imani would hold the funds and be merchant of record.
     */
    private RequestOptions buildRequestOptions(StripeCheckoutRequest checkoutRequest) {
        // properties.requestOptions() carries the api-base when one is set, so a
        // developer running against stripe-mock does not have SOME calls quietly
        // reach real Stripe. See StripeGatewayProperties#requestOptions.
        RequestOptions.RequestOptionsBuilder builder = properties.requestOptions()
                .setApiKey(properties.getSecretKey())
                .setIdempotencyKey(checkoutRequest.getIdempotencyKey())
                // A buyer is waiting on this call (payment-adapter#274).
                .setConnectTimeout(properties.getCheckoutConnectTimeoutMs())
                .setReadTimeout(properties.getCheckoutReadTimeoutMs())
                .setMaxNetworkRetries(properties.getCheckoutMaxNetworkRetries());
        if (checkoutRequest.isDirectCharge()) {
            builder.setStripeAccount(checkoutRequest.getConnectedAccountId());
        }
        return builder.build();
    }

    @Override
    public StripeCheckoutSession createCheckoutSession(StripeCheckoutRequest checkoutRequest) {
        try {
            SessionCreateParams.Builder params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(checkoutRequest.getSuccessUrl() != null
                            ? checkoutRequest.getSuccessUrl() : properties.getSuccessUrl())
                    .setCancelUrl(checkoutRequest.getCancelUrl() != null
                            ? checkoutRequest.getCancelUrl() : properties.getCancelUrl())
                    .setExpiresAt(expirationEpochSeconds())
                    .setClientReferenceId(checkoutRequest.getQuoteId())
                    .putMetadata("quote_id", checkoutRequest.getQuoteId())
                    .addLineItem(buildLineItem(checkoutRequest));

            // Caller-supplied metadata rides the session and comes back on the
            // webhook, which is how a purchase names its buyer without a second
            // lookup. Added after `quote_id` so a caller cannot overwrite the
            // one field the settlement path reads.
            if (checkoutRequest.getMetadata() != null) {
                checkoutRequest.getMetadata().forEach((key, value) -> {
                    if (!"quote_id".equals(key)) {
                        params.putMetadata(key, value);
                    }
                });
            }

            // Only on a direct charge. Stripe rejects an application fee on a
            // session that names no connected account, so asking for one
            // without the other is a caller bug rather than a Stripe error to
            // surface from deep inside the SDK.
            if (checkoutRequest.getApplicationFeeMinor() != null) {
                if (!checkoutRequest.isDirectCharge()) {
                    throw new StripeValidationException(
                            "Stripe applicationFeeMinor requires a connectedAccountId");
                }
                params.setPaymentIntentData(
                        SessionCreateParams.PaymentIntentData.builder()
                                .setApplicationFeeAmount(checkoutRequest.getApplicationFeeMinor())
                                .build());
            }

            return toCheckoutSession(Session.create(params.build(), buildRequestOptions(checkoutRequest)));
        } catch (StripeException e) {
            throw new StripeCheckoutCreationException(checkoutRequest.getQuoteId(), checkoutRequest.getCurrency(), e);
        }
    }

    @Override
    public StripeCheckoutSession retrieveCheckoutSession(String sessionId) {
        return retrieveCheckoutSession(sessionId, null);
    }

    /**
     * Read a session back, from the account that owns it.
     *
     * <p>A session created on a connected account is NOT visible from the
     * platform account: retrieving it without {@code Stripe-Account} answers
     * "no such checkout session". So a caller holding a direct-charge session
     * has to say whose it is, and the single-argument form remains correct for
     * every platform charge.
     */
    @Override
    public StripeCheckoutSession retrieveCheckoutSession(String sessionId, String connectedAccountId) {
        try {
            RequestOptions.RequestOptionsBuilder builder = properties.requestOptions()
                    .setApiKey(properties.getSecretKey());
            if (connectedAccountId != null && !connectedAccountId.isBlank()) {
                builder.setStripeAccount(connectedAccountId);
            }
            return toCheckoutSession(Session.retrieve(sessionId, builder.build()));
        } catch (StripeException e) {
            throw new StripeGatewayException("Failed to retrieve Stripe checkout session: " + sessionId, e);
        }
    }

    @Override
    public StripeCheckoutSession createPlanCheckoutSession(StripePlanCheckoutRequest request) {
        try {
            RequestOptions options = properties.requestOptions()
                    .setApiKey(properties.getSecretKey())
                    .setIdempotencyKey(request.getIdempotencyKey())
                    .setConnectTimeout(properties.getCheckoutConnectTimeoutMs())
                    .setReadTimeout(properties.getCheckoutReadTimeoutMs())
                    .setMaxNetworkRetries(properties.getCheckoutMaxNetworkRetries())
                    .setStripeAccount(request.getConnectedAccountId())
                    .build();
            return toCheckoutSession(Session.create(planCheckoutParams(request), options));
        } catch (StripeException e) {
            throw new StripeCheckoutCreationException(request.getPlanId(), request.getCurrency(), e);
        }
    }

    /** The plan session we ask Stripe for. Separate so a test can pin it without a live Stripe. */
    SessionCreateParams planCheckoutParams(StripePlanCheckoutRequest request) {
        SessionCreateParams.SubscriptionData.Builder subscription = SessionCreateParams.SubscriptionData.builder();
        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setSuccessUrl(request.getSuccessUrl())
                .setCancelUrl(request.getCancelUrl())
                .setClientReferenceId(request.getPlanId())
                .setExpiresAt(expirationEpochSeconds())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(request.getCurrency())
                                .setUnitAmount(request.getAmountMinor())
                                .setRecurring(SessionCreateParams.LineItem.PriceData.Recurring.builder()
                                        .setInterval(SessionCreateParams.LineItem.PriceData.Recurring.Interval.MONTH)
                                        .build())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName(request.getDescription())
                                        .build())
                                .build())
                        .build());
        // On the session (the first webhook) AND on the subscription (every invoice carries it from here on):
        // renewals have no Checkout Session, so the subscription is the only thing that can name the plan.
        if (request.getMetadata() != null) {
            request.getMetadata().forEach((key, value) -> {
                params.putMetadata(key, value);
                subscription.putMetadata(key, value);
            });
        }
        // No payment_intent_data and no application fee: neither is valid in subscription mode, and Imani takes
        // no cut of a plan in v1.
        return params.setSubscriptionData(subscription.build()).build();
    }

    private SessionCreateParams.LineItem buildLineItem(StripeCheckoutRequest checkoutRequest) {
        return SessionCreateParams.LineItem.builder()
                .setQuantity(1L)
                .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency(checkoutRequest.getCurrency())
                        .setUnitAmount(checkoutRequest.getAmount().longValue())
                        .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                .setName(checkoutRequest.getDescription())
                                .build())
                        .build())
                .build();
    }

    private StripeCheckoutSession toCheckoutSession(Session session) {
        return StripeCheckoutSession.builder()
                .sessionId(session.getId())
                .sessionUrl(session.getUrl())
                .paymentStatus(session.getPaymentStatus())
                .status(session.getStatus())
                .paymentIntentId(Objects.toString(session.getPaymentIntent(), null))
                .expiresAtEpochSeconds(session.getExpiresAt())
                .livemode(Boolean.TRUE.equals(session.getLivemode()))
                .build();
    }

    private long expirationEpochSeconds() {
        return java.time.Instant.now().getEpochSecond() + properties.getCheckoutExpirySeconds();
    }
}
