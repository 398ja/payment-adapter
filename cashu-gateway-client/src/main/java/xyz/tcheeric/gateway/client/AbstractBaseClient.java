package xyz.tcheeric.gateway.client;

import lombok.Getter;
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
 * Base REST client providing common CRUD operations for gateway entities.
 *
 * @param <T> type of gateway entity handled by the client
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractBaseClient<T extends GatewayEntity> {

    @Getter
    protected final RestTemplate restTemplate = new RestTemplate();
    private final String entity;
    private final Class<T> entityClass;

    public T get(@NonNull Long id) {
        String url = getBaseUrl() + "/" + id;
        log.info("[{}] GET byId start: id={}, url={}", entity, id, url);
        ResponseEntity<T> response = restTemplate.getForEntity(url, entityClass);
        log.info("[{}] GET byId success: id={}, body={}", entity, id, response.getBody());
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
        log.info("[{}] GET byEntityId start: entityId={}, url={}", entity, entityId, url);
        ResponseEntity<T> response = restTemplate.getForEntity(url, entityClass);
        log.info("[{}] GET byEntityId success: entityId={}, body={}", entity, entityId, response.getBody());
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
        log.info("[{}] DELETE start: id={}, url={}", entity, id, url);
        restTemplate.delete(url);
        log.info("[{}] DELETE success: id={}", entity, id);
    }

    protected String getBaseUrl() {
        String baseUrl = System.getProperty("gateway.api.base_url", "http://localhost:8080");
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        return baseUrl + entity;
    }
}
