# PayPal instalment plans mirror Stripe's, on the same plan table

A stall that takes cards through PayPal can offer instalment plans exactly as a Stripe stall can: the buyer
approves a recurring payment on a PayPal-hosted page, every instalment settles in the stall's own PayPal account,
and each paid instalment issues vouchers through the same "paid means owed" ledger. This record explains how the
Stripe design in [the recurring spec](../specs/payment-adapter-stripe-recurring-spec.md) maps onto PayPal's
Subscriptions API, and what differs.

## The shape, side by side

| Concern | Stripe | PayPal |
| --- | --- | --- |
| Start a plan | `POST /api/v1/checkout/plan` | `POST /api/v1/checkout/paypal/plan` (same body, refusals, codes and response) |
| Stall opt-in | `stall_instalment_settings` (signed by the stall) | The same row: the opt-in is the stall's, not the processor's |
| Where the plan lives | Subscription on the connected account (`Stripe-Account`) | Product, billing plan and subscription created as the seller (`PayPal-Auth-Assertion`) |
| Fixed term | Imani sets `cancel_at_period_end` once N purchase rows exist | PayPal's own `total_cycles = N`; PayPal stops and sends `BILLING.SUBSCRIPTION.EXPIRED` |
| Open-ended | No end date | `total_cycles = 0` |
| Linking events to the plan | `plan_id` metadata on the Checkout Session and subscription | The subscription id, stored when Imani creates it, before the buyer sees PayPal |
| Started | `checkout.session.completed` (links only) | `BILLING.SUBSCRIPTION.ACTIVATED` (status only) |
| Paid means owed | `invoice.paid` | `PAYMENT.SALE.COMPLETED` (`billing_agreement_id` names the subscription) |
| Ledger key | Invoice id in `checkout_session_id`, payment intent in `payment_intent_id` | Sale id in both; `processor = 'paypal'` |
| Failure | `invoice.payment_failed` | `BILLING.SUBSCRIPTION.PAYMENT.FAILED`, `BILLING.SUBSCRIPTION.SUSPENDED` |
| End | `customer.subscription.deleted` | `BILLING.SUBSCRIPTION.CANCELLED`, `BILLING.SUBSCRIPTION.EXPIRED` |
| Refund / reversal | `charge.refunded` | `PAYMENT.SALE.REFUNDED`, `PAYMENT.SALE.REVERSED`; the plan is marked `ATTENTION` |
| Buyer cancel | Shared `POST /api/v1/checkout/plans/{id}/cancel`, at period end | Same route; PayPal cancel (no further charges) through `ProcessorPlanCanceller` |
| Lost webhooks | `InstalmentReconciler` lists invoices | `PaypalInstalmentReconciler` lists `/v1/billing/subscriptions/{id}/transactions` |
| Abandoned page | Checkout Session expires | Subscription still `APPROVAL_PENDING` after `abandon-after` is cancelled by the reconciler |
| Disconnect | Blocked while plans charge; deauthorize orphans plans | Same: disconnect refused (`ACTIVE_PLANS`), `MERCHANT.PARTNER-CONSENT.REVOKED` orphans plans |

## One plan table, not two

`recurring_plan` gained a `processor` column (V28), as `stripe_purchase` did in ADR 0002. A plan means "this buyer
pays this stall in instalments", whoever moves the money, so the wallet's plan list, the stall summary, the signed
read and cancel routes and the purchase issuer serve PayPal without a second copy. Every Stripe-only reader is
scoped: the Stripe instalment reconciler reads only `processor = 'stripe'`, and the plan cancel route hands any
other processor's plan to its `ProcessorPlanCanceller`, never to Stripe.

## Why the fixed term is PayPal's, not Imani's

Stripe's build counts recorded instalments and asks Stripe to stop after the last one, because Checkout could not
create a fixed-term subscription. PayPal billing plans take `total_cycles`, so PayPal itself stops after N. There is
no final cancel that a lost webhook could leave undone. Imani still counts rows: a sale beyond N, or after the plan
was cancelled, is recorded (paid means owed) and the plan is flagged `ATTENTION` for a person.

## Why a product and billing plan per buyer

PayPal fixes the price and schedule on the billing plan, and each buyer chooses their own amount and term, so a plan
cannot be shared between buyers. Each step carries a `PayPal-Request-Id` derived from Imani's plan id, so a retry
returns the objects the first attempt created. The cost is clutter in the stall's PayPal catalogue.

## Not mirrored, or not yet verified

- **Approval for third-party subscriptions.** PayPal documents multiparty subscriptions with
  `PayPal-Auth-Assertion`, and says the integration "is available to select partners only". Imani's PayPal
  business account has not completed KYC, so this has not run against PayPal, not even the sandbox. Ask PayPal to
  enable subscriptions for the partner app, and confirm the seller consent covers billing plans.
- **Payload shapes** (`PAYMENT.SALE.*`, transaction list) are written from PayPal's documentation and tested with
  hand-written fixtures only.
- **Platform fees.** None, as for Stripe plans in v1.
- **Payment-method update link and plan screen** in the wallet: not built.
- **PayPal-only deployments.** The shared plan read and cancel routes are registered with Stripe's recurring
  configuration (`stripe.recurring.enabled`). A deployment that turns on only `paypal.recurring.enabled` can start
  PayPal plans but has no buyer cancel route yet.
- **Partial refunds read live.** A v1 sale reports only its state, so the operator's live check reads a partially
  refunded instalment as at least one minor unit refunded; the exact figure arrives with `PAYMENT.SALE.REFUNDED`.

## Operating it

Off unless `paypal.enabled=true` and `paypal.recurring.enabled=true`. Subscribe the payments webhook
(`/webhook/paypal`) to `BILLING.SUBSCRIPTION.ACTIVATED`, `BILLING.SUBSCRIPTION.PAYMENT.FAILED`,
`BILLING.SUBSCRIPTION.SUSPENDED`, `BILLING.SUBSCRIPTION.CANCELLED`, `BILLING.SUBSCRIPTION.EXPIRED`,
`PAYMENT.SALE.COMPLETED`, `PAYMENT.SALE.REFUNDED` and `PAYMENT.SALE.REVERSED`, in addition to ADR 0002's events.
See [configuration](../reference/configuration.md) for `paypal.recurring.*`.
