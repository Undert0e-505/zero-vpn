# ZeroVPN GitHub release build script.
#
# Dry run:
#   .\scripts\build-apk.ps1 -Version 0.1 -DryRun -PreRelease -Clean
#
# Real release, after review and signing setup:
#   .\scripts\build-apk.ps1 -Version 0.1 -PreRelease -Clean

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+(\.\d+){1,3}$')]
    [string]$Version,

    [switch]$DryRun,
    [switch]$PreRelease,
    [switch]$Clean,
    [switch]$AllowUnsigned
)

$ErrorActionPreference = "Stop"

$AppName = "ZeroVPN"
$RepoDir = Split-Path $PSScriptRoot -Parent
$AndroidDir = Join-Path $RepoDir "android"
$GradleFile = Join-Path $AndroidDir "app\build.gradle.kts"
$DistDir = Join-Path $RepoDir "dist"
$TagName = "v$Version"
$ReleaseTitle = "$AppName $TagName"
$NotesFile = Join-Path $DistDir "release-notes-$TagName.md"
$SecretPattern = 'BEGIN PRIVATE KEY|BEGIN OPENSSH PRIVATE KEY|ST\$eyJ|Authorization:|Bearer |session_token|security-token|PrivateKey =|PresharedKey =|ocid1\.|gmail\.com'

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Fail([string]$Message) {
    throw $Message
}

function Run([string]$Command, [string[]]$Arguments, [switch]$AllowDryRun) {
    $display = "$Command $($Arguments -join ' ')".Trim()
    if ($DryRun -and $AllowDryRun) {
        Write-Host "[dry-run] would run: $display" -ForegroundColor Yellow
        return
    }
    Write-Host $display
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        Fail "Command failed ($LASTEXITCODE): $display"
    }
}

