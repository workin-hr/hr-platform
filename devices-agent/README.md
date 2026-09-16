# WorkIn Devices Agent

Reads attendance terminals on a branch LAN and delivers their punches to the platform, and
carries the site-visit kit and the lab simulators. Python 3.11+, standard library only.

- What it is, how to install it on a branch computer, and every command:
  [docs/devices/on-prem-agent.md](../docs/devices/on-prem-agent.md)
- Testing real terminals at a customer site:
  [docs/devices/field-visit-runbook.md](../docs/devices/field-visit-runbook.md)
- Running everything locally with simulated terminals:
  [docs/devices/devices-lab.md](../docs/devices/devices-lab.md)

## Develop

```bash
cd devices-agent
python3 -m unittest discover -s tests -t .      # 35 tests, no packages needed
python3 -m pip install "pyzk==0.9"               # optional: cross-check the emulator against pyzk
python3 -m unittest tests.test_zk4370
python3 -m workin_devices --help
```

pyzk is GPL-2.0 and is never bundled: the agent's 4370 client (`workin_devices/zk4370.py`) is
its own, read-only by construction, and pyzk only confirms in a test that the emulator speaks
the protocol the way pyzk -- which has been run against real terminals -- expects.

## Build

- Windows: `packaging\windows\build-exe.ps1` on a Windows machine produces
  `dist\workin-devices-agent.exe` with the install scripts beside it.
- Linux and Raspberry Pi: no build; run the package with the system `python3`
  (`packaging/linux/workin-devices-agent.service`).
