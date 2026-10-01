package com.astrawms.inventory.service;

import com.astrawms.common.security.AccessScope;
import com.astrawms.common.web.ApiException;
import com.astrawms.inventory.api.InventoryDtos.BalanceView;
import com.astrawms.inventory.api.InventoryDtos.ItemSummary;
import com.astrawms.inventory.api.InventoryDtos.LpnView;
import com.astrawms.inventory.api.InventoryDtos.Page;
import com.astrawms.inventory.api.InventoryDtos.TxnView;
import com.astrawms.inventory.domain.Quantities;
import com.astrawms.inventory.domain.StockStatus;
import com.astrawms.inventory.persistence.SerialRepository;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the inventory API. Cursor pagination on the monotonic row id (NFR-122). */
@Service
@Transactional(readOnly = true)
public class InventoryQueryService {

    public static final int MAX_LIMIT = 500;

    private final JdbcClient jdbc;
    private final SerialRepository serials;

    public InventoryQueryService(JdbcClient jdbc, SerialRepository serials) {
        this.jdbc = jdbc;
        this.serials = serials;
    }

    public SerialRepository.SerialView serial(String ownerId, String itemNo, String serialNo) {
        return serials.find(ownerId, itemNo, serialNo).orElseThrow(() -> ApiException.notFound("INV_SERIAL_UNKNOWN",
                "Serial " + serialNo + " of item " + itemNo + " is not known"));
    }

    public record BalanceFilter(String ownerId, String itemNo, String lotNo, String lpnId, String locationId,
                                StockStatus status) {
    }

    public Page<BalanceView> balances(String siteId, BalanceFilter f, Long after, int limit) {
        int size = clamp(limit);
        StringBuilder sql = new StringBuilder("""
                select id, owner_id, item_no, lot_no, lpn_id, location_id, stock_status, qty, allocated_qty,
                       expiry_date, receipt_date
                from inventory_balance where site_id = :site and id > :after""");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("site", siteId);
        params.put("after", after == null ? 0L : after);
        filter(sql, params, "owner_id", "owner", f.ownerId());
        ownerScope(sql, params);
        filter(sql, params, "item_no", "item", f.itemNo());
        filter(sql, params, "lot_no", "lot", f.lotNo());
        filter(sql, params, "lpn_id", "lpn", f.lpnId());
        filter(sql, params, "location_id", "loc", f.locationId());
        filter(sql, params, "stock_status", "status", f.status() == null ? null : f.status().name());
        sql.append(" order by id limit :limit");
        params.put("limit", size + 1);
        List<BalanceView> rows = jdbc.sql(sql.toString()).params(params).query(InventoryQueryService::balance).list();
        return page(rows, size, BalanceView::id);
    }

    public ItemSummary itemSummary(String siteId, String ownerId, String itemNo) {
        List<ItemSummary.StatusTotal> totals = jdbc.sql("""
                        select stock_status, sum(qty) as qty, sum(allocated_qty) as allocated
                        from inventory_balance
                        where site_id = :site and owner_id = :owner and item_no = :item
                        group by stock_status order by stock_status""")
                .param("site", siteId).param("owner", ownerId).param("item", itemNo)
                .query((rs, n) -> new ItemSummary.StatusTotal(StockStatus.valueOf(rs.getString("stock_status")),
                        Quantities.normalize(rs.getBigDecimal("qty")), Quantities.normalize(rs.getBigDecimal("allocated"))))
                .list();
        return new ItemSummary(ownerId, itemNo, totals);
    }

