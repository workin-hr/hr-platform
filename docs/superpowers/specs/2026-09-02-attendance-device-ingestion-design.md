# Attendance Device Ingestion — ZKTeco ADMS Push — Design (2026-09-02)

## Purpose And Authority

Every company on the platform may run several branches, and each branch may
have its own fingerprint/face terminal. Today a punch on such a terminal
reaches the platform only when someone exports the device's log to Excel and
imports it through `attendance/import_excel.php`. This document designs the
path by which a punch on a branch device becomes an attendance record in the
platform directly, with the same `check_in`/`check_out` semantics the mobile
and QR paths already produce.

Authority is split exactly as ADR-0006 is:

- The **vendor-neutral core** — device registry, raw punch log, idempotent
  ingestion, the `DeviceAttendanceEvent` seam — is covered by ADR-0006
  **Part A** (`docs/bootstrap/decision-log.md` D-023, Accepted) and by
  `docs/devices/device-integration-architecture.md`. `backend/` is unlocked
  for implementation (D-028).
- The **ZKTeco connectivity pattern** is ADR-0006 **Part B**, which this
  document proposed and the repository owner **accepted on 2026-09-02**
  (D-158), with one recorded condition: the hardware checklist in §4.3 must
  pass on the customers' actual models before the adapter is declared
  verified. Building Slice A is authorised.

This document is planning output. Implementation follows the `AGENTS.md`
workflow as its own step, sliced as §11 describes. It closes the
documentation half of `workin-hr/hr-platform#12` (PMR-04); the hardware half
stays open until §4.3 is executed against a real terminal.

## 1. Evidence

### 1.1 Repository evidence

- `hr-legacy/apis/api/attendance/check_in.php` lines 33–42 and
  `check_out.php`: the self-service check-in contract, the two-hour
  minimum-gap rule, and the open-session check-out pairing.
- `backend/src/main/java/com/workin/legacy/attendance/session/LegacyAttendanceSessions.java`:
  the stale-session auto-close that writes a synthetic `check_out`.
- `backend/src/main/java/com/workin/legacy/attendance/spreadsheet/LegacyAttendanceImportReader.java`:
  the fingerprint-machine export the platform already ingests — a
  two-column `code + datetime` punch log whose code-column aliases include
  `fingerprint_no`, grouped into day records by `groupPunches()` with
  first-punch-in / last-punch-out inference. `hr-legacy/apis/helpers/attendance_excel_analyzer.php`
  line 8 names the source: "Column aliases for fingerprint-machine punch
  logs". **`employees.employee_code` is therefore already the de-facto
  device PIN in production practice.**
- `backend/src/test/resources/legacy/mysql_workin.schema.sql`:
  `attendance.method` is `enum('app','excel','qr')`; `employees` carries
  `employee_code varchar(64)`, `can_check_in_any_branch`,
  `is_mobile_attendance_enabled`; `branches` carries geofence fields and no
  device relation. Java writes the literals `'excel'`
  (`LegacyAttendanceImportStore.java:40`) and `'qr'`
  (`LegacyCheckInService.java:154`).
- `backend/src/test/resources/legacy/phase1_extensions.schema.sql`: the
  sanctioned home for Phase-1-owned tables that are not part of the vendored
  legacy contract (`legacy_refresh_tokens` is the precedent).
  `TenantFilterCoverageTest` exempts such tables structurally.
- `backend/src/main/java/com/workin/backend/config/LegacyPersistenceConfig.java`:
  no Flyway ownership of any MariaDB schema; a persistent MariaDB "needs its
  own, separately-approved provisioning mechanism first (ADR-0013 Open
  Questions)". That gate applies to every table this design adds.
- `SecurityConfig.legacySecurityFilterChain`: under `phase1-mysql` the only
  chain matches `/api/legacy/**` and `/apis/**`. `LegacyPhpRouteInventoryTest`
  inventories `/apis/` patterns only.
- `docs/legacy/business-rule-extraction.md`: the QR path already bypasses
  the two-hour rule, so a device path with its own policy is not the first
  method-specific exception.
- `AGENTS.md`: access to biometric data is prohibited for agents; the
  design must keep biometric templates off the platform by default.

### 1.2 External evidence (protocol)

Public documentation and independent implementations, read 2026-09-02.
None of this is hardware-verified; §4.3 is the verification step.

