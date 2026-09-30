package com.astrawms.inventory.domain;

/**
 * WMS stock statuses (scope §6.1) and the ERP stock type each one reconciles to.
 * ALLOCATED is not a status: it is tracked as {@code allocated_qty} on AVAILABLE balances.
 */
public enum StockStatus {
    AVAILABLE(ErpStockType.UNRESTRICTED),
    QI(ErpStockType.QUALITY_INSPECTION),
    BLOCKED(ErpStockType.BLOCKED),
    DAMAGED(ErpStockType.BLOCKED),
    EXPIRED(ErpStockType.BLOCKED);

    private final ErpStockType erpStockType;

    StockStatus(ErpStockType erpStockType) {
        this.erpStockType = erpStockType;
    }

    public ErpStockType erpStockType() {
        return erpStockType;
    }

    public enum ErpStockType { UNRESTRICTED, QUALITY_INSPECTION, BLOCKED }
}
