package com.astrawms.common.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Canonical ERP integration messages (docs/isd). ERP-neutral: adapters map them to and from SAP / Oracle formats.
 * Additive changes only within a schema major version (NFR-121).
 */
public final class IntegrationContracts {

    private IntegrationContracts() {
    }

    // ------------------------------------------------------------------ topics

    public static final String TOPIC_RECEIPT_EXPECTATIONS = "wms.integration.inbound.receiptexpectation.v1";
    public static final String TOPIC_RECEIPT_CONFIRMATIONS = "wms.integration.outbound.receiptconfirmation.v1";
    public static final String TOPIC_GOODS_MOVEMENTS = "wms.integration.outbound.goodsmovement.v1";
    public static final String TOPIC_ERP_POSTING_RESULTS = "wms.integration.inbound.erpposting.v1";
    public static final String TOPIC_APPLICATION_ACKS = "wms.integration.outbound.ack.v1";

    // ------------------------------------------------------------------ IF-IB-001

    /** {@code ReceiptExpectation} v3 (ISD IF-IB-001 §4). Business key {@code siteId:erpDocNo}. */
    public record ReceiptExpectation(
            String erpDocNo, String erpDocType, String action, long revision, String sourceIdocOrEventId,
            String vendorId, String shipFromGln, String supplyingSiteId, String carrierScac,
            Instant expectedArrivalUtc, String externalRef, String billOfLading, String containerNo, String sealNo,
            List<Line> lines, List<HandlingUnit> handlingUnits, Instant sourceChangedAt) {

        public static final String TYPE = "ReceiptExpectation";
        public static final String VERSION = "3.0";

        public record Line(String erpLineRef, String ownerId, String itemNo, BigDecimal qtyExpected, String uom,
                           String lotNo, String vendorLotNo, PoRef poRef, String stockTypeTarget,
                           BigDecimal overTolerancePct, BigDecimal underTolerancePct) {
        }

        public record PoRef(String poNo, String poLine, String schedule) {
        }

        public record HandlingUnit(String sscc, String packagingMaterial, List<HuContent> contents) {
        }

        public record HuContent(String erpLineRef, BigDecimal qty, String uom, String lotNo) {
        }
    }

    /**
     * Application acknowledgement for ERP→WMS documents (ISD-00 §4): lets the adapter set the ERP-side status,
     * e.g. SAP ALEAUD status 53 (accepted) or 51 (rejected, with reason).
     */
    public record ApplicationAck(String sourceSystem, String sourceMessageId, String sourceDocumentId,
                                 String erpDocNo, String result, String reasonCode, String reasonText) {
        public static final String TYPE = "ApplicationAck";
        public static final String VERSION = "1.0";
        public static final String ACCEPTED = "ACCEPTED";
        public static final String REJECTED = "REJECTED";
    }

    // ------------------------------------------------------------------ IF-IB-002

    /** {@code ReceiptConfirmation} v3 (ISD IF-IB-002 §4). Business key {@code siteId:erpDocNo}. */
    public record ReceiptConfirmation(
            String wmsTxnId, String erpDocNo, boolean blindReceipt, String vendorId, Instant receiptCompletedUtc,
            @JsonProperty("final") boolean finalConfirmation, List<Line> lines, List<HandlingUnit> handlingUnits) {

        public static final String TYPE = "ReceiptConfirmation";
        public static final String VERSION = "3.0";

        /** {@code serials}: mandatory for INBOUND/FULL serial-controlled items; count = qtyReceived (base UoM). */
        public record Line(String erpLineRef, String itemNo, BigDecimal qtyReceived, String uom,
                           List<LotSplit> lotSplits, List<String> serials, String stockStatus, String reasonCode) {
        }

        public record LotSplit(String lotNo, String vendorLotNo, BigDecimal qty, LocalDate expiryDate) {
        }

        public record HandlingUnit(String lpnOrSscc, String packagingMaterial, List<HuContent> contents) {
        }

        public record HuContent(String erpLineRef, String lotNo, BigDecimal qty) {
        }
    }

    // ------------------------------------------------------------------ IF-INV-001

    /** {@code GoodsMovement} v2 (ISD IF-INV-001 §4). Quantities in the item's base UoM. Business key site:item. */
    public record GoodsMovement(String wmsTxnId, String movementType, String reasonCode, Instant physicalDateTimeUtc,
                                String approvedBy, List<Item> items) {

        public static final String TYPE = "GoodsMovement";
        public static final String VERSION = "2.0";

        /** {@code stockType}: ERP stock type of the source stock (UNRESTRICTED, QUALITY_INSPECTION, BLOCKED). */
        public record Item(String itemNo, BigDecimal qty, String uom, String fromBucket, String toBucket,
                           String lotNo, String toLotNo, List<String> serials, String stockType, String text) {
        }
    }

    // ------------------------------------------------------------------ ERP posting results (all WMS→ERP flows)

    /**
     * Terminal application result of a WMS→ERP posting (INT-011, INT-012). {@code errorClass} follows scope §D.6.1:
     * TRANSIENT, PERMANENT_TECHNICAL, BUSINESS_CORRECTABLE, BUSINESS_CONFLICT.
     */
    public record ErpPostingResult(String wmsTxnId, String sourceMessageType, String erpDocNo, boolean success,
                                   String erpDocument, String erpDocumentYear, boolean duplicate,
                                   String errorClass, String erpMessageId, String erpMessageText,
                                   Instant processedAtUtc) {
        public static final String TYPE = "ErpPostingResult";
        public static final String VERSION = "1.0";
    }
}
