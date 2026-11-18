param(
    [Parameter(Mandatory)][ValidateSet('on','off')][string]$Action,
    [Parameter(Mandatory)][string]$Launcher,
    [string]$StartupDir = [Environment]::GetFolderPath('Startup')
)
$ErrorActionPreference = 'Stop'
$entry = Join-Path $StartupDir 'Flowstate host.lnk'
if ($Action -eq 'off') {
    if (Test-Path -LiteralPath $entry) { Remove-Item -LiteralPath $entry }
    Write-Output 'Flowstate will no longer start at login.'
    exit 0
}
if (-not (Test-Path -LiteralPath $Launcher -PathType Leaf)) { throw 'Flowstate launcher was moved. Run this command from your current Flowstate checkout.' }
New-Item -ItemType Directory -Force -Path $StartupDir | Out-Null
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($entry)
$link.TargetPath = (Resolve-Path -LiteralPath $Launcher).Path
$link.WorkingDirectory = Split-Path -Parent $Launcher
$link.Description = 'Start the Flowstate host for headset Live mode'
$link.Save()
Write-Output 'Flowstate will start in a terminal at login. Keep this checkout in place. Close the terminal to stop it.'
