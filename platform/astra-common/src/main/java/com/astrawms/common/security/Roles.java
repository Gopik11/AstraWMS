package com.astrawms.common.security;

/**
 * AstraWMS role catalogue (§G.5.2). Roles are issued by the identity provider as realm/app roles and become Spring
 * authorities {@code ROLE_<name>}. Endpoints declare the roles they accept with {@code @PreAuthorize}, e.g.
 * {@code hasAnyRole('RECEIVER','SUPERVISOR')}; reads only require an authenticated user of the tenant.
 */
public final class Roles {

    public static final String RECEIVER = "RECEIVER";
    public static final String PICKER = "PICKER";
    public static final String INV_ANALYST = "INV_ANALYST";
    public static final String INV_MANAGER = "INV_MANAGER";
    public static final String SUPERVISOR = "SUPERVISOR";
    public static final String QA_MANAGER = "QA_MANAGER";
    public static final String SOLUTION_ADMIN = "SOLUTION_ADMIN";

    /** Technical role of an ERP middleware client (SAP CPI/PI, Oracle OIC). Its token carries the tenant claim. */
    public static final String ERP_INTEGRATION = "ERP_INTEGRATION";

    /**
     * Technical role of a device controller (pick-to-light, robots, AS/RS; ADR-0021). Its token carries the tenant
     * claim; it may only claim, confirm and raise exceptions on tasks of automated zones.
     */
    public static final String AUTOMATION = "AUTOMATION";

    /**
     * Technical role of an AstraWMS service account (OAuth2 client credentials). Service tokens carry no tenant: the
     * calling service names the tenant and acting user in {@code X-Tenant-Id} / {@code X-User-Id}, which are trusted
     * only for this role (trusted subsystem, ADR-0010).
     */
    public static final String WMS_SERVICE = "WMS_SERVICE";

    private Roles() {
    }
}
