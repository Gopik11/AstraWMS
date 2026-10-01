package com.astrawms.task.putaway;

import com.astrawms.task.projection.Projections;
import com.astrawms.task.projection.Projections.Item;
import com.astrawms.task.projection.Projections.Location;
import com.astrawms.task.projection.Projections.Stock;
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
 * Directed putaway (scope §2.3). Strategies are evaluated in order; the first that yields a location wins:
 * <ol>
 *   <li>{@code CONSOLIDATE}: a location already holding the same owner/item (and lot when lots may not mix)
 *       with free LPN capacity;</li>
 *   <li>{@code EMPTY_NEAREST}: an empty location, lowest pick sequence first.</li>
 * </ol>
 * Hard constraints apply before every strategy and also to operator overrides (PUT-001, CCH-001, CCH-003):
 * active location, not inbound staging, temperature class equality (ambient goods only in unclassified
 * locations), hazmat permission, mixed-item / mixed-lot rules, and LPN capacity including reservations held by
 * open tasks, so two pallets are never directed to the same empty slot.
 */
@Component
public class PutawayEngine {

    /** Location types that are inbound staging: never putaway targets, and stock arriving there triggers putaway. */
    public static final List<String> STAGING_TYPES = List.of("DOOR", "DOCK", "STAGING", "STAGING_IN");
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
        List<Location> consolidate = new ArrayList<>();
        List<Location> empty = new ArrayList<>();
        for (Location loc : projections.storageLocations(siteId, STAGING_TYPES)) {
            if (excluded.contains(loc.locationId())) {
                continue;
            }
            List<Stock> there = stockByLocation.getOrDefault(loc.locationId(), List.of());
            if (check(loc, contents, there, reservations.getOrDefault(loc.locationId(), 0)).isPresent()) {
                continue;
            }
            boolean sameItem = there.stream().anyMatch(s -> itemKeys.contains(s.ownerId() + "|" + s.itemNo()));
            if (sameItem) {
                consolidate.add(loc);
            } else if (there.isEmpty() && reservations.getOrDefault(loc.locationId(), 0) == 0) {
                empty.add(loc);
            }
        }
        // Candidate lists are already in pick-sequence order (storageLocations sorts by pick_seq, location_id).
        if (!consolidate.isEmpty()) {
            return Optional.of(new Plan(consolidate.getFirst().locationId(), "CONSOLIDATE"));
        }
        if (!empty.isEmpty()) {
            return Optional.of(new Plan(empty.getFirst().locationId(), "EMPTY_NEAREST"));
        }
        return Optional.empty();
    }

    /** Validates an operator-chosen location against the same hard constraints (PUT-002 override). */
    public Optional<Rejection> validate(String siteId, List<Stock> contents, String locationId, int reservationsThere) {
        Optional<Location> loc = projections.location(siteId, locationId);
        if (loc.isEmpty()) {
            return Optional.of(new Rejection("TSK_LOCATION_UNKNOWN", "Location " + locationId + " is not known"));
        }
        if (STAGING_TYPES.contains(loc.get().locationType())) {
            return Optional.of(new Rejection("TSK_LOCATION_NOT_ALLOWED", locationId + " is an inbound staging location"));
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
