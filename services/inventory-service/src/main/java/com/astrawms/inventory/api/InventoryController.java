package com.astrawms.inventory.api;

import com.astrawms.common.security.AccessScope;
import com.astrawms.common.security.ApprovalVerifier;
import com.astrawms.common.security.Roles;
import com.astrawms.common.tenancy.TenantFilter;
import com.astrawms.common.web.ApiException;
import com.astrawms.inventory.api.InventoryDtos.AdjustRequest;
import com.astrawms.inventory.api.InventoryDtos.BalanceView;
import com.astrawms.inventory.api.InventoryDtos.ItemSummary;
import com.astrawms.inventory.api.InventoryDtos.LpnView;
import com.astrawms.inventory.api.InventoryDtos.MoveRequest;
import com.astrawms.inventory.api.InventoryDtos.OperationResult;
import com.astrawms.inventory.api.InventoryDtos.Page;
import com.astrawms.inventory.api.InventoryDtos.ReceiveRequest;
import com.astrawms.inventory.api.InventoryDtos.StatusChangeRequest;
import com.astrawms.inventory.api.InventoryDtos.TxnView;
import com.astrawms.inventory.domain.StockStatus;
import com.astrawms.inventory.persistence.AllocationRepository;
import com.astrawms.inventory.persistence.SerialRepository;
import com.astrawms.inventory.service.InventoryCommandService;
import com.astrawms.inventory.service.InventoryQueryService;
import com.astrawms.inventory.service.InventoryQueryService.BalanceFilter;
import com.astrawms.inventory.service.InventoryQueryService.TxnFilter;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inventory API v1. Every POST requires an {@code Idempotency-Key} header (NFR-123): a retry with the same key and
 * body returns the original result with {@code replayed = true} and status 200 instead of 201.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/inventory")
