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

    /**
     * Moves a quantity of one balance (owner, item, lot, status) onto an LPN, in base units. Used by the dock sweep to
     * put loose dock stock on a generated LPN at the same location, so it gets a putaway task.
     */
    UUID moveQuantity(String siteId, String idempotencyKey, String ownerId, String itemNo, String lotNo, String status,
                      java.math.BigDecimal qty, String fromLocationId, String fromLpnId, String toLocationId, String toLpnId);

    /** Picks (part of) an allocation into outbound staging; {@code shortClose} releases the remainder. */
    UUID pick(String siteId, String idempotencyKey, UUID allocationId, java.math.BigDecimal qty, String toLocationId,
              String toLpnId, java.util.List<String> serials, boolean shortClose);

    /** Completes a replenishment: reserved stock moves from the reserve location to the forward location. */
    UUID confirmReplenishment(String siteId, String idempotencyKey, UUID replenishmentId);

    /** Submits a blind count result; returns the count's new status (CLOSED, ADJUSTED, RECOUNT, PENDING_APPROVAL). */
    String submitCount(String siteId, String idempotencyKey, UUID countId, java.util.List<?> lines);

    /** Returns the picked stock of an allocation from outbound staging to the given location and LPN. */
    UUID returnToStock(String siteId, String idempotencyKey, UUID allocationId, String toLocationId, String toLpnId);
}
