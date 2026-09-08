package xyz.tcheeric.payment.adapter.stripe.connect;

import com.stripe.exception.StripeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Event;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectException;
import xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectExceptionCode;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

public class StripeSdkConnectClient implements StripeConnectClient {

    private static final Logger log = LoggerFactory.getLogger(StripeSdkConnectClient.class);

    private final StripeGatewayProperties gatewayProperties;

    public StripeSdkConnectClient(StripeGatewayProperties gatewayProperties) {
        this.gatewayProperties = gatewayProperties;
    }

    @Override
    public StripeAccountSnapshot createConnectedAccount(String merchantPubkey, String country) {
        try {
            AccountCreateParams.Builder builder = AccountCreateParams.builder()
                    .setType(AccountCreateParams.Type.EXPRESS)
                    .putMetadata("merchant_pubkey", merchantPubkey);
            if (country != null && !country.isBlank()) {
                builder.setCountry(country.toUpperCase(Locale.ROOT));
            }
            return toSnapshot(Account.create(builder.build(), requestOptions()));
        } catch (StripeException e) {
            // Logged HERE because the exception's message never reaches anyone:
            // the REST layer answers a fixed "Failed to create Stripe connected
            // account" and the cause is dropped. Debugging a genuine 401 from
            // the API meant reproducing the call by hand with wget — the service
            // knew exactly what was wrong and said none of it.
            //
            // Stripe's own code and message are the whole diagnosis: an
            // authentication failure, a disabled capability and an unreachable
            // host are three different afternoons.
            logStripeFailure("create_connected_account", e);
            throw StripeConnectException.apiError("Failed to create Stripe connected account", e);
        }
    }

    @Override
    public StripeAccountSnapshot retrieveAccount(String stripeAccountId) {
        try {
            return toSnapshot(Account.retrieve(stripeAccountId, requestOptions()));
        } catch (StripeException e) {
            if (e.getStatusCode() != null && e.getStatusCode() == 404) {
                throw StripeConnectException.accountNotFound(stripeAccountId, e);
            }
            logStripeFailure("retrieve_account", e);
            throw StripeConnectException.apiError("Failed to retrieve Stripe account: " + stripeAccountId, e);
        }
    }

    @Override
    public String createOnboardingLink(String stripeAccountId, String returnUrl, String refreshUrl) {
        try {
            AccountLinkCreateParams params = AccountLinkCreateParams.builder()
                    .setAccount(stripeAccountId)
                    .setRefreshUrl(refreshUrl)
                    .setReturnUrl(returnUrl)
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .build();
            AccountLink link = AccountLink.create(params, requestOptions());
            return link.getUrl();
        } catch (StripeException e) {
            logStripeFailure("create_onboarding_link", e);
            throw new StripeConnectException(
                    StripeConnectExceptionCode.ONBOARDING_LINK_FAILED,
                    "Failed to create Stripe onboarding link",
                    e);
        }
    }

    @Override
    public Event constructWebhookEvent(String payload, String signatureHeader, String webhookSecret) {
        try {
            return Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (Exception e) {
            throw new StripeConnectException(
                    StripeConnectExceptionCode.WEBHOOK_SIGNATURE_INVALID,
                    "Invalid Stripe webhook signature",
                    e);
        }
    }

    /**
     * The per-call options every Stripe request here is made with.
     *
     * <p>Carries the api-base when one is configured, which is what lets the
     * Connect flows run against {@code stripe-mock} with no Stripe account. The
     * SDK's default is Stripe itself, so an unset property behaves exactly as
     * before.
     *
     * <p>Built through {@code StripeGatewayProperties#requestOptions()} rather
     * than RequestOptions.builder() directly, so this client cannot be the one
     * that forgets the api-base while its neighbours honour it. Set per-request
     * rather than via the global {@code Stripe.overrideApiBase}, which would
     * apply to every Stripe call in the JVM including other components'.
     *
     * <p>The override cannot name a non-local host: {@code StripeGatewayProperties}
     * refuses to start otherwise. Pointing production at a fake would make every
     * charge and payout appear to succeed while no money moved, and there is no
     * natural alarm for that.
     */
    /**
     * What Stripe actually said, at WARN.
     *
     * <p>Every catch block here throws an exception whose message is a fixed
     * sentence, and the REST layer surfaces only that. Stripe's own code, message
     * and status — the entire diagnosis — went nowhere. A key the API rejected
     * and a host that could not be reached produced identical output.
     *
     * <p>Includes the api-base, because "which Stripe is this talking to?" is the
     * first question when the answer is surprising and the only clue that a
     * developer is pointed at a mock. The API KEY is never logged.
     */
    private void logStripeFailure(String operation, StripeException e) {
        log.warn("stripe_connect_failed operation={} status={} code={} api_base={} message={}",
                operation,
                e.getStatusCode(),
                e.getCode(),
                gatewayProperties.isApiBaseOverridden() ? gatewayProperties.getApiBase() : "stripe",
                e.getMessage());
    }

    private RequestOptions requestOptions() {
        return gatewayProperties.requestOptions()
                .setApiKey(gatewayProperties.getSecretKey())
                .build();
    }

    private StripeAccountSnapshot toSnapshot(Account account) {
        List<String> currentlyDue = account.getRequirements() == null || account.getRequirements().getCurrentlyDue() == null
                ? List.of()
                : account.getRequirements().getCurrentlyDue();
        boolean detailsSubmitted = Boolean.TRUE.equals(account.getDetailsSubmitted());
        return new StripeAccountSnapshot(
                account.getMetadata() == null ? null : account.getMetadata().get("merchant_pubkey"),
                account.getId(),
                detailsSubmitted && currentlyDue.isEmpty(),
                Boolean.TRUE.equals(account.getChargesEnabled()),
                Boolean.TRUE.equals(account.getPayoutsEnabled()),
                detailsSubmitted,
                normalizeCurrency(account.getDefaultCurrency()),
                List.copyOf(currentlyDue),
                account.getRequirements() == null ? null : account.getRequirements().getDisabledReason(),
                account.getCountry(),
                Objects.toString(account.getEmail(), null)
        );
    }

    private String normalizeCurrency(String currency) {
        return currency == null ? null : currency.toLowerCase(Locale.ROOT);
    }
}
