# Attendance Device Endpoints

Java-only routes added by the attendance-device slice (D-156). They are
**not** part of the PHP parity inventory (`existing-endpoint-inventory.md`,
`LegacyPhpRouteInventoryTest`'s 198 `/apis/**` routes) and change no existing
client contract (D-111). Design and rationale:
`../superpowers/specs/2026-09-02-attendance-device-ingestion-design.md`.

## Device-facing: `/iclock/**` (ZKTeco ADMS / PUSH SDK)

Plain text in both directions. Exists only when
`app.devices.ingest.enabled=true`; a deployment without the flag maps none
of these routes (asserted by `LegacyPhpRouteInventoryTest`). No bearer
token: the device is identified by the `SN` query parameter resolved against
the registry, and an unknown or deactivated serial is refused where it
matters. `SN` must match `[A-Za-z0-9][A-Za-z0-9._:@-]{0,63}` — anything else
is refused before it can reach a query or create a sighting row. The POST
handlers read their parameters from the query string and their body from a
filter that runs ahead of the security chain, so a device may send any content
type without its batch being swallowed by form parsing.

| Method and path | Purpose | Answer |
|---|---|---|
| `GET /iclock/cdata?SN=&options=all&pushver=&DeviceType=` | Handshake: device asks for its operating options | `200`, `GET OPTION FROM: <SN>` plus `key=value` lines; `TransFlag` allows `AttLog` and `OpLog` only. An unclaimed **or deactivated** serial gets the same shape with zero stamp and zone, and an unclaimed one is recorded as a sighting |
| `POST /iclock/cdata?SN=&table=ATTLOG&Stamp=` | Punch upload, one tab-separated line per punch | `200 OK: n` (n = lines accepted: stored, duplicate, or refused by the database); `403 ERROR: device is not registered` for an unclaimed or deactivated serial; `413` over the body cap. `Stamp` is recorded only when it is digits and the delivery carried at least one punch |
| `POST /iclock/cdata?SN=&table=OPERLOG&Stamp=` | Operation log; may carry `USER` and template lines | `200 OK: n`; `OPLOG` lines stored, template lines discarded, `USER` lines counted only |
| `POST /iclock/cdata?SN=&table=options` | Device describing itself | `200 OK`; `~DeviceName`, `FirmVer`, `PushVersion` recorded |
| `POST /iclock/cdata?SN=&table=<anything else>` | Templates, photos, unknown tables | `200 OK`, discarded |
| `GET /iclock/getrequest?SN=` | Command poll — the liveness signal | `200 OK` (no command queue in this slice) |
| `POST /iclock/devicecmd?SN=` | Command result `ID=&Return=&CMD=` | `200 OK`, logged |
| any, without `SN` | | `400 ERROR: missing SN` |
| any, with an unusable `SN` | | `400 ERROR: invalid SN`, before any database work |

Errors here are never the platform `{code,message}` body nor the PHP
envelope: the client is a terminal.

## Tenant-facing: `/api/v1/devices/**`

JSON, snake_case, no envelope. Authenticated with the legacy PHP JWT through
the legacy security chain; then `requireAuth(company_admin, hr)` and
`requireCompanyActive`. The company is the token's validated tenant and a
predicate on every query. Errors render the platform `{code, message}` body
(`ApiExceptionHandler`); keys are under `devices.*` in both message catalogs.

| Method and path | Purpose | Notes |
|---|---|---|
| `GET /api/v1/devices` | List the company's devices | `{ "devices": [ {id, serial_number, name, branch_id, vendor, model, firmware, push_version, device_time_zone, is_active, last_seen_at, last_handshake_at, last_seen_ip, created_at} ] }` |
| `POST /api/v1/devices` | Claim a serial for a branch | body `{serial_number, branch_id, name, device_time_zone?}`; `201` with the device; `404 devices.branch_not_found` for a missing or foreign branch; `409 devices.serial_already_claimed`; `400 devices.serial_number_required`, `devices.serial_number_invalid`, `devices.name_required`, `devices.time_zone_invalid`, `devices.time_zone_not_whole_hour`. Zone defaults to the platform runtime offset (D-099) and must be a whole-hour offset — the handshake cannot express a half-hour one. Claiming is first-come and global; see R-041 |
| `PATCH /api/v1/devices/{id}` | Rename, move branch, change zone, activate or deactivate | any of `name`, `branch_id`, `device_time_zone`, `is_active`; `404 devices.not_found` (also for another tenant's id); `400 devices.nothing_to_update` |
| `GET /api/v1/devices/unclaimed?serial_number=` | Has this exact serial contacted the receiver? | `{serial_number, seen, claimed}` and nothing more — no timestamps, address, push version or device type. `claimed` is true only for the caller's own device; a serial owned by another company answers exactly as one never seen. No list form exists, by design. `400 devices.serial_number_query_required`, `devices.serial_number_invalid` |
| `GET /api/v1/devices/identities` | Device PIN bindings for the company | `{ "identities": [ {employee_id, pin, card_no, source, updated_at, employee_name} ] }`. Where no binding exists a PIN falls back to the `employee_code` of an **active** employee |
| `PUT /api/v1/devices/identities` | Bind (or rebind) an employee's PIN | body `{employee_id, pin, card_no?}`; `404 devices.employee_not_found` (also for another tenant's employee); `400 devices.pin_required`, `devices.pin_invalid`; `409 devices.pin_already_bound`, `devices.employee_already_bound` |
| `GET /api/v1/devices/punches?device_id=&state=&limit=` | Raw punches, newest first, for shadow-mode visibility | `limit` defaults to 100, capped at 500; `state` is `RECEIVED`, `UNMATCHED`, `PAIRED` or `IGNORED` |

Finer HR permission flags (`hr_permissions.can_attendance`) are not consulted:
the slice follows the legacy norm for new tenant-admin surface and defers
per-capability authorization to ADR-0010's module.
