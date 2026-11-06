package com.astrawms.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.astrawms.common.security.Roles;
import com.astrawms.masterdata.platform.IdentityProvisioner;
import com.astrawms.masterdata.platform.TenantUsers;
import com.astrawms.test.AstraContainers;
import com.astrawms.test.AstraMockMvc;
import com.astrawms.test.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A tenant's administrator manages the tenant's accounts (ADR-0031): only its own tenant, only tenant roles, no wider
 * scope than its own, never its own roles, with an audit trail and the temporary password shown once. The identity
 * provider is an in-memory stand-in here; KeycloakIdentityIT covers the real one.
 */
@SpringBootTest
class UserAdminIT {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        AstraContainers.register(r);
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    MemoryUsers identity;

    @Autowired
    JsonMapper json;
    MockMvc mvc;
    String tenant;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        mvc = AstraMockMvc.create(context);
        tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
        admin = TestTokens.bearer(TestTokens.token().tenant(tenant).user("boss").roles(Roles.SOLUTION_ADMIN).sign());
    }

    private ResultActions call(MockHttpServletRequestBuilder r, RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(r.with(who).contentType(MediaType.APPLICATION_JSON).content(body == null ? "" : body));
    }

    private String create(RequestPostProcessor who, String username, String roles, String sites) throws Exception {
        String body = call(post("/api/v1/users"), who, """
                {"username":"%s","roles":[%s],"sites":[%s],"owners":["*"],"zones":["*"]}""".formatted(username, roles, sites))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).at("/user/id").asText();
    }

    @Test
    void anAdministratorCreatesAUserOfItsOwnTenantAndThePasswordIsShownOnce() throws Exception {
        call(post("/api/v1/users"), admin, """
                {"username":" Rita.Receiver ","email":"rita@acme.example","firstName":"Rita","roles":["receiver","PICKER"],
                 "sites":["DC1"],"owners":["*"],"zones":["*"],"approvalLimit":"250.00"}""")
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.user.username", is("rita.receiver")))
                .andExpect(jsonPath("$.user.roles", org.hamcrest.Matchers.contains("RECEIVER", "PICKER")))
                .andExpect(jsonPath("$.user.sites[0]", is("DC1")))
                .andExpect(jsonPath("$.user.approvalLimit", is("250")))
                .andExpect(jsonPath("$.temporaryPassword", is("Temp0rary-Passw0rd")))
                .andExpect(jsonPath("$.passwordChangeRequired", is(true)));
        assertThat(identity.tenantOf("rita.receiver")).isEqualTo(tenant);

        call(get("/api/v1/users"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", hasItem("rita.receiver")));
        // the trail says who did what, never a password
        call(get("/api/v1/users/audit"), admin, null)
                .andExpect(jsonPath("$[0].action", is("CREATED")))
                .andExpect(jsonPath("$[0].changed_by", is("boss")))
                .andExpect(jsonPath("$[0].detail", containsString("roles RECEIVER,PICKER; sites DC1")))
                .andExpect(content().string(not(containsString("Temp0rary"))));
    }

    @Test
    void onlyAdministratorsManageUsersAndNeverAnotherTenantsOrAPlatformRole() throws Exception {
        String id = create(admin, "u-" + tenant, "\"PICKER\"", "\"*\"");
        String other = "t-" + UUID.randomUUID().toString().substring(0, 8);
        RequestPostProcessor otherAdmin = TestTokens.bearer(TestTokens.token().tenant(other).user("x").roles(Roles.SOLUTION_ADMIN).sign());

        call(get("/api/v1/users"), TestTokens.as(tenant, "sup", Roles.SUPERVISOR), null).andExpect(status().isForbidden());
        call(post("/api/v1/users"), TestTokens.as(tenant, "sup", Roles.SUPERVISOR),
                "{\"username\":\"sneaky\",\"roles\":[\"SOLUTION_ADMIN\"],\"sites\":[\"*\"],\"owners\":[\"*\"],\"zones\":[\"*\"]}")
                .andExpect(status().isForbidden());
        // another tenant's administrator does not see, change or reset the account
        call(get("/api/v1/users"), otherAdmin, null).andExpect(jsonPath("$[*].id", not(hasItem(id))));
        call(get("/api/v1/users/" + id), otherAdmin, null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code", is("USER_UNKNOWN")));
        call(put("/api/v1/users/" + id), otherAdmin, "{\"enabled\":false}").andExpect(status().isNotFound());
        call(post("/api/v1/users/" + id + "/password"), otherAdmin, null).andExpect(status().isNotFound());
        assertThat(identity.find(id).orElseThrow().enabled()).isTrue();

        for (String role : List.of(Roles.PLATFORM_ADMIN, Roles.WMS_SERVICE, Roles.ERP_INTEGRATION, Roles.AUTOMATION)) {
            call(post("/api/v1/users"), admin, """
                    {"username":"r-%s","roles":["%s"],"sites":["*"],"owners":["*"],"zones":["*"]}""".formatted(tenant, role))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("USER_ROLE_NOT_GRANTABLE")));
            call(put("/api/v1/users/" + id), admin, "{\"roles\":[\"%s\"]}".formatted(role))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("USER_ROLE_NOT_GRANTABLE")));
        }
        call(post("/api/v1/users"), admin, "{\"username\":\"no-roles\",\"roles\":[],\"sites\":[\"*\"],\"owners\":[\"*\"],\"zones\":[\"*\"]}")
                .andExpect(jsonPath("$.code", is("USER_ROLES_REQUIRED")));
        call(post("/api/v1/users"), admin, "{\"username\":\"no-scope\",\"roles\":[\"PICKER\"]}")
                .andExpect(jsonPath("$.code", is("USER_SCOPE_REQUIRED")));
    }

    @Test
    void aSiteScopedAdministratorGrantsAndManagesOnlyWithinItsSites() throws Exception {
        String wide = create(admin, "wide-" + tenant, "\"PICKER\"", "\"*\"");
        RequestPostProcessor dc1Admin = TestTokens.bearer(TestTokens.token().tenant(tenant).user("dc1-boss")
                .roles(Roles.SOLUTION_ADMIN).sites("DC1").sign());

        call(post("/api/v1/users"), dc1Admin, """
                {"username":"p2-%s","roles":["PICKER"],"sites":["DC2"],"owners":["*"],"zones":["*"]}""".formatted(tenant))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("USER_SCOPE_EXCEEDS")));
        call(post("/api/v1/users"), dc1Admin, """
                {"username":"p3-%s","roles":["PICKER"],"sites":["*"],"owners":["*"],"zones":["*"]}""".formatted(tenant))
                .andExpect(status().isForbidden());
        String mine = create(dc1Admin, "p1-" + tenant, "\"PICKER\"", "\"DC1\"");
        call(put("/api/v1/users/" + mine), dc1Admin, "{\"roles\":[\"PICKER\",\"RECEIVER\"]}").andExpect(status().isOk());
        // an account wider than the administrator is managed by someone with that scope
        call(put("/api/v1/users/" + wide), dc1Admin, "{\"enabled\":false}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("USER_SCOPE_EXCEEDS")));
        call(post("/api/v1/users/" + wide + "/password"), dc1Admin, null).andExpect(status().isForbidden());
    }

    @Test
    void usersAreChangedDisabledAndResetWithATrailAndNobodyChangesTheirOwnAccess() throws Exception {
        String id = create(admin, "pat-" + tenant, "\"PICKER\"", "\"DC1\"");
        call(put("/api/v1/users/" + id), admin, "{\"roles\":[\"PICKER\",\"SUPERVISOR\"],\"sites\":[\"DC1\",\"DC2\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.contains("PICKER", "SUPERVISOR")))
                .andExpect(jsonPath("$.sites", org.hamcrest.Matchers.contains("DC1", "DC2")));
        call(put("/api/v1/users/" + id), admin, "{\"enabled\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled", is(false)));
        call(post("/api/v1/users/" + id + "/password"), admin, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.temporaryPassword", is("Temp0rary-Passw0rd-2")));
        call(get("/api/v1/users/audit"), admin, null)
                .andExpect(jsonPath("$[0].action", is("PASSWORD_RESET")))
                .andExpect(jsonPath("$[1].action", is("DISABLED")))
                .andExpect(jsonPath("$[2].action", is("UPDATED")))
                .andExpect(jsonPath("$[2].detail", containsString("roles PICKER → PICKER,SUPERVISOR")));

        // the administrator's own account: names yes, roles, scope and enabled no
        String self = identity.add(tenant, "boss", List.of(Roles.SOLUTION_ADMIN), true);
        call(put("/api/v1/users/" + self), admin, "{\"firstName\":\"Bo\"}").andExpect(status().isOk());
        call(put("/api/v1/users/" + self), admin, "{\"roles\":[\"PICKER\"]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("USER_SELF_CHANGE")));
        call(put("/api/v1/users/" + self), admin, "{\"enabled\":false}").andExpect(jsonPath("$.code", is("USER_SELF_CHANGE")));
        // an integration account is listed, not managed
        String erp = identity.add(tenant, "erp-" + tenant, List.of(Roles.ERP_INTEGRATION), false);
        call(get("/api/v1/users/" + erp), admin, null).andExpect(jsonPath("$.managed", is(false)));
        call(put("/api/v1/users/" + erp), admin, "{\"enabled\":false}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("USER_NOT_MANAGED")));
    }

    @Test
    void identityFailuresAreReportedAndChangeNothing() throws Exception {
        try {
            identity.failure = new IdentityProvisioner.UserExists("taken");
            call(post("/api/v1/users"), admin, "{\"username\":\"taken\",\"roles\":[\"PICKER\"],\"sites\":[\"*\"],\"owners\":[\"*\"],\"zones\":[\"*\"]}")
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("USER_USERNAME_TAKEN")));
            identity.failure = new IdentityProvisioner.Unavailable("down", null);
            call(post("/api/v1/users"), admin, "{\"username\":\"later\",\"roles\":[\"PICKER\"],\"sites\":[\"*\"],\"owners\":[\"*\"],\"zones\":[\"*\"]}")
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code", is("USER_IDENTITY_UNAVAILABLE")));
        } finally {
            identity.failure = null;
        }
        JsonNode audit = json.readTree(call(get("/api/v1/users/audit"), admin, null).andReturn().getResponse().getContentAsString());
        assertThat(audit.size()).isZero();
    }

    /** An identity provider with several tenants, in memory. */
    static class MemoryUsers implements TenantUsers {
        private final Map<String, String> tenants = new ConcurrentHashMap<>();
        private final Map<String, User> users = new ConcurrentHashMap<>();
        volatile RuntimeException failure;
        private int resets;

        String tenantOf(String username) {
            return users.values().stream().filter(u -> u.username().equals(username)).map(u -> tenants.get(u.id()))
                    .findFirst().orElse(null);
        }

        Optional<User> find(String id) {
            return Optional.ofNullable(users.get(id));
        }

        String add(String tenantId, String username, List<String> roles, boolean managed) {
            String id = UUID.randomUUID().toString();
            users.put(id, new User(id, username, null, null, null, true, roles, List.of("*"), List.of("*"), List.of("*"),
                    null, false, 0L, managed));
            tenants.put(id, tenantId);
            return id;
        }

        private void fail() {
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public List<User> users(String tenantId) {
            fail();
            return new ArrayList<>(users.values().stream().filter(u -> tenantId.equals(tenants.get(u.id()))).toList());
        }

        @Override
        public Optional<User> user(String tenantId, String userId) {
            fail();
            return find(userId).filter(u -> tenantId.equals(tenants.get(userId)));
        }

        @Override
        public Created create(String tenantId, Spec s) {
            fail();
            String id = UUID.randomUUID().toString();
            users.put(id, new User(id, s.username(), s.email(), s.firstName(), s.lastName(), true, s.roles(), s.sites(),
                    s.owners(), s.zones(), s.approvalLimit(), true, 0L, true));
            tenants.put(id, tenantId);
            return new Created(users.get(id), "Temp0rary-Passw0rd");
        }

        @Override
        public User update(String tenantId, String userId, Spec c) {
            fail();
            User u = user(tenantId, userId).orElseThrow(() -> new NoSuchUser(userId));
            if (!u.managed()) {
                throw new NotManaged(u.username());
            }
            User n = new User(u.id(), u.username(), c.email() != null ? c.email() : u.email(),
                    c.firstName() != null ? c.firstName() : u.firstName(), c.lastName() != null ? c.lastName() : u.lastName(),
                    c.enabled() != null ? c.enabled() : u.enabled(), c.roles() != null ? c.roles() : u.roles(),
                    c.sites() != null ? c.sites() : u.sites(), c.owners() != null ? c.owners() : u.owners(),
                    c.zones() != null ? c.zones() : u.zones(), c.approvalLimit() != null ? c.approvalLimit() : u.approvalLimit(),
                    u.passwordChangeRequired(), u.createdAt(), true);
            users.put(userId, n);
            return n;
        }

        @Override
        public synchronized String resetPassword(String tenantId, String userId) {
            fail();
            user(tenantId, userId).orElseThrow(() -> new NoSuchUser(userId));
            return "Temp0rary-Passw0rd-" + (++resets + 1);
        }
    }

    @TestConfiguration
    static class Identity {
        @Bean
        @Primary
        MemoryUsers memoryUsers() {
            return new MemoryUsers();
        }
    }
}
