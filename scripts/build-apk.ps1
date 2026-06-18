# ============================================================
# ZeroVPN -- Local APK Build (Windows-native PowerShell)
# ============================================================
# Builds, signs, and copies the APK to the Jimothy Share folder
# for Google Drive delivery. No GitHub release step (yet).
#
# Usage:
#   .\build-apk.ps1                          # build current version
#   .\build-apk.ps1 -Version 0.2.0           # bump version, build, sign, copy
#   .\build-apk.ps1 -Clean                   # nuke gradle cache first
#
# What it does (in order):
#   1. (Optional) Bump version in build.gradle.kts
#   2. Gradle assembleRelease
#   3. Sign APK with apksigner (debug keystore)
#   4. Verify signature
#   5. Copy to Jimothy Share folder for Drive delivery
#
# Requirements (all on this Windows host):
#   - JDK 21 at C:\Program Files\Java\jdk-21
#   - Android SDK at D:\dev\android-sdk
#   - debug.keystore (copied to android\app\ on first run)
# ============================================================

[CmdletBinding()]
param(
    [string]$Version = "",

    [switch]$Clean
)

# --- Constants ---
$APP_NAME = "ZeroVPN"
$REPO_DIR = Split-Path $PSScriptRoot -Parent
if (-not (Test-Path "$REPO_DIR\android\app\build.gradle.kts")) {
    # If script is in the repo root, REPO_DIR is the script's parent
    $REPO_DIR = $PSScriptRoot
}
$ANDROID_DIR = "$REPO_DIR\android"

$JDK_PATH = "C:\Program Files\Java\jdk-21"
$ANDROID_SDK = "D:\dev\android-sdk"
$SHARE_FOLDER = "D:\AIProjects\Aaron\Jimothy Share\zero-vpn"
$KEYSTORE = "$ANDROID_DIR\app\debug.keystore"

# Try to reuse TubePulse's debug keystore if ZeroVPN doesn't have one
$TUBEPULSE_KEYSTORE = "D:\dev\tubepulse\android\app\debug.keystore"

$UTF8_NO_BOM = [System.Text.UTF8Encoding]::new($false)

# --- Banner ---
Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  $APP_NAME - Build APK" -ForegroundColor Cyan
if ($Version) { Write-Host "  Version: $Version" -ForegroundColor Cyan }
Write-Host "  JDK: $JDK_PATH" -ForegroundColor Cyan
Write-Host "  SDK: $ANDROID_SDK" -ForegroundColor Cyan
Write-Host "  Share: $SHARE_FOLDER" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# --- Verify tools ---
Write-Host "[0/5] Verifying build tools..."

if (-not (Test-Path "$JDK_PATH\bin\java.exe")) {
    Write-Error "JDK not found at $JDK_PATH"
    exit 1
}
Write-Host "  JDK 21  OK"

if (-not (Test-Path "$ANDROID_SDK\platform-tools\adb.exe")) {
    Write-Error "Android SDK not found at $ANDROID_SDK"
    exit 1
}
Write-Host "  Android SDK  OK"

$buildToolsDir = Get-ChildItem "$ANDROID_SDK\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
if (-not $buildToolsDir) {
    Write-Error "No build-tools found in $ANDROID_SDK\build-tools"
    exit 1
}
$apksigner = "$($buildToolsDir.FullName)\apksigner.bat"
$aapt2 = "$($buildToolsDir.FullName)\aapt2.exe"
Write-Host "  Build tools: $($buildToolsDir.Name)  OK"

# Ensure keystore exists
if (-not (Test-Path $KEYSTORE)) {
    if (Test-Path $TUBEPULSE_KEYSTORE) {
        Copy-Item $TUBEPULSE_KEYSTORE $KEYSTORE
        Write-Host "  Copied debug.keystore from TubePulse  OK"
    } else {
        Write-Error "debug.keystore not found at $KEYSTORE or $TUBEPULSE_KEYSTORE"
        exit 1
    }
} else {
    Write-Host "  debug.keystore  OK"
}

# Ensure share folder exists
if (-not (Test-Path $SHARE_FOLDER)) {
    New-Item -ItemType Directory -Force -Path $SHARE_FOLDER | Out-Null
    Write-Host "  Created share folder  OK"
} else {
    Write-Host "  Share folder  OK"
}

# --- Set environment ---
$env:JAVA_HOME = $JDK_PATH
$env:ANDROID_HOME = $ANDROID_SDK
$env:ANDROID_SDK_ROOT = $ANDROID_SDK
$env:PATH = "$JDK_PATH\bin;$ANDROID_SDK\platform-tools;$env:PATH"

# --- Optional: clean ---
if ($Clean) {
    Write-Host ""
    Write-Host "[clean] Removing gradle caches..."
    Remove-Item -Recurse -Force "$ANDROID_DIR\app\build", "$ANDROID_DIR\build", "$ANDROID_DIR\.gradle" -ErrorAction SilentlyContinue
    Write-Host "  done"
}

