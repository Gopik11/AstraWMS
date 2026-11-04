package com.astrawms.common.tenancy;

import com.astrawms.common.security.AccessScope;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Sets the Postgres session variables {@code app.tenant_id} and {@code app.owners} on every connection checkout.
 *
 * <p>Both are always overwritten, so a pooled connection never carries a previous request's tenant or owners into a
 * new one. Row-level security policies compare against them:
 * <ul>
 *   <li>{@code app.tenant_id} (ADR-0003): empty when no tenant is bound, so no tenant-scoped row is visible.</li>
 *   <li>{@code app.owners} (ADR-0030 C): {@code *} for a scope unrestricted by owner (service accounts, message
 *       consumers, jobs, users with every owner); otherwise the user's owners separated by commas, read by the
 *       restrictive {@code owner_isolation} policies. Empty when the scope allows no owner or nothing is bound, so no
 *       owner-scoped row is visible. An owner id containing a comma cannot be expressed and is left out (fail
 *       closed).</li>
 * </ul>
 * System tables (outbox, inbox, flyway history) carry no RLS policy and remain readable by background relays.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    /** The value of {@code app.owners} for a scope that sees every owner of its tenant. */
    public static final String ALL_OWNERS = "*";

    private static final String SET_SCOPE =
            "select set_config('app.tenant_id', ?, false), set_config('app.owners', ?, false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bind(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bind(super.getConnection(username, password));
    }

    /** The {@code app.owners} value for a scope: {@code *}, the owners joined by commas, or empty. */
    public static String owners(AccessScope access) {
        if (access == null || access.ownersAll()) {
            return ALL_OWNERS;
        }
        Set<String> owners = access.owners();
        return owners.stream()
                .filter(o -> o != null && !o.isEmpty() && !o.contains(",") && !ALL_OWNERS.equals(o))
                .sorted()
                .collect(Collectors.joining(","));
    }

    private Connection bind(Connection connection) throws SQLException {
        var scope = TenantContext.current();
        String tenant = scope.map(TenantContext.Scope::tenantId).orElse("");
        String owners = scope.map(s -> owners(s.access())).orElse("");
        try (PreparedStatement ps = connection.prepareStatement(SET_SCOPE)) {
            ps.setString(1, tenant);
            ps.setString(2, owners);
            ps.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
