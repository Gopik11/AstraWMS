-- ADR-0019: backorder recovery serves short lines by planned goods issue, then order priority (higher first).
alter table outbound_order add column priority integer not null default 50;
create index outbound_line_short_idx on outbound_line (tenant_id, owner_id, item_no) where qty_short > 0;
