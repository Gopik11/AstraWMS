package com.astrawms.inbound.yard;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Yard and dock appointments (ADR-0021), part of the inbound service rather than a separate product:
 * <pre>
 * SCHEDULED ──check-in (gate)──▶ CHECKED_IN ──to door──▶ AT_DOOR ──check-out──▶ CHECKED_OUT
 *     └──▶ CANCELLED / NO_SHOW
 * </pre>
 * A door can be booked before the ASN exists (the delivery number is linked later). A door is booked by at most one
 * appointment at a time, and holds at most one trailer. Receipts show their appointment and the trailer's dwell.
 */
@Service
public class YardService {

    static final List<String> ACTIVE = List.of("SCHEDULED", "CHECKED_IN", "AT_DOOR");

    private final JdbcClient jdbc;
    private final Clock clock;

    public YardService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Booking(String direction, String door, String carrierScac, String trailerNo, String docNo,
                          Instant start, Instant end, String note) {
    }

    @Transactional
    public Map<String, Object> book(String siteId, Booking b) {
        String direction = b.direction() == null ? "INBOUND" : b.direction().trim().toUpperCase();
        if (!List.of("INBOUND", "OUTBOUND").contains(direction)) {
            throw ApiException.badRequest("YRD_DIRECTION_INVALID", "direction must be INBOUND or OUTBOUND");
        }
        if (b.start() == null) {
            throw ApiException.badRequest("YRD_TIME_REQUIRED", "start is required");
        }
        Instant end = b.end() == null ? b.start().plus(Duration.ofHours(1)) : b.end();
        if (!end.isAfter(b.start())) {
            throw ApiException.badRequest("YRD_TIME_INVALID", "end must be after start");
        }
        String door = upper(b.door());
        requireDoorFree(siteId, door, b.start(), end, null);
        long seq = jdbc.sql("select count(*) + 1 from dock_appointment where site_id = :site").param("site", siteId)
                .query(Long.class).single();
        String no = "AP%06d".formatted(seq);
        Timestamp now = Timestamp.from(clock.instant());
        String user = TenantContext.require().userId();
        jdbc.sql("""
                        insert into dock_appointment (id, tenant_id, site_id, appt_no, direction, door, carrier_scac,
                            trailer_no, doc_no, scheduled_start, scheduled_end, status, note, created_by, created_at,
                            updated_by, updated_at)
                        values (:id, :t, :site, :no, :dir, :door, :scac, :trailer, :doc, :start, :end, 'SCHEDULED', :note,
                                :user, :now, :user, :now)""")
                .param("id", UUID.randomUUID()).param("t", TenantContext.tenantId()).param("site", siteId).param("no", no)
                .param("dir", direction).param("door", door).param("scac", upper(b.carrierScac()))
                .param("trailer", upper(b.trailerNo())).param("doc", trim(b.docNo())).param("start", Timestamp.from(b.start()))
                .param("end", Timestamp.from(end)).param("note", trim(b.note())).param("user", user).param("now", now).update();
        return detail(siteId, no);
    }

    /** Reschedule, change the door or trailer, or link the delivery once the ASN is known; null keeps a field. */
    @Transactional
    public Map<String, Object> update(String siteId, String apptNo, Booking b) {
        Map<String, Object> cur = lock(siteId, apptNo);
        if (!ACTIVE.contains((String) cur.get("status"))) {
            throw ApiException.unprocessable("YRD_NOT_ACTIVE", "Appointment " + apptNo + " is " + cur.get("status"));
        }
        Instant start = b.start() != null ? b.start() : ((Timestamp) cur.get("scheduled_start")).toInstant();
        Instant end = b.end() != null ? b.end() : b.start() != null
                ? start.plus(Duration.between(((Timestamp) cur.get("scheduled_start")).toInstant(),
                        ((Timestamp) cur.get("scheduled_end")).toInstant()))
                : ((Timestamp) cur.get("scheduled_end")).toInstant();
        if (!end.isAfter(start)) {
            throw ApiException.badRequest("YRD_TIME_INVALID", "end must be after start");
        }
        String door = b.door() != null ? upper(b.door()) : (String) cur.get("door");
        requireDoorFree(siteId, door, start, end, (UUID) cur.get("id"));
        jdbc.sql("""
                        update dock_appointment set door = :door, scheduled_start = :start, scheduled_end = :end,
                            carrier_scac = coalesce(:scac, carrier_scac), trailer_no = coalesce(:trailer, trailer_no),
                            doc_no = coalesce(:doc, doc_no), note = coalesce(:note, note), updated_by = :user,
                            updated_at = :now
                        where id = :id""")
                .param("door", door).param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
                .param("scac", upper(b.carrierScac())).param("trailer", upper(b.trailerNo())).param("doc", trim(b.docNo()))
                .param("note", trim(b.note())).param("user", TenantContext.require().userId())
                .param("now", Timestamp.from(clock.instant())).param("id", cur.get("id")).update();
        return detail(siteId, apptNo);
    }

