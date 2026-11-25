# PayPal card purchases mirror Stripe, on the same ledger

A stall can take card payments through PayPal exactly as it does through Stripe:
the buyer pays on a PayPal-hosted page, the stall is merchant of record, the
platform's cut (if any) is a platform fee, and a paid purchase becomes a row in
the same "paid means owed" ledger the Purchase Issuer already works.

## The shape, side by side

| Concern | Stripe | PayPal |
| --- | --- | --- |
| Stall's account | Connect account `acct_…` | Seller onboarded by Partner Referrals, PayPal merchant id |
| Onboarding | `POST /api/v1/stripe/connect/accounts` | `POST /api/v1/paypal/connect/accounts` (same routes and response shape) |
| "Can take a card today" | `CardChargeCapability` (`charges_enabled` AND `card_payments: active`) | `PaypalCardCapability` (`payments_receivable` AND `primary_email_confirmed` AND third-party `PAYMENT` permission granted) |
| Hosted payment page | Checkout Session on the connected account (`Stripe-Account`) | Order created as the seller (`PayPal-Auth-Assertion`, `payee.merchant_id`), `landing_page=GUEST_CHECKOUT` so the card form opens first |
| Platform cut | `application_fee_amount` | `payment_instruction.platform_fees` (needs the seller's `PARTNER_FEE` consent) |
| Start a purchase | `POST /api/v1/checkout/purchase` | `POST /api/v1/checkout/paypal/purchase` (same body, same refusals, same response) |
| Money captured | Automatic | Captured by this service on `CHECKOUT.ORDER.APPROVED`, on the buyer's return, or by the reconciler, always with the same `PayPal-Request-Id` |
| Paid means owed | `checkout.session.completed` (paid) | `PAYMENT.CAPTURE.COMPLETED` |
| Processor reference on the coupon | PaymentIntent `pi_…` | Capture id |
| Refund / dispute | `charge.refunded`, `charge.dispute.*` | `PAYMENT.CAPTURE.REFUNDED` / `REVERSED`, `CUSTOMER.DISPUTE.CREATED` / `RESOLVED` |
| Webhook authenticity | `Stripe-Signature` HMAC | PayPal transmission signature, verified locally against PayPal's certificate |
| Reference check for a stall | `…/stripe/connect/accounts/{pk}/payments/{pi}/verification` | `…/paypal/connect/accounts/{pk}/payments/{capture}/verification` |
| Lost webhook recovery | Reconciler lists the account's Checkout Sessions | Reconciler walks this service's own `paypal_checkout` rows (PayPal has no order listing) |

## One ledger, not two

`stripe_purchase` gained a `processor` column (`stripe` or `paypal`) and is
otherwise shared. The row's meaning was never Stripe's: it records that a
purchase was paid and is undischarged. Sharing it means the Purchase Issuer,
`/api/v1/purchases/owed`, discharge verification against gateway-core, the
claim lease, the attention and operator flows, and every gauge serve PayPal
without a line changed. A second table would have needed a second issuer
integration that could drift from the first, which is the opposite of a mirror.

The columns keep their Stripe names and take the PayPal equivalent:
`checkout_session_id` holds the order id, `payment_intent_id` holds the capture
id (the processor reference) and `connected_account_id` holds the PayPal
merchant id. The unique constraints mean the same thing for both.

Every Stripe-only reader is now scoped to `processor = 'stripe'` (the
reconciler's stale and parked sweeps), and the operator's live check before a
retry is routed by processor, so a PayPal row is never read from Stripe.

## Why the metadata lives in a table here

A Checkout Session carries a metadata map that comes back on the webhook.
PayPal has `custom_id` (127 characters) and nothing else. So the checkout
writes a `paypal_checkout` row (order id, stall, amount, currency, the buyer
metadata, this deployment's name) before the buyer sees PayPal, and the
webhook reads it back. That is stronger than metadata riding the payment: the
buyer key cannot be changed in transit. `custom_id` carries
`<environment>:<quote id>` so an order created by another deployment on a
shared sandbox app is recognised as foreign, and one with no stamp is recorded
parked as `unstamped_session`, exactly the Stripe rules.

The same table is what makes lost-webhook recovery possible.

## What is not mirrored, deliberately

- **The mint-quote `Gateway`** (platform charge settling a `GatewayQuote`).
  Nothing in the platform calls a `CREDIT_CARD` gateway, and a second one would
  compete with `StripeGateway` in `GatewayRegistry` for the same payment type.
- **Gifting-site drafts** (imani-wallet#410). `GiftingService` chooses Stripe
  inside the draft flow; offering PayPal there is a product decision for the
  wallet integration.
- **Wallet integration.** The wallet's `pi_` shape checks (reference
  verification, sale pull) need to learn capture ids. The backend already
  answers for them.

## Operating it

PayPal is off unless `paypal.enabled=true`. Connect-style onboarding needs
`paypal.connect.enabled=true` too, and refuses to start without the gateway,
for the same reason Stripe does. Two webhooks are registered on the platform
app: payments to `/webhook/paypal` (`paypal.webhook-id`) and onboarding to
`/api/v1/paypal/connect/webhooks` (`paypal.connect.webhook-id`).

`paypal.environment` defaults to `STRIPE_ENVIRONMENT` and the PayPal webhook
module refuses to start when the two differ: the ledger knows one deployment
name, and an order stamped with any other would be read as foreign and dropped.

`paypal.return-base-url`, when set, sends the approving buyer back to
`GET /api/v1/checkout/paypal/return?token=<order>` here. That route captures at
once and then answers 303 to `paypal.success-url` (or to `paypal.cancel-url`
when PayPal declined). It never shows the buyer an error: whatever fails there,
`CHECKOUT.ORDER.APPROVED` and the reconciler capture the same order with the
same request id. Unset, PayPal returns the buyer straight to the success URL
and capture waits for the webhook.

With PayPal off, `/webhook/paypal` has no handler at all (unlike Stripe's
ServiceLoader stub): nothing could verify a delivery without the webhook id.

The reconciler never closes an order PayPal says was captured, even when the
follow-up read fails; only an order PayPal no longer knows, or one never
approved for `paypal.reconcile.abandon-after`, is closed.
