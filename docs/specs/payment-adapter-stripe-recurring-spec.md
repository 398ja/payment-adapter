# Payment Adapter - Stripe Recurring Purchases (Instalment Plans) Spec

**Status:** Draft
**Date:** 2026-11-27
**Repository:** `payment-adapter`
**Document Type:** Specification
**Extends:** [`payment-adapter-stripe-spec.md`](payment-adapter-stripe-spec.md) (which lists "Subscription billing" as out of scope for v1)
**Related:** [ADR 0001](../adr/0001-coupon-purchases-are-direct-charges.md), [ADR 0002](../adr/0002-paypal-card-purchases-mirror-stripe.md)

## Purpose

Let a buyer pay a stall for vouchers in instalments (for example £50 a month for six months), with every
instalment settling in the stall's own Stripe account and issuing vouchers to the buyer's wallet when, and only
when, that instalment is paid.

The first customers are Community Supported Agriculture (CSA) farms, whose members prepay for a season's share.
The CSA Network's own guidance describes monthly standing orders and direct debits as admin-heavy: every price
rise needs each member to change their standing order, and "there is nothing to actually stop people cancelling"
(*Share in the Harvest*, s.8.2). See Appendix B for sources.

## Scope

### In Scope

- Recurring card payments created as Stripe subscriptions on the stall's connected account (direct charges).
- Fixed-term plans (N instalments) and open-ended plans (until cancelled).
- Voucher issuance per paid instalment, through the existing "paid means owed" ledger and Purchase Issuer.
- Connecting a farm's **existing** Stripe account through OAuth, and offering plans on it (see "Account types").
  Express, Custom and Accounts v2 are documented there for comparison, and are out of scope for plans in v1.
- Member and stall cancellation, failed-payment handling, refund and dispute visibility.

### Out of Scope for v1

- PayPal recurring payments (see "PayPal"). Stripe only.
- Bacs Direct Debit and other non-card methods while `Gateway` still maps one implementation to one `PaymentType`
  (inherited from the parent spec). Appendix A shows why this is worth revisiting.
- Automatic voucher clawback after a refund or dispute (inherited from the parent spec).
- Issuing vouchers ahead of payment (no credit extended by Imani or the stall).
- Platform fees on subscriptions. v1 charges none; see "Fees".
- Proration, plan changes, trials, pause and resume.

## Design Principles

1. **No float.** Every charge is a direct charge on the stall's account. Destination charges and separate
   charges and transfers are refused, because they put customer funds on the platform balance and make Imani
   merchant of record (ADR 0001).
2. **Paid means owed, per instalment.** Vouchers for instalment *k* are issued when instalment *k* is paid. A
   plan that stops after three of six payments has issued three instalments of vouchers and no more.
3. **Stripe is the source of truth for the schedule.** Imani does not run its own timer or pull payments. It
   reacts to webhooks.
4. **Behave on capabilities, not account types.** The adapter decides what it can do from what the account can
   do and who can manage its subscriptions, not from a `type` string.
5. **Refunds and disputes stay with the stall,** as in ADR 0001. That holds only for account setups where the
   stall bears fees and losses (R4). Legacy Express accounts put them on Imani; see "Who bears fees and losses".
6. **No stall secret.** As in ADR 0001, the platform key plus the `acct_...` id is enough. Nothing new is stored.

## What Stripe's documentation confirms

Checked 2026-11-27. These are the facts this design relies on.

| Fact | Source |
|---|---|
| A subscription created while authenticated as the connected account (`Stripe-Account`) is a direct charge. The account is merchant of record. Refunds and chargebacks debit its balance. | docs.stripe.com/connect/subscriptions |
| `invoice.paid` is sent when a subscription invoice is paid; provision on it when the subscription `status` is `active`. | docs.stripe.com/billing/subscriptions/webhooks |
| `invoice.payment_failed` and `invoice.payment_action_required` cover failed and authentication-needed renewals. | same |
| A subscription schedule can end after a set number of periods (a phase with `duration.interval_count`), with `end_behavior` `cancel` or `release`. Phases accept `on_behalf_of` and `application_fee_percent` through the API. | docs.stripe.com/billing/subscriptions/subscription-schedules |
| Subscription application fees are a percentage (`application_fee_percent`). A flat fee must be set per invoice with `application_fee_amount`. | docs.stripe.com/connect/subscriptions |
| Subscriptions are **not** cancelled when a connected account disconnects. Under direct charges they keep running on the account. | docs.stripe.com/connect/subscriptions |
| Only connected accounts with the full Stripe Dashboard can manage their customers' subscriptions. For other accounts the platform must. | docs.stripe.com/connect/subscriptions |
| Refund or dispute events on subscription charges are tied back to the subscription through the PaymentIntent and the invoice-payments list (API `2025-03-31.basil` and later). | docs.stripe.com/billing/subscriptions/webhooks |

