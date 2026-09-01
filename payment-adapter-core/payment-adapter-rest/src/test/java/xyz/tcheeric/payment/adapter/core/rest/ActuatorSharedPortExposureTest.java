package xyz.tcheeric.payment.adapter.core.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the app the way production does: actuator sharing the application port.
 *
 * <p>{@link ActuatorPrometheusExposureTest} pins {@code management.server.port=0}
 * to obtain a separate management port, which is a configuration production does
 * not use by default. That difference hid a startup crash — with
 * {@code management.server.address} set while the ports are shared, Spring Boot
 * refuses to start at all ("Management-specific server address cannot be
 * configured as the management server is not listening on a separate port"), and
 * the service crash-looped on deploy.
 *
 * <p>So this test deliberately does NOT override the management port. Whatever
 * the shipped configuration does on a shared port, it does here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.endpoints.web.exposure.include=health,info,prometheus,metrics",
                "management.prometheus.metrics.export.enabled=true"
        })
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
class ActuatorSharedPortExposureTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int serverPort;

    /**
     * The context starting at all is most of the assertion: the regression this
     * guards against was a refusal to boot. Serving metrics on the shared port
     * confirms the endpoint is still reachable in that arrangement.
     */
    @Test
    void servesMetricsOnTheApplicationPortWhenTheManagementPortIsNotSplit() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + serverPort + "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode().value())
                .as("actuator must serve on the application port when the ports are shared")
                .isEqualTo(200);
        assertThat(response.getBody()).contains("# TYPE");
    }
}
