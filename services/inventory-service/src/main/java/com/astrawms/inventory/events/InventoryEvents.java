package com.astrawms.inventory.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Messages published by the inventory service. */
public final class InventoryEvents {

    private InventoryEvents() {
    }

    @ConfigurationProperties("astra.topics")
    public record Topics(
            @DefaultValue("wms.inventory.events.v1") String inventoryEvents,
            @DefaultValue("wms.integration.outbound.goodsmovement.v1") String goodsMovements,
            @DefaultValue("wms.masterdata.events.v1") String masterdataEvents) {
    }

    /** {@code InventoryChanged} v1: one message per item touched by an operation (business key site:item). */
    public record InventoryChanged(UUID operationId, String wmsTxnId, String opType, String ownerId, String itemNo,
                                   List<Line> lines, Instant occurredAt) {
        public record Line(String txnType, String lotNo, String lpnId, String locationId, String status,
                           BigDecimal qtyDelta, BigDecimal qtyAfter) {
        }
    }

    /**
     * {@code GoodsMovement} v2: canonical message for IF-INV-001 (docs/isd/IF-INV-001.md §4). Quantities are in the
     * item's base UoM. The ERP adapter maps {@code movementType} and {@code reasonCode} to SAP / Oracle values.
     */
    public record GoodsMovement(String wmsTxnId, String movementType, String reasonCode, Instant physicalDateTimeUtc,
                                String approvedBy, List<Item> items) {
        /** {@code stockType}: ERP stock type of the source stock (UNRESTRICTED, QUALITY_INSPECTION, BLOCKED). */
        public record Item(String itemNo, BigDecimal qty, String uom, String fromBucket, String toBucket,
                           String lotNo, String toLotNo, String stockType, String text) {
        }
    }
}
