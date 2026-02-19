package xyz.tcheeric.payment.adapter.test.e2e;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
@EnableJpaRepositories("xyz.tcheeric.payment.adapter.core.model.repository")
@ComponentScan(basePackages = {
        "xyz.tcheeric.payment.adapter.cash.gateway",
        "xyz.tcheeric.payment.adapter.cash.webhook",
        "xyz.tcheeric.payment.adapter.test.e2e.config"
})
public class E2ETestApplication {
}
