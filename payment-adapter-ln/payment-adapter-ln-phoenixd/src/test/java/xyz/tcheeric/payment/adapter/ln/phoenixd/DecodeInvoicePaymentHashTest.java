package xyz.tcheeric.payment.adapter.ln.phoenixd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.ln.phoenixd.service.PhoenixdServiceImpl;
import xyz.tcheeric.phoenixd.model.param.DecodeInvoiceParam;
import xyz.tcheeric.phoenixd.model.response.DecodeInvoiceResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression for phoenixd-java 0.3.0, whose {@code DecodeInvoiceResponse.paymentHash} was
 * {@code @JsonIgnore}d. Every RECEIVE quote lookup decodes the quote's invoice for its payment
 * hash, so with 0.3.0 the hash was always null, every lookup failed as unavailable and the mint
 * answered 500 ({@code phoenixd_lookup_failed}). This affected real phoenixd, not only the mock.
 *
 * <p>The sample is a real phoenixd {@code /decodeinvoice} answer: lightning-kmp's Bolt11Invoice
 * serialised, field names and nesting included.
 */
class DecodeInvoicePaymentHashTest {

    private static final String SAMPLE = "/phoenixd/decodeinvoice-response.json";
    private static final String SAMPLE_HASH =
            "5a3e6c1f0f8b2d47e9a1c3b5d7f90e2a4c6e8a0b2d4f6a8c0e2a4c6e8a0b2d4f";

    private HttpServer server;
    private String previousBaseUrl;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/decodeinvoice", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = sample();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        previousBaseUrl = System.getProperty("phoenixd.base_url");
        System.setProperty("phoenixd.base_url", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        if (previousBaseUrl == null) {
            System.clearProperty("phoenixd.base_url");
        } else {
            System.setProperty("phoenixd.base_url", previousBaseUrl);
        }
    }

    // Reading phoenixd's real /decodeinvoice JSON into the library's model must keep the payment
    // hash. It is read with a default ObjectMapper, as phoenixd-rest does, so every field phoenixd
    // sends must also be accepted. On phoenixd-java 0.3.0 the hash came back null.
    @Test
    void theModelKeepsThePaymentHashFromARealPhoenixdAnswer() throws IOException {
        DecodeInvoiceResponse decoded = new ObjectMapper().readValue(sample(), DecodeInvoiceResponse.class);

        assertEquals(SAMPLE_HASH, decoded.getPaymentHash());
        assertEquals(30000, decoded.getAmount());
        assertEquals("Imani voucher", decoded.getDescription());
    }

    // The same answer through the adapter's own HTTP client, the call PhoenixdGateway makes before
    // asking phoenixd whether the invoice was paid. The hash it returns is what the lookup uses.
    @Test
    void theAdapterClientReturnsThePaymentHashOverHttp() {
        DecodeInvoiceParam param = new DecodeInvoiceParam();
        param.setInvoice("lnbc300n1ptest");

        DecodeInvoiceResponse decoded = new PhoenixdServiceImpl().decodeInvoice(param);

        assertNotNull(decoded);
        assertEquals(SAMPLE_HASH, decoded.getPaymentHash());
    }

    private static byte[] sample() throws IOException {
        try (InputStream in = DecodeInvoicePaymentHashTest.class.getResourceAsStream(SAMPLE)) {
            assertNotNull(in, SAMPLE + " is missing");
            return in.readAllBytes();
        }
    }
}
