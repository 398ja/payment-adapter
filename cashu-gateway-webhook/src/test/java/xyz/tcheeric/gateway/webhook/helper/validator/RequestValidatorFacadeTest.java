package xyz.tcheeric.gateway.webhook.helper.validator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RequestValidatorFacadeTest {

    @AfterEach
    void clearWidProperty() {
        System.clearProperty("wid");
    }

    @Test
    void validateThrowsWhenWidMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RequestValidatorFacade.validate(request));
        assertEquals("Invalid webhook id", ex.getMessage());
    }

    @Test
    void validateUsesSystemProperty() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        System.setProperty("wid", "unknown");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RequestValidatorFacade.validate(request));
        assertEquals("Unknown webhook request id: unknown", ex.getMessage());
    }

    @Test
    void validateUnknownWidThrowsException() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("wid", "unknown");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RequestValidatorFacade.validate(request));
        assertEquals("Unknown webhook request id: unknown", ex.getMessage());
    }
}
