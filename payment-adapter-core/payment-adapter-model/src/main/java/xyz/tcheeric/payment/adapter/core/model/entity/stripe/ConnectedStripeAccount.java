package xyz.tcheeric.payment.adapter.core.model.entity.stripe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "connected_stripe_account", indexes = {
        @Index(name = "idx_connected_stripe_account_merchant_pubkey", columnList = "merchant_pubkey", unique = true),
        @Index(name = "idx_connected_stripe_account_account_id", columnList = "stripe_account_id", unique = true)
})
public class ConnectedStripeAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_pubkey", nullable = false, unique = true)
    private String merchantPubkey;

    @Column(name = "stripe_account_id", nullable = false, unique = true)
    private String stripeAccountId;

    @Column(name = "onboarding_complete", nullable = false)
    private boolean onboardingComplete;

    @Column(name = "charges_enabled", nullable = false)
    private boolean chargesEnabled;

    @Column(name = "payouts_enabled", nullable = false)
    private boolean payoutsEnabled;

    @Column(name = "details_submitted", nullable = false)
    private boolean detailsSubmitted;

    @Column(name = "default_currency")
    private String defaultCurrency;

    @Column(name = "requirements_due", length = 4096)
    private String requirementsDue;

    @Column(name = "disabled_reason")
    private String disabledReason;

    @Column(name = "country", length = 8)
    private String country;

    @Column(name = "email")
    private String email;

    /** How this account was connected: created by Imani, or the stall's own through OAuth. */
    @Enumerated(EnumType.STRING)
    @Column(name = "connection_type", nullable = false, length = 16)
    private StripeConnectionType connectionType = StripeConnectionType.CREATED;

    /** Stripe's {@code type} (standard, express, custom, none). Null when Stripe did not report it. */
    @Column(name = "account_type", length = 16)
    private String accountType;

    /** {@code controller.fees.payer}: who pays Stripe's fees. Null when Stripe did not report it. */
    @Column(name = "fee_payer", length = 32)
    private String feePayer;

    /** {@code controller.losses.payments}: who bears a negative balance. Null when not reported. */
    @Column(name = "losses_payer", length = 16)
    private String lossesPayer;

    /** {@code controller.stripe_dashboard.type}. Null when Stripe did not report it. */
    @Column(name = "dashboard_type", length = 16)
    private String dashboardType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
