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
import xyz.tcheeric.payment.adapter.core.model.entity.enums.Direction;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;

import java.io.Serial;
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
