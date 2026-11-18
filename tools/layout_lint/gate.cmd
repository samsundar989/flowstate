@echo off
rem Layout lint gate (#807): unit tests plus :app:layoutLintGate, as the release build runs it. Submit through workerq.
setlocal
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
pushd "%~dp0..\..\native\overhand"
call gradlew.bat :app:testDebugUnitTest :app:layoutLintGate --console=plain
set rc=%errorlevel%
popd
exit /b %rc%
