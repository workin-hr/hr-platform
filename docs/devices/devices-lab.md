# The Devices Lab — Every Terminal Path, Simulated, On One Laptop

The whole device flow with no hardware: a push terminal, an older ZKTeco read
over 4370, a Hikvision, a USB export, the agent, the receiver and the
dashboard, against the sanitised local seed. It is the rehearsal for a site
visit ([field-visit-runbook.md](field-visit-runbook.md)) and the quickest way to
see what a change does.

The simulators are built from the protocols, not from captures of real
firmware. The lab proves that the platform, the agent and the kit agree with
each other and with the protocol as documented; only a real terminal proves
that a model behaves that way.

## Run It

Docker, and Python 3.11 or later. From the repository root:

```bash
scripts/devices-lab.sh up         # first time: builds the backend and loads the seed (several minutes)
scripts/devices-lab.sh seed       # allocates the lab terminals, binds PINs, issues a lab agent token
scripts/devices-lab.sh simulate   # runs every simulator and the agent, then prints a summary
```

Then open `https://localhost:18443/admin/devices?live=1` (accept the local
certificate; password `devpassword`). `scripts/devices-lab.sh status` prints
the same summary again; `scripts/devices-lab.sh down` stops the lab and
`down --wipe` deletes its database. `scripts/devices-lab.sh allocate SERIAL
VENDOR ZONE` allocates a real terminal to the lab branch the way the
dashboard's *Allocate to a branch* does (the registry row, its first history
row from now, the waiting-list entry removed, in one transaction); the site
visit's `visit` command uses it.

The lab is its own compose project (`workin-devices-lab`) on its own ports —
18443 for the dashboard and API, 18080 for the receiver, 13316 for MariaDB —
so it runs beside other stacks. It never connects to production.

## What `simulate` Does, And What A Correct Run Prints

| Step | Simulates | Expect |
|---|---|---|
| 1 | an allocated ZKTeco push terminal (`SIM-PUSH-001`): handshake, self-description, a live punch, a re-sent punch, a 300-punch backlog, unreadable lines, a form-encoded body, an operation log with a fingerprint template, and a 6,000-line upload | nine `[PASS]` lines; the last is a `413`, because the receiver refuses a batch above 5,000 records whole |
| 2 | a push terminal nobody has allocated | `[PASS] unregistered`: no time zone in the handshake, the upload refused with `403`, and the serial listed under *Terminals waiting to be allocated* |
| 3 | an older ZKTeco on 4370 (`SIM-ZK4370-001`) and a Hikvision (`SIM-HIK-001`), read by the agent | `doctor` shows both terminals; the first `once` stores every record; the second stores nothing |
| 4 | a USB export (`lab/1_attlog.dat`) imported through the agent | `stored` equals `lines` |

The summary then lists punches per terminal, and should read `PUSH` for the
push terminal, `AGENT` for the 4370 terminal and the Hikvision, and `FILE` for
the USB import, all `RECEIVED`: `seed` bound PINs 1001–1005 to five of the lab
branch's employees, because the seed's sanitised employee codes (`E000123`)
are not PINs a terminal can hold.

## Rehearsing The Site Visit

The runbook's path through the recorder, with a simulator as the terminal:

```bash
cd devices-agent
python3 -m workin_devices capture --listen 127.0.0.1:8081 \
  --upstream http://127.0.0.1:18080 --host-header devices.localhost --out lab/captures &
python3 -m workin_devices sim-push --server http://127.0.0.1:8081 --serial SIM-REHEARSAL-1 --scenario unregistered
# allocate SIM-REHEARSAL-1 in the dashboard, then:
python3 -m workin_devices sim-push --server http://127.0.0.1:8081 --serial SIM-REHEARSAL-1 --live 20 --every 3
```

Or rehearse the guided visit itself against the 4370 simulator: start
`python3 -m workin_devices sim-zk --port 14370 --serial SIM-REHEARSAL-2`, run
`python3 -m workin_devices visit`, choose *type the address* and enter
`127.0.0.1:14370`. It allocates `SIM-REHEARSAL-2` in the lab and ends with a
report in `field-report/`.

Watch the punches arrive in the dashboard, and read what the recorder kept
under `lab/captures/SIM-REHEARSAL-1/`. Stop `capture` mid-way to see the
simulator answered `502` and its punches arrive once it is back.

Other things worth trying:

- `sim-zk --comm-key 12345` with `comm_key` in `lab/agent.toml` — and without
  it, to see the refusal.
- `sim-zk --record-size 16` or `8` — the older record formats. The 8-byte
  format names users by internal slot, so its punches are quarantined as
  unreadable: expected, and visible on the device's page.
- `sim-zk --clock-offset-minutes 45` — the agent logs the skew.
- `sim-zk --live-every 5` and `run --config lab/agent.toml` — the agent
  picking up new scans as they happen.
- Deactivate a terminal in the dashboard and run `once` — `not registered`,
  and nothing lost from the spool.

## The Agent's Own Tests

`cd devices-agent && python3 -m unittest discover -s tests -t .` — the 4370
client against the emulator over TCP and UDP, with and without a Comm Key, in
all three record formats; the spool, the server client and the agent loop
against a stand-in server, including outages, a wrong terminal and oversized
batches; the Hikvision source against its simulator; the recorder; and the USB
import. With `pyzk==0.9` installed, `tests.test_zk4370` also checks that pyzk
reads the emulator exactly as the agent does.
