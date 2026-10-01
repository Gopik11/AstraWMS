package com.astrawms.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.astrawms.common.tenancy.TenantContext;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class ServiceCallSecurityTest {

    private final AstraJwtAuthenticationConverter converter =
            new AstraJwtAuthenticationConverter(new AstraSecurityProperties.Claims());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void convertsKeycloakAndAppRolesAndUserName() {
        Jwt jwt = jwt(Map.of("preferred_username", "alice", "realm_access", Map.of("roles", List.of("RECEIVER")),
                "roles", List.of("SUPERVISOR")));
        var auth = converter.convert(jwt);
        assertThat(auth.getName()).isEqualTo("alice");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_RECEIVER", "ROLE_SUPERVISOR");
        assertThat(converter.convert(jwt(Map.of())).getName()).isEqualTo("subject-1");
    }

    @Test
    void relaysTheUsersTokenInsideAUserRequest() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(converter.convert(
                jwt(Map.of("preferred_username", "alice", "tenant_id", "t1", "realm_access", Map.of("roles", List.of("PICKER"))))));
        HttpHeaders sent = call(new ServiceCallInterceptor(fixedServiceToken("svc-token")), "t1", "alice");
        assertThat(sent.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer user-token");
        assertThat(sent.getFirst("X-Tenant-Id")).isEqualTo("t1");
        assertThat(sent.getFirst("X-User-Id")).isEqualTo("alice");
    }

    @Test
    void usesTheServiceTokenOutsideAUserRequest() throws Exception {
        HttpHeaders sent = call(new ServiceCallInterceptor(fixedServiceToken("svc-token")), "t1", "kafka-consumer");
        assertThat(sent.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer svc-token");
        assertThat(sent.getFirst("X-User-Id")).isEqualTo("kafka-consumer");
    }

    @Test
    void refusesToCallWithoutAnyCredentials() {
        assertThatThrownBy(() -> call(new ServiceCallInterceptor(null), "t1", "job"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("astra.security.client");
    }

    private static HttpHeaders call(ServiceCallInterceptor interceptor, String tenant, String user) throws Exception {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://inventory/x"));
        HttpHeaders[] sent = new HttpHeaders[1];
        ClientHttpRequestExecution execution = (HttpRequest r, byte[] body) -> {
            sent[0] = r.getHeaders();
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        };
        TenantContext.callAs(new TenantContext.Scope(tenant, user, "API"),
                () -> interceptor.intercept(request, new byte[0], execution));
        return sent[0];
    }

    private static ServiceTokenProvider fixedServiceToken(String token) {
        return new ServiceTokenProvider(null, new AstraSecurityProperties.Client(), Clock.systemUTC()) {
            @Override
            public synchronized String token() {
                return token;
            }
        };
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("user-token").header("alg", "RS256").subject("subject-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
