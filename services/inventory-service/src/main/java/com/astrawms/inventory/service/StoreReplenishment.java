package com.astrawms.inventory.service;

import com.astrawms.common.security.AccessScope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Predictive store replenishment (ADR-0025). For every store item with a policy (min, max, safety stock, transit days):
 * <ol>
 *   <li><b>Projected</b> stock when a transfer started now would arrive = available (on hand not allocated) + pipeline
 *       (in transit to the store, and accepted recommendations not yet shipped) − daily usage × transit days. Daily
 *       usage is the store's issues (orders and material issues) over the last 28 days.</li>
 *   <li>A transfer is <b>recommended</b> when projected &lt; min + safety stock: quantity = max − projected.</li>
 *   <li><b>Source</b>: a site with enough available for transfer, warehouses (sites without store policies) first, then a
 *       store with stock above its max (store to store), most available first.</li>
 *   <li><b>Required date</b>: when the store would fall below min + safety at its current usage, at the latest; today
 *       plus the transit days when it already has.</li>
 *   <li><b>Confidence</b>: HIGH with 10+ issue days of history, MEDIUM with 3+, else LOW; LOW too when no source
 *       covers the full quantity.</li>
 * </ol>
 * The supervisor accepts a recommendation: the UI creates the transfer (outbound) and records it here, so the same
 * need is not recommended again while the transfer is open.
 */
@Service
public class StoreReplenishment {

    static final int HISTORY_DAYS = 28;

    private final JdbcClient jdbc;
    private final Clock clock;

