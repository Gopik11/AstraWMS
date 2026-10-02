package com.astrawms.task.projection;

import com.astrawms.common.contracts.InventoryContracts.InventoryChanged;
import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.contracts.MasterDataEvents.LocationUpserted;
import com.astrawms.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read models this service needs: items, locations and stock per balance key (event-carried state). */
@Repository
public class Projections {

    private final JdbcClient jdbc;

    public Projections(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Item(String ownerId, String itemNo, String temperatureClass, boolean hazardous) {
    }

    public record Location(String siteId, String locationId, String zoneId, String locationType,
                           String temperatureClass, boolean hazmatAllowed, boolean allowMixedItems,
                           boolean allowMixedLots, String status, String checkDigit, Integer pickSeq,
                           String zoneType) {
    }

    /** A fixed pick face of an item (min/max rule of the inventory service). */
    public record PickFace(String locationId, String ownerId, String itemNo, BigDecimal maxQty) {
    }

    /** One balance inside an LPN or location. */
    public record Stock(String ownerId, String itemNo, String lotNo, String lpnId, String locationId, String status,
                        BigDecimal qty) {
    }

    // ------------------------------------------------------------------ updates

    public void upsertItem(ItemUpserted e) {
        jdbc.sql("""
                        insert into ref_item (tenant_id, owner_id, item_no, temperature_class, hazardous, source_changed_at)
                        values (:t, :owner, :item, :temp, :haz, :at)
                        on conflict (tenant_id, owner_id, item_no) do update set
                            temperature_class = excluded.temperature_class, hazardous = excluded.hazardous,
                            source_changed_at = excluded.source_changed_at
                        where ref_item.source_changed_at <= excluded.source_changed_at""")
                .param("t", TenantContext.tenantId()).param("owner", e.ownerId()).param("item", e.itemNo())
                .param("temp", e.temperatureClass()).param("haz", e.hazardous())
                .param("at", Timestamp.from(e.sourceChangedAt())).update();
        // GTINs for RF item scans (ItemUpserted 1.3); an older message without GTINs leaves them as they are.
        if (e.uoms() != null && e.uoms().stream().anyMatch(u -> u.gtin() != null)) {
            jdbc.sql("delete from ref_item_gtin where owner_id = :o and item_no = :i")
                    .param("o", e.ownerId()).param("i", e.itemNo()).update();
            e.uoms().stream().filter(u -> u.gtin() != null && !u.gtin().isBlank()).forEach(u -> jdbc.sql("""
                            insert into ref_item_gtin (tenant_id, owner_id, item_no, gtin, uom) values (:t, :o, :i, :g, :u)
                            on conflict do nothing""")
                    .param("t", TenantContext.tenantId()).param("o", e.ownerId()).param("i", e.itemNo())
                    .param("g", normaliseGtin(u.gtin())).param("u", u.uom()).update());
        }
    }

    /** GTIN-8/12/13/14 are compared as GTIN-14 without leading zeros. */
    public static String normaliseGtin(String gtin) {
        String digits = gtin == null ? "" : gtin.trim();
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }

    /** Whether a scan identifies the item: its item number, or a GTIN of one of its units (ADR-0020). */
    public boolean scanMatchesItem(String ownerId, String itemNo, String scan) {
        if (scan == null || scan.isBlank()) {
            return false;
        }
        String s = scan.trim();
        if (s.equalsIgnoreCase(itemNo)) {
            return true;
        }
        if (!s.chars().allMatch(Character::isDigit)) {
            return false;
        }
        return jdbc.sql("select exists (select 1 from ref_item_gtin where owner_id = :o and item_no = :i and gtin = :g)")
                .param("o", ownerId).param("i", itemNo).param("g", normaliseGtin(s)).query(Boolean.class).single();
    }

    public void upsertLocation(LocationUpserted e) {
        jdbc.sql("""
                        insert into ref_location (tenant_id, site_id, location_id, zone_id, location_type,
                            temperature_class, hazmat_allowed, allow_mixed_items, allow_mixed_lots, status,
                            check_digit, pick_seq, source_changed_at, zone_type)
                        values (:t, :site, :loc, :zone, :type, :temp, :haz, :mixItems, :mixLots, :status, :cd, :seq, :at,
                                :zoneType)
                        on conflict (tenant_id, site_id, location_id) do update set
                            zone_id = excluded.zone_id, location_type = excluded.location_type,
                            temperature_class = excluded.temperature_class, hazmat_allowed = excluded.hazmat_allowed,
                            allow_mixed_items = excluded.allow_mixed_items, allow_mixed_lots = excluded.allow_mixed_lots,
                            status = excluded.status, check_digit = excluded.check_digit, pick_seq = excluded.pick_seq,
                            source_changed_at = excluded.source_changed_at,
                            zone_type = coalesce(excluded.zone_type, ref_location.zone_type)
                        where ref_location.source_changed_at <= excluded.source_changed_at""")
                .param("zoneType", e.zoneType())
                .param("t", TenantContext.tenantId()).param("site", e.siteId()).param("loc", e.locationId())
                .param("zone", e.zoneId()).param("type", e.locationType()).param("temp", e.temperatureClass())
                .param("haz", e.hazmatAllowed()).param("mixItems", e.allowMixedItems())
                .param("mixLots", e.allowMixedLots()).param("status", e.status()).param("cd", e.checkDigit())
                .param("seq", e.pickSeq()).param("at", Timestamp.from(e.sourceChangedAt())).update();
    }

    public void upsertPickFace(String siteId, com.astrawms.common.contracts.InventoryContracts.PickFaceChanged e) {
        jdbc.sql("""
                        insert into ref_pick_face (tenant_id, site_id, location_id, owner_id, item_no, max_qty, active, changed_at)
                        values (:t, :site, :loc, :owner, :item, :max, :active, :at)
                        on conflict (tenant_id, site_id, location_id, owner_id, item_no) do update set
                            max_qty = excluded.max_qty, active = excluded.active, changed_at = excluded.changed_at
                        where ref_pick_face.changed_at <= excluded.changed_at""")
                .param("t", TenantContext.tenantId()).param("site", siteId).param("loc", e.locationId())
                .param("owner", e.ownerId()).param("item", e.itemNo()).param("max", e.maxQty())
                .param("active", e.active()).param("at", Timestamp.from(e.changedAt())).update();
    }

    /** Active pick faces of the site, any item. */
    public List<PickFace> pickFaces(String siteId) {
        return jdbc.sql("select location_id, owner_id, item_no, max_qty from ref_pick_face where site_id = :site and active")
                .param("site", siteId)
                .query((rs, n) -> new PickFace(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4)))
                .list();
    }

