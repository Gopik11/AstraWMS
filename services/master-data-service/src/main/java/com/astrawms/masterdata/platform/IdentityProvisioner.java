package com.astrawms.masterdata.platform;

import java.util.List;

/**
 * Creates sign-in accounts in the identity provider (ADR-0030 A): the first administrator of a tenant. The provider
 * holds the passwords; this service never stores one.
 */
public interface IdentityProvisioner {

    /** The roles an account created here may get. Never PLATFORM_ADMIN: that role is the operator's alone. */
    List<String> GRANTABLE_ROLES = List.of("SOLUTION_ADMIN");

    record NewAdmin(String username, String email, String firstName, String lastName) {
    }

    /**
     * @param userId            the provider's id of the new account
     * @param temporaryPassword shown once to the platform administrator; the user must change it at first sign-in
     */
    record CreatedAdmin(String userId, String username, String temporaryPassword) {
    }

    /** The username is taken in the realm (usernames are unique across tenants). */
    class UserExists extends RuntimeException {
        public UserExists(String username) {
            super("Username " + username + " is taken");
        }
    }

    /** The identity provider cannot be reached or refused the provisioning account. */
    class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Creates an enabled account bound to the tenant (attribute {@code tenant_id}), with every site, owner and zone, the
     * role {@code SOLUTION_ADMIN}, and a temporary password. Nothing is left behind when a step fails.
     */
    CreatedAdmin createTenantAdmin(String tenantId, NewAdmin admin);
}
