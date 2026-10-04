package com.astrawms.inventory.service;

import com.astrawms.common.security.AccessScope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.inventory.outbound.OutboundClient;
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
 * Predictive store replenishment (ADR-0025). For every store item with a policy (min, max, safety stock):
 * <ol>
 *   <li><b>Transit days</b> are the store's (site setting), unless the item policy overrides them; 1 when neither is
 *       set, and the row says so.</li>
 *   <li><b>Pipeline</b>: what is in transit to the store, plus what open transfers to it (any transfer, accepted from
 *       a recommendation or not) will bring and has not shipped yet.</li>
 *   <li><b>Usage</b> is the store's issues (orders and material issues) over the last 28 days. No history is not
 *       demand: usage 0 adds nothing, confidence is LOW and the stockout risk says "no history".</li>
 *   <li><b>Projected</b> = available + pipeline − usage × transit days. A transfer is recommended when projected &lt;
 *       min + safety stock, for max − projected.</li>
 *   <li><b>Source</b> availability is real: on hand, less allocated, less what open orders and transfers at that site
 *       still wait for (from outbound). The warehouse (a site without store policies) wins when it covers the
 *       quantity, else a store holding more than its max (store to store), else the source with most free; the row
 *       says why the source won and lists the alternatives.</li>
 *   <li><b>Confidence</b>: HIGH with usage on 10+ of the last 28 days, MEDIUM with 3+, else LOW; LOW too when no source
 *       covers the quantity.</li>
 * </ol>
 * Accept creates a strict transfer at the source (outbound refuses it when the source cannot cover it, naming who
 * holds the stock, or when an open transfer already brings the item), then records the acceptance.
 */
@Service
public class StoreReplenishment {

    static final int HISTORY_DAYS = 28;
    static final int DEFAULT_TRANSIT_DAYS = 1;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final OutboundClient outbound;

