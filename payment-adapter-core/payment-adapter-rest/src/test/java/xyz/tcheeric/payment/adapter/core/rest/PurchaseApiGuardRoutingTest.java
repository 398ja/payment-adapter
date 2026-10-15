package xyz.tcheeric.payment.adapter.core.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The purchase API's guard against the real container and router (second review of #280).
 *
 * <p>The filter's unit tests feed it URIs; whether a URI is dangerous depends on what Tomcat and
 * Spring then route. Spring strips matrix parameters from every segment and Tomcat resolves dot
 * segments, so {@code /api;x/v1/purchases/owed} reaches {@code owed()}. Raw sockets, because an
 * HTTP client would normalise the very paths under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "purchase.api.token=" + PurchaseApiGuardRoutingTest.ISSUER,
                "purchase.operator.token=" + PurchaseApiGuardRoutingTest.OPERATOR
        })
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
class PurchaseApiGuardRoutingTest {

    static final String ISSUER = "issuer-token-0123456789";
    static final String OPERATOR = "operator-token-9876543210";

    @LocalServerPort
    private int port;

    private int status(String method, String uri, String token) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            String request = method + " " + uri + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + (token == null ? "" : "Authorization: Bearer " + token + "\r\n")
                    + "Content-Type: application/json\r\nContent-Length: 18\r\n\r\n{\"action\":\"retry\"}";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    .readLine();
            return Integer.parseInt(line.split(" ")[1]);
        }
    }

    @Test
    void noRoutableDisguiseReadsTheQueueWithoutAToken() throws Exception {
        for (String uri : new String[] {
                "/api/v1/purchases/owed",
                "/api;x/v1/purchases/owed",
                "/api/v1;x/purchases/owed",
                "/api/v1/purchases;a=b/owed",
                "/api/v1/purchases;/owed",
                "/api/v1/%70urchases;a=b/owed",
                "/api/v1/purchases/attention",
                "/api;x/v1/purchases/attention"}) {
            assertThat(status("GET", uri, null)).as(uri).isNotEqualTo(200);
        }
    }

    @Test
    void noRoutableDisguiseLetsTheIssuerResolve() throws Exception {
        for (String uri : new String[] {
                "/api/v1/purchases/evt_1/resolve",
                "/api;a/v1/purchases/evt_1/resolve",
                "/api/v1/purchases;a=b/evt_1/resolve",
                "/api/v1/purchases/evt_1;a=b/resolve",
                "/api/v1/purchases/evt_1/resolve;x=1",
                "/api/v1/purchases/evt_1/%72esolve"}) {
            assertThat(status("POST", uri, ISSUER)).as(uri).isEqualTo(401);
        }
    }

    @Test
    void theIssuerStillReachesItsRoutes() throws Exception {
        assertThat(status("GET", "/api/v1/purchases/owed", ISSUER)).isEqualTo(200);
        assertThat(status("GET", "/api/v1/purchases/attention", ISSUER)).isEqualTo(200);
        assertThat(status("GET", "/api/v1/purchases/attention", OPERATOR)).isEqualTo(200);
        assertThat(status("GET", "/api/v1/purchases/owed", OPERATOR)).isEqualTo(401);
        // Reaches the controller (unknown purchase), so not refused by the guard.
        assertThat(status("POST", "/api/v1/purchases/evt_none/resolve", OPERATOR)).isEqualTo(404);
    }
}
