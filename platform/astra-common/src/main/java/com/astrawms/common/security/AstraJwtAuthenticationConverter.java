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
