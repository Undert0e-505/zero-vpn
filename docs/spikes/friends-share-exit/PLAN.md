# Friends / Share Exit Spike Implementation Plan

## Scope

This is a planning spike only. Do not implement app code, tag, release, or merge as part of this spike.

The feature adds social sharing for owner-created Oracle Free Exits. A newly provisioned Oracle exit should create the owner's WireGuard profile plus three pre-created friend invite profiles. The owner can share each friend profile by QR code without Oracle reauthentication because the server peer already exists. The QR remains shareable until the owner app verifies the first successful WireGuard handshake for that invite peer. At that point the owner app burns the local share material by deleting the friend client private key/config, while retaining public metadata and friend status.

## Current Repo Baseline

Relevant current implementation points:

- Android app code lives under `android/app/src/main/java/com/zerovpn/app`.
- Oracle provisioning is implemented in `oci/OciProvisioner.kt`.
- `OciProvisioner.ProvisionResult` currently returns one owner client config and key metadata.
- The embedded `SETUP_WG_SCRIPT` in `OciProvisioner.kt` writes `/etc/wireguard/wg0.conf` with one `[Peer]`.
- `harness/setup-wg.sh` and `harness/oci-full-pipeline.py` still reflect older single-peer setup patterns.
- Node installer assets live under `node/install`, `node/scripts`, and `node/templates`.
- `node/scripts/add-peer.sh` and `node/scripts/revoke-peer.sh` already demonstrate dynamic peer add/revoke scripts, but the Oracle Android provisioning path currently uses the embedded setup script.
- The current tunnel network is `10.66.66.0/24`, with server `10.66.66.1/24` and owner `10.66.66.2/32`. The product examples use `10.8.0.0/24`, but this plan should preserve `10.66.66.0/24` unless Phase 0 discovers a compelling reason to migrate.
- `ConfiguredExit.kt` currently has `ExitProvider.OCI` and `ExitProvider.VOLUNTEER`.
- Configured exits are persisted as JSON in `ProvisioningViewModel` under `configured_exits_json` using regular `SharedPreferences`.
- `ProvisioningViewModel` intentionally does not persist SSH private keys today: `toJson()` writes `sshPrivateKey` as `JSONObject.NULL`.
- `AddExitScreen.kt` already has a disabled `Scan QR Invite` entry point.
- `NodeInviteScreen.kt` exists as a placeholder and is currently labelled "Nodes" through `Screen.NodeInvite` in `NavGraph.kt`.
- `HomeScreen.kt`, `VpnViewModel.kt`, and `WireGuardTunnelController.kt` already coordinate a single active exit across provider types.

## Product Model

An owner-created Oracle exit becomes a managed shareable exit. It owns:

- the owner's local WireGuard profile
- three invite slots
- SSH/server management metadata where available
- OCI resource metadata for destroy and owner verification

An imported shared exit becomes a local WireGuard profile on the recipient device. It does not have OCI authority, VM destroy, friend management, or server mutation actions.

## Provisioning Design

### Peer Allocation

Keep the existing subnet unless Phase 0 changes it:

| Role | Tunnel IP |
| --- | --- |
| Server | `10.66.66.1/24` |
| Owner | `10.66.66.2/32` |
| Friend slot 1 | `10.66.66.3/32` |
| Friend slot 2 | `10.66.66.4/32` |
| Friend slot 3 | `10.66.66.5/32` |

Phase 0 must inspect every hardcoded assumption around `10.66.66.2`, `/24`, DNS, and `AllowedIPs` before finalizing. Current candidates include:

- `OciProvisioner.kt`
- `harness/setup-wg.sh`
- `harness/oci-full-pipeline.py`
- `harness/pipeline.py`
- `node/scripts/add-peer.sh`
- `node/install/install.sh`
- `node/templates/*.template`
- documentation references in `docs/`

### Provision Result

Change `OciProvisioner.ProvisionResult` from a single client result to a richer result:

```kotlin
data class ProvisionResult(
    val publicIp: String,
    val wireGuardPort: Int,
    val ownerProfile: WireGuardPeerProfile,
    val inviteProfiles: List<InvitePeerProvisionResult>,
    val serverPublicKey: String,
    val sshUsername: String,
    val sshPrivateKey: String,
)
```

