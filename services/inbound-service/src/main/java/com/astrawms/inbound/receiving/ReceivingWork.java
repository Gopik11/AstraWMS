package com.astrawms.inbound.receiving;

import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.contracts.ReceivingContracts;
import com.astrawms.common.contracts.ReceivingContracts.ReceiveEnded;
import com.astrawms.common.contracts.ReceivingContracts.ReceiveRequested;
import com.astrawms.common.messaging.OutboxWriter;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Publishes RF receiving work to the task service (ADR-0019): a vendor delivery or RMA that can be received becomes a
 * RECEIVE task; when it is closed or cancelled, the task ends. Written in the same transaction as the document change.
 */
@Component
public class ReceivingWork {

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final int priority;

    public ReceivingWork(JdbcClient jdbc, OutboxWriter outbox,
                         @Value("${astra.inbound.receive-task-priority:40}") int priority) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.priority = priority;
    }

    /** A vendor delivery (receipt expectation) was created or changed while it can still be received. */
    public void asnReceivable(String siteId, UUID expectationId) {
        record H(String doc, String vendor, Timestamp arrival) {
        }
        H h = jdbc.sql("select erp_doc_no, vendor_id, expected_arrival_utc from receipt_expectation where id = :id")
                .param("id", expectationId).query((rs, n) -> new H(rs.getString(1), rs.getString(2), rs.getTimestamp(3)))
                .single();
        record L(String owner, ReceiveRequested.Line line) {
        }
        List<L> lines = jdbc.sql("""
                        select owner_id, erp_line_ref, item_no, qty_expected, uom, lot_no from receipt_expectation_line
                        where expectation_id = :id order by erp_line_ref""")
                .param("id", expectationId)
                .query((rs, n) -> new L(rs.getString(1), new ReceiveRequested.Line(rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4).stripTrailingZeros(), rs.getString(5), rs.getString(6))))
                .list();
        publish(siteId, new ReceiveRequested(ReceivingContracts.KIND_ASN, h.doc(),
                lines.isEmpty() ? null : lines.getFirst().owner(), h.vendor(),
                h.arrival() == null ? null : h.arrival().toInstant(), lines.stream().map(L::line).toList(), priority));
    }

    /** An RMA or blind return was created or changed while it can still be received. */
    public void rmaReceivable(String siteId, UUID returnId) {
        record H(String rma, String customer, Timestamp arrival) {
        }
        H h = jdbc.sql("select rma_no, customer_name, expected_arrival_utc from return_order where id = :id")
                .param("id", returnId).query((rs, n) -> new H(rs.getString(1), rs.getString(2), rs.getTimestamp(3)))
                .single();
        record L(String owner, ReceiveRequested.Line line) {
        }
        List<L> lines = jdbc.sql("""
                        select owner_id, erp_line_ref, item_no, qty_expected, uom from return_line
                        where return_id = :id order by erp_line_ref""")
                .param("id", returnId)
                .query((rs, n) -> new L(rs.getString(1), new ReceiveRequested.Line(rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4).stripTrailingZeros(), rs.getString(5), null)))
                .list();
        publish(siteId, new ReceiveRequested(ReceivingContracts.KIND_RMA, h.rma(),
                lines.isEmpty() ? null : lines.getFirst().owner(), h.customer(),
                h.arrival() == null ? null : h.arrival().toInstant(), lines.stream().map(L::line).toList(), priority));
    }

    public void ended(String siteId, String kind, String docNo, String reason) {
        outbox.append(new OutboxWriter.Message(OutboundContracts.TOPIC_TASK_REQUESTS, ReceiveEnded.TYPE,
                ReceiveEnded.VERSION, null, siteId, null, siteId + ":" + docNo, new ReceiveEnded(kind, docNo, reason)));
    }

    private void publish(String siteId, ReceiveRequested r) {
        outbox.append(new OutboxWriter.Message(OutboundContracts.TOPIC_TASK_REQUESTS, ReceiveRequested.TYPE,
                ReceiveRequested.VERSION, null, siteId, r.ownerId(), siteId + ":" + r.docNo(), r));
    }
}
