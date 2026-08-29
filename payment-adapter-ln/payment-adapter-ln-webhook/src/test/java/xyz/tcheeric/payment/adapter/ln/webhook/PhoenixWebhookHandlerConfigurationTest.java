package xyz.tcheeric.payment.adapter.ln.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.payment.adapter.webhook.core.WebhookRegistry;
import xyz.tcheeric.payment.adapter.webhook.forwarder.MintWebhookForwarder;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookHandler;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered phoenixd handler must hold a mint forwarder.
 *
 * <p>Staging 2026-08-29: token issuance failed with {@code funding_required} because the mint was
 * never told a payment had arrived. {@link WebhookRegistry} discovers handlers through
 * {@code ServiceLoader}, which can only call a no-argument constructor, so the registered
 * {@link PhoenixWebhookHandler} had a null {@code mintForwarder}. The forwarding call is guarded
 * by a null check, so nothing failed and nothing was logged — the notification simply never
 * happened.
 */
class PhoenixWebhookHandlerConfigurationTest {

    private static MintWebhookForwarder forwarderOf(WebhookHandler<?> handler) throws Exception {
        Field field = PhoenixWebhookHandler.class.getDeclaredField("mintForwarder");
        field.setAccessible(true);
        return (MintWebhookForwarder) field.get(handler);
    }

    /**
     * The ServiceLoader-created handler has no forwarder. This is the defect, pinned so the
     * configuration below is understood to be load-bearing rather than redundant.
     */
    @Test
    @DisplayName("a handler built the ServiceLoader way cannot reach the mint")
    void serviceLoaderHandlerHasNoForwarder() throws Exception {
        assertThat(forwarderOf(new PhoenixWebhookHandler())).isNull();
    }

    /** After the configuration runs, the registered handler can reach the mint. */
    @Test
    @DisplayName("registers a handler that holds the mint forwarder")
    void registersHandlerWithForwarder() throws Exception {
        MintWebhookForwarder forwarder = Mockito.mock(MintWebhookForwarder.class);
        Mockito.when(forwarder.isEnabled()).thenReturn(true);

        new PhoenixWebhookHandlerConfiguration(forwarder).registerHandlerWithForwarder();

        Optional<WebhookHandler<?>> registered = WebhookRegistry.getInstance().getHandler("phoenixd");
        assertThat(registered).isPresent();
        assertThat(forwarderOf(registered.get())).isSameAs(forwarder);
    }

    /** Registration must replace the ServiceLoader entry, not sit alongside it. */
    @Test
    @DisplayName("replaces the handler registered for phoenixd rather than adding one")
    void replacesRatherThanAdds() {
        MintWebhookForwarder forwarder = Mockito.mock(MintWebhookForwarder.class);

        new PhoenixWebhookHandlerConfiguration(forwarder).registerHandlerWithForwarder();
        Optional<WebhookHandler<?>> first = WebhookRegistry.getInstance().getHandler("phoenixd");

        new PhoenixWebhookHandlerConfiguration(forwarder).registerHandlerWithForwarder();
        Optional<WebhookHandler<?>> second = WebhookRegistry.getInstance().getHandler("phoenixd");

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(second.get().getPaymentType()).isEqualTo("phoenixd");
        assertThat(second.get()).isNotSameAs(first.get());
    }
}
