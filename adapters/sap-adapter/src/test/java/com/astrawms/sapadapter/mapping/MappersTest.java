package com.astrawms.sapadapter.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.astrawms.common.contracts.IntegrationContracts.GoodsMovement;
import com.astrawms.common.contracts.IntegrationContracts.ReceiptConfirmation;
import com.astrawms.common.contracts.IntegrationContracts.ReceiptExpectation;
import com.astrawms.sapadapter.sap.Bapi;
import com.astrawms.sapadapter.sap.Delvry07;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Mapping rules from ISD IF-IB-001 §5, IF-IB-002 §5 and IF-INV-001 §5.1. */
class MappersTest {

    static final DelvryMapper.Plant DALLAS = new DelvryMapper.Plant("1000", "DC1", ZoneId.of("America/Chicago"), "ACME");
    static final Instant NOW = Instant.parse("2026-09-30T15:00:00Z");

    static Delvry07 delivery(String mestyp, List<Delvry07.E1edl18> control) {
        return new Delvry07("0000000000123456", mestyp,
                new Delvry07.E1edl20("0180000123", "EL", "ASN-77", "BOL-1", "TRL-9", "1000"),
                control,
                List.of(new Delvry07.E1adrm1("LF", "V-100"), new Delvry07.E1adrm1("SP", "UPSN")),
                List.of(new Delvry07.E1edt13("007", "20261002", "083000")),
                List.of(new Delvry07.E1edl24("000010", "SKU-1", "1000", "24.000", "ST", "B1", "VB1", "4500001", "00010", " ", "5.0", null),
                        new Delvry07.E1edl24("000020", "SKU-2", "1000", "10", "KAR", null, null, null, null, "X", null, null)),
                List.of(new Delvry07.E1edl37("00106141410000000019", "PAL01",
                        List.of(new Delvry07.E1edl44("000010", "24", "ST", "B1")))));
    }

    @Nested
    class Delvry {

        @Test
        void mapsHeaderPartnersDatesAndLines() {
            ReceiptExpectation e = DelvryMapper.map(delivery(Delvry07.SAVE_REPLICA, null), DALLAS, NOW).expectation();
            assertThat(e.erpDocNo()).isEqualTo("0180000123");
            assertThat(e.erpDocType()).isEqualTo("VENDOR_ASN");
            assertThat(e.action()).isEqualTo("CREATE");
            assertThat(e.revision()).isEqualTo(123456L);
            assertThat(e.vendorId()).isEqualTo("V-100");
            assertThat(e.carrierScac()).isEqualTo("UPSN");
            // 2026-10-02 08:30 in Chicago (CDT, UTC-5)
            assertThat(e.expectedArrivalUtc()).isEqualTo(Instant.parse("2026-10-02T13:30:00Z"));
            assertThat(e.externalRef()).isEqualTo("ASN-77");

            ReceiptExpectation.Line l1 = e.lines().get(0);
            assertThat(l1.erpLineRef()).isEqualTo("000010");
            assertThat(l1.uom()).isEqualTo("EA");
            assertThat(l1.qtyExpected()).isEqualByComparingTo("24");
            assertThat(l1.lotNo()).isEqualTo("B1");
            assertThat(l1.poRef().poNo()).isEqualTo("4500001");
            assertThat(l1.stockTypeTarget()).isEqualTo("AVAILABLE");
            assertThat(l1.overTolerancePct()).isEqualByComparingTo("5");
            assertThat(l1.ownerId()).isEqualTo("ACME");

            ReceiptExpectation.Line l2 = e.lines().get(1);
            assertThat(l2.uom()).isEqualTo("CS");
            assertThat(l2.stockTypeTarget()).isEqualTo("QI");

            assertThat(e.handlingUnits()).singleElement().satisfies(hu -> {
                assertThat(hu.sscc()).isEqualTo("106141410000000019");   // AI 00 stripped
                assertThat(hu.contents().getFirst().qty()).isEqualByComparingTo("24");
            });
        }

        @Test
        void actionFromMessageTypeAndDeletionFlag() {
            assertThat(DelvryMapper.map(delivery(Delvry07.CHANGE, null), DALLAS, NOW).expectation().action()).isEqualTo("CHANGE");
            assertThat(DelvryMapper.map(delivery(Delvry07.CHANGE, List.of(new Delvry07.E1edl18("DEL"))), DALLAS, NOW)
                    .expectation().action()).isEqualTo("DELETE");
            assertThatThrownBy(() -> DelvryMapper.map(delivery("ORDERS", null), DALLAS, NOW))
                    .isInstanceOf(MappingException.class).hasMessageContaining("ORDERS");
        }

        @Test
        void unmappedUnitIsAPermanentError() {
            Delvry07 d = delivery(Delvry07.SAVE_REPLICA, null);
            Delvry07 bad = new Delvry07(d.docnum(), d.mestyp(), d.e1edl20(), null, d.e1adrm1(), d.e1edt13(),
                    List.of(new Delvry07.E1edl24("000010", "SKU-1", "1000", "1", "XYZ", null, null, null, null, null, null, null)),
                    null);
            assertThatThrownBy(() -> DelvryMapper.map(bad, DALLAS, NOW))
                    .isInstanceOfSatisfying(MappingException.class, e -> assertThat(e.code()).isEqualTo("DELVRY_UOM_UNMAPPED"));
        }
    }

