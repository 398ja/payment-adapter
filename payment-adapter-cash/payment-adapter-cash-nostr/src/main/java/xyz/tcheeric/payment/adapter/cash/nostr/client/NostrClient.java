package xyz.tcheeric.payment.adapter.cash.nostr.client;

import lombok.extern.slf4j.Slf4j;
import nostr.event.impl.GenericEvent;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Facade for publishing and subscribing to Nostr relay events.
 * Manages a pool of relay connections and dispatches events using virtual threads.
 */
@Slf4j
public class NostrClient {

    private final List<String> relayUrls;
    private final Map<String, RelayConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, Long> lastEventTimestamps = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    public NostrClient(String relayUrlsCsv) {
        this.relayUrls = Arrays.stream(relayUrlsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public NostrClient(List<String> relayUrls) {
        this.relayUrls = List.copyOf(relayUrls);
    }

    @PostConstruct
    public void start() {
        log.info("Starting NostrClient with {} relays", relayUrls.size());
        running = true;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = relayUrls.stream()
                    .map(url -> CompletableFuture.runAsync(() -> {
                        RelayConnection conn = new WebSocketRelayConnection(url);
                        connections.put(url, conn);
                        conn.connect();
                    }, executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(10, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        log.warn("Some relay connections timed out: {}", ex.getMessage());
                        return null;
                    })
                    .join();
        }

        log.info("NostrClient started with {} connections", connections.size());
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping NostrClient");
        running = false;
        for (RelayConnection conn : connections.values()) {
            try {
                conn.disconnect();
            } catch (Exception e) {
                log.debug("Error disconnecting relay: {}", e.getMessage());
            }
        }
        connections.clear();
        log.info("NostrClient stopped");
    }

    /**
     * Publish an event to all connected relays using virtual threads.
     *
     * @param event the event to publish
     */
    public void publish(GenericEvent event) {
        if (!running) {
            log.warn("NostrClient not running, cannot publish");
            return;
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = connections.values().stream()
                    .filter(RelayConnection::isConnected)
                    .map(conn -> CompletableFuture.runAsync(() -> conn.publish(event), executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(5, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        log.warn("Some relay publishes timed out: {}", ex.getMessage());
                        return null;
                    })
                    .join();
        }
    }

    /**
     * Publish an event to specific relays.
     *
     * @param event     the event to publish
     * @param relayUrls specific relay URLs to publish to
     */
    public void publish(GenericEvent event, List<String> relayUrls) {
        if (!running) {
            return;
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = relayUrls.stream()
                    .map(url -> connections.get(url))
                    .filter(conn -> conn != null && conn.isConnected())
                    .map(conn -> CompletableFuture.runAsync(() -> conn.publish(event), executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
        }
    }

    /**
     * Subscribe to events matching a filter on all connected relays.
     *
     * @param kind     event kind to subscribe for
     * @param ref      optional ref tag filter
     * @param callback callback for received events
     * @return subscription ID
     */
    public String subscribe(int kind, String ref, Consumer<GenericEvent> callback) {
        String subscriptionId = UUID.randomUUID().toString().substring(0, 8);
        String filter = CashEventFilter.customerEventFilter(ref, null);

        Consumer<GenericEvent> wrappedCallback = event -> {
            lastEventTimestamps.put(subscriptionId, event.getCreatedAt());
            callback.accept(event);
        };

        for (RelayConnection conn : connections.values()) {
            conn.subscribe(subscriptionId, filter, wrappedCallback);
        }

        log.debug("Created subscription: id={}, kind={}, ref={}", subscriptionId, kind, ref);
        return subscriptionId;
    }

    /**
     * Subscribe to events on specific relays.
     *
     * @param kind      event kind
     * @param ref       optional ref filter
     * @param relayUrls specific relays
     * @param callback  callback for events
     * @return subscription ID
     */
    public String subscribe(int kind, String ref, List<String> relayUrls, Consumer<GenericEvent> callback) {
        String subscriptionId = UUID.randomUUID().toString().substring(0, 8);
        String filter = CashEventFilter.customerEventFilter(ref, null);

        Consumer<GenericEvent> wrappedCallback = event -> {
            lastEventTimestamps.put(subscriptionId, event.getCreatedAt());
            callback.accept(event);
        };

        for (String url : relayUrls) {
            RelayConnection conn = connections.get(url);
            if (conn == null) {
                // Connect to new relay
                conn = new WebSocketRelayConnection(url);
                connections.put(url, conn);
                conn.connect();
            }
            conn.subscribe(subscriptionId, filter, wrappedCallback);
        }

        return subscriptionId;
    }

    /**
     * Unsubscribe from all relays.
     *
     * @param subscriptionId the subscription to close
     */
    public void unsubscribe(String subscriptionId) {
        for (RelayConnection conn : connections.values()) {
            conn.unsubscribe(subscriptionId);
        }
        lastEventTimestamps.remove(subscriptionId);
        log.debug("Removed subscription: id={}", subscriptionId);
    }

    /**
     * Get the last event timestamp for a subscription (for replay on reconnect).
     *
     * @param subscriptionId the subscription ID
     * @return last event timestamp, or null if none received
     */
    public Long getLastEventTimestamp(String subscriptionId) {
        return lastEventTimestamps.get(subscriptionId);
    }

    /**
     * Check if client is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Get the number of connected relays.
     */
    public int getConnectedRelayCount() {
        return (int) connections.values().stream()
                .filter(RelayConnection::isConnected)
                .count();
    }
}
