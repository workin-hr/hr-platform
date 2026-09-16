# Removes the scheduled task and the program. Leaves the data folder (config, token, spool):
# records still pending in the spool are the only copy of punches the server has not received.
param([string]$InstallDir = "$env:ProgramFiles\WorkIn Devices Agent")
$ErrorActionPreference = "Stop"
Unregister-ScheduledTask -TaskName "WorkIn Devices Agent" -Confirm:$false -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force $InstallDir -ErrorAction SilentlyContinue
Write-Host "Removed. The data folder under $env:ProgramData is kept on purpose."
