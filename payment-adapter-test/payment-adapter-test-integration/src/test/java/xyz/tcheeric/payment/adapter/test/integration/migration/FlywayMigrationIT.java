package xyz.tcheeric.payment.adapter.test.integration.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import xyz.tcheeric.payment.adapter.test.integration.BasePostgresIT;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationIT extends BasePostgresIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Verifies that all Flyway migrations have been applied successfully
    @Test
    void allMigrations_areAppliedSuccessfully() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank");

        assertThat(history).isNotEmpty();
        assertThat(history).allSatisfy(row ->
                assertThat(row.get("success")).isEqualTo(true));
    }

    // Verifies that the cash_invoice table exists with expected columns
    @Test
    void cashInvoiceTable_hasExpectedColumns() {
        List<String> columns = getColumnNames("cash_invoice");

        assertThat(columns).containsExactlyInAnyOrder(
                "id", "ref", "ephemeral_pubkey", "ephemeral_privkey", "amount",
                "fiat", "memo", "proof_code", "expires_at", "relay_urls",
                "status", "event_id", "created_at", "published_at",
                "intent_received_at", "paid_at", "cancel_reason", "customer_pubkey");
    }

    // Verifies that the cash_intent table exists with expected columns
    @Test
    void cashIntentTable_hasExpectedColumns() {
        List<String> columns = getColumnNames("cash_intent");

        assertThat(columns).containsExactlyInAnyOrder(
                "id", "ref", "customer_pubkey", "proof", "customer_timestamp",
                "event_id", "received_at", "processed");
    }

    // Verifies that the cash_receipt table exists with expected columns
    @Test
    void cashReceiptTable_hasExpectedColumns() {
        List<String> columns = getColumnNames("cash_receipt");

        assertThat(columns).containsExactlyInAnyOrder(
                "id", "ref", "status", "amount_received", "confirmed_at",
                "event_id", "published_at");
    }

    // Verifies that the Stripe payment reference table exists with the expected columns.
    @Test
    void stripePaymentReferenceTable_hasExpectedColumns() {
        List<String> columns = getColumnNames("stripe_payment_reference");

        assertThat(columns).containsExactlyInAnyOrder(
                "id", "quote_id", "checkout_session_id", "payment_intent_id", "charge_id",
                "connected_account_id", "stripe_status", "livemode", "last_event_id",
                "refunded_amount_minor", "disputed", "created_at", "updated_at");
    }

    // Verifies that the processed Stripe webhook event table exists with lifecycle-tracking columns.
    @Test
    void processedStripeWebhookEventTable_hasExpectedColumns() {
        List<String> columns = getColumnNames("processed_stripe_webhook_event");

        assertThat(columns).containsExactlyInAnyOrder(
                "event_id", "event_type", "payload_hash", "livemode",
                "received_at", "processing_status", "processing_started_at", "processed_at", "last_error");
    }

    // Verifies that the connected Stripe account table stores the merchant-facing Connect status fields.
    @Test
    void connectedStripeAccountTable_hasExpectedColumns() {
        List<String> columns = getColumnNames("connected_stripe_account");

        assertThat(columns).containsExactlyInAnyOrder(
                "id", "merchant_pubkey", "stripe_account_id", "onboarding_complete",
                "charges_enabled", "payouts_enabled", "details_submitted", "default_currency",
                "requirements_due", "disabled_reason", "country", "email",
                "connection_type", "account_type", "fee_payer", "losses_payer", "dashboard_type",
                "created_at", "updated_at");
    }

    // Verifies that an account recorded before V24 reads back as one Imani created, not as an OAuth connection.
    @Test
    void connectedStripeAccount_connectionTypeDefaultsToCreated() {
        String defaultValue = jdbcTemplate.queryForObject(
                "SELECT column_default FROM information_schema.columns"
                        + " WHERE table_name = 'connected_stripe_account' AND column_name = 'connection_type'",
                String.class);

        assertThat(defaultValue).contains("CREATED");
    }

    // Verifies the plan table and the link from a purchase row to its plan (V25).
    @Test
    void recurringPlanTableAndPurchaseLink_exist() {
        assertThat(getColumnNames("recurring_plan")).containsExactlyInAnyOrder(
                "id", "connected_account_id", "stall_pubkey", "buyer_pubkey", "amount_minor", "currency",
                "interval_unit", "total_instalments", "status", "checkout_session_id", "subscription_id",
                "livemode", "cancel_requested_at", "reconciled_at", "created_at", "updated_at");
        assertThat(getColumnNames("stripe_purchase")).contains("recurring_plan_id");
        assertThat(indexExists("idx_stripe_purchase_recurring_plan")).isTrue();
    }

    // Verifies the OAuth state table: one single-use row per flow, keyed by the opaque state.
    @Test
    void stripeOAuthStateTable_hasExpectedColumnsAndKey() {
        assertThat(getColumnNames("stripe_oauth_state")).containsExactlyInAnyOrder(
                "state", "merchant_pubkey", "created_at", "expires_at", "consumed_at");
        Integer primaryKeyColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints"
                        + " WHERE table_name = 'stripe_oauth_state' AND constraint_type = 'PRIMARY KEY'",
                Integer.class);
        assertThat(primaryKeyColumns).isEqualTo(1);
    }

    // Verifies that unique indexes exist on cash_invoice.ref
    @Test
    void cashInvoice_hasUniqueRefIndex() {
        boolean hasIndex = indexExists("idx_cashinvoice_ref");
        assertThat(hasIndex).isTrue();
    }

    // Verifies that indexes exist on cash_intent.event_id
    @Test
    void cashIntent_hasEventIdIndex() {
        boolean hasIndex = indexExists("idx_cashintent_event_id");
        assertThat(hasIndex).isTrue();
    }

    // Verifies that the ref column on cash_invoice is NOT NULL
    @Test
    void cashInvoice_refColumn_isNotNull() {
        String isNullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns " +
                "WHERE table_name = 'cash_invoice' AND column_name = 'ref'",
                String.class);
        assertThat(isNullable).isEqualTo("NO");
    }

    private List<String> getColumnNames(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position",
                String.class, tableName);
    }

    private boolean indexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = ?",
                Integer.class, indexName);
        return count != null && count > 0;
    }
}
