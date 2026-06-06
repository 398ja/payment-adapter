package xyz.tcheeric.payment.adapter.stripe.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedStripeWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePaymentReferenceRepository;
import xyz.tcheeric.payment.adapter.webhook.core.WebhookRegistry;

/**
 * Registers a Spring-wired {@link StripeWebhookHandler} with the
 * {@link WebhookRegistry}.
 *
 * <p>{@code WebhookRegistry} discovers handlers via {@code ServiceLoader},
 * which instantiates {@link StripeWebhookHandler} through its no-arg
 * constructor — outside the Spring container, so the {@code @Autowired}
 * setters never fire and {@code processedEventRepository} /
 * {@code paymentReferenceRepository} stay {@code null}. Every Stripe payment
 * webhook then failed {@code ensureDependencies()} with a processing error.
 *
 * <p>This configuration builds a handler whose repositories are injected from
 * the Spring context and {@code register()}s it; since the registry keys
 * handlers by payment type ({@code "stripe"}), the wired instance replaces the
 * ServiceLoader stub. The no-arg constructor still supplies the REST clients
 * and the env-backed signature verifier.
 */
@Slf4j
@Configuration
public class StripeWebhookHandlerConfiguration {

    @Bean
    public StripeWebhookHandler stripeWebhookHandler(
            ProcessedStripeWebhookEventRepository processedEventRepository,
            StripePaymentReferenceRepository paymentReferenceRepository) {
        StripeWebhookHandler handler = new StripeWebhookHandler();
        handler.setProcessedEventRepository(processedEventRepository);
        handler.setPaymentReferenceRepository(paymentReferenceRepository);
        WebhookRegistry.getInstance().register(handler);
        log.info("Registered Spring-wired StripeWebhookHandler with WebhookRegistry (replaces ServiceLoader stub)");
        return handler;
    }
}
