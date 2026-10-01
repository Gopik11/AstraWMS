package com.astrawms.inventory.service;

import com.astrawms.common.ids.WmsTxnId;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.inventory.api.InventoryDtos.AdjustRequest;
import com.astrawms.inventory.api.InventoryDtos.MoveRequest;
import com.astrawms.inventory.api.InventoryDtos.OperationResult;
import com.astrawms.inventory.api.InventoryDtos.ReceiveRequest;
import com.astrawms.inventory.api.InventoryDtos.StatusChangeRequest;
import com.astrawms.inventory.domain.ErpMovementType;
import com.astrawms.inventory.domain.StockStatus;
import com.astrawms.inventory.domain.TxnType;
import com.astrawms.common.contracts.IntegrationContracts.GoodsMovement;
import com.astrawms.common.contracts.InventoryContracts.InventoryChanged;
import com.astrawms.inventory.events.InventoryEvents.Topics;
import com.astrawms.inventory.persistence.InventoryRepository;
import com.astrawms.inventory.persistence.InventoryRepository.Balance;
import com.astrawms.inventory.persistence.InventoryRepository.BalanceKey;
import com.astrawms.inventory.persistence.InventoryRepository.Reason;
import com.astrawms.inventory.persistence.InventoryRepository.StoredOperation;
import com.astrawms.inventory.persistence.InventoryRepository.TxnLine;
import com.astrawms.inventory.persistence.SerialRepository;
import com.astrawms.inventory.reference.ReferenceData.ItemRef;
import com.astrawms.inventory.reference.ReferenceData.LocationRef;
import com.astrawms.inventory.reference.ReferenceRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executes inventory commands. Each command is one database transaction that updates balances, appends the
 * ledger, and writes events to the outbox, so state and events can never diverge.
 */
@Service
public class InventoryCommandService {

    private static final int MAX_IDEMPOTENCY_KEY = 100;

    private final InventoryRepository repo;
    private final SerialRepository serials;
    private final ReferenceRepository refs;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final Clock clock;
    private final Topics topics;

    public InventoryCommandService(InventoryRepository repo, SerialRepository serials, ReferenceRepository refs,
                                   OutboxWriter outbox, JsonMapper json, Clock clock, Topics topics) {
        this.repo = repo;
        this.serials = serials;
        this.refs = refs;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
        this.topics = topics;
    }

    // =====================================================================================================
    // Commands
    // =====================================================================================================

    @Transactional
    public OperationResult receive(String siteId, String idempotencyKey, ReceiveRequest r) {
        return idempotent(siteId, idempotencyKey, "RECEIPT", r, ctx -> {
            ItemRef item = requireItem(r.ownerId(), r.itemNo(), siteId);
            if (!item.receivable()) {
                throw ApiException.unprocessable("INV_ITEM_BLOCKED", "Item " + r.itemNo() + " is not receivable (status " + item.status() + ")");
            }
            String lot = normalise(r.lotNo());
            validateLot(item, lot, r.expiryDate(), true);
            BigDecimal qty = toBase(item, r.qty(), r.uom());
            LocationRef location = lockLocations(siteId, List.of(r.locationId())).get(r.locationId());
            checkPutaway(siteId, item, location, lot);
            String lpn = ensureLpn(siteId, r.lpnId(), r.ownerId(), location.locationId());
            StockStatus status = r.status() == null ? StockStatus.AVAILABLE : r.status();

            List<String> sn = serialsFor(item, qty, r.serials());
            requireNotInStock(item, sn);
            BalanceKey key = new BalanceKey(siteId, r.ownerId(), r.itemNo(), lot, lpn, location.locationId(), status);
            BigDecimal after = repo.increment(key, qty, r.expiryDate(), ctx.now);
            serials.place(key, sn, ctx.operationId, ctx.now);
            ctx.line(TxnType.RECEIPT, key, qty, after, sn);
            ctx.sourceDoc = r.sourceDoc();
            // Receipt postings to the ERP go through the Inbound service (IF-IB-002), not IF-INV-001.
        });
    }

