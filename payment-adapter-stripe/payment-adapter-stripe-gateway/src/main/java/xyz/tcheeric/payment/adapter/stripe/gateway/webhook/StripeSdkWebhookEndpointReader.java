package xyz.tcheeric.payment.adapter.stripe.gateway.webhook;

import com.stripe.exception.StripeException;
import com.stripe.model.WebhookEndpoint;
import com.stripe.net.RequestOptions;
import lombok.RequiredArgsConstructor;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

/**
 * {@link StripeWebhookEndpointReader} over stripe-java. Read-only, on the PLATFORM account (webhook
 * endpoints belong to the platform, even those listening to connected accounts), through the api-base
 * guard, with short bounds: startup must not wait long on it.
 */
@RequiredArgsConstructor
public class StripeSdkWebhookEndpointReader implements StripeWebhookEndpointReader {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_NETWORK_RETRIES = 1;

    private final StripeGatewayProperties properties;

    @Override
    public Subscription read(String endpointId) {
        try {
            WebhookEndpoint endpoint = WebhookEndpoint.retrieve(endpointId, options());
            return new Subscription(endpoint.getStatus(), endpoint.getEnabledEvents());
        } catch (StripeException e) {
            // The message, not the exception: a StripeException never carries the key, but its
            // request id and code are all an operator needs.
            throw new StripeWebhookEndpointException("Could not read webhook endpoint " + endpointId + ": "
                    + e.getMessage(), e);
        }
    }

    private RequestOptions options() {
        return properties.requestOptions()
                .setApiKey(properties.getSecretKey())
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .setMaxNetworkRetries(MAX_NETWORK_RETRIES)
                .build();
    }
}
