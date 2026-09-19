# The On-Premises Agent

A small program on a computer at the branch that reads attendance terminals
which cannot send punches themselves, and delivers those punches to the
platform over HTTPS. Its code is `devices-agent/`; the platform side is
`/api/v1/device-agents` (D-258).

## Which Terminals Need It

| Terminal | How punches reach the platform | Agent needed |
|---|---|---|
| ZKTeco with *Cloud Server Setting* / ADMS | the terminal pushes to the receiver on `APP_DEVICES_DOMAIN` | no |
| ZKTeco without it (port 4370 only: K14, K40, X628, MB20 without ADMS, older iClock) | the agent reads it over 4370 | **yes** |
| Hikvision access terminal | the agent reads its event log over ISAPI | **yes** (no push receiver yet) |
| Any ZKTeco on no network | an operator imports its USB export (`attlog.dat`) | no — dashboard, or the agent's `import-usb` |
| Dahua, Suprema, Anviz | not supported yet | — |

A push-capable ZKTeco can be read by an agent as well. Both copies of a punch
collapse into one row when the terminal pushes wall-clock times, because the
dedup key is then the same whichever path a punch arrives by
(`DeviceAttendanceEvent.dedupKey`), and the row records which path came first
(`device_punches.delivered_via`). A firmware that pushes Unix-seconds
timestamps is keyed by the instant instead, and its pushed and agent-read
copies are two rows -- the site visit records which form a model uses
(field-visit-runbook.md, 5.4 item 4).

## What It Does And Does Not Do

- Every `poll_interval_seconds` (default 60) it opens each configured
  terminal, checks that the serial the terminal reports is the one configured,
  reads its log, and keeps every record in a local SQLite spool.
- It sends what the server has not acknowledged, in batches, and marks a
  record delivered only after the server answers 200. A server outage costs
  nothing: records wait in the spool.
- It sends a heartbeat listing each terminal it tried, whether it reached it,
  and what the terminal said about itself. The dashboard shows it under
  *On-premises agents*, and a serial nobody has allocated appears under
  *Terminals waiting to be allocated*.
- **It never writes to a terminal.** Its ZKTeco client can send only read
  commands (`READ_ONLY_COMMANDS` in `workin_devices/zk4370.py`): no clearing
  the log, no setting the clock, no user or fingerprint changes. Clock skew is
  logged, not corrected.
- **It never reads fingerprints, faces or the user table.** The ZKTeco client
  asks for the attendance log only.
- It submits for a terminal only when the platform has that serial allocated
  to the agent's own company and active. Anything else is answered `404`,
  identically for an unknown serial and another company's.

## Security

- The token is a bearer credential, shown once when issued and stored on the
  server as SHA-256 only. Keep it in its own file readable only by the agent
  (the agent refuses a group- or world-readable token file on Linux). Revoke
  it in the dashboard the moment a branch computer is lost. From its next
  request the agent submits nothing: it logs that the token was refused, stops
  reading terminals, and every 15 minutes re-reads the token file and asks the
  server whether the token is accepted, so a replacement token takes effect
  without restarting it.
- `server_url` must be `https://`. Plain HTTP is accepted only for this
  computer's own address, or with `--allow-plain-http` for a lab on a trusted
  LAN. `insecure_skip_tls_verify` exists for the laptop's self-signed lab edge
  and must stay off at a branch.
- The terminal side of the LAN is not encrypted — ZKTeco's 4370 protocol and
  a Hikvision's plain-HTTP ISAPI never were. The agent belongs on the same LAN
  segment as the terminals, not across the internet from them.

## Install

### 1. Allocate the terminals and issue a token

In the dashboard (`/admin/devices`), as the platform administrator:

1. Allocate each terminal to its company and branch (serial, vendor, name,
   the time zone the terminal is set to).
2. *On-premises agents* → the company → a name such as "Cairo branch PC" →
   *Issue agent token*. Copy the token now; it is not shown again.

The deployment must run with `APP_DEVICES_AGENTS_ENABLED=true`
(`DEVICES_AGENTS_ENABLED=true` in the compose env files).

### 2a. Windows

On a build machine: `devices-agent\packaging\windows\build-exe.ps1` produces
`dist\` with `workin-devices-agent.exe`, `agent.example.toml` and the install
scripts. Copy `dist\` to the branch computer, then in an elevated PowerShell:

```powershell
.\install-agent.ps1
notepad "$env:ProgramData\WorkIn Devices Agent\agent.toml"        # server_url, devices
Set-Content "$env:ProgramData\WorkIn Devices Agent\agent.token" "wda_..."   # the token, one line
& "$env:ProgramFiles\WorkIn Devices Agent\workin-devices-agent.exe" doctor --config "$env:ProgramData\WorkIn Devices Agent\agent.toml"
Start-ScheduledTask -TaskName "WorkIn Devices Agent"
```

The agent runs as a scheduled task at boot, as SYSTEM, restarts itself if it
stops, and logs to `agent.log` in the data folder. The computer must stay on
and on the terminals' LAN; a sleeping computer delivers nothing until it
wakes, and loses nothing either — the terminals keep their logs.
`uninstall-agent.ps1` removes the task and the program and keeps the data
folder, because its spool may hold punches the server has not received.

### 2b. Linux, including a Raspberry Pi

Python 3.11 or later; nothing to install from PyPI.

```bash
sudo useradd --system --home /var/lib/workin-agent --create-home workin-agent
sudo mkdir -p /opt/workin-devices-agent /etc/workin-agent
sudo cp -r devices-agent/workin_devices /opt/workin-devices-agent/
sudo cp devices-agent/agent.example.toml /etc/workin-agent/agent.toml   # edit: server_url, devices,
                                                                        # spool_path = "/var/lib/workin-agent/spool.sqlite3"
