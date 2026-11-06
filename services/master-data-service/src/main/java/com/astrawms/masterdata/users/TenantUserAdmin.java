package com.astrawms.masterdata.users;

import com.astrawms.common.security.AccessScope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.masterdata.platform.IdentityProvisioner;
import com.astrawms.masterdata.platform.TenantUsers;
import com.astrawms.masterdata.platform.TenantUsers.Spec;
import com.astrawms.masterdata.platform.TenantUsers.User;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A tenant's administrator manages the tenant's sign-in accounts (ADR-0031). The rules are here, the accounts in the
 * identity provider ({@link TenantUsers}):
 * <ul>
 *   <li>the tenant is always the administrator's own: an account of another tenant reads as unknown;</li>
 *   <li>only the tenant roles ({@link TenantUsers#ROLES}) are granted, never a platform, service or integration
 *       role; integration and service accounts are listed, not changed;</li>
 *   <li>an administrator grants no wider sites, owners or zones than their own, and does not manage an account wider
 *       than their own;</li>
 *   <li>an administrator does not change their own roles, scope or enabled state, so nobody locks themselves out;</li>
 *   <li>every change is recorded in {@code tenant_user_audit}; a temporary password is returned once and never
 *       stored.</li>
 * </ul>
 * No database transaction is held while the identity provider is called.
 */
@Service
public class TenantUserAdmin {

    private static final Logger log = LoggerFactory.getLogger(TenantUserAdmin.class);
    private static final String USERNAME = "[a-z0-9][a-z0-9._@-]{2,63}";
    private static final String SCOPE_ID = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

    public record UserRequest(String username, String email, String firstName, String lastName, Boolean enabled,
                              List<String> roles, List<String> sites, List<String> owners, List<String> zones,
                              String approvalLimit) {
    }

    private final TenantUsers users;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TenantUserAdmin(TenantUsers users, JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.users = users;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    public List<User> list() {
        return identity(() -> users.users(TenantContext.tenantId()));
    }

    public User get(String userId) {
        return identity(() -> users.user(TenantContext.tenantId(), userId))
                .orElseThrow(() -> ApiException.notFound("USER_UNKNOWN", "No user " + userId));
    }

    public Map<String, Object> create(UserRequest r) {
        String username = r.username() == null ? "" : r.username().trim().toLowerCase(Locale.ROOT);
        if (!username.matches(USERNAME)) {
            throw ApiException.badRequest("USER_USERNAME_INVALID",
                    "A username is 3-64 lower-case letters, digits, '.', '_', '@' or '-', starting with a letter or digit");
        }
        Spec spec = new Spec(username, email(r.email()), blankToNull(r.firstName()), blankToNull(r.lastName()), true,
                roles(r.roles()), scope("sites", r.sites(), true), scope("owners", r.owners(), true),
                scope("zones", r.zones(), true), approvalLimit(r.approvalLimit()));
        withinOwnScope(spec.sites(), spec.owners(), spec.zones());
        String tenant = TenantContext.tenantId();
        TenantUsers.Created created = identity(() -> users.create(tenant, spec));
        audit(created.user(), "CREATED", describe(created.user()));
        log.info("User {} created in tenant {} by {}", username, tenant, TenantContext.require().userId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", created.user());
        out.put("temporaryPassword", created.temporaryPassword());
        out.put("passwordChangeRequired", true);
        return out;
    }

    public User update(String userId, UserRequest r) {
        User before = get(userId);
        manageable(before);
        Spec change = new Spec(null, r.email() == null ? null : Objects.requireNonNullElse(email(r.email()), ""),
                r.firstName(), r.lastName(), r.enabled(), r.roles() == null ? null : roles(r.roles()),
                scope("sites", r.sites(), false), scope("owners", r.owners(), false), scope("zones", r.zones(), false),
                r.approvalLimit() == null ? null : Objects.requireNonNullElse(approvalLimit(r.approvalLimit()), ""));
        boolean self = before.username().equals(TenantContext.require().userId());
        if (self && (changes(change.roles(), before.roles()) || changes(change.sites(), before.sites())
                || changes(change.owners(), before.owners()) || changes(change.zones(), before.zones())
                || (change.enabled() != null && change.enabled() != before.enabled()))) {
            throw ApiException.conflict("USER_SELF_CHANGE",
                    "You cannot change your own roles, scope or enabled state; ask another administrator");
        }
        withinOwnScope(change.sites(), change.owners(), change.zones());
        User after = identity(() -> users.update(TenantContext.tenantId(), userId, change));
        String action = before.enabled() && !after.enabled() ? "DISABLED"
                : !before.enabled() && after.enabled() ? "ENABLED" : "UPDATED";
        audit(after, action, difference(before, after));
        return after;
    }

    public Map<String, Object> resetPassword(String userId) {
        User user = get(userId);
        manageable(user);
        String password = identity(() -> users.resetPassword(TenantContext.tenantId(), userId));
        audit(user, "PASSWORD_RESET", "temporary password issued");
        return Map.of("userId", userId, "username", user.username(), "temporaryPassword", password,
                "passwordChangeRequired", true);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> audit(int limit) {
        return jdbc.sql("""
                        select user_id, username, action, detail, changed_by, changed_at from tenant_user_audit
                        where tenant_id = :t order by changed_at desc, id desc limit :limit""")
                .param("t", TenantContext.tenantId()).param("limit", limit).query().listOfRows();
    }

    // ------------------------------------------------------------------ rules

    /** Not an integration or service account, and no wider than the administrator. */
    private static void manageable(User user) {
        if (!user.managed()) {
            throw ApiException.conflict("USER_NOT_MANAGED",
                    user.username() + " is an integration or service account; it is not managed here");
        }
        AccessScope own = AccessScope.current();
        if (!within(user.sites(), own.sitesAll(), own.sitesAll() ? List.of() : own.siteList())
                || !within(user.owners(), own.ownersAll(), own.ownersAll() ? List.of() : own.ownerList())
                || !within(user.zones(), own.zonesAll(), own.zonesAll() ? List.of() : own.zoneList())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_SCOPE_EXCEEDS",
                    user.username() + " has sites, owners or zones beyond yours; an administrator with them manages this user");
        }
    }

    /** Null lists (not changed) pass. */
    private static void withinOwnScope(List<String> sites, List<String> owners, List<String> zones) {
        AccessScope own = AccessScope.current();
        if ((sites != null && !within(sites, own.sitesAll(), own.sitesAll() ? List.of() : own.siteList()))
                || (owners != null && !within(owners, own.ownersAll(), own.ownersAll() ? List.of() : own.ownerList()))
                || (zones != null && !within(zones, own.zonesAll(), own.zonesAll() ? List.of() : own.zoneList()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_SCOPE_EXCEEDS", "You can grant only sites, owners and zones you have yourself");
        }
    }

    private static boolean within(List<String> granted, boolean ownAll, List<String> own) {
        return ownAll || (!granted.contains("*") && own.containsAll(granted));
    }

    private static List<String> roles(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw ApiException.badRequest("USER_ROLES_REQUIRED", "A user needs at least one role");
        }
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        for (String role : requested) {
            String r = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
            if (!TenantUsers.ROLES.contains(r)) {
                throw ApiException.badRequest("USER_ROLE_NOT_GRANTABLE",
                        "Role " + role + " cannot be granted here; the roles are " + String.join(", ", TenantUsers.ROLES));
            }
            roles.add(r);
        }
        return List.copyOf(roles);
    }

    /** {@code *} alone, or ids; required when creating, null (unchanged) when not given on an update. */
    private static List<String> scope(String name, List<String> requested, boolean required) {
        if (requested == null) {
            if (required) {
                throw ApiException.badRequest("USER_SCOPE_REQUIRED", "Give the user's " + name + " (or * for all)");
            }
            return null;
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String v : requested) {
            String id = v == null ? "" : v.trim();
            if (id.equals("*")) {
                return List.of("*");
            }
            if (!id.isEmpty()) {
                if (!id.matches(SCOPE_ID)) {
                    throw ApiException.badRequest("USER_SCOPE_INVALID", "Not a valid " + name + " id: " + v);
                }
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            throw ApiException.badRequest("USER_SCOPE_REQUIRED", "Give the user's " + name + " (or * for all)");
        }
        return List.copyOf(ids);
    }

    private static String approvalLimit(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            BigDecimal limit = new BigDecimal(v).stripTrailingZeros();
            if (limit.signum() < 0 || limit.scale() > 2) {
                throw new NumberFormatException();
            }
            return limit.signum() == 0 ? "0" : limit.toPlainString();
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("USER_APPROVAL_LIMIT_INVALID",
                    "An approval limit is an amount of 0 or more, with at most two decimals");
        }
    }

    private static String email(String value) {
        String email = blankToNull(value);
        if (email != null && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw ApiException.badRequest("USER_EMAIL_INVALID", "Not an e-mail address: " + email);
        }
        return email;
    }

    private static boolean changes(List<String> requested, List<String> current) {
        return requested != null && !new LinkedHashSet<>(requested).equals(new LinkedHashSet<>(current));
    }

    private static <T> T identity(Supplier<T> call) {
        try {
            return call.get();
        } catch (IdentityProvisioner.UserExists e) {
            throw ApiException.conflict("USER_USERNAME_TAKEN", e.getMessage() + " (usernames are unique across the system)");
        } catch (TenantUsers.NoSuchUser e) {
            throw ApiException.notFound("USER_UNKNOWN", e.getMessage());
        } catch (TenantUsers.NotManaged e) {
            throw ApiException.conflict("USER_NOT_MANAGED", e.getMessage());
        } catch (IdentityProvisioner.Unavailable e) {
            log.warn("Identity provider call failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "USER_IDENTITY_UNAVAILABLE",
                    "The sign-in service cannot make this change now; nothing was changed. Try again shortly.");
        }
    }

    // ------------------------------------------------------------------ audit

    private void audit(User user, String action, String detail) {
        TenantContext.Scope scope = TenantContext.require();
        tx.executeWithoutResult(s -> jdbc.sql("""
                        insert into tenant_user_audit (tenant_id, user_id, username, action, detail, changed_by, changed_at)
                        values (:t, :id, :u, :action, :detail, :by, :at)""")
                .param("t", scope.tenantId()).param("id", user.id()).param("u", user.username()).param("action", action)
                .param("detail", detail).param("by", scope.userId()).param("at", Timestamp.from(clock.instant())).update());
    }

    private static String describe(User u) {
        return "roles " + String.join(",", u.roles()) + "; sites " + String.join(",", u.sites()) + "; owners "
                + String.join(",", u.owners()) + "; zones " + String.join(",", u.zones())
                + (u.approvalLimit() == null ? "" : "; approval limit " + u.approvalLimit());
    }

    private static String difference(User before, User after) {
        List<String> parts = new ArrayList<>();
        diff(parts, "roles", String.join(",", before.roles()), String.join(",", after.roles()));
        diff(parts, "sites", String.join(",", before.sites()), String.join(",", after.sites()));
        diff(parts, "owners", String.join(",", before.owners()), String.join(",", after.owners()));
        diff(parts, "zones", String.join(",", before.zones()), String.join(",", after.zones()));
        diff(parts, "approval limit", before.approvalLimit(), after.approvalLimit());
        diff(parts, "enabled", String.valueOf(before.enabled()), String.valueOf(after.enabled()));
        diff(parts, "email", before.email(), after.email());
        diff(parts, "name", before.firstName() + " " + before.lastName(), after.firstName() + " " + after.lastName());
        return parts.isEmpty() ? "no change" : String.join("; ", parts);
    }

    private static void diff(List<String> parts, String name, String before, String after) {
        if (!Objects.equals(before, after)) {
            parts.add(name + " " + (before == null ? "-" : before) + " → " + (after == null ? "-" : after));
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
