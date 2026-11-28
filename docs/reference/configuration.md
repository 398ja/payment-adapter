# Configuration Reference

This document lists configuration properties and environment variables available across Payment Adapter modules.

## REST module ([payment-adapter-core/payment-adapter-rest](../../payment-adapter-core/payment-adapter-rest))

| Property | Default | Description |
| --- | --- | --- |
| `spring.application.name` | `payment-adapter-rest` | Spring Boot application name. |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/payment-adapter` | JDBC connection string. |
| `spring.datasource.driverClassName` | `org.postgresql.Driver` | JDBC driver class. |
| `spring.datasource.username` | `postgres` | Database username. |
| `spring.datasource.password` | `password` | Database password. |
| `spring.jpa.database-platform` | `org.hibernate.dialect.PostgreSQLDialect` | Hibernate dialect. |
| `spring.jpa.hibernate.ddl-auto` | `create-drop` | JPA schema generation strategy (test). |
| `server.port` | `8080` | HTTP port (test). |

### Environment Variables

| Variable | Default | Description |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://payment-adapter-db:5432/payment-adapter` | Overrides database URL in Docker. |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | Overrides database user in Docker. |
| `SPRING_DATASOURCE_PASSWORD` | `password` | Overrides database password in Docker. |
| `POSTGRES_DB` | `payment-adapter` | Database name for the PostgreSQL container. |
| `POSTGRES_USER` | `postgres` | Username for the PostgreSQL container. |
| `POSTGRES_PASSWORD` | `password` | Password for the PostgreSQL container. |

## Dummy Gateway module ([payment-adapter-ln/payment-adapter-ln-dummy](../../payment-adapter-ln/payment-adapter-ln-dummy))

| Property | Default | Description |
| --- | --- | --- |
| `dummy.payment_status` | `80` | Simulated payment status. |
| `dummy.amount` | `10` | Simulated invoice amount. |
| `dummy.expiry` | `86400` | Invoice expiry in seconds. |
| `dummy.fee_reserve` | `30` | Fee reserve amount. |
| `webhook.base_url` | `http://localhost:9090/webhook` | Base URL for webhook callbacks; gateway name appended automatically. |

## Phoenixd Gateway module ([payment-adapter-ln/payment-adapter-ln-phoenixd](../../payment-adapter-ln/payment-adapter-ln-phoenixd))

| Property | Default | Description |
| --- | --- | --- |
| `phoenixd.currency` | `sat` | Currency unit for invoices. |
| `phoenixd.expiration` | `86400` | Quote expiration in seconds. |
| `phoenixd.fee.percent` | `0.004` | Percentage fee applied. |
| `phoenixd.fee.fixed` | `4` | Fixed fee amount. |
| `phoenixd.expiry` | `60` | Invoice expiry in seconds. |
| `phoenixd.lnaddress` | `on` | Enable LN address support. |
| `phoenixd.payee` | `398ja@strike.me` | LN address for payouts (test). |
| `phoenixd.username` | *(empty)* | Basic auth username. |
| `phoenixd.password` | *(configured)* | Basic auth password. |
| `phoenixd.base_url` | `http://localhost:9740` | Base URL of phoenixd node. |
| `phoenixd.timeout` | `5000` | HTTP timeout in milliseconds. |
| `phoenixd.webhook_secret` | *(empty)* | Secret for verifying webhooks. |
| `webhook.base_url` | `http://localhost:9090/webhook` | Base URL for webhook callbacks; gateway name appended automatically. |

## Cash Gateway module ([payment-adapter-cash/payment-adapter-cash-gateway](../../payment-adapter-cash/payment-adapter-cash-gateway))

Configuration is read from `cash.properties` on the classpath.

| Property | Default | Description |
| --- | --- | --- |
| `cash.default.expiry` | `300` | Default invoice expiry in seconds (5 minutes). |
| `cash.default.relays` | `wss://relay.damus.io,wss://nos.lol` | Default relay URLs (comma-separated). |
| `cash.proof.length` | `4` | Proof code length (4–6 digits recommended). |
| `cash.subscriber.enabled` | `true` | Enable the Nostr event subscriber. |
| `cash.subscriber.expiry-check-interval` | `30000` | Interval for checking expired invoices (milliseconds). |

