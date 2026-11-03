package com.astrawms.outbound.service;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.outbound.inventory.InventoryClient;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Compensation for strict transfers (ADR-0029, review H-04). Inventory reserves stock in its own transaction; when the
 * transfer is then refused (not fully allocable) or fails for any other reason, the outbound transaction rolls back but
 * the reservation would stay, held by an order that does not exist. This releases it:
 * <ol>
 *   <li>{@link #begin} binds a collector to the outbound transaction; every allocation inventory returns is recorded
 *       ({@link #record}).</li>
 *   <li>On rollback the recorded allocations are released at once, exactly those ids.</li>
 *   <li>If inventory cannot be reached, or an allocation call ended without an answer (inventory may have committed a
 *       reservation whose id never arrived), a {@code reservation_compensation} row is written and {@link #retry}
 *       finishes it: the ids again, and for the unanswered case every open reservation of the order reference while no
 *       order holds that number. Transfer numbering takes the same advisory lock ({@link #lockNumber}), so a new
 *       transfer cannot take the number in between.</li>
 * </ol>
 */
@Component
public class StrictReservations {

    private static final Logger log = LoggerFactory.getLogger(StrictReservations.class);
    private static final Object KEY = StrictReservations.class;
    static final Duration RETRY_FOR = Duration.ofDays(2);

    private final InventoryClient inventory;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public StrictReservations(InventoryClient inventory, JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.inventory = inventory;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    private static final class Collector {
        final String siteId;
        final String orderRef;
        final List<UUID> ids = new ArrayList<>();
        boolean uncertain;

        Collector(String siteId, String orderRef) {
            this.siteId = siteId;
            this.orderRef = orderRef;
        }
    }

    /** Starts collecting the reservations of a strict transfer in the current transaction. */
    public void begin(String siteId, String orderRef) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("StrictReservations.begin needs a transaction");
        }
        Collector c = new Collector(siteId, orderRef);
        TransactionSynchronizationManager.bindResource(KEY, c);
        String tenant = TenantContext.tenantId();
        String user = TenantContext.require().userId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(KEY);
                if (status != STATUS_COMMITTED) {
                    TenantContext.runAs(new TenantContext.Scope(tenant, user, "SYSTEM"), () -> compensate(c));
                }
            }
        });
    }

    /** An allocation call of this transaction ended without an answer (timeout, connection lost). */
    public void uncertain() {
        if (TransactionSynchronizationManager.getResource(KEY) instanceof Collector c) {
            c.uncertain = true;
        }
    }

    /** Serialises transfer numbering with the by-reference sweep of a number (transaction-scoped). */
    public void lockNumber(String siteId, String orderRef) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:k))").param("k", siteId + "/" + orderRef).query().singleRow();
    }

    /** Records what inventory reserved, when a strict transfer is being created on this transaction. */
    public void record(InventoryClient.AllocateResult result) {
        Object c = TransactionSynchronizationManager.getResource(KEY);
        if (c instanceof Collector collector && result != null && result.allocations() != null) {
            result.allocations().forEach(a -> collector.ids.add(a.id()));
        }
    }

    private void compensate(Collector c) {
        String error = null;
        try {
            if (!c.ids.isEmpty()) {
                inventory.release(c.siteId, "OUT-STRICT-RB-" + UUID.randomUUID(), c.orderRef, List.copyOf(c.ids));
            }
            log.info("Strict transfer {} at {} rolled back; released {} reservation(s)", c.orderRef, c.siteId, c.ids.size());
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("Strict transfer {} at {}: releasing {} reservation(s) failed, kept for retry ({})", c.orderRef,
                    c.siteId, c.ids.size(), error);
        }
        if (error == null && !c.uncertain) {
            return;
        }
        String err = error;
        tx.executeWithoutResult(s -> jdbc.sql("""
                        insert into reservation_compensation (id, tenant_id, site_id, order_ref, allocation_ids,
                                                              sweep_by_ref, created_at, attempts, last_error)
                        values (:id, :t, :s, :ref, cast(:ids as uuid[]), :sweep, :now, 1, :err)""")
                .param("id", UUID.randomUUID()).param("t", TenantContext.tenantId()).param("s", c.siteId)
                .param("ref", c.orderRef).param("ids", "{" + String.join(",", c.ids.stream().map(UUID::toString).toList()) + "}")
                .param("sweep", c.uncertain).param("now", Timestamp.from(clock.instant()))
                .param("err", truncate(err == null ? "allocation call without an answer" : err)).update());
    }

    private record Pending(UUID id, String tenant, String site, String orderRef, List<UUID> ids, boolean sweep) {
    }

    /** Releases what earlier attempts could not; returns how many compensations completed. */
    @Scheduled(fixedDelayString = "${astra.outbound.compensation-interval-ms:30000}",
               initialDelayString = "${astra.outbound.compensation-initial-delay-ms:30000}")
    public int retry() {
        List<Pending> pending = jdbc.sql("""
                        select id, tenant_id, site_id, order_ref, allocation_ids, sweep_by_ref from reservation_compensation
                        where done_at is null and created_at > :since order by created_at limit 50""")
                .param("since", Timestamp.from(clock.instant().minus(RETRY_FOR)))
                .query((rs, n) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), uuids(rs.getArray(5)), rs.getBoolean(6)))
                .list();
        int done = 0;
        for (Pending p : pending) {
            try {
                TenantContext.runAs(new TenantContext.Scope(p.tenant(), "system:outbound-compensation", "SYSTEM"), () -> {
                    if (!p.ids().isEmpty()) {
                        inventory.release(p.site(), "OUT-STRICT-RT-" + p.id() + "-ids", p.orderRef(), p.ids());
                    }
                    if (p.sweep()) {
                        // what was reserved but never reported back: released by order reference, under the numbering
                        // lock and only while no order holds the number
                        tx.executeWithoutResult(s -> {
                            lockNumber(p.site(), p.orderRef());
                            boolean free = !jdbc.sql(
                                            "select exists (select 1 from outbound_order where site_id = :s and erp_doc_no = :d)")
                                    .param("s", p.site()).param("d", p.orderRef()).query(Boolean.class).single();
                            if (free) {
                                inventory.release(p.site(), "OUT-STRICT-RT-" + p.id() + "-ref", p.orderRef(), List.of());
                            }
                        });
                    }
                });
                jdbc.sql("update reservation_compensation set done_at = :now, attempts = attempts + 1 where id = :id")
                        .param("now", Timestamp.from(clock.instant())).param("id", p.id()).update();
                done++;
            } catch (RuntimeException e) {
                jdbc.sql("update reservation_compensation set attempts = attempts + 1, last_error = :e where id = :id")
                        .param("e", truncate(e.getMessage())).param("id", p.id()).update();
            }
        }
        return done;
    }

    private static List<UUID> uuids(Array array) throws java.sql.SQLException {
        Object[] values = (Object[]) array.getArray();
        List<UUID> out = new ArrayList<>();
        for (Object v : values) {
            out.add(v instanceof UUID u ? u : UUID.fromString(v.toString()));
        }
        return out;
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }
}