    /** Gate check-in: the trailer is in the yard; dwell starts. */
    @Transactional
    public Map<String, Object> checkIn(String siteId, String apptNo, String trailerNo) {
        Map<String, Object> cur = lock(siteId, apptNo);
        requireStatus(cur, "SCHEDULED");
        jdbc.sql("""
                        update dock_appointment set status = 'CHECKED_IN', checked_in_at = :now,
                            trailer_no = coalesce(:trailer, trailer_no), updated_by = :user, updated_at = :now where id = :id""")
                .param("trailer", upper(trailerNo)).param("user", TenantContext.require().userId())
                .param("now", Timestamp.from(clock.instant())).param("id", cur.get("id")).update();
        return detail(siteId, apptNo);
    }

    /** The trailer moves to a door (the booked one unless another is given); the door must be empty. */
    @Transactional
    public Map<String, Object> toDoor(String siteId, String apptNo, String door) {
        Map<String, Object> cur = lock(siteId, apptNo);
        requireStatus(cur, "CHECKED_IN");
        String target = door != null && !door.isBlank() ? upper(door) : (String) cur.get("door");
        if (target == null) {
            throw ApiException.badRequest("YRD_DOOR_REQUIRED", "No door booked; say which door");
        }
        String occupant = jdbc.sql("""
                        select appt_no from dock_appointment where site_id = :site and door = :door and status = 'AT_DOOR'
                          and id <> :id limit 1""")
                .param("site", siteId).param("door", target).param("id", cur.get("id")).query(String.class).optional()
                .orElse(null);
        if (occupant != null) {
            throw ApiException.conflict("YRD_DOOR_OCCUPIED", "Door " + target + " holds the trailer of " + occupant);
        }
        jdbc.sql("""
                        update dock_appointment set status = 'AT_DOOR', door = :door, at_door_at = :now, updated_by = :user,
                            updated_at = :now where id = :id""")
                .param("door", target).param("user", TenantContext.require().userId())
                .param("now", Timestamp.from(clock.instant())).param("id", cur.get("id")).update();
        return detail(siteId, apptNo);
    }

