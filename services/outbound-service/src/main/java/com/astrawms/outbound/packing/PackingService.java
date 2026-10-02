package com.astrawms.outbound.packing;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pack station (§5.1): an order's picked quantities are packed into cartons. Each carton gets a GS1 SSCC; items are
 * scanned in (never more than picked and not yet packed, SHP-001); closing records the weight and fetches the carrier
 * label and tracking number. Stock stays on the order's pick LPN at staging until the order ships.
 */
@Service
public class PackingService {

    private final JdbcClient jdbc;
    private final CarrierGateway carriers;
    private final JsonMapper json;
    private final Clock clock;
    private final String companyPrefix;

    public PackingService(JdbcClient jdbc, CarrierGateway carriers, JsonMapper json, Clock clock,
                          @Value("${astra.outbound.gs1-company-prefix:0614141}") String companyPrefix) {
        this.jdbc = jdbc;
        this.carriers = carriers;
        this.json = json;
        this.clock = clock;
        this.companyPrefix = companyPrefix;
    }

    private record Order(UUID id, String status, String carrier, String shipTo) {
    }

    /** GS1 SSCC: extension digit + company prefix + serial reference (17 digits) + mod-10 check digit. */
    static String sscc(String companyPrefix, long serial) {
        String base = "0" + companyPrefix;
        String body = base + String.format("%0" + (17 - base.length()) + "d", serial % (long) Math.pow(10, 17 - base.length()));
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            int d = body.charAt(body.length() - 1 - i) - '0';
            sum += i % 2 == 0 ? d * 3 : d;
        }
        return body + ((10 - sum % 10) % 10);
    }

    @Transactional
    public Map<String, Object> openCarton(String siteId, String erpDocNo, String cartonType) {
        Order o = order(siteId, erpDocNo, true);
        if (!List.of("PICKED", "RELEASED").contains(o.status())) {
            throw ApiException.unprocessable("OUT_NOT_PACKABLE", "Order " + erpDocNo + " is " + o.status());
        }
        long serial = jdbc.sql("select nextval('carton_serial_seq')").query(Long.class).single();
        String sscc = sscc(companyPrefix, serial);
        jdbc.sql("""
                        insert into carton (id, tenant_id, site_id, order_id, sscc, carton_type, status, packed_by, created_at)
                        values (:id, :t, :site, :o, :sscc, :type, 'OPEN', :user, :now)""")
                .param("id", UUID.randomUUID()).param("t", TenantContext.tenantId()).param("site", siteId)
                .param("o", o.id()).param("sscc", sscc).param("type", cartonType == null || cartonType.isBlank() ? "BOX" : cartonType)
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        return carton(siteId, sscc);
    }

    /** Scan-to-verify (SHP-001): adds packed quantity of an order line to an open carton. */
    @Transactional
    public Map<String, Object> pack(String siteId, String sscc, String erpLineRef, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw ApiException.badRequest("OUT_PACK_QTY", "Quantity must be positive");
        }
        record C(UUID id, UUID orderId, String status) {
        }
        C c = jdbc.sql("select id, order_id, status from carton where site_id = :site and sscc = :sscc for update")
                .param("site", siteId).param("sscc", sscc)
                .query((rs, n) -> new C(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)))
                .optional().orElseThrow(() -> unknownCarton(sscc));
        if (!"OPEN".equals(c.status())) {
            throw ApiException.unprocessable("OUT_CARTON_CLOSED", "Carton " + sscc + " is closed");
        }
        record L(String item, BigDecimal picked, BigDecimal packed) {
        }
        L line = jdbc.sql("""
                        select l.item_no, l.qty_picked,
                               coalesce((select sum(ci.qty) from carton_item ci join carton k on k.id = ci.carton_id
                                         where k.order_id = l.order_id and ci.erp_line_ref = l.erp_line_ref), 0)
                        from outbound_line l where l.order_id = :o and l.erp_line_ref = :line for update of l""")
                .param("o", c.orderId()).param("line", erpLineRef)
                .query((rs, n) -> new L(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)))
                .optional().orElseThrow(() -> ApiException.unprocessable("OUT_LINE_UNKNOWN", "Order has no line " + erpLineRef));
        BigDecimal open = line.picked().subtract(line.packed());
        if (qty.compareTo(open) > 0) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "OUT_PACK_EXCEEDS_PICKED",
                    "Only " + open.stripTrailingZeros().toPlainString() + " of line " + erpLineRef + " are picked and not packed",
                    Map.of("openToPack", open));
        }
        jdbc.sql("""
                        insert into carton_item (carton_id, tenant_id, erp_line_ref, item_no, qty) values (:c, :t, :line, :item, :qty)
                        on conflict (carton_id, erp_line_ref) do update set qty = carton_item.qty + excluded.qty""")
                .param("c", c.id()).param("t", TenantContext.tenantId()).param("line", erpLineRef).param("item", line.item())
                .param("qty", qty).update();
        return carton(siteId, sscc);
    }

    /** Closes a carton (SHP-001): weight captured, carrier label and tracking number fetched. */
    @Transactional
    public Map<String, Object> close(String siteId, String sscc, BigDecimal weightKg) {
        record C(UUID id, UUID orderId, String status, String orderRef) {
        }
        C c = jdbc.sql("""
                        select k.id, k.order_id, k.status, o.erp_doc_no from carton k join outbound_order o on o.id = k.order_id
                        where k.site_id = :site and k.sscc = :sscc for update of k""")
                .param("site", siteId).param("sscc", sscc)
                .query((rs, n) -> new C(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)))
                .optional().orElseThrow(() -> unknownCarton(sscc));
        if ("CLOSED".equals(c.status())) {
            return carton(siteId, sscc);
        }
        boolean empty = !jdbc.sql("select exists (select 1 from carton_item where carton_id = :c)").param("c", c.id())
                .query(Boolean.class).single();
        if (empty) {
            throw ApiException.unprocessable("OUT_CARTON_EMPTY", "Carton " + sscc + " has nothing packed");
        }
        Order o = orderById(c.orderId());
        JsonNode shipTo = o.shipTo() == null ? null : json.readTree(o.shipTo());
        CarrierGateway.Label label = carriers.label(new CarrierGateway.Parcel(o.carrier(), sscc, c.orderRef(),
                text(shipTo, "name"), text(shipTo, "city"), text(shipTo, "country"), weightKg));
        // ADR-0021: a RETAIL owner gets a content label (what is in the carton) after the carrier label.
        String zpl = label.zpl();
        if ("RETAIL".equals(ownerRules(c.orderId()).get("label_template"))) {
            zpl = zpl + "\n" + contentLabel(c.id(), sscc, c.orderRef());
        }
        jdbc.sql("""
                        update carton set status = 'CLOSED', weight_kg = :w, carrier_scac = :scac, tracking_no = :tracking,
                            label = :label, closed_at = :now where id = :id""")
                .param("w", weightKg).param("scac", label.carrierScac()).param("tracking", label.trackingNo())
                .param("label", zpl).param("now", Timestamp.from(clock.instant())).param("id", c.id()).update();
        return carton(siteId, sscc);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> packView(String siteId, String erpDocNo) {
        Order o = order(siteId, erpDocNo, false);
        Map<String, Object> view = new HashMap<>();
        view.put("erpDocNo", erpDocNo);
        view.put("status", o.status());
        view.put("carrierScac", o.carrier());
        view.put("lines", jdbc.sql("""
                        select l.erp_line_ref, l.item_no, l.qty_picked, l.base_uom,
                               coalesce((select sum(ci.qty) from carton_item ci join carton k on k.id = ci.carton_id
                                         where k.order_id = l.order_id and ci.erp_line_ref = l.erp_line_ref), 0) as qty_packed
                        from outbound_line l where l.order_id = :o order by l.erp_line_ref""")
                .param("o", o.id()).query().listOfRows());
        view.put("cartons", jdbc.sql("""
                        select k.sscc, k.carton_type, k.status, k.weight_kg, k.carrier_scac, k.tracking_no,
                               (select json_agg(json_build_object('lineRef', ci.erp_line_ref, 'itemNo', ci.item_no, 'qty', ci.qty))
                                from carton_item ci where ci.carton_id = k.id)::text as items
                        from carton k where k.order_id = :o order by k.created_at""")
                .param("o", o.id()).query().listOfRows());
        return view;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> carton(String siteId, String sscc) {
        Map<String, Object> c = jdbc.sql("""
                        select k.sscc, k.carton_type, k.status, k.weight_kg, k.carrier_scac, k.tracking_no, k.label,
                               o.erp_doc_no, o.id as order_id, o.ship_to ->> 'name' as ship_to_name
                        from carton k join outbound_order o on o.id = k.order_id where k.site_id = :site and k.sscc = :sscc""")
                .param("site", siteId).param("sscc", sscc).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> unknownCarton(sscc));
        Map<String, Object> out = new HashMap<>(c);
        List<Map<String, Object>> items = jdbc.sql("""
                        select ci.erp_line_ref, ci.item_no, ci.qty from carton_item ci join carton k on k.id = ci.carton_id
                        where k.site_id = :site and k.sscc = :sscc order by ci.erp_line_ref""")
                .param("site", siteId).param("sscc", sscc).query().listOfRows();
        out.put("items", items);
        // ADR-0021: the owner's rules: a pack list in the carton, and which label template applies.
        Map<String, Object> rules = ownerRules((UUID) c.get("order_id"));
        out.remove("order_id");
        out.put("owner_id", rules.get("owner_id"));
        out.put("label_template", rules.getOrDefault("label_template", "STANDARD"));
        if (Boolean.TRUE.equals(rules.get("pack_list"))) {
            StringBuilder list = new StringBuilder("PACK LIST\nDelivery " + c.get("erp_doc_no") + "  Carton " + sscc
                    + "\nShip to " + (c.get("ship_to_name") == null ? "" : c.get("ship_to_name")) + "\n\nLine    Item                Qty\n");
            items.forEach(i -> list.append("%-8s%-20s%s%n".formatted(i.get("erp_line_ref"), i.get("item_no"),
                    ((BigDecimal) i.get("qty")).stripTrailingZeros().toPlainString())));
            out.put("pack_list", list.toString());
        }
        return out;
    }

    /** The owner rules of an order (its first line's owner); empty when the owner has none. */
    private Map<String, Object> ownerRules(UUID orderId) {
        return jdbc.sql("""
                        select l.owner_id, p.ship_complete, coalesce(p.pack_list, false) as pack_list,
                               coalesce(p.label_template, 'STANDARD') as label_template
                        from outbound_line l left join outbound_owner_policy p on p.owner_id = l.owner_id
                        where l.order_id = :o order by l.erp_line_ref limit 1""")
                .param("o", orderId).query().listOfRows().stream().findFirst().orElse(Map.of());
    }

    /** A ZPL content label: delivery, carton and what it holds. */
    private String contentLabel(UUID cartonId, String sscc, String orderRef) {
        StringBuilder zpl = new StringBuilder("^XA\n^FO40,40^A0N,36,36^FDCONTENTS^FS\n^FO40,90^A0N,26,26^FDDelivery "
                + orderRef + "^FS\n^FO40,125^A0N,26,26^FDSSCC " + sscc + "^FS\n");
        int y = 175;
        for (Map<String, Object> i : jdbc.sql("select item_no, qty from carton_item where carton_id = :c order by erp_line_ref")
                .param("c", cartonId).query().listOfRows()) {
            zpl.append("^FO40,%d^A0N,26,26^FD%s x %s^FS%n".formatted(y, i.get("item_no"),
                    ((BigDecimal) i.get("qty")).stripTrailingZeros().toPlainString()));
            y += 35;
        }
        return zpl.append("^XZ").toString();
    }

    /**
     * SHP-002: with packing required at the site, everything picked must be packed in closed cartons before the order
     * ships or is loaded.
     */
    public void requirePacked(UUID orderId, String siteId, String erpDocNo) {
        boolean required = jdbc.sql("select coalesce((select pack_required from outbound_site_config where site_id = :site), false)")
                .param("site", siteId).query(Boolean.class).single();
        if (!required) {
            return;
        }
        boolean complete = jdbc.sql("""
                        select not exists (select 1 from outbound_line l where l.order_id = :o and l.qty_picked >
                                   coalesce((select sum(ci.qty) from carton_item ci join carton k on k.id = ci.carton_id
                                             where k.order_id = l.order_id and ci.erp_line_ref = l.erp_line_ref and k.status = 'CLOSED'), 0))
                           and not exists (select 1 from carton k where k.order_id = :o and k.status = 'OPEN')""")
                .param("o", orderId).query(Boolean.class).single();
        if (!complete) {
            throw ApiException.unprocessable("OUT_NOT_PACKED",
                    "Order " + erpDocNo + " is not completely packed in closed cartons (packing is required at " + siteId + ")");
        }
    }

    private Order order(String siteId, String erpDocNo, boolean lock) {
        return jdbc.sql("select id, status, carrier_scac, ship_to::text from outbound_order where site_id = :site and erp_doc_no = :doc"
                        + (lock ? " for update" : ""))
                .param("site", siteId).param("doc", erpDocNo)
                .query((rs, n) -> new Order(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional().orElseThrow(() -> ApiException.notFound("OUT_ORDER_UNKNOWN", "No outbound order " + erpDocNo));
    }

    private Order orderById(UUID id) {
        return jdbc.sql("select id, status, carrier_scac, ship_to::text from outbound_order where id = :id")
                .param("id", id)
                .query((rs, n) -> new Order(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)))
                .single();
    }

    private static String text(JsonNode node, String field) {
        return node == null || !node.hasNonNull(field) ? null : node.get(field).asString();
    }

    private static ApiException unknownCarton(String sscc) {
        return ApiException.notFound("OUT_CARTON_UNKNOWN", "No carton " + sscc);
    }
}
