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
import com.astrawms.sapadapter.mapping.SapCodes;
import com.astrawms.sapadapter.sap.Bapi;
import com.astrawms.sapadapter.sap.SapGateway;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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
            if (confirmation.transferFromSiteId() != null) {   // ADR-0023: 305 at the receiving plant
                DelvryMapper.Plant to = plant(envelope.siteId());
                DelvryMapper.Plant from = plant(confirmation.transferFromSiteId());
                List<BapiMapper.TransferLine> lines = new ArrayList<>();
                for (ReceiptConfirmation.Line l : confirmation.lines()) {
                    if (l.lotSplits() == null || l.lotSplits().isEmpty()) {
                        if (l.qtyReceived().signum() > 0) {
                            lines.add(new BapiMapper.TransferLine(l.itemNo(), null, l.qtyReceived(), l.uom(), l.serials()));
                        }
                    } else {
                        l.lotSplits().forEach(s -> lines.add(new BapiMapper.TransferLine(l.itemNo(), s.lotNo(), s.qty(),
                                l.uom(), null)));
                    }
                }
                Bapi.GoodsmvtCreate call = BapiMapper.transferMovement(confirmation.wmsTxnId(), "305", to.werks(),
                        from.werks(), confirmation.receiptCompletedUtc().atZone(to.timeZone()).toLocalDate(),
                        "WMS TRANSFER " + confirmation.erpDocNo(), lines);
                result = toResult(confirmation.wmsTxnId(), ReceiptConfirmation.TYPE, confirmation.erpDocNo(),
                        gateway.createGoodsMovement(call));
                publish(envelope, result);
                return;
            }
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
            if (confirmation.transferToSiteId() != null) {      // ADR-0023: 303 from the issuing plant
                DelvryMapper.Plant from = plant(envelope.siteId());
                DelvryMapper.Plant to = plant(confirmation.transferToSiteId());
                List<BapiMapper.TransferLine> lines = new ArrayList<>();
                for (OutboundContracts.ShipmentConfirmation.Line l : confirmation.lines()) {
                    if (l.lotSplits() == null || l.lotSplits().isEmpty()) {
                        if (l.qtyShipped().signum() > 0) {
                            lines.add(new BapiMapper.TransferLine(l.itemNo(), null, l.qtyShipped(), l.uom(), l.serials()));
                        }
                    } else {
                        l.lotSplits().forEach(s -> lines.add(new BapiMapper.TransferLine(l.itemNo(), s.lotNo(), s.qty(),
                                l.uom(), null)));
                    }
                }
                Bapi.GoodsmvtCreate call = BapiMapper.transferMovement(confirmation.wmsTxnId(), "303", from.werks(),
                        to.werks(), confirmation.shipDateTimeUtc().atZone(from.timeZone()).toLocalDate(),
                        "WMS TRANSFER " + confirmation.erpDocNo(), lines);
                result = toResult(confirmation.wmsTxnId(), OutboundContracts.ShipmentConfirmation.TYPE,
                        confirmation.erpDocNo(), gateway.createGoodsMovement(call));
                publish(envelope, result);
                return;
            }
            Bapi.OutbDeliveryConfirmDec call = BapiMapper.confirmOutbound(confirmation);
            result = toResult(confirmation.wmsTxnId(), OutboundContracts.ShipmentConfirmation.TYPE, confirmation.erpDocNo(),
                    gateway.confirmOutboundDelivery(call));
        } catch (MappingException e) {
            result = mappingFailure(confirmation.wmsTxnId(), OutboundContracts.ShipmentConfirmation.TYPE,
                    confirmation.erpDocNo(), e);
        }
        publish(envelope, result);
    }

    private DelvryMapper.Plant plant(String siteId) {
        return sites.bySite(siteId).orElseThrow(() -> new MappingException("SITE_NOT_MAPPED",
                "Site " + siteId + " has no SAP plant"));
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

    /**
     * IF-RET-002, two steps (RET002-R01): (1) receipt of everything returned into blocked returns stock, movement 651,
     * reference = receiptTxnId; (2) disposition: units to restock move to unrestricted stock, movement 453, reference =
     * dispositionTxnId. Other dispositions stay in returns stock until their follow-up (refurbish, RTV, scrap, QA)
     * posts. Both steps are idempotent in SAP by their reference, so a repost is safe.
     */
    @Transactional
    public void confirmReturn(EventEnvelope envelope, com.astrawms.common.contracts.ReturnsContracts.ReturnConfirmation r) {
        String type = com.astrawms.common.contracts.ReturnsContracts.ReturnConfirmation.TYPE;
        String docNo = r.rmaNo();
        ErpPostingResult result;
        try {
            DelvryMapper.Plant plant = sites.bySite(envelope.siteId()).orElseThrow(() -> new MappingException(
                    "SITE_NOT_MAPPED", "Site " + envelope.siteId() + " has no SAP plant"));
            String date = java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(
                    r.receivedAtUtc().atZone(plant.timeZone()).toLocalDate());
            Bapi.GoodsmvtCreate receipt = returnsMovement(r, r.lines(), r.receiptTxnId(), "01", "651", plant.werks(), date,
                    "WMS RETURN RECEIPT");
            SapGateway.Result step1 = gateway.createGoodsMovement(receipt);
            result = toResult(r.receiptTxnId(), type, docNo, step1);
            var restock = r.lines().stream().filter(l -> "RESTOCK".equals(l.disposition())).toList();
            if (step1.success() && !restock.isEmpty()) {
                Bapi.GoodsmvtCreate disposition = returnsMovement(r, restock, r.dispositionTxnId(), "04", "453",
                        plant.werks(), date, "WMS RETURN RESTOCK");
                SapGateway.Result step2 = gateway.createGoodsMovement(disposition);
                if (!step2.success()) {
                    result = toResult(r.receiptTxnId(), type, docNo, step2);
                }
            }
        } catch (MappingException e) {
            result = mappingFailure(r.receiptTxnId(), type, docNo, e);
        }
        publish(envelope, result);
    }

    private static Bapi.GoodsmvtCreate returnsMovement(com.astrawms.common.contracts.ReturnsContracts.ReturnConfirmation r,
                                                       List<com.astrawms.common.contracts.ReturnsContracts.ReturnConfirmation.Line> lines,
                                                       String reference, String gmCode, String moveType, String plant,
                                                       String date, String text) {
        List<Bapi.GoodsmvtItem> items = new ArrayList<>();
        List<Bapi.GoodsmvtSerial> serials = new ArrayList<>();
        for (int n = 0; n < lines.size(); n++) {
            var l = lines.get(n);
            items.add(new Bapi.GoodsmvtItem(l.itemNo(), plant, "0001", l.lotNo(), moveType, " ", l.qty(),
                    SapCodes.uomToSap(l.uom()), null, (l.conditionGrade() + " " + l.disposition()).trim()));
            String position = String.format("%04d", n + 1);
            if (l.serials() != null) {
                l.serials().forEach(sn -> serials.add(new Bapi.GoodsmvtSerial(position, sn)));
            }
        }
        String header = r.rmaNo() == null ? text : (text + " " + r.rmaNo());
        return new Bapi.GoodsmvtCreate(new Bapi.GoodsmvtHeader(date, date, reference,
                header.length() > 25 ? header.substring(0, 25) : header), gmCode, items, serials);
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
