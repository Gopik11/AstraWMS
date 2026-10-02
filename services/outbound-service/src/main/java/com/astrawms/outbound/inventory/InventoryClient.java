package com.astrawms.outbound.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Allocation and issue commands to the inventory service; all idempotent by key (ADR-0005). */
public interface InventoryClient {

    record Allocation(UUID id, String locationId, String lpnId, String lotNo, BigDecimal qty) {
    }

    /** {@code shortReason}/{@code shortDetail}: why the allocation came up short (ADR-0021); null when not short. */
    record AllocateResult(String baseUom, BigDecimal requestedQty, BigDecimal allocatedQty, BigDecimal shortQty,
                          List<Allocation> allocations, String shortReason, String shortDetail) {

        public AllocateResult(String baseUom, BigDecimal requestedQty, BigDecimal allocatedQty, BigDecimal shortQty,
                              List<Allocation> allocations) {
            this(baseUom, requestedQty, allocatedQty, shortQty, allocations, null, null);
        }
    }

    record LotQty(String lotNo, BigDecimal qty) {
    }

    record IssuedLine(String orderLineRef, String itemNo, BigDecimal qty, String uom, List<LotQty> lots,
                      List<String> serials) {
    }

    /** Inventory's view of an allocation; picked stock sits at {@code pickedLocation} / {@code pickedLpn}. */
    record InventoryAllocation(UUID id, String orderLineRef, String ownerId, String itemNo, String lotNo, String lpnId,
                               String locationId, BigDecimal qtyPicked, String pickedLocation, String pickedLpn,
                               String status) {
    }

    /** @param excludeLocationIds locations not to allocate from (e.g. where a pick came up short) */
    AllocateResult allocate(String siteId, String key, String orderRef, String orderLineRef, String ownerId,
                            String itemNo, BigDecimal qty, String uom, String lotNo, List<String> excludeLocationIds);

    /** All allocations of an order, as inventory sees them (the authority on what was actually picked). */
    List<InventoryAllocation> allocations(String siteId, String orderRef);

    List<IssuedLine> issue(String siteId, String key, String orderRef);

    void release(String siteId, String key, String orderRef);
}
