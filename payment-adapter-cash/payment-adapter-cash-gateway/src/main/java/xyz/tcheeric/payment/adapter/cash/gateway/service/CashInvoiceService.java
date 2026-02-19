package xyz.tcheeric.payment.adapter.cash.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import nostr.base.PublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import xyz.tcheeric.payment.adapter.cash.nostr.client.NostrClient;
import xyz.tcheeric.payment.adapter.cash.nostr.crypto.EphemeralKeyPair;
import xyz.tcheeric.payment.adapter.cash.nostr.crypto.Nip44EncryptionService;
import xyz.tcheeric.payment.adapter.cash.nostr.event.CashCancelEvent;
import xyz.tcheeric.payment.adapter.cash.nostr.event.CashInvoiceEvent;
import xyz.tcheeric.payment.adapter.cash.nostr.event.CashReceiptEvent;
import xyz.tcheeric.payment.adapter.cash.gateway.state.CashInvoiceStateMachine;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.CashReceipt;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.CashInvoiceRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.CashReceiptRepository;
import xyz.tcheeric.payment.adapter.webhook.forwarder.GatewayWebhookForwarder;
import xyz.tcheeric.payment.adapter.webhook.forwarder.PaymentNotification;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service layer for cash invoice business logic.
 * Encapsulates database access, relay publishing, and encryption.
 */
