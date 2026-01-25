package xyz.tcheeric.payment.adapter.cash.gateway;

import com.google.zxing.WriterException;
import lombok.extern.slf4j.Slf4j;
import nostr.base.PublicKey;
import nostr.id.Identity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;
import xyz.tcheeric.payment.adapter.cash.nostr.codec.NostrCashUri;
import xyz.tcheeric.payment.adapter.cash.nostr.event.CashCancelEvent;
import xyz.tcheeric.payment.adapter.cash.nostr.event.CashInvoiceEvent;
import xyz.tcheeric.payment.adapter.cash.nostr.event.CashReceiptEvent;
import xyz.tcheeric.payment.adapter.core.common.Gateway;
import xyz.tcheeric.payment.adapter.core.common.PaymentType;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.CashReceipt;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>The standard Gateway methods (createMintQuote, pay, etc.) are not
 * applicable to cash payments and will throw {@link UnsupportedOperationException}.
 */
@Slf4j
@Component
@PropertySource(value = "classpath:cash.properties", ignoreResourceNotFound = true)
public class CashGateway implements Gateway {

    private static final String GATEWAY_NAME = "nostr-cash";
    private static final String GATEWAY_ID = "cash";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${cash.default.expiry:300}")
    private int defaultExpiry;

    @Value("${cash.default.relays:wss://relay.damus.io,wss://nos.lol}")
    private String defaultRelays;

    @Value("${cash.proof.length:4}")
    private int proofCodeLength;

    // In-memory store for invoices (in production, use database via CashInvoiceRepository)
    private final Map<String, CashInvoice> invoices = new ConcurrentHashMap<>();
    private final Map<String, CashReceipt> receipts = new ConcurrentHashMap<>();

    private final QRCodeGenerator qrCodeGenerator;

    /**
     * Get the QR code generator instance.
     */
    public QRCodeGenerator qrCodeGenerator() {
        return qrCodeGenerator;
    }

    public CashGateway() {
        loadPropertiesIfNeeded();
        this.qrCodeGenerator = new QRCodeGenerator();
    }

    public CashGateway(QRCodeGenerator qrCodeGenerator) {
        loadPropertiesIfNeeded();
        this.qrCodeGenerator = qrCodeGenerator;
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
        log.info("Creating cash invoice: amount={}, fiat={}, memo={}", amount, fiat, memo);

        // Generate ephemeral keypair
        Identity ephemeralIdentity = Identity.generateRandomIdentity();
        PublicKey publicKey = ephemeralIdentity.getPublicKey();
        String pubkeyHex = publicKey.toString();
        String privkeyHex = ephemeralIdentity.getPrivateKey().toString();

        // Generate unique reference
        String ref = generateRef();

        // Generate proof code
        String proofCode = generateProofCode();

        // Calculate expiry
        int ttl = ttlSeconds != null ? ttlSeconds : defaultExpiry;
        Instant expiresAt = Instant.now().plusSeconds(ttl);

        // Use provided relays or defaults
        List<String> relays = relayUrls != null && !relayUrls.isEmpty()
                ? relayUrls
                : Arrays.asList(defaultRelays.split(","));
        String relayUrlsStr = String.join(",", relays);

        // Create the invoice entity
        CashInvoice invoice = CashInvoice.create(
                ref, pubkeyHex, privkeyHex, amount, fiat, memo, expiresAt, relayUrlsStr);
        invoice.setProofCode(proofCode);

        // Build the Nostr event
        CashInvoiceEvent.Builder eventBuilder = CashInvoiceEvent.builder()
                .merchantPubkey(publicKey)
                .amount(amount)
                .ref(ref)
                .expiresAt(expiresAt)
                .relays(relays);

        if (fiat != null && !fiat.isEmpty()) {
            eventBuilder.fiat(fiat);
        }
        if (memo != null && !memo.isEmpty()) {
            eventBuilder.memo(memo);
        }

        CashInvoiceEvent event = eventBuilder.build();

        // TODO: Publish event to relays using nostr-java client
        // For now, we'll just store the invoice locally
        // String eventId = nostrClient.publish(event.getEvent());
        // invoice.setEventId(eventId);

        invoice.setStatus(CashInvoiceStatus.PENDING);
        invoice.setPublishedAt(Instant.now());

        // Store the invoice
        invoices.put(ref, invoice);

        log.info("Created cash invoice: ref={}, expiresAt={}", ref, expiresAt);
        return invoice;
    }

