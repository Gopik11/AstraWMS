package com.astrawms.common.config;

import com.astrawms.common.security.ApprovalVerifier;
import com.astrawms.common.security.AstraJwtAuthenticationConverter;
import com.astrawms.common.security.AstraSecurityProperties;
import com.astrawms.common.security.EarlyRoleCheckInterceptor;
import com.astrawms.common.security.Roles;
import com.astrawms.common.security.SecuredEndpointsVerifier;
import com.astrawms.common.security.ServiceCallInterceptor;
import com.astrawms.common.security.ServiceTokenProvider;
import com.astrawms.common.tenancy.TenantFilter;
import com.astrawms.common.web.Problems;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every AstraWMS service is an OAuth2 resource server (ADR-0010, NFR-100/101/126):
 * <ul>
 *   <li>all API requests need a bearer token signed by the identity provider, for this issuer and audience, unexpired;
 *       only health and info probes are anonymous;</li>
 *   <li>the tenant and user come from the token ({@link TenantFilter}), never from a caller-chosen header, except for
 *       AstraWMS service accounts;</li>
 *   <li>roles are checked per endpoint with {@code @PreAuthorize}; {@link SecuredEndpointsVerifier} refuses to start
 *       a service with an unprotected write endpoint;</li>
 *   <li>stateless: no sessions, no cookies, so no CSRF surface.</li>
 * </ul>
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration"})
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AstraSecurityProperties.class)
public class AstraSecurityAutoConfiguration {

    /** The validation rules for AstraWMS access tokens; shared with the test token issuer. */
    public static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience, Duration clockSkew) {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(clockSkew),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<Object>(JwtClaimNames.AUD, aud -> aud instanceof Collection<?> list
                        ? list.contains(audience) : audience.equals(aud)));
    }

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder astraJwtDecoder(AstraSecurityProperties properties) {
        AstraSecurityProperties.Jwt jwt = properties.getJwt();
        if (isBlank(jwt.getJwkSetUri()) || isBlank(jwt.getIssuer())) {
            throw new IllegalStateException("astra.security.jwt.jwk-set-uri and astra.security.jwt.issuer must be set: "
                    + "AstraWMS services only accept tokens from the configured identity provider");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwt.getJwkSetUri()).build();
        decoder.setJwtValidator(tokenValidator(jwt.getIssuer(), jwt.getAudience(), jwt.getClockSkew()));
        return decoder;
    }

    @Bean
    AstraJwtAuthenticationConverter astraJwtAuthenticationConverter(AstraSecurityProperties properties) {
        return new AstraJwtAuthenticationConverter(properties.getClaims());
    }

    @Bean
    SecurityFilterChain astraSecurityFilterChain(HttpSecurity http, AstraJwtAuthenticationConverter converter,
                                                 AstraSecurityProperties properties) throws Exception {
        AuthenticationEntryPoint unauthenticated = unauthenticated();
        AccessDeniedHandler forbidden = (request, response, e) -> Problems.write(response, HttpStatus.FORBIDDEN,
                "FORBIDDEN", "Your roles do not permit this operation");
        http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole(Roles.SOLUTION_ADMIN)
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden))
                .addFilterAfter(new TenantFilter(properties.getClaims().getTenant()), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Role checks before body binding and validation (see {@link EarlyRoleCheckInterceptor}). */
    @Bean
    WebMvcConfigurer astraEarlyRoleCheck() {
        EarlyRoleCheckInterceptor interceptor = new EarlyRoleCheckInterceptor();
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }

    @Bean
    SecuredEndpointsVerifier securedEndpointsVerifier() {
        return new SecuredEndpointsVerifier();
    }

    @Bean
    @ConditionalOnProperty("astra.security.client.token-uri")
    ServiceTokenProvider serviceTokenProvider(AstraSecurityProperties properties, Clock clock) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new ServiceTokenProvider(RestClient.builder().requestFactory(factory).build(), properties.getClient(),
                clock);
    }

    @Bean
    ServiceCallInterceptor serviceCallInterceptor(ObjectProvider<ServiceTokenProvider> serviceTokens) {
        return new ServiceCallInterceptor(serviceTokens.getIfAvailable());
    }

    @Bean
    ApprovalVerifier approvalVerifier(JwtDecoder decoder, AstraJwtAuthenticationConverter converter,
                                      AstraSecurityProperties properties, Clock clock) {
        return new ApprovalVerifier(decoder, converter, properties.getClaims().getTenant(),
                properties.getApprovalMaxAge(), clock);
    }

    /** 401 with {@code WWW-Authenticate: Bearer ...} (RFC 6750) and a problem body. */
    private static AuthenticationEntryPoint unauthenticated() {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, e) -> {
            bearer.commence(request, response, e);
            boolean badToken = e instanceof OAuth2AuthenticationException;
            Problems.write(response, HttpStatus.UNAUTHORIZED, badToken ? "TOKEN_INVALID" : "UNAUTHENTICATED",
                    badToken ? "The bearer token is not valid: " + e.getMessage()
                            : "A bearer token from the AstraWMS identity provider is required");
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
