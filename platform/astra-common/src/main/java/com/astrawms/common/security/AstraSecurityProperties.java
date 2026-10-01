package com.astrawms.common.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code astra.security.*}: how tokens are validated and read, and the service's own client credentials.
 *
 * <pre>
 * astra.security.jwt.jwk-set-uri   signing keys of the identity provider (required)
 * astra.security.jwt.issuer        expected "iss" (required)
 * astra.security.jwt.audience      expected "aud" (default astrawms-api)
 * astra.security.claims.*          claim names for tenant, user and roles (Keycloak defaults)
 * astra.security.client.*          client-credentials registration for service-to-service calls (optional)
 * </pre>
 */
@ConfigurationProperties("astra.security")
public class AstraSecurityProperties {

    private final Jwt jwt = new Jwt();
    private final Claims claims = new Claims();
    private final Client client = new Client();
    /** Maximum age of an approver's token (X-Approval-Token): approval needs a fresh login, like an e-signature. */
    private Duration approvalMaxAge = Duration.ofMinutes(5);

    public Jwt getJwt() {
        return jwt;
    }

    public Claims getClaims() {
        return claims;
    }

    public Client getClient() {
        return client;
    }

    public Duration getApprovalMaxAge() {
        return approvalMaxAge;
    }

    public void setApprovalMaxAge(Duration approvalMaxAge) {
        this.approvalMaxAge = approvalMaxAge;
    }

    public static class Jwt {
        private String jwkSetUri;
        private String issuer;
        private String audience = "astrawms-api";
        private Duration clockSkew = Duration.ofSeconds(30);

        public String getJwkSetUri() {
            return jwkSetUri;
        }

        public void setJwkSetUri(String jwkSetUri) {
            this.jwkSetUri = jwkSetUri;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public Duration getClockSkew() {
            return clockSkew;
        }

        public void setClockSkew(Duration clockSkew) {
            this.clockSkew = clockSkew;
        }
    }

    public static class Claims {
        private String tenant = "tenant_id";
        /** First claim present wins; falls back to {@code sub}. */
        private List<String> user = List.of("preferred_username");
        /** Dotted paths to string arrays; all are merged. Keycloak realm roles and Entra ID / Okta app roles. */
        private List<String> roles = List.of("realm_access.roles", "roles");
        /** Access scope claims (§G.5.1): string arrays, {@code "*"} = all. */
        private String sites = "wms_sites";
        private String owners = "wms_owners";
        private String zones = "wms_zones";
        /** Maximum value the user may approve (number, tenant reporting currency); absent = no value limit. */
        private String approvalLimit = "approval_limit";
        /** A missing site/owner/zone claim grants nothing (NONE, least privilege) or everything (ALL). */
        private String scopeWhenMissing = "NONE";

        public String getSites() {
            return sites;
        }

        public void setSites(String sites) {
            this.sites = sites;
        }

        public String getOwners() {
            return owners;
        }

        public void setOwners(String owners) {
            this.owners = owners;
        }

        public String getZones() {
            return zones;
        }

        public void setZones(String zones) {
            this.zones = zones;
        }

        public String getApprovalLimit() {
            return approvalLimit;
        }

        public void setApprovalLimit(String approvalLimit) {
            this.approvalLimit = approvalLimit;
        }

        public String getScopeWhenMissing() {
            return scopeWhenMissing;
        }

        public void setScopeWhenMissing(String scopeWhenMissing) {
            this.scopeWhenMissing = scopeWhenMissing;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public List<String> getUser() {
            return user;
        }

        public void setUser(List<String> user) {
            this.user = user;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }
    }

    public static class Client {
        private String tokenUri;
        private String clientId;
        private String clientSecret;

        public String getTokenUri() {
            return tokenUri;
        }

        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }
    }
}