## Stripe Gateway module ([payment-adapter-stripe/payment-adapter-stripe-gateway](../../payment-adapter-stripe/payment-adapter-stripe-gateway))

| Property | Default | Description |
| --- | --- | --- |
| `stripe.enabled` | `false` | Enables the Stripe gateway beans in the REST service. |
| `stripe.secret-key` | *(required when enabled)* | Stripe secret API key used for Checkout Session operations. |
| `stripe.success-url` | *(required when enabled)* | Redirect URL after successful Checkout completion. |
| `stripe.cancel-url` | *(required when enabled)* | Redirect URL after Checkout cancellation. |
| `stripe.checkout-connect-timeout-ms` (`STRIPE_CHECKOUT_CONNECT_TIMEOUT_MS`) | `5000` | Connect timeout of the Checkout create call a buyer waits on (#274). |
| `stripe.checkout-read-timeout-ms` (`STRIPE_CHECKOUT_READ_TIMEOUT_MS`) | `10000` | Read timeout of that call. |
| `stripe.checkout-max-network-retries` (`STRIPE_CHECKOUT_MAX_NETWORK_RETRIES`) | `1` | SDK retries of that call (safe: same idempotency key). |
| `stripe.purchase-success-url` (`STRIPE_PURCHASE_SUCCESS_URL`) | *(falls back to `stripe.success-url`)* | Where a BUYER returns after paying for a coupon, e.g. `https://wallet.example/buy/done?session_id={CHECKOUT_SESSION_ID}`. |
| `stripe.purchase-cancel-url` (`STRIPE_PURCHASE_CANCEL_URL`) | *(falls back to `stripe.cancel-url`)* | Where a buyer returns after cancelling, e.g. `https://wallet.example/buy/cancelled`. |
| `stripe.default-currency` | `usd` | Default Stripe currency code in lowercase. |
| `stripe.allowed-currencies` | `usd` | Comma-separated allowlist of supported currencies. |
| `stripe.checkout-expiry-seconds` | `1800` | Checkout Session expiry in seconds. |
| `STRIPE_WEBHOOK_TOLERANCE_SECONDS` *(env var)* | `300` | Maximum accepted webhook signature timestamp age, in seconds. Read from the environment variable — the webhook signature verifier is instantiated via `ServiceLoader` (outside the Spring context), so it does **not** bind a Spring property. Paired with `STRIPE_WEBHOOK_SECRET`. |
| `stripe.webhook-endpoint-id` (`STRIPE_WEBHOOK_ENDPOINT_ID`) | *(empty)* | The payments endpoint's `we_...` id, for the [webhook subscription check](purchase-fulfilment.md#webhook-subscription-check-286). Not a secret. Empty: the check reports itself failed. |
| `stripe.min-amount-minor` | `1` | Minimum amount in minor currency units. |
| `stripe.max-amount-minor` | `2147483647` | Maximum amount in minor currency units. |

## Stripe instalment plans (`payment-adapter-stripe-webhook`, package `recurring`)

Off by default. Nothing about plans exists until `stripe.recurring.enabled=true`, and the endpoints also need
`stripe.enabled=true`. See [the spec](../specs/payment-adapter-stripe-recurring-spec.md).

| Property | Default | Meaning |
|---|---|---|
| `stripe.recurring.enabled` (`STRIPE_RECURRING_ENABLED`) | `false` | Turns on plan creation, the plan endpoints and the invoice event handlers. |
| `stripe.recurring.min-instalment-minor` (`STRIPE_RECURRING_MIN_INSTALMENT_MINOR`) | `2000` | Smallest instalment accepted, in minor units. Small instalments cost the stall more: Stripe's 20p per payment plus the 0.7% Billing fee (spec, Appendix A). |
| `stripe.recurring.max-instalments` (`STRIPE_RECURRING_MAX_INSTALMENTS`) | `24` | Longest fixed term of a monthly plan. A plan may also be open-ended. |
| `stripe.recurring.max-instalments-weekly` (`STRIPE_RECURRING_MAX_INSTALMENTS_WEEKLY`) | `52` | Longest fixed term of a weekly plan. |
| `stripe.recurring.max-instalments-yearly` (`STRIPE_RECURRING_MAX_INSTALMENTS_YEARLY`) | `5` | Longest fixed term of a yearly plan. |
| `stripe.recurring.require-controls` (`STRIPE_RECURRING_REQUIRE_CONTROLS`) | `true` | Refuse a plan on an account whose fee payer or loss collector Stripe has not reported. Relax only after confirming Stripe reports them for the accounts you connect. A reported "Imani pays" is refused either way. |

### Instalment reconciler

Runs with plans on (nothing to enable). It recovers instalments whose webhook was lost: for each running plan it lists
the subscription's paid invoices on the stall's account and records any the ledger lacks, through the same listener the
webhook uses and after a live read of the payment (so a refunded or disputed instalment is never made owed). It also
ends a fixed-term plan that has every instalment but whose subscription was never ended, which would otherwise be
charged once too many.

| Property | Default | Meaning |
|---|---|---|
| `stripe.recurring.reconcile.enabled` | `true` | Set `false` to stop the scheduled job. |
| `stripe.recurring.reconcile.interval` / `initial-delay` | `PT10M` / `PT3M` | Run frequency and first-run delay. |
| `stripe.recurring.reconcile.grace` | `PT10M` | An invoice paid more recently than this is left to its own webhook. |
| `stripe.recurring.reconcile.recheck` | `PT1H` | A plan checked within this window is skipped. Also the claim window between replicas, so an idle system makes no Stripe calls. |
| `stripe.recurring.reconcile.ended-lookback` | `P7D` | How long after a plan ends it is still read, for an instalment paid late. |
| `stripe.recurring.reconcile.batch-size` | `20` | Plans claimed per run. |
| `stripe.recurring.reconcile.max-stripe-calls-per-run` | `40` | Every Stripe read counts; shared with checkout's rate limit. A 429 ends the run. |

Look for these log lines: `[alert] stripe_reconcile recorded a paid instalment no webhook recorded` (a recovered
instalment: find out why its webhook was lost), `[alert] recurring_plan_final_cancel_repaired` (a final cancel was
repaired), and `[alert] instalment_reconcile could not match paid invoice` (an invoice the plan service refused; a person
must look).

**Enable these events on the PAYMENTS webhook endpoint** (the one with `charge.refunded` and
`checkout.session.completed`, `stripe.webhook-endpoint-id`), not the account-updates endpoint:
`invoice.paid`, `invoice.payment_failed`, `customer.subscription.deleted`. With plans on, the webhook subscription
check (#286) requires them; with plans off it does not.

## Stripe Connect module ([payment-adapter-stripe/payment-adapter-stripe-connect](../../payment-adapter-stripe/payment-adapter-stripe-connect))

| Property | Default | Description |
| --- | --- | --- |
| `stripe.connect.enabled` | `false` | Enables Stripe Connect-specific services. |
| `stripe.connect.refresh-url` | *(empty)* | Refresh URL used during Connect onboarding. |
| `stripe.connect.return-url` | *(empty)* | Return URL used during Connect onboarding. |
| `stripe.connect.webhook-endpoint-id` (`STRIPE_CONNECT_WEBHOOK_ENDPOINT_ID`) | *(empty)* | The Connect endpoint's `we_...` id, for the webhook subscription check (#286). |
| `stripe.connect.oauth.client-id` (`STRIPE_CONNECT_OAUTH_CLIENT_ID`) | *(empty)* | The platform's Connect `client_id` (`ca_...`), from Connect settings > Onboarding options > OAuth. Live and test mode have different ids. Not a secret. Connecting an existing account is off until this and the redirect URI are both set. |
| `stripe.connect.oauth.redirect-uri` (`STRIPE_CONNECT_OAUTH_REDIRECT_URI`) | *(empty)* | Where Stripe sends the browser after consent. Must exactly match a redirect URI registered in the same settings page, and be HTTPS in live mode. The adapter does not serve it: whoever does passes the `code` and `state` to `POST /api/v1/stripe/connect/oauth/complete`. |
| `stripe.connect.oauth.state-ttl` (`STRIPE_CONNECT_OAUTH_STATE_TTL`) | `PT10M` | How long a farm has to finish Stripe's screens before the connection request expires. |

## PayPal Gateway module ([payment-adapter-paypal/payment-adapter-paypal-gateway](../../payment-adapter-paypal/payment-adapter-paypal-gateway))

Card purchases on a stall's own PayPal account, mirroring Stripe ([ADR 0002](../adr/0002-paypal-card-purchases-mirror-stripe.md)).
One platform REST app acts for every onboarded seller through `PayPal-Auth-Assertion`, as one Stripe key
acts for every connected account through `Stripe-Account`. Every `paypal.*` property binds from its
`PAYPAL_*` environment variable.

| Property (`ENV`) | Default | Description |
| --- | --- | --- |
| `paypal.enabled` (`PAYPAL_ENABLED`) | `false` | Enables the PayPal gateway, checkout, webhook and reconciler beans. |
| `paypal.mode` (`PAYPAL_MODE`) | `sandbox` | `sandbox` or `live`. Picks the API host and every row's `livemode`. |
| `paypal.client-id` (`PAYPAL_CLIENT_ID`) | *(required when enabled)* | The platform REST app's client id. |
| `paypal.client-secret` (`PAYPAL_CLIENT_SECRET`) | *(required when enabled)* | Its secret. Never logged. |
| `paypal.bn-code` (`PAYPAL_BN_CODE`) | *(empty)* | Partner attribution code, sent as `PayPal-Partner-Attribution-Id`. |
| `paypal.success-url` (`PAYPAL_SUCCESS_URL`) | *(required when enabled)* | Where a buyer lands after paying; `{ORDER_ID}` is replaced with the order id (through the return route; without it PayPal appends `token=<order id>` instead). |
| `paypal.cancel-url` (`PAYPAL_CANCEL_URL`) | *(required when enabled)* | Where a buyer lands after cancelling, or when PayPal declines. |
| `paypal.return-base-url` (`PAYPAL_RETURN_BASE_URL`) | *(empty)* | This service's public origin. Set: PayPal returns the buyer to `/api/v1/checkout/paypal/return`, which captures at once, then redirects to the success URL. Empty: PayPal returns straight to the success URL and the capture happens on `CHECKOUT.ORDER.APPROVED` or by the reconciler. |
| `paypal.webhook-id` (`PAYPAL_WEBHOOK_ID`) | *(empty)* | The payments webhook's PayPal id (served at `/webhook/paypal`). Empty: every delivery is refused. |
| `paypal.environment` (`PAYPAL_ENVIRONMENT`) | `STRIPE_ENVIRONMENT` | Stamped on every order's `custom_id` as `<env>:<quote>`. **Must equal `stripe.environment`**; the PayPal webhook module refuses to start otherwise, because the ledger drops orders naming another deployment. |
| `paypal.brand-name` (`PAYPAL_BRAND_NAME`) | *(empty)* | Shown on PayPal's page; empty shows the seller's own name. |
| `paypal.default-currency` / `paypal.allowed-currencies` | `usd` / `usd` | As Stripe's. |
| `paypal.min-amount-minor` / `paypal.max-amount-minor` | `1` / `2147483647` | As Stripe's. The public ceiling is still `purchase.max-amount-minor`. |
| `paypal.connect-timeout-ms` / `paypal.read-timeout-ms` | `5000` / `10000` | Bounds on every PayPal call. |
| `paypal.max-network-retries` (`PAYPAL_MAX_NETWORK_RETRIES`) | `1` | Retries of a call PayPal could not answer (no answer, 5xx, 429). Safe: every write carries a `PayPal-Request-Id`. |
| `paypal.webhook-tolerance-seconds` (`PAYPAL_WEBHOOK_TOLERANCE_SECONDS`) | `0` | Maximum age of `PAYPAL-TRANSMISSION-TIME`; 0 is no bound (the event-id table already makes a replay a duplicate). |
| `paypal.api-base` (`PAYPAL_API_BASE`) | *(empty)* | Local mock only (`localhost`, `127.0.0.1`, `host.docker.internal`, `paypal-mock`); refused at startup otherwise, as `stripe.api-base`. |

Webhook signatures are verified locally against PayPal's certificate (fetched only over HTTPS from a
`paypal.com` host, chain-validated against the JDK trust store, and cached), never through PayPal's
`verify-webhook-signature` call.

## PayPal Connect module ([payment-adapter-paypal/payment-adapter-paypal-connect](../../payment-adapter-paypal/payment-adapter-paypal-connect))

Seller onboarding through Partner Referrals, at `/api/v1/paypal/connect` with the same routes and
bodies as `/api/v1/stripe/connect`.

| Property (`ENV`) | Default | Description |
| --- | --- | --- |
| `paypal.connect.enabled` (`PAYPAL_CONNECT_ENABLED`) | `false` | Enables onboarding. Requires `paypal.enabled=true` (refused at startup otherwise). |
| `paypal.connect.partner-merchant-id` (`PAYPAL_CONNECT_PARTNER_MERCHANT_ID`) | *(required when enabled)* | The platform's own PayPal merchant id, the `partner_id` of every seller-status read. |
| `paypal.connect.return-url` (`PAYPAL_CONNECT_RETURN_URL`) | *(empty)* | Where PayPal returns a seller after onboarding, when the request names none. |
| `paypal.connect.webhook-id` (`PAYPAL_CONNECT_WEBHOOK_ID`) | *(empty)* | The onboarding webhook's PayPal id (served at `/api/v1/paypal/connect/webhooks`). |
| `paypal.connect.products` | `EXPRESS_CHECKOUT` | Partner Referrals `products`. |
| `paypal.connect.features` | `PAYMENT,REFUND` | Permissions the seller grants. Must include `PAYMENT`. |

A stall takes PayPal cards (`charges_enabled`) only when PayPal reports `payments_receivable`,
`primary_email_confirmed`, and the `PAYMENT` permission granted to this platform's app.

### PayPal purchase reconciler

Exists only when `paypal.enabled=true`. Shares `purchase.reconcile.enabled`, `interval`,
`initial-delay`, `grace`, `max-stripe-calls-per-run` and the stale/parked settings with Stripe's;
its stale and parked sweeps read only `processor='paypal'` rows, from PayPal.

| Property (`ENV`) | Default | Description |
| --- | --- | --- |
| `paypal.reconcile.lookback` (`PAYPAL_RECONCILE_LOOKBACK`) | `P30D` | Open orders older than this are no longer looked at. |
| `paypal.reconcile.abandon-after` (`PAYPAL_RECONCILE_ABANDON_AFTER`) | `PT6H` | An order never approved this long is closed: nothing was paid. |
| `paypal.reconcile.batch-size` (`PAYPAL_RECONCILE_BATCH_SIZE`) | `50` | Open orders read per run. |

## Webhook module ([payment-adapter-webhook](../../payment-adapter-webhook))

| Property | Default | Description |
| --- | --- | --- |
| `gateway.api.base_url` | `http://localhost:8080` | REST API base URL for the webhook handler to call back. |

### Card checkout readiness gate (#293)

Before `POST /api/v1/checkout/purchase` creates a Stripe Checkout Session it asks purchase-issuer
`GET {base-url}/api/v1/card-sales/ready/<stall>`. Not ready answers **409** `{error, reason}`
(`not_enrolled`, `validity_never_expires`, `validity_outlives_delegation`, `validity_unknown`).
Unknown (timeout, PI down, non-200, bad body, URL unset) answers **503** with `Retry-After: 5`.
No session is created in either case.

| Property | Default | Description |
| --- | --- | --- |
| `purchase.issuer.base-url` (`PURCHASE_ISSUER_BASE_URL`) | `http://purchase-issuer:8791`; *(empty)* under a `prod`/`production` profile | purchase-issuer's internal URL. Empty fails every card checkout closed (503). |
| `purchase.issuer.timeout-ms` (`PURCHASE_ISSUER_TIMEOUT_MS`) | `2000` | Connect + request timeout for the readiness call. |
| `purchase.issuer.ready-cache-seconds` (`PURCHASE_ISSUER_READY_CACHE_SECONDS`) | `30` | Per-stall reuse of a ready / not-ready answer. Unknown is never cached. |

**Production go-live:** set `PURCHASE_ISSUER_BASE_URL` explicitly. The empty default only applies
when a `prod`/`production` Spring profile is active, and imani-deploy's `docker-compose.prod.yml`
sets no profile for payment-adapter. Without the variable a prod payment-adapter falls back to
`http://purchase-issuer:8791`, which still fails closed (503) while no service of that name exists on
the prod network, but should not be relied on.

### Mint Webhook Forwarder

| Property | Default | Description |
| --- | --- | --- |
| `mint.webhook.enabled` | `true` | Enable/disable payment forwarding to cashu-mint. |
| `mint.webhook.url` | `http://localhost:7777/webhook/payment` | Cashu-mint webhook endpoint URL. |
| `mint.webhook.secret` | *(empty)* | HMAC-SHA256 secret for signature authentication. |
| `mint.webhook.timeout-ms` | `5000` | HTTP request timeout in milliseconds. |
| `mint.webhook.retry.max-attempts` | `3` | Maximum retry attempts on failure. |
| `mint.webhook.retry.initial-delay-ms` | `1000` | Initial retry delay in milliseconds. |
| `mint.webhook.retry.multiplier` | `2.0` | Exponential backoff multiplier. |

### Stripe Webhook Runtime

| Variable | Default | Description |
| --- | --- | --- |
| `STRIPE_WEBHOOK_SECRET` | *(required for Stripe webhook processing)* | Secret used to verify the `Stripe-Signature` header. |
| `STRIPE_WEBHOOK_TOLERANCE_SECONDS` | `300` | Maximum accepted webhook signature age in seconds. |

### Stripe purchase reconciler (#272)

Exists only when `stripe.enabled=true`. See [purchase fulfilment](purchase-fulfilment.md#stripe-reconciler-272).

| Property (`ENV`) | Default | Description |
| --- | --- | --- |
| `purchase.reconcile.enabled` (`PURCHASE_RECONCILE_ENABLED`) | `true` | Switch the runs off without removing the bean. |
| `purchase.reconcile.interval` (`PURCHASE_RECONCILE_INTERVAL`) | `PT10M` | Delay between runs. |
| `purchase.reconcile.initial-delay` (`PURCHASE_RECONCILE_INITIAL_DELAY`) | `PT2M` | First run after boot. |
| `purchase.reconcile.overlap` (`PURCHASE_RECONCILE_OVERLAP`) | `PT1H` | Each pass re-reads this much before the cursor. |
| `purchase.reconcile.grace` (`PURCHASE_RECONCILE_GRACE`) | `PT10M` | Sessions younger than this are left to their webhook. |
| `purchase.reconcile.initial-lookback` (`PURCHASE_RECONCILE_INITIAL_LOOKBACK`) | `P30D` | Where a new account's cursor starts. |
| `purchase.reconcile.lease` (`PURCHASE_RECONCILE_LEASE`) | `PT15M` | How long one replica holds an account's cursor. |
| `purchase.reconcile.page-size` (`PURCHASE_RECONCILE_PAGE_SIZE`) | `100` | Sessions per list call (1-100). |
| `purchase.reconcile.max-stripe-calls-per-run` (`PURCHASE_RECONCILE_MAX_STRIPE_CALLS_PER_RUN`) | `50` | List pages and lost-row PaymentIntent reads (plus one re-read per newly recorded row); a 429 ends the run. |
| `purchase.reconcile.issuing-stale-after` (`PURCHASE_RECONCILE_ISSUING_STALE_AFTER`) | `PT1H` | ISSUING whose lease lapsed this long ago is parked `stale_issuing`. |
| `purchase.reconcile.issued-stale-after` (`PURCHASE_RECONCILE_ISSUED_STALE_AFTER`) | `P14D` | ISSUED older than this (the re-check horizon) is parked `stale_issued`. |
| `purchase.reconcile.stale-batch-size` (`PURCHASE_RECONCILE_STALE_BATCH_SIZE`) | `20` | Stale rows looked at per run; also the sweep's own Stripe-read budget. |
| `purchase.reconcile.parked-batch-size` (`PURCHASE_RECONCILE_PARKED_BATCH_SIZE`) | `20` | NEEDS_ATTENTION rows read live per run (#286), rotating; its own Stripe-read budget. |

Each module reads configuration from its properties file or environment variables. See the guides in [docs](../) for deployment details.
