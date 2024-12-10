package xyz.tcheeric.gateway.dummy;

import cashu.common.annotation.Supports;
import cashu.common.model.PaymentMethod;
import cashu.gateway.Gateway;
import lombok.NonNull;
import xyz.tcheeric.util.ConfigUtil;

import java.util.UUID;

@Supports({PaymentMethod.MOCK})
public class DummyGateway implements Gateway {

    private static final String GATEWAY_NAME = "dummy";

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
        String paymentStatus = getParameter("payment_status");
        int status = Integer.parseInt(paymentStatus);
        return Math.random() < status / 100.0;
    }

    private Integer getAmount() {
        return Integer.valueOf(getParameter("amount"));
    }

    private Integer getExpiry() {
        return Integer.valueOf(getParameter("expiry"));
    }

    private Integer getFeeReserve() {
        return Integer.valueOf(getParameter("fee_reserve"));
    }

    private String getParameter(@NonNull String key) {
        ConfigUtil configUtil = new ConfigUtil("dummy");
        return configUtil.get(key);
    }

}