    @Transactional
    public Map<String, Object> checkOut(String siteId, String apptNo) {
        Map<String, Object> cur = lock(siteId, apptNo);
        if (!List.of("CHECKED_IN", "AT_DOOR").contains((String) cur.get("status"))) {
            throw ApiException.unprocessable("YRD_NOT_IN_YARD", "Appointment " + apptNo + " is " + cur.get("status"));
        }
        jdbc.sql("""
                        update dock_appointment set status = 'CHECKED_OUT', checked_out_at = :now, updated_by = :user,
                            updated_at = :now where id = :id""")
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant()))
                .param("id", cur.get("id")).update();
        return detail(siteId, apptNo);
    }

    /** CANCELLED (before arrival) or NO_SHOW (the carrier never came). */
    @Transactional
    public Map<String, Object> close(String siteId, String apptNo, String status) {
        Map<String, Object> cur = lock(siteId, apptNo);
        requireStatus(cur, "SCHEDULED");
        jdbc.sql("update dock_appointment set status = :s, updated_by = :user, updated_at = :now where id = :id")
                .param("s", status).param("user", TenantContext.require().userId())
                .param("now", Timestamp.from(clock.instant())).param("id", cur.get("id")).update();
        return detail(siteId, apptNo);
    }

    // ------------------------------------------------------------------------------------------ queries

    private static final String SELECT = """
            select a.appt_no, a.direction, a.door, a.carrier_scac, a.trailer_no, a.doc_no, a.scheduled_start,
                   a.scheduled_end, a.status, a.note, a.checked_in_at, a.at_door_at, a.checked_out_at, a.created_by,
                   a.updated_by, a.updated_at,
                   exists (select 1 from receipt_expectation e where e.site_id = a.site_id and e.erp_doc_no = a.doc_no) as asn_known,
                   (select e.status from receipt_expectation e where e.site_id = a.site_id and e.erp_doc_no = a.doc_no) as receipt_status
            from dock_appointment a\s""";

    /** Appointments of a site: by day (site-local is up to the client; UTC day here), status or delivery. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String siteId, Instant from, Instant to, String status, String docNo) {
        return jdbc.sql(SELECT + """
                         where a.site_id = :site
                          and (cast(:from as timestamptz) is null or a.scheduled_end >= :from)
                          and (cast(:to as timestamptz) is null or a.scheduled_start < :to)
                          and (cast(:status as text) is null or a.status = :status)
                          and (cast(:doc as text) is null or a.doc_no = :doc)
                        order by a.scheduled_start, a.appt_no limit 500""")
                .param("site", siteId).param("from", from == null ? null : Timestamp.from(from))
                .param("to", to == null ? null : Timestamp.from(to)).param("status", status).param("doc", trim(docNo))
                .query().listOfRows().stream().map(this::withDwell).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String siteId, String apptNo) {
        return jdbc.sql(SELECT + " where a.site_id = :site and a.appt_no = :no").param("site", siteId).param("no", apptNo)
                .query().listOfRows().stream().findFirst().map(this::withDwell)
                .orElseThrow(() -> ApiException.notFound("YRD_UNKNOWN", "No appointment " + apptNo));
    }

    /**
     * The yard now: trailers in the yard and at doors with their dwell, late arrivals (scheduled, start passed by more
     * than 15 minutes), and the door board (who is at each door, who is next).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(String siteId) {
        Instant now = clock.instant();
        List<Map<String, Object>> inYard = jdbc.sql(SELECT + """
                         where a.site_id = :site and a.status in ('CHECKED_IN', 'AT_DOOR') order by a.checked_in_at""")
                .param("site", siteId).query().listOfRows().stream().map(this::withDwell).toList();
        List<Map<String, Object>> late = jdbc.sql(SELECT + """
                         where a.site_id = :site and a.status = 'SCHEDULED' and a.scheduled_start < :late
                        order by a.scheduled_start""")
                .param("site", siteId).param("late", Timestamp.from(now.minus(Duration.ofMinutes(15))))
                .query().listOfRows().stream().map(this::withDwell).toList();
        Map<String, Map<String, Object>> doors = new java.util.TreeMap<>();
        for (Map<String, Object> a : jdbc.sql(SELECT + """
                         where a.site_id = :site and a.door is not null and a.status in ('SCHEDULED', 'CHECKED_IN', 'AT_DOOR')
                        order by a.scheduled_start""")
                .param("site", siteId).query().listOfRows()) {
            Map<String, Object> d = doors.computeIfAbsent((String) a.get("door"), k -> new HashMap<>(Map.of("door", k)));
            if ("AT_DOOR".equals(a.get("status"))) {
                d.put("current", withDwell(a));
            } else if (!d.containsKey("next")) {
                d.put("next", withDwell(a));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inYard", inYard);
        result.put("late", late);
        result.put("doors", List.copyOf(doors.values()));
        result.put("longestDwellMinutes", inYard.stream().mapToLong(a -> (Long) a.get("dwell_minutes")).max().orElse(0));
        return result;
    }

    private Map<String, Object> withDwell(Map<String, Object> row) {
        Map<String, Object> out = new HashMap<>(row);
        Instant now = clock.instant();
        Instant in = instant(row.get("checked_in_at"));
        Instant door = instant(row.get("at_door_at"));
        Instant outAt = instant(row.get("checked_out_at"));
        Instant until = outAt != null ? outAt : now;
        out.put("dwell_minutes", in == null ? null : Duration.between(in, until).toMinutes());
        out.put("door_minutes", door == null ? null : Duration.between(door, until).toMinutes());
        Instant start = instant(row.get("scheduled_start"));
        out.put("late_minutes", "SCHEDULED".equals(row.get("status")) && start != null && start.isBefore(now)
                ? Duration.between(start, now).toMinutes() : null);
        return out;
    }

    private static Instant instant(Object v) {
        return v instanceof Timestamp t ? t.toInstant() : v instanceof java.time.OffsetDateTime o ? o.toInstant()
                : v instanceof Instant i ? i : null;
    }

    private Map<String, Object> lock(String siteId, String apptNo) {
        return jdbc.sql("""
                        select id, status, door, scheduled_start, scheduled_end from dock_appointment
                        where site_id = :site and appt_no = :no for update""")
                .param("site", siteId).param("no", apptNo).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("YRD_UNKNOWN", "No appointment " + apptNo));
    }

    private static void requireStatus(Map<String, Object> cur, String status) {
        if (!status.equals(cur.get("status"))) {
            throw ApiException.unprocessable("YRD_WRONG_STATUS", "Appointment is " + cur.get("status") + ", not " + status);
        }
    }

    private void requireDoorFree(String siteId, String door, Instant start, Instant end, UUID self) {
        if (door == null) {
            return;
        }
        jdbc.sql("""
                        select appt_no from dock_appointment
                        where site_id = :site and door = :door and status in ('SCHEDULED', 'CHECKED_IN', 'AT_DOOR')
                          and scheduled_start < :end and scheduled_end > :start
                          and (cast(:self as uuid) is null or id <> :self)
                        limit 1""")
                .param("site", siteId).param("door", door).param("start", Timestamp.from(start))
                .param("end", Timestamp.from(end)).param("self", self).query(String.class).optional()
                .ifPresent(other -> {
                    throw ApiException.conflict("YRD_DOOR_BOOKED", "Door " + door + " is booked then by " + other);
                });
    }

    private static String upper(String v) {
        return v == null || v.isBlank() ? null : v.trim().toUpperCase();
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