    public LpnView lpn(String siteId, String lpnId) {
        LpnView header = jdbc.sql("""
                        select lpn_id, owner_id, location_id, lpn_type from lpn where site_id = :site and lpn_id = :lpn""")
                .param("site", siteId).param("lpn", lpnId)
                .query((rs, n) -> new LpnView(rs.getString("lpn_id"), rs.getString("owner_id"),
                        rs.getString("location_id"), rs.getString("lpn_type"), List.of(), List.of()))
                .optional()
                .orElseThrow(() -> ApiException.notFound("INV_LPN_UNKNOWN", "LPN " + lpnId + " does not exist"));
        if (!AccessScope.current().allowsOwner(header.ownerId())) {
            throw ApiException.notFound("INV_LPN_UNKNOWN", "LPN " + lpnId + " does not exist");   // not visible to this user
        }
        List<BalanceView> contents = jdbc.sql("""
                        select id, owner_id, item_no, lot_no, lpn_id, location_id, stock_status, qty, allocated_qty,
                               expiry_date, receipt_date
                        from inventory_balance where site_id = :site and lpn_id = :lpn order by id""")
                .param("site", siteId).param("lpn", lpnId)
                .query(InventoryQueryService::balance).list();
        List<String> lpnSerials = serials.inLpn(siteId, lpnId).stream().map(SerialRepository.SerialView::serialNo).toList();
        return new LpnView(header.lpnId(), header.ownerId(), header.locationId(), header.lpnType(), contents, lpnSerials);
    }

    public record TxnFilter(String itemNo, String lpnId, String locationId, UUID operationId) {
    }

    public Page<TxnView> transactions(String siteId, TxnFilter f, Long after, int limit) {
        int size = clamp(limit);
        StringBuilder sql = new StringBuilder("""
                select id, operation_id, txn_type, owner_id, item_no, lot_no, lpn_id, location_id, stock_status,
                       qty_delta, qty_after, reason_code, source_doc, user_id, channel, occurred_at
                from inventory_txn where site_id = :site and id > :after""");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("site", siteId);
        params.put("after", after == null ? 0L : after);
        ownerScope(sql, params);
        filter(sql, params, "item_no", "item", f.itemNo());
        filter(sql, params, "lpn_id", "lpn", f.lpnId());
        filter(sql, params, "location_id", "loc", f.locationId());
        if (f.operationId() != null) {
            sql.append(" and operation_id = :op");
            params.put("op", f.operationId());
        }
        sql.append(" order by id limit :limit");
        params.put("limit", size + 1);
        List<TxnView> rows = jdbc.sql(sql.toString()).params(params).query((rs, n) -> new TxnView(
                rs.getLong("id"), rs.getObject("operation_id", UUID.class), rs.getString("txn_type"),
                rs.getString("owner_id"), rs.getString("item_no"), rs.getString("lot_no"), rs.getString("lpn_id"),
                rs.getString("location_id"), StockStatus.valueOf(rs.getString("stock_status")),
                Quantities.normalize(rs.getBigDecimal("qty_delta")), Quantities.normalize(rs.getBigDecimal("qty_after")), rs.getString("reason_code"),
                rs.getString("source_doc"), rs.getString("user_id"), rs.getString("channel"),
                rs.getTimestamp("occurred_at").toInstant())).list();
        return page(rows, size, TxnView::id);
    }

    /** Owner scope of the user (§G.5.1): a 3PL client sees only its own stock. */
    private static void ownerScope(StringBuilder sql, Map<String, Object> params) {
        AccessScope scope = AccessScope.current();
        if (!scope.ownersAll()) {
            sql.append(" and owner_id in (:scopeOwners)");
            params.put("scopeOwners", scope.ownerList());
        }
    }

    private static BalanceView balance(ResultSet rs, int n) throws SQLException {
        Date expiry = rs.getDate("expiry_date");
        var qty = Quantities.normalize(rs.getBigDecimal("qty"));
        var allocated = Quantities.normalize(rs.getBigDecimal("allocated_qty"));
        return new BalanceView(rs.getLong("id"), rs.getString("owner_id"), rs.getString("item_no"),
                rs.getString("lot_no"), rs.getString("lpn_id"), rs.getString("location_id"),
                StockStatus.valueOf(rs.getString("stock_status")), qty, allocated, Quantities.normalize(qty.subtract(allocated)),
                expiry == null ? null : expiry.toLocalDate(), rs.getTimestamp("receipt_date").toInstant());
    }

    private static void filter(StringBuilder sql, Map<String, Object> params, String column, String name, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" and ").append(column).append(" = :").append(name);
            params.put(name, value);
        }
    }

    private static <T> Page<T> page(List<T> rows, int size, java.util.function.ToLongFunction<T> id) {
        if (rows.size() <= size) {
            return new Page<>(rows, null);
        }
        List<T> items = new ArrayList<>(rows.subList(0, size));
        return new Page<>(items, id.applyAsLong(items.get(size - 1)));
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
