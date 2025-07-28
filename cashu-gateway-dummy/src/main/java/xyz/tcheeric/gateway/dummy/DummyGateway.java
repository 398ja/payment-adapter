package xyz.tcheeric.gateway.dummy;

import lombok.NonNull;
import xyz.tcheeric.gateway.common.Gateway;

import java.io.IOException;
import java.util.Properties;
import java.util.UUID;

public class DummyGateway implements Gateway {

    private static final String GATEWAY_NAME = "dummy";
    private static final Properties properties = new Properties();

    static {
        try (var inputStream = DummyGateway.class.getClassLoader().getResourceAsStream("app.properties")) {
            if (inputStream != null) {
                properties.load(inputStream);
            } else {
                throw new IllegalStateException("Could not find app.properties file in the classpath.");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load app.properties file.", e);
        }
    }

    @Override
    public String createMintQuote(@NonNull Integer amount, String description) {
        return UUID.randomUUID().toString();
    }

    @Override
    public String createMeltQuote(@NonNull Integer amount, @NonNull String lnIvoice, String description) {
        return UUID.randomUUID().toString();
    }

    @Override
    public String createMeltQuote(@NonNull String s) {
        return UUID.randomUUID().toString();
    }

    @Override
    public String getRequest(@NonNull String paymentId) {
        return UUID.randomUUID().toString();
    }

    @Override
    public boolean checkPaymentStatus(@NonNull String quoteId) {
        return getPaymentStatus();
    }

    @Override
    public String getPaymentPreimage(@NonNull String quoteId) {
        return UUID.randomUUID().toString();
    }

    @Override
    public String pay(@NonNull String s) {
        return UUID.randomUUID().toString();
    }

    @Override
    public Integer getAmount(@NonNull String quoteId) {
        return getAmount();
    }

    @Override
    public Integer getPaymentExpiry(@NonNull String quoteId) {
        return getExpiry();
    }

    @Override
    public Integer getFeeReserve(@NonNull String quoteId) {
        return getFeeReserve();
    }

    @Override
    public String getName() {
        return GATEWAY_NAME;
    }

    private boolean getPaymentStatus() {
        int status = Integer.parseInt(properties.getProperty("dummy.payment_status", "0"));
        return Math.random() < status / 100.0;
    }

    private Integer getAmount() {
        return Integer.parseInt(properties.getProperty("dummy.amount", "0"));
    }

    private Integer getExpiry() {
        return Integer.parseInt(properties.getProperty("dummy.expiry", "0"));
    }

    private Integer getFeeReserve() {
        return Integer.parseInt(properties.getProperty("dummy.fee_reserve", "0"));
    }
}