package com.astrawms.common.contracts;

import java.time.Instant;
import java.util.List;

/**
 * Event contracts published by the Master Data service on {@link #TOPIC} and consumed by other services.
 * Changing a record here is a contract change: additive fields only within a schema major version (NFR-121).
 */
public final class MasterDataEvents {

    public static final String TOPIC = "wms.masterdata.events.v1";
    public static final String ITEM_UPSERTED = "ItemUpserted";
    public static final String LOCATION_UPSERTED = "LocationUpserted";
    /** 1.1: ItemUpserted.standardCost (additive). */
    public static final String SCHEMA_VERSION = "1.1";

    private MasterDataEvents() {
    }

    /** Business key {@code ownerId:itemNo}. Carries the full item so consumers can replace their projection. */
    /**
     * @param standardCost value of one base unit in the tenant's reporting currency (optional); used for approval
     *                     value limits (§G.5.1). Added in schema 1.1; consumers of 1.0 ignore it.
     */
    public record ItemUpserted(String ownerId, String itemNo, String baseUom, String status, Integer shelfLifeDays,
                               String temperatureClass, boolean hazardous, List<Site> sites, List<Uom> uoms,
                               Instant sourceChangedAt, java.math.BigDecimal standardCost) {

        public ItemUpserted(String ownerId, String itemNo, String baseUom, String status, Integer shelfLifeDays,
                            String temperatureClass, boolean hazardous, List<Site> sites, List<Uom> uoms,
                            Instant sourceChangedAt) {
            this(ownerId, itemNo, baseUom, status, shelfLifeDays, temperatureClass, hazardous, sites, uoms,
                    sourceChangedAt, null);
        }

        /** Site-level control data (IF-MD-001: lot/serial control can differ per site). */
        public record Site(String siteId, boolean lotControlled, String serialControl, String status) {
        }

        /** Alternative unit: base quantity = quantity × numerator ÷ denominator. */
        public record Uom(String uom, int numerator, int denominator) {
        }
    }

    /**
     * Business key {@code siteId:locationId}. Attributes are effective values (zone defaults already applied).
     * {@code checkDigit} is printed on the location label and scanned on RF confirmation; {@code pickSeq} orders
     * locations along the travel path (lower = closer to the dock / start of the path).
     */
    public record LocationUpserted(String siteId, String locationId, String zoneId, String locationType,
                                   String erpBucket, String temperatureClass, boolean hazmatAllowed,
                                   boolean allowMixedItems, boolean allowMixedLots, String status,
                                   Instant sourceChangedAt, String checkDigit, Integer pickSeq) {
    }
}
