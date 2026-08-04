# Device Discovery

Use this area for attendance-device vendor analysis, protocol notes, certification checklists, and push, polling, or API integration evidence.

## Documents

- `attendance-device-model-and-firmware-inventory.md`,
  `vendor-capability-matrix.md` — real vendor/hardware Discovery
  templates. Correctly empty as of 2026-08-04: no vendor/hardware
  access exists yet (PMR-04). Do not fill these in without real
  evidence.
- `device-integration-architecture.md` — added 2026-08-04. Everything
  that can be designed vendor-agnostically *without* hardware access:
  adapter/SPI pattern, event ingestion contract, idempotency, retry/
  offline-sync behavior, device authentication model, mock
  simulator, and a test-scenario checklist prepared for when real
  device access arrives. Hardware access blocks only final device
  validation, not this architecture or the rest of backend
  implementation.
