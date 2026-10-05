package com.astrawms.inventory.service;

import com.astrawms.common.ids.WmsTxnId;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.common.security.AccessScope;
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
import com.astrawms.inventory.persistence.AllocationRepository;
import com.astrawms.inventory.persistence.AllocationRepository.Allocation;
import com.astrawms.inventory.api.AllocationDtos.AllocateRequest;
import com.astrawms.inventory.api.AllocationDtos.AllocationResult;
import com.astrawms.inventory.api.AllocationDtos.AllocationView;
import com.astrawms.inventory.api.AllocationDtos.IssueRequest;
import com.astrawms.inventory.api.AllocationDtos.IssueResult;
import com.astrawms.inventory.api.AllocationDtos.IssuedLine;
import com.astrawms.inventory.api.AllocationDtos.LotQty;
import com.astrawms.inventory.api.AllocationDtos.PickRequest;
import com.astrawms.inventory.api.AllocationDtos.ReleaseRequest;
import com.astrawms.inventory.api.AllocationDtos.ReturnRequest;
import com.astrawms.inventory.api.AllocationDtos.ReleaseResult;
import com.astrawms.inventory.api.AllocationDtos.Rotation;
import com.astrawms.inventory.reference.ReferenceData.ItemRef;
import com.astrawms.inventory.reference.ReferenceData.LocationRef;
import com.astrawms.inventory.reference.ReferenceRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.sql.Timestamp;
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
    private final AllocationRepository allocations;
    private final ReferenceRepository refs;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final Clock clock;
    private final Topics topics;
    private final CountRequests countRequests;
    private final Replenishments replenishments;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final AllocationPolicies policies;

    public InventoryCommandService(InventoryRepository repo, SerialRepository serials, AllocationRepository allocations,
                                   ReferenceRepository refs, OutboxWriter outbox, JsonMapper json, Clock clock,
                                   Topics topics, CountRequests countRequests, Replenishments replenishments,
                                   org.springframework.jdbc.core.simple.JdbcClient jdbc, AllocationPolicies policies) {
        this.policies = policies;
        this.repo = repo;
        this.serials = serials;
        this.allocations = allocations;
        this.refs = refs;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
        this.topics = topics;
        this.countRequests = countRequests;
        this.replenishments = replenishments;
        this.jdbc = jdbc;
    }

    // =====================================================================================================
    // Commands
    // =====================================================================================================

    @Transactional
    public OperationResult receive(String siteId, String idempotencyKey, ReceiveRequest r) {
        AccessScope.current().requireOwner(r.ownerId());
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
            receivedFromTransit(siteId, r.sourceDoc(), r.ownerId(), r.itemNo(), lot, qty, ctx.now);
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
    public OperationResult adjust(String siteId, String idempotencyKey, AdjustRequest r, AccessScope approver) {
        AccessScope.current().requireOwner(r.ownerId());
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
            checkApprover(reason, approver, siteId, item, qty);
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

    // =====================================================================================================
    // Material issue to a cost object and its return (ADR-0022)
    // =====================================================================================================

    /** One issue or return scan of a material issue document; {@code qty} in {@code uom}. */
    public record MaterialMove(String issueNo, String ownerId, String itemNo, String lotNo, BigDecimal qty, String uom,
                               String locationId, String lpnId, List<String> serials,
                               GoodsMovement.AccountAssignment account) {
    }

    /**
     * Takes AVAILABLE, unallocated stock out of the warehouse for consumption by the cost object; the ERP gets a goods
     * issue to it (SAP 201 / 221 / 261) with the account assignment.
     */
    @Transactional
    public OperationResult materialIssue(String siteId, String idempotencyKey, MaterialMove m) {
        AccessScope.current().requireOwner(m.ownerId());
        return idempotent(siteId, idempotencyKey, "MATERIAL_ISSUE", m, ctx -> {
            ItemRef item = requireItem(m.ownerId(), m.itemNo(), siteId);
            String lot = normalise(m.lotNo());
            validateLot(item, lot, null, false);
            BigDecimal qty = toBase(item, m.qty(), m.uom());
            LocationRef location = lockLocations(siteId, List.of(m.locationId())).get(m.locationId());
            String lpn = requireLpnAt(siteId, m.lpnId(), location.locationId());
            BalanceKey key = new BalanceKey(siteId, m.ownerId(), m.itemNo(), lot, lpn, location.locationId(),
                    StockStatus.AVAILABLE);
            ctx.reasonCode = "ISSUE";
            ctx.sourceDoc = "ISSUE " + m.issueNo();
            ctx.account = m.account();
            List<String> sn = serialsFor(item, qty, m.serials());
            requireSerialsAt(key, sn);
            ctx.line(TxnType.MATERIAL_ISSUE, key, qty.negate(), decrementOrFail(key, qty), sn);
            serials.remove(key, sn, ctx.operationId, ctx.now);
            ctx.erp(ErpMovementType.issueTo(m.account().objectType()), item, qty, lot, StockStatus.AVAILABLE,
                    location.erpBucket(), null, sn);
        });
    }

    /** Issued material comes back unused: AVAILABLE stock again, and the ERP reverses the consumption (202 / 222 / 262). */
    @Transactional
    public OperationResult materialReturn(String siteId, String idempotencyKey, MaterialMove m) {
        AccessScope.current().requireOwner(m.ownerId());
        return idempotent(siteId, idempotencyKey, "MATERIAL_RETURN", m, ctx -> {
            ItemRef item = requireItem(m.ownerId(), m.itemNo(), siteId);
            String lot = normalise(m.lotNo());
            validateLot(item, lot, null, false);
            BigDecimal qty = toBase(item, m.qty(), m.uom());
            LocationRef location = lockLocations(siteId, List.of(m.locationId())).get(m.locationId());
            checkPutaway(siteId, item, location, lot);
            String lpn = ensureLpn(siteId, m.lpnId(), m.ownerId(), location.locationId());
            BalanceKey key = new BalanceKey(siteId, m.ownerId(), m.itemNo(), lot, lpn, location.locationId(),
                    StockStatus.AVAILABLE);
            ctx.reasonCode = "ISSUE_RETURN";
            ctx.sourceDoc = "ISSUE " + m.issueNo();
            ctx.account = m.account();
            List<String> sn = serialsFor(item, qty, m.serials());
            requireNotInStock(item, sn);
            ctx.line(TxnType.MATERIAL_RETURN, key, qty, repo.increment(key, qty, null, ctx.now), sn);
            serials.place(key, sn, ctx.operationId, ctx.now);
            ctx.erp(ErpMovementType.returnFrom(m.account().objectType()), item, qty, lot, StockStatus.AVAILABLE,
                    location.erpBucket(), null, sn);
        });
    }

    @Transactional
    public OperationResult changeStatus(String siteId, String idempotencyKey, StatusChangeRequest r,
                                        AccessScope approver) {
        AccessScope.current().requireOwner(r.ownerId());
        return idempotent(siteId, idempotencyKey, "STATUS_CHANGE", r, ctx -> {
            if (r.fromStatus() == r.toStatus()) {
                throw ApiException.unprocessable("INV_STATUS_UNCHANGED", "fromStatus and toStatus are equal");
            }
            Reason reason = requireReason(r.reasonCode(), "STATUS_CHANGE", r.approvedBy(), ctx);
            ItemRef item = requireItem(r.ownerId(), r.itemNo(), siteId);
            String lot = normalise(r.lotNo());
            validateLot(item, lot, null, false);
            BigDecimal qty = toBase(item, r.qty(), r.uom());
            checkApprover(reason, approver, siteId, item, qty);
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
    // Allocation, pick, issue (scope §3.4, §4, §5)
    // =====================================================================================================

    /**
     * Reserves stock for an order line, never from staging, receiving, returns, shipping or QC locations (PUT-006,
     * ADR-0019). The site's {@link AllocationPolicies allocation policy} (ADR-0020) sets the rotation (by default FEFO
     * for lot-controlled items, otherwise FIFO, unless the request names one) and the full-LPN rule. Default steps,
     * each in rotation order:
     * <ol>
     *   <li>the item's pick faces;</li>
     *   <li>reserve: loose stock, and full LPNs that the remaining quantity covers;</li>
     *   <li>reserve LPNs broken into, but only for items without a pick face. Items with a face get the face
     *       replenished instead, and the shortfall is allocated when that stock arrives (backorder recovery).</li>
     * </ol>
     * Allocating from a face can take it below its minimum: it is replenished at once (demand replenishment), with a
     * task priority above the pick. A shortfall is returned as {@code shortQty} (OUT-EX-01).
     */
    @Transactional
    public AllocationResult allocate(String siteId, String idempotencyKey, AllocateRequest r) {
        AccessScope.current().requireOwner(r.ownerId());
        return idempotentValue(siteId, idempotencyKey, "ALLOCATE", r, AllocationResult.class, ctx -> {
            ItemRef item = requireItem(r.ownerId(), r.itemNo(), siteId);
            BigDecimal wanted = toBase(item, r.qty(), r.uom());
            String lot = blank(r.lotNo()) ? null : r.lotNo().trim();
            AllocationPolicies.Policy policy = policies.of(siteId, r.ownerId());
            boolean fefo = r.rotation() == null ? policy.fefo(item.lotControlled()) : r.rotation() == Rotation.FEFO;
            boolean faceFirst = policy.pickFaceFirst();
            AllocationPolicies.FullLpn fullLpn = policy.fullLpn();
            boolean hasFace = replenishments.hasPickFace(siteId, r.ownerId(), r.itemNo());
            List<AllocationRepository.Candidate> candidates = allocations.candidates(siteId, r.ownerId(), r.itemNo(), lot,
                    r.minExpiryDate(), fefo, r.excludeLocationIds());
            if (policy.lotAffinity() && item.lotControlled() && lot == null) {
                candidates = singleLot(candidates, wanted);
            }
            Map<AllocationRepository.Candidate, BigDecimal> free = new LinkedHashMap<>();
            candidates.forEach(c -> free.put(c, c.free()));
            BigDecimal remaining = wanted;
            List<AllocationView> views = new ArrayList<>();
            for (int step = 1; step <= 3 && remaining.signum() > 0; step++) {
                for (AllocationRepository.Candidate c : candidates) {
                    BigDecimal left = free.get(c);
                    if (remaining.signum() == 0) {
                        break;
                    }
                    if (left.signum() == 0) {
                        continue;
                    }
                    boolean loose = c.key().lpnId().isEmpty();
                    boolean face = c.face() && faceFirst;
                    BigDecimal take = switch (step) {
                        case 1 -> face ? left.min(remaining) : BigDecimal.ZERO;
                        case 2 -> face ? BigDecimal.ZERO
                                : loose || c.face() || fullLpn == AllocationPolicies.FullLpn.SPLIT_ALLOWED ? left.min(remaining)
                                : c.wholeLpn() && left.compareTo(remaining) <= 0 ? left : BigDecimal.ZERO;
                        default -> face || fullLpn == AllocationPolicies.FullLpn.NEVER_SPLIT
                                || (fullLpn == AllocationPolicies.FullLpn.COVERED_ONLY && hasFace)
                                ? BigDecimal.ZERO : left.min(remaining);
                    };
                    if (take.signum() == 0) {
                        continue;
                    }
                    allocations.reserve(c.key(), take);
                    UUID id = UUID.randomUUID();
                    allocations.insert(id, c.key(), r.orderRef(), r.orderLineRef(), take, ctx.now);
                    views.add(new AllocationView(id, c.key().locationId(), c.key().lpnId(), c.key().lotNo(), take, c.expiry()));
                    free.put(c, left.subtract(take));
                    remaining = remaining.subtract(take);
                }
            }
            if (hasFace) {
                replenishments.onDemand(siteId, r.ownerId(), r.itemNo());
            }
            String[] why = remaining.signum() == 0 ? new String[] {null, null}
                    : explainShort(siteId, r, item, remaining, free.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add),
                    hasFace, fullLpn);
            return new AllocationResult(r.orderRef(), r.orderLineRef(), r.itemNo(), item.baseUom(), wanted,
                    wanted.subtract(remaining), remaining, views, false, why[0], why[1],
                    rule(policy, fefo, r.rotation() != null, item.lotControlled()));
        });
    }

    /** The allocation rule that fired, in words, recorded on the order line (ADR-0025). */
    static String rule(AllocationPolicies.Policy policy, boolean fefo, boolean requested, boolean lotControlled) {
        StringBuilder s = new StringBuilder(fefo ? "FEFO" : "FIFO");
        s.append(requested ? " (requested by the order)" : lotControlled ? " (lot-controlled)" : "");
        s.append(policy.ownerId() != null ? " · owner " + policy.ownerId() + " policy"
                : policy.updatedBy() == null ? " · built-in default (site policy not saved)" : " · site policy");
        if (policy.pickFaceFirst()) {
            s.append(" · pick face first");
        }
        s.append(switch (policy.fullLpn()) {
            case COVERED_ONLY -> " · whole pallet only when the order covers it";
            case SPLIT_ALLOWED -> " · pallets split freely";
            case NEVER_SPLIT -> " · whole pallets only";
        });
        if (policy.lotAffinity() && lotControlled) {
            s.append(" · one lot per line");
        }
        return s.toString();
    }

    /**
     * Lot affinity (ADR-0021): only the candidates of the first lot, in rotation order, whose free stock covers the
     * whole line; all candidates (lots mixed) when no single lot can.
     */
    private static List<AllocationRepository.Candidate> singleLot(List<AllocationRepository.Candidate> candidates,
                                                                  BigDecimal wanted) {
        Map<String, BigDecimal> byLot = new LinkedHashMap<>();
        candidates.forEach(c -> byLot.merge(c.key().lotNo(), c.free(), BigDecimal::add));
        return byLot.entrySet().stream().filter(e -> e.getValue().compareTo(wanted) >= 0).findFirst()
                .map(e -> candidates.stream().filter(c -> c.key().lotNo().equals(e.getKey())).toList())
                .orElse(candidates);
    }

    /**
     * Why an allocation came up short (ADR-0021), most actionable cause first: stock the policy held back (the face is
     * being replenished, or pallets are not split), stock still on its way to storage, a requested lot or expiry not
     * in stock, stock held by other orders, stock in a status that is not allocable, or none at all.
     */
    private String[] explainShort(String siteId, AllocateRequest r, ItemRef item, BigDecimal remaining, BigDecimal heldBack,
                                  boolean hasFace, AllocationPolicies.FullLpn fullLpn) {
        String unit = " " + item.baseUom();
        if (heldBack.signum() > 0) {
            return hasFace && fullLpn != AllocationPolicies.FullLpn.NEVER_SPLIT
                    ? new String[] {"WAITING_FOR_REPLENISHMENT", heldBack.toPlainString() + unit
                            + " are in reserve pallets; " + replenishments.faceSummary(siteId, r.ownerId(), r.itemNo())
                            + "; the line is allocated when the face is replenished"}
                    : new String[] {"POLICY_NO_SPLIT", heldBack.toPlainString() + unit
                            + " are in reserve pallets larger than the open quantity; the site's policy does not split pallets"};
        }
        AllocationRepository.ShortFacts f = allocations.shortFacts(siteId, r.ownerId(), r.itemNo(),
                blank(r.lotNo()) ? null : r.lotNo().trim());
        if (f.awaitingPutaway().signum() > 0) {
            return new String[] {"AWAITING_PUTAWAY", f.awaitingPutaway().toPlainString() + unit + " at " + f.awaitingAt()
                    + " wait for putaway; the line is allocated when they reach storage"};
        }
        if (f.otherLots().signum() > 0 || r.minExpiryDate() != null) {
            return new String[] {"LOT_UNAVAILABLE", "No stock of the requested lot"
                    + (r.minExpiryDate() == null ? "" : " / minimum expiry " + r.minExpiryDate())
                    + (f.otherLots().signum() > 0 ? "; " + f.otherLots().toPlainString() + unit + " of other lots" : "")};
        }
        if (f.allocatedElsewhere().signum() > 0) {
            return new String[] {"ALLOCATED_ELSEWHERE", f.allocatedElsewhere().toPlainString() + unit
                    + " in storage are allocated to other orders"};
        }
        if (f.notAvailable().signum() > 0) {
            return new String[] {"NOT_AVAILABLE", f.notAvailable().toPlainString() + unit + " in status " + f.statuses()
                    + " cannot be allocated"};
        }
        return new String[] {"NO_STOCK", "No stock of " + r.itemNo() + " at " + siteId};
    }

    /**
     * Picks (part of) an allocation into outbound staging. The stock stays allocated at the destination so it can
     * never be re-allocated; serials must be at the source (INB-006); a short close releases the remainder (PCK-003).
     */
    @Transactional
    public OperationResult pick(String siteId, UUID allocationId, String idempotencyKey, PickRequest r) {
        return idempotent(siteId, idempotencyKey, "PICK", java.util.Map.of("allocation", allocationId, "request", r), ctx -> {
            Allocation a = allocations.lock(siteId, allocationId).orElseThrow(() ->
                    ApiException.notFound("INV_ALLOCATION_UNKNOWN", "Allocation " + allocationId + " not found"));
            AccessScope.current().requireOwner(a.ownerId());
            if (!"OPEN".equals(a.status())) {
                throw ApiException.unprocessable("INV_ALLOCATION_NOT_OPEN", "Allocation is " + a.status());
            }
            BigDecimal qty = checkScale(r.qty());
            if (qty.compareTo(a.open()) > 0) {
                throw ApiException.unprocessable("INV_PICK_QTY_EXCEEDS",
                        "Pick of " + qty.toPlainString() + " exceeds the open allocation of " + a.open().toPlainString());
            }
            if (qty.signum() == 0 && !r.shortClose()) {
                throw ApiException.unprocessable("INV_ZERO_QTY", "A zero pick is only allowed with shortClose");
            }
            ItemRef item = requireItem(a.ownerId(), a.itemNo(), siteId);
            List<String> sn = qty.signum() == 0 ? List.of() : serialsFor(item, qty, r.serials());
            BalanceKey from = a.sourceKey();
            requireSerialsAt(from, sn);
            Map<String, LocationRef> locs = lockLocations(siteId, List.of(from.locationId(), r.toLocationId()));
            LocationRef fromLoc = locs.get(from.locationId());
            LocationRef toLoc = locs.get(r.toLocationId());
            requireActive(toLoc);
            String toLpn = ensureLpn(siteId, r.toLpnId(), a.ownerId(), toLoc.locationId());
            if (a.pickedLocation() != null && (!a.pickedLocation().equals(toLoc.locationId()) || !a.pickedLpn().equals(toLpn))) {
                throw ApiException.unprocessable("INV_PICK_DESTINATION_CHANGED",
                        "Earlier picks of this allocation went to " + a.pickedLocation() + " / " + a.pickedLpn());
            }
            BigDecimal picked = a.qtyPicked().add(qty);
            if (qty.signum() > 0) {
                Balance source = repo.find(from).orElseThrow(() -> noStock(from));
                BigDecimal after = allocations.takeAllocated(from, qty).orElseThrow(() ->
                        ApiException.conflict("INV_ALLOCATION_INCONSISTENT", "Allocated stock no longer at " + from.locationId()));
                ctx.line(TxnType.PICK_OUT, from, qty.negate(), after, sn);
                BalanceKey to = from.withLocation(toLoc.locationId(), toLpn);
                ctx.line(TxnType.PICK_IN, to, qty, allocations.putAllocated(to, qty, source.expiryDate(), source.receiptDate()), sn);
                serials.transfer(sn, to, ctx.operationId, ctx.now);
                if (!fromLoc.erpBucket().equals(toLoc.erpBucket())) {
                    ctx.erp(ErpMovementType.BUCKET_TRANSFER, item, qty, from.lotNo(), StockStatus.AVAILABLE,
                            fromLoc.erpBucket(), toLoc.erpBucket(), sn);
                }
            }
            BigDecimal allocated = a.qtyAllocated();
            if (r.shortClose() && allocated.compareTo(picked) > 0) {
                allocations.unreserve(from, allocated.subtract(picked));
                allocated = picked;
                // PCK-003 (b): the location came up short, so its stock record is suspect: count it.
                countRequests.open(siteId, from.locationId(), "SHORT_PICK",
                        "Short pick of " + a.itemNo() + " for " + a.orderRef() + "/" + a.orderLineRef());
            }
            String status = picked.signum() == 0 ? "RELEASED" : picked.compareTo(allocated) == 0 ? "PICKED" : "OPEN";
            allocations.recordPick(a.id(), picked, allocated, picked.signum() == 0 ? null : toLoc.locationId(),
                    picked.signum() == 0 ? null : toLpn, status, ctx.now);
            ctx.sourceDoc = a.orderRef() + "/" + a.orderLineRef();
        });
    }

    /** Issues all picked allocations of an order at shipment; serials leave stock as SHIPPED (SHP-007). */
    @Transactional
    public IssueResult issue(String siteId, String idempotencyKey, IssueRequest r) {
        return idempotentValue(siteId, idempotencyKey, "ISSUE", r, IssueResult.class, ctx -> {
            List<Allocation> all = allocations.lockByOrder(siteId, r.orderRef());
            all.forEach(a -> AccessScope.current().requireOwner(a.ownerId()));
            if (all.stream().anyMatch(a -> "OPEN".equals(a.status()))) {
                throw ApiException.unprocessable("INV_ALLOCATIONS_OPEN",
                        "Order " + r.orderRef() + " still has open allocations; pick or release them first");
            }
            List<Allocation> picked = all.stream().filter(a -> "PICKED".equals(a.status())).toList();
            if (picked.isEmpty()) {
                throw ApiException.unprocessable("INV_NOTHING_TO_ISSUE", "Order " + r.orderRef() + " has no picked stock");
            }
            Map<BalanceKey, java.util.Deque<String>> serialPool = new java.util.HashMap<>();
            Map<String, IssuedLine> lines = new LinkedHashMap<>();
            for (Allocation a : picked) {
                ItemRef item = requireItem(a.ownerId(), a.itemNo(), siteId);
                BalanceKey key = a.pickedKey();
                List<String> sn = new ArrayList<>();
                if (item.serialTracked()) {
                    java.util.Deque<String> pool = serialPool.computeIfAbsent(key, k -> new java.util.ArrayDeque<>(serials.atKey(k)));
                    for (int i = 0; i < a.qtyPicked().intValue() && !pool.isEmpty(); i++) {
                        sn.add(pool.poll());
                    }
                }
                BigDecimal after = allocations.takeAllocated(key, a.qtyPicked()).orElseThrow(() ->
                        ApiException.conflict("INV_ALLOCATION_INCONSISTENT", "Picked stock no longer at " + key.locationId()));
                ctx.line(TxnType.ISSUE, key, a.qtyPicked().negate(), after, sn);
                serials.ship(a.ownerId(), a.itemNo(), sn, ctx.operationId, ctx.now);
                allocations.setStatus(a.id(), "ISSUED", ctx.now);
                IssuedLine prev = lines.get(a.orderLineRef());
                List<LotQty> lots = new ArrayList<>(prev == null ? List.of() : prev.lots());
                if (!a.lotNo().isEmpty()) {
                    lots.add(new LotQty(a.lotNo(), a.qtyPicked()));
                }
                List<String> allSerials = new ArrayList<>(prev == null ? List.of() : prev.serials());
                allSerials.addAll(sn);
                lines.put(a.orderLineRef(), new IssuedLine(a.orderLineRef(), a.itemNo(),
                        (prev == null ? BigDecimal.ZERO : prev.qty()).add(a.qtyPicked()), item.baseUom(), lots, allSerials));
            }
            ctx.sourceDoc = r.orderRef();
            if (r.transferToSite() != null && !r.transferToSite().isBlank()) {
                for (Allocation a : picked) {
                    inTransit(r.orderRef(), siteId, r.transferToSite().trim().toUpperCase(), a, ctx);
                }
            }
            return new IssueResult(r.orderRef(), new ArrayList<>(lines.values()), false);
        });
    }

    /**
     * Releases the open (unpicked) quantity of an order's allocations, e.g. on cancellation (OUT-EX-02). Picked stock
     * stays allocated at outbound staging until it is returned to stock ({@link #returnToStock}).
     */
    @Transactional
    public ReleaseResult release(String siteId, String idempotencyKey, ReleaseRequest r) {
        return idempotentValue(siteId, idempotencyKey, "RELEASE", r, ReleaseResult.class, ctx -> {
            int count = 0;
            BigDecimal qty = BigDecimal.ZERO;
            for (Allocation a : allocations.lockByOrder(siteId, r.orderRef())) {
                AccessScope.current().requireOwner(a.ownerId());
                if (!"OPEN".equals(a.status())) {
                    continue;
                }
                allocations.unreserve(a.sourceKey(), a.open());
                if (a.qtyPicked().signum() > 0) {
                    allocations.recordPick(a.id(), a.qtyPicked(), a.qtyPicked(), a.pickedLocation(), a.pickedLpn(),
                            "PICKED", ctx.now);
                } else {
                    allocations.setStatus(a.id(), "RELEASED", ctx.now);
                }
                count++;
                qty = qty.add(a.open());
            }
            return new ReleaseResult(r.orderRef(), count, qty, false);
        });
    }

    /**
     * Reverse pick (OUT-EX-02): moves the picked stock of an allocation from outbound staging back into stock, where
     * it is unallocated and allocable again. Default destination: the location and LPN it was picked from.
     */
    @Transactional
    public OperationResult returnToStock(String siteId, UUID allocationId, String idempotencyKey, ReturnRequest r) {
        return idempotent(siteId, idempotencyKey, "RETURN", java.util.Map.of("allocation", allocationId, "request", r), ctx -> {
            Allocation a = allocations.lock(siteId, allocationId).orElseThrow(() ->
                    ApiException.notFound("INV_ALLOCATION_UNKNOWN", "Allocation " + allocationId + " not found"));
            AccessScope.current().requireOwner(a.ownerId());
            if (!"PICKED".equals(a.status())) {
                throw ApiException.unprocessable("INV_ALLOCATION_NOT_PICKED",
                        "Only picked allocations can be returned to stock; allocation is " + a.status());
            }
            ItemRef item = requireItem(a.ownerId(), a.itemNo(), siteId);
            String toLocation = blank(r == null ? null : r.toLocationId()) ? a.locationId() : r.toLocationId();
            String requestedLpn = r == null || blank(r.toLpnId())
                    ? (toLocation.equals(a.locationId()) ? a.lpnId() : null) : r.toLpnId();
            BalanceKey from = a.pickedKey();
            Map<String, LocationRef> locs = lockLocations(siteId, List.of(from.locationId(), toLocation));
            LocationRef fromLoc = locs.get(from.locationId());
            LocationRef toLoc = locs.get(toLocation);
            requireActive(toLoc);
            checkPutaway(siteId, item, toLoc, from.lotNo());
            String toLpn = ensureLpn(siteId, requestedLpn, a.ownerId(), toLoc.locationId());
            BigDecimal qty = a.qtyPicked();
            List<String> sn = item.serialTracked()
                    ? serials.atKey(from).stream().limit(qty.longValue()).toList() : List.of();
            Balance source = repo.find(from).orElseThrow(() -> noStock(from));
            BigDecimal after = allocations.takeAllocated(from, qty).orElseThrow(() ->
                    ApiException.conflict("INV_ALLOCATION_INCONSISTENT", "Picked stock no longer at " + from.locationId()));
            ctx.line(TxnType.RETURN_OUT, from, qty.negate(), after, sn);
            BalanceKey to = from.withLocation(toLoc.locationId(), toLpn);
            ctx.line(TxnType.RETURN_IN, to, qty, repo.increment(to, qty, source.expiryDate(), source.receiptDate()), sn);
            serials.transfer(sn, to, ctx.operationId, ctx.now);
            if (!fromLoc.erpBucket().equals(toLoc.erpBucket())) {
                ctx.erp(ErpMovementType.BUCKET_TRANSFER, item, qty, from.lotNo(), StockStatus.AVAILABLE,
                        fromLoc.erpBucket(), toLoc.erpBucket(), sn);
            }
            allocations.setStatus(a.id(), "RETURNED", ctx.now);
            ctx.sourceDoc = a.orderRef() + "/" + a.orderLineRef();
        });
    }

    // =====================================================================================================
    // Move variants
    // =====================================================================================================

    private void moveLpn(String siteId, MoveRequest r, OpContext ctx) {
        InventoryRepository.Lpn lpn = repo.lockLpn(siteId, r.lpnId())
                .orElseThrow(() -> ApiException.unprocessable("INV_LPN_UNKNOWN", "LPN " + r.lpnId() + " does not exist"));
        AccessScope.current().requireOwner(lpn.ownerId());
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
        if (blank(r.ownerId()) || blank(r.itemNo()) || r.qty() == null) {
            throw ApiException.badRequest("INV_MOVE_FIELDS",
                    "ownerId, itemNo and qty are required unless a whole LPN is moved");
        }
        AccessScope.current().requireOwner(r.ownerId());
        ItemRef item = requireItem(r.ownerId(), r.itemNo(), siteId);
        String lot = normalise(r.lotNo());
        validateLot(item, lot, null, false);
        BigDecimal qty = toBase(item, r.qty(), blank(r.uom()) ? item.baseUom() : r.uom());   // no unit: base unit
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
        return lockLocations(siteId, ids, false);
    }

    /**
     * Locks the locations of a stock movement. A location frozen by a physical inventory in progress (ADR-0022) takes
     * no movement, except the count postings themselves ({@code countPosting}).
     */
    private Map<String, LocationRef> lockLocations(String siteId, List<String> ids, boolean countPosting) {
        if (!countPosting) {
            List<String> frozen = jdbc.sql("""
                            select f.location_id || ' (' || p.pi_no || ')' from location_freeze f
                            join physical_inventory p on p.id = f.pi_id
                            where f.site_id = :site and f.location_id in (:ids)""")
                    .param("site", siteId).param("ids", ids).query(String.class).list();
            if (!frozen.isEmpty()) {
                throw ApiException.unprocessable("INV_LOCATION_FROZEN",
                        "Frozen for physical inventory: " + String.join(", ", frozen));
            }
        }
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

    /**
     * Completes a replenishment (§7.2 step 4): the reserved stock leaves the reserve location and arrives unallocated
     * at the forward location. Idempotent per key (the task service sends {@code TSK-<taskId>}).
     */
    @Transactional
    public OperationResult confirmReplenishment(String siteId, UUID replenishmentId, String idempotencyKey) {
        return idempotent(siteId, idempotencyKey, "REPLENISH", Map.of("replenishment", replenishmentId), ctx -> {
            record Repl(String status, String location, UUID allocation) {
            }
            Repl r = jdbc.sql("select status, location_id, allocation_id from replenishment where site_id = :site and id = :id for update")
                    .param("site", siteId).param("id", replenishmentId)
                    .query((rs, n) -> new Repl(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class)))
                    .optional().orElseThrow(() -> ApiException.notFound("INV_REPLEN_UNKNOWN", "Replenishment " + replenishmentId + " not found"));
            if (!"OPEN".equals(r.status())) {
                throw ApiException.unprocessable("INV_REPLEN_NOT_OPEN", "Replenishment is " + r.status());
            }
            Allocation a = allocations.lock(siteId, r.allocation()).orElseThrow();
            AccessScope.current().requireOwner(a.ownerId());
            ItemRef item = requireItem(a.ownerId(), a.itemNo(), siteId);
            BalanceKey from = a.sourceKey();
            Map<String, LocationRef> locs = lockLocations(siteId, List.of(from.locationId(), r.location()));
            LocationRef fromLoc = locs.get(from.locationId());
            LocationRef toLoc = locs.get(r.location());
            requireActive(toLoc);
            BigDecimal qty = a.open();
            List<String> sn = item.serialTracked() ? serials.atKey(from).stream().limit(qty.longValue()).toList() : List.of();
            Balance source = repo.find(from).orElseThrow(() -> noStock(from));
            BigDecimal after = allocations.takeAllocated(from, qty).orElseThrow(() ->
                    ApiException.conflict("INV_ALLOCATION_INCONSISTENT", "Reserved stock no longer at " + from.locationId()));
            ctx.line(TxnType.REPLEN_OUT, from, qty.negate(), after, sn);
            BalanceKey to = from.withLocation(toLoc.locationId(), "");
            ctx.line(TxnType.REPLEN_IN, to, qty, repo.increment(to, qty, source.expiryDate(), source.receiptDate()), sn);
            serials.transfer(sn, to, ctx.operationId, ctx.now);
            if (!fromLoc.erpBucket().equals(toLoc.erpBucket())) {
                ctx.erp(ErpMovementType.BUCKET_TRANSFER, item, qty, from.lotNo(), StockStatus.AVAILABLE,
                        fromLoc.erpBucket(), toLoc.erpBucket(), sn);
            }
            allocations.recordPick(a.id(), a.qtyAllocated(), a.qtyAllocated(), toLoc.locationId(), "", "REPLENISHED", ctx.now);
            jdbc.sql("update replenishment set status = 'DONE', operation_id = :op, completed_at = :now where id = :id")
                    .param("op", ctx.operationId).param("now", java.sql.Timestamp.from(ctx.now)).param("id", replenishmentId)
                    .update();
            ctx.sourceDoc = "REPL " + replenishmentId;
        });
    }

    /** One balance correction from a cycle count: {@code delta} = counted − system quantity (AVAILABLE stock). */
    public record CountAdjustment(String ownerId, String itemNo, String lotNo, String lpnId, BigDecimal delta) {
    }

    /**
     * Posts the variance of a cycle count as adjustments at the counted location (reason CC_TOL when auto-accepted
     * within tolerance, CC_VAR when approved), with ERP goods movements where the reason is ERP-relevant.
     */
    @Transactional
    public OperationResult applyCountVariance(String siteId, String locationId, String idempotencyKey, UUID countId,
                                              String reasonCode, String approvedBy, List<CountAdjustment> adjustments) {
        return idempotent(siteId, idempotencyKey, "COUNT_ADJUST", Map.of("count", countId, "reason", reasonCode), ctx -> {
            Reason reason = repo.reason(reasonCode).orElseThrow(() ->
                    ApiException.unprocessable("INV_REASON_UNKNOWN", "Reason code " + reasonCode + " is not defined"));
            ctx.reasonCode = reason.code();
            ctx.approvedBy = approvedBy;
            ctx.sourceDoc = "COUNT " + countId;
            LocationRef location = lockLocations(siteId, List.of(locationId), true).get(locationId);
            for (CountAdjustment a : adjustments) {
                ItemRef item = requireItem(a.ownerId(), a.itemNo(), siteId);
                if (item.serialTracked()) {
                    throw ApiException.unprocessable("INV_COUNT_SERIALS",
                            "Item " + a.itemNo() + " is serial-tracked: adjust it with its serial numbers");
                }
                String lot = normalise(a.lotNo());
                BigDecimal qty = a.delta().abs();
                boolean positive = a.delta().signum() > 0;
                String lpn = positive ? ensureLpn(siteId, blank(a.lpnId()) ? null : a.lpnId(), a.ownerId(), locationId)
                        : normalise(a.lpnId());
                BalanceKey key = new BalanceKey(siteId, a.ownerId(), a.itemNo(), lot, lpn, locationId, StockStatus.AVAILABLE);
                if (positive) {
                    ctx.line(TxnType.ADJUST_POS, key, qty, repo.increment(key, qty, null, ctx.now), List.of());
                } else {
                    ctx.line(TxnType.ADJUST_NEG, key, qty.negate(), decrementOrFail(key, qty), List.of());
                }
                if (reason.erpRelevant()) {
                    ctx.erp(positive ? ErpMovementType.ADJ_POS : ErpMovementType.ADJ_NEG, item, qty, lot,
                            StockStatus.AVAILABLE, location.erpBucket(), null, List.of());
                }
            }
        });
    }

    /**
     * Approvals beyond the approver role (§G.5.1): the approver must be authorised for the site and owner, and the
     * value (standard cost × base quantity) must be within the approver's limit. {@code approver} is null when the
     * reason needs no approval or no approver was given (then {@link #requireReason} has already decided).
     */
    private static void checkApprover(Reason reason, AccessScope approver, String siteId, ItemRef item, BigDecimal qty) {
        if (!reason.requiresApproval() || approver == null) {
            return;
        }
        if (!approver.allowsSite(siteId) || !approver.allowsOwner(item.ownerId())) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "APPROVER_SCOPE_DENIED",
                    "The approver is not authorised for site " + siteId + " / owner " + item.ownerId());
        }
        BigDecimal limit = approver.approvalLimit();
        if (limit == null) {
            return;
        }
        if (item.standardCost() == null) {
            throw ApiException.unprocessable("APPROVAL_VALUE_UNKNOWN",
                    "Item " + item.itemNo() + " has no standard cost, so the approver's value limit cannot be checked");
        }
        BigDecimal value = item.standardCost().multiply(qty).setScale(2, java.math.RoundingMode.HALF_UP);
        if (value.compareTo(limit) > 0) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "APPROVAL_LIMIT_EXCEEDED",
                    "Value " + value.toPlainString() + " exceeds the approver's limit of " + limit.toPlainString(),
                    Map.of("value", value, "approvalLimit", limit));
        }
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

    // ------------------------------------------------------------------ stock in transit (ADR-0025)

    /** A picked allocation of a transfer leaves the shipping site: it is in transit until the receiving site receives it. */
    private void inTransit(String transferNo, String fromSite, String toSite, Allocation a, OpContext ctx) {
        jdbc.sql("""
                        insert into stock_in_transit (tenant_id, transfer_no, from_site, to_site, owner_id, item_no, lot_no, qty,
                                                      shipped_at, operation_id)
                        values (:t, :tr, :from, :to, :owner, :item, :lot, :qty, :now, :op)
                        on conflict (tenant_id, transfer_no, owner_id, item_no, lot_no)
                        do update set qty = stock_in_transit.qty + excluded.qty""")
                .param("t", TenantContext.tenantId()).param("tr", transferNo).param("from", fromSite).param("to", toSite)
                .param("owner", a.ownerId()).param("item", a.itemNo()).param("lot", a.lotNo()).param("qty", a.qtyPicked())
                .param("now", Timestamp.from(ctx.now)).param("op", ctx.operationId).update();
    }

    /**
     * A receipt against a transfer ({@code sourceDoc} "TR-DC1-000001/10") reduces what is in transit to this site,
     * same lot first, then any lot of the item. Over-receipt beyond the transit quantity is a normal receipt.
     */
    private void receivedFromTransit(String siteId, String sourceDoc, String ownerId, String itemNo, String lot,
                                     BigDecimal qty, Instant now) {
        if (sourceDoc == null || sourceDoc.isBlank()) {
            return;
        }
        String transferNo = sourceDoc.contains("/") ? sourceDoc.substring(0, sourceDoc.indexOf('/')) : sourceDoc;
        record Open(long id, BigDecimal open) {
        }
        List<Open> open = jdbc.sql("""
                        select id, qty - qty_received from stock_in_transit
                        where transfer_no = :tr and to_site = :site and owner_id = :owner and item_no = :item
                          and qty_received < qty
                        order by case when lot_no = :lot then 0 else 1 end, id for update""")
                .param("tr", transferNo).param("site", siteId).param("owner", ownerId).param("item", itemNo)
                .param("lot", lot).query((rs, n) -> new Open(rs.getLong(1), rs.getBigDecimal(2))).list();
        BigDecimal left = qty;
        for (Open o : open) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal take = left.min(o.open());
            jdbc.sql("update stock_in_transit set qty_received = qty_received + :q, received_at = :now where id = :id")
                    .param("q", take).param("now", Timestamp.from(now)).param("id", o.id()).update();
            left = left.subtract(take);
        }
    }

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
        GoodsMovement.AccountAssignment account;

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

    /**
     * Idempotent execution returning a custom result type. Ledger lines and events the body adds are written exactly
     * as for {@link #idempotent}.
     */
    private <T> T idempotentValue(String siteId, String idempotencyKey, String opType, Object request, Class<T> type,
                                  java.util.function.Function<OpContext, T> body) {
        if (blank(idempotencyKey) || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID",
                    "Header Idempotency-Key is required (1-" + MAX_IDEMPOTENCY_KEY + " characters)");
        }
        String hash = sha256(opType + "|" + siteId + "|" + json.writeValueAsString(request));
        Optional<StoredOperation> existing = repo.findOperation(idempotencyKey);
        if (existing.isEmpty()) {
            TenantContext.Scope scope = TenantContext.require();
            OpContext ctx = new OpContext(UUID.randomUUID(), WmsTxnId.next(clock), opType, siteId, scope, clock.instant());
            if (repo.insertOperation(ctx.operationId, siteId, idempotencyKey, hash, opType, ctx.wmsTxnId, ctx.userId,
                    ctx.channel, ctx.now)) {
                T result = body.apply(ctx);
                if (!ctx.lines.isEmpty() || !ctx.movements.isEmpty()) {
                    finish(ctx);
                }
                repo.saveResponse(ctx.operationId, json.writeValueAsString(result));
                return result;
            }
            existing = repo.findOperation(idempotencyKey);
        }
        StoredOperation op = existing.orElseThrow();
        if (!op.requestHash().equals(hash)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used for a different request");
        }
        if (op.responseJson() == null) {
            throw ApiException.conflict("OPERATION_IN_PROGRESS", "The original request is still being processed");
        }
        T stored = json.readValue(op.responseJson(), type);
        return replayed(stored);
    }

    @SuppressWarnings("unchecked")
    private static <T> T replayed(T value) {
        return switch (value) {
            case AllocationResult a -> (T) a.asReplay();
            case IssueResult i -> (T) i.asReplay();
            case ReleaseResult r -> (T) r.asReplay();
            default -> value;
        };
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
        // Stock left these locations: forward locations below their minimum get replenished (§7.1 min/max).
        replenishments.onStockDecreased(ctx.siteId, ctx.lines.stream()
                .filter(l -> l.qtyDelta().signum() < 0 && l.key().status() == StockStatus.AVAILABLE)
                .map(l -> new String[] {l.key().locationId(), l.key().ownerId(), l.key().itemNo()})
                .distinct().toList());
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
                    new GoodsMovement(txnId, m.type().name(), ctx.reasonCode, ctx.now, ctx.approvedBy, m.items(),
                            ctx.account)));
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
