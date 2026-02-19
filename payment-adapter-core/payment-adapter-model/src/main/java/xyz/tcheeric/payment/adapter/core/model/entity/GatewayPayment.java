/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package xyz.tcheeric.payment.adapter.core.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.time.Instant;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.core.common.PaymentType;
/**
 * JPA entity representing a payment processed by the gateway. Use
 * {@link #create(String, String, String, String, Integer, Integer, Integer, String, String)}
 * to instantiate a payment with default {@link State#PENDING} state.
 */
@Data
@Entity(name = "payment")
@Table(indexes = {
        @Index(name = "idx_gatewaypayment_request", columnList = "request"),
        @Index(name = "idx_gatewaypayment_payment_id", columnList = "payment_id"),
        @Index(name = "idx_gatewaypayment_quote_id", columnList = "quote_id"),
        @Index(name = "idx_gatewaypayment_idempotency_key", columnList = "idempotency_key", unique = true)
})
@NoArgsConstructor
public class GatewayPayment implements GatewayEntity {

    @Serial
    private static final long serialVersionUID = 1089714744450886412L;

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)    
    private Long id;

    @Column(length = 1024)
    private String request;
    private String paymentId;
    private String quoteId;

    @Enumerated(EnumType.STRING)
    private State state;

    private Instant paidDate;
    private Instant confirmedDate;
    private String sourceCurrency;
    private Integer amount;
    private Integer lightningNetworkFee;
    private Integer totalAmount;

    @Column(length = 1024)
    private String paymentHash;

    @Column(length = 1024)
    private String paymentPreimage;

    /**
     * The type of payment (LN, cash, credit card, etc.)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type")
    private PaymentType paymentType;

    /**
     * The identifier of the gateway that processed this payment
     */
    @Column(name = "gateway_id")
    private String gatewayId;

    /**
     * Unique key for webhook idempotency - prevents duplicate processing
     */
    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    /**
     * Timestamp when the webhook was processed for this payment
     */
    @Column(name = "webhook_processed_at")
    private Instant webhookProcessedAt;

    /**
     * Factory method to create a {@link GatewayPayment} with a default state of
     * {@link State#PENDING}.
     *
     * @param request             payment request string
     * @param paymentId           identifier for the payment
     * @param quoteId             associated quote identifier
     * @param sourceCurrency      currency of the original request
     * @param amount              amount of the payment
     * @param lightningNetworkFee fee paid on the lightning network
     * @param totalAmount         total amount including fees
     * @param paymentHash         hash of the payment
     * @param paymentPreimage     preimage of the payment
     * @return a new {@link GatewayPayment} instance with state {@link State#PENDING}
     */
    public static GatewayPayment create(String request, String paymentId, String quoteId,
                                        String sourceCurrency, Integer amount,
                                        Integer lightningNetworkFee, Integer totalAmount,
                                        String paymentHash, String paymentPreimage) {
        GatewayPayment payment = new GatewayPayment();
        payment.setRequest(request);
        payment.setPaymentId(paymentId);
        payment.setQuoteId(quoteId);
        payment.setSourceCurrency(sourceCurrency);
        payment.setAmount(amount);
        payment.setLightningNetworkFee(lightningNetworkFee);
        payment.setTotalAmount(totalAmount);
        payment.setPaymentHash(paymentHash);
        payment.setPaymentPreimage(paymentPreimage);
        payment.setState(State.PENDING);
        return payment;
    }
}
