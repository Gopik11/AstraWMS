package com.astrawms.masterdata.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * The provisioner against a real Keycloak with the repository's realm (ADR-0030 A): the account it creates is bound to
 * the tenant, holds SOLUTION_ADMIN and nothing more, must change its temporary password, and nothing is left behind when
 * a step fails. Keycloak runs with the VPS hostname settings (deploy/vps/docker-compose.yml). Image override: {@code -Dastra.test.keycloak-image=...}.
 */
class KeycloakIdentityIT {

    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
            System.getProperty("astra.test.keycloak-image", "quay.io/keycloak/keycloak:26.4"))
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            // as on the VPS: tokens carry the public issuer while the service calls the private address
            .withEnv("KC_HOSTNAME", "https://wms.example.test")
            .withEnv("KC_HOSTNAME_ADMIN", "http://localhost:8181")
            .withEnv("KC_HOSTNAME_BACKCHANNEL_DYNAMIC", "false")
            .withCopyFileToContainer(MountableFile.forHostPath(realmFile()), "/opt/keycloak/data/import/astrawms-realm.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/astrawms").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)));

    static String url;

    @BeforeAll
    static void start() {
        KEYCLOAK.start();
        url = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    }

    @AfterAll
    static void stop() {
        KEYCLOAK.stop();
    }

    private static Path realmFile() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !dir.resolve("deploy/keycloak/astrawms-realm.json").toFile().exists()) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("deploy/keycloak/astrawms-realm.json not found above the working directory");
        }
        return dir.resolve("deploy/keycloak/astrawms-realm.json");
    }

    private static KeycloakIdentityProvisioner provisioner() {
        return new KeycloakIdentityProvisioner(url, "astrawms", "astra-provisioner", "provisioner-dev-secret");
    }

    private static String username() {
        return "admin-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** The master realm's administrator, to look at what the provisioner made. */
    private static RestClient admin() {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", "admin");
        form.add("password", "admin");
        Map<?, ?> token = RestClient.create(url).post().uri("/realms/master/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
        return RestClient.builder().baseUrl(url + "/admin/realms/astrawms")
                .defaultHeader("Authorization", "Bearer " + token.get("access_token")).build();
    }

    private static List<Map<String, Object>> usersNamed(String username) {
        return admin().get().uri("/users?exact=true&username={u}", username).retrieve().body(List.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAdministratorIsBoundToTheTenantHoldsSolutionAdminOnlyAndMustChangeThePassword() {
        String username = username();
        IdentityProvisioner.CreatedAdmin created = provisioner().createTenantAdmin("ACME",
                new IdentityProvisioner.NewAdmin(username, username + "@acme.example", "Ada", "Admin"));

        assertThat(created.username()).isEqualTo(username);
        assertThat(created.temporaryPassword()).hasSize(20);
        Map<String, Object> user = admin().get().uri("/users/{id}", created.userId()).retrieve().body(Map.class);
        assertThat(user.get("username")).isEqualTo(username);
        assertThat(user.get("enabled")).isEqualTo(true);
        assertThat((List<String>) user.get("requiredActions")).containsExactly("UPDATE_PASSWORD");
        Map<String, List<String>> attributes = (Map<String, List<String>>) user.get("attributes");
        assertThat(attributes.get("tenant_id")).containsExactly("ACME");
        assertThat(attributes.get("wms_sites")).containsExactly("*");
        assertThat(attributes.get("wms_owners")).containsExactly("*");
        assertThat(attributes.get("wms_zones")).containsExactly("*");

        List<Map<String, Object>> roles = admin().get().uri("/users/{id}/role-mappings/realm", created.userId())
                .retrieve().body(List.class);
        assertThat(roles).extracting(r -> r.get("name")).contains("SOLUTION_ADMIN").doesNotContain("PLATFORM_ADMIN");
        List<Map<String, Object>> credentials = admin().get().uri("/users/{id}/credentials", created.userId())
                .retrieve().body(List.class);
        assertThat(credentials).extracting(c -> c.get("type")).containsExactly("password");
    }

    @Test
    void aTakenUsernameIsReportedAndTheExistingAccountKept() {
        String username = username();
        IdentityProvisioner.CreatedAdmin first = provisioner().createTenantAdmin("ACME",
                new IdentityProvisioner.NewAdmin(username, null, null, null));

        assertThatThrownBy(() -> provisioner().createTenantAdmin("OTHER",
                new IdentityProvisioner.NewAdmin(username, null, null, null)))
                .isInstanceOf(IdentityProvisioner.UserExists.class);
        assertThat(usersNamed(username)).singleElement().satisfies(u -> assertThat(u.get("id")).isEqualTo(first.userId()));
    }

    @Test
    void whenTheRoleCannotBeGrantedTheHalfMadeAccountIsRemoved() {
        String username = username();
        KeycloakIdentityProvisioner missingRole = new KeycloakIdentityProvisioner(url, "astrawms", "astra-provisioner",
                "provisioner-dev-secret", List.of("NO_SUCH_ROLE"));

        assertThatThrownBy(() -> missingRole.createTenantAdmin("ACME", new IdentityProvisioner.NewAdmin(username, null, null, null)))
                .isInstanceOf(IdentityProvisioner.Unavailable.class);
        assertThat(usersNamed(username)).isEmpty();
    }

    @Test
    void aWrongSecretOrAnUnreachableKeycloakIsUnavailableAndCreatesNothing() {
        String username = username();
        assertThatThrownBy(() -> new KeycloakIdentityProvisioner(url, "astrawms", "astra-provisioner", "wrong")
                .createTenantAdmin("ACME", new IdentityProvisioner.NewAdmin(username, null, null, null)))
                .isInstanceOf(IdentityProvisioner.Unavailable.class);
        assertThatThrownBy(() -> new KeycloakIdentityProvisioner("http://127.0.0.1:9", "astrawms", "astra-provisioner",
                "provisioner-dev-secret").createTenantAdmin("ACME", new IdentityProvisioner.NewAdmin(username, null, null, null)))
                .isInstanceOf(IdentityProvisioner.Unavailable.class);
        assertThat(usersNamed(username)).isEmpty();
    }
}
