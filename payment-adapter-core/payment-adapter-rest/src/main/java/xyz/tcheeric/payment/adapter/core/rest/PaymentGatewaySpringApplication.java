package xyz.tcheeric.payment.adapter.core.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication(scanBasePackages = "xyz.tcheeric.payment.adapter")
// Payment providers deliver over plain servlets, not @RestControllers: WebhookServlet is
// declared with @WebServlet("/webhook/phoenixd"). Component scanning does not see servlet
// annotations, so without this the endpoint simply does not exist and every notification
// 404s silently — which is how payments stopped reaching the mint at all.
@ServletComponentScan("xyz.tcheeric.payment.adapter")
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
@EnableJpaRepositories(basePackages = "xyz.tcheeric.payment.adapter.core")
// Without this NO @Scheduled method in this application runs. It was previously declared
// only on CashGatewayConfig, so scheduling existed at all only as a side effect of that
// unrelated config being loaded — and the failure it produced is the quiet kind: a @Scheduled
// bean still constructs, still registers its gauge, and simply never ticks. The
// paid-unforwarded gauge published a confident 0.0 while the database held 88, which is worse
// than publishing nothing, because a zero reads as "no money stranded".
@EnableScheduling
public class PaymentGatewaySpringApplication {

    /**
     * Starts the service and does nothing else.
     *
     * <p>This used to dump every environment variable and Spring property at DEBUG, filtered by a
     * substring denylist of "secret-looking" names. A denylist can only fail open: it caught
     * {@code privatekey} but not {@code PRIVATE_KEY}, so {@code PURCHASE_GATEWAY_PRIVATE_KEY} went to
     * the staging logs in plaintext, and every name nobody thought of ({@code *_NSEC},
     * {@code *_MNEMONIC}, a jdbc URL carrying {@code password=}) would have followed. The dump existed
     * only for debugging, so it is gone rather than patched. Actuator {@code env} is not exposed in
     * any profile; if the resolved configuration is ever needed, expose it temporarily on the
     * unpublished management port, where values stay masked
     * ({@code management.endpoint.env.show-values} defaults to {@code never}).
     */
    public static void main(String[] args) {
        SpringApplication.run(PaymentGatewaySpringApplication.class, args);
    }
}
