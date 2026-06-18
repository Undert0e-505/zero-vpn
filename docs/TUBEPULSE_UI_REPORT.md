# TubePulse UI Mining Report

Source: `D:\dev\tubepulse` (React Native + Expo, not native Kotlin/Compose)
Date: 2026-06-18

## Summary

TubePulse is a React Native / Expo app, not a native Kotlin/Compose app.
ZeroVPN will use Kotlin + Jetpack Compose, so we cannot reuse code
directly. We mine the *visual language* and *structural conventions*
instead, translating them to Compose equivalents.

## Colour palette

| Token | Hex | Usage |
|---|---|---|
| `bg` | `#0D0D0D` | App background |
| `surface` | `#1A1A1A` | Cards, option buttons, placeholders |
| `surfaceTranslucent` | `rgba(26, 26, 26, 0.85)` | Overlays |
| `text` | `#E0E0E0` | Primary text |
| `textDim` | `#666666` | Secondary text, metadata, section titles |
| `accent` | `#4FC3F7` | Active state, new dot, glow, links |
| `accentGlow` | `rgba(79, 195, 247, 0.3)` | Avatar glow, section highlight |
| `border` | `#2A2A2A` | Hairline borders between sections |
| `danger` | `#EF5350` | Destructive actions |

Dark-only. No light theme. `userInterfaceStyle: "dark"` in app.json.

## Compose translation

```kotlin
// ZeroVPN theme — TubePulse-inspired, Compose-native
val Bg = Color(0xFF0D0D0D)
val Surface = Color(0xFF1A1A1A)
val TextPrimary = Color(0xFFE0E0E0)
val TextDim = Color(0xFF666666)
val Accent = Color(0xFF4FC3F7)
val AccentGlow = Color(0x4D4FC3F7) // 30% opacity
val Border = Color(0xFF2A2A2A)
val Danger = Color(0xFFEF5350)
```

## Typography

- Primary text: 15sp, weight 400-600
- Section titles: 12sp, weight 600, uppercase, letter-spacing 0.5
- Body/metadata: 12-13sp, `textDim` colour
- Channel/exit names: 18sp, weight 600
- Button text: 14sp, weight 500-700 (700 when active)

No custom font family — system default (Roboto on Android).

## Button treatment

- **Option buttons:** `surface` background, `border` stroke, 8dp
  corner radius, 9-10dp vertical padding. Active state: `accent`
  background + `accent` border, text becomes `bg` colour + weight 700.
- **Toggle rows:** label left, Switch right, 5dp vertical padding.
  Switch track: `border` (off), `accent` (on). Thumb: `textDim` (off),
  `bg` (on).
- **Destructive:** `danger` colour for text or background.

## Screen spacing

- Horizontal padding: 16dp
- Section title margin top: 12dp (first section: 4dp)
- Section title margin bottom: 6dp
- Row padding vertical: 5-8dp
- Content bottom padding: 40dp (scroll end space)

## Layout patterns

- `ScrollView` / `FlatList` with 16dp horizontal padding
- Card/section: no explicit card background; sections separated by
  hairline `border` colour (`StyleSheet.hairlineWidth`)
- "New" highlight: `rgba(79, 195, 247, 0.04)` tinted background on the
  section + accent border/glow on avatar
- Empty state: centred, `textDim` text + `accent` action link

## Architecture conventions

TubePulse uses:
- Screen-based navigation (React Navigation)
- `useFocusEffect` for screen load
- `AppState` listener for foreground refresh
- Local storage via `AsyncStorage` (key-value)
- Refs to keep callbacks stable across re-renders

**ZeroVPN Compose equivalent:**
- `NavHost` + `Composable` destinations
- `LaunchedEffect` / `DisposableEffect` for screen lifecycle
- `EncryptedSharedPreferences` for local storage
- `ViewModel` + `StateFlow` for state management
- Single-activity architecture

## Build conventions

- JDK 21 at `C:\Program Files\Java\jdk-21`
- Android SDK at `D:\dev\android-sdk`
- `assembleRelease` for APK
- `apksigner` for signing
- `build-and-release.ps1` pattern (version bump → build → sign → commit
  → push → release)
- No WSL, no EAS, no `gh` CLI — pure Windows-native PowerShell

## What NOT to copy

- TubePulse product logic (YouTube feed, channel management, widgets)
- TubePulse's Cloudflare Worker backend
- TubePulse's FCM/notification system
- TubePulse's React Native / Expo build system

## What TO reuse

- Colour tokens → Compose `Color` constants
- Typography scale → Compose `TextStyle` definitions
- Button/option treatment → Compose `Button` / `FilterChip` styling
- Section title pattern → Compose `Text` with uppercase + letter-spacing
- Build script pattern → adapted `build-and-release.ps1` for ZeroVPN
- Dark-only theme
- 16dp horizontal padding convention
- Hairline border between sections