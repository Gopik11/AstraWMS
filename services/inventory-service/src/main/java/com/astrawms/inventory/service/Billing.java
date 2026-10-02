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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 3PL billing (ADR-0021), on the inventory ledger instead of a separate product. {@link #capture} turns ledger
 * operations into billable events, idempotently (one event per operation, owner and type):
 * <ul>
 *   <li>RECEIPT: a receipt operation (not from an RMA) — units, lines (items) and LPNs received;</li>
 *   <li>RETURN: a receipt from a customer return (source document {@code RMA ...});</li>
 *   <li>PICK: a pick operation — units and LPNs picked;</li>
 *   <li>STORAGE: per owner and UTC day, the units and LPNs on hand at the end of the day (from the ledger);</li>
 *   <li>VAS: value-added services entered by a supervisor (labelling, kitting, ...).</li>
 * </ul>
 * Each event is priced when captured, by the owner's rate for its type (and service) or the site's default rate:
 * per unit, line, LPN (storage: unit-day or LPN-day) or event. Events without a rate are kept at zero, so a rate set
 * later does not reprice history.
 */
@Service
public class Billing {

    static final List<String> TYPES = List.of("RECEIPT", "PICK", "RETURN", "STORAGE", "VAS");
    static final List<String> BASES = List.of("UNIT", "LINE", "LPN", "EVENT");
    /** How far back a capture re-reads the ledger, so operations committed late are not missed. */
    static final Duration LOOKBACK = Duration.ofMinutes(10);
    static final int MAX_STORAGE_BACKFILL_DAYS = 62;

    private final JdbcClient jdbc;
    private final Clock clock;

    public Billing(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------ rates

    public List<Map<String, Object>> rates(String siteId) {
        return jdbc.sql("""
                        select owner_id, event_type, service, basis, rate, currency, updated_by, updated_at from billing_rate
                        where site_id = :site order by owner_id, event_type, service""")
                .param("site", siteId).query().listOfRows();
    }

    public record RateRequest(String ownerId, String eventType, String service, String basis, BigDecimal rate,
                              String currency) {
    }

    @Transactional
    public List<Map<String, Object>> putRate(String siteId, RateRequest r) {
        String type = upper(r.eventType());
        String basis = upper(r.basis());
        if (type == null || !TYPES.contains(type) || basis == null || !BASES.contains(basis) || r.rate() == null
                || r.rate().signum() < 0) {
            throw ApiException.badRequest("INV_RATE_INVALID",
                    "eventType " + TYPES + ", basis " + BASES + " and a rate ≥ 0 are required");
        }
        if ("STORAGE".equals(type) && !List.of("UNIT", "LPN").contains(basis)) {
            throw ApiException.badRequest("INV_RATE_INVALID", "Storage is billed per unit-day or LPN-day");
        }
        jdbc.sql("""
                        insert into billing_rate (tenant_id, site_id, owner_id, event_type, service, basis, rate, currency,
                                                  updated_by, updated_at)
                        values (:t, :site, :owner, :type, :service, :basis, :rate, :cur, :user, :now)
                        on conflict (tenant_id, site_id, owner_id, event_type, service) do update set basis = excluded.basis,
                            rate = excluded.rate, currency = excluded.currency, updated_by = excluded.updated_by,
                            updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("site", siteId).param("owner", nz(upper(r.ownerId())))
                .param("type", type).param("service", "VAS".equals(type) ? nz(upper(r.service())) : "")
                .param("basis", basis).param("rate", r.rate())
                .param("cur", r.currency() == null || r.currency().isBlank() ? "USD" : r.currency().trim().toUpperCase())
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        return rates(siteId);
    }

    // ------------------------------------------------------------------------------------------ capture

    /** Captures what the ledger holds since the last run (and finished storage days); returns the events created. */
    @Transactional
    public Map<String, Object> capture(String siteId) {
        Instant now = clock.instant();
        record Run(Instant lastRun, LocalDate storageThrough) {
        }
        Run last = jdbc.sql("select last_run_at, storage_through from billing_capture where site_id = :site for update")
                .param("site", siteId)
                .query((rs, n) -> new Run(rs.getTimestamp(1).toInstant(),
                        rs.getDate(2) == null ? null : rs.getDate(2).toLocalDate()))
                .optional().orElse(new Run(null, null));
        Instant since = last.lastRun() == null ? Instant.EPOCH : last.lastRun().minus(LOOKBACK);
        int operations = captureOperations(siteId, since);

        LocalDate yesterday = now.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(1);
        LocalDate from = last.storageThrough() != null ? last.storageThrough().plusDays(1)
                : yesterday.minusDays(MAX_STORAGE_BACKFILL_DAYS - 1);
        if (from.isBefore(yesterday.minusDays(MAX_STORAGE_BACKFILL_DAYS - 1))) {
            from = yesterday.minusDays(MAX_STORAGE_BACKFILL_DAYS - 1);
        }
        int storage = 0;
        for (LocalDate day = from; !day.isAfter(yesterday); day = day.plusDays(1)) {
            storage += captureStorage(siteId, day);
        }
        jdbc.sql("""
                        insert into billing_capture (tenant_id, site_id, last_run_at, storage_through)
                        values (:t, :site, :now, :through)
                        on conflict (tenant_id, site_id) do update set last_run_at = excluded.last_run_at,
                            storage_through = excluded.storage_through""")
                .param("t", TenantContext.tenantId()).param("site", siteId).param("now", Timestamp.from(now))
                .param("through", Date.valueOf(yesterday)).update();
        return Map.of("operationEvents", operations, "storageEvents", storage, "capturedAt", now);
    }

    private int captureOperations(String siteId, Instant since) {
        record Op(UUID operation, String owner, String type, BigDecimal units, int lines, int lpns, Instant at,
                  String doc) {
        }
        List<Op> ops = jdbc.sql("""
                        select operation_id, owner_id,
                               case when txn_type = 'PICK_OUT' then 'PICK'
                                    when coalesce(source_doc, '') like 'RMA %' then 'RETURN' else 'RECEIPT' end as type,
                               sum(abs(qty_delta)), count(distinct item_no), count(distinct nullif(lpn_id, '')),
                               min(occurred_at), min(source_doc)
                        from inventory_txn
                        where site_id = :site and occurred_at > :since and txn_type in ('RECEIPT', 'PICK_OUT')
                        group by operation_id, owner_id, 3""")
                .param("site", siteId).param("since", Timestamp.from(since))
                .query((rs, n) -> new Op(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getInt(5), rs.getInt(6), rs.getTimestamp(7).toInstant(), rs.getString(8)))
                .list();
        int created = 0;
        for (Op o : ops) {
            created += insert(siteId, o.owner(), o.type(), "", "OP:" + o.operation() + ":" + o.owner(), o.at(), o.units(),
                    o.lines(), o.lpns(), o.doc());
        }
        return created;
    }

    /** End-of-day stock of each owner on {@code day} (UTC), from the ledger: units and LPNs with stock. */
    private int captureStorage(String siteId, LocalDate day) {
        Instant end = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        record Stock(String owner, BigDecimal units, int lpns) {
        }
        List<Stock> stock = jdbc.sql("""
                        with bal as (
                            select owner_id, lpn_id, location_id, sum(qty_delta) as qty from inventory_txn
                            where site_id = :site and occurred_at < :end
                            group by owner_id, lpn_id, location_id having sum(qty_delta) > 0)
                        select owner_id, sum(qty), count(distinct nullif(lpn_id, '')) from bal group by owner_id""")
                .param("site", siteId).param("end", Timestamp.from(end))
                .query((rs, n) -> new Stock(rs.getString(1), rs.getBigDecimal(2), rs.getInt(3))).list();
        int created = 0;
        for (Stock s : stock) {
            created += insert(siteId, s.owner(), "STORAGE", "", "STORAGE:" + day + ":" + s.owner(), end.minusSeconds(1),
                    s.units(), 0, s.lpns(), "Stock on hand at end of " + day + " (UTC)");
        }
        return created;
    }

    public record VasRequest(String ownerId, String service, BigDecimal qty, String ref, String note) {
    }

    /** A value-added service done for an owner (labelling, kitting, ...), billed by its VAS rate. */
    @Transactional
    public Map<String, Object> vas(String siteId, VasRequest r) {
        String owner = upper(r.ownerId());
        String service = upper(r.service());
        if (owner == null || service == null || r.qty() == null || r.qty().signum() <= 0) {
            throw ApiException.badRequest("INV_VAS_INVALID", "ownerId, service and a positive qty are required");
        }
        AccessScope.current().requireOwner(owner);
        String ref = "VAS:" + (r.ref() == null || r.ref().isBlank() ? UUID.randomUUID() : r.ref().trim());
        int created = insert(siteId, owner, "VAS", service, ref, clock.instant(), r.qty(), 1, 0,
                r.note() == null || r.note().isBlank() ? service : service + ": " + r.note().trim());
        if (created == 0) {
            throw ApiException.conflict("INV_VAS_DUPLICATE", "Service " + ref + " was already billed");
        }
        return events(siteId, null, null, owner, "VAS").getFirst();
    }

    private int insert(String siteId, String owner, String type, String service, String ref, Instant at, BigDecimal units,
                       int lines, int lpns, String description) {
        record Rate(String basis, BigDecimal rate, String currency) {
        }
        Rate rate = jdbc.sql("""
                        select basis, rate, currency from billing_rate
                        where site_id = :site and owner_id in (:owner, '') and event_type = :type and service = :service
                        order by owner_id desc limit 1""")
                .param("site", siteId).param("owner", owner).param("type", type).param("service", service)
                .query((rs, n) -> new Rate(rs.getString(1), rs.getBigDecimal(2), rs.getString(3))).optional().orElse(null);
        BigDecimal amount = BigDecimal.ZERO;
        if (rate != null) {
            BigDecimal quantity = switch (rate.basis()) {
                case "UNIT" -> units;
                case "LINE" -> BigDecimal.valueOf(lines);
                case "LPN" -> BigDecimal.valueOf(lpns);
                default -> BigDecimal.ONE;
            };
            amount = quantity.multiply(rate.rate()).setScale(2, RoundingMode.HALF_UP);
        }
        return jdbc.sql("""
                        insert into billing_event (tenant_id, site_id, owner_id, event_type, service, ref, occurred_at, units,
                                                   lines, lpns, basis, rate, amount, currency, description, created_by,
                                                   created_at)
                        values (:t, :site, :owner, :type, :service, :ref, :at, :units, :lines, :lpns, :basis, :rate, :amount,
                                :cur, :desc, :user, :now)
                        on conflict (tenant_id, site_id, event_type, ref) do nothing""")
                .param("t", TenantContext.tenantId()).param("site", siteId).param("owner", owner).param("type", type)
                .param("service", service).param("ref", ref).param("at", Timestamp.from(at)).param("units", units)
                .param("lines", lines).param("lpns", lpns).param("basis", rate == null ? null : rate.basis())
                .param("rate", rate == null ? null : rate.rate()).param("amount", amount)
                .param("cur", rate == null ? null : rate.currency()).param("desc", description)
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
    }

    // ------------------------------------------------------------------------------------------ reports

    /** Totals per owner, event type and service in a period (only the owners the caller may see). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> summary(String siteId, Instant from, Instant to, String ownerId) {
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select owner_id, event_type, service, count(*) as events, sum(units) as units, sum(lines) as lines,
                               sum(lpns) as lpns, sum(amount) as amount, max(currency) as currency,
                               count(*) filter (where rate is null) as unpriced
                        from billing_event
                        where site_id = :site and occurred_at >= :from and occurred_at < :to
                          and (cast(:owner as text) is null or owner_id = :owner)
                          and (:ownersAll or owner_id in (:owners))
                        group by owner_id, event_type, service order by owner_id, event_type, service""")
                .param("site", siteId).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("owner", upper(ownerId)).param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                .query().listOfRows().stream().map(Billing::strip).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> events(String siteId, Instant from, Instant to, String ownerId, String type) {
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select id, owner_id, event_type, service, ref, occurred_at, units, lines, lpns, basis, rate, amount,
                               currency, description, created_by
                        from billing_event
                        where site_id = :site and (cast(:from as timestamptz) is null or occurred_at >= :from)
                          and (cast(:to as timestamptz) is null or occurred_at < :to)
                          and (cast(:owner as text) is null or owner_id = :owner)
                          and (cast(:type as text) is null or event_type = :type)
                          and (:ownersAll or owner_id in (:owners))
                        order by occurred_at desc, id desc limit 500""")
                .param("site", siteId).param("from", from == null ? null : Timestamp.from(from))
                .param("to", to == null ? null : Timestamp.from(to)).param("owner", upper(ownerId))
                .param("type", upper(type)).param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                .query().listOfRows().stream().map(Billing::strip).toList();
    }

    private static Map<String, Object> strip(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.replaceAll((k, v) -> v instanceof BigDecimal b ? (b.signum() == 0 ? BigDecimal.ZERO : b.stripTrailingZeros()
                .scale() < 0 ? b.stripTrailingZeros().setScale(0) : b.stripTrailingZeros()) : v);
        return out;
    }

    private static String upper(String v) {
        return v == null || v.isBlank() ? null : v.trim().toUpperCase();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
