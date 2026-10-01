package com.astrawms.common.tenancy;

import com.astrawms.common.security.AccessScope;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Carries the tenant and acting user for the current thread.
 *
 * <p>Set by {@link TenantFilter} for HTTP requests and by {@link #callAs} for message consumers and jobs.
 * {@link TenantAwareDataSource} copies the tenant into the Postgres session so that row-level security
 * policies ({@code tenant_id = current_setting('app.tenant_id')}) isolate tenants in the database itself
 * (MWH-001, NFR-103), not only in application code.
 */
public final class TenantContext {

    /**
     * @param access the user's site / owner / zone / approval-value constraints; unrestricted for service accounts,
     *               message consumers and jobs
     */
    public record Scope(String tenantId, String userId, String channel, AccessScope access) {
        public Scope {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId is required");
            }
            if (access == null) {
                access = AccessScope.UNRESTRICTED;
            }
        }

        public Scope(String tenantId, String userId, String channel) {
            this(tenantId, userId, channel, AccessScope.UNRESTRICTED);
        }
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<Scope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Scope require() {
        Scope scope = CURRENT.get();
        if (scope == null) {
            throw new IllegalStateException("No tenant bound to the current thread");
        }
        return scope;
    }

    public static String tenantId() {
        return require().tenantId();
    }

    public static <T> T callAs(Scope scope, Callable<T> work) {
        Scope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return work.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            restore(previous);
        }
    }

    public static void runAs(Scope scope, Runnable work) {
        callAs(scope, () -> {
            work.run();
            return null;
        });
    }

    static void bind(Scope scope) {
        CURRENT.set(scope);
    }

    static void clear() {
        CURRENT.remove();
    }

    private static void restore(Scope previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
