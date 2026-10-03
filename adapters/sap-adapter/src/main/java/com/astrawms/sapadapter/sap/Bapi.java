package com.astrawms.sapadapter.sap;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * BAPI parameter structures as the adapter fills them. A JCo gateway copies these field by field into
 * {@code JCoFunction} import/table parameters; the mock gateway stores them as they are. Field names follow SE37.
 * Names marked (confirm) in the ISDs are to be verified against the customer's release before go-live.
 */
public final class Bapi {

    private Bapi() {
    }

    // ------------------------------------------------------------------ BAPI_INB_DELIVERY_CONFIRM_DEC (IF-IB-002)

    public record InbDeliveryConfirmDec(
            @JsonProperty("HEADER_DATA") HeaderData headerData,
            @JsonProperty("HEADER_CONTROL") HeaderControl headerControl,
            @JsonProperty("DELIVERY") String delivery,
            @JsonProperty("HEADER_DEADLINES") List<Deadline> headerDeadlines,
            @JsonProperty("ITEM_DATA") List<ItemData> itemData,
            @JsonProperty("ITEM_CONTROL") List<ItemControl> itemControl,
            @JsonProperty("HANDLING_UNIT_HEADER") List<HuHeader> handlingUnitHeader,
            @JsonProperty("HANDLING_UNIT_ITEM") List<HuItem> handlingUnitItem,
            @JsonProperty("ITEM_SERIAL_NO") List<ItemSerialNo> itemSerialNo,
            /** Not a BAPI parameter: the reference the adapter uses for the duplicate check (XBLNR). */
            @JsonProperty("X_WMS_TXN_ID") String wmsTxnId) {
    }

    public record HeaderData(@JsonProperty("DELIV_NUMB") String delivNumb) {
    }

    public record HeaderControl(@JsonProperty("DELIV_NUMB") String delivNumb,
                                @JsonProperty("POST_GR_FLG") String postGoodsReceiptFlag) {
    }

    /** TIMETYPE e.g. WSHDRWADTI (actual goods movement date, confirm); TIMESTAMP_UTC yyyyMMddHHmmss. */
    public record Deadline(@JsonProperty("DELIV_NUMB") String delivNumb, @JsonProperty("TIMETYPE") String timetype,
                           @JsonProperty("TIMESTAMP_UTC") String timestampUtc) {
    }

    public record ItemData(@JsonProperty("DELIV_NUMB") String delivNumb, @JsonProperty("DELIV_ITEM") String delivItem,
                           @JsonProperty("MATERIAL") String material, @JsonProperty("BATCH") String batch,
                           @JsonProperty("DLV_QTY") BigDecimal dlvQty, @JsonProperty("SALES_UNIT") String salesUnit,
                           @JsonProperty("HIERARITEM") String hierarItem, @JsonProperty("USEHIERITM") String useHierItm) {
    }

    public record ItemControl(@JsonProperty("DELIV_NUMB") String delivNumb, @JsonProperty("DELIV_ITEM") String delivItem,
                              @JsonProperty("CHG_DELQTY") String chgDelqty) {
    }

    public record ItemSerialNo(@JsonProperty("DELIV_NUMB") String delivNumb, @JsonProperty("ITM_NUMBER") String itmNumber,
                               @JsonProperty("SERIALNO") String serialNo) {
    }

    public record HuHeader(@JsonProperty("DELIV_NUMB") String delivNumb, @JsonProperty("HDL_UNIT_EXID") String hdlUnitExid,
                           @JsonProperty("HDL_UNIT_EXID_TY") String hdlUnitExidTy,
                           @JsonProperty("SHIP_MAT") String shipMat) {
    }

    public record HuItem(@JsonProperty("DELIV_NUMB") String delivNumb, @JsonProperty("HDL_UNIT_INTO") String hdlUnitInto,
                         @JsonProperty("DELIV_ITEM") String delivItem, @JsonProperty("PACK_QTY") BigDecimal packQty,
                         @JsonProperty("BATCH") String batch) {
    }

    // ------------------------------------------------------------------ BAPI_OUTB_DELIVERY_CONFIRM_DEC (IF-OB-003)

