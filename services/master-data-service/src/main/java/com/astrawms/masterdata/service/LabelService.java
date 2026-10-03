package com.astrawms.masterdata.service;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Barcode labels (ADR-0022) as ZPL II for Zebra-compatible printers, 4 x 2 in (812 x 406 dots at 203 dpi):
 * <ul>
 *   <li><b>Location</b>: the location ID as Code 128 and in large text, with its check digit (scanned or keyed on
 *       RF to confirm the bin) and zone;</li>
 *   <li><b>Item</b>: GS1-128 {@code (01)} GTIN of the unit when it has one, otherwise the item number as Code 128,
 *       with the description;</li>
 *   <li><b>LPN</b>: pre-printed pallet labels from the site's number series, Code 128.</li>
 * </ul>
 * Each request returns the labels (for printing from the browser) and the ZPL; with a printer it is also sent to the
 * printer's raw port (TCP 9100), which works where AstraWMS can reach the printer (on-premises or over a VPN).
 */
@Service
public class LabelService {

    static final int MAX_LABELS = 500;

    private final JdbcClient jdbc;
    private final Clock clock;

    public LabelService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** One label: the barcode content, its symbology, the human-readable lines, and its ZPL. */
    public record Label(String barcode, String symbology, List<String> lines, String zpl) {
    }

    public record Labels(int count, List<Label> labels, String zpl, String printedOn) {
    }

    // ------------------------------------------------------------------------------------------ printers

    public record PrinterRequest(String host, Integer port, Integer dpi, String purpose) {
    }

    public List<Map<String, Object>> printers(String siteId) {
        return jdbc.sql("select name, host, port, dpi, purpose, updated_by, updated_at from label_printer where site_id = :site order by name")
                .param("site", siteId).query().listOfRows();
    }

