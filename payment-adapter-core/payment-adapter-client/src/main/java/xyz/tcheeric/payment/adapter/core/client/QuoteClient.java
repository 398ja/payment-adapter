package xyz.tcheeric.payment.adapter.core.client;


import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;

import java.time.Instant;
import java.util.Map;


@Slf4j
public class QuoteClient extends AbstractBaseClient<GatewayQuote> {

    public QuoteClient() {
        super("quote", GatewayQuote.class);
    }

    public GatewayQuote getByInvoiceId(String invoiceId) {
        String url = getUrl() + "/search/findByInvoiceId?invoiceId=" + invoiceId;
        log.info("Sending request: {}", url);
        ResponseEntity<GatewayQuote> response = restTemplate.getForEntity(url, GatewayQuote.class);
        log.info("Received response: {}", response.getBody());
        return response.getBody();
    }

    /**
     * Sends the WHOLE quote as a PUT, overwriting every field with the caller's copy.
     *
     * <p><strong>Only call this when you intend to write every field you are holding.</strong>
     * A caller that read the quote earlier, changed one field and called this has also written
     * back whatever the other fields held AT READ TIME — and if another writer changed one of
     * them in between, this silently reverts it.
     *
     * <p>That is not hypothetical. {@code PhoenixWebhookHandler} used this to stamp
     * {@code mintNotifiedAt} and reverted {@code state} from PAID to PENDING on three real
     * staging sales (#245), because phoenixd marked the invoice paid between the read and this
     * write. The mint then reported "Invoice not paid" for a payment that had settled. Use
     * {@link #stampMintNotified} for that, and prefer a targeted stamp generally.
     */
    public GatewayQuote updateQuote(GatewayQuote quote) {
        String url = getUrl() + "/" + quote.getId();
        log.info("Sending update request: {}", url);
        HttpEntity<GatewayQuote> request = new HttpEntity<>(quote);
        ResponseEntity<GatewayQuote> response = restTemplate.exchange(url, HttpMethod.PUT, request, GatewayQuote.class);
        log.info("Received update response: {}", response.getBody());
        return response.getBody();
    }

    /**
     * Stamps {@code mintNotifiedAt} and NOTHING else.
     *
     * <p>A partial update carrying one field, rather than a PUT carrying the caller's whole copy
     * of the row. That distinction is the fix for #245: the bookkeeping stamp must not be able to
     * express an opinion about {@code state}, because it has none — the payment's state is owned
     * by whoever observed the payment, and this caller only knows that the mint was told.
     *
     * <p>This CLOSES the race rather than narrowing it. Re-reading immediately before a PUT would
     * only shrink the window; a field the request never mentions cannot be clobbered however the
     * two writers interleave.
     *
     * <p><strong>A real PATCH.</strong> The default {@code RestTemplate} could not send one —
     * {@code HttpURLConnection} throws {@code Invalid HTTP method: PATCH} before reaching the
     * network — so {@link AbstractBaseClient} now builds on {@code JdkClientHttpRequestFactory}.
     * Worth knowing because {@code recordMintNotified} swallows its exceptions: on the old
     * factory this stamp would have thrown on every call and failed silently, which is the exact
     * failure mode #245 is about. An {@code X-HTTP-Method-Override} header was tried first and
     * the server answered 404; it honours real PATCH, which {@code OPTIONS} confirms
     * ({@code Allow: HEAD,DELETE,GET,OPTIONS,PUT,PATCH}).
     */
    public GatewayQuote stampMintNotified(Long id, Instant notifiedAt) {
        // A null id would build `/quote/null`, which answers 404, which becomes a
        // RuntimeException that the caller catches and logs as a warning — a write silently not
        // happening, which is the entire class of bug this change exists to remove. Fail on the
        // programming error instead of laundering it into the same quiet failure.
        if (id == null) {
            throw new IllegalArgumentException("stampMintNotified requires a quote id");
        }
        String url = getUrl() + "/" + id;
        log.info("Sending mint-notified stamp: {}", url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request =
                new HttpEntity<>(Map.of("mintNotifiedAt", notifiedAt.toString()), headers);
        ResponseEntity<GatewayQuote> response =
                restTemplate.exchange(url, HttpMethod.PATCH, request, GatewayQuote.class);
        log.info("Received mint-notified stamp response: {}", response.getBody());
        return response.getBody();
    }
}
