package xyz.tcheeric.payment.adapter.core.rest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that this service's metrics are actually reachable.
 *
 * <p>The Prometheus registry was on the classpath for a long time while
 * {@code prometheus} was missing from the actuator exposure list, so
 * {@code /actuator/prometheus} returned 404 and every metric recorded here was
 * unreachable. Nothing failed, because a service whose metrics cannot be
 * scraped looks exactly like a service that is idle.
 *
 * <p>Two assertions are needed, and neither is sufficient alone. The boot test
 * proves the endpoint serves; but {@code src/test/resources/application.properties}
 * shadows the shipped one, so on its own it would only prove the test config.
 * The config test reads the shipped file directly, so it cannot be satisfied by
 * a test-only override.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.endpoints.web.exposure.include=health,info,prometheus,metrics",
                "management.prometheus.metrics.export.enabled=true",
                "management.server.port=0"
        })
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
class ActuatorPrometheusExposureTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalManagementPort
    private int managementPort;

    /** The endpoint serves, and serves real Prometheus exposition text. */
    @Test
    void prometheusEndpointServesMetrics() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode().value())
                .as("/actuator/prometheus must not 404 — metrics nothing can scrape are invisible")
                .isEqualTo(200);
        assertThat(response.getBody())
                .as("response must be Prometheus exposition format")
                .contains("# TYPE");
    }

    /**
     * The shipped configuration must not set {@code management.server.address}.
     *
     * <p>Spring Boot rejects that property outright whenever the management port
     * equals the server port — which is the shipped default — with
     * "Management-specific server address cannot be configured as the management
     * server is not listening on a separate port". Setting it unconditionally
     * crash-loops the service on startup.
     *
     * <p>This is not hypothetical: it took staging down on first deploy. The
     * boot tests above did not catch it because they pin {@code management.server.port=0},
     * which IS a separate port, so the illegal combination never arose.
     */
    @Test
    void shippedConfigurationDoesNotPinManagementAddress() throws IOException {
        Properties shipped = readShippedConfiguration();

        assertThat(shipped.getProperty("management.server.address"))
                .as("management.server.address must not be set while the management port "
                        + "defaults to the server port — Spring Boot refuses to start")
                .isNull();
    }

    /**
     * The shipped configuration, not the test override, is what production runs.
     * Read from the source tree rather than the classpath, because
     * {@code src/test/resources/application.properties} shadows the shipped file
     * and would otherwise be the thing under test.
     */
    @Test
    void shippedConfigurationExposesPrometheus() throws IOException {
        Properties shipped = readShippedConfiguration();

        assertThat(shipped.getProperty("management.endpoints.web.exposure.include"))
                .as("shipped actuator exposure list must include prometheus")
                .contains("prometheus");
        assertThat(shipped.getProperty("management.prometheus.metrics.export.enabled"))
                .isEqualTo("true");
        assertThat(shipped.getProperty("management.metrics.tags.application"))
                .as("common tags let one dashboard filter across mint and adapter")
                .isEqualTo("payment-adapter");
    }

    private Properties readShippedConfiguration() throws IOException {
        Path shipped = Path.of("src", "main", "resources", "application.properties");
        assertThat(shipped).as("shipped configuration must exist").exists();

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(shipped, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
