package com.astrawms.inventory;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.rfid.Epc;
import com.astrawms.inventory.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** RFID resolve, location reconciliation and tag commissioning (ADR-0027). */
class RfidIT extends IntegrationTest {

    private static final String EA_GTIN = "00614141123452";
    private static final String CS_GTIN = "80614141123458";
    private static final String SER_GTIN = "00614141999996";
    private static final String PALLET = "106141412345678908";
    private static final String PALLET_EPC = "3174257BF4499602D2000000";

    @BeforeEach
    void tagged() {
        asTenant(() -> {
            refs.upsertItem(new ItemUpserted(OWNER, "SKU-TAG", "EA", "ACTIVE", null, null, false,
                    List.of(new ItemUpserted.Site(SITE, false, "NONE", "ACTIVE")),
                    List.of(new ItemUpserted.Uom("EA", 1, 1, EA_GTIN), new ItemUpserted.Uom("CS", 12, 1, CS_GTIN)),
                    Instant.now(), null));
            refs.upsertItem(new ItemUpserted(OWNER, "SKU-SERT", "EA", "ACTIVE", null, null, false,
                    List.of(new ItemUpserted.Site(SITE, false, "FULL", "ACTIVE")),
                    List.of(new ItemUpserted.Uom("EA", 1, 1, SER_GTIN)), Instant.now(), null));
        });
    }

    private static String unit(String gtin, long serial) {
        return Epc.sgtin96(gtin, 7, serial, 1).hex();
    }

    private static String reads(String... reads) {
        return "{\"reads\":[" + java.util.Arrays.stream(reads).map(r -> "\"" + r + "\"").collect(Collectors.joining(","))
                + "]}";
    }

    private ResultActions rfid(String path, String json) throws Exception {
        return postAs("operator1", "/rfid" + path, UUID.randomUUID().toString(), json);
    }

    @Test
    void resolvesUnitsCasesPalletsAndUnknownReads() throws Exception {
        receive("SKU-TAG", "24", "EA", "A-01-01", PALLET, null).andExpect(status().isCreated());

        rfid("/resolve", reads(unit(EA_GTIN, 1), unit(CS_GTIN, 7), "0x" + PALLET_EPC.toLowerCase(),
                "urn:epc:id:sscc:0614141.1234567890",
                unit("00614141999989", 1), "E2801160600002000000000A", "not-a-tag"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))                         // the pallet read twice (hex, URI) is one tag
                .andExpect(jsonPath("$[0].kind", is("ITEM")))
                .andExpect(jsonPath("$[0].itemNo", is("SKU-TAG")))
                .andExpect(jsonPath("$[0].uom", is("EA")))
                .andExpect(jsonPath("$[0].baseQty", is(1.0)))
                .andExpect(jsonPath("$[0].serialNo", nullValue()))           // not serial-tracked
                .andExpect(jsonPath("$[1].uom", is("CS")))
                .andExpect(jsonPath("$[1].baseQty", is(12.0)))
                .andExpect(jsonPath("$[1].baseUom", is("EA")))
                .andExpect(jsonPath("$[2].kind", is("LPN")))
                .andExpect(jsonPath("$[2].lpnId", is(PALLET)))
                .andExpect(jsonPath("$[2].locationId", is("A-01-01")))
                .andExpect(jsonPath("$[3].problem", is("GTIN_UNKNOWN")))
                .andExpect(jsonPath("$[4].problem", is("UNREGISTERED")))
                .andExpect(jsonPath("$[5].problem", is("NOT_AN_EPC")));
    }

    @Test
    void reconcilesALocation() throws Exception {
        receive("SKU-TAG", "24", "EA", "A-01-01", PALLET, null).andExpect(status().isCreated());
        receive("SKU-TAG", "3", "EA", "A-01-01", null, null).andExpect(status().isCreated());
        receive("SKU-TAG", "5", "EA", "A-01-01", "LPN-NOTREAD", null).andExpect(status().isCreated());
        String otherPallet = Epc.sscc96("106141417777777779", 7, 0).hex();

        rfid("/locations/a-01-01/reconcile", reads(PALLET_EPC, unit(EA_GTIN, 11), unit(EA_GTIN, 12), otherPallet,
                "E2801160600002000000000A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId", is("A-01-01")))
                .andExpect(jsonPath("$.tags", is(5)))
                .andExpect(jsonPath("$.lpns[?(@.lpnId == '" + PALLET + "')].result").value("FOUND"))
                .andExpect(jsonPath("$.lpns[?(@.lpnId == 'LPN-NOTREAD')].result").value("MISSING"))
                .andExpect(jsonPath("$.lpns[?(@.lpnId == '106141417777777779')].result").value("UNEXPECTED"))
                // loose stock: 3 expected, 2 unit tags read
                .andExpect(jsonPath("$.lines[?(@.lpnId == '')].expectedQty").value(3.0))
                .andExpect(jsonPath("$.lines[?(@.lpnId == '')].readQty").value(2.0))
                .andExpect(jsonPath("$.lines[?(@.lpnId == '')].variance").value(-1.0))
                .andExpect(jsonPath("$.lines[?(@.lpnId == 'LPN-NOTREAD')].readQty").value(0))
                .andExpect(jsonPath("$.countLines", hasSize(2)))
                .andExpect(jsonPath("$.countLines[?(@.lpnId == '" + PALLET + "')].qty").value(24.0))
                .andExpect(jsonPath("$.unresolved", hasSize(1)));

        rfid("/locations/NOWHERE/reconcile", reads(PALLET_EPC)).andExpect(status().isNotFound());
    }

