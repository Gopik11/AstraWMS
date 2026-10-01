package com.astrawms.task.inventory;

import java.util.UUID;

/** Commands the task service sends to the inventory service. */
public interface InventoryClient {

    /**
     * Moves a whole LPN. Idempotent by {@code idempotencyKey} (ADR-0005).
     *
     * @return the inventory operation ID
     * @throws com.astrawms.common.web.ApiException with the inventory problem code, or 503 TSK_INVENTORY_UNAVAILABLE
     */
    UUID moveLpn(String siteId, String idempotencyKey, String lpnId, String fromLocationId, String toLocationId);
}
