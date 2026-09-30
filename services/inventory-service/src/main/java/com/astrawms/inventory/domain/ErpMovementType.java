package com.astrawms.inventory.domain;

import com.astrawms.inventory.domain.StockStatus.ErpStockType;
import java.util.Optional;

/**
 * WMS movement types sent to the ERP through IF-INV-001 (ISD IF-INV-001 §5.1). The adapter maps each value to the
 * SAP movement type / Oracle transaction type; this service only decides <em>whether</em> and <em>which</em>
 * canonical movement is ERP-relevant.
 */
public enum ErpMovementType {
    ADJ_POS,
    ADJ_NEG,
    STATUS_AVL_TO_QI,
    STATUS_QI_TO_AVL,
    STATUS_AVL_TO_BLK,
    STATUS_BLK_TO_AVL,
    STATUS_QI_TO_BLK,
    STATUS_BLK_TO_QI,
    BUCKET_TRANSFER;

    /**
     * Returns the ERP movement for a WMS status change, or empty when both statuses map to the same ERP stock type
     * (e.g. DAMAGED → BLOCKED needs no ERP posting).
     */
    public static Optional<ErpMovementType> forStatusChange(StockStatus from, StockStatus to) {
        ErpStockType f = from.erpStockType();
        ErpStockType t = to.erpStockType();
        if (f == t) {
            return Optional.empty();
        }
        return Optional.of(switch (f) {
            case UNRESTRICTED -> t == ErpStockType.QUALITY_INSPECTION ? STATUS_AVL_TO_QI : STATUS_AVL_TO_BLK;
            case QUALITY_INSPECTION -> t == ErpStockType.UNRESTRICTED ? STATUS_QI_TO_AVL : STATUS_QI_TO_BLK;
            case BLOCKED -> t == ErpStockType.UNRESTRICTED ? STATUS_BLK_TO_AVL : STATUS_BLK_TO_QI;
        });
    }
}
