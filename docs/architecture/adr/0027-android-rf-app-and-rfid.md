# ADR-0027: Android RF App and RFID

- **Status:** Accepted
- **Date:** 2026-10-04

## Context

RF work runs in the browser (ADR-0013, ADR-0023): handhelds open the web RF screens and scan with a keyboard-wedge
barcode scanner. Two things were asked for:

- A native Android app for the handhelds.
- RFID reading, with the backend changes RFID needs.

The constraints:

- **Hardware varies.** Zebra handhelds read UHF tags through DataWedge (MC3300R / MC3390R, RFD40 / RFD90 sleds).
  Other sites use Bluetooth readers. Phones only have NFC.
- **Tags vary.** Most UHF tags carry a GS1 EPC: an SGTIN-96 (GTIN + serial) on units and cases, an SSCC-96 on
  pallets. Some carry a vendor EPC or only a TID. Bins may have tags too.
- **RFID never moves stock by itself.** Receipts, picks, counts and moves still go through the existing commands, with
  their rules: tolerances, check digits, blind counts, serials and approvals.
- **Offline work stays.** The app works offline the way the web RF screens do (ADR-0023).

## Decision

### A. EPCs in the platform library

`astra-common` gets `com.astrawms.common.rfid.Epc`, which implements the GS1 EPC Tag Data Standard.

- **Decoding.** It decodes SGTIN-96, SSCC-96, SGLN-96, GRAI-96, GIAI-96 and GID-96. The input can be:
  - the EPC bank in hex, as readers report it: with or without `0x`, with spaces or dashes, or longer with zero
    padding after the EPC;
  - a pure identity URI (`urn:epc:id:sgtin:…`);
  - a tag URI (`urn:epc:tag:sgtin-96:…`).
- **Encoding.** It encodes SGTIN-96 and SSCC-96. A GTIN or SSCC does not say how long its GS1 company prefix is, so
  the caller gives the length.
- **Tests.** The tests use the reference encodings of the standard.

`Gs1.parse` falls back to `Epc`:

| Tag | Reads as |
| --- | --- |
| SGTIN | AI 01 + AI 21 (GTIN and serial) |
| SSCC | AI 00 (SSCC) |

GS1 element strings are tried first, so no barcode reads differently than before. As a result, every RF scan that
accepts a GS1 label also accepts an RFID read, with no further change:

- the item on RF receive;
- the item check on RF pick (ADR-0020);
- the item on material issue (ADR-0022).

A tag is identified by its pure identity URI, not by its bits. The same tag read as hex (with its filter value) and as
a URI (without one) counts once.

### B. RFID in RF commands (task-service)

- **Putaway.** The LPN scan may be the LPN, its GS1-128 label `(00)…`, or its SSCC-96 tag.
- **Receive.** A tag in the item field identifies the item. Its serial is not taken as the unit's serial: every SGTIN
  carries a serial, including on items that are not serial-tracked. The client sends the serials of serial-tracked
  units explicitly. A GS1-128 label with AI 21 still fills the serial, as before.

### C. RFID API (inventory-service, `/api/v1/sites/{site}/inventory/rfid`)

