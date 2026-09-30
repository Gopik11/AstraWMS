package com.astrawms.inventory.reference;

/**
 * Reference data this service needs from Master Data, projected from
 * {@link com.astrawms.common.contracts.MasterDataEvents} messages.
 */
public final class ReferenceData {

    private ReferenceData() {
    }

    public record ItemRef(String ownerId, String itemNo, String siteId, String baseUom, boolean lotControlled,
                          String serialControl, Integer shelfLifeDays, String temperatureClass, boolean hazardous,
                          String status) {

        public boolean receivable() {
            return "ACTIVE".equals(status) || "BLOCKED_PROCUREMENT".equals(status);
        }

        /** Serials are tracked in inventory from receipt (INBOUND/FULL); OUTBOUND-only serials are captured at pack. */
        public boolean serialTracked() {
            return "INBOUND".equals(serialControl) || "FULL".equals(serialControl);
        }
    }

    public record UomRef(String uom, int numerator, int denominator) {
    }

    public record LocationRef(String siteId, String locationId, String zoneId, String locationType, String erpBucket,
                              String temperatureClass, boolean hazmatAllowed, boolean allowMixedItems,
                              boolean allowMixedLots, String status) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }
}
