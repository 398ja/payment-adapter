package xyz.tcheeric.payment.adapter.cash.gateway.subscriber;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import nostr.base.ElementAttribute;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.GenericTag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.payment.adapter.cash.nostr.client.NostrClient;
import xyz.tcheeric.payment.adapter.cash.nostr.event.CashEventKind;
import xyz.tcheeric.payment.adapter.cash.nostr.event.CashIntentEvent;
import xyz.tcheeric.payment.adapter.cash.nostr.event.payload.CashIntentPayload;
import xyz.tcheeric.payment.adapter.cash.gateway.service.CashInvoiceService;
import xyz.tcheeric.payment.adapter.cash.gateway.state.CashInvoiceStateMachine;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.CashIntentRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.CashInvoiceRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.CashReceiptRepository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Background service for subscribing to Nostr relay events.
 *
 * <p>This service:
 * <ul>
 *   <li>Subscribes to relays for CashIntent events (kind 5201)</li>
 *   <li>Monitors invoice expiry</li>
 *   <li>Notifies listeners of state changes</li>
 *   <li>Auto-prunes old transaction data</li>
 * </ul>
 */
@Slf4j
@Service
public class CashEventSubscriber {

    @Value("${cash.subscriber.enabled:true}")
    private boolean enabled;

    @Value("${cash.retention.days:30}")
    private int retentionDays;

    private final CashInvoiceService invoiceService;
    private final CashInvoiceStateMachine stateMachine;
    private final NostrClient nostrClient;
    private final CashInvoiceRepository invoiceRepository;
    private final CashIntentRepository intentRepository;
    private final CashReceiptRepository receiptRepository;
    private final Map<String, Consumer<CashInvoice>> stateChangeListeners = new ConcurrentHashMap<>();
    private final Map<String, String> activeSubscriptions = new ConcurrentHashMap<>();

    // Metrics counters
    private Counter intentsReceivedCounter;
    private Counter invoicesExpiredCounter;

    private volatile boolean running;

    @Autowired
    public CashEventSubscriber(CashInvoiceService invoiceService,
                               CashInvoiceStateMachine stateMachine,
                               NostrClient nostrClient,
                               CashInvoiceRepository invoiceRepository,
                               CashIntentRepository intentRepository,
                               CashReceiptRepository receiptRepository,
                               @Autowired(required = false) MeterRegistry meterRegistry) {
        this.invoiceService = invoiceService;
        this.stateMachine = stateMachine;
        this.nostrClient = nostrClient;
        this.invoiceRepository = invoiceRepository;
        this.intentRepository = intentRepository;
        this.receiptRepository = receiptRepository;

        if (meterRegistry != null) {
            intentsReceivedCounter = Counter.builder("cash.intents.received")
                    .description("Number of cash intents received")
                    .register(meterRegistry);
            invoicesExpiredCounter = Counter.builder("cash.invoices.expired")
                    .description("Number of cash invoices expired")
                    .register(meterRegistry);
        }
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("Cash event subscriber disabled");
            return;
        }

        log.info("Starting cash event subscriber");
        this.running = true;

        // Subscribe for active invoices
        List<CashInvoice> activeInvoices = invoiceService.findActiveInvoices();
        for (CashInvoice invoice : activeInvoices) {
            subscribeForInvoice(invoice);
        }

        log.info("Cash event subscriber started with {} active subscriptions", activeSubscriptions.size());
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping cash event subscriber");
        this.running = false;

        for (String subId : activeSubscriptions.values()) {
            nostrClient.unsubscribe(subId);
        }
        activeSubscriptions.clear();

