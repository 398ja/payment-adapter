package xyz.tcheeric.gateway.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import java.util.Map;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.EnumerablePropertySource;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;


@SpringBootApplication
@EntityScan("xyz.tcheeric.gateway.model.entity")
@Slf4j
public class CashuGatewaySpringApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(CashuGatewaySpringApplication.class);
        Environment env = app.run(args).getEnvironment();
        displayAllProperties(env);
    }

    public static void displayAllProperties(Environment env) {
        // Log system environment variables
        log.info("System Environment Variables:");
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            log.info("{} = {}", entry.getKey(), entry.getValue());
        }

        // Log application properties
        log.info("Application Properties:");
        if (env instanceof ConfigurableEnvironment) {
            Set<String> seen = new HashSet<>();
            for (PropertySource<?> propertySource : ((ConfigurableEnvironment) env).getPropertySources()) {
                if (propertySource instanceof EnumerablePropertySource) {
                    for (String name : ((EnumerablePropertySource<?>) propertySource).getPropertyNames()) {
                        if (seen.add(name)) {
                            log.info("{} = {}", name, env.getProperty(name));
                        }
                    }
                }
            }
        } else {
            log.warn("Environment is not a ConfigurableEnvironment, cannot enumerate property names.");
        }
    }
}
