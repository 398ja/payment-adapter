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

## Key rollover (#288)

`creatorPubkey` is compared x-only (gc#255 F1): a `02`/`03`-prefixed compressed form is
the same key, and only a 66-hex value is ever stripped.

Rotating `purchase.gateway.private-key` used to strand every registered row: core still
names the old key as creator, so the discharge was refused as "not this service". During
a rollover the old **public** key may be named:

| Property | Env | Meaning |
| --- | --- | --- |
| `purchase.gateway.previous-pubkeys` | `PURCHASE_GATEWAY_PREVIOUS_PUBKEYS` | Comma-separated x-only hex. Public keys only. |
| `purchase.gateway.previous-pubkeys-until` | `PURCHASE_GATEWAY_PREVIOUS_PUBKEYS_UNTIL` | ISO-8601 instant, exclusive. Required with any key. |
| `purchase.gateway.previous-pubkeys-from` | `PURCHASE_GATEWAY_PREVIOUS_PUBKEYS_FROM` | ISO-8601 instant, optional. Pins the row cutoff (below). |

A registration by a previous key counts as this service's own until `until`. Every
other fact (buyer, amount, unit, stall, coupon) is checked exactly as before. Boot
fails on a malformed or off-curve key, a missing or unparseable end, an end more than
21 days ahead (the 14-day re-check horizon plus a week), or the current private key
pasted into the list. The window is read on every check, so it closes without a
restart. Removing the variables and recreating the service ends it early.

A previous key only vouches for a purchase recorded no later than the moment the
running service adopted the rollover (its boot, logged as "for purchases recorded no
later than ..."). Every later purchase was registered by the current key, so if core
names the old key as its creator, somebody holding the old key got there first: it is
refused like any other squatter. Recreate the service with the new key and the
previous keys in ONE step: a later restart that adds the previous keys still covers
every old-key row (they predate it), but those rows are refused until it happens.

A restart later in the window (a redeploy, an OOM, a host reboot) would move a
boot-time cutoff later, and a leaked old key could then squat purchases recorded
between the first new-key boot and that restart. To prevent it, copy the instant from
the first boot's log line into `PURCHASE_GATEWAY_PREVIOUS_PUBKEYS_FROM`. The cutoff is
the EARLIER of that pin and the current boot, so the pin can only narrow the allowance:
one in the future is ignored (with a warning), and one set too early refuses old-key
rows, as before #288, rather than letting anyone in. An unparseable pin stops the
boot. Unset, the cutoff is the current boot, and the log line says so.

Nothing changes in gateway-core. The discharge only ever GETs an existing binding, and
core answers that GET for any NIP-98 key. The PUT is sent only for a new row or one
core calls `unregistered`, so the new key never re-registers an old binding, which would
409 because core binds the creator into the terms. Procedure: imani-deploy
`docs/runbooks/purchase-gateway-key.md`.

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
| `retry` | First re-reads the payment **live** from Stripe (see below). Then OWED if no coupon, ISSUED if one exists, REFUNDED if no coupon and the payment was refunded while parked; resets `lease_expiries`. 409 for `gateway_unrecorded`. |
| `discharged` | DISCHARGED, naming the voucher (400 when neither the body nor the row has one, or the body names a different one than the row records). |
| `acknowledge` | Marks the latest refund/dispute seen. No status change. 409 when there is none. |

**Live check before `retry` (#286).** A retry of a parked row is the one operator action that
can mint (OWED) or deliver (ISSUED), and a missed refund or dispute webhook leaves the row saying
the money is intact. So before moving it, PA reads the PaymentIntent and its latest charge
(refunds, dispute) on the stall's connected account, the same read the reconciler makes. A
refund or dispute the row did not carry is recorded through the webhook's listener path
(`refunded_amount_minor`, `disputed`, `reversed_at`) and logged `[alert]`.

| Stripe says | Answer |
| --- | --- |
| intact | the retry runs as above |
| disputed | 409 `{"error":"retry_refused","reason":"disputed","detail":...}`, row stays parked |
| refunded, coupon exists | 409 `reason: refunded_after_issue`; `acknowledge` it |
| refunded, no coupon, news to the row | 409 `reason: refunded` with the refund now recorded; a second `retry` closes it REFUNDED (never mints) |
| row has no `payment_intent_id` | 409 `reason: no_payment_intent`; resolve by hand |
| Stripe unreachable, or Stripe disabled | 503 `{"error":"stripe_unavailable"}`; nothing changed, try again |

200 on success or repeat, 404, 409 when the row is not parked. `GET /attention`
lists parked rows, oldest first. Gauge: `imani_payments_card_needs_attention{reason}`.
`imani_payments_paid_unissued` still counts parked rows.

## Stripe reconciler (#272)

Webhooks were the only way a paid card purchase reached `stripe_purchase`; a DB
restore or a webhook outage beyond Stripe's ~3-day retry lost it (audit S10).
`StripePurchaseReconciler` runs every `purchase.reconcile.interval` (10 min) when
Stripe is enabled. Configuration: [reference](configuration.md#stripe-purchase-reconciler-272).

1. **Lost rows.** For each connected account that has `charges_enabled`, or has a cursor (an
   account Stripe restricted after it took payments stays in scope; a deauthorized one is
   deleted and drops out), least recently reconciled first, it leases the
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
     Only an intact payment becomes OWED, and it is **read again after the commit**:
     a refund or dispute that landed in between went to a webhook that found no row,
     so the re-read applies it (REFUNDED, or parked) before any issuer can claim.
   * a constraint failure counts as "recorded by its webhook" only when a row for the
     session or `pi_` exists afterwards; otherwise the account stops with its cursor kept.
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

The row is re-read inside the move and the staleness test applied again, so an
ISSUING row an issuer re-claimed meanwhile is left to it. When Stripe will not
answer about a row (other than a 429), it is parked anyway with "live check
FAILED" in the detail: parking never makes a row mintable.

**Staging purchase #1** (PAID/BLOCKED after a NIP-98 401, not refunded): the first
run reads its PaymentIntent, finds it intact, and parks it `issue_blocked`. It
then appears on `GET /attention` and in `imani_payments_card_needs_attention`.
Once the cause is fixed (U2-U4), an operator `retry`s it to OWED. Had it been
refunded meanwhile, the run would make it REFUNDED instead.

3. **Parked rows** (#286). Up to `parked-batch-size` (20) NEEDS_ATTENTION rows a run, rotating
   by `created_at` so a long list is covered over several runs, get the same live read. A
   refund or dispute no webhook delivered is recorded (facts only, the row stays parked for the
   operator) and the run logs `[alert]`. A re-read that finds nothing new writes nothing, so an
   acknowledged reversal is not re-raised. A 429 ends the sweep.

**Bounded.** Each run may make `max-stripe-calls-per-run` (50) Stripe reads for
listing and lost rows (list pages and PaymentIntent reads alike), plus one
unbudgeted re-read per newly recorded row; the stale sweep has its own bound
(`stale-batch-size`, 20) so a listing backlog cannot starve it. A 429 ends the run.
A pass that does
not finish keeps its window (`pass_until`) and position (`resume_after`), and the
next run continues it. A Stripe error leaves the cursor where it was.

## Webhook subscription check (#286)

Stripe sends only the events an endpoint subscribes to, and says nothing about the rest. When the
deployment names its endpoints (`STRIPE_WEBHOOK_ENDPOINT_ID`, `STRIPE_CONNECT_WEBHOOK_ENDPOINT_ID`),
PA reads each with `GET /v1/webhook_endpoints/{id}` once the application is ready and then every
`purchase.reconcile.interval`, and compares `enabled_events` with the event types its handlers act
on. Those lists are the handlers' own registries (`StripePaymentEvent`, `StripeConnectEvent`), which
their dispatch switches over exhaustively, so a newly handled event is checked with no second list.

| Gauge (tag `endpoint`: `payments`, `connect`) | Meaning |
| --- | --- |
| `imani_stripe_webhook_events_missing` | handled event types the endpoint does not deliver (all of them when it is disabled; `*` delivers all). Held at its last value while the endpoint cannot be read. |
| `imani_stripe_webhook_subscription_check_failed` | 1 when the last check could not read the endpoint (Stripe error, or no id configured) |

Both are bound at 0. A gap or a failed check logs `[alert]` with the endpoint id and the missing
event names. Never fatal: startup does not wait on or fail because of it, and no key or signing
secret is logged.

**Async payment methods.** A session that completes `unpaid` (Bacs, SEPA) and is
paid days later is outside the overlap by then: if its `async_payment_succeeded`
webhook is also lost, this reconciler does not find it (follow-up).
