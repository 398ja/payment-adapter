package xyz.tcheeric.payment.adapter.core.model.entity.stripe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(name = "stripe_payment_reference", indexes = {
        @Index(name = "idx_stripe_reference_quote_id", columnList = "quote_id", unique = true),
        @Index(name = "idx_stripe_reference_checkout_session_id", columnList = "checkout_session_id", unique = true),
        @Index(name = "idx_stripe_reference_payment_intent_id", columnList = "payment_intent_id", unique = true),
        @Index(name = "idx_stripe_reference_charge_id", columnList = "charge_id", unique = true)
})
public class StripePaymentReference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "quote_id", nullable = false, unique = true)
    private String quoteId;

    @Column(name = "checkout_session_id", nullable = false, unique = true)
    private String checkoutSessionId;

    @Column(name = "payment_intent_id", unique = true)
    private String paymentIntentId;

    @Column(name = "charge_id", unique = true)
    private String chargeId;

    @Column(name = "connected_account_id")
    private String connectedAccountId;

    @Column(name = "stripe_status")
    private String stripeStatus;

    @Column(name = "livemode", nullable = false)
    private boolean livemode;

    @Column(name = "last_event_id")
    private String lastEventId;

    @Column(name = "refunded_amount_minor")
    private Integer refundedAmountMinor;

    @Column(name = "disputed", nullable = false)
    private boolean disputed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
