@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "OUT=/opt/flowstate\Documents\example-project\.autoflow\worktrees\t880\runs\rowspage\final"
if not "%~1"=="" set "OUT=%~f1"
pushd "%~dp0..\..\native\overhand"
call gradlew.bat :app:testDebugUnitTest :app:preReleaseBuild "-PlayoutLintOut=%OUT%" --max-workers=2 --no-daemon --console=plain
set rc=%errorlevel%
xcopy /E /I /Y "app\build\test-results\testDebugUnitTest" "%OUT%\unit-results" >nul
popd
exit /b %rc%
