package xyz.tcheeric.payment.adapter.cash.gateway.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class CashInvoiceStateMachineTest {

    private CashInvoiceStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new CashInvoiceStateMachine();
    }

    // Verifies CREATED -> PENDING is valid
    @Test
    void testCreatedToPending() {
        assertTrue(stateMachine.isValidTransition(CashInvoiceStatus.CREATED, CashInvoiceStatus.PENDING));
    }

    // Verifies PENDING -> INTENT_RECEIVED is valid
    @Test
    void testPendingToIntentReceived() {
        assertTrue(stateMachine.isValidTransition(CashInvoiceStatus.PENDING, CashInvoiceStatus.INTENT_RECEIVED));
    }

    // Verifies PENDING -> EXPIRED is valid
    @Test
    void testPendingToExpired() {
        assertTrue(stateMachine.isValidTransition(CashInvoiceStatus.PENDING, CashInvoiceStatus.EXPIRED));
    }

    // Verifies PENDING -> CANCELLED is valid
    @Test
    void testPendingToCancelled() {
        assertTrue(stateMachine.isValidTransition(CashInvoiceStatus.PENDING, CashInvoiceStatus.CANCELLED));
    }

    // Verifies INTENT_RECEIVED -> PAID is valid
    @Test
    void testIntentReceivedToPaid() {
        assertTrue(stateMachine.isValidTransition(CashInvoiceStatus.INTENT_RECEIVED, CashInvoiceStatus.PAID));
    }

    // Verifies INTENT_RECEIVED -> EXPIRED is valid
    @Test
    void testIntentReceivedToExpired() {
        assertTrue(stateMachine.isValidTransition(CashInvoiceStatus.INTENT_RECEIVED, CashInvoiceStatus.EXPIRED));
    }

    // Verifies terminal states cannot transition
    @Test
    void testTerminalStatesCannotTransition() {
        assertFalse(stateMachine.isValidTransition(CashInvoiceStatus.PAID, CashInvoiceStatus.CANCELLED));
        assertFalse(stateMachine.isValidTransition(CashInvoiceStatus.EXPIRED, CashInvoiceStatus.PENDING));
        assertFalse(stateMachine.isValidTransition(CashInvoiceStatus.CANCELLED, CashInvoiceStatus.PENDING));
    }

    // Verifies self-transitions are not allowed
    @Test
    void testSelfTransitionNotAllowed() {
        assertFalse(stateMachine.isValidTransition(CashInvoiceStatus.PENDING, CashInvoiceStatus.PENDING));
    }

    // Verifies invalid forward transition is rejected
    @Test
    void testInvalidTransition() {
        assertFalse(stateMachine.isValidTransition(CashInvoiceStatus.CREATED, CashInvoiceStatus.PAID));
    }

    // Verifies PENDING -> PAID is valid (direct confirmation)
    @Test
    void testPendingToPaid() {
        assertTrue(stateMachine.isValidTransition(CashInvoiceStatus.PENDING, CashInvoiceStatus.PAID));
    }

    // Verifies transition() updates invoice status and timestamps
    @Test
    void testTransitionUpdatesInvoice() {
        CashInvoice invoice = CashInvoice.create("aabb11", "pubkey", "privkey",
                1500, "USD", "test", Instant.now().plusSeconds(300), "wss://relay.test");

        stateMachine.transition(invoice, CashInvoiceStatus.PENDING);
        assertEquals(CashInvoiceStatus.PENDING, invoice.getStatus());
        assertNotNull(invoice.getPublishedAt());

        stateMachine.transition(invoice, CashInvoiceStatus.INTENT_RECEIVED);
        assertEquals(CashInvoiceStatus.INTENT_RECEIVED, invoice.getStatus());
        assertNotNull(invoice.getIntentReceivedAt());

        stateMachine.transition(invoice, CashInvoiceStatus.PAID);
        assertEquals(CashInvoiceStatus.PAID, invoice.getStatus());
        assertNotNull(invoice.getPaidAt());
    }

    // Verifies transition() throws on invalid state change
    @Test
    void testTransitionThrowsOnInvalid() {
        CashInvoice invoice = CashInvoice.create("aabb11", "pubkey", "privkey",
                1500, "USD", "test", Instant.now().plusSeconds(300), "wss://relay.test");

        assertThrows(IllegalStateException.class,
                () -> stateMachine.transition(invoice, CashInvoiceStatus.PAID));
    }

    // Verifies tryExpire expires an invoice past its TTL
    @Test
    void testTryExpireExpiresInvoice() {
        CashInvoice invoice = CashInvoice.create("aabb11", "pubkey", "privkey",
                1500, "USD", "test", Instant.now().minusSeconds(10), "wss://relay.test");
        invoice.setStatus(CashInvoiceStatus.PENDING);

        assertTrue(stateMachine.tryExpire(invoice));
        assertEquals(CashInvoiceStatus.EXPIRED, invoice.getStatus());
        assertEquals("cash.expired", invoice.getCancelReason());
    }

    // Verifies tryExpire does not expire non-expired invoice
    @Test
    void testTryExpireDoesNotExpireActive() {
        CashInvoice invoice = CashInvoice.create("aabb11", "pubkey", "privkey",
                1500, "USD", "test", Instant.now().plusSeconds(300), "wss://relay.test");
        invoice.setStatus(CashInvoiceStatus.PENDING);

        assertFalse(stateMachine.tryExpire(invoice));
        assertEquals(CashInvoiceStatus.PENDING, invoice.getStatus());
    }

    // Verifies tryExpire does not re-expire terminal states
    @Test
    void testTryExpireIgnoresTerminalState() {
        CashInvoice invoice = CashInvoice.create("aabb11", "pubkey", "privkey",
                1500, "USD", "test", Instant.now().minusSeconds(10), "wss://relay.test");
        invoice.setStatus(CashInvoiceStatus.PAID);

        assertFalse(stateMachine.tryExpire(invoice));
        assertEquals(CashInvoiceStatus.PAID, invoice.getStatus());
    }

    // Verifies canReceiveIntent, canConfirm, canCancel guards
    @Test
    void testGuardMethods() {
        assertTrue(stateMachine.canReceiveIntent(CashInvoiceStatus.PENDING));
        assertFalse(stateMachine.canReceiveIntent(CashInvoiceStatus.INTENT_RECEIVED));

        assertTrue(stateMachine.canConfirm(CashInvoiceStatus.INTENT_RECEIVED));
        assertTrue(stateMachine.canConfirm(CashInvoiceStatus.PENDING));
        assertFalse(stateMachine.canConfirm(CashInvoiceStatus.PAID));

        assertTrue(stateMachine.canCancel(CashInvoiceStatus.PENDING));
        assertTrue(stateMachine.canCancel(CashInvoiceStatus.INTENT_RECEIVED));
        assertFalse(stateMachine.canCancel(CashInvoiceStatus.PAID));
    }

    // Verifies terminal state detection
    @Test
    void testIsTerminal() {
        assertTrue(stateMachine.isTerminal(CashInvoiceStatus.PAID));
        assertTrue(stateMachine.isTerminal(CashInvoiceStatus.EXPIRED));
        assertTrue(stateMachine.isTerminal(CashInvoiceStatus.CANCELLED));
        assertFalse(stateMachine.isTerminal(CashInvoiceStatus.PENDING));
        assertFalse(stateMachine.isTerminal(CashInvoiceStatus.CREATED));
    }

    // Verifies getValidTransitions returns correct sets
    @Test
    void testGetValidTransitions() {
        var pending = stateMachine.getValidTransitions(CashInvoiceStatus.PENDING);
        assertTrue(pending.contains(CashInvoiceStatus.INTENT_RECEIVED));
        assertTrue(pending.contains(CashInvoiceStatus.PAID));
        assertTrue(pending.contains(CashInvoiceStatus.EXPIRED));
        assertTrue(pending.contains(CashInvoiceStatus.CANCELLED));
        assertEquals(4, pending.size());

        var terminal = stateMachine.getValidTransitions(CashInvoiceStatus.PAID);
        assertTrue(terminal.isEmpty());
    }
}
