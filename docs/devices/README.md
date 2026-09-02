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
  constraints); model and firmware remain `Not yet discovered` until a real
  terminal connects.
- `zkteco-adms-receiver-setup.md` — operator steps for the pilot: point a
  terminal at the receiver, claim it, verify punches, and the hardware
  checklist that fills the two documents above.
- `../superpowers/specs/2026-09-02-attendance-device-ingestion-design.md`
  — the full design for the ZKTeco ADMS push receiver across branches,
  including the hardware checklist (§4.3) that turns documentation evidence
  into hardware evidence and the decisions the repository owner still has to
  make (§12). Resolved ADR-0006 Part B (D-156, accepted 2026-09-02).

## Status

Hardware access still blocks final validation only. It does not block the
vendor-neutral core, which ADR-0006 Part A already authorises, nor building
the ZKTeco adapter against the documented protocol, which D-156 (accepted
2026-09-02) authorises; it blocks declaring the adapter verified.
