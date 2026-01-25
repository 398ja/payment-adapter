package xyz.tcheeric.payment.adapter.cash.gateway.controller;

import com.google.zxing.WriterException;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import xyz.tcheeric.payment.adapter.cash.gateway.CashGateway;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.*;
import xyz.tcheeric.payment.adapter.cash.nostr.codec.NostrCashUri;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.CashReceipt;

import java.io.IOException;

/**
 * REST controller for cash payment operations.
 *
 * <p>Provides endpoints for creating cash invoices, checking status,
 * confirming receipt, and cancelling invoices. Follows NIP-XX Cash
 * Payments specification.
 *
 * @see <a href="https://github.com/nostr-protocol/nips">Nostr NIPs</a>
 */
@Slf4j
@RestController
@RequestMapping("/cash")
public class CashPaymentController {

    private final CashGateway cashGateway;

    public CashPaymentController(CashGateway cashGateway) {
        this.cashGateway = cashGateway;
    }

    /**
     * Create a new cash invoice.
     *
     * @param request invoice creation parameters
     * @return created invoice with QR code data
     */
    @PostMapping("/invoice")
    public ResponseEntity<CashInvoiceResponse> createInvoice(@Valid @RequestBody CashInvoiceRequest request) {
        log.info("Creating cash invoice: amount={}, fiat={}", request.getAmount(), request.getFiat());

        try {
            CashInvoice invoice = cashGateway.createCashInvoice(
                    request.getAmount(),
                    request.getFiat(),
                    request.getMemo(),
                    request.getTtlSeconds(),
                    request.getRelayUrls()
            );

            NostrCashUri qrUri = cashGateway.getQrUri(invoice.getRef());
            String qrPayload = qrUri.encode();
            String qrDataUri = cashGateway.qrCodeGenerator().generateDataUri(qrUri);

            CashInvoiceResponse response = CashInvoiceResponse.fromEntityWithQr(invoice, qrPayload, qrDataUri);

            log.info("Cash invoice created: ref={}", invoice.getRef());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (WriterException | IOException e) {
            log.error("Failed to generate QR code", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get invoice status by reference.
     *
     * @param ref the invoice reference
     * @return invoice details or 404 if not found
     */
    @GetMapping("/invoice/{ref}")
    public ResponseEntity<CashInvoiceResponse> getInvoice(@PathVariable String ref) {
        log.debug("Getting cash invoice: ref={}", ref);

        CashInvoice invoice = cashGateway.getInvoiceByRef(ref);
        if (invoice == null) {
            log.debug("Invoice not found: ref={}", ref);
            return ResponseEntity.notFound().build();
        }

        CashInvoiceResponse response = CashInvoiceResponse.fromEntity(invoice);
        return ResponseEntity.ok(response);
    }

    /**
     * Get QR code as PNG image.
     *
     * @param ref the invoice reference
     * @return PNG image bytes
     */
    @GetMapping(value = "/invoice/{ref}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getQrCode(@PathVariable String ref) {
        log.debug("Getting QR code: ref={}", ref);

        try {
            byte[] png = cashGateway.getQrCodePng(ref);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setContentLength(png.length);
            headers.setCacheControl("max-age=300");

            return new ResponseEntity<>(png, headers, HttpStatus.OK);

        } catch (IllegalArgumentException e) {
            log.debug("Invoice not found for QR: ref={}", ref);
            return ResponseEntity.notFound().build();
        } catch (WriterException | IOException e) {
            log.error("Failed to generate QR code: ref={}", ref, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Confirm cash received for an invoice.
     *
     * @param ref     the invoice reference
     * @param request confirmation details
     * @return receipt confirmation or error
     */
    @PostMapping("/invoice/{ref}/confirm")
    public ResponseEntity<CashReceiptResponse> confirmReceipt(
            @PathVariable String ref,
            @Valid @RequestBody(required = false) CashConfirmRequest request) {

        log.info("Confirming cash receipt: ref={}", ref);

        try {
            Integer amountReceived = request != null ? request.getAmountReceived() : null;
            CashReceipt receipt = cashGateway.confirmCashReceipt(ref, amountReceived);

            CashReceiptResponse response = CashReceiptResponse.fromEntity(receipt);

            log.info("Cash receipt confirmed: ref={}", ref);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.debug("Invoice not found for confirm: ref={}", ref);
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Invalid state for confirm: ref={}, error={}", ref, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    /**
     * Cancel a cash invoice.
     *
     * @param ref     the invoice reference
     * @param request cancellation details
     * @return 204 No Content on success
     */
    @PostMapping("/invoice/{ref}/cancel")
    public ResponseEntity<Void> cancelInvoice(
            @PathVariable String ref,
            @Valid @RequestBody(required = false) CashCancelRequest request) {

        log.info("Cancelling cash invoice: ref={}", ref);

        try {
            String reason = request != null ? request.getReason() : "cash.merchant_request";
            cashGateway.cancelCashInvoice(ref, reason);

            log.info("Cash invoice cancelled: ref={}", ref);
            return ResponseEntity.noContent().build();

        } catch (IllegalArgumentException e) {
            log.debug("Invoice not found for cancel: ref={}", ref);
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Invalid state for cancel: ref={}, error={}", ref, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    /**
     * Get QR payload URI (for custom QR generation).
     *
     * @param ref the invoice reference
     * @return QR payload string
     */
    @GetMapping("/invoice/{ref}/qr-payload")
    public ResponseEntity<String> getQrPayload(@PathVariable String ref) {
        log.debug("Getting QR payload: ref={}", ref);

        try {
            NostrCashUri uri = cashGateway.getQrUri(ref);
            return ResponseEntity.ok(uri.encode());

        } catch (IllegalArgumentException e) {
            log.debug("Invoice not found for QR payload: ref={}", ref);
            return ResponseEntity.notFound().build();
        }
    }
}
