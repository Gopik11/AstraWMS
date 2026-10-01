package com.astrawms.inventory.service;

import com.astrawms.common.contracts.InventoryContracts.CountRequested;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.common.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Opens cycle counts and asks the task service for count tasks. At most one open count per location. */
@Component
public class CountRequests {

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final int priority;

    public CountRequests(JdbcClient jdbc, OutboxWriter outbox, Clock clock,
                         @Value("${astra.inventory.count.priority:55}") int priority) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
        this.priority = priority;
    }

    /** Opens a count for the location unless one is open already; returns the open count. */
    public UUID open(String siteId, String locationId, String trigger, String note) {
        Optional<UUID> existing = jdbc.sql("""
                        select id from stock_count where site_id = :site and location_id = :loc
                          and status in ('OPEN', 'RECOUNT', 'PENDING_APPROVAL')""")
                .param("site", siteId).param("loc", locationId).query(UUID.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        int inserted = jdbc.sql("""
                        insert into stock_count (id, tenant_id, site_id, location_id, trigger, status, requested_by, note,
                                                 created_at, updated_at)
                        values (:id, :t, :site, :loc, :trigger, 'OPEN', :user, :note, :now, :now)
                        on conflict do nothing""")
                .param("id", id).param("t", TenantContext.tenantId()).param("site", siteId).param("loc", locationId)
                .param("trigger", trigger).param("user", TenantContext.require().userId()).param("note", note)
                .param("now", now).update();
        if (inserted == 0) {   // a concurrent request opened one
            return open(siteId, locationId, trigger, note);
        }
        request(siteId, id, locationId, 1, List.of(), trigger);
        return id;
    }

    void request(String siteId, UUID countId, String locationId, int sequence, List<String> excludedUsers, String trigger) {
        outbox.append(new OutboxWriter.Message(OutboundContracts.TOPIC_TASK_REQUESTS, CountRequested.TYPE,
                CountRequested.VERSION, null, siteId, null, siteId + ":" + locationId,
                new CountRequested(countId, locationId, sequence, excludedUsers, trigger, priority + sequence * 5)));
    }
}
