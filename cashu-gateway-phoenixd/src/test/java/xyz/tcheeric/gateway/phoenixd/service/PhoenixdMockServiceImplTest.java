package xyz.tcheeric.gateway.phoenixd.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.phoenixd.model.param.CreateInvoiceParam;
import xyz.tcheeric.phoenixd.model.response.CreateInvoiceResponse;

/**
 * Verifies that PhoenixdMockServiceImpl can create an invoice using the mock server.
 */
public class PhoenixdMockServiceImplTest {

    private PhoenixdMockServiceImpl service;

    /**
     * Starts the mock server and ensures a non-null response when creating an invoice.
     */
    @Test
    public void testCreateInvoice() {
        service = new PhoenixdMockServiceImpl();
        CreateInvoiceParam param = new CreateInvoiceParam();
        param.setAmountSat(1);
        param.setDescription("test");
        CreateInvoiceResponse response = service.createInvoice(param);
        Assertions.assertNotNull(response);
    }

    @AfterEach
    public void tearDown() {
        if (service != null) {
            service.stop();
        }
    }
}

