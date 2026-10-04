# AstraWMS RF — Android app with RFID

The AstraWMS RF app is the native app for warehouse handhelds. It does everything the web RF screens do:

- the operator's next task, worked by scanning, for every task type: putaway, pick, receive (ASN / RMA), count, move,
  replenish and return;
- downloading tasks for offline work, and an ordered offline queue (ADR-0023).

It also reads **RFID** tags (ADR-0027). The decision record is
[ADR-0027](../../docs/architecture/adr/0027-android-rf-app-and-rfid.md).

## Layout

| Build | What it holds |
| --- | --- |
| [`core`](core) | Plain Kotlin/JVM, no Android SDK needed: GS1 EPC codec (`rfid/Epc`), tag buffer, GS1 barcode parser, wedge payload classifier, API client, offline command queue, RFID helpers. Unit tests run on any JDK 17+. |
| [`app`](app) | The Android app: Jetpack Compose UI, AppAuth sign-in, reader drivers (`rfid/`). It uses `core` as an included build. |

## Build

```bash
./gradlew -p core test           # core unit tests (JVM only)
./gradlew :app:assembleDebug     # the APK (needs the Android SDK: ANDROID_HOME or local.properties sdk.dir)
```

CI runs both (the `android` job) and keeps the debug APK as an artifact.

## Set up a device

1. **Server.**
   - Open Settings and enter the gateway URL, e.g. `https://astrawms.cloud`. On the emulator against a local stack,
     use `http://10.0.2.2:8080`; debug builds allow plain HTTP.
   - The app takes the identity provider from the gateway's `/config.json`. If that address is not reachable from the
     device (`localhost:8180` locally), set the issuer in Settings: `http://10.0.2.2:8180/realms/astrawms` on the
     emulator.
2. **Sign in.** Sign-in opens the AstraWMS sign-in page in a browser tab. The Keycloak client is `astra-mobile`, a
   public client with PKCE and redirect `com.astrawms.mobile:/oauth2redirect`. Passkeys work there.
3. **Site.** Pick the site, from the sites in your `wms_sites` claim.
4. **Reader.** Choose one in Settings:

| Reader | Use it for | Notes |
| --- | --- | --- |
| Zebra DataWedge | MC3300R / MC3390R, RFD40 / RFD90 sleds, Zebra barcode handhelds | The app creates the DataWedge profile **AstraWMS**: broadcast intent `com.astrawms.mobile.SCAN`, keystroke output off, barcode and RFID input on the hardware trigger. The Read button sends the soft RFID trigger. |
| Other scan-to-intent wedge | Honeywell, Chainway, Urovo, … | Set the broadcast action and the extra that holds the data, as your wedge sends them. Several tags per broadcast are fine: one per line, or separated by commas, semicolons or tabs. |
| Bluetooth UHF reader (TSL ASCII) | TSL 1128 / 2128 and compatible | Pair the reader in Android settings and enter its address. Allow "Nearby devices". The trigger reads; so does Read in the app. RSSI drives "find". |
| Phone NFC | HF tags on phones | NDEF records with an EPC, a GS1 Digital Link or label text are read; otherwise the tag UID. |
| Simulated reader | Training, the emulator | Read returns the EPCs listed in Settings. |

A vendor RFID SDK (for example Zebra RFID API3) plugs in by implementing `rfid/RfidReader`.

## What RFID does on each screen

- **Putaway:** the pallet's SSCC-96 tag is the LPN scan; a commissioned bin tag fills the location.
- **Pick:** the unit tags you read verify the item. The server checks the GTIN in the EPC. They also give the quantity
  (a case tag counts its case size) and the serials of serial-tracked items. Reading more than requested is flagged.
- **Receive:** tags propose the item, preferring items on the delivery, the quantity in base units, the serials of
  tracked units, and the pallet SSCC as the LPN. You check them, then confirm. Tolerances, lots and serials are
  checked by the server as usual.
- **Count:** read the whole location, then **Use the tags**. The server proposes the count lines; an LPN tag counts
  its contents. Add stock that has no tag. The count stays blind: the app never shows the system quantity.
- **Check a location** (supervisors, analysts): pallets found, missing and unexpected; read vs expected per item, lot
  and LPN; serials not read. Can record where commissioned tags were seen.
- **Identify / find:** what each tag is (unit, LPN, bin, asset). Tap a tag to find it: the proximity bar follows the
  RSSI on readers that report it.
- **Commission:** bind the strongest tag in the field to an LPN, a unit or a bin. Or let the WMS encode a GS1 EPC
  for an RFID printer or encoder: SSCC-96 for an SSCC LPN, SGTIN-96 for a numeric serial. This uses the GS1 company
  prefix length from Settings. Retire tags that are reused.

RFID only proposes. Every quantity goes through the same RF commands as a barcode scan, with the operator's
confirmation.

## Offline

RF commands made without network wait on the device in order. They are sent with the same idempotency key when the
network is back, and every 30 s while they wait. A command the server refuses then needs attention: see the Offline
queue screen, and the task is flagged SYNC_CONFLICT for a supervisor. **Download my work** takes up to 10 tasks onto
the device.

Without network:
- pallet SSCC tags still fill the LPN, decoded on the device;
- unit tags still give a pick quantity; the server checks the item when the command is sent.
