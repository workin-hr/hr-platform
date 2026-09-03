# Vendor Capability Matrix

Evidence levels used below: **documentation** (vendor documents and
independent implementations, read 2026-09-02, listed in
`../superpowers/specs/2026-09-02-attendance-device-ingestion-design.md` §1.2)
and **hardware-verified** (observed on a real terminal). No entry is
hardware-verified yet; the verification checklist is that specification's
§4.3.

## ZKTeco — attendance terminals, all models in use

Vendor named directly by the product/business owner on 2026-08-05
(ADR-0006 Part B; `../bootstrap/decision-log.md` D-025).

### Vendor

ZKTeco (`https://www.zkteco.com`). OEM derivatives that speak the same
protocol are known to exist (eSSL, FingerTec); none is confirmed in use.

### Push Support

**Yes — documentation.** The ADMS / PUSH SDK protocol: the terminal is given
a server address and port under `Comm -> Cloud Server Setting` and then
initiates all traffic itself over HTTP — a handshake
(`GET /iclock/cdata?SN=...&options=all&pushver=...`), real-time attendance
upload (`POST /iclock/cdata?SN=...&table=ATTLOG&Stamp=...`, tab-separated
`PIN`, timestamp, status, verify, workcode), command polling
(`GET /iclock/getrequest?SN=...`) and command acknowledgement
(`POST /iclock/devicecmd`). Records are buffered on the device while the
server is unreachable and re-sent from the last acknowledged stamp. Whether a
given model exposes the menu, and whether it offers HTTPS, is per-model and
not yet observed.

### Polling Support

**Yes — documentation, not recommended.** A proprietary TCP/UDP protocol on
port 4370, reverse-engineered by `pyzk`, `zklib` and the community
`zk-protocol` specification, plus the vendor's Windows COM SDK. The server
must reach the device's IP, which behind branch NAT means a static IP and
port-forward, a VPN or an on-site agent per branch. Real-time capture is
reported unreliable across models. Authentication is a `COMKey` integer,
default `0`, keyspace 0–999999.

### API Support

**Vendor-cloud API: not applicable to this design.** ZKTeco's own
middleware (ZKBio Time / BioTime) exposes REST APIs, but it is a separate
licensed product built on the same ADMS protocol this platform would receive
directly. No customer is known to run it.

### Local Gateway Need

**Probably not required — documentation** (D-164, accepted 2026-09-02). For
ADMS-capable terminals, the device-initiated push model needs no local
component. A gateway remains the fallback for terminals that do not expose
the Cloud Server Setting. Tie-breaker is the §4.3 hardware check, not
preference.

### Notes

- Wire security is weak by default: plain HTTP, serial-number-only identity,
  and terminal firmware with documented injection and server-impersonation
  vulnerabilities (Kaspersky, 2024). The platform-side controls are the
  specification's §8: TLS at the edge, claim-before-ingest by serial, closed
  command allow-list, no biometric templates accepted.
- `STATUS` (in/out key) is unreliable in practice; in/out must be inferred
  from punch sequence.
- Timestamps are device-local wall-clock with no offset; the device clock
  drifts.
- No per-record identifier exists; idempotency is a synthesised hash.
- Multi-tenant: a serial number identifies one physical terminal; the
  platform binds it to one company and one branch at claim time. PIN
  uniqueness is per device and, in practice, per company — never global.
- Unanswered for the vendor: HTTPS availability by model/firmware; whether
  ADMS is a paid firmware option on the cheapest models; PIN length limits by
  model.
