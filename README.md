# ZeroVPN

ZeroVPN is an experimental open-source Android app for creating and using
personal VPN exits.

The current v0.1 path provisions an Oracle Cloud Always Free VM, installs
WireGuard, and connects the Android device through that VM using Android
`VpnService`.

ZeroVPN is not a VPN service provider. It does not operate exit nodes, collect
ZeroVPN accounts, sell VPN subscriptions, or run a central VPN backend. Traffic
exits through infrastructure that you control.

## Status

ZeroVPN v0.1 is an experimental public pre-release.

Currently working:

- Create an Oracle Free Exit.
- Authenticate with Oracle Cloud.
- Discover the OCI home region automatically.
- Provision an Oracle Cloud VM.
- Configure WireGuard on the VM.
- Connect Android traffic through the VM.
- Show basic connection diagnostics.

Known rough edges:

- Oracle account signup, login, and MFA are still awkward.
- Users should create and verify their Oracle Cloud account before using the
  app.
- VM setup can still fail and diagnostics are still improving.
- Destroy/recreate flow needs hardening.
- Logs are not implemented yet.
- Volunteer Network, QR Invite, Import Config, and Private Node are not
  implemented yet.

## What Works In v0.1

- Creates an Oracle Free Exit using your own Oracle Cloud account.
- Provisions a small Oracle VM intended for Oracle Always Free usage.
- Installs and configures WireGuard on the VM.
- Starts an Android VPN tunnel using Android's `VpnService`.
- Shows basic diagnostics such as exit IP, DNS configuration, and handshake
  status.

## Planned / Not Implemented Yet

- Volunteer Network Mode.
- QR Invite / trusted operator invites.
- Import existing WireGuard config.
- Create Private Node.
- In-app logs.
- Better destroy/recreate lifecycle.
- Better onboarding and failure recovery.

## Important Limitations

This is experimental software. It may fail during Oracle provisioning, VM
setup, connection, or teardown.

ZeroVPN is not a privacy guarantee. A VPN changes your network exit, not your
identity. Websites, apps, Oracle, the exit network, DNS infrastructure, and
other systems may still observe traffic metadata.

Use at your own risk. Do not use ZeroVPN for anything that requires a
professionally audited security product.

## Installation

Download the v0.1 pre-release APK from GitHub:

https://github.com/Undert0e-505/zero-vpn/releases/tag/v0.1

Install `ZeroVPN-v0.1.apk`. The public release APK is signed. Android will ask
you to allow installation from the browser or file manager you use to open the
APK.

## Before Using ZeroVPN

You need:

- An Android device.
- An Oracle Cloud account.
- Oracle account email, password, and MFA working in a browser.
- Oracle Free Tier / Always Free eligibility.
- An internet connection.

ZeroVPN cannot create your Oracle account for you. It does not bypass Oracle
login, payment-card verification, region checks, or MFA.

Useful Oracle links:

- Oracle Cloud Free Tier signup: https://signup.oraclecloud.com/
- Oracle Cloud Free Tier overview: https://www.oracle.com/cloud/free/
- Oracle MFA documentation: https://docs.oracle.com/en-us/iaas/Content/Identity/Tasks/usingmfa.htm
- Oracle Cloud Console: https://cloud.oracle.com/

## Basic Usage

1. Install the APK from the v0.1 release.
2. Open ZeroVPN.
3. Choose **Add Exit**.
4. Choose **Create Oracle Free Exit**.
5. Sign in to Oracle when the browser opens.
6. Return to ZeroVPN.
7. Let ZeroVPN discover your Oracle account region and provision the VM.
8. Connect.

If region discovery fails, the app may ask you to choose your Oracle
account/tenancy region manually. This is an Oracle account setting, not a
statement about where you physically live.

## Build From Source

Requirements:

- Windows-native PowerShell.
- Android Studio or Android SDK installed.
- JDK available to Gradle.
- `android/local.properties` with `sdk.dir=...` if Gradle cannot find your SDK.

Debug build:

```powershell
cd android
.\gradlew.bat assembleDebug
```

Release dry-run:

```powershell
.\scripts\build-apk.ps1 -Version 0.1 -DryRun -PreRelease -Clean
```

Real release builds require local signing configuration and GitHub CLI
authentication. Do not commit local signing files or release APKs.

## Release Signing

Public release APKs are signed.

Local signing configuration is intentionally ignored by git. Use
`android/signing.properties` or the supported signing environment variables.
Do not commit `signing.properties`, keystores, passwords, APKs, or AAB files.

## Privacy / Security Model

ZeroVPN's current model is bring your own exit.

It reduces dependence on a commercial VPN provider, but it does not make you
anonymous. The Oracle VM is still a cloud VM in your Oracle account, and traffic
exits from that VM.

You are responsible for your Oracle account and any infrastructure created in
it. Destroy exits when you no longer need them.

## License

MIT. See `LICENSE`.
