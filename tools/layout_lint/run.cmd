@echo off
rem Layout lint (#773): render every window off-device and lint it. One command:
rem   tools\layout_lint\run.cmd [--check-baseline] [--write-baseline]
rem Output: runs\layout_lint\<stamp>\<window>.png/.json, report.md, report.json, crops\.
rem The Gradle (Robolectric) step goes through workerq when it is installed; live WebView windows need the
rem dashboard server (LAYOUT_LINT_SERVER, default http://127.0.0.1:8790) and are skipped without it.
setlocal
set "ROOT=%~dp0..\.."
for /f %%i in ('python -c "import datetime;print(datetime.datetime.now().strftime('%%Y%%m%%d_%%H%%M%%S'))"') do set "STAMP=%%i"
set "OUT=%ROOT%\runs\layout_lint\%STAMP%"
for %%i in ("%OUT%") do set "OUT=%%~fi"
mkdir "%OUT%" 2>nul
echo layout lint: %OUT%
node "%~dp0web_capture.mjs" --fetch "%OUT%"
where workerq >nul 2>nul
if errorlevel 1 (
  call "%~dp0capture.cmd" "%OUT%" || exit /b 1
) else (
  python "%~dp0queue.py" "%OUT%" || exit /b 1
)
node "%~dp0web_capture.mjs" --render "%OUT%"
python "%~dp0lint.py" "%OUT%" --self-test %*
exit /b %errorlevel%
