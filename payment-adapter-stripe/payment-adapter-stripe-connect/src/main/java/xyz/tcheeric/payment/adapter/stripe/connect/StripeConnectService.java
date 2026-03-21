package xyz.tcheeric.payment.adapter.stripe.connect;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;

@Service
@RequiredArgsConstructor
public class StripeConnectService {

    private final ConnectedStripeAccountRepository connectedStripeAccountRepository;

    public ConnectedStripeAccount linkMerchantAccount(String merchantPubkey,
                                                      String stripeAccountId,
                                                      String defaultCurrency) {
        ConnectedStripeAccount account = connectedStripeAccountRepository.findByMerchantPubkey(merchantPubkey)
                .orElseGet(ConnectedStripeAccount::new);
        account.setMerchantPubkey(merchantPubkey);
        account.setStripeAccountId(stripeAccountId);
        account.setDefaultCurrency(defaultCurrency);
        account.setUpdatedAt(Instant.now());
        if (account.getCreatedAt() == null) {
            account.setCreatedAt(Instant.now());
        }
        return connectedStripeAccountRepository.save(account);
    }

    public ConnectedStripeAccount updateCapabilities(String stripeAccountId,
                                                     boolean onboardingComplete,
                                                     boolean chargesEnabled,
                                                     boolean payoutsEnabled) {
        ConnectedStripeAccount account = connectedStripeAccountRepository.findByStripeAccountId(stripeAccountId)
                .orElseThrow(() -> new IllegalStateException("Unknown Stripe account: " + stripeAccountId));
        account.setOnboardingComplete(onboardingComplete);
        account.setChargesEnabled(chargesEnabled);
        account.setPayoutsEnabled(payoutsEnabled);
        account.setUpdatedAt(Instant.now());
        return connectedStripeAccountRepository.save(account);
    }
}
