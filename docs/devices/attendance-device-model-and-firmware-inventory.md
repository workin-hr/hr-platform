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
- **Decided (D-158, accepted 2026-09-02):** push callback — device-initiated ADMS /
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
- Hardware evidence: none yet — this is what closes `workin-hr/hr-platform#12`.
