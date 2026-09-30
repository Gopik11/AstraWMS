package com.astrawms.sapadapter.flow;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.sapadapter.mapping.DelvryMapper.Plant;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SAP plant ↔ AstraWMS site mapping (per tenant). */
@Repository
public class SiteMapRepository {

    private final JdbcClient jdbc;

    public SiteMapRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Plant> byPlant(String werks) {
        return jdbc.sql("select werks, site_id, time_zone, default_owner from site_map where werks = :w")
                .param("w", werks)
                .query((rs, n) -> new Plant(rs.getString(1), rs.getString(2), ZoneId.of(rs.getString(3)), rs.getString(4)))
                .optional();
    }

    public Optional<Plant> bySite(String siteId) {
        return jdbc.sql("select werks, site_id, time_zone, default_owner from site_map where site_id = :s")
                .param("s", siteId)
                .query((rs, n) -> new Plant(rs.getString(1), rs.getString(2), ZoneId.of(rs.getString(3)), rs.getString(4)))
                .optional();
    }

    public void upsert(String werks, String siteId, String timeZone, String defaultOwner) {
        jdbc.sql("""
                        insert into site_map (tenant_id, werks, site_id, time_zone, default_owner)
                        values (:t, :w, :s, :tz, :o)
                        on conflict (tenant_id, werks) do update set site_id = excluded.site_id,
                            time_zone = excluded.time_zone, default_owner = excluded.default_owner""")
                .param("t", TenantContext.tenantId()).param("w", werks).param("s", siteId).param("tz", timeZone)
                .param("o", defaultOwner).update();
    }
}
