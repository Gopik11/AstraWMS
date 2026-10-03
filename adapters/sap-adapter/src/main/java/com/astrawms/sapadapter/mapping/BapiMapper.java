package com.astrawms.sapadapter.mapping;

import com.astrawms.common.contracts.IntegrationContracts.GoodsMovement;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.contracts.IntegrationContracts.ReceiptConfirmation;
import com.astrawms.sapadapter.sap.Bapi;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Canonical WMS→ERP messages → BAPI calls (ISD IF-IB-002 §5, ISD IF-INV-001 §5). */
public final class BapiMapper {

    /** First item number SAP uses for batch-split sub-items of a delivery item. */
    static final int BATCH_SPLIT_START = 900001;
    private static final DateTimeFormatter UTC_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SAP_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private BapiMapper() {
    }

    /**
     * ReceiptConfirmation → BAPI_INB_DELIVERY_CONFIRM_DEC.
     * <ul>
     *   <li>one lot: the batch is set on the delivery item itself;</li>
     *   <li>several lots: the delivery item keeps the total, and batch-split sub-items 900001+ carry each lot with
     *       HIERARITEM = parent item and USEHIERITM = 1;</li>
     *   <li>every item is confirmed with CHG_DELQTY = X so short and over receipts adjust the delivery quantity;</li>
     *   <li>handling units carry the LPN/SSCC as external ID (type F = free, confirm).</li>
     * </ul>
     */
    public static Bapi.InbDeliveryConfirmDec confirmInbound(ReceiptConfirmation c) {
        String vbeln = c.erpDocNo();
        List<Bapi.ItemData> items = new ArrayList<>();
        List<Bapi.ItemControl> controls = new ArrayList<>();
        List<Bapi.ItemSerialNo> serials = new ArrayList<>();
        int splitItem = BATCH_SPLIT_START;
        for (ReceiptConfirmation.Line line : c.lines()) {
            String unit = SapCodes.uomToSap(line.uom());
            List<ReceiptConfirmation.LotSplit> splits = line.lotSplits() == null ? List.of() : line.lotSplits();
            String singleBatch = splits.size() == 1 ? splits.getFirst().lotNo() : null;
            items.add(new Bapi.ItemData(vbeln, line.erpLineRef(), line.itemNo(), singleBatch, line.qtyReceived(),
                    unit, null, null));
            controls.add(new Bapi.ItemControl(vbeln, line.erpLineRef(), "X"));
            if (line.serials() != null) {
                line.serials().forEach(sn -> serials.add(new Bapi.ItemSerialNo(vbeln, line.erpLineRef(), sn)));
            }
            if (splits.size() > 1) {
                for (ReceiptConfirmation.LotSplit split : splits) {
                    String item = String.valueOf(splitItem++);
                    items.add(new Bapi.ItemData(vbeln, item, line.itemNo(), split.lotNo(), split.qty(), unit,
                            line.erpLineRef(), "1"));
                    controls.add(new Bapi.ItemControl(vbeln, item, "X"));
                }
            }
        }
        List<Bapi.HuHeader> huHeaders = new ArrayList<>();
        List<Bapi.HuItem> huItems = new ArrayList<>();
        for (ReceiptConfirmation.HandlingUnit hu : c.handlingUnits() == null ? List.<ReceiptConfirmation.HandlingUnit>of() : c.handlingUnits()) {
            huHeaders.add(new Bapi.HuHeader(vbeln, hu.lpnOrSscc(), "F", hu.packagingMaterial()));
            for (ReceiptConfirmation.HuContent content : hu.contents()) {
                huItems.add(new Bapi.HuItem(vbeln, hu.lpnOrSscc(), content.erpLineRef(), content.qty(), content.lotNo()));
            }
        }
        return new Bapi.InbDeliveryConfirmDec(
                new Bapi.HeaderData(vbeln),
                new Bapi.HeaderControl(vbeln, "X"),
                vbeln,
                List.of(new Bapi.Deadline(vbeln, "WSHDRWADTI", UTC_STAMP.format(c.receiptCompletedUtc()))),
                items, controls, huHeaders, huItems, serials, c.wmsTxnId());
    }

