package com.astrawms.masterdata.api;

import com.astrawms.masterdata.platform.TenantDirectory;
import com.astrawms.masterdata.platform.TenantDirectory.AdminRequest;
import com.astrawms.masterdata.platform.TenantDirectory.StatusRequest;
import com.astrawms.masterdata.platform.TenantDirectory.TenantRequest;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform API (ADR-0030 A): the tenant registry, across tenants. Binds no tenant (TenantFilter skips
 * {@code /api/v1/platform/}); every endpoint names its role, since an authenticated user of a tenant must not reach it.
 */
@RestController
@RequestMapping("/api/v1/platform/tenants")
public class PlatformController {

    private final TenantDirectory tenants;

    public PlatformController(TenantDirectory tenants) {
        this.tenants = tenants;
    }

    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','WMS_SERVICE')")
    @GetMapping
    public List<Map<String, Object>> list() {
        return tenants.list();
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/{tenantId}")
    public Map<String, Object> get(@PathVariable String tenantId) {
        return tenants.get(tenantId);
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/{tenantId}/history")
    public List<Map<String, Object>> history(@PathVariable String tenantId) {
        return tenants.history(tenantId);
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody TenantRequest body, Principal user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tenants.create(body, user.getName()));
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PutMapping("/{tenantId}")
    public Map<String, Object> update(@PathVariable String tenantId, @RequestBody TenantRequest body, Principal user) {
        return tenants.update(tenantId, body, user.getName());
    }

    /** ACTIVE, SUSPENDED or OFFBOARDING, with a reason; every change is recorded in the history. */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PostMapping("/{tenantId}/status")
    public Map<String, Object> status(@PathVariable String tenantId, @RequestBody StatusRequest body, Principal user) {
        return tenants.changeStatus(tenantId, body, user.getName());
    }

    /**
     * Creates an administrator of the tenant in Keycloak (role SOLUTION_ADMIN, never PLATFORM_ADMIN). The response holds
     * the temporary password, once: it is not stored, and the user must change it at first sign-in.
     */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PostMapping("/{tenantId}/admins")
    public ResponseEntity<Map<String, Object>> createAdmin(@PathVariable String tenantId, @RequestBody AdminRequest body,
                                                           Principal user) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(tenants.createAdmin(tenantId, body, user.getName()));
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/{tenantId}/admins")
    public List<Map<String, Object>> admins(@PathVariable String tenantId) {
        return tenants.admins(tenantId);
    }
}
