package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StallInstalmentSettings;
import xyz.tcheeric.payment.adapter.core.model.repository.StallInstalmentSettingsRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.gifting.Nip98Verifier;
import xyz.tcheeric.payment.adapter.stripe.webhook.recurring.StallInstalmentSettingsController.SettingsView;

/**
 * Only the stall's own signed request can switch its instalment plans on or off, and the signature covers the body, so
 * a captured one cannot be replayed with different settings.
 */
class StallInstalmentSettingsControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long NOW = 1_800_000_000L;
    private static final String ORIGIN = "https://wallet.imani.casa";
    private static final String URL = ORIGIN + StallInstalmentSettingsController.PATH;
    private static final byte[] STALL_SK = HexFormat.of().parseHex("33".repeat(32));
    private static final byte[] OTHER_SK = HexFormat.of().parseHex("22".repeat(32));

    private StallInstalmentSettingsRepository settings;
    private StallInstalmentSettingsController controller;
    private String stall;

    @BeforeEach
    void setUp() throws Exception {
        stall = HexFormat.of().formatHex(Schnorr.genPubKey(STALL_SK));
        settings = mock(StallInstalmentSettingsRepository.class);
        when(settings.findById(any())).thenReturn(Optional.empty());
        when(settings.save(any(StallInstalmentSettings.class))).thenAnswer(i -> i.getArgument(0));
        controller = new StallInstalmentSettingsController(settings,
                new Nip98Verifier(Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC)), List.of(ORIGIN));
    }

    /** A NIP-98 header for POST {@link #URL}, with a payload tag over {@code signedBody}. */
    static String nip98(byte[] sk, String signedBody) throws Exception {
        String pubkey = HexFormat.of().formatHex(Schnorr.genPubKey(sk));
        ArrayNode tags = MAPPER.createArrayNode();
        tags.add(MAPPER.createArrayNode().add("u").add(URL));
        tags.add(MAPPER.createArrayNode().add("method").add("POST"));
        String payload = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(signedBody.getBytes(StandardCharsets.UTF_8)));
        tags.add(MAPPER.createArrayNode().add("payload").add(payload));
        ArrayNode canonical = MAPPER.createArrayNode().add(0).add(pubkey).add(NOW).add(27235);
        canonical.add(tags);
        canonical.add("");
        byte[] id = MessageDigest.getInstance("SHA-256")
                .digest(MAPPER.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
        ObjectNode event = MAPPER.createObjectNode();
        event.put("id", HexFormat.of().formatHex(id));
        event.put("pubkey", pubkey);
        event.put("created_at", NOW);
        event.put("kind", 27235);
        event.set("tags", tags);
        event.put("content", "");
        event.put("sig", HexFormat.of().formatHex(Schnorr.sign(id, sk, new byte[32])));
        return "Nostr " + Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(event));
    }

    private ResponseEntity<?> post(byte[] sk, String body) throws Exception {
        return controller.save(body.getBytes(StandardCharsets.UTF_8), nip98(sk, body));
    }

    private static HttpStatus status(ResponseEntity<?> response) {
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    // The stall switches plans on for itself: the row is keyed by the SIGNER, not by anything in the body.
    @Test
    void theSignerSwitchesItsOwnPlansOn() throws Exception {
        ResponseEntity<?> response = post(STALL_SK, "{\"enabled\":true,\"intervals\":[\"month\",\"week\"]}");

        assertThat(status(response)).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new SettingsView(true, List.of("week", "month")));
        ArgumentCaptor<StallInstalmentSettings> saved = ArgumentCaptor.forClass(StallInstalmentSettings.class);
        verify(settings).save(saved.capture());
        assertThat(saved.getValue().getStallPubkey()).isEqualTo(stall);
        assertThat(saved.getValue().isEnabled()).isTrue();
        assertThat(saved.getValue().getIntervals()).isEqualTo("week,month");
    }

    // No intervals named means monthly for a new stall...
    @Test
    void noIntervalsMeansMonthly() throws Exception {
        assertThat(post(STALL_SK, "{\"enabled\":true}").getBody()).isEqualTo(new SettingsView(true, List.of("month")));
    }

    // ...and keeps a stall's own intervals otherwise: a wallet that only knows monthly must not erase weekly.
    @Test
    void noIntervalsKeepsTheStallsOwn() throws Exception {
        StallInstalmentSettings existing = new StallInstalmentSettings();
        existing.setStallPubkey(stall);
        existing.setEnabled(false);
        existing.setIntervals("week,year");
        when(settings.findById(stall)).thenReturn(Optional.of(existing));

        assertThat(post(STALL_SK, "{\"enabled\":true}").getBody()).isEqualTo(new SettingsView(true, List.of("week", "year")));
    }

    // Switching off is saved too, and keeps the intervals the stall had.
    @Test
    void switchingOffIsSaved() throws Exception {
        StallInstalmentSettings existing = new StallInstalmentSettings();
        existing.setStallPubkey(stall);
        existing.setEnabled(true);
        existing.setIntervals("week");
        when(settings.findById(stall)).thenReturn(Optional.of(existing));

        ResponseEntity<?> response = post(STALL_SK, "{\"enabled\":false,\"intervals\":[\"week\"]}");

        assertThat(response.getBody()).isEqualTo(new SettingsView(false, List.of("week")));
        assertThat(existing.isEnabled()).isFalse();
    }

    // Unsigned, or signed over a different body, changes nothing: a captured signature cannot switch plans on.
    @Test
    void aSignatureMustCoverThisExactBody() throws Exception {
        String off = "{\"enabled\":false}";
        String on = "{\"enabled\":true}";

        assertThat(status(controller.save(on.getBytes(StandardCharsets.UTF_8), null))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(controller.save(on.getBytes(StandardCharsets.UTF_8), nip98(STALL_SK, off))))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(settings, never()).save(any());
    }

    // A body that names a stall must name the signer.
    @Test
    void aBodyNamingAnotherStallIsRefused() throws Exception {
        String other = HexFormat.of().formatHex(Schnorr.genPubKey(OTHER_SK));

        ResponseEntity<?> response = post(STALL_SK, "{\"stallPubkey\":\"" + other + "\",\"enabled\":true}");

        assertThat(status(response)).isEqualTo(HttpStatus.FORBIDDEN);
        verify(settings, never()).save(any());
        assertThat(status(post(STALL_SK, "{\"stallPubkey\":\"" + stall + "\",\"enabled\":true}"))).isEqualTo(HttpStatus.OK);
    }

    // Only week, month and year; an enabled stall must offer at least one; enabled must be a boolean.
    @Test
    void badSettingsAreRefused() throws Exception {
        assertThat(status(post(STALL_SK, "{\"enabled\":true,\"intervals\":[\"day\"]}"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(post(STALL_SK, "{\"enabled\":true,\"intervals\":[]}"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(post(STALL_SK, "{\"enabled\":true,\"intervals\":\"month\"}"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(post(STALL_SK, "{\"enabled\":\"yes\"}"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(post(STALL_SK, "not json"))).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(settings, never()).save(any());
    }

    // Anyone may read a stall's offer, as anyone may read its profile; a stall that never saved one offers nothing.
    @Test
    void theOfferIsPublicAndOffByDefault() {
        assertThat(controller.read(stall).getBody()).isEqualTo(new SettingsView(false, List.of()));
        assertThat(status(controller.read("nope"))).isEqualTo(HttpStatus.BAD_REQUEST);

        StallInstalmentSettings row = new StallInstalmentSettings();
        row.setEnabled(true);
        row.setIntervals("month,year");
        when(settings.findById(stall)).thenReturn(Optional.of(row));
        assertThat(controller.read(stall.toUpperCase()).getBody()).isEqualTo(new SettingsView(true, List.of("month", "year")));
    }

    // Two first saves from the same stall race on the insert; the loser updates the row the winner made.
    @Test
    void twoFirstSavesThatRaceEndAsAnUpdate() throws Exception {
        StallInstalmentSettings winner = new StallInstalmentSettings();
        winner.setStallPubkey(stall);
        winner.setIntervals("month");
        when(settings.findById(stall)).thenReturn(Optional.empty(), Optional.of(winner));
        when(settings.save(any(StallInstalmentSettings.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"))
                .thenAnswer(i -> i.getArgument(0));

        ResponseEntity<?> response = post(STALL_SK, "{\"enabled\":true,\"intervals\":[\"year\"]}");

        assertThat(status(response)).isEqualTo(HttpStatus.OK);
        assertThat(winner.isEnabled()).isTrue();
        assertThat(winner.getIntervals()).isEqualTo("year");
    }

    // With no public origin configured nothing can be verified, so saving is unavailable rather than open.
    @Test
    void withoutAPublicOriginSavingIsUnavailable() throws Exception {
        controller = new StallInstalmentSettingsController(settings, new Nip98Verifier(), List.of());

        assertThat(status(post(STALL_SK, "{\"enabled\":true}"))).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat((Map<String, Object>) controller.save("{}".getBytes(), null).getBody()).containsKey("error");
    }
}
