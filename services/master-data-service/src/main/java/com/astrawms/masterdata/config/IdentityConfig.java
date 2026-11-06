package com.astrawms.masterdata.config;

import com.astrawms.masterdata.platform.KeycloakIdentityProvisioner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The identity provider: the platform API creates tenant administrators in it (ADR-0030 A), and a tenant's administrator
 * manages the tenant's accounts through it (ADR-0031). One bean serves both, as {@code IdentityProvisioner} and
 * {@code TenantUsers}.
 */
@Configuration
public class IdentityConfig {

    @Bean
    KeycloakIdentityProvisioner identityProvisioner(@Value("${astra.identity.keycloak.url}") String url,
                                                    @Value("${astra.identity.keycloak.realm}") String realm,
                                                    @Value("${astra.identity.keycloak.client-id}") String clientId,
                                                    @Value("${astra.identity.keycloak.client-secret}") String clientSecret) {
        return new KeycloakIdentityProvisioner(url, realm, clientId, clientSecret);
    }
}