    @Test
    void serialTrackedUnitsAreMatchedToTheirStockRecords() throws Exception {
        post("/receipts", """
                {"ownerId":"ACME","itemNo":"SKU-SERT","qty":2,"uom":"EA","locationId":"A-01-02","serials":["1001","1002"]}""")
                .andExpect(status().isCreated());

        rfid("/resolve", reads(unit(SER_GTIN, 1001), unit(SER_GTIN, 5555)))
                .andExpect(jsonPath("$[0].serialNo", is("1001")))
                .andExpect(jsonPath("$[0].locationId", is("A-01-02")))
                .andExpect(jsonPath("$[0].problem", nullValue()))
                .andExpect(jsonPath("$[1].problem", is("SERIAL_NOT_IN_STOCK")));

        rfid("/locations/A-01-02/reconcile", reads(unit(SER_GTIN, 1001)))
                .andExpect(jsonPath("$.serialsNotRead[*].serialNo", containsInAnyOrder("1002")))
                .andExpect(jsonPath("$.countLines[0].qty").value(1.0));
        rfid("/locations/A-01-01/reconcile", reads(unit(SER_GTIN, 1001)))
                .andExpect(jsonPath("$.unexpectedUnits[0].serialNo", is("1001")))
                .andExpect(jsonPath("$.unexpectedUnits[0].locationId", is("A-01-02")));
    }

    @Test
    void commissionsResolvesAndRetiresTags() throws Exception {
        // the WMS encodes an SSCC-96 for an SSCC LPN; the same binding again is the same tag
        for (int i = 0; i < 2; i++) {
            rfid("/tags", """
                    {"lpnId":"%s","companyPrefixLength":7}""".formatted(PALLET))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.epc", is(Epc.sscc96(PALLET, 7, 0).hex())))
                    .andExpect(jsonPath("$.uri", is("urn:epc:id:sscc:0614141.1234567890")))
                    .andExpect(jsonPath("$.scheme", is("SSCC")))
                    .andExpect(jsonPath("$.status", is("ACTIVE")));
        }
        // a non-GS1 EPC (as delivered on the tag) bound to an LPN that is not an SSCC
        String raw = "E2801160600002000000000A";
        rfid("/tags", """
                {"epc":"%s","lpnId":"LPN-77"}""".formatted(raw)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.scheme", is("RAW")));
        rfid("/resolve", reads(raw)).andExpect(jsonPath("$[0].kind", is("LPN")))
                .andExpect(jsonPath("$[0].lpnId", is("LPN-77")))
                .andExpect(jsonPath("$[0].registered", is(true)));
        rfid("/tags", """
                {"epc":"%s","locationId":"A-01-01"}""".formatted(raw))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INV_RFID_TAG_IN_USE")));
        rfid("/sightings", """
                {"reads":["%s"],"locationId":"dock-01"}""".formatted(raw))
                .andExpect(jsonPath("$.updated", is(1)));
        getJson("/rfid/tags/" + raw).andExpect(jsonPath("$.lastSeenLocation", is("DOCK-01")))
                .andExpect(jsonPath("$.lastSeenBy", is("operator1")));
        rfid("/tags/" + raw + "/retire", "{}").andExpect(jsonPath("$.status", is("RETIRED")));
        rfid("/resolve", reads(raw)).andExpect(jsonPath("$[0].problem", is("RETIRED")));
        // a retired tag can be commissioned again, here as a bin tag
        rfid("/tags", """
                {"epc":"%s","locationId":"a-01-01"}""".formatted(raw)).andExpect(status().isCreated());
        rfid("/resolve", reads(raw)).andExpect(jsonPath("$[0].kind", is("LOCATION")))
                .andExpect(jsonPath("$[0].locationId", is("A-01-01")));
        getJson("/rfid/tags?locationId=A-01-01").andExpect(jsonPath("$", hasSize(1)));

        // unit tags: an SGTIN must be a unit of the item; the WMS encodes an SGTIN-96 for a numeric serial
        rfid("/tags", """
                {"epc":"%s","ownerId":"ACME","itemNo":"SKU-SERT"}""".formatted(unit(EA_GTIN, 1)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("INV_RFID_EPC_MISMATCH")));
        rfid("/tags", """
                {"ownerId":"ACME","itemNo":"SKU-TAG","serialNo":"4711","companyPrefixLength":7}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.epc", is(unit(EA_GTIN, 4711))))
                .andExpect(jsonPath("$.uri", is("urn:epc:id:sgtin:0614141.012345.4711")));
        rfid("/tags", """
                {"lpnId":"LPN-77","companyPrefixLength":7}""")
                .andExpect(jsonPath("$.code", is("INV_RFID_NOT_ENCODABLE")));
        rfid("/tags", """
                {"lpnId":"LPN-77","locationId":"A-01-01","epc":"%s"}""".formatted(raw))
                .andExpect(jsonPath("$.code", is("INV_RFID_TARGET")));
    }
}