printf '%s\n' 'wda_...' | sudo tee /etc/workin-agent/agent.token >/dev/null
sudo chown -R workin-agent: /etc/workin-agent && sudo chmod 600 /etc/workin-agent/agent.token
sudo -u workin-agent env PYTHONPATH=/opt/workin-devices-agent \
  python3 -m workin_devices doctor --config /etc/workin-agent/agent.toml
sudo cp devices-agent/packaging/linux/workin-devices-agent.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now workin-devices-agent
journalctl -u workin-devices-agent -f
```

## Configuration

`devices-agent/agent.example.toml` is the annotated template. The keys:

| Key | Meaning |
|---|---|
| `server_url` | the platform's API address, `https://` |
| `token_file` | the file holding the `wda_` token, relative to the config |
| `spool_path` | the SQLite spool; losing it re-sends everything once, which the server deduplicates |
| `poll_interval_seconds` | seconds between passes, at least 10 |
| `batch_size` | records per request, at most the server's 5,000 cap |
| `in_out_field` | `punch` or `status`: which of a ZKTeco record's two codes is the in/out key (field-visit runbook, step 6) |
| `ca_file`, `insecure_skip_tls_verify` | a private CA for the server, or none for the lab |
| `[[devices]] serial` | exactly as allocated; checked against what the terminal reports |
| `kind` | `zk`, `hikvision` or `file` |
| `host`, `port` | the terminal's LAN address; 4370 for ZKTeco, 80 or 443 for Hikvision |
| `comm_key`, `udp` | ZKTeco Comm Key; UDP for firmware that does not answer over TCP |
| `username`, `password_file`, `https` | Hikvision ISAPI credentials (password in its own file) |
| `window_hours`, `page_size`, `attendance_minors` | Hikvision: how far back each pass reads, page size, which event codes are punches (default card, fingerprint, face) |
| `path` | `file` sources: an ATTLOG-shaped file (the lab uses it) |

## Commands

All run as `python3 -m workin_devices <command>` from `devices-agent/`, or
`workin-devices-agent.exe <command>` on Windows. `--log-file` and `--verbose`
go before the command.

| Command | What it does |
|---|---|
| `run --config agent.toml` | the agent: poll, deliver, heartbeat, forever |
| `once --config agent.toml` | one pass, prints per-terminal counts, exits non-zero if any terminal failed |
| `doctor --config agent.toml` | reads every terminal and prints what it is and its last records; sends nothing |
| `import-usb --config agent.toml --serial SN --file attlog.dat` | imports a USB export for an allocated terminal, marked as a file |
| `scan --cidr 192.168.1.0/24` | finds terminals on a LAN (ask first) |
| `zk-info --host IP [--comm-key N] [--udp] [--backup FILE]` | a ZKTeco terminal's identity, counts and clock; optionally its whole log to a local file |
| `hik-info --host IP --username U --password-file F [--dump FILE]` | a Hikvision terminal's identity and its event codes; `--dump` writes each event as structure only (codes, times, in/out; no name, employee, card or picture) |
| `capture --listen 0.0.0.0:8081 [--upstream URL --host-header NAME]` | the site-visit recorder (field-visit runbook, step 5). It forwards every upload unchanged, but writes and prints only what the platform keeps, in the shape the platform parses: `ATTLOG` lines, `OPTIONS` pairs, `OPLOG` lines and command results, each field bounded (an unparsed attendance line is written as its shape, digits as `9` and letters as `a`). Other brands' JSON and XML are written as structure only -- field names, value types and lengths, event and status codes (a number under any other key, such as `pin` or `id`, is written as `<number>`), timestamps, and in/out and verify-mode enumerations. Templates, pictures, names, card numbers, employee codes, enrolment and ID-card records are withheld; the request's `.json` records their kind and size |
| `visit [--out DIR]` | the whole site visit, guided, in Egyptian Arabic, every question answered with a number (field-visit runbook, the quick path at the top). It asks before it scans, backs up a ZKTeco's log, runs the recorder in front of the lab receiver, allocates the terminal in the lab (`scripts/devices-lab.sh allocate`, with the time zone the operator reads off the terminal), times the push tests, finds which record code carries in/out from a check-out punch, sends through the agent twice, reads a Hikvision's event codes, imports a USB export, deletes the Hikvision password file, and writes `field-report/visit-SN-DATE.md`: the runbook's results sheet filled in and every problem with its fix, with no employee code, name or card number. Mode A (the lab) only: it has no path to production, which stays the runbook's section 12 and the repository owner's |
| `sim-zk`, `sim-push`, `sim-hik`, `sim-usb` | lab simulators ([devices-lab.md](devices-lab.md)) |

## When It Is Not Working

| The log or `once` says | Meaning | Do |
|---|---|---|
| `unauthorized` | token wrong or revoked | issue a new token and replace the token file |
| `not registered` | the serial is not allocated to this company, or is deactivated | allocate or reactivate it in the dashboard |
| `configured as X but the terminal reports Y` | the config's `host` points at a different terminal | fix `serial` or `host` |
| `the terminal refused the communication key` | Comm Key wrong | set `comm_key` |
| `did not answer in time` | terminal off, wrong IP, or UDP-only | check the address; try `udp = true` |
| `retry: server answered 503` or `unreachable` | platform or internet down | nothing: records wait in the spool |
| `terminal clock is +N seconds` | the terminal's clock drifts | fix the clock on the terminal with the customer |
| a punch appears twice in the dashboard | the terminal pushes Unix-seconds times, or `in_out_field` differs from the terminal's push | do not send again: changing `in_out_field` re-sends the whole log under new keys. Settle the mapping with `doctor` first (field-visit runbook, 6.3), and on a production database leave the duplicates for the owner to decide |
