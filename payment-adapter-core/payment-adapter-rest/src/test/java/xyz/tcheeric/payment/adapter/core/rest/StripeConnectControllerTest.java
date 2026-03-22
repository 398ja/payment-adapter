package xyz.tcheeric.payment.adapter.core.rest;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xyz.tcheeric.payment.adapter.stripe.connect.StripeConnectController;
import xyz.tcheeric.payment.adapter.stripe.connect.StripeConnectExceptionHandler;
import xyz.tcheeric.payment.adapter.stripe.connect.StripeConnectService;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectAccountResponse;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StripeConnectControllerTest {

    private StripeConnectService stripeConnectService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        stripeConnectService = mock(StripeConnectService.class);
        StripeConnectController controller = new StripeConnectController(stripeConnectService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new StripeConnectExceptionHandler())
                .build();
    }

    @Test
    void createOrResumeReturnsOnboardingUrl() throws Exception {
        when(stripeConnectService.createOrResume("merchant-123", "https://merchant.app/settings", "https://merchant.app/settings"))
                .thenReturn(new StripeConnectAccountResponse(
                        "merchant-123",
                        "acct_123",
                        "https://connect.stripe.test/onboarding",
                        "onboarding_in_progress",
                        false,
                        false,
                        false,
                        false,
                        "usd",
                        List.of("external_account")));

        mockMvc.perform(post("/api/v1/stripe/connect/accounts")
                        .contentType("application/json")
                        .content("""
                                {
                                  "merchant_pubkey": "merchant-123",
                                  "return_url": "https://merchant.app/settings",
                                  "refresh_url": "https://merchant.app/settings"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripe_account_id").value("acct_123"))
                .andExpect(jsonPath("$.onboarding_url").value("https://connect.stripe.test/onboarding"))
                .andExpect(jsonPath("$.requirements_due[0]").value("external_account"));
    }

    @Test
    void getStatusReturnsNotConnectedWhenAdapterHasNoAccount() throws Exception {
        when(stripeConnectService.getStatus("merchant-123"))
                .thenReturn(new StripeConnectAccountResponse(
                        "merchant-123",
                        null,
                        null,
                        "not_connected",
                        false,
                        false,
                        false,
                        false,
                        null,
                        List.of()));

        mockMvc.perform(get("/api/v1/stripe/connect/accounts/merchant-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("not_connected"));
    }
}
