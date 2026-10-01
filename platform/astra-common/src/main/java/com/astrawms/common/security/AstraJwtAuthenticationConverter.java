package com.astrawms.common.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns a validated token into an authentication: the principal name is the user claim (e.g.
 * {@code preferred_username}, falling back to {@code sub}) and the roles become {@code ROLE_*} authorities.
 */
public class AstraJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AstraSecurityProperties.Claims claims;

    public AstraJwtAuthenticationConverter(AstraSecurityProperties.Claims claims) {
        this.claims = claims;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, authorities(jwt), userName(jwt));
    }

    String userName(Jwt jwt) {
        for (String claim : claims.getUser()) {
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return jwt.getSubject();
    }

    Collection<GrantedAuthority> authorities(Jwt jwt) {
        Set<GrantedAuthority> result = new LinkedHashSet<>();
        for (String path : claims.getRoles()) {
            Object value = resolve(jwt.getClaims(), path);
            if (value instanceof Collection<?> roles) {
                roles.stream().filter(String.class::isInstance).map(String.class::cast)
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .forEach(result::add);
            }
        }
        return List.copyOf(result);
    }

    /** The access scope (§G.5.1) a user token carries. */
    public AccessScope accessScope(Jwt jwt) {
        boolean allWhenMissing = "ALL".equalsIgnoreCase(claims.getScopeWhenMissing());
        return new AccessScope(scopeSet(jwt, claims.getSites(), allWhenMissing),
                scopeSet(jwt, claims.getOwners(), allWhenMissing),
                scopeSet(jwt, claims.getZones(), allWhenMissing),
                decimal(jwt.getClaims().get(claims.getApprovalLimit())));
    }

    /** {@code null} = unrestricted. A claim may be an array or a single (comma-separated) string. */
    private static Set<String> scopeSet(Jwt jwt, String claim, boolean allWhenMissing) {
        Object value = jwt.getClaims().get(claim);
        if (value == null) {
            return allWhenMissing ? null : Set.of();
        }
        List<String> values = value instanceof Collection<?> c
                ? c.stream().map(String::valueOf).toList()
                : List.of(String.valueOf(value).split(","));
        Set<String> result = new LinkedHashSet<>();
        for (String v : values) {
            String t = v.trim();
            if ("*".equals(t)) {
                return null;
            }
            if (!t.isEmpty()) {
                result.add(t);
            }
        }
        return Set.copyOf(result);
    }

    private static java.math.BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        Object single = value instanceof Collection<?> c ? c.stream().findFirst().orElse(null) : value;
        try {
            return single == null || String.valueOf(single).isBlank() ? null : new java.math.BigDecimal(String.valueOf(single).trim());
        } catch (NumberFormatException e) {
            return java.math.BigDecimal.ZERO;   // unreadable limit: approves nothing
        }
    }

    private static Object resolve(Map<String, Object> claims, String dottedPath) {
        Object current = claims;
        for (String part : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }
}
