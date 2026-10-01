-- AstraWMS SAP adapter (release 0.1): configuration, IDoc status tracking and the simulated SAP backend used by
-- MockSapGateway. The mock_sap_* tables are replaced by a real SAP system (JCo gateway) in integration environments.

-- SAP plant → AstraWMS site (ISD-00 §3.2 site map)
create table site_map (
    tenant_id     text not null,
    werks         text not null,
    site_id       text not null,
    time_zone     text not null,
    default_owner text not null,
    primary key (tenant_id, werks),
    unique (tenant_id, site_id)
);

-- IDoc processing status as SAP shows it in WE02/BD87: 03 dispatched, 53 posted, 51 error.
create table idoc_status (
    tenant_id    text        not null,
    idoc_number  text        not null,
    message_type text        not null,
    direction    text        not null check (direction in ('INBOUND_TO_WMS', 'OUTBOUND_FROM_WMS')),
    vbeln        text,
    status       char(2)     not null,
    status_text  text,
    message_id   uuid,
    updated_at   timestamptz not null,
    primary key (tenant_id, idoc_number)
);
create index idoc_status_message_idx on idoc_status (message_id);

-- ---------------------------------------------------------------- simulated SAP backend (MockSapGateway)
create sequence mock_sap_matdoc_seq start with 4900000000;

create table mock_sap_document (
    tenant_id         text        not null,
    material_document text        not null,
    doc_year          text        not null,
    doc_type          text        not null check (doc_type in ('GR_INBOUND_DELIVERY', 'GOODS_MOVEMENT')),
    xblnr             varchar(16) not null,     -- WMS transaction ID: SAP-side duplicate check (INT-014)
    vbeln             text,
    payload           jsonb       not null,     -- the BAPI call as the adapter built it
    created_at        timestamptz not null,
    primary key (tenant_id, material_document, doc_year),
    unique (tenant_id, xblnr)
);

-- Fault injection: key = delivery number (VBELN) or material number (MATNR)
create table mock_sap_fault (
    tenant_id text    not null,
    fault_key text    not null,
    fault     text    not null check (fault in ('PERIOD_CLOSED', 'BATCH_MISSING', 'LOCKED')),
    remaining integer,                          -- null = until removed; n = the next n calls
    primary key (tenant_id, fault_key)
);

do $$
declare t text;
begin
    foreach t in array array['site_map', 'idoc_status', 'mock_sap_document', 'mock_sap_fault']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
                         using (tenant_id = current_setting('app.tenant_id', true))
                         with check (tenant_id = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
