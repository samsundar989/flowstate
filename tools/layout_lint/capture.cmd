@echo off
rem Layout lint step 1: render every native window (Robolectric) into %1. Called by run.cmd (through workerq).
setlocal
if "%~1"=="" (echo usage: capture.cmd ^<out dir^> & exit /b 2)
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
pushd "%~dp0..\..\native\overhand"
call gradlew.bat :app:layoutLintCapture "-PlayoutLintOut=%~f1" --console=plain
set rc=%errorlevel%
popd
exit /b %rc%
