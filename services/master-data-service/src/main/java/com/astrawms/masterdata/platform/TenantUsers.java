package com.astrawms.masterdata.platform;

import com.astrawms.common.security.Roles;
import java.util.List;
import java.util.Optional;

/**
 * The sign-in accounts of one tenant in the identity provider (ADR-0031): what a tenant's administrator manages without
 * access to the provider's own console, which in the shared realm would show every tenant's users.
 *
 * <p>Every method takes the tenant and touches only accounts bound to it (attribute {@code tenant_id}). An account of
 * another tenant reads as absent. The tenant binding of an account is never changed here.
 */
public interface TenantUsers {

    /** The roles a tenant's administrator may grant. Never PLATFORM_ADMIN, WMS_SERVICE, ERP_INTEGRATION or AUTOMATION. */
    List<String> ROLES = List.of(Roles.RECEIVER, Roles.PICKER, Roles.INV_ANALYST, Roles.INV_MANAGER, Roles.SUPERVISOR,
            Roles.QA_MANAGER, Roles.SOLUTION_ADMIN);

    /**
     * An account of the tenant.
     *
     * @param sites,owners,zones the access scope ({@code *} for all)
     * @param managed            false when the account holds a role outside {@link #ROLES} (an integration or device
     *                           account) or is a client's service account: it is listed, not changed here
     */
    record User(String id, String username, String email, String firstName, String lastName, boolean enabled,
                List<String> roles, List<String> sites, List<String> owners, List<String> zones, String approvalLimit,
                boolean passwordChangeRequired, Long createdAt, boolean managed) {
    }

    /** What an account should be. Null fields of an update are left as they are. */
    record Spec(String username, String email, String firstName, String lastName, Boolean enabled, List<String> roles,
                List<String> sites, List<String> owners, List<String> zones, String approvalLimit) {
    }

    record Created(User user, String temporaryPassword) {
    }

    /** No account with this id in the tenant (also when it belongs to another tenant). */
    class NoSuchUser extends RuntimeException {
        public NoSuchUser(String userId) {
            super("No user " + userId);
        }
    }

    /** The account is listed but not changed here (see {@link User#managed()}). */
    class NotManaged extends RuntimeException {
        public NotManaged(String username) {
            super(username + " is an integration or service account and is not managed here");
        }
    }

    // Failures of the provider: IdentityProvisioner.Unavailable; a taken username: IdentityProvisioner.UserExists.

    List<User> users(String tenantId);

    Optional<User> user(String tenantId, String userId);

    /** An enabled account with a temporary password the user must change at first sign-in. */
    Created create(String tenantId, Spec spec);

    /** Changes a managed account of the tenant; disabling it also ends its sessions. */
    User update(String tenantId, String userId, Spec change);

    /** A new temporary password, returned once. */
    String resetPassword(String tenantId, String userId);
}
