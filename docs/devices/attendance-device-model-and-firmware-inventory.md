# Attendance Device Model And Firmware Inventory

One entry per distinct model/firmware found in customer branches. Fields not
yet observed on hardware say `Not yet discovered`; nothing here is filled in
from assumption. The hardware checklist that completes an entry is
`../superpowers/specs/2026-09-02-attendance-device-ingestion-design.md` §4.3.

## Entry: ZKTeco attendance terminals (customer fleet, model not yet inventoried)

### Vendor

ZKTeco — stated directly by the product/business owner on 2026-08-05
(ADR-0006 Part B; `../bootstrap/decision-log.md` D-025). The owner also
stated the platform must support every model and firmware in the product
line, not one specific model.

### Model

Not yet discovered. The only production signal is indirect: the Excel punch
logs customers import today are two-column `code + datetime` exports whose
timestamps carry device suffixes such as `A4P4`
(`hr-legacy/apis/helpers/attendance_excel_analyzer.php` line 187), the
shape of a ZKTeco attendance-log export.

### Firmware

Not yet discovered. The push protocol version the device reports on handshake
(`pushver`, e.g. `2.4.0` in the captured collection cited by the
specification) is the first value to record when a real terminal connects.

### Integration Pattern

- **Today:** manual export/import — the device log is exported to Excel and
  imported through `attendance/import_excel.php` (ported as
  `LegacyAttendanceImporter`). `employees.employee_code` is the de-facto
  device PIN.
- **Decided (D-164, accepted 2026-09-02):** push callback — device-initiated ADMS /
  PUSH SDK over HTTP(S) to a platform hostname. See
  `vendor-capability-matrix.md` and the specification §5.

### Network Constraints

- Requires outbound HTTP(S) from the branch LAN to one platform hostname and
  port. No inbound port, no static IP, no VPN, no software at the branch.
- HTTPS availability is per firmware and not yet observed; where a terminal
  is HTTP-only the residual in-branch interception risk is recorded per
  device here.
- The device clock is a per-device concern (NTP availability not yet
  observed).

### Evidence

- Owner statement, 2026-08-05 (ADR-0006 Part B, D-025).
- `hr-legacy/apis/helpers/attendance_excel_analyzer.php` (fingerprint-machine
  punch-log aliases and datetime suffix handling); `LegacyAttendanceImportReader`
  (`PUNCH_LOG_CODE_ALIASES` including `fingerprint_no`).
- Protocol documentation and independent implementations: specification
  §1.2.
- Hardware evidence: the first terminal is inventoried below. This entry stays as the
  fleet-wide statement, because the owner's requirement is every model in the product
  line and one terminal does not close that.

## Entry: ZKTeco MB20/ID, firmware `Ver 6.60 Oct 12 2021`

The first terminal observed on real hardware, at a customer branch on 2026-09-21
(D-274). Everything below was read off the device itself; nothing is inferred. What
was not observed says so.

### Observed

A terminal's serial is the **only** thing that identifies it to the device endpoint (`docs/api/device-endpoints.md`), so knowing one is enough to inject punches against a claimed device (R-041) or to squat an unclaimed one (R-042). A serial read on a visit therefore never reaches a tracked file: this repository is public. Each verified terminal gets a stable pseudonym here -- `TERMINAL-A`, `TERMINAL-B`, ... -- and the real serial stays in that visit's own `field-report/` on the operator's laptop. `test_no_tracked_file_publishes_a_terminal_serial` fails on the ZKTeco form -- two to six letters then six to fourteen digits -- in any tracked document, agent file, contract or spec. It is a backstop for the mistake that was actually made, not a proof that nothing can slip through: an all-digit serial is indistinguishable from a record count or a date. The visit report is written without the serial or any address for the same reason, since the runbook's step 11 says to paste it into a public issue.

| Field | Value |
|---|---|
| Vendor | ZKTeco |
| Model | MB20/ID |
| Platform | ZLM60_TFT |
| Firmware | Ver 6.60 Oct 12 2021 |
| Serial | `TERMINAL-A` |
| Transport | TCP 4370 |
| Comm key | none set |
| Record format | 40-byte |
| In/out column | punch |
| Records at the visit | 11426 of 50000 |
| Users | 58 |
| Fingerprints | 60 |
| Faces | 1 |

### Firmware And Push Version

`Ver 6.60 Oct 12 2021`, reported by the terminal to `CMD_GET_VERSION`. No `pushver`:
this terminal has no Cloud Server / ADMS screen in its menu, so it never handshakes
and reports no push protocol version.

### Integration Pattern For This Firmware

**Pull over the ZKTeco binary protocol on TCP 4370**, which is what the agent does
(`workin_devices/zk4370.py`, read-only by construction). Push is not available on
this firmware: the operator confirmed there is no Cloud Server Setting or ADMS screen,
so D-164's device-initiated callback cannot be used for this model as shipped. The
manual Excel export path remains available and is unchanged.

### Record Semantics

Every one of the 11 426 stored records is the **40-byte** layout, and the in/out state
is in the code this repository calls `punch` (pyzk's naming), not `status`. The evidence
is the log's own shape rather than one punch: `punch` splits 5 779 / 5 640 across the
log while `status` reads 1 in 11 424 records and 15 in two. A single check-in and
check-out could not settle it, because this terminal recorded both with identical codes
(`punch=5, status=1`) — see D-274 for why that is, and for the rule that reads it.

### Network Constraints Observed

- Answers on TCP 4370 on the branch LAN; no inbound port from outside the branch.
- HTTPS: not applicable — there is no web or push surface on this firmware.
- Clock: NTP reported on by the operator, daylight saving off, and the device clock ran
  9 seconds behind the laptop at the visit. The device was allocated `+02:00` in the lab
  while its own screen read `+03:00`; that mismatch is a lab-allocation fact, not a
  device one.

### Not Observed

Push/ADMS behaviour, HTTPS, `pushver`, `DeviceType`, USB export column order, and
anything requiring a settings change — the visit changes nothing on a terminal.

### Evidence For This Terminal

- Site visit 2026-09-21, `workin_devices visit` against the terminal on the branch LAN; its
  own answers to `CMD_GET_VERSION`, `CMD_OPTIONS_RRQ` and `CMD_GET_FREE_SIZES`.
- The attendance log backup the visit took (11 426 records), counted for the record
  format and the code distribution above. The file itself stays on the operator's laptop:
  it carries employee codes and punch times.
- D-274 (the visit's findings and the in/out rule); `devices-agent/workin_devices/zk4370.py`
  for the formats the client can read, which `test_the_inventory_claims_no_record_format_the_client_cannot_read`
  holds against this table.
