package xyz.tcheeric.payment.adapter.test.integration;

import xyz.tcheeric.payment.adapter.core.model.entity.CashIntent;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.CashReceipt;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestDataFactory {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final String DEFAULT_PUBKEY = "02" + "a".repeat(64);
    private static final String DEFAULT_PRIVKEY = "b".repeat(64);
    private static final String DEFAULT_RELAYS = "wss://relay.test.local";

    private TestDataFactory() {
    }

    public static CashInvoice createInvoice() {
        return createInvoice(1000, "USD");
    }

    public static CashInvoice createInvoice(int amount, String fiat) {
        String ref = uniqueRef();
        return CashInvoice.create(
                ref, DEFAULT_PUBKEY, DEFAULT_PRIVKEY,
                amount, fiat, "Test invoice " + ref,
                Instant.now().plusSeconds(300), DEFAULT_RELAYS
        );
    }

    public static CashInvoice createInvoiceWithStatus(CashInvoiceStatus status) {
        CashInvoice invoice = createInvoice();
        invoice.setStatus(status);
        if (status == CashInvoiceStatus.PENDING) {
            invoice.setPublishedAt(Instant.now());
        } else if (status == CashInvoiceStatus.INTENT_RECEIVED) {
            invoice.setPublishedAt(Instant.now());
            invoice.setIntentReceivedAt(Instant.now());
        } else if (status == CashInvoiceStatus.PAID) {
            invoice.setPublishedAt(Instant.now());
            invoice.setPaidAt(Instant.now());
        }
        return invoice;
    }

    public static CashInvoice createExpiredInvoice() {
        String ref = uniqueRef();
        CashInvoice invoice = CashInvoice.create(
                ref, DEFAULT_PUBKEY, DEFAULT_PRIVKEY,
                500, "USD", "Expired invoice",
                Instant.now().minusSeconds(60), DEFAULT_RELAYS
        );
        invoice.setStatus(CashInvoiceStatus.PENDING);
        invoice.setPublishedAt(Instant.now().minusSeconds(120));
        return invoice;
    }

    public static CashIntent createIntent(String ref) {
        return CashIntent.create(
                ref, DEFAULT_PUBKEY, "1234",
                Instant.now(), uniqueEventId()
        );
    }

    public static CashReceipt createReceipt(String ref) {
        return createReceipt(ref, 1000);
    }

    public static CashReceipt createReceipt(String ref, int amountReceived) {
        return CashReceipt.create(ref, amountReceived, uniqueEventId());
    }

    public static String uniqueRef() {
        return String.format("%06x", COUNTER.incrementAndGet());
    }

    public static String uniqueEventId() {
        String part1 = UUID.randomUUID().toString().replace("-", "");
        String part2 = UUID.randomUUID().toString().replace("-", "");
        return (part1 + part2).substring(0, 64);
    }
}
