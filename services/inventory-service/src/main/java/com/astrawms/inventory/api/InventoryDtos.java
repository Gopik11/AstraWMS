package com.astrawms.inventory.api;

import com.astrawms.inventory.domain.StockStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of the inventory API (OpenAPI: /v3/api-docs). */
public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record ReceiveRequest(
            @NotBlank String ownerId,
            @NotBlank String itemNo,
            String lotNo,
            LocalDate expiryDate,
            @NotNull @Positive BigDecimal qty,
            @NotBlank String uom,
            @Size(max = 40) String lpnId,
            @NotBlank String locationId,
            StockStatus status,
            String sourceDoc,
            List<String> serials) {
    }

    /**
     * Either moves a whole LPN ({@code lpnId} set and {@code itemNo} empty) or a quantity of one item/lot/status
     * from a location (optionally from an LPN) to a location (optionally into an LPN).
     */
    public record MoveRequest(
            @NotBlank String fromLocationId,
            String lpnId,
            String ownerId,
            String itemNo,
            String lotNo,
            StockStatus status,
            @Positive BigDecimal qty,
            String uom,
            @NotBlank String toLocationId,
            @Size(max = 40) String toLpnId,
            List<String> serials) {

        public boolean wholeLpn() {
            return lpnId != null && !lpnId.isBlank() && (itemNo == null || itemNo.isBlank());
        }
    }

    public record AdjustRequest(
            @NotBlank String ownerId,
            @NotBlank String itemNo,
            String lotNo,
            LocalDate expiryDate,
            String lpnId,
            @NotBlank String locationId,
            StockStatus status,
            @NotNull BigDecimal qtyDelta,
            @NotBlank String uom,
            @NotBlank String reasonCode,
            String approvedBy,
            List<String> serials) {

        public AdjustRequest withApprovedBy(String approver) {
            return new AdjustRequest(ownerId, itemNo, lotNo, expiryDate, lpnId, locationId, status, qtyDelta, uom,
                    reasonCode, approver, serials);
        }
    }

    public record StatusChangeRequest(
            @NotBlank String ownerId,
            @NotBlank String itemNo,
            String lotNo,
            String lpnId,
            @NotBlank String locationId,
            @NotNull StockStatus fromStatus,
            @NotNull StockStatus toStatus,
            @NotNull @Positive BigDecimal qty,
            @NotBlank String uom,
            @NotBlank String reasonCode,
            String approvedBy,
            List<String> serials) {

        public StatusChangeRequest withApprovedBy(String approver) {
            return new StatusChangeRequest(ownerId, itemNo, lotNo, lpnId, locationId, fromStatus, toStatus, qty, uom,
                    reasonCode, approver, serials);
        }
    }

    public record OperationResult(UUID operationId, String wmsTxnId, String opType, List<Line> lines,
                                  List<ErpMovement> erpMovements, Instant occurredAt, boolean replayed) {

        public OperationResult asReplay() {
            return new OperationResult(operationId, wmsTxnId, opType, lines, erpMovements, occurredAt, true);
        }

        public record Line(String txnType, String ownerId, String itemNo, String lotNo, String lpnId,
                           String locationId, StockStatus status, BigDecimal qtyDelta, BigDecimal qtyAfter) {
        }

        public record ErpMovement(String wmsTxnId, String movementType, String itemNo) {
        }
    }

    public record BalanceView(long id, String ownerId, String itemNo, String lotNo, String lpnId, String locationId,
                              StockStatus status, BigDecimal qty, BigDecimal allocatedQty, BigDecimal availableQty,
                              LocalDate expiryDate, Instant receiptDate) {
    }

    public record Page<T>(List<T> items, Long nextCursor) {
    }

    public record ItemSummary(String ownerId, String itemNo, List<StatusTotal> byStatus) {
        public record StatusTotal(StockStatus status, BigDecimal qty, BigDecimal allocatedQty) {
        }
    }

    public record LpnView(String lpnId, String ownerId, String locationId, String lpnType, List<BalanceView> contents,
                          List<String> serials) {
    }

    public record TxnView(long id, UUID operationId, String txnType, String ownerId, String itemNo, String lotNo,
                          String lpnId, String locationId, StockStatus status, BigDecimal qtyDelta,
                          BigDecimal qtyAfter, String reasonCode, String sourceDoc, String userId, String channel,
                          Instant occurredAt) {
    }
}
