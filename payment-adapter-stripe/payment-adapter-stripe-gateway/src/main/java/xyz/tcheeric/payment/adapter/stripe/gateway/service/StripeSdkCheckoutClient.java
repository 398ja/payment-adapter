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
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutRequest;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;

@RequiredArgsConstructor
public class StripeSdkCheckoutClient implements StripeCheckoutClient {

    private final StripeGatewayProperties properties;

    private RequestOptions buildRequestOptions() {
        return RequestOptions.builder()
                .setApiKey(properties.getSecretKey())
                .build();
    }

    @Override
    public StripeCheckoutSession createCheckoutSession(StripeCheckoutRequest checkoutRequest) {
        try {
            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(properties.getSuccessUrl())
                    .setCancelUrl(properties.getCancelUrl())
                    .setExpiresAt(expirationEpochSeconds())
                    .setClientReferenceId(checkoutRequest.getQuoteId())
                    .putMetadata("quote_id", checkoutRequest.getQuoteId())
                    .addLineItem(buildLineItem(checkoutRequest))
                    .build();

            RequestOptions requestOptions = buildRequestOptions().toBuilder()
                    .setIdempotencyKey(checkoutRequest.getIdempotencyKey())
                    .build();

            return toCheckoutSession(Session.create(params, requestOptions));
        } catch (StripeException e) {
            throw new StripeCheckoutCreationException(checkoutRequest.getQuoteId(), checkoutRequest.getCurrency(), e);
        }
    }

    @Override
    public StripeCheckoutSession retrieveCheckoutSession(String sessionId) {
        try {
            return toCheckoutSession(Session.retrieve(sessionId, buildRequestOptions()));
        } catch (StripeException e) {
            throw new StripeGatewayException("Failed to retrieve Stripe checkout session: " + sessionId, e);
        }
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
