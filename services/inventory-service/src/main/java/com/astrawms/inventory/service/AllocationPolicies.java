package com.astrawms.inventory.service;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The allocation policy of a site (ADR-0020), applied by every allocation:
 * <ul>
 *   <li>{@code lotRotation}: rotation of lot-controlled items (FEFO default);</li>
 *   <li>{@code otherRotation}: rotation of other items (FIFO default);</li>
 *   <li>{@code pickFaceFirst}: an item's pick faces before reserve (default true);</li>
 *   <li>{@code fullLpn}: how reserve LPNs are used. {@code COVERED_ONLY} (default): whole when the order covers the LPN,
 *       broken only for items without a pick face; {@code SPLIT_ALLOWED}: broken freely in rotation order;
 *       {@code NEVER_SPLIT}: only whole LPNs leave reserve, the rest stays short until the face is replenished.</li>
 * </ul>
 */
@Component
public class AllocationPolicies {

    public enum FullLpn { COVERED_ONLY, SPLIT_ALLOWED, NEVER_SPLIT }

    public record Policy(String lotRotation, String otherRotation, boolean pickFaceFirst, FullLpn fullLpn,
                         String updatedBy, java.time.Instant updatedAt) {

        public boolean fefo(boolean lotControlled) {
            return "FEFO".equals(lotControlled ? lotRotation : otherRotation);
        }
    }

    public static final Policy DEFAULT = new Policy("FEFO", "FIFO", true, FullLpn.COVERED_ONLY, null, null);
    private static final List<String> ROTATIONS = List.of("FEFO", "FIFO");

    private final JdbcClient jdbc;
    private final Clock clock;

    public AllocationPolicies(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Policy of(String siteId) {
        return jdbc.sql("""
                        select lot_rotation, other_rotation, pick_face_first, full_lpn, updated_by, updated_at
                        from allocation_policy where site_id = :site""")
                .param("site", siteId)
                .query((rs, n) -> new Policy(rs.getString(1), rs.getString(2), rs.getBoolean(3),
                        FullLpn.valueOf(rs.getString(4)), rs.getString(5), rs.getTimestamp(6).toInstant()))
                .optional().orElse(DEFAULT);
    }

    @Transactional
    public Policy put(String siteId, String lotRotation, String otherRotation, Boolean pickFaceFirst, String fullLpn) {
        Policy cur = of(siteId);
        String lot = lotRotation == null ? cur.lotRotation() : lotRotation.trim().toUpperCase();
        String other = otherRotation == null ? cur.otherRotation() : otherRotation.trim().toUpperCase();
        if (!ROTATIONS.contains(lot) || !ROTATIONS.contains(other)) {
            throw ApiException.badRequest("INV_POLICY_INVALID", "Rotations must be FEFO or FIFO");
        }
        FullLpn full;
        try {
            full = fullLpn == null ? cur.fullLpn() : FullLpn.valueOf(fullLpn.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INV_POLICY_INVALID", "fullLpn must be one of " + List.of(FullLpn.values()));
        }
        boolean face = pickFaceFirst == null ? cur.pickFaceFirst() : pickFaceFirst;
        jdbc.sql("""
                        insert into allocation_policy (tenant_id, site_id, lot_rotation, other_rotation, pick_face_first,
                                                       full_lpn, updated_by, updated_at)
                        values (:t, :site, :lot, :other, :face, :full, :user, :now)
                        on conflict (tenant_id, site_id) do update set lot_rotation = excluded.lot_rotation,
                            other_rotation = excluded.other_rotation, pick_face_first = excluded.pick_face_first,
                            full_lpn = excluded.full_lpn, updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("site", siteId).param("lot", lot).param("other", other)
                .param("face", face).param("full", full.name()).param("user", TenantContext.require().userId())
                .param("now", Timestamp.from(clock.instant())).update();
        return of(siteId);
    }
}
