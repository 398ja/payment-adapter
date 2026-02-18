package xyz.tcheeric.payment.adapter.cash.nostr.client;

import nostr.event.impl.GenericEvent;

import java.util.function.Consumer;

/**
 * Interface for connecting to a single Nostr relay.
 */
public interface RelayConnection {

    /**
     * Connect to the relay.
     */
    void connect();

    /**
     * Disconnect from the relay.
     */
    void disconnect();

    /**
     * Publish an event to the relay.
     *
     * @param event the event to publish
     */
    void publish(GenericEvent event);

    /**
     * Subscribe to events matching a filter.
     *
     * @param subscriptionId unique subscription identifier
     * @param filter         NIP-01 REQ filter JSON
     * @param callback       callback for received events
     */
    void subscribe(String subscriptionId, String filter, Consumer<GenericEvent> callback);

    /**
     * Unsubscribe from a subscription.
     *
     * @param subscriptionId the subscription to close
     */
    void unsubscribe(String subscriptionId);

    /**
     * Check if connected.
     *
     * @return true if the connection is active
     */
    boolean isConnected();

    /**
     * Get the relay URL.
     *
     * @return the relay URL
     */
    String getRelayUrl();
}
