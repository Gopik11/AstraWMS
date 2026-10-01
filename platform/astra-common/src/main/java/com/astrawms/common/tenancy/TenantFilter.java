package com.astrawms.common.tenancy;

import com.astrawms.common.security.AccessScope;
import com.astrawms.common.security.AstraJwtAuthenticationConverter;
import com.astrawms.common.security.Roles;
import com.astrawms.common.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the tenant and acting user of an authenticated request (ADR-0010). Runs inside the Spring Security chain,
 * after the bearer token has been validated.
 * <ul>
 *   <li><b>User and ERP-integration tokens</b>: tenant from the token's tenant claim, user from the principal. An
 *       {@code X-Tenant-Id} header is allowed only if it names the same tenant; {@code X-User-Id} is ignored.</li>
 *   <li><b>Service tokens</b> (role {@link Roles#WMS_SERVICE}): an AstraWMS service acting for a tenant names it in
 *       {@code X-Tenant-Id} and the user it acts for in {@code X-User-Id}. Only service accounts are trusted to do
 *       so. Service accounts are not limited by site, owner or zone; the user's {@link AccessScope} comes from
 *       the token's scope claims.</li>
 * </ul>
 * Unauthenticated requests pass through untouched; the authorization rules reject them (or allow health probes).
 */
public class TenantFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String USER_HEADER = "X-User-Id";
    public static final String CHANNEL_HEADER = "X-Channel";

    private static final String SERVICE_AUTHORITY = "ROLE_" + Roles.WMS_SERVICE;

    private final String tenantClaim;
    private final AstraJwtAuthenticationConverter converter;

    public TenantFilter(String tenantClaim, AstraJwtAuthenticationConverter converter) {
        this.tenantClaim = tenantClaim;
        this.converter = converter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        String headerTenant = trimToNull(request.getHeader(TENANT_HEADER));
        String tenant;
        String user;
        AccessScope access = AccessScope.UNRESTRICTED;
        if (isService(token)) {
            if (headerTenant == null) {
                Problems.write(response, HttpStatus.BAD_REQUEST, "TENANT_MISSING",
                        "Service calls must name the tenant in X-Tenant-Id");
                return;
            }
            tenant = headerTenant;
            String onBehalfOf = trimToNull(request.getHeader(USER_HEADER));
            user = onBehalfOf != null ? onBehalfOf : token.getName();
        } else {
            tenant = trimToNull(token.getToken().getClaimAsString(tenantClaim));
            if (tenant == null) {
                Problems.write(response, HttpStatus.FORBIDDEN, "TENANT_CLAIM_MISSING",
                        "The token is not issued for a tenant (claim " + tenantClaim + ")");
                return;
            }
            if (headerTenant != null && !headerTenant.equals(tenant)) {
                Problems.write(response, HttpStatus.FORBIDDEN, "TENANT_MISMATCH",
                        "X-Tenant-Id does not match the tenant of the token");
                return;
            }
            user = token.getName();
            access = converter.accessScope(token.getToken());
        }
        String channel = trimToNull(request.getHeader(CHANNEL_HEADER));
        TenantContext.bind(new TenantContext.Scope(tenant, user, channel == null ? "API" : channel, access));
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    public static boolean isService(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> SERVICE_AUTHORITY.equals(a.getAuthority()));
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