    @Transactional
    public List<Map<String, Object>> putPrinter(String siteId, String name, PrinterRequest r) {
        if (r.host() == null || r.host().isBlank() || !r.host().trim().matches("[A-Za-z0-9.:\\-]+")) {
            throw ApiException.badRequest("MD_PRINTER_INVALID", "host (name or IP address) is required");
        }
        int port = r.port() == null ? 9100 : r.port();
        if (port < 9100 || port > 9199) {
            // Only raw print ports: the service must not be pointed at other services' ports.
            throw ApiException.badRequest("MD_PRINTER_INVALID", "port must be a raw print port (9100-9199)");
        }
        int dpi = r.dpi() == null ? 203 : r.dpi();
        if (!List.of(203, 300, 600).contains(dpi)) {
            throw ApiException.badRequest("MD_PRINTER_INVALID", "dpi must be 203, 300 or 600");
        }
        jdbc.sql("""
                        insert into label_printer (tenant_id, site_id, name, host, port, dpi, purpose, updated_by, updated_at)
                        values (:t, :site, :name, :host, :port, :dpi, :purpose, :user, :now)
                        on conflict (tenant_id, site_id, name) do update set host = excluded.host, port = excluded.port,
                            dpi = excluded.dpi, purpose = excluded.purpose, updated_by = excluded.updated_by,
                            updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("site", siteId).param("name", name.trim().toUpperCase())
                .param("host", r.host().trim()).param("port", port).param("dpi", dpi)
                .param("purpose", r.purpose() == null || r.purpose().isBlank() ? null : r.purpose().trim().toUpperCase())
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        return printers(siteId);
    }

    @Transactional
    public void deletePrinter(String siteId, String name) {
        jdbc.sql("delete from label_printer where site_id = :site and name = :name")
                .param("site", siteId).param("name", name.trim().toUpperCase()).update();
    }

    // ------------------------------------------------------------------------------------------ labels

    /** Location labels: listed IDs, or a zone, or an ID range (inclusive), in pick-path order. */
    public Labels locations(String siteId, List<String> ids, String zoneId, String from, String to, String printer) {
        record Loc(String id, String zone, String checkDigit, String type) {
        }
        List<Loc> locs = jdbc.sql("""
                        select location_id, zone_id, check_digit, location_type from location
                        where site_id = :site
                          and (:all or location_id in (:ids))
                          and (cast(:zone as text) is null or zone_id = :zone)
                          and (cast(:from as text) is null or location_id >= :from)
                          and (cast(:to as text) is null or location_id <= :to)
                        order by pick_seq nulls last, location_id limit :max""")
                .param("site", siteId).param("all", ids == null || ids.isEmpty())
                .param("ids", ids == null || ids.isEmpty() ? List.of("") : ids.stream().map(s -> s.trim().toUpperCase()).toList())
                .param("zone", blankUpper(zoneId)).param("from", blankUpper(from)).param("to", blankUpper(to))
                .param("max", MAX_LABELS + 1)
                .query((rs, n) -> new Loc(rs.getString(1), rs.getString(2), rs.getString(3).trim(), rs.getString(4))).list();
        requireSize(locs.size());
        List<Label> labels = new ArrayList<>();
        for (Loc l : locs) {
            String zpl = """
                    ^XA^CI28^PW812^LL406
                    ^FO30,25^A0N,90,80^FD%s^FS
                    ^FO620,30^GB160,110,4^FS^FO640,45^A0N,85,85^FD%s^FS
                    ^FO30,140^BY3^BCN,150,N,N,N^FD%s^FS
                    ^FO30,320^A0N,40,36^FDZone %s · %s^FS
                    ^FO640,150^A0N,26,24^FDcheck^FS
                    ^XZ""".formatted(zpl(l.id()), zpl(l.checkDigit()), zpl(l.id()), zpl(l.zone()), zpl(l.type()));
            labels.add(new Label(l.id(), "CODE128", List.of(l.id(), "Check " + l.checkDigit(), "Zone " + l.zone()), zpl));
        }
        return finish(siteId, labels, printer, "LOCATION");
    }

    public record ItemLabelRequest(String ownerId, List<String> itemNos, String uom, Integer copies, String printer) {
    }

    /** Item labels: GS1-128 GTIN of the chosen unit (base unit by default), else the item number. */
    public Labels items(ItemLabelRequest r, String siteId) {
        if (r.ownerId() == null || r.itemNos() == null || r.itemNos().isEmpty()) {
            throw ApiException.badRequest("MD_LABEL_INVALID", "ownerId and itemNos are required");
        }
        int copies = r.copies() == null ? 1 : Math.max(1, r.copies());
        requireSize(r.itemNos().size() * copies);
        List<Label> labels = new ArrayList<>();
        for (String itemNo : r.itemNos()) {
            record Item(String description, String baseUom) {
            }
            Item item = jdbc.sql("select description, base_uom from item where owner_id = :o and item_no = :i")
                    .param("o", r.ownerId().trim().toUpperCase()).param("i", itemNo.trim())
                    .query((rs, n) -> new Item(rs.getString(1), rs.getString(2))).optional()
                    .orElseThrow(() -> ApiException.notFound("MD_ITEM_UNKNOWN", "No item " + itemNo + " of " + r.ownerId()));
            String uom = r.uom() == null || r.uom().isBlank() ? item.baseUom() : r.uom().trim().toUpperCase();
            String gtin = jdbc.sql("select gtin from item_uom where owner_id = :o and item_no = :i and uom = :u")
                    .param("o", r.ownerId().trim().toUpperCase()).param("i", itemNo.trim()).param("u", uom)
                    .query(String.class).optional().orElse(null);
            String desc = item.description().length() > 40 ? item.description().substring(0, 40) : item.description();
            Label label;
            if (gtin != null) {
                String gtin14 = "0".repeat(14 - gtin.length()) + gtin;
                String zpl = """
                        ^XA^CI28^PW812^LL406
                        ^FO30,25^A0N,50,44^FD%s^FS
                        ^FO30,85^A0N,34,30^FD%s · %s^FS
                        ^FO30,140^BY3^BCN,150,Y,N,N,D^FD(01)%s^FS
                        ^XZ""".formatted(zpl(itemNo), zpl(desc), zpl(uom), gtin14);
                label = new Label("(01)" + gtin14, "GS1-128", List.of(itemNo, desc, uom), zpl);
            } else {
                String zpl = """
                        ^XA^CI28^PW812^LL406
                        ^FO30,25^A0N,50,44^FD%s^FS
                        ^FO30,85^A0N,34,30^FD%s · %s^FS
                        ^FO30,140^BY3^BCN,150,Y,N,N^FD%s^FS
                        ^XZ""".formatted(zpl(itemNo), zpl(desc), zpl(uom), zpl(itemNo));
                label = new Label(itemNo, "CODE128", List.of(itemNo, desc, uom), zpl);
            }
            for (int c = 0; c < copies; c++) {
                labels.add(label);
            }
        }
        return finish(siteId, labels, r.printer(), "ITEM");
    }

    /** Pre-printed LPN labels: the next {@code count} numbers of the site's series ({@code <prefix><9 digits>}). */
    @Transactional
    public Labels lpns(String siteId, int count, String printer) {
        if (count < 1) {
            throw ApiException.badRequest("MD_LABEL_INVALID", "count must be at least 1");
        }
        requireSize(count);
        jdbc.sql("""
                        insert into lpn_series (tenant_id, site_id, prefix, next_number, updated_at)
                        values (:t, :site, :prefix, 1, :now) on conflict (tenant_id, site_id) do nothing""")
                .param("t", TenantContext.tenantId()).param("site", siteId)
                .param("prefix", "L" + siteId.replaceAll("[^A-Za-z0-9]", "").toUpperCase())
                .param("now", Timestamp.from(clock.instant())).update();
        record Series(String prefix, long first) {
        }
        Series s = jdbc.sql("""
                        update lpn_series set next_number = next_number + :n, updated_at = :now where site_id = :site
                        returning prefix, next_number - :n""")
                .param("n", count).param("now", Timestamp.from(clock.instant())).param("site", siteId)
                .query((rs, n) -> new Series(rs.getString(1), rs.getLong(2))).single();
        List<Label> labels = new ArrayList<>();
        for (long i = s.first(); i < s.first() + count; i++) {
            String lpn = s.prefix() + "%09d".formatted(i);
            String zpl = """
                    ^XA^CI28^PW812^LL406
                    ^FO30,25^A0N,40,36^FDLPN · %s^FS
                    ^FO30,80^BY3^BCN,200,N,N,N^FD%s^FS
                    ^FO30,300^A0N,80,70^FD%s^FS
                    ^XZ""".formatted(zpl(siteId), lpn, lpn);
            labels.add(new Label(lpn, "CODE128", List.of(lpn, "Site " + siteId), zpl));
        }
        return finish(siteId, labels, printer, "LPN");
    }

    private Labels finish(String siteId, List<Label> labels, String printer, String purpose) {
        String zpl = String.join("\n", labels.stream().map(Label::zpl).toList());
        String printedOn = null;
        if (printer != null && !printer.isBlank()) {
            Map<String, Object> p = printer(siteId, printer, purpose);
            send(String.valueOf(p.get("host")), ((Number) p.get("port")).intValue(), zpl);
            printedOn = String.valueOf(p.get("name"));
        }
        return new Labels(labels.size(), labels, zpl, printedOn);
    }

    /** The named printer, or with "DEFAULT" the site's printer for this label purpose. */
    private Map<String, Object> printer(String siteId, String name, String purpose) {
        boolean byPurpose = "DEFAULT".equalsIgnoreCase(name.trim());
        return jdbc.sql("""
                        select name, host, port from label_printer where site_id = :site
                          and (case when :byPurpose then purpose = :purpose else name = :name end)
                        order by name limit 1""")
                .param("site", siteId).param("byPurpose", byPurpose).param("purpose", purpose)
                .param("name", name.trim().toUpperCase()).query().listOfRows().stream().findFirst()
                .map(LinkedHashMap::new)
                .orElseThrow(() -> ApiException.notFound("MD_PRINTER_UNKNOWN", byPurpose
                        ? "No " + purpose.toLowerCase() + " label printer set up at " + siteId : "No printer " + name + " at " + siteId));
    }

    void send(String host, int port, String zpl) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(zpl.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MD_PRINTER_UNREACHABLE",
                    "Printer " + host + ":" + port + " not reachable (" + e.getMessage() + ")");
        }
    }

    private static void requireSize(int n) {
        if (n == 0) {
            throw ApiException.unprocessable("MD_LABEL_NONE", "Nothing to print");
        }
        if (n > MAX_LABELS) {
            throw ApiException.unprocessable("MD_LABEL_TOO_MANY", "At most " + MAX_LABELS + " labels per request");
        }
    }

    /** Field data: ZPL control characters ^ and ~ cannot appear in ^FD. */
    private static String zpl(String v) {
        return v == null ? "" : v.replace('^', ' ').replace('~', ' ');
    }

    private static String blankUpper(String v) {
        return v == null || v.isBlank() ? null : v.trim().toUpperCase();
    }
}