        log.info("Cash event subscriber stopped");
    }

    /**
     * Scheduled task to check for expired invoices.
     */
    @Scheduled(fixedDelayString = "${cash.subscriber.expiry-check-interval:30000}")
    @Transactional
    public void checkExpiredInvoices() {
        if (!running) {
            return;
        }

        log.debug("Checking for expired invoices");

        List<CashInvoice> expired = invoiceService.findExpiredInvoices();
        for (CashInvoice invoice : expired) {
            if (stateMachine.tryExpire(invoice)) {
                invoiceRepository.save(invoice);
                notifyStateChange(invoice);
                unsubscribeForInvoice(invoice.getRef());
                if (invoicesExpiredCounter != null) {
                    invoicesExpiredCounter.increment();
                }
            }
        }

        if (!expired.isEmpty()) {
            log.info("Expired {} invoice(s)", expired.size());
        }
    }

    /**
     * Scheduled task to prune old transaction data.
     */
    @Scheduled(cron = "${cash.retention.cron:0 0 2 * * ?}")
    @Transactional
    public void pruneOldTransactions() {
        if (!running) {
            return;
        }

        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        log.info("Pruning transactions older than {} (retention={} days)", cutoff, retentionDays);

        invoiceRepository.deleteByCreatedAtBefore(cutoff);
        intentRepository.deleteByReceivedAtBefore(cutoff);
        receiptRepository.deleteByConfirmedAtBefore(cutoff);

        log.info("Transaction pruning complete");
    }

    /**
     * Handle an incoming CashIntent event (kind 5201).
     */
    public void handleIntent(String ref, String customerPubkey, String proof) {
        log.info("Handling intent: ref={}, customerPubkey={}", ref, customerPubkey);

        try {
            invoiceService.recordIntent(ref, customerPubkey, proof);

            invoiceService.getInvoiceByRef(ref).ifPresent(this::notifyStateChange);

            if (intentsReceivedCounter != null) {
                intentsReceivedCounter.increment();
            }
        } catch (Exception e) {
            log.error("Failed to handle intent: ref={}", ref, e);
        }
    }

    /**
     * Handle an incoming relay event.
     */
    private void handleRelayEvent(GenericEvent event) {
        try {
            if (event.getKind() == CashEventKind.CASH_INTENT) {
                // Parse intent from event
                String ref = extractRefTag(event);
                String customerPubkey = event.getPubKey() != null ? event.getPubKey().toString() : null;

                if (ref != null) {
                    // Check for duplicate via DB
                    String eventId = event.getId();
                    if (eventId != null && intentRepository.existsByEventId(eventId)) {
                        log.debug("Duplicate intent event ignored: eventId={}", eventId);
                        return;
                    }
                    handleIntent(ref, customerPubkey, null);
                }
            } else if (event.getKind() == CashEventKind.CASH_CANCEL) {
                String ref = extractRefTag(event);
                if (ref != null) {
                    handleCancel(ref, "cash.cancelled_by_customer");
                }
            }
        } catch (Exception e) {
            log.error("Failed to handle relay event: kind={}", event.getKind(), e);
        }
    }

    private String extractRefTag(GenericEvent event) {
        if (event.getTags() != null) {
            for (var tag : event.getTags()) {
                if (tag instanceof GenericTag genericTag && "ref".equals(genericTag.getCode())) {
                    var attrs = genericTag.getAttributes();
                    if (attrs != null && !attrs.isEmpty()) {
                        return attrs.get(0).value().toString();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Handle an incoming CashCancel event from customer (kind 5203).
     */
    public void handleCancel(String ref, String reason) {
        log.info("Handling cancel from customer: ref={}, reason={}", ref, reason);

        try {
            invoiceService.cancelInvoice(ref, reason != null ? reason : "cash.cancelled_by_customer");

            invoiceService.getInvoiceByRef(ref).ifPresent(invoice -> {
                notifyStateChange(invoice);
                unsubscribeForInvoice(ref);
            });
        } catch (IllegalArgumentException e) {
            log.warn("Cancel for unknown invoice: ref={}", ref);
        } catch (IllegalStateException e) {
            log.warn("Cannot cancel invoice: ref={}, error={}", ref, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to handle cancel: ref={}", ref, e);
        }
    }

    /**
     * Register a listener for invoice state changes.
     */
    public void addStateChangeListener(String listenerId, Consumer<CashInvoice> listener) {
        stateChangeListeners.put(listenerId, listener);
        log.debug("Added state change listener: {}", listenerId);
    }

    /**
     * Remove a state change listener.
     */
    public void removeStateChangeListener(String listenerId) {
        stateChangeListeners.remove(listenerId);
        log.debug("Removed state change listener: {}", listenerId);
    }

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
     */
    public void subscribeForInvoice(CashInvoice invoice) {
        if (!running || !nostrClient.isRunning()) {
            return;
        }

        String ref = invoice.getRef();
        if (activeSubscriptions.containsKey(ref)) {
            return;
        }

        List<String> relays = Arrays.asList(invoice.getRelayUrls().split(","));
        String subId = nostrClient.subscribe(
                CashEventKind.CASH_INTENT, ref, relays, this::handleRelayEvent);
        activeSubscriptions.put(ref, subId);

        log.debug("Subscribed for invoice: ref={}, subId={}", ref, subId);
    }

    /**
     * Unsubscribe from relays for a specific invoice.
     */
    public void unsubscribeForInvoice(String ref) {
        String subId = activeSubscriptions.remove(ref);
        if (subId != null) {
            nostrClient.unsubscribe(subId);
            log.debug("Unsubscribed for invoice: ref={}", ref);
        }
    }

    /**
     * Check if subscriber is running.
     */
    public boolean isRunning() {
        return running;
    }
}
