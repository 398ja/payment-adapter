package xyz.tcheeric.test.gateway;

import lombok.extern.java.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.model.PaymentMethod;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.common.config.Configuration;
import xyz.tcheeric.gateway.client.PaymentClient;
import xyz.tcheeric.gateway.client.QuoteClient;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;
import xyz.tcheeric.gateway.model.entity.enums.State;
import xyz.tcheeric.gateway.phoenixd.PhoenixdGateway;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Log
public class PhoenixdGatewayTest {

    private PhoenixdGateway gateway;

    private static final Properties properties = new Properties();
    private final static String CONFIG_FILE_PATH = "gw-test.properties";
    static {
        String configFilePath = System.getProperty(CONFIG_FILE_PATH);
        try (InputStream input = (configFilePath != null) ? new FileInputStream(configFilePath) : Configuration.class.getClassLoader().getResourceAsStream(CONFIG_FILE_PATH)) {
            if (input == null) {
                throw new IOException("Unable to find " + CONFIG_FILE_PATH);
            }
            properties.load(input);
        } catch (IOException ex) {
            log.log(Level.SEVERE, "Unable to load configuration", ex);
        }
    }

    @BeforeEach
    public void init() {
        gateway = new PhoenixdGateway();
    }

    @Test
    public void testCreateMintQuote() {
        // Arrange
        QuoteClient client = new QuoteClient();

        // Act
        System.setProperty("wid", "testCreateMintQuote");
        String quoteId = gateway.createMintQuote(10, "testCreateMintQuote" + System.currentTimeMillis());
        GatewayQuote quote = client.getByEntityId(quoteId);

        // Assert
        assertNotNull(quote);
        assertEquals(quoteId, quote.getQuoteId());
        assertNotEquals(State.CONFIRMED, quote.getState());
        assertEquals(State.PENDING, quote.getState());
        log.log(Level.ALL, "LN Invoice: {0}", quote.getRequest());

    }

    @Test
    public void testPayInvoice() {
        // Arrange
        PaymentClient paymentClient = new PaymentClient();
        QuoteClient quoteClient = new QuoteClient();

        // Act
        String payee = properties.getProperty("phoenixd.payee");
        String quoteId = gateway.createMeltQuote(10, payee,"testPayInvoice" + System.currentTimeMillis());
        String paymentId = gateway.pay(quoteId);
        GatewayPayment payment = paymentClient.getByEntityId(paymentId);
        GatewayQuote quote = quoteClient.getByEntityId(quoteId);

        // Assert
        assertNotNull(payment);
        assertNotNull(paymentId);
        assertEquals(paymentId, payment.getPaymentId());
        assertEquals(quote.getRequest(), payment.getRequest());
        assertEquals(State.PAID, payment.getState());
    }

    @Test
    public void testSupports()  {
        // Arrange & Act
        Gateway gateway = new PhoenixdGateway();

        // Assert
        assertTrue(gateway.supports(PaymentMethod.BOLT11));
        assertTrue(gateway.supports(PaymentMethod.BOLT12));
        assertTrue(gateway.supports(PaymentMethod.ON_CHAIN));
        assertFalse(gateway.supports(PaymentMethod.CASH));
        assertFalse(gateway.supports(PaymentMethod.PAYMENT_API));
        assertFalse(gateway.supports(PaymentMethod.CREDIT_CARD));
        assertFalse(gateway.supports(PaymentMethod.MOBILE_MONEY));
        assertFalse(gateway.supports(PaymentMethod.MOCK));
    }
}
