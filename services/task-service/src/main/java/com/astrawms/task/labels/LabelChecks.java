package com.astrawms.task.labels;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The site's label-check policy for when master data cannot be reached (ADR-0029, review M-03). ADR-0025 made the
 * label check fail open: RF keeps working through a master-data outage. That stays the default, but a bypass is no
 * longer silent:
 * <ul>
 *   <li>FAIL_OPEN: the scan is accepted; the bypass is recorded (who, task, label, when) for a supervisor to review,
 *       counted in {@code astra_label_check_bypassed_total} and written to the task's history.</li>
 *   <li>FAIL_CLOSED: the scan is refused with TSK_LABEL_CHECK_UNAVAILABLE (503) until master data answers.</li>
 * </ul>
 */
@Service
public class LabelChecks {

    private static final Logger log = LoggerFactory.getLogger(LabelChecks.class);
    public static final List<String> MODES = List.of("FAIL_OPEN", "FAIL_CLOSED");

    private final JdbcClient jdbc;
    private final Clock clock;
    private final MeterRegistry meters;

    public LabelChecks(JdbcClient jdbc, Clock clock, org.springframework.beans.factory.ObjectProvider<MeterRegistry> meters) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.meters = meters.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new);
    }

    @Transactional(readOnly = true)
    public String mode(String siteId) {
        return jdbc.sql("select mode from label_check_policy where site_id = :s").param("s", siteId)
                .query(String.class).optional().orElse("FAIL_OPEN");
    }

    @Transactional
    public Map<String, Object> setMode(String siteId, String mode) {
        String m = mode == null ? "" : mode.trim().toUpperCase();
        if (!MODES.contains(m)) {
            throw ApiException.badRequest("TSK_LABEL_POLICY_INVALID", "mode is one of " + MODES);
        }
        jdbc.sql("""
                        insert into label_check_policy (tenant_id, site_id, mode, updated_by, updated_at)
                        values (:t, :s, :m, :u, :now)
                        on conflict (tenant_id, site_id) do update set mode = excluded.mode,
                            updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("s", siteId).param("m", m)
                .param("u", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        return Map.of("siteId", siteId, "mode", m);
    }

    /**
     * Master data could not say whether the label is active. Refuses the scan (FAIL_CLOSED) or records the bypass in
     * its own transaction, so the record stays even when the scan itself fails later (FAIL_OPEN). The caller writes the
     * task's history in its own transaction (it holds the task row lock; task_event references the task).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void unreachable(String siteId, UUID taskId, String type, String barcode) {
        String mode = mode(siteId);
        Counter.builder("astra.label.check.bypassed").tag("site", siteId).tag("type", type).tag("mode", mode)
                .description("Label checks master data could not answer").register(meters).increment();
        if ("FAIL_CLOSED".equals(mode)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TSK_LABEL_CHECK_UNAVAILABLE",
                    "The " + type.toLowerCase() + " label " + barcode + " cannot be checked right now (master data is"
                            + " unavailable) and " + siteId + " does not accept unchecked labels: try again shortly");
        }
        Instant now = clock.instant();
        String user = TenantContext.require().userId();
        jdbc.sql("""
                        insert into label_check_bypass (id, tenant_id, site_id, task_id, label_type, barcode, user_id, at)
                        values (:id, :t, :s, :task, :type, :code, :u, :now)""")
                .param("id", UUID.randomUUID()).param("t", TenantContext.tenantId()).param("s", siteId)
                .param("task", taskId).param("type", type).param("code", barcode).param("u", user)
                .param("now", Timestamp.from(now)).update();
        log.warn("Label check bypassed at {}: {} {} on task {} by {} (master data unavailable)", siteId, type, barcode,
                taskId, user);
    }

    /** Bypasses still to review (or all of them), newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> bypasses(String siteId, boolean openOnly) {
        return jdbc.sql("""
                        select id, task_id, label_type, barcode, user_id, at, reviewed_by, reviewed_at, review_note
                        from label_check_bypass
                        where site_id = :s and (not :open or reviewed_at is null)
                        order by at desc limit 500""")
                .param("s", siteId).param("open", openOnly).query().listOfRows();
    }

    /** A supervisor checked the label (scanned it, or saw the stock where RF put it) and closes the bypass. */
    @Transactional
    public Map<String, Object> review(String siteId, UUID id, String note) {
        int n = jdbc.sql("""
                        update label_check_bypass set reviewed_by = :u, reviewed_at = :now, review_note = :note
                        where id = :id and site_id = :s and reviewed_at is null""")
                .param("u", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant()))
                .param("note", note == null || note.isBlank() ? null : note.trim()).param("id", id).param("s", siteId)
                .update();
        if (n == 0) {
            throw ApiException.notFound("TSK_LABEL_BYPASS_UNKNOWN", "No open label-check bypass " + id + " at " + siteId);
        }
        return Map.of("id", id, "reviewed", true);
    }
}
