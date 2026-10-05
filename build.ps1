# Build, install and launch DisPinterest on the connected phone.
#   .\build.ps1            debug build (page inspection enabled)
#   .\build.ps1 -Release   minified build (debug-signed until a keystore exists)
#   .\build.ps1 -Logs      tail the app's logcat after launch
param([switch]$Release, [switch]$Logs)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android/Sdk")) | Where-Object { $_ }
$adb = $sdkRoots | ForEach-Object { Join-Path $_ "platform-tools/adb.exe" } | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $adb) { $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source }
if (-not $adb) { Write-Host "adb not found. Set ANDROID_HOME or put platform-tools on PATH." -ForegroundColor Red; exit 1 }

$type = if ($Release) { "release" } else { "debug" }
$task = if ($Release) { "assembleRelease" } else { "assembleDebug" }
$apk = "app/build/outputs/apk/$type/app-$type.apk"

# Redirected to a file: piping gradlew through Select-Object can kill it early.
& ./gradlew.bat $task --console=plain *> build.log
if ($LASTEXITCODE -ne 0) { Get-Content build.log -Tail 40; Write-Host "Gradle build failed (see build.log)" -ForegroundColor Red; exit 1 }
Write-Host ("Built {0} ({1:N2} MB)" -f $apk, ((Get-Item $apk).Length / 1MB)) -ForegroundColor Green

& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { Write-Host "adb install failed" -ForegroundColor Red; exit 1 }
& $adb shell am start -n app.tack.webview/app.tack.MainActivity | Out-Null

if ($Logs) {
    & $adb logcat -c
    & $adb logcat Tack:V AndroidRuntime:E "*:S"
}
