package xyz.tcheeric.payment.adapter.core.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;

import java.time.Instant;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

public class QuoteClientTest {

    private QuoteClient quoteClient;
    private MockRestServiceServer mockServer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        quoteClient = new QuoteClient();
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(quoteClient, "restTemplate");
        mockServer = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    void getByInvoiceIdCallsCorrectUrlAndReturnsQuote() throws Exception {
        GatewayQuote expected = new GatewayQuote();
        expected.setId(1L);
        expected.setInvoiceId("inv123");

        String body = objectMapper.writeValueAsString(expected);
        mockServer.expect(requestTo("http://localhost:8080/quote/search/findByInvoiceId?invoiceId=inv123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        GatewayQuote result = quoteClient.getByInvoiceId("inv123");

        mockServer.verify();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getInvoiceId()).isEqualTo("inv123");
    }

    @Test
    void createCallsCorrectUrlAndReturnsCreatedQuote() throws Exception {
        GatewayQuote request = new GatewayQuote();
        request.setDescription("desc");

        GatewayQuote expected = new GatewayQuote();
        expected.setId(2L);
        expected.setDescription("desc");

        String body = objectMapper.writeValueAsString(expected);
        mockServer.expect(requestTo("http://localhost:8080/quote"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        GatewayQuote result = quoteClient.create(request);

        mockServer.verify();
        assertThat(result.getId()).isEqualTo(2L);
        assertThat(result.getDescription()).isEqualTo("desc");
    }

    // Verifies updating a quote sends a PUT request to the entity resource URL.
    @Test
    void updateQuoteCallsCorrectUrlAndReturnsUpdatedQuote() throws Exception {
        GatewayQuote request = new GatewayQuote();
        request.setId(9L);
        request.setState(State.PAID);

        GatewayQuote expected = new GatewayQuote();
        expected.setId(9L);
        expected.setState(State.PAID);

        String body = objectMapper.writeValueAsString(expected);
        mockServer.expect(requestTo("http://localhost:8080/quote/9"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        GatewayQuote result = quoteClient.updateQuote(request);

        mockServer.verify();
        assertThat(result.getId()).isEqualTo(9L);
        assertThat(result.getState()).isEqualTo(State.PAID);
    }

    /**
     * The regression test for #245.
     *
     * <p>Two things are asserted and both matter. The request must be a PATCH, because the
     * default RestTemplate factory could not send one at all ({@code Invalid HTTP method:
     * PATCH}) and this call site swallows its exceptions — a stamp that throws every time would
     * fail silently, which is the very failure this fixes. And the BODY must mention
     * {@code mintNotifiedAt} and nothing else: a body carrying {@code state} is how a settled
     * payment got reverted to PENDING and three real sales were lost.
     */
    @Test
    void stampMintNotifiedSendsAPatchCarryingOnlyThatField() throws Exception {
        GatewayQuote expected = new GatewayQuote();
        expected.setId(9L);
        expected.setState(State.PAID);

        String body = objectMapper.writeValueAsString(expected);
        mockServer.expect(requestTo("http://localhost:8080/quote/9"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.mintNotifiedAt").exists())
                // The assertion with teeth. Everything else here would still pass if the body
                // were a whole serialised quote.
                .andExpect(jsonPath("$.state").doesNotExist())
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        GatewayQuote result = quoteClient.stampMintNotified(9L, Instant.parse("2026-09-23T16:32:37Z"));

        mockServer.verify();
        assertThat(result.getState()).isEqualTo(State.PAID);
    }
}