function GitOutput([string[]]$Arguments) {
    $output = & git @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        Fail "git $($Arguments -join ' ') failed: $($output -join "`n")"
    }
    return $output
}

function Read-GradleVersion {
    $text = Get-Content -LiteralPath $GradleFile -Raw
    if ($text -notmatch 'versionName\s*=\s*"([^"]+)"') {
        Fail "Could not read versionName from $GradleFile"
    }
    $versionName = $Matches[1]
    if ($text -notmatch 'versionCode\s*=\s*(\d+)') {
        Fail "Could not read versionCode from $GradleFile"
    }
    $versionCode = [int]$Matches[1]
    [pscustomobject]@{
        VersionName = $versionName
        VersionCode = $versionCode
    }
}

function Test-CommandAvailable([string]$Name) {
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Get-AndroidSdkPath {
    $localProperties = Join-Path $AndroidDir "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        $line = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            $path = ($line -replace '^sdk\.dir=', '').Trim()
            return ($path -replace '\\:', ':' -replace '\\\\', '\')
        }
    }
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }
    return $null
}

function Find-Aapt2 {
    $sdk = Get-AndroidSdkPath
    if (-not $sdk -or -not (Test-Path -LiteralPath $sdk)) { return $null }
    $buildTools = Join-Path $sdk "build-tools"
    if (-not (Test-Path -LiteralPath $buildTools)) { return $null }
    $tool = Get-ChildItem -LiteralPath $buildTools -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName "aapt2.exe" } |
        Where-Object { Test-Path -LiteralPath $_ } |
        Select-Object -First 1
    return $tool
}

function Find-ApkSigner {
    $sdk = Get-AndroidSdkPath
    if (-not $sdk -or -not (Test-Path -LiteralPath $sdk)) { return $null }
    $buildTools = Join-Path $sdk "build-tools"
    if (-not (Test-Path -LiteralPath $buildTools)) { return $null }
    $tool = Get-ChildItem -LiteralPath $buildTools -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName "apksigner.bat" } |
        Where-Object { Test-Path -LiteralPath $_ } |
        Select-Object -First 1
    return $tool
}

function Write-SigningSetupHelp {
    Write-Host ""
    Write-Host "Release signing is not configured." -ForegroundColor Yellow
    Write-Host "Create a keystore outside the repo, for example:"
    Write-Host '  keytool -genkeypair -v -keystore "%LOCALAPPDATA%\ZeroVPN\zerovpn-release.jks" -alias zerovpn -keyalg RSA -keysize 4096 -validity 10000'
    Write-Host ""
    Write-Host "Then create ignored android/signing.properties:"
    Write-Host "  storeFile=C:\Users\<user>\AppData\Local\ZeroVPN\zerovpn-release.jks"
    Write-Host "  storePassword=<password>"
    Write-Host "  keyAlias=zerovpn"
    Write-Host "  keyPassword=<password>"
    Write-Host ""
    Write-Host "Or set environment variables:"
    Write-Host "  ZEROVPN_KEYSTORE_FILE, ZEROVPN_KEYSTORE_PASSWORD, ZEROVPN_KEY_ALIAS, ZEROVPN_KEY_PASSWORD"
}

function Remove-GeneratedBuildDirectory([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    Get-ChildItem -LiteralPath $Path -Recurse -Force -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.Attributes -band [System.IO.FileAttributes]::ReadOnly) {
            $_.Attributes = $_.Attributes -band (-bnot [System.IO.FileAttributes]::ReadOnly)
        }
    }
    $root = Get-Item -LiteralPath $Path -Force
    if ($root.Attributes -band [System.IO.FileAttributes]::ReadOnly) {
        $root.Attributes = $root.Attributes -band (-bnot [System.IO.FileAttributes]::ReadOnly)
    }
    try {
        Remove-Item -LiteralPath $Path -Recurse -Force
    } catch {
        Write-Host "Warning: could not fully remove generated build directory ${Path}: $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

function Test-SensitiveMaterial {
    Write-Step "Running public-readiness guardrail scan"

    $trackedMatches = & git grep -n -I -E $SecretPattern -- . 2>$null
    $trackedExit = $LASTEXITCODE
    if ($trackedExit -eq 0) {
        $blocked = $trackedMatches | Where-Object { -not (Test-AllowedSecretScanMatch $_) }
        if ($blocked) {
            $blocked | ForEach-Object { Write-Host $_ -ForegroundColor Red }
            Fail "Sensitive-looking material found in tracked files."
        }
    } elseif ($trackedExit -ne 1) {
        Fail "git grep secret scan failed."
    }

    $diffMatches = & git diff --cached -- 2>$null | Select-String -Pattern $SecretPattern
    $blockedDiff = $diffMatches | ForEach-Object { $_.Line } | Where-Object { -not (Test-AllowedSecretScanMatch $_) }
    if ($blockedDiff) {
        $blockedDiff | ForEach-Object { Write-Host $_ -ForegroundColor Red }
        Fail "Sensitive-looking material found in staged diff."
    }

    $trackedArtifacts = GitOutput @("ls-files") | Where-Object {
        $_ -match '\.(apk|aab)$' -or
        $_ -match 'harness/(python_all_requests|python_post_capture|state_machine_capture|test_capture).*\.(log|json)$' -or
        $_ -match '__pycache__|\.pyc$'
    }
    if ($trackedArtifacts) {
        $trackedArtifacts | ForEach-Object { Write-Host $_ -ForegroundColor Red }
        Fail "Forbidden generated/release artifacts are tracked."
    }

    Write-Host "Guardrail scan passed."
}

function Test-AllowedSecretScanMatch([string]$Line) {
    if ($Line -match 'REDACTED|example|<[^>]+>|\$\{|PrivateKey = \$[A-Z_]+|PresharedKey = \$[A-Z_]+') { return $true }
    if ($Line -match 'PrivateKey = \{|PrivateKey = \$\(|PrivateKey = \{peer_|PrivateKey = \{peer') { return $true }
    if ($Line -match 'value\(interfaceSection, "PrivateKey"\)|sshPrivateKey|clientKeys\.privateKey|sshKeyPair\.second|onCopyPrivateKey') { return $true }
    if ($Line -match 'SecretPattern|Test-AllowedSecretScanMatch|git grep|PrivateKey = \\') { return $true }
    if ($Line -match 'Authorization: Signature \.\.\.|Authorization header generated|Bearer token') { return $true }
    if ($Line -match 'security-token material|security-token auth|ST\$\.\.\.') { return $true }
    if ($Line -match 'ocid1\.example') { return $true }
    return $false
}

function Write-ReleaseNotes {
    New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
    @"
ZeroVPN v$Version is the first experimental public release.

Current working path:

* Create an Oracle Free Exit
* Provision an Oracle Cloud VM
* Configure WireGuard
* Connect Android through the VM using Android VpnService
* Show basic connection diagnostics

Important limitations:

* Oracle account signup/login/MFA is still rough.
* Create and verify your Oracle Cloud account before using the app.
* Volunteer Network, QR Invite, Import Config, Private Node, and Logs are not implemented yet.
* This is experimental software.
* Traffic exits through your own Oracle VM.
"@ | Set-Content -LiteralPath $NotesFile -Encoding UTF8
}

Set-Location $RepoDir

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  $AppName release build" -ForegroundColor Cyan
Write-Host "  Version: $Version" -ForegroundColor Cyan
Write-Host "  Tag: $TagName" -ForegroundColor Cyan
Write-Host "  DryRun: $DryRun" -ForegroundColor Cyan
Write-Host "  PreRelease: $PreRelease" -ForegroundColor Cyan
Write-Host "  AllowUnsigned: $AllowUnsigned" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

Write-Step "Validating tools"
Run "git" @("--version")
if (Test-CommandAvailable "gh") {
    Run "gh" @("--version")
    if (-not $DryRun) {
        Run "gh" @("auth", "status")
    } else {
        Write-Host "[dry-run] would run: gh auth status" -ForegroundColor Yellow
    }
} elseif (-not $DryRun) {
    Fail "GitHub CLI 'gh' is required for a real release."
} else {
    Write-Host "gh not found; dry-run will not create a release." -ForegroundColor Yellow
}

Write-Step "Validating git state"
$branch = (GitOutput @("branch", "--show-current") | Select-Object -First 1).Trim()
$remote = GitOutput @("remote", "get-url", "origin") | Select-Object -First 1
Write-Host "Branch: $branch"
Write-Host "Origin: $remote"

$status = GitOutput @("status", "--short")
if ($status -and -not $DryRun) {
    $status | ForEach-Object { Write-Host $_ -ForegroundColor Yellow }
    Fail "Working tree must be clean for a real release."
} elseif ($status) {
    Write-Host "Working tree is dirty; allowed because this is a dry-run." -ForegroundColor Yellow
}

$localTag = & git rev-parse -q --verify "refs/tags/$TagName" 2>$null
if ($LASTEXITCODE -eq 0 -and $localTag) {
    Fail "Local tag already exists: $TagName"
}

if (-not $DryRun) {
    $remoteTag = & git ls-remote --tags origin $TagName 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "Could not check remote tag: $($remoteTag -join "`n")" }
    if ($remoteTag) { Fail "Remote tag already exists: $TagName" }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $releaseView = & gh release view $TagName 2>$null
    $releaseViewExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference
    if ($releaseViewExitCode -eq 0) { Fail "GitHub release already exists: $TagName" }
} else {
    Write-Host "[dry-run] would check remote tag and GitHub release existence for $TagName" -ForegroundColor Yellow
}

