package xyz.tcheeric.payment.adapter.cash.gateway.controller;

import com.google.zxing.WriterException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import xyz.tcheeric.payment.adapter.cash.gateway.CashGateway;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.*;
import xyz.tcheeric.payment.adapter.cash.gateway.ratelimit.CashRateLimiter;
import xyz.tcheeric.payment.adapter.cash.gateway.subscriber.CashEventSubscriber;
import xyz.tcheeric.payment.adapter.cash.nostr.codec.NostrCashUri;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.CashReceipt;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST controller for cash payment operations.
 *
 * <p>Provides endpoints for creating cash invoices, checking status,
 * confirming receipt, cancelling invoices, and subscribing to status updates via SSE.
 */
@Slf4j
@RestController
@RequestMapping("/cash")
@Tag(name = "Cash Payments", description = "NIP-XX Cash Payment operations")
public class CashPaymentController {

    private final CashGateway cashGateway;
    private final CashRateLimiter rateLimiter;
    private final CashEventSubscriber eventSubscriber;
    private final ConcurrentHashMap<String, SseEmitter> sseEmitters = new ConcurrentHashMap<>();

    public CashPaymentController(CashGateway cashGateway,
                                 CashRateLimiter rateLimiter,
                                 CashEventSubscriber eventSubscriber) {
        this.cashGateway = cashGateway;
        this.rateLimiter = rateLimiter;
        this.eventSubscriber = eventSubscriber;
    }

    @Operation(summary = "Create a new cash invoice",
            description = "Creates a cash invoice, publishes to Nostr relays, and returns QR code data")
    @ApiResponse(responseCode = "201", description = "Invoice created successfully")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    @PostMapping("/invoice")
    public ResponseEntity<CashInvoiceResponse> createInvoice(
            @Valid @RequestBody CashInvoiceRequest request,
            HttpServletRequest httpRequest) {

        // Rate limiting
        String source = httpRequest.getRemoteAddr();
        if (!rateLimiter.tryAcquire(source)) {
            log.warn("Rate limit exceeded for invoice creation from {}", source);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

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

    @Operation(summary = "Get invoice status",
            description = "Returns the current status and details of a cash invoice")
    @ApiResponse(responseCode = "200", description = "Invoice found")
    @ApiResponse(responseCode = "404", description = "Invoice not found")
    @GetMapping("/invoice/{ref}")
    public ResponseEntity<CashInvoiceResponse> getInvoice(
            @Parameter(description = "Invoice reference") @PathVariable String ref) {

        log.debug("Getting cash invoice: ref={}", ref);

        CashInvoice invoice = cashGateway.getInvoiceByRef(ref);
        if (invoice == null) {
            log.debug("Invoice not found: ref={}", ref);
            return ResponseEntity.notFound().build();
        }

        CashInvoiceResponse response = CashInvoiceResponse.fromEntity(invoice);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get QR code as PNG image")
    @ApiResponse(responseCode = "200", description = "QR code PNG")
    @ApiResponse(responseCode = "404", description = "Invoice not found")
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
            return ResponseEntity.notFound().build();
        } catch (WriterException | IOException e) {
            log.error("Failed to generate QR code: ref={}", ref, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @Operation(summary = "Confirm cash received",
            description = "Confirms physical cash receipt and publishes receipt event to relays")
    @ApiResponse(responseCode = "200", description = "Receipt confirmed")
    @ApiResponse(responseCode = "404", description = "Invoice not found")
    @ApiResponse(responseCode = "409", description = "Invoice in invalid state for confirmation")
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
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Invalid state for confirm: ref={}, error={}", ref, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @Operation(summary = "Cancel a cash invoice",
            description = "Cancels an invoice and publishes cancel event to relays")
    @ApiResponse(responseCode = "204", description = "Invoice cancelled")
    @ApiResponse(responseCode = "404", description = "Invoice not found")
    @ApiResponse(responseCode = "409", description = "Invoice in invalid state for cancellation")
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
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Invalid state for cancel: ref={}, error={}", ref, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @Operation(summary = "Get QR payload URI",
            description = "Returns the nostr+cash:// URI for custom QR generation")
    @GetMapping("/invoice/{ref}/qr-payload")
    public ResponseEntity<String> getQrPayload(@PathVariable String ref) {
        try {
            NostrCashUri uri = cashGateway.getQrUri(ref);
            return ResponseEntity.ok(uri.encode());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Subscribe to invoice status updates via SSE",
            description = "Server-Sent Events stream for real-time invoice status changes")
    @GetMapping(value = "/invoice/{ref}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable String ref) {
        log.info("SSE subscription for invoice: ref={}", ref);

        SseEmitter emitter = new SseEmitter(300_000L); // 5 minute timeout
        String listenerId = "sse-" + ref + "-" + UUID.randomUUID().toString().substring(0, 8);

        sseEmitters.put(listenerId, emitter);

        // Register state change listener
        eventSubscriber.addStateChangeListener(listenerId, invoice -> {
            if (ref.equals(invoice.getRef())) {
                try {
                    CashInvoiceResponse response = CashInvoiceResponse.fromEntity(invoice);
                    emitter.send(SseEmitter.event()
                            .name("status")
                            .data(response));

                    // Complete emitter on terminal state
                    if (invoice.getStatus().name().equals("PAID") ||
                        invoice.getStatus().name().equals("EXPIRED") ||
                        invoice.getStatus().name().equals("CANCELLED")) {
                        emitter.complete();
                    }
                } catch (IOException e) {
                    log.debug("SSE send failed for ref={}: {}", ref, e.getMessage());
                    emitter.completeWithError(e);
                }
            }
        });

        emitter.onCompletion(() -> {
            eventSubscriber.removeStateChangeListener(listenerId);
            sseEmitters.remove(listenerId);
            log.debug("SSE completed for ref={}", ref);
        });

        emitter.onTimeout(() -> {
            eventSubscriber.removeStateChangeListener(listenerId);
            sseEmitters.remove(listenerId);
            log.debug("SSE timeout for ref={}", ref);
        });

        emitter.onError(e -> {
            eventSubscriber.removeStateChangeListener(listenerId);
            sseEmitters.remove(listenerId);
            log.debug("SSE error for ref={}: {}", ref, e.getMessage());
        });

        return emitter;
    }
}
