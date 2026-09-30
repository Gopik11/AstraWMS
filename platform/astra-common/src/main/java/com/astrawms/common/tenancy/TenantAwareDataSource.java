package com.astrawms.common.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Sets the Postgres session variable {@code app.tenant_id} on every connection checkout.
 *
 * <p>The value is always overwritten (empty string when no tenant is bound), so a pooled connection can never
 * carry a previous tenant into a new request. Row-level security policies compare against this setting;
 * with an empty value no tenant-scoped row is visible. System tables (outbox, inbox, flyway history) carry no
 * RLS policy and remain readable by background relays.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String SET_TENANT = "select set_config('app.tenant_id', ?, false)";

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

    private Connection bind(Connection connection) throws SQLException {
        String tenant = TenantContext.current().map(TenantContext.Scope::tenantId).orElse("");
        try (PreparedStatement ps = connection.prepareStatement(SET_TENANT)) {
            ps.setString(1, tenant);
            ps.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
