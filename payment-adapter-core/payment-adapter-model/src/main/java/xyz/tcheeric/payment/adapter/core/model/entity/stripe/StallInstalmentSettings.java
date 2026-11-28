package xyz.tcheeric.payment.adapter.core.model.entity.stripe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A stall's opt-in to instalment plans and the intervals it accepts. See {@code V27__stall_instalment_settings.sql}.
 *
 * <p>Written only from a request signed by the stall's own key; read by plan start, which refuses without it.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "stall_instalment_settings")
public class StallInstalmentSettings {

    /** How often an instalment is taken. The lower-case name is Stripe's {@code recurring.interval}. */
    public enum Interval {
        WEEK, MONTH, YEAR;

        public String stripe() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** For messages: weekly, monthly, yearly. */
        public String adjective() {
            return switch (this) {
                case WEEK -> "weekly";
                case MONTH -> "monthly";
                case YEAR -> "yearly";
            };
        }

        /** Empty for anything that is not exactly one of week, month or year (any case). */
        public static Optional<Interval> parse(String value) {
            if (value == null) {
                return Optional.empty();
            }
            String v = value.trim().toUpperCase(Locale.ROOT);
            return Arrays.stream(values()).filter(i -> i.name().equals(v)).findFirst();
        }
    }

    @Id
    @Column(name = "stall_pubkey", length = 64)
    private String stallPubkey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "intervals", nullable = false, length = 64)
    private String intervals = "month";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The stored intervals. Unknown values (a hand edit) are dropped, never trusted. */
    public Set<Interval> intervalSet() {
        Set<Interval> set = EnumSet.noneOf(Interval.class);
        if (intervals != null) {
            for (String part : intervals.split(",")) {
                Interval.parse(part).ifPresent(set::add);
            }
        }
        return set;
    }

    /** Stores a non-empty set in a stable order (week, month, year). */
    public void setIntervalSet(Set<Interval> set) {
        this.intervals = EnumSet.copyOf(set).stream().map(Interval::stripe).collect(Collectors.joining(","));
    }

    public List<String> intervalNames() {
        return intervalSet().stream().map(Interval::stripe).toList();
    }

    /** True when a buyer may start a plan at this interval. */
    public boolean offers(Interval interval) {
        return enabled && intervalSet().contains(interval);
    }
}
