# Builds dist\workin-devices-agent.exe. Run on Windows with Python 3.12 from this repository's
# devices-agent folder. PyInstaller builds for the platform it runs on, so the Windows
# executable cannot be produced on Linux. See docs/devices/on-prem-agent.md.
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..\..")

python -m pip install "pyinstaller==6.22.3"
python -m unittest discover -s tests -t .
if ($LASTEXITCODE -ne 0) { throw "unit tests failed; not building" }

# zk (pyzk, GPL-2.0) is excluded: the agent has its own read-only 4370 client, and pyzk is used
# only by a test that cross-checks the emulator. The modules the CLI imports lazily are named.
python -m PyInstaller --onefile --name workin-devices-agent --paths . --exclude-module zk `
    --hidden-import workin_devices.agent --hidden-import workin_devices.usb `
    --hidden-import workin_devices.capture --hidden-import workin_devices.probe `
    --hidden-import workin_devices.sim.zk4370 --hidden-import workin_devices.sim.adms `
    --hidden-import workin_devices.sim.hikvision packaging\entry.py
if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed" }

Copy-Item -Force agent.example.toml, packaging\windows\install-agent.ps1, packaging\windows\uninstall-agent.ps1 dist\
& dist\workin-devices-agent.exe version
Write-Host "dist\ now holds everything to copy to the branch computer."
