package xyz.tcheeric.gateway.client;

import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Resolves configuration for gateway REST clients with a clear precedence order.
 */
final class GatewayClientConfig {

    private static final String KEY = "gateway.api.base_url";
    private static final String ENV = "GATEWAY_API_BASE_URL";
    private static final String CLASSPATH_FILE = "gateway.properties";
    private static final String DEFAULT = "http://localhost:8080";

    private GatewayClientConfig() {}

    static String resolveBaseUrl(String explicit) {
        // 1) Explicit constructor argument
        if (explicit != null && !explicit.isBlank()) {
            return normalize(explicit);
        }

        // 2) System property
        String sys = System.getProperty(KEY);
        if (sys != null && !sys.isBlank()) {
            return normalize(sys);
        }

        // 3) Environment variable
        String env = System.getenv(ENV);
        if (env != null && !env.isBlank()) {
            return normalize(env);
        }

        // 4) Classpath properties file
        Optional<String> fromFile = loadFromClasspath();
        if (fromFile.isPresent()) {
            return normalize(fromFile.get());
        }

        // 5) Default
        return normalize(DEFAULT);
    }

    private static Optional<String> loadFromClasspath() {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(CLASSPATH_FILE)) {
            if (in == null) {
                return Optional.empty();
            }
            Properties p = new Properties();
            p.load(in);
            String value = p.getProperty(KEY);
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
        } catch (Exception ignore) {
            return Optional.empty();
        }
    }

    private static String normalize(String url) {
        String trimmed = url.trim();
        // Drop trailing slashes for stable concatenation
        return trimmed.replaceAll("/+$", "");
    }
}

