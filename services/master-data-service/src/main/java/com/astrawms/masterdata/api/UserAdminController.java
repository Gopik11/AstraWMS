package com.astrawms.masterdata.api;

import com.astrawms.masterdata.platform.TenantUsers;
import com.astrawms.masterdata.users.TenantUserAdmin;
import com.astrawms.masterdata.users.TenantUserAdmin.UserRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant's sign-in accounts, for its administrators (ADR-0031). Bound to the caller's tenant like every tenant API;
 * the identity provider's own console is not needed (and in the shared realm would show every tenant).
 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('SOLUTION_ADMIN')")
public class UserAdminController {

    private final TenantUserAdmin admin;

    public UserAdminController(TenantUserAdmin admin) {
        this.admin = admin;
    }

    @GetMapping
    public List<TenantUsers.User> list() {
        return admin.list();
    }

    /** The roles an administrator may grant. */
    @GetMapping("/roles")
    public List<String> roles() {
        return TenantUsers.ROLES;
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "100") int limit) {
        return admin.audit(Math.max(1, Math.min(limit, 500)));
    }

    @GetMapping("/{userId}")
    public TenantUsers.User get(@PathVariable String userId) {
        return admin.get(userId);
    }

    /** The response holds the temporary password, once: it is not stored, and the user changes it at first sign-in. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody UserRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(admin.create(body));
    }

    /** Fields left out are not changed. {@code enabled: false} also ends the user's sessions. */
    @PutMapping("/{userId}")
    public TenantUsers.User update(@PathVariable String userId, @RequestBody UserRequest body) {
        return admin.update(userId, body);
    }

    @PostMapping("/{userId}/password")
    public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable String userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(admin.resetPassword(userId));
    }
}
