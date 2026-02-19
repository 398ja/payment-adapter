package xyz.tcheeric.payment.adapter.cash.nostr.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import xyz.tcheeric.payment.adapter.cash.nostr.event.CashEventKind;

import java.util.List;

/**
 * Builds NIP-01 REQ filters for cash payment event kinds (5200-5204).
 */
public class CashEventFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Build a filter for cash intent events (kind 5201) matching a specific ref.
     *
     * @param ref   the invoice reference to filter on
     * @param since optional Unix timestamp to filter events after
     * @return JSON filter string
     */
    public static String intentFilter(String ref, Long since) {
        return buildFilter(List.of(CashEventKind.CASH_INTENT), ref, since);
    }

    /**
     * Build a filter for cash cancel events (kind 5203) matching a specific ref.
     *
     * @param ref   the invoice reference
     * @param since optional Unix timestamp
     * @return JSON filter string
     */
    public static String cancelFilter(String ref, Long since) {
        return buildFilter(List.of(CashEventKind.CASH_CANCEL), ref, since);
    }

    /**
     * Build a filter for all customer-initiated events (intent + cancel) matching a ref.
     *
     * @param ref   the invoice reference
     * @param since optional Unix timestamp
     * @return JSON filter string
     */
    public static String customerEventFilter(String ref, Long since) {
        return buildFilter(List.of(CashEventKind.CASH_INTENT, CashEventKind.CASH_CANCEL), ref, since);
    }

    /**
     * Build a filter for all cash event kinds.
     *
     * @param since optional Unix timestamp
     * @return JSON filter string
     */
    public static String allCashEventsFilter(Long since) {
        return buildFilter(
                List.of(CashEventKind.CASH_INVOICE, CashEventKind.CASH_INTENT,
                        CashEventKind.CASH_RECEIPT, CashEventKind.CASH_CANCEL,
                        CashEventKind.CASH_DISPUTE),
                null, since);
    }

    private static String buildFilter(List<Integer> kinds, String ref, Long since) {
        ObjectNode filter = MAPPER.createObjectNode();

        ArrayNode kindsArray = filter.putArray("kinds");
        for (int kind : kinds) {
            kindsArray.add(kind);
        }

        if (ref != null) {
            ArrayNode refArray = filter.putArray("#ref");
            refArray.add(ref);
        }

        if (since != null) {
            filter.put("since", since);
        }

        try {
            return MAPPER.writeValueAsString(filter);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize filter", e);
        }
    }
}
