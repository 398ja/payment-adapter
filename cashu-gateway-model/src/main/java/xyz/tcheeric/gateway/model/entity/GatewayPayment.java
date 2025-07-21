/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package xyz.tcheeric.gateway.model.entity;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import xyz.tcheeric.gateway.model.entity.enums.State;

import java.io.Serial;
import java.time.Instant;

/**
 *
 * @author eric
 */
@Data
@Entity(name = "payment")
@Table(indexes = {
        @Index(name = "idx_gatewaypayment_request", columnList = "request"),
        @Index(name = "idx_gatewaypayment_payment_id", columnList = "payment_id"),
        @Index(name = "idx_gatewaypayment_quote_id", columnList = "quote_id")
})
@NoArgsConstructor
@Getter
@Setter
// TODO - Create a static factory method to create a GatewayPayment with default values
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
}
