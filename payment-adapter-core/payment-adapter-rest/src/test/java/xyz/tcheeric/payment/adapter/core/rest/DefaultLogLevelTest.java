package xyz.tcheeric.payment.adapter.core.rest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * payment-adapter#287: the shipped {@code logback-spring.xml} sets {@code xyz.tcheeric} to INFO in
 * every profile. DEBUG (request and payload detail) is opt-in via {@code LOGGING_LEVEL_XYZ_TCHEERIC}.
 */
class DefaultLogLevelTest {

    private static Level configuredLevel(String override) throws Exception {
        LoggerContext context = new LoggerContext();
        if (override != null) {
            context.putProperty("LOGGING_LEVEL_XYZ_TCHEERIC", override);
        }
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try (InputStream xml = DefaultLogLevelTest.class.getResourceAsStream("/logback-spring.xml")) {
            assertThat(xml).as("logback-spring.xml on the classpath").isNotNull();
            configurator.doConfigure(xml);
        }
        Level level = context.getLogger("xyz.tcheeric").getLevel();
        context.stop();
        return level;
    }

    @Test
    void defaultsToInfo() throws Exception {
        assertThat(configuredLevel(null)).isEqualTo(Level.INFO);
    }

    @Test
    void debugIsOptInPerEnvironment() throws Exception {
        assertThat(configuredLevel("DEBUG")).isEqualTo(Level.DEBUG);
    }
}
