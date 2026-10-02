package com.astrawms.task.service;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.task.api.TaskDtos.TaskView;
import com.astrawms.task.projection.Projections;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Automation adapter (ADR-0021): one task API for devices — pick-to-light controllers, robots, AS/RS — on the same
 * tasks people work on RF. A device controller (role AUTOMATION) claims the next task of an automated zone for a
 * device, confirms it with the quantity done, or raises an exception that hands the task to people.
 * <ul>
 *   <li>Tasks that start in an enabled automation zone are not offered on RF unless a device gave them back.</li>
 *   <li>A device's confirmation goes through the same validation and inventory calls as an RF confirmation; the
 *       device is the actor ({@code device:<id>}) and its position stands in for the RF scans.</li>
 * </ul>
 */
@Service
public class Automation {

    public static final List<String> DEVICE_TASK_TYPES = List.of("PICK", "PUTAWAY", "REPLEN", "MOVE");
    public static final List<String> EXCEPTION_REASONS = List.of("ITEM_NOT_FOUND", "LOCATION_BLOCKED", "DEVICE_FAULT",
            "WRONG_ITEM", "OTHER");

    private final JdbcClient jdbc;
    private final TaskService tasks;
    private final Projections projections;
    private final Clock clock;

    public Automation(JdbcClient jdbc, TaskService tasks, Projections projections, Clock clock) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.projections = projections;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------ zones

    public List<Map<String, Object>> zones(String siteId) {
        return jdbc.sql("""
                        select z.zone_id, z.device_type, z.enabled, z.updated_by, z.updated_at,
                               (select count(*) from task t join ref_location l on l.site_id = t.site_id
                                  and l.location_id = t.from_location
                                where t.site_id = z.site_id and l.zone_id = z.zone_id and t.status = 'RELEASED'
                                  and not t.automation_manual) as queued,
                               (select count(*) from task t where t.site_id = z.site_id and t.status = 'ASSIGNED'
                                  and t.device_id is not null) as on_devices
                        from automation_zone z where z.site_id = :site order by z.zone_id""")
                .param("site", siteId).query().listOfRows();
    }

