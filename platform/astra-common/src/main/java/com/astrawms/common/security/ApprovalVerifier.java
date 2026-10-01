package com.astrawms.common.security;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Proves who approved a transaction (NFR-101 segregation of duties, §G.5). The approver signs in themselves and the
 * client sends the approver's access token in {@code X-Approval-Token}; a typed-in name is not accepted from users.
 * The token must be valid, for the same tenant, fresh (issued within {@code astra.security.approval-max-age}) and
 * carry one of the approver roles. Self-approval is rejected by the caller, which knows the requester.
 */
public class ApprovalVerifier {

    public static final String APPROVAL_HEADER = "X-Approval-Token";

    private final JwtDecoder decoder;
    private final AstraJwtAuthenticationConverter converter;
    private final String tenantClaim;
    private final Duration maxAge;
    private final Clock clock;

    public ApprovalVerifier(JwtDecoder decoder, AstraJwtAuthenticationConverter converter, String tenantClaim,
                            Duration maxAge, Clock clock) {
        this.decoder = decoder;
        this.converter = converter;
        this.tenantClaim = tenantClaim;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /**
     * A verified approver with their own access scope (§G.5.1): the caller checks that the approver may work for the
     * transaction's site and owner and that its value is within {@link AccessScope#approvalLimit()}.
     */
    public record Approver(String userName, AccessScope scope) {
    }

    /** Verifies the approval token and returns the approver. */
    public Approver approver(String approvalToken, String... approverRoles) {
        Jwt jwt;
        try {
            jwt = decoder.decode(approvalToken);
        } catch (JwtException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "APPROVAL_TOKEN_INVALID",
                    "The approval token is not valid: " + e.getMessage());
        }
        if (!TenantContext.tenantId().equals(jwt.getClaimAsString(tenantClaim))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "APPROVAL_TENANT_MISMATCH",
                    "The approver does not belong to this tenant");
        }
        Instant issuedAt = jwt.getIssuedAt();
        if (issuedAt == null || issuedAt.isBefore(clock.instant().minus(maxAge))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "APPROVAL_TOKEN_STALE",
                    "The approver must sign in again to approve (token older than " + maxAge.toMinutes() + " min)");
        }
        boolean authorised = converter.authorities(jwt).stream().map(GrantedAuthority::getAuthority)
                .anyMatch(a -> Arrays.stream(approverRoles).anyMatch(r -> a.equals("ROLE_" + r)));
        String approver = converter.userName(jwt);
        if (!authorised) {
            throw new ApiException(HttpStatus.FORBIDDEN, "APPROVER_NOT_AUTHORISED",
                    "User " + approver + " may not approve this transaction",
                    Map.of("approverRoles", Arrays.asList(approverRoles)));
        }
        return new Approver(approver, converter.accessScope(jwt));
    }
}
