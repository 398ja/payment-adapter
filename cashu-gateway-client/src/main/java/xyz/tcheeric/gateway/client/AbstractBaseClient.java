package xyz.tcheeric.gateway.client;

import lombok.Getter;
import lombok.NonNull;
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
public abstract class AbstractBaseClient<T extends GatewayEntity> {

    @Getter
    protected final RestTemplate restTemplate = new RestTemplate();
    private final String entity;
    private final Class<T> entityClass;
    private final String baseUrl;

    protected AbstractBaseClient(@NonNull String entity, @NonNull Class<T> entityClass) {
        this(null, entity, entityClass);
    }

    protected AbstractBaseClient(String explicitBaseUrl, @NonNull String entity, @NonNull Class<T> entityClass) {
        this.entity = entity;
        this.entityClass = entityClass;
        this.baseUrl = GatewayClientConfig.resolveBaseUrl(explicitBaseUrl);
        log.debug("Resolved gateway API base URL: {}", this.baseUrl);
    }

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
        String url = getBaseUrl();
        log.info("[{}] POST create start: url={}", entity, url);
        HttpEntity<T> request = new HttpEntity<>(entity);
        ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.POST, request, entityClass);
        log.info("[{}] POST create success: body={}", entity, response.getBody());
        return response.getBody();
    }

    public void delete(@NonNull Long id) {
        String url = getBaseUrl() + "/" + id;
        log.info("[{}] DELETE start: id={}, url={}", entity, id, url);
        restTemplate.delete(url);
        log.info("[{}] DELETE success: id={}", entity, id);
    }

    protected String getBaseUrl() {
        return baseUrl + "/" + entity;
    }
}