| Endpoint | Roles | What it does |
| --- | --- | --- |
| `POST /resolve` `{reads}` | RF roles, QA_MANAGER, AUTOMATION | Turns up to 2000 reads into results, one per tag. **ITEM:** owner, item, the tag's UoM, base quantity (a case tag counts its case size) with the base UoM, serial-tracked or not, and the serial's stock record. **LPN:** the LPN with that SSCC and where it is. **LOCATION** and **ASSET** tags. Problems: NOT_AN_EPC, UNREGISTERED, RETIRED, GTIN_UNKNOWN, GTIN_AMBIGUOUS, LPN_UNKNOWN, SERIAL_NOT_IN_STOCK, OWNER_DENIED. |
| `POST /locations/{loc}/reconcile` `{reads}` | same | Compares the reads at one location with its stock. **LPNs:** FOUND, MISSING, or UNEXPECTED (with where the system has them). **Lines:** read and expected quantity, and the variance, per owner, item, lot and LPN. Also: serials not read, units the system has elsewhere, unrecognised tags, and the count lines an RF count can submit. |
| `POST /sightings` `{reads, locationId}` | same | Records the last seen time, location and user of commissioned tags. |
| `POST /tags` | RECEIVER, INV_ANALYST, INV_MANAGER, SUPERVISOR | Commissions a tag: binds an EPC to exactly one unit (owner, item, optional serial), LPN or bin. Without an EPC, the WMS encodes one: an SSCC-96 for an LPN that is an SSCC, or an SGTIN-96 for a numeric serial (from the item's GTIN). |
| `POST /tags/{epc}/retire` | INV_ANALYST, INV_MANAGER, SUPERVISOR | Retires a tag; it can then be commissioned again. |
| `GET /tags/{epc}`, `GET /tags?lpnId=&itemNo=&serialNo=&locationId=` | any user of the tenant | Lists commissioned tags. |

**Resolve rules:**
- A commissioned tag wins over the GS1 key in its EPC. That is how vendor EPCs, TIDs and bin tags resolve.
- GS1 tags need no registration.

**Reconcile rules:**
- An LPN tag stands for the LPN's contents.
- A unit tag inside an LPN that was read is not counted twice.
- A unit at this location counts at its own stock key. A unit the system has elsewhere is counted loose here, and also
  reported.
- Untagged stock reads as 0. The UI says so.

**Other rules:**
- Commissioning is idempotent per EPC and binding. An active tag bound to something else is refused with
  INV_RFID_TAG_IN_USE. A GS1 EPC must agree with its binding (INV_RFID_EPC_MISMATCH):
  - an SSCC-96 tag must be that LPN;
  - an SGTIN-96 tag must be a unit of that item, with that serial.
- Resolve and reconcile change nothing. They are POSTs only because a read can carry thousands of EPCs.
- Results respect the owner scope (ADR-0012).

**Schema:** inventory V19 adds `rfid_tag`, with row-level security.

### D. The Android RF app (`mobile/android`)

- **Two Gradle builds.**
  - `core` is plain Kotlin/JVM and is built and tested without the Android SDK. It holds the device twins of `Epc`
    and `Gs1`, the tag buffer, the payload classifier, the API client (OkHttp + kotlinx.serialization), the offline
    command queue and the RFID helpers.
  - `app` is the Android app: Jetpack Compose, minSdk 26, targetSdk 35. It consumes `core` as an included build.
- **Sign-in.**
  - OIDC authorization code with PKCE in a browser tab (AppAuth). The new Keycloak public client is `astra-mobile`,
    with redirect `com.astrawms.mobile:/oauth2redirect`.
  - The issuer comes from the gateway's `/config.json`, the same source as the web UI.
  - The AuthState and its refresh token are kept in encrypted preferences.
  - Passkeys work on the sign-in page (ADR-0023).
  - The VPS deploy creates the client on realms that were imported before the client existed.
- **Readers.** All readers sit behind one `RfidReader` interface:

  | Reader | How it reads |
  | --- | --- |
  | DataWedge | The app creates a profile "AstraWMS": broadcast intent output, RFID and barcode input with the hardware trigger. It also uses the soft RFID trigger. |
  | Other scan-to-intent wedge | Action and extras are configurable. |
  | TSL ASCII 2.0 over Bluetooth SPP | `.iv` rounds; `EP:` lines carry tags, `RI:` lines RSSI, `BC:` lines barcodes. |
  | Phone NFC | NDEF records with an EPC, a GS1 Digital Link or label text. Otherwise the UID. |
  | Simulated reader | For training and the emulator. |

  A vendor SDK (for example Zebra RFID API3) plugs in through the same interface.
- **Screens.** RF work covers every task type, with RFID where it helps:

  | Task | What RFID does |
  | --- | --- |
  | Putaway | The pallet tag is the LPN scan; a bin tag gives the location. |
  | Pick | Unit tags verify the item (the server checks the GTIN) and give the quantity and serials. Reading more than requested is flagged. |
  | Receive | Tags propose the item (preferring items on the document), the quantity in base units, the serials of tracked units, and the pallet SSCC as the LPN. |
  | Count | A whole-location read becomes the proposed count lines. The count stays blind: the app never shows the system quantity. The counter adds untagged stock. |

  The other screens:
  - **Check a location:** the full reconciliation, for supervisors and analysts.
  - **Identify / find:** what each tag is, and proximity from RSSI on readers that report it.
  - **Commission:** bind the strongest tag, or have the WMS encode a GS1 EPC for a printer or encoder; retire.
  - **Offline queue.**
  - **Settings.**
- **Offline.** RF commands go through the same queue rules as the web (ADR-0023): ordered, same idempotency key,
  "needs attention" on refusal, and SYNC_CONFLICT on the task (ADR-0024). Tasks can be downloaded to the device.
  Without network:
  - SSCC tags still fill the LPN, decoded on the device;
  - unit tags still give a pick quantity; the server checks the item when the command is sent.

## Consequences

- One migration: inventory V19 (`rfid_tag`). The Keycloak realm gains the `astra-mobile` client.
- RFID adds no new stock paths. Every quantity proposed from tags is confirmed by the operator and checked by the
  existing commands.
- Only 96-bit GS1 schemes are decoded. SGTIN-198 and other long encodings resolve only once commissioned.
- Writing EPC memory from the app is not implemented. The WMS encodes the EPC; an RFID printer (ZPL `^RFW`) or an
  encoder writes it. A reader-SDK implementation can add writing behind `RfidReader`.
- **CI.** CI gains an `android` job: core tests, then `assembleDebug`, with the debug APK as an artifact.
- **Unverified in this change.** The Android SDK host (`dl.google.com`) was not reachable where this change was made.
  - The Android module was type-checked against Compose Multiplatform 1.7, the Android 15 framework classes and
    AppAuth, but has not been built with the Android Gradle Plugin yet. The CI job is its first real build.
  - The DataWedge profile keys and the TSL command set follow the vendors' documented interfaces. They have not yet
    been tried on hardware.
