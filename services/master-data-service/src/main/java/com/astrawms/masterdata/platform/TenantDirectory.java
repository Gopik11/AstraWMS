package com.astrawms.masterdata.platform;

import com.astrawms.common.contracts.PlatformContracts;
import com.astrawms.common.contracts.PlatformContracts.TenantChanged;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tenant registry (ADR-0030 A): who the tenants are and whether they may work. Every change is published as
 * {@link TenantChanged} on the compacted {@link PlatformContracts#TOPIC_TENANTS}, from which every service's
 * {@code TenantRegistry} reads. The registry tables are platform tables, read across tenants; the platform API that
 * changes them requires {@code PLATFORM_ADMIN}.
 */
@Service
public class TenantDirectory {

    private static final Logger log = LoggerFactory.getLogger(TenantDirectory.class);

    /** Status changes the platform API makes. PURGED is set by the purge procedure (ADR-0030 D), never here. */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            PlatformContracts.ACTIVE, Set.of(PlatformContracts.SUSPENDED, PlatformContracts.OFFBOARDING),
            PlatformContracts.SUSPENDED, Set.of(PlatformContracts.ACTIVE, PlatformContracts.OFFBOARDING),
            PlatformContracts.OFFBOARDING, Set.of(PlatformContracts.ACTIVE));

    public record TenantRequest(String tenantId, String displayName, String plan, String timeZone, String reportingCurrency) {
    }

    public record StatusRequest(String status, String reason) {
    }

    public record AdminRequest(String username, String email, String firstName, String lastName) {
    }

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final IdentityProvisioner identity;

    public TenantDirectory(JdbcClient jdbc, OutboxWriter outbox, TransactionTemplate tx, Clock clock,
                           IdentityProvisioner identity) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
        this.identity = identity;
    }

    // cross-tenant: the registry's view; every use below filters by tenant_id or is itself cross-tenant
    private static final String VIEW = """
            select tenant_id, display_name, status, plan, cell, time_zone, reporting_currency, version, created_at,
                   created_by, status_changed_at, status_changed_by, status_reason
            from tenant""";

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        // cross-tenant: the registry lists every tenant (platform API and the services' status read)
        return jdbc.sql(VIEW + " order by tenant_id").query().listOfRows();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String tenantId) {
        return jdbc.sql(VIEW + " where tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("PLATFORM_TENANT_UNKNOWN", "No tenant " + tenantId));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(String tenantId) {
        get(tenantId);
        return jdbc.sql("""
                        select from_status, to_status, reason, changed_by, changed_at from tenant_status_change
                        where tenant_id = :t order by changed_at, id""").param("t", tenantId).query().listOfRows();
    }

    @Transactional
    public Map<String, Object> create(TenantRequest r, String user) {
        String id = r.tenantId() == null ? "" : r.tenantId().trim();
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw ApiException.badRequest("PLATFORM_TENANT_ID_INVALID", "A tenant id is 1-64 letters, digits, '-' or '_'");
        }
        validate(r);
        Instant now = clock.instant();
        int created = jdbc.sql("""
                        insert into tenant (tenant_id, display_name, status, plan, time_zone, reporting_currency, created_at,
                                            created_by, status_changed_at, status_changed_by, status_reason)
                        values (:t, :name, 'ACTIVE', :plan, :tz, :cur, :now, :user, :now, :user, 'Created')
                        on conflict (tenant_id) do nothing""")
                .param("t", id).param("name", r.displayName() == null || r.displayName().isBlank() ? id : r.displayName().trim())
                .param("plan", nz(r.plan(), "STANDARD")).param("tz", nz(r.timeZone(), "UTC"))
                .param("cur", nz(r.reportingCurrency(), "USD")).param("now", Timestamp.from(now)).param("user", user)
                .update();
        if (created == 0) {
            throw ApiException.conflict("PLATFORM_TENANT_EXISTS", "Tenant " + id + " already exists");
        }
        record(id, null, PlatformContracts.ACTIVE, "Created", user, now);
        publishPending(id);
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String tenantId, TenantRequest r, String user) {
        validate(r);
        int n = jdbc.sql("""
                        update tenant set display_name = coalesce(:name, display_name), plan = coalesce(:plan, plan),
                            time_zone = coalesce(:tz, time_zone), reporting_currency = coalesce(:cur, reporting_currency),
                            version = version + 1
                        where tenant_id = :t""")
                .param("name", blankToNull(r.displayName())).param("plan", blankToNull(r.plan()))
                .param("tz", blankToNull(r.timeZone())).param("cur", blankToNull(r.reportingCurrency())).param("t", tenantId)
                .update();
        if (n == 0) {
            throw ApiException.notFound("PLATFORM_TENANT_UNKNOWN", "No tenant " + tenantId);
        }
        publishPending(tenantId);
        return get(tenantId);
    }

    @Transactional
    public Map<String, Object> changeStatus(String tenantId, StatusRequest r, String user) {
        String to = r.status() == null ? "" : r.status().trim().toUpperCase();
        if (r.reason() == null || r.reason().isBlank()) {
            throw ApiException.badRequest("PLATFORM_REASON_REQUIRED", "A status change needs a reason");
        }
        String from = jdbc.sql("select status from tenant where tenant_id = :t for update").param("t", tenantId)
                .query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("PLATFORM_TENANT_UNKNOWN", "No tenant " + tenantId));
        if (from.equals(to)) {
            return get(tenantId);
        }
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.conflict("PLATFORM_STATUS_TRANSITION", "A tenant that is " + from + " cannot become "
                    + (to.isEmpty() ? "nothing" : to) + " here" + (PlatformContracts.PURGED.equals(to)
                    ? ": purging is the purge procedure's (ADR-0030 D)" : ""));
        }
        Instant now = clock.instant();
        jdbc.sql("""
                        update tenant set status = :to, status_changed_at = :now, status_changed_by = :user,
                            status_reason = :reason, version = version + 1
                        where tenant_id = :t""")
                .param("to", to).param("now", Timestamp.from(now)).param("user", user).param("reason", r.reason().trim())
                .param("t", tenantId).update();
        record(tenantId, from, to, r.reason().trim(), user, now);
        publishPending(tenantId);
        log.info("Tenant {} is now {} (was {}), by {}: {}", tenantId, to, from, user, r.reason().trim());
        return get(tenantId);
    }

    // ------------------------------------------------------------------ publishing

    /** Publishes every tenant version not yet published: tenants seeded by the migration, and a safety net. */
    /**
     * Creates an administrator of an active tenant in the identity provider: every site, owner and zone of the tenant,
     * the role SOLUTION_ADMIN, and a temporary password returned here once and never stored. No transaction is held
     * while the provider is called; the account is recorded once it exists.
     */
    public Map<String, Object> createAdmin(String tenantId, AdminRequest r, String user) {
        String username = r.username() == null ? "" : r.username().trim().toLowerCase(Locale.ROOT);
        if (!username.matches("[a-z0-9][a-z0-9._@-]{2,63}")) {
            throw ApiException.badRequest("PLATFORM_USERNAME_INVALID",
                    "A username is 3-64 lower-case letters, digits, '.', '_', '@' or '-', starting with a letter or digit");
        }
        String email = blankToNull(r.email());
        if (email != null && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw ApiException.badRequest("PLATFORM_EMAIL_INVALID", "Not an e-mail address: " + email);
        }
        Object status = tx.execute(s -> get(tenantId).get("status"));
        if (!PlatformContracts.ACTIVE.equals(status)) {
            throw ApiException.conflict("PLATFORM_TENANT_NOT_ACTIVE",
                    "Tenant " + tenantId + " is " + status + "; administrators are created for active tenants only");
        }
        IdentityProvisioner.CreatedAdmin created;
        try {
            created = identity.createTenantAdmin(tenantId,
                    new IdentityProvisioner.NewAdmin(username, email, blankToNull(r.firstName()), blankToNull(r.lastName())));
        } catch (IdentityProvisioner.UserExists e) {
            throw ApiException.conflict("PLATFORM_USERNAME_TAKEN", "Username " + username + " is taken");
        } catch (IdentityProvisioner.Unavailable e) {
            log.warn("Tenant administrator {} for {} not created: {}", username, tenantId, e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLATFORM_IDENTITY_UNAVAILABLE",
                    "The identity provider cannot create the account now; nothing was created");
        }
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> jdbc.sql("""
                        insert into tenant_admin_account (tenant_id, username, identity_user_id, email, created_by, created_at)
                        values (:t, :u, :id, :email, :user, :now)""")
                .param("t", tenantId).param("u", created.username()).param("id", created.userId()).param("email", email)
                .param("user", user).param("now", Timestamp.from(now)).update());
        log.info("Tenant administrator {} created for {} by {}", created.username(), tenantId, user);
        return Map.of("tenantId", tenantId, "username", created.username(), "userId", created.userId(),
                "roles", IdentityProvisioner.GRANTABLE_ROLES, "temporaryPassword", created.temporaryPassword(),
                "passwordChangeRequired", true);
    }

    /** The administrators the platform API created for a tenant (never a password). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> admins(String tenantId) {
        get(tenantId);
        return jdbc.sql("""
                        select username, identity_user_id, email, created_by, created_at from tenant_admin_account
                        where tenant_id = :t order by created_at, id""").param("t", tenantId).query().listOfRows();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${astra.tenancy.publish-interval-ms:60000}", initialDelayString = "${astra.tenancy.publish-interval-ms:60000}")
    public void publishAllPending() {
        // cross-tenant: tenants whose latest version has not been published yet
        List<String> pending = jdbc.sql("select tenant_id from tenant where version > published_version order by tenant_id")
                .query(String.class).list();
        for (String t : pending) {
            tx.executeWithoutResult(s -> publishPending(t));
        }
        if (!pending.isEmpty()) {
            log.info("Tenant registry: published {} tenant state(s)", pending.size());
        }
    }

    private void publishPending(String tenantId) {
        TenantChanged t = jdbc.sql("""
                        select tenant_id, display_name, status, plan, cell, time_zone, reporting_currency, version,
                               status_changed_at
                        from tenant where tenant_id = :t and version > published_version for update""")
                .param("t", tenantId)
                .query((rs, n) -> new TenantChanged(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getTimestamp(9).toInstant()))
                .optional().orElse(null);
        if (t == null) {
            return;
        }
        TenantContext.runAs(new TenantContext.Scope(tenantId, "system:tenant-registry", "SYSTEM"), () ->
                outbox.append(new OutboxWriter.Message(PlatformContracts.TOPIC_TENANTS, TenantChanged.TYPE,
                        TenantChanged.VERSION, null, null, null, TenantChanged.KEY, t)));
        jdbc.sql("update tenant set published_version = :v where tenant_id = :t").param("v", t.version())
                .param("t", tenantId).update();
    }

    private void record(String tenantId, String from, String to, String reason, String user, Instant at) {
        jdbc.sql("""
                        insert into tenant_status_change (tenant_id, from_status, to_status, reason, changed_by, changed_at)
                        values (:t, :from, :to, :reason, :user, :at)""")
                .param("t", tenantId).param("from", from).param("to", to).param("reason", reason).param("user", user)
                .param("at", Timestamp.from(at)).update();
    }

    private static void validate(TenantRequest r) {
        if (r.reportingCurrency() != null && !r.reportingCurrency().isBlank() && !r.reportingCurrency().matches("[A-Z]{3}")) {
            throw ApiException.badRequest("PLATFORM_CURRENCY_INVALID", "A reporting currency is an ISO 4217 code, e.g. EUR");
        }
        if (r.timeZone() != null && !r.timeZone().isBlank()) {
            try {
                ZoneId.of(r.timeZone().trim());
            } catch (RuntimeException e) {
                throw ApiException.badRequest("PLATFORM_TIME_ZONE_INVALID", "Unknown time zone " + r.timeZone());
            }
        }
    }

    private static String nz(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