    /** Applies the quantity after each change; idempotent, so redelivered events are harmless. */
    public void applyStock(String siteId, InventoryChanged e) {
        for (InventoryChanged.Line l : e.lines()) {
            if (l.qtyAfter().signum() == 0) {
                jdbc.sql("""
                                delete from stock_projection where site_id = :site and owner_id = :owner and item_no = :item
                                  and lot_no = :lot and lpn_id = :lpn and location_id = :loc and status = :status""")
                        .param("site", siteId).param("owner", e.ownerId()).param("item", e.itemNo())
                        .param("lot", nz(l.lotNo())).param("lpn", nz(l.lpnId())).param("loc", l.locationId())
                        .param("status", l.status()).update();
            } else {
                jdbc.sql("""
                                insert into stock_projection (tenant_id, site_id, owner_id, item_no, lot_no, lpn_id,
                                                              location_id, status, qty)
                                values (:t, :site, :owner, :item, :lot, :lpn, :loc, :status, :qty)
                                on conflict (tenant_id, site_id, owner_id, item_no, lot_no, lpn_id, location_id, status)
                                do update set qty = excluded.qty""")
                        .param("t", TenantContext.tenantId()).param("site", siteId).param("owner", e.ownerId())
                        .param("item", e.itemNo()).param("lot", nz(l.lotNo())).param("lpn", nz(l.lpnId()))
                        .param("loc", l.locationId()).param("status", l.status()).param("qty", l.qtyAfter()).update();
            }
        }
    }

    // ------------------------------------------------------------------ queries

    public Optional<Item> item(String ownerId, String itemNo) {
        return jdbc.sql("select owner_id, item_no, temperature_class, hazardous from ref_item where owner_id = :o and item_no = :i")
                .param("o", ownerId).param("i", itemNo)
                .query((rs, n) -> new Item(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)))
                .optional();
    }

    public Optional<Location> location(String siteId, String locationId) {
        return jdbc.sql(LOCATION + " where site_id = :site and location_id = :loc")
                .param("site", siteId).param("loc", locationId).query(Projections::location).optional();
    }

    /** Active locations of a site in travel-path order; the putaway engine decides which may store what. */
    public List<Location> activeLocations(String siteId) {
        return jdbc.sql(LOCATION + " " + """
                         where site_id = :site and status = 'ACTIVE'
                        order by pick_seq nulls last, location_id""")
                .param("site", siteId).query(Projections::location).list();
    }

    public List<Stock> lpnContents(String siteId, String lpnId, String locationId) {
        return jdbc.sql(STOCK + " where site_id = :site and lpn_id = :lpn and location_id = :loc order by item_no, lot_no")
                .param("site", siteId).param("lpn", lpnId).param("loc", locationId).query(Projections::stock).list();
    }

    /** All stock of a site (putaway planning reads it once per plan). */
    public List<Stock> stockAtSite(String siteId) {
        return jdbc.sql(STOCK + " where site_id = :site").param("site", siteId).query(Projections::stock).list();
    }

    /** Stock at the site's inbound staging locations (dock, door, receiving, returns), for the dock sweep. */
    public List<Stock> stockAtLocations(String siteId, java.util.Collection<String> locationIds) {
        if (locationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(STOCK + " where site_id = :site and location_id in (:locs) order by location_id, lpn_id, item_no")
                .param("site", siteId).param("locs", locationIds).query(Projections::stock).list();
    }

    public List<Stock> stockAt(String siteId, String locationId) {
        return jdbc.sql(STOCK + " where site_id = :site and location_id = :loc")
                .param("site", siteId).param("loc", locationId).query(Projections::stock).list();
    }

    private static final String LOCATION = """
            select site_id, location_id, zone_id, location_type, temperature_class, hazmat_allowed, allow_mixed_items,
                   allow_mixed_lots, status, check_digit, pick_seq, zone_type
            from ref_location""";

    private static final String STOCK = """
            select owner_id, item_no, lot_no, lpn_id, location_id, status, qty from stock_projection""";

    private static Location location(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Location(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8), rs.getString(9), rs.getString(10),
                (Integer) rs.getObject(11), rs.getString(12));
    }

    private static Stock stock(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Stock(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getBigDecimal(7));
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
