# Coupon purchases are direct charges on the connected account

A Checkout Session created for a coupon purchase is created **on the stall's
connected account**, with `Stripe-Account` set and the platform's cut expressed
as `application_fee_amount`. The connected account is merchant of record, the
funds land there, and no money passes through a platform balance.

## This is a correction, not a new feature

`StripeSdkCheckoutClient` builds its `RequestOptions` from the platform secret
key alone. There is no `setStripeAccount(...)` and no `application_fee_amount`,
so every session it creates today charges **the platform account**. That is the
right shape for the flow it was written for — settling a `GatewayQuote` against
the mint — and the wrong shape for a stall selling a coupon.

The difference is not a fee-routing detail. With a platform charge, the platform
holds the funds and is merchant of record. That makes Imani a holder of customer
money, which is the position the whole no-float design exists to avoid, and it is
the difference between sitting outside the FCA safeguarding perimeter and sitting
inside it. Building coupon purchase on the current path would ship precisely the
float position the design rules out.

**So the funds-flow correction lands first, on its own**, before anything is
built on top of it. It changes where money goes for every caller of the Stripe
gateway, which is too large a consequence to arrive as a side effect of a
coupon feature.

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
- **Existing callers must be classified.** Every current use of
  `StripeCheckoutRequest` is either a platform charge that should stay one, or a
  charge that was always meant to be the merchant's. That list has to be made
  explicitly rather than assumed.
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
