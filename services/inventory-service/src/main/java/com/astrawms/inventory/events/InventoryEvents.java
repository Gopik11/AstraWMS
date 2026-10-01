package com.astrawms.inventory.events;

import com.astrawms.common.contracts.IntegrationContracts;
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
            @DefaultValue(IntegrationContracts.TOPIC_GOODS_MOVEMENTS) String goodsMovements,
            @DefaultValue("wms.masterdata.events.v1") String masterdataEvents) {
    }

    /** {@code InventoryChanged} v1: one message per item touched by an operation (business key site:item). */
    public record InventoryChanged(UUID operationId, String wmsTxnId, String opType, String ownerId, String itemNo,
                                   List<Line> lines, Instant occurredAt) {
        public record Line(String txnType, String lotNo, String lpnId, String locationId, String status,
                           BigDecimal qtyDelta, BigDecimal qtyAfter) {
        }
    }

}
