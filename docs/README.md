# Documentation

This index organizes the repository documentation using the existing `docs/`
layout and links to the active references, guides, and specifications.

## Guides

- [Requirements](requirements.md)
- [Building](building.md)
- [Running](running.md)
- [Docker](docker.md)
- [Testing](testing.md)
- [PR Guidelines](pr-guidelines.md)

## Reference

- [Reference Index](reference/README.md)
- [API Reference](reference/api.md)
- [Configuration](reference/configuration.md)
- [Webhook Handler](reference/webhook.md)
- [Payment Polling](reference/payment-polling.md)
- [Cash Payments](reference/cash-payments.md)
- [Java Client](reference/client.md)
- [Logging](reference/logging.md)

## Specs

- [Stripe Module Spec](specs/payment-adapter-stripe-spec.md)
- [Stripe Recurring Purchases (Instalment Plans) Spec](specs/payment-adapter-stripe-recurring-spec.md)

## Explanation (architecture decisions)

- [ADR 0001: Coupon purchases are direct charges](adr/0001-coupon-purchases-are-direct-charges.md)
- [ADR 0002: PayPal card purchases mirror Stripe](adr/0002-paypal-card-purchases-mirror-stripe.md)
- [ADR 0003: PayPal instalment plans mirror Stripe's](adr/0003-paypal-instalment-plans.md)
