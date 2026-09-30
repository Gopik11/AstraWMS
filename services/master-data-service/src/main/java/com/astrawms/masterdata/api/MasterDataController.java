package com.astrawms.masterdata.api;

import com.astrawms.common.web.ApiException;
import com.astrawms.masterdata.api.MasterDataDtos.GenerateRequest;
import com.astrawms.masterdata.api.MasterDataDtos.GenerateResult;
import com.astrawms.masterdata.api.MasterDataDtos.ItemRequest;
import com.astrawms.masterdata.api.MasterDataDtos.ItemView;
import com.astrawms.masterdata.api.MasterDataDtos.LocationRequest;
import com.astrawms.masterdata.api.MasterDataDtos.LocationView;
import com.astrawms.masterdata.api.MasterDataDtos.Page;
import com.astrawms.masterdata.api.MasterDataDtos.SiteRequest;
import com.astrawms.masterdata.api.MasterDataDtos.ZoneRequest;
import com.astrawms.masterdata.service.ItemService;
import com.astrawms.masterdata.service.LocationService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Master data API v1. PUT is a full, idempotent replace; items support ETag / If-Match (NFR-124). */
@RestController
@RequestMapping("/api/v1")
public class MasterDataController {

    private final ItemService items;
    private final LocationService locations;

    public MasterDataController(ItemService items, LocationService locations) {
        this.items = items;
        this.locations = locations;
    }

    @PutMapping("/items/{ownerId}/{itemNo}")
    public ResponseEntity<ItemView> putItem(@PathVariable String ownerId, @PathVariable String itemNo,
                                            @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                            @Valid @RequestBody ItemRequest body) {
        ItemView view = items.upsert(ownerId, itemNo, body, parseETag(ifMatch));
        return ResponseEntity.ok().eTag(etag(view.version())).body(view);
    }

    @GetMapping("/items/{ownerId}/{itemNo}")
    public ResponseEntity<ItemView> getItem(@PathVariable String ownerId, @PathVariable String itemNo) {
        ItemView view = items.get(ownerId, itemNo);
        return ResponseEntity.ok().eTag(etag(view.version())).body(view);
    }

    @GetMapping("/items/{ownerId}")
    public Page<ItemView> listItems(@PathVariable String ownerId, @RequestParam(required = false) String after,
                                    @RequestParam(defaultValue = "50") int limit) {
        return items.list(ownerId, after, limit);
    }

    @PutMapping("/sites/{siteId}")
    public ResponseEntity<Void> putSite(@PathVariable String siteId, @Valid @RequestBody SiteRequest body) {
        locations.upsertSite(siteId, body);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/sites/{siteId}/zones/{zoneId}")
    public Map<String, Integer> putZone(@PathVariable String siteId, @PathVariable String zoneId,
                                        @Valid @RequestBody ZoneRequest body) {
        return Map.of("locationsRepublished", locations.upsertZone(siteId, zoneId, body));
    }

    @PostMapping("/sites/{siteId}/zones/{zoneId}/locations/generate")
    public GenerateResult generate(@PathVariable String siteId, @PathVariable String zoneId,
                                   @Valid @RequestBody GenerateRequest body) {
        return locations.generate(siteId, zoneId, body);
    }

    @PutMapping("/sites/{siteId}/locations/{locationId}")
    public LocationView putLocation(@PathVariable String siteId, @PathVariable String locationId,
                                    @Valid @RequestBody LocationRequest body) {
        return locations.upsertLocation(siteId, locationId, body);
    }

    @GetMapping("/sites/{siteId}/locations/{locationId}")
    public LocationView getLocation(@PathVariable String siteId, @PathVariable String locationId) {
        return locations.getLocation(siteId, locationId);
    }

    @GetMapping("/sites/{siteId}/locations")
    public Page<LocationView> listLocations(@PathVariable String siteId,
                                            @RequestParam(required = false) String zoneId,
                                            @RequestParam(required = false) String after,
                                            @RequestParam(defaultValue = "100") int limit) {
        return locations.listLocations(siteId, zoneId, after, limit);
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }

    private static Long parseETag(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        try {
            return Long.parseLong(value.replace("\"", ""));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("IF_MATCH_INVALID", "If-Match must be an ETag returned by this API");
        }
    }
}
