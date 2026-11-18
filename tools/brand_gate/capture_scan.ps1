# Brand gate (#879) rendered scan by hand, meant for workerq:
#   workerq submit --project example-project --ram 8 --cpus 4 -- powershell -NoProfile -File tools/brand_gate/capture_scan.ps1 -OutDir <abs dir>
# Renders every gated window (layoutLintCapture, Robolectric) into <OutDir>/capture, then runs tools/brand_gate/gate.py
# over the capture and the app's res/ + assets/. -Tests also runs the debug unit suite. --no-daemon: nothing left running.
param([Parameter(Mandatory)][string]$OutDir, [switch]$Tests)
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$env:JAVA_HOME) { $env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr' }
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$cap = Join-Path $OutDir 'capture'
$tasks = @('layoutLintCapture') + $(if ($Tests) { @('testDebugUnitTest') } else { @() })
& (Join-Path $repo 'native/overhand/gradlew.bat') -p (Join-Path $repo 'native/overhand') --no-daemon --console=plain --continue @tasks "-PlayoutLintOut=$cap" *> (Join-Path $OutDir 'gradle.log')
$g = $LASTEXITCODE
if ($Tests) { Copy-Item (Join-Path $repo 'native/overhand/app/build/test-results/testDebugUnitTest') (Join-Path $OutDir 'test-results') -Recurse -Force -ErrorAction SilentlyContinue }
& python (Join-Path $repo 'tools/brand_gate/gate.py') --capture $cap --src (Join-Path $repo 'native/overhand/app/src/main') --out (Join-Path $OutDir 'brand.json') *> (Join-Path $OutDir 'brand.log')
$b = $LASTEXITCODE
& git -C $repo checkout -- runs/native/stations/brand_check.json runs/native/stations/rule_output.json 2>$null
"gradle_rc=$g brand_rc=$b" | Tee-Object -FilePath (Join-Path $OutDir 'rc.txt')
exit $g
