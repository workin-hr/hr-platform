# ZKTeco Terminal Setup — Pointing A Device At The Receiver

Operator steps for the pilot (D-158, Slice A). The receiver's design and
protocol are in
`../superpowers/specs/2026-09-02-attendance-device-ingestion-design.md`; the
routes are in `../api/device-endpoints.md`. Nothing here is hardware-verified
yet — the first real terminal is what fills in §4 below.

## 0. Before this is enabled for a real customer

Three gates, all set by D-159, none of which the pilot satisfies by itself:

- The **§4 hardware checklist below has been executed on a real customer
  terminal** of that model, and its answers recorded in the two
  `docs/devices/` documents. Until then the adapter is built against
  documented protocol, not verified against hardware, and must not be
  described as verified.
- The **Phase-1-owned tables have an approved provisioning mechanism** on the
  production database (**R-023**) — solved deliberately, not discovered
  during the change.
- **Device ownership is established by platform staff**, not claimed by a
  tenant admin from a serial number (**R-041**), and an audited
  unclaim/transfer/replace path exists. The tenant claim flow in §3 is a
  pilot arrangement.

## 1. Platform prerequisites

- The deployment sets `APP_DEVICES_INGEST_ENABLED=true`
  (`app.devices.ingest.enabled`). Without it there is no `/iclock` surface.
- A public hostname for devices (recommended: a dedicated one, e.g.
  `devices.<platform-host>`), TLS terminated at the edge, forwarding
  `/iclock/**` to the application. Per-IP rate limiting belongs at this edge
  for the pilot.
- The five Phase-1 tables from
  `backend/src/test/resources/legacy/phase1_extensions.schema.sql` exist on
  the target MariaDB — the same provisioning gate `legacy_refresh_tokens`
  sits behind (ADR-0013 open question; specification §12 Q7).

## 2. On the terminal

Menu names vary by model and firmware; the setting is the one that holds a
server address and port.

1. `Menu -> Comm. -> Cloud Server Setting` (some firmwares: *ADMS*).
2. Enable **Domain Name** and enter the platform hostname; or enter the IP.
3. **Server Port**: `443` for HTTPS, or the port the edge listens on.
4. **Enable HTTPS** where the firmware offers it. Record whether it does.
5. Leave **Proxy** off unless the branch requires one.
6. Save, then reboot the terminal so it performs the handshake.
7. Note the serial number from the sticker or `Menu -> System Info ->
   Device Info`.

## 3. On the platform

Use a `company_admin` or `hr` session's bearer token for the company that
owns the branch.

1. Confirm the terminal has reached the receiver:
   `GET /api/v1/devices/unclaimed?serial_number=<SN>` — `seen: true` means the
   network path works. `seen: false` after a reboot means the device cannot
   reach the hostname: check branch DNS, outbound firewall, and the port. The
   answer is deliberately just `seen` and `claimed`; a device already
   registered to another company reads the same as one never seen, so it
   cannot be used to discover other tenants' hardware.
2. Claim it: `POST /api/v1/devices` with
   `{"serial_number": "<SN>", "branch_id": <branch>, "name": "Gate 1",
   "device_time_zone": "Africa/Cairo"}`. The zone must be the one the
   terminal's clock is set to, and must be a whole-hour offset — a
   half-hour zone (India, Iran) is refused, because the handshake can only
   express whole hours and the terminal's clock would end up thirty minutes
   out. Record it on the checklist below if you meet one.
3. Punch on the device. Within seconds:
   `GET /api/v1/devices/punches?device_id=<id>` shows the punch with
   `processing_state` `RECEIVED` (PIN resolved) or `UNMATCHED`.
4. For `UNMATCHED` PINs, bind them:
   `PUT /api/v1/devices/identities` with `{"employee_id": <id>, "pin": "<PIN>"}`.
   Until bound, a PIN resolves through `employees.employee_code`.

Punches made before the claim are not lost: the terminal received `403` for
them, kept them, and re-sends after its retry delay once the claim exists.

## 4. Hardware confirmation checklist (specification §4.3)

Fill `attendance-device-model-and-firmware-inventory.md` and
`vendor-capability-matrix.md` with the answers; this is what turns
documentation evidence into hardware evidence.

- Cloud Server Setting present; accepts a domain name.
- What the `TimeZone` handshake field accepts — whole hours only, or minutes
  (this decides whether half-hour zones can ever be supported).
- The content type the device uses for its uploads.
- Whether timestamps arrive as wall clock or Unix seconds.
- How the firmware encodes `ATTLOGStamp` (the receiver never echoes it today
  and always asks for a full re-send; a trusted bookmark needs this).
- How many records the device puts in one upload, against the 5000-record cap.
- HTTPS offered; TLS versions that succeed.
- Handshake query string as logged by the receiver (`pushver`,
  `DeviceType`, `language`, `PushOptionsFlag`).
- ATTLOG field count and separator; whether employees ever press the in/out
  state key; PIN length in use.
- Receiver stopped for ten minutes: buffered records arrive on reconnect with
  their original timestamps.
- Acknowledgement dropped: the same batch is re-sent and stored once.
- Clock source (NTP or manual) and skew against the receiver.

## 5. Troubleshooting

| Symptom | Meaning | Action |
|---|---|---|
| Device never appears in the unclaimed lookup | It is not reaching the hostname | Branch DNS, outbound firewall, port; try the IP form |
| Appears unclaimed, punches not stored | Not claimed, or deactivated | Claim it, or `PATCH` `is_active: true` |
| Punches `UNMATCHED` | PIN bound to nobody, and no **active** employee has that `employee_code` — including a departed employee whose badge is still in use | Bind the PIN, or investigate the badge |
| Punch times off by whole hours | Wrong `device_time_zone` or wrong device clock | Fix the zone on the device row; set the device clock |
| Receiver logs "uploaded table FINGERTMP ... discarded" | Firmware ignored `TransFlag` | Expected; nothing stored. Record the firmware |
| `last_seen_at` stale | Device offline or power-cycled | Site check; the terminal is buffering |
| Claiming answers `409`, and the device is not in your list | The serial is registered to another company — either a genuine mix-up or a squatted claim (**R-041**) | There is **no unclaim or transfer path**: deactivating stops ingestion, but correcting the owner needs a database change. Escalate; do not re-issue the serial |