    public record OutbDeliveryConfirmDec(
            @JsonProperty("HEADER_DATA") OutbHeaderData headerData,
            @JsonProperty("HEADER_CONTROL") OutbHeaderControl headerControl,
            @JsonProperty("DELIVERY") String delivery,
            @JsonProperty("HEADER_DEADLINES") List<Deadline> headerDeadlines,
            @JsonProperty("ITEM_DATA") List<ItemData> itemData,
            @JsonProperty("ITEM_CONTROL") List<ItemControl> itemControl,
            @JsonProperty("ITEM_SERIAL_NO") List<ItemSerialNo> itemSerialNo,
            @JsonProperty("X_WMS_TXN_ID") String wmsTxnId) {
    }

    /** BOL_NR / TRAID carry the bill of lading and the means-of-transport ID (tracking) (confirm field names). */
    public record OutbHeaderData(@JsonProperty("DELIV_NUMB") String delivNumb, @JsonProperty("BOL_NR") String bolNr,
                                 @JsonProperty("TRAID") String traid) {
    }

    public record OutbHeaderControl(@JsonProperty("DELIV_NUMB") String delivNumb,
                                    @JsonProperty("POST_GI_FLG") String postGoodsIssueFlag) {
    }

    // ------------------------------------------------------------------ BAPI_GOODSMVT_CREATE (IF-INV-001)

    public record GoodsmvtCreate(
            @JsonProperty("GOODSMVT_HEADER") GoodsmvtHeader header,
            @JsonProperty("GOODSMVT_CODE") String gmCode,
            @JsonProperty("GOODSMVT_ITEM") List<GoodsmvtItem> items,
            @JsonProperty("GOODSMVT_SERIALNUMBER") List<GoodsmvtSerial> serials) {
    }

    /** MATDOC_ITM: 4-digit position of the item within GOODSMVT_ITEM (0001-based). */
    public record GoodsmvtSerial(@JsonProperty("MATDOC_ITM") String matdocItm, @JsonProperty("SERIALNO") String serialNo) {
    }

    /** REF_DOC_NO → MKPF-XBLNR carries the WMS transaction ID (INT-014). Dates yyyyMMdd, plant local. */
    public record GoodsmvtHeader(@JsonProperty("PSTNG_DATE") String pstngDate, @JsonProperty("DOC_DATE") String docDate,
                                 @JsonProperty("REF_DOC_NO") String refDocNo, @JsonProperty("HEADER_TXT") String headerTxt) {
    }

    public record GoodsmvtItem(@JsonProperty("MATERIAL") String material, @JsonProperty("PLANT") String plant,
                               @JsonProperty("STGE_LOC") String stgeLoc, @JsonProperty("BATCH") String batch,
                               @JsonProperty("MOVE_TYPE") String moveType, @JsonProperty("STCK_TYPE") String stckType,
                               @JsonProperty("ENTRY_QNT") BigDecimal entryQnt, @JsonProperty("ENTRY_UOM") String entryUom,
                               @JsonProperty("MOVE_STLOC") String moveStloc, @JsonProperty("ITEM_TEXT") String itemText,
                               @JsonProperty("COSTCENTER") String costCenter, @JsonProperty("WBS_ELEM") String wbsElem,
                               @JsonProperty("ORDERID") String orderId, @JsonProperty("GR_RCPT") String grRcpt) {

        public GoodsmvtItem(String material, String plant, String stgeLoc, String batch, String moveType, String stckType,
                            BigDecimal entryQnt, String entryUom, String moveStloc, String itemText) {
            this(material, plant, stgeLoc, batch, moveType, stckType, entryQnt, entryUom, moveStloc, itemText, null, null,
                    null, null);
        }
    }

    // ------------------------------------------------------------------ BAPIRET2-style result

    public record Return(@JsonProperty("TYPE") String type, @JsonProperty("ID") String id,
                         @JsonProperty("NUMBER") String number, @JsonProperty("MESSAGE") String message) {
        public boolean isError() {
            return "E".equals(type) || "A".equals(type);
        }
    }
}
