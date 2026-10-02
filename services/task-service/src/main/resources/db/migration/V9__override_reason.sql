-- ADR-0021: why an operator put an LPN somewhere other than the suggested location.
alter table task add column override_reason text;
