-- ADR-0020: what the picker decided for a short pick, and which short quantity is final.
--   qty_short_closed: part of qty_short that ships short (SHIP_SHORT, an unrecoverable reallocation, or a supervisor's
--                     "close shorts"); backorder recovery only allocates qty_short - qty_short_closed.
--   short_hold:       a BACKORDER short keeps the order RELEASED (not PICKED) until it is recovered or closed.
alter table outbound_line add column qty_short_closed numeric(18, 3) not null default 0;
alter table outbound_line add column short_hold boolean not null default false;
-- Shorts recorded before this release were final (they shipped short): keep it that way.
update outbound_line set qty_short_closed = qty_short_pick where qty_short_pick > 0;

alter table outbound_allocation add column short_reason text;
alter table outbound_allocation add column short_action text;
