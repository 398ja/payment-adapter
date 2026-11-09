package xyz.tcheeric.payment.adapter.stripe.connect;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import xyz.tcheeric.payment.adapter.stripe.connect.config.StripeConnectProperties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PaymentReferenceControllerTest {

    @Test
    void aPubkeyThatIsNotSixtyFourLowercaseHexIsABadRequest() {
        PaymentReferenceVerifier verifier = mock(PaymentReferenceVerifier.class);
        StripeConnectProperties props = new StripeConnectProperties();
        props.setEnabled(true);
        PaymentReferenceController controller = new PaymentReferenceController(verifier, props);

        assertThatThrownBy(() -> controller.verify("A".repeat(64), "pi_1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> controller.verify("ab", "pi_1"))
                .isInstanceOf(ResponseStatusException.class);
        verify(verifier, never()).verify(anyString(), anyString());
    }
}
