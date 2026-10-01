package com.astrawms.common.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Customer returns contracts: RMAs from the ERP (IF-RET-001) and return receipt + disposition to the ERP (IF-RET-002). */
public final class ReturnsContracts {

    public static final String TOPIC_RETURN_EXPECTATIONS = "wms.integration.inbound.returnexpectation.v1";
    public static final String TOPIC_RETURN_CONFIRMATIONS = "wms.integration.outbound.returnconfirmation.v1";

    private ReturnsContracts() {
    }

    /** {@code ReturnExpectation} v1 (IF-RET-001 §4): an RMA / returns delivery expected at a site. */
    public record ReturnExpectation(String rmaNo, String action, long revision, String sourceIdocOrEventId,
                                    String returnType, String campaignId, Customer customer, Instant expectedArrivalUtc,
                                    List<String> trackingNos, List<Line> lines, Instant sourceChangedAt) {
        public static final String TYPE = "ReturnExpectation";
        public static final String VERSION = "1.0";

        /** Customer data is PII (ISD-00 §7). */
        public record Customer(String partnerId, String name) {
        }

        public record Line(String erpLineRef, String ownerId, String itemNo, BigDecimal qtyExpected, String uom,
                           String returnReason, List<String> expectedSerials, boolean inspectionRequired) {
        }
    }

    /**
     * {@code ReturnConfirmation} v1 (IF-RET-002 §4): what came back, in which condition, and its disposition. Posted
     * in two steps (RET002-R01): the receipt into returns stock ({@code receiptTxnId}), then the disposition movements
     * ({@code dispositionTxnId}); each step is idempotent in the ERP by its own transaction ID. {@code rmaNo} is
     * absent for blind returns.
     */
    public record ReturnConfirmation(String receiptTxnId, String dispositionTxnId, String rmaNo, Instant receivedAtUtc,
                                     List<Line> lines) {
        public static final String TYPE = "ReturnConfirmation";
        public static final String VERSION = "1.0";

        public record Line(String erpLineRef, String itemNo, BigDecimal qty, String uom, String lotNo,
                           List<String> serials, String conditionGrade, String returnReasonActual, String disposition,
                           boolean wrongItem) {
        }
    }
}