# --- Step 1: Version bump (optional) ---
if ($Version) {
    Write-Host ""
    Write-Host "[1/5] Bumping version to $Version..."

    $buildGradlePath = "$ANDROID_DIR\app\build.gradle.kts"
    $buildGradle = Get-Content $buildGradlePath -Raw

    $versionCode = $Version -replace '\.', ''
    $buildGradle = $buildGradle -replace 'versionCode = \d+', "versionCode = $versionCode"
    $buildGradle = $buildGradle -replace 'versionName = "[^"]+"', "versionName = `"$Version`""

    [System.IO.File]::WriteAllText($buildGradlePath, $buildGradle, $UTF8_NO_BOM)
    Write-Host "  build.gradle.kts (versionCode=$versionCode, versionName=$Version)  OK"
} else {
    Write-Host ""
    Write-Host "[1/5] No version bump requested, building current version..."
    # Read current version from build.gradle.kts
    $buildGradle = Get-Content "$ANDROID_DIR\app\build.gradle.kts" -Raw
    if ($buildGradle -match 'versionName = "([^"]+)"') {
        $Version = $Matches[1]
        $versionCode = $Version -replace '\.', ''
    } else {
        $Version = "unknown"
        $versionCode = 0
    }
    Write-Host "  Current version: $Version  OK"
}

# --- Step 2: Gradle build ---
Write-Host ""
Write-Host "[2/5] Building APK with Gradle..."
Set-Location $ANDROID_DIR
& .\gradlew.bat assembleRelease --no-daemon 2>&1 | Tee-Object -FilePath "$env:TEMP\zerovpn-gradle.log" | Select-Object -Last 8
$gradleExit = $LASTEXITCODE
Set-Location $REPO_DIR

if ($gradleExit -ne 0) {
    Write-Error "Gradle build failed (exit $gradleExit). Log: $env:TEMP\zerovpn-gradle.log"
    exit 1
}
Write-Host "  BUILD SUCCESSFUL  OK"

# --- Step 3: Sign APK ---
Write-Host ""
Write-Host "[3/5] Signing APK..."

$unsignedApk = "$ANDROID_DIR\app\build\outputs\apk\release\app-release-unsigned.apk"
$signedApk = "$ANDROID_DIR\app\build\outputs\apk\release\app-release.apk"

if (-not (Test-Path $unsignedApk)) {
    # Try alternate name (some Gradle versions name it differently)
    $altApk = "$ANDROID_DIR\app\build\outputs\apk\release\app-release.apk"
    if (Test-Path $altApk) {
        $unsignedApk = $altApk
    } else {
        Write-Error "APK not found at expected path: $unsignedApk"
        exit 1
    }
}

& $apksigner sign --ks $KEYSTORE --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out $signedApk $unsignedApk *>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Error "apksigner failed (exit $LASTEXITCODE)"
    exit 1
}
Write-Host "  Signed  OK"

# --- Step 4: Verify ---
Write-Host ""
Write-Host "[4/5] Verifying..."

$verifyOutput = (& $apksigner verify --verbose $signedApk 2>&1) -join "`n"
if (-not $verifyOutput.Contains("Verifies")) {
    Write-Error "Signature verification failed"
    exit 1
}
Write-Host "  Signature verified  OK"

# Check version in APK
$badging = (& $aapt2 dump badging $signedApk 2>&1) -join "`n"
if ($badging -match "versionCode='(\d+)' versionName='([^']+)'") {
    $apkVc = $Matches[1]; $apkVn = $Matches[2]
    Write-Host "  APK version: $apkVn (code=$apkVc)  OK"
} else {
    Write-Host "  Could not read APK version (non-critical)" -ForegroundColor Yellow
}

$apkSize = [math]::Round((Get-Item $signedApk).Length / 1MB, 1)
Write-Host ("  Size: {0:N1} MB" -f $apkSize)

# --- Step 5: Copy to share folder ---
Write-Host ""
Write-Host "[5/5] Copying to Jimothy Share folder..."

$shareApkName = "$APP_NAME-$Version.apk"
$shareApkPath = Join-Path $SHARE_FOLDER $shareApkName

# Clean up any old .idsig files from previous apksigner runs
Get-ChildItem $SHARE_FOLDER -Filter "*.idsig" | Remove-Item -Force -ErrorAction SilentlyContinue

Copy-Item $signedApk $shareApkPath -Force
Write-Host "  Copied to: $shareApkPath  OK"

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  Build complete!" -ForegroundColor Cyan
Write-Host "  APK: $shareApkPath" -ForegroundColor Cyan
Write-Host ("  Size: {0:N1} MB" -f $apkSize) -ForegroundColor Cyan
Write-Host "  Drive: Jimothy Share > zero-vpn > $shareApkName" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan