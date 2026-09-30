package com.astrawms.inventory.persistence;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.inventory.persistence.InventoryRepository.BalanceKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Serial number register. All methods run inside the command transaction that changes the matching balance. */
@Repository
public class SerialRepository {

    private final JdbcClient jdbc;

    public SerialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SerialView(String ownerId, String itemNo, String serialNo, String status, String siteId,
                             String locationId, String lpnId, String lotNo, String stockStatus, UUID lastOperationId,
                             Instant updatedAt) {
    }

    /** Serials of the list that are currently IN_STOCK anywhere (for duplicate detection on receipt). */
    public List<String> inStock(String ownerId, String itemNo, List<String> serials) {
        if (serials.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select serial_no from serial_number
                        where owner_id = :owner and item_no = :item and serial_no in (:serials) and status = 'IN_STOCK'
                        order by serial_no""")
                .param("owner", ownerId).param("item", itemNo).param("serials", serials)
                .query(String.class).list();
    }

    /** Serials of the list that are NOT IN_STOCK at exactly this balance key. Locks the found rows. */
    public List<String> missingAt(BalanceKey k, List<String> serials) {
        if (serials.isEmpty()) {
            return List.of();
        }
        List<String> present = jdbc.sql("""
                        select serial_no from serial_number
                        where owner_id = :owner and item_no = :item and serial_no in (:serials) and status = 'IN_STOCK'
                          and site_id = :site and location_id = :loc and lpn_id = :lpn and lot_no = :lot
                          and stock_status = :status
                        for update""")
                .param("owner", k.ownerId()).param("item", k.itemNo()).param("serials", serials)
                .param("site", k.siteId()).param("loc", k.locationId()).param("lpn", k.lpnId()).param("lot", k.lotNo())
                .param("status", k.status().name())
                .query(String.class).list();
        return serials.stream().filter(s -> !present.contains(s)).toList();
    }

    /** Puts serials into stock at the key (receipt, positive adjustment). Previously REMOVED serials come back. */
    public void place(BalanceKey k, List<String> serials, UUID operationId, Instant now) {
        for (String serial : serials) {
            jdbc.sql("""
                            insert into serial_number (tenant_id, owner_id, item_no, serial_no, site_id, lot_no, lpn_id,
                                                       location_id, stock_status, status, last_operation_id, updated_at)
                            values (:tenant, :owner, :item, :serial, :site, :lot, :lpn, :loc, :status, 'IN_STOCK', :op, :now)
                            on conflict (tenant_id, owner_id, item_no, serial_no) do update set
                                site_id = excluded.site_id, lot_no = excluded.lot_no, lpn_id = excluded.lpn_id,
                                location_id = excluded.location_id, stock_status = excluded.stock_status,
                                status = 'IN_STOCK', last_operation_id = excluded.last_operation_id,
                                updated_at = excluded.updated_at
                            where serial_number.status <> 'IN_STOCK'""")
                    .param("tenant", TenantContext.tenantId()).param("owner", k.ownerId()).param("item", k.itemNo())
                    .param("serial", serial).param("site", k.siteId()).param("lot", k.lotNo()).param("lpn", k.lpnId())
                    .param("loc", k.locationId()).param("status", k.status().name()).param("op", operationId)
                    .param("now", Timestamp.from(now))
                    .update();
        }
    }

    /** Moves serials from one balance key to another (quantity move, status change). */
    public void transfer(List<String> serials, BalanceKey to, UUID operationId, Instant now) {
        if (serials.isEmpty()) {
            return;   // untracked item: SQL "in ()" would be invalid
        }
        jdbc.sql("""
                        update serial_number set site_id = :site, location_id = :loc, lpn_id = :lpn, lot_no = :lot,
                            stock_status = :status, last_operation_id = :op, updated_at = :now
                        where owner_id = :owner and item_no = :item and serial_no in (:serials)""")
                .param("site", to.siteId()).param("loc", to.locationId()).param("lpn", to.lpnId()).param("lot", to.lotNo())
                .param("status", to.status().name()).param("op", operationId).param("now", Timestamp.from(now))
                .param("owner", to.ownerId()).param("item", to.itemNo()).param("serials", serials)
                .update();
    }

    public void remove(BalanceKey k, List<String> serials, UUID operationId, Instant now) {
        if (serials.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        update serial_number set status = 'REMOVED', last_operation_id = :op, updated_at = :now
                        where owner_id = :owner and item_no = :item and serial_no in (:serials)""")
                .param("op", operationId).param("now", Timestamp.from(now))
                .param("owner", k.ownerId()).param("item", k.itemNo()).param("serials", serials)
                .update();
    }

    /** A serial moved with its LPN; {@code balanceKey} identifies the balance it belongs to within the LPN. */
    public record MovedSerial(String balanceKey, String serialNo) {
    }

    public static String balanceKey(String itemNo, String lotNo, String stockStatus) {
        return itemNo + "|" + lotNo + "|" + stockStatus;
    }

    /** Whole-LPN move: all serials in the LPN follow it. */
    public List<MovedSerial> relocateLpn(String siteId, String lpnId, String toLocation, UUID operationId, Instant now) {
        return jdbc.sql("""
                        update serial_number set location_id = :to, last_operation_id = :op, updated_at = :now
                        where site_id = :site and lpn_id = :lpn and status = 'IN_STOCK'
                        returning item_no, lot_no, stock_status, serial_no""")
                .param("to", toLocation).param("op", operationId).param("now", Timestamp.from(now))
                .param("site", siteId).param("lpn", lpnId)
                .query((rs, n) -> new MovedSerial(balanceKey(rs.getString(1), rs.getString(2), rs.getString(3)),
                        rs.getString(4)))
                .list();
    }

    public Optional<SerialView> find(String ownerId, String itemNo, String serialNo) {
        return jdbc.sql(VIEW + " where owner_id = :owner and item_no = :item and serial_no = :serial")
                .param("owner", ownerId).param("item", itemNo).param("serial", serialNo)
                .query(SerialRepository::view).optional();
    }

    public List<SerialView> inLpn(String siteId, String lpnId) {
        return jdbc.sql(VIEW + " where site_id = :site and lpn_id = :lpn and status = 'IN_STOCK' order by item_no, serial_no")
                .param("site", siteId).param("lpn", lpnId).query(SerialRepository::view).list();
    }

    private static final String VIEW = """
            select owner_id, item_no, serial_no, status, site_id, location_id, lpn_id, lot_no, stock_status,
                   last_operation_id, updated_at
            from serial_number""";

    private static SerialView view(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new SerialView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getObject(10, UUID.class),
                rs.getTimestamp(11).toInstant());
    }
}
