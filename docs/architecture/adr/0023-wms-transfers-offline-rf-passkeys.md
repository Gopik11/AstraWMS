# ADR-0023: Transfers Between Sites Started in the WMS, Offline RF Work, and Passkey Sign-in

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

ADR-0022 left three items of the barcode-WMS tender (one main warehouse and 25 satellite stores) as stated positions:

1. Transfers between sites (main warehouse → store, store → store) are driven by SAP stock transport orders. The tender
   expects the stores to be able to move stock among themselves from the WMS.
2. Stores have unreliable network. The supplier "shall clearly state whether the solution supports offline operation".
3. A login is required per user, and facial recognition is "highly recommended".

## Decision

### 1. Transfers started in the WMS

- **Creation.** `POST /sites/{site}/outbound/transfers` (supervisor, inventory manager) creates an outbound order of
  type TRANSFER with `transfer_to_site`. Its number is `TR-<site>-<seq>` and its source system is ASTRAWMS. From
  then on it is an ordinary order: release (waveless or wave), allocation, RF picks, packing, loading and shipping.
- **Shipment.** When the transfer ships:
  - The receiving site gets a `ReceiptExpectation` with type WMS_TRANSFER and `supplyingSiteId` = the issuing site.
    It lists what actually left, one line per lot. The receiving site receives it on RF like any delivery; it is the
    in-transit view.
  - The `ShipmentConfirmation` carries `transferToSiteId` (4.1, additive). The adapter posts it as SAP 303 at the
    issuing plant with MOVE_PLANT = the receiving plant (stock in transit), not as an outbound delivery.
- **Receipt.** The `ReceiptConfirmation` of a WMS_TRANSFER receipt carries `transferFromSiteId` (3.1, additive). The
  adapter posts it as SAP 305 at the receiving plant.
- **Inquiry.** `GET /sites/{site}/outbound/transfers?direction=OUT|IN` lists transfers leaving a site, or coming to
  it. The Transfers page shows both and creates transfers.
- **SAP-driven transfers** (NL/NLCC deliveries) keep working as before. The two kinds are told apart by the
  confirmation fields.

### 2. Offline RF work

- **App shell.** A service worker (`/sw.js`, production builds) caches the app shell: content-hashed assets cache
  first, pages and `/config.json` network first with the cached copy offline. API calls are never cached. The RF
  screens (task work and material issue) are in the main bundle, so they are available offline.
- **Download my work.** `POST /tasks/claim-batch?count=N` (N ≤ 25) assigns to the operator, chosen exactly as "next
  task" would, up to N tasks in total, counting the ones already assigned to them. The device keeps them and works
  through them without network. Material-issue documents opened online are kept the same way.
- **Command queue.** RF commands (receive scans and close, putaway, pick, count, move, replenish, return, hand back,
  material issue and return) go through `rfPost`:
  - When a command fails for lack of network, it is kept in an ordered queue on the device (localStorage).
  - While commands wait, new ones queue behind them, so the server sees them in the order they were done.
  - When the device is back online, and every 30 s while commands wait, they are sent again with the same body and
    the same idempotency key. A command that had already reached the server is answered, not repeated: confirming a
    completed task returns it; receive scans carry `scanId`; issue scans carry `Idempotency-Key`.
  - Sending stops at a network failure, a server error or an expired sign-in (401), and resumes later.
- **Conflicts.** A command the server refuses when it is finally sent is kept as "needs attention", with the server's
  reason (a 4xx answer, for example a task reassigned meanwhile or stock moved). The operator retries or discards it
  from the status bar on the RF screens, which also shows online/offline, waiting scans and tasks on the device.
- **Sign-in.** An access token that expires while offline does not redirect to the sign-in page, which could not
  load. It is renewed silently when the network is back. A page reloaded offline with an expired token still opens,
  and its commands wait.
- **What the device does not do.** It does not check check digits or stock on its own: the server validates every
  command when it arrives, and refusals show as "needs attention". New tasks cannot be fetched offline beyond the
  downloaded batch.

### 3. Passkey sign-in

- **Realm settings.** The realm turns on the Keycloak passkey sign-in (feature PASSKEYS, enabled by default in 26.4):
  WebAuthn passwordless with user verification required and resident keys, RP name AstraWMS. The sign-in page offers
  "Sign in with Passkey" next to the password, using the device's face, fingerprint or PIN unlock.
- **Registration.** "Set up passkey" in the app header runs the application-initiated action
  `webauthn-register-passwordless` and returns to the page.
- **Existing realms.** New realms get this from the realm export. `scripts/keycloak-passkeys.sh` turns it on for an
  existing realm, locally or over SSH; it reads the admin credentials inside the Keycloak container and prints none.
- **Not facial recognition by the WMS.** The face unlock is the device's own (Windows Hello, Android, iOS). Shared
  rugged scanners without biometrics keep passwords.

## Consequences

- **Tender answers.** Transfers between stores: supported from the WMS, as well as SAP-driven. Offline: supported for
  RF work on downloaded tasks, with sync and conflict review. Biometric sign-in: supported through passkeys.
- **Tasks on a device.** Downloaded tasks stay assigned to the operator until done or handed back. A lost device's
  tasks are reassigned by a supervisor.
- **Contracts.** `ShipmentConfirmation` 4.1 and `ReceiptConfirmation` 3.1 add optional fields. Older consumers
  ignore them.
