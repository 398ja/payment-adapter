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

    // Redacting toString must not change equality, which JPA and the tests rely on.
    @Test
    void equalityStillUsesTheQuoteId() {
        GatewayQuote a = GatewayQuote.create(QUOTE_ID, QUOTE_ID, 60, "d", "r", 1, "sat");
        GatewayQuote b = GatewayQuote.create(QUOTE_ID, QUOTE_ID, 60, "d", "r", 1, "sat");
        GatewayQuote c = GatewayQuote.create("other", QUOTE_ID, 60, "d", "r", 1, "sat");

        assertThat(a).isEqualTo(b).isNotEqualTo(c);
    }
}
