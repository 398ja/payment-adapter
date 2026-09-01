package xyz.tcheeric.payment.adapter.ln.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.webhook.core.WebhookRegistry;
import xyz.tcheeric.payment.adapter.webhook.forwarder.MintWebhookForwarder;

import jakarta.annotation.PostConstruct;

/**
 * Gives the phoenixd webhook handler its mint forwarder.
 *
 * <p>{@link WebhookRegistry} discovers handlers through {@link java.util.ServiceLoader}, which can
 * only use a no-argument constructor. {@link PhoenixWebhookHandler} therefore starts life with a
 * null {@code mintForwarder} and skips the notification that tells the mint a payment arrived —
 * silently, because the call site is guarded by a null check.
 *
 * <p>The forwarder cannot be constructed by the handler itself: it reads {@code mint.webhook.*}
 * through {@code @Value}, so only Spring can produce a usable one. This re-registers the handler
 * once the context is up, replacing the ServiceLoader instance with one that can actually reach
 * the mint.
 *
 * <p>Registration is idempotent and keyed on payment type, so the replacement is exact rather than
 * additive.
 */
@Slf4j
@Configuration
public class PhoenixWebhookHandlerConfiguration {

    private final MintWebhookForwarder mintForwarder;

    @Autowired
    public PhoenixWebhookHandlerConfiguration(MintWebhookForwarder mintForwarder) {
        this.mintForwarder = mintForwarder;
    }

    @PostConstruct
    public void registerHandlerWithForwarder() {
        PhoenixWebhookHandler handler =
                new PhoenixWebhookHandler(new QuoteClient(), new PaymentClient(), mintForwarder);
        WebhookRegistry.getInstance().register(handler);
        log.info("phoenixd_webhook_handler registered_with_mint_forwarder enabled={}",
                mintForwarder.isEnabled());
    }
}
