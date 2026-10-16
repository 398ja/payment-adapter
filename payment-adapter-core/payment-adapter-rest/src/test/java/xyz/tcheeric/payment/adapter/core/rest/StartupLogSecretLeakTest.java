package xyz.tcheeric.payment.adapter.core.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * No secret value reaches the startup log, even at DEBUG.
 *
 * <p>Found on staging (2026-10-16): at DEBUG, startup dumped every environment variable and Spring
 * property through a substring denylist. The denylist knew {@code privatekey} and
 * {@code private-key} but not {@code private_key}, so {@code PURCHASE_GATEWAY_PRIVATE_KEY} was
 * logged in plaintext. The fix removes the dump; these tests keep it removed, and catch any other
 * startup code that prints configuration values.
 *
 * <p>Why a child JVM rather than {@code @SpringBootTest}: the dump read {@code System.getenv()}
 * and ran in {@code main} after the context was up. Neither is reachable from a test context,
 * and a JVM's environment cannot be changed from inside it. So the application is started the way
 * a container starts it, through its real {@code main}, with the secrets as real environment
 * variables and DEBUG on, and everything it writes to stdout and stderr is captured.
 */
class StartupLogSecretLeakTest {

    private static final String GATEWAY_PRIVATE_KEY =
            "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318";
    private static final String STRIPE_SECRET_KEY = "sk_test_LEAKCANARY0123456789abcdefXYZ";
    private static final String DB_PASSWORD = "LeakCanaryDbPw7731";
    private static final String NSEC = "nsec1leakcanaryqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq";

    private static String output;
    private static int exitCode;

    @TempDir
    static Path workDir;

    @BeforeAll
    static void startTheApplicationWithSecretsInItsEnvironment() throws Exception {
        Path log = workDir.resolve("startup.log");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(StartupLogProbe.class.getName());

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        Map<String, String> env = builder.environment();
        // Start from nothing: the child must not inherit the developer's or CI's real environment,
        // which the old dump would have copied into the test reports.
        String path = env.get("PATH");
        env.clear();
        if (path != null) {
            env.put("PATH", path);
        }
        env.put("HOME", workDir.toString());
        env.put("PURCHASE_GATEWAY_PRIVATE_KEY", GATEWAY_PRIVATE_KEY);
        env.put("STRIPE_SECRET_KEY", STRIPE_SECRET_KEY);
        // A harmless-looking name whose VALUE carries the credential. Not consumed by the app,
        // so the test database is unaffected, but the old dump printed it in full.
        env.put("REPORTING_DATASOURCE_URL",
                "jdbc:postgresql://db:5432/payments?user=pa&password=" + DB_PASSWORD);
        env.put("IMANI_SIGNER_NSEC", NSEC);
        env.put("LOGGING_LEVEL_ROOT", "DEBUG");
        env.put("LOGGING_LEVEL_XYZ_TCHEERIC", "DEBUG");
        env.put("SERVER_PORT", "0");
        env.put("MANAGEMENT_SERVER_PORT", "0");

        Process process = builder.start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("application did not finish starting within 180s");
        }
        exitCode = process.exitValue();
        output = Files.readString(log, StandardCharsets.UTF_8);
    }

    // Guards against a vacuous pass: the other tests mean nothing unless the application really
    // started, through main, with DEBUG logging actually on.
    @Test
    void theApplicationStartedThroughMainWithDebugLoggingOn() {
        assertThat(exitCode).as("child exit code; output:%n%s", tail()).isZero();
        assertThat(output).contains(StartupLogProbe.DONE_MARKER);
        assertThat(output).contains("Started PaymentGatewaySpringApplication");
        assertThat(output).contains(" DEBUG ");
    }

    // The staging leak itself: an env var spelled with an underscore, which the old denylist missed.
    @Test
    void theGatewayPrivateKeyIsNotLogged() {
        assertNotLogged(GATEWAY_PRIVATE_KEY);
    }

    // A Stripe secret key must never be logged, whatever its variable happens to be called.
    @Test
    void theStripeSecretKeyIsNotLogged() {
        assertNotLogged(STRIPE_SECRET_KEY);
    }

    // A credential carried inside a URL: the variable name (REPORTING_DATASOURCE_URL) looks harmless,
    // which is exactly why a name-based filter cannot protect it.
    @Test
    void aPasswordInsideTheJdbcUrlIsNotLogged() {
        assertNotLogged(DB_PASSWORD);
    }

    // A secret under a name no denylist anticipated, standing in for every name nobody thought of.
    @Test
    void aSecretUnderAnUnanticipatedNameIsNotLogged() {
        assertNotLogged(NSEC);
    }

    private static void assertNotLogged(String secret) {
        // A boolean on purpose: doesNotContain would echo the whole output, secret included,
        // into the failure message and the surefire report.
        assertThat(output.contains(secret))
                .as("startup output must not contain the secret value")
                .isFalse();
    }

    private static String tail() {
        return output.length() <= 4000 ? output : output.substring(output.length() - 4000);
    }
}
