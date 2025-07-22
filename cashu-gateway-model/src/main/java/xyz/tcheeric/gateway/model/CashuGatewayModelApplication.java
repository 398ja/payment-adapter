package xyz.tcheeric.gateway.model;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
@Slf4j
public class CashuGatewayModelApplication {

        public static void main(String[] args) {
                log.info("Starting CashuGatewayModelApplication");
                SpringApplication.run(CashuGatewayModelApplication.class, args);
        }

}
