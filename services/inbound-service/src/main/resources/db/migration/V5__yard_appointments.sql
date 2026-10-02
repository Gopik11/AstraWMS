-- ADR-0021 yard and dock: appointments (a door booked before the ASN may exist), gate check-in, door, check-out.
-- Dwell = time in the yard since check-in; door time = since the trailer reached the door.
create table dock_appointment (
    id              uuid        primary key,
    tenant_id       text        not null,
    site_id         text        not null,
    appt_no         text        not null,
    direction       text        not null check (direction in ('INBOUND', 'OUTBOUND')),
    door            text,
    carrier_scac    text,
    trailer_no      text,
    doc_no          text,
    scheduled_start timestamptz not null,
    scheduled_end   timestamptz not null,
    status          text        not null check (status in
                        ('SCHEDULED', 'CHECKED_IN', 'AT_DOOR', 'CHECKED_OUT', 'CANCELLED', 'NO_SHOW')),
    note            text,
    checked_in_at   timestamptz,
    at_door_at      timestamptz,
    checked_out_at  timestamptz,
    created_by      text        not null,
    created_at      timestamptz not null,
    updated_by      text        not null,
    updated_at      timestamptz not null,
    unique (tenant_id, site_id, appt_no),
    check (scheduled_end > scheduled_start)
);
create index dock_appointment_day_idx on dock_appointment (tenant_id, site_id, scheduled_start);
create index dock_appointment_doc_idx on dock_appointment (tenant_id, site_id, doc_no);
alter table dock_appointment enable row level security;
alter table dock_appointment force row level security;
create policy tenant_isolation on dock_appointment
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));
