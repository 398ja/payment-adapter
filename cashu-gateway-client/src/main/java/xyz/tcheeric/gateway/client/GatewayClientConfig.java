package xyz.tcheeric.gateway.client;

import lombok.extern.slf4j.Slf4j;
import java.io.InputStream;
import java.net.URI;
import java.util.Optional;
import java.util.Properties;

/**
 * Resolves configuration for gateway REST clients with a clear precedence order.
 */
@Slf4j
final class GatewayClientConfig {

    private static final String KEY = "gateway.api.base_url";
    private static final String ENV = "GATEWAY_API_BASE_URL";
    private static final String CLASSPATH_FILE = "gateway.properties";
    private static final String KEY_PORT = "gateway.api.port";
    private static final String ENV_PORT = "GATEWAY_API_PORT";
    private static final String DEFAULT_PORT = "8080";
    private static final String DEFAULT = "http://localhost:8080";

    private GatewayClientConfig() {}

    static String resolveBaseUrl(String explicit) {
        // 1) Explicit constructor argument
        String resolved = firstNonBlank(explicit,
                System.getProperty(KEY),
                System.getenv(ENV),
                loadFromClasspath(KEY).orElse(null),
                DEFAULT);

        String port = firstNonBlank(
                System.getProperty(KEY_PORT),
                System.getenv(ENV_PORT),
                loadFromClasspath(KEY_PORT).orElse(DEFAULT_PORT),
                DEFAULT_PORT);

        String normalized = normalize(resolved);
        String finalUrl = ensurePort(normalized, port);
        log.debug("Resolved gateway client base URL: {} (port={})", finalUrl, port);
        return finalUrl;
    }

    private static Optional<String> loadFromClasspath(String key) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(CLASSPATH_FILE)) {
            if (in == null) {
                log.trace("ClassPath properties not found: {}", CLASSPATH_FILE);
                return Optional.empty();
            }
            Properties p = new Properties();
            p.load(in);
            String value = p.getProperty(key);
            if (value != null && !value.isBlank()) {
                log.trace("Loaded '{}' from {}", key, CLASSPATH_FILE);
            }
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
        } catch (Exception ignore) {
            return Optional.empty();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String normalize(String url) {
        String trimmed = url.trim();
        // Drop trailing slashes for stable concatenation
        return trimmed.replaceAll("/+$", "");
    }

    private static String ensurePort(String url, String port) {
        try {
            // If url does not contain scheme, treat as host
            boolean hasScheme = url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*$");
            if (!hasScheme) {
                return "http://" + url + ":" + port;
            }

            URI uri = URI.create(url);
            if (uri.getPort() != -1) {
                return url; // Port already specified
            }
            // Rebuild URI with port
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String authority = (uri.getUserInfo() != null ? uri.getUserInfo() + "@" : "") + host + ":" + port;
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String query = uri.getRawQuery();
            String fragment = uri.getRawFragment();
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(authority).append(path);
            if (query != null) sb.append('?').append(query);
            if (fragment != null) sb.append('#').append(fragment);
            return sb.toString();
        } catch (Exception ignore) {
            // Fallback: assume host-only
            return "http://" + url + ":" + port;
        }
    }
}
