package xyz.tcheeric.payment.adapter.ln.dummy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class DummyGatewayTest {

    @Test
    void testGetName() {
        DummyGateway gateway = new DummyGateway();
        assertEquals("dummy", gateway.getName());
    }

    @Test
    void testGatewayMethodsReturnNonNull() {
        DummyGateway gateway = new DummyGateway();

        String mintQuote = gateway.createMintQuote(1, "test");
        String meltQuote = gateway.createMeltQuote(1, "invoice", "test");
        // pay must be called with a known quote id (e.g., melt quote)
        String payment = gateway.pay(meltQuote);
        // preimage should be available for auto-paid mint quotes
        String preimage = gateway.getPaymentPreimage(mintQuote);

        assertNotNull(mintQuote);
        assertNotNull(meltQuote);
        assertNotNull(payment);
        assertNotNull(preimage);
    }

    /**
     * Ensures paying with the exact quoteId returned by createMeltQuote produces a preimage
     * and that the stored preimage for that quoteId matches what pay() returned (consistency).
     */
    @Test
    void testQuoteIdConsistencyOnPay() {
        DummyGateway gateway = new DummyGateway();

        String invoice = "lnbc1-test-invoice";
        String quoteId = gateway.createMeltQuote(5, invoice, "consistency");

        String preimage = gateway.pay(quoteId);
        String storedPreimage = gateway.getPaymentPreimage(quoteId);

        assertNotNull(preimage);
        assertEquals(preimage, storedPreimage);
    }

    /**
     * Verifies that attempting to pay with an unknown/stale quoteId is rejected.
     */
    @Test
    void testPayWithUnknownQuoteIdThrows() {
        DummyGateway gateway = new DummyGateway();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> gateway.pay("unknown-quote-id"));
    }

    /**
     * Confirms that getRequest returns the same invoice that was provided when creating the quote.
     */
    @Test
    void testGetRequestMatchesCreatedInvoice() {
        DummyGateway gateway = new DummyGateway();
        String invoice = "lnbc1-sample-invoice";
        String quoteId = gateway.createMeltQuote(7, invoice, "match-invoice");

        String fetched = gateway.getRequest(quoteId);
        assertEquals(invoice, fetched);
    }
}
