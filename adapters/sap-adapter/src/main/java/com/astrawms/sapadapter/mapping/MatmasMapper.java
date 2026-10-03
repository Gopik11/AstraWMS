package com.astrawms.sapadapter.mapping;

import com.astrawms.common.barcode.Gs1;
import com.astrawms.sapadapter.sap.Matmas05;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * MATMAS05 → AstraWMS item master (ADR-0022). SAP stays the system of record for materials; the adapter replaces
 * what SAP owns and keeps what only the WMS knows (sites at plants not in this IDoc, temperature class when SAP sends
 * none).
 * <table>
 *   <tr><th>SAP</th><th>AstraWMS</th></tr>
 *   <tr><td>MATNR (leading zeros removed when numeric)</td><td>itemNo</td></tr>
 *   <tr><td>MAKTX (English, else the first)</td><td>description</td></tr>
 *   <tr><td>MEINS, E1MARMM MEINH/UMREZ/UMREN/EAN11, dimensions, BRGEW</td><td>base UoM and units (cm, kg)</td></tr>
 *   <tr><td>MTART</td><td>itemType</td></tr>
 *   <tr><td>LVORM X → DELETED; MSTAE set → BLOCKED_PROCUREMENT</td><td>status</td></tr>
 *   <tr><td>MHDHB / MHDRZ</td><td>shelf life / minimum remaining shelf life</td></tr>
 *   <tr><td>STOFF set</td><td>hazardous</td></tr>
 *   <tr><td>E1MARCM per mapped plant: XCHPF, SERNP (any profile → FULL), MMSTA, LVORM</td><td>item site</td></tr>
 *   <tr><td>E1MBEWM STPRS (standard price, V: VERPR) / PEINH</td><td>standardCost per base unit</td></tr>
 * </table>
 */
public final class MatmasMapper {

    private MatmasMapper() {
    }

    public record Site(String siteId, boolean lotControlled, String serialControl, String status) {
    }

    public record Uom(String uom, int numerator, int denominator, String gtin, BigDecimal lengthCm, BigDecimal widthCm,
                      BigDecimal heightCm, BigDecimal grossWeightKg) {
    }

    /** The item as master data's PUT /items/{owner}/{item} takes it. */
    public record Item(String description, String baseUom, String itemType, String status, Integer shelfLifeDays,
                       Integer minRemainingShelfLifeDays, String temperatureClass, boolean hazardous, List<Site> sites,
                       List<Uom> uoms, Instant sourceChangedAt, BigDecimal standardCost) {
    }

    public record Mapped(String ownerId, String itemNo, Item item, List<String> skipped) {
    }

    public static String itemNo(String matnr) {
        String m = matnr == null ? "" : matnr.trim();
        return m.chars().allMatch(Character::isDigit) ? m.replaceFirst("^0+(?=.)", "") : m;
    }

