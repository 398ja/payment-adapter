package xyz.tcheeric.payment.adapter.webhook.helper.validator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RequestValidatorFacadeTest {

    // Validates that an empty request fails phoenixd validation
    @Test
    void validateFailsWithEmptyRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RequestValidatorFacade.validate(request));
        assertEquals("Missing required parameter: externalId", ex.getMessage());
    }
}
