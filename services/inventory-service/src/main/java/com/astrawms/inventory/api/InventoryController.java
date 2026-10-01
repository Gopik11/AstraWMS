package com.astrawms.inventory.api;

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
import com.astrawms.inventory.persistence.SerialRepository;
import com.astrawms.inventory.service.InventoryCommandService;
import com.astrawms.inventory.service.InventoryQueryService;
import com.astrawms.inventory.service.InventoryQueryService.BalanceFilter;
import com.astrawms.inventory.service.InventoryQueryService.TxnFilter;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public InventoryController(InventoryCommandService commands, InventoryQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping("/receipts")
    public ResponseEntity<OperationResult> receive(@PathVariable String siteId,
                                                   @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                   @Valid @RequestBody ReceiveRequest body) {
        return created(commands.receive(siteId, key, body));
    }

    @PostMapping("/moves")
    public ResponseEntity<OperationResult> move(@PathVariable String siteId,
                                                @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                @Valid @RequestBody MoveRequest body) {
        return created(commands.move(siteId, key, body));
    }

    @PostMapping("/adjustments")
    public ResponseEntity<OperationResult> adjust(@PathVariable String siteId,
                                                  @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                  @Valid @RequestBody AdjustRequest body) {
        return created(commands.adjust(siteId, key, body));
    }

    @PostMapping("/status-changes")
    public ResponseEntity<OperationResult> changeStatus(@PathVariable String siteId,
                                                        @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                        @Valid @RequestBody StatusChangeRequest body) {
        return created(commands.changeStatus(siteId, key, body));
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
}
