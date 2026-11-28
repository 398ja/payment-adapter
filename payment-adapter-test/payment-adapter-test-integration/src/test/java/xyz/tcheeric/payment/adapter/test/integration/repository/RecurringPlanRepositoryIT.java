package xyz.tcheeric.payment.adapter.test.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.test.integration.BasePostgresIT;

/** The plan table and the queries the disconnect guard and the webhook rely on, against a real database. */
class RecurringPlanRepositoryIT extends BasePostgresIT {

    @Autowired
    private RecurringPlanRepository plans;

    @BeforeEach
    void cleanUp() {
        plans.deleteAll();
    }

    private RecurringPlan plan(String account, RecurringPlan.Status status, String subscription) {
        RecurringPlan p = new RecurringPlan();
        p.setId(UUID.randomUUID());
        p.setConnectedAccountId(account);
        p.setStallPubkey("a".repeat(64));
        p.setBuyerPubkey("b".repeat(64));
        p.setAmountMinor(5000);
        p.setCurrency("gbp");
        p.setStatus(status);
        p.setSubscriptionId(subscription);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return plans.save(p);
    }

    // Verifies a saved plan round-trips with its buyer, term and status.
    @Test
    void aPlanRoundTrips() {
        RecurringPlan saved = plan("acct_1", RecurringPlan.Status.ACTIVE, "sub_1");
        saved.setTotalInstalments(6);
        plans.save(saved);

        RecurringPlan read = plans.findById(saved.getId()).orElseThrow();

        assertThat(read.getBuyerPubkey()).isEqualTo("b".repeat(64));
        assertThat(read.getTotalInstalments()).isEqualTo(6);
        assertThat(read.getStatus()).isEqualTo(RecurringPlan.Status.ACTIVE);
        assertThat(plans.findBySubscriptionId("sub_1")).isPresent();
    }

    // Verifies one subscription belongs to one plan: a second plan claiming it is refused by the database.
    @Test
    void aSubscriptionIdIsUnique() {
        plan("acct_1", RecurringPlan.Status.ACTIVE, "sub_dup");

        assertThatThrownBy(() -> plan("acct_1", RecurringPlan.Status.ACTIVE, "sub_dup"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // Verifies many plans may have no subscription yet (a plan whose buyer has not finished Stripe's page).
    @Test
    void manyPlansMayAwaitASubscription() {
        plan("acct_1", RecurringPlan.Status.PENDING, null);
        plan("acct_1", RecurringPlan.Status.PENDING, null);

        assertThat(plans.count()).isEqualTo(2);
    }

    // Verifies the disconnect guard's query: only live plans on that account block, ended ones and other accounts do not.
    @Test
    void onlyLivePlansOnTheSameAccountBlockDisconnecting() {
        plan("acct_1", RecurringPlan.Status.COMPLETED, "sub_a");
        plan("acct_1", RecurringPlan.Status.CANCELLED, "sub_b");
        plan("acct_2", RecurringPlan.Status.ACTIVE, "sub_c");
        assertThat(plans.existsByConnectedAccountIdAndStatusIn("acct_1", RecurringPlan.LIVE)).isFalse();

        plan("acct_1", RecurringPlan.Status.PAST_DUE, "sub_d");
        assertThat(plans.existsByConnectedAccountIdAndStatusIn("acct_1", RecurringPlan.LIVE)).isTrue();
        assertThat(plans.findByConnectedAccountIdAndStatusIn("acct_1", RecurringPlan.LIVE)).hasSize(1);
    }

    // Verifies every status the code can write fits the column: a value that does not would only fail in production.
    @Test
    void everyStatusFitsTheColumn() {
        for (RecurringPlan.Status status : EnumSet.allOf(RecurringPlan.Status.class)) {
            assertThat(plan("acct_x", status, null).getStatus()).isEqualTo(status);
        }
    }

    // Regression: a plan saved without a processor is Stripe's (V28 default), and a PayPal plan keeps its processor.
    @Test
    void aPlanRemembersItsProcessor() {
        RecurringPlan stripe = plan("acct_x", RecurringPlan.Status.ACTIVE, "sub_1");
        RecurringPlan paypal = paypalPlan("I-1");

        assertThat(plans.findById(stripe.getId()).orElseThrow().getProcessor()).isEqualTo("stripe");
        assertThat(plans.findByProcessorAndSubscriptionId("paypal", "I-1")).map(RecurringPlan::getId)
                .contains(paypal.getId());
        assertThat(plans.findByProcessorAndSubscriptionId("stripe", "I-1")).isEmpty();
    }

    // Regression: Stripe's instalment reconciler never receives a PayPal plan, and PayPal's never a Stripe one.
    @Test
    void eachReconcilerReadsOnlyItsOwnProcessor() {
        RecurringPlan stripe = plan("acct_x", RecurringPlan.Status.ACTIVE, "sub_1");
        RecurringPlan paypal = paypalPlan("I-1");
        Instant later = Instant.now().plusSeconds(60);
        var live = RecurringPlan.LIVE;
        var ended = EnumSet.of(RecurringPlan.Status.COMPLETED);
        var page = PageRequest.of(0, 10);

        assertThat(plans.findReconcilable(live, ended, Instant.EPOCH, later, later, page))
                .extracting(RecurringPlan::getId).containsExactly(stripe.getId());
        assertThat(plans.findReconcilableFor("paypal", live, ended, Instant.EPOCH, later, later, page))
                .extracting(RecurringPlan::getId).containsExactly(paypal.getId());
    }

    private RecurringPlan paypalPlan(String subscription) {
        RecurringPlan p = new RecurringPlan();
        p.setId(UUID.randomUUID());
        p.setProcessor(RecurringPlan.PROCESSOR_PAYPAL);
        p.setConnectedAccountId("SELLER123");
        p.setStallPubkey("a".repeat(64));
        p.setBuyerPubkey("b".repeat(64));
        p.setAmountMinor(5000);
        p.setCurrency("gbp");
        p.setStatus(RecurringPlan.Status.ACTIVE);
        p.setSubscriptionId(subscription);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return plans.save(p);
    }
}