Each generated peer profile should include:

- slot id for invite profiles: `friend-1`, `friend-2`, `friend-3`
- client private key
- client public key
- optional preshared key only if the implementation can apply it consistently to server and client with low risk
- tunnel IP/CIDR
- endpoint host and port
- server public key
- DNS settings
- allowed IPs
- generated standard WireGuard config text

### Server Config

At provisioning time, `/etc/wireguard/wg0.conf` should include one `[Peer]` per owner/friend profile:

```ini
[Peer]
PublicKey = <owner-public-key>
AllowedIPs = 10.66.66.2/32

[Peer]
PublicKey = <friend-1-public-key>
AllowedIPs = 10.66.66.3/32

[Peer]
PublicKey = <friend-2-public-key>
AllowedIPs = 10.66.66.4/32

[Peer]
PublicKey = <friend-3-public-key>
AllowedIPs = 10.66.66.5/32
```

Implementation should prefer a structured script input over ad hoc environment variables. For example, write a temporary peer file or JSON-like shell-safe lines to the VM, then generate `wg0.conf` from that list. Avoid command lines containing all private material.

NAT/forwarding rules should remain subnet-based for `10.66.66.0/24`, so all peers are routed without additional firewall work.

## Invite Slot Lifecycle

### States

```kotlin
enum class InviteSlotState {
    UNUSED,
    PENDING_CLAIM,
    CLAIMED,
    REVOKED,
}
```

### State Machine

```text
UNUSED
  - slot exists
  - server peer exists
  - owner device stores shareable client config/private key securely
  - QR may never have been shown
  - owner can name slot and show QR

UNUSED --show QR--> PENDING_CLAIM

PENDING_CLAIM
  - QR has been shown at least once
  - owner may re-show QR
  - owner may edit label/name
  - owner may check handshake status
  - app may auto-poll while QR screen is open
  - owner device still stores shareable client config/private key securely

PENDING_CLAIM --first non-zero latest-handshake for peer--> CLAIMED

CLAIMED
  - first successful handshake verified by owner app
  - owner device deletes shareable client config/private key
  - QR cannot be displayed again
  - server peer remains active
  - friend can continue using the exit
  - slot retains name, public key, tunnel IP, firstHandshakeAt, lastHandshakeAt
  - owner can revoke/reset after verification

CLAIMED --owner verification + server mutation--> UNUSED

REVOKED
  - old server peer removed or replaced
  - old QR/config no longer works
  - transient or audit state before resetting to UNUSED with a new keypair
```

### Burn Rule

Burn only after the owner app verifies a first successful WireGuard handshake for that invite peer.

Do not burn when:

- QR is displayed
- QR is scanned locally
- recipient import succeeds
- recipient merely stores the config

Burn when:

- owner app connects to the VM over SSH
- owner app runs `sudo wg show wg0 latest-handshakes`
- output contains the invite peer public key with a non-zero timestamp
- app records `firstHandshakeAt` and `lastHandshakeAt`
- app deletes the owner-held friend private key/config
- slot state becomes `CLAIMED`

The burn operation should be idempotent. If the private key/config is already gone but the slot is marked `CLAIMED`, the app should not surface an error.

## Handshake Detection

Preferred source is SSH to the VM, not Oracle API:

```bash
sudo wg show wg0 latest-handshakes
```

The app should parse lines shaped like:

```text
<peer-public-key>\t<unix-timestamp-seconds>
```

Rules:

- match by exact invite peer public key
- timestamp `0` means no confirmed handshake
- timestamp greater than `0` means the peer has connected at least once
- store timestamps as epoch millis in Android models
- update `lastHandshakeAt` whenever a newer timestamp is observed
- set `firstHandshakeAt` only once

Checking handshake should not require Oracle reauth if SSH details are available. Because current persistence drops `sshPrivateKey`, the implementation must decide whether to persist a management key securely for owner-created shareable exits. If SSH credentials are missing, show owner verification and use Oracle reauth/recovery flow where possible.

## Revoke And Reset

Revoke/reset is a server mutation and should be presented as "Owner verification required."

Preferred execution path:

1. Verify owner authority.
2. SSH to the VM if SSH details are available.
3. Remove old peer by public key from the running interface.
4. Remove or replace the old peer entry in `/etc/wireguard/wg0.conf`.
5. Generate a new keypair for the same slot.
6. Add the new peer with the same slot tunnel IP unless collision/audit findings require a new IP.
7. Write the new server peer entry.
8. Reload WireGuard safely.
9. Return/store the new shareable client config.
10. Mark slot `UNUSED`.
11. Preserve the friend label only if product chooses to keep history; otherwise reset to "Friend slot N".

Safe reload options to evaluate:

- `sudo wg syncconf wg0 <(sudo wg-quick strip wg0)` if shell support is acceptable
- `sudo systemctl reload wg-quick@wg0` if supported
- `sudo systemctl restart wg-quick@wg0` as a fallback, with clear UX that active clients may briefly disconnect

The old peer must stop working immediately after revoke.

Danger dialog copy:

Title: `Revoke and reset invite?`

Body: `This will stop this shared exit from working for <name>. ZeroVPN will create a new unused invite slot afterwards. The old QR will never work again.`

Buttons: `Cancel`, `Revoke and reset`

## Friends Screen

Repurpose `NodeInviteScreen.kt` as `FriendsScreen.kt` and rename navigation:

- `Screen.NodeInvite` route can be migrated to `Screen.Friends`
- bottom navigation label changes from `Nodes` to `Friends`
- consider `Icons.Default.Group` or similar instead of `Hub`

### Empty State

Before an owner-created Oracle exit exists:

`No shareable exit yet. Create an Oracle Free Exit first, then you can share it with trusted friends.`

CTA should route to `Add Exit` or directly to Oracle provisioning depending on current app convention.

### With Oracle Exit

Show three invite slots for the selected or first owner-created Oracle exit. If multiple Oracle exits exist, Phase 1 should decide whether Friends screen manages:

- selected owner Oracle exit only, matching current selected-exit model, or
- a small owner-exit selector at the top

Recommended first implementation: manage the selected owner Oracle exit; if none is selected, use the first owner Oracle exit and provide a compact selector later.

Each slot shows:

- slot label/name
- state: `Unused`, `Pending`, `Claimed`, `Revoked`
- tunnel IP
- first connected timestamp if available
- last seen timestamp if available
- state-specific actions

### Slot Behavior

Unused:

- tap slot
- prompt: `Who is this for?`
- save label
- show QR
- transition to `PENDING_CLAIM` once QR is shown

Pending:

- show name
- show QR again
- show `Waiting for first connection`
- allow label rename
- allow manual `Check status`
- optionally auto-poll while QR screen is open
- if first handshake detected, transition to `CLAIMED` and burn local share material

Claimed:

- show name
- show first connected and last seen if available
- do not show QR
- allow label rename if product wants persistent local names
- show `Revoke and reset invite` with danger confirmation

Revoked/reset:

- perform owner verification
- mutate server peer
- create replacement keypair/config
- mark slot `UNUSED`
- no QR shown automatically unless owner taps again

## QR Sharing

### Owner Side

QR payload should be a standard WireGuard client config so it remains compatible with the official WireGuard app:

```ini
[Interface]
PrivateKey = <friend-private-key>
Address = 10.66.66.3/32
DNS = 1.1.1.1

[Peer]
PublicKey = <server-public-key>
Endpoint = <owner-vm-public-ip>:51820
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
```

If ZeroVPN metadata is needed, prefer one of these approaches:

- store metadata separately in owner/recipient app storage after import
- use WireGuard comments only if import compatibility is verified
- avoid wrapping the config in a custom JSON format for the default QR

Owner QR warning:

`Anyone who scans this QR can use your exit until you revoke this invite. Only share it with someone you trust. Their traffic will use your Oracle VM's public IP.`

QR generation dependency options:

- AndroidX/Compose bitmap generation using ZXing core
- `com.google.zxing:core` only, rendered into an Android `Bitmap`

Recommended: add ZXing core for QR encode/decode primitives, and keep camera scanning separate through CameraX/ML Kit or ZXing embedded only after dependency review.

### Recipient Side

Use the existing `Add Exit / Scan QR Invite` entry point.

The recipient flow should:

