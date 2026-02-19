package xyz.tcheeric.payment.adapter.webhook.core;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookHandler;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for webhook handlers.
 * Handlers are discovered via ServiceLoader or registered programmatically.
 */
@Slf4j
public class WebhookRegistry {

    private static final WebhookRegistry INSTANCE = new WebhookRegistry();

    private final Map<String, WebhookHandler<?>> handlers = new ConcurrentHashMap<>();

    private WebhookRegistry() {
        // Load handlers via ServiceLoader
        loadHandlers();
    }

    /**
     * Returns the singleton instance.
     */
    public static WebhookRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Loads handlers via ServiceLoader SPI.
     */
    @SuppressWarnings("rawtypes")
    private void loadHandlers() {
        ServiceLoader<WebhookHandler> loader = ServiceLoader.load(WebhookHandler.class);
        for (WebhookHandler<?> handler : loader) {
            register(handler);
        }
        log.info("Loaded {} webhook handlers via ServiceLoader", handlers.size());
    }

    /**
     * Registers a handler for a payment type.
     *
     * @param handler the handler to register
     */
    public void register(WebhookHandler<?> handler) {
        String paymentType = handler.getPaymentType();
        WebhookHandler<?> existing = handlers.put(paymentType, handler);
        if (existing != null) {
            log.warn("Replaced existing handler for payment type: {}", paymentType);
        } else {
            log.info("Registered webhook handler: type={}, class={}", paymentType, handler.getClass().getName());
        }
    }

    /**
     * Gets a handler for a payment type.
     *
     * @param paymentType the payment type identifier
     * @return the handler, if registered
     */
    public Optional<WebhookHandler<?>> getHandler(String paymentType) {
        return Optional.ofNullable(handlers.get(paymentType));
    }

    /**
     * Returns all registered payment types.
     */
    public Set<String> getRegisteredTypes() {
        return Collections.unmodifiableSet(handlers.keySet());
    }

    /**
     * Checks if a handler is registered for a payment type.
     */
    public boolean hasHandler(String paymentType) {
        return handlers.containsKey(paymentType);
    }
}
