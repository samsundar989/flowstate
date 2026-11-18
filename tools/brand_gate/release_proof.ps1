# Brand gate (#879) proof run, meant for workerq (the repo is a snapshot, so the planted edit never reaches the live tree):
#   workerq submit --project example-project --ram 10 --cpus 4 -- powershell -NoProfile -File tools/brand_gate/release_proof.ps1 -OutDir <abs dir>
# 1. The release gate as #808 runs it (tools/apk_privacy_gate/build_release.ps1: assembleRelease with the layout lint,
#    privacy and brand gates inside, plus the debug unit suite). Must pass (rc 0).
# 2. Control: plant one vendor name in a string a window renders (the opening hint), run assembleRelease again. Must FAIL.
param([Parameter(Mandatory)][string]$OutDir, [string]$NamesFile)
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$args1 = @('-NoProfile', '-File', (Join-Path $repo 'tools/apk_privacy_gate/build_release.ps1'), '-OutDir', (Join-Path $OutDir 'release'))
if ($NamesFile) { $args1 += @('-NamesFile', $NamesFile) }
& powershell @args1
$release = $LASTEXITCODE
Copy-Item (Join-Path $repo 'runs/brand_gate/release.json') (Join-Path $OutDir 'brand_release.json') -ErrorAction SilentlyContinue

# Signing for the control's packageRelease, the same way build_release.ps1 does it (never printed).
$common = (& git -C $repo rev-parse --path-format=absolute --git-common-dir).Trim()
$SigningRoot = if ($env:OVERHAND_SIGNING_ROOT) { $env:OVERHAND_SIGNING_ROOT } else { Join-Path (Split-Path $common) '.autoflow/artifacts/t313/native/overhand' }
if (!$env:JAVA_HOME) { $env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr' }
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk" }
foreach ($line in Get-Content -LiteralPath (Join-Path $SigningRoot 'local.properties')) {
    if ($line -match '^(RELEASE_KEY_ALIAS|RELEASE_STORE_PASSWORD|RELEASE_KEY_PASSWORD)=(.*)$') { Set-Item -Path "env:$($Matches[1])" -Value $Matches[2] }
}
$env:RELEASE_KEYSTORE = Join-Path $SigningRoot 'keystore/overhand-release.jks'

$src = Join-Path $repo 'native/overhand/app/src/main/java/com/overhand/app/opening/Opening.kt'
$orig = [IO.File]::ReadAllText($src)
$planted = $orig.Replace('const val HINT="Turn a palm up', 'const val HINT="Ask Claude: turn a palm up')
if ($planted -eq $orig) { "plant failed: HINT text not found" | Tee-Object -FilePath (Join-Path $OutDir 'rc.txt'); exit 2 }
[IO.File]::WriteAllText($src, $planted)
& (Join-Path $repo 'native/overhand/gradlew.bat') -p (Join-Path $repo 'native/overhand') --no-daemon --console=plain assembleRelease *> (Join-Path $OutDir 'control.log')
$control = $LASTEXITCODE
[IO.File]::WriteAllText($src, $orig)
Copy-Item (Join-Path $repo 'runs/brand_gate/release.json') (Join-Path $OutDir 'brand_control.json') -ErrorAction SilentlyContinue
Remove-Item env:RELEASE_STORE_PASSWORD, env:RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
& git -C $repo checkout -- runs/native/stations/brand_check.json runs/native/stations/rule_output.json 2>$null

"release_rc=$release brand_control_rc=$control (control must be non-zero)" | Tee-Object -FilePath (Join-Path $OutDir 'rc.txt')
if ($release -ne 0 -or $control -eq 0) { exit 1 }
exit 0
