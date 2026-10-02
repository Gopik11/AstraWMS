package com.astrawms.inbound.api;

import com.astrawms.common.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * ADR-0021: receiving is floor work, done on RF (receiving tasks of the task service, which call this service on the
 * RF channel). The desktop is the supervisors' exception console: only a supervisor receives from it. The channel
 * header is trusted because the gateway strips it from outside requests; only services set it.
 */
final class RfOnly {

    static final String RF_CHANNEL = "RF";

    private RfOnly() {
    }

    static void require(String what) {
        boolean rf = TenantContext.current().map(s -> RF_CHANNEL.equals(s.channel())).orElse(false);
        boolean supervisor = SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPERVISOR".equals(a.getAuthority()));
        if (!rf && !supervisor) {
            throw new com.astrawms.common.web.ApiException(HttpStatus.FORBIDDEN, "RF_ONLY",
                    what + " is done on RF (Work on the handheld); from the desktop only a supervisor can, as an exception");
        }
    }
}
