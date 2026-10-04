package com.astrawms.inventory.service;

import com.astrawms.common.rfid.Epc;
import com.astrawms.common.security.AccessScope;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.inventory.persistence.SerialRepository;
import com.astrawms.inventory.persistence.SerialRepository.SerialView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RFID (ADR-0027): what tags are, where they belong and what a location read says about its stock.
 * <ul>
 *   <li><b>Resolve</b>: a handheld or a fixed reader sends the EPCs it read; each becomes a unit (SGTIN: GTIN + serial,
 *       the item by its GTIN, the serial's stock record), an LPN (SSCC: the LPN with that SSCC), a location or an
 *       asset. Commissioned tags (the registry) win over the EPC's own GS1 key, so non-GS1 EPCs work too.</li>
 *   <li><b>Reconcile</b>: the reads at one location against its stock: LPNs found, missing and unexpected, read vs
 *       expected quantity per item, lot and LPN, serials not read, and the count lines an RF count can submit. An LPN
 *       tag stands for the LPN's contents; a unit tag inside a read LPN is not counted twice.</li>
 *   <li><b>Commission</b> and <b>retire</b>: bind a tag to a unit, an LPN or a bin. Without an EPC, the WMS encodes an
 *       SGTIN-96 (item GTIN + numeric serial) or an SSCC-96 (an LPN that is an SSCC) for the handheld to write.</li>
 *   <li><b>Sightings</b>: last seen time, location and user of commissioned tags (trace).</li>
 * </ul>
 * Reads are never stock movements: RFID only proposes; counts, receipts and moves go through their own commands.
 */
@Service
public class Rfid {

    /** Most reads a single request may carry (a dense pallet or a bin of tagged units). */
    public static final int MAX_READS = 2000;

    public enum Kind { ITEM, LPN, LOCATION, ASSET, UNKNOWN }

    /**
     * One read, resolved. {@code epc} is the canonical hex; {@code baseQty} is how many base units the tag stands for
     * (1 for a unit of the base UoM, 12 for a case of 12). {@code problem} says why a read is not usable as it is:
     * NOT_AN_EPC, UNREGISTERED, RETIRED, GTIN_UNKNOWN, GTIN_AMBIGUOUS, LPN_UNKNOWN, SERIAL_NOT_IN_STOCK, OWNER_DENIED.
     */
    public record Resolved(String read, String epc, String scheme, String uri, Kind kind, String gtin, String sscc,
                           String tagSerial, String ownerId, String itemNo, String uom, BigDecimal baseQty,
                           boolean serialTracked, String serialNo, String lotNo, String lpnId, String locationId,
                           String stockStatus, boolean registered, String problem) {
    }

    public record ResolveRequest(List<String> reads) {
    }

    public record ReconcileRequest(List<String> reads) {
    }

    public record SightingRequest(List<String> reads, String locationId) {
    }

    /**
     * Binds a tag. Either {@code epc} (what the tag already holds) or, to have the WMS encode one,
     * {@code companyPrefixLength} (the GS1 company prefix length of the GTIN / SSCC, 6-12) with an LPN that is an SSCC
     * or an item with a numeric serial. Exactly one of item, LPN or location.
     */
    public record CommissionRequest(String epc, String ownerId, String itemNo, String serialNo, String lpnId,
                                    String locationId, Integer companyPrefixLength, Integer filter) {
    }

    public record TagView(String epc, String scheme, String uri, String siteId, String ownerId, String itemNo,
                          String serialNo, String lpnId, String locationId, String status, String commissionedBy,
                          Instant commissionedAt, Instant lastSeenAt, String lastSeenLocation, String lastSeenBy) {
    }

    /** One stock key at the reconciled location: expected (system) vs read. */
    public record Line(String ownerId, String itemNo, String lotNo, String lpnId, BigDecimal expectedQty,
                       BigDecimal readQty, BigDecimal variance) {
    }

    /** FOUND (expected and read), MISSING (expected, not read), UNEXPECTED (read, the system has it elsewhere or not). */
    public record LpnCheck(String lpnId, String result, String systemLocation) {
    }

    public record CountLine(String ownerId, String itemNo, String lotNo, String lpnId, BigDecimal qty) {
    }

    public record Reconciliation(String locationId, int reads, int tags, List<LpnCheck> lpns, List<Line> lines,
                                 List<Resolved> unexpectedUnits, List<SerialView> serialsNotRead,
                                 List<Resolved> unresolved, List<CountLine> countLines) {
    }

    private final JdbcClient jdbc;
    private final SerialRepository serials;
    private final Clock clock;

    public Rfid(JdbcClient jdbc, SerialRepository serials, Clock clock) {
        this.jdbc = jdbc;
        this.serials = serials;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ resolve

    @Transactional(readOnly = true)
    public List<Resolved> resolve(String siteId, List<String> reads) {
        Map<String, Resolved> byEpc = new LinkedHashMap<>();
        List<Resolved> out = new ArrayList<>();
        for (String read : limit(reads)) {
            if (read == null || read.isBlank()) {
                continue;
            }
            Resolved r = resolveOne(siteId, read.strip());
            String key = r.epc() != null ? r.epc() : r.read();
            if (byEpc.putIfAbsent(key, r) == null) {        // a reader reports a tag many times: one result per tag
                out.add(r);
            }
        }
        return out;
    }

    private Resolved resolveOne(String siteId, String read) {
        Optional<Epc.Tag> decoded = Epc.parse(read);
        String hex = decoded.map(Epc.Tag::hex).orElseGet(() -> {
            String h = Epc.hexOf(read);
            return h != null && h.length() >= 24 && h.length() % 4 == 0 && h.length() <= 124 ? h : null;
        });
        Epc.Tag tag = decoded.orElse(null);
        Base base = new Base(read, hex, tag == null ? null : tag.scheme().name(), tag == null ? null : tag.uri(),
                tag == null ? null : tag.gtin(), tag == null ? null : tag.sscc(), tag == null ? null : tag.serial());
        if (hex == null) {
            return base.unknown("NOT_AN_EPC");
        }
        Optional<TagView> registered = tag(hex);
        if (registered.isPresent()) {
            TagView t = registered.get();
            if (!"ACTIVE".equals(t.status())) {
                return base.unknown("RETIRED");
            }
            if (t.lpnId() != null) {
                return lpn(siteId, base, t.lpnId(), true);
            }
            if (t.locationId() != null) {
                return base.location(t.locationId(), true);
            }
            ItemUom item = itemUom(t.ownerId(), t.itemNo(), siteId);
            return unit(siteId, base, item == null ? List.of() : List.of(item), t.serialNo(), true);
        }
        if (tag == null) {
            return base.unknown("UNREGISTERED");
        }
        return switch (tag.scheme()) {
            case SSCC -> lpn(siteId, base, tag.sscc(), false);
            case SGTIN -> unit(siteId, base, itemsByGtin(siteId, tag.gtin()), tag.serial(), false);
            case SGLN -> base.unknown("UNREGISTERED");     // bins are tagged by commissioning (location tags)
            case GRAI, GIAI, GID -> base.asset();
        };
    }

    /** What every result carries from the read itself. */
    private record Base(String read, String epc, String scheme, String uri, String gtin, String sscc, String tagSerial) {

        Resolved unknown(String problem) {
            return new Resolved(read, epc, scheme, uri, Kind.UNKNOWN, gtin, sscc, tagSerial, null, null, null, null,
                    false, null, null, null, null, null, false, problem);
        }

        Resolved asset() {
            return new Resolved(read, epc, scheme, uri, Kind.ASSET, gtin, sscc, tagSerial, null, null, null, null,
                    false, null, null, null, null, null, false, null);
        }

        Resolved location(String locationId, boolean registered) {
            return new Resolved(read, epc, scheme, uri, Kind.LOCATION, gtin, sscc, tagSerial, null, null, null, null,
                    false, null, null, null, locationId, null, registered, null);
        }
    }

    private Resolved lpn(String siteId, Base b, String lpnId, boolean registered) {
        record Lpn(String owner, String location) {
        }
        Optional<Lpn> lpn = jdbc.sql("select owner_id, location_id from lpn where site_id = :site and lpn_id = :lpn")
                .param("site", siteId).param("lpn", lpnId)
                .query((rs, n) -> new Lpn(rs.getString(1), rs.getString(2))).optional();
        if (lpn.isPresent() && !AccessScope.current().allowsOwner(lpn.get().owner())) {
            return b.unknown("OWNER_DENIED");
        }
        // An LPN that is not (or no longer) in stock keeps its id: receiving can use the tag as the new LPN.
        return new Resolved(b.read(), b.epc(), b.scheme(), b.uri(), Kind.LPN, b.gtin(), b.sscc(), b.tagSerial(),
                lpn.map(Lpn::owner).orElse(null), null, null, null, false, null, null, lpnId,
                lpn.map(Lpn::location).orElse(null), null, registered, lpn.isPresent() ? null : "LPN_UNKNOWN");
    }

    /** An item unit of measure that a tag can stand for. */
    private record ItemUom(String owner, String item, String uom, BigDecimal baseQty, boolean serialTracked) {
    }

    private Resolved unit(String siteId, Base b, List<ItemUom> candidates, String serial, boolean registered) {
        AccessScope scope = AccessScope.current();
        List<ItemUom> visible = candidates.stream().filter(c -> scope.allowsOwner(c.owner())).toList();
        if (visible.isEmpty()) {
            return b.unknown(candidates.isEmpty() ? "GTIN_UNKNOWN" : "OWNER_DENIED");
        }
        if (visible.size() > 1) {
            return b.unknown("GTIN_AMBIGUOUS");
        }
        ItemUom i = visible.getFirst();
        SerialView s = serial == null ? null
                : serials.find(i.owner(), i.item(), serial).filter(v -> "IN_STOCK".equals(v.status())).orElse(null);
        boolean atSite = s != null && siteId.equals(s.siteId());
        String problem = i.serialTracked() && serial != null && !atSite ? "SERIAL_NOT_IN_STOCK" : null;
        return new Resolved(b.read(), b.epc(), b.scheme(), b.uri(), Kind.ITEM, b.gtin(), b.sscc(), b.tagSerial(),
                i.owner(), i.item(), i.uom(), i.baseQty(), i.serialTracked(), i.serialTracked() ? serial : null,
                atSite ? emptyToNull(s.lotNo()) : null, atSite ? emptyToNull(s.lpnId()) : null,
                atSite ? s.locationId() : null, atSite ? s.stockStatus() : null, registered, problem);
    }

    /** Items with a unit of this GTIN, known at the site (GTINs are stored without leading zeros). */
    private List<ItemUom> itemsByGtin(String siteId, String gtin) {
        return jdbc.sql("""
                        select u.owner_id, u.item_no, u.uom, u.numerator, u.denominator, i.serial_control
                        from ref_item_uom u
                        join ref_item i on i.owner_id = u.owner_id and i.item_no = u.item_no and i.site_id = :site
                        where u.gtin = :gtin
                        order by u.owner_id, u.item_no, u.uom""")
                .param("site", siteId).param("gtin", gtin.replaceFirst("^0+(?=.)", ""))
                .query((rs, n) -> new ItemUom(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4).divide(rs.getBigDecimal(5), 3, RoundingMode.HALF_UP),
                        tracked(rs.getString(6))))
                .list();
    }

    /** A registered unit tag: one base unit of the item; null when the item is not known at the site. */
    private ItemUom itemUom(String owner, String item, String siteId) {
        return jdbc.sql("""
                        select base_uom, serial_control from ref_item
                        where owner_id = :o and item_no = :i and site_id = :site""")
                .param("o", owner).param("i", item).param("site", siteId)
                .query((rs, n) -> new ItemUom(owner, item, rs.getString(1), BigDecimal.ONE, tracked(rs.getString(2))))
                .optional().orElse(null);
    }

    private static boolean tracked(String serialControl) {
        return "INBOUND".equals(serialControl) || "FULL".equals(serialControl);
    }

    // ------------------------------------------------------------------ reconcile

    /** Compares the tags read at a location with its stock (read-only; an RF count submits the result). */
    @Transactional(readOnly = true)
    public Reconciliation reconcile(String siteId, String locationId, List<String> reads) {
        boolean known = jdbc.sql("select exists (select 1 from ref_location where site_id = :site and location_id = :loc)")
                .param("site", siteId).param("loc", locationId).query(Boolean.class).single();
        if (!known) {
            throw ApiException.notFound("INV_LOCATION_UNKNOWN", "Location " + locationId + " is not known at " + siteId);
        }
        List<Resolved> resolved = resolve(siteId, reads);
        AccessScope scope = AccessScope.current();
        record Stock(String owner, String item, String lot, String lpn, BigDecimal qty) {
        }
        List<Stock> stock = jdbc.sql("""
                        select owner_id, item_no, lot_no, lpn_id, sum(qty) from inventory_balance
                        where site_id = :site and location_id = :loc and qty > 0
                          and (:ownersAll or owner_id in (:owners))
                        group by owner_id, item_no, lot_no, lpn_id order by owner_id, item_no, lot_no, lpn_id""")
                .param("site", siteId).param("loc", locationId)
                .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                .query((rs, n) -> new Stock(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5)))
                .list();

        // LPNs: an LPN tag stands for the whole LPN.
        Set<String> expectedLpns = new LinkedHashSet<>();
        stock.stream().map(Stock::lpn).filter(l -> !l.isEmpty()).forEach(expectedLpns::add);
        Map<String, Resolved> readLpns = new LinkedHashMap<>();
        resolved.stream().filter(r -> r.kind() == Kind.LPN).forEach(r -> readLpns.putIfAbsent(r.lpnId(), r));
        List<LpnCheck> lpns = new ArrayList<>();
        expectedLpns.forEach(l -> lpns.add(new LpnCheck(l, readLpns.containsKey(l) ? "FOUND" : "MISSING", locationId)));
        readLpns.forEach((l, r) -> {
            if (!expectedLpns.contains(l)) {
                lpns.add(new LpnCheck(l, "UNEXPECTED", r.locationId()));
            }
        });

        Map<String, BigDecimal> expected = new TreeMap<>();
        Map<String, BigDecimal> read = new TreeMap<>();
        for (Stock s : stock) {
            String k = key(s.owner(), s.item(), s.lot(), s.lpn());
            expected.merge(k, s.qty(), BigDecimal::add);
            if (!s.lpn().isEmpty() && readLpns.containsKey(s.lpn())) {
                read.merge(k, s.qty(), BigDecimal::add);
            }
        }

        // Units: counted at their stock key here; a unit in a read LPN is already counted with it.
        List<Resolved> unexpectedUnits = new ArrayList<>();
        Map<String, Set<String>> serialsRead = new HashMap<>();
        for (Resolved r : resolved) {
            if (r.kind() != Kind.ITEM || "GTIN_UNKNOWN".equals(r.problem()) || r.itemNo() == null) {
                continue;
            }
            if (r.serialNo() != null) {
                serialsRead.computeIfAbsent(r.ownerId() + "|" + r.itemNo(), k -> new LinkedHashSet<>()).add(r.serialNo());
            }
            String lot;
            String lpn;
            if (r.locationId() != null && r.locationId().equals(locationId)) {
                lot = nz(r.lotNo());
                lpn = nz(r.lpnId());
                if (!lpn.isEmpty() && readLpns.containsKey(lpn)) {
                    continue;
                }
            } else {
                if (r.locationId() != null || r.serialTracked()) {
                    unexpectedUnits.add(r);                  // the system has this serial elsewhere (or nowhere)
                }
                lot = r.lotNo() != null ? r.lotNo() : onlyLooseLot(stock.stream()
                        .filter(s -> s.owner().equals(r.ownerId()) && s.item().equals(r.itemNo()))
                        .map(s -> new String[] {s.lot(), s.lpn()}).toList());
                lpn = "";
            }
            read.merge(key(r.ownerId(), r.itemNo(), lot, lpn), r.baseQty(), BigDecimal::add);
        }

        // Serials expected here but not read, for serial-tracked items whose units are tagged (one was read here),
        // outside LPNs that were read.
        List<SerialView> serialsNotRead = new ArrayList<>();
        serialsRead.forEach((ownerItem, seen) -> {
            String[] oi = ownerItem.split("\\|", 2);
            jdbc.sql("""
                            select serial_no from serial_number
                            where site_id = :site and location_id = :loc and owner_id = :o and item_no = :i
                              and status = 'IN_STOCK' order by serial_no""")
                    .param("site", siteId).param("loc", locationId).param("o", oi[0]).param("i", oi[1])
                    .query(String.class).list().stream()
                    .filter(sn -> !seen.contains(sn))
                    .map(sn -> serials.find(oi[0], oi[1], sn).orElseThrow())
                    .filter(v -> v.lpnId().isEmpty() || !readLpns.containsKey(v.lpnId()))
                    .forEach(serialsNotRead::add);
        });

        Set<String> keys = new java.util.TreeSet<>(expected.keySet());
        keys.addAll(read.keySet());
        List<Line> lines = new ArrayList<>();
        List<CountLine> countLines = new ArrayList<>();
        for (String k : keys) {
            String[] p = k.split("\\|", -1);
            BigDecimal e = expected.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal r = read.getOrDefault(k, BigDecimal.ZERO);
            lines.add(new Line(p[0], p[1], p[2], p[3], e, r, r.subtract(e)));
            if (r.signum() > 0) {
                countLines.add(new CountLine(p[0], p[1], emptyToNull(p[2]), emptyToNull(p[3]), r));
            }
        }
        List<Resolved> unresolved = resolved.stream()
                .filter(r -> r.kind() == Kind.UNKNOWN || "GTIN_UNKNOWN".equals(r.problem())).toList();
        return new Reconciliation(locationId, reads == null ? 0 : reads.size(), resolved.size(), lpns, lines,
                unexpectedUnits, serialsNotRead, unresolved, countLines);
    }

    /** The lot of an item's loose stock at the location when there is exactly one; else "" (the counter enters it). */
    private static String onlyLooseLot(List<String[]> lotAndLpn) {
        List<String> loose = lotAndLpn.stream().filter(a -> a[1].isEmpty()).map(a -> a[0]).distinct().toList();
        if (loose.size() == 1) {
            return loose.getFirst();
        }
        List<String> any = lotAndLpn.stream().map(a -> a[0]).distinct().toList();
        return any.size() == 1 ? any.getFirst() : "";
    }

    private static String key(String owner, String item, String lot, String lpn) {
        return owner + "|" + item + "|" + nz(lot) + "|" + nz(lpn);
    }

    // ------------------------------------------------------------------ registry

    /**
     * Commissions a tag: binds an EPC to a unit, an LPN or a location at the site. Idempotent: the same binding again
     * returns the tag unchanged; an active tag bound to something else is refused (retire it first).
     */
    @Transactional
    public TagView commission(String siteId, CommissionRequest r) {
        int targets = (blank(r.itemNo()) ? 0 : 1) + (blank(r.lpnId()) ? 0 : 1) + (blank(r.locationId()) ? 0 : 1);
        if (targets != 1) {
            throw ApiException.unprocessable("INV_RFID_TARGET", "A tag is for exactly one of: item, LPN, location");
        }
        String owner = trim(r.ownerId());
        String item = trim(r.itemNo());
        String serial = trim(r.serialNo());
        String lpn = trim(r.lpnId());
        String location = blank(r.locationId()) ? null : r.locationId().trim().toUpperCase();
        if (item != null) {
            if (owner == null) {
                throw ApiException.unprocessable("INV_RFID_OWNER_REQUIRED", "A unit tag needs the owner of the item");
            }
            AccessScope.current().requireOwner(owner);
            if (itemUom(owner, item, siteId) == null) {
                throw ApiException.unprocessable("INV_ITEM_UNKNOWN", "Item " + owner + "/" + item + " is not known at " + siteId);
            }
        } else if (serial != null) {
            throw ApiException.unprocessable("INV_RFID_TARGET", "A serial number needs its item");
        }
        if (location != null) {
            boolean known = jdbc.sql("select exists (select 1 from ref_location where site_id = :s and location_id = :l)")
                    .param("s", siteId).param("l", location).query(Boolean.class).single();
            if (!known) {
                throw ApiException.unprocessable("INV_LOCATION_UNKNOWN", "Location " + location + " is not known");
            }
        }
        if (lpn != null) {
            jdbc.sql("select owner_id from lpn where site_id = :s and lpn_id = :l").param("s", siteId).param("l", lpn)
                    .query(String.class).optional().ifPresent(o -> AccessScope.current().requireOwner(o));
        }

        Epc.Tag tag;
        String hex;
        if (!blank(r.epc())) {
            tag = Epc.parse(r.epc()).orElse(null);
            hex = tag != null ? tag.hex() : Epc.hexOf(r.epc());
            if (hex == null || hex.length() < 24 || hex.length() % 4 != 0 || hex.length() > 124) {
                throw ApiException.unprocessable("INV_RFID_EPC_INVALID", "EPC " + r.epc() + " is not a tag EPC (hex or EPC URI)");
            }
            checkMatches(siteId, tag, owner, item, serial, lpn);
        } else {
            tag = encode(siteId, r, owner, item, serial, lpn);
            hex = tag.hex();
        }
        String scheme = tag == null ? "RAW" : tag.scheme().name();

        Optional<TagView> existing = tag(hex);
        if (existing.isPresent() && "ACTIVE".equals(existing.get().status())) {
            TagView e = existing.get();
            if (siteId.equals(e.siteId()) && java.util.Objects.equals(e.ownerId(), item == null ? null : owner)
                    && java.util.Objects.equals(e.itemNo(), item) && java.util.Objects.equals(e.serialNo(), serial)
                    && java.util.Objects.equals(e.lpnId(), lpn) && java.util.Objects.equals(e.locationId(), location)) {
                return e;
            }
            throw ApiException.conflict("INV_RFID_TAG_IN_USE", "Tag " + hex + " is already commissioned to "
                    + describe(e) + "; retire it first");
        }
        String user = TenantContext.require().userId();
        jdbc.sql("""
                        insert into rfid_tag (tenant_id, epc, scheme, site_id, owner_id, item_no, serial_no, lpn_id,
                            location_id, status, commissioned_by, commissioned_at)
                        values (:t, :epc, :scheme, :site, :owner, :item, :serial, :lpn, :loc, 'ACTIVE', :user, :now)
                        on conflict (tenant_id, epc) do update set scheme = excluded.scheme, site_id = excluded.site_id,
                            owner_id = excluded.owner_id, item_no = excluded.item_no, serial_no = excluded.serial_no,
                            lpn_id = excluded.lpn_id, location_id = excluded.location_id, status = 'ACTIVE',
                            commissioned_by = excluded.commissioned_by, commissioned_at = excluded.commissioned_at,
                            retired_at = null""")
                .param("t", TenantContext.tenantId()).param("epc", hex).param("scheme", scheme).param("site", siteId)
                .param("owner", item == null ? null : owner).param("item", item).param("serial", serial)
                .param("lpn", lpn).param("loc", location).param("user", user)
                .param("now", Timestamp.from(clock.instant()))
                .update();
        return tag(hex).orElseThrow();
    }

    /** A GS1 EPC must say the same as the binding: an SSCC tag is that LPN, an SGTIN tag a unit of that item. */
    private void checkMatches(String siteId, Epc.Tag tag, String owner, String item, String serial, String lpn) {
        if (tag == null) {
            return;
        }
        if (tag.scheme() == Epc.Scheme.SSCC && (lpn == null || !lpn.equals(tag.sscc()))) {
            throw ApiException.unprocessable("INV_RFID_EPC_MISMATCH", "Tag holds SSCC " + tag.sscc()
                    + (lpn == null ? "; bind it to that LPN" : ", not LPN " + lpn));
        }
        if (tag.scheme() == Epc.Scheme.SGTIN) {
            boolean ofItem = item != null && itemsByGtin(siteId, tag.gtin()).stream()
                    .anyMatch(c -> c.owner().equals(owner) && c.item().equals(item));
            if (!ofItem) {
                throw ApiException.unprocessable("INV_RFID_EPC_MISMATCH", "Tag holds GTIN " + tag.gtin()
                        + ", which is not a unit of " + (item == null ? "the target" : item));
            }
            if (serial != null && !serial.equals(tag.serial())) {
                throw ApiException.unprocessable("INV_RFID_EPC_MISMATCH", "Tag holds serial " + tag.serial()
                        + ", not " + serial);
            }
        }
    }

    private Epc.Tag encode(String siteId, CommissionRequest r, String owner, String item, String serial, String lpn) {
        Integer cpl = r.companyPrefixLength();
        if (cpl == null || cpl < 6 || cpl > 12) {
            throw ApiException.unprocessable("INV_RFID_PREFIX_REQUIRED",
                    "Without an EPC, give the GS1 company prefix length (6-12) to encode one");
        }
        try {
            if (lpn != null) {
                if (!lpn.matches("\\d{18}")) {
                    throw ApiException.unprocessable("INV_RFID_NOT_ENCODABLE",
                            "LPN " + lpn + " is not an SSCC; read the tag's EPC and commission that instead");
                }
                return Epc.sscc96(lpn, cpl, r.filter() == null ? 0 : r.filter());
            }
            if (item != null) {
                if (serial == null || !serial.matches("[1-9]\\d{0,11}|0")) {
                    throw ApiException.unprocessable("INV_RFID_NOT_ENCODABLE",
                            "An SGTIN-96 needs a numeric serial without leading zeros");
                }
                String gtin = jdbc.sql("""
                                select u.gtin from ref_item_uom u join ref_item i
                                  on i.owner_id = u.owner_id and i.item_no = u.item_no and i.site_id = :site
                                where u.owner_id = :o and u.item_no = :i and u.gtin is not null
                                order by (u.uom = i.base_uom) desc, u.numerator limit 1""")
                        .param("site", siteId).param("o", owner).param("i", item).query(String.class).optional()
                        .orElseThrow(() -> ApiException.unprocessable("INV_RFID_NOT_ENCODABLE",
                                "Item " + item + " has no GTIN to encode"));
                return Epc.sgtin96(gtin, cpl, Long.parseLong(serial), r.filter() == null ? 1 : r.filter());
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("INV_RFID_NOT_ENCODABLE", e.getMessage());
        }
        throw ApiException.unprocessable("INV_RFID_NOT_ENCODABLE", "Only units and SSCC LPNs can be encoded; give the EPC");
    }

    @Transactional
    public TagView retire(String siteId, String epc) {
        String hex = canonical(epc);
        TagView t = tag(hex).filter(v -> v.siteId().equals(siteId))
                .orElseThrow(() -> ApiException.notFound("INV_RFID_TAG_UNKNOWN", "Tag " + epc + " is not commissioned at " + siteId));
        if (t.ownerId() != null) {
            AccessScope.current().requireOwner(t.ownerId());
        }
        jdbc.sql("update rfid_tag set status = 'RETIRED', retired_at = :now where epc = :epc and status = 'ACTIVE'")
                .param("now", Timestamp.from(clock.instant())).param("epc", hex).update();
        return tag(hex).orElseThrow();
    }

    /** Records where commissioned tags were seen; reads of tags that are not commissioned are ignored. */
    @Transactional
    public Map<String, Object> sightings(String siteId, SightingRequest r) {
        List<String> epcs = resolve(siteId, r.reads()).stream().filter(Resolved::registered).map(Resolved::epc).toList();
        int updated = epcs.isEmpty() ? 0 : jdbc.sql("""
                        update rfid_tag set last_seen_at = :now, last_seen_location = :loc, last_seen_by = :user
                        where epc in (:epcs) and site_id = :site""")
                .param("now", Timestamp.from(clock.instant()))
                .param("loc", blank(r.locationId()) ? null : r.locationId().trim().toUpperCase())
                .param("user", TenantContext.require().userId()).param("epcs", epcs).param("site", siteId)
                .update();
        return Map.of("reads", r.reads() == null ? 0 : r.reads().size(), "updated", updated);
    }

    @Transactional(readOnly = true)
    public TagView get(String siteId, String epc) {
        return tag(canonical(epc)).filter(v -> v.siteId().equals(siteId))
                .filter(v -> v.ownerId() == null || AccessScope.current().allowsOwner(v.ownerId()))
                .orElseThrow(() -> ApiException.notFound("INV_RFID_TAG_UNKNOWN", "Tag " + epc + " is not commissioned at " + siteId));
    }

    @Transactional(readOnly = true)
    public List<TagView> tags(String siteId, String lpnId, String itemNo, String serialNo, String locationId) {
        AccessScope scope = AccessScope.current();
        return jdbc.sql(TAG_VIEW + """
                         where site_id = :site and status = 'ACTIVE'
                          and (cast(:lpn as text) is null or lpn_id = :lpn)
                          and (cast(:item as text) is null or item_no = :item)
                          and (cast(:serial as text) is null or serial_no = :serial)
                          and (cast(:loc as text) is null or location_id = :loc)
                          and (owner_id is null or :ownersAll or owner_id in (:owners))
                        order by commissioned_at desc limit 500""")
                .param("site", siteId).param("lpn", trim(lpnId)).param("item", trim(itemNo))
                .param("serial", trim(serialNo)).param("loc", trim(locationId))
                .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                .query(Rfid::tagView).list();
    }

    private Optional<TagView> tag(String hex) {
        return jdbc.sql(TAG_VIEW + " where epc = :epc").param("epc", hex).query(Rfid::tagView).optional();
    }

    private static final String TAG_VIEW = """
            select epc, scheme, site_id, owner_id, item_no, serial_no, lpn_id, location_id, status, commissioned_by,
                   commissioned_at, last_seen_at, last_seen_location, last_seen_by
            from rfid_tag""";

    private static TagView tagView(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        String epc = rs.getString(1);
        return new TagView(epc, rs.getString(2), Epc.parse(epc).map(Epc.Tag::uri).orElse(null), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getTimestamp(11).toInstant(), instant(rs.getTimestamp(12)), rs.getString(13),
                rs.getString(14));
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> limit(List<String> reads) {
        if (reads == null) {
            return List.of();
        }
        if (reads.size() > MAX_READS) {
            throw ApiException.unprocessable("INV_RFID_TOO_MANY_READS", "At most " + MAX_READS + " reads per request");
        }
        return reads;
    }

    /** The registry key of an EPC given as hex or URI. */
    private static String canonical(String epc) {
        String hex = Epc.parse(epc).map(Epc.Tag::hex).orElseGet(() -> Epc.hexOf(epc == null ? "" : epc));
        if (hex == null) {
            throw ApiException.badRequest("INV_RFID_EPC_INVALID", "EPC " + epc + " is not hex or an EPC URI");
        }
        return hex;
    }

    private static String describe(TagView t) {
        if (t.lpnId() != null) {
            return "LPN " + t.lpnId();
        }
        if (t.locationId() != null) {
            return "location " + t.locationId();
        }
        return "item " + t.ownerId() + "/" + t.itemNo() + (t.serialNo() == null ? "" : " serial " + t.serialNo());
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static String trim(String v) {
        return blank(v) ? null : v.trim();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String emptyToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }
}
