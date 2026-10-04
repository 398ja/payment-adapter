package xyz.tcheeric.payment.adapter.ln.phoenixd.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.phoenixd.common.rest.Response;

/**
 * phoenixd's answer to {@code GET /payments/incoming/{paymentHash}}: whether the invoice with
 * that hash has been paid.
 *
 * <p>Only the fields the adapter reads are mapped. phoenixd returns more (fees, timestamps,
 * description), and phoenixd-java parses with a default {@code ObjectMapper} that fails on unknown
 * fields, so they are ignored explicitly rather than mapped and left unused.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IncomingPaymentResponse implements Response {

    private String paymentHash;
    private String preimage;
    private String externalId;

    @JsonProperty("isPaid")
    private Boolean paid;

    private Long receivedSat;
}