### Not yet verified (resolve before implementation)

1. Whether Stripe Checkout can create a fixed-term subscription directly. If not, on
   `checkout.session.completed` (mode `subscription`) attach a schedule with `from_subscription` and
   `end_behavior=cancel`, or set `cancel_at` on the subscription.
2. ~~That events for subscriptions on connected accounts reach the existing Connect webhook endpoint.~~
   **Resolved** (docs.stripe.com/connect/accounts-v2/migrate-integration): updates to invoices and subscriptions
   that connected accounts charge their customers using direct charges, and direct charges themselves, are in the
   **Connected accounts** event scope, which is the scope the Connect endpoint listens to. Enable the new event types
   on that endpoint.
3. Whether a customer portal session can be created on a connected account (`Stripe-Account`) for direct-charge
   subscriptions, and what Express accounts allow. If not, cancellation and payment-method updates are built
   against the Subscriptions API.
4. The exact invoice field for `billing_reason` (first instalment vs renewal) on the pinned API version.
5. Whether the purchase ledger's `checkout_session_id` column may be null. Renewal rows have no Checkout session.
6. Whether Imani's Stripe account has Connect OAuth enabled. Stripe's OAuth reference describes no approval step.
   It is settings: enable OAuth under Connect > Onboarding options > OAuth, copy the live `client_id`, register the
   live HTTPS `redirect_uri`(s) (an exact match is required), confirm the application is registered as a
   **Platform** (not an Extension; extensions are read-only), and give the key used for the token exchange the
   **Write** permission on **Connect OAuth** if it is a restricted key. Stripe's platform-profile page could not be
   fetched, so a review gate there is unconfirmed. Test with the sandbox `client_id` first. Other facts from the
   reference: the authorization `code` lasts 5 minutes and is single use, and reusing it revokes the connection
   (the token endpoint is not idempotent), so the callback must never retry the exchange.
7. Who bears a negative balance on a farm's own Standard account when a refund or dispute exceeds its balance.
   Stripe's risk page splits platforms into "Platform is responsible" (platform incurs the losses, Stripe Express
   Dashboard only) and "Stripe is responsible" (Stripe covers losses, farm gets the full or Express Dashboard).
   Standard accounts have the full Dashboard, so they sit on the Stripe-responsible side, and the
   account-type table puts dispute liability on the connected account for direct charges. Stripe's own words
   for the Standard case should be confirmed in writing (Stripe Support or the platform profile) before launch.

Resolved on 2026-11-28 from docs.stripe.com/connect/direct-charges-fee-payer-behavior: the Stripe Billing
("Invoicing and Subscriptions") fee is billed to the **connected account** when its fee payer is `account` /
`stripe` (this includes Standard accounts), and to the **platform** for legacy Express and Custom accounts
(`application_express`, `application_custom`) and for `application`.

## Account types

**Product decision (2026-11-28): two onboarding routes, both ending in the same kind of account.**
(1) A farm that already has a Stripe account connects it through OAuth. (2) A farm with no account is onboarded to a
new one through Stripe-hosted Connect Onboarding. Plans run only on accounts where the farm pays Stripe's fees and
Stripe bears negative balances (R4), which is what both routes produce.

Today the adapter can only do the opposite. `StripeSdkConnectClient.accountCreateParams` creates **Express**
accounts (`Type.EXPRESS`, `card_payments` and `transfers` requested), **which this spec replaces** and sends the stall through Stripe-hosted
onboarding. `StripeConnectController` exposes create-or-resume, refresh, status, disconnect and the webhook. There is
no OAuth, no `client_id`, no `stripe_user_id`, and no way to register an account that already exists.

