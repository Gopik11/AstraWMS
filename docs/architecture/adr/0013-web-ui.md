# ADR-0013: Web UI as a Single-Page App Served by the Gateway

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

Up to release 0.5, AstraWMS had APIs only. Floor staff need RF screens (§G.3), supervisors and inventory staff need
workspaces (§G.2), and administrators need master-data screens (UX-015). The security model of ADR-0010/0012
(OIDC, roles, scope claims) must hold in the browser too.

## Decision

1. **One web app** (`frontend/`): React + TypeScript built with Vite. It has role-based navigation and pages for:
   - RF work: putaway, pick and return, one task at a time, scanner-friendly, large inputs;
   - overview; receipts (receive by line or SSCC, close, repost); outbound orders (ship, repost); waves (preview, create, release); tasks;
   - stock inquiry (balances, LPN, ledger) and adjustments/status changes;
   - master data, SAP plant mapping and release mode;
   - an ERP simulator for test environments (sends DELVRY07 IDocs, shows simulated SAP postings).

   The layout is responsive: the same app runs on handhelds and desktops.
2. **Sign-in** uses the authorization code flow with PKCE (oidc-client-ts) against the public client `astra-web`. Tokens live in session storage (per tab) and are refreshed silently. The UI reads roles and scopes from the token only for display and navigation; every service still enforces them (ADR-0010/0012).
3. **Approvals** (segregation of duties) open a second sign-in in a popup with `prompt=login`. The approver's token is kept in memory only and sent as `X-Approval-Token`.
4. **Served by the gateway image** (`deploy/gateway/Dockerfile`): the UI build is baked into the nginx gateway, so UI and API share one origin and no CORS is needed. `/config.json` is mounted per environment (OIDC authority, client ID). Unknown `/api/` paths still return problem JSON. Hashed assets are cached immutably.

## Consequences

- The whole warehouse cycle can be run from a browser. Verified locally, end to end in the UI:
  1. master data;
  2. SAP inbound delivery, receive, close;
  3. RF putaway;
  4. SAP outbound delivery, RF pick, ship;
  5. goods issue confirmed in simulated SAP;
  6. the ledger shows each step with the signed-in user.
- **The browser requires a secure context (HTTPS or localhost) for PKCE.** The VPS test environment, which runs plain HTTP by IP address, needs HTTPS before the UI can sign in there. Its APIs work as before.
- Not built yet:
  - the control tower dashboards with KPIs (§G.6) and the reporting module (§H);
  - offline RF operation;
  - voice and wearables;
  - browser end-to-end tests (only unit tests in CI).
