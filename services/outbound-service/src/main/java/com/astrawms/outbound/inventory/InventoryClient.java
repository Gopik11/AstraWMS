package com.astrawms.outbound.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Allocation and issue commands to the inventory service; all idempotent by key (ADR-0005). */
public interface InventoryClient {

    record Allocation(UUID id, String locationId, String lpnId, String lotNo, BigDecimal qty) {
    }

    record AllocateResult(String baseUom, BigDecimal requestedQty, BigDecimal allocatedQty, BigDecimal shortQty,
                          List<Allocation> allocations) {
    }

    record LotQty(String lotNo, BigDecimal qty) {
    }

    record IssuedLine(String orderLineRef, String itemNo, BigDecimal qty, String uom, List<LotQty> lots,
                      List<String> serials) {
    }

    AllocateResult allocate(String siteId, String key, String orderRef, String orderLineRef, String ownerId,
                            String itemNo, BigDecimal qty, String uom, String lotNo);

    List<IssuedLine> issue(String siteId, String key, String orderRef);

    void release(String siteId, String key, String orderRef);
}
