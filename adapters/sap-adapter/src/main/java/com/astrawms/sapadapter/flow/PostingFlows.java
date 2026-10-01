package com.astrawms.sapadapter.flow;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.ErpPostingResult;
import com.astrawms.common.contracts.IntegrationContracts.GoodsMovement;
import com.astrawms.common.contracts.IntegrationContracts.ReceiptConfirmation;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.sapadapter.config.SapProperties;
import com.astrawms.sapadapter.mapping.BapiMapper;
import com.astrawms.sapadapter.mapping.DelvryMapper;
import com.astrawms.sapadapter.mapping.MappingException;
import com.astrawms.sapadapter.sap.Bapi;
import com.astrawms.sapadapter.sap.SapGateway;
import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WMS→SAP postings: IF-IB-002 (BAPI_INB_DELIVERY_CONFIRM_DEC) and IF-INV-001 (BAPI_GOODSMVT_CREATE). Every
 * message ends in an {@link ErpPostingResult} (INT-011) except transient failures, which propagate so that the
 * Kafka listener retries the whole message; the gateway's duplicate check makes the retry safe (INT-014).
 */
@Service
public class PostingFlows {

    private static final Logger log = LoggerFactory.getLogger(PostingFlows.class);
    /** SAP messages that a business user can correct in SAP (scope §D.6.1). Everything else: BUSINESS_CONFLICT. */
    private static final Set<String> CORRECTABLE = Set.of("M7 053", "M7 102", "M7 021", "VL 609");

    private final SapGateway gateway;
    private final SiteMapRepository sites;
    private final OutboxWriter outbox;
    private final SapProperties sap;
    private final Clock clock;

    public PostingFlows(SapGateway gateway, SiteMapRepository sites, OutboxWriter outbox, SapProperties sap,
                        Clock clock) {
        this.gateway = gateway;
        this.sites = sites;
        this.outbox = outbox;
        this.sap = sap;
        this.clock = clock;
    }

    @Transactional
    public void confirmReceipt(EventEnvelope envelope, ReceiptConfirmation confirmation) {
        ErpPostingResult result;
        try {
            Bapi.InbDeliveryConfirmDec call = BapiMapper.confirmInbound(confirmation);
            result = toResult(confirmation.wmsTxnId(), ReceiptConfirmation.TYPE, confirmation.erpDocNo(),
                    gateway.confirmInboundDelivery(call));
        } catch (MappingException e) {
            result = mappingFailure(confirmation.wmsTxnId(), ReceiptConfirmation.TYPE, confirmation.erpDocNo(), e);
        }
        publish(envelope, result);
    }

    @Transactional
    public void confirmShipment(EventEnvelope envelope, OutboundContracts.ShipmentConfirmation confirmation) {
        ErpPostingResult result;
        try {
            Bapi.OutbDeliveryConfirmDec call = BapiMapper.confirmOutbound(confirmation);
            result = toResult(confirmation.wmsTxnId(), OutboundContracts.ShipmentConfirmation.TYPE, confirmation.erpDocNo(),
                    gateway.confirmOutboundDelivery(call));
        } catch (MappingException e) {
            result = mappingFailure(confirmation.wmsTxnId(), OutboundContracts.ShipmentConfirmation.TYPE,
                    confirmation.erpDocNo(), e);
        }
        publish(envelope, result);
    }

    @Transactional
    public void postGoodsMovement(EventEnvelope envelope, GoodsMovement movement) {
        ErpPostingResult result;
        try {
            DelvryMapper.Plant plant = sites.bySite(envelope.siteId()).orElseThrow(() -> new MappingException(
                    "SITE_NOT_MAPPED", "Site " + envelope.siteId() + " has no SAP plant"));
            Bapi.GoodsmvtCreate call = BapiMapper.goodsMovement(movement, plant.werks(), plant.timeZone());
            result = toResult(movement.wmsTxnId(), GoodsMovement.TYPE, null, gateway.createGoodsMovement(call));
        } catch (MappingException e) {
            result = mappingFailure(movement.wmsTxnId(), GoodsMovement.TYPE, null, e);
        }
        publish(envelope, result);
    }

    private ErpPostingResult toResult(String wmsTxnId, String type, String docNo, SapGateway.Result r) {
        if (r.success()) {
            return new ErpPostingResult(wmsTxnId, type, docNo, true, r.materialDocument(), r.year(), r.duplicate(),
                    null, null, null, clock.instant());
        }
        Bapi.Return error = r.firstError();
        String messageId = error == null ? null : error.id() + " " + error.number();
        String errorClass = CORRECTABLE.contains(messageId) ? "BUSINESS_CORRECTABLE" : "BUSINESS_CONFLICT";
        log.info("SAP rejected {} {}: {} {}", type, wmsTxnId, messageId, error == null ? "" : error.message());
        return new ErpPostingResult(wmsTxnId, type, docNo, false, null, null, false, errorClass, messageId,
                error == null ? "No error message returned" : error.message(), clock.instant());
    }

    private ErpPostingResult mappingFailure(String wmsTxnId, String type, String docNo, MappingException e) {
        log.warn("Cannot map {} {}: {}", type, wmsTxnId, e.getMessage());
        return new ErpPostingResult(wmsTxnId, type, docNo, false, null, null, false, "PERMANENT_TECHNICAL",
                e.code(), e.getMessage(), clock.instant());
    }

    private void publish(EventEnvelope source, ErpPostingResult result) {
        outbox.append(new OutboxWriter.Message(IntegrationContracts.TOPIC_ERP_POSTING_RESULTS, ErpPostingResult.TYPE,
                ErpPostingResult.VERSION, sap.logicalSystem(), "ASTRAWMS", source.siteId(), source.ownerId(),
                source.businessKey(), result));
    }
}
