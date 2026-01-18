package xyz.tcheeric.gateway.rest.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.rest.repository.PaymentRepository;

/**
 * REST controller for payment/quote lookup operations.
 *
 * <p>This controller provides an alias for quote lookups at the /payment path,
 * for compatibility with frontends that expect Spring Data REST style endpoints.
 */
@RestController
@RequestMapping("/payment")
public class PaymentController {

    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentRepository paymentRepository;

    public PaymentController(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /**
     * Gets a quote/payment by quote ID via search endpoint.
     *
     * <p>This is an alias for /quote/search/findByQuoteId for frontend compatibility.
     *
     * @param quoteId the quote identifier
     * @return the quote if found
     */
    @GetMapping("/search/findByQuoteId")
    public ResponseEntity<GatewayPayment> findByQuoteId(@RequestParam("quoteId") String quoteId) {
        LOGGER.debug("payment_search_by_quote_id quote_id={}", quoteId);

        return paymentRepository.findByQuoteId(quoteId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
