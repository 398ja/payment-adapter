package xyz.tcheeric.payment.adapter.paypal.connect;

import java.util.Optional;

/** The Partner Referrals and seller-status calls onboarding makes, as the platform itself. */
public interface PaypalPartnerClient {

    /**
     * Start (or restart) a seller's PayPal onboarding.
     *
     * @return the {@code action_url} the seller opens
     */
    String createReferral(String trackingId, String returnUrl);

    /** The merchant id PayPal holds for this tracking id, once the seller has finished PayPal's flow. */
    Optional<String> findMerchantId(String trackingId);

    /**
     * @throws xyz.tcheeric.payment.adapter.paypal.connect.exception.PaypalConnectException with
     *         {@code ACCOUNT_NOT_FOUND} when PayPal knows no such seller for this partner
     */
    PaypalSellerStatus sellerStatus(String merchantId);
}
