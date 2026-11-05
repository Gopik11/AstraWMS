package com.astrawms.common.tenancy;

/**
 * A message of a tenant that is suspended, offboarding, purged or unknown to the registry (ADR-0030 A). Not retryable:
 * the message goes to the dead-letter topic at once, with this reason, and is replayed after reactivation.
 */
public class TenantNotActiveException extends RuntimeException {

    public TenantNotActiveException(String tenantId, TenantRegistry.Decision decision) {
        super("TENANT_" + decision + ": tenant " + tenantId + " is not active; replay this message after reactivation");
    }
}
