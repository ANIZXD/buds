# Builds the debug APK.
$ErrorActionPreference = 'Stop'

$env:JAVA_HOME = 'C:\Users\ay660\dev\jdk'
$env:ANDROID_HOME = 'C:\Users\ay660\dev\android-sdk'
$env:PATH = "$env:JAVA_HOME\bin;C:\Users\ay660\dev\gradle\bin;$env:PATH"

& 'C:\Users\ay660\dev\gradle\bin\gradle.bat' -p $PSScriptRoot assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $apk) {
    $mb = [math]::Round((Get-Item $apk).Length / 1MB, 2)
    Write-Host ""
    Write-Host "APK ready: $apk ($mb MB)" -ForegroundColor Green
}
