package com.astrawms.inbound.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class InboundDtos {

    private InboundDtos() {
    }

    /** Line-level receipt (RF: scan item, qty, lot/expiry, LPN, dock location). */
    public record ReceiveLineRequest(
            @NotNull @Positive BigDecimal qty,
            @NotBlank String uom,
            String lotNo,
            String vendorLotNo,
            LocalDate expiryDate,
            @Size(max = 40) String lpnId,
            @NotBlank String locationId,
            String overrideReason,
            String approvedBy,
            List<String> serials,
            String damageReason,
            String damageNote,
            String photo) {

        /** {@code damageReason} (ADR-0025): received as DAMAGED stock; {@code photo} is a data URL from the device. */
        public ReceiveLineRequest(BigDecimal qty, String uom, String lotNo, String vendorLotNo, LocalDate expiryDate,
                                  String lpnId, String locationId, String overrideReason, String approvedBy,
                                  List<String> serials) {
            this(qty, uom, lotNo, vendorLotNo, expiryDate, lpnId, locationId, overrideReason, approvedBy, serials,
                    null, null, null);
        }
    }

    /** RF receipt by item scan (ADR-0019): the item chooses the line. */
    public record ReceiveItemRequest(
            @NotBlank String itemNo,
            @NotNull @Positive BigDecimal qty,
            @NotBlank String uom,
            String lotNo,
            String vendorLotNo,
            LocalDate expiryDate,
            @Size(max = 40) String lpnId,
            @NotBlank String locationId,
            String overrideReason,
            String approvedBy,
            List<String> serials) {

        public ReceiveLineRequest line() {
            return new ReceiveLineRequest(qty, uom, lotNo, vendorLotNo, expiryDate, lpnId, locationId, overrideReason,
                    approvedBy, serials);
        }
    }

    /** SSCC single-scan receipt of an expected handling unit (INB-011). */
    public record ReceiveSsccRequest(@NotBlank String locationId) {
    }

    /** {@code shortReasons}: erpLineRef → SHORT_VENDOR / DAMAGED / REFUSED / IN_TRANSIT for lines received short. */
    public record CloseRequest(Map<String, String> shortReasons) {
    }

    public record ReceiveResult(String erpDocNo, List<LineProgress> lines, List<UUID> inventoryOperationIds,
                                String lpnId, boolean replayed) {

        public ReceiveResult asReplay() {
            return new ReceiveResult(erpDocNo, lines, inventoryOperationIds, lpnId, true);
        }
    }

    public record LineProgress(String erpLineRef, String itemNo, BigDecimal qtyExpected, BigDecimal qtyReceived,
                               BigDecimal qtyOpen, String uom) {
    }

    public record ExpectationSummary(UUID id, String erpDocNo, String erpDocType, long revision, String vendorId,
                                     Instant expectedArrivalUtc, String status, int lineCount,
                                     String confirmationTxnId, String erpDocument, String erpErrorClass,
                                     String erpErrorText, Instant createdAt, Instant updatedAt) {
    }

    public record ExpectationDetail(ExpectationSummary header, List<LineDetail> lines, List<HuDetail> handlingUnits) {
    }

    public record LineDetail(String erpLineRef, String ownerId, String itemNo, BigDecimal qtyExpected,
                             BigDecimal qtyReceived, String uom, String lotNo, String stockTypeTarget,
                             BigDecimal overTolerancePct, String shortReason) {
    }

    public record HuDetail(String sscc, String erpLineRef, BigDecimal qty, String lotNo, boolean received) {
    }
}
