package com.astrawms.inventory.persistence;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.inventory.domain.Quantities;
import com.astrawms.inventory.domain.StockStatus;
import com.astrawms.inventory.domain.TxnType;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for balances, LPNs, the inventory ledger, operations and reason codes. RLS scopes every query to the tenant. */
@Repository
public class InventoryRepository {

    private final JdbcClient jdbc;

    public InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ balances

    /** Natural key of a balance. Empty string means "no lot" / "no LPN". */
    public record BalanceKey(String siteId, String ownerId, String itemNo, String lotNo, String lpnId,
                             String locationId, StockStatus status) {
        public BalanceKey {
            lotNo = lotNo == null ? "" : lotNo;
            lpnId = lpnId == null ? "" : lpnId;
        }

        public BalanceKey withLocation(String location, String lpn) {
            return new BalanceKey(siteId, ownerId, itemNo, lotNo, lpn, location, status);
        }

        public BalanceKey withStatus(StockStatus s) {
            return new BalanceKey(siteId, ownerId, itemNo, lotNo, lpnId, locationId, s);
        }
    }

    public record Balance(long id, BalanceKey key, BigDecimal qty, BigDecimal allocatedQty, LocalDate expiryDate,
                          Instant receiptDate) {
        public BigDecimal availableQty() {
            return qty.subtract(allocatedQty);
        }
    }

    private static final String BALANCE_COLUMNS = """
            id, site_id, owner_id, item_no, lot_no, lpn_id, location_id, stock_status, qty, allocated_qty,
            expiry_date, receipt_date""";

    private static Balance mapBalance(ResultSet rs, int n) throws SQLException {
        Date expiry = rs.getDate("expiry_date");
        return new Balance(rs.getLong("id"),
                new BalanceKey(rs.getString("site_id"), rs.getString("owner_id"), rs.getString("item_no"),
                        rs.getString("lot_no"), rs.getString("lpn_id"), rs.getString("location_id"),
                        StockStatus.valueOf(rs.getString("stock_status"))),
                Quantities.normalize(rs.getBigDecimal("qty")), Quantities.normalize(rs.getBigDecimal("allocated_qty")),
                expiry == null ? null : expiry.toLocalDate(), rs.getTimestamp("receipt_date").toInstant());
    }

    public Optional<Balance> find(BalanceKey k) {
        return jdbc.sql("select " + BALANCE_COLUMNS + " " + """
                        from inventory_balance
                        where site_id = :site and owner_id = :owner and item_no = :item and lot_no = :lot
                          and lpn_id = :lpn and location_id = :loc and stock_status = :status""")
                .params(keyParams(k)).query(InventoryRepository::mapBalance).optional();
    }

    /**
     * Adds quantity to a balance, creating it if needed. The earliest receipt date is kept (FIFO) and an existing
     * expiry date is not overwritten.
     *
     * @return quantity after the change
     */
    public BigDecimal increment(BalanceKey k, BigDecimal qty, LocalDate expiry, Instant receiptDate) {
        BigDecimal after = jdbc.sql("""
                        insert into inventory_balance (tenant_id, site_id, owner_id, item_no, lot_no, lpn_id,
                                                       location_id, stock_status, qty, expiry_date, receipt_date)
                        values (:tenant, :site, :owner, :item, :lot, :lpn, :loc, :status, :qty, :expiry, :receipt)
                        on conflict (tenant_id, site_id, owner_id, item_no, lot_no, lpn_id, location_id, stock_status)
                        do update set qty = inventory_balance.qty + excluded.qty,
                                      expiry_date = coalesce(inventory_balance.expiry_date, excluded.expiry_date),
                                      receipt_date = least(inventory_balance.receipt_date, excluded.receipt_date),
                                      version = inventory_balance.version + 1
                        returning qty""")
                .params(keyParams(k))
                .param("tenant", TenantContext.tenantId())
                .param("qty", qty)
                .param("expiry", expiry == null ? null : Date.valueOf(expiry))
                .param("receipt", Timestamp.from(receiptDate))
                .query(BigDecimal.class).single();
        return Quantities.normalize(after);
    }

