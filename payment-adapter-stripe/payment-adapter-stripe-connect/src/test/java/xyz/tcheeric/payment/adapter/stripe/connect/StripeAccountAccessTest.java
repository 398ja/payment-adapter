package xyz.tcheeric.payment.adapter.stripe.connect;

import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which Stripe failures mean "that stored account id is no longer usable".
 *
 * <h2>Why this is tested on its own</h2>
 *
 * <p>{@code StripeConnectServiceTest} mocks the client, so it can prove the
 * SERVICE recovers when told an account is missing — and it cannot prove the
 * client ever says so. The bug lived precisely in that gap: the recovery was
 * written, correct, and unreachable, because the mapping upstream classified the
 * real-world response as a generic API error. Mocking the boundary you are
 * testing is how a defect hides between two green suites.
 *
 * <p>The real-world response is a 403, not a 404. Stripe will not confirm
 * whether an account it cannot show you exists, so an id belonging to another
 * key comes back "forbidden" with `account_invalid`, and its own message admits
 * the ambiguity: "(or that account does not exist)".
 *
 * <p>Symptom when this is wrong: switch Stripe keys — from stripe-mock to a real
 * sandbox, say — and every stall whose account id predates the switch is stuck
 * on "Failed to retrieve Stripe account: acct_..." with no route forward from
 * the UI. Only a hand-edited database clears it.
 */
class StripeAccountAccessTest {

    /**
     * THE CASE THAT HAPPENS. Verified against real Stripe with a sandbox key and
     * an account id from stripe-mock:
     *
     * <pre>
     * status=403 code=account_invalid
     * message=The provided key '...' does not have access to account
     *         'acct_RKk5V8GFKXE3pdb' (or that account does not exist).
     * </pre>
     */
    @Test
    void forbiddenWithAccountInvalidMeansUnusable() {
        assertThat(missingOrInaccessible(new PermissionException(
                "does not have access to account 'acct_x' (or that account does not exist)",
                null, "account_invalid", 403))).isTrue();
    }

    /** The obvious case, and the only one the code used to handle. */
    @Test
    void notFoundMeansUnusable() {
        assertThat(missingOrInaccessible(new InvalidRequestException(
                "No such account", null, "resource_missing", null, 404, null))).isTrue();
    }

    /**
     * A BARE 403 MUST NOT COUNT, and this is the assertion that keeps the fix
     * from becoming a worse bug. A revoked key or a permissions problem also
     * answers 403; treating those as "account is gone" would delete a live
     * connected account and open a fresh one, orphaning a merchant's real
     * account — with its payouts — because of a temporary access failure.
     */
    @Test
    void forbiddenWithoutAccountInvalidIsNotAnExcuseToRecreate() {
        assertThat(missingOrInaccessible(new PermissionException(
                "Application access may have been revoked.", null, "api_key_expired", 403))).isFalse();
    }

    /** Transient failures keep the account, for the same reason. */
    @Test
    void rateLimitingIsNotAMissingAccount() {
        assertThat(missingOrInaccessible(new RateLimitException(
                "Too many requests", null, "rate_limit", null, 429, null))).isFalse();
    }

    /** A server-side wobble is not evidence about the account either. */
    @Test
    void serverErrorsAreNotAMissingAccount() {
        assertThat(missingOrInaccessible(new InvalidRequestException(
                "Internal error", null, null, null, 500, null))).isFalse();
    }

    private static boolean missingOrInaccessible(StripeException e) {
        try {
            Method m = StripeSdkConnectClient.class
                    .getDeclaredMethod("isMissingOrInaccessible", StripeException.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, e);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(
                    "isMissingOrInaccessible(StripeException) must exist on StripeSdkConnectClient", failure);
        }
    }
}
