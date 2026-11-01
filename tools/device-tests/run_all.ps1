# ArogyaDoot device tests (debug only, keeps app data). From repo root:
#   powershell -ExecutionPolicy Bypass -File tools\device-tests\run_all.ps1 [-SkipInstall] [-Only V1|M1off|M1on|V2|FB]
# Never uninstalls or clears data. Restores airplane mode / wifi / policy at the end.
param([switch]$SkipInstall, [string]$Only = "")
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME -and (Test-Path 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe')) {
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
}
$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$RUNNER = 'com.example.arogyadoot.test/androidx.test.runner.AndroidJUnitRunner'
$OUT = 'docs\benchmark\device'
New-Item -ItemType Directory $OUT -Force | Out-Null

function Net-Off { & $ADB shell cmd connectivity airplane-mode enable | Out-Null; Start-Sleep 2 }
function Net-On { & $ADB shell cmd connectivity airplane-mode disable | Out-Null; & $ADB shell svc wifi enable | Out-Null; Start-Sleep 4 }
function Foreground {
  & $ADB shell svc power stayon true | Out-Null
  & $ADB shell am start -n com.example.arogyadoot/com.example.arfas.MainActivity 2>$null | Out-Null
  Start-Sleep 3
}
$LOGTAGS = @('ArogyaSelfTest:*', 'ArogyaDemo:*', 'PocketRagEngine:*', 'GenericKnowledgeContext:*', 'GenericKnowledgePack:*', 'LlamaCppCore:*', 'LocalModelManager:*', 'MetaModelApi:*', 'MetaOnline:*', 'AndroidRuntime:E')
function Run-Class($class, $eargs = @()) {
  Write-Output "===== $class $($eargs -join ' ') ====="
  Foreground
  $job = Start-Job -ScriptBlock { param($adb, $tags, $f) & $adb logcat -v time -s @tags > $f 2>$null } -ArgumentList $ADB, $LOGTAGS, "$PWD\$OUT\$class-logcat.txt"
  try {
    & $ADB shell am instrument -w @eargs -e class "com.example.arfas.$class" $RUNNER 2>&1 | Tee-Object "$OUT\$class.txt"
  } finally { Stop-Job $job 2>$null | Out-Null; Remove-Job $job -Force 2>$null | Out-Null }
}
try {
  & $ADB devices -l
  if (-not $SkipInstall) {
    cmd /c "gradlew.bat :app:installDebug :app:installDebugAndroidTest --console=plain" 2>&1 | Tee-Object "$OUT\install.txt" | Select-String -Pattern 'BUILD|FAILED|error:|e: '
  }
  & $ADB shell logcat -c
  if ($Only -eq "" -or $Only -eq "V1") { Net-On; Run-Class 'V1CloudTest' }
  if ($Only -eq "" -or $Only -eq "FB") { Net-Off; Run-Class 'V1FallbackTest' }
  if ($Only -eq "" -or $Only -eq "M1off") { Net-Off; Run-Class 'M1BenchmarkTest' @('-e', 'mode', 'offline'); if ($Only -eq "") { Write-Output '--- cool-down 180s ---'; Start-Sleep 180 } }
  if ($Only -eq "" -or $Only -eq "M1on") { Net-On; Run-Class 'M1BenchmarkTest' @('-e', 'mode', 'online'); if ($Only -eq "") { Write-Output '--- cool-down 120s ---'; Start-Sleep 120 } }
  if ($Only -eq "" -or $Only -eq "V2") { Net-Off; Run-Class 'V2VisitFlowTest' }
} finally { Net-On; & $ADB shell svc power stayon false | Out-Null }
$prevPref = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
Remove-Item -Recurse -Force $OUT\selftest -ErrorAction SilentlyContinue
& $ADB pull /sdcard/Android/data/com.example.arogyadoot/files/selftest/ $OUT\  2>$null | Select-Object -Last 2
& $ADB logcat -d -v time -s ArogyaSelfTest:* ArogyaDemo:* PocketRagEngine:* GenericKnowledgeContext:* GenericKnowledgePack:* LlamaCppCore:* LocalModelManager:* MetaModelApi:* MetaOnline:* AndroidRuntime:E > $OUT\logcat.txt 2>$null
$ErrorActionPreference = $prevPref
Write-Output ("pulled: {0} result files, logcat {1} bytes" -f @(Get-ChildItem $OUT\selftest\* -ErrorAction SilentlyContinue).Count, @(Get-Item $OUT\logcat.txt -ErrorAction SilentlyContinue)[0].Length)
Write-Output '===== SUMMARY ====='
Get-ChildItem $OUT\selftest\*.json | ForEach-Object {
  $j = Get-Content $_.FullName -Raw | ConvertFrom-Json
  if ($j.rows) { Write-Output ("{0}: pins {1}/{2}" -f $_.BaseName, $j.pins, $j.of) }
  else { $ok = @($j.checks | Where-Object { $_.ok }).Count; Write-Output ("{0}: {1}/{2} passed" -f $_.BaseName, $ok, $j.checks.Count) }
}
