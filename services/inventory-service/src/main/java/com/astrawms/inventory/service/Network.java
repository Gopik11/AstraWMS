package com.astrawms.inventory.service;

import com.astrawms.common.security.AccessScope;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The inventory half of the site network view (ADR-0024): per site what is on hand, allocated and held, what has been
 * waiting at the dock too long and whether a physical inventory has stock frozen; per aisle of one site the same, for
 * the digital twin. Only the sites and owners of the user's access scope are counted.
 */
@Service
public class Network {

    /** Dock stock older than this is an aged exception (the overview's dock tile limit). */
    static final Duration DOCK_LIMIT = Duration.ofMinutes(15);

    private static final String DOCK = """
            (l.location_type in ('DOOR', 'DOCK', 'STAGING', 'STAGING_IN')
             or coalesce(l.zone_type, '') in ('DOCK', 'RECEIVING', 'RETURNS'))""";

    private final JdbcClient jdbc;
    private final Clock clock;

    public Network(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> sites() {
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select b.site_id,
                               sum(b.qty) as on_hand,
                               sum(b.allocated_qty) as allocated,
                               sum(b.qty) filter (where b.stock_status <> 'AVAILABLE') as held,
                               count(distinct b.location_id || '/' || b.lpn_id)
                                   filter (where %s and b.stock_status = 'AVAILABLE' and b.receipt_date < :dockBefore) as dock_aged,
                               (select count(*) from physical_inventory p
                                 where p.site_id = b.site_id and p.status = 'COUNTING' and p.freeze_stock) as pi_frozen
                        from inventory_balance b
                        left join ref_location l on l.site_id = b.site_id and l.location_id = b.location_id
                        where b.qty > 0 and (:sitesAll or b.site_id in (:sites))
                          and (:ownersAll or b.owner_id in (:owners))
                        group by b.site_id order by b.site_id""".formatted(DOCK))
                .param("dockBefore", Timestamp.from(clock.instant().minus(DOCK_LIMIT)))
                .param("sitesAll", scope.sitesAll()).param("sites", scope.siteList())
                .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                .query().listOfRows();
    }

    /**
     * Stock per aisle of a site. The aisle is the location id up to its second dash ({@code A-01-10} is aisle
     * {@code A-01}); a location without one is its own aisle (DOCK-01 is aisle DOCK-01).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> aisles(String siteId) {
        AccessScope scope = AccessScope.current();
        return jdbc.sql("""
                        select coalesce(substring(b.location_id from '^[^-]+-[^-]+'), b.location_id) as aisle,
                               min(coalesce(l.zone_type, '')) as zone_type,
                               sum(b.qty) as on_hand,
                               sum(b.allocated_qty) as allocated,
                               sum(b.qty) filter (where b.stock_status <> 'AVAILABLE') as held,
                               count(*) filter (where %s and b.stock_status = 'AVAILABLE' and b.receipt_date < :dockBefore) as dock_aged,
                               bool_or(f.location_id is not null) as frozen
                        from inventory_balance b
                        left join ref_location l on l.site_id = b.site_id and l.location_id = b.location_id
                        left join location_freeze f on f.site_id = b.site_id and f.location_id = b.location_id
                        where b.site_id = :site and b.qty > 0 and (:ownersAll or b.owner_id in (:owners))
                        group by 1 order by 1""".formatted(DOCK))
                .param("site", siteId).param("dockBefore", Timestamp.from(clock.instant().minus(DOCK_LIMIT)))
                .param("ownersAll", scope.ownersAll()).param("owners", scope.ownerList())
                .query().listOfRows();
    }
}