    /**
     * Get invoice status by reference.
     *
     * @param ref the invoice reference
     * @return the CashInvoice or null if not found
     */
    public CashInvoice getInvoiceByRef(String ref) {
        CashInvoice invoice = invoices.get(ref);
        if (invoice != null) {
            checkAndUpdateExpiry(invoice);
        }
        return invoice;
    }

    /**
     * Generate QR code URI for an invoice.
     *
     * @param ref the invoice reference
     * @return NostrCashUri for QR encoding
     */
    public NostrCashUri getQrUri(String ref) {
        CashInvoice invoice = getInvoiceByRef(ref);
        if (invoice == null) {
            throw new IllegalArgumentException("Invoice not found: " + ref);
        }

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
     *
     * @param ref the invoice reference
     * @return PNG image bytes
     * @throws WriterException if QR encoding fails
     * @throws IOException if image writing fails
     */
    public byte[] getQrCodePng(String ref) throws WriterException, IOException {
        NostrCashUri uri = getQrUri(ref);
        return qrCodeGenerator.generatePng(uri);
    }

    /**
     * Confirm cash received for an invoice.
     *
     * @param ref            the invoice reference
     * @param amountReceived actual amount received
     * @return the CashReceipt
     */
    public CashReceipt confirmCashReceipt(String ref, Integer amountReceived) {
        log.info("Confirming cash receipt: ref={}, amountReceived={}", ref, amountReceived);

        CashInvoice invoice = invoices.get(ref);
        if (invoice == null) {
            throw new IllegalArgumentException("Invoice not found: " + ref);
        }

        if (invoice.getStatus() == CashInvoiceStatus.PAID) {
            throw new IllegalStateException("Invoice already paid: " + ref);
        }

        if (invoice.getStatus() == CashInvoiceStatus.EXPIRED ||
            invoice.getStatus() == CashInvoiceStatus.CANCELLED) {
            throw new IllegalStateException("Invoice is " + invoice.getStatus() + ": " + ref);
        }

        // Use invoice amount if not specified
        int received = amountReceived != null ? amountReceived : invoice.getAmount();

        // Create receipt
        // TODO: Build and publish CashReceiptEvent
        String receiptEventId = UUID.randomUUID().toString();
        CashReceipt receipt = CashReceipt.create(ref, received, receiptEventId);

        // Update invoice status
        invoice.setStatus(CashInvoiceStatus.PAID);
        invoice.setPaidAt(Instant.now());

        // Store receipt
        receipts.put(ref, receipt);

        log.info("Cash receipt confirmed: ref={}, amountReceived={}", ref, received);
        return receipt;
    }

    /**
     * Cancel a cash invoice.
     *
     * @param ref    the invoice reference
     * @param reason cancellation reason code
     */
    public void cancelCashInvoice(String ref, String reason) {
        log.info("Cancelling cash invoice: ref={}, reason={}", ref, reason);

        CashInvoice invoice = invoices.get(ref);
        if (invoice == null) {
            throw new IllegalArgumentException("Invoice not found: " + ref);
        }

        if (invoice.getStatus() == CashInvoiceStatus.PAID) {
            throw new IllegalStateException("Cannot cancel paid invoice: " + ref);
        }

        // TODO: Build and publish CashCancelEvent

        invoice.setStatus(CashInvoiceStatus.CANCELLED);
        invoice.setCancelReason(reason != null ? reason : "cash.cancelled_by_merchant");

        log.info("Cash invoice cancelled: ref={}", ref);
    }

    /**
     * Mark invoice as having received an intent (kind 5201).
     *
     * @param ref           the invoice reference
     * @param customerPubkey customer's ephemeral public key
     * @param proof         optional proof code from customer
     */
    public void recordIntent(String ref, String customerPubkey, String proof) {
        log.info("Recording intent: ref={}, customerPubkey={}", ref, customerPubkey);

        CashInvoice invoice = invoices.get(ref);
        if (invoice == null) {
            throw new IllegalArgumentException("Invoice not found: " + ref);
        }

        if (invoice.getStatus() != CashInvoiceStatus.PENDING) {
            log.warn("Intent received for non-pending invoice: ref={}, status={}", ref, invoice.getStatus());
            return;
        }

        invoice.setStatus(CashInvoiceStatus.INTENT_RECEIVED);
        invoice.setIntentReceivedAt(Instant.now());

        log.info("Intent recorded: ref={}", ref);
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
        // For cash, return the QR URI as the "request"
        CashInvoice invoice = invoices.get(quoteId);
        if (invoice != null) {
            return getQrUri(quoteId).encode();
        }
        return null;
    }

    @Override
    public boolean checkPaymentStatus(String ref) {
        CashInvoice invoice = invoices.get(ref);
        if (invoice != null) {
            checkAndUpdateExpiry(invoice);
            return invoice.getStatus() == CashInvoiceStatus.PAID;
        }
        return false;
    }

    @Override
    public String getPaymentPreimage(String ref) {
        // Cash payments don't have preimages; return receipt event ID
        CashReceipt receipt = receipts.get(ref);
        return receipt != null ? receipt.getEventId() : null;
    }

    @Override
    public String pay(String request) {
        throw new UnsupportedOperationException("Cash payments require physical cash exchange. Use confirmCashReceipt() after receiving cash.");
    }

    @Override
    public Integer getAmount(String ref) {
        CashInvoice invoice = invoices.get(ref);
        return invoice != null ? invoice.getAmount() : null;
    }

    @Override
    public Integer getPaymentExpiry(String ref) {
        CashInvoice invoice = invoices.get(ref);
        if (invoice != null) {
            long secondsUntilExpiry = invoice.getExpiresAt().getEpochSecond() - Instant.now().getEpochSecond();
            return (int) Math.max(0, secondsUntilExpiry);
        }
        return null;
    }

    @Override
    public Integer getFeeReserve(String quoteId) {
        // Cash payments have no fees
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

    // ========== Private helper methods ==========

    /**
     * Generate a unique reference (6-12 hex characters).
     */
    private String generateRef() {
        byte[] bytes = new byte[6];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.substring(0, 6); // Use first 6 hex chars
    }

    /**
     * Generate a proof code (4-6 digits).
     */
    private String generateProofCode() {
        int max = (int) Math.pow(10, proofCodeLength);
        int code = SECURE_RANDOM.nextInt(max);
        return String.format("%0" + proofCodeLength + "d", code);
    }

    /**
     * Check and update invoice status if expired.
     */
    private void checkAndUpdateExpiry(CashInvoice invoice) {
        if (invoice.getStatus() == CashInvoiceStatus.PENDING ||
            invoice.getStatus() == CashInvoiceStatus.INTENT_RECEIVED) {
            if (Instant.now().isAfter(invoice.getExpiresAt())) {
                invoice.setStatus(CashInvoiceStatus.EXPIRED);
                invoice.setCancelReason("cash.expired");
                log.info("Invoice expired: ref={}", invoice.getRef());
            }
        }
    }

    /**
     * Load properties from classpath when not in Spring context.
     */
    private void loadPropertiesIfNeeded() {
        if (defaultExpiry == 0) {
            defaultExpiry = 300;
        }
        if (defaultRelays == null || defaultRelays.isBlank()) {
            defaultRelays = "wss://relay.damus.io,wss://nos.lol";
        }
        if (proofCodeLength == 0) {
            proofCodeLength = 4;
        }

        try (InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("cash.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);

                String exp = p.getProperty("cash.default.expiry");
                if (exp != null) {
                    try { defaultExpiry = Integer.parseInt(exp); } catch (NumberFormatException ignored) {}
                }
                String relays = p.getProperty("cash.default.relays");
                if (relays != null && !relays.isBlank()) {
                    defaultRelays = relays;
                }
                String proof = p.getProperty("cash.proof.length");
                if (proof != null) {
                    try { proofCodeLength = Integer.parseInt(proof); } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load cash.properties: {}", e.getMessage());
        }
    }
}
