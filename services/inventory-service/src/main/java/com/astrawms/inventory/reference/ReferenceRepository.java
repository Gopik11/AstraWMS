package com.astrawms.inventory.reference;

import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.contracts.MasterDataEvents.LocationUpserted;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.inventory.reference.ReferenceData.ItemRef;
import com.astrawms.inventory.reference.ReferenceData.LocationRef;
import com.astrawms.inventory.reference.ReferenceData.UomRef;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReferenceRepository {

    private final JdbcClient jdbc;

    public ReferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ItemRef> item(String ownerId, String itemNo, String siteId) {
        return jdbc.sql("""
                        select owner_id, item_no, site_id, base_uom, lot_controlled, serial_control, shelf_life_days,
                               temperature_class, hazardous, status
                        from ref_item where owner_id = :owner and item_no = :item and site_id = :site""")
                .param("owner", ownerId).param("item", itemNo).param("site", siteId)
                .query((rs, n) -> new ItemRef(rs.getString("owner_id"), rs.getString("item_no"),
                        rs.getString("site_id"), rs.getString("base_uom"), rs.getBoolean("lot_controlled"),
                        rs.getString("serial_control"), (Integer) rs.getObject("shelf_life_days"),
                        rs.getString("temperature_class"), rs.getBoolean("hazardous"), rs.getString("status")))
                .optional();
    }

    public Optional<UomRef> uom(String ownerId, String itemNo, String uom) {
        return jdbc.sql("""
                        select uom, numerator, denominator from ref_item_uom
                        where owner_id = :owner and item_no = :item and uom = :uom""")
                .param("owner", ownerId).param("item", itemNo).param("uom", uom)
                .query((rs, n) -> new UomRef(rs.getString("uom"), rs.getInt("numerator"), rs.getInt("denominator")))
                .optional();
    }

    public Optional<LocationRef> location(String siteId, String locationId) {
        return lockedOrPlain(siteId, List.of(locationId), false).stream().findFirst();
    }

    /**
     * Locks the given locations in a deterministic order ({@code FOR UPDATE}), serialising putaway decisions per
     * location (mixed-item / mixed-lot checks) and avoiding deadlocks between opposite moves.
     */
    public List<LocationRef> lockLocations(String siteId, Collection<String> locationIds) {
        return lockedOrPlain(siteId, locationIds.stream().distinct().sorted().toList(), true);
    }

    private List<LocationRef> lockedOrPlain(String siteId, List<String> ids, boolean lock) {
        return jdbc.sql("""
                        select site_id, location_id, zone_id, location_type, erp_bucket, temperature_class,
                               hazmat_allowed, allow_mixed_items, allow_mixed_lots, status
                        from ref_location where site_id = :site and location_id in (:ids)
                        order by location_id""" + (lock ? " for update" : ""))
                .param("site", siteId).param("ids", ids)
                .query((rs, n) -> new LocationRef(rs.getString("site_id"), rs.getString("location_id"),
                        rs.getString("zone_id"), rs.getString("location_type"), rs.getString("erp_bucket"),
                        rs.getString("temperature_class"), rs.getBoolean("hazmat_allowed"),
                        rs.getBoolean("allow_mixed_items"), rs.getBoolean("allow_mixed_lots"), rs.getString("status")))
                .list();
    }

    /**
     * Applies an item event. Events older than the stored version are ignored (ISD-00 §5 stale rule).
     *
     * @return {@code false} if the event was stale
     */
    public boolean upsertItem(ItemUpserted e) {
        String tenant = TenantContext.tenantId();
        Timestamp changedAt = Timestamp.from(e.sourceChangedAt());
        Optional<Timestamp> stored = jdbc.sql("""
                        select max(source_changed_at) from ref_item where owner_id = :owner and item_no = :item""")
                .param("owner", e.ownerId()).param("item", e.itemNo())
                .query(Timestamp.class).optional();
        if (stored.isPresent() && stored.get().after(changedAt)) {
            return false;
        }
        for (ItemUpserted.Site site : e.sites()) {
            jdbc.sql("""
                            insert into ref_item (tenant_id, owner_id, item_no, site_id, base_uom, lot_controlled,
                                                  serial_control, shelf_life_days, temperature_class, hazardous,
                                                  status, source_changed_at)
                            values (:tenant, :owner, :item, :site, :baseUom, :lot, :serial, :shelf, :temp, :haz,
                                    :status, :changedAt)
                            on conflict (tenant_id, owner_id, item_no, site_id) do update set
                                base_uom = excluded.base_uom, lot_controlled = excluded.lot_controlled,
                                serial_control = excluded.serial_control, shelf_life_days = excluded.shelf_life_days,
                                temperature_class = excluded.temperature_class, hazardous = excluded.hazardous,
                                status = excluded.status, source_changed_at = excluded.source_changed_at
                            where ref_item.source_changed_at <= excluded.source_changed_at""")
                    .param("tenant", tenant).param("owner", e.ownerId()).param("item", e.itemNo())
                    .param("site", site.siteId()).param("baseUom", e.baseUom()).param("lot", site.lotControlled())
                    .param("serial", site.serialControl())
                    .param("shelf", e.shelfLifeDays())
                    .param("temp", e.temperatureClass()).param("haz", e.hazardous())
                    .param("status", site.status() != null ? site.status() : e.status())
                    .param("changedAt", changedAt)
                    .update();
        }
        jdbc.sql("delete from ref_item_uom where owner_id = :owner and item_no = :item")
                .param("owner", e.ownerId()).param("item", e.itemNo()).update();
        for (ItemUpserted.Uom u : e.uoms()) {
            jdbc.sql("""
                            insert into ref_item_uom (tenant_id, owner_id, item_no, uom, numerator, denominator)
                            values (:tenant, :owner, :item, :uom, :num, :den)""")
                    .param("tenant", tenant).param("owner", e.ownerId()).param("item", e.itemNo())
                    .param("uom", u.uom()).param("num", u.numerator()).param("den", u.denominator())
                    .update();
        }
        return true;
    }

    public void upsertLocation(LocationUpserted e) {
        jdbc.sql("""
                        insert into ref_location (tenant_id, site_id, location_id, zone_id, location_type, erp_bucket,
                                                  temperature_class, hazmat_allowed, allow_mixed_items,
                                                  allow_mixed_lots, status, source_changed_at)
                        values (:tenant, :site, :loc, :zone, :type, :bucket, :temp, :haz, :mixItems, :mixLots,
                                :status, :changedAt)
                        on conflict (tenant_id, site_id, location_id) do update set
                            zone_id = excluded.zone_id, location_type = excluded.location_type,
                            erp_bucket = excluded.erp_bucket, temperature_class = excluded.temperature_class,
                            hazmat_allowed = excluded.hazmat_allowed, allow_mixed_items = excluded.allow_mixed_items,
                            allow_mixed_lots = excluded.allow_mixed_lots, status = excluded.status,
                            source_changed_at = excluded.source_changed_at
                        where ref_location.source_changed_at <= excluded.source_changed_at""")
                .param("tenant", TenantContext.tenantId()).param("site", e.siteId()).param("loc", e.locationId())
                .param("zone", e.zoneId()).param("type", e.locationType()).param("bucket", e.erpBucket())
                .param("temp", e.temperatureClass()).param("haz", e.hazmatAllowed())
                .param("mixItems", e.allowMixedItems()).param("mixLots", e.allowMixedLots())
                .param("status", e.status()).param("changedAt", Timestamp.from(e.sourceChangedAt()))
                .update();
    }
}
