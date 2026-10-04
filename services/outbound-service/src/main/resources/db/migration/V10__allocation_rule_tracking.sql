-- ADR-0025 cross-site allocation: when stock is short, waiting lines are served by priority, then promised date,
-- then criticality, then distance (nearest first), then age; the rule that allocated a line is kept on the line.
alter table outbound_order add column criticality text not null default 'NORMAL'
    check (criticality in ('LOW', 'NORMAL', 'HIGH', 'CRITICAL'));
alter table outbound_order add column distance_km numeric(9, 1);
alter table outbound_line add column allocation_rule text;

-- Carrier tracking is a status on the load (no TMS): updated by the carrier webhook or by hand.
alter table shipment_load add column tracking_no text;
alter table shipment_load add column tracking_status text
    check (tracking_status in ('PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'EXCEPTION'));
alter table shipment_load add column tracking_detail text;
alter table shipment_load add column tracking_updated_at timestamptz;
