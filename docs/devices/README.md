# Device Discovery

Use this area for attendance-device vendor analysis, protocol notes, certification checklists, and push, polling, or API integration evidence.

## Documents

- `device-integration-architecture.md` — added 2026-08-04. The
  vendor-neutral adapter/SPI pattern, event ingestion contract, idempotency,
  retry/offline-sync behaviour, device authentication model, mock simulator
  and test-scenario checklist. ADR-0006 Part A (Accepted, D-023).
- `vendor-capability-matrix.md` — populated 2026-09-02 for ZKTeco from
  vendor documentation and independent implementations (evidence level
  marked per field). Not hardware-verified.
- `attendance-device-model-and-firmware-inventory.md` — populated
  2026-09-02 with what is known (vendor, current manual pattern, network
  constraints). **One model is now hardware-verified**: a ZKTeco MB20/ID on
  `Ver 6.60 Oct 12 2021`, read on a 2026-09-21 site visit and recorded as the
  document's second entry (D-274). The rest of the fleet's models and firmware
  remain `Not yet discovered` until each terminal is read.
- `zkteco-adms-receiver-setup.md` — operator steps for the pilot: point a
  terminal at the receiver, claim it, verify punches, and the hardware
  checklist that fills the two documents above.
- `../superpowers/specs/2026-09-02-attendance-device-ingestion-design.md`
  — the full design for the ZKTeco ADMS push receiver across branches,
  including the hardware checklist (§4.3) that turns documentation evidence
  into hardware evidence and the decisions the repository owner still has to
  make (§12). Resolved ADR-0006 Part B (D-164, accepted 2026-09-02).

- `field-visit-runbook.md` — added 2026-09-16. Testing real terminals at a
  customer site: what to photograph, the read-only backup, pointing a
  terminal at a laptop through the recorder, the agent, the USB export,
  Hikvision, what to check against the hardware checklist, restoring
  everything, and the production-database mode's prerequisites.
- `on-prem-agent.md` — added 2026-09-16. The agent that reads terminals which
  cannot push (ZKTeco over 4370, Hikvision over ISAPI): what it does and
  refuses to do, installing it on Windows and Linux, its configuration and
  commands (D-258).
- `devices-lab.md` — added 2026-09-16. Every terminal path simulated on one
  laptop against the sanitised seed: `scripts/devices-lab.sh`.

## Status

Hardware access still blocks final validation only. It does not block the
vendor-neutral core, which ADR-0006 Part A already authorises, nor building
the ZKTeco adapter against the documented protocol, which D-164 (accepted
2026-09-02) authorises; it blocks declaring the adapter verified.

As of 2026-09-16 (D-258) every path a punch can take has code and a simulated
end-to-end run: a ZKTeco push, a ZKTeco read over 4370 by the on-premises
agent, a Hikvision read over ISAPI by the agent, and a USB export imported
from the dashboard or the agent. None has met real hardware. The first site
visit ([field-visit-runbook.md](field-visit-runbook.md)) is what fills the
inventory and the capability matrix, and what may overturn an assumption the
simulators share with the code: the in/out code mapping, the USB export's
column order, Hikvision's attendance event codes, and how a firmware batches
its uploads.
