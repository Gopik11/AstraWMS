package com.astrawms.task.putaway;

import com.astrawms.task.projection.Projections;
import com.astrawms.task.projection.Projections.Item;
import com.astrawms.task.projection.Projections.Location;
import com.astrawms.task.projection.Projections.PickFace;
import com.astrawms.task.projection.Projections.Stock;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Directed putaway (scope §2.3, ADR-0019). Locations are classified by location type and zone type:
 * <ul>
 *   <li><b>inbound staging</b> (dock, door, receiving, returns): stock arriving there gets a putaway task; never a
 *       target;</li>
 *   <li><b>never storage</b>: inbound staging, outbound staging ({@code STAGING_OUT}) and shipping zones;</li>
 *   <li><b>QC</b> zones: the only targets for stock that is not AVAILABLE (quarantine, blocked, damaged), and never for
 *       AVAILABLE stock;</li>
 *   <li><b>pick faces</b>: a location with the item's min/max rule; other items never go there; pick zones without a
 *       rule for the item are skipped too;</li>
 *   <li><b>reserve</b>: everything else.</li>
 * </ul>
 * AVAILABLE stock goes to the item's pick face if the LPN fits under the face's maximum ({@code PICK_FACE}), otherwise
 * to reserve: a location already holding the item ({@code CONSOLIDATE}), else the nearest empty one
 * ({@code EMPTY_NEAREST}). Other stock goes to QC the same way ({@code QC_CONSOLIDATE} / {@code QC_EMPTY}); a site
 * without QC zones stores it like available stock, where its status still keeps it from being allocated.
 * Hard constraints apply to every candidate and to operator overrides (PUT-001, CCH-001, CCH-003): active,
 * temperature class, hazmat, mixed items and lots, and LPN capacity including reservations of open tasks.
 */
@Component
public class PutawayEngine {

    /** Inbound staging location types: never targets; stock arriving there triggers putaway. */
    public static final List<String> STAGING_TYPES = List.of("DOOR", "DOCK", "STAGING", "STAGING_IN");
    /** Inbound staging zone types (whatever the location type). */
    public static final List<String> INBOUND_ZONES = List.of("DOCK", "RECEIVING", "RETURNS");
    private static final List<String> OUTBOUND_TYPES = List.of("STAGING_OUT");
    private static final List<String> OUTBOUND_ZONES = List.of("SHIPPING", "STAGING");
    private static final List<String> QC_ZONES = List.of("QC", "QUARANTINE");
    private static final List<String> PICK_ZONES = List.of("PICK", "FORWARD");
    /** LPN capacity per location type; types not listed hold one LPN. */
    private static final Map<String, Integer> LPN_CAPACITY = Map.of(
            "FLOOR", Integer.MAX_VALUE, "BULK", Integer.MAX_VALUE, "BLOCK_STACK", 20, "SHELF", 4);

    private final Projections projections;

    public PutawayEngine(Projections projections) {
        this.projections = projections;
    }

    public record Plan(String locationId, String strategy) {
    }

    /** Why a location cannot take the LPN; empty when it can. */
    public record Rejection(String code, String reason) {
    }

    public static boolean inboundStaging(Location l) {
        return STAGING_TYPES.contains(l.locationType()) || INBOUND_ZONES.contains(Objects.requireNonNullElse(l.zoneType(), ""));
    }

    private static boolean neverStorage(Location l) {
        return inboundStaging(l) || OUTBOUND_TYPES.contains(l.locationType())
                || OUTBOUND_ZONES.contains(Objects.requireNonNullElse(l.zoneType(), ""));
    }

    private static boolean qc(Location l) {
        return QC_ZONES.contains(Objects.requireNonNullElse(l.zoneType(), ""));
    }

