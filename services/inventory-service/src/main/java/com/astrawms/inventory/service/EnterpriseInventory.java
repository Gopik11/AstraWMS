package com.astrawms.inventory.service;

import com.astrawms.common.security.AccessScope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enterprise inventory (ADR-0025): one item across every site of the user's scope, from the one ledger.
 * <ul>
 *   <li>Buckets per site: on hand, available, allocated, picked (staged for shipping), quarantine (QI), blocked,
 *       damaged, expired (status EXPIRED or past its expiry date), returned (in a returns zone), in transit to and
 *       from the site, and available for transfer (available less the store's safety stock).</li>
 *   <li>Ownership is an attribute of the owner (own, consignment, customer-owned, supplier-owned): no second ledger.</li>
 *   <li>Recall: every balance of an item lot or serial, at any site and owner, plus what is in transit.</li>
 * </ul>
 */
@Service
public class EnterpriseInventory {

    public static final List<String> OWNERSHIP = List.of("OWN", "CONSIGNMENT", "CUSTOMER_OWNED", "SUPPLIER_OWNED");

    private final JdbcClient jdbc;
    private final Clock clock;

    public EnterpriseInventory(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ item balance across sites

    @Transactional(readOnly = true)
    public Map<String, Object> itemBalance(String ownerId, String itemNo) {
        AccessScope scope = AccessScope.current();
        scope.requireOwner(ownerId);
        Date today = Date.valueOf(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        Map<String, Map<String, Object>> sites = new TreeMap<>();
        jdbc.sql("""
                        select b.site_id,
                               sum(b.qty) as on_hand,
                               sum(b.qty - b.allocated_qty) filter (where b.stock_status = 'AVAILABLE'
                                   and coalesce(b.expiry_date, :today) >= :today
                                   and not exists (select 1 from location_freeze f where f.site_id = b.site_id
                                                   and f.location_id = b.location_id)
                                   and coalesce(l.location_type, '') <> 'STAGING_OUT') as available,
                               sum(b.allocated_qty) as allocated,
                               sum(b.qty) filter (where l.location_type = 'STAGING_OUT') as picked,
                               sum(b.qty) filter (where b.stock_status = 'QI') as quarantine,
                               sum(b.qty) filter (where b.stock_status = 'BLOCKED') as blocked,
                               sum(b.qty) filter (where b.stock_status = 'DAMAGED') as damaged,
                               sum(b.qty) filter (where b.stock_status = 'EXPIRED'
                                   or (b.stock_status = 'AVAILABLE' and b.expiry_date < :today)) as expired,
                               sum(b.qty) filter (where coalesce(l.zone_type, '') = 'RETURNS') as returned,
                               bool_or(f.location_id is not null) as frozen
                        from inventory_balance b
                        left join ref_location l on l.site_id = b.site_id and l.location_id = b.location_id
                        left join location_freeze f on f.site_id = b.site_id and f.location_id = b.location_id
                        where b.owner_id = :owner and b.item_no = :item and b.qty > 0
                          and (:sitesAll or b.site_id in (:sites))
                        group by b.site_id""")
                .param("owner", ownerId).param("item", itemNo).param("today", today)
                .param("sitesAll", scope.sitesAll()).param("sites", scope.siteList())
                .query().listOfRows().forEach(r -> sites.put((String) r.get("site_id"), new LinkedHashMap<>(r)));
        // In transit to (and from) each site, not yet received.
        List<Map<String, Object>> transit = jdbc.sql("""
                        select transfer_no, from_site, to_site, lot_no, qty - qty_received as open_qty, shipped_at
                        from stock_in_transit
                        where owner_id = :owner and item_no = :item and qty_received < qty
                          and (:sitesAll or from_site in (:sites) or to_site in (:sites))
                        order by shipped_at""")
                .param("owner", ownerId).param("item", itemNo)
                .param("sitesAll", scope.sitesAll()).param("sites", scope.siteList())
                .query().listOfRows();
        for (Map<String, Object> t : transit) {
            add(sites, (String) t.get("to_site"), "in_transit_in", (BigDecimal) t.get("open_qty"));
            add(sites, (String) t.get("from_site"), "in_transit_out", (BigDecimal) t.get("open_qty"));
        }
        Map<String, BigDecimal> safety = new java.util.HashMap<>();
        jdbc.sql("select site_id, safety_qty from store_stock_policy where owner_id = :owner and item_no = :item")
                .param("owner", ownerId).param("item", itemNo)
                .query((rs, n) -> safety.put(rs.getString(1), rs.getBigDecimal(2))).list();
        BigDecimal[] totals = new BigDecimal[1];
        totals[0] = BigDecimal.ZERO;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : sites.entrySet()) {
            Map<String, Object> r = e.getValue();
            r.put("site_id", e.getKey());
            for (String k : List.of("on_hand", "available", "allocated", "picked", "quarantine", "blocked", "damaged",
                    "expired", "returned", "in_transit_in", "in_transit_out")) {
                r.putIfAbsent(k, BigDecimal.ZERO);
                if (r.get(k) == null) {
                    r.put(k, BigDecimal.ZERO);
                }
            }
            BigDecimal available = (BigDecimal) r.get("available");
            BigDecimal transferable = available.subtract(safety.getOrDefault(e.getKey(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
            r.put("available_for_transfer", transferable);
            r.put("frozen", Boolean.TRUE.equals(r.get("frozen")));
            totals[0] = totals[0].add((BigDecimal) r.get("on_hand"));
            rows.add(r);
        }
        BigDecimal inTransit = transit.stream().map(t -> (BigDecimal) t.get("open_qty")).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ownerId", ownerId);
        out.put("itemNo", itemNo);
        out.put("ownershipType", ownershipType(ownerId));
        out.put("sites", rows);
        out.put("inTransit", transit);
        out.put("totalOnHand", totals[0]);
        out.put("totalInTransit", inTransit);
        // The network holds what is on hand anywhere plus what is between sites.
        out.put("networkTotal", totals[0].add(inTransit));
        return out;
    }

    private static void add(Map<String, Map<String, Object>> sites, String site, String key, BigDecimal qty) {
        Map<String, Object> r = sites.computeIfAbsent(site, s -> new LinkedHashMap<>());
        r.merge(key, qty, (a, b) -> ((BigDecimal) a).add((BigDecimal) b));
    }

    /** Items whose number matches {@code q}, with their network on hand and in transit, for the item search. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> searchItems(String q) {
        AccessScope scope = AccessScope.current();
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toUpperCase() + "%";
        return jdbc.sql("""
                        select i.owner_id, i.item_no,
                               coalesce((select sum(b.qty) from inventory_balance b where b.owner_id = i.owner_id
                                          and b.item_no = i.item_no and (:sitesAll or b.site_id in (:sites))), 0) as on_hand,
                               coalesce((select sum(t.qty - t.qty_received) from stock_in_transit t where t.owner_id = i.owner_id
                                          and t.item_no = i.item_no), 0) as in_transit
                        from (select distinct owner_id, item_no from ref_item) i
                        where upper(i.item_no) like :q and (:ownersAll or i.owner_id in (:owners))
                        order by i.owner_id, i.item_no limit 100""")
                .param("q", like).param("sitesAll", scope.sitesAll()).param("sites", scope.siteList())
                .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                .query().listOfRows();
    }

    // ------------------------------------------------------------------ ownership

    public String ownershipType(String ownerId) {
        return jdbc.sql("select ownership_type from owner_profile where owner_id = :o").param("o", ownerId)
                .query(String.class).optional().orElse("OWN");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> owners() {
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select o.owner_id, p.name, coalesce(p.ownership_type, 'OWN') as ownership_type, p.updated_by, p.updated_at
                        from (select distinct owner_id from ref_item union select owner_id from owner_profile) o
                        left join owner_profile p on p.owner_id = o.owner_id
                        where (:ownersAll or o.owner_id in (:owners)) order by o.owner_id""")
                .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList()).query().listOfRows();
    }

    @Transactional
    public Map<String, Object> putOwner(String ownerId, String name, String ownershipType) {
        String type = ownershipType == null || ownershipType.isBlank() ? "OWN" : ownershipType.trim().toUpperCase();
        if (!OWNERSHIP.contains(type)) {
            throw ApiException.badRequest("INV_OWNERSHIP_INVALID", "ownershipType must be one of " + OWNERSHIP);
        }
        String owner = ownerId.trim().toUpperCase();
        jdbc.sql("""
                        insert into owner_profile (tenant_id, owner_id, name, ownership_type, updated_by, updated_at)
                        values (:t, :o, :name, :type, :user, :now)
                        on conflict (tenant_id, owner_id) do update set name = excluded.name,
                            ownership_type = excluded.ownership_type, updated_by = excluded.updated_by,
                            updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("o", owner).param("name", name).param("type", type)
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        return owners().stream().filter(r -> owner.equals(r.get("owner_id"))).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ recall

    /** Every balance of an item lot or serial at any site and owner of the scope, and what of it is in transit. */
    @Transactional(readOnly = true)
    public Map<String, Object> recall(String itemNo, String lotNo, String serialNo) {
        if (itemNo == null || itemNo.isBlank() || ((lotNo == null || lotNo.isBlank()) && (serialNo == null || serialNo.isBlank()))) {
            throw ApiException.badRequest("INV_RECALL_INVALID", "itemNo and a lotNo or serialNo are required");
        }
        AccessScope scope = AccessScope.current();
        String item = itemNo.trim();
        List<Map<String, Object>> balances;
        if (serialNo != null && !serialNo.isBlank()) {
            balances = jdbc.sql("""
                            select s.site_id, s.owner_id, s.location_id, s.lpn_id, s.lot_no, s.stock_status, 1 as qty,
                                   s.status as serial_status, p.ownership_type
                            from serial_number s left join owner_profile p on p.owner_id = s.owner_id
                            where s.item_no = :item and s.serial_no = :sn
                              and (:sitesAll or s.site_id in (:sites)) and (:ownersAll or s.owner_id in (:owners))""")
                    .param("item", item).param("sn", serialNo.trim())
                    .param("sitesAll", scope.sitesAll()).param("sites", scope.siteList())
                    .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                    .query().listOfRows();
        } else {
            balances = jdbc.sql("""
                            select b.site_id, b.owner_id, b.location_id, b.lpn_id, b.lot_no, b.stock_status, b.qty,
                                   b.allocated_qty, b.expiry_date, coalesce(p.ownership_type, 'OWN') as ownership_type
                            from inventory_balance b left join owner_profile p on p.owner_id = b.owner_id
                            where b.item_no = :item and b.lot_no = :lot and b.qty > 0
                              and (:sitesAll or b.site_id in (:sites)) and (:ownersAll or b.owner_id in (:owners))
                            order by b.site_id, b.location_id""")
                    .param("item", item).param("lot", lotNo.trim())
                    .param("sitesAll", scope.sitesAll()).param("sites", scope.siteList())
                    .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                    .query().listOfRows();
        }
        List<Map<String, Object>> transit = jdbc.sql("""
                        select transfer_no, from_site, to_site, owner_id, lot_no, qty - qty_received as open_qty, shipped_at
                        from stock_in_transit where item_no = :item and qty_received < qty
                          and (cast(:lot as text) is null or lot_no = :lot)
                          and (:ownersAll or owner_id in (:owners))""")
                .param("item", item).param("lot", lotNo == null || lotNo.isBlank() ? null : lotNo.trim())
                .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                .query().listOfRows();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemNo", item);
        out.put("lotNo", lotNo);
        out.put("serialNo", serialNo);
        out.put("balances", balances);
        out.put("inTransit", transit);
        out.put("sites", balances.stream().map(b -> b.get("site_id")).distinct().count());
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> shipped(String siteId, int days) {
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select owner_id, item_no, -sum(qty_delta) as units
                        from inventory_txn
                        where site_id = :site and txn_type = 'ISSUE' and qty_delta < 0
                          and coalesce(source_doc, '') not like 'ISSUE %' and occurred_at >= :since
                          and (:ownersAll or owner_id in (:owners))
                        group by owner_id, item_no""")
                .param("site", siteId).param("since", Timestamp.from(clock.instant().minus(java.time.Duration.ofDays(days))))
                .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                .query().listOfRows();
    }

    // ------------------------------------------------------------------ value

    /** Inventory value per site and owner at standard cost (items without a cost are counted, not valued). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> value() {
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select b.site_id, b.owner_id, coalesce(p.ownership_type, 'OWN') as ownership_type,
                               sum(b.qty) as qty, sum(b.qty * i.standard_cost) as value,
                               count(distinct b.item_no) filter (where i.standard_cost is null) as items_without_cost
                        from inventory_balance b
                        join ref_item i on i.owner_id = b.owner_id and i.item_no = b.item_no and i.site_id = b.site_id
                        left join owner_profile p on p.owner_id = b.owner_id
                        where b.qty > 0 and (:sitesAll or b.site_id in (:sites)) and (:ownersAll or b.owner_id in (:owners))
                        group by b.site_id, b.owner_id, p.ownership_type order by b.site_id, b.owner_id""")
                .param("sitesAll", scope.sitesAll()).param("sites", scope.siteList())
                .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                .query().listOfRows();
    }
}
