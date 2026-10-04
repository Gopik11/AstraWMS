package com.astrawms.outbound.packing;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.outbound.service.OutboundService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loading (§5.3): a load is a trailer at a door for one carrier. Orders are loaded by delivery number or by scanning
 * one of their cartons; units for another carrier are refused (SHP-003 cross-load check). Closing the load with the
 * seal number ships every order on it with the load's bill of lading (ship confirm → goods issue, IF-OB-003).
 */
@Service
public class LoadService {

    private final JdbcClient jdbc;
    private final OutboundService outbound;
    private final PackingService packing;
    private final Clock clock;

    public LoadService(JdbcClient jdbc, OutboundService outbound, PackingService packing, Clock clock) {
        this.jdbc = jdbc;
        this.outbound = outbound;
        this.packing = packing;
        this.clock = clock;
    }

    private record Load(UUID id, String loadNo, String carrier, String status) {
    }

    @Transactional
    public Map<String, Object> create(String siteId, String carrierScac, String door, String trailerNo) {
        long seq = jdbc.sql("select count(*) + 1 from shipment_load where site_id = :site").param("site", siteId)
                .query(Long.class).single();
        String loadNo = "L%06d".formatted(seq);
        jdbc.sql("""
                        insert into shipment_load (id, tenant_id, site_id, load_no, carrier_scac, door, trailer_no, status,
                                                   created_by, created_at)
                        values (:id, :t, :site, :no, :scac, :door, :trailer, 'OPEN', :user, :now)""")
                .param("id", UUID.randomUUID()).param("t", TenantContext.tenantId()).param("site", siteId).param("no", loadNo)
                .param("scac", blankToNull(carrierScac)).param("door", blankToNull(door)).param("trailer", blankToNull(trailerNo))
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        return detail(siteId, loadNo);
    }