    @Transactional
    public OperationResult move(String siteId, String idempotencyKey, MoveRequest r) {
        return idempotent(siteId, idempotencyKey, "MOVE", r, ctx -> {
            if (r.wholeLpn()) {
                moveLpn(siteId, r, ctx);
            } else {
                moveQuantity(siteId, r, ctx);
            }
        });
    }

    @Transactional
    public OperationResult adjust(String siteId, String idempotencyKey, AdjustRequest r) {
        return idempotent(siteId, idempotencyKey, "ADJUSTMENT", r, ctx -> {
            if (r.qtyDelta().signum() == 0) {
                throw ApiException.unprocessable("INV_ZERO_QTY", "qtyDelta must not be zero");
            }
            Reason reason = requireReason(r.reasonCode(), "ADJUSTMENT", r.approvedBy(), ctx);
            ItemRef item = requireItem(r.ownerId(), r.itemNo(), siteId);
            String lot = normalise(r.lotNo());
            boolean positive = r.qtyDelta().signum() > 0;
            validateLot(item, lot, r.expiryDate(), false);
            BigDecimal qty = toBase(item, r.qtyDelta().abs(), r.uom());
            LocationRef location = lockLocations(siteId, List.of(r.locationId())).get(r.locationId());
            StockStatus status = r.status() == null ? StockStatus.AVAILABLE : r.status();
            String lpn = positive ? ensureLpn(siteId, r.lpnId(), r.ownerId(), location.locationId())
                    : requireLpnAt(siteId, r.lpnId(), location.locationId());

            BalanceKey key = new BalanceKey(siteId, r.ownerId(), r.itemNo(), lot, lpn, location.locationId(), status);
            ctx.reasonCode = reason.code();
            ctx.approvedBy = r.approvedBy();
            List<String> sn = serialsFor(item, qty, r.serials());
            if (positive) {
                requireNotInStock(item, sn);
                ctx.line(TxnType.ADJUST_POS, key, qty, repo.increment(key, qty, r.expiryDate(), ctx.now), sn);
                serials.place(key, sn, ctx.operationId, ctx.now);
            } else {
                requireSerialsAt(key, sn);
                ctx.line(TxnType.ADJUST_NEG, key, qty.negate(), decrementOrFail(key, qty), sn);
                serials.remove(key, sn, ctx.operationId, ctx.now);
            }
            if (reason.erpRelevant()) {
                ctx.erp(positive ? ErpMovementType.ADJ_POS : ErpMovementType.ADJ_NEG, item, qty, lot, status,
                        location.erpBucket(), null, sn);
            }
        });
    }

    @Transactional
    public OperationResult changeStatus(String siteId, String idempotencyKey, StatusChangeRequest r) {
        return idempotent(siteId, idempotencyKey, "STATUS_CHANGE", r, ctx -> {
            if (r.fromStatus() == r.toStatus()) {
                throw ApiException.unprocessable("INV_STATUS_UNCHANGED", "fromStatus and toStatus are equal");
            }
            Reason reason = requireReason(r.reasonCode(), "STATUS_CHANGE", r.approvedBy(), ctx);
            ItemRef item = requireItem(r.ownerId(), r.itemNo(), siteId);
            String lot = normalise(r.lotNo());
            validateLot(item, lot, null, false);
            BigDecimal qty = toBase(item, r.qty(), r.uom());
            LocationRef location = lockLocations(siteId, List.of(r.locationId())).get(r.locationId());
            String lpn = requireLpnAt(siteId, r.lpnId(), location.locationId());

            BalanceKey from = new BalanceKey(siteId, r.ownerId(), r.itemNo(), lot, lpn, location.locationId(), r.fromStatus());
            Balance source = repo.find(from).orElseThrow(() -> noStock(from));
            BalanceKey to = from.withStatus(r.toStatus());
            ctx.reasonCode = reason.code();
            ctx.approvedBy = r.approvedBy();
            List<String> sn = serialsFor(item, qty, r.serials());
            requireSerialsAt(from, sn);
            ctx.line(TxnType.STATUS_OUT, from, qty.negate(), decrementOrFail(from, qty), sn);
            ctx.line(TxnType.STATUS_IN, to, qty, repo.increment(to, qty, source.expiryDate(), source.receiptDate()), sn);
            serials.transfer(sn, to, ctx.operationId, ctx.now);
            if (reason.erpRelevant()) {
                ErpMovementType.forStatusChange(r.fromStatus(), r.toStatus()).ifPresent(type ->
                        ctx.erp(type, item, qty, lot, r.fromStatus(), location.erpBucket(), null, sn));
            }
        });
    }

