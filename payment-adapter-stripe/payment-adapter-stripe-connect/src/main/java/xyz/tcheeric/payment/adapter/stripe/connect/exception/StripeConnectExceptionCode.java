package xyz.tcheeric.payment.adapter.stripe.connect.exception;

public enum StripeConnectExceptionCode {
    CONNECT_DISABLED,
    MERCHANT_ALREADY_LINKED,
    ACCOUNT_NOT_FOUND,
    ONBOARDING_LINK_FAILED,
    OWNERSHIP_MISMATCH,
    WEBHOOK_SIGNATURE_INVALID,
    OAUTH_DISABLED,
    OAUTH_STATE_INVALID,
    OAUTH_CODE_REJECTED,
    STRIPE_API_ERROR
}
