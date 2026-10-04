# Task API for devices: AMR, voice, pick-to-light, scale and printer

Every piece of floor work in AstraWMS is a **task**. RF handhelds, voice headsets, pick-to-light controllers,
autonomous mobile robots (AMR) and automated zones all use the same task API. A device integration is a client of
this API, not a new workflow, so stock, labor, the control tower and SAP postings stay the same whichever device does
the work.

The base path is `/api/v1/sites/{site}/tasks`. Authenticate with an OIDC bearer token: a user for people-driven
devices, a client-credentials service account for machines. The user's roles, zones, owners, skills and equipment
decide which tasks they get (ADR-0019, ADR-0021).

## People with a device (voice, pick-to-light, RF)

| Step | Call | Notes |
| --- | --- | --- |
| Get work | `POST /next` | The best released task for this operator; 204 when there is none. Resumes a task already assigned. |
| Get a trip | `POST /pick-group?mode=CLUSTER\|BATCH&size=N` | Several picks for one trip (ADR-0025); confirm each pick on its own. |
| Confirm a putaway | `POST /{id}/confirm` `{lpnId, locationId, checkDigit, overrideReason?}` | The check digit proves the operator is at the location. |
| Confirm a pick | `POST /{id}/pick` `{checkDigit, item, qty, serials?, shortReason?, shortAction?}` | `item` is the item number, GTIN or GS1 scan. A lower qty is a short pick (reason required). |
| Confirm a replenishment / move | `POST /{id}/replenish` · `POST /{id}/move` `{checkDigit}` | |
| Confirm a count | `POST /{id}/count` `{checkDigit, lines:[{ownerId,itemNo,lotNo,lpnId,qty}]}` | Blind count: the expected quantity is never sent. |
| Receive | `POST /{id}/receive` `{scanId, docNo, itemNo, qty, uom, lpnId, locationId, checkDigit, damageReason?, photo?}` | `scanId` makes a retried scan harmless. |
| Report a problem | `POST /{id}/exception` `{reason, detail}` | |
| Hand back | `POST /{id}/release` | |

- **Voice.** The voice server turns prompts and spoken check digits into these calls. Say the check digit for
  `checkDigit` and the quantity for `qty`.
- **Pick-to-light.** The controller lights the bin of `fromLocation` from `/next` or `/pick-group`. The button press
  is the pick confirmation, sending the light's check digit.

## Machines: automated zones and AMR (ADR-0021)

A zone is marked automated under `PUT /automation/zones/{zoneId}` `{deviceType, enabled}`. Its tasks are not offered to people. The device's
service account works them:

| Step | Call |
| --- | --- |
| Claim a task | `POST /automation/claim` `{deviceId, zoneId, taskTypes}` (returns the next task of that zone, or 204) |
| Confirm | `POST /automation/tasks/{id}/confirm` `{deviceId, qty, shortReason?, shortAction?}` |
| Exception | `POST /automation/tasks/{id}/exception` `{deviceId, reason: ITEM_NOT_FOUND\|LOCATION_BLOCKED\|DEVICE_FAULT\|..., detail}` (the task goes back to people) |

An AMR fleet manager is one such client. It claims tasks for its zone, sends the robots, and confirms when a robot
reports the work done.

## Scales and printers

- **Scale.** Send the weight with the carton close: `POST /api/v1/sites/{site}/outbound/cartons/{sscc}/close`
  `{weightKg}`. A scale integration is a small client that reads the scale and calls this.
- **Label printers** are network printers (ZPL over TCP 9100) registered per site under `/labels/printers`. Every
  label printed is recorded. It becomes active after a verification scan (`/labels/printed/verify`), and can be
  reprinted or voided (ADR-0024, ADR-0025).

## Events out

To follow what devices do without polling, subscribe to webhooks (ADR-0025, Integration page). The events are
`transfer.shipped`, `transfer.received`, `issue.posted`, `issue.returned` and `count.variance`. Each is signed with
`X-AstraWMS-Signature: sha256=HMAC(secret, X-AstraWMS-Timestamp + "." + body)`; verify the signature and reject
timestamps older than five minutes.

Errors are RFC 9457 problem details with a stable `code`, for example `TSK_CHECK_DIGIT_MISMATCH`,
`TSK_LABEL_NOT_VERIFIED` or `TSK_NOT_ASSIGNED`. Devices act on the code, not on the message text.
