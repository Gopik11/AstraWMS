package com.astrawms.masterdata.platform;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link IdentityProvisioner} on Keycloak's admin API, signed in as the provisioner (client credentials; its service
 * account has realm-management manage-users), the account the provisioning scripts use.
 */
public class KeycloakIdentityProvisioner implements IdentityProvisioner {

    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityProvisioner.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RestClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final List<String> roles;

    public KeycloakIdentityProvisioner(String baseUrl, String realm, String clientId, String clientSecret) {
        this(baseUrl, realm, clientId, clientSecret, GRANTABLE_ROLES);
    }

    /** With other roles: tests only, to make a role mapping fail. */
    KeycloakIdentityProvisioner(String baseUrl, String realm, String clientId, String clientSecret, List<String> roles) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(5));
        requests.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.roles = List.copyOf(roles);
    }

    @Override
    public CreatedAdmin createTenantAdmin(String tenantId, NewAdmin admin) {
        String token = token();
        String password = temporaryPassword();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", admin.username());
        if (admin.email() != null) {
            user.put("email", admin.email());
        }
        if (admin.firstName() != null) {
            user.put("firstName", admin.firstName());
        }
        if (admin.lastName() != null) {
            user.put("lastName", admin.lastName());
        }
        user.put("enabled", true);
        user.put("requiredActions", List.of("UPDATE_PASSWORD"));
        user.put("attributes", Map.of("tenant_id", List.of(tenantId), "wms_sites", List.of("*"),
                "wms_owners", List.of("*"), "wms_zones", List.of("*")));
        user.put("credentials", List.of(Map.of("type", "password", "value", password, "temporary", true)));
        String userId;
        try {
            ResponseEntity<Void> created = http.post().uri("/admin/realms/{realm}/users", realm)
                    .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                    .body(user).retrieve().toBodilessEntity();
            URI location = created.getHeaders().getLocation();
            if (location == null) {
                throw new Unavailable("Keycloak created the user but did not say where", null);
            }
            String path = location.getPath();
            userId = path.substring(path.lastIndexOf('/') + 1);
        } catch (HttpClientErrorException.Conflict e) {
            throw new UserExists(admin.username());
        } catch (RestClientException e) {
            throw new Unavailable("Keycloak refused or did not answer the user creation: " + e.getMessage(), e);
        }
        try {
            for (String role : roles) {
                Map<?, ?> representation = http.get().uri("/admin/realms/{realm}/roles/{role}", realm, role)
                        .header("Authorization", "Bearer " + token).retrieve().body(Map.class);
                http.post().uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .body(List.of(representation)).retrieve().toBodilessEntity();
            }
        } catch (RestClientException e) {
            // no account without its role: remove the half-made one
            try {
                http.delete().uri("/admin/realms/{realm}/users/{id}", realm, userId)
                        .header("Authorization", "Bearer " + token).retrieve().toBodilessEntity();
            } catch (RestClientException cleanup) {
                log.error("Could not remove the half-made Keycloak user {} ({}): remove it by hand", admin.username(), userId);
            }
            throw new Unavailable("Keycloak refused the role of the new user: " + e.getMessage(), e);
        }
        return new CreatedAdmin(userId, admin.username(), password);
    }

    private String token() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        try {
            Map<?, ?> body = http.post().uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new Unavailable("Keycloak refused the provisioner's sign-in: HTTP " + res.getStatusCode(), null);
                    })
                    .body(Map.class);
            Object token = body == null ? null : body.get("access_token");
            if (token == null) {
                throw new Unavailable("Keycloak returned no access token", null);
            }
            return token.toString();
        } catch (RestClientException e) {
            throw new Unavailable("Keycloak cannot be reached: " + e.getMessage(), e);
        }
    }

    /** 20 characters, without look-alikes (0/O, 1/l/I), with a digit, a lower- and an upper-case letter. */
    static String temporaryPassword() {
        while (true) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            String p = b.toString();
            if (p.chars().anyMatch(Character::isDigit) && p.chars().anyMatch(Character::isLowerCase)
                    && p.chars().anyMatch(Character::isUpperCase)) {
                return p;
            }
        }
    }
}
