# Coupon purchase fulfilment: registration and discharge

How a paid Stripe coupon purchase becomes a closed debt, and why the gateway's
answer is checked rather than believed.

## The two calls to gateway-core

Both are under `/api/v1/atomic`, so both carry NIP-98, signed with
`purchase.gateway.private-key`.

| Call | When | What |
| --- | --- | --- |
| `PUT /api/v1/atomic/fulfilment/{paymentRequestId}` | `RecordingPurchaseListener`, right after the row is saved | Binds `{recipientPubkey, issuerId, amount, unit}`: the buyer's pubkey, the stall's pubkey, the amount in minor units, the upper-cased currency. NIP-98 `payload` tag = SHA-256 of the body. |
| `GET /api/v1/atomic/fulfilment/{paymentRequestId}` | `PurchaseDischargeService.discharge` | `{fulfilled, sendId, issuerId, recipientPubkey, amount, unit, requested, reason}` |

PUT answers: 201 registered, 200 same terms already registered, 409 different
terms got there first (first writer wins), 400 malformed. 404 or 405 means a
gateway from before imani-gateway-core#131. That is logged and the purchase is
recorded anyway.

## Why

Before imani-gateway-core#131 the GET said `fulfilled` for any completed send
carrying the id. The sender sets that id, the amount and the recipient, so a
1-sat send to oneself tagged with the id discharged a buyer's debt
(imani-wallet#160 review). Core now counts only sends matching the registered
terms. This service registers them, and also checks the facts it gets back.

## Discharge rules

| Gateway answer | Result | HTTP from `/discharge` |
| --- | --- | --- |
| unreachable, non-200, unconfigured | `UNVERIFIABLE`, nothing changes | 503 |
| `fulfilled: false, reason: unregistered` | register now (late), ask once more | depends on the second answer |
| `fulfilled: false, reason: unregistered`, late PUT failed (timeout, 5xx) or 404/405 | `UNVERIFIABLE`, no attempt counted | 503 |
| `fulfilled: false, reason: unregistered`, late PUT 409 or row lacks buyer/stall | `REFUSED` | 409 |
| `fulfilled: false` | `REFUSED`, attempt recorded | 409 |
| `fulfilled: true` without `recipientPubkey`/`amount`/`unit` (old core) | `UNVERIFIABLE`: not trusted, nobody accused | 503 |
| `fulfilled: true`, but recipient is not the buyer, amount below the purchase, unit differs, or issuer is not the row's stall | `REFUSED`, attempt recorded with the reason | 409 |
| `fulfilled: true`, every fact matches | `DISCHARGED` | 200 |

A purchase with no buyer pubkey or no known stall cannot be registered, so it
is never confirmed automatically and needs a manual check.

## Re-checking ISSUED rows

The issuer calls `/discharge` once, after minting. On a 503 it reports a
failure naming the voucher, which parks the row in `ISSUED` (never back in
`OWED`, which would mint again). `IssuedPurchaseRecheck` re-runs discharge for
`ISSUED` rows every `purchase.recheck.interval-ms` (default 5 min). Each row
rests 15 minutes between checks, at most 50 rows go per sweep, and rows older
than 14 days are left for a person. A row that still cannot be verified stays
`ISSUED` and no attempt is counted.

## Registration timing

`RecordingPurchaseListener` registers after the recording transaction commits,
not inside it, so the PUT never holds a DB connection and a rollback cannot
leave a binding for a row that does not exist.

## Why late registration is safe

The id is not secret by discharge time: the issuer's delivery DM carries it to
the buyer. A squatter who registers different terms first gets our PUT a 409.
One who registers identical terms gets it a 200, so the answer's
`creatorPubkey` (imani-gateway-core#131, fb2b76a) must also be this service's
own key; an answer without it is unverifiable. Every answer is compared against
this row's own recipient, amount, unit and stall, so the worst case is a debt
left for a manual check, on the squatter's own purchase.

## Rollout

Safe in either order. Against an old core, registration is a logged 405 and
discharge returns `UNVERIFIABLE`, so the issuer parks card purchases in
`ISSUED`. Once core#131 is live, the re-check sweep registers them late and
discharges them, with no manual step. New purchases register at recording.

## NEEDS_ATTENTION (#273)

A row nothing automatic can safely move on is parked. The claim and `/owed`
never match it; a `/failure` report never moves it. `/attention` (POST) and
`GET /attention` take the issuer's purchase API token. `/resolve` takes only the
operator's `PURCHASE_OPERATOR_TOKEN` (distinct from the issuer's; unset or equal
answers 503), which also reads `GET /attention` and nothing else.

| Reason | Set by | Meaning |
| --- | --- | --- |
| `gateway_unrecorded` | issuer, `POST /{eventId}/attention` | gc answered 409 UNRECORDED for the warrant nonce (gc#232). Mint state unknown: never retried. |
| `delivery_exhausted` | issuer, `POST /{eventId}/attention` (with `voucherId`) | Delivery outbox gave up (imani-wallet#274). A coupon exists. |
| `stale_issuing` | the claim | The lease lapsed `purchase.claim.max-lease-expiries` (3) times. |
| `stale_issuing`, `stale_issued`, `issue_blocked`, `disputed_before_issue` | the Stripe reconciler (#272) | See [Stripe reconciler](#stripe-reconciler-272). |
| `refunded_after_issue` | derived, gauge only | Refund recorded on a row with a coupon, not acknowledged. Status unchanged (#265). |
| `disputed` | derived, gauge only | Dispute recorded, not acknowledged. Status unchanged (#265). |

`POST /{eventId}/attention` `{reason, detail?, worker?, voucherId?}`: 200 parked
(or already; first reason stands), 400 unknown/derived reason, 404, 409 closed or
another worker holds the live claim, 503 optimistic lock lost (retry).

`POST /{eventId}/resolve` `{action, voucherId?, operator?, note?}`:

| Action | Effect |
| --- | --- |
| `retry` | OWED if no coupon, ISSUED if one exists, REFUNDED if no coupon and the payment was refunded while parked; resets `lease_expiries`. 409 for `gateway_unrecorded`. |
| `discharged` | DISCHARGED, naming the voucher (400 when neither the body nor the row has one, or the body names a different one than the row records). |
| `acknowledge` | Marks the latest refund/dispute seen. No status change. 409 when there is none. |

200 on success or repeat, 404, 409 when the row is not parked. `GET /attention`
lists parked rows, oldest first. Gauge: `imani_payments_card_needs_attention{reason}`.
`imani_payments_paid_unissued` still counts parked rows.

## Stripe reconciler (#272)

Webhooks were the only way a paid card purchase reached `stripe_purchase`; a DB
restore or a webhook outage beyond Stripe's ~3-day retry lost it (audit S10).
`StripePurchaseReconciler` runs every `purchase.reconcile.interval` (10 min) when
Stripe is enabled. Configuration: [reference](configuration.md#stripe-purchase-reconciler-272).

1. **Lost rows.** For each `charges_enabled` connected account it leases the
   account's `stripe_reconcile_cursor` row (one replica per account; PA has no
   ShedLock, so this is the purchase claim's conditional-UPDATE lease), lists
   `status=complete` Checkout Sessions with `Stripe-Account`, created in
   `[created_through - overlap, now - grace]`, and for each `payment_status=paid`
   session with no row by `checkout_session_id`:
   * reads the **live** PaymentIntent and latest Charge first;
   * records it through the webhook's `RecordingPurchaseListener` as
     `reconcile:<cs_id>` (the V14 uniques make a racing webhook harmless);
   * refunded (any `amount_refunded`): the same transaction makes it **REFUNDED**,
     so it is never claimable; disputed: parked `disputed_before_issue`.
     Only an intact payment becomes OWED.
   * counts `imani_payments_reconciled_total{source="card"}` and logs `[alert]`.
     Non-zero means webhooks are being lost.
2. **Stale rows** (no blind re-mint, gc#232). After the live check, never back to OWED:

| Row | Becomes |
| --- | --- |
| ISSUING, lease lapsed > `issuing-stale-after` (1 h) | NEEDS_ATTENTION `stale_issuing` |
| ISSUED, created > `issued-stale-after` (14 d) | NEEDS_ATTENTION `stale_issued` (voucher kept; a refund after issue is recorded as facts only) |
| BLOCKED, payment intact | NEEDS_ATTENTION `issue_blocked`, last failure in the detail |
| BLOCKED, refunded | REFUNDED |
| BLOCKED, disputed | NEEDS_ATTENTION `disputed_before_issue` |

**Staging purchase #1** (PAID/BLOCKED after a NIP-98 401, not refunded): the first
run reads its PaymentIntent, finds it intact, and parks it `issue_blocked`. It
then appears on `GET /attention` and in `imani_payments_card_needs_attention`.
Once the cause is fixed (U2-U4), an operator `retry`s it to OWED. Had it been
refunded meanwhile, the run would make it REFUNDED instead.

**Bounded.** Each run may make `max-stripe-calls-per-run` (50) Stripe reads
(list pages and PaymentIntent reads alike); a 429 ends the run. A pass that does
not finish keeps its window (`pass_until`) and position (`resume_after`), and the
next run continues it. A Stripe error leaves the cursor where it was.
