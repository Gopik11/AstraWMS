package com.astrawms.masterdata.service;

import com.astrawms.common.contracts.MasterDataEvents;
import com.astrawms.common.contracts.MasterDataEvents.LocationUpserted;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.masterdata.api.MasterDataDtos.GenerateRequest;
import com.astrawms.masterdata.api.MasterDataDtos.GenerateResult;
import com.astrawms.masterdata.api.MasterDataDtos.LocationRequest;
import com.astrawms.masterdata.api.MasterDataDtos.LocationStatus;
import com.astrawms.masterdata.api.MasterDataDtos.LocationView;
import com.astrawms.masterdata.api.MasterDataDtos.Page;
import com.astrawms.masterdata.api.MasterDataDtos.SiteRequest;
import com.astrawms.masterdata.api.MasterDataDtos.ZoneRequest;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sites, zones and locations. Locations inherit ERP bucket, temperature class and hazmat flag from their zone
 * unless overridden; {@code LocationUpserted} always carries the effective values, and a zone change republishes
 * every location of the zone.
 */
@Service
public class LocationService {

    public static final int MAX_GENERATE = 10_000;

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final Clock clock;

    public LocationService(JdbcClient jdbc, OutboxWriter outbox, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ sites and zones

    @Transactional
    public void upsertSite(String siteId, SiteRequest r) {
        try {
            ZoneId.of(r.timeZone());
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("MD_TIME_ZONE_INVALID", "Unknown time zone " + r.timeZone());
        }
        jdbc.sql("""
                        insert into site (tenant_id, site_id, name, time_zone, erp_site)
                        values (:tenant, :site, :name, :tz, :erp)
                        on conflict (tenant_id, site_id) do update set
                            name = excluded.name, time_zone = excluded.time_zone, erp_site = excluded.erp_site""")
                .param("tenant", TenantContext.tenantId()).param("site", siteId).param("name", r.name())
                .param("tz", r.timeZone()).param("erp", r.erpSite()).update();
    }

    @Transactional
    public int upsertZone(String siteId, String zoneId, ZoneRequest r) {
        requireSite(siteId);
        boolean changed = jdbc.sql("""
                        insert into zone (tenant_id, site_id, zone_id, zone_type, erp_bucket, temperature_class, hazmat_allowed)
                        values (:tenant, :site, :zone, :type, :bucket, :temp, :haz)
                        on conflict (tenant_id, site_id, zone_id) do update set
                            zone_type = excluded.zone_type, erp_bucket = excluded.erp_bucket,
                            temperature_class = excluded.temperature_class, hazmat_allowed = excluded.hazmat_allowed
                        where (zone.zone_type, zone.erp_bucket, zone.temperature_class, zone.hazmat_allowed)
                              is distinct from
                              (excluded.zone_type, excluded.erp_bucket, excluded.temperature_class, excluded.hazmat_allowed)
                        returning zone_id""")
                .param("tenant", TenantContext.tenantId()).param("site", siteId).param("zone", zoneId)
                .param("type", r.zoneType()).param("bucket", r.erpBucket()).param("temp", r.temperatureClass())
                .param("haz", r.hazmatAllowed())
                .query(String.class).optional().isPresent();
        if (!changed) {
            return 0;
        }
        // Inherited attributes may have changed for every location in the zone.
        List<LocationView> affected = jdbc.sql(EFFECTIVE + " where l.site_id = :site and l.zone_id = :zone order by l.location_id")
                .param("site", siteId).param("zone", zoneId).query(LocationService::view).list();
        affected.forEach(this::publish);
        return affected.size();
    }

    // ------------------------------------------------------------------ locations

    @Transactional
    public LocationView upsertLocation(String siteId, String locationId, LocationRequest r) {
        requireZone(siteId, r.zoneId());
        upsert(siteId, locationId, r);
        return getLocation(siteId, locationId);
    }

    @Transactional
    public GenerateResult generate(String siteId, String zoneId, GenerateRequest g) {
        requireZone(siteId, zoneId);
        if (g.bayTo() < g.bayFrom() || g.positionTo() < g.positionFrom()) {
            throw ApiException.unprocessable("MD_RANGE_INVALID", "Range end must not be before range start");
        }
        long count = (long) g.aisles().size() * (g.bayTo() - g.bayFrom() + 1) * g.levels().size()
                * (g.positionTo() - g.positionFrom() + 1);
        if (count > MAX_GENERATE) {
            throw ApiException.unprocessable("MD_GENERATE_TOO_LARGE",
                    "Request would generate " + count + " locations; maximum is " + MAX_GENERATE);
        }
        int width = g.numberWidth() == null ? 2 : g.numberWidth();
        LocationRequest template = new LocationRequest(zoneId, g.locationType(), null, null, null,
                g.allowMixedItems(), g.allowMixedLots(), null, null, null, null, null, null, LocationStatus.ACTIVE);
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int seq = 0;
        List<String> samples = new ArrayList<>();
        for (String aisle : g.aisles()) {
            for (int bay = g.bayFrom(); bay <= g.bayTo(); bay++) {
                for (String level : g.levels()) {
                    for (int pos = g.positionFrom(); pos <= g.positionTo(); pos++) {
                        String id = g.pattern()
                                .replace("{aisle}", aisle)
                                .replace("{bay}", pad(bay, width))
                                .replace("{level}", level)
                                .replace("{position}", pad(pos, width));
                        LocationRequest r = new LocationRequest(template.zoneId(), template.locationType(), null,
                                null, null, template.allowMixedItems(), template.allowMixedLots(), null, null,
                                ++seq, null, null, null, LocationStatus.ACTIVE);
                        switch (upsert(siteId, id, r)) {
                            case CREATED -> created++;
                            case UPDATED -> updated++;
                            case UNCHANGED -> unchanged++;
                        }
                        if (samples.size() < 5) {
                            samples.add(id);
                        }
                    }
                }
            }
        }
        return new GenerateResult(created, updated, unchanged, samples);
    }

    /**
     * Publishes every location of a site again, so consumers whose projections predate a contract change (e.g. the
     * 1.2 zone type) or were rebuilt catch up. Consumers are idempotent and stale-safe.
     */
    @Transactional
    public int republish(String siteId) {
        requireSite(siteId);
        List<LocationView> all = jdbc.sql(EFFECTIVE + " where l.site_id = :site order by l.location_id")
                .param("site", siteId).query(LocationService::view).list();
        all.forEach(this::publish);
        return all.size();
    }

    @Transactional(readOnly = true)
    public LocationView getLocation(String siteId, String locationId) {
        return jdbc.sql(EFFECTIVE + " where l.site_id = :site and l.location_id = :loc")
                .param("site", siteId).param("loc", locationId).query(LocationService::view).optional()
                .orElseThrow(() -> ApiException.notFound("MD_LOCATION_UNKNOWN", "Location " + locationId + " not found"));
    }

    @Transactional(readOnly = true)
    public Page<LocationView> listLocations(String siteId, String zoneId, String after, int limit) {
        int size = Math.max(1, Math.min(limit, 500));
        List<LocationView> rows = jdbc.sql(EFFECTIVE + " " + """
                         where l.site_id = :site and l.location_id > :after
                           and (cast(:zone as text) is null or l.zone_id = :zone)
                        order by l.location_id limit :limit""")
                .param("site", siteId).param("after", after == null ? "" : after).param("zone", zoneId)
                .param("limit", size + 1)
                .query(LocationService::view).list();
        List<LocationView> items = rows.subList(0, Math.min(size, rows.size()));
        return new Page<>(new ArrayList<>(items), rows.size() > size ? items.getLast().locationId() : null);
    }

    /** Stable 2-digit check digit printed on the location label and scanned/spoken to confirm arrival (§G.3). */
    public static String checkDigit(String siteId, String locationId) {
        CRC32 crc = new CRC32();
        crc.update((siteId + "/" + locationId).getBytes(StandardCharsets.UTF_8));
        return String.valueOf(10 + crc.getValue() % 90);
    }

    // ------------------------------------------------------------------ internals

    private enum Outcome { CREATED, UPDATED, UNCHANGED }

    private static final String EFFECTIVE = """
            select l.site_id, l.location_id, l.zone_id, l.location_type, l.check_digit,
                   coalesce(l.erp_bucket, z.erp_bucket) as erp_bucket,
                   coalesce(l.temperature_class, z.temperature_class) as temperature_class,
                   coalesce(l.hazmat_allowed, z.hazmat_allowed) as hazmat_allowed,
                   l.allow_mixed_items, l.allow_mixed_lots, l.max_weight_kg, l.max_volume_m3, l.pick_seq,
                   l.status, l.updated_at, z.zone_type
            from location l join zone z on z.site_id = l.site_id and z.zone_id = l.zone_id""";

    private Outcome upsert(String siteId, String locationId, LocationRequest r) {
        Instant now = clock.instant();
        Optional<Boolean> inserted = jdbc.sql("""
                        insert into location (tenant_id, site_id, location_id, zone_id, location_type, check_digit,
                                              erp_bucket, temperature_class, hazmat_allowed, allow_mixed_items,
                                              allow_mixed_lots, max_weight_kg, max_volume_m3, pick_seq, x, y, z,
                                              status, updated_at)
                        values (:tenant, :site, :loc, :zone, :type, :cd, :bucket, :temp, :haz, :mixItems, :mixLots,
                                :maxKg, :maxM3, :seq, :x, :y, :z, :status, :now)
                        on conflict (tenant_id, site_id, location_id) do update set
                            zone_id = excluded.zone_id, location_type = excluded.location_type,
                            erp_bucket = excluded.erp_bucket, temperature_class = excluded.temperature_class,
                            hazmat_allowed = excluded.hazmat_allowed, allow_mixed_items = excluded.allow_mixed_items,
                            allow_mixed_lots = excluded.allow_mixed_lots, max_weight_kg = excluded.max_weight_kg,
                            max_volume_m3 = excluded.max_volume_m3, pick_seq = excluded.pick_seq, x = excluded.x,
                            y = excluded.y, z = excluded.z, status = excluded.status, updated_at = excluded.updated_at
                        where (location.zone_id, location.location_type, location.erp_bucket,
                               location.temperature_class, location.hazmat_allowed, location.allow_mixed_items,
                               location.allow_mixed_lots, location.max_weight_kg, location.max_volume_m3,
                               location.pick_seq, location.x, location.y, location.z, location.status)
                              is distinct from
                              (excluded.zone_id, excluded.location_type, excluded.erp_bucket,
                               excluded.temperature_class, excluded.hazmat_allowed, excluded.allow_mixed_items,
                               excluded.allow_mixed_lots, excluded.max_weight_kg, excluded.max_volume_m3,
                               excluded.pick_seq, excluded.x, excluded.y, excluded.z, excluded.status)
                        returning (xmax = 0) as inserted""")
                .param("tenant", TenantContext.tenantId()).param("site", siteId).param("loc", locationId)
                .param("zone", r.zoneId()).param("type", r.locationType()).param("cd", checkDigit(siteId, locationId))
                .param("bucket", r.erpBucket()).param("temp", r.temperatureClass()).param("haz", r.hazmatAllowed())
                .param("mixItems", r.allowMixedItems() == null || r.allowMixedItems())
                .param("mixLots", r.allowMixedLots() == null || r.allowMixedLots())
                .param("maxKg", r.maxWeightKg()).param("maxM3", r.maxVolumeM3()).param("seq", r.pickSeq())
                .param("x", r.x()).param("y", r.y()).param("z", r.z())
                .param("status", (r.status() == null ? LocationStatus.ACTIVE : r.status()).name())
                .param("now", Timestamp.from(now))
                .query(Boolean.class).optional();
        if (inserted.isEmpty()) {
            return Outcome.UNCHANGED;
        }
        publish(getLocation(siteId, locationId));
        return inserted.get() ? Outcome.CREATED : Outcome.UPDATED;
    }

    private void publish(LocationView v) {
        outbox.append(new OutboxWriter.Message(MasterDataEvents.TOPIC, MasterDataEvents.LOCATION_UPSERTED,
                MasterDataEvents.SCHEMA_VERSION, null, v.siteId(), null, v.siteId() + ":" + v.locationId(),
                new LocationUpserted(v.siteId(), v.locationId(), v.zoneId(), v.locationType(), v.erpBucket(),
                        v.temperatureClass(), v.hazmatAllowed(), v.allowMixedItems(), v.allowMixedLots(),
                        v.status().name(), clock.instant(), v.checkDigit(), v.pickSeq(), v.zoneType())));
    }

    private void requireSite(String siteId) {
        if (jdbc.sql("select count(*) from site where site_id = :site").param("site", siteId)
                .query(Integer.class).single() == 0) {
            throw ApiException.unprocessable("MD_SITE_UNKNOWN", "Site " + siteId + " does not exist");
        }
    }

    private void requireZone(String siteId, String zoneId) {
        if (jdbc.sql("select count(*) from zone where site_id = :site and zone_id = :zone")
                .param("site", siteId).param("zone", zoneId).query(Integer.class).single() == 0) {
            throw ApiException.unprocessable("MD_ZONE_UNKNOWN", "Zone " + zoneId + " does not exist at site " + siteId);
        }
    }

    private static LocationView view(ResultSet rs, int n) throws SQLException {
        return new LocationView(rs.getString("site_id"), rs.getString("location_id"), rs.getString("zone_id"),
                rs.getString("location_type"), rs.getString("check_digit"), rs.getString("erp_bucket"),
                rs.getString("temperature_class"), rs.getBoolean("hazmat_allowed"),
                rs.getBoolean("allow_mixed_items"), rs.getBoolean("allow_mixed_lots"),
                rs.getBigDecimal("max_weight_kg"), rs.getBigDecimal("max_volume_m3"),
                (Integer) rs.getObject("pick_seq"), LocationStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("zone_type"));
    }

    private static String pad(int value, int width) {
        return String.format("%0" + width + "d", value);
    }
}
