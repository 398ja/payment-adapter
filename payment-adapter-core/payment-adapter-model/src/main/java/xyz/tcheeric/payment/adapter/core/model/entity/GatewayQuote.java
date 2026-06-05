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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.Direction;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;

import java.io.Serial;
import java.time.Instant;
/**
 * JPA entity representing a quote handled by the gateway for minting or melting
 * operations. The {@link #create(String, String, Integer, String, String, Integer, String)}
 * factory method initializes a quote with default {@link State#PENDING} state and
 * {@link Direction#RECEIVE} direction.
 */
@Data
@Entity(name = "quote")
@Table(indexes = {
        @Index(name = "idx_gatewayquote_quote_id", columnList = "quote_id")
})
@NoArgsConstructor
public class GatewayQuote implements GatewayEntity {

    @Serial
    private static final long serialVersionUID = 296731830847697981L;
        
    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)    
    private Long id;
    private String quoteId;
    private String invoiceId;
    private Integer expiry;

    @Column(length = 1024)
    private String description;

    @Column(length = 1024)
    private String request;
    private Integer amount;
    private String unit;

    @Enumerated(EnumType.STRING)
    private State state;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    /**
     * Spec 041 (cashu-mint REQ-MINT-3) — when the row was created. Lets the
     * mint compute the absolute expiry instant from this + {@link #expiry}
     * (which is a TTL in seconds) and enforce {@code quote_expired} strictly.
     * Auto-populated by {@link #onCreate()} via JPA's {@code @PrePersist} so
     * existing factory callers don't need to set it. Nullable for backward
     * compatibility — rows persisted before this column was added decode
     * cleanly with null and the mint skips enforcement in that case.
     */
    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    /**
     * Factory method to create a {@link GatewayQuote} with default state and direction.
     *
     * @param quoteId     identifier assigned by the gateway
     * @param invoiceId   identifier of the underlying invoice
     * @param expiry      invoice expiry in seconds
     * @param description description of the quote
     * @param request     payment request string
     * @param amount      amount of the quote
     * @param unit        currency unit for the amount
     * @return a new {@link GatewayQuote} instance with state {@link State#PENDING} and
     * direction {@link Direction#RECEIVE}
     */
    public static GatewayQuote create(String quoteId, String invoiceId, Integer expiry,
                                      String description, String request, Integer amount,
                                      String unit) {
        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId(quoteId);
        quote.setInvoiceId(invoiceId);
        quote.setExpiry(expiry);
        quote.setDescription(description);
        quote.setRequest(request);
        quote.setAmount(amount);
        quote.setUnit(unit);
        quote.setState(State.PENDING);
        quote.setDirection(Direction.RECEIVE);
        return quote;
    }
}