    /**
     * Removes unallocated quantity atomically. Returns empty if the balance does not exist or its unallocated
     * quantity is lower than {@code qty}; the caller then inspects {@link #find} to explain why.
     */
    public Optional<BigDecimal> decrement(BalanceKey k, BigDecimal qty) {
        Optional<BigDecimal> after = jdbc.sql("""
                        update inventory_balance set qty = qty - :qty, version = version + 1
                        where site_id = :site and owner_id = :owner and item_no = :item and lot_no = :lot
                          and lpn_id = :lpn and location_id = :loc and stock_status = :status
                          and qty - allocated_qty >= :qty
                        returning qty""")
                .params(keyParams(k)).param("qty", qty)
                .query(BigDecimal.class).optional().map(Quantities::normalize);
        after.filter(q -> q.signum() == 0).ifPresent(q -> jdbc.sql("""
                        delete from inventory_balance
                        where site_id = :site and owner_id = :owner and item_no = :item and lot_no = :lot
                          and lpn_id = :lpn and location_id = :loc and stock_status = :status
                          and qty = 0 and allocated_qty = 0""")
                .params(keyParams(k)).update());
        return after;
    }

    public List<Balance> lockLpnContents(String siteId, String lpnId) {
        return jdbc.sql("select " + BALANCE_COLUMNS
                        + " from inventory_balance where site_id = :site and lpn_id = :lpn order by id for update")
                .param("site", siteId).param("lpn", lpnId)
                .query(InventoryRepository::mapBalance).list();
    }

    public void relocateLpnBalances(String siteId, String lpnId, String toLocation) {
        jdbc.sql("""
                        update inventory_balance set location_id = :to, version = version + 1
                        where site_id = :site and lpn_id = :lpn""")
                .param("site", siteId).param("lpn", lpnId).param("to", toLocation).update();
    }

    public boolean locationHoldsOtherItem(String siteId, String locationId, String ownerId, String itemNo) {
        return jdbc.sql("""
                        select exists (select 1 from inventory_balance
                                       where site_id = :site and location_id = :loc
                                         and (owner_id <> :owner or item_no <> :item))""")
                .param("site", siteId).param("loc", locationId).param("owner", ownerId).param("item", itemNo)
                .query(Boolean.class).single();
    }

    public boolean locationHoldsOtherLot(String siteId, String locationId, String ownerId, String itemNo, String lotNo) {
        return jdbc.sql("""
                        select exists (select 1 from inventory_balance
                                       where site_id = :site and location_id = :loc and owner_id = :owner
                                         and item_no = :item and lot_no <> :lot)""")
                .param("site", siteId).param("loc", locationId).param("owner", ownerId).param("item", itemNo)
                .param("lot", lotNo == null ? "" : lotNo)
                .query(Boolean.class).single();
    }

    // ------------------------------------------------------------------ LPNs

    public record Lpn(String siteId, String lpnId, String ownerId, String locationId, String lpnType) {
    }

    public Optional<Lpn> lockLpn(String siteId, String lpnId) {
        return jdbc.sql("""
                        select site_id, lpn_id, owner_id, location_id, lpn_type from lpn
                        where site_id = :site and lpn_id = :lpn for update""")
                .param("site", siteId).param("lpn", lpnId)
                .query((rs, n) -> new Lpn(rs.getString("site_id"), rs.getString("lpn_id"), rs.getString("owner_id"),
                        rs.getString("location_id"), rs.getString("lpn_type")))
                .optional();
    }

    public void insertLpn(String siteId, String lpnId, String ownerId, String locationId) {
        jdbc.sql("""
                        insert into lpn (tenant_id, site_id, lpn_id, owner_id, location_id)
                        values (:tenant, :site, :lpn, :owner, :loc)""")
                .param("tenant", TenantContext.tenantId()).param("site", siteId).param("lpn", lpnId)
                .param("owner", ownerId).param("loc", locationId).update();
    }

    public void moveLpn(String siteId, String lpnId, String toLocation) {
        jdbc.sql("update lpn set location_id = :to, updated_at = now() where site_id = :site and lpn_id = :lpn")
                .param("site", siteId).param("lpn", lpnId).param("to", toLocation).update();
    }

    // ------------------------------------------------------------------ ledger

    public record TxnLine(TxnType type, BalanceKey key, BigDecimal qtyDelta, BigDecimal qtyAfter, List<String> serials) {
    }