What "existing account" means in practice: a farm that signed up to Stripe itself holds an ordinary account, which
Stripe treats as Standard when it connects to a platform. Express and Custom accounts are created by, and belong to,
a platform, so a farm cannot bring an "existing Express account" from elsewhere. Stripe also says that since June 2021
a platform using OAuth with `read_write` scope cannot connect a Standard account that is controlled by another
platform. For such a farm (for example one whose account was created through a store builder), Stripe's OAuth flow
directs the user to create a separate Standard account, which connects to Imani automatically. The farm is not
blocked, but the connected account is a new one, not the account the other platform controls.

| | Standard | Express (current) | Custom | Accounts v2 / controller properties |
|---|---|---|---|---|
| Charge type used by Imani | Direct | Direct | Direct | Direct |
| Account holder's Dashboard | Full | Express (limited) | None | Configurable |
| Who can manage subscriptions | **Stall** (full Dashboard) | **Imani** (platform) | **Imani** | Depends on dashboard setting |
| Onboarding | Stripe-hosted | Stripe-hosted | Platform or Stripe | Stripe-hosted |
| Stripe's guidance | Direct charges recommended | Direct charges "not recommended" on legacy Express/Custom; use v2 or destination charges | Same as Express | Recommended for new platforms |

Requirements:

- **R1.** Subscriptions are always created with the `Stripe-Account` header (direct charge), whatever the account
  type. Creating one without it is refused before any call to Stripe, as `createPurchaseSession` already does.
- **R2.** The adapter stores a `subscriptionManagement` capability per account, derived from the account's
  dashboard access: `STALL` or `PLATFORM`. It is refreshed on `account.updated`, alongside the existing
  `CardChargeCapability` check. A plan can be offered only when `CardChargeCapability` is true.
- **R3.** When management is `PLATFORM`, Imani must provide the operations a stall cannot do in its own
  Dashboard: list plans, cancel (at period end or immediately), and send the buyer a payment-method update link.
  When management is `STALL`, the same operations are still offered, because buyers act through the wallet.
- **R4. Plans are offered only on accounts with Standard-equivalent responsibilities**, read from the account at
  connection time and refreshed on `account.updated`: the farm pays all Stripe fees (v1 `controller.fees.payer=account`,
  v2 `fees_collector=stripe`) and Stripe bears negative balances (`controller.losses.payments=stripe`, v2
  `losses_collector=stripe`). The farm then pays Billing fees, Imani is billed no Connect fees, and the farm has a
  Dashboard. Plans are refused on any account where Imani would bear the Billing fee, Connect fees or losses (for
  example a legacy Express account; see "Who bears fees and losses"). **Imani is not live and has no Express stalls, so there is nothing to migrate: account creation switches from
  Express to Standard-equivalent outright, and there is no legacy path to keep.** The R4 gate stays, because an
  OAuth-connected account's settings are the farm's own and must be checked, not assumed. Gate on these
  properties, not on how the account was connected.
