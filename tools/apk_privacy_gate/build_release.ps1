# APK privacy gate (#808) proof run, meant for workerq:
#   workerq submit --project example-project --ram 10 --cpus 4 -- powershell -NoProfile -File tools/apk_privacy_gate/build_release.ps1
# 1. assembleRelease (layout lint gate + apkPrivacyGate run inside it) and the debug unit suite.
# 2. Control: the same Gradle gate with a names list holding a word that IS in the APK must FAIL the build.
# Signing secrets go only into this process's environment and are never printed. --no-daemon: nothing is left running.
param([string]$SigningRoot = $env:OVERHAND_SIGNING_ROOT, [string]$OutDir, [string]$NamesFile, [switch]$SkipLayoutLint)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$SigningRoot) {
    $common = (& git -C $repo rev-parse --path-format=absolute --git-common-dir).Trim()
    $SigningRoot = Join-Path (Split-Path $common) '.autoflow/artifacts/t313/native/overhand'
}
$gateOut = Join-Path $repo 'runs/apk_privacy_gate'
# Under workerq the repo is a snapshot: pass an absolute -OutDir in the live repo so results come home.
$out = if ($OutDir) { $OutDir } else { $gateOut }
New-Item -ItemType Directory -Force -Path $out | Out-Null
if (!$env:JAVA_HOME) { $env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr' }
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk" }
foreach ($line in Get-Content -LiteralPath (Join-Path $SigningRoot 'local.properties')) {
    if ($line -match '^(RELEASE_KEY_ALIAS|RELEASE_STORE_PASSWORD|RELEASE_KEY_PASSWORD)=(.*)$') { Set-Item -Path "env:$($Matches[1])" -Value $Matches[2] }
}
$env:RELEASE_KEYSTORE = Join-Path $SigningRoot 'keystore/overhand-release.jks'
# The snapshot has no gitignored data/: pass the live list with -NamesFile, or the gate runs generic-only.
if ($NamesFile) { $env:APK_PRIVACY_NAMES = $NamesFile }
$gradle = Join-Path $repo 'native/overhand/gradlew.bat'
$common = @('-p', (Join-Path $repo 'native/overhand'), '--no-daemon', '--console=plain')
if ($SkipLayoutLint) { $common += '-PskipLayoutLint' }  # only to isolate this gate from a layout lint failure

$ErrorActionPreference = 'Continue'
& $gradle @common --continue assembleRelease testDebugUnitTest *> (Join-Path $out 'build.log')
$build = $LASTEXITCODE
Copy-Item (Join-Path $gateOut 'release.json') (Join-Path $out 'release_build.json') -ErrorAction SilentlyContinue
Copy-Item (Join-Path $repo 'native/overhand/app/build/outputs/apk/release/app-release.apk') (Join-Path $out 'app-release.apk') -ErrorAction SilentlyContinue
Copy-Item (Join-Path $repo 'native/overhand/app/build/test-results/testDebugUnitTest') (Join-Path $out 'test-results') -Recurse -Force -ErrorAction SilentlyContinue

$control = Join-Path ([IO.Path]::GetTempPath()) 'apk_privacy_control_names.txt'
Set-Content -LiteralPath $control -Value "# control: a word every build of this APK carries`nGeothermal faults" -Encoding utf8
$env:APK_PRIVACY_NAMES = $control
& $gradle @common apkPrivacyGate -x packageRelease *> (Join-Path $out 'control.log')
$controlRc = $LASTEXITCODE
Copy-Item (Join-Path $gateOut 'release.json') (Join-Path $out 'control.json') -ErrorAction SilentlyContinue
Remove-Item env:APK_PRIVACY_NAMES, env:RELEASE_STORE_PASSWORD, env:RELEASE_KEY_PASSWORD
Remove-Item -LiteralPath $control
# StationRulesTest rewrites these tracked files during the unit suite.
& git -C $repo checkout -- runs/native/stations/brand_check.json runs/native/stations/rule_output.json 2>$null

"build_rc=$build control_rc=$controlRc (control must be non-zero)" | Tee-Object -FilePath (Join-Path $out 'rc.txt')
if ($build -ne 0 -or $controlRc -eq 0) { exit 1 }
exit 0
