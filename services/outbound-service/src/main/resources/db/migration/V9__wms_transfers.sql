-- ADR-0023: transfers between sites started in the WMS (main warehouse to satellite store, store to store) without an
-- SAP stock transport order. The order is picked and shipped like any other; the receiving site gets an expected
-- receipt, and SAP is posted as a two-step stock transfer (303 at shipment, 305 at receipt).
alter table outbound_order add column transfer_to_site text;
alter table outbound_order add column note text;
create index outbound_order_transfer_idx on outbound_order (tenant_id, site_id, transfer_to_site)
    where transfer_to_site is not null;
