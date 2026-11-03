@echo off
rem Pramaan: build debug APK, check phone, install, push KB. Output -> _claude_work\build_log.txt
cd /d "%~dp0\..\.."
set LOG=%~dp0build_log.txt
echo START %date% %time% > "%LOG%"
if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
call gradlew.bat :app:assembleDebug --console=plain >> "%LOG%" 2>&1
echo GRADLE_EXIT=%ERRORLEVEL% >> "%LOG%"
"%ADB%" devices -l >> "%LOG%" 2>&1
"%ADB%" get-state 1>nul 2>nul
if errorlevel 1 (echo PHONE=NOT_CONNECTED >> "%LOG%" & goto end)
echo PHONE=CONNECTED >> "%LOG%"
"%ADB%" shell getprop ro.product.model >> "%LOG%" 2>&1
"%ADB%" shell getprop ro.build.version.release >> "%LOG%" 2>&1
"%ADB%" install -r "app\build\outputs\apk\debug\app-debug.apk" >> "%LOG%" 2>&1
if errorlevel 1 (echo INSTALL=FAILED >> "%LOG%") else (echo INSTALL=OK >> "%LOG%")
"%ADB%" push "%~dp0ASHA_Health_Guide.db" /sdcard/Download/ASHA_Health_Guide.db >> "%LOG%" 2>&1
if errorlevel 1 (echo KB_PUSH=FAILED >> "%LOG%") else (echo KB_PUSH=OK >> "%LOG%")
if exist "%~dp0ASHA_Health_Guide_v2.db" ("%ADB%" push "%~dp0ASHA_Health_Guide_v2.db" /sdcard/Download/ASHA_Health_Guide_v2.db >> "%LOG%" 2>&1 & if errorlevel 1 (echo KB_PUSH2=FAILED >> "%LOG%") else (echo KB_PUSH2=OK >> "%LOG%")) else (echo KB_PUSH2=SKIPPED-no-v2-file >> "%LOG%")
:end
echo END %date% %time% >> "%LOG%"
