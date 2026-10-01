package com.astrawms.inventory.domain;

/** Inventory ledger transaction types. */
public enum TxnType {
    RECEIPT,
    MOVE_OUT,
    MOVE_IN,
    ADJUST_POS,
    ADJUST_NEG,
    STATUS_OUT,
    STATUS_IN,
    PICK_OUT,
    PICK_IN,
    ISSUE,
    RETURN_OUT,
    RETURN_IN
}
