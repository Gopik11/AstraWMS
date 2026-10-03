package com.astrawms.masterdata.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class MasterDataDtos {

    private MasterDataDtos() {
    }

    public enum ItemStatus { ACTIVE, BLOCKED_PROCUREMENT, BLOCKED_ALL, DELETED }

    public enum SerialControl { NONE, INBOUND, OUTBOUND, FULL }

    public enum LocationStatus { ACTIVE, BLOCKED, INACTIVE }

    // ------------------------------------------------------------------ items

    public record ItemRequest(
            @NotBlank @Size(max = 80) String description,
            @NotBlank String baseUom,
            String itemType,
            @NotNull ItemStatus status,
            @Positive Integer shelfLifeDays,
            @PositiveOrZero Integer minRemainingShelfLifeDays,
            String temperatureClass,
            boolean hazardous,
            @NotEmpty List<@Valid ItemSite> sites,
            List<@Valid ItemUom> uoms,
            Instant sourceChangedAt,
            @PositiveOrZero java.math.BigDecimal standardCost) {
    }

    public record ItemSite(@NotBlank String siteId, boolean lotControlled, @NotNull SerialControl serialControl,
                           ItemStatus status) {
    }

    public record ItemUom(@NotBlank String uom, @Positive int numerator, @Positive int denominator,
                          @Size(min = 8, max = 14) String gtin, BigDecimal lengthCm, BigDecimal widthCm,
                          BigDecimal heightCm, BigDecimal grossWeightKg) {
    }

    public record ItemView(String ownerId, String itemNo, String description, String baseUom, String itemType,
                           ItemStatus status, Integer shelfLifeDays, Integer minRemainingShelfLifeDays,
                           String temperatureClass, boolean hazardous, List<ItemSite> sites, List<ItemUom> uoms,
                           String source, long version, Instant updatedAt, String updatedBy,
                           java.math.BigDecimal standardCost) {
    }

    public record Page<T>(List<T> items, String nextCursor) {
    }

    // ------------------------------------------------------------------ sites, zones, locations

    /** A site; {@code siteType} MAIN (default) or STORE, a store optionally names the main site supplying it (ADR-0024). */
    public record SiteRequest(@NotBlank String name, @NotBlank String timeZone, @NotBlank String erpSite, String siteType,
                              String supplyingSite) {
    }

    public record SiteView(String siteId, String name, String timeZone, String erpSite, String siteType,
                           String supplyingSite) {
    }

    public record ZoneRequest(@NotBlank String zoneType, @NotBlank String erpBucket, String temperatureClass,
                              boolean hazmatAllowed) {
    }

    /** Null {@code erpBucket}, {@code temperatureClass} or {@code hazmatAllowed} inherit the zone value. */
    public record LocationRequest(
            @NotBlank String zoneId,
            @NotBlank String locationType,
            String erpBucket,
            String temperatureClass,
            Boolean hazmatAllowed,
            Boolean allowMixedItems,
            Boolean allowMixedLots,
            BigDecimal maxWeightKg,
            BigDecimal maxVolumeM3,
            Integer pickSeq,
            BigDecimal x,
            BigDecimal y,
            BigDecimal z,
            LocationStatus status) {
    }

    /** Effective location (zone defaults applied). */
    public record LocationView(String siteId, String locationId, String zoneId, String locationType,
                               String checkDigit, String erpBucket, String temperatureClass, boolean hazmatAllowed,
                               boolean allowMixedItems, boolean allowMixedLots, BigDecimal maxWeightKg,
                               BigDecimal maxVolumeM3, Integer pickSeq, LocationStatus status, Instant updatedAt,
                               String zoneType) {
    }

    /**
     * Generates the cartesian product aisles × bays × levels × positions using {@code pattern} with placeholders
     * {@code {aisle}}, {@code {bay}}, {@code {level}}, {@code {position}}; numeric parts are zero-padded to
     * {@code numberWidth}. Example: pattern "{aisle}-{bay}-{level}{position}" → "A-01-B2".
     */
    public record GenerateRequest(
            @NotEmpty List<@NotBlank String> aisles,
            @Positive int bayFrom, @Positive int bayTo,
            @NotEmpty List<@NotBlank String> levels,
            @Positive int positionFrom, @Positive int positionTo,
            @NotBlank String pattern,
            @Positive @Max(4) Integer numberWidth,
            @NotBlank String locationType,
            Boolean allowMixedItems,
            Boolean allowMixedLots) {
    }

    public record GenerateResult(int created, int updated, int unchanged, List<String> sampleIds) {
    }
}
