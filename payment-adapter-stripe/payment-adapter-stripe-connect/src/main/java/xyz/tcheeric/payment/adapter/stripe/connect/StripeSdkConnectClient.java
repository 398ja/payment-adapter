package xyz.tcheeric.payment.adapter.stripe.connect;

import com.stripe.exception.StripeException;
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

    private RequestOptions requestOptions() {
        return RequestOptions.builder()
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
