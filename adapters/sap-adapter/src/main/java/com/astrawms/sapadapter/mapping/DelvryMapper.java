package com.astrawms.sapadapter.mapping;

import com.astrawms.common.contracts.IntegrationContracts.ReceiptExpectation;
import com.astrawms.sapadapter.sap.Delvry07;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** DELVRY07 inbound delivery → canonical ReceiptExpectation v3 (ISD IF-IB-001 §5 field mapping). */
public final class DelvryMapper {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    /** Plant configuration from the adapter site map. */
    public record Plant(String werks, String siteId, ZoneId timeZone, String defaultOwner) {
    }

    public record Mapped(String siteId, String ownerId, ReceiptExpectation expectation) {
    }

    private DelvryMapper() {
    }

    public static String plantOf(Delvry07 idoc) {
        if (idoc.e1edl20() == null || blank(idoc.e1edl20().vbeln())) {
            throw new MappingException("DELVRY_NO_HEADER", "E1EDL20-VBELN is missing");
        }
        String werks = idoc.e1edl20().werks();
        if (blank(werks) && idoc.e1edl24() != null && !idoc.e1edl24().isEmpty()) {
            werks = idoc.e1edl24().getFirst().werks();
        }
        if (blank(werks)) {
            throw new MappingException("DELVRY_NO_PLANT", "Receiving plant (WERKS) is missing");
        }
        return werks.trim();
    }

    public static Mapped map(Delvry07 idoc, Plant plant, Instant receivedAt) {
        Delvry07.E1edl20 h = idoc.e1edl20();
        String action = action(idoc);
        List<ReceiptExpectation.Line> lines = new ArrayList<>();
        for (Delvry07.E1edl24 item : nullSafe(idoc.e1edl24())) {
            String uom = SapCodes.uomFromSap(item.vrkme()).orElseThrow(() -> new MappingException(
                    "DELVRY_UOM_UNMAPPED", "Unit " + item.vrkme() + " of item " + item.posnr() + " has no canonical mapping"));
            lines.add(new ReceiptExpectation.Line(
                    item.posnr().trim(), plant.defaultOwner(), item.matnr().trim(), decimal(item.lfimg(), "LFIMG"), uom,
                    trimToNull(item.charg()), trimToNull(item.lichn()),
                    blank(item.vgbel()) ? null : new ReceiptExpectation.PoRef(item.vgbel().trim(), trimToNull(item.vgpos()), null),
                    SapCodes.stockType(item.insmk()),
                    blank(item.uebto()) ? null : decimal(item.uebto(), "UEBTO"),
                    blank(item.untto()) ? null : decimal(item.untto(), "UNTTO")));
        }
        if (lines.isEmpty() && !"DELETE".equals(action)) {
            throw new MappingException("DELVRY_NO_ITEMS", "Delivery " + h.vbeln() + " has no E1EDL24 items");
        }
        List<ReceiptExpectation.HandlingUnit> hus = new ArrayList<>();
        for (Delvry07.E1edl37 hu : nullSafe(idoc.e1edl37())) {
            List<ReceiptExpectation.HuContent> contents = new ArrayList<>();
            for (Delvry07.E1edl44 c : nullSafe(hu.e1edl44())) {
                contents.add(new ReceiptExpectation.HuContent(c.posnr().trim(), decimal(c.vemng(), "VEMNG"),
                        SapCodes.uomFromSap(c.vemeh()).orElse(c.vemeh()), trimToNull(c.charg())));
            }
            hus.add(new ReceiptExpectation.HandlingUnit(sscc(hu.exidv()), trimToNull(hu.vhilm()), contents));
        }
        ReceiptExpectation expectation = new ReceiptExpectation(
                h.vbeln().trim(), SapCodes.docType(h.lfart()), action, revision(idoc), idoc.docnum(),
                partner(idoc, "LF").orElse(null), null, null, partner(idoc, "SP").orElse(null),
                deliveryDate(idoc, plant.timeZone()).orElse(receivedAt),
                trimToNull(h.lifex()), trimToNull(h.bolnr()), trimToNull(h.traid()), null,
                lines, hus, receivedAt);
        return new Mapped(plant.siteId(), plant.defaultOwner(), expectation);
    }

    static String action(Delvry07 idoc) {
        boolean deletion = nullSafe(idoc.e1edl18()).stream().anyMatch(s -> "DEL".equals(trimToNull(s.qualf())));
        if (deletion) {
            return "DELETE";
        }
        if (Delvry07.SAVE_REPLICA.equals(idoc.mestyp())) {
            return "CREATE";
        }
        if (Delvry07.CHANGE.equals(idoc.mestyp())) {
            return "CHANGE";
        }
        throw new MappingException("DELVRY_MESSAGE_TYPE", "Unsupported message type " + idoc.mestyp());
    }

    /** IDoc numbers are assigned monotonically by SAP, so they order versions of the same delivery. */
    static long revision(Delvry07 idoc) {
        try {
            return Long.parseLong(idoc.docnum().trim());
        } catch (RuntimeException e) {
            throw new MappingException("DELVRY_DOCNUM", "DOCNUM " + idoc.docnum() + " is not numeric");
        }
    }

    private static Optional<String> partner(Delvry07 idoc, String qualifier) {
        return nullSafe(idoc.e1adrm1()).stream().filter(p -> qualifier.equals(trimToNull(p.partnerQ())))
                .map(p -> trimToNull(p.partnerId())).filter(v -> v != null).findFirst();
    }

    private static Optional<Instant> deliveryDate(Delvry07 idoc, ZoneId zone) {
        return nullSafe(idoc.e1edt13()).stream().filter(d -> "007".equals(trimToNull(d.qualf())) && !blank(d.ntanf()))
                .findFirst()
                .map(d -> LocalDate.parse(d.ntanf().trim(), DATE)
                        .atTime(blank(d.ntanz()) || "000000".equals(d.ntanz().trim()) ? LocalTime.NOON
                                : LocalTime.parse(d.ntanz().trim(), TIME))
                        .atZone(zone).toInstant());
    }

    /**
     * Canonical SSCC is 18 digits without application identifier (ISD-00 §3.2). SAP EXIDV frequently stores the
     * GS1-128 form with AI "00" (20 characters), which is stripped here.
     */
    static String sscc(String exidv) {
        String v = exidv == null ? "" : exidv.trim();
        if (v.length() == 20 && v.startsWith("00")) {
            v = v.substring(2);
        }
        if (!v.matches("[0-9]{18}")) {
            throw new MappingException("DELVRY_SSCC_INVALID", "EXIDV " + exidv + " is not an 18-digit SSCC");
        }
        return v;
    }

    private static BigDecimal decimal(String value, String field) {
        try {
            return new BigDecimal(value.trim());
        } catch (RuntimeException e) {
            throw new MappingException("DELVRY_NUMBER", field + " value '" + value + "' is not a number");
        }
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static String trimToNull(String v) {
        return blank(v) ? null : v.trim();
    }
}