1. request camera permission
2. show an in-app camera preview
3. scan a QR without launching an external camera app
4. parse WireGuard config
5. validate required fields
6. create a local shared exit profile
7. show import success
8. route to Home or select the new exit

Imported shared exits:

- appear on Home
- are subtly visually different from owner Oracle exits
- are renameable anytime
- require no Oracle account
- connect/disconnect like any WireGuard exit
- can be removed locally

Camera implementation should use Android permission `android.permission.CAMERA` in `AndroidManifest.xml`.

Permission denied copy:

`Camera access is needed to scan an invite QR. You can allow camera access and try again.`

If permanently denied, show an app-settings path.

Recommended camera dependencies to evaluate:

- CameraX: `androidx.camera:camera-camera2`, `androidx.camera:camera-lifecycle`, `androidx.camera:camera-view`
- ML Kit Barcode Scanning: `com.google.mlkit:barcode-scanning`
- ZXing core for QR encoding and fallback decoding utilities

CameraX + ML Kit is the recommended in-app scanner direction because it fits Compose and modern Android permission flows.

## Home Screen Behavior

Imported shared exits must appear in existing Home exit cards and respect the single active exit model.

Owner-created Oracle exit:

- label as Oracle Free Exit or current naming pattern
- connect/disconnect
- destroy VM
- manage Friends/share access
- developer diagnostics

Imported shared exit:

- label: `Shared Exit`
- small shared badge
- connect/disconnect
- rename
- remove local profile
- no destroy VM
- no Friends management
- no server management
- no Oracle reauth prompts

Connection coordinator:

- shared exits should be WireGuard-backed profiles
- connecting a shared exit should disconnect any active Volunteer exit first
- connecting a shared exit should disconnect any active Oracle exit first
- connecting Oracle/Volunteer should disconnect shared WireGuard exit first
- only one active exit at a time

Implementation likely needs either:

- add `ExitProvider.SHARED_WIREGUARD`, or
- add a `profileKind`/`ownership` field while keeping provider as `OCI`

Recommended: add `ExitProvider.SHARED_WIREGUARD` or `ExitOwnership.IMPORTED_SHARED` to avoid conflating recipient profiles with owner-created OCI exits. The plan below uses `ExitProvider.SHARED_WIREGUARD` for clarity.

## Data Model

### Configured Exit

Extend `ConfiguredExit` or introduce a clearer `ExitProfile` model. A narrow extension is lower risk for the spike implementation.

Proposed fields:

```kotlin
enum class ExitProvider {
    OCI,
    VOLUNTEER,
    SHARED_WIREGUARD,
}

enum class ExitOwnership {
    OWNER_MANAGED,
    IMPORTED_SHARED,
    LOCAL_VOLUNTEER,
}

data class ConfiguredExit(
    val id: String,
    val name: String,
    val provider: ExitProvider,
    val ownership: ExitOwnership,
    val publicIp: String,
    val endpointHost: String,
    val endpointPort: Int,
    val wireGuardConfig: String,
    val ownerOracleExitId: String? = null,
    val inviteSlots: List<InviteSlot> = emptyList(),
    ...
)
```

Owner Oracle exit:

- `provider = OCI`
- `ownership = OWNER_MANAGED`
- contains owner WireGuard config
- contains three invite slots
- contains OCI resource ids
- contains API key identifiers for destroy
- may contain securely stored SSH management key or a reference to one

Imported shared exit:

- `provider = SHARED_WIREGUARD`
- `ownership = IMPORTED_SHARED`
- contains recipient's imported WireGuard config
- no OCI resource ids
- no SSH credentials
- no invite slots
- `destroyMeaning = removeLocalProfile`

Volunteer exit:

- `provider = VOLUNTEER`
- `ownership = LOCAL_VOLUNTEER`
- no WireGuard config

### Invite Slot

```kotlin
data class InviteSlot(
    val id: String,
    val oracleExitId: String,
    val slotNumber: Int,
    val label: String,
    val state: InviteSlotState,
    val tunnelIp: String,
    val peerPublicKey: String,
    val encryptedClientPrivateKeyRef: String? = null,
    val encryptedClientConfigRef: String? = null,
    val firstHandshakeAt: Long? = null,
    val lastHandshakeAt: Long? = null,
    val revokedAt: Long? = null,
    val localProfileId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
```