- **R5a. Route 2, a farm with no Stripe account: Stripe-hosted Connect Onboarding.** This is the existing
  `POST /api/v1/stripe/connect/accounts` flow (create the account, then an account link), with one change: create
  the account with **Standard-equivalent** settings instead of `Type.EXPRESS`.
  - Preferred and smallest change: Accounts v1 with `type=standard`, or with no controller properties at all.
    Stripe states that defaults (`losses.payments=stripe`, `fees.payer=account`, `requirement_collection=stripe`,
    `stripe_dashboard.type=full`) match Standard behaviour. The existing `AccountCreateParams` code keeps working.
  - Alternative: Accounts v2, `POST /v2/core/accounts` with `dashboard=full`, a `merchant` configuration requesting
    `card_payments`, and `defaults.responsibilities` `fees_collector=stripe`, `losses_collector=stripe`.
  - Light-Dashboard option: v1 controller properties allow `stripe_dashboard.type=express` with `losses=stripe` and
    `fees=account` (not on Stripe's unsupported list, but not shown as an example either; verify before using).
  - Keep `card_payments` requested so `charges_enabled` means what the wallet assumes. `transfers` is no longer
    requested: it serves only destination charges and separate charges and transfers, which are refused.
  - **Done 2026-11-28 (PR #341):** `StripeSdkConnectClient.accountCreateParams` now uses `Type.STANDARD`, and
    ADR 0001 records the reversal. Not yet exercised against live Stripe: confirm a Standard account created this way
    reaches `charges_enabled` with `card_payments` active in test mode, and that the account link flow works unchanged.
  - Account links need a `return_url` and a `refresh_url`, a user who has finished the flow still needs the
    `charges_enabled` check, and account-link URLs must only be given to the authenticated account holder.
- **R6. Route 1, a farm with an existing account: connect through Stripe OAuth.** New endpoints, alongside the current ones:
  - `POST /api/v1/stripe/connect/oauth/start` with `{merchant_pubkey}` builds the
    `https://connect.stripe.com/oauth/authorize` URL (`response_type=code`, Imani's `client_id`, `scope=read_write`,
    a registered `redirect_uri`, and a `state`).
  - `POST /api/v1/stripe/connect/oauth/complete` with `{code, state}`, called by whoever serves the redirect URI
    (server to server, like the other Connect endpoints, so the adapter needs no public browser-facing route).
    It exchanges the code once, reads `stripe_user_id`, retrieves the account and stores it. **It stores the
    account even when it cannot take cards yet** (the farm's own account; the status says what is left), rather
    than requiring `charges_enabled`: refusing would force the farm through consent again. The plan gate (R4)
    is where card capability is enforced.
  - Only the account id is stored. Imani acts with its platform key plus the `Stripe-Account` header, as ADR 0001
    requires, so there is still no per-stall secret.
  - Stripe's OAuth page says OAuth "isn't recommended for new Connect platforms" and recommends Connect Onboarding
    for Standard accounts. That is Stripe's advice on which method to prefer, not a statement that Standard
    accounts are unsupported. Connect Onboarding calls `/v1/accounts` with `type=standard` and so always creates a
    **new** account (verified in Stripe's Standard-accounts guide). The same guide says an application that needs
    access to an **existing** account "can still use OAuth". So OAuth remains the documented route for this
    requirement.
  - Both Stripe pages carry a banner marking Standard, Express and Custom as legacy account types and pointing
    new platforms to Accounts v2. I found no v2 equivalent for connecting an existing account. Ask Stripe whether
    OAuth for existing Standard accounts is staying, and until then keep the OAuth code behind one interface.
- **R7. Ownership comes from the OAuth consent, not from a client-supplied account id.** Today
  `ensureOwnership` passes whenever the Stripe account carries no `merchant_pubkey` metadata, which is true of every
  existing account. Accepting an `acct_...` id from a request would let anyone claim any farm's account. So the
  `state` is a random, single-use, short-lived token stored against the `merchantPubkey` that called `start`
  (table `stripe_oauth_state`), and the merchant is read back from it, never from the callback.
  **Implemented as a database-backed opaque token, not an HMAC:** a lookup gives the same guarantees (unforgeable,
  bound to the merchant, expiring) and needs no new signing secret. It is consumed in one atomic statement
  (`UPDATE ... WHERE consumed_at IS NULL AND expires_at > now`) and **committed before the code is exchanged**, so a
  failure later cannot hand the same state, and so the same single-use code, a second chance. One Stripe account
  maps to one `merchantPubkey` (unique constraint), and a second claim is refused, not overwritten.
- **R8. Disconnecting is blocked while plans are active.** Stripe keeps charging buyers after a disconnect.
  `disconnect` today only deletes Imani's database row, and `account.application.deauthorized` also deletes the
  row. With plans, both must first mark every active plan on the account `orphaned` and notify buyers (see Risks).
  Imani can end the connection itself with `POST connect.stripe.com/oauth/deauthorize`, after the stall has
  cancelled or moved its plans.
- **R9. Record how the account was connected** (`CREATED_EXPRESS`, `CREATED_STANDARD`, `OAUTH_STANDARD`) and its
  fee payer, loss collector and dashboard type next to the capability fields, so R4 is a lookup and not a guess.
  Verify that an account a farm opened itself returns those fields when retrieved through OAuth access.
- **R5.** Charge-type refusals are placed where the caller sees them, before the buyer commits: no connected
  account, an account without card capability, a plan whose instalment would not cover a minimum amount, a
  currency the deployment disallows.

### Who bears fees and losses, by account setup (Stripe, 2026-11-28)

| | Standard / v2 `stripe` | Legacy Express | Legacy Custom |
|---|---|---|---|
| Card processing fee | Stall | Stall | Stall |
| Dispute fee | Stall | Stall | Stall |
| Stripe Billing (subscriptions) fee | **Stall** | **Imani** | **Imani** |
| Stripe Tax, Card Account Updater, Checkout add-ons | Stall | **Imani** | **Imani** |
| Connect fees (active account, payouts) | None to Imani | Imani pays (Stripe: "additional cost") | Imani pays |
| Negative-balance losses | **Stripe** (Stripe-responsible side of its risk model; see unverified item 7) | **Imani** (a legacy Express account is created with `losses_collector=application`) | **Imani** |

Two consequences:

1. For legacy Express accounts, which is what the adapter's code creates today but which no live stall uses, Imani carries the Billing fee, the Connect fees
   and any shortfall when a refund or dispute exceeds the stall's balance. Stripe also writes that it does not
   recommend direct charges with this legacy setting and suggests destination charges, which put funds on the
   platform balance and are refused here (principle 1). That is why account creation moves to Standard-equivalent (R5a).
2. Contractual terms (below) cannot replace R4. A contract is only as good as the stall's ability to pay.
   Choosing an account setup where Stripe holds the loss is the stronger protection.

The earlier draft treated Express disputes as the stall's problem. That was wrong for legacy Express accounts.

### Accounts v2: what Stripe's documentation says (2026-11-28)

- **Status and direction.** Stripe recommends Accounts v2 "for all new platforms", and calls it GA for Connect users.
  The Standard, Express and Custom pages carry a "legacy account types" banner. Controller properties on Accounts v1
  are documented as a stable, fully backwards-compatible step, and migrating to them is optional.
- **Standard equivalent.** A v2 account with `dashboard=full`, `fees_collector=stripe`, `losses_collector=stripe`, or
  a v1 account with default controller properties, behaves as Standard: the farm pays all fees, Stripe bears
  negative balances, Stripe collects requirements, and the farm has the full Dashboard.
- **Express equivalent is a different thing.** Legacy `type=express` gets `fees.payer=application_express` and
  `losses.payments=application`: Imani pays Billing and Connect fees and bears losses. An Express-style Dashboard
  with `fees=account` and `losses=stripe` is a separate, newer combination.
- **OAuth stays on v1.** Stripe lists "Using OAuth to authenticate connected accounts" among the cases that require
  Accounts v1. The link in that line goes to Stripe Apps OAuth, so it is not certain it covers Connect OAuth for
  Standard accounts. Either way, Imani's platform must be allowed to call v1 (it already does:
  `POST /v1/accounts`), and an OAuth-connected account is the farm's own, not one Imani creates.
- **Both versions interoperate.** A v1 account can be used with v2 endpoints, a v2 account with v1 endpoints, and a
  v2 account keeps sending the v1 events the adapter already handles (`account.updated`,
  `account.application.deauthorized`). A new v1 account may take up to ten minutes before v2 endpoints accept it.
- **Billing a farm's customers does not need v2.** The v2 `customer` configuration lets the *platform* bill a
  connected account as a customer (SaaS subscriptions). Imani's case is the reverse: members are customers of the
  farm, so subscriptions use ordinary Customer objects on the farm's account with direct charges. `customer_account`
  on a v2 account is optional and out of scope.
- **SDK.** The pinned `stripe-java` 31.4.1 contains both `AccountCreateParams.Controller` (v1) and the v2
  `AccountService`, so neither route needs an SDK upgrade.

**Recommendation.** Use Accounts v1 with Standard-equivalent controller properties for route 2 now. It is the
smallest change to the existing connect code, is documented as supported, and matches the account OAuth produces, so
one code path serves both routes. Put account creation behind one interface so it can move to `POST /v2/core/accounts`
later, which Stripe prefers, without touching callers.

## Data model

Add to the Stripe schema (names indicative):

- **`recurring_plan`**: `id`, `processor`, `connected_account_id`, `subscription_id`, `schedule_id` (nullable),
  `buyer_pubkey`, `stall_pubkey`, `amount_per_instalment` (minor units), `currency`, `interval`,
  `total_instalments` (null = open-ended), `instalments_paid`, `status`
  (`pending`, `active`, `past_due`, `completed`, `cancelled`, `orphaned`, `attention`), `livemode`, timestamps.
- **Purchase ledger rows** gain `invoice_id` and `recurring_plan_id` (nullable). Uniqueness: one row per
  `(processor, invoice_id)`. `checkout_session_id` must be nullable for renewals (unverified item 5).
- The buyer key and stall are recorded server-side **before** the buyer reaches Stripe, in `recurring_plan`,
  not trusted from metadata alone (same approach as ADR 0002's `paypal_checkout`). Metadata carries only the
  plan id and the deployment's environment stamp.

## Flows

### Start a plan

`POST /api/v1/checkout/purchase/plan` (same auth, refusals and response shape as `/checkout/purchase`).

1. Validate (R5), write `recurring_plan` as `pending`.
2. Create a Checkout Session in mode `subscription` on the connected account with `subscription_data.metadata`
   carrying the plan id and environment stamp.
3. Return the hosted URL. The wallet only opens it. The recurring pull belongs to Stripe, against the card
   saved on the stall's account.
4. On `checkout.session.completed` (mode `subscription`, `payment_status=paid`): mark the plan `active`, and
   apply the fixed term if unverified item 1 requires it. The first instalment's vouchers are issued on the
   matching `invoice.paid`, not here, so there is one issuance path.

### Each instalment

On `invoice.paid` for a subscription carrying a known plan id: confirm the subscription is `active`, record a
purchase row keyed by invoice, increment `instalments_paid`, and hand the row to the existing Purchase Issuer
for `invoice.amount_paid`. Duplicate delivery is absorbed by the existing processed-event table and the
`(processor, invoice_id)` uniqueness. An invoice for an unknown plan or environment is parked as unstamped, as
for session purchases.

### Failure and end

| Event | Action |
|---|---|
| `invoice.payment_failed` | Plan `past_due`. Issue nothing. Notify the buyer through the wallet. Let Stripe retry. |
| `invoice.payment_action_required` | Notify the buyer with the hosted invoice link. |
| `customer.subscription.deleted` | Plan `cancelled` or, if all instalments are paid, `completed`. |
| `subscription_schedule.completed` / `.canceled` / `.aborted` | Update plan status. |
| `account.application.deauthorized` | Mark every plan on the account `orphaned` and notify buyers. See risk below. |
| `charge.refunded` / `charge.dispute.*` | Existing handling, extended to resolve the invoice and plan. Mark the plan `attention`. Do not auto-cancel. |

### Cancellation

- A buyer cancels through the wallet. Imani cancels the subscription **at period end** on the stall's account.
  An immediate cancel is a stall action.
- Vouchers already issued stay valid. Money is refunded only through the stall's own Stripe account, by the stall
  (or by Imani on the stall's instruction where management is `PLATFORM`).
- Nothing is issued for instalments that were never paid, so cancelling creates no clawback problem.

## Risks

- **Disconnected account keeps charging buyers.** Stripe does not cancel subscriptions on disconnect, and they
  continue on the stall's account. After a Standard account disconnects, Imani stops receiving events, so buyers
  could be charged with no vouchers issued. Mitigation: mark plans `orphaned` on `account.application.deauthorized`,
  tell buyers, and make this a condition in the stall terms (cancel or migrate plans before disconnecting).
- **Rolling reserve.** ADR 0001 notes that stored value and future delivery are commonly reserve triggers (5-15%
  held for 30-180 days). Recurring plans do not remove that. Tell stalls before they sign up.
- **No commitment.** A buyer can cancel at any time. The plan is a convenience, not an enforceable commitment.
  The stall's member agreement carries that.
- **Small instalments are expensive.** The 20p fixed fee and the Billing fee bite hardest below about £40 (see
  Appendix A). Set a minimum instalment per stall.

## Fees

- **Platform fee:** none in v1 (`application_fee_percent` unset). The adapter keeps the existing refusal of a fee
  at or above the amount and of a negative fee. If a fee is ever introduced, it must be a percentage on the
  subscription, or a per-invoice `application_fee_amount` set on `invoice.created`.
- **Stall pays Stripe:** card processing and Stripe Billing. The stall must be told before it creates a plan,
  using the figures in Appendix A.
- **Platform costs (Connect fees):** Stripe's UK Connect pricing offers two models. Which applies to Imani is a
  commercial decision outside this spec.
  - **"You handle pricing":** Imani pays **£2 per monthly active account** (an account is active in any month in
    which a payout goes to its bank account or debit card, so it recurs monthly but only in months with a payout)
    and **0.25% + 10p per payout sent**, billed to Imani at the end of the month on total payouts. Imani may collect
    its own fees from stalls; Stripe does not say this specific fee can be passed through line for line.
  - **"Stripe handles pricing":** Imani pays none of these, and Stripe collects processing fees from the stalls.
    Accounts with fee payer `account` / `stripe` (R4) fall here.
- **Recovering a Billing fee Imani is charged:** for a legacy Express account, `application_fee_percent=0.7`
  would pass the 0.7% on. That is an application fee, so it reaches Imani's balance, and contradicts "no platform
  fee in v1". Prefer R4 so the question does not arise.

## Stall terms (contract)

Before a stall can offer plans, it accepts terms with Imani that settle who bears what. This spec states the
intent. Counsel must draft and review the wording.

1. **Merchant of record.** The stall sells the vouchers and the underlying produce, owns its refund policy, and
   is the contracting party for the buyer's instalments.
2. **Refunds, chargebacks and dispute fees** are the stall's cost, and the stall must keep its Stripe balance able
   to cover them.
3. **Indemnity.** The stall reimburses Imani for any refund, dispute amount, dispute fee, Stripe fee or other
   charge that Stripe debits from Imani's account because of the stall's transactions, with a short payment
   deadline.
4. **Disconnection.** Before disconnecting from Imani or closing its Stripe account, the stall cancels or
   migrates its active plans. It acknowledges that Stripe keeps charging buyers after disconnection.
5. **Reserve and payout holds.** The stall acknowledges Stripe may hold a reserve or delay payouts, which Imani
   does not control.
6. **Fees.** The stall acknowledges the Stripe fees in Appendix A, including the Billing fee and the per-payment
   20p, before creating a plan.
7. **Buyer-facing terms.** Cancellation, refund and unspent-voucher rules are set by the stall and shown to the
   buyer before the first instalment.
8. **Limits.** Imani may cap plan size and term per stall, and may suspend plan creation for a stall with
   unresolved disputes.

Practical limit: an indemnity is a promise from a small business. It protects Imani only to the extent the stall
can pay. Use it as a second layer behind R4, not instead of it.

## PayPal

PayPal Subscriptions exist (Product, Plan, billing cycles, failed-payment recovery), but the documentation
reachable on 2026-11-27 does not cover fixed cycle counts, subscription webhooks, or sellers onboarded through
Partner Referrals. `PaypalPaymentEvent` today lists only `CHECKOUT.ORDER.APPROVED`, `PAYMENT.CAPTURE.*` and
`CUSTOMER.DISPUTE.*`. PayPal parity needs its own ADR after the Subscriptions API and platform docs are read.
Do not promise PayPal instalments until then.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `stripe.recurring.enabled` | `false` | Turns plan creation and the new event handlers on. |
| `stripe.recurring.min-instalment-minor` | `2000` | Smallest instalment accepted (pence). |
| `stripe.recurring.max-instalments` | `24` | Longest fixed term. |

Enable on the Connect webhook endpoint: `invoice.paid`, `invoice.payment_failed`,
`invoice.payment_action_required`, `customer.subscription.updated`, `customer.subscription.deleted`,
`subscription_schedule.completed`, `subscription_schedule.canceled`, `subscription_schedule.aborted`.
`StripePaymentEvent` is an exhaustive switch, so each type added to it must be handled in dispatch.

## Testing

- **Unit:** plan validation and refusals; capability derivation (`STALL` vs `PLATFORM`) for each account type;
  `invoice.paid` for a known plan, unknown plan, foreign environment, duplicate delivery, and a subscription
  that is not `active`.
- **Direct-charge guard:** like the existing pair of tests, one that fails if a plan is created without the
  connected account, verified by nulling the account.
- **Integration:** a six-instalment plan in Stripe test mode with test clocks. Instalments 1-6 each issue one
  voucher batch, a seventh is never charged, a failed card on instalment 3 issues nothing, a refund marks the
  plan `attention`.
- **Account types:** run the plan flow against an OAuth-connected Standard test account and one created through
  Connect Onboarding, and confirm a plan is refused on an account where Imani would bear fees or losses.
- **Security:** replayed and forged webhooks; a plan id from another environment.

## Implementation Plan

1. **Resolve the unverified items** above, including Stripe's written answer on Standard-account losses and Imani's
   OAuth approval (items 6 and 7).
1a. **Onboarding, both routes:** switch account creation to Standard-equivalent (R5a); OAuth start and callback
   (R6); signed `state` and unique account mapping (R7); `connection_type` and responsibilities recorded (R9);
   disconnect guard (R8). Nothing else in this spec can be used until this ships.
2. **Schema and model:** `recurring_plan`, ledger columns, migration, nullability of `checkout_session_id`.
3. **Plan start:** endpoint, Checkout subscription session, `checkout.session.completed` mode handling.
4. **Instalments:** `invoice.paid` handler and issuance; failure and end events.
5. **Management API:** list, cancel, payment-method link; `subscriptionManagement` capability.
6. **Wallet:** a plan screen that opens the Stripe page and shows plan status and cancel.
7. **Docs and operator runbook:** reference docs, stall terms (disconnect, reserve, fees), metrics for plans by
   status.

## Appendix A: Fee estimates (UK, GBP)

Rates used (stripe.com/gb, fetched 2026-11-27): standard UK card **1.5% + 20p**; premium UK card 2.8% + 20p;
Stripe Billing **0.7%** of recurring invoice volume (pay as you go, UK); Bacs Direct Debit **1%**, minimum 20p,
cap £4. A stall's negotiated rates may differ. The Bacs column assumes a method this spec does not yet support.

| Instalment | Card only | Card + Billing | Bacs + Billing |
|---|---|---|---|
| £20 | 50p (2.5%) | 64p (3.2%) | 34p (1.7%) |
| £40 | 80p (2.0%) | £1.08 (2.7%) | 68p (1.7%) |
| £80 | £1.40 (1.75%) | £1.96 (2.45%) | £1.36 (1.7%) |
| £120 | £2.00 (1.7%) | £2.84 (2.4%) | £2.04 (1.7%) |
| £200 | £3.20 (1.6%) | £4.60 (2.3%) | £3.40 (1.7%) |

**Per farm, per year** (every member pays by card each month, 12 instalments; Billing fee included):

| Members | £40 a month | £80 a month |
|---|---|---|
| 30 | £389 on £14,400 | £706 on £28,800 |
| 70 | £907 on £33,600 | £1,646 on £67,200 |
| 125 | £1,620 on £60,000 | £2,940 on £120,000 |

These are the stall's costs on accounts where the stall pays fees (R4). On legacy Express accounts Imani, not the
stall, is billed the Billing column's extra 0.7%.

**Connect fees under "you handle pricing"** (Imani's cost), for a 70-member farm collecting £2,800 a month:
£2 active-account fee, plus 0.25% of about £2,720 paid out (about £6.80), plus 10p per payout (10p if one payout a
month, about £3 if payouts happen on 30 different days). That is about £9 to £12 a month, or £107 to £142 a
year, about 0.3% to 0.4% of the farm's £33,600. The percentage base is inferred from Stripe's wording.

**Comparisons:** one £480 card payment up front costs £7.40 (1.5%), against £12.96 for twelve £40 instalments on
card with Billing. A standing order costs the farm nothing, and the CSA guide puts direct-debit bank charges at
"a few hundred pounds a year". Instalments on card cost more than a standing order and about the same as direct
debit. The case for them rests on admin, gifting and vouchers, not on fees.

## Appendix B: CSA data points used

- Network research note (`imani-marketting/research/uk-cooperatives-fit.md`): about 200 CSA farms, 25,000+ people
  (about 125 per farm on average).
- *Share in the Harvest* (CSA Network UK): most members pay monthly in advance; direct debit "a few hundred
  pounds a year" in bank charges; 2008 Stroud prices of £30-40 a month for a share for two adults and £45-55 for
  a family of four, so prices today are likely higher and the £40 and £80 columns above are sensible bounds;
  bursary and sliding-scale ideas in s.9.1.1.
- CSA farm pages (2026): Tyninghame Community Farm about 70 weekly shares; Food Smiles about 30 members.
- Wales impact summary: 11 CSAs, 500 families, members provided £17,500 of credit.
- CSA Network membership bands (under 50, 50-100, over 100 members) match the three farm sizes above.
