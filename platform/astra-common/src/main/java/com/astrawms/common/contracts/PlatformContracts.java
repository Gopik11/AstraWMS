package com.astrawms.common.contracts;

import java.time.Instant;

/**
 * Platform contracts (ADR-0030 A): the tenant registry, owned by the master data service and read by every service.
 *
 * <p>{@link #TOPIC_TENANTS} is compacted: the latest {@link TenantChanged} of every tenant stays on it for good, so a
 * service that starts reads every tenant from the beginning, however long ago the tenant was created.
 */
public final class PlatformContracts {

    public static final String TOPIC_TENANTS = "wms.platform.tenants.v1";

    public static final String ACTIVE = "ACTIVE";
    /** Sign-in and API refused; messages dead-lettered for replay after reactivation; data kept. */
    public static final String SUSPENDED = "SUSPENDED";
    /** Read-only, for an export before the tenant is purged. */
    public static final String OFFBOARDING = "OFFBOARDING";
    /** Data deleted; the row stays so the id is never reused. */
    public static final String PURGED = "PURGED";

    private PlatformContracts() {
    }

    /**
     * The full state of one tenant, keyed by the tenant; a consumer keeps the highest {@code version} it has seen.
     *
     * @param plan the quota set (ADR-0030 E)
     * @param cell the deployment cell the tenant lives in (ADR-0030 H); {@code main} until there is a second
     */
    public record TenantChanged(String tenantId, String displayName, String status, String plan, String cell,
                                String timeZone, String reportingCurrency, long version, Instant changedAt) {
        public static final String TYPE = "TenantChanged";
        public static final String VERSION = "1.0";
        /** Business key of every TenantChanged, so the topic's key is the same for one tenant and compaction keeps the last. */
        public static final String KEY = "tenant";
    }
}
