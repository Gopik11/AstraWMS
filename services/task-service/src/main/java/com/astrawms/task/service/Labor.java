package com.astrawms.task.service;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Labor standards and the supervisor's labor board (ADR-0021). Each task type has an engineered standard — a base
 * time plus a time per unit — that gives every task an expected duration. The board compares, per operator, the
 * standard minutes of the work completed in a window with the minutes it took (assigned → completed), shows what each
 * active operator is doing and whether it is over its standard, and the open backlog in standard hours.
 * Operators can carry equipment and skills: {@link TaskService#next} gives a task only to an operator who has the
 * skill its type requires and the equipment the zones it touches require.
 */
@Service
public class Labor {

    /** Defaults until a site sets its own standards: base seconds and seconds per unit. */
    static final Map<String, int[]> DEFAULTS = Map.of(
            "RECEIVE", new int[] {300, 0},
            "PUTAWAY", new int[] {180, 0},
            "PICK", new int[] {45, 6},
            "REPLEN", new int[] {240, 0},
            "MOVE", new int[] {180, 0},
            "COUNT", new int[] {120, 0},
            "RETURN", new int[] {180, 0});

    public record Standard(String taskType, int baseSeconds, BigDecimal perUnitSeconds, String requiredSkill,
                           boolean isDefault, String updatedBy) {

        long seconds(BigDecimal qty) {
            return baseSeconds + (qty == null ? 0 : perUnitSeconds.multiply(qty).longValue());
        }
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public Labor(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------ standards

    public List<Standard> standards(String siteId) {
        Map<String, Standard> byType = new TreeMap<>();
        DEFAULTS.forEach((type, d) -> byType.put(type, new Standard(type, d[0], BigDecimal.valueOf(d[1]), null, true, null)));
        jdbc.sql("""
                        select task_type, base_seconds, per_unit_seconds, required_skill, updated_by from task_standard
                        where site_id = :site""")
                .param("site", siteId)
                .query((rs, n) -> new Standard(rs.getString(1), rs.getInt(2), rs.getBigDecimal(3).stripTrailingZeros(),
                        rs.getString(4), false, rs.getString(5)))
                .list().forEach(s -> byType.put(s.taskType(), s));
        return List.copyOf(byType.values());
    }

    @Transactional
    public List<Standard> putStandard(String siteId, String taskType, Integer baseSeconds, BigDecimal perUnitSeconds,
                                     String requiredSkill) {
        String type = taskType.trim().toUpperCase();
        if (!DEFAULTS.containsKey(type)) {
            throw ApiException.badRequest("TSK_STANDARD_INVALID", "Unknown task type " + taskType);
        }
        if (baseSeconds == null || baseSeconds < 0 || (perUnitSeconds != null && perUnitSeconds.signum() < 0)) {
            throw ApiException.badRequest("TSK_STANDARD_INVALID", "baseSeconds (≥ 0) is required; perUnitSeconds must be ≥ 0");
        }
        jdbc.sql("""
                        insert into task_standard (tenant_id, site_id, task_type, base_seconds, per_unit_seconds,
                                                   required_skill, updated_by, updated_at)
                        values (:t, :site, :type, :base, :unit, :skill, :user, :now)
                        on conflict (tenant_id, site_id, task_type) do update set base_seconds = excluded.base_seconds,
                            per_unit_seconds = excluded.per_unit_seconds, required_skill = excluded.required_skill,
                            updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("site", siteId).param("type", type).param("base", baseSeconds)
                .param("unit", perUnitSeconds == null ? BigDecimal.ZERO : perUnitSeconds)
                .param("skill", blankToNull(requiredSkill)).param("user", TenantContext.require().userId())
                .param("now", Timestamp.from(clock.instant())).update();
        return standards(siteId);
    }

    // ------------------------------------------------------------------------------------------ equipment and skills

    public List<Map<String, Object>> operators() {
        return jdbc.sql("""
                        select user_id, array_to_string(equipment, ',') as equipment, array_to_string(skills, ',') as skills,
                               updated_by, updated_at
                        from operator_profile order by user_id""").query().listOfRows();
    }

    @Transactional
    public Map<String, Object> putOperator(String userId, List<String> equipment, List<String> skills) {
        jdbc.sql("""
                        insert into operator_profile (tenant_id, user_id, equipment, skills, updated_by, updated_at)
                        values (:t, :u, string_to_array(:eq, ','), string_to_array(:sk, ','), :user, :now)
                        on conflict (tenant_id, user_id) do update set equipment = excluded.equipment,
                            skills = excluded.skills, updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("u", userId.trim()).param("eq", codes(equipment))
                .param("sk", codes(skills)).param("user", TenantContext.require().userId())
                .param("now", Timestamp.from(clock.instant())).update();
        return operators().stream().filter(o -> userId.trim().equals(o.get("user_id"))).findFirst().orElseThrow();
    }

    public List<Map<String, Object>> zoneEquipment(String siteId) {
        return jdbc.sql("select zone_id, equipment, updated_by, updated_at from zone_equipment where site_id = :site order by zone_id")
                .param("site", siteId).query().listOfRows();
    }

    /** Sets the equipment a zone needs; blank removes the requirement. */
    @Transactional
    public List<Map<String, Object>> putZoneEquipment(String siteId, String zoneId, String equipment) {
        String zone = zoneId.trim().toUpperCase();
        String eq = blankToNull(equipment);
        if (eq == null) {
            jdbc.sql("delete from zone_equipment where site_id = :site and zone_id = :zone")
                    .param("site", siteId).param("zone", zone).update();
        } else {
            jdbc.sql("""
                            insert into zone_equipment (tenant_id, site_id, zone_id, equipment, updated_by, updated_at)
                            values (:t, :site, :zone, :eq, :user, :now)
                            on conflict (tenant_id, site_id, zone_id) do update set equipment = excluded.equipment,
                                updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                    .param("t", TenantContext.tenantId()).param("site", siteId).param("zone", zone).param("eq", eq)
                    .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        }
        return zoneEquipment(siteId);
    }

    /** The current operator's equipment and skills, comma-separated for SQL ({@code string_to_array}). */
    String[] profileOfCurrentUser() {
        return jdbc.sql("""
                        select array_to_string(equipment, ','), array_to_string(skills, ',') from operator_profile
                        where user_id = :u""")
                .param("u", TenantContext.require().userId())
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).optional()
                .orElse(new String[] {"", ""});
    }

    // ------------------------------------------------------------------------------------------ the board

    /**
     * The labor board for the last {@code hours}: per operator the completed tasks, standard vs actual minutes and
     * performance, their current task and how long it has run against its standard; and the open backlog per type.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> board(String siteId, int hours) {
        Instant now = clock.instant();
        Instant from = now.minus(Duration.ofHours(hours));
        Map<String, Standard> std = new HashMap<>();
        standards(siteId).forEach(s -> std.put(s.taskType(), s));

        record Done(String user, String type, BigDecimal qty, Instant assigned, Instant completed) {
        }
        List<Done> done = jdbc.sql("""
                        select assigned_to, task_type, qty, assigned_at, completed_at from task
                        where site_id = :site and status = 'COMPLETED' and completed_at >= :from and assigned_to is not null""")
                .param("site", siteId).param("from", Timestamp.from(from))
                .query((rs, n) -> new Done(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()))
                .list();
        record Open(String id, String user, String type, BigDecimal qty, Instant assigned, String status, String from,
                    String to, String orderRef) {
        }
        List<Open> open = jdbc.sql("""
                        select id::text, assigned_to, task_type, qty, assigned_at, status, from_location, target_location,
                               order_ref
                        from task where site_id = :site and status in ('RELEASED', 'ASSIGNED')""")
                .param("site", siteId)
                .query((rs, n) -> new Open(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9)))
                .list();
        Map<String, Instant> lastActivity = new HashMap<>();
        jdbc.sql("""
                        select e.user_id, max(e.at) from task_event e join task t on t.id = e.task_id
                        where t.site_id = :site and e.at >= :from group by e.user_id""")
                .param("site", siteId).param("from", Timestamp.from(from))
                .query((rs, n) -> lastActivity.put(rs.getString(1), rs.getTimestamp(2).toInstant())).list();

        Map<String, Map<String, Object>> operators = new TreeMap<>();
        for (Done d : done) {
            Map<String, Object> o = operators.computeIfAbsent(d.user(), Labor::operatorRow);
            Standard s = std.get(d.type());
            long standard = s == null ? 0 : s.seconds(d.qty());
            long actual = d.assigned() == null ? standard : Math.max(1, Duration.between(d.assigned(), d.completed()).toSeconds());
            o.merge("completed", 1, (a, b) -> (Integer) a + (Integer) b);
            o.merge("standardSeconds", standard, (a, b) -> (Long) a + (Long) b);
            o.merge("actualSeconds", actual, (a, b) -> (Long) a + (Long) b);
            @SuppressWarnings("unchecked")
            Map<String, Integer> byType = (Map<String, Integer>) o.get("byType");
            byType.merge(d.type(), 1, Integer::sum);
        }
        Map<String, long[]> backlog = new TreeMap<>();
        Map<String, long[]> byOrder = new TreeMap<>();
        for (Open t : open) {
            Standard s = std.get(t.type());
            long expected = s == null ? 0 : s.seconds(t.qty());
            if ("PICK".equals(t.type()) && t.orderRef() != null) {
                long[] o = byOrder.computeIfAbsent(t.orderRef(), k -> new long[2]);
                o[0]++;
                o[1] += expected;
            }
            if ("RELEASED".equals(t.status())) {
                long[] b = backlog.computeIfAbsent(t.type(), k -> new long[2]);
                b[0]++;
                b[1] += expected;
                continue;
            }
            if (t.user() == null) {
                continue;
            }
            Map<String, Object> o = operators.computeIfAbsent(t.user(), Labor::operatorRow);
            long age = t.assigned() == null ? 0 : Duration.between(t.assigned(), now).toSeconds();
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("taskId", t.id());
            current.put("taskType", t.type());
            current.put("from", t.from());
            current.put("to", t.to());
            current.put("assignedAt", t.assigned());
            current.put("ageMinutes", round(age / 60.0));
            current.put("expectedMinutes", round(expected / 60.0));
            current.put("overStandard", expected > 0 && age > expected);
            o.put("current", current);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : operators.entrySet()) {
            Map<String, Object> o = e.getValue();
            o.put("userId", e.getKey());
            long standard = (Long) o.remove("standardSeconds");
            long actual = (Long) o.remove("actualSeconds");
            o.put("standardMinutes", round(standard / 60.0));
            o.put("actualMinutes", round(actual / 60.0));
            o.put("performancePct", actual == 0 ? null : Math.round(100.0 * standard / actual));
            Instant last = lastActivity.get(e.getKey());
            o.put("lastActivity", last);
            o.put("active", o.get("current") != null || (last != null && Duration.between(last, now).toMinutes() < 15));
            rows.add(o);
        }
        List<Map<String, Object>> backlogRows = new ArrayList<>();
        backlog.forEach((type, b) -> backlogRows.add(Map.of("taskType", type, "open", b[0],
                "standardHours", round(b[1] / 3600.0))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from);
        result.put("to", now);
        result.put("activeOperators", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("active"))).count());
        result.put("operators", rows);
        result.put("backlog", backlogRows);
        // ADR-0024 cutoff forecast: open pick work per order in standard minutes; the UI sets it against the cutoffs.
        List<Map<String, Object>> orderRows = new ArrayList<>();
        byOrder.forEach((order, o) -> orderRows.add(Map.of("orderRef", order, "openPicks", o[0],
                "standardMinutes", round(o[1] / 60.0))));
        result.put("pickWorkByOrder", orderRows);
        return result;
    }

    private static Map<String, Object> operatorRow(String user) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("completed", 0);
        o.put("standardSeconds", 0L);
        o.put("actualSeconds", 0L);
        o.put("byType", new TreeMap<String, Integer>());
        return o;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String codes(List<String> values) {
        return values == null ? "" : String.join(",", values.stream().map(String::trim).filter(v -> !v.isEmpty())
                .map(String::toUpperCase).distinct().toList());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim().toUpperCase();
    }
}