    public StoreReplenishment(JdbcClient jdbc, Clock clock, OutboundClient outbound) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.outbound = outbound;
    }

    // ------------------------------------------------------------------ policies and store settings

    public record PolicyRequest(String ownerId, String itemNo, BigDecimal minQty, BigDecimal maxQty, BigDecimal safetyQty,
                                Integer transitDays) {
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> policies(String siteId) {
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select p.site_id, p.owner_id, p.item_no, p.min_qty, p.max_qty, p.safety_qty, p.transit_days,
                               s.transit_days as site_transit_days, p.updated_by, p.updated_at
                        from store_stock_policy p left join store_site_setting s on s.site_id = p.site_id
                        where (cast(:site as text) is null or p.site_id = :site)
                          and (:sitesAll or p.site_id in (:sites)) and (:ownersAll or p.owner_id in (:owners))
                        order by p.site_id, p.owner_id, p.item_no""")
                .param("site", siteId).param("sitesAll", scope.sitesAll()).param("sites", scope.siteList())
                .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList()).query().listOfRows();
    }

    @Transactional
    public List<Map<String, Object>> putPolicy(String siteId, PolicyRequest r) {
        if (r.ownerId() == null || r.itemNo() == null || r.minQty() == null || r.maxQty() == null
                || r.minQty().signum() < 0 || r.maxQty().compareTo(r.minQty()) < 0
                || (r.transitDays() != null && (r.transitDays() < 0 || r.transitDays() > 60))) {
            throw ApiException.badRequest("INV_STORE_POLICY_INVALID",
                    "ownerId, itemNo, minQty ≥ 0, maxQty ≥ minQty are required; transitDays (optional) 0–60");
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
                .param("days", r.transitDays())
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        return policies(siteId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> storeSetting(String siteId) {
        return jdbc.sql("select site_id, transit_days, updated_by, updated_at from store_site_setting where site_id = :site")
                .param("site", siteId).query().listOfRows().stream().findFirst()
                .orElse(Map.of("site_id", siteId, "transit_days", DEFAULT_TRANSIT_DAYS, "default", true));
    }

    @Transactional
    public Map<String, Object> putStoreSetting(String siteId, Integer transitDays) {
        if (transitDays == null || transitDays < 0 || transitDays > 60) {
            throw ApiException.badRequest("INV_STORE_SETTING_INVALID", "transitDays 0–60 is required");
        }
        jdbc.sql("""
                        insert into store_site_setting (tenant_id, site_id, transit_days, updated_by, updated_at)
                        values (:t, :site, :days, :user, :now)
                        on conflict (tenant_id, site_id) do update set transit_days = excluded.transit_days,
                            updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("site", siteId).param("days", transitDays)
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        return storeSetting(siteId);
    }

    // ------------------------------------------------------------------ recommendations

    record Stock(BigDecimal onHand, BigDecimal allocated, BigDecimal available) {
        static final Stock NONE = new Stock(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** One possible source: what it holds and what it can give. */
    record Source(String site, boolean store, BigDecimal onHand, BigDecimal allocatedOrders, BigDecimal allocatedTransfers,
                  BigDecimal waiting, BigDecimal free, BigDecimal transferable) {

        Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("site", site);
            m.put("type", store ? "STORE" : "WAREHOUSE");
            m.put("onHand", onHand);
            m.put("allocatedOrders", allocatedOrders);
            m.put("allocatedTransfers", allocatedTransfers);
            m.put("waitingOnOpenDocuments", waiting);
            m.put("free", free);
            m.put("transferable", transferable);
            return m;
        }
    }

    /** Recommendations for one store, or every store of the user's scope ({@code siteId} null). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recommendations(String siteId) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<Map<String, Object>> policies = policies(siteId);
        OutboundClient.Commitments commitments = outbound.commitments();
        // On hand and allocated in AVAILABLE status (not frozen, not staged), per site/owner/item, for items with a policy.
        Map<String, Stock> stock = new HashMap<>();
        jdbc.sql("""
                        select b.site_id, b.owner_id, b.item_no, coalesce(sum(b.qty), 0), coalesce(sum(b.allocated_qty), 0)
                        from inventory_balance b
                        left join ref_location l on l.site_id = b.site_id and l.location_id = b.location_id
                        where b.qty > 0 and b.stock_status = 'AVAILABLE' and coalesce(l.location_type, '') <> 'STAGING_OUT'
                          and not exists (select 1 from location_freeze f where f.site_id = b.site_id and f.location_id = b.location_id)
                          and exists (select 1 from store_stock_policy p where p.owner_id = b.owner_id and p.item_no = b.item_no)
                        group by b.site_id, b.owner_id, b.item_no""")
                .query((rs, n) -> stock.put(key(rs.getString(1), rs.getString(2), rs.getString(3)),
                        new Stock(rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(4).subtract(rs.getBigDecimal(5)))))
                .list();
        Map<String, OutboundClient.SourceCommitment> held = new HashMap<>();
        commitments.bySource().forEach(c -> held.put(key(c.siteId(), c.ownerId(), c.itemNo()), c));
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
        // Pipeline: in transit (shipped, not received) + open transfers to the store not shipped yet.
        Map<String, BigDecimal> inTransit = new HashMap<>();
        jdbc.sql("""
                        select to_site, owner_id, item_no, sum(qty - qty_received) from stock_in_transit
                        where qty_received < qty group by to_site, owner_id, item_no""")
                .query((rs, n) -> inTransit.merge(key(rs.getString(1), rs.getString(2), rs.getString(3)), rs.getBigDecimal(4), BigDecimal::add))
                .list();
        Map<String, BigDecimal> openTransfers = new HashMap<>();
        Map<String, String> openTransferNos = new HashMap<>();
        if (commitments.available()) {
            for (OutboundClient.OpenTransfer t : commitments.toStore()) {
                String k = key(t.siteId(), t.ownerId(), t.itemNo());
                openTransfers.merge(k, t.openQty(), BigDecimal::add);
                openTransferNos.merge(k, t.transfers(), (a, b) -> a + "," + b);
            }
        } else {
            // Outbound unreachable: fall back to accepted recommendations not yet shipped.
            jdbc.sql("""
                            select r.site_id, r.owner_id, r.item_no, sum(r.qty), string_agg(r.transfer_no, ',') from store_replenishment r
                            where not exists (select 1 from stock_in_transit t where t.transfer_no = r.transfer_no and t.item_no = r.item_no)
                              and r.accepted_at >= :since
                            group by r.site_id, r.owner_id, r.item_no""")
                    .param("since", Timestamp.from(clock.instant().minus(Duration.ofDays(30))))
                    .query((rs, n) -> {
                        String k = key(rs.getString(1), rs.getString(2), rs.getString(3));
                        openTransfers.merge(k, rs.getBigDecimal(4), BigDecimal::add);
                        openTransferNos.put(k, rs.getString(5));
                        return k;
                    }).list();
        }
        Map<String, BigDecimal> maxOf = new HashMap<>();
        java.util.Set<String> storeSites = new java.util.HashSet<>();
        jdbc.sql("select site_id, owner_id, item_no, max_qty from store_stock_policy")
                .query((rs, n) -> {
                    storeSites.add(rs.getString(1));
                    return maxOf.put(key(rs.getString(1), rs.getString(2), rs.getString(3)), rs.getBigDecimal(4));
                }).list();
        java.util.Set<String> sites = new java.util.TreeSet<>();
        stock.keySet().forEach(k -> sites.add(k.split("\u0001")[0]));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : policies) {
            String site = (String) p.get("site_id");
            String owner = (String) p.get("owner_id");
            String item = (String) p.get("item_no");
            String k = key(site, owner, item);
            BigDecimal min = (BigDecimal) p.get("min_qty");
            BigDecimal max = (BigDecimal) p.get("max_qty");
            BigDecimal safety = (BigDecimal) p.get("safety_qty");
            Integer policyDays = (Integer) p.get("transit_days");
            Integer siteDays = (Integer) p.get("site_transit_days");
            int transitDays = policyDays != null ? policyDays : siteDays != null ? siteDays : DEFAULT_TRANSIT_DAYS;
            String transitFrom = policyDays != null ? "item policy" : siteDays != null ? "store setting" : "default (store setting not set)";
            Stock here = stock.getOrDefault(k, Stock.NONE);
            int days = usageDays.getOrDefault(k, 0);
            BigDecimal daily = usage.getOrDefault(k, BigDecimal.ZERO).divide(BigDecimal.valueOf(HISTORY_DAYS), 3, RoundingMode.HALF_UP);
            BigDecimal transit = inTransit.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal open = openTransfers.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal pipeline = transit.add(open);
            BigDecimal projected = here.available().add(pipeline).subtract(daily.multiply(BigDecimal.valueOf(transitDays)));
            BigDecimal trigger = min.add(safety);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("siteId", site);
            r.put("ownerId", owner);
            r.put("itemNo", item);
            r.put("onHand", here.onHand());
            r.put("allocated", here.allocated());
            r.put("available", here.available());
            r.put("inTransit", transit);
            r.put("openTransferQty", open);
            r.put("openTransfers", openTransferNos.get(k));
            r.put("dailyUsage", daily);
            r.put("usageDays", days);
            r.put("history", days == 0 ? "NONE" : days < 3 ? "SHORT" : "OK");
            r.put("daysOfCover", daily.signum() == 0 ? null : here.available().divide(daily, 1, RoundingMode.HALF_DOWN));
            r.put("projected", projected);
            r.put("min", min);
            r.put("max", max);
            r.put("safety", safety);
            r.put("transitDays", transitDays);
            r.put("transitDaysFrom", transitFrom);
            r.put("commitmentsKnown", commitments.available());
            // No usage history is not demand: the risk is unknown, not zero.
            String risk = days == 0 ? (here.available().signum() == 0 ? "EMPTY" : "NO_HISTORY")
                    : here.available().add(pipeline).compareTo(daily.multiply(BigDecimal.valueOf(transitDays + 1L))) < 0 ? "HIGH" : "LOW";
            r.put("stockoutRisk", risk.equals("HIGH") || risk.equals("EMPTY"));
            r.put("stockoutRiskText", switch (risk) {
                case "EMPTY" -> "empty";
                case "NO_HISTORY" -> "no history";
                case "HIGH" -> "runs out before a transfer arrives";
                default -> "low";
            });
            if (projected.compareTo(trigger) >= 0) {
                r.put("recommended", false);
                r.put("reason", "Projected " + strip(projected) + " (available " + strip(here.available()) + " + pipeline "
                        + strip(pipeline) + (daily.signum() > 0 ? " − usage over " + transitDays + " day(s)" : "")
                        + ") stays at or above min + safety " + strip(trigger));
                out.add(r);
                continue;
            }
            BigDecimal qty = max.subtract(projected).setScale(0, RoundingMode.CEILING);
            List<Source> sources = new ArrayList<>();
            for (String s : sites) {
                if (s.equals(site)) {
                    continue;
                }
                Stock st = stock.getOrDefault(key(s, owner, item), Stock.NONE);
                OutboundClient.SourceCommitment c = held.get(key(s, owner, item));
                BigDecimal allocOrders = c == null ? BigDecimal.ZERO : c.allocatedOrders();
                BigDecimal allocTransfers = c == null ? BigDecimal.ZERO : c.allocatedTransfers();
                BigDecimal waiting = c == null ? BigDecimal.ZERO : c.shortOrders().add(c.shortTransfers());
                BigDecimal free = st.available().subtract(waiting).max(BigDecimal.ZERO);
                boolean store = storeSites.contains(s);
                BigDecimal transferable = free;
                if (store) {
                    BigDecimal storeMax = maxOf.get(key(s, owner, item));
                    transferable = storeMax == null ? BigDecimal.ZERO : free.subtract(storeMax).max(BigDecimal.ZERO);
                }
                if (transferable.signum() > 0) {
                    sources.add(new Source(s, store, st.onHand(), allocOrders, allocTransfers, waiting, free, transferable));
                }
            }
            sources.sort(Comparator.comparing(Source::store).thenComparing(Source::transferable, Comparator.reverseOrder()));
            Source best = sources.stream().filter(s -> s.transferable().compareTo(qty) >= 0).findFirst()
                    .orElse(sources.stream().max(Comparator.comparing(Source::transferable)).orElse(null));
            BigDecimal shipQty = best == null ? BigDecimal.ZERO : qty.min(best.transferable()).setScale(0, RoundingMode.FLOOR);
            LocalDate required;
            BigDecimal headroom = here.available().add(pipeline).subtract(trigger);
            if (daily.signum() > 0 && headroom.signum() > 0) {
                required = today.plusDays(headroom.divide(daily, 0, RoundingMode.FLOOR).longValue());
            } else {
                required = today.plusDays(transitDays);
            }
            String confidence = days >= 10 ? "HIGH" : days >= 3 ? "MEDIUM" : "LOW";
            if (best == null || shipQty.compareTo(qty) < 0 || !commitments.available()) {
                confidence = "LOW";
            }
            r.put("recommended", best != null && shipQty.signum() > 0);
            r.put("qty", shipQty);
            r.put("neededQty", qty);
            r.put("sourceSite", best == null ? null : best.site());
            r.put("source", best == null ? null : best.view());
            r.put("sourceAvailable", best == null ? null : best.free());
            r.put("alternatives", sources.stream().filter(s -> s != best).limit(3).map(Source::view).toList());
            r.put("whySource", best == null ? "No site has " + item + " free for transfer"
                    : !best.store() && best.transferable().compareTo(qty) >= 0
                    ? best.site() + " is a warehouse and has " + strip(best.free()) + " free (on hand " + strip(best.onHand())
                      + ", allocated to orders " + strip(best.allocatedOrders()) + ", to transfers " + strip(best.allocatedTransfers())
                      + ", waiting on open documents " + strip(best.waiting()) + ")"
                    : best.store() && best.transferable().compareTo(qty) >= 0
                    ? "No warehouse covers " + strip(qty) + "; store " + best.site() + " holds " + strip(best.transferable()) + " above its max"
                    : "No site covers " + strip(qty) + "; " + best.site() + " has the most free (" + strip(best.transferable()) + ")");
            r.put("requiredDate", required.toString());
            r.put("confidence", confidence);
            r.put("reason", "Available " + strip(here.available()) + " + pipeline " + strip(pipeline)
                    + (daily.signum() > 0 ? " − usage " + strip(daily) + "/day × " + transitDays + " day(s) transit" : " (no usage history: no demand assumed)")
                    + " = " + strip(projected) + ", below min " + strip(min) + " + safety " + strip(safety)
                    + "; refill to max " + strip(max));
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
