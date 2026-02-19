package xyz.tcheeric.payment.adapter.core.rest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
class PaymentGatewaySpringApplicationTests {

	@Test
	void contextLoads() {
	}

}