public class InventoryController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final InventoryCommandService commands;
    private final InventoryQueryService queries;
    private final AllocationRepository allocationsRepo;
    private final ApprovalVerifier approvals;

    public InventoryController(InventoryCommandService commands, InventoryQueryService queries,
                               AllocationRepository allocationsRepo, ApprovalVerifier approvals) {
        this.commands = commands;
        this.queries = queries;
        this.allocationsRepo = allocationsRepo;
        this.approvals = approvals;
    }

    @PreAuthorize("hasAnyRole('RECEIVER','SUPERVISOR','WMS_SERVICE')")
    @PostMapping("/receipts")
    public ResponseEntity<OperationResult> receive(@PathVariable String siteId,
                                                   @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                   @Valid @RequestBody ReceiveRequest body) {
        return created(commands.receive(siteId, key, body));
    }

    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','INV_ANALYST','SUPERVISOR','WMS_SERVICE')")
    @PostMapping("/moves")
    public ResponseEntity<OperationResult> move(@PathVariable String siteId,
                                                @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                @Valid @RequestBody MoveRequest body) {
        return created(commands.move(siteId, key, body));
    }

    @PreAuthorize("hasAnyRole('INV_ANALYST','INV_MANAGER','SUPERVISOR','WMS_SERVICE')")
    @PostMapping("/adjustments")
    public ResponseEntity<OperationResult> adjust(@PathVariable String siteId,
                                                  @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                  @RequestHeader(value = ApprovalVerifier.APPROVAL_HEADER, required = false)
                                                  String approvalToken,
                                                  @Valid @RequestBody AdjustRequest body) {
        Approval a = approval(approvalToken, body.approvedBy());
        return created(commands.adjust(siteId, key, body.withApprovedBy(a.name()), a.scope()));
    }

    @PreAuthorize("hasAnyRole('INV_ANALYST','INV_MANAGER','SUPERVISOR','QA_MANAGER','WMS_SERVICE')")
    @PostMapping("/status-changes")
    public ResponseEntity<OperationResult> changeStatus(@PathVariable String siteId,
                                                        @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                        @RequestHeader(value = ApprovalVerifier.APPROVAL_HEADER, required = false)
                                                        String approvalToken,
                                                        @Valid @RequestBody StatusChangeRequest body) {
        if (body.fromStatus() == StockStatus.QI && body.toStatus() == StockStatus.AVAILABLE
                && !hasRole(Roles.QA_MANAGER) && !isService()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INV_QA_RELEASE_REQUIRED",
                    "Releasing stock from quality inspection requires the QA_MANAGER role");
        }
        Approval a = approval(approvalToken, body.approvedBy());
        return created(commands.changeStatus(siteId, key, body.withApprovedBy(a.name()), a.scope()));
    }

    // ------------------------------------------------------------------ allocation / pick / issue

    @PreAuthorize("hasAnyRole('SUPERVISOR','WMS_SERVICE')")
    @PostMapping("/allocations")
    public ResponseEntity<AllocationDtos.AllocationResult> allocate(@PathVariable String siteId,
                                                                   @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                                   @Valid @RequestBody AllocationDtos.AllocateRequest body) {
        AllocationDtos.AllocationResult r = commands.allocate(siteId, key, body);
        return ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(r);
    }

    @GetMapping("/allocations")
    public java.util.List<AllocationRepository.Allocation> allocations(@PathVariable String siteId,
                                                                      @RequestParam String orderRef) {
        AccessScope scope = AccessScope.current();
        return allocationsRepo.byOrder(siteId, orderRef).stream().filter(a -> scope.allowsOwner(a.ownerId())).toList();
    }

    @PreAuthorize("hasAnyRole('PICKER','SUPERVISOR','WMS_SERVICE')")
    @PostMapping("/allocations/{allocationId}/pick")
    public ResponseEntity<OperationResult> pick(@PathVariable String siteId, @PathVariable UUID allocationId,
                                                @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                @Valid @RequestBody AllocationDtos.PickRequest body) {
        return created(commands.pick(siteId, allocationId, key, body));
    }

    @PreAuthorize("hasAnyRole('SUPERVISOR','WMS_SERVICE')")
    @PostMapping("/allocations/release")
    public AllocationDtos.ReleaseResult release(@PathVariable String siteId, @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                @Valid @RequestBody AllocationDtos.ReleaseRequest body) {
        return commands.release(siteId, key, body);
    }

    @PreAuthorize("hasAnyRole('RECEIVER','PICKER','SUPERVISOR','WMS_SERVICE')")
    @PostMapping("/allocations/{allocationId}/return")
    public ResponseEntity<OperationResult> returnToStock(@PathVariable String siteId, @PathVariable UUID allocationId,
                                                        @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                        @RequestBody(required = false) AllocationDtos.ReturnRequest body) {
        return created(commands.returnToStock(siteId, allocationId, key, body));
    }

    @PreAuthorize("hasAnyRole('SUPERVISOR','WMS_SERVICE')")
    @PostMapping("/issues")
    public ResponseEntity<AllocationDtos.IssueResult> issue(@PathVariable String siteId,
                                                           @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                           @Valid @RequestBody AllocationDtos.IssueRequest body) {
        AllocationDtos.IssueResult r = commands.issue(siteId, key, body);
        return ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(r);
    }

    @GetMapping("/balances")
    public Page<BalanceView> balances(@PathVariable String siteId,
                                      @RequestParam(required = false) String ownerId,
                                      @RequestParam(required = false) String itemNo,
                                      @RequestParam(required = false) String lotNo,
                                      @RequestParam(required = false) String lpnId,
                                      @RequestParam(required = false) String locationId,
                                      @RequestParam(required = false) StockStatus status,
                                      @RequestParam(required = false) Long after,
                                      @RequestParam(defaultValue = "100") int limit) {
        return queries.balances(siteId, new BalanceFilter(ownerId, itemNo, lotNo, lpnId, locationId, status), after, limit);
    }

    @GetMapping("/inbound-staging")
    public java.util.List<java.util.Map<String, Object>> inboundStaging(@PathVariable String siteId) {
        return queries.inboundStaging(siteId);
    }

    @GetMapping("/items/{ownerId}/{itemNo}/summary")
    public ItemSummary itemSummary(@PathVariable String siteId, @PathVariable String ownerId,
                                   @PathVariable String itemNo) {
        return queries.itemSummary(siteId, ownerId, itemNo);
    }

    @GetMapping("/lpns/{lpnId}")
    public LpnView lpn(@PathVariable String siteId, @PathVariable String lpnId) {
        return queries.lpn(siteId, lpnId);
    }

    @GetMapping("/serials/{ownerId}/{itemNo}/{serialNo}")
    public SerialRepository.SerialView serial(@PathVariable String siteId, @PathVariable String ownerId,
                                              @PathVariable String itemNo, @PathVariable String serialNo) {
        return queries.serial(ownerId, itemNo, serialNo);
    }

    @GetMapping("/transactions")
    public Page<TxnView> transactions(@PathVariable String siteId,
                                      @RequestParam(required = false) String itemNo,
                                      @RequestParam(required = false) String lpnId,
                                      @RequestParam(required = false) String locationId,
                                      @RequestParam(required = false) UUID operationId,
                                      @RequestParam(required = false) Long after,
                                      @RequestParam(defaultValue = "100") int limit) {
        return queries.transactions(siteId, new TxnFilter(itemNo, lpnId, locationId, operationId), after, limit);
    }

    private static ResponseEntity<OperationResult> created(OperationResult result) {
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    // ------------------------------------------------------------------ approvals (NFR-101, §G.5)

    private record Approval(String name, AccessScope scope) {
    }

    /**
     * The approver must prove their identity with their own fresh sign-in (X-Approval-Token); their site/owner scope
     * and value limit then apply (§G.5.1). A plain {@code approvedBy} name is accepted only from AstraWMS services
     * (e.g. an approval workflow) acting for a user, which have checked the approver themselves.
     */
    private Approval approval(String approvalToken, String claimedApprover) {
        if (approvalToken != null && !approvalToken.isBlank()) {
            ApprovalVerifier.Approver a = approvals.approver(approvalToken, Roles.INV_MANAGER, Roles.SUPERVISOR);
            return new Approval(a.userName(), a.scope());
        }
        if (claimedApprover != null && !claimedApprover.isBlank()) {
            if (!isService()) {
                throw ApiException.unprocessable("INV_APPROVAL_TOKEN_REQUIRED",
                        "The approver must sign in to approve: send their token in " + ApprovalVerifier.APPROVAL_HEADER);
            }
            return new Approval(claimedApprover, AccessScope.UNRESTRICTED);
        }
        return new Approval(null, null);
    }

    private static boolean isService() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && TenantFilter.isService(auth);
    }

    private static boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
    }
}
