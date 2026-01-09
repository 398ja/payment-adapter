package xyz.tcheeric.gateway.dummy;

import lombok.NoArgsConstructor;
import lombok.NonNull;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.entities.annotation.Supports;
import xyz.tcheeric.gateway.common.Gateway;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@NoArgsConstructor
@Supports({PaymentMethod.BOLT11, PaymentMethod.BOLT12, PaymentMethod.ON_CHAIN})
public class DummyGateway implements Gateway {

    private static final String GATEWAY_NAME = "dummy";
    private static final Properties properties = new Properties();

    // Sane defaults if properties are missing
    private static final int DEFAULT_EXPIRY_SECONDS = 60;
    private static final int DEFAULT_FEE_RESERVE = 1;

    private static final Map<String, Quote> QUOTES = new ConcurrentHashMap<>();

    static {
        try (var inputStream = DummyGateway.class.getClassLoader().getResourceAsStream("dummy.properties")) {
            if (inputStream != null) {
                properties.load(inputStream);
            } // else keep defaults for dev convenience
        } catch (IOException ignored) {
        }
    }

    private static final class Quote {
        final String id;
        final String request;
        final int amount;
        final int expiry;
        volatile boolean paid;
        volatile String preimage;

        Quote(String id, String request, int amount, int expiry, boolean paid, String preimage) {
            this.id = id;
            this.request = request;
            this.amount = amount;
            this.expiry = expiry;
            this.paid = paid;
            this.preimage = preimage;
        }
    }

    // Explicit support in addition to @Supports
    public boolean supports(PaymentMethod method) {
        return method == PaymentMethod.BOLT11
                || method == PaymentMethod.BOLT12
                || method == PaymentMethod.ON_CHAIN;
    }

    @Override
    public String createMintQuote(@NonNull Integer amount, String description) {
        String id = UUID.randomUUID().toString();
        String req = "lnbc" + amount + "-dummy-" + id.substring(0, 8);
        int expiry = getIntProp("dummy.expiry", DEFAULT_EXPIRY_SECONDS);
        // Auto-pay mint quotes and assign preimage immediately
        String preimage = randomHex(32);
        QUOTES.put(id, new Quote(id, req, amount, expiry, true, preimage));
        return id;
    }

    @Override
    public String createMeltQuote(@NonNull Integer amount, @NonNull String lnIvoice, String description) {
        String id = UUID.randomUUID().toString();
        int expiry = getIntProp("dummy.expiry", DEFAULT_EXPIRY_SECONDS);
        QUOTES.put(id, new Quote(id, lnIvoice, amount, expiry, false, null));
        return id;
    }

    @Override
    public String createMeltQuote(@NonNull String s) {
        String id = UUID.randomUUID().toString();
        int amount = getIntProp("dummy.amount", 10);
        int expiry = getIntProp("dummy.expiry", DEFAULT_EXPIRY_SECONDS);
        QUOTES.put(id, new Quote(id, s, amount, expiry, false, null));
        return id;
    }

    @Override
    public String getRequest(@NonNull String paymentId) {
        return get(paymentId).request;
    }

    @Override
    public boolean checkPaymentStatus(@NonNull String quoteId) {
        return get(quoteId).paid;
    }

    @Override
    public String getPaymentPreimage(@NonNull String quoteId) {
        return get(quoteId).preimage;
    }

    @Override
    public String pay(@NonNull String s) {
        Quote q = get(s);
        q.paid = true;
        if (q.preimage == null || q.preimage.isBlank()) {
            q.preimage = randomHex(32);
        }
        return q.preimage;
    }

    @Override
    public Integer getAmount(@NonNull String quoteId) {
        return get(quoteId).amount;
    }

    @Override
    public Integer getPaymentExpiry(@NonNull String quoteId) {
        return get(quoteId).expiry;
    }

    @Override
    public Integer getFeeReserve(@NonNull String quoteId) {
        return getIntProp("dummy.fee_reserve", DEFAULT_FEE_RESERVE);
    }

    @Override
    public String getName() {
        return GATEWAY_NAME;
    }

    // Helpers

    private static Quote get(String quoteId) {
        Quote q = QUOTES.get(quoteId);
        if (q == null) throw new IllegalArgumentException("Unknown quoteId: " + quoteId);
        return q;
    }

    private static int getIntProp(String key, int def) {
        try {
            String v = properties.getProperty(key);
            if (v != null && !v.isBlank()) return Integer.parseInt(v.trim());
        } catch (Exception ignored) {
        }
        return def;
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