    /**
     * @param reservations open-task reservations per location (excluding the task being planned)
     */
    public Optional<Plan> plan(String siteId, List<Stock> contents, Collection<String> excluded,
                               Map<String, Integer> reservations) {
        if (contents.isEmpty()) {
            return Optional.empty();
        }
        Map<String, List<Stock>> stockByLocation = projections.stockAtSite(siteId).stream()
                .collect(Collectors.groupingBy(Stock::locationId));
        Set<String> itemKeys = contents.stream().map(s -> s.ownerId() + "|" + s.itemNo()).collect(Collectors.toSet());
        boolean available = contents.stream().allMatch(s -> "AVAILABLE".equals(s.status()));
        List<PickFace> faces = projections.pickFaces(siteId);
        Set<String> faceLocations = faces.stream().map(PickFace::locationId).collect(Collectors.toSet());
        // The LPN's own pick face(s): only for a single-item LPN of available stock.
        Map<String, BigDecimal> ownFaces = new java.util.HashMap<>();
        if (available && itemKeys.size() == 1) {
            faces.stream().filter(f -> itemKeys.contains(f.ownerId() + "|" + f.itemNo()))
                    .forEach(f -> ownFaces.put(f.locationId(), f.maxQty()));
        }
        BigDecimal lpnQty = contents.stream().map(Stock::qty).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Location> locations = projections.activeLocations(siteId);
        // Non-available stock goes to QC; a site without QC zones stores it normally (its status blocks allocation).
        boolean toQc = !available && locations.stream().anyMatch(PutawayEngine::qc);
        List<Location> face = new ArrayList<>();
        List<Location> consolidate = new ArrayList<>();
        List<Location> empty = new ArrayList<>();
        for (Location loc : locations) {
            if (excluded.contains(loc.locationId()) || neverStorage(loc) || qc(loc) != toQc) {
                continue;
            }
            List<Stock> there = stockByLocation.getOrDefault(loc.locationId(), List.of());
            int reserved = reservations.getOrDefault(loc.locationId(), 0);
            if (check(loc, contents, there, reserved).isPresent()) {
                continue;
            }
            if (ownFaces.containsKey(loc.locationId())) {
                BigDecimal atFace = there.stream().filter(s -> itemKeys.contains(s.ownerId() + "|" + s.itemNo()))
                        .map(Stock::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (atFace.add(lpnQty).compareTo(ownFaces.get(loc.locationId())) <= 0) {
                    face.add(loc);
                }
                continue;
            }
            if (faceLocations.contains(loc.locationId()) || PICK_ZONES.contains(Objects.requireNonNullElse(loc.zoneType(), ""))) {
                continue;   // another item's pick face, or a pick slot without a rule: not reserve storage
            }
            boolean sameItem = there.stream().anyMatch(s -> itemKeys.contains(s.ownerId() + "|" + s.itemNo()));
            if (sameItem) {
                consolidate.add(loc);
            } else if (there.isEmpty() && reserved == 0) {
                empty.add(loc);
            }
        }
        // Candidate lists are in travel-path order (pick_seq, location_id).
        String prefix = toQc ? "QC_" : "";
        if (!face.isEmpty()) {
            return Optional.of(new Plan(face.getFirst().locationId(), "PICK_FACE"));
        }
        if (!consolidate.isEmpty()) {
            return Optional.of(new Plan(consolidate.getFirst().locationId(), prefix + "CONSOLIDATE"));
        }
        if (!empty.isEmpty()) {
            return Optional.of(new Plan(empty.getFirst().locationId(), toQc ? "QC_EMPTY" : "EMPTY_NEAREST"));
        }
        return Optional.empty();
    }

    /** Validates an operator-chosen location against the same hard constraints (PUT-002 override). */
    public Optional<Rejection> validate(String siteId, List<Stock> contents, String locationId, int reservationsThere) {
        Optional<Location> loc = projections.location(siteId, locationId);
        if (loc.isEmpty()) {
            return Optional.of(new Rejection("TSK_LOCATION_UNKNOWN", "Location " + locationId + " is not known"));
        }
        if (inboundStaging(loc.get())) {
            return reject("TSK_LOCATION_NOT_ALLOWED", locationId + " is an inbound staging location");
        }
        if (neverStorage(loc.get())) {
            return reject("TSK_LOCATION_NOT_ALLOWED", locationId + " is an outbound staging / shipping location");
        }
        return check(loc.get(), contents, projections.stockAt(siteId, locationId), reservationsThere);
    }

    private Optional<Rejection> check(Location loc, List<Stock> contents, List<Stock> there, int reserved) {
        if (!"ACTIVE".equals(loc.status())) {
            return reject("TSK_LOCATION_NOT_ALLOWED", loc.locationId() + " is " + loc.status());
        }
        for (Stock s : contents) {
            Optional<Item> item = projections.item(s.ownerId(), s.itemNo());
            if (item.isEmpty()) {
                return reject("TSK_ITEM_UNKNOWN", "No master data for item " + s.itemNo());
            }
            if (!Objects.equals(item.get().temperatureClass(), loc.temperatureClass())) {
                return reject("TSK_LOCATION_NOT_ALLOWED", "Item " + s.itemNo() + " needs temperature class "
                        + Objects.requireNonNullElse(item.get().temperatureClass(), "ambient") + "; "
                        + loc.locationId() + " is " + Objects.requireNonNullElse(loc.temperatureClass(), "ambient"));
            }
            if (item.get().hazardous() && !loc.hazmatAllowed()) {
                return reject("TSK_LOCATION_NOT_ALLOWED", "Hazardous item " + s.itemNo() + " not allowed in " + loc.locationId());
            }
        }
        if (!loc.allowMixedItems()) {
            Set<String> items = new HashSet<>();
            contents.forEach(s -> items.add(s.ownerId() + "|" + s.itemNo()));
            there.forEach(s -> items.add(s.ownerId() + "|" + s.itemNo()));
            if (items.size() > 1) {
                return reject("TSK_LOCATION_NOT_ALLOWED", loc.locationId() + " does not allow mixed items");
            }
        }
        if (!loc.allowMixedLots()) {
            for (Stock s : contents) {
                boolean otherLot = there.stream().anyMatch(t -> t.itemNo().equals(s.itemNo())
                        && t.ownerId().equals(s.ownerId()) && !t.lotNo().equals(s.lotNo()));
                if (otherLot) {
                    return reject("TSK_LOCATION_NOT_ALLOWED", loc.locationId() + " does not allow mixed lots");
                }
            }
        }
        long lpns = there.stream().map(Stock::lpnId).filter(l -> !l.isEmpty()).distinct().count();
        boolean loose = there.stream().anyMatch(s -> s.lpnId().isEmpty());
        long used = lpns + (loose ? 1 : 0) + reserved;
        int capacity = LPN_CAPACITY.getOrDefault(loc.locationType(), 1);
        if (used + 1 > capacity) {
            return reject("TSK_LOCATION_FULL", loc.locationId() + " has no free LPN capacity (" + used + "/" + capacity + ")");
        }
        return Optional.empty();
    }

    private static Optional<Rejection> reject(String code, String reason) {
        return Optional.of(new Rejection(code, reason));
    }
}
