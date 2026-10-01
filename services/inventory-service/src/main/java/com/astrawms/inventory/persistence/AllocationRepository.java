package com.astrawms.inventory.persistence;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.inventory.domain.Quantities;
import com.astrawms.inventory.domain.StockStatus;
import com.astrawms.inventory.persistence.InventoryRepository.BalanceKey;
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

/** Allocations and the balance-level allocated quantity they reserve. */
@Repository
public class AllocationRepository {

    /** Location types whose stock is never allocable: inbound/outbound staging (PUT-006). */
    public static final List<String> NON_ALLOCABLE_TYPES = List.of("DOOR", "DOCK", "STAGING", "STAGING_IN", "STAGING_OUT");

    private final JdbcClient jdbc;

    public AllocationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Allocation(UUID id, String siteId, String orderRef, String orderLineRef, String ownerId,
                             String itemNo, String lotNo, String lpnId, String locationId, BigDecimal qtyAllocated,
                             BigDecimal qtyPicked, String pickedLocation, String pickedLpn, String status) {

        public BalanceKey sourceKey() {
            return new BalanceKey(siteId, ownerId, itemNo, lotNo, lpnId, locationId, StockStatus.AVAILABLE);
        }

        public BalanceKey pickedKey() {
            return new BalanceKey(siteId, ownerId, itemNo, lotNo, pickedLpn, pickedLocation, StockStatus.AVAILABLE);
        }

        public BigDecimal open() {
            return qtyAllocated.subtract(qtyPicked);
        }
    }

    /** A balance with free (unallocated) quantity, in rotation order. */
    public record Candidate(BalanceKey key, BigDecimal free, LocalDate expiry) {
    }

