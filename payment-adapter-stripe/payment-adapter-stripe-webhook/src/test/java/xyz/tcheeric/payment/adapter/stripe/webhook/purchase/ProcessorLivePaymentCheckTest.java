package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;

/** The operator's live read before a retry goes to the row's own processor (ADR 0002). */
class ProcessorLivePaymentCheckTest {

    static final LivePayment FROM_STRIPE = new LivePayment(0, 100, false, false);
    static final LivePayment FROM_PAYPAL = new LivePayment(0, 200, false, false);

    final LivePaymentCheck stripe = purchase -> FROM_STRIPE;
    final LivePaymentCheck paypal = purchase -> FROM_PAYPAL;

    @Test
    void eachRowIsReadFromItsOwnProcessor() {
        LivePaymentCheck routed = ProcessorLivePaymentCheck.routed(stripe,
                List.of(new ProcessorLivePaymentCheck(StripePurchase.PROCESSOR_PAYPAL, paypal)));

        assertThat(routed.refresh(row(StripePurchase.PROCESSOR_STRIPE))).isSameAs(FROM_STRIPE);
        assertThat(routed.refresh(row(StripePurchase.PROCESSOR_PAYPAL))).isSameAs(FROM_PAYPAL);
        assertThat(routed.refresh(row(null))).isSameAs(FROM_STRIPE);
    }

    @Test
    void aPaypalRowWithPaypalOffFailsClosedAndIsNeverReadFromStripe() {
        LivePaymentCheck routed = ProcessorLivePaymentCheck.routed(stripe, List.of());

        assertThatThrownBy(() -> routed.refresh(row(StripePurchase.PROCESSOR_PAYPAL)))
                .isInstanceOf(LivePaymentUnavailableException.class).hasMessageContaining("paypal");
        assertThat(routed.refresh(row(StripePurchase.PROCESSOR_STRIPE))).isSameAs(FROM_STRIPE);
    }

    @Test
    void withStripeOffAStripeRowStillFailsClosed() {
        LivePaymentCheck routed = ProcessorLivePaymentCheck.routed(LivePaymentCheck.unconfigured(),
                List.of(new ProcessorLivePaymentCheck(StripePurchase.PROCESSOR_PAYPAL, paypal)));

        assertThatThrownBy(() -> routed.refresh(row(StripePurchase.PROCESSOR_STRIPE)))
                .isInstanceOf(LivePaymentUnavailableException.class);
        assertThat(routed.refresh(row(StripePurchase.PROCESSOR_PAYPAL))).isSameAs(FROM_PAYPAL);
    }

    @Test
    void referencesOfEitherShapeAreWellFormedAndNothingElse() {
        assertThat(ProcessorReference.isWellFormed("pi_3NkX0a2eZvKYlo2C1abc")).isTrue();
        assertThat(ProcessorReference.isWellFormed("3C679366HH908993F")).isTrue();
        assertThat(ProcessorReference.isWellFormed("3c679366hh908993f")).isFalse();
        assertThat(ProcessorReference.isWellFormed("../../etc")).isFalse();
        assertThat(ProcessorReference.isWellFormed(null)).isFalse();
    }

    private static StripePurchase row(String processor) {
        StripePurchase row = new StripePurchase();
        row.setProcessor(processor);
        row.setEventId("evt-" + processor);
        return row;
    }
}