    // =====================================================================================================
    // Move variants
    // =====================================================================================================

    private void moveLpn(String siteId, MoveRequest r, OpContext ctx) {
        InventoryRepository.Lpn lpn = repo.lockLpn(siteId, r.lpnId())
                .orElseThrow(() -> ApiException.unprocessable("INV_LPN_UNKNOWN", "LPN " + r.lpnId() + " does not exist"));
        if (!lpn.locationId().equals(r.fromLocationId())) {
            throw ApiException.unprocessable("INV_LPN_LOCATION_MISMATCH",
                    "LPN " + lpn.lpnId() + " is at " + lpn.locationId() + ", not " + r.fromLocationId());
        }
        if (r.fromLocationId().equals(r.toLocationId())) {
            throw ApiException.unprocessable("INV_MOVE_SAME_LOCATION", "Source and target location are equal");
        }
        Map<String, LocationRef> locations = lockLocations(siteId, List.of(r.fromLocationId(), r.toLocationId()));
        LocationRef fromLoc = locations.get(r.fromLocationId());
        LocationRef toLoc = locations.get(r.toLocationId());
        List<Balance> contents = repo.lockLpnContents(siteId, lpn.lpnId());

        long distinctItems = contents.stream().map(b -> b.key().ownerId() + "/" + b.key().itemNo()).distinct().count();
        if (!toLoc.allowMixedItems() && distinctItems > 1) {
            throw ApiException.unprocessable("INV_LOCATION_MIXED_ITEMS",
                    "Location " + toLoc.locationId() + " does not allow mixed items; LPN holds " + distinctItems);
        }
        Map<String, ItemRef> items = new LinkedHashMap<>();
        for (Balance b : contents) {
            ItemRef item = items.computeIfAbsent(b.key().itemNo(), i -> requireItem(b.key().ownerId(), i, siteId));
            checkPutaway(siteId, item, toLoc, b.key().lotNo());
        }
        requireActive(toLoc);
        repo.moveLpn(siteId, lpn.lpnId(), toLoc.locationId());
        repo.relocateLpnBalances(siteId, lpn.lpnId(), toLoc.locationId());
        // Serials travel with the LPN; the ledger and the ERP movement list them per balance.
        Map<String, List<String>> moved = new LinkedHashMap<>();
        for (SerialRepository.MovedSerial m : serials.relocateLpn(siteId, lpn.lpnId(), toLoc.locationId(),
                ctx.operationId, ctx.now)) {
            moved.computeIfAbsent(m.balanceKey(), k -> new ArrayList<>()).add(m.serialNo());
        }
        for (Balance b : contents) {
            List<String> sn = moved.getOrDefault(SerialRepository.balanceKey(b.key().itemNo(), b.key().lotNo(),
                    b.key().status().name()), List.of());
            ctx.line(TxnType.MOVE_OUT, b.key(), b.qty().negate(), BigDecimal.ZERO, sn);
            ctx.line(TxnType.MOVE_IN, b.key().withLocation(toLoc.locationId(), lpn.lpnId()), b.qty(), b.qty(), sn);
            if (!fromLoc.erpBucket().equals(toLoc.erpBucket())) {
                ctx.erp(ErpMovementType.BUCKET_TRANSFER, items.get(b.key().itemNo()), b.qty(), b.key().lotNo(),
                        b.key().status(), fromLoc.erpBucket(), toLoc.erpBucket(), sn);
            }
        }
    }

