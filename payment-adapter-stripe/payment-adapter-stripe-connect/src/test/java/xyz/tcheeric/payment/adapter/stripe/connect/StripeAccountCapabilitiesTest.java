package xyz.tcheeric.payment.adapter.stripe.connect;

import com.stripe.param.AccountCreateParams;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a connected account must ASK STRIPE FOR to be able to take a card.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>Connected accounts were created with a type and a country and nothing
 * else. Stripe requests no capability by default, so the account came back with
 * {@code card_payments: unrequested} — verified live:
 *
 * <pre>
 * charges_enabled: true   payouts_enabled: true   details_submitted: true
 * capabilities: {transfers: active}
 * card_payments -> unrequested
 * </pre>
 *
 * <p>Read that pair together, because it is the whole bug. {@code transfers}
 * alone is enough to make {@code charges_enabled} TRUE, and
 * {@code charges_enabled} is the single flag the wallet's Payments screen shows
 * and the merchant mirror republishes {@code acceptsCards} from. So a stall that
 * physically cannot accept a card was reported as ready, every shopper was
 * offered a Buy button, and the failure landed inside Stripe's own hosted
 * checkout at {@code POST /v1/payment_pages/cs_.../confirm} with a 400 — after
 * a real buyer had typed a real card number.
 *
 * <p>NOTHING ON OUR SIDE COULD HAVE LOGGED IT. The session was created happily,
 * because a session is valid; the charge is what the capability forbids, and
 * that is decided in Stripe's UI, in a request our code never makes. The only
 * visible artefact was a status code in someone's browser devtools.
 *
 * <h2>Why the assertion is on the params, not on a mock's return</h2>
 *
 * <p>The failure was an OMISSION in the outbound request. A test that stubs
 * {@code Account.create} and checks the snapshot cannot see it: the stub returns
 * whatever it was told to, capabilities included, and stays green while the real
 * call asks for nothing. So this serialises the params the client actually
 * builds and asserts on the wire form Stripe will receive.
 *
 * @see StripeAccountAccessTest for the same lesson about mocking a boundary
 */
class StripeAccountCapabilitiesTest {

    /**
     * THE REGRESSION. Without {@code card_payments} requested, every purchase
     * dies at Stripe's confirm step and no card number can save it.
     */
    @Test
    void cardPaymentsIsRequested() {
        assertThat(capability("card_payments"))
                .as("an account that cannot take a card is the bug this file exists for")
                .isEqualTo(Map.of("requested", true));
    }

    /**
     * Transfers stays requested. Express defaults it on, but relying on a
     * default that is granted implicitly is what made {@code charges_enabled}
     * misleading in the first place — so both are asked for explicitly.
     */
    @Test
    void transfersIsStillRequested() {
        assertThat(capability("transfers")).isEqualTo(Map.of("requested", true));
    }

    /** The identity that ties the account back to a stall must survive. */
    @Test
    void merchantPubkeyIsStillCarried() {
        assertThat(params().get("metadata")).isEqualTo(Map.of("merchant_pubkey", "npub_test"));
        assertThat(params().get("type")).isEqualTo("express");
        assertThat(params().get("country")).isEqualTo("GB");
    }

    /** Country is optional, and omitting it must not produce a null key. */
    @Test
    void blankCountryIsOmittedEntirely() {
        assertThat(build("npub_test", "  ").toMap()).doesNotContainKey("country");
    }

    /**
     * One capability as Stripe's SDK serialises it. {@code toMap()} nests
     * rather than flattening to {@code capabilities[card_payments][requested]},
     * so the lookup goes through the nested map the wire form is built from.
     */
    @SuppressWarnings("unchecked")
    private Object capability(String name) {
        Map<String, Object> caps = (Map<String, Object>) params().get("capabilities");
        assertThat(caps).as("no capabilities requested at all").isNotNull();
        return caps.get(name);
    }

    private Map<String, Object> params() {
        return build("npub_test", "gb").toMap();
    }

    /**
     * Calls the REAL production method. Deliberately not a copy of the builder:
     * a duplicated builder would keep passing after someone dropped the
     * capability from the client, which is the exact failure being pinned.
     */
    private AccountCreateParams build(String merchantPubkey, String country) {
        return new StripeSdkConnectClient(new StripeGatewayProperties())
                .accountCreateParams(merchantPubkey, country);
    }
}