    @Nested
    class ReceiptConfirmationToBapi {

        @Test
        void singleLotOnItemMultiLotAsBatchSplitSubItems() {
            ReceiptConfirmation c = new ReceiptConfirmation("W1M3S5G87458N692", "0180000123", false, "V-100",
                    Instant.parse("2026-10-02T14:05:00Z"), true,
                    List.of(new ReceiptConfirmation.Line("000010", "SKU-1", new BigDecimal("24"), "EA",
                                    List.of(new ReceiptConfirmation.LotSplit("B1", null, new BigDecimal("24"), LocalDate.parse("2027-01-31"))),
                                    "AVAILABLE", null),
                            new ReceiptConfirmation.Line("000020", "SKU-2", new BigDecimal("9"), "CS",
                                    List.of(new ReceiptConfirmation.LotSplit("L1", null, new BigDecimal("5"), null),
                                            new ReceiptConfirmation.LotSplit("L2", null, new BigDecimal("4"), null)),
                                    "QI", "SHORT_VENDOR")),
                    List.of(new ReceiptConfirmation.HandlingUnit("106141410000000019", null,
                            List.of(new ReceiptConfirmation.HuContent("000010", "B1", new BigDecimal("24"))))));

            Bapi.InbDeliveryConfirmDec call = BapiMapper.confirmInbound(c);

            assertThat(call.delivery()).isEqualTo("0180000123");
            assertThat(call.headerControl().postGoodsReceiptFlag()).isEqualTo("X");
            assertThat(call.headerDeadlines().getFirst().timestampUtc()).isEqualTo("20261002140500");
            assertThat(call.wmsTxnId()).isEqualTo("W1M3S5G87458N692");
            assertThat(call.itemData()).extracting(Bapi.ItemData::delivItem)
                    .containsExactly("000010", "000020", "900001", "900002");
            assertThat(call.itemData().get(0).batch()).isEqualTo("B1");
            assertThat(call.itemData().get(0).salesUnit()).isEqualTo("ST");
            assertThat(call.itemData().get(1).batch()).isNull();
            assertThat(call.itemData().get(1).salesUnit()).isEqualTo("KAR");
            assertThat(call.itemData().get(2)).satisfies(i -> {
                assertThat(i.hierarItem()).isEqualTo("000020");
                assertThat(i.useHierItm()).isEqualTo("1");
                assertThat(i.batch()).isEqualTo("L1");
                assertThat(i.dlvQty()).isEqualByComparingTo("5");
            });
            assertThat(call.itemControl()).allSatisfy(ic -> assertThat(ic.chgDelqty()).isEqualTo("X"));
            assertThat(call.handlingUnitHeader()).singleElement()
                    .satisfies(hu -> assertThat(hu.hdlUnitExid()).isEqualTo("106141410000000019"));
        }
    }

    @Nested
    class GoodsMovementToBapi {

        @Test
        void adjustmentAndStatusChangeUseCatalogueMovementTypes() {
            GoodsMovement adj = new GoodsMovement("W1M3S5G87458N693", "ADJ_NEG", "CC_TOL",
                    Instant.parse("2026-10-01T04:30:00Z"), null,
                    List.of(new GoodsMovement.Item("SKU-1", new BigDecimal("2"), "EA", "0001", null, "B1", null,
                            "UNRESTRICTED", "ADJUSTMENT x")));
            Bapi.GoodsmvtCreate call = BapiMapper.goodsMovement(adj, "1000", ZoneId.of("America/Chicago"));
            assertThat(call.gmCode()).isEqualTo("03");
            // 04:30 UTC is still 30 Sep in Chicago: posting date is plant-local
            assertThat(call.header().pstngDate()).isEqualTo("20260930");
            assertThat(call.header().refDocNo()).isEqualTo("W1M3S5G87458N693");
            assertThat(call.items().getFirst()).satisfies(i -> {
                assertThat(i.moveType()).isEqualTo("702");
                assertThat(i.plant()).isEqualTo("1000");
                assertThat(i.stgeLoc()).isEqualTo("0001");
                assertThat(i.stckType()).isEqualTo(" ");
                assertThat(i.entryUom()).isEqualTo("ST");
            });

            GoodsMovement fromQi = new GoodsMovement("W1M3S5G87458N694", "STATUS_QI_TO_BLK", "QA_REJ", NOW, "qa1",
                    List.of(new GoodsMovement.Item("SKU-1", BigDecimal.ONE, "EA", "0001", null, null, null,
                            "QUALITY_INSPECTION", null)));
            Bapi.GoodsmvtCreate qi = BapiMapper.goodsMovement(fromQi, "1000", ZoneId.of("UTC"));
            assertThat(qi.gmCode()).isEqualTo("04");
            assertThat(qi.items().getFirst().moveType()).isEqualTo("350");
            assertThat(qi.items().getFirst().stckType()).isEqualTo("X");
        }

        @Test
        void unknownMovementTypeIsRejected() {
            GoodsMovement m = new GoodsMovement("W1", "TELEPORT", null, NOW, null, List.of());
            assertThatThrownBy(() -> BapiMapper.goodsMovement(m, "1000", ZoneId.of("UTC")))
                    .isInstanceOf(MappingException.class);
        }
    }
}
