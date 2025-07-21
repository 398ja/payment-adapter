package xyz.tcheeric.gateway.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@Slf4j
@SpringBootApplication
@EntityScan("xyz.tcheeric.gateway.model.entity")
public class CashuGatewaySpringApplication {

        public static void main(String[] args) {
                log.info("Starting Cashu Gateway REST application...");
                SpringApplication.run(CashuGatewaySpringApplication.class, args);
                log.info("Cashu Gateway REST application started.");
        }

}
