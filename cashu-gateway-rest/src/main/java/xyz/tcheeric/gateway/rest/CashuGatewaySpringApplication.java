package xyz.tcheeric.gateway.rest;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;


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
        if (env == null) {
            log.debug("Environment is null; skipping property logging");
            return;
        }

        if (env.acceptsProfiles(Profiles.of("prod", "production"))) {
            log.debug("Skipping environment/property logging for production profiles");
            return;
        }

        if (!log.isDebugEnabled()) {
            return;
        }

        log.debug("System Environment Variables (filtered):");
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (isSafeKey(entry.getKey())) {
                log.debug("{} = {}", entry.getKey(), entry.getValue());
            }
        }

        log.debug("Application Properties (filtered):");
        if (env instanceof ConfigurableEnvironment configurableEnvironment) {
            Set<String> seen = new HashSet<>();
            for (PropertySource<?> propertySource : configurableEnvironment.getPropertySources()) {
                if (propertySource instanceof EnumerablePropertySource<?> enumerablePropertySource) {
                    for (String name : enumerablePropertySource.getPropertyNames()) {
                        if (seen.add(name) && isSafeKey(name)) {
                            log.debug("{} = {}", name, env.getProperty(name));
                        }
                    }
                }
            }
        } else {
            log.debug("Environment is not a ConfigurableEnvironment; cannot enumerate property names.");
        }
    }

    private static boolean isSafeKey(String key) {
        String lowerCaseKey = key.toLowerCase(Locale.ROOT);
        return !lowerCaseKey.contains("password")
                && !lowerCaseKey.contains("secret")
                && !lowerCaseKey.contains("key")
                && !lowerCaseKey.contains("token");
    }
}
