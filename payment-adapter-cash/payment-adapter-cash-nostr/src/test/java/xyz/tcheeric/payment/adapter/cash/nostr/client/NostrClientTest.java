package xyz.tcheeric.payment.adapter.cash.nostr.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NostrClientTest {

    // Verifies that NostrClient constructs with CSV relay URLs
    @Test
    void testConstructWithCsv() {
        NostrClient client = new NostrClient("wss://relay1.example.com,wss://relay2.example.com");
        assertNotNull(client);
        assertFalse(client.isRunning());
    }

    // Verifies that NostrClient constructs with list of relay URLs
    @Test
    void testConstructWithList() {
        NostrClient client = new NostrClient(java.util.List.of("wss://relay1.example.com"));
        assertNotNull(client);
        assertFalse(client.isRunning());
    }

    // Verifies that getConnectedRelayCount returns 0 when not started
    @Test
    void testConnectedRelayCountNotStarted() {
        NostrClient client = new NostrClient("wss://relay.example.com");
        assertEquals(0, client.getConnectedRelayCount());
    }

    // Verifies that CashEventFilter builds correct intent filter JSON
    @Test
    void testIntentFilterGeneration() {
        String filter = CashEventFilter.intentFilter("abc123", null);
        assertNotNull(filter);
        assertTrue(filter.contains("5201"));
        assertTrue(filter.contains("abc123"));
    }

    // Verifies that CashEventFilter builds customer event filter with both intent and cancel
    @Test
    void testCustomerEventFilterGeneration() {
        String filter = CashEventFilter.customerEventFilter("ref456", 1000L);
        assertNotNull(filter);
        assertTrue(filter.contains("5201"));
        assertTrue(filter.contains("5203"));
        assertTrue(filter.contains("ref456"));
        assertTrue(filter.contains("1000"));
    }

    // Verifies that cancel filter is correctly built
    @Test
    void testCancelFilterGeneration() {
        String filter = CashEventFilter.cancelFilter("ref789", null);
        assertNotNull(filter);
        assertTrue(filter.contains("5203"));
        assertTrue(filter.contains("ref789"));
    }

    // Verifies that all cash events filter includes all kinds
    @Test
    void testAllCashEventsFilter() {
        String filter = CashEventFilter.allCashEventsFilter(null);
        assertNotNull(filter);
        assertTrue(filter.contains("5200"));
        assertTrue(filter.contains("5201"));
        assertTrue(filter.contains("5202"));
        assertTrue(filter.contains("5203"));
        assertTrue(filter.contains("5204"));
    }
}
