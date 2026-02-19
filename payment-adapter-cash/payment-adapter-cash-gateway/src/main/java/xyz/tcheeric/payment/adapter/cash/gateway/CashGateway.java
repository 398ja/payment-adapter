package xyz.tcheeric.payment.adapter.cash.gateway;

import com.google.zxing.WriterException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;
import xyz.tcheeric.payment.adapter.cash.nostr.codec.NostrCashUri;
import xyz.tcheeric.payment.adapter.cash.gateway.service.CashInvoiceService;
import xyz.tcheeric.payment.adapter.cash.gateway.state.CashInvoiceStateMachine;
import xyz.tcheeric.payment.adapter.core.common.Gateway;
import xyz.tcheeric.payment.adapter.core.common.PaymentType;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.CashReceipt;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Gateway implementation for in-person cash payments using Nostr as the
 * coordination layer (NIP-XX Cash Payments specification).
 *
 * <p>Cash payments have a different flow from Lightning Network payments:
 * <ul>
 *   <li>Merchant creates an invoice (kind 5200) with ephemeral keys</li>
 *   <li>Customer signals intent (kind 5201)</li>
 *   <li>Physical cash is exchanged</li>
 *   <li>Merchant confirms receipt (kind 5202)</li>
 * </ul>
 *
 * <p>Delegates business logic to {@link CashInvoiceService}.
 * The standard Gateway methods (createMintQuote, pay, etc.) are not
 * applicable to cash payments and will throw {@link UnsupportedOperationException}.
 */
@Slf4j
@Component
@PropertySource(value = "classpath:cash.properties", ignoreResourceNotFound = true)
public class CashGateway implements Gateway {

    private static final String GATEWAY_NAME = "nostr-cash";
    private static final String GATEWAY_ID = "cash";

    private final QRCodeGenerator qrCodeGenerator;
    private final CashInvoiceService invoiceService;

    @Autowired
    public CashGateway(CashInvoiceService invoiceService) {
        this.qrCodeGenerator = new QRCodeGenerator();
        this.invoiceService = invoiceService;
    }

    public CashGateway(CashInvoiceService invoiceService, QRCodeGenerator qrCodeGenerator) {
        this.qrCodeGenerator = qrCodeGenerator;
        this.invoiceService = invoiceService;
    }

    /**
     * Get the QR code generator instance.
     */
    public QRCodeGenerator qrCodeGenerator() {
        return qrCodeGenerator;
    }

    // ========== Cash-specific methods ==========

    /**
     * Create a new cash invoice.
     *
     * @param amount     amount in minor currency units
     * @param fiat       ISO 4217 currency code (null for satoshis)
     * @param memo       optional description
     * @param ttlSeconds time-to-live in seconds (null for default)
     * @param relayUrls  relay URLs (null for defaults)
     * @return the created CashInvoice
     */
    public CashInvoice createCashInvoice(Integer amount, String fiat, String memo,
                                         Integer ttlSeconds, List<String> relayUrls) {
        return invoiceService.createInvoice(amount, fiat, memo, ttlSeconds, relayUrls);
    }

    /**
     * Get invoice status by reference.
     *
     * @param ref the invoice reference
     * @return the CashInvoice or null if not found
     */
    public CashInvoice getInvoiceByRef(String ref) {
        return invoiceService.getInvoiceByRef(ref).orElse(null);
    }

    /**
     * Generate QR code URI for an invoice.
     *
     * @param ref the invoice reference
     * @return NostrCashUri for QR encoding
     */
    public NostrCashUri getQrUri(String ref) {
        CashInvoice invoice = invoiceService.getInvoiceByRef(ref)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + ref));

        List<String> relays = Arrays.asList(invoice.getRelayUrls().split(","));

        return NostrCashUri.builder()
                .merchantPubkey(invoice.getEphemeralPubkey())
                .ref(invoice.getRef())
                .amount(invoice.getAmount())
                .fiat(invoice.getFiat())
                .expiresAt(invoice.getExpiresAt().getEpochSecond())
                .relays(relays)
                .encryptionMode("nip44")
                .version("0.2")
                .build();
    }

    /**
     * Generate QR code as PNG bytes.
     */
    public byte[] getQrCodePng(String ref) throws WriterException, IOException {
        NostrCashUri uri = getQrUri(ref);
        return qrCodeGenerator.generatePng(uri);
    }

    /**
     * Confirm cash received for an invoice.
     */
    public CashReceipt confirmCashReceipt(String ref, Integer amountReceived) {
        return invoiceService.confirmReceipt(ref, amountReceived);
    }

    /**
     * Cancel a cash invoice.
     */
    public void cancelCashInvoice(String ref, String reason) {
        invoiceService.cancelInvoice(ref, reason);
    }

    /**
     * Mark invoice as having received an intent (kind 5201).
     */
    public void recordIntent(String ref, String customerPubkey, String proof) {
        invoiceService.recordIntent(ref, customerPubkey, proof);
    }

    /**
     * Get all active invoices.
     */
    public Collection<CashInvoice> getAllInvoices() {
        return invoiceService.findActiveInvoices();
    }

    // ========== Gateway interface methods (most not applicable for cash) ==========

    @Override
    public String createMintQuote(Integer amount, String description) {
        throw new UnsupportedOperationException("Cash payments don't use mint quotes. Use createCashInvoice() instead.");
    }

    @Override
    public String createMeltQuote(Integer amount, String request, String description) {
        throw new UnsupportedOperationException("Cash payments don't use melt quotes.");
    }

    @Override
    public String createMeltQuote(String request) {
        throw new UnsupportedOperationException("Cash payments don't use melt quotes.");
    }

    @Override
    public String getRequest(String quoteId) {
        Optional<CashInvoice> opt = invoiceService.getInvoiceByRef(quoteId);
        if (opt.isPresent()) {
            return getQrUri(quoteId).encode();
        }
        return null;
    }

    @Override
    public boolean checkPaymentStatus(String ref) {
        Optional<CashInvoice> opt = invoiceService.getInvoiceByRef(ref);
        return opt.map(invoice -> invoice.getStatus() == CashInvoiceStatus.PAID).orElse(false);
    }

    @Override
    public String getPaymentPreimage(String ref) {
        return invoiceService.getReceiptByRef(ref)
                .map(CashReceipt::getEventId)
                .orElse(null);
    }

    @Override
    public String pay(String request) {
        throw new UnsupportedOperationException("Cash payments require physical cash exchange. Use confirmCashReceipt() after receiving cash.");
    }

    @Override
    public Integer getAmount(String ref) {
        return invoiceService.getInvoiceByRef(ref)
                .map(CashInvoice::getAmount)
                .orElse(null);
    }

    @Override
    public Integer getPaymentExpiry(String ref) {
        return invoiceService.getInvoiceByRef(ref)
                .map(invoice -> {
                    long secondsUntilExpiry = invoice.getExpiresAt().getEpochSecond() - Instant.now().getEpochSecond();
                    return (int) Math.max(0, secondsUntilExpiry);
                })
                .orElse(null);
    }

    @Override
    public Integer getFeeReserve(String quoteId) {
        return 0;
    }

    @Override
    public String getName() {
        return GATEWAY_NAME;
    }

    @Override
    public PaymentType getPaymentType() {
        return PaymentType.CASH;
    }

    @Override
    public String getGatewayId() {
        return GATEWAY_ID;
    }
}