    public void insertTxn(UUID operationId, TxnLine line, String reasonCode, String sourceDoc, String userId,
                          String channel, Instant occurredAt) {
        BalanceKey k = line.key();
        jdbc.sql("""
                        insert into inventory_txn (tenant_id, site_id, operation_id, txn_type, owner_id, item_no,
                                                   lot_no, lpn_id, location_id, stock_status, qty_delta, qty_after,
                                                   reason_code, source_doc, user_id, channel, occurred_at, serials)
                        values (:tenant, :site, :op, :type, :owner, :item, :lot, :lpn, :loc, :status, :delta,
                                :after, :reason, :sourceDoc, :user, :channel, :at, :serials)""")
                .params(keyParams(k))
                .param("tenant", TenantContext.tenantId()).param("op", operationId).param("type", line.type().name())
                .param("delta", line.qtyDelta()).param("after", line.qtyAfter()).param("reason", reasonCode)
                .param("sourceDoc", sourceDoc).param("user", userId).param("channel", channel)
                .param("at", Timestamp.from(occurredAt))
                .param("serials", line.serials() == null || line.serials().isEmpty() ? null
                        : line.serials().toArray(String[]::new))
                .update();
    }

    // ------------------------------------------------------------------ operations (idempotency)

    public record StoredOperation(UUID id, String requestHash, String opType, String responseJson) {
    }

    public Optional<StoredOperation> findOperation(String idempotencyKey) {
        return jdbc.sql("""
                        select id, request_hash, op_type, response::text as response from inventory_operation
                        where idempotency_key = :key""")
                .param("key", idempotencyKey)
                .query((rs, n) -> new StoredOperation(rs.getObject("id", UUID.class), rs.getString("request_hash"),
                        rs.getString("op_type"), rs.getString("response")))
                .optional();
    }

    /** Returns {@code false} if another operation already uses the idempotency key. */
    public boolean insertOperation(UUID id, String siteId, String idempotencyKey, String requestHash, String opType,
                                   String wmsTxnId, String userId, String channel, Instant createdAt) {
        return jdbc.sql("""
                        insert into inventory_operation (id, tenant_id, site_id, idempotency_key, request_hash,
                                                         op_type, wms_txn_id, created_by, channel, created_at)
                        values (:id, :tenant, :site, :key, :hash, :type, :txn, :user, :channel, :at)
                        on conflict (tenant_id, idempotency_key) do nothing""")
                .param("id", id).param("tenant", TenantContext.tenantId()).param("site", siteId)
                .param("key", idempotencyKey).param("hash", requestHash).param("type", opType).param("txn", wmsTxnId)
                .param("user", userId).param("channel", channel).param("at", Timestamp.from(createdAt))
                .update() == 1;
    }

    public void saveResponse(UUID operationId, String responseJson) {
        jdbc.sql("update inventory_operation set response = cast(:response as jsonb) where id = :id")
                .param("id", operationId).param("response", responseJson).update();
    }

    public void insertErpMovement(String wmsTxnId, UUID operationId, String siteId, String itemNo, String movementType,
                                  Instant createdAt) {
        jdbc.sql("""
                        insert into erp_movement (tenant_id, wms_txn_id, operation_id, site_id, item_no, movement_type,
                                                  created_at)
                        values (:tenant, :txn, :op, :site, :item, :type, :at)""")
                .param("tenant", TenantContext.tenantId()).param("txn", wmsTxnId).param("op", operationId)
                .param("site", siteId).param("item", itemNo).param("type", movementType)
                .param("at", Timestamp.from(createdAt)).update();
    }

    // ------------------------------------------------------------------ reason codes

    public record Reason(String code, String appliesTo, boolean requiresApproval, boolean erpRelevant) {
    }

    /** Tenant-specific reason codes override platform defaults with the same code. */
    public Optional<Reason> reason(String code) {
        return jdbc.sql("""
                        select code, applies_to, requires_approval, erp_relevant from reason_code
                        where code = :code order by tenant_id nulls last limit 1""")
                .param("code", code)
                .query((rs, n) -> new Reason(rs.getString("code"), rs.getString("applies_to"),
                        rs.getBoolean("requires_approval"), rs.getBoolean("erp_relevant")))
                .optional();
    }

    // ------------------------------------------------------------------ helpers

    private static java.util.Map<String, Object> keyParams(BalanceKey k) {
        return java.util.Map.of(
                "site", k.siteId(), "owner", k.ownerId(), "item", k.itemNo(), "lot", k.lotNo(),
                "lpn", k.lpnId(), "loc", k.locationId(), "status", k.status().name());
    }
}
