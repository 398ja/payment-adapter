package xyz.tcheeric.gateway.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;

import static org.assertj.core.api.Assertions.assertThat;
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
}