    /**
     * ShipmentConfirmation → BAPI_OUTB_DELIVERY_CONFIRM_DEC with post goods issue (ISD IF-OB-003 §5): picked quantity
     * per item with CHG_DELQTY = X (short lines reduce the delivery quantity; zero lines too), batch-split sub-items
     * 900001+ for several lots, serials per delivery item, actual goods-issue time as WSHDRWADTI (confirm).
     */
    public static Bapi.OutbDeliveryConfirmDec confirmOutbound(OutboundContracts.ShipmentConfirmation c) {
        String vbeln = c.erpDocNo();
        List<Bapi.ItemData> items = new ArrayList<>();
        List<Bapi.ItemControl> controls = new ArrayList<>();
        List<Bapi.ItemSerialNo> serials = new ArrayList<>();
        int splitItem = BATCH_SPLIT_START;
        for (OutboundContracts.ShipmentConfirmation.Line line : c.lines()) {
            String unit = SapCodes.uomToSap(line.uom());
            List<OutboundContracts.ShipmentConfirmation.LotSplit> lots = line.lotSplits() == null ? List.of() : line.lotSplits();
            items.add(new Bapi.ItemData(vbeln, line.erpLineRef(), line.itemNo(), lots.size() == 1 ? lots.getFirst().lotNo() : null,
                    line.qtyShipped(), unit, null, null));
            controls.add(new Bapi.ItemControl(vbeln, line.erpLineRef(), "X"));
            if (line.serials() != null) {
                line.serials().forEach(sn -> serials.add(new Bapi.ItemSerialNo(vbeln, line.erpLineRef(), sn)));
            }
            if (lots.size() > 1) {
                for (OutboundContracts.ShipmentConfirmation.LotSplit lot : lots) {
                    String item = String.valueOf(splitItem++);
                    items.add(new Bapi.ItemData(vbeln, item, line.itemNo(), lot.lotNo(), lot.qty(), unit, line.erpLineRef(), "1"));
                    controls.add(new Bapi.ItemControl(vbeln, item, "X"));
                }
            }
        }
        return new Bapi.OutbDeliveryConfirmDec(new Bapi.OutbHeaderData(vbeln, c.billOfLading(), c.trackingNo()),
                new Bapi.OutbHeaderControl(vbeln, "X"), vbeln,
                List.of(new Bapi.Deadline(vbeln, "WSHDRWADTI", UTC_STAMP.format(c.shipDateTimeUtc()))),
                items, controls, serials, c.wmsTxnId());
    }

    /**
     * GoodsMovement → BAPI_GOODSMVT_CREATE. Posting and document date are the plant-local date of the physical
     * movement (INT-015; the period-close re-dating rule is applied by the gateway's error handling).
     */
    public static Bapi.GoodsmvtCreate goodsMovement(GoodsMovement m, String plant, ZoneId plantZone) {
        SapCodes.Movement sap = SapCodes.movement(m.movementType()).orElseThrow(() -> new MappingException(
                "GM_TYPE_UNMAPPED", "Movement type " + m.movementType() + " has no SAP mapping"));
        String date = SAP_DATE.format(m.physicalDateTimeUtc().atZone(plantZone).toLocalDate());
        GoodsMovement.AccountAssignment a = m.account();
        String type = a == null ? "" : a.objectType();
        List<Bapi.GoodsmvtItem> items = m.items().stream().map(i -> new Bapi.GoodsmvtItem(
                i.itemNo(), plant, i.fromBucket(), i.lotNo(), sap.moveType(), SapCodes.stckType(i.stockType()),
                i.qty(), SapCodes.uomToSap(i.uom()), i.toBucket(), truncate(i.text(), 50),
                "COST_CENTER".equals(type) ? a.code() : null, "WBS".equals(type) ? a.code() : null,
                "ORDER".equals(type) ? a.code() : null, a == null ? null : truncate(a.recipient(), 12))).toList();
        if (a != null && a.code() == null) {
            throw new MappingException("GM_ACCOUNT_MISSING", "Consumption posting " + m.wmsTxnId() + " has no cost object");
        }
        List<Bapi.GoodsmvtSerial> serials = new ArrayList<>();
        for (int n = 0; n < m.items().size(); n++) {
            String position = String.format("%04d", n + 1);
            List<String> itemSerials = m.items().get(n).serials();
            if (itemSerials != null) {
                itemSerials.forEach(sn -> serials.add(new Bapi.GoodsmvtSerial(position, sn)));
            }
        }
        return new Bapi.GoodsmvtCreate(
                new Bapi.GoodsmvtHeader(date, date, m.wmsTxnId(), truncate("WMS " + m.movementType() + " " + nz(m.reasonCode()), 25)),
                sap.gmCode(), items, serials);
    }

    private static String truncate(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
