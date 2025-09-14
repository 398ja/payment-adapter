package xyz.tcheeric.gateway.rest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.rest.core.config.RepositoryRestConfiguration;
import org.springframework.data.rest.webmvc.config.RepositoryRestConfigurer;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;

/**
 * Explicitly configures HTTP method exposure for Spring Data REST repositories.
 * Ensures collection POST (create) and item write methods are enabled for
 * GatewayQuote and GatewayPayment, avoiding read-only exposure in some setups.
 */
@Configuration
public class RestExposureConfig implements RepositoryRestConfigurer {

    @Override
    public void configureRepositoryRestConfiguration(RepositoryRestConfiguration config, CorsRegistry cors) {
        // Expose identifiers (useful for clients)
        config.exposeIdsFor(GatewayQuote.class, GatewayPayment.class);

        // Return entity body on create/update so clients can rely on response payloads
        config.setReturnBodyOnCreate(true);
        config.setReturnBodyOnUpdate(true);

        var exposure = config.getExposureConfiguration();

        exposure.forDomainType(GatewayQuote.class)
                .withCollectionExposure((metadata, http) -> http
                        .enable(HttpMethod.GET)
                        .enable(HttpMethod.POST))
                .withItemExposure((metadata, http) -> http
                        .enable(HttpMethod.GET)
                        .enable(HttpMethod.PUT)
                        .enable(HttpMethod.PATCH)
                        .enable(HttpMethod.DELETE));

        exposure.forDomainType(GatewayPayment.class)
                .withCollectionExposure((metadata, http) -> http
                        .enable(HttpMethod.GET)
                        .enable(HttpMethod.POST))
                .withItemExposure((metadata, http) -> http
                        .enable(HttpMethod.GET)
                        .enable(HttpMethod.PUT)
                        .enable(HttpMethod.PATCH)
                        .enable(HttpMethod.DELETE));
    }
}
