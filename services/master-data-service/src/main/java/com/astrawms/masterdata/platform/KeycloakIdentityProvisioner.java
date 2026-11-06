package com.astrawms.masterdata.platform;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
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
 * {@link IdentityProvisioner} and {@link TenantUsers} on Keycloak's admin API, signed in as the provisioner (client
 * credentials; its service account has realm-management manage-users and view-users), the account the provisioning
 * scripts use.
 */
public class KeycloakIdentityProvisioner implements IdentityProvisioner, TenantUsers {

    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityProvisioner.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RestClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final List<String> roles;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

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
        String userId = createAccount(token, tenantId, admin.username(), admin.email(), admin.firstName(), admin.lastName(),
                roles, List.of("*"), List.of("*"), List.of("*"), null, password);
        return new CreatedAdmin(userId, admin.username(), password);
    }

    // ------------------------------------------------------------------ the accounts of a tenant (ADR-0031)

    @Override
    public List<User> users(String tenantId) {
        String token = token();
        List<Map<String, Object>> found;
        try {
            // q= matches the attribute exactly; the binding is checked again on every account below
            found = http.get().uri("/admin/realms/{realm}/users?q={q}&briefRepresentation=false&max=2000", realm,
                            "tenant_id:" + tenantId)
                    .header("Authorization", "Bearer " + token).retrieve().body(LIST_OF_MAPS);
        } catch (RestClientException e) {
            throw new Unavailable("Keycloak cannot list the users: " + e.getMessage(), e);
        }
        List<User> users = new ArrayList<>();
        for (Map<String, Object> u : found == null ? List.<Map<String, Object>>of() : found) {
            if (boundTo(u, tenantId)) {
                users.add(toUser(u, realmRoles(token, String.valueOf(u.get("id")))));
            }
        }
        users.sort(Comparator.comparing(User::username));
        return users;
    }

    @Override
    public Optional<User> user(String tenantId, String userId) {
        String token = token();
        return representation(token, tenantId, userId).map(u -> toUser(u, realmRoles(token, userId)));
    }

    @Override
    public Created create(String tenantId, Spec spec) {
        String token = token();
        String password = temporaryPassword();
        String userId = createAccount(token, tenantId, spec.username(), spec.email(), spec.firstName(), spec.lastName(),
                spec.roles(), spec.sites(), spec.owners(), spec.zones(), spec.approvalLimit(), password);
        return new Created(user(tenantId, userId).orElseThrow(), password);
    }

    @Override
    public User update(String tenantId, String userId, Spec change) {
        String token = token();
        Map<String, Object> u = new LinkedHashMap<>(representation(token, tenantId, userId)
                .orElseThrow(() -> new NoSuchUser(userId)));
        List<String> current = realmRoles(token, userId);
        if (!toUser(u, current).managed()) {
            throw new NotManaged(String.valueOf(u.get("username")));
        }
        if (change.email() != null) {
            u.put("email", change.email().isBlank() ? null : change.email());
        }
        if (change.firstName() != null) {
            u.put("firstName", change.firstName());
        }
        if (change.lastName() != null) {
            u.put("lastName", change.lastName());
        }
        if (change.enabled() != null) {
            u.put("enabled", change.enabled());
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (u.get("attributes") instanceof Map<?, ?> existing) {
            existing.forEach((k, v) -> attributes.put(String.valueOf(k), v));
        }
        attributes.put("tenant_id", List.of(tenantId));     // the binding is never changed here
        if (change.sites() != null) {
            attributes.put("wms_sites", change.sites());
        }
        if (change.owners() != null) {
            attributes.put("wms_owners", change.owners());
        }
        if (change.zones() != null) {
            attributes.put("wms_zones", change.zones());
        }
        if (change.approvalLimit() != null) {
            if (change.approvalLimit().isBlank()) {
                attributes.remove("approval_limit");
            } else {
                attributes.put("approval_limit", List.of(change.approvalLimit()));
            }
        }
        u.put("attributes", attributes);
        u.remove("access");
        try {
            http.put().uri("/admin/realms/{realm}/users/{id}", realm, userId).header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).body(u).retrieve().toBodilessEntity();
            if (change.roles() != null) {
                List<Map<?, ?>> add = new ArrayList<>();
                List<Map<?, ?>> remove = new ArrayList<>();
                for (String role : TenantUsers.ROLES) {
                    if (change.roles().contains(role) && !current.contains(role)) {
                        add.add(role(token, role));
                    } else if (!change.roles().contains(role) && current.contains(role)) {
                        remove.add(role(token, role));
                    }
                }
                if (!add.isEmpty()) {
                    http.post().uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                            .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                            .body(add).retrieve().toBodilessEntity();
                }
                if (!remove.isEmpty()) {
                    http.method(HttpMethod.DELETE).uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                            .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                            .body(remove).retrieve().toBodilessEntity();
                }
            }
            if (Boolean.FALSE.equals(change.enabled())) {
                // a disabled account keeps no session, so its tokens are not renewed
                http.post().uri("/admin/realms/{realm}/users/{id}/logout", realm, userId)
                        .header("Authorization", "Bearer " + token).retrieve().toBodilessEntity();
            }
        } catch (RestClientException e) {
            throw new Unavailable("Keycloak refused the change of " + u.get("username") + ": " + e.getMessage(), e);
        }
        return user(tenantId, userId).orElseThrow();
    }

    @Override
    public String resetPassword(String tenantId, String userId) {
        String token = token();
        Map<String, Object> u = representation(token, tenantId, userId).orElseThrow(() -> new NoSuchUser(userId));
        if (!toUser(u, realmRoles(token, userId)).managed()) {
            throw new NotManaged(String.valueOf(u.get("username")));
        }
        String password = temporaryPassword();
        try {
            http.put().uri("/admin/realms/{realm}/users/{id}/reset-password", realm, userId)
                    .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("type", "password", "value", password, "temporary", true))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new Unavailable("Keycloak refused the new password of " + u.get("username") + ": " + e.getMessage(), e);
        }
        return password;
    }

    /** The account, if it is bound to the tenant (empty for an unknown id and for another tenant's account). */
    private Optional<Map<String, Object>> representation(String token, String tenantId, String userId) {
        if (userId == null || !userId.matches("[A-Za-z0-9-]{1,64}")) {
            return Optional.empty();
        }
        try {
            Map<String, Object> u = http.get().uri("/admin/realms/{realm}/users/{id}", realm, userId)
                    .header("Authorization", "Bearer " + token).retrieve().body(MAP);
            return u != null && boundTo(u, tenantId) ? Optional.of(u) : Optional.empty();
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new Unavailable("Keycloak cannot read the user: " + e.getMessage(), e);
        }
    }

    /** Bound to exactly this tenant: one {@code tenant_id} value, equal to it. */
    private static boolean boundTo(Map<String, Object> u, String tenantId) {
        return u.get("attributes") instanceof Map<?, ?> a && a.get("tenant_id") instanceof List<?> l && l.size() == 1
                && tenantId.equals(String.valueOf(l.getFirst()));
    }

    private List<String> realmRoles(String token, String userId) {
        try {
            List<Map<String, Object>> mapped = http.get()
                    .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                    .header("Authorization", "Bearer " + token).retrieve().body(LIST_OF_MAPS);
            return mapped == null ? List.of() : mapped.stream().map(r -> String.valueOf(r.get("name")))
                    // Keycloak's own roles are not AstraWMS roles
                    .filter(r -> !r.startsWith("default-roles-") && !r.equals("offline_access") && !r.equals("uma_authorization"))
                    .sorted().toList();
        } catch (RestClientException e) {
            throw new Unavailable("Keycloak cannot read the roles: " + e.getMessage(), e);
        }
    }

    private static User toUser(Map<String, Object> u, List<String> realmRoles) {
        Map<?, ?> a = u.get("attributes") instanceof Map<?, ?> m ? m : Map.of();
        boolean foreignRole = realmRoles.stream().anyMatch(r -> !TenantUsers.ROLES.contains(r));
        boolean serviceAccount = u.get("serviceAccountClientLink") != null
                || String.valueOf(u.get("username")).startsWith("service-account-");
        boolean passwordChange = u.get("requiredActions") instanceof List<?> l && l.contains("UPDATE_PASSWORD");
        Object created = u.get("createdTimestamp");
        return new User(String.valueOf(u.get("id")), String.valueOf(u.get("username")), (String) u.get("email"),
                (String) u.get("firstName"), (String) u.get("lastName"), Boolean.TRUE.equals(u.get("enabled")),
                realmRoles, values(a, "wms_sites"), values(a, "wms_owners"), values(a, "wms_zones"),
                values(a, "approval_limit").stream().findFirst().orElse(null), passwordChange,
                created instanceof Number n ? n.longValue() : null, !foreignRole && !serviceAccount);
    }

    private static List<String> values(Map<?, ?> attributes, String name) {
        return attributes.get(name) instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private Map<?, ?> role(String token, String role) {
        return http.get().uri("/admin/realms/{realm}/roles/{role}", realm, role)
                .header("Authorization", "Bearer " + token).retrieve().body(Map.class);
    }

    /** Creates the account with its roles, or removes it again when a role cannot be granted. Returns its id. */
    private String createAccount(String token, String tenantId, String username, String email, String firstName,
                                 String lastName, List<String> grant, List<String> sites, List<String> owners,
                                 List<String> zones, String approvalLimit, String password) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        if (email != null) {
            user.put("email", email);
        }
        if (firstName != null) {
            user.put("firstName", firstName);
        }
        if (lastName != null) {
            user.put("lastName", lastName);
        }
        user.put("enabled", true);
        user.put("requiredActions", List.of("UPDATE_PASSWORD"));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("tenant_id", List.of(tenantId));
        attributes.put("wms_sites", sites);
        attributes.put("wms_owners", owners);
        attributes.put("wms_zones", zones);
        if (approvalLimit != null) {
            attributes.put("approval_limit", List.of(approvalLimit));
        }
        user.put("attributes", attributes);
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
            throw new UserExists(username);
        } catch (RestClientException e) {
            throw new Unavailable("Keycloak refused or did not answer the user creation: " + e.getMessage(), e);
        }
        try {
            List<Map<?, ?>> representations = new ArrayList<>();
            for (String role : grant) {
                representations.add(role(token, role));
            }
            http.post().uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                    .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                    .body(representations).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            // no account without its roles: remove the half-made one
            try {
                http.delete().uri("/admin/realms/{realm}/users/{id}", realm, userId)
                        .header("Authorization", "Bearer " + token).retrieve().toBodilessEntity();
            } catch (RestClientException cleanup) {
                log.error("Could not remove the half-made Keycloak user {} ({}): remove it by hand", username, userId);
            }
            throw new Unavailable("Keycloak refused the roles of the new user: " + e.getMessage(), e);
        }
        return userId;
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
