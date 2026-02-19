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
