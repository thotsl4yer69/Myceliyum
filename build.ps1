# Mycilliyums — one-shot build / install / launch script.
#
# Usage (from the project folder):
#   powershell -ExecutionPolicy Bypass -File .\build.ps1            # build + install + launch
#   powershell -ExecutionPolicy Bypass -File .\build.ps1 -Clean     # also nuke caches first
#   powershell -ExecutionPolicy Bypass -File .\build.ps1 -BuildOnly # just make the APK

param(
  [switch]$Clean,
  [switch]$BuildOnly
)

$ErrorActionPreference = "Stop"
$projectDir = $PSScriptRoot
Set-Location $projectDir

function Step($msg) { Write-Host ""; Write-Host "==> $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "    $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "    $msg" -ForegroundColor Red; exit 1 }

# 1. Find Android Studio's bundled JBR.
Step "Locating Java"
$jbrCandidates = @(
  "C:\Program Files\Android\Android Studio\jbr",
  "C:\Program Files\Android\Android Studio Preview\jbr",
  "$env:LOCALAPPDATA\JetBrains\Toolbox\apps\AndroidStudio\ch-0\*\jbr"
)
$jbr = $jbrCandidates | ForEach-Object { Get-Item $_ -ErrorAction SilentlyContinue } | Select-Object -First 1
if (-not $jbr) { Fail "Couldn't find Android Studio's JBR. Install Android Studio or set JAVA_HOME manually." }
$env:JAVA_HOME = $jbr.FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Ok "JAVA_HOME = $env:JAVA_HOME"

# 2. Find the Android SDK.
Step "Locating Android SDK"
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path $env:ANDROID_HOME)) {
  Fail "Couldn't find Android SDK at $env:ANDROID_HOME. Open Android Studio once and let it install."
}
Ok "ANDROID_HOME = $env:ANDROID_HOME"

# 3. Optional cache wipe — equivalent to 'Invalidate caches and restart'.
if ($Clean) {
  Step "Clearing caches"
  .\gradlew.bat --stop | Out-Null
  Remove-Item .\.gradle, .\app\build -Recurse -Force -ErrorAction SilentlyContinue
  Ok "Caches cleared"
}

# 3b. Restore the stable debug keystore so locally-built APKs are signed with
# the same key as the published rolling builds — lets you update between them
# without "couldn't be installed due to a conflict". (debug.keystore is
# git-ignored; debug.keystore.base64 is the committed source of truth.)
if ((Test-Path .\debug.keystore.base64) -and (-not (Test-Path .\debug.keystore))) {
  Step "Restoring debug keystore"
  [IO.File]::WriteAllBytes(
    "$projectDir\debug.keystore",
    [Convert]::FromBase64String((Get-Content .\debug.keystore.base64 -Raw))
  )
  Ok "debug.keystore restored from debug.keystore.base64"
}

# 4. Build the debug APK.
Step "Building debug APK"
& .\gradlew.bat --warning-mode=summary :app:assembleDebug 2>&1 | Tee-Object -FilePath build.log
if ($LASTEXITCODE -ne 0) {
  Write-Host ""
  Write-Host "Build failed. First error from build.log:" -ForegroundColor Red
  Select-String -Path build.log -Pattern '^(e:|FAILURE|FAILED|error:)' | Select-Object -First 5 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
  exit 1
}
$apk = ".\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) { Ok "APK ready: $(Resolve-Path $apk)" }

if ($BuildOnly) { return }

# 5. Install + launch on a connected device or running emulator.
Step "Looking for a device"
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\bdevice$" }
if (-not $devices) {
  Write-Host "    No device or emulator detected." -ForegroundColor Yellow
  Write-Host "    Start an emulator from Android Studio (Tools > Device Manager) or plug in a phone with USB debugging on, then re-run." -ForegroundColor Yellow
  return
}
Ok "Found: $($devices -join ', ')"

Step "Installing"
& .\gradlew.bat :app:installDebug 2>&1 | Tee-Object -FilePath install.log
if ($LASTEXITCODE -ne 0) { Fail "Install failed. See install.log." }
Ok "Installed"

Step "Launching app"
& $adb shell am start -n com.aistudio.myceliummapper.vcfqka/com.example.MainActivity | Out-Null
Ok "Launched. Watch the device."