    /**
     * Allocable balances of an item: AVAILABLE, unallocated quantity left, in an active non-staging location,
     * optional lot and minimum expiry. FEFO: earliest expiry first, then oldest receipt; FIFO: oldest receipt.
     * The rows are locked so concurrent allocations cannot over-reserve.
     */
    public List<Candidate> candidates(String siteId, String ownerId, String itemNo, String lotNo, LocalDate minExpiry,
                                      boolean fefo) {
        String order = fefo ? "b.expiry_date nulls last, b.receipt_date, b.id" : "b.receipt_date, b.id";
        return jdbc.sql("""
                        select b.site_id, b.owner_id, b.item_no, b.lot_no, b.lpn_id, b.location_id,
                               b.qty - b.allocated_qty as free, b.expiry_date
                        from inventory_balance b
                        join ref_location l on l.site_id = b.site_id and l.location_id = b.location_id
                        where b.site_id = :site and b.owner_id = :owner and b.item_no = :item
                          and b.stock_status = 'AVAILABLE' and b.qty > b.allocated_qty
                          and l.status = 'ACTIVE' and l.location_type not in (:staging)
                          and (cast(:lot as text) is null or b.lot_no = :lot)
                          and (cast(:minExpiry as date) is null or b.expiry_date >= :minExpiry)
                        order by\s""" + order + " for update of b")
                .param("site", siteId).param("owner", ownerId).param("item", itemNo).param("lot", lotNo)
                .param("minExpiry", minExpiry == null ? null : Date.valueOf(minExpiry))
                .param("staging", NON_ALLOCABLE_TYPES)
                .query((rs, n) -> {
                    Date expiry = rs.getDate(8);
                    return new Candidate(new BalanceKey(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), StockStatus.AVAILABLE),
                            Quantities.normalize(rs.getBigDecimal(7)), expiry == null ? null : expiry.toLocalDate());
                })
                .list();
    }

    // ------------------------------------------------------------------ balance-level reservation

    public void reserve(BalanceKey k, BigDecimal qty) {
        int n = jdbc.sql("""
                        update inventory_balance set allocated_qty = allocated_qty + :q, version = version + 1
                        where site_id = :site and owner_id = :owner and item_no = :item and lot_no = :lot
                          and lpn_id = :lpn and location_id = :loc and stock_status = :status
                          and qty - allocated_qty >= :q""")
                .params(keyParams(k)).param("q", qty).update();
        if (n != 1) {
            throw new IllegalStateException("Balance changed during allocation: " + k);
        }
    }

    public void unreserve(BalanceKey k, BigDecimal qty) {
        jdbc.sql("""
                        update inventory_balance set allocated_qty = allocated_qty - :q, version = version + 1
                        where site_id = :site and owner_id = :owner and item_no = :item and lot_no = :lot
                          and lpn_id = :lpn and location_id = :loc and stock_status = :status
                          and allocated_qty >= :q""")
                .params(keyParams(k)).param("q", qty).update();
    }

    /** Removes allocated quantity from a balance (pick source, issue). Returns qty after, or empty if not possible. */
    public Optional<BigDecimal> takeAllocated(BalanceKey k, BigDecimal qty) {
        Optional<BigDecimal> after = jdbc.sql("""
                        update inventory_balance set qty = qty - :q, allocated_qty = allocated_qty - :q,
                            version = version + 1
                        where site_id = :site and owner_id = :owner and item_no = :item and lot_no = :lot
                          and lpn_id = :lpn and location_id = :loc and stock_status = :status
                          and allocated_qty >= :q and qty >= :q
                        returning qty""")
                .params(keyParams(k)).param("q", qty).query(BigDecimal.class).optional().map(Quantities::normalize);
        after.filter(q -> q.signum() == 0).ifPresent(q -> jdbc.sql("""
                        delete from inventory_balance
                        where site_id = :site and owner_id = :owner and item_no = :item and lot_no = :lot
                          and lpn_id = :lpn and location_id = :loc and stock_status = :status
                          and qty = 0 and allocated_qty = 0""")
                .params(keyParams(k)).update());
        return after;
    }

    /** Adds quantity that stays allocated (picked stock at outbound staging). Returns qty after. */
    public BigDecimal putAllocated(BalanceKey k, BigDecimal qty, LocalDate expiry, Instant receiptDate) {
        return Quantities.normalize(jdbc.sql("""
                        insert into inventory_balance (tenant_id, site_id, owner_id, item_no, lot_no, lpn_id,
                                                       location_id, stock_status, qty, allocated_qty, expiry_date,
                                                       receipt_date)
                        values (:tenant, :site, :owner, :item, :lot, :lpn, :loc, :status, :q, :q, :expiry, :receipt)
                        on conflict (tenant_id, site_id, owner_id, item_no, lot_no, lpn_id, location_id, stock_status)
                        do update set qty = inventory_balance.qty + excluded.qty,
                                      allocated_qty = inventory_balance.allocated_qty + excluded.allocated_qty,
                                      version = inventory_balance.version + 1
                        returning qty""")
                .params(keyParams(k)).param("tenant", TenantContext.tenantId()).param("q", qty)
                .param("expiry", expiry == null ? null : Date.valueOf(expiry))
                .param("receipt", Timestamp.from(receiptDate))
                .query(BigDecimal.class).single());
    }

    // ------------------------------------------------------------------ allocation rows

    public void insert(UUID id, BalanceKey k, String orderRef, String lineRef, BigDecimal qty, Instant now) {
        jdbc.sql("""
                        insert into allocation (id, tenant_id, site_id, order_ref, order_line_ref, owner_id, item_no,
                                                lot_no, lpn_id, location_id, qty_allocated, status, created_at, updated_at)
                        values (:id, :tenant, :site, :order, :line, :owner, :item, :lot, :lpn, :loc, :qty, 'OPEN', :now, :now)""")
                .param("id", id).param("tenant", TenantContext.tenantId()).param("site", k.siteId())
                .param("order", orderRef).param("line", lineRef).param("owner", k.ownerId()).param("item", k.itemNo())
                .param("lot", k.lotNo()).param("lpn", k.lpnId()).param("loc", k.locationId()).param("qty", qty)
                .param("now", Timestamp.from(now)).update();
    }

    public Optional<Allocation> lock(String siteId, UUID id) {
        return jdbc.sql(SELECT + " where site_id = :site and id = :id for update")
                .param("site", siteId).param("id", id).query(AllocationRepository::map).optional();
    }

    public List<Allocation> lockByOrder(String siteId, String orderRef) {
        return jdbc.sql(SELECT + " where site_id = :site and order_ref = :order order by order_line_ref, created_at for update")
                .param("site", siteId).param("order", orderRef).query(AllocationRepository::map).list();
    }

    public List<Allocation> byOrder(String siteId, String orderRef) {
        return jdbc.sql(SELECT + " where site_id = :site and order_ref = :order order by order_line_ref, created_at")
                .param("site", siteId).param("order", orderRef).query(AllocationRepository::map).list();
    }

    public void recordPick(UUID id, BigDecimal picked, BigDecimal newAllocated, String location, String lpn,
                           String status, Instant now) {
        jdbc.sql("""
                        update allocation set qty_picked = :picked, qty_allocated = :allocated, picked_location = :loc,
                            picked_lpn = :lpn, status = :status, updated_at = :now where id = :id""")
                .param("picked", picked).param("allocated", newAllocated).param("loc", location).param("lpn", lpn)
                .param("status", status).param("now", Timestamp.from(now)).param("id", id).update();
    }

    public void setStatus(UUID id, String status, Instant now) {
        jdbc.sql("update allocation set status = :status, updated_at = :now where id = :id")
                .param("status", status).param("now", Timestamp.from(now)).param("id", id).update();
    }

    private static final String SELECT = """
            select id, site_id, order_ref, order_line_ref, owner_id, item_no, lot_no, lpn_id, location_id,
                   qty_allocated, qty_picked, picked_location, picked_lpn, status
            from allocation""";

    private static Allocation map(ResultSet rs, int n) throws SQLException {
        return new Allocation(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                Quantities.normalize(rs.getBigDecimal(10)), Quantities.normalize(rs.getBigDecimal(11)),
                rs.getString(12), rs.getString(13), rs.getString(14));
    }

    private static java.util.Map<String, Object> keyParams(BalanceKey k) {
        return java.util.Map.of("site", k.siteId(), "owner", k.ownerId(), "item", k.itemNo(), "lot", k.lotNo(),
                "lpn", k.lpnId(), "loc", k.locationId(), "status", k.status().name());
    }
}
