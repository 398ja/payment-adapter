package xyz.tcheeric.payment.adapter.paypal.gateway.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalValidationException;

/**
 * Between this service's minor units and PayPal's decimal strings ({@code "10.00"}).
 *
 * <p>The ledger, the issuer and Stripe all count in minor units. PayPal takes and returns a decimal
 * {@code value}, and refuses decimals on a few currencies whose ISO definition has them
 * ({@code HUF}, {@code TWD}); an amount that would need them is refused here, before the buyer sees
 * PayPal, rather than by PayPal after.
 */
public final class PaypalMoney {

    /** ISO currencies with minor units that PayPal accepts only as whole numbers. */
    private static final Set<String> WHOLE_ONLY = Set.of("HUF", "TWD");

    private PaypalMoney() {
    }

    /** PayPal's {@code currency_code}: upper case. */
    public static String code(String currency) {
        return currency == null ? null : currency.trim().toUpperCase(Locale.ROOT);
    }

    /** @throws PaypalValidationException for an unknown currency, or one PayPal cannot take this amount in */
    public static String toValue(long minor, String currency) {
        String code = code(currency);
        int digits = digits(code);
        BigDecimal value = BigDecimal.valueOf(minor).movePointLeft(digits);
        if (WHOLE_ONLY.contains(code)) {
            if (value.stripTrailingZeros().scale() > 0) {
                throw new PaypalValidationException(
                        "PayPal takes " + code + " in whole units only; " + minor + " minor units is not one");
            }
            return value.setScale(0, RoundingMode.UNNECESSARY).toPlainString();
        }
        return value.setScale(digits, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** @throws PaypalValidationException when the value has more precision than the currency */
    public static long toMinor(String value, String currency) {
        if (value == null || value.isBlank()) {
            throw new PaypalValidationException("PayPal amount has no value");
        }
        int digits = digits(code(currency));
        try {
            return new BigDecimal(value.trim()).movePointRight(digits).setScale(0, RoundingMode.UNNECESSARY)
                    .longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new PaypalValidationException("PayPal amount " + value + " is not a whole number of "
                    + code(currency) + " minor units");
        }
    }

    private static int digits(String code) {
        try {
            int digits = Currency.getInstance(code).getDefaultFractionDigits();
            return Math.max(0, digits);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new PaypalValidationException("Unknown currency: " + code);
        }
    }
}
