package xyz.tcheeric.payment.adapter.core.model;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
@Slf4j
public class PaymentGatewayModelApplication {

        public static void main(String[] args) {
                log.info("Starting PaymentGatewayModelApplication");
                SpringApplication.run(PaymentGatewayModelApplication.class, args);
        }

}
