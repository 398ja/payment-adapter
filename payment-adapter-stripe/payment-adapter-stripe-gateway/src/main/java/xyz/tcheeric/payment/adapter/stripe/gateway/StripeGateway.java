package xyz.tcheeric.payment.adapter.stripe.gateway;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.entities.annotation.Supports;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.core.common.Gateway;
import xyz.tcheeric.payment.adapter.core.common.PaymentType;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayPayment;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePaymentReferenceRepository;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePaymentReference;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;

@Slf4j
@Component
@RequiredArgsConstructor
@Supports(PaymentMethod.CREDIT_CARD)
@ConditionalOnProperty(prefix = "stripe", name = "enabled", havingValue = "true")
public class StripeGateway implements Gateway {

    private static final String GATEWAY_ID = "stripe";

    private final StripeCheckoutService checkoutService;
    private final QuoteClient quoteClient;
    private final PaymentClient paymentClient;
    private final StripePaymentReferenceRepository paymentReferenceRepository;
    private final StripeGatewayProperties properties;

    @Override
    public String createMintQuote(Integer amount, String description) {
        String quoteId = UUID.randomUUID().toString();
        StripeCheckoutSession checkoutSession = checkoutService.createCheckoutSession(quoteId, amount, description);
        GatewayQuote quote = createQuote(quoteId, amount, description, checkoutSession);
        GatewayPayment payment = createPendingPayment(quote, checkoutSession);

        GatewayQuote persistedQuote = quoteClient.create(quote);
        GatewayPayment persistedPayment = paymentClient.create(payment);
        paymentReferenceRepository.save(createPaymentReference(persistedQuote, checkoutSession));

        log.info("Created Stripe mint quote: quoteId={}, paymentId={}, checkoutSessionId={}",
                persistedQuote.getQuoteId(), persistedPayment.getId(), checkoutSession.getSessionId());
        return persistedQuote.getQuoteId();
    }

    @Override
    public String createMeltQuote(Integer amount, String request, String description) {
        throw new UnsupportedOperationException("Stripe gateway does not support createMeltQuote(amount, request, description)");
    }

    @Override
    public String createMeltQuote(String request) {
        throw new UnsupportedOperationException("Stripe gateway does not support createMeltQuote(request)");
    }

    @Override
    public String getRequest(String quoteId) {
        return quoteClient.getByEntityId(quoteId).getRequest();
    }

    // Spec 041 REQ-MINT-3 — expose the persisted quote creation time so callers
    // can enforce the strict `createdAt + expiry` window. Without this override
    // Stripe/card quotes fall back to the default null and skip enforcement,
    // unlike Phoenixd. Mirrors PhoenixdGateway#getCreatedAt.
    @Override
    public java.time.Instant getCreatedAt(String quoteId) {
        GatewayQuote quote = quoteClient.getByEntityId(quoteId);
        return quote != null ? quote.getCreatedAt() : null;
    }

    @Override
    public boolean checkPaymentStatus(String quoteId) {
        GatewayQuote quote = quoteClient.getByEntityId(quoteId);
        if (quote != null && State.PAID.equals(quote.getState())) {
            return true;
        }

        GatewayPayment payment = paymentClient.getByQuoteId(quoteId);
        if (payment != null && (State.PAID.equals(payment.getState()) || State.CONFIRMED.equals(payment.getState()))) {
            return true;
        }

        return paymentReferenceRepository.findByQuoteId(quoteId)
                .map(reference -> checkoutService.retrieveCheckoutSession(reference.getCheckoutSessionId()))
                .map(this::isCheckoutPaid)
                .orElse(false);
    }

    @Override
    public String getPaymentPreimage(String quoteId) {
        GatewayPayment payment = paymentClient.getByQuoteId(quoteId);
        return payment == null ? null : payment.getPaymentId();
    }

    @Override
    public String pay(String request) {
        throw new UnsupportedOperationException("Stripe gateway does not support pay(request)");
    }

    @Override
    public Integer getAmount(String quoteId) {
        return quoteClient.getByEntityId(quoteId).getAmount();
    }

    @Override
    public Integer getPaymentExpiry(String quoteId) {
        return quoteClient.getByEntityId(quoteId).getExpiry();
    }

    @Override
    public Integer getFeeReserve(String request) {
        return 0;
    }

    @Override
    public String getName() {
        return GATEWAY_ID;
    }

    @Override
    public PaymentType getPaymentType() {
        return PaymentType.CREDIT_CARD;
    }

    @Override
    public String getGatewayId() {
        return GATEWAY_ID;
    }

    private GatewayQuote createQuote(String quoteId, Integer amount, String description, StripeCheckoutSession checkoutSession) {
        int expiry = calculateExpirySeconds(checkoutSession.getExpiresAtEpochSeconds());
        return GatewayQuote.create(
                quoteId,
                checkoutSession.getSessionId(),
                expiry,
                description,
                checkoutSession.getSessionUrl(),
                amount,
                properties.getDefaultCurrency()
        );
    }

    private GatewayPayment createPendingPayment(GatewayQuote quote, StripeCheckoutSession checkoutSession) {
        GatewayPayment payment = GatewayPayment.create(
                checkoutSession.getSessionUrl(),
                null,
                quote.getQuoteId(),
                properties.getDefaultCurrency(),
                quote.getAmount(),
                0,
                quote.getAmount(),
                null,
                null
        );
        payment.setPaymentType(PaymentType.CREDIT_CARD);
        payment.setGatewayId(GATEWAY_ID);
        payment.setIdempotencyKey(checkoutService.buildIdempotencyKey(quote.getQuoteId()));
        return payment;
    }

    private StripePaymentReference createPaymentReference(GatewayQuote quote, StripeCheckoutSession checkoutSession) {
        StripePaymentReference reference = new StripePaymentReference();
        reference.setQuoteId(quote.getQuoteId());
        reference.setCheckoutSessionId(checkoutSession.getSessionId());
        reference.setPaymentIntentId(checkoutSession.getPaymentIntentId());
        reference.setStripeStatus(checkoutSession.getPaymentStatus());
        reference.setLivemode(checkoutSession.isLivemode());
        reference.setCreatedAt(Instant.now());
        reference.setUpdatedAt(Instant.now());
        return reference;
    }

    private boolean isCheckoutPaid(StripeCheckoutSession checkoutSession) {
        return "paid".equalsIgnoreCase(checkoutSession.getPaymentStatus())
                || "complete".equalsIgnoreCase(checkoutSession.getStatus());
    }

    private int calculateExpirySeconds(long expiresAtEpochSeconds) {
        long expiresInSeconds = expiresAtEpochSeconds - Instant.now().getEpochSecond();
        return (int) Math.max(expiresInSeconds, 0);
    }
}
