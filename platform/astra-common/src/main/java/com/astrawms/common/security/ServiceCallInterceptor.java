package com.astrawms.common.security;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.tenancy.TenantFilter;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Authenticates calls from one AstraWMS service to another (ADR-0010):
 * <ul>
 *   <li>inside a user's request, the user's own token is relayed, so the downstream service applies that user's
 *       roles and records that user;</li>
 *   <li>otherwise (message consumers, jobs) the service's client-credentials token is sent, with the tenant and the
 *       acting user in {@code X-Tenant-Id} / {@code X-User-Id}.</li>
 * </ul>
 * Add it to every {@code RestClient} that calls another AstraWMS service.
 */
public class ServiceCallInterceptor implements ClientHttpRequestInterceptor {

    private final ServiceTokenProvider serviceTokens;

    /** @param serviceTokens may be null when the service has no client registration; then only relay works. */
    public ServiceCallInterceptor(ServiceTokenProvider serviceTokens) {
        this.serviceTokens = serviceTokens;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        HttpHeaders headers = request.getHeaders();
        TenantContext.current().ifPresent(scope -> {
            headers.set(TenantFilter.TENANT_HEADER, scope.tenantId());
            headers.set(TenantFilter.USER_HEADER, scope.userId());
            headers.set(TenantFilter.CHANNEL_HEADER, scope.channel());
        });
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken user && !TenantFilter.isService(user)) {
            headers.setBearerAuth(user.getToken().getTokenValue());
        } else if (serviceTokens != null) {
            headers.setBearerAuth(serviceTokens.token());
        } else {
            throw new IllegalStateException("No user token to relay and no client registration "
                    + "(astra.security.client.*) for service-to-service calls");
        }
        return execution.execute(request, body);
    }
}
