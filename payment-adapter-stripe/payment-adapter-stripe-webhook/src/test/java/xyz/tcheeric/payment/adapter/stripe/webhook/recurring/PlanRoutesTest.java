package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StallInstalmentSettingsRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

/**
 * {@code /plans/settings} sits beside {@code /plans/{planId}}. A literal path must win, or a stall reading its offer
 * would be answered "no such plan", and saving it would never reach the settings controller.
 */
class PlanRoutesTest {

    private static final String STALL = "a".repeat(64);

    private StallInstalmentSettingsRepository settings;
    private RecurringPlanRepository plans;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        settings = mock(StallInstalmentSettingsRepository.class);
        plans = mock(RecurringPlanRepository.class);
        when(settings.findById(any())).thenReturn(Optional.empty());
        List<String> origins = List.of("https://wallet.imani.casa");
        mvc = MockMvcBuilders.standaloneSetup(
                new PlanManagementController(plans, mock(StripePurchaseRepository.class),
                        mock(StripeSubscriptionClient.class), new xyz.tcheeric.payment.adapter.stripe.webhook.gifting.Nip98Verifier(), origins),
                new StallInstalmentSettingsController(settings, new xyz.tcheeric.payment.adapter.stripe.webhook.gifting.Nip98Verifier(), origins))
                .build();
    }

    @Test
    void readingTheOfferReachesTheSettingsControllerNotAPlanLookup() throws Exception {
        mvc.perform(get(StallInstalmentSettingsController.PATH).param("stall", STALL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        verify(settings).findById(STALL);
        verifyNoInteractions(plans);
    }

    @Test
    void savingTheOfferReachesTheSettingsController() throws Exception {
        // Unsigned, so refused; what matters is WHO refused it (a 401 from settings, not a 404/405 from plans).
        mvc.perform(post(StallInstalmentSettingsController.PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(plans);
    }
}