Test-SensitiveMaterial

Write-Step "Validating Gradle version"
$gradleVersion = Read-GradleVersion
Write-Host "Gradle versionName=$($gradleVersion.VersionName), versionCode=$($gradleVersion.VersionCode)"
if ($gradleVersion.VersionName -ne $Version) {
    Fail "Version mismatch: -Version $Version but Gradle versionName is $($gradleVersion.VersionName). Update android/app/build.gradle.kts first."
}
if ($Version -eq "0.1" -and $gradleVersion.VersionCode -ne 1) {
    Fail "v0.1 must use versionCode 1; found $($gradleVersion.VersionCode)."
}

Write-ReleaseNotes

Write-Step "Building release APK"
Push-Location $AndroidDir
try {
    if ($Clean) {
        Run ".\gradlew.bat" @("--stop") -AllowDryRun:$false
        Remove-GeneratedBuildDirectory (Join-Path $AndroidDir "app\build")
        Remove-GeneratedBuildDirectory (Join-Path $AndroidDir "build")
    }
    Run ".\gradlew.bat" @("assembleRelease", "--no-daemon") -AllowDryRun:$false
} finally {
    Pop-Location
}

$releaseDir = Join-Path $AndroidDir "app\build\outputs\apk\release"
$releaseApk = Join-Path $releaseDir "app-release.apk"
$unsignedApk = Join-Path $releaseDir "app-release-unsigned.apk"
$isUnsigned = $false
if (Test-Path -LiteralPath $releaseApk) {
    $sourceApk = $releaseApk
} elseif (Test-Path -LiteralPath $unsignedApk) {
    $sourceApk = $unsignedApk
    $isUnsigned = $true
} else {
    Fail "Release APK not found in $releaseDir"
}
if ($sourceApk -match 'debug') {
    Fail "Refusing to release a debug APK: $sourceApk"
}

