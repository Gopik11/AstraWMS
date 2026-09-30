package com.astrawms.sapadapter.mapping;

import java.util.Map;
import java.util.Optional;

/**
 * Value maps between SAP codes and canonical codes. In a customer project these come from the adapter's mapping
 * tables (ISD open points); the defaults below are SAP standard values.
 */
public final class SapCodes {

    private SapCodes() {
    }

    /** SAP internal unit (T006-MSEHI, as delivered in DELVRY in the logon language EN) ↔ canonical UoM (ISD-00 §3.1). */
    private static final Map<String, String> SAP_TO_UOM = Map.of(
            "ST", "EA", "PC", "EA", "EA", "EA", "KAR", "CS", "CS", "CS", "PAL", "PAL", "KG", "KG", "G", "G", "L", "L",
            "M", "M");
    private static final Map<String, String> UOM_TO_SAP = Map.of(
            "EA", "ST", "CS", "KAR", "PAL", "PAL", "KG", "KG", "G", "G", "L", "L", "M", "M", "IN", "IN", "BX", "BOX");

    public static Optional<String> uomFromSap(String sapUnit) {
        return Optional.ofNullable(sapUnit).map(String::trim).map(SAP_TO_UOM::get);
    }

    public static String uomToSap(String uom) {
        return UOM_TO_SAP.getOrDefault(uom, uom);
    }

    /** Delivery type (LFART) → canonical erpDocType (ISD IF-IB-001 §5). */
    public static String docType(String lfart) {
        if (lfart == null) {
            return "OTHER";
        }
        return switch (lfart.trim()) {
            case "EL" -> "VENDOR_ASN";
            case "NL", "NLCC" -> "STO";
            default -> "OTHER";
        };
    }

    /** Delivery item stock type (INSMK) → canonical stockTypeTarget. */
    public static String stockType(String insmk) {
        if (insmk == null || insmk.isBlank()) {
            return "AVAILABLE";
        }
        return switch (insmk.trim()) {
            case "X", "2" -> "QI";
            case "S", "3" -> "BLOCKED";
            default -> "AVAILABLE";
        };
    }

    /** Canonical ERP stock type → BAPI_GOODSMVT_CREATE STCK_TYPE. */
    public static String stckType(String stockType) {
        if (stockType == null) {
            return " ";
        }
        return switch (stockType) {
            case "QUALITY_INSPECTION" -> "X";
            case "BLOCKED" -> "S";
            default -> " ";
        };
    }

    /** SAP movement for a canonical GoodsMovement type (ISD IF-INV-001 §5.1): GM_CODE and MOVE_TYPE. */
    public record Movement(String gmCode, String moveType) {
    }

    private static final Map<String, Movement> MOVEMENTS = Map.of(
            "ADJ_POS", new Movement("05", "701"),
            "ADJ_NEG", new Movement("03", "702"),
            "STATUS_AVL_TO_QI", new Movement("04", "322"),
            "STATUS_QI_TO_AVL", new Movement("04", "321"),
            "STATUS_AVL_TO_BLK", new Movement("04", "344"),
            "STATUS_BLK_TO_AVL", new Movement("04", "343"),
            "STATUS_QI_TO_BLK", new Movement("04", "350"),
            "STATUS_BLK_TO_QI", new Movement("04", "349"),
            "BUCKET_TRANSFER", new Movement("04", "311"));

    public static Optional<Movement> movement(String movementType) {
        return Optional.ofNullable(MOVEMENTS.get(movementType));
    }
}