    private void moveQuantity(String siteId, MoveRequest r, OpContext ctx) {
        if (blank(r.ownerId()) || blank(r.itemNo()) || r.qty() == null || blank(r.uom())) {
            throw ApiException.badRequest("INV_MOVE_FIELDS",
                    "ownerId, itemNo, qty and uom are required unless a whole LPN is moved");
        }
        ItemRef item = requireItem(r.ownerId(), r.itemNo(), siteId);
        String lot = normalise(r.lotNo());
        validateLot(item, lot, null, false);
        BigDecimal qty = toBase(item, r.qty(), r.uom());
        StockStatus status = r.status() == null ? StockStatus.AVAILABLE : r.status();
        Map<String, LocationRef> locations = lockLocations(siteId, List.of(r.fromLocationId(), r.toLocationId()));
        LocationRef fromLoc = locations.get(r.fromLocationId());
        LocationRef toLoc = locations.get(r.toLocationId());
        String fromLpn = requireLpnAt(siteId, r.lpnId(), fromLoc.locationId());
        String toLpn = ensureLpn(siteId, r.toLpnId(), r.ownerId(), toLoc.locationId());
        if (fromLoc.locationId().equals(toLoc.locationId()) && fromLpn.equals(toLpn)) {
            throw ApiException.unprocessable("INV_MOVE_SAME_LOCATION", "Source and target are identical");
        }
        checkPutaway(siteId, item, toLoc, lot);

        BalanceKey from = new BalanceKey(siteId, r.ownerId(), r.itemNo(), lot, fromLpn, fromLoc.locationId(), status);
        Balance source = repo.find(from).orElseThrow(() -> noStock(from));
        BalanceKey to = from.withLocation(toLoc.locationId(), toLpn);
        List<String> sn = serialsFor(item, qty, r.serials());
        requireSerialsAt(from, sn);
        ctx.line(TxnType.MOVE_OUT, from, qty.negate(), decrementOrFail(from, qty), sn);
        ctx.line(TxnType.MOVE_IN, to, qty, repo.increment(to, qty, source.expiryDate(), source.receiptDate()), sn);
        serials.transfer(sn, to, ctx.operationId, ctx.now);
        if (!fromLoc.erpBucket().equals(toLoc.erpBucket())) {
            ctx.erp(ErpMovementType.BUCKET_TRANSFER, item, qty, lot, status, fromLoc.erpBucket(), toLoc.erpBucket(), sn);
        }
    }

    // =====================================================================================================
    // Validation helpers
    // =====================================================================================================

