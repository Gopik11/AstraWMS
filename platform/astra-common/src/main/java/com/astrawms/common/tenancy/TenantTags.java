package com.astrawms.common.tenancy;

/**
 * The {@code tenant} tag of a metric (ADR-0030 E). A tenant is named only when the registry knows it: the tenant comes
 * from a token or a message, and naming any value would let one caller create as many time series as it likes.
 * Everything else is {@value #UNREGISTERED}.
 */
public class TenantTags {

    public static final String UNREGISTERED = "unregistered";

    private final TenantRegistry registry;

    /** @param registry the tenant registry; null where there is none, then every tenant is {@value #UNREGISTERED} */
    public TenantTags(TenantRegistry registry) {
        this.registry = registry;
    }

    public String of(String tenantId) {
        return tenantId != null && registry != null && registry.tenant(tenantId).isPresent() ? tenantId : UNREGISTERED;
    }
}
