/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package xyz.tcheeric.gateway.client;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.gateway.model.entity.GatewayEntity;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;


/**
 *
 * @author eric
 * @param <T>
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractBaseClient<T extends GatewayEntity> {

    protected final RestTemplate restTemplate = new RestTemplate();
    private final String entity;
    private final Class entityClass;

    public T get(@NonNull Long id) {
        String url = getBaseUrl() + "/" + id;
        log.info("Sending request: {}", url);
        ResponseEntity<T> response = restTemplate.getForEntity(url, entityClass);
        log.info("Received response: {}", response.getBody());
        return response.getBody();
    }

    public T getByEntityId(@NonNull String entityId) {
        String url;
        if (entityClass.equals(GatewayPayment.class)) {
            url = getBaseUrl() + "/search/findByPaymentId?paymentId=" + entityId;
        } else if (entityClass.equals(GatewayQuote.class)) {
            url = getBaseUrl() + "/search/findByQuoteId?quoteId=" + entityId;
        } else {
            throw new IllegalArgumentException("Unsupported entity type: " + entityClass.getName());
        }
        log.info("Sending request: {}", url);
        ResponseEntity<T> response = restTemplate.getForEntity(url, entityClass);
        log.info("Received response: {}", response.getBody());
        return response.getBody();
    }

    public T create(@NonNull T entity) {
        HttpEntity<T> request = new HttpEntity<>(entity);
        ResponseEntity<T> response = restTemplate
                .exchange(getBaseUrl(), HttpMethod.POST, request, entityClass);
        return response.getBody(); // This should now include the id if the server returns the created object
    }

    public void delete(@NonNull Long id) {
        String url = getBaseUrl() + "/" + id;
        log.info("Sending request to delete entity with id: {}", id);
        restTemplate.delete(url);
        log.info("Deleted entity with id: {}", id);
    }

    protected String getBaseUrl() {
        return "http://localhost:8080/" + entity;
    }
}