    public StoreReplenishment(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ policies

    public record PolicyRequest(String ownerId, String itemNo, BigDecimal minQty, BigDecimal maxQty, BigDecimal safetyQty,
                                Integer transitDays) {
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> policies(String siteId) {
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select site_id, owner_id, item_no, min_qty, max_qty, safety_qty, transit_days, updated_by, updated_at
                        from store_stock_policy where (cast(:site as text) is null or site_id = :site)
                          and (:sitesAll or site_id in (:sites)) and (:ownersAll or owner_id in (:owners))
                        order by site_id, owner_id, item_no""")
                .param("site", siteId).param("sitesAll", scope.sitesAll()).param("sites", scope.siteList())
                .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList()).query().listOfRows();
    }

    @Transactional
    public List<Map<String, Object>> putPolicy(String siteId, PolicyRequest r) {
        if (r.ownerId() == null || r.itemNo() == null || r.minQty() == null || r.maxQty() == null
                || r.minQty().signum() < 0 || r.maxQty().compareTo(r.minQty()) < 0) {
            throw ApiException.badRequest("INV_STORE_POLICY_INVALID", "ownerId, itemNo, minQty ≥ 0 and maxQty ≥ minQty are required");
        }
        AccessScope.current().requireOwner(r.ownerId());
        jdbc.sql("""
                        insert into store_stock_policy (tenant_id, site_id, owner_id, item_no, min_qty, max_qty, safety_qty,
                                                        transit_days, updated_by, updated_at)
                        values (:t, :site, :owner, :item, :min, :max, :safety, :days, :user, :now)
                        on conflict (tenant_id, site_id, owner_id, item_no) do update set min_qty = excluded.min_qty,
                            max_qty = excluded.max_qty, safety_qty = excluded.safety_qty, transit_days = excluded.transit_days,
                            updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("site", siteId).param("owner", r.ownerId().trim().toUpperCase())
                .param("item", r.itemNo().trim()).param("min", r.minQty()).param("max", r.maxQty())
                .param("safety", r.safetyQty() == null ? BigDecimal.ZERO : r.safetyQty())
                .param("days", r.transitDays() == null ? 1 : r.transitDays())
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        return policies(siteId);
    }

    // ------------------------------------------------------------------ recommendations

    record Stock(BigDecimal available, BigDecimal onHand) {
    }

    /** Recommendations for one store, or every store of the user's scope ({@code siteId} null). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recommendations(String siteId) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<Map<String, Object>> policies = policies(siteId);
        // Available per site/owner/item for every item that has a policy anywhere (sources included).
        Map<String, Stock> stock = new HashMap<>();
        jdbc.sql("""
                        select b.site_id, b.owner_id, b.item_no,
                               coalesce(sum(b.qty - b.allocated_qty) filter (where b.stock_status = 'AVAILABLE'
                                   and coalesce(l.location_type, '') <> 'STAGING_OUT'
                                   and not exists (select 1 from location_freeze f where f.site_id = b.site_id
                                                   and f.location_id = b.location_id)), 0),
                               sum(b.qty)
                        from inventory_balance b
                        left join ref_location l on l.site_id = b.site_id and l.location_id = b.location_id
                        where b.qty > 0 and exists (select 1 from store_stock_policy p where p.owner_id = b.owner_id
                                                    and p.item_no = b.item_no)
                        group by b.site_id, b.owner_id, b.item_no""")
                .query((rs, n) -> stock.put(key(rs.getString(1), rs.getString(2), rs.getString(3)),
                        new Stock(rs.getBigDecimal(4), rs.getBigDecimal(5)))).list();
        Map<String, BigDecimal> usage = new HashMap<>();
        Map<String, Integer> usageDays = new HashMap<>();
        jdbc.sql("""
                        select site_id, owner_id, item_no, -sum(qty_delta), count(distinct occurred_at::date)
                        from inventory_txn where txn_type = 'ISSUE' and qty_delta < 0 and occurred_at >= :since
                        group by site_id, owner_id, item_no""")
                .param("since", Timestamp.from(clock.instant().minus(Duration.ofDays(HISTORY_DAYS))))
                .query((rs, n) -> {
                    String k = key(rs.getString(1), rs.getString(2), rs.getString(3));
                    usage.put(k, rs.getBigDecimal(4));
                    usageDays.put(k, rs.getInt(5));
                    return k;
                }).list();
        Map<String, BigDecimal> pipeline = new HashMap<>();
        jdbc.sql("""
                        select to_site, owner_id, item_no, sum(qty - qty_received) from stock_in_transit
                        where qty_received < qty group by to_site, owner_id, item_no""")
                .query((rs, n) -> pipeline.merge(key(rs.getString(1), rs.getString(2), rs.getString(3)), rs.getBigDecimal(4), BigDecimal::add))
                .list();
        // Accepted, not yet shipped (no transit row for its transfer yet).
        jdbc.sql("""
                        select r.site_id, r.owner_id, r.item_no, sum(r.qty) from store_replenishment r
                        where not exists (select 1 from stock_in_transit t where t.transfer_no = r.transfer_no
                                          and t.item_no = r.item_no)
                          and r.accepted_at >= :since
                        group by r.site_id, r.owner_id, r.item_no""")
                .param("since", Timestamp.from(clock.instant().minus(Duration.ofDays(30))))
                .query((rs, n) -> pipeline.merge(key(rs.getString(1), rs.getString(2), rs.getString(3)), rs.getBigDecimal(4), BigDecimal::add))
                .list();
        Map<String, BigDecimal> maxOf = new HashMap<>();
        java.util.Set<String> storeSites = new java.util.HashSet<>();
        jdbc.sql("select site_id, owner_id, item_no, max_qty from store_stock_policy")
                .query((rs, n) -> {
                    storeSites.add(rs.getString(1));
                    return maxOf.put(key(rs.getString(1), rs.getString(2), rs.getString(3)), rs.getBigDecimal(4));
                }).list();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : policies) {
            String site = (String) p.get("site_id");
            String owner = (String) p.get("owner_id");
            String item = (String) p.get("item_no");
            String k = key(site, owner, item);
            BigDecimal min = (BigDecimal) p.get("min_qty");
            BigDecimal max = (BigDecimal) p.get("max_qty");
            BigDecimal safety = (BigDecimal) p.get("safety_qty");
            int transitDays = ((Number) p.get("transit_days")).intValue();
            Stock here = stock.getOrDefault(k, new Stock(BigDecimal.ZERO, BigDecimal.ZERO));
            BigDecimal daily = usage.getOrDefault(k, BigDecimal.ZERO).divide(BigDecimal.valueOf(HISTORY_DAYS), 3, RoundingMode.HALF_UP);
            BigDecimal inPipe = pipeline.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal projected = here.available().add(inPipe).subtract(daily.multiply(BigDecimal.valueOf(transitDays)));
            BigDecimal trigger = min.add(safety);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("siteId", site);
            r.put("ownerId", owner);
            r.put("itemNo", item);
            r.put("available", here.available());
            r.put("inTransit", inPipe);
            r.put("dailyUsage", daily);
            r.put("daysOfCover", daily.signum() == 0 ? null : here.available().divide(daily, 1, RoundingMode.HALF_DOWN));
            r.put("projected", projected);
            r.put("min", min);
            r.put("max", max);
            r.put("safety", safety);
            r.put("transitDays", transitDays);
            boolean stockoutRisk = daily.signum() > 0
                    && here.available().add(inPipe).compareTo(daily.multiply(BigDecimal.valueOf(transitDays + 1L))) < 0;
            r.put("stockoutRisk", stockoutRisk || here.available().signum() == 0);
            if (projected.compareTo(trigger) >= 0) {
                r.put("recommended", false);
                r.put("reason", "Projected " + strip(projected) + " stays at or above min + safety " + strip(trigger));
                out.add(r);
                continue;
            }
            BigDecimal qty = max.subtract(projected).setScale(0, RoundingMode.CEILING);
            // Sources: warehouses first, then stores holding more than their max, most available first.
            record Source(String site, BigDecimal transferable, boolean store) {
            }
            List<Source> sources = new ArrayList<>();
            for (Map.Entry<String, Stock> e : stock.entrySet()) {
                String[] parts = e.getKey().split("\u0001");
                if (!parts[1].equals(owner) || !parts[2].equals(item) || parts[0].equals(site)) {
                    continue;
                }
                boolean store = storeSites.contains(parts[0]);
                BigDecimal transferable = e.getValue().available();
                if (store) {
                    BigDecimal storeMax = maxOf.get(key(parts[0], owner, item));
                    transferable = storeMax == null ? BigDecimal.ZERO : transferable.subtract(storeMax);
                }
                if (transferable.signum() > 0) {
                    sources.add(new Source(parts[0], transferable, store));
                }
            }
            sources.sort(Comparator.comparing(Source::store).thenComparing(Source::transferable, Comparator.reverseOrder()));
            Source best = sources.stream().filter(s -> s.transferable().compareTo(qty) >= 0).findFirst()
                    .orElse(sources.isEmpty() ? null : sources.getFirst());
            BigDecimal shipQty = best == null ? BigDecimal.ZERO : qty.min(best.transferable()).setScale(0, RoundingMode.FLOOR);
            LocalDate required;
            BigDecimal headroom = here.available().add(inPipe).subtract(trigger);
            if (daily.signum() > 0 && headroom.signum() > 0) {
                required = today.plusDays(headroom.divide(daily, 0, RoundingMode.FLOOR).longValue());
            } else {
                required = today.plusDays(transitDays);
            }
            int days = usageDays.getOrDefault(k, 0);
            String confidence = days >= 10 ? "HIGH" : days >= 3 ? "MEDIUM" : "LOW";
            if (best == null || shipQty.compareTo(qty) < 0) {
                confidence = "LOW";
            }
            r.put("recommended", best != null && shipQty.signum() > 0);
            r.put("qty", shipQty);
            r.put("neededQty", qty);
            r.put("sourceSite", best == null ? null : best.site());
            r.put("sourceAvailable", best == null ? null : best.transferable());
            r.put("requiredDate", required.toString());
            r.put("confidence", confidence);
            r.put("reason", "Available " + strip(here.available()) + " + in transit " + strip(inPipe) + " − usage "
                    + strip(daily) + "/day × " + transitDays + " day(s) transit = " + strip(projected)
                    + ", below min " + strip(min) + " + safety " + strip(safety) + "; refill to max " + strip(max)
                    + (best == null ? ". No site has stock to send" : best.store()
                            ? ". From store " + best.site() + " (above its max)" : ". From " + best.site())
                    + (days < 3 ? "; little usage history (" + days + " day(s) in " + HISTORY_DAYS + ")" : ""));
            out.add(r);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> !Boolean.TRUE.equals(m.get("recommended")))
                .thenComparing(m -> String.valueOf(m.getOrDefault("requiredDate", "9999")))
                .thenComparing(m -> (String) m.get("siteId")));
        return out;
    }

    public record AcceptRequest(String ownerId, String itemNo, BigDecimal qty, String sourceSite, String requiredDate,
                                String reason, String confidence, String transferNo) {
    }

    /** Records an accepted recommendation and the transfer created for it. */
    @Transactional
    public Map<String, Object> accept(String siteId, AcceptRequest r) {
        if (r.transferNo() == null || r.transferNo().isBlank() || r.qty() == null || r.qty().signum() <= 0
                || r.sourceSite() == null || r.ownerId() == null || r.itemNo() == null) {
            throw ApiException.badRequest("INV_REPLEN_ACCEPT_INVALID", "ownerId, itemNo, qty > 0, sourceSite and transferNo are required");
        }
        AccessScope.current().requireOwner(r.ownerId());
        UUID id = UUID.randomUUID();
        LocalDate required = r.requiredDate() == null ? LocalDate.now(clock.withZone(ZoneOffset.UTC)) : LocalDate.parse(r.requiredDate());
        jdbc.sql("""
                        insert into store_replenishment (id, tenant_id, site_id, source_site, owner_id, item_no, qty,
                                                         required_date, reason, confidence, transfer_no, accepted_by, accepted_at)
                        values (:id, :t, :site, :src, :owner, :item, :qty, :req, :reason, :conf, :tr, :user, :now)""")
                .param("id", id).param("t", TenantContext.tenantId()).param("site", siteId).param("src", r.sourceSite())
                .param("owner", r.ownerId()).param("item", r.itemNo()).param("qty", r.qty()).param("req", Date.valueOf(required))
                .param("reason", r.reason() == null ? "" : r.reason())
                .param("conf", r.confidence() == null ? "LOW" : r.confidence()).param("tr", r.transferNo().trim())
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        return Map.of("id", id, "transferNo", r.transferNo().trim(), "siteId", siteId);
    }

    private static String key(String site, String owner, String item) {
        return site + "\u0001" + owner + "\u0001" + item;
    }

    private static String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