@Slf4j
@Service
public class CashInvoiceService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${cash.default.expiry:300}")
    private int defaultExpiry;

    @Value("${cash.default.relays:wss://relay.imani.casa,wss://relay.398ja.xyz}")
    private String defaultRelays;

    @Value("${cash.proof.length:4}")
    private int proofCodeLength;

    private final CashInvoiceRepository invoiceRepository;
    private final CashReceiptRepository receiptRepository;
    private final CashInvoiceStateMachine stateMachine;
    private final NostrClient nostrClient;
    private final Nip44EncryptionService encryptionService;
    private final GatewayWebhookForwarder webhookForwarder;

    // Callback for subscribing new invoices to relay events
    private volatile java.util.function.Consumer<CashInvoice> invoiceCreatedCallback;

    public CashInvoiceService(CashInvoiceRepository invoiceRepository,
                              CashReceiptRepository receiptRepository,
                              CashInvoiceStateMachine stateMachine,
                              NostrClient nostrClient,
                              Nip44EncryptionService encryptionService,
                              GatewayWebhookForwarder webhookForwarder) {
        this.invoiceRepository = invoiceRepository;
        this.receiptRepository = receiptRepository;
        this.stateMachine = stateMachine;
        this.nostrClient = nostrClient;
        this.encryptionService = encryptionService;
        this.webhookForwarder = webhookForwarder;
    }

    /**
     * Set a callback to be notified when a new invoice is created.
     */
    public void setInvoiceCreatedCallback(java.util.function.Consumer<CashInvoice> callback) {
        this.invoiceCreatedCallback = callback;
    }

    /**
     * Create a new cash invoice, persist it, and publish to relays.
     */
    public CashInvoice createInvoice(Integer amount, String fiat, String memo,
                                     Integer ttlSeconds, List<String> relayUrls) {
        log.info("Creating cash invoice: amount={}, fiat={}, memo={}", amount, fiat, memo);

        // Generate ephemeral keypair
        EphemeralKeyPair keyPair = EphemeralKeyPair.generate();

        // Generate unique reference (retry if collision)
        String ref = generateUniqueRef();

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
                ref, keyPair.getPublicKeyHex(), keyPair.getPrivateKeyHex(),
                amount, fiat, memo, expiresAt, relayUrlsStr);
        invoice.setProofCode(proofCode);

        // Build and publish the Nostr event
        CashInvoiceEvent.Builder eventBuilder = CashInvoiceEvent.builder()
                .merchantPubkey(keyPair.getPublicKey())
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

        // Encrypt payload and publish
        try {
            String payloadJson = event.serializePayload();
            String encrypted = encryptionService.encrypt(
                    payloadJson, keyPair.getPrivateKeyHex(), keyPair.getPublicKey());
            event.getEvent().setContent(encrypted);
        } catch (JsonProcessingException e) {
            log.warn("Failed to encrypt invoice payload, publishing unencrypted: {}", e.getMessage());
        }

        nostrClient.publish(event.getEvent(), relays);
        String eventId = event.getEvent().getId();
        if (eventId != null) {
            invoice.setEventId(eventId);
        }

        stateMachine.transition(invoice, CashInvoiceStatus.PENDING);

        // Persist
        invoice = invoiceRepository.save(invoice);

        // Subscribe to relay events for this invoice
        if (invoiceCreatedCallback != null) {
            try {
                invoiceCreatedCallback.accept(invoice);
            } catch (Exception e) {
                log.warn("Invoice created callback failed for ref={}: {}", ref, e.getMessage());
            }
        }

        log.info("Created cash invoice: ref={}, expiresAt={}", ref, expiresAt);
        return invoice;
    }

    /**
     * Get invoice by reference.
     */
    public Optional<CashInvoice> getInvoiceByRef(String ref) {
        Optional<CashInvoice> opt = invoiceRepository.findByRef(ref);
        opt.ifPresent(invoice -> {
            if (stateMachine.tryExpire(invoice)) {
                invoiceRepository.save(invoice);
            }
        });
        return opt;
    }

    /**
     * Confirm cash receipt for an invoice.
     */
    public CashReceipt confirmReceipt(String ref, Integer amountReceived) {
        log.info("Confirming cash receipt: ref={}, amountReceived={}", ref, amountReceived);

        CashInvoice invoice = invoiceRepository.findByRef(ref)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + ref));

        if (!stateMachine.canConfirm(invoice.getStatus())) {
            throw new IllegalStateException("Invoice is " + invoice.getStatus() + ": " + ref);
        }

        int received = amountReceived != null ? amountReceived : invoice.getAmount();

        // Build and publish receipt event
        EphemeralKeyPair keyPair = EphemeralKeyPair.fromHex(
                invoice.getEphemeralPubkey(), invoice.getEphemeralPrivkey());

        // Address receipt to customer pubkey if available, otherwise fall back to merchant key
        PublicKey recipientPubkey = keyPair.getPublicKey();
        if (invoice.getCustomerPubkey() != null && !invoice.getCustomerPubkey().isBlank()) {
            try {
                recipientPubkey = new PublicKey(invoice.getCustomerPubkey());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid customer pubkey for ref={}, falling back to merchant key: {}",
                        ref, e.getMessage());
            }
        }

        CashReceiptEvent receiptEvent = CashReceiptEvent.builder()
                .merchantPubkey(keyPair.getPublicKey())
                .customerPubkey(recipientPubkey)
                .ref(ref)
                .amountReceived(received)
                .build();

        try {
            String payloadJson = receiptEvent.serializePayload();
            String encrypted = encryptionService.encrypt(
                    payloadJson, keyPair.getPrivateKeyHex(), recipientPubkey);
            receiptEvent.getEvent().setContent(encrypted);
        } catch (JsonProcessingException e) {
            log.warn("Failed to encrypt receipt payload: {}", e.getMessage());
        }

        List<String> relays = Arrays.asList(invoice.getRelayUrls().split(","));
        nostrClient.publish(receiptEvent.getEvent(), relays);

        String receiptEventId = receiptEvent.getEvent().getId();
        if (receiptEventId == null) {
            receiptEventId = UUID.randomUUID().toString();
        }

        CashReceipt receipt = CashReceipt.create(ref, received, receiptEventId);

        // Update invoice status
        stateMachine.transition(invoice, CashInvoiceStatus.PAID);
        invoiceRepository.save(invoice);

        // Dispose ephemeral key
        keyPair.dispose();

        // Save receipt
        receipt = receiptRepository.save(receipt);

        // Notify gateway via webhook to trigger voucher minting saga
        try {
            PaymentNotification notification = PaymentNotification.forCash(
                    ref, received, invoice.getFiat(),
                    receipt.getEventId(), invoice.getCustomerPubkey());
            boolean sent = webhookForwarder.notifyPaymentConfirmed(notification);
            if (!sent) {
                log.warn("Failed to forward cash payment webhook for ref={}", ref);
            }
        } catch (Exception e) {
            log.error("Error forwarding cash payment webhook for ref={}: {}", ref, e.getMessage(), e);
        }

        log.info("Cash receipt confirmed: ref={}, amountReceived={}", ref, received);
        return receipt;
    }

    /**
     * Cancel a cash invoice.
     */
    public void cancelInvoice(String ref, String reason) {
        log.info("Cancelling cash invoice: ref={}, reason={}", ref, reason);

        CashInvoice invoice = invoiceRepository.findByRef(ref)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + ref));

        if (!stateMachine.canCancel(invoice.getStatus())) {
            throw new IllegalStateException("Cannot cancel invoice in state " + invoice.getStatus() + ": " + ref);
        }

        // Build and publish cancel event
        EphemeralKeyPair keyPair = EphemeralKeyPair.fromHex(
                invoice.getEphemeralPubkey(), invoice.getEphemeralPrivkey());

        CashCancelEvent cancelEvent = CashCancelEvent.builder()
                .senderPubkey(keyPair.getPublicKey())
                .ref(ref)
                .reason(reason != null ? reason : "cash.cancelled_by_merchant")
                .build();

        List<String> relays = Arrays.asList(invoice.getRelayUrls().split(","));
        nostrClient.publish(cancelEvent.getEvent(), relays);

        invoice.setCancelReason(reason != null ? reason : "cash.cancelled_by_merchant");
        stateMachine.transition(invoice, CashInvoiceStatus.CANCELLED);
        invoiceRepository.save(invoice);

        // Dispose ephemeral key
        keyPair.dispose();

        log.info("Cash invoice cancelled: ref={}", ref);
    }

    /**
     * Record an intent for an invoice.
     */
    public void recordIntent(String ref, String customerPubkey, String proof) {
        log.info("Recording intent: ref={}, customerPubkey={}", ref, customerPubkey);

        CashInvoice invoice = invoiceRepository.findByRef(ref)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + ref));

        if (!stateMachine.canReceiveIntent(invoice.getStatus())) {
            log.warn("Intent received for non-pending invoice: ref={}, status={}", ref, invoice.getStatus());
            throw new IllegalStateException("Invoice is " + invoice.getStatus() + ": " + ref);
        }

        if (customerPubkey != null && !customerPubkey.isBlank()) {
            invoice.setCustomerPubkey(customerPubkey);
        }

        stateMachine.transition(invoice, CashInvoiceStatus.INTENT_RECEIVED);
        invoiceRepository.save(invoice);

        log.info("Intent recorded: ref={}", ref);
    }

    /**
     * Check if a ref already exists.
     */
    public boolean existsByRef(String ref) {
        return invoiceRepository.existsByRef(ref);
    }

    /**
     * Get receipt by ref.
     */
    public Optional<CashReceipt> getReceiptByRef(String ref) {
        return receiptRepository.findByRef(ref);
    }

    /**
     * Find all active (non-terminal) invoices.
     */
    public List<CashInvoice> findActiveInvoices() {
        return invoiceRepository.findByStatusIn(
                List.of(CashInvoiceStatus.CREATED, CashInvoiceStatus.PENDING, CashInvoiceStatus.INTENT_RECEIVED));
    }

    /**
     * Find expired invoices.
     */
    public List<CashInvoice> findExpiredInvoices() {
        return invoiceRepository.findExpiredInvoices(
                List.of(CashInvoiceStatus.PENDING, CashInvoiceStatus.INTENT_RECEIVED),
                Instant.now());
    }

    private String generateUniqueRef() {
        for (int i = 0; i < 10; i++) {
            String ref = generateRef();
            if (!invoiceRepository.existsByRef(ref)) {
                return ref;
            }
        }
        throw new IllegalStateException("Failed to generate unique ref after 10 attempts");
    }

    private String generateRef() {
        byte[] bytes = new byte[6];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.substring(0, 6);
    }

    private String generateProofCode() {
        int max = (int) Math.pow(10, proofCodeLength);
        int code = SECURE_RANDOM.nextInt(max);
        return String.format("%0" + proofCodeLength + "d", code);
    }
}
