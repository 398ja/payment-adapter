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
                String port = System.getProperty("cashu_gateway_port");
                if (port != null && !port.isBlank()) {
                        System.setProperty("server.port", port);
                }
                log.info("Starting CashuGatewaySpringApplication on port {}",
                                System.getProperty("server.port", "8080"));
                String wid = System.getProperty("wid");
                if (wid != null && !wid.isBlank()) {
                        log.info("wid system property detected: {}", wid);
                } else {
                        log.info("wid system property not set");
                }
                SpringApplication.run(CashuGatewaySpringApplication.class, args);
        }

}
