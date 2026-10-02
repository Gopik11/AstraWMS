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
 *   <li>{@code lotAffinity} (ADR-0021): a line of a lot-controlled item is filled from one lot — the first lot in
 *       rotation order that can cover it — and mixes lots only when no single lot can.</li>
 * </ul>
 * An owner (3PL client) can have its own policy at a site (ADR-0021); it replaces the site's for that owner's lines.
 */
@Component
public class AllocationPolicies {

    public enum FullLpn { COVERED_ONLY, SPLIT_ALLOWED, NEVER_SPLIT }

    public record Policy(String lotRotation, String otherRotation, boolean pickFaceFirst, FullLpn fullLpn,
                         String updatedBy, java.time.Instant updatedAt, boolean lotAffinity, String ownerId) {

        public boolean fefo(boolean lotControlled) {
            return "FEFO".equals(lotControlled ? lotRotation : otherRotation);
        }
    }

    public static final Policy DEFAULT = new Policy("FEFO", "FIFO", true, FullLpn.COVERED_ONLY, null, null, false, null);
    private static final List<String> ROTATIONS = List.of("FEFO", "FIFO");

    private final JdbcClient jdbc;
    private final Clock clock;

    public AllocationPolicies(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The site's own policy. */
    public Policy of(String siteId) {
        return find(siteId, "").orElse(DEFAULT);
    }

    /** The policy for an owner's lines: the owner's own at this site, else the site's. */
    public Policy of(String siteId, String ownerId) {
        return ownerId == null ? of(siteId) : find(siteId, ownerId).orElseGet(() -> of(siteId));
    }

    /** The owner overrides of a site. */
    public List<Policy> owners(String siteId) {
        return jdbc.sql("""
                        select lot_rotation, other_rotation, pick_face_first, full_lpn, updated_by, updated_at, lot_affinity,
                               owner_id
                        from allocation_policy where site_id = :site and owner_id <> '' order by owner_id""")
                .param("site", siteId).query(AllocationPolicies::map).list();
    }

    private java.util.Optional<Policy> find(String siteId, String ownerId) {
        return jdbc.sql("""
                        select lot_rotation, other_rotation, pick_face_first, full_lpn, updated_by, updated_at, lot_affinity,
                               owner_id
                        from allocation_policy where site_id = :site and owner_id = :owner""")
                .param("site", siteId).param("owner", ownerId).query(AllocationPolicies::map).optional();
    }

    private static Policy map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        String owner = rs.getString(8);
        return new Policy(rs.getString(1), rs.getString(2), rs.getBoolean(3), FullLpn.valueOf(rs.getString(4)),
                rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getBoolean(7), owner.isEmpty() ? null : owner);
    }

    @Transactional
    public Policy put(String siteId, String lotRotation, String otherRotation, Boolean pickFaceFirst, String fullLpn) {
        return put(siteId, null, lotRotation, otherRotation, pickFaceFirst, fullLpn, null);
    }

    /** Sets the site's policy ({@code ownerId} null) or an owner's; null fields keep the current (or inherited) value. */
    @Transactional
    public Policy put(String siteId, String ownerId, String lotRotation, String otherRotation, Boolean pickFaceFirst,
                      String fullLpn, Boolean lotAffinity) {
        String owner = ownerId == null || ownerId.isBlank() ? "" : ownerId.trim().toUpperCase();
        Policy cur = owner.isEmpty() ? of(siteId) : of(siteId, owner);
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
        boolean affinity = lotAffinity == null ? cur.lotAffinity() : lotAffinity;
        jdbc.sql("""
                        insert into allocation_policy (tenant_id, site_id, owner_id, lot_rotation, other_rotation,
                                                       pick_face_first, full_lpn, lot_affinity, updated_by, updated_at)
                        values (:t, :site, :owner, :lot, :other, :face, :full, :affinity, :user, :now)
                        on conflict (tenant_id, site_id, owner_id) do update set lot_rotation = excluded.lot_rotation,
                            other_rotation = excluded.other_rotation, pick_face_first = excluded.pick_face_first,
                            full_lpn = excluded.full_lpn, lot_affinity = excluded.lot_affinity,
                            updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("site", siteId).param("owner", owner).param("lot", lot)
                .param("other", other).param("face", face).param("full", full.name()).param("affinity", affinity)
                .param("user", TenantContext.require().userId()).param("now", Timestamp.from(clock.instant())).update();
        return owner.isEmpty() ? of(siteId) : of(siteId, owner);
    }

    /** Removes an owner's override: its lines follow the site's policy again. */
    @Transactional
    public void deleteOwner(String siteId, String ownerId) {
        jdbc.sql("delete from allocation_policy where site_id = :site and owner_id = :owner and owner_id <> ''")
                .param("site", siteId).param("owner", ownerId.trim().toUpperCase()).update();
    }
}
