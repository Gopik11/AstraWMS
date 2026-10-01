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
