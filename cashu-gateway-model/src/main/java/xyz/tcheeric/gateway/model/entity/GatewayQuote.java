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
import xyz.tcheeric.gateway.model.entity.enums.Direction;
import xyz.tcheeric.gateway.model.entity.enums.State;

/**
 *
 * @author eric
 */
@Data
@Entity(name = "quote")
@Table(indexes = {
        @Index(name = "idx_gatewayquote_quote_id", columnList = "quote_id")
})
@NoArgsConstructor
@Getter
@Setter
public class GatewayQuote implements GatewayEntity {

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
}
