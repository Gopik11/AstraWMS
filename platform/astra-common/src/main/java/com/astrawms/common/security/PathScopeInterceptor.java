package com.astrawms.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Applies the user's {@link AccessScope} to the {@code {siteId}} and {@code {ownerId}} path variables of every
 * endpoint, reads included (§G.5.1): a user scoped to site DC1 gets 403 SCOPE_SITE_DENIED on {@code /sites/DC2/...}.
 * Owners named in request bodies and owner filters on lists are applied by the services themselves.
 */
public class PathScopeInterceptor implements HandlerInterceptor {

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars instanceof Map<?, ?> map) {
            AccessScope scope = AccessScope.current();
            Object site = ((Map<String, Object>) map).get("siteId");
            if (site != null) {
                scope.requireSite(site.toString());
            }
            Object owner = ((Map<String, Object>) map).get("ownerId");
            if (owner != null) {
                scope.requireOwner(owner.toString());
            }
        }
        return true;
    }
}