    /** Loads an order, given by delivery number or by one of its cartons' SSCC (load verification scan). */
    @Transactional
    public Map<String, Object> addOrder(String siteId, String loadNo, String erpDocNo, String sscc) {
        Load load = lock(siteId, loadNo);
        if (!"OPEN".equals(load.status())) {
            throw ApiException.unprocessable("OUT_LOAD_CLOSED", "Load " + loadNo + " is closed");
        }
        String doc = erpDocNo;
        if ((doc == null || doc.isBlank()) && sscc != null) {
            doc = jdbc.sql("select o.erp_doc_no from carton k join outbound_order o on o.id = k.order_id where k.site_id = :site and k.sscc = :sscc")
                    .param("site", siteId).param("sscc", normaliseSscc(sscc)).query(String.class)
                    .optional().orElseThrow(() -> ApiException.notFound("OUT_CARTON_UNKNOWN", "No carton " + sscc));
        }
        if (doc == null || doc.isBlank()) {
            throw ApiException.badRequest("OUT_LOAD_ORDER_MISSING", "Give erpDocNo or a carton SSCC");
        }
        record O(UUID id, String status, String carrier, UUID loadId) {
        }
        String d = doc;
        O o = jdbc.sql("select id, status, carrier_scac, load_id from outbound_order where site_id = :site and erp_doc_no = :doc for update")
                .param("site", siteId).param("doc", d)
                .query((rs, n) -> new O(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class)))
                .optional().orElseThrow(() -> ApiException.notFound("OUT_ORDER_UNKNOWN", "No outbound order " + d));
        if (load.id().equals(o.loadId())) {
            return detail(siteId, loadNo);
        }
        if (o.loadId() != null) {
            throw ApiException.conflict("OUT_ALREADY_LOADED", "Order " + d + " is on another load");
        }
        if (!"PICKED".equals(o.status())) {
            throw ApiException.unprocessable("OUT_NOT_PICKED", "Order " + d + " is " + o.status() + "; only picked orders can be loaded");
        }
        if (load.carrier() != null && o.carrier() != null && !load.carrier().equals(o.carrier())) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "OUT_CROSS_LOAD",
                    "Order " + d + " ships with " + o.carrier() + ", not with this load's carrier " + load.carrier(),
                    Map.of("orderCarrier", o.carrier(), "loadCarrier", load.carrier()));
        }
        packing.requirePacked(o.id(), siteId, d);
        jdbc.sql("update outbound_order set load_id = :l, updated_at = :now where id = :id")
                .param("l", load.id()).param("now", Timestamp.from(clock.instant())).param("id", o.id()).update();
        return detail(siteId, loadNo);
    }

    @Transactional
    public Map<String, Object> removeOrder(String siteId, String loadNo, String erpDocNo) {
        Load load = lock(siteId, loadNo);
        if (!"OPEN".equals(load.status())) {
            throw ApiException.unprocessable("OUT_LOAD_CLOSED", "Load " + loadNo + " is closed");
        }
        jdbc.sql("update outbound_order set load_id = null where site_id = :site and erp_doc_no = :doc and load_id = :l")
                .param("site", siteId).param("doc", erpDocNo).param("l", load.id()).update();
        return detail(siteId, loadNo);
    }

    /** Trailer close: seal and BOL recorded, every order on the load ships (goods issue to the ERP). */
    @Transactional
    public Map<String, Object> close(String siteId, String loadNo, String sealNo) {
        Load load = lock(siteId, loadNo);
        if ("CLOSED".equals(load.status())) {
            return detail(siteId, loadNo);
        }
        List<String> docs = jdbc.sql("select erp_doc_no from outbound_order where load_id = :l order by erp_doc_no")
                .param("l", load.id()).query(String.class).list();
        if (docs.isEmpty()) {
            throw ApiException.unprocessable("OUT_LOAD_EMPTY", "Load " + loadNo + " has no orders");
        }
        String bol = "BOL-" + siteId + "-" + loadNo;
        for (String doc : docs) {
            String tracking = jdbc.sql("""
                            select k.tracking_no from carton k join outbound_order o on o.id = k.order_id
                            where o.site_id = :site and o.erp_doc_no = :doc and k.tracking_no is not null
                            order by k.closed_at limit 1""")
                    .param("site", siteId).param("doc", doc).query(String.class).optional().orElse(null);
            outbound.ship(siteId, doc, new OutboundService.ShipRequest(load.carrier(), tracking, bol));
        }
        jdbc.sql("""
                        update shipment_load set status = 'CLOSED', seal_no = :seal, bol_no = :bol, closed_by = :user,
                            closed_at = :now where id = :id""")
                .param("seal", blankToNull(sealNo)).param("bol", bol).param("user", TenantContext.require().userId())
                .param("now", Timestamp.from(clock.instant())).param("id", load.id()).update();
        return detail(siteId, loadNo);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String siteId, String status) {
        return jdbc.sql("""
                        select l.load_no, l.carrier_scac, l.door, l.trailer_no, l.status, l.seal_no, l.bol_no, l.created_at,
                               l.closed_at, (select count(*) from outbound_order o where o.load_id = l.id) as orders,
                               l.tracking_no, l.tracking_status, l.tracking_detail, l.tracking_updated_at
                        from shipment_load l where l.site_id = :site and (cast(:status as text) is null or l.status = :status)
                        order by l.created_at desc limit 200""")
                .param("site", siteId).param("status", status).query().listOfRows();
    }

    static final List<String> TRACKING = List.of("PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "EXCEPTION");

    /**
     * Carrier tracking (ADR-0025): a status on the load, set by the carrier's webhook (through the integration
     * client) or by hand. No route planning or freight audit: this is not a TMS.
     */
    @Transactional
    public Map<String, Object> track(String siteId, String loadNo, String status, String trackingNo, String detail) {
        String s = status == null ? null : status.trim().toUpperCase();
        if (s == null || !TRACKING.contains(s)) {
            throw ApiException.badRequest("OUT_TRACKING_INVALID", "status is one of " + TRACKING);
        }
        int n = jdbc.sql("""
                        update shipment_load set tracking_status = :s, tracking_no = coalesce(:no, tracking_no),
                            tracking_detail = :d, tracking_updated_at = now()
                        where site_id = :site and load_no = :load""")
                .param("s", s).param("no", trackingNo == null || trackingNo.isBlank() ? null : trackingNo.trim())
                .param("d", detail).param("site", siteId).param("load", loadNo).update();
        if (n == 0) {
            throw unknown(loadNo);
        }
        return detail(siteId, loadNo);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String siteId, String loadNo) {
        Map<String, Object> header = jdbc.sql("""
                        select id, load_no, carrier_scac, door, trailer_no, status, seal_no, bol_no, created_by, created_at,
                               closed_by, closed_at, tracking_no, tracking_status, tracking_detail, tracking_updated_at
                        from shipment_load where site_id = :site and load_no = :no""")
                .param("site", siteId).param("no", loadNo).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> unknown(loadNo));
        Map<String, Object> out = new HashMap<>(header);
        out.put("orders", jdbc.sql("""
                        select o.erp_doc_no, o.status, o.carrier_scac, o.erp_document,
                               (select count(*) from carton k where k.order_id = o.id) as cartons
                        from outbound_order o where o.load_id = :l order by o.erp_doc_no""")
                .param("l", header.get("id")).query().listOfRows());
        return out;
    }

    private Load lock(String siteId, String loadNo) {
        return jdbc.sql("select id, load_no, carrier_scac, status from shipment_load where site_id = :site and load_no = :no for update")
                .param("site", siteId).param("no", loadNo)
                .query((rs, n) -> new Load(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional().orElseThrow(() -> unknown(loadNo));
    }

    private static ApiException unknown(String loadNo) {
        return ApiException.notFound("OUT_LOAD_UNKNOWN", "No load " + loadNo);
    }

    /** A scanned GS1-128 SSCC carries the application identifier 00: "(00)" + 18 digits, or 20 digits. */
    static String normaliseSscc(String scan) {
        String s = scan.trim();
        if (s.startsWith("(00)")) {
            return s.substring(4);
        }
        return s.length() == 20 && s.startsWith("00") ? s.substring(2) : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
