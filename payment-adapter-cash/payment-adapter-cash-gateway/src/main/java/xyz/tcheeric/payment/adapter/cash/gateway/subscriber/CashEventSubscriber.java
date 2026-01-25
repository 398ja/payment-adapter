package xyz.tcheeric.payment.adapter.cash.gateway.subscriber;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import xyz.tcheeric.payment.adapter.cash.gateway.CashGateway;
import xyz.tcheeric.payment.adapter.cash.gateway.state.CashInvoiceStateMachine;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Background service for subscribing to Nostr relay events.
 *
 * <p>This service:
 * <ul>
 *   <li>Subscribes to relays for CashIntent events (kind 5201)</li>
 *   <li>Monitors invoice expiry</li>
 *   <li>Notifies listeners of state changes</li>
 * </ul>
 *
 * <p>In a full implementation, this would use nostr-java's WebSocket
 * client to subscribe to relays. For now, it provides the framework
 * for event handling and expiry monitoring.
 */
@Slf4j
@Service
public class CashEventSubscriber {

    @Value("${cash.subscriber.enabled:true}")
    private boolean enabled;

    @Value("${cash.subscriber.expiry-check-interval:30000}")
    private long expiryCheckInterval;

    private final CashGateway cashGateway;
    private final CashInvoiceStateMachine stateMachine;
    private final Map<String, Consumer<CashInvoice>> stateChangeListeners = new ConcurrentHashMap<>();

    private ExecutorService executor;
    private volatile boolean running;

    public CashEventSubscriber(CashGateway cashGateway, CashInvoiceStateMachine stateMachine) {
        this.cashGateway = cashGateway;
        this.stateMachine = stateMachine;
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("Cash event subscriber disabled");
            return;
        }

        log.info("Starting cash event subscriber");
        this.running = true;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cash-event-subscriber");
            t.setDaemon(true);
            return t;
        });

        // TODO: In production, start WebSocket connections to relays
        // For each active invoice, subscribe for kind 5201 events with matching ref
        log.info("Cash event subscriber started (relay connections not yet implemented)");
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping cash event subscriber");
        this.running = false;

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("Cash event subscriber stopped");
    }

    /**
     * Scheduled task to check for expired invoices.
     */
    @Scheduled(fixedDelayString = "${cash.subscriber.expiry-check-interval:30000}")
    public void checkExpiredInvoices() {
        if (!running) {
            return;
        }

        log.debug("Checking for expired invoices");

        // TODO: In production, iterate over all pending invoices from repository
        // For now, this is handled in CashGateway.getInvoiceByRef()
    }

    /**
     * Handle an incoming CashIntent event (kind 5201).
     *
     * @param ref            the invoice reference
     * @param customerPubkey customer's ephemeral public key
     * @param proof          optional proof code
     */
    public void handleIntent(String ref, String customerPubkey, String proof) {
        log.info("Handling intent: ref={}, customerPubkey={}", ref, customerPubkey);

        try {
            cashGateway.recordIntent(ref, customerPubkey, proof);

            CashInvoice invoice = cashGateway.getInvoiceByRef(ref);
            if (invoice != null) {
                notifyStateChange(invoice);
            }
        } catch (Exception e) {
            log.error("Failed to handle intent: ref={}", ref, e);
        }
    }

    /**
     * Register a listener for invoice state changes.
     *
     * @param listenerId unique listener identifier
     * @param listener   callback for state changes
     */
    public void addStateChangeListener(String listenerId, Consumer<CashInvoice> listener) {
        stateChangeListeners.put(listenerId, listener);
        log.debug("Added state change listener: {}", listenerId);
    }

    /**
     * Remove a state change listener.
     *
     * @param listenerId the listener to remove
     */
    public void removeStateChangeListener(String listenerId) {
        stateChangeListeners.remove(listenerId);
        log.debug("Removed state change listener: {}", listenerId);
    }

    /**
     * Notify all listeners of a state change.
     *
     * @param invoice the invoice that changed
     */
    private void notifyStateChange(CashInvoice invoice) {
        for (Map.Entry<String, Consumer<CashInvoice>> entry : stateChangeListeners.entrySet()) {
            try {
                entry.getValue().accept(invoice);
            } catch (Exception e) {
                log.error("State change listener {} failed: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /**
     * Subscribe to relays for a specific invoice.
     *
     * @param invoice the invoice to subscribe for
     */
    public void subscribeForInvoice(CashInvoice invoice) {
        if (!running) {
            return;
        }

        log.debug("Subscribing for invoice: ref={}", invoice.getRef());

        // TODO: Create subscription filter for kind 5201 with ref tag
        // Filter: {"kinds": [5201], "#ref": [invoice.getRef()]}
        // Connect to each relay in invoice.getRelayUrls()

        List<String> relays = List.of(invoice.getRelayUrls().split(","));
        log.info("Would subscribe to {} relays for ref={}", relays.size(), invoice.getRef());
    }

    /**
     * Unsubscribe from relays for a specific invoice.
     *
     * @param ref the invoice reference
     */
    public void unsubscribeForInvoice(String ref) {
        if (!running) {
            return;
        }

        log.debug("Unsubscribing for invoice: ref={}", ref);
        // TODO: Close subscription for this ref
    }

    /**
     * Check if subscriber is running.
     */
    public boolean isRunning() {
        return running;
    }
}
