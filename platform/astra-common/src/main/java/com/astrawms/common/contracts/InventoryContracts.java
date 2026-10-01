package com.astrawms.common.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Event contracts published by the Inventory service. Additive changes only within a major version. */
public final class InventoryContracts {

    public static final String TOPIC = "wms.inventory.events.v1";

    private InventoryContracts() {
    }

    /**
     * Cycle count request (§6.3) for one location, sent to the task service on the task request topic
     * ({@code OutboundContracts.TOPIC_TASK_REQUESTS}). {@code sequence} 1 is the first count; recounts have 2 and 3
     * and must be done by users not in {@code excludedUsers} (INV-008 spirit: independent recount).
     */
    /**
     * Replenishment of a forward location (§7, min/max), sent to the task service on the task request topic. The
     * source stock is already reserved (allocation {@code allocationId}); the RF operator takes {@code qty} from
     * {@code fromLocation}/{@code fromLpn} and confirms at {@code toLocation} with its check digit.
     */
    public record ReplenRequested(UUID replenishmentId, String ownerId, String itemNo, String lotNo, BigDecimal qty,
                                  String uom, String fromLocation, String fromLpn, String toLocation, int priority) {
        public static final String TYPE = "ReplenRequested";
        public static final String VERSION = "1.0";
    }

    public record CountRequested(UUID countId, String locationId, int sequence, List<String> excludedUsers,
                                 String trigger, int priority) {
        public static final String TYPE = "CountRequested";
        public static final String VERSION = "1.0";
    }

    /**
     * {@code InventoryChanged} v1: one message per item touched by an inventory operation, business key
     * {@code siteId:itemNo}. Each line carries the balance key and the quantity after the change, so consumers can
     * maintain an exact projection of stock per location / LPN (event-carried state transfer).
     */
    public record InventoryChanged(UUID operationId, String wmsTxnId, String opType, String ownerId, String itemNo,
                                   List<Line> lines, Instant occurredAt) {

        public static final String TYPE = "InventoryChanged";
        public static final String VERSION = "1.0";

        public record Line(String txnType, String lotNo, String lpnId, String locationId, String status,
                           BigDecimal qtyDelta, BigDecimal qtyAfter) {
        }
    }
}
