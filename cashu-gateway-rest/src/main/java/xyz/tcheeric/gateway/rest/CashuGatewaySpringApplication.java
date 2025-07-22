package xyz.tcheeric.gateway.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
@EntityScan("xyz.tcheeric.gateway.model.entity")
@Slf4j
public class CashuGatewaySpringApplication {

        public static void main(String[] args) {
                log.info("Starting CashuGatewaySpringApplication");
                SpringApplication.run(CashuGatewaySpringApplication.class, args);
        }

}
