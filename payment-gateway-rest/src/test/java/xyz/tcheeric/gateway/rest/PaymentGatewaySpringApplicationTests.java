package xyz.tcheeric.gateway.rest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EntityScan("xyz.tcheeric.gateway.model.entity")
class PaymentGatewaySpringApplicationTests {

	@Test
	void contextLoads() {
	}

}
