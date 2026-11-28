# Draft: question to Stripe Support on Connect OAuth and loss liability

**Status:** Draft, not sent. Fill the `[ ]` fields and send from the Stripe Dashboard
(Help > Contact support, topic: Connect).
**Date:** 2026-11-28
**Related:** [`payment-adapter-stripe-recurring-spec.md`](payment-adapter-stripe-recurring-spec.md), unverified items 6 and 7.
**Why:** Stripe's written answers confirm OAuth eligibility and who bears losses on a Standard account connected to
Imani. The spec relies on both.

---

**To:** Stripe Support
**Subject:** Connect OAuth for existing UK Standard accounts: eligibility and loss liability

Hello,

I'm [name] at Imani App Ltd (UK, company number 17525772). Our platform account is [acct_… or the email on the
account]. We're a Connect platform, and we're planning to let small UK farms offer customers instalment plans
(Stripe Billing subscriptions) on the farms' own Stripe accounts.

Our design is direct charges only. The farm is merchant of record, funds settle in the farm's balance, and we hold
no customer funds. Most farms already have a Stripe account, so we'd like them to connect it to us using Connect
OAuth (`scope=read_write`). Farms without an account would use Connect Onboarding for Standard accounts. I'd be
grateful for written answers to these questions:

1. **OAuth eligibility.** Can our live platform account use Connect OAuth with `read_write` scope to connect
   existing UK Standard accounts? Is any review or approval needed beyond enabling OAuth and registering a redirect
   URI? Please confirm our application is registered as a Platform.
2. **OAuth's future.** The OAuth documentation says it isn't recommended for new platforms, and Standard accounts
   are now labelled legacy. Is OAuth for connecting existing Standard accounts supported long term? Is there an
   Accounts v2 equivalent for connecting an existing account?
3. **Losses.** For a Standard account connected to us through OAuth, with direct charges and subscriptions, who is
   liable if a refund or dispute makes the farm's balance negative and it can't cover it? Is it Stripe, the farm or
   Imani? Does this differ between Standard accounts connected through OAuth and ones created through our own
   onboarding?
4. **Fees.** For these accounts, please confirm that Stripe bills the farm for payment processing, Stripe Billing
   and dispute fees, and that Imani is billed none, including Connect per-account and payout fees. Our pricing model
   is [Stripe handles pricing / you handle pricing: please correct if wrong].
5. **Settings.** Can we read `controller.fees.payer`, `controller.losses.payments` and the dashboard type from an
   OAuth-connected account, using our platform key with the `Stripe-Account` header, so we can check the setup
   before offering a farm instalment plans?
6. **Disconnection.** If a farm disconnects from us, its subscriptions continue to run and charge its customers. Is
   there anything we should do on our side, other than listen for `account.application.deauthorized` and ask the
   farm to cancel or migrate its subscriptions first?

A reply to each point would help us, with links to the relevant documentation if that's easier.

Thank you,
[Name]
Imani App Ltd
[email] · [phone]

---

## Notes for the sender

- Add whatever Support needs to find the account. Nothing identifying was included in this draft.
- Question 4 needs the Connect pricing model Imani's account actually uses.
- If shorter is better, keep questions 1, 3 and 4.
- Record Stripe's answers in the spec's "Not yet verified" list and update items 6 and 7.
