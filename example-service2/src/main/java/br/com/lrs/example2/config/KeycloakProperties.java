package br.com.lrs.example2.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * example-service2 is an INTERNAL service — it only accepts tokens from gateway-realm.
 *
 * Users never talk directly to this service; only example-service does.
 * This guarantees that all traffic entering this service has already been
 * validated and token-exchanged at the Gateway boundary.
 *
 * In a real system you would use mTLS or a service mesh to further restrict
 * access to internal services. For this portfolio demo, checking the issuer is sufficient.
 */
@Data
@Component
@ConfigurationProperties(prefix = "keycloak")
public class KeycloakProperties {

    private List<String> issuers = new ArrayList<>();
}
