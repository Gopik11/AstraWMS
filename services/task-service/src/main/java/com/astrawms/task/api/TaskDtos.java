package com.astrawms.task.api;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TaskDtos {

    private TaskDtos() {
    }

    /** RF confirmation: the operator scans the LPN, then the location label (location ID + check digit). */
    public record ConfirmRequest(@NotBlank String lpnId, @NotBlank String locationId, @NotBlank String checkDigit) {
    }

    /** RF exception (PUT-EX-02 / PUT-EX-04): LOCATION_BLOCKED, LOCATION_OCCUPIED, LPN_NOT_FOUND. */
    public record ExceptionRequest(@NotBlank String reason, String detail) {
    }

    public record TaskView(UUID id, String taskType, String status, int priority, String ownerId, String lpnId,
                           String fromLocation, String targetLocation, String strategy,
                           String exceptionReason, String assignedTo, String confirmedLocation,
                           UUID inventoryOperationId, List<Content> contents, Instant createdAt,
                           Instant completedAt) {
    }

    public record Content(String ownerId, String itemNo, String lotNo, BigDecimal qty) {
    }
}
