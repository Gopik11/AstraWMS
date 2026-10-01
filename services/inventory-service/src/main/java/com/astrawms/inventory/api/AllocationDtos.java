package com.astrawms.inventory.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Allocation, pick and issue API (scope §3.4, §4, §5). Quantities in responses are in the item's base UoM. */
public final class AllocationDtos {

    private AllocationDtos() {
    }

    public enum Rotation { FEFO, FIFO }

    public record AllocateRequest(
            @NotBlank String orderRef,
            @NotBlank String orderLineRef,
            @NotBlank String ownerId,
            @NotBlank String itemNo,
            @NotNull @Positive BigDecimal qty,
            @NotBlank String uom,
            String lotNo,
            LocalDate minExpiryDate,
            Rotation rotation) {
    }

    public record AllocationResult(String orderRef, String orderLineRef, String itemNo, String baseUom,
                                   BigDecimal requestedQty, BigDecimal allocatedQty, BigDecimal shortQty,
                                   List<AllocationView> allocations, boolean replayed) {
        public AllocationResult asReplay() {
            return new AllocationResult(orderRef, orderLineRef, itemNo, baseUom, requestedQty, allocatedQty, shortQty,
                    allocations, true);
        }
    }

    public record AllocationView(UUID id, String locationId, String lpnId, String lotNo, BigDecimal qty,
                                 LocalDate expiryDate) {
    }

    /**
     * Picks quantity of an allocation into outbound staging. {@code shortClose} ends the allocation: the unpicked
     * remainder is released (short pick, PCK-003); then {@code qty} may be 0.
     */
    public record PickRequest(
            @NotNull @PositiveOrZero BigDecimal qty,
            @NotBlank String toLocationId,
            String toLpnId,
            List<String> serials,
            boolean shortClose) {
    }

    public record IssueRequest(@NotBlank String orderRef) {
    }

    public record IssueResult(String orderRef, List<IssuedLine> lines, boolean replayed) {
        public IssueResult asReplay() {
            return new IssueResult(orderRef, lines, true);
        }
    }

    public record IssuedLine(String orderLineRef, String itemNo, BigDecimal qty, String uom, List<LotQty> lots,
                             List<String> serials) {
    }

    public record LotQty(String lotNo, BigDecimal qty) {
    }

    public record ReleaseRequest(@NotBlank String orderRef) {
    }

    public record ReleaseResult(String orderRef, int releasedAllocations, BigDecimal releasedQty, boolean replayed) {
        public ReleaseResult asReplay() {
            return new ReleaseResult(orderRef, releasedAllocations, releasedQty, true);
        }
    }
}
