# Installs the debug APK on the phone over USB and opens it.
# Plug the phone in, enable Developer options > USB debugging, and accept the
# "Allow USB debugging?" prompt on the phone first.
$ErrorActionPreference = 'Continue'

$adb = 'C:\Users\ay660\dev\android-sdk\platform-tools\adb.exe'
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'

if (-not (Test-Path $apk)) {
    Write-Host "No APK yet - running build.ps1 first..." -ForegroundColor Yellow
    & (Join-Path $PSScriptRoot 'build.ps1')
}

Write-Host "Looking for the phone..." -ForegroundColor Cyan
& $adb devices

$count = (& $adb devices) | Select-String -Pattern "`tdevice$" | Measure-Object | Select-Object -ExpandProperty Count
if ($count -lt 1) {
    Write-Host ""
    Write-Host "No phone detected. Plug it in with USB debugging on." -ForegroundColor Red
    Write-Host "  - Settings > About phone > tap Build number 7x = Developer options"
    Write-Host "  - Developer options > USB debugging > ON"
    Write-Host "  - Reconnect the cable and accept the prompt on the phone"
    Write-Host ""
    Write-Host "Or copy this file to the phone by hand and tap it:" -ForegroundColor Yellow
    Write-Host "  $apk"
    exit 1
}

& $adb install -r $apk
& $adb shell am start -n com.aniz.nova/.MainActivity
Write-Host "Done - Nova should be open on the phone." -ForegroundColor Green