`localProfileId` is normally null on the owner. It is useful if the owner imports their own QR for testing or if future same-device flows need a relationship. Recipient imported exits should store source metadata such as `sharedFromPublicKey` or `serverPublicKey`, but should not be tied to the owner slot unless the QR contains compatible metadata.

### Secure Storage

Current `SharedPreferences` persistence is not enough for friend invite private keys.

Required security changes:

- store invite client private keys/configs encrypted at rest while `UNUSED` or `PENDING_CLAIM`
- after burn, delete encrypted private key/config entries
- retain only public metadata in normal JSON: public key, tunnel IP, timestamps, state, label
- recipient stores imported WireGuard config as their own secret material, also encrypted
- avoid logging configs, private keys, QR payloads, or SSH keys

Recommended implementation:

- add AndroidX Security Crypto `EncryptedSharedPreferences` or a small Keystore-backed encrypted blob store
- keep non-secret profile metadata in `configured_exits_json` or migrate all exit storage into an encrypted repository
- persist SSH management key only if needed for Friends operations, and only in encrypted storage

Migration:

- read existing `configured_exits_json`
- assign default `ownership` by provider
- initialize `inviteSlots = emptyList()` for existing OCI exits
- no retroactive invite slots for existing servers unless a migration/reconcile flow is built
- preserve existing selected exit id

## Server-Side Implementation Plan

### Provisioning Script

Change the Android embedded `SETUP_WG_SCRIPT` first, then mirror the same logic into `harness/setup-wg.sh` if still used by tests/manual flows.

Provisioning should:

1. generate owner + three invite client keypairs in Android using `com.wireguard.crypto.KeyPair`, or generate server-side and return private keys once
2. generate server keypair on VM
3. write `wg0.conf` with all four peer public keys
4. enable IPv4 forwarding
5. configure NAT/forwarding for `10.66.66.0/24`
6. enable and restart `wg-quick@wg0`
7. return server public key and verification output
8. build standard client configs in Android

Prefer generating client keypairs in Android. It keeps the friend private keys off the VM and avoids writing shareable client configs under `/etc/wireguard/peers` during initial provisioning.

### Revoke/Reset Script

Implement a small script or SSH command sequence that accepts:

- slot id
- old peer public key
- new peer public key
- slot tunnel IP

Behavior:

1. verify `wg0` exists
2. remove old peer from running interface if present
3. remove old `[Peer]` block from `/etc/wireguard/wg0.conf`
4. append replacement `[Peer]` block with the new public key and same `/32`
5. apply the config
6. verify `wg show wg0 allowed-ips` reports the new peer and not the old peer

Use structured config editing. Shell `sed` block deletion by public key is possible but fragile; safer options include:

- write a simple server-side helper script with clear markers per slot
- include comments before peer blocks, such as `# ZeroVPNInviteSlot = friend-1`
- rewrite the entire config from a known peer list stored by the app

Recommended server config comments:

```ini
# ZeroVPNPeer = owner
[Peer]
PublicKey = ...
AllowedIPs = 10.66.66.2/32

# ZeroVPNInviteSlot = friend-1
[Peer]
PublicKey = ...
AllowedIPs = 10.66.66.3/32
```

## UX Copy

Friends empty state:

`No shareable exit yet. Create an Oracle Free Exit first, then you can share it with trusted friends.`

Share warning:

`Anyone who scans this QR can use your exit until you revoke this invite. Only share it with someone you trust. Their traffic will use your Oracle VM's public IP.`

Name prompt:

Title: `Who is this for?`

Field placeholder: `Friend name`

QR screen:

Title: `Share invite with <name>`

Body: `Ask them to scan this with ZeroVPN or any WireGuard-compatible app. You can show this QR again until they connect for the first time.`

Waiting:

`Waiting for first connection`

Status detail:

`ZeroVPN will hide this QR after the first successful WireGuard handshake is detected.`

Invite claimed/burned:

`Invite claimed. The share QR was removed from this device. <name> can keep using the exit until you revoke it.`

Revoke/reset danger:

Title: `Revoke and reset invite?`

Body: `This will stop this shared exit from working for <name>. ZeroVPN will create a new unused invite slot afterwards. The old QR will never work again.`

Recipient import success:

`Shared Exit added. You can rename it or connect from Home.`

Recipient remove local profile:

Title: `Remove shared exit?`

Body: `This removes the shared exit from this device. It does not revoke the invite or change the owner's Oracle VM.`

Camera permission denied:

`Camera access is needed to scan an invite QR. You can allow camera access and try again.`

## Implementation Phases

### Phase 0: Plan And Inspect Existing Code

Files likely touched:

- `docs/spikes/friends-share-exit/PLAN.md`
- no app code

Checks:

- `git status --short`
- review hardcoded WireGuard subnet references
- review current exit persistence and single active exit behavior

Manual validation:

- confirm current integration branch builds before later implementation work
- confirm no dirty worktree before starting feature implementation

Rollback risks:

- none; documentation only

### Phase 1: Data Model And Storage Migration

Files likely touched:

- `android/app/src/main/java/com/zerovpn/app/vpn/ConfiguredExit.kt`
- `android/app/src/main/java/com/zerovpn/app/ui/provisioning/ProvisioningViewModel.kt`
- new storage/repository classes under `android/app/src/main/java/com/zerovpn/app/storage` or similar
- `android/gradle/libs.versions.toml`
- `android/app/build.gradle.kts`

Work:

- add shared exit provider/ownership distinction
- add `InviteSlot` and `InviteSlotState`
- add JSON serialization/deserialization migration
- add encrypted storage for private configs/keys
- migrate existing exits safely
- ensure existing Volunteer and OCI exits still load

Checks:

- `.\gradlew.bat :app:assembleDebug`
- unit tests if test harness exists or is added
- manual app launch with existing persisted data

Manual validation:

- existing Oracle exit remains visible
- existing Volunteer exit remains visible
- no invite slots appear for legacy OCI exits unless intentionally migrated

Rollback risks:

- persistence migration can hide existing exits if JSON parsing is wrong
- storing secrets securely may require dependency changes and migration testing

### Phase 2: Oracle Provisioning Creates Owner Plus Three Invite Peers

Files likely touched:

- `android/app/src/main/java/com/zerovpn/app/oci/OciProvisioner.kt`
- `android/app/src/main/java/com/zerovpn/app/ui/provisioning/ProvisioningViewModel.kt`
- `harness/setup-wg.sh`
- `harness/oci-full-pipeline.py`
- `node/templates/wg0.conf.template`
- docs that describe provisioning

Work:

- generate four client keypairs
- assign owner and invite tunnel IPs
- update setup script to write all peer public keys
- return owner config plus invite profile data
- create three `InviteSlot` records on successful provisioning
- store invite configs/private keys encrypted

Checks:

- `.\gradlew.bat :app:assembleDebug`
- run provisioning in dev mode against a disposable OCI account/VM
- SSH to VM and run `sudo wg show wg0 allowed-ips`

Manual validation:

- owner can connect normally
- server shows four peers
- Friends screen can read three slots after provisioning

Rollback risks:

- bad `wg0.conf` generation can prevent owner connection
- peer IP collisions can break routing
- secrets can leak if logs include generated configs

### Phase 3: Friends Screen Replaces Nodes

Files likely touched:

- `android/app/src/main/java/com/zerovpn/app/ui/navigation/NavGraph.kt`
- `android/app/src/main/java/com/zerovpn/app/ui/screens/NodeInviteScreen.kt` renamed or replaced
- `android/app/src/main/java/com/zerovpn/app/ui/screens/HomeScreen.kt` for entry points if needed

Work:

- rename bottom navigation label to Friends
- implement empty state
- show selected Oracle exit invite slots
- add name prompt
- add state-specific slot actions
- add calm error/empty states for missing SSH or missing encrypted config

Checks:

- `.\gradlew.bat :app:assembleDebug`
- Compose preview/manual visual checks on small and large screens

Manual validation:

- no Oracle exit shows empty state
- Oracle exit shows exactly three invite slots
- legacy Oracle exit without slots shows a clear unsupported/migration state

Rollback risks:

- navigation route rename can break saved back stack
- selected-exit ambiguity if multiple Oracle exits exist

### Phase 4: QR Generation For Owner Share Slots

Files likely touched:

- `android/gradle/libs.versions.toml`
- `android/app/build.gradle.kts`
- new QR utility under `android/app/src/main/java/com/zerovpn/app/qr`
- Friends screen QR UI

Work:

- add QR generation dependency
- render standard WireGuard config as QR
- show share warning
- transition `UNUSED` to `PENDING_CLAIM` after QR is displayed
- allow re-show while pending
- block QR after claimed/burned

Checks:

- `.\gradlew.bat :app:assembleDebug`
- scan generated QR using official WireGuard app and ZeroVPN scanner once available

Manual validation:

- QR payload is readable text WireGuard config
- QR remains available while pending
- QR not available for claimed slot

Rollback risks:

- QR may be too dense if config grows
- comments/metadata may reduce compatibility

### Phase 5: In-App QR Scanner And Import Flow

Files likely touched:

- `android/app/src/main/AndroidManifest.xml`
- `android/gradle/libs.versions.toml`
- `android/app/build.gradle.kts`
- `android/app/src/main/java/com/zerovpn/app/ui/screens/AddExitScreen.kt`
- `android/app/src/main/java/com/zerovpn/app/ui/navigation/NavGraph.kt`
- new scanner/import screens and parser classes
- `ProvisioningViewModel.kt` or new exit repository

Work:

- enable `Scan QR Invite`
- add camera permission
- implement CameraX + ML Kit scanner
- parse WireGuard config
- validate `PrivateKey`, `Address`, `[Peer] PublicKey`, `Endpoint`, and `AllowedIPs`
- create imported shared exit
- store imported config securely
- select new exit or show import success

Checks:

- `.\gradlew.bat :app:assembleDebug`
- permission denial path
- QR scan on physical Android device

Manual validation:

- camera opens in-app
- denied permission shows clear retry state
- valid QR imports
- invalid QR shows actionable error
- official WireGuard config QR imports if compatible

Rollback risks:

- camera dependencies increase APK size
- scanner lifecycle can leak camera resources if Compose lifecycle is wrong
- permissive parser may import unsafe/non-WireGuard text

### Phase 6: Home Support For Imported Shared Exits

Files likely touched:

- `HomeScreen.kt`
- `ConfiguredExit.kt`
- `ProvisioningViewModel.kt`
- `VpnViewModel.kt`
- `WireGuardTunnelController.kt` if provider assumptions need changes

Work:

- show shared badge/label
- hide destroy/manage Friends actions for shared exits
- add rename
- add remove local profile
- ensure shared exits connect through existing WireGuard path
- preserve single active exit behavior

Checks:

- `.\gradlew.bat :app:assembleDebug`
- connect/disconnect shared exit
- switch from Volunteer to shared
- switch from Oracle to shared
- switch from shared to Oracle/Volunteer

Manual validation:

- shared exit appears on Home
- shared exit cannot destroy VM
- shared exit can rename
- shared exit can remove local profile

Rollback risks:

- provider checks currently may assume any WireGuard exit is OCI
- incorrect actions could expose owner-only controls to recipients

### Phase 7: Handshake Polling And Burn Flow

Files likely touched:

- `OciProvisioner.kt` or new `OciExitManager.kt`
- `ProvisioningViewModel.kt` or new Friends view model
- Friends screen QR/status UI
- Diagnostics screen if status is exposed there

Work:

- implement SSH handshake query
- parse `latest-handshakes`
- match invite peer public key
- support manual status check
- optionally auto-poll while QR screen is open
- burn encrypted private key/config after first non-zero handshake
- update first/last handshake timestamps

Checks:

- `.\gradlew.bat :app:assembleDebug`
- unit test parser with zero/non-zero timestamps
- manual SSH query against test VM

Manual validation:

- pending slot stays pending before friend connects
- after recipient connects, owner status check marks claimed
- QR disappears
- encrypted share config is deleted
- friend remains connected or can reconnect

Rollback risks:

- missing SSH key blocks status check
- clock/timezone formatting can confuse first/last seen display
- accidental burn on wrong peer if public key matching is loose

### Phase 8: Revoke And Reset Invite

Files likely touched:

- `OciProvisioner.kt` or new owner-management class
- Friends view model/screen
- server helper script under `node/scripts` if reused
- diagnostics docs

Work:

