package xyz.tcheeric.gateway.dummy;

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
}