    @Transactional
    public List<Map<String, Object>> putZone(String siteId, String zoneId, String deviceType, Boolean enabled) {
        String type = deviceType == null || deviceType.isBlank() ? null : deviceType.trim().toUpperCase();
        if (type == null) {
            throw ApiException.badRequest("TSK_AUTOMATION_INVALID", "deviceType is required (e.g. PICK_TO_LIGHT, ROBOT)");
        }
        jdbc.sql("""
                        insert into automation_zone (tenant_id, site_id, zone_id, device_type, enabled, updated_by, updated_at)
                        values (:t, :site, :zone, :type, :enabled, :user, :now)
                        on conflict (tenant_id, site_id, zone_id) do update set device_type = excluded.device_type,
                            enabled = excluded.enabled, updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("site", siteId).param("zone", zoneId.trim().toUpperCase())
                .param("type", type).param("enabled", enabled == null || enabled)
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        return zones(siteId);
    }

    // ------------------------------------------------------------------------------------------ device API

    /** A compact task for a device: where to take what, how much, and where it goes. */
    public record DeviceTask(UUID taskId, String taskType, String deviceId, int priority, String fromLocation,
                             String fromZone, String lpnId, String ownerId, String itemNo, String lotNo, BigDecimal qty,
                             String uom, String toLocation, String toLpn, String orderRef) {

        static DeviceTask of(TaskView t, String deviceId, String fromZone) {
            return new DeviceTask(t.id(), t.taskType(), deviceId, t.priority(), t.fromLocation(), fromZone,
                    t.lpnId() == null || t.lpnId().isEmpty() ? null : t.lpnId(), t.ownerId(), t.itemNo(), t.lotNo(),
                    t.qty(), t.uom(), t.targetLocation(), t.toLpn(), t.orderRef());
        }
    }

    /**
     * Claims the next task of an enabled automation zone (one zone, or any) for a device: highest priority, then the
     * travel path. A device holding a task gets that task again.
     */
    @Transactional
    public Optional<DeviceTask> claim(String siteId, String deviceId, String zoneId, List<String> types) {
        String device = requireDevice(deviceId);
        List<String> wanted = types == null || types.isEmpty() ? List.of("PICK")
                : types.stream().map(s -> s.trim().toUpperCase()).filter(DEVICE_TASK_TYPES::contains).toList();
        if (wanted.isEmpty()) {
            throw ApiException.badRequest("TSK_AUTOMATION_INVALID", "taskTypes must be among " + DEVICE_TASK_TYPES);
        }
        Optional<UUID> held = jdbc.sql("""
                        select id from task where site_id = :site and status = 'ASSIGNED' and device_id = :device
                        order by assigned_at limit 1""")
                .param("site", siteId).param("device", device).query(UUID.class).optional();
        if (held.isPresent()) {
            return Optional.of(deviceTask(siteId, held.get()));
        }
        Optional<UUID> next = jdbc.sql("""
                        select t.id from task t
                        join ref_location f on f.site_id = t.site_id and f.location_id = t.from_location
                        join automation_zone z on z.site_id = f.site_id and z.zone_id = f.zone_id and z.enabled
                        where t.site_id = :site and t.status = 'RELEASED' and t.task_type in (:types)
                          and not t.automation_manual
                          and (cast(:zone as text) is null or z.zone_id = :zone)
                        order by t.priority desc, f.pick_seq nulls last, t.from_location, t.created_at
                        limit 1 for update of t skip locked""")
                .param("site", siteId).param("types", wanted)
                .param("zone", zoneId == null || zoneId.isBlank() ? null : zoneId.trim().toUpperCase())
                .query(UUID.class).optional();
        next.ifPresent(id -> {
            jdbc.sql("""
                            update task set status = 'ASSIGNED', assigned_to = :actor, device_id = :device, assigned_at = :now,
                                updated_at = :now where id = :id""")
                    .param("actor", actor(device)).param("device", device).param("now", Timestamp.from(clock.instant()))
                    .param("id", id).update();
            event(id, "ASSIGNED", "device " + device);
        });
        return next.map(id -> deviceTask(siteId, id));
    }

    /**
     * The device did the task: {@code qty} done (a pick of less is a short pick and needs {@code shortReason};
     * other task types are done whole). Idempotent like the RF confirmations.
     */
    public TaskView confirm(String siteId, UUID taskId, String deviceId, BigDecimal qty, String shortReason,
                            String shortAction) {
        String device = requireDevice(deviceId);
        TaskView t = tasks.get(siteId, taskId);
        if ("COMPLETED".equals(t.status())) {
            return t;
        }
        if (!device.equals(deviceOf(siteId, taskId))) {
            throw ApiException.conflict("TSK_NOT_ASSIGNED", "Task " + taskId + " is not held by device " + device);
        }
        TenantContext.Scope me = TenantContext.require();
        return TenantContext.callAs(new TenantContext.Scope(me.tenantId(), actor(device), "AUTOMATION", me.access()), () ->
                switch (t.taskType()) {
                    case "PICK" -> tasks.confirmPick(siteId, taskId, checkDigit(siteId, t.fromLocation()), t.itemNo(),
                            qty == null ? t.qty() : qty, null, shortReason, shortAction);
                    case "PUTAWAY" -> tasks.confirm(siteId, taskId, t.lpnId(), t.targetLocation(),
                            checkDigit(siteId, t.targetLocation()), null);
                    case "REPLEN" -> tasks.confirmReplenishment(siteId, taskId, checkDigit(siteId, t.targetLocation()));
                    case "MOVE" -> tasks.confirmMove(siteId, taskId, checkDigit(siteId, t.targetLocation()));
                    default -> throw ApiException.unprocessable("TSK_WRONG_TYPE", t.taskType() + " tasks are not done by devices");
                });
    }

    /** The device cannot do the task: it goes back to the RF queue for people, with the reason on it. */
    @Transactional
    public TaskView exception(String siteId, UUID taskId, String deviceId, String reason, String detail) {
        String device = requireDevice(deviceId);
        String r = reason == null ? null : reason.trim().toUpperCase();
        if (r == null || !EXCEPTION_REASONS.contains(r)) {
            throw ApiException.unprocessable("TSK_REASON_UNKNOWN", "reason must be one of " + EXCEPTION_REASONS);
        }
        int n = jdbc.sql("""
                        update task set status = 'RELEASED', assigned_to = null, assigned_at = null, device_id = null,
                            automation_manual = true, exception_reason = :reason, updated_at = :now
                        where site_id = :site and id = :id and status = 'ASSIGNED' and device_id = :device""")
                .param("reason", "AUTOMATION_" + r).param("now", Timestamp.from(clock.instant())).param("site", siteId)
                .param("id", taskId).param("device", device).update();
        if (n == 0) {
            throw ApiException.conflict("TSK_NOT_ASSIGNED", "Task " + taskId + " is not held by device " + device);
        }
        event(taskId, "AUTOMATION_EXCEPTION", device + ": " + r + (detail == null || detail.isBlank() ? "" : " — " + detail.trim()));
        return tasks.get(siteId, taskId);
    }

    private DeviceTask deviceTask(String siteId, UUID id) {
        TaskView t = tasks.get(siteId, id);
        String zone = projections.location(siteId, t.fromLocation()).map(Projections.Location::zoneId).orElse(null);
        return DeviceTask.of(t, deviceOf(siteId, id), zone);
    }

    private String deviceOf(String siteId, UUID taskId) {
        return jdbc.sql("select device_id from task where site_id = :site and id = :id").param("site", siteId)
                .param("id", taskId).query(String.class).optional().orElse(null);
    }

    private String checkDigit(String siteId, String locationId) {
        return projections.location(siteId, locationId).map(Projections.Location::checkDigit)
                .orElseThrow(() -> ApiException.unprocessable("TSK_LOCATION_UNKNOWN", "Location " + locationId + " is not known"));
    }

    private static String requireDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 60) {
            throw ApiException.badRequest("TSK_DEVICE_REQUIRED", "deviceId is required");
        }
        return deviceId.trim();
    }

    private static String actor(String device) {
        return "device:" + device;
    }

    private void event(UUID taskId, String event, String detail) {
        jdbc.sql("""
                        insert into task_event (tenant_id, task_id, event, detail, user_id, at)
                        values (:t, :task, :event, :detail, :user, :now)""")
                .param("t", TenantContext.tenantId()).param("task", taskId).param("event", event).param("detail", detail)
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
    }
}
