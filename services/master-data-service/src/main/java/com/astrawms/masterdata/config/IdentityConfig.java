package com.astrawms.masterdata.config;

import com.astrawms.masterdata.platform.IdentityProvisioner;
import com.astrawms.masterdata.platform.KeycloakIdentityProvisioner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The identity provider the platform API creates tenant administrators in (ADR-0030 A). */
@Configuration
public class IdentityConfig {

    @Bean
    IdentityProvisioner identityProvisioner(@Value("${astra.identity.keycloak.url}") String url,
                                            @Value("${astra.identity.keycloak.realm}") String realm,
                                            @Value("${astra.identity.keycloak.client-id}") String clientId,
                                            @Value("${astra.identity.keycloak.client-secret}") String clientSecret) {
        return new KeycloakIdentityProvisioner(url, realm, clientId, clientSecret);
    }
}
