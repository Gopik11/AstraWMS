package com.astrawms.common.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * RF receiving work (ADR-0019), sent by the inbound service to the task service on the task request topic
 * ({@link OutboundContracts#TOPIC_TASK_REQUESTS}). A vendor delivery (ASN) or an RMA that can be received becomes one
 * RECEIVE task for receivers; it is cancelled when the document is closed or cancelled on the desktop.
 */
public final class ReceivingContracts {

    public static final String KIND_ASN = "ASN";
    public static final String KIND_RMA = "RMA";

    private ReceivingContracts() {
    }

    /**
     * @param kind {@link #KIND_ASN} or {@link #KIND_RMA}
     * @param partner vendor ID (ASN) or customer name (RMA), for the RF screen
     * @param lines what is expected; empty for a blind return
     */
    public record ReceiveRequested(String kind, String docNo, String ownerId, String partner,
                                   Instant expectedArrivalUtc, List<Line> lines, int priority) {
        public static final String TYPE = "ReceiveRequested";
        public static final String VERSION = "1.0";

        public record Line(String lineRef, String itemNo, BigDecimal qty, String uom, String lotNo) {
        }
    }

    /** The document can no longer be received on RF (closed, cancelled, or fully received). */
    public record ReceiveEnded(String kind, String docNo, String reason) {
        public static final String TYPE = "ReceiveEnded";
        public static final String VERSION = "1.0";
    }
}
