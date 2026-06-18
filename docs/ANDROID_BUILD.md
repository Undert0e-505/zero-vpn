# Android Build Instructions

> **Status:** Placeholder. Build instructions will be written when the
> Android skeleton (Phase 1) is implemented.

## Prerequisites (planned)

- JDK 21 at `C:\Program Files\Java\jdk-21`
- Android SDK at `D:\dev\android-sdk`
- Windows-native PowerShell (no WSL)

## Build (planned)

```powershell
cd android
.\gradlew assembleRelease
```

APK output: `android/app/build/outputs/apk/release/app-release.apk`

## Sign (planned)

APK signing via `apksigner` using a debug or release keystore.

## Install (planned)

```powershell
adb install app-release.apk
```

Or transfer APK to phone and sideload.

See `jobs/001_android_shell.md` for the full build plan.