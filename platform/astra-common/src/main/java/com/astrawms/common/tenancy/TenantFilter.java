package com.astrawms.common.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the tenant for API requests.
 *
 * <p>Release 0.1 reads {@code X-Tenant-Id} / {@code X-User-Id} headers, which the API gateway sets from the
 * validated OIDC token (NFR-100). Services must not be exposed without the gateway. Paths under
 * {@code /actuator} are tenant-less.
 */
public class TenantFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String USER_HEADER = "X-User-Id";
    public static final String CHANNEL_HEADER = "X-Channel";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenant = request.getHeader(TENANT_HEADER);
        if (tenant == null || tenant.isBlank()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"https://astrawms.com/problems/tenant-missing","title":"Tenant missing",\
                    "status":400,"detail":"Header X-Tenant-Id is required","code":"TENANT_MISSING"}""");
            return;
        }
        String user = request.getHeader(USER_HEADER);
        String channel = request.getHeader(CHANNEL_HEADER);
        TenantContext.bind(new TenantContext.Scope(tenant.trim(), user == null ? "anonymous" : user,
                channel == null ? "API" : channel));
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
