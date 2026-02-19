package xyz.tcheeric.payment.adapter.core.common;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for Gateway implementations.
 * Allows registration and lookup of gateways by their ID or payment type.
 */
@Slf4j
public class GatewayRegistry {

    private final Map<String, Gateway> gatewaysById = new ConcurrentHashMap<>();
    private final Map<PaymentType, List<Gateway>> gatewaysByType = new ConcurrentHashMap<>();

    /**
     * Register a gateway
     * @param gateway the gateway to register
     */
    public void register(Gateway gateway) {
        String gatewayId = gateway.getGatewayId();
        PaymentType paymentType = gateway.getPaymentType();

        if (gatewaysById.containsKey(gatewayId)) {
            log.warn("Gateway with ID '{}' already registered, replacing", gatewayId);
        }

        gatewaysById.put(gatewayId, gateway);
        gatewaysByType.computeIfAbsent(paymentType, k -> new ArrayList<>()).add(gateway);

        log.info("Registered gateway '{}' for payment type '{}'", gatewayId, paymentType);
    }

    /**
     * Get a gateway by its ID
     * @param gatewayId the gateway ID
     * @return Optional containing the gateway if found
     */
    public Optional<Gateway> getById(String gatewayId) {
        return Optional.ofNullable(gatewaysById.get(gatewayId));
    }

    /**
     * Get all gateways for a payment type
     * @param paymentType the payment type
     * @return list of gateways (empty if none registered)
     */
    public List<Gateway> getByPaymentType(PaymentType paymentType) {
        return Collections.unmodifiableList(
            gatewaysByType.getOrDefault(paymentType, Collections.emptyList())
        );
    }

    /**
     * Get all registered gateways
     * @return unmodifiable collection of all gateways
     */
    public Collection<Gateway> getAll() {
        return Collections.unmodifiableCollection(gatewaysById.values());
    }

    /**
     * Get all registered gateway IDs
     * @return set of gateway IDs
     */
    public Set<String> getGatewayIds() {
        return Collections.unmodifiableSet(gatewaysById.keySet());
    }

    /**
     * Check if a gateway is registered
     * @param gatewayId the gateway ID
     * @return true if registered
     */
    public boolean isRegistered(String gatewayId) {
        return gatewaysById.containsKey(gatewayId);
    }

    /**
     * Unregister a gateway by ID
     * @param gatewayId the gateway ID to unregister
     * @return the removed gateway, or null if not found
     */
    public Gateway unregister(String gatewayId) {
        Gateway removed = gatewaysById.remove(gatewayId);
        if (removed != null) {
            List<Gateway> typeGateways = gatewaysByType.get(removed.getPaymentType());
            if (typeGateways != null) {
                typeGateways.remove(removed);
            }
            log.info("Unregistered gateway '{}'", gatewayId);
        }
        return removed;
    }

    /**
     * Clear all registered gateways
     */
    public void clear() {
        gatewaysById.clear();
        gatewaysByType.clear();
        log.info("Cleared all registered gateways");
    }
}
