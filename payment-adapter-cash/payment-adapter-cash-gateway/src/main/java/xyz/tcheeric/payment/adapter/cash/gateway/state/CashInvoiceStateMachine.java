package xyz.tcheeric.payment.adapter.cash.gateway.state;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * State machine for cash invoice lifecycle management.
 *
 * <p>Valid state transitions:
 * <pre>
 * CREATED → PENDING (published to relays)
 * PENDING → INTENT_RECEIVED (customer intent received)
 * PENDING → PAID (direct confirmation without intent)
 * PENDING → EXPIRED (TTL exceeded)
 * PENDING → CANCELLED (merchant cancelled)
 * INTENT_RECEIVED → PAID (cash confirmed)
 * INTENT_RECEIVED → EXPIRED (TTL exceeded)
 * INTENT_RECEIVED → CANCELLED (merchant cancelled)
 * </pre>
 */
@Slf4j
@Component
public class CashInvoiceStateMachine {

    /**
     * Valid transitions from each state.
     */
    private static final EnumSet<CashInvoiceStatus> TERMINAL_STATES =
            EnumSet.of(CashInvoiceStatus.PAID, CashInvoiceStatus.EXPIRED, CashInvoiceStatus.CANCELLED);

    /**
     * Check if a transition is valid.
     *
     * @param from current state
     * @param to   target state
     * @return true if transition is valid
     */
    public boolean isValidTransition(CashInvoiceStatus from, CashInvoiceStatus to) {
        if (from == to) {
            return false; // No self-transitions
        }
        if (TERMINAL_STATES.contains(from)) {
            return false; // Cannot transition from terminal states
        }

        return switch (from) {
            case CREATED -> to == CashInvoiceStatus.PENDING;
            case PENDING -> to == CashInvoiceStatus.INTENT_RECEIVED ||
                           to == CashInvoiceStatus.PAID ||
                           to == CashInvoiceStatus.EXPIRED ||
                           to == CashInvoiceStatus.CANCELLED;
            case INTENT_RECEIVED -> to == CashInvoiceStatus.PAID ||
                                   to == CashInvoiceStatus.EXPIRED ||
                                   to == CashInvoiceStatus.CANCELLED;
            default -> false;
        };
    }

    /**
     * Get valid target states from a given state.
     *
     * @param from current state
     * @return set of valid target states
     */
    public Set<CashInvoiceStatus> getValidTransitions(CashInvoiceStatus from) {
        return switch (from) {
            case CREATED -> EnumSet.of(CashInvoiceStatus.PENDING);
            case PENDING -> EnumSet.of(
                    CashInvoiceStatus.INTENT_RECEIVED,
                    CashInvoiceStatus.PAID,
                    CashInvoiceStatus.EXPIRED,
                    CashInvoiceStatus.CANCELLED);
            case INTENT_RECEIVED -> EnumSet.of(
                    CashInvoiceStatus.PAID,
                    CashInvoiceStatus.EXPIRED,
                    CashInvoiceStatus.CANCELLED);
            default -> EnumSet.noneOf(CashInvoiceStatus.class);
        };
    }

    /**
     * Transition an invoice to a new state.
     *
     * @param invoice the invoice to transition
     * @param to      the target state
     * @return true if transition was successful
     * @throws IllegalStateException if transition is not valid
     */
    public boolean transition(CashInvoice invoice, CashInvoiceStatus to) {
        CashInvoiceStatus from = invoice.getStatus();

        if (!isValidTransition(from, to)) {
            throw new IllegalStateException(
                    String.format("Invalid state transition: %s → %s for invoice %s",
                            from, to, invoice.getRef()));
        }

        log.info("State transition: ref={}, {} → {}", invoice.getRef(), from, to);

        invoice.setStatus(to);

        // Update timestamps based on new state
        switch (to) {
            case PENDING -> invoice.setPublishedAt(Instant.now());
            case INTENT_RECEIVED -> invoice.setIntentReceivedAt(Instant.now());
            case PAID -> invoice.setPaidAt(Instant.now());
            case EXPIRED, CANCELLED -> {
                // Already handled by caller
            }
            default -> {}
        }

        return true;
    }

    /**
     * Check if invoice is in a terminal state (no further transitions possible).
     *
     * @param status the status to check
     * @return true if terminal
     */
    public boolean isTerminal(CashInvoiceStatus status) {
        return TERMINAL_STATES.contains(status);
    }

    /**
     * Check if invoice can receive an intent.
     *
     * @param status current status
     * @return true if intent can be received
     */
    public boolean canReceiveIntent(CashInvoiceStatus status) {
        return status == CashInvoiceStatus.PENDING;
    }

    /**
     * Check if invoice can be confirmed (paid).
     *
     * @param status current status
     * @return true if can be confirmed
     */
    public boolean canConfirm(CashInvoiceStatus status) {
        return status == CashInvoiceStatus.INTENT_RECEIVED ||
               status == CashInvoiceStatus.PENDING;
    }

    /**
     * Check if invoice can be cancelled.
     *
     * @param status current status
     * @return true if can be cancelled
     */
    public boolean canCancel(CashInvoiceStatus status) {
        return status == CashInvoiceStatus.PENDING ||
               status == CashInvoiceStatus.INTENT_RECEIVED;
    }

    /**
     * Check if invoice should be expired based on timestamp.
     *
     * @param invoice the invoice to check
     * @return true if expired
     */
    public boolean isExpired(CashInvoice invoice) {
        if (isTerminal(invoice.getStatus())) {
            return false;
        }
        return Instant.now().isAfter(invoice.getExpiresAt());
    }

    /**
     * Attempt to expire an invoice if TTL exceeded.
     *
     * @param invoice the invoice to check
     * @return true if invoice was expired
     */
    public boolean tryExpire(CashInvoice invoice) {
        if (isExpired(invoice)) {
            invoice.setStatus(CashInvoiceStatus.EXPIRED);
            invoice.setCancelReason("cash.expired");
            log.info("Invoice expired: ref={}", invoice.getRef());
            return true;
        }
        return false;
    }
}
