# Codex Task: ZeroVPN Android Oracle Provisioning UI

## Context

ZeroVPN is an Android app (Kotlin + Jetpack Compose) at `D:\dev\zero-vpn\android`.
A Python reference pipeline at `D:\dev\zero-vpn\harness\pipeline.py` has been proven
end-to-end: it does Oracle browser auth → API key upload → network creation →
VM launch → SSH + WireGuard install → client config generation.

The task is to implement the **Android UI** for this provisioning flow. The
actual OCI API calls will be ported separately later — this task is the
**progress/UI layer** that displays provisioning events to the user.

## What to build

### 1. ProvisioningEvent model

```kotlin
data class ProvisioningEvent(
    val timestamp: Long,           // epoch millis
    val phase: Phase,              // enum: AUTH, API_KEY, NETWORK, VM_LAUNCH, WAIT_SSH, WIREGUARD, DONE
    val status: Status,            // enum: RUNNING, SUCCESS, WARNING, ERROR
    val message: String,           // user-safe message (no secrets)
    val technicalDetail: String? = null  // redacted technical detail (no secrets)
)

enum class Phase(val number: Int, val label: String) {
    AUTH(1, "Browser auth"),
    API_KEY(2, "API key setup"),
    NETWORK(3, "Network creation"),
    VM_LAUNCH(4, "VM launch"),
    WAIT_SSH(5, "SSH connection"),
    WIREGUARD(6, "WireGuard setup"),
    DONE(7, "Complete")
}

enum class Status { RUNNING, SUCCESS, WARNING, ERROR }
```

### 2. ProvisioningViewModel

- Holds a `StateFlow<List<ProvisioningEvent>>` for the event log
- Holds a `StateFlow<Phase?>` for current phase
- Holds a `StateFlow<ProvisioningState>` (Idle, PreStart, Running, Success, Failure)
- Method `startProvisioning()` that emits events with delays simulating the pipeline
- Method `destroyNode()` that emits teardown events
- **For now, simulate the pipeline steps** with `delay()` and emit events matching
  the Python pipeline's output. The real OCI calls will be wired in later.
- Survives screen rotation (ViewModel scoped to the activity/nav graph)

### 3. Pre-start screen

When user taps "Create Oracle Free Exit" (already exists on AddExitScreen):
- Show a dialog/screen with pre-start copy:
  "Creating your Oracle exit usually takes 4-6 minutes. Keep ZeroVPN open
  while it creates the cloud network, VM, WireGuard server, and local phone config."
- Two buttons: "Start" and "Cancel"

### 4. Provisioning progress screen

- Terminal-style progress card showing live events
- Each event line: `[HH:MM:SS] [phase] message`
- Color-code by status: RUNNING=accent, SUCCESS=green, WARNING=yellow, ERROR=red
- Auto-scroll to bottom as new events arrive
- Show a phase progress indicator (Phase 3/6: Network creation)
- No spinners — live text progress instead
- The screen should look like a terminal/console output

### 5. Success screen

When provisioning completes:
- Show "Exit created successfully"
- Show public IP and WireGuard port
- Two buttons: "Connect Now" and "Destroy Node"
- "Connect Now" can just show a toast for now (WireGuard integration later)

### 6. Failure screen

When provisioning fails:
- Show "Provisioning failed at: [phase name]"
- Show the last successful phase
- Two buttons: "Retry" and "Cleanup"
- "Cleanup" attempts to destroy any partially-created resources

### 7. Navigation

- Add a new route `oracle_provision` to the NavGraph
- Navigate from AddExitScreen → oracle_provision when "Create Oracle Free Exit" is tapped

## Existing code structure

```
android/app/src/main/java/com/zerovpn/app/
  ZeroVpnApp.kt          — Application class
  MainActivity.kt         — single activity
  oci/
    OciBootstrapActivity.kt  — old spike activity (can be deleted or ignored)
    OciRequestSigner.kt      — OCI signing utility (keep for later)
  ui/
    components/
      OptionButton.kt
      StatusCard.kt
    navigation/
      NavGraph.kt          — add route here
    screens/
      AddExitScreen.kt     — add navigation to provisioning
      HomeScreen.kt
      ...
    theme/
      Color.kt            — Bg, Surface, TextPrimary, TextDim, Accent, Danger
      Theme.kt
      Type.kt
```

## Reference: Working Python Pipeline

The Python pipeline at `D:\dev\zero-vpn\harness\pipeline.py` has been proven
end-to-end. It does:

1. **Browser auth** — generates RSA keypair, builds JWK (CLI-exact format),
   opens browser to Oracle OAuth2, captures security token via localhost listener
2. **API key upload** — computes MD5 fingerprint, uploads public key as API key
3. **Network creation** — VCN, security list (SSH 22/tcp + WG 51820/udp),
   subnet, internet gateway, route table
4. **VM launch** — Ubuntu 22.04, VM.Standard.E2.1.Micro, public IP, SSH key injection
5. **SSH + WireGuard** — apt install, key generation, wg0.conf, peer config
6. **Client config** — generates client.conf with peer private key + server public key

Key files to read:
- `harness/pipeline.py` — the main pipeline (read this first)
- `harness/setup-wg.sh` — the WireGuard server setup script (scp'd to VM and run)
- `harness/teardown.py` — destroys all created resources
- `harness/oci-auth-harness.py` — original instrumented auth harness
- `android/app/src/main/java/com/zerovpn/app/oci/OciRequestSigner.kt` — existing
  Kotlin OCI signing utility (JWK generation, request signing, JWT decode)

The Android port should **simulate** the pipeline for now (delays + events).
Real OCI API calls will be wired in a follow-up task. The UX layer is this task.

## Constraints

- **No secrets in UI or logs.** Never display tokens, private keys, passwords,
  full client configs. User-safe output only: region, phase names, public IP,
  WireGuard port, "SSH connected", "WireGuard configured".
- **No spinners.** Live text progress, not indeterminate loading.
- **Survive rotation.** Use ViewModel.
- **Release build.** Must build with `./gradlew assembleRelease` (not debug —
  the debug APK has `android:debuggable=true` which Google Drive blocks).

## Theme colors (from Color.kt)

```kotlin
val Bg = Color(0xFF0D0D0D)          // dark background
val Surface = Color(0xFF1A1A1A)     // card background
val TextPrimary = Color(0xFFE0E0E0) // main text
val TextDim = Color(0xFF666666)      // secondary text
val Accent = Color(0xFF4FC3F7)       // accent/blue
val Danger = Color(0xFFEF5350)      // error/red
```

## Simulated event sequence (for the initial implementation)

```
[12:00:01] [1/7] Browser auth          RUNNING  Opening Oracle login...
[12:00:15] [1/7] Browser auth          SUCCESS  Authenticated
[12:00:16] [2/7] API key setup         RUNNING  Uploading API key...
[12:00:17] [2/7] API key setup         SUCCESS  API key uploaded
[12:00:18] [3/7] Network creation      RUNNING  Creating VCN...
[12:00:19] [3/7] Network creation      RUNNING  Creating security list...
[12:00:20] [3/7] Network creation      RUNNING  Creating subnet...
[12:00:21] [3/7] Network creation      RUNNING  Creating internet gateway...
[12:00:22] [3/7] Network creation      SUCCESS  Network ready
[12:00:23] [4/7] VM launch            RUNNING  Launching instance...
[12:01:23] [4/7] VM launch            SUCCESS  Instance running
[12:01:24] [5/7] SSH connection       RUNNING  Waiting for SSH...
[12:01:54] [5/7] SSH connection       SUCCESS  SSH connected
[12:01:55] [6/7] WireGuard setup      RUNNING  Installing WireGuard...
[12:02:25] [6/7] WireGuard setup      RUNNING  Configuring WireGuard...
[12:02:27] [6/7] WireGuard setup      SUCCESS  WireGuard configured
[12:02:28] [7/7] Complete             SUCCESS  Exit created: 141.147.106.118:51820
```

## Acceptance criteria

1. User taps "Create Oracle Free Exit" on AddExitScreen
2. Pre-start warning shown with estimated time
3. User taps "Start" → provisioning progress screen with live events
4. Events stream in (simulated for now) with timestamps and phases
5. Screen survives rotation (events preserved)
6. On success: shows public IP, "Connect Now" + "Destroy Node" buttons
7. On failure: shows last successful phase, "Retry" + "Cleanup" buttons
8. No secrets visible anywhere
9. `./gradlew assembleRelease` builds successfully