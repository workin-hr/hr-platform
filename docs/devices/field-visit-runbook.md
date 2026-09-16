# Field Visit Runbook — Testing Attendance Terminals At A Customer Site

One visit to a customer's branch, with a laptop, turns the device work from
"built against documentation" into "verified against hardware". This is the
order to do it in, what to type, what to look at, and what never to do. It
assumes nothing is known about the terminals beforehand: the brand, the
model and whether they can push are all found out on site.

Everything here has been rehearsed in the lab first
([devices-lab.md](devices-lab.md)); do that rehearsal before leaving. The
agent and its commands are described in [on-prem-agent.md](on-prem-agent.md).
The questions the visit answers are the hardware checklist in
[zkteco-adms-receiver-setup.md](zkteco-adms-receiver-setup.md) §4.

## Rules That Hold For The Whole Visit

A terminal on a customer's wall records their payroll. Nothing done during
the visit may change what it holds or stop it working for them.

- **Never clear, delete or overwrite anything on a terminal** — attendance
  log, users, fingerprints, administrators. The agent cannot: its 4370 client
  has no such command (`READ_ONLY_COMMANDS` in `workin_devices/zk4370.py`).
  Do not use the terminal's own menus for it either.
- **Never change the terminal's date, time or time zone** unless the
  customer asks you to fix a wrong clock, and then write down the old value.
- **Photograph every settings screen before you change it**, and put every
  changed setting back before you leave (step 10).
- **Ask before scanning the customer's network** or plugging into it.
- **Stay away from shift change.** The first and last hour of a shift is
  when every employee punches.
- **The backup files and captures hold employee PINs and punch times.** They
  are personal data: keep them on the laptop, never in the repository, a chat
  or a ticket. Only what step 11 lists goes into the repository.
- **No fingerprint templates, photos or faces are collected.** The receiver
  discards template uploads, and the agent never asks for them.

## 1. The Day Before

### 1.1 Ask the customer

- Which terminals they have, how many, and where.
- The terminal **admin password** or someone who can open the menu, and the
  **Comm Key** if one is set (`Menu → Comm. → Connection → Comm Key`).
- What reads the terminals today: ZKTime.Net / ZKAccess (reads over 4370),
  BioTime / ZKBioSecurity (the terminal pushes to it), a USB stick into Excel,
  or nothing. This decides what you must restore afterwards.
- Permission to plug a laptop into their network, and a network cable run
  or switch port near the terminal.
- A 30-minute window outside shift change, and one employee willing to punch
  a few times.
- If they are a platform customer: which employees are on the terminal, so
  device PINs can be matched to employees.

### 1.2 Prepare the laptop

1. Rehearse: `scripts/devices-lab.sh up && scripts/devices-lab.sh seed &&
   scripts/devices-lab.sh simulate`. Every `[PASS]` line must pass. The images
   are then cached, so the lab starts at the site without internet.
2. Check the kit runs with nothing installed:
   `cd devices-agent && python3 -m workin_devices version` (Python 3.11 or
   later; no packages needed).
3. Allow the capture port through the firewall: `sudo ufw allow 8081/tcp`
   (remove it afterwards).
4. Decide where punches go (section 1.3) and start that stack once to be
   sure it works.
5. Pack: laptop charger, a USB-to-Ethernet adapter, two network cables, a
   small switch, a FAT32 USB stick, a phone hotspot, and this runbook.

### 1.3 Where the punches go

**A. The lab database on the laptop (default).** Nothing leaves the laptop.
Use it for the first visit unless there is a reason not to.

