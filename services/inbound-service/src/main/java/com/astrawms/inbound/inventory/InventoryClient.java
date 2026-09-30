package com.astrawms.inbound.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Commands the inbound service sends to the inventory service. */
public interface InventoryClient {

    record ReceiveCommand(String ownerId, String itemNo, String lotNo, LocalDate expiryDate, BigDecimal qty,
                          String uom, String lpnId, String locationId, String status, String sourceDoc,
                          List<String> serials) {
    }

    record ReceiveResult(UUID operationId, boolean replayed) {
    }

    /**
     * Receives stock into a dock/receiving location. Idempotent by {@code idempotencyKey}: a repeated call returns
     * the original operation without a second effect.
     *
     * @throws com.astrawms.common.web.ApiException with the inventory service's problem code on business errors,
     *         or {@code INB_INVENTORY_UNAVAILABLE} (503) when inventory cannot be reached (safe to retry)
     */
    ReceiveResult receive(String siteId, String idempotencyKey, ReceiveCommand command);
}
