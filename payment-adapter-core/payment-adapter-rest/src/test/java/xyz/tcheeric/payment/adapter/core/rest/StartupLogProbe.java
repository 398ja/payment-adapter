package xyz.tcheeric.payment.adapter.core.rest;

/**
 * Child-JVM entry point for {@link StartupLogSecretLeakTest}.
 *
 * <p>Runs the real {@link PaymentGatewaySpringApplication#main} (so anything main does after the
 * context is up is included), prints a marker once main has returned, and exits. Without the
 * explicit exit the embedded web server would keep the JVM alive forever.
 */
public final class StartupLogProbe {

    static final String DONE_MARKER = "STARTUP-LOG-PROBE-DONE";

    private StartupLogProbe() {
    }

    public static void main(String[] args) {
        int status = 0;
        try {
            PaymentGatewaySpringApplication.main(args);
        } catch (Throwable t) {
            t.printStackTrace();
            status = 1;
        }
        System.out.println(DONE_MARKER);
        System.out.flush();
        System.exit(status);
    }
}
