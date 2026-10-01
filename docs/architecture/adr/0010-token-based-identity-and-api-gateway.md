# ADR-0010: Token-Based Identity, Role Checks and an API Gateway

- **Status:** Accepted
- **Date:** 2026-10-01
- **Supersedes:** the header-based tenant binding of release 0.1 (ADR-0003 §Context)

## Context

Up to now the services took the tenant and user from `X-Tenant-Id` / `X-User-Id` headers and had no authentication. Row-level security (ADR-0003) isolated tenants inside the database, but it trusted whichever tenant a caller named. Anyone who could reach a service port could act as any user of any tenant. Approvals (`approvedBy`) were free text.

This blocked any deployment beyond a developer machine. It also contradicted NFR-100 (OIDC SSO), NFR-101 (RBAC and SoD), NFR-125 (rate limits) and NFR-126 (OAuth 2.0).

## Decision

1. **Every service is an OAuth2 resource server.** This is implemented once, in `astra-common` (`AstraSecurityAutoConfiguration`).
   - API requests need an RS256 bearer token from the configured identity provider (`astra.security.jwt.*`).
   - The token must carry the expected issuer and the audience `astrawms-api`, an expiry, and a valid signature (keys from the IdP's JWKS).
   - Health and info probes are the only anonymous endpoints; the rest of `/actuator` needs `SOLUTION_ADMIN`.
   - The services are stateless: no sessions or cookies, and therefore no CSRF surface.
   - Errors are RFC 9457 problems:

     | Status | Code |
     |---|---|
     | 401 | `UNAUTHENTICATED` / `TOKEN_INVALID` (with a `WWW-Authenticate: Bearer` challenge) |
     | 403 | `FORBIDDEN` / `TENANT_MISMATCH` / `TENANT_CLAIM_MISSING` |

2. **The tenant and the user come from the token** (`TenantFilter`, which runs after token validation).
   - **User and ERP-integration tokens:** the tenant is the `tenant_id` claim. An `X-Tenant-Id` header is allowed only if it repeats the same tenant. `X-User-Id` is ignored, so the audit trail records the signed-in user.
   - **Service-account tokens** (role `WMS_SERVICE`, issued through client credentials): no tenant claim. The calling service names the tenant and acting user in `X-Tenant-Id` / `X-User-Id`. This is the trusted-subsystem pattern; only AstraWMS service accounts get that trust.
   - Header mode is removed entirely, so there is no insecure mode that could be switched on in production.

3. **Role checks per endpoint (§G.5.2).**
   - Every endpoint that changes data declares its roles with `@PreAuthorize`. Reads need only an authenticated user of the tenant.
   - `SecuredEndpointsVerifier` makes a service refuse to start if a write endpoint has no rule, so new endpoints are denied by default.
   - `EarlyRoleCheckInterceptor` evaluates the role rule before the request body is read. An unauthorised caller gets 403, not validation details.
   - Roles: `RECEIVER`, `PICKER`, `INV_ANALYST`, `INV_MANAGER`, `SUPERVISOR`, `QA_MANAGER`, `SOLUTION_ADMIN`, and the technical roles `ERP_INTEGRATION` and `WMS_SERVICE`.

   | Area | Roles |
   |---|---|
   | Master data changes | `SOLUTION_ADMIN`; item master also `ERP_INTEGRATION` |
   | SAP IDoc port | `ERP_INTEGRATION` |
   | SAP site map | `SOLUTION_ADMIN` |
   | Simulated SAP (`/mock-sap`) | `SOLUTION_ADMIN`; the controller exists only with `astra.sap.gateway=mock` |
   | RF receiving, receipt close | `RECEIVER`, `SUPERVISOR` |
   | Receipt repost | `SUPERVISOR` |
   | Task next / confirm / exception | `RECEIVER`, `PICKER`, `SUPERVISOR` |
   | Pick confirmation | `PICKER`, `SUPERVISOR` |
   | Task replan | `SUPERVISOR` |
   | Ship, shipment repost | `SUPERVISOR` |
   | Inventory receipts | `RECEIVER`, `SUPERVISOR`, `WMS_SERVICE` |
   | Inventory moves | `RECEIVER`, `PICKER`, `INV_ANALYST`, `SUPERVISOR`, `WMS_SERVICE` |
   | Adjustments | `INV_ANALYST`, `INV_MANAGER`, `SUPERVISOR`, `WMS_SERVICE` |
   | Status changes | the adjustment roles plus `QA_MANAGER`; QI → AVAILABLE only by `QA_MANAGER` |
   | Allocate, release, issue | `SUPERVISOR`, `WMS_SERVICE` |
   | Allocation pick | `PICKER`, `SUPERVISOR`, `WMS_SERVICE` |

4. **Approvals are proven, not typed** (NFR-101 SoD).
   - The approver signs in, and the client sends the approver's token in `X-Approval-Token` (`ApprovalVerifier`).
   - The token must be valid, for the same tenant, issued within the last 5 minutes (`astra.security.approval-max-age`), and carry an approver role (`INV_MANAGER` or `SUPERVISOR`). Self-approval is still rejected.
   - A plain `approvedBy` value is accepted only from service accounts, for example a future approval workflow acting for a user.

5. **Service-to-service calls** (`ServiceCallInterceptor` on every inter-service `RestClient`):
   - Inside a user request, the **user's token is relayed**. The downstream service applies that user's roles and records that user (e.g. RF receiving → inventory receipt).
   - Outside a user request, such as a Kafka consumer allocating an order, the service uses **its own client-credentials token** (`ServiceTokenProvider`, cached until a minute before expiry) and names the tenant and acting user in the headers.

6. **API gateway** (`deploy/gateway`, nginx): the single published entry point.
   - Routes by path to the services. Service ports are no longer published.
   - Exposes only `/health/<service>` from actuator.
   - Limits request size, applies per-client rate limits (429 with `Retry-After`, NFR-125), and strips `X-User-Id`.
   - The gateway does **not** replace token validation: every service validates every token itself (defence in depth). TLS terminates at the ingress / load balancer of the target platform (NFR-102).

7. **Identity provider.**
   - Locally: Keycloak with the `astrawms` realm (`deploy/keycloak`): the roles above, a `tenant_id` user attribute (editable by administrators only, so users cannot move themselves to another tenant), audience mappers, service accounts for inbound, task and outbound, and a provisioning client.
   - In customer environments: the customer's IdP (Entra ID, Okta, Ping) federated through the same realm, or configured directly. Claim names are configurable (`astra.security.claims.*`); app roles in a top-level `roles` claim are supported as well as Keycloak realm roles.

## Consequences

- A service can be exposed to a network: it accepts only tokens of the configured issuer and audience, and a caller cannot choose another tenant or user. RLS (ADR-0003) remains the second line of defence.
- **Tests run the production rules.** `astra-test-support` provides a test token issuer (`TestTokens`) whose public key replaces the JWKS in tests. Integration tests sign in as users with roles. Each service has tests for 401, 403 and tenant spoofing; inventory also covers forged and expired tokens, foreign issuers, wrong audiences, service accounts and approvals.
- **The smoke tests run against the real stack.** They provision a fresh tenant's users in Keycloak, sign in, call only through the gateway, and check that the wrong role gets 403.
- One user token is bound to one tenant. Staff who serve several tenants of a 3PL get a token per tenant (tenant switch = token exchange). This is not built yet.
- Not yet covered:
  - **Kafka:** messages carry the tenant in the envelope, and producers are trusted. Production brokers need mTLS/SASL with per-service ACLs (topic write rights only for the owning service).
  - **Attribute scopes:** site, owner, zone and value thresholds (§G.5.1) are not yet enforced. The current check is role-level.
  - **Approval threshold limits.**
  - **Badge + PIN RF sign-in** (NFR-100) needs a device-session flow at the IdP.
  - **SCIM provisioning and SIEM export** of security events (NFR-109).
- All passwords and client secrets in `deploy/` are for the local stack only. Real environments inject them from a secret store.