New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
$artifactName = if ($isUnsigned) { "$AppName-$TagName-unsigned.apk" } else { "$AppName-$TagName.apk" }
$artifactPath = Join-Path $DistDir $artifactName
Copy-Item -LiteralPath $sourceApk -Destination $artifactPath -Force
Write-Host "Artifact: $artifactPath"
if ($isUnsigned) {
    Write-Host "Signing status: unsigned release APK" -ForegroundColor Yellow
} else {
    Write-Host "Signing status: signed release APK"
}

$apksigner = Find-ApkSigner
$signatureVerified = $false
$debugSigned = $false
if ($apksigner) {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $verifyLines = & $apksigner verify --verbose --print-certs $artifactPath 2>&1
    $verifyExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference
    $verifyOutput = $verifyLines -join "`n"
    if ($verifyExitCode -eq 0) {
        $signatureVerified = $true
        Write-Host "APK signature verification: passed"
        if ($verifyOutput -match 'CN=Android Debug') {
            $debugSigned = $true
        }
    } else {
        Write-Host "APK signature verification: failed" -ForegroundColor Yellow
        if ($DryRun) {
            Write-Host $verifyOutput -ForegroundColor DarkYellow
        }
    }
} else {
    Write-Host "apksigner not found; cannot verify APK signature." -ForegroundColor Yellow
}

if ($debugSigned) {
    Fail "Refusing to release a debug-signed APK."
}

if ($isUnsigned -or -not $signatureVerified) {
    Write-SigningSetupHelp
    if (-not $DryRun -and -not $AllowUnsigned) {
        Fail "Real releases require an apksigner-verified signed APK. Configure release signing or pass -AllowUnsigned only for internal testing."
    }
    if ($DryRun) {
        Write-Host "Dry-run is allowed to continue with an unsigned APK. A real release would fail without signing." -ForegroundColor Yellow
    }
}

$aapt2 = Find-Aapt2
if ($aapt2) {
    $badging = (& $aapt2 dump badging $artifactPath 2>&1) -join "`n"
    if ($badging -match "versionCode='(\d+)'\s+versionName='([^']+)'") {
        $apkVersionCode = [int]$Matches[1]
        $apkVersionName = $Matches[2]
        Write-Host "APK versionName=$apkVersionName, versionCode=$apkVersionCode"
        if ($apkVersionName -ne $Version -or $apkVersionCode -ne $gradleVersion.VersionCode) {
            Fail "APK version does not match Gradle/release version."
        }
    } else {
        Write-Host "Could not read APK badging; continuing after Gradle version validation." -ForegroundColor Yellow
    }
} else {
    Write-Host "aapt2 not found; skipping APK badging audit." -ForegroundColor Yellow
}

Write-Step "Release commands"
Run "git" @("tag", "-a", $TagName, "-m", $ReleaseTitle) -AllowDryRun
Run "git" @("push", "origin", $TagName) -AllowDryRun

$releaseArgs = @("release", "create", $TagName, $artifactPath, "--title", $ReleaseTitle, "--notes-file", $NotesFile)
if ($PreRelease) {
    $releaseArgs += "--prerelease"
}
Run "gh" $releaseArgs -AllowDryRun

Write-Host ""
Write-Host "Release build prepared successfully." -ForegroundColor Green
Write-Host "Tag: $TagName"
Write-Host "Title: $ReleaseTitle"
Write-Host "APK: $artifactPath"
Write-Host "Notes: $NotesFile"
if ($DryRun) {
    Write-Host "Dry-run completed: no tag, push, or GitHub release was created." -ForegroundColor Yellow
}
