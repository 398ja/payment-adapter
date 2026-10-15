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
never match it; a `/failure` report never moves it. All routes below are behind
the purchase API token.

| Reason | Set by | Meaning |
| --- | --- | --- |
| `gateway_unrecorded` | issuer, `POST /{eventId}/attention` | gc answered 409 UNRECORDED for the warrant nonce (gc#232). Mint state unknown: never retried. |
| `delivery_exhausted` | issuer, `POST /{eventId}/attention` (with `voucherId`) | Delivery outbox gave up (imani-wallet#274). A coupon exists. |
| `stale_issuing` | the claim | The lease lapsed `purchase.claim.max-lease-expiries` (3) times. |
| `refunded_after_issue` | derived, gauge only | Refund recorded on a row with a coupon, not acknowledged. Status unchanged (#265). |
| `disputed` | derived, gauge only | Dispute recorded, not acknowledged. Status unchanged (#265). |

`POST /{eventId}/attention` `{reason, detail?, worker?, voucherId?}`: 200 parked
(or already; first reason stands), 400 unknown/derived reason, 404, 409 closed or
another worker holds the live claim, 503 optimistic lock lost (retry).

`POST /{eventId}/resolve` `{action, voucherId?, operator?, note?}`:

| Action | Effect |
| --- | --- |
| `retry` | OWED if no coupon, ISSUED if one exists; resets `lease_expiries`. 409 for `gateway_unrecorded`. |
| `discharged` | DISCHARGED, naming the voucher (400 when neither the body nor the row has one). |
| `acknowledge` | Marks the latest refund/dispute seen. No status change. 409 when there is none. |

200 on success or repeat, 404, 409 when the row is not parked. `GET /attention`
lists parked rows, oldest first. Gauge: `imani_payments_card_needs_attention{reason}`.
`imani_payments_paid_unissued` still counts parked rows.
