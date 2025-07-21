package xyz.tcheeric.gateway.config;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;

/**
 * Simple wrapper around Apache Commons Configuration to load properties with a prefix.
 */
public class AppConfig {
    private final Configuration configuration;
    private final String prefix;

    public AppConfig(String prefix) {
        this.prefix = prefix;
        try {
            this.configuration = new Configurations().properties("app.properties");
        } catch (ConfigurationException e) {
            throw new RuntimeException("Unable to load app.properties", e);
        }
    }

    public String get(String key) {
        return configuration.getString(prefix + "." + key);
    }

    public String get(String key, String defaultValue) {
        return configuration.getString(prefix + "." + key, defaultValue);
    }
}
