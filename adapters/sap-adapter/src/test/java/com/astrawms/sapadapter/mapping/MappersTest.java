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
    class OutboundDelivery {

        Delvry07 outbound(String mestyp, List<Delvry07.E1edl18> control) {
            return new Delvry07("0000000000777001", mestyp,
                    new Delvry07.E1edl20("0080001234", "LF", null, null, null, "1000"), control,
                    List.of(new Delvry07.E1adrm1("WE", "C-77", "Acme Stores", "Dallas", "US"),
                            new Delvry07.E1adrm1("SP", "UPSN")),
                    List.of(new Delvry07.E1edt13("006", "20261003", "140000")),
                    List.of(new Delvry07.E1edl24("000010", "SKU-1", "1000", "5", "ST", "B7", null, null, null, null, null, null)),
                    null);
        }

        @Test
        void mapsOrderShipToCarrierAndGoodsIssueDate_IFOB001() {
            var o = DelvryMapper.mapOutbound(outbound(Delvry07.OB_SAVE_REPLICA, null), DALLAS, NOW);
            assertThat(o.erpDocNo()).isEqualTo("0080001234");
            assertThat(o.orderType()).isEqualTo("CUSTOMER");
            assertThat(o.action()).isEqualTo("CREATE");
            assertThat(o.shipTo().name()).isEqualTo("Acme Stores");
            assertThat(o.carrierScac()).isEqualTo("UPSN");
            assertThat(o.plannedGoodsIssueUtc()).isEqualTo(Instant.parse("2026-10-03T19:00:00Z"));
            assertThat(o.lines().getFirst().uom()).isEqualTo("EA");
            assertThat(o.lines().getFirst().lotNo()).isEqualTo("B7");
            assertThat(DelvryMapper.mapOutbound(outbound(Delvry07.OB_CHANGE, List.of(new Delvry07.E1edl18("DEL"))), DALLAS, NOW)
                    .action()).isEqualTo("CANCEL");
        }

        @Test
        void shipmentConfirmationBecomesConfirmWithGoodsIssue_IFOB003() {
            var c = new com.astrawms.common.contracts.OutboundContracts.ShipmentConfirmation("W1M3S5G87458N6A1",
                    "0080001234", Instant.parse("2026-10-03T18:30:00Z"), "UPSN", "1Z999", "BOL-9",
                    List.of(new com.astrawms.common.contracts.OutboundContracts.ShipmentConfirmation.Line("000010",
                                    "SKU-1", new BigDecimal("3"), "EA",
                                    List.of(new com.astrawms.common.contracts.OutboundContracts.ShipmentConfirmation.LotSplit("B7", new BigDecimal("2")),
                                            new com.astrawms.common.contracts.OutboundContracts.ShipmentConfirmation.LotSplit("B8", BigDecimal.ONE)),
                                    null, "SHORT_PICK"),
                            new com.astrawms.common.contracts.OutboundContracts.ShipmentConfirmation.Line("000020",
                                    "SCANNER", BigDecimal.ONE, "EA", List.of(), List.of("SN-1"), null)));
            Bapi.OutbDeliveryConfirmDec call = BapiMapper.confirmOutbound(c);
            assertThat(call.headerControl().postGoodsIssueFlag()).isEqualTo("X");
            assertThat(call.headerData().bolNr()).isEqualTo("BOL-9");
            assertThat(call.headerDeadlines().getFirst().timestampUtc()).isEqualTo("20261003183000");
            assertThat(call.itemData()).extracting(Bapi.ItemData::delivItem)
                    .containsExactly("000010", "900001", "900002", "000020");
            assertThat(call.itemData().get(0).dlvQty()).isEqualByComparingTo("3");
            assertThat(call.itemSerialNo()).singleElement().satisfies(sn -> assertThat(sn.itmNumber()).isEqualTo("000020"));
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
                                    null, "AVAILABLE", null),
                            new ReceiptConfirmation.Line("000020", "SKU-2", new BigDecimal("9"), "CS",
                                    List.of(new ReceiptConfirmation.LotSplit("L1", null, new BigDecimal("5"), null),
                                            new ReceiptConfirmation.LotSplit("L2", null, new BigDecimal("4"), null)),
                                    null, "QI", "SHORT_VENDOR"),
                            new ReceiptConfirmation.Line("000030", "SCANNER", new BigDecimal("2"), "EA", List.of(),
                                    List.of("SN-1", "SN-2"), "AVAILABLE", null)),
                    List.of(new ReceiptConfirmation.HandlingUnit("106141410000000019", null,
                            List.of(new ReceiptConfirmation.HuContent("000010", "B1", new BigDecimal("24"))))));

            Bapi.InbDeliveryConfirmDec call = BapiMapper.confirmInbound(c);

            assertThat(call.delivery()).isEqualTo("0180000123");
            assertThat(call.headerControl().postGoodsReceiptFlag()).isEqualTo("X");
            assertThat(call.headerDeadlines().getFirst().timestampUtc()).isEqualTo("20261002140500");
            assertThat(call.wmsTxnId()).isEqualTo("W1M3S5G87458N692");
            assertThat(call.itemData()).extracting(Bapi.ItemData::delivItem)
                    .containsExactly("000010", "000020", "900001", "900002", "000030");
            assertThat(call.itemSerialNo()).extracting(Bapi.ItemSerialNo::itmNumber, Bapi.ItemSerialNo::serialNo)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("000030", "SN-1"),
                            org.assertj.core.groups.Tuple.tuple("000030", "SN-2"));
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
                            null, "UNRESTRICTED", "ADJUSTMENT x")));
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
                            List.of("SN-9"), "QUALITY_INSPECTION", null)));
            Bapi.GoodsmvtCreate qi = BapiMapper.goodsMovement(fromQi, "1000", ZoneId.of("UTC"));
            assertThat(qi.gmCode()).isEqualTo("04");
            assertThat(qi.items().getFirst().moveType()).isEqualTo("350");
            assertThat(qi.items().getFirst().stckType()).isEqualTo("X");
            assertThat(qi.serials()).singleElement().satisfies(sn -> {
                assertThat(sn.matdocItm()).isEqualTo("0001");
                assertThat(sn.serialNo()).isEqualTo("SN-9");
            });
        }

        @Test
        void materialIssuePostsToTheCostObject_ADR0022() {
            GoodsMovement issue = new GoodsMovement("W1M3S5G87458N695", "ISSUE_WBS", "ISSUE", NOW, null,
                    List.of(new GoodsMovement.Item("SKU-1", new BigDecimal("3"), "EA", "0001", null, null, null, null,
                            "UNRESTRICTED", "ISSUE MI000001")),
                    new GoodsMovement.AccountAssignment("WBS", "P-1000-01", "Site crew North", "MI000001"));
            Bapi.GoodsmvtCreate call = BapiMapper.goodsMovement(issue, "1000", ZoneId.of("UTC"));
            assertThat(call.gmCode()).isEqualTo("03");
            assertThat(call.items().getFirst()).satisfies(i -> {
                assertThat(i.moveType()).isEqualTo("221");
                assertThat(i.wbsElem()).isEqualTo("P-1000-01");
                assertThat(i.costCenter()).isNull();
                assertThat(i.grRcpt()).isEqualTo("Site crew No");                 // GR_RCPT is 12 characters
            });
            GoodsMovement back = new GoodsMovement("W1M3S5G87458N696", "RETURN_COST_CENTER", "ISSUE_RETURN", NOW, null,
                    issue.items(), new GoodsMovement.AccountAssignment("COST_CENTER", "CC100", "J. Doe", "MI000002"));
            Bapi.GoodsmvtItem i = BapiMapper.goodsMovement(back, "1000", ZoneId.of("UTC")).items().getFirst();
            assertThat(i.moveType()).isEqualTo("202");
            assertThat(i.costCenter()).isEqualTo("CC100");
        }

        @Test
        void unknownMovementTypeIsRejected() {
            GoodsMovement m = new GoodsMovement("W1", "TELEPORT", null, NOW, null, List.of());
            assertThatThrownBy(() -> BapiMapper.goodsMovement(m, "1000", ZoneId.of("UTC")))
                    .isInstanceOf(MappingException.class);
        }
    }
}
