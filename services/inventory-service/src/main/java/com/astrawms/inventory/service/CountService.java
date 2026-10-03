package com.astrawms.inventory.service;

import com.astrawms.common.security.AccessScope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.inventory.domain.Quantities;
import com.astrawms.inventory.reference.ReferenceData.ItemRef;
import com.astrawms.inventory.reference.ReferenceRepository;
import com.astrawms.inventory.service.InventoryCommandService.CountAdjustment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cycle counting (§6.3, INV-003/004/008):
 * <pre>
 * OPEN ──count──▶ no variance ─────────────────────────────▶ CLOSED
 *                 within tolerance (units, value, no serials) ▶ ADJUSTED (CC_TOL)
 *                 otherwise ──▶ RECOUNT by another user ──▶ same result ─▶ PENDING_APPROVAL ─approve─▶ ADJUSTED (CC_VAR)
 *                                                        └▶ different ──▶ third count ──▶ PENDING_APPROVAL ─reject──▶ REJECTED
 * </pre>
 * Counts are blind: counters submit what they find and never see the system quantity. The variance is measured
 * against the stock at the location when the count is submitted, all stock statuses summed; adjustments go to
 * AVAILABLE stock. An approver may not be one of the counters, and their value limit applies (§G.5.1).
 */
@Service
public class CountService {

    /** A counted line as the counter enters it. */
    public record CountLine(String ownerId, String itemNo, String lotNo, String lpnId, BigDecimal qty) {
    }

    record Key(String ownerId, String itemNo, String lotNo, String lpnId) implements Comparable<Key> {
        @Override
        public int compareTo(Key o) {
            return (ownerId + "|" + itemNo + "|" + lotNo + "|" + lpnId).compareTo(o.ownerId + "|" + o.itemNo + "|" + o.lotNo + "|" + o.lpnId);
        }
    }

    public record Variance(String ownerId, String itemNo, String lotNo, String lpnId, BigDecimal systemQty,
                           BigDecimal countedQty, BigDecimal variance, BigDecimal unitCost) {
    }

    public record CountView(UUID id, String siteId, String locationId, String trigger, String status, int countsDone,
                            String requestedBy, String note, BigDecimal varianceValue, String decidedBy,
                            List<Map<String, Object>> results, List<Variance> variances) {
    }

    /** What a counter learns from submitting: never the system quantity (blind count, INV-003). */
    public record SubmitResult(UUID countId, int sequence, String status) {
    }

    private record Count(UUID id, String siteId, String locationId, String trigger, String status, int countsDone) {
    }

    private final JdbcClient jdbc;
    private final ReferenceRepository refs;
    private final InventoryCommandService commands;
    private final CountRequests requests;
    private final JsonMapper json;
    private final Clock clock;
    private final BigDecimal toleranceUnits;
    private final BigDecimal toleranceValue;

    public CountService(JdbcClient jdbc, ReferenceRepository refs, InventoryCommandService commands, CountRequests requests,
                        JsonMapper json, Clock clock,
                        @Value("${astra.inventory.count.tolerance-units:2}") BigDecimal toleranceUnits,
                        @Value("${astra.inventory.count.tolerance-value:50}") BigDecimal toleranceValue) {
        this.jdbc = jdbc;
        this.refs = refs;
        this.commands = commands;
        this.requests = requests;
        this.json = json;
        this.clock = clock;
        this.toleranceUnits = toleranceUnits;
        this.toleranceValue = toleranceValue;
    }

    /** Ad-hoc counts (supervisor-created, §6.3) for the given locations; returns the open count per location. */
    @Transactional
    public List<UUID> create(String siteId, List<String> locationIds, String note) {
        List<UUID> ids = new ArrayList<>();
        for (String loc : new LinkedHashSet<>(locationIds)) {
            boolean known = jdbc.sql("select exists (select 1 from ref_location where site_id = :site and location_id = :loc)")
                    .param("site", siteId).param("loc", loc).query(Boolean.class).single();
            if (!known) {
                throw ApiException.unprocessable("INV_LOCATION_UNKNOWN", "Location " + loc + " does not exist");
            }
            ids.add(requests.open(siteId, loc, "ADHOC", note));
        }
        return ids;
    }

