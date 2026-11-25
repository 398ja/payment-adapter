package xyz.tcheeric.payment.adapter.paypal.gateway.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalValidationException;

class PaypalMoneyTest {

    @Test
    void minorUnitsBecomePaypalDecimals() {
        assertThat(PaypalMoney.toValue(1000, "usd")).isEqualTo("10.00");
        assertThat(PaypalMoney.toValue(5, "EUR")).isEqualTo("0.05");
        assertThat(PaypalMoney.toValue(1500, "jpy")).isEqualTo("1500");
    }

    @Test
    void paypalDecimalsBecomeMinorUnits() {
        assertThat(PaypalMoney.toMinor("10.00", "USD")).isEqualTo(1000L);
        assertThat(PaypalMoney.toMinor("10", "usd")).isEqualTo(1000L);
        assertThat(PaypalMoney.toMinor("1500", "JPY")).isEqualTo(1500L);
    }

    @Test
    void moreprecisionThanTheCurrencyIsRefusedNotRounded() {
        assertThatThrownBy(() -> PaypalMoney.toMinor("10.005", "usd")).isInstanceOf(PaypalValidationException.class);
        assertThatThrownBy(() -> PaypalMoney.toMinor("1.5", "jpy")).isInstanceOf(PaypalValidationException.class);
    }

    @Test
    void wholeOnlyCurrenciesRefuseFractionsBeforePaypalSees_them() {
        assertThat(PaypalMoney.toValue(10000, "huf")).isEqualTo("100");
        assertThatThrownBy(() -> PaypalMoney.toValue(10050, "huf")).isInstanceOf(PaypalValidationException.class);
    }

    @Test
    void unknownCurrencyIsRefused() {
        assertThatThrownBy(() -> PaypalMoney.toValue(1, "zzz")).isInstanceOf(PaypalValidationException.class);
        assertThatThrownBy(() -> PaypalMoney.toMinor("", "usd")).isInstanceOf(PaypalValidationException.class);
    }
}
