@echo off
setlocal
where node >nul 2>nul
if errorlevel 1 goto neednode
node -e "const [a,b]=process.versions.node.split('.').map(Number);process.exit(a>22||(a===22&&b>=6)?0:1)"
if errorlevel 1 goto neednode
node "%~dp0host\scripts\start-host.mjs" %*
set "result=%errorlevel%"
if not "%result%"=="0" pause
exit /b %result%
:neednode
echo Flowstate needs Node.js 22.6 or newer. Download and install it from https://nodejs.org/ then double-click this file again.
pause
exit /b 1
