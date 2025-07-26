package xyz.tcheeric.gateway.dummy;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.entities.annotation.Supports;
import xyz.tcheeric.common.util.Configuration;
import xyz.tcheeric.gateway.common.Gateway;

import java.util.UUID;

@Supports({PaymentMethod.MOCK})
public class DummyGateway implements Gateway {

    private static final String GATEWAY_NAME = "dummy";
    private static Configuration configUtil = new Configuration("dummy");

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
        int status = configUtil.getInt("payment_status");
        return Math.random() < status / 100.0;
    }

    private Integer getAmount() {
        return configUtil.getInt("amount");
    }

    private Integer getExpiry() {
        return configUtil.getInt("expiry");
    }

    private Integer getFeeReserve() {
        return configUtil.getInt("fee_reserve");
    }
}
