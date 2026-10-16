package xyz.tcheeric.payment.adapter.core.model.entity;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.core.common.QuoteRef;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A quote id is a bearer claim on the payment (cashu-mint#531). Any log line that passes a quote
 * or payment as a {@code {}} argument prints its {@code toString}, so the entities' own
 * {@code toString} must never carry the id, the invoice id (the same value for phoenixd) or a
 * preimage. It carries the quote_ref instead, so log lines still correlate.
 */
class EntityToStringRedactionTest {

    private static final String QUOTE_ID = "6f1c2d3e-4b5a-6789-abcd-ef0123456789";

    // A quote's toString shows its quote_ref and state, never the raw quote id or invoice id.
    @Test
    void quoteToStringCarriesTheRefNotTheId() {
        GatewayQuote quote = GatewayQuote.create(QUOTE_ID, QUOTE_ID, 60, "desc", "lnbc1", 100, "sat");
        quote.setId(7L);

        String text = quote.toString();

        assertThat(text).doesNotContain(QUOTE_ID);
        assertThat(text).contains(QuoteRef.of(QUOTE_ID)).contains("PENDING").contains("id=7");
    }

    // A payment's toString shows its quote_ref, never the raw quote id or the preimage.
    @Test
    void paymentToStringCarriesTheRefNotTheIdOrPreimage() {
        GatewayPayment payment = new GatewayPayment();
        payment.setQuoteId(QUOTE_ID);
        payment.setPaymentPreimage("preimage-secret-value");
        payment.setIdempotencyKey("idem-" + QUOTE_ID);
        payment.setState(State.PAID);

        String text = payment.toString();

        assertThat(text).doesNotContain(QUOTE_ID).doesNotContain("preimage-secret-value");
        assertThat(text).contains(QuoteRef.of(QUOTE_ID)).contains("PAID");
    }

    // A cash invoice is @Data and stores the merchant's ephemeral private key and the counter
    // proof code. Its toString must carry neither, so logging the entity cannot leak them.
    @Test
    void cashInvoiceToStringCarriesNeitherThePrivateKeyNorTheProofCode() {
        CashInvoice invoice = CashInvoice.create("ref-1", "02" + "ab".repeat(32),
                "privkeycanary" + "cd".repeat(25), 100, "GBP", "memo",
                java.time.Instant.parse("2026-10-16T00:00:00Z"), "wss://relay.test");
        invoice.setProofCode("918273");

        String text = invoice.toString();

        assertThat(text.contains("privkeycanary")).as("ephemeral private key in toString").isFalse();
        assertThat(text.contains("918273")).as("proof code in toString").isFalse();
        assertThat(text).contains("ref-1");
    }

    // A cash intent carries the customer's proof code, the secret the counter checks.
    @Test
    void cashIntentToStringCarriesNotTheProof() {
        CashIntent intent = new CashIntent();
        intent.setRef("ref-2");
        intent.setProof("564738");

        String text = intent.toString();

        assertThat(text.contains("564738")).as("proof in toString").isFalse();
        assertThat(text).contains("ref-2");
    }

    // Redacting toString must not change equality, which JPA and the tests rely on.
    @Test
    void equalityStillUsesTheQuoteId() {
        GatewayQuote a = GatewayQuote.create(QUOTE_ID, QUOTE_ID, 60, "d", "r", 1, "sat");
        GatewayQuote b = GatewayQuote.create(QUOTE_ID, QUOTE_ID, 60, "d", "r", 1, "sat");
        GatewayQuote c = GatewayQuote.create("other", QUOTE_ID, 60, "d", "r", 1, "sat");

        assertThat(a).isEqualTo(b).isNotEqualTo(c);
    }
}
