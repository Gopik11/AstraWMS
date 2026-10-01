package com.astrawms.inventory.events;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.InventoryContracts;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Messages published by the inventory service. */
public final class InventoryEvents {

    private InventoryEvents() {
    }

    @ConfigurationProperties("astra.topics")
    public record Topics(
            @DefaultValue(InventoryContracts.TOPIC) String inventoryEvents,
            @DefaultValue(IntegrationContracts.TOPIC_GOODS_MOVEMENTS) String goodsMovements,
            @DefaultValue("wms.masterdata.events.v1") String masterdataEvents) {
    }


}