- ZKTeco's own [PUSH SDK page](https://www.zkteco.com/en/PUSHSDK) and the
  [Attendance PUSH Communication Protocol](https://www.scribd.com/document/604032067/Attendance-PUSH-Communication-Protocol-20200325)
  (vendor document, third-party hosted).
- Independent server implementations agreeing on the wire format:
  [s0x90/zkteco-adms (Go)](https://github.com/s0x90/zkteco-adms),
  [shadow046/zkteco-adms (Laravel)](https://github.com/shadow046/zkteco-adms),
  [syofyanzuhad/filament-zkteco-adms](https://github.com/syofyanzuhad/filament-zkteco-adms),
  and a [captured Postman collection](https://github.com/saifulcoder/adms-server-ZKTeco/blob/main/ADMS%20server%20ZKTeco.postman_collection.json)
  from a real X100-C device (handshake query string and ATTLOG body quoted in
  §5).
- Device-side configuration walk-throughs confirming the outbound-only
  model: [ClockIt ZKTeco ADMS setup](https://get.clockit.io/en/zkteco/zkteco-ua300)
  and the [Odoo ADMS attendance module](https://apps.odoo.com/apps/modules/17.0/zkteco_adms_attendance).
- The pull alternative and its limits: [pyzk](https://github.com/fananimi/pyzk),
  [pyzk issue #36 (live capture unreliable by model)](https://github.com/fananimi/pyzk/issues/36),
  the community [zk-protocol specification](https://github.com/adrobinoga/zk-protocol/blob/master/protocol.md).
- Security properties: Kaspersky's
  [analysis of a ZKTeco biometric terminal](https://securelist.com/biometric-terminal-vulnerabilities/112800/)
  (24 vulnerabilities, default `COMKey=0`, server impersonation of the ADMS
  endpoint, a root `SHELL` command handler).
- Other vendors, for the adapter roadmap:
  [Hikvision ISAPI listening mode](https://www.hikvisioneurope.com/eu/portal/portal/Technology%20Partner%20Program/03-How%20to/How%20to%20get%20real-time%20event%20in%20listening%20mode.pdf),
  [Anviz CrossChex webhooks](https://community.anviz.com/t/how-to-synchronize-the-time-attendance-data-to-the-third-party-systems/491),
  [Suprema BioStar 2](https://docs.supremainc.com/en/platform/biostar_x/tna).

## 2. The Connectivity Decision (ADR-0006 Part B, proposed)

There are three ways a punch can leave a terminal. The multi-branch
requirement is what decides between them.

| Pattern | Mechanism | Fit for many branches behind NAT |
|---|---|---|
| Pull over TCP 4370 (vendor SDK, `pyzk`, `zklib`) | The platform opens a connection to the device's IP | Needs a static IP and port-forward per branch, a VPN, or an on-site agent. The vendor SDK is a Windows COM component. Live capture is model-dependent (pyzk #36). Default `COMKey=0`, brute-forceable keyspace 0–999999 (Securelist). |
| Manual export / import | Operator exports the device log, HR imports Excel | Already ported; keeps working. Not real-time, error-prone, and the reason this design exists. |
| **ADMS / PUSH SDK ("Cloud Server Setting")** | The device dials out over HTTP to a hostname and port, uploads punches as they happen, polls for commands, buffers while offline | Works behind any NAT with no static IP, no VPN, no software at the branch. Documented on the vendor's own PUSH SDK page; the same mechanism ZKBio Time is built on; the captured handshake even reports `DeviceType=middle east`. |

**Decision (D-158, accepted 2026-09-02):** the ZKTeco adapter is the ADMS push receiver. The
`edge-gateway/` boundary is retained as a *fallback* for terminals that do not
expose the Cloud Server Setting, not as the default path. Excel import stays
as the manual fallback. This is a per-adapter choice, exactly the shape Part A
anticipated.

**What this decision does not claim:** that any specific model or firmware
the customers own supports ADMS, or over HTTPS. §4.3 is the acceptance test
for that claim. Until it runs, the adapter is built but not declared verified.

## 3. Topology

```text
Company A - Branch 1 - [ZK terminal SN=A1] --+
          |- Branch 2 - [ZK terminal SN=A2] --+   outbound HTTPS only,
          |- Branch 2 - [ZK terminal SN=A3] --+   device-initiated
Company B - Branch 1 - [ZK terminal SN=B1] --+
                                             v
                 https://devices.<platform-host>/iclock/*   (dedicated hostname)
                                             |
            +--------------------------------+--------------------------------+
            | ZkTecoAdmsAdapter: parse only. SN -> attendance_devices row ->  |
            | company_id, branch_id. Tenant is resolved here, never read from |
            | the payload.                                                    |
            +--------------------------------+--------------------------------+
                                             v
                 device_punches   (raw, append-only, idempotent on dedup_key)
                                             v
                 PunchPairingService -> attendance(check_in, check_out, method='device')
                                             v
                 notifications / dashboard live view
```

Design points:

- **Whole-hour device zones only.** The handshake expresses a device's zone
  as an integer number of hours, so a half-hour zone (India, Iran) would be
  delivered rounded and the terminal's clock — and every punch it reports,
  stored verbatim — would be thirty minutes out. A claim naming one is
  refused until the hardware checklist shows what that field really accepts.
- **One public ingest hostname**, separate from the API hostname: its own
  TLS termination, rate limits and WAF rules, and it can be moved off the
  monolith later without touching device configuration. Inside the
  application it is a module, not a separate service — the SPI is the seam
  that keeps that reversible.
- **Two terminals in one branch** (entrance and exit) are two registry rows
  bound to the same branch; their punch streams merge per employee.
- **An employee punching at another branch's terminal** is recorded and
  flagged `OUT_OF_HOME_BRANCH` unless `can_check_in_any_branch` is set.
  Biometric presence is stronger evidence than the policy; the policy
  produces a review item, not a rejection.

## 4. Device Identity, Registration And Claim

### 4.1 Registry

`attendance_devices` (Phase-1-owned, `phase1_extensions.schema.sql`; DDL
sketch in §7) keyed by the vendor serial number. It carries the tenant and
branch binding, the vendor, the device's configured time zone, liveness
fields (`last_seen_at`, `last_handshake_at`, `last_seen_ip`), the last
acknowledged `ATTLOGStamp`, and model/firmware/push-version as reported by the
device on handshake.

### 4.2 Claim before ingest

A terminal cannot display a pairing code, but its serial number is printed on
the unit and shown in its About menu. Registration is therefore a **claim**:
an authorised person adds the device in the platform, choosing the branch and
typing the serial number. Until a serial is claimed:

- the handshake is answered with the normal configuration, so the device
  does not error-loop and keeps trying to deliver;
- every upload is **refused with an error status (HTTP 403) and nothing is
  stored** — the terminal treats that as a failed transmission, keeps the
  records and retries after `ErrorDelay`, so punches made before the claim
  arrive once it is claimed rather than being lost;
- the sighting is recorded in `unclaimed_device_sightings` (serial, first and
  last seen, source IP, push version, device type) so the claim screen can
  show "a device with serial X has been knocking since 09:12".

This is the mechanism that keeps a payload-asserted tenant out of the trust
boundary (`docs/devices/device-integration-architecture.md`, Device
Authentication; `hr-legacy#9`'s guessable `company_id` is the failure mode it
avoids).

**Who may claim differs between pilot and production (D-159, R-041).** A
serial number is printed on the unit and the protocol offers no proof of
possession, so *who is allowed to establish ownership* is the only real
control:

- **Pilot — accepted as built.** Supervised claiming by a tenant
  `company_admin`/`hr` through `/api/v1/devices/**`. The claim, the sighting
  cleanup and the read-back are one transaction, so a failure cannot leave a
  serial owned by a caller who was told the claim failed — which, with no
  unclaim path, would have needed a manual database correction.
  **Attribution is weaker than it first looks**: legacy issues a
  `type=company` token for a company admin, and that token identifies the
  company rather than a person, so `registered_by_employee_id` is null for
  those claims. An individual actor is recorded only when the caller holds an
  employee token. Genuine per-person attribution depends on the individual
  platform-admin identity work (F-26) that production claiming is gated on
  anyway.
- **Production — required, not yet built.** Tenant admins may not claim by
  serial number at all. **Platform staff pre-allocate a device to a
  company**; tenant HR then only assigns an already-owned device to one of
  its branches. That removes the race rather than narrowing it, and makes
  allocation a privileged, audited operation — which ties it to F-26
  (individual platform-admin identity), already a P0 release gate.
- **Recovery — required before broad rollout.** An **audited unclaim /
  transfer / replace-device path**. Slice A has none, so a device registered
  to the wrong company today needs a manual database change, which D-159
  rules out as a long-term answer. Replacement is the ordinary case rather
  than the exceptional one: a terminal that dies is swapped for a new serial,
  and the branch's history has to survive it.

### 4.3 Hardware confirmation — a hard prerequisite

**Gate semantics (D-159).** This checklist does not block merging Slice A
while `app.devices.ingest.enabled` is off — the code is unreachable in that
state. It **does** block two things absolutely: describing the adapter as
hardware-verified anywhere, and enabling it for any real customer.

Executed once against a real **customer** terminal of each model in use,
with its Cloud Server Setting pointed at a development receiver through a
tunnel. Every answer is recorded in
`docs/devices/attendance-device-model-and-firmware-inventory.md` and
`vendor-capability-matrix.md`, which is what turns documentation evidence
into hardware evidence:

1. **Cloud Server Setting / ADMS availability** — the menu exists, and
   accepts a domain name rather than only an IP.
2. **HTTP vs HTTPS support** — whether TLS is offered at all, and which
   versions succeed. An HTTP-only model is recorded with its residual
   in-branch interception risk, per device.
3. **Actual POST `Content-Type`** — the receiver is built to survive
   `application/x-www-form-urlencoded`, which would otherwise have its body
   consumed before the handler reads it; this confirms which content type
   the firmware really sends.
4. **Wall-clock vs epoch timestamps** — the two forms need different
   handling, and reading one as the other shifts every punch by the device's
   offset (§5.2).
5. **Push protocol / version** — the `pushver`, `DeviceType`, `language` and
   `PushOptionsFlag` the device presents on handshake.
6. **PIN format and length** in use, against the receiver's own bound.
7. **Offline buffering and replay** — with the receiver stopped for ten
   minutes, buffered records arrive on reconnect carrying their original
   timestamps.
8. **ACK / retry behaviour** — a dropped acknowledgement causes the same
   batch to be re-sent, and it is stored once.

Two more, added by the review round (D-160) because the answers decide
whether two deliberate compromises can be lifted:

- **How the firmware encodes `ATTLOGStamp`** — the receiver currently never
  echoes it and always asks for a full re-send, which is correct but costs
  re-delivery. A trusted bookmark needs this.
- **The real per-upload record count** — to confirm the 5000-record cap is
  generous rather than something a buffered reconnect would trip.

Also recorded while the device is connected: the ATTLOG field count and
separator, whether employees ever press the in/out `STATUS` key, the device
clock source (NTP or manual) and its observed skew, and — where a half-hour
zone is involved — what the handshake's `TimeZone` field actually accepts
(§3).

## 5. The Wire Contract (ZKTeco adapter)

All requests are device-initiated HTTP. Bodies are plain text. The formats
below are as documented and as observed by the independent implementations in
§1.2; `\t` marks a tab character.

### 5.1 Handshake

```text
GET /iclock/cdata?SN=<serial>&options=all&language=69&pushver=2.4.0
    &DeviceType=middle%20east&PushOptionsFlag=1
```

Server reply (documented shape; values below are this design's choices):

```text
GET OPTION FROM: <serial>
ATTLOGStamp=0          always: this receiver never echoes a device's own
                       stamp back to it -- see below
OPERLOGStamp=<same, for operation logs>
ErrorDelay=30          seconds before the device retries after a failure
Delay=10               seconds between command polls -- this is the heartbeat
TransTimes=00:00;14:05 scheduled full-transmission times
TransInterval=1        minutes between periodic transmissions
TransFlag=TransData AttLog\tOpLog
                       deliberately excludes EnrollUser/ChgUser/EnrollFP/
                       ChgFP/UserPic/FACE/BioData -- see section 8
TimeZone=<device zone offset, from the registry row>
Realtime=1             upload each punch as it happens
Encrypt=None
```

### 5.2 Punch upload

```text
POST /iclock/cdata?SN=<serial>&table=ATTLOG&Stamp=<device stamp>

1\t2024-07-28 01:25:24\t0\t1\t\t0\t0
1\t2024-07-28 10:41:21\t0\t1\t\t0\t0
```

Fields: `PIN`, device-local wall-clock timestamp, `STATUS`, `VERIFY`,
`WORKCODE`, then reserved fields whose count varies by firmware. The parser
tolerates five to eight fields and CRLF line endings, quarantines a malformed
line without rejecting the batch, and accepts a Unix-seconds timestamp where
a firmware sends one. The server replies `OK: <n>`; a device that does not
receive the acknowledgement re-sends the batch.

Three facts drive the design:

- **A timestamp comes in two forms and they are not interchangeable.** The
  wall-clock form is already in the device's zone. A Unix-seconds value is an
  *instant*, so it becomes a wall clock only by applying that same zone once
  — converting it as UTC and then applying the zone again puts the stored
  local time behind the device's real clock by the offset and the stored
  instant ahead of the truth by it, which near midnight files the punch on
  the wrong attendance day. The adapter therefore receives the device's zone
  and resolves both forms itself.
- **What the parser emits has to fit the columns it lands in.** Status and
  verify codes are `SMALLINT` and the timestamp is a `DATETIME`; a value
  outside either is dropped at parse time, because the database is non-strict
  and would otherwise store a clamped number or a zero date. Wall clocks are
  parsed strictly, too: the default resolution would rewrite an impossible
  `2024-02-30` into 29 February and store a firmware fault as a real punch on
  a different day. A row the
  database still refuses is counted and acknowledged rather than thrown — an
  exception would make the device re-send the whole batch after every
  `ErrorDelay`, so one unstorable punch would block every good one beside it
  forever.
- **`STATUS` is unreliable.** `0` in, `1` out, `2`/`3` break, `4`/`5`
  overtime — but employees rarely press the state key, so most devices report
  `0` for every punch. In/out is **inferred from sequence** (§7.2), exactly
  as the Excel import already does. `STATUS` and `VERIFY` are stored raw for
  audit and never interpreted by business logic in Slice A.
- **Timestamps are device-local wall-clock with no offset**, from a clock
  that drifts. The registry row's `device_time_zone` converts them;
  observed skew (device-reported time versus `received_at`) is a metric.
- **The resume stamp is never trusted, and never echoed.** The `Stamp` a
  device sends arrives on an unauthenticated request, so anyone who knows a
  claimed serial could set it — one fabricated punch is enough to satisfy any
  "was this delivery real" test. A far-future value returned in the next
  handshake tells the terminal that everything up to it has been received,
  and it drops the buffered punches it still holds. The receiver therefore
  always answers `ATTLOGStamp=0` — "send what you have" — and relies on the
  content-hash idempotency for the duplicates that produces. The observed
  stamp is still recorded as a diagnostic and never used. A trusted bookmark
  needs the firmware's stamp encoding, which is a §4.3 question.
- **The record cap counts records, not separators.** A batch of exactly the
  maximum ends with a line terminator, and counting terminators would refuse
  it — so a terminal that always fills its batch would retry the same upload
  forever, which is the failure the cap exists to prevent rather than cause.
- **An operation-log replay is idempotent.** Because the handshake always asks
  a device to resume from the beginning, operation lines carry the same kind of
  content-hash key the punches do; otherwise every reconnect would append the
  terminal's whole history again.
- **A byte cap does not bound the work.** A one-megabyte body of minimal
  lines is tens of thousands of records, and each becomes a statement, so one
  permitted request turns into tens of thousands of database operations that
  proxy rate limiting cannot see. Uploads are capped by *record count* as
  well as bytes (`app.devices.ingest.max-records-per-upload`, default 5000)
  and refused — not truncated — above it, because silently keeping the first
  N of a batch the device believes was delivered is how punches disappear.
  Operation-log inserts are batched into one statement.
- **The parameters must be read without touching the body.** On a POST,
  `@RequestParam` reads the servlet parameter map, and for a
  `application/x-www-form-urlencoded` content type the container builds that
  map by *consuming the body* — which here is the punch batch. Whoever asks
  for a parameter first wins, and the handler then reads nothing, answers
  `OK`, and the terminal drops the records. The receiver captures the body in
  a filter ahead of the security chain and takes its parameters from the
  query string.
- **There is no per-record identifier.** The idempotency key is
  `sha256(serial | PIN | time | STATUS)`, where *time* is the **instant** when
  the device reported one and the wall clock otherwise — keying an epoch-form
  punch by its local time would collapse the two distinct instants that share
  a wall clock in an autumn daylight-saving overlap, silently discarding one — the synthesised key
  `device-integration-architecture.md` anticipated for vendors without a
  stable event ID. A lost acknowledgement re-sends a batch; a factory reset
  resets the stamp and re-sends everything; both are no-ops.

### 5.3 Command polling and acknowledgement

```text
GET  /iclock/getrequest?SN=<serial>          every `Delay` seconds
     -> "OK"                                 nothing queued
     -> "C:<id>:<command>"                   one line per queued command
POST /iclock/devicecmd                        body: ID=<id>&Return=<code>&CMD=<name>
```

Every poll updates `last_seen_at`; that is the liveness signal, with no
extra mechanism. The command allow-list is closed (§8).

### 5.4 Other tables

`OPERLOG` uploads mix record kinds on one body: `OPLOG` lines (operations
on the device), `USER` lines (enrolment records carrying a name) and — when
a handshake allows it — biometric template lines (`FP`, `FACE`, `BIODATA`,
`BIOPHOTO`, `USERPIC`). Only `OPLOG` lines are stored, for audit. `USER`
lines are counted, not stored (names stay on the device; the enrolled-PIN
set is Slice C). Template lines are **discarded before storage**; only their
count is logged. Uploads of other tables (`FINGERTMP`, `BIODATA`,
`ATTPHOTO`, ...) are acknowledged with `OK` — so the device does not retry
indefinitely — and discarded, with a WARN log, because `TransFlag` should
have prevented them. A `table=options` upload (the device describing
itself) updates model, firmware and push version in the registry.

## 6. Where It Lives In The Backend

- **Layers.** Controller → service → store, as elsewhere in this codebase.
  The controllers (`ZkTecoAdmsController`, `DeviceManagementController`) hold
  only HTTP: where a parameter comes from, how the body is obtained, which
  status code an outcome becomes, what the JSON looks like. The services
  (`ZkTecoAdmsService`, `DeviceManagementService`,
  `DevicePunchIngestionService`) hold every rule — the trust model, serial
  validation, record caps, what a tenant may claim, how a PIN resolves — so a
  rule is testable without a request and reusable when a second vendor
  arrives. The stores hold SQL, with the tenant predicate on every query.
  Beside them sit pure translators (parser, handshake renderer, operation-log
  filter, `DeviceInput`, `QueryParameters`), which is what lets the protocol's
  sharpest edges be unit-tested without a container.
- **Package** `com.workin.devices` — a new root, outside both
  `com.workin.backend` (Postgres-era scan root) and `com.workin.legacy` (the
  PHP parity port). It is component-scanned **only** under the
  `phase1-mysql` profile by adding it to `LegacyPersistenceConfig`'s
  `@ComponentScan`, the "explicit, deliberate scanning" that
  `LegacyAdapterIsolationTest`'s javadoc describes as the intended path for
  MySQL-era code. Persistence is `JdbcTemplate` over `legacyDataSource`, the
  pattern `LegacyAttendanceSessions` already uses; no JPA entities, so
  `TenantFilterCoverageTest` and `@EntityScan` are untouched.
- **Security.** A dedicated `SecurityFilterChain` (`@Order(1)`,
  `@Profile("phase1-mysql")`, `securityMatcher("/iclock/**")`): stateless,
  CSRF off, no JWT filter, and `permitAll` at the chain layer because device
  identity is the serial-to-registry resolution enforced inside the adapter,
  not a bearer token. The existing `/apis/**` chain is unchanged.
  `LegacyPhpRouteInventoryTest` filters `/apis/` and is unaffected; a
  one-line assertion that no `/iclock` pattern appears under `/apis/` pins
  that.
- **Default closed.** `app.devices.ingest.enabled=false` unless set; the
  controller is `@ConditionalOnProperty` so a deployment without the flag
  exposes no `/iclock` surface at all.
- **Errors** answer in plain text, never the D-074 PHP envelope: the client
  is a terminal, not a Flutter app. A dedicated `@RestControllerAdvice`
  scoped to `com.workin.devices` keeps `LegacyWireExceptionHandler` out of
  the path.
- **Device-management API for people** (claim, list, rename, deactivate,
  unclaimed sightings): `/api/v1/devices/**`, JSON, authenticated with the
  legacy PHP JWT (roles `company_admin`, `hr`) through the existing legacy
  filter. This is new tenant-admin surface, not a PHP parity route; it does
  not change any existing client (D-111's invariant), and it is the API the
  JTE admin surface (ADR-0015) or the desktop client renders later. Who
  performs the claim during the pilot is Q6 (§12).

## 7. Data Model And Pipeline

### 7.1 Two layers, deliberately

- **`device_punches`** — immutable facts from devices. Never edited, never
  deleted, replayable.
- **`attendance`** — the derived sessions HR can edit and payroll reads.
  `method='device'` requires an **expand-only** enum change on a live
  MariaDB table that frozen PHP still reads (Q5). `attendance_source_punches`
  links each derived row to the punches that produced it.

**Device rows follow the lifecycle of what they point at.** None of the five
tables has a foreign key — they are Phase-1-owned, and the legacy dump adds its
own constraints separately — so every lifecycle is closed in code, and each
tolerates the tables being absent because provisioning is still open (R-023 /
Q7). Deleting an **employee** removes their PIN binding, on both paths that
delete one, or the identities endpoint would list a PIN against a blank
employee and the unique key would keep that PIN from ever being reissued.
Deleting a **branch** deactivates the devices placed in it rather than refusing
the deletion: `branches/delete.php` answers 200 today and D-111 does not permit
that route to start answering 409, so the terminal keeps its registration and
history but stops ingesting into a branch that no longer exists.

**A company's device data is deleted with the company.** `LegacyCompanyDelete`
cascades through explicit table lists, and the five device tables are added to
it (children first, tolerating a table a deployment has not provisioned).
Without that, a deleted company's registry, PIN bindings, punches and
operation logs would outlive it, and the globally-unique serial would keep the
terminal from ever being claimed again. The **preview** that endpoint returns
is deliberately left alone: its key set is a client-visible contract (D-111)
rendered by Flutter clients that cannot be inspected from this repository
(PMR-02), so a company admin is told how many attendance records will go but
not how many device punches. That under-reporting is a recorded gap awaiting
an owner decision.

**The Q5 audit is done (D-159) and the change is safe.** Every frozen-PHP
site *writes* `attendance.method` — `check_in.php:58` and `create.php:111`
(`?? 'app'`), `check_in_qr.php:71` (`'qr'`),
`attendance_excel_analyzer.php:1019` and `xlsx_parser.php:615` (`'excel'`),
`request_actions_helper.php:170` (`'app'`) — and exactly one site *reads*
it: `dashboard/pages/employees/detail.php:82`, which renders
`clean($a['method'])` verbatim. There is no comparison, no `switch`, no
`WHERE method =` filter, no i18n label keyed by the value and no export
column; the dashboard's `ATTEND_APP`/`ATTEND_QR`/`ATTEND_EXCEL` constants are
only a form default for HR-entered rows. Old PHP and new Java therefore both
work throughout the rollout, in either deployment order. Two residual checks
belong to Slice B: the dashboard shows the literal word `device` until it is
given a label, and the Flutter clients are pinned submodule references that
no clone populates (PMR-02), so if either renders `method` it must be
verified first. **Migration shape**: `attendance` is 36,316 rows / 64 MB, and
a fourth value does not change a `≤255`-value enum's one-byte storage, so the
`ALTER` should be `ALGORITHM=INSTANT` and is trivial even if it copies.

**`backend/src/test/resources/legacy/phase1_extensions.schema.sql` is the
authoritative schema — provision from that file, not from this section.** What
follows shows the three tables whose shape the design turns on; the other two
(`employee_device_identities`, §7.2, and `device_operation_logs`, which mirrors
`device_punches`'s content-hash key so a replayed operation log is stored once)
live in the same file. This sketch drifted from the real schema once already,
which is why it now says where the truth is instead of trying to be it.

```sql
CREATE TABLE attendance_devices (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id INT UNSIGNED NOT NULL,
    branch_id INT UNSIGNED NOT NULL,
    vendor VARCHAR(32) NOT NULL,
    serial_number VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    model VARCHAR(100) NULL,
    firmware VARCHAR(100) NULL,
    push_version VARCHAR(32) NULL,
    device_time_zone VARCHAR(64) NOT NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    last_seen_at DATETIME NULL,
    last_handshake_at DATETIME NULL,
    last_attlog_stamp VARCHAR(32) NULL,
    last_seen_ip VARCHAR(45) NULL,
    registered_by_employee_id INT UNSIGNED NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT attendance_devices_vendor_chk CHECK (vendor IN ('zkteco'))
);

CREATE TABLE device_punches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id BIGINT NOT NULL,
    company_id INT UNSIGNED NOT NULL,
    -- Snapshotted at ingestion, not read back through the device: a terminal
    -- can be moved between branches, and reporting its current branch would
    -- retroactively relabel every punch it ever sent.
    branch_id INT UNSIGNED NOT NULL,
    employee_id INT UNSIGNED NULL,
    pin VARCHAR(32) NOT NULL,
    punched_at_local DATETIME NOT NULL,
    punched_at_utc DATETIME NOT NULL,
    status_code SMALLINT NULL,
    verify_code SMALLINT NULL,
    work_code VARCHAR(32) NULL,
    received_at DATETIME NOT NULL,
    dedup_key CHAR(64) NOT NULL UNIQUE,
    raw_line VARCHAR(512) NOT NULL,
    processing_state VARCHAR(16) NOT NULL,
    CONSTRAINT device_punches_state_chk
        CHECK (processing_state IN ('RECEIVED', 'UNMATCHED', 'PAIRED', 'IGNORED'))
);

CREATE INDEX device_punches_device_time_idx ON device_punches (device_id, punched_at_local);
CREATE INDEX device_punches_employee_time_idx ON device_punches (company_id, employee_id, punched_at_local);
CREATE INDEX device_punches_state_idx ON device_punches (processing_state);

CREATE TABLE unclaimed_device_sightings (
    serial_number VARCHAR(64) PRIMARY KEY,
    first_seen_at DATETIME NOT NULL,
    last_seen_at DATETIME NOT NULL,
    last_seen_ip VARCHAR(45) NULL,
    push_version VARCHAR(32) NULL,
    device_type VARCHAR(64) NULL,
    hit_count INT UNSIGNED NOT NULL DEFAULT 1
);
```

`company_id` and `branch_id` on `device_punches` are both snapshotted from the
device row at insert — the first so every tenant-scoped read has a direct
predicate, the second so a device's history is not rewritten when it moves. `punched_at_utc`
is derived from `punched_at_local` and the device's zone; Slice B writes
`attendance.check_in` in legacy's runtime offset (`LegacyRuntimeOffset`,
D-099), not in UTC, so it compares correctly with app and QR rows.

### 7.2 Employee identity (Q1)

A ZKTeco `PIN` is a numeric string, unique **per device**, in practice kept
consistent per company. Two options:

- **(a)** reuse `employees.employee_code` as the PIN. Matches today's Excel
  practice and needs no table, but forces `employee_code` to be numeric and
  unique per company forever, and leaves card numbers homeless.
- **(b)** `employee_device_identities (employee_id, company_id, pin, card_no,
  source)` with `UNIQUE (company_id, pin)`; where no row exists, resolution
  falls back to `employee_code` equal to the PIN, so a company that never
  binds a PIN keeps today's behaviour with no seeding step. PIN uniqueness
  is per company, not global: serial resolves the company first, then
  `(company_id, pin)` resolves the person. A code shared by two employees
  resolves to nobody rather than to the lower id.

Recommendation: (b). It also becomes the source for pushing `USERINFO` down
to branch devices in Slice C.

An unmatched PIN never drops a punch: the row is stored with
`employee_id NULL` and `processing_state='UNMATCHED'`; HR maps the PIN once
and the pairing job replays.

### 7.3 Pairing (Slice B)

`pair(employee, shiftWindow, punchesSortedByTime) -> sessions` is a pure
function, so it can be re-run for an `(employee, attendance day)` whenever a
late punch arrives — and late punches will arrive: a terminal offline for two
days delivers hundreds of old-timestamped records in one burst. Ordering is
by `punched_at`, never by arrival (`device-integration-architecture.md`,
Late-event business-rule implications).

Rules, against legacy behaviour:

- **Two-hour minimum gap (Q2) — decided (D-159): a device punch is never
  rejected.** Legacy rejects a second check-in within 120 minutes and tells
  the app. A terminal cannot be told; it has already said "Thank you", so
  refusing at the boundary would destroy biometric evidence of presence with
  nothing to show the person. For `method='device'`: always persist; suppress
  a double-read with a short duplicate/debounce window (the terminals have
  the same setting, and `processing_state='IGNORED'` already exists for it);
  and flag a rapid re-check-in as `RAPID_RECHECKIN` for review.
- **Synthetic check-out.** When `LegacyAttendanceSessions` has auto-closed a
  session and a real device check-out arrives later, the device value
  replaces the synthetic one and the row is flagged `SYNTHETIC_REPLACED`.
- **Night shifts** pair within the employee's shift window from the schedule
  module, not the calendar day.
- **A PIN resolves only to an active employee**, whether it was matched by
  an explicit binding or by the `employee_code` fallback. A badge outlives
  employment, so a punch on a departed employee's PIN is `UNMATCHED` for
  review rather than attributed to them — a deliberate difference from the
  Excel import, which applies no such filter.
- **An unrecognised `is_active` is refused, not read as false.** This is a new
  JSON API rather than a PHP-coercion route, so a typo or a null would
  otherwise deactivate the terminal and start refusing its punches — with a 200
  to say it worked.
- **A punch records the branch it happened at**, snapshotted from the device
  at ingestion rather than read back through the registry. A terminal can be
  moved between branches, and reporting its current branch would retroactively
  relabel every punch it ever sent — which would make the out-of-home-branch
  policy unreconstructable.
- **Odd punch counts** leave an open session, as today, plus a review flag.

### 7.4 Events

After pairing, a domain event drives the existing notification path and the
dashboard's live view. That is the user-visible "appears directly in the
platform".

## 8. Security Model

The protocol is weak by default: plain HTTP, and the serial number is the
only identity. Kaspersky found 24 vulnerabilities in the terminal's own
parsers and a root `SHELL` command reachable by anyone who can impersonate the
server. The design compensates:

- **TLS at the edge, always.** Where a firmware is HTTP-only, the residual
  risk is in-branch interception; it is documented per device in the
  inventory, not assumed away.
- **Claim-before-ingest** (§4.2) is the primary control against "anyone who
  knows a serial can post fake punches into payroll". Added to it: a
  request-body cap enforced ahead of the security chain, a tab-split parser
  that treats every byte as hostile, and — at the reverse proxy, not in the
  application — per-IP rate limits and soft-binding of serial to the observed
  address with alerting (not blocking, since branch addresses change).
- **An unclaimed sighting expires.** Every syntactically valid serial sent to
  the public routes creates a row, and only a claim removes one, so without a
  retention window a slow distributed probe could grow that table indefinitely
  without knowing a single real serial. Rows unseen for 30 days are pruned, and
  only when a genuinely new serial arrives, which keeps the cost off the path a
  real device takes every few seconds.
- **One PIN rule everywhere.** The binding API, the receiver's parser and both
  columns share a single definition. A PIN the API accepted but the parser
  rejected was the worst combination available: the binding reported success
  and every punch it should have matched was quarantined as malformed.
- **A serial number is validated before it is used for anything.** Shape and
  length, at the entry of every device route. This is not tidiness: the
  legacy database is non-strict, so an over-long serial would be *truncated*
  into a collision with a different device's registry key, and an
  unconstrained one would let any caller grow the sightings table a row at a
  time.
- **Nothing a caller controls is echoed, tagged or logged verbatim.** The
  `ATTLOGStamp` is accepted only as digits and only from a delivery that
  actually carried punches — the first rule stops CR/LF writing extra lines
  into the handshake reply, the second stops an empty POST moving a
  terminal's resume bookmark to a far-future value, after which the terminal
  would consider its buffered punches already delivered and drop them.
  Metric tags come from a closed set, so the `table` parameter cannot grow
  the meter registry without bound. Logged values are
  control-character-stripped and length-bounded.
- **Deactivating a device stops the flow of information, not only of
  uploads.** An inactive serial is handshaken exactly like an unknown one —
  no stamp, no zone — and nothing new is learned from it.
- **What the platform cannot fix in code: claim squatting** (R-041, Q8). The
  protocol has no proof of possession, so a tenant who learns an unclaimed
  serial can register another company's terminal. The claim is attributable,
  a serial resolves to one company only, and the unclaimed lookup answers for
  another company's device exactly as for one never seen — but prevention
  needs the owner's decision on Q8, not another guard here.
- **Closed command allow-list:** `INFO`, `CHECK`, `DATA QUERY ATTLOG`,
  `DATA UPDATE USERINFO`, `DATA DELETE USERINFO`, `SET OPTION` (time),
  `REBOOT`. Never `SHELL`; never an automatic `CLEAR LOG` — the device's
  memory is the last-resort backup. Every queued command is an audited
  action by an authenticated person.
- **No biometric templates on the platform in Phase 1 — decided (D-159).** `TransFlag`
  excludes them and the adapter discards any that arrive (§5.4). Templates
  are sensitive personal data under the applicable data-protection regimes
  and `AGENTS.md` already forbids agent access to them. Cross-branch
  "enrol once, work anywhere" template sync is a real future request; it
  needs an explicit decision with encryption at rest, retention and impact
  assessment first.
- **Raw payload retention:** `raw_line` only, never the whole request, with
  a retention period to be set with Q3.
- **A tenant is told only what is theirs.** `GET /api/v1/devices/unclaimed`
  answers an exact serial with a boolean and nothing else — no timestamps,
  address, push version or device type — and a serial owned by another
  company answers exactly as one that was never seen. There is no list form:
  listing unclaimed serials would hand every company a directory of every
  other company's hardware.

## 9. Operations

- **Liveness for free:** every `getrequest` poll updates `last_seen_at`. A
  device silent beyond a threshold surfaces as "Branch 2 device offline since
  09:12"; employees fall back to app or QR. This is the operator signal for
  the new external dependency.
- **Metrics:** punches received and deduplicated per device, pairing lag
  (`received_at - punched_at`), unmatched-PIN count, unclaimed-serial hits,
  clock skew per device. Logs carry the serial and company on every line, per
  `docs/operations/logging-conventions.md`.
- **Runbook cases** (to be folded into
  `docs/operations/gateway-operational-support.md`, which currently assumes a
  gateway): device replaced (new serial: re-claim, re-map identities or push
  `USERINFO`); factory reset (stamp resets, full re-send, dedup absorbs it);
  receiver down (device buffers, typically tens of thousands of records,
  retries every `ErrorDelay`).
- **Production schema provisioning** for the new tables is the same open
  gate `legacy_refresh_tokens` already sits behind (ADR-0013 Open Questions).
  Slice A is testable end to end without it and deployable only with it.

## 10. Rollout And Rollback

- **Pilot in shadow mode:** one branch, `device_punches` populated, nothing
  written to `attendance`. Compare against that branch's Excel import for one
  payroll cycle.
- **Enable pairing per company** behind a company-level flag once shadow
  data matches.
- **Rollback:** disable the flag (pairing stops; raw punches remain), or set
  `app.devices.ingest.enabled=false` (the `/iclock` surface disappears;
  devices buffer). No existing table is altered in Slice A, so rollback needs
  no schema step. The Q5 enum expansion, when it comes, is expand-only and
  independently reversible.

## 11. Slices

| Slice | Scope | Gate |
|---|---|---|
| **A — ingest and visibility** | Registry and claim API; PIN identities; `/iclock/{cdata,getrequest,devicecmd}`; raw punch log with dedup; unclaimed sightings; heartbeat; template discard; property gate; raw-punch visibility endpoint; tests in §13 (the end-to-end test plays the device). **Not in A:** in-app per-serial rate limits (the pilot relies on the edge/reverse proxy), the command queue, a standalone simulator CLI | **Open** — D-158 accepted, Q1 = identity table, Q6 = tenant API (all 2026-09-02) |
| **B — pairing** | Pure pairing function with replay; anomaly flags; `method='device'` enum expansion; `attendance_source_punches`; HR review queue; notifications | Q2 and Q5 answered; Slice A in shadow at a pilot branch |
| **B′ — ownership and recovery** (D-159) | Platform-mediated allocation of a device to a company, tenant assignment to a branch, and the audited unclaim / transfer / replace-device path | Required before production ingestion; closes R-041 |
| **C — device management** | Push `USERINFO` on employee create/move; time sync; queued-command UI with audit | Slice B live |
| **D — later** | Hikvision/Anviz/Suprema adapters; edge-gateway bridge for non-ADMS terminals. Biometric template sync is **not** on this roadmap — D-159 rules it out for Phase 1 | Separate decisions |

## 12. Decisions Required From The Repository Owner

Q0, Q1 and Q6 were decided on 2026-09-02 (D-158); Q2, Q3, Q5, Q7 and Q8 the
same day (**D-159**). None remains open — what is left is the work those
answers oblige, tracked in D-159's Follow-up, R-041 and §11's slice B′.

| # | Question | Recommendation |
|---|---|---|
| Q0 | Accept D-158: ADMS push is the primary ZKTeco adapter; the edge gateway is a fallback | **Decided 2026-09-02: accepted**, conditional on §4.3 passing on the first real device |
| Q1 | Device PIN identity: reuse `employee_code` (a) or a new `employee_device_identities` table (b) | **Decided 2026-09-02: (b)** |
| Q2 | Device punches under the two-hour rule: reject like the app, or debounce and flag | **Decided 2026-09-02: never reject** — persist, debounce a double-read, flag a rapid re-check-in |
| Q3 | Biometric templates: never in Phase 1, or planned with controls | **Decided 2026-09-02: none in Phase 1** — attendance events and metadata only |
| Q5 | When to expand `attendance.method` on the live table, given frozen PHP still reads it | **Decided 2026-09-02: expand-only with Slice B**, and the audit it was conditional on is complete (§7.1) |
| Q6 | Who claims devices during the pilot: HR through `/api/v1/devices/**`, or platform staff on the tenant's behalf | **Decided 2026-09-02: tenant HR/admin through the API**; platform staff use a company admin's session for the pilot |
| Q7 | Production provisioning of Phase-1-owned MariaDB tables (ADR-0013 open question, now on the critical path) | **Decided 2026-09-02: must be explicitly solved before production ingestion is enabled** (R-023) |
| Q8 | Proof of possession when claiming a device (**R-041**) | **Decided 2026-09-02: supervised tenant claiming for the pilot; platform-mediated allocation for production, with tenant HR only assigning an owned device to a branch; an audited unclaim/transfer/replace path before broad rollout** (§4.2, slice B′) |

Q4 (module inside the monolith versus a separate service) is recorded as an
assumption in §3, not a question: the module is cheaper, and the SPI keeps
the split available.

## 13. Verification Plan (Slice A)

Implemented on `feat/attendance-device-ingestion`; every item below is a
test that exists and passes.

- `ZkTecoAttlogParserTest` (unit): the captured seven-field line, the
  five-field shape, a two-field minimum, Unix-seconds timestamps, CRLF and
  blank lines, four kinds of malformed line quarantined without losing the
  good ones, bounded PIN and work code, and a dedup key that is stable
  across redelivery and differs by status and by device.
- `ZkTecoOperlogFilterTest` (unit): `OPLOG` kept, `USER` counted, every
  template kind discarded with none of its content surviving.
- `ZkTecoHandshakeTest` (unit): stamp and zone from the registry row, only
  `AttLog` and `OpLog` transfer flags, a valid zero-stamp reply for an
  unclaimed serial.
- `DevicesModuleIsolationTest` (architecture): the module is reached only by
  `LegacyPersistenceConfig`'s scan, declares no JPA entity, and every
  configuration in it is guarded to `phase1-mysql`.
- `DeviceIngestionEndToEndTest` (real MariaDB, `phase1-mysql`, flag on; the
  test plays the terminal and the administrator): an unclaimed serial gets a
  valid handshake, is recorded as a sighting, has its upload refused with 403
  and nothing stored, and is visible by exact-serial lookup; a claimed
  device's punches carry the registry's company and branch, resolve through
  `employee_code`, keep the device wall-clock verbatim and derive UTC through
  the device zone, and record the stamp; redelivery of a batch stores
  nothing new and is still acknowledged; a malformed line is quarantined
  without refusing the batch; `OPERLOG` keeps operation lines and no
  template or name content reaches storage; template and photo tables are
  acknowledged and discarded; a `table=options` upload updates model,
  firmware and push version; `getrequest` advances liveness and `devicecmd`
  is acknowledged; an oversized body answers 413; a deactivated device is
  refused like an unclaimed one; a missing serial is a plain-text 400; the
  management API answers 401 without a token, 403 for an employee role and a
  suspended company, hides another tenant's devices, answers 404 for another
  tenant's device or branch, 409 for a serial already claimed, and validates
  serial, zone and empty updates; a bound identity overrides the
  `employee_code` fallback and its conflicts answer 409; the punches endpoint
  shows only the caller's company; the module's routes are mapped under
  `/iclock` and `/api/v1/devices` and never under `/apis/`.
- `LegacyPhpRouteInventoryTest` (existing, extended): a `phase1-mysql`
  context without the flag maps no `/iclock` route — default closed is
  asserted, not assumed.
- Existing guards re-run unchanged: `ProfileCoverageArchTest`,
  `MessageCatalogSyncTest`, `LegacyAdapterIsolationTest`,
  `TenantFilterCoverageTest`; then the full backend suite.
- Added by the review round, each pinning a defect that was silent rather
  than visible: `DeviceInputTest` and `QueryParametersTest` (the validation
  and query-string rules the fixes rest on); `DevicePunchIngestionServiceTest`
  (both timestamp forms agree on the stored instant, and the daylight-saving
  policy is the documented one); parser cases for out-of-range codes and
  years; and end-to-end cases for a form-urlencoded upload keeping its
  punches, a CR/LF stamp never reaching the handshake, a stamp on an empty
  delivery not moving the bookmark, an unusable serial refused before it can
  create a sighting, a deactivated device handshaken neutrally, a half-hour
  zone refused at claim time, the unclaimed lookup telling one tenant nothing
  about another's device, and a departed employee's PIN staying `UNMATCHED`.
- Added by the **independent review round** on PR #162, which found ten
  further defects — four of them P1 — each now pinned by a test: the resume
  stamp is never echoed, so a fabricated punch cannot move a terminal's
  bookmark; an upload above the record cap is refused rather than partly
  stored; a punch keeps the branch it happened at when its device is moved; a
  company's device data is deleted with the company; an impossible calendar
  date is malformed rather than rolled back; two epoch punches sharing a wall
  clock in a daylight-saving overlap stay distinct; an explicit binding to a
  deactivated employee stops resolving; an `employee_code` stored with a
  trailing space still resolves; an over-long serial is refused rather than
  registered as its prefix; and a zone that turns fractional in another season
  is refused.
- Added by the **second review round** (D-161), which found eleven more:
  a batch of exactly the record cap is accepted terminator and all; a replayed
  operation log is stored once; a PIN at the API's limit still resolves a
  punch; an over-long `PushVersion` is bounded to its column; an unrecognised
  `is_active` is refused rather than deactivating the device; and deleting a
  branch deactivates the terminals placed in it.
- Not automated in this slice: the §4.3 hardware checklist (needs a real
  terminal) and a standalone simulator CLI for it.

## 14. Risks

- **The customers' terminals turn out not to support ADMS** (R-004). Then the
  edge-gateway fallback moves forward for those models; the vendor-neutral
  core is unchanged. §4.3 finds this out before Slice A ships.
- **Serial spoofing after a claim.** Mitigated by claim-before-ingest, rate
  limits, IP soft-binding and anomaly flags; residual risk documented in the
  threat model.
- **Clock skew produces wrong attendance days.** Mitigated by per-device
  zone, skew metric, and Slice C time sync.
- **Decision-log numbering collision.** Two sessions are active in this
  repository today; D-158 is claimed here and must be renumbered if another
  branch lands first (the repository has had exactly this collision before).

## Related

- ADR-0006, `docs/devices/device-integration-architecture.md`,
  `docs/devices/vendor-capability-matrix.md`,
  `docs/devices/attendance-device-model-and-firmware-inventory.md`
- `docs/bootstrap/decision-log-wave12r.md` D-158 (accepted 2026-09-02)
- `docs/bootstrap/risk-register.md` R-004
- `docs/migration/pre-migration-readiness-gap-analysis.md` PMR-04
- `workin-hr/hr-platform#12`