    /**
     * A counter's result (idempotent per task via {@code idempotencyKey}): evaluates the variance and moves the count
     * on. Counters other than the first must differ from the earlier counters.
     */
    @Transactional
    public SubmitResult submit(String siteId, UUID countId, String idempotencyKey, List<CountLine> lines) {
        Count c = lock(siteId, countId);
        String user = TenantContext.require().userId();
        Optional<Integer> replay = jdbc.sql("select sequence from stock_count_result where count_id = :c and lines->>'key' = :k")
                .param("c", countId).param("k", idempotencyKey).query(Integer.class).optional();
        if (replay.isPresent()) {
            return new SubmitResult(countId, replay.get(), lock(siteId, countId).status());
        }
        if (!List.of("OPEN", "RECOUNT").contains(c.status())) {
            throw ApiException.conflict("INV_COUNT_NOT_OPEN", "Count is " + c.status());
        }
        List<String> counters = counters(countId);
        if (counters.contains(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INV_RECOUNT_SAME_USER", "A recount must be done by another user");
        }
        Map<Key, BigDecimal> counted = normalise(lines);
        counted.keySet().forEach(k -> AccessScope.current().requireOwner(k.ownerId()));
        int sequence = c.countsDone() + 1;
        jdbc.sql("""
                        insert into stock_count_result (count_id, tenant_id, sequence, counted_by, counted_at, lines)
                        values (:c, :t, :seq, :user, :now, cast(:lines as jsonb))""")
                .param("c", countId).param("t", TenantContext.tenantId()).param("seq", sequence).param("user", user)
                .param("now", Timestamp.from(clock.instant()))
                .param("lines", json.writeValueAsString(Map.of("key", idempotencyKey, "lines", toLines(counted))))
                .update();

        List<Variance> variances = evaluate(c, counted);
        // ADR-0022: counts of a physical inventory never post on their own; differences wait for the PI's posting.
        boolean physical = jdbc.sql("select pi_id is not null from stock_count where id = :id").param("id", countId)
                .query(Boolean.class).single();
        String next;
        if (variances.isEmpty()) {
            next = "CLOSED";
        } else if (sequence == 1 && withinTolerance(siteId, variances)) {
            if (physical) {
                next = "PENDING_APPROVAL";
            } else {
                apply(c, variances, "CC_TOL", null, "count-" + countId + "-tol");
                next = "ADJUSTED";
            }
        } else if (sequence == 1 || (sequence == 2 && !counted.equals(previous(countId, 1)))) {
            List<String> excluded = new ArrayList<>(counters);
            excluded.add(user);
            requests.request(siteId, countId, c.locationId(), sequence + 1, excluded, c.trigger());
            next = "RECOUNT";
        } else {
            next = "PENDING_APPROVAL";
        }
        BigDecimal value = value(variances);
        jdbc.sql("""
                        update stock_count set status = :status, counts_done = :seq, variance_value = :value,
                            decided_by = case when :status = 'ADJUSTED' then 'system (tolerance)' else decided_by end,
                            decided_at = case when :status in ('ADJUSTED', 'CLOSED') then :now else decided_at end,
                            updated_at = :now where id = :id""")
                .param("status", next).param("seq", sequence).param("value", value)
                .param("now", Timestamp.from(clock.instant())).param("id", countId).update();
        return new SubmitResult(countId, sequence, next);
    }

    /** Approves the variance of the final count (INV-008: not by a counter; value within the approver's limit). */
    @Transactional
    public CountView approve(String siteId, UUID countId) {
        boolean physical = jdbc.sql("select pi_id is not null from stock_count where site_id = :site and id = :id")
                .param("site", siteId).param("id", countId).query(Boolean.class).optional().orElse(false);
        if (physical) {
            throw ApiException.unprocessable("INV_COUNT_IN_PHYSICAL_INVENTORY",
                    "This count belongs to a physical inventory; its differences are posted with the physical inventory");
        }
        return decide(siteId, countId, "CC_VAR");
    }

    /** Posting of a physical inventory (ADR-0022): approves one of its counts with reason PI_DIFF. */
    CountView approveForPhysicalInventory(String siteId, UUID countId) {
        return decide(siteId, countId, "PI_DIFF");
    }

    private CountView decide(String siteId, UUID countId, String reason) {
        Count c = lock(siteId, countId);
        if (!"PENDING_APPROVAL".equals(c.status())) {
            throw ApiException.conflict("INV_COUNT_NOT_PENDING", "Count is " + c.status());
        }
        String approver = TenantContext.require().userId();
        if (counters(countId).contains(approver)) {
            throw ApiException.unprocessable("INV_SELF_APPROVAL", "A counter cannot approve their own count (INV-008)");
        }
        List<Variance> variances = evaluate(c, previous(countId, c.countsDone()));
        BigDecimal limit = AccessScope.current().approvalLimit();
        if (limit != null) {
            if (variances.stream().anyMatch(v -> v.unitCost() == null)) {
                throw ApiException.unprocessable("APPROVAL_VALUE_UNKNOWN",
                        "An item of this count has no standard cost, so your value limit cannot be checked");
            }
            BigDecimal value = value(variances);
            if (value.compareTo(limit) > 0) {
                throw new ApiException(HttpStatus.FORBIDDEN, "APPROVAL_LIMIT_EXCEEDED",
                        "Variance value " + value.toPlainString() + " exceeds your approval limit of " + limit.toPlainString(),
                        Map.of("value", value, "approvalLimit", limit));
            }
        }
        UUID operation = variances.isEmpty() ? null : apply(c, variances, reason, approver, "count-" + countId + "-var");
        jdbc.sql("""
                        update stock_count set status = :status, decided_by = :user, decided_at = :now, operation_id = :op,
                            variance_value = :value, updated_at = :now where id = :id""")
                .param("status", variances.isEmpty() ? "CLOSED" : "ADJUSTED").param("user", approver)
                .param("now", Timestamp.from(clock.instant())).param("op", operation).param("value", value(variances))
                .param("id", countId).update();
        return view(siteId, countId);
    }

    @Transactional
    public CountView reject(String siteId, UUID countId, String note) {
        Count c = lock(siteId, countId);
        if (!"PENDING_APPROVAL".equals(c.status())) {
            throw ApiException.conflict("INV_COUNT_NOT_PENDING", "Count is " + c.status());
        }
        jdbc.sql("""
                        update stock_count set status = 'REJECTED', decided_by = :user, decided_at = :now,
                            note = coalesce(:note, note), updated_at = :now where id = :id""")
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant()))
                .param("note", note).param("id", countId).update();
        return view(siteId, countId);
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String siteId, String status) {
        return jdbc.sql("""
                        select id, location_id, trigger, status, counts_done, requested_by, variance_value, decided_by,
                               created_at, updated_at
                        from stock_count where site_id = :site and (cast(:status as text) is null or status = :status)
                        order by created_at desc limit 500""")
                .param("site", siteId).param("status", status).query().listOfRows();
    }

    @Transactional(readOnly = true)
    public CountView view(String siteId, UUID countId) {
        record Header(String location, String trigger, String status, int done, String requestedBy, String note,
                      BigDecimal value, String decidedBy) {
        }
        Header h = jdbc.sql("""
                        select location_id, trigger, status, counts_done, requested_by, note, variance_value, decided_by
                        from stock_count where site_id = :site and id = :id""")
                .param("site", siteId).param("id", countId)
                .query((rs, n) -> new Header(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getString(8)))
                .optional().orElseThrow(() -> unknown(countId));
        List<Map<String, Object>> results = jdbc.sql("""
                        select sequence, counted_by, counted_at, lines->'lines' as lines from stock_count_result
                        where count_id = :c order by sequence""")
                .param("c", countId)
                .query((rs, n) -> Map.<String, Object>of("sequence", rs.getInt(1), "countedBy", rs.getString(2),
                        "countedAt", rs.getTimestamp(3).toInstant(), "lines", json.readTree(rs.getString(4))))
                .list();
        List<Variance> variances = jdbc.sql("""
                        select owner_id, item_no, lot_no, lpn_id, system_qty, counted_qty, unit_cost
                        from stock_count_variance where count_id = :c order by owner_id, item_no, lot_no, lpn_id""")
                .param("c", countId)
                .query((rs, n) -> {
                    BigDecimal sys = Quantities.normalize(rs.getBigDecimal(5));
                    BigDecimal cnt = Quantities.normalize(rs.getBigDecimal(6));
                    return new Variance(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), sys, cnt,
                            Quantities.normalize(cnt.subtract(sys)), rs.getBigDecimal(7));
                })
                .list();
        return new CountView(countId, siteId, h.location(), h.trigger(), h.status(), h.done(), h.requestedBy(), h.note(),
                h.value(), h.decidedBy(), results, variances);
    }

    // ------------------------------------------------------------------ internals

    private Count lock(String siteId, UUID countId) {
        return jdbc.sql("""
                        select id, site_id, location_id, trigger, status, counts_done from stock_count
                        where site_id = :site and id = :id for update""")
                .param("site", siteId).param("id", countId)
                .query((rs, n) -> new Count(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6)))
                .optional().orElseThrow(() -> unknown(countId));
    }

    private List<String> counters(UUID countId) {
        return jdbc.sql("select counted_by from stock_count_result where count_id = :c order by sequence")
                .param("c", countId).query(String.class).list();
    }

    private Map<Key, BigDecimal> previous(UUID countId, int sequence) {
        String lines = jdbc.sql("select lines->'lines' from stock_count_result where count_id = :c and sequence = :s")
                .param("c", countId).param("s", sequence).query(String.class).single();
        List<CountLine> parsed = json.readerForListOf(CountLine.class).readValue(lines);
        return normalise(parsed);
    }

    private static Map<Key, BigDecimal> normalise(List<CountLine> lines) {
        Map<Key, BigDecimal> out = new TreeMap<>();
        for (CountLine l : lines == null ? List.<CountLine>of() : lines) {
            if (l.ownerId() == null || l.ownerId().isBlank() || l.itemNo() == null || l.itemNo().isBlank() || l.qty() == null
                    || l.qty().signum() < 0) {
                throw ApiException.badRequest("INV_COUNT_LINE_INVALID", "Each counted line needs owner, item and a quantity ≥ 0");
            }
            Key k = new Key(l.ownerId().trim(), l.itemNo().trim(), trim(l.lotNo()), trim(l.lpnId()));
            out.merge(k, l.qty(), BigDecimal::add);
        }
        out.replaceAll((k, v) -> Quantities.normalize(v));
        out.values().removeIf(v -> v.signum() == 0);
        return out;
    }

    private static List<CountLine> toLines(Map<Key, BigDecimal> counted) {
        return counted.entrySet().stream()
                .map(e -> new CountLine(e.getKey().ownerId(), e.getKey().itemNo(), e.getKey().lotNo(), e.getKey().lpnId(), e.getValue()))
                .toList();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** Compares the counted quantities with the stock at the location now (all statuses) and stores the variance. */
    private List<Variance> evaluate(Count c, Map<Key, BigDecimal> counted) {
        Map<Key, BigDecimal> system = new HashMap<>();
        jdbc.sql("""
                        select owner_id, item_no, lot_no, lpn_id, sum(qty) from inventory_balance
                        where site_id = :site and location_id = :loc group by owner_id, item_no, lot_no, lpn_id""")
                .param("site", c.siteId()).param("loc", c.locationId())
                .query((rs, n) -> Map.entry(new Key(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                        Quantities.normalize(rs.getBigDecimal(5))))
                .list().forEach(e -> system.put(e.getKey(), e.getValue()));
        Set<Key> keys = new java.util.TreeSet<>(system.keySet());
        keys.addAll(counted.keySet());
        List<Variance> out = new ArrayList<>();
        jdbc.sql("delete from stock_count_variance where count_id = :c").param("c", c.id()).update();
        for (Key k : keys) {
            BigDecimal sys = system.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal cnt = counted.getOrDefault(k, BigDecimal.ZERO);
            if (sys.compareTo(cnt) == 0) {
                continue;
            }
            BigDecimal cost = refs.item(k.ownerId(), k.itemNo(), c.siteId()).map(ItemRef::standardCost).orElse(null);
            out.add(new Variance(k.ownerId(), k.itemNo(), k.lotNo(), k.lpnId(), sys, cnt, cnt.subtract(sys), cost));
            jdbc.sql("""
                            insert into stock_count_variance (count_id, tenant_id, owner_id, item_no, lot_no, lpn_id,
                                                              system_qty, counted_qty, unit_cost)
                            values (:c, :t, :owner, :item, :lot, :lpn, :sys, :cnt, :cost)""")
                    .param("c", c.id()).param("t", TenantContext.tenantId()).param("owner", k.ownerId())
                    .param("item", k.itemNo()).param("lot", k.lotNo()).param("lpn", k.lpnId()).param("sys", sys)
                    .param("cnt", cnt).param("cost", cost).update();
        }
        return out;
    }

    /** Auto-accept (§6.3 tolerance matrix): every variance within the unit and value tolerance, no serial items. */
    private boolean withinTolerance(String siteId, List<Variance> variances) {
        for (Variance v : variances) {
            Optional<ItemRef> item = refs.item(v.ownerId(), v.itemNo(), siteId);
            if (item.isEmpty() || item.get().serialTracked() || v.unitCost() == null) {
                return false;
            }
            if (v.variance().abs().compareTo(toleranceUnits) > 0
                    || v.variance().abs().multiply(v.unitCost()).compareTo(toleranceValue) > 0) {
                return false;
            }
        }
        return true;
    }

    private static BigDecimal value(List<Variance> variances) {
        return variances.stream().filter(v -> v.unitCost() != null)
                .map(v -> v.variance().abs().multiply(v.unitCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private UUID apply(Count c, List<Variance> variances, String reason, String approvedBy, String key) {
        List<CountAdjustment> adjustments = variances.stream()
                .map(v -> new CountAdjustment(v.ownerId(), v.itemNo(), v.lotNo(), v.lpnId(), v.variance()))
                .filter(a -> a.delta().signum() != 0)
                .toList();
        return commands.applyCountVariance(c.siteId(), c.locationId(), key, c.id(), reason, approvedBy, adjustments)
                .operationId();
    }

    private static ApiException unknown(UUID countId) {
        return ApiException.notFound("INV_COUNT_UNKNOWN", "Count " + countId + " not found");
    }
}