    /**
     * Validates the serials of a command (INB-006): tracked items need exactly one distinct serial per base unit;
     * untracked items must not carry serials.
     */
    private static List<String> serialsFor(ItemRef item, BigDecimal baseQty, List<String> requested) {
        List<String> sn = requested == null ? List.of()
                : requested.stream().map(v -> v == null ? "" : v.trim()).toList();
        if (!item.serialTracked()) {
            if (!sn.isEmpty()) {
                throw ApiException.unprocessable("INV_SERIALS_NOT_ALLOWED",
                        "Item " + item.itemNo() + " is not serial-tracked in inventory");
            }
            return List.of();
        }
        if (sn.isEmpty()) {
            throw ApiException.unprocessable("INV_SERIALS_REQUIRED", "Item " + item.itemNo() + " requires serial numbers");
        }
        if (sn.stream().anyMatch(String::isEmpty)) {
            throw ApiException.unprocessable("INV_SERIAL_INVALID", "Serial numbers must not be blank");
        }
        if (sn.stream().distinct().count() != sn.size()) {
            throw ApiException.unprocessable("INV_SERIAL_DUPLICATE", "The request lists a serial number twice");
        }
        if (baseQty.stripTrailingZeros().scale() > 0 || baseQty.compareTo(BigDecimal.valueOf(sn.size())) != 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INV_SERIAL_COUNT_MISMATCH",
                    sn.size() + " serials for a quantity of " + baseQty.toPlainString() + " " + item.baseUom(),
                    Map.of("serialCount", sn.size(), "baseQty", baseQty));
        }
        return sn;
    }

    private void requireNotInStock(ItemRef item, List<String> sn) {
        if (sn.isEmpty()) {
            return;
        }
        List<String> duplicates = serials.inStock(item.ownerId(), item.itemNo(), sn);
        if (!duplicates.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INV_SERIAL_DUPLICATE",
                    "Serial numbers already in stock for " + item.itemNo(), Map.of("serials", duplicates));
        }
    }

    private void requireSerialsAt(BalanceKey key, List<String> sn) {
        if (sn.isEmpty()) {
            return;
        }
        List<String> missing = serials.missingAt(key, sn);
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INV_SERIAL_NOT_AT_SOURCE",
                    "Serial numbers are not in " + key.status() + " stock at " + key.locationId()
                            + (key.lpnId().isEmpty() ? "" : " / " + key.lpnId()), Map.of("serials", missing));
        }
    }

    private ItemRef requireItem(String ownerId, String itemNo, String siteId) {
        return refs.item(ownerId, itemNo, siteId).orElseThrow(() -> ApiException.unprocessable("INV_ITEM_UNKNOWN",
                "Item " + itemNo + " of owner " + ownerId + " is not known at site " + siteId));
    }

    private static void validateLot(ItemRef item, String lot, LocalDate expiry, boolean receipt) {
        if (item.lotControlled() && lot.isEmpty()) {
            throw ApiException.unprocessable("INV_LOT_REQUIRED", "Item " + item.itemNo() + " is lot-controlled");
        }
        if (!item.lotControlled() && !lot.isEmpty()) {
            throw ApiException.unprocessable("INV_LOT_NOT_ALLOWED", "Item " + item.itemNo() + " is not lot-controlled");
        }
        if (receipt && item.lotControlled() && item.shelfLifeDays() != null && expiry == null) {
            throw ApiException.unprocessable("INV_EXPIRY_REQUIRED", "Expiry date is required for shelf-life item " + item.itemNo());
        }
    }

    /** Hard putaway constraints (PUT-001, CCH-001, CCH-003) and location mixing rules. */
    private void checkPutaway(String siteId, ItemRef item, LocationRef loc, String lot) {
        requireActive(loc);
        if (item.temperatureClass() != null && !item.temperatureClass().equals(loc.temperatureClass())) {
            throw ApiException.unprocessable("INV_LOCATION_INCOMPATIBLE", "Item " + item.itemNo()
                    + " requires temperature class " + item.temperatureClass() + "; location " + loc.locationId()
                    + " is " + Objects.requireNonNullElse(loc.temperatureClass(), "unclassified"));
        }
        if (item.hazardous() && !loc.hazmatAllowed()) {
            throw ApiException.unprocessable("INV_LOCATION_INCOMPATIBLE",
                    "Hazardous item " + item.itemNo() + " cannot be stored in " + loc.locationId());
        }
        if (!loc.allowMixedItems() && repo.locationHoldsOtherItem(siteId, loc.locationId(), item.ownerId(), item.itemNo())) {
            throw ApiException.unprocessable("INV_LOCATION_MIXED_ITEMS",
                    "Location " + loc.locationId() + " already holds another item");
        }
        if (!loc.allowMixedLots() && repo.locationHoldsOtherLot(siteId, loc.locationId(), item.ownerId(), item.itemNo(), lot)) {
            throw ApiException.unprocessable("INV_LOCATION_MIXED_LOTS",
                    "Location " + loc.locationId() + " already holds another lot of " + item.itemNo());
        }
    }

    private static void requireActive(LocationRef loc) {
        if (!loc.active()) {
            throw ApiException.unprocessable("INV_LOCATION_BLOCKED", "Location " + loc.locationId() + " is " + loc.status());
        }
    }

    private Map<String, LocationRef> lockLocations(String siteId, List<String> ids) {
        Map<String, LocationRef> found = new LinkedHashMap<>();
        refs.lockLocations(siteId, ids).forEach(l -> found.put(l.locationId(), l));
        for (String id : ids) {
            if (!found.containsKey(id)) {
                throw ApiException.unprocessable("INV_LOCATION_UNKNOWN", "Location " + id + " is not known at site " + siteId);
            }
        }
        return found;
    }

    /** Creates the LPN at the location if new; an existing LPN must be at that location and of the same owner. */
    private String ensureLpn(String siteId, String lpnId, String ownerId, String locationId) {
        if (blank(lpnId)) {
            return "";
        }
        Optional<InventoryRepository.Lpn> existing = repo.lockLpn(siteId, lpnId);
        if (existing.isEmpty()) {
            repo.insertLpn(siteId, lpnId, ownerId, locationId);
            return lpnId;
        }
        InventoryRepository.Lpn lpn = existing.get();
        if (!lpn.ownerId().equals(ownerId)) {
            throw ApiException.unprocessable("INV_LPN_OWNER_MISMATCH",
                    "LPN " + lpnId + " belongs to owner " + lpn.ownerId() + "; mixed-owner LPNs are not allowed");
        }
        if (!lpn.locationId().equals(locationId)) {
            throw ApiException.unprocessable("INV_LPN_LOCATION_MISMATCH", "LPN " + lpnId + " is at " + lpn.locationId());
        }
        return lpnId;
    }

    private String requireLpnAt(String siteId, String lpnId, String locationId) {
        if (blank(lpnId)) {
            return "";
        }
        InventoryRepository.Lpn lpn = repo.lockLpn(siteId, lpnId)
                .orElseThrow(() -> ApiException.unprocessable("INV_LPN_UNKNOWN", "LPN " + lpnId + " does not exist"));
        if (!lpn.locationId().equals(locationId)) {
            throw ApiException.unprocessable("INV_LPN_LOCATION_MISMATCH", "LPN " + lpnId + " is at " + lpn.locationId());
        }
        return lpnId;
    }

    private Reason requireReason(String code, String kind, String approvedBy, OpContext ctx) {
        Reason reason = repo.reason(code)
                .orElseThrow(() -> ApiException.unprocessable("INV_REASON_UNKNOWN", "Reason code " + code + " is not defined"));
        if (!reason.appliesTo().equals(kind) && !reason.appliesTo().equals("ANY")) {
            throw ApiException.unprocessable("INV_REASON_NOT_APPLICABLE", "Reason " + code + " is not valid for " + kind);
        }
        if (reason.requiresApproval()) {
            if (blank(approvedBy)) {
                throw ApiException.unprocessable("INV_APPROVAL_REQUIRED", "Reason " + code + " requires approvedBy");
            }
            if (approvedBy.equals(ctx.userId)) {
                throw ApiException.unprocessable("INV_SELF_APPROVAL", "The requester cannot approve their own " + kind.toLowerCase());
            }
        }
        return reason;
    }

    private BigDecimal toBase(ItemRef item, BigDecimal qty, String uom) {
        if (uom.equals(item.baseUom())) {
            return checkScale(qty);
        }
        var conversion = refs.uom(item.ownerId(), item.itemNo(), uom).orElseThrow(() -> ApiException.unprocessable(
                "INV_UOM_UNKNOWN", "UoM " + uom + " is not defined for item " + item.itemNo()));
        BigDecimal base = qty.multiply(BigDecimal.valueOf(conversion.numerator()))
                .divide(BigDecimal.valueOf(conversion.denominator()), MathContext.DECIMAL128);
        return checkScale(base);
    }

    private static BigDecimal checkScale(BigDecimal qty) {
        BigDecimal stripped = com.astrawms.inventory.domain.Quantities.normalize(qty);
        if (stripped.scale() > 3) {
            throw ApiException.unprocessable("INV_UOM_FRACTION", "Quantity " + qty.toPlainString()
                    + " has more than 3 decimals in the base unit of measure");
        }
        return stripped;
    }

    private BigDecimal decrementOrFail(BalanceKey key, BigDecimal qty) {
        return repo.decrement(key, qty).orElseThrow(() -> {
            Balance b = repo.find(key).orElse(null);
            if (b == null) {
                return noStock(key);
            }
            if (b.qty().compareTo(qty) < 0) {
                return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INV_INSUFFICIENT_STOCK",
                        "Only " + b.qty().toPlainString() + " on hand for the requested stock",
                        Map.of("onHandQty", b.qty(), "availableQty", b.availableQty()));
            }
            return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INV_STOCK_ALLOCATED",
                    "Requested quantity is allocated to open orders; de-allocate first",
                    Map.of("onHandQty", b.qty(), "availableQty", b.availableQty()));
        });
    }

    private static ApiException noStock(BalanceKey k) {
        return ApiException.unprocessable("INV_NO_STOCK", "No " + k.status() + " stock of " + k.itemNo()
                + (k.lotNo().isEmpty() ? "" : " lot " + k.lotNo()) + " at " + k.locationId()
                + (k.lpnId().isEmpty() ? "" : " in LPN " + k.lpnId()));
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // =====================================================================================================
    // Idempotent execution, ledger and events
    // =====================================================================================================

    private final class OpContext {
        final UUID operationId;
        final String wmsTxnId;
        final String opType;
        final String siteId;
        final String userId;
        final String channel;
        final Instant now;
        final List<TxnLine> lines = new ArrayList<>();
        final Map<String, PendingMovement> movements = new LinkedHashMap<>();
        String reasonCode;
        String sourceDoc;
        String approvedBy;

        OpContext(UUID operationId, String wmsTxnId, String opType, String siteId, TenantContext.Scope scope, Instant now) {
            this.operationId = operationId;
            this.wmsTxnId = wmsTxnId;
            this.opType = opType;
            this.siteId = siteId;
            this.userId = scope.userId();
            this.channel = scope.channel();
            this.now = now;
        }

        void line(TxnType type, BalanceKey key, BigDecimal delta, BigDecimal after, List<String> serials) {
            lines.add(new TxnLine(type, key, delta, after, serials));
        }

        void erp(ErpMovementType type, ItemRef item, BigDecimal qty, String lot, StockStatus status,
                 String fromBucket, String toBucket, List<String> serials) {
            PendingMovement m = movements.computeIfAbsent(item.itemNo() + "|" + type,
                    k -> new PendingMovement(type, item.ownerId(), item.itemNo(), new ArrayList<>()));
            m.items().add(new GoodsMovement.Item(item.itemNo(), qty, item.baseUom(), fromBucket, toBucket,
                    lot.isEmpty() ? null : lot, null, serials.isEmpty() ? null : serials, status.erpStockType().name(),
                    sourceDoc != null ? sourceDoc : opType + " " + operationId));
        }
    }

    private record PendingMovement(ErpMovementType type, String ownerId, String itemNo, List<GoodsMovement.Item> items) {
    }

    private OperationResult idempotent(String siteId, String idempotencyKey, String opType, Object request,
                                       Consumer<OpContext> body) {
        if (blank(idempotencyKey) || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID",
                    "Header Idempotency-Key is required (1-" + MAX_IDEMPOTENCY_KEY + " characters)");
        }
        String hash = sha256(opType + "|" + siteId + "|" + json.writeValueAsString(request));
        Optional<StoredOperation> existing = repo.findOperation(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }
        TenantContext.Scope scope = TenantContext.require();
        OpContext ctx = new OpContext(UUID.randomUUID(), WmsTxnId.next(clock), opType, siteId, scope, clock.instant());
        if (!repo.insertOperation(ctx.operationId, siteId, idempotencyKey, hash, opType, ctx.wmsTxnId, ctx.userId,
                ctx.channel, ctx.now)) {
            // A concurrent request with the same key committed first.
            return replay(repo.findOperation(idempotencyKey).orElseThrow(), hash);
        }
        body.accept(ctx);
        OperationResult result = finish(ctx);
        repo.saveResponse(ctx.operationId, json.writeValueAsString(result));
        return result;
    }

    private OperationResult replay(StoredOperation op, String hash) {
        if (!op.requestHash().equals(hash)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used for a different request");
        }
        if (op.responseJson() == null) {
            throw ApiException.conflict("OPERATION_IN_PROGRESS", "The original request is still being processed");
        }
        return json.readValue(op.responseJson(), OperationResult.class).asReplay();
    }

    private OperationResult finish(OpContext ctx) {
        List<OperationResult.Line> resultLines = new ArrayList<>();
        Map<String, List<InventoryChanged.Line>> eventLines = new LinkedHashMap<>();
        Map<String, String> owners = new LinkedHashMap<>();
        for (TxnLine l : ctx.lines) {
            repo.insertTxn(ctx.operationId, l, ctx.reasonCode, ctx.sourceDoc, ctx.userId, ctx.channel, ctx.now);
            BalanceKey k = l.key();
            resultLines.add(new OperationResult.Line(l.type().name(), k.ownerId(), k.itemNo(), k.lotNo(), k.lpnId(),
                    k.locationId(), k.status(), l.qtyDelta(), l.qtyAfter()));
            String itemKey = k.ownerId() + "|" + k.itemNo();
            owners.put(itemKey, k.ownerId());
            eventLines.computeIfAbsent(itemKey, x -> new ArrayList<>()).add(new InventoryChanged.Line(
                    l.type().name(), k.lotNo(), k.lpnId(), k.locationId(), k.status().name(), l.qtyDelta(), l.qtyAfter()));
        }
        eventLines.forEach((itemKey, lines) -> {
            String owner = owners.get(itemKey);
            String itemNo = itemKey.substring(owner.length() + 1);
            outbox.append(new OutboxWriter.Message(topics.inventoryEvents(), InventoryChanged.TYPE, InventoryChanged.VERSION, null,
                    ctx.siteId, owner, ctx.siteId + ":" + itemNo,
                    new InventoryChanged(ctx.operationId, ctx.wmsTxnId, ctx.opType, owner, itemNo, lines, ctx.now)));
        });

        List<OperationResult.ErpMovement> erpMovements = new ArrayList<>();
        boolean first = true;
        for (PendingMovement m : ctx.movements.values()) {
            String txnId = first ? ctx.wmsTxnId : WmsTxnId.next(clock);
            first = false;
            repo.insertErpMovement(txnId, ctx.operationId, ctx.siteId, m.itemNo(), m.type().name(), ctx.now);
            outbox.append(new OutboxWriter.Message(topics.goodsMovements(), GoodsMovement.TYPE, GoodsMovement.VERSION, "ERP",
                    ctx.siteId, m.ownerId(), ctx.siteId + ":" + m.itemNo(),
                    new GoodsMovement(txnId, m.type().name(), ctx.reasonCode, ctx.now, ctx.approvedBy, m.items())));
            erpMovements.add(new OperationResult.ErpMovement(txnId, m.type().name(), m.itemNo()));
        }
        return new OperationResult(ctx.operationId, ctx.wmsTxnId, ctx.opType, resultLines, erpMovements, ctx.now, false);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
