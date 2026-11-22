package xyz.tcheeric.payment.adapter.core.model.entity.paypal;

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

/**
 * A stall's PayPal seller account: the {@code ConnectedStripeAccount} of the PayPal side (V23).
 *
 * <p>Same fields, same meanings, so the onboarding API answers in the same shape. Two differ:
 * {@link #trackingId} is the stall's own key for the Partner Referrals flow, and
 * {@link #paypalMerchantId} is unknown until the seller finishes PayPal's flow, so it is nullable.
 *
 * <p>{@link #chargesEnabled} means what it means for Stripe: a card can be taken TODAY. It is set
 * only by {@code PaypalCardCapability}, never from a single PayPal flag.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "connected_paypal_account", indexes = {
        @Index(name = "idx_connected_paypal_account_merchant_pubkey", columnList = "merchant_pubkey", unique = true),
        @Index(name = "idx_connected_paypal_account_merchant_id", columnList = "paypal_merchant_id", unique = true)
})
public class ConnectedPaypalAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_pubkey", nullable = false, unique = true)
    private String merchantPubkey;

    /** What this service sent as {@code tracking_id}; how PayPal finds the seller before a merchant id exists. */
    @Column(name = "tracking_id", nullable = false, unique = true, length = 127)
    private String trackingId;

    /** PayPal's {@code merchant_id} (payer id) for the seller, or null while onboarding is unfinished. */
    @Column(name = "paypal_merchant_id", unique = true, length = 64)
    private String paypalMerchantId;

    @Column(name = "onboarding_complete", nullable = false)
    private boolean onboardingComplete;

    @Column(name = "charges_enabled", nullable = false)
    private boolean chargesEnabled;

    @Column(name = "payouts_enabled", nullable = false)
    private boolean payoutsEnabled;

    @Column(name = "details_submitted", nullable = false)
    private boolean detailsSubmitted;

    @Column(name = "primary_email_confirmed", nullable = false)
    private boolean primaryEmailConfirmed;

    @Column(name = "default_currency", length = 8)
    private String defaultCurrency;

    @Column(name = "requirements_due", length = 4096)
    private String requirementsDue;

    @Column(name = "disabled_reason")
    private String disabledReason;

    @Column(name = "country", length = 8)
    private String country;

    @Column(name = "email")
    private String email;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
