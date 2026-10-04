package com.astrawms.outbound.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Allocation and issue commands to the inventory service; all idempotent by key (ADR-0005). */
public interface InventoryClient {

    record Allocation(UUID id, String locationId, String lpnId, String lotNo, BigDecimal qty) {
    }

    /** {@code shortReason}/{@code shortDetail}: why the allocation came up short (ADR-0021); null when not short. */
    /** {@code rule}: the allocation rule that fired, in words (ADR-0025), recorded on the order line. */
    record AllocateResult(String baseUom, BigDecimal requestedQty, BigDecimal allocatedQty, BigDecimal shortQty,
                          List<Allocation> allocations, String shortReason, String shortDetail, String rule) {

        public AllocateResult(String baseUom, BigDecimal requestedQty, BigDecimal allocatedQty, BigDecimal shortQty,
                              List<Allocation> allocations) {
            this(baseUom, requestedQty, allocatedQty, shortQty, allocations, null, null, null);
        }

        public AllocateResult(String baseUom, BigDecimal requestedQty, BigDecimal allocatedQty, BigDecimal shortQty,
                              List<Allocation> allocations, String shortReason, String shortDetail) {
            this(baseUom, requestedQty, allocatedQty, shortQty, allocations, shortReason, shortDetail, null);
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

    /** On hand in AVAILABLE status and how much of it is allocated, for one item at a site (ADR-0025). */
    record Availability(BigDecimal onHand, BigDecimal allocated) {
        public BigDecimal free() {
            return onHand.subtract(allocated);
        }
    }

    default Availability availability(String siteId, String ownerId, String itemNo) {
        throw new UnsupportedOperationException("availability");
    }

    List<IssuedLine> issue(String siteId, String key, String orderRef);

    /** Goods issue of a transfer (ADR-0025): inventory keeps what was issued in transit to {@code transferToSite}. */
    default List<IssuedLine> issue(String siteId, String key, String orderRef, String transferToSite) {
        return issue(siteId, key, orderRef);
    }

    void release(String siteId, String key, String orderRef);
}