    /**
     * @param plants    resolves a plant (WERKS) to its AstraWMS site; plants without a site are skipped
     * @param existingSites sites the item already has, kept unless this IDoc covers their plant
     * @param existingTemperature kept as the temperature class
     */
    public static Mapped map(Matmas05 idoc, Function<String, Optional<DelvryMapper.Plant>> plants,
                             List<Site> existingSites, String existingTemperature, Instant now) {
        Matmas05.E1maram m = idoc.e1maram();
        if (m == null || m.matnr() == null || m.matnr().isBlank()) {
            throw new MappingException("MATNR_MISSING", "E1MARAM-MATNR is required");
        }
        List<String> skipped = new ArrayList<>();
        String baseUom = SapCodes.uomFromSap(m.meins()).orElse(m.meins() == null ? null : m.meins().trim());
        if (baseUom == null || baseUom.isBlank()) {
            throw new MappingException("MEINS_MISSING", "E1MARAM-MEINS (base unit) is required");
        }

        Map<String, Site> sites = new LinkedHashMap<>();
        String owner = null;
        for (Matmas05.E1marcm c : m.e1marcm() == null ? List.<Matmas05.E1marcm>of() : m.e1marcm()) {
            Optional<DelvryMapper.Plant> plant = plants.apply(c.werks());
            if (plant.isEmpty()) {
                skipped.add("plant " + c.werks() + " not mapped");
                continue;
            }
            owner = owner == null ? plant.get().defaultOwner() : owner;
            boolean batch = flag(c.xchpf()) || flag(m.xchpf());
            String serial = c.sernp() == null || c.sernp().isBlank() ? "NONE" : "FULL";
            String status = flag(c.lvorm()) ? "DELETED" : c.mmsta() == null || c.mmsta().isBlank() ? "ACTIVE" : "BLOCKED_PROCUREMENT";
            sites.put(plant.get().siteId(), new Site(plant.get().siteId(), batch, serial, status));
        }
        if (sites.isEmpty()) {
            throw new MappingException("PLANT_NOT_MAPPED", "None of the material's plants is mapped to an AstraWMS site");
        }
        List<Site> allSites = new ArrayList<>(sites.values());
        for (Site s : existingSites) {
            if (!sites.containsKey(s.siteId())) {
                allSites.add(s);
            }
        }

        List<Uom> uoms = new ArrayList<>();
        for (Matmas05.E1marmm u : m.e1marmm() == null ? List.<Matmas05.E1marmm>of() : m.e1marmm()) {
            String uom = SapCodes.uomFromSap(u.meinh()).orElse(u.meinh() == null ? null : u.meinh().trim());
            if (uom == null || u.umrez() == null || u.umren() == null || u.umrez() <= 0 || u.umren() <= 0) {
                skipped.add("unit " + u.meinh() + " incomplete");
                continue;
            }
            String gtin = u.ean11() == null ? null : u.ean11().trim();
            if (gtin != null && (gtin.isEmpty() || !gtin.matches("\\d{8}|\\d{12,14}") || !Gs1.checkDigitOk(gtin))) {
                if (!gtin.isEmpty()) {
                    skipped.add("EAN " + gtin + " of " + uom + " is not a valid GTIN");
                }
                gtin = null;
            }
            boolean base = uom.equals(baseUom);
            uoms.add(new Uom(uom, base ? 1 : u.umrez(), base ? 1 : u.umren(), gtin, cm(u.laeng(), u.meabm()),
                    cm(u.breit(), u.meabm()), cm(u.hoehe(), u.meabm()), kg(u.brgew(), u.gewei())));
        }

        String description = description(m);
        String status = flag(m.lvorm()) ? "DELETED" : m.mstae() == null || m.mstae().isBlank() ? "ACTIVE" : "BLOCKED_PROCUREMENT";
        Item item = new Item(description, baseUom, m.mtart(), status, positive(m.mhdhb()), nonNegative(m.mhdrz()),
                existingTemperature, m.stoff() != null && !m.stoff().isBlank(), allSites, uoms, now, standardCost(m));
        return new Mapped(owner, itemNo(m.matnr()), item, skipped);
    }

    private static String description(Matmas05.E1maram m) {
        List<Matmas05.E1maktm> texts = m.e1maktm() == null ? List.of() : m.e1maktm();
        String text = texts.stream().filter(t -> "EN".equalsIgnoreCase(t.sprasIso()) || "E".equalsIgnoreCase(t.spras()))
                .map(Matmas05.E1maktm::maktx).findFirst()
                .orElse(texts.isEmpty() ? null : texts.getFirst().maktx());
        String d = text == null || text.isBlank() ? itemNo(m.matnr()) : text.trim();
        return d.length() > 80 ? d.substring(0, 80) : d;
    }

    private static BigDecimal standardCost(Matmas05.E1maram m) {
        if (m.e1mbewm() == null || m.e1mbewm().isEmpty()) {
            return null;
        }
        Matmas05.E1mbewm v = m.e1mbewm().getFirst();
        BigDecimal price = "V".equals(v.vprsv()) && v.verpr() != null ? v.verpr() : v.stprs();
        if (price == null) {
            return null;
        }
        BigDecimal unit = v.peinh() == null || v.peinh().signum() <= 0 ? BigDecimal.ONE : v.peinh();
        return price.divide(unit, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal cm(BigDecimal v, String unit) {
        if (v == null || v.signum() <= 0) {
            return null;
        }
        return switch (unit == null ? "CM" : unit.trim().toUpperCase()) {
            case "MM" -> v.divide(BigDecimal.TEN, 3, RoundingMode.HALF_UP);
            case "M" -> v.multiply(BigDecimal.valueOf(100));
            default -> v;
        };
    }

    private static BigDecimal kg(BigDecimal v, String unit) {
        if (v == null || v.signum() <= 0) {
            return null;
        }
        return "G".equalsIgnoreCase(unit == null ? "" : unit.trim()) ? v.divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP) : v;
    }

    private static boolean flag(String v) {
        return v != null && "X".equalsIgnoreCase(v.trim());
    }

    private static Integer positive(Integer v) {
        return v == null || v <= 0 ? null : v;
    }

    private static Integer nonNegative(Integer v) {
        return v == null || v < 0 ? null : v;
    }
}
