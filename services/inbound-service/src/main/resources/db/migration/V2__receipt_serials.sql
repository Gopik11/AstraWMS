-- Serial numbers captured at receipt (INB-006); forwarded to inventory and in ReceiptConfirmation (IF-IB-002).
alter table receipt_txn add column serials text[];
