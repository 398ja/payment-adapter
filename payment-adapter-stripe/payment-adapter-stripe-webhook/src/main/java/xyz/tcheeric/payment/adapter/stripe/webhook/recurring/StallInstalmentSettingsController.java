package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StallInstalmentSettings;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StallInstalmentSettings.Interval;
import xyz.tcheeric.payment.adapter.core.model.repository.StallInstalmentSettingsRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.gifting.Nip98Verifier;

/**
 * A stall's own switch for instalment plans, and which intervals it offers (week, month, year).
 *
 * <p><b>Why it is stored here and not read from the stall's Nostr profile.</b> The profile's {@code acceptsInstalments}
 * is what the wallet reads to show "Pay monthly", but plan start is a public endpoint: anyone can call it directly, so
 * the decision has to be made here. Reading the profile at plan start would put a relay query (seconds, sometimes a
 * timeout) on the path to a card form, and would trust whichever copy of a replaceable event the relays happened to
 * return, possibly a stale "on" after the stall switched off. A row written only by a request the stall SIGNED is a
 * primary-key lookup at plan start, cannot be stale, and cannot be written by anyone but the stall.
 *
 * <ul>
 *   <li>{@code GET /api/v1/checkout/plans/settings?stall=<pubkey>}: public, like the profile flag it mirrors.
 *       {@code {enabled, intervals}}; a stall that never saved one answers {@code enabled: false}.</li>
 *   <li>{@code POST /api/v1/checkout/plans/settings}: signed BY the stall (NIP-98, with the body's hash), body
 *       {@code {enabled, intervals: ["month", ...]}}. The stall is whoever signed. {@code intervals} left out keeps the
 *       stall's current ones (monthly for a new stall), so a wallet that only knows monthly cannot erase weekly.
 *       Turning it off stops new plans; plans already running are not touched.</li>
 * </ul>
 */
@RestController
@ConditionalOnProperty(prefix = "stripe", name = {"enabled", "recurring.enabled"}, havingValue = "true")
@RequestMapping(StallInstalmentSettingsController.PATH)
public class StallInstalmentSettingsController {

    public static final String PATH = "/api/v1/checkout/plans/settings";
    private static final String HEX_KEY = "^[0-9a-f]{64}$";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StallInstalmentSettingsRepository settings;
    private final SignedCaller signed;

    @Autowired
    public StallInstalmentSettingsController(StallInstalmentSettingsRepository settings,
            @Value("${purchase.state.public-origins:}") List<String> publicOrigins) {
        this(settings, new Nip98Verifier(), publicOrigins);
    }

    public StallInstalmentSettingsController(StallInstalmentSettingsRepository settings, Nip98Verifier verifier,
            List<String> publicOrigins) {
        this.settings = settings;
        this.signed = new SignedCaller(verifier, publicOrigins);
    }

    @GetMapping
    public ResponseEntity<?> read(@RequestParam(name = "stall", required = false) String stallParam) {
        String stall = stallParam == null ? "" : stallParam.trim().toLowerCase(Locale.ROOT);
        if (!stall.matches(HEX_KEY)) {
            return ResponseEntity.badRequest().body(Map.of("error", "stall must be 64 hex characters"));
        }
        return ResponseEntity.ok(view(settings.findById(stall)));
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody(required = false) byte[] body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!signed.configured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "instalment plans are not configured"));
        }
        if (body == null || body.length == 0) {
            return bad("a body is required: {enabled, intervals}");
        }
        // The payload hash binds the signature to these exact bytes.
        Optional<String> signer = signed.of(authorization, PATH, "POST", body);
        if (signer.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "NIP-98 authorization required"));
        }
        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (Exception e) {
            return bad("the body is not JSON");
        }
        if (json == null || !json.path("enabled").isBoolean()) {
            return bad("enabled must be true or false");
        }
        // A body naming a stall must name the signer: a client cannot be confused into saving for somebody else.
        JsonNode named = json.path("stallPubkey").isMissingNode() ? json.path("stall_pubkey") : json.path("stallPubkey");
        if (!named.isMissingNode() && !named.isNull() && !named.asText("").toLowerCase(Locale.ROOT).equals(signer.get())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "signed by another key"));
        }
        boolean enabled = json.path("enabled").asBoolean();
        // Absent: keep what the stall has (a wallet that only knows monthly must not erase weekly), monthly if new.
        Set<Interval> intervals = EnumSet.noneOf(Interval.class);
        JsonNode list = json.path("intervals");
        boolean keepIntervals = list.isMissingNode() || list.isNull();
        if (keepIntervals) {
            intervals.add(Interval.MONTH);
        } else if (!list.isArray()) {
            return bad("intervals must be a list of week, month or year");
        } else {
            for (JsonNode item : list) {
                Optional<Interval> interval = item.isTextual() ? Interval.parse(item.asText()) : Optional.empty();
                if (interval.isEmpty()) {
                    return bad("intervals must be a list of week, month or year");
                }
                intervals.add(interval.get());
            }
        }
        if (enabled && intervals.isEmpty()) {
            return bad("an enabled stall must offer at least one interval");
        }
        if (intervals.isEmpty()) {
            intervals.add(Interval.MONTH);
        }

        Instant now = Instant.now();
        try {
            return ResponseEntity.ok(view(Optional.of(upsert(signer.get(), enabled, intervals, keepIntervals, now))));
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            // Two first saves from the same stall raced on the insert; the row exists now, so update it.
            return ResponseEntity.ok(view(Optional.of(upsert(signer.get(), enabled, intervals, keepIntervals, now))));
        }
    }

    private StallInstalmentSettings upsert(String stall, boolean enabled, Set<Interval> intervals, boolean keepIntervals,
            Instant now) {
        Optional<StallInstalmentSettings> existing = settings.findById(stall);
        StallInstalmentSettings row = existing.orElseGet(() -> {
            StallInstalmentSettings fresh = new StallInstalmentSettings();
            fresh.setStallPubkey(stall);
            fresh.setCreatedAt(now);
            return fresh;
        });
        row.setEnabled(enabled);
        if (!keepIntervals || existing.isEmpty() || row.intervalSet().isEmpty()) {
            row.setIntervalSet(intervals);
        }
        row.setUpdatedAt(now);
        return settings.save(row);
    }

    private static SettingsView view(Optional<StallInstalmentSettings> row) {
        return row.map(r -> new SettingsView(r.isEnabled(), r.intervalNames()))
                .orElseGet(() -> new SettingsView(false, List.of()));
    }

    private static ResponseEntity<?> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    /** camelCase, like the rest of the checkout surface the wallet reads. */
    public record SettingsView(boolean enabled, List<String> intervals) {}
}
