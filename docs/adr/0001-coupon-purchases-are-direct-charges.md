# Coupon purchases are direct charges on the connected account

A Checkout Session created for a coupon purchase is created **on the stall's
connected account**, with `Stripe-Account` set and the platform's cut expressed
as `application_fee_amount`. The connected account is merchant of record, the
funds land there, and no money passes through a platform balance.

## This was a correction, and it has landed

`StripeSdkCheckoutClient` built its `RequestOptions` from the platform secret
key alone. There was no `setStripeAccount(...)` and no `application_fee_amount`,
so every session it created charged **the platform account**. That is the
right shape for the flow it was written for — settling a `GatewayQuote` against
the mint — and the wrong shape for a stall selling a coupon.

The difference is not a fee-routing detail. With a platform charge, the platform
holds the funds and is merchant of record. That makes Imani a holder of customer
money, which is the position the whole no-float design exists to avoid, and it is
the difference between sitting outside the FCA safeguarding perimeter and sitting
inside it. Building coupon purchase on the old path would have shipped precisely
the float position the design rules out.

**The correction landed on its own**, before anything is built on top of it,
because it changes where money goes and that is too large a consequence to
arrive as a side effect of a coupon feature.

**Implemented as `createPurchaseSession`**, a separate entry point rather than
an extra argument on the mint path. The two answer different questions about who
is selling, and keeping them apart means no caller drifts from one to the other
by leaving an argument null. The mint-quote path is untouched and still charges
the platform, which is correct: Imani sells its own product there.

Two tests hold both halves — a purchase must be a direct charge, and a mint
quote must stay a platform one — and were verified by nulling the account and
watching the first fail.

Refusals are placed where the caller can still see them, rather than surfacing
from inside the Stripe SDK after a customer has committed: a purchase with no
connected account, a fee at or above the amount, a negative fee, and a currency
the deployment disallows. Caller metadata cannot overwrite `quote_id`, which the
settlement path reads.

**Retrieve needed the same treatment.** A direct-charge session lives on the
connected account and is invisible from the platform, so retrieving it without
`Stripe-Account` answers "no such session". `retrieveCheckoutSession` gained an
account-aware overload; the existing single-argument form stays correct for
every platform charge.

## No stall ever hands over a Stripe key

Worth stating because the opposite is the obvious reading of "the stall's own
Stripe account". Acting on a connected account needs the **platform** key plus
the account's **id**; `acct_...` is an identifier, not a credential. So there is
no per-stall secret, and nowhere to put one:
`MerchantStripeAccount` stores an account id, four status booleans, a default
currency and outstanding requirements, and the only `secretKey` in the Stripe
stack is `StripeGatewayProperties.secretKey`, which is Imani's own.

The alternative — a stall pastes their Stripe secret key into settings — was
rejected. It would make Imani custodian of full-access keys for every stall,
which is a far larger prize than one platform key and forfeits the revocability
Connect gives: a stall disconnects, and Imani's ability to act on their account
ends with no secret to rotate.

The honest cost: the platform key can act on **every** connected account, so a
breach of it is a breach across all stalls. Inherent to Connect, unchanged by
this decision, and the reason the key stays in one service with a narrow surface
rather than being passed around.

## Consequences

- **Disputes and refunds land on the stall**, because they are merchant of
  record. This is the intended risk position and it is not a detail a stall can
  be allowed to discover: "get paid upfront" is the pitch, and a dispute
  reverses part of it.
- **A rolling reserve is likely.** Stripe treats stored value and future
  delivery as reserve triggers, commonly 5–15% held for 30–180 days on a
  high-risk classification. Good for Imani's risk position, and a real thing to
  tell a stall before they sign up, because a reserve claws back part of the
  same pitch.
- **Existing callers were classified, and there was exactly one.**
  `StripeGateway.createMintQuote` is the only production caller, it is a
  platform charge, and it stays one. So the correction added a path rather than
  changing any existing behaviour, which is why it could land ahead of the
  feature that needs it.
- **The webhook must resolve the account.** A direct charge arrives with the
  connected account's id, and the handler currently resolves only a
  `GatewayQuote`.

## The consequence of payment is not the mint's alone

`StripeWebhookHandler` hard-wires a successful payment to settling a mint quote
through `QuoteClient` and `PaymentClient`. A coupon purchase is a different
consequence of the same event, and there is no seam for one:
`GatewayWebhookForwarder` exists but only the cash gateway uses it.

**A forwarder-shaped extension point is added, and issuance hangs off it rather
than living here.** This service says a purchase was paid; what follows is
somebody else's business. Coupon issuance requires custody of Nostr issuing
keys, and a payments adapter is the wrong place to acquire that.

`ProcessedStripeWebhookEvent` already gives replay idempotency, and it applies to
the new consequence unchanged: Stripe retries, and a coupon cannot be un-minted.

## Express accounts, inherited rather than chosen

`StripeSdkConnectClient` creates `Type.EXPRESS`, and that stands. Express
supports direct charges, so the property that matters — merchant of record, no
float — is delivered either way; the account type decides who runs the dashboard
and who answers when a payout is late.

Standard was argued for on support burden and rejected on cost of reversal:
Express accounts cannot be converted in place, so switching means re-onboarding
every stall already connected, to win an argument about support rather than
about funds. Under Express, Imani is first-line support and the stall's
dashboard shows them less, which makes the reserve disclosure above **more**
important, not less.
