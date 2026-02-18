package xyz.tcheeric.payment.adapter.cash.nostr.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import nostr.event.impl.GenericEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * WebSocket-based relay connection using Java HttpClient WebSocket.
 * Supports automatic reconnection with exponential backoff.
 */
@Slf4j
public class WebSocketRelayConnection implements RelayConnection {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RECONNECT_DELAY_SECONDS = 60;
    private static final int INITIAL_RECONNECT_DELAY_SECONDS = 1;

    private final String relayUrl;
    private final Map<String, Consumer<GenericEvent>> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, String> subscriptionFilters = new ConcurrentHashMap<>();
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private volatile WebSocket webSocket;
    private volatile boolean shouldReconnect = true;
    private volatile boolean connected = false;

    private final ScheduledExecutorService scheduler;

    public WebSocketRelayConnection(String relayUrl) {
        this.relayUrl = relayUrl;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "relay-reconnect-" + relayUrl);
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void connect() {
        log.info("Connecting to relay: {}", relayUrl);
        shouldReconnect = true;
        doConnect();
    }

    private void doConnect() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .executor(Executors.newVirtualThreadPerTaskExecutor())
                    .build();

            webSocket = client.newWebSocketBuilder()
                    .buildAsync(URI.create(relayUrl), new WebSocket.Listener() {
                        private final StringBuilder messageBuffer = new StringBuilder();

                        @Override
                        public void onOpen(WebSocket webSocket) {
                            log.info("Connected to relay: {}", relayUrl);
                            connected = true;
                            reconnectAttempts.set(0);
                            resubscribeAll();
                            WebSocket.Listener.super.onOpen(webSocket);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            messageBuffer.append(data);
                            if (last) {
                                handleMessage(messageBuffer.toString());
                                messageBuffer.setLength(0);
                            }
                            return WebSocket.Listener.super.onText(webSocket, data, last);
                        }

                        @Override
                        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
                            log.trace("Pong received from relay: {}", relayUrl);
                            return WebSocket.Listener.super.onPong(webSocket, message);
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            log.info("Relay disconnected: {} (code={}, reason={})", relayUrl, statusCode, reason);
                            connected = false;
                            scheduleReconnect();
                            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            log.warn("Relay error: {} - {}", relayUrl, error.getMessage());
                            connected = false;
                            scheduleReconnect();
                        }
                    })
                    .join();
        } catch (Exception e) {
            log.error("Failed to connect to relay: {} - {}", relayUrl, e.getMessage());
            connected = false;
            scheduleReconnect();
        }
    }

    @Override
    public void disconnect() {
        log.info("Disconnecting from relay: {}", relayUrl);
        shouldReconnect = false;
        connected = false;
        scheduler.shutdownNow();
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "closing").join();
            } catch (Exception e) {
                log.debug("Error closing WebSocket: {}", e.getMessage());
            }
        }
    }

    @Override
    public void publish(GenericEvent event) {
        if (!connected || webSocket == null) {
            log.warn("Cannot publish to disconnected relay: {}", relayUrl);
            return;
        }
        try {
            String eventJson = MAPPER.writeValueAsString(event);
            String message = "[\"EVENT\"," + eventJson + "]";
            webSocket.sendText(message, true);
            log.debug("Published event to relay {}: kind={}", relayUrl, event.getKind());
        } catch (Exception e) {
            log.error("Failed to publish event to relay {}: {}", relayUrl, e.getMessage());
        }
    }

    @Override
    public void subscribe(String subscriptionId, String filter, Consumer<GenericEvent> callback) {
        subscriptions.put(subscriptionId, callback);
        subscriptionFilters.put(subscriptionId, filter);

        if (connected && webSocket != null) {
            sendSubscription(subscriptionId, filter);
        }
    }

    @Override
    public void unsubscribe(String subscriptionId) {
        subscriptions.remove(subscriptionId);
        subscriptionFilters.remove(subscriptionId);

        if (connected && webSocket != null) {
            String closeMsg = "[\"CLOSE\",\"" + subscriptionId + "\"]";
            webSocket.sendText(closeMsg, true);
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String getRelayUrl() {
        return relayUrl;
    }

    private void sendSubscription(String subscriptionId, String filter) {
        try {
            String reqMsg = "[\"REQ\",\"" + subscriptionId + "\"," + filter + "]";
            webSocket.sendText(reqMsg, true);
            log.debug("Sent subscription to relay {}: id={}", relayUrl, subscriptionId);
        } catch (Exception e) {
            log.error("Failed to send subscription to relay {}: {}", relayUrl, e.getMessage());
        }
    }

    private void resubscribeAll() {
        for (Map.Entry<String, String> entry : subscriptionFilters.entrySet()) {
            sendSubscription(entry.getKey(), entry.getValue());
        }
    }

    private void handleMessage(String message) {
        try {
            JsonNode json = MAPPER.readTree(message);
            if (!json.isArray() || json.isEmpty()) {
                return;
            }

            String type = json.get(0).asText();
            if ("EVENT".equals(type) && json.size() >= 3) {
                String subId = json.get(1).asText();
                Consumer<GenericEvent> callback = subscriptions.get(subId);
                if (callback != null) {
                    GenericEvent event = MAPPER.treeToValue(json.get(2), GenericEvent.class);
                    callback.accept(event);
                }
            } else if ("OK".equals(type)) {
                log.debug("Event accepted by relay {}: {}", relayUrl, json);
            } else if ("NOTICE".equals(type)) {
                log.info("Relay notice from {}: {}", relayUrl, json.get(1).asText());
            }
        } catch (Exception e) {
            log.warn("Failed to parse relay message from {}: {}", relayUrl, e.getMessage());
        }
    }

    private void scheduleReconnect() {
        if (!shouldReconnect) {
            return;
        }
        int attempts = reconnectAttempts.incrementAndGet();
        int delay = Math.min(INITIAL_RECONNECT_DELAY_SECONDS * (1 << (attempts - 1)), MAX_RECONNECT_DELAY_SECONDS);
        log.info("Scheduling reconnect to {} in {}s (attempt {})", relayUrl, delay, attempts);
        try {
            scheduler.schedule(this::doConnect, delay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            log.debug("Reconnect scheduler shut down for relay: {}", relayUrl);
        }
    }
}
