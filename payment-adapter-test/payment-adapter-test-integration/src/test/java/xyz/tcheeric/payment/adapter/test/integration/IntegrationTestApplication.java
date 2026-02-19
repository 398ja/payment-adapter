package xyz.tcheeric.payment.adapter.test.integration;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
@EnableJpaRepositories("xyz.tcheeric.payment.adapter.core.model.repository")
public class IntegrationTestApplication {
}