**B. The production database**, through the remote-db stack on the laptop.
Only once the prerequisites in [section 12](#12-mode-b-the-production-database)
are done, and never as a way to skip them.

Both modes run the same code and the same capture proxy; only the upstream
changes.

## 2. Arrive And Photograph

Before touching anything, photograph:

1. The sticker on the back: model, serial number (SN), MAC.
2. `Menu → System Info → Device Info` (or *About*): firmware version,
   platform, and the push/ADMS version if shown.
3. `Menu → Comm. → Ethernet`: IP address, subnet mask, gateway, DNS, DHCP.
4. `Menu → Comm. → Cloud Server Setting` (or *ADMS*): whether it exists, and
   every value on it — **this is what you restore in step 10**.
5. `Menu → System → Date Time`: the time, the time zone, whether it follows
   daylight saving, and whether NTP is on.
6. `Menu → Data Mgt.` (or *Record*): how many attendance records and users
   it holds.

Hikvision terminals show most of this on their web page instead (step 8).

## 3. Join The Network And Find The Terminals

```bash
ip -4 addr            # the laptop's address, e.g. 192.168.1.57/24
ping -c 3 <terminal IP from step 2>
cd devices-agent
python3 -m workin_devices scan --cidr 192.168.1.0/24
```

`scan` probes TCP 4370, 80, 443, 8000, 8080, 37777 and 5010, and ZKTeco over
UDP. For each host it prints what it found, and writes
`field-report/scan-<time>.json`. Read it like this:

| `scan` says | It is | Go to |
|---|---|---|
| `zk_tcp` with `serial`, `firmware` | a ZKTeco terminal reachable over 4370 | step 4 |
| `zk_tcp` with `comm_key_required: true` | the same, with a Comm Key | step 4 with `--comm-key` |
| `zk_udp` | an older ZKTeco that answers only over UDP | step 4 with `--udp` |
| `http_80` guess `hikvision (ISAPI)` | a Hikvision terminal | step 8 |
| `http_80` guess `dahua (CGI)`, or port 37777 | a Dahua terminal | step 9 |
| nothing for the terminal's IP | wrong subnet, or a terminal with networking off | check step 2's photo |

## 4. ZKTeco: Read It And Back Up Its Log

Do this first, for every ZKTeco terminal that answers on 4370, **before any
setting is changed**. It is read-only, and the backup is the customer's
attendance log on your laptop in case anything later goes wrong.

```bash
python3 -m workin_devices zk-info --host 192.168.1.201 \
  --backup field-report/<SN>-attlog-backup.tsv
# add --comm-key 12345 if asked; add --udp if TCP does not answer
```

Check, and write down:

- `serial` matches the sticker.
- `records` matches the count on the terminal's screen, and the backup file
  has that many lines (minus its header).
- `device_time` against `laptop_time`: the difference is the terminal's clock
  skew. More than a few minutes is a finding.
- The last lines of the backup against the terminal's own attendance query
  (`Menu → Attendance Search`) for one employee: same PIN, same times.

## 5. ZKTeco That Can Push: The Receiver

Only if step 2 found a *Cloud Server Setting* / *ADMS* screen.

### 5.1 Start the receiver on the laptop

Mode A (lab):

```bash
scripts/devices-lab.sh up                       # already built at home: starts in seconds
cd devices-agent
python3 -m workin_devices capture --listen 0.0.0.0:8081 \
  --upstream http://127.0.0.1:18080 --host-header devices.localhost \
  --out field-report/captures
```

Mode B: the same `capture` command with `--upstream http://127.0.0.1:80`
(section 12).

`capture` prints the address to type into the terminal, forwards the
terminal's `/iclock` requests to the receiver under the hostname the receiver
answers on (any other path gets `404` and never reaches the platform), and
keeps every byte both ways under `field-report/captures/<SN>/` -- except
`Authorization` and `Cookie` headers, which it never writes down. The
receiver sees the laptop, not the terminal, as the sender, so the dashboard's
*address* column reads the laptop's; the capture files keep the terminal's. If the stack is
down it answers the terminal `502`, never `OK`, so the terminal keeps its
records and retries. Open the dashboard beside it:
`https://localhost:18443/admin/devices?live=1` (mode A).

### 5.2 Point the terminal at the laptop

On `Cloud Server Setting`, with step 2's photo taken:

1. Server mode **ADMS**; **Enable Domain Name** off.
2. **Server Address** = the laptop's IP; **Server Port** = `8081`.
3. **HTTPS** off, **Proxy** off. Save. Reboot the terminal if it asks.

Within a minute `capture` shows `NEW [<SN>] GET /iclock/cdata?...options=all`,
and the dashboard lists the serial under *Terminals waiting to be allocated*.
**The terminal is not allocated yet, and that is deliberate:** until it is,
the receiver sends it no time zone and refuses its uploads with `403`, so it
keeps every record and nothing about it changes.

Nothing in 60 seconds: the laptop firewall (`sudo ufw status`), a different
subnet, or a firmware that needs a reboot to apply the setting.

### 5.3 Allocate it — with the terminal's own time zone

**Read this before allocating.** Once allocated, the handshake carries
`TimeZone=<hours>`, and some firmware apply it to their clock. Allocate with
the zone the terminal is **already** set to (step 2, photo 5):

- set to a fixed offset such as +02:00 → `+02:00`
- following Egypt's daylight saving → `Africa/Cairo`
- unsure → `+02:00` or `+03:00`, matching the time the terminal shows now
  against the laptop's clock

In the dashboard: *Allocate to a branch* → serial, vendor ZKTeco, a name,
that zone, the customer's branch (mode A: any lab branch). After allocating,
`capture` shows the next handshake carrying `TimeZone=` and then
`POST /iclock/cdata?...table=ATTLOG` bodies: the terminal uploading its log.
The receiver asks for everything (`ATTLOGStamp=0`), so the first upload is
the whole history.

### 5.4 What to check, and how

Each line answers one item of the hardware checklist (§4 of
[zkteco-adms-receiver-setup.md](zkteco-adms-receiver-setup.md)). Record the
answer as you go.

| # | Do | Look at | Record |
|---|---|---|---|
| 1 | nothing more | the handshake arrived at all | Cloud Server / ADMS exists; accepts IP (and domain name, if you try one) |
| 2 | look at the menu | whether HTTPS can be enabled | HTTPS offered yes/no |
| 3 | nothing | `captures/<SN>/*-POST.json`, `request_headers.Content-Type` | the Content-Type the firmware sends |
| 4 | nothing | `*.request.bin` of an ATTLOG upload: `2026-09-16 08:01:02` or a 10-digit number | wall clock or epoch |
| 5 | nothing | the handshake URL: `pushver=`, `DeviceType=`, `language=`, `PushOptionsFlag=` | each value |
| 6 | nothing | the first field of each ATTLOG line | PIN format and longest length |
| 7 | unplug the terminal's cable, have the employee punch twice, plug it back | dashboard: the two punches arrive with the times they were made | offline buffering yes/no, and how long until they arrived |
| 8 | stop `capture` (Ctrl-C), punch once, wait 1 minute, start `capture` again | the punch arrives after restart; `capture` shows the retry | retry after a failed delivery yes/no, and after how long |
| 9 | nothing | `Stamp=` on ATTLOG uploads | how the stamp is encoded |
| 10 | nothing | the largest ATTLOG upload: `request_bytes` and its line count; any `413` | records per upload; whether the 5,000 cap was ever hit |

Then with the employee:

- Punch normally → a row appears in the dashboard within seconds
  (`Realtime=1`): PIN, time, `RECEIVED` if the PIN matched an employee or
  `UNMATCHED` if not, *delivered via* `PUSH`.
- Punch with the **Check-Out** key (or *F2*) → the *state / verification*
  column changes its first number. Note which number means in and which out.
- Punch twice within ten seconds → both stored; the second may carry a review
  flag later (pairing debounces, it never rejects).
- Compare three punches with the terminal's own attendance query.

A `413` repeated for the same upload in `capture` means the firmware does not
split its batch: stop, write it down (checklist item 10), and point the
terminal back (step 10) — the receiver's cap needs raising before this model
can push.

## 6. ZKTeco Over 4370: The Agent

For every ZKTeco terminal that answered on 4370 — old ones that cannot push,
and push-capable ones too, because reading the same terminal both ways is
what proves the two paths agree.

1. Issue a token: dashboard → *On-premises agents* → company → name →
   *Issue agent token*. Copy it once into `field-report/agent.token`, then
   `chmod 600 field-report/agent.token`. (Mode A can reuse `lab/agent.token`
   from `devices-lab.sh seed`.)
2. Allocate the terminal first (5.3) if it is not already — the agent can
   only submit for a terminal allocated to its own company.
3. Write `field-report/agent.toml`:

   ```toml
   server_url = "https://localhost:18443"   # mode A; mode B: "https://localhost"
   token_file = "agent.token"
   spool_path = "spool.sqlite3"
   insecure_skip_tls_verify = true          # the laptop's own self-signed edge only
   in_out_field = "punch"

   [[devices]]
   serial = "<SN from the sticker>"
   kind = "zk"
   host = "192.168.1.201"
   comm_key = 0          # the Comm Key, if any
   udp = false           # true if step 4 needed --udp
   ```

4. Run, in this order:

   ```bash
   python3 -m workin_devices doctor --config field-report/agent.toml
   python3 -m workin_devices once   --config field-report/agent.toml
   python3 -m workin_devices once   --config field-report/agent.toml   # second pass
   ```

   - `doctor` prints the serial the terminal reports (a mismatch means the
     config names the wrong terminal — nothing is ever submitted then), its
     counts and clock, and its last three records with `in/out=`. **Compare
     `in/out=` with the Check-Out test from 5.4.** If the Check-Out punch does
     not show `1`, set `in_out_field = "status"` and run `doctor` again.
   - The first `once` stores the whole log; the second stores nothing.
   - On a terminal that also pushed in step 5, the agent's records arrive as
     *duplicates*, and the dashboard shows each punch once, *delivered via*
     `PUSH`. If instead every punch appears twice, the in/out mapping is
     wrong: that is the finding, and `in_out_field` is the fix.

## 7. The USB Export

For every ZKTeco terminal, including ones on no network.

1. On the terminal: `Menu → USB Manager → Download → Attendance Data`, to the
   FAT32 stick. It writes `<number>_attlog.dat` or `attlog.dat`.
2. Copy it to `field-report/` and keep the original untouched.
3. Import it against the allocated terminal:

   ```bash
   python3 -m workin_devices import-usb --config field-report/agent.toml \
     --serial <SN> --file field-report/1_attlog.dat
   ```

   (Up to 1 MB also works from the dashboard: the device's page → *Import a
   USB export*.)
4. Read the totals. For a terminal already read in steps 5 or 6, a correct
   import stores almost nothing and reports the rest as `duplicates`. If
   instead most lines are `stored`, the export orders its columns differently
   from a push: note it, and send the file's first three lines (PINs replaced)
   with the report. Any `malformed` lines are visible on the device's page.

## 8. Hikvision

1. The terminal's web page (`http://<IP>`) needs the admin user and password;
   ask for them. Photograph `Configuration → Network → Advanced → HTTP
   Listening` before changing anything.
2. Read it:

   ```bash
   printf '%s' '<password>' > field-report/hik.pw && chmod 600 field-report/hik.pw
   python3 -m workin_devices hik-info --host 192.168.1.64 --username admin \
     --password-file field-report/hik.pw --days 7 --dump field-report/hik-events.json
   ```

   `events_with_employee_by_minor` lists the event codes that name an
   employee. The agent counts `1` (card), `38` (fingerprint) and `75` (face)
   as attendance. Compare with the terminal's own event search for a known
   punch; a code that is a real punch but missing from that list is a finding,
   and `attendance_minors` in the agent config is where it goes.
3. Allocate it in the dashboard with vendor **Hikvision** and the serial
   `hik-info` printed, then add it to `agent.toml`:

   ```toml
   [[devices]]
   serial = "<serial from hik-info>"
   kind = "hikvision"
   host = "192.168.1.64"
   username = "admin"
   password_file = "hik.pw"
   ```

   and run `doctor` and `once` as in step 6.
4. Optional: record what it pushes. Set *HTTP Listening* to the laptop's IP,
   port `8081`, URL `/hik/<serial>`, and run `capture` **without**
   `--upstream` (there is no Hikvision push receiver yet; this is evidence for
   building one). Standalone, `capture` answers every upload `503`, so the
   terminal keeps each event queued for whatever else it reports to. Restore the
   setting in step 10.

A PIN containing letters is refused by the receiver as unreadable — note the
`employeeNoString` format if you see one.

## 9. Anything Else

Dahua, Suprema, Anviz or an unknown brand: photograph step 2's screens, keep
the `scan` output, and if it has any "server" or "cloud" setting, run
`capture` standalone and point it there. No adapter exists yet; the evidence
is what makes one possible.

## 10. Before Leaving: Put Everything Back

1. On every terminal, restore `Cloud Server Setting` (and Hikvision's *HTTP
   Listening*) to step 2's photographs — or off, if it was off.
2. If the customer's software reads the terminals, ask them to confirm it
   still does: a punch made now appears in it.
3. Stop `capture` and the stack. `sudo ufw delete allow 8081/tcp`.
4. Leave nothing plugged in that was not there.

What happened on the platform side stays: allocated terminals and their
punches. In mode A that is the laptop's lab database. In mode B, deactivate
each allocated terminal in the dashboard (the device's page → *Deactivate
device*) unless it is meant to stay connected — it keeps its punches and
accepts nothing more.

## 11. After The Visit

- Copy `field-report/` somewhere private. **Not into the repository.**
- Fill in, per model, **without PINs or names**:
  [attendance-device-model-and-firmware-inventory.md](attendance-device-model-and-firmware-inventory.md)
  and [vendor-capability-matrix.md](vendor-capability-matrix.md), from the
  table in 5.4 and the notes from steps 4–8.
- Open an issue with the device-compatibility template
  (`.github/ISSUE_TEMPLATE/device-compatibility-finding.yml`) for each
  finding: a `FAIL`, a `413`, a wrong in/out mapping, a USB column order, a
  Hikvision event code.
- Captures become regression fixtures only after every PIN and name in them
  is replaced.

## 12. Mode B: The Production Database

The punches go into the production database, the dashboard shows production
data, and nothing on the laptop is the source of truth. What must already be
true, in this order:

1. **The code is merged**, and the laptop runs that commit. Production is not
   provisioned from a branch.
2. **The Phase 1 tables exist on production at the fifteen-table shape.** An
   operator runs `verify_phase1_tables.sql` (read-only) and follows its
   verdict: `not applied` → the full procedure in
   [provisioning-phase1-tables.md](../operations/provisioning-phase1-tables.md);
   `PRE-AGENTS` → `upgrade_device_agents_and_delivery.sql`; `applied` → nothing.
   That runbook takes a backup first. This is a production schema change, so
   it is the repository owner's to run or to authorise explicitly — an agent
   session never runs it.
3. **The remote-db stack** ([running-the-backend](../operations/running-the-backend.md),
   `deploy/env.remote-db.example`) with three more lines in `.env.remote-db`:

   ```bash
   DEVICES_INGEST_ENABLED=true
   DEVICES_AGENTS_ENABLED=true
   APP_DEVICES_DOMAIN=devices.localhost
   ```

   ```bash
   cd deploy
   docker compose -f compose.remote-db.yaml -f compose.tls.yaml -f compose.field-loopback.yaml \
     --env-file .env.remote-db up -d --build
   ```

   **`compose.field-loopback.yaml` is not optional on a customer's network.**
   Without it the edge publishes 80 and 443 on every interface, and anyone on
   the customer's LAN reaches the production sign-in and API through the
   laptop; Docker's published ports bypass `ufw`. With it only the laptop can
   reach them, and terminals reach the receiver through `capture`, which
   forwards `/iclock` and nothing else. That stack's own header applies
   unchanged: its admin actions are on, so the PHP admin panel must not be used
   at the same time.
4. **Internet at the site** — the laptop reaches the database host over the
   phone hotspot while its cable is on the customer's LAN.
5. **The customer's company and branch exist** on production. If they are
   not a platform customer, stay in mode A.

Then steps 5–8 are unchanged except for two values: `capture --upstream
http://127.0.0.1:80`, and the agent's `server_url = "https://localhost"`.

**What mode B writes to production:** the terminal's registry row and its
assignment history, unclaimed sightings, `device_punches` (and any unreadable
or operation-log lines), the agent row, and the admin audit rows. **What it
does not write:** attendance. Punch-to-attendance pairing has no caller in the
application yet, so payroll and the attendance pages are unaffected; punches
stay `RECEIVED` or `UNMATCHED` until pairing is switched on by its own change.

**Afterwards:** set `DEVICES_INGEST_ENABLED=false` and
`DEVICES_AGENTS_ENABLED=false` again, or stop the stack; deactivate the
allocated terminals (step 10). A terminal still pointed at the laptop after
that is refused, keeps its records, and loses nothing.

## 13. When Something Goes Wrong

| Symptom | Likely cause | Do |
|---|---|---|
| `capture` shows nothing | firewall, subnet, setting not applied | `sudo ufw status`; ping the laptop from another device; reboot the terminal |
| `capture` shows `502 UPSTREAM ERROR` | the stack is down | `scripts/devices-lab.sh up` (mode A) or `docker compose ... ps` (mode B); the terminal keeps its records meanwhile |
| uploads answered `403` | not allocated, or deactivated | allocate it (5.3) |
| uploads answered `413` again and again | batch above 5,000 records | a finding: restore the terminal (step 10) |
| punches `UNMATCHED` | the PIN is no employee's `employee_code` | bind PINs to employees (`PUT /api/v1/devices/identities`) or note the mapping |
| punches on the wrong day or hour | terminal clock or zone | compare `zk-info`'s two clocks; check the allocation zone against step 2's photo |
| `zk-info`: `comm_key_required` | a Comm Key is set | ask for it; `--comm-key` |
| `zk-info`: `did not answer in time` | UDP-only firmware, or a busy terminal | `--udp`; retry outside shift change |
| agent: `SERIAL MISMATCH` | the config names another terminal | fix `serial` or `host` — it will not submit until they agree |
| agent: `not registered` | not allocated to the token's company | allocate it to that company |
| agent: `unauthorized` | token wrong or revoked | issue a new one |
| every punch appears twice | in/out mapping differs between push and agent | `in_out_field = "status"`, and record it |
