package com.astrawms.test;

import com.astrawms.common.config.AstraSecurityAutoConfiguration;
import com.astrawms.common.security.Roles;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A test identity provider: mints RS256 tokens that the services validate with the production rules
 * ({@link AstraSecurityAutoConfiguration#tokenValidator}), only with this issuer's key. Integration tests sign in with
 * {@code mvc.perform(get(...).with(TestTokens.as(tenant, "alice", Roles.SUPERVISOR)))}.
 */
public final class TestTokens {

    public static final String ISSUER = "https://idp.test.astrawms.local/realms/astrawms";
    public static final String AUDIENCE = "astrawms-api";

    /** Every business role, for functional tests that are not about authorisation. */
    public static final String[] ALL_ROLES = {Roles.RECEIVER, Roles.PICKER, Roles.INV_ANALYST, Roles.INV_MANAGER,
            Roles.SUPERVISOR, Roles.QA_MANAGER, Roles.SOLUTION_ADMIN, Roles.ERP_INTEGRATION};

    private static final RSAKey KEY = newKey("astra-test");
    private static final RSAKey FOREIGN_KEY = newKey("attacker");

    private TestTokens() {
    }

    public static JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(AstraSecurityAutoConfiguration.tokenValidator(ISSUER, AUDIENCE, Duration.ofSeconds(30)));
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A user of {@code tenant} with the given roles. */
    public static RequestPostProcessor as(String tenant, String user, String... roles) {
        return bearer(token().tenant(tenant).user(user).roles(roles).sign());
    }

    /** An AstraWMS service account (no tenant claim; it names the tenant in X-Tenant-Id). */
    public static RequestPostProcessor service(String clientId) {
        return bearer(token().user("service-account-" + clientId).roles(Roles.WMS_SERVICE).sign());
    }

    public static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    public static Builder token() {
        return new Builder();
    }

    public static final class Builder {
        private String tenant;
        private String user = "test-user";
        private List<String> roles = List.of();
        private String issuer = ISSUER;
        private String audience = AUDIENCE;
        private Instant issuedAt = Instant.now();
        private Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));
        private RSAKey key = KEY;

        public Builder tenant(String tenant) {
            this.tenant = tenant;
            return this;
        }

        public Builder user(String user) {
            this.user = user;
            return this;
        }

        public Builder roles(String... roles) {
            this.roles = List.of(roles);
            return this;
        }

        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Builder audience(String audience) {
            this.audience = audience;
            return this;
        }

        public Builder issuedAt(Instant issuedAt) {
            this.issuedAt = issuedAt;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        /** Signs with a key the services do not trust (a forged token). */
        public Builder forged() {
            this.key = FOREIGN_KEY;
            return this;
        }

        public String sign() {
            JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                    .issuer(issuer)
                    .audience(List.of(audience))
                    .subject("sub-" + user)
                    .issuedAt(issuedAt)
                    .expiresAt(expiresAt)
                    .claim("preferred_username", user)
                    .claim("realm_access", Map.of("roles", roles));
            if (tenant != null) {
                claims.claim("tenant_id", tenant);
            }
            NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
            JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
            return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        }
    }

    private static RSAKey newKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
