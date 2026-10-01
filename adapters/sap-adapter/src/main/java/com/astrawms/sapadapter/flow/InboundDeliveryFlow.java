package com.astrawms.sapadapter.flow;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.ApplicationAck;
import com.astrawms.common.contracts.IntegrationContracts.ReceiptExpectation;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.sapadapter.config.SapProperties;
import com.astrawms.sapadapter.mapping.DelvryMapper;
import com.astrawms.sapadapter.mapping.MappingException;
import com.astrawms.sapadapter.sap.Delvry07;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IF-IB-001, SAP side: DELVRY07 (SHP_IBDLV_SAVE_REPLICA / SHP_IBDLV_CHANGE) → ReceiptExpectation v3, plus the
 * IDoc status SAP would show: 03 dispatched, then 53 / 51 once AstraWMS acknowledges (ALEAUD01 equivalent).
 */
@Service
public class InboundDeliveryFlow {

    public record IdocStatus(String idocNumber, String messageType, String vbeln, String status, String statusText,
                             UUID messageId) {
    }

    private final SiteMapRepository sites;
    private final OutboxWriter outbox;
    private final JdbcClient jdbc;
    private final SapProperties sap;
    private final Clock clock;

    public InboundDeliveryFlow(SiteMapRepository sites, OutboxWriter outbox, JdbcClient jdbc, SapProperties sap,
                               Clock clock) {
        this.sites = sites;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.sap = sap;
        this.clock = clock;
    }

    /** Maps and forwards the IDoc. Mapping errors are recorded as status 51 and re-thrown (not retried). */
    @Transactional(noRollbackFor = MappingException.class)
    public IdocStatus receive(Delvry07 idoc) {
        String vbeln = idoc.e1edl20() == null ? null : idoc.e1edl20().vbeln();
        try {
            String werks = DelvryMapper.plantOf(idoc);
            DelvryMapper.Plant plant = sites.byPlant(werks).orElseThrow(() ->
                    new MappingException("PLANT_NOT_MAPPED", "Plant " + werks + " is not mapped to an AstraWMS site"));
            if (idoc.outbound()) {
                OutboundContracts.OutboundOrder order = DelvryMapper.mapOutbound(idoc, plant, clock.instant());
                EventEnvelope envelope = outbox.append(new OutboxWriter.Message(
                        OutboundContracts.TOPIC_OUTBOUND_ORDERS, OutboundContracts.OutboundOrder.TYPE,
                        OutboundContracts.OutboundOrder.VERSION, sap.logicalSystem(), "ASTRAWMS", plant.siteId(),
                        plant.defaultOwner(), plant.siteId() + ":" + order.erpDocNo(), order));
                return saveStatus(idoc, vbeln, "03", "Passed to AstraWMS", envelope.messageId());
            }
            DelvryMapper.Mapped mapped = DelvryMapper.map(idoc, plant, clock.instant());
            EventEnvelope envelope = outbox.append(new OutboxWriter.Message(
                    IntegrationContracts.TOPIC_RECEIPT_EXPECTATIONS, ReceiptExpectation.TYPE, ReceiptExpectation.VERSION,
                    sap.logicalSystem(), "ASTRAWMS", mapped.siteId(), mapped.ownerId(),
                    mapped.siteId() + ":" + mapped.expectation().erpDocNo(), mapped.expectation()));
            return saveStatus(idoc, vbeln, "03", "Passed to AstraWMS", envelope.messageId());
        } catch (MappingException e) {
            saveStatus(idoc, vbeln, "51", e.code() + ": " + e.getMessage(), null);
            throw e;
        }
    }

    /** ALEAUD equivalent: AstraWMS accepted (53) or rejected (51) the document. */
    @Transactional
    public void onAck(ApplicationAck ack) {
        boolean accepted = ApplicationAck.ACCEPTED.equals(ack.result());
        jdbc.sql("""
                        update idoc_status set status = :status, status_text = :text, updated_at = :now
                        where idoc_number = :idoc""")
                .param("status", accepted ? "53" : "51")
                .param("text", accepted ? "Application document posted in AstraWMS"
                        : ack.reasonCode() + ": " + ack.reasonText())
                .param("idoc", ack.sourceDocumentId()).param("now", Timestamp.from(clock.instant()))
                .update();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<IdocStatus> status(String idocNumber) {
        return jdbc.sql("""
                        select idoc_number, message_type, vbeln, status, status_text, message_id
                        from idoc_status where idoc_number = :idoc""")
                .param("idoc", idocNumber)
                .query((rs, n) -> new IdocStatus(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, UUID.class)))
                .optional();
    }

    private IdocStatus saveStatus(Delvry07 idoc, String vbeln, String status, String text, UUID messageId) {
        jdbc.sql("""
                        insert into idoc_status (tenant_id, idoc_number, message_type, direction, vbeln, status,
                                                 status_text, message_id, updated_at)
                        values (:t, :idoc, :type, 'INBOUND_TO_WMS', :vbeln, :status, :text, :msg, :now)
                        on conflict (tenant_id, idoc_number) do update set status = excluded.status,
                            status_text = excluded.status_text, message_id = excluded.message_id,
                            updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("idoc", idoc.docnum()).param("type", idoc.mestyp())
                .param("vbeln", vbeln).param("status", status).param("text", text).param("msg", messageId)
                .param("now", Timestamp.from(clock.instant())).update();
        return new IdocStatus(idoc.docnum(), idoc.mestyp(), vbeln, status, text, messageId);
    }
}
