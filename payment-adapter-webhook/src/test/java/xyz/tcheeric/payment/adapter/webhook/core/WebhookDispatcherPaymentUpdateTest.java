package xyz.tcheeric.payment.adapter.webhook.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayPayment;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookHandler;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookPayload;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A payment row that is absent is routine; a payment row that cannot be WRITTEN is not.
 *
 * <p>Both calls once shared a single try block with a {@code NotFound} catch that logged at
 * debug. That was added for a real reason — staging has an empty {@code payment} table, so
 * every webhook logged a stack trace at ERROR for a harmless condition, and three of those had
 * to be ruled out before the actual defect in #462 could be seen.
 *
 * <p>But it quietened too much. A 404 from {@code updatePayment} — a row deleted between the
 * read and the write, or a client pointed at a stale path — took the same branch, so a lost
 * state transition and a nonexistent row produced identical output. The read and the write
 * fail for different reasons and deserve different levels.
 */
@DisplayName("webhook payment updates: absent row is quiet, failed write is loud")
class WebhookDispatcherPaymentUpdateTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger dispatcherLogger;

    @BeforeEach
    void captureLogs() {
        dispatcherLogger = (Logger) LoggerFactory.getLogger(WebhookDispatcher.class);
        appender = new ListAppender<>();
        appender.start();
        dispatcherLogger.addAppender(appender);
        dispatcherLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void releaseLogs() {
        dispatcherLogger.detachAppender(appender);
    }

    /**
     * The case the quietening was written for: no row, nothing to do, no ERROR.
     */
    @Test
    @DisplayName("an absent payment row does not log an error")
    void absentPaymentRowIsNotAnError() {
        PaymentClient client = new PaymentClient() {
            @Override
            public GatewayPayment getByPaymentId(String paymentId) {
                throw HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", null, null, null);
            }
        };

        dispatch(client);

        assertThat(errors())
                .as("an empty payment table is normal on some deployments; an ERROR that is "
                        + "always present is an ERROR nobody reads")
                .isEmpty();
    }

    /**
     * The case the quietening broke. Mutation check: putting {@code updatePayment} back inside
     * the {@code NotFound} catch makes this fail, because the failure is logged at debug.
     */
    @Test
    @DisplayName("a payment row that cannot be written still logs an error")
    void failedWriteIsStillAnError() {
        GatewayPayment existing = new GatewayPayment();
        existing.setPaymentId("pay-1");

        PaymentClient client = new PaymentClient() {
            @Override
            public GatewayPayment getByPaymentId(String paymentId) {
                return existing;
            }

            @Override
            public GatewayPayment updatePayment(GatewayPayment payment) {
                // The row vanished between the read and the write.
                throw HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", null, null, null);
            }
        };

        dispatch(client);

        assertThat(errors())
                .as("a failed WRITE means a state transition was lost, which is not the same "
                        + "thing as there being no row to write to")
                .isNotEmpty();
    }

    private List<String> errors() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * Drives the dispatcher through its public entry point, since updatePaymentState is
     * private and testing it directly would assert on a shape rather than on behaviour.
     */
    private void dispatch(PaymentClient client) {
        WebhookPayload payload = Mockito.mock(WebhookPayload.class);
        Mockito.when(payload.getIdempotencyKey()).thenReturn("idem-1");
        Mockito.when(payload.getEventType()).thenReturn("payment.succeeded");

        @SuppressWarnings("unchecked")
        WebhookHandler<WebhookPayload> handler = Mockito.mock(WebhookHandler.class);
        Mockito.when(handler.getPaymentType()).thenReturn(PAYMENT_TYPE);
        Mockito.when(handler.parsePayload(Mockito.any())).thenReturn(payload);
        Mockito.when(handler.handle(payload)).thenReturn(new WebhookResult(
                true, "pay-1", State.PAID, Map.of()));

        // The registry is a singleton with no unregister, so the handler is registered under
        // a name no production handler uses. Nothing else resolves this type.
        WebhookRegistry registry = WebhookRegistry.getInstance();
        registry.register(handler);

        new WebhookDispatcher(registry, client)
                .dispatch(PAYMENT_TYPE, Mockito.mock(HttpServletRequest.class));
    }

    private static final String PAYMENT_TYPE = "webhook-dispatcher-review-test";
}