- add owner verification flow
- SSH into VM
- remove old peer
- generate replacement keypair in app
- add replacement peer with same slot IP
- reload WireGuard
- store replacement config encrypted
- mark slot `UNUSED`
- verify old peer removed and new peer present

Checks:

- `.\gradlew.bat :app:assembleDebug`
- server command parser tests
- manual revoke on disposable VM

Manual validation:

- claimed friend loses access after revoke
- old QR no longer works
- slot returns to unused
- new QR works for a new recipient

Rollback risks:

- config rewrite can drop owner peer
- restart can interrupt active owner/friend sessions
- owner verification recovery path can be hard if SSH metadata is missing

### Phase 9: Polish, Diagnostics, Tests, Docs

Files likely touched:

- `DiagnosticsScreen.kt`
- `docs/USER_GUIDE.md`
- `docs/THREAT_MODEL.md`
- `docs/ARCHITECTURE.md`
- new tests under Android test source sets if added

Work:

- add developer diagnostics for invite slots and peer status
- keep normal UI calm
- document security model
- document owner/recipient behavior
- add parser, serialization, and state-machine tests
- verify no secrets in logs

Checks:

- `.\gradlew.bat :app:assembleDebug`
- targeted unit tests
- manual provisioning/import/revoke test matrix

Manual validation:

- diagnostics show useful peer state without exposing private keys
- user guide matches app flows
- threat model covers shared exit trust/IP attribution

Rollback risks:

- diagnostics could leak sensitive config if not redacted
- docs can drift if finalized before UI copy stabilizes

## Acceptance Criteria

Provisioning:

- new Oracle exit creates owner config plus three invite slots
- owner can connect normally
- server has owner and three invite peers
- friend slots are visible in Friends screen

Sharing:

- owner names a slot and shows QR
- QR can be shown again while `PENDING_CLAIM`
- recipient scans QR in-app and gets a new Shared Exit
- Shared Exit appears on recipient Home
- recipient can rename Shared Exit
- recipient can connect and gets owner VM exit IP

Burn:

- owner app checks VM handshake by peer public key
- first non-zero handshake marks slot `CLAIMED`
- owner-held QR/private key/config is deleted locally
- QR cannot be shown again
- slot retains friend name, public key, tunnel IP, first connected, and last seen

Revoke/reset:

- claimed slot has danger `Revoke and reset invite`
- owner verification/reauth flow runs
- old peer stops working
- slot becomes `UNUSED` with new keypair/config
- new QR can be shared

Home:

- shared exits look subtly different
- shared exits can connect/disconnect
- shared exits cannot destroy/manage VM
- shared exits can be renamed and removed locally
- only one exit is active at a time across Oracle, Volunteer, and Shared exits

Diagnostics:

- Developer Diagnostics can show invite/peer status if useful
- diagnostics never show private keys, full configs, or QR payloads
- normal UI remains calm

## Open Questions

- Should existing Oracle exits be upgradeable to add invite slots, or only newly provisioned exits?
- Should the app persist SSH management keys encrypted for owner-created exits, given Friends status/revoke depends on SSH?
- If multiple Oracle exits exist, should Friends manage the selected exit only or provide an explicit owner-exit selector?
- Should claimed friend labels remain renameable by the owner after burn?
- Should revoke/reset preserve the old friend label as history or reset the label with the slot?
- Should share QR include ZeroVPN metadata in comments, or stay pure WireGuard config for maximum compatibility?
- Should imported shared exits default to `AllowedIPs = 0.0.0.0/0` only, or preserve `::/0` when present?
- Should preshared keys be added now or deferred until after basic owner/friend flow is stable?
- What is the owner verification UX when SSH credentials are unavailable but OCI destroy metadata exists?

## Recommended First Implementation Path

Implement the smallest coherent vertical path:

1. Add data model/storage support with encrypted secret storage.
2. Update new Oracle provisioning to create three invite peers.
3. Replace Nodes with Friends and show slots.
4. Generate owner QR for pending slots.
5. Import QR as shared exit.
6. Show shared exits on Home and connect them.
7. Add handshake burn.
8. Add revoke/reset.

This order makes the first user-visible milestone "owner can provision and share a QR" while keeping destructive server mutation for later, after the data model and import path are stable.
