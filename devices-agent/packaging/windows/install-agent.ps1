# Installs the WorkIn devices agent as a Windows scheduled task that starts at boot, runs as
# SYSTEM, and restarts itself if it stops. Run from an elevated PowerShell in the folder that
# holds workin-devices-agent.exe and agent.example.toml. See docs/devices/on-prem-agent.md.
param(
    [string]$InstallDir = "$env:ProgramFiles\WorkIn Devices Agent",
    [string]$DataDir = "$env:ProgramData\WorkIn Devices Agent"
)
$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $InstallDir, $DataDir | Out-Null
Copy-Item -Force (Join-Path $PSScriptRoot "workin-devices-agent.exe") $InstallDir
if (-not (Test-Path (Join-Path $DataDir "agent.toml"))) {
    Copy-Item (Join-Path $PSScriptRoot "agent.example.toml") (Join-Path $DataDir "agent.toml")
}

# The data folder holds the agent token and the spool: Administrators and SYSTEM only.
icacls $DataDir /inheritance:r /grant:r "*S-1-5-32-544:(OI)(CI)F" "*S-1-5-18:(OI)(CI)F" | Out-Null

$exe = Join-Path $InstallDir "workin-devices-agent.exe"
$arguments = "--log-file `"$DataDir\agent.log`" run --config `"$DataDir\agent.toml`""
$action = New-ScheduledTaskAction -Execute $exe -Argument $arguments -WorkingDirectory $DataDir
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
$principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
Register-ScheduledTask -TaskName "WorkIn Devices Agent" -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Force | Out-Null

Write-Host "Installed. Edit $DataDir\agent.toml and put the token in $DataDir\agent.token, then:"
Write-Host "  & `"$exe`" doctor --config `"$DataDir\agent.toml`""
Write-Host "  Start-ScheduledTask -TaskName 'WorkIn Devices Agent'"
