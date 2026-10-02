package com.astrawms.common.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Outbound integration messages (IF-OB-001, IF-OB-003) and the pick-work contracts between services. */
public final class OutboundContracts {

    public static final String TOPIC_OUTBOUND_ORDERS = "wms.integration.inbound.outboundorder.v1";
    public static final String TOPIC_SHIPMENT_CONFIRMATIONS = "wms.integration.outbound.shipmentconfirmation.v1";
    public static final String TOPIC_TASK_REQUESTS = "wms.task.requests.v1";
    public static final String TOPIC_TASK_EVENTS = "wms.task.events.v1";

    private OutboundContracts() {
    }

    // ------------------------------------------------------------------ IF-OB-001

    /** {@code OutboundOrder} v4 (ISD IF-OB-001 §4, release subset). Business key {@code siteId:erpDocNo}. */
    public record OutboundOrder(String erpDocNo, String orderType, String action, long revision,
                                String sourceIdocOrEventId, ShipTo shipTo, String carrierScac,
                                Instant plannedGoodsIssueUtc, List<Line> lines, Instant sourceChangedAt) {

        public static final String TYPE = "OutboundOrder";
        public static final String VERSION = "4.0";

        /** PII: name and address are the ship-to snapshot used for labels and documents. */
        public record ShipTo(String partnerId, String name, String city, String country) {
        }

        public record Line(String erpLineRef, String ownerId, String itemNo, BigDecimal qtyRequested, String uom,
                           String lotNo) {
        }
    }

    // ------------------------------------------------------------------ IF-OB-003

    /** {@code ShipmentConfirmation} v4 (ISD IF-OB-003 §4, release subset). Quantities in the item's base UoM. */
    public record ShipmentConfirmation(String wmsTxnId, String erpDocNo, Instant shipDateTimeUtc, String carrierScac,
                                       String trackingNo, String billOfLading, List<Line> lines) {

        public static final String TYPE = "ShipmentConfirmation";
        public static final String VERSION = "4.0";

        /** {@code shortReason} is mandatory when less than requested was shipped (NO_STOCK, SHORT_PICK). */
        public record Line(String erpLineRef, String itemNo, BigDecimal qtyShipped, String uom,
                           List<LotSplit> lotSplits, List<String> serials, String shortReason) {
        }

        public record LotSplit(String lotNo, BigDecimal qty) {
        }
    }

    // ------------------------------------------------------------------ pick work (outbound ↔ task service)

    /** One pick per inventory allocation; quantities in base UoM. */
    public record PickRequested(UUID allocationId, String orderRef, String orderLineRef, String ownerId, String itemNo,
                                String lotNo, BigDecimal qty, String uom, String fromLocation, String fromLpn,
                                String toLocation, String toLpn, int priority) {
        public static final String TYPE = "PickRequested";
        public static final String VERSION = "1.0";
    }

    /**
     * Reverse pick (OUT-EX-02): bring the picked stock of an allocation from outbound staging back to stock. The
     * task service creates a RETURN task; its completion is reported as {@link TaskCompleted} with type RETURN.
     */
    public record ReturnRequested(UUID allocationId, String orderRef, String orderLineRef, String ownerId,
                                  String itemNo, String lotNo, BigDecimal qty, String uom, String fromLocation,
                                  String fromLpn, String toLocation, String toLpn, int priority) {
        public static final String TYPE = "ReturnRequested";
        public static final String VERSION = "1.0";
    }

    public record PickCancelled(UUID allocationId, String orderRef) {
        public static final String TYPE = "PickCancelled";
        public static final String VERSION = "1.0";
    }

    /**
     * @param shortReason why a pick came up short (NOT_FOUND, DAMAGED, QTY_LESS, WRONG_ITEM, OTHER); null when not short
     * @param shortAction what to do with the missing quantity (ADR-0020): {@code REALLOCATE} elsewhere now (default),
     *                    {@code BACKORDER} (wait for stock; backorder recovery allocates it), {@code SHIP_SHORT}
     *                    (deallocate: the line ships short). Both added in 1.1.
     */
    public record TaskCompleted(UUID taskId, String taskType, UUID allocationId, String orderRef, String orderLineRef,
                                BigDecimal qtyPicked, BigDecimal qtyShort, String completedBy, Instant completedAt,
                                String shortReason, String shortAction) {
        public static final String TYPE = "TaskCompleted";
        public static final String VERSION = "1.1";

        public TaskCompleted(UUID taskId, String taskType, UUID allocationId, String orderRef, String orderLineRef,
                             BigDecimal qtyPicked, BigDecimal qtyShort, String completedBy, Instant completedAt) {
            this(taskId, taskType, allocationId, orderRef, orderLineRef, qtyPicked, qtyShort, completedBy, completedAt,
                    null, null);
        }
    }
}
