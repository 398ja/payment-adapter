package xyz.tcheeric.payment.adapter.core.model.entity.stripe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One Stripe OAuth round trip: the opaque {@code state} the farm's browser carries out and back, and the
 * merchant who started it. See {@code V24__stripe_connect_oauth.sql} for why it is single use.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "stripe_oauth_state", indexes = {
        @Index(name = "idx_stripe_oauth_state_expires_at", columnList = "expires_at")
})
public class StripeOAuthState {

    @Id
    @Column(name = "state", nullable = false, length = 64)
    private String state;

    @Column(name = "merchant_pubkey", nullable = false)
    private String merchantPubkey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;
}
