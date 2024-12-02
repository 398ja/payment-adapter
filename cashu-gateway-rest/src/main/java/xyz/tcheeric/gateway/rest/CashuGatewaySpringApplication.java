package xyz.tcheeric.gateway.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication
@EntityScan("xyz.tcheeric.gateway.model.entity")
public class CashuGatewaySpringApplication {

	public static void main(String[] args) {
		SpringApplication.run(CashuGatewaySpringApplication.class, args);
	}

}
