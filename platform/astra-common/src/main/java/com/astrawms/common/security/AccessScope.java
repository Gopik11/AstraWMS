package com.astrawms.common.security;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * Attribute constraints on a user's roles (§G.5.1, NFR-101): which sites, owners (3PL clients) and zones the user may
 * work in, and up to which value they may approve. {@code null} means unrestricted. Comes from token claims
 * (see {@code TenantFilter}); AstraWMS service accounts and message consumers are unrestricted.
 */
public record AccessScope(Set<String> sites, Set<String> owners, Set<String> zones, BigDecimal approvalLimit) {

    public static final AccessScope UNRESTRICTED = new AccessScope(null, null, null, null);

    /** The scope of the current request or job; unrestricted when nothing is bound. */
    public static AccessScope current() {
        return TenantContext.current().map(TenantContext.Scope::access).orElse(UNRESTRICTED);
    }

    public boolean allowsSite(String siteId) {
        return sites == null || sites.contains(siteId);
    }

    public boolean allowsOwner(String ownerId) {
        return owners == null || owners.contains(ownerId);
    }

    public boolean allowsZone(String zoneId) {
        return zones == null || zones.contains(zoneId);
    }

    public void requireSite(String siteId) {
        if (!allowsSite(siteId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SCOPE_SITE_DENIED", "You are not authorised for site " + siteId,
                    Map.of("siteId", siteId));
        }
    }

    public void requireOwner(String ownerId) {
        if (!allowsOwner(ownerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SCOPE_OWNER_DENIED",
                    "You are not authorised for owner " + ownerId, Map.of("ownerId", ownerId));
        }
    }

    // ------------------------------------------------------------------ SQL helpers
    // Use as: (:ownersAll or owner_id in (:owners))

    public boolean ownersAll() {
        return owners == null;
    }

    /** The allowed owners for an {@code in (...)} list; never empty (an empty scope matches nothing). */
    public List<String> ownerList() {
        return owners == null || owners.isEmpty() ? List.of("") : List.copyOf(owners);
    }

    public boolean sitesAll() {
        return sites == null;
    }

    /** The allowed sites for an {@code in (...)} list; never empty. Use as: (:sitesAll or site_id in (:sites)). */
    public List<String> siteList() {
        return sites == null || sites.isEmpty() ? List.of("") : List.copyOf(sites);
    }

    public boolean zonesAll() {
        return zones == null;
    }

    public List<String> zoneList() {
        return zones == null || zones.isEmpty() ? List.of("") : List.copyOf(zones);
    }
}
