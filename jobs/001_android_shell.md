# Job: Android app skeleton with TubePulse-style theme

Branch: agent/2026-06-19_android-shell

## Objective

Create a buildable Kotlin + Jetpack Compose Android app that launches
with the four main screens (Home, Add Exit, Node/Invite, Diagnostics)
and a Settings/About screen. No networking. No VPN service yet.

## Context

- ZeroVPN is a VPN client + node kit. This job creates the app shell only.
- Visual design inherits TubePulse's dark theme. See
  `docs/TUBEPULSE_UI_REPORT.md` for the mined design tokens.
- Build on Windows-native PowerShell. JDK 21, Android SDK at
  `D:\dev\android-sdk`.

## Scope

- `android/` directory — full Gradle project
- `android/app/src/main/java/com/zerovpn/app/` — Kotlin source
- `android/app/src/main/res/` — resources, themes, colours
- `android/app/build.gradle` — app module build config
- `android/build.gradle` — root build config
- `android/settings.gradle` — project settings
- `android/gradle.properties` — Gradle props

## Non-goals

- No `VpnService` implementation
- No WireGuard integration
- No network calls
- No QR scanning
- No encrypted storage (placeholder is fine)
- No real state persistence (in-memory state is fine for Phase 1)

## Constraints

- Kotlin + Jetpack Compose
- `compileSdk` 36, `minSdk` 29 (Android 10+)
- Dark theme only (TubePulse-style)
- No third-party UI libraries (Material3 + Compose only)
- Windows-native PowerShell build
- APK must build with `.\gradlew assembleRelease`

## Expected files changed

- `android/settings.gradle`
- `android/build.gradle`
- `android/gradle.properties`
- `android/gradlew` + `android/gradlew.bat`
- `android/gradle/wrapper/gradle-wrapper.properties`
- `android/app/build.gradle`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/com/zerovpn/app/ZeroVpnApp.kt`
- `android/app/src/main/java/com/zerovpn/app/MainActivity.kt`
- `android/app/src/main/java/com/zerovpn/app/ui/theme/Theme.kt`
- `android/app/src/main/java/com/zerovpn/app/ui/theme/Color.kt`
- `android/app/src/main/java/com/zerovpn/app/ui/theme/Type.kt`
- `android/app/src/main/java/com/zerovpn/app/ui/navigation/NavGraph.kt`
- `android/app/src/main/java/com/zerovpn/app/ui/screens/HomeScreen.kt`
- `android/app/src/main/java/com/zerovpn/app/ui/screens/AddExitScreen.kt`
- `android/app/src/main/java/com/zerovpn/app/ui/screens/NodeInviteScreen.kt`
- `android/app/src/main/java/com/zerovpn/app/ui/screens/DiagnosticsScreen.kt`
- `android/app/src/main/java/com/zerovpn/app/ui/screens/SettingsScreen.kt`
- `android/app/src/main/java/com/zerovpn/app/ui/components/StatusCard.kt`
- `android/app/src/main/java/com/zerovpn/app/ui/components/OptionButton.kt`
- `android/app/src/main/res/values/colors.xml`
- `android/app/src/main/res/values/strings.xml`
- `android/app/src/main/res/values/themes.xml`
- `android/app/src/main/res/drawable/` (app icon placeholder)

## Implementation notes

### Theme

Use the TubePulse colour tokens translated to Compose:
- Background: `#0D0D0D`
- Surface: `#1A1A1A`
- Text primary: `#E0E0E0`
- Text dim: `#666666`
- Accent: `#4FC3F7`
- Border: `#2A2A2A`
- Danger: `#EF5350`

Section titles: 12sp, uppercase, letter-spacing 0.5, text dim.
Horizontal padding: 16dp throughout.

### Screens

**Home:** Large status indicator (disconnected state), selected mode
label, large connect/disconnect button (non-functional, shows a snackbar
or disabled state). Show empty exit list.

**Add Exit:** Four option rows: Import Config, Scan QR Invite, Create
Private Node, Volunteer Network. Each navigates or shows "coming soon".

**Node/Invite:** Empty state with "No exits configured" + "Generate
Invite" button (disabled).

**Dagnostics:** Placeholder rows for Exit IP, Country, DNS Leak Check,
Last Handshake, Logs. All show "N/A — not connected".

**Settings/About:** App name, version, "About ZeroVPN" text, links to
THREAT_MODEL.md and project repo. No settings yet.

### Navigation

`NavHost` with bottom navigation bar (5 items: Home, Add Exit, Nodes,
Diagnostics, Settings). Use Material3 `NavigationBar`.

## Tests / validation

- `.\gradlew assembleRelease` completes without errors
- APK is produced at `android/app/build/outputs/apk/release/`
- APK installs and launches on a physical Android 10+ phone
- All five screens are navigable
- Visual style matches TubePulse dark theme (dark bg, light text, accent
  blue, surface cards)

## Done when

- [ ] APK builds from PowerShell
- [ ] APK launches on a physical phone
- [ ] All five screens are reachable
- [ ] Theme matches TubePulse visual conventions
- [ ] No networking code exists
- [ ] No third-party UI dependencies beyond Compose + Material3

## Report back with

- summary
- files changed
- commands run
- tests passed/failed
- manual checks still needed
- APK path and checksum