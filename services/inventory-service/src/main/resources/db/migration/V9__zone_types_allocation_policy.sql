-- ADR-0019: the zone's role decides which stock is allocable and where demand replenishment draws from.
-- Filled by LocationUpserted 1.2; null for locations not yet republished (treated as storage).
alter table ref_location add column zone_type text;
