# ZeroVPN Private Chat Integration Architecture

Status: Phase 0 repository audit  
Date: 2026-07-10  
ZeroVPN revision: 85f2e847bc656adde9090e9499be8bc759044697  
Zero Chat reference revision: fad89a4a75951b8373020c2c4732dc2146fedb99

## 1. Scope

This document maps the existing ZeroVPN implementation and identifies the boundaries at which the private Matrix node described in [ZEROVPN_PRIVATE_CHAT_NODE_PLAN.md](../ZEROVPN_PRIVATE_CHAT_NODE_PLAN.md) can integrate.

It is an audit, not an implementation design freeze. No Synapse provisioning, bootstrap service, Matrix UI, QR redemption, or server package installation was added during Phase 0.

The implementation has two distinct server paths:

1. The Android-owned OCI provisioner in [OciProvisioner.kt](../../android/app/src/main/java/com/zerovpn/app/oci/OciProvisioner.kt), which is the path used by the current app.
2. The standalone node kit under [node/](../../node), whose shell scripts and templates are useful for comparison but are not the authoritative state for Android-created exits.

They have already drifted in key generation, peer persistence, and firewall behavior. Private chat work must select one authoritative model rather than composing both paths.

## 2. Executive findings

- The current Android app can host the feature, but it is a single-module Compose application with manual dependency construction, SharedPreferences metadata, and no database, background-work framework, chat notification layer, or Matrix dependency.
- OCI provisioning is a monolithic, in-memory operation. A successful VPN is durable, but an interrupted provision cannot reliably resume at a stage or clean up resources created before the final result is returned.
- The safe chat integration seam is after WireGuard has been installed, verified, and persisted as a working exit. Chat installation must be a separate optional workload with its own durable stage ledger and server-side manifest.
- The current VM choice is VM.Standard.E2.1.Micro with Ubuntu 22.04 and a 24.04 fallback. That is not the plan's proposed VM.Standard.A1.Flex configuration and is unlikely to have the intended Synapse/PostgreSQL capacity. Shape availability and resource sizing need a separate implementation decision.
- The app's OCI security list exposes only SSH and WireGuard ingress, which is a good starting point for a private Matrix endpoint. The host firewall does not yet implement chat-only peer classes or deliberate peer isolation.
- Owner and friend WireGuard private keys are generated on Android. Only public peer keys are sent to the server. Friend configuration is retained in Android Keystore-backed storage until a handshake is seen, then burned.
- The app already has CameraX/ZXing QR scanning and QR generation. The scanner can be extended for a signed bootstrap envelope; the existing WireGuard invite codec is not an authorization format for chat.
- ZeroVPN currently uses Kotlin 2.1.0. Trixnity 5.5.2 artifacts in the Zero Chat boneyard carry Kotlin 2.3 metadata. An isolated compiler probe confirmed that the current ZeroVPN compiler rejects them. Matrix integration therefore requires either a coordinated Kotlin/build-tool upgrade or a separately validated older Trixnity release.
- The Zero Chat reference repository has no applied source license at the audited revision. Its original source must not be copied into ZeroVPN without a license grant. Independently licensed dependencies can be evaluated separately.

## 3. Current runtime topology

The current OCI path is:

    ZeroVPN Compose UI
        |
        v
    ProvisioningViewModel
        |
        +-- browser authentication and local callback
        |
        v
    OciProvisioner -- signed OCI HTTPS requests --> Oracle APIs
        |
        +-- creates VCN, subnet, gateway, route, security list, VM, API key
        |
        +-- JSch SSH --> Ubuntu VM
                              |
                              +-- installs and configures WireGuard
                              +-- enables wg-quick@wg0
                              +-- applies iptables forwarding/NAT rules

After provisioning:

    WireGuardTunnelController -- GoBackend --> Android VPN service
        |
        +-- encrypted client configuration from SecureSecretStore
        |
        v
    VM wg0 (10.66.66.1/24) --> public-interface NAT --> Internet

The proposed chat workload slots behind the existing VM's WireGuard interface:

    owner or chat-only Android peer
        |
        v
    WireGuard wg0
        |
        +-- private TLS reverse proxy :443 --> Synapse --> PostgreSQL
        |
        +-- host firewall denies chat-only peer access to:
            SSH, PostgreSQL, Synapse administration/internal listeners,
            instance metadata, other peers, and Internet forwarding

Synapse, PostgreSQL, and the reverse proxy must remain an optional workload. Their failure or removal must not rewrite the base WireGuard configuration, destroy OCI networking, or stop wg-quick.

## 4. Android application architecture

### 4.1 Stack and module structure

| Concern | Current implementation | Private-chat implication |
|---|---|---|
| Gradle | 8.12 wrapper | A Trixnity-compatible toolchain change must be tested as one coordinated upgrade. |
| Android Gradle Plugin | 8.7.3 | Must be checked alongside Kotlin, Compose, Room, and KSP upgrades. |
| Kotlin | 2.1.0 | Cannot compile against Trixnity 5.5.2 metadata; see section 12. |
| Java bytecode/toolchain | 17 | JDK 21 can run the build, while app source targets Java/Kotlin 17. |
| Android SDK | compile/target 36, minimum 29 | Compatible in principle with the proposed chat UI and Matrix client. |
| UI | Jetpack Compose, BOM 2024.12.01 | New screens should use the existing design system and navigation shell. |
| Modules | One module, [android/app](../../android/app) | Chat can start as packages in the app module; a module split should be justified by build or ownership needs. |
| Build variants | debug and release only | No product flavors currently isolate experimental private chat. |
| Feature gates | Gradle properties for HEV native and volunteer debug | A private-chat feature gate/version capability may be useful, but none exists. |

Version declarations live in [libs.versions.toml](../../android/gradle/libs.versions.toml), with application configuration in [app/build.gradle.kts](../../android/app/build.gradle.kts).

### 4.2 Entry point, navigation, and state

- [MainActivity.kt](../../android/app/src/main/java/com/zerovpn/app/MainActivity.kt) hosts the Compose application.
- [ZeroVpnApp.kt](../../android/app/src/main/java/com/zerovpn/app/ZeroVpnApp.kt) is currently an empty Application subclass.
- [NavGraph.kt](../../android/app/src/main/java/com/zerovpn/app/ui/navigation/NavGraph.kt) owns the root navigation graph and manually creates ProvisioningViewModel and VpnViewModel.
- Primary destinations are Home, Add Exit, Friends, Diagnostics, and Settings. Oracle provisioning, invite scanning, volunteer onboarding, and volunteer details are subordinate routes.
- Screen state is exposed through StateFlow from ViewModels and collected by Compose.
- There is no Hilt, Koin, Dagger, or application-scoped dependency container.

Private chat needs application-scoped Matrix sessions, repositories, encrypted persistence, notification coordination, and tunnel-aware lifecycle. Continuing to instantiate these from the navigation graph would make ownership and cleanup ambiguous. A small explicit application container is sufficient; introducing a DI framework is optional rather than a prerequisite.

### 4.3 Persistence and secure storage

Current persistence is split:

- Provisioning metadata, configured exits, resource identifiers, statuses, and active-tunnel metadata use ordinary SharedPreferences, principally through [ProvisioningViewModel.kt](../../android/app/src/main/java/com/zerovpn/app/ui/provisioning/ProvisioningViewModel.kt).
- WireGuard configurations, SSH private keys, and unredeemed friend configurations use [SecureSecretStore.kt](../../android/app/src/main/java/com/zerovpn/app/storage/SecureSecretStore.kt).
- SecureSecretStore creates an Android Keystore AES/GCM key and stores each value as an IV/ciphertext pair in a private SharedPreferences file.
- There is no Room database, SQLCipher database, DataStore, schema migration system, database-key API, key rotation, or recovery flow for corrupted encrypted records.

Private chat should extend the existing direct-Keystore approach for Matrix database passphrases, device/session credentials, TLS pin material, and bootstrap secrets. It should not replace it with Zero Chat's deprecated EncryptedSharedPreferences implementation.

The manifest currently sets allowBackup to true. Chat databases, credentials, key material, notification state, and bootstrap artifacts require explicit backup/extraction exclusions or a reviewed application-wide backup policy.

### 4.4 Networking

- OCI REST operations use OkHttp and custom OCI request signing.
- JSch supplies SSH transport to the VM.
- The app uses the official WireGuard Android tunnel library and its GoBackend.
- CameraX and ZXing provide QR capture and parsing.
- NanoHTTPD hosts the temporary localhost OCI authentication callback.
- Browser Custom Tabs are used for OCI login.
- There is no Ktor/Trixnity client, certificate-pinning layer, or app-scoped private-service resolver today.

The Matrix HTTP client must only connect to the selected node through the active WireGuard route and must validate the node's pinned TLS identity. Android route selection is necessary but is not a server-side access-control boundary.

### 4.5 VPN service behavior

[WireGuardTunnelController.kt](../../android/app/src/main/java/com/zerovpn/app/vpn/WireGuardTunnelController.kt) parses stored configurations and drives WireGuard's GoBackend. It reconciles running tunnel names and Android VPN transport state after app restart.

The manifest registers:

- WireGuard GoBackend's VPN service.
- [VolunteerVpnService.kt](../../android/app/src/main/java/com/zerovpn/app/volunteer/vpn/VolunteerVpnService.kt).

Both require BIND_VPN_SERVICE and are not exported. The current source does not demonstrate an app-owned long-running foreground notification lifecycle for private chat. VolunteerVpnService returns START_NOT_STICKY and does not call startForeground. The private-chat plan must therefore treat foreground/background behavior as an integration question, not as an already-proven capability.

### 4.6 QR support

- [ScanInviteScreen.kt](../../android/app/src/main/java/com/zerovpn/app/ui/screens/ScanInviteScreen.kt) combines CameraX analysis with ZXing decoding.
- [QrCodeGenerator.kt](../../android/app/src/main/java/com/zerovpn/app/friends/QrCodeGenerator.kt) generates QR bitmaps.
- [WireGuardInviteParser.kt](../../android/app/src/main/java/com/zerovpn/app/friends/WireGuardInviteParser.kt) parses the current friend WireGuard configuration payload.

The camera and QR rendering components are reusable integration surfaces. The chat payload requires a distinct, versioned, signed, expiring, single-use bootstrap envelope with strict size and field bounds. It must not be routed through the existing WireGuard configuration parser.

### 4.7 Notifications and background work

There is no application chat notification manager, POST_NOTIFICATIONS permission flow, WorkManager dependency, scheduled synchronization worker, or boot-time chat restoration path.

Private chat needs:

- privacy-preserving notification channels and redacted lock-screen content;
- per-node/per-conversation notification identities;
- runtime notification permission handling;
- synchronization that is gated by the correct WireGuard tunnel and Matrix session;
- explicit behavior for tunnel loss, app process death, reboot, and node deletion.

A generic network-connected WorkManager constraint is insufficient because Android may report unrelated Internet connectivity while the private Matrix route is unavailable.

### 4.8 Testing and release

No tracked app unit tests or instrumentation tests exist under android/app/src/test or android/app/src/androidTest at this revision. Existing verification is primarily build scripts, documentation, manual flows, and hardware/network checks.

The release build can be signed from ignored signing.properties data or environment variables. [build-apk.ps1](../../scripts/build-apk.ps1) performs secret scanning, build/signature checks, and release/tag operations when deliberately invoked. Candidate GitHub workflows exist for friends/HEV and volunteer debug builds, but are branch-scoped and should not be assumed to validate this feature.

Private chat requires unit, instrumentation, migration, server integration, negative-network, and encrypted Matrix interop tests before a release workflow can be treated as evidence.

## 5. OCI provisioning architecture

### 5.1 Entry and control flow

Provisioning begins in the Add Exit UI and proceeds to the Oracle provisioning route. [ProvisioningViewModel.kt](../../android/app/src/main/java/com/zerovpn/app/ui/provisioning/ProvisioningViewModel.kt) calls startProvisioning, then runProvisioning, which delegates authentication, preflight checks, and resource creation to [OciProvisioner.kt](../../android/app/src/main/java/com/zerovpn/app/oci/OciProvisioner.kt).

The main sequence is:

| Stage | Existing action | Durable at stage boundary? |
|---|---|---|
| Authentication | Generate temporary RSA key; open Oracle login; receive a security token on localhost | No. Token and signing private key remain in ViewModel memory. |
| API key | Upload generated public key to OCI | Not independently checkpointed. |
| Network | Create VCN, security list, subnet, Internet gateway, and route | Identifiers are local to the running provisioner until final success. |
| VM launch | Select image/shape and launch instance with SSH public key metadata | Not independently checkpointed. |
| SSH readiness | Wait for public IP, SSH, and cloud-init completion | Not independently checkpointed. |
| WireGuard | Upload and run setup script, then produce client configuration | Final configuration is not stored until this succeeds. |
| Commit | Persist configured exit, OCI identifiers, SSH secret, WireGuard secret | Yes, after the entire operation succeeds. |

### 5.2 Oracle authentication

The app generates an ephemeral RSA-2048 keypair and starts the browser-based Oracle bootstrap flow. NanoHTTPD listens on localhost port 8181 for the return. The security token's claims are decoded for tenancy/user data, but its signature is not verified locally; OCI remains the authority when signed API requests are made.

The security token and corresponding private signing key remain in process memory. The persisted configured-exit metadata contains user/tenancy OCIDs and the API-key fingerprint, not reusable Oracle login credentials. Destruction after a process restart therefore requires a new browser login. There is no separate Oracle logout UI or general ZeroVPN account session.

### 5.3 Compute, image, networking, and OCI firewall

The current choices are hard-coded in OciProvisioner:

- first available availability domain;
- VM.Standard.E2.1.Micro;
- newest Ubuntu 22.04 image for that shape, with Ubuntu 24.04 fallback;
- 50 GB boot volume;
- public IPv4 address;
- VCN/subnet CIDR 10.0.0.0/24;
- public TCP port 22 and UDP port 51820 ingress;
- all egress;
- Internet gateway and default route.

There is no current A1 Flex OCPU/memory configuration, dynamic shape-capacity selection, network security group, private-only SSH path, or Matrix ingress rule.

The OCI security-list shape already supports the desired private Matrix posture because port 443 need not be opened publicly. If bootstrap must be public, it needs an explicitly separate, narrowly scoped listener and ingress decision; that exception must not make the Matrix client endpoint public.

### 5.4 Remote setup

No cloud-init user-data installs the workload. The VM receives an SSH public key in launch metadata. The app then:

1. waits for the instance and public IP;
2. retries JSch SSH for up to approximately ten minutes;
3. waits for cloud-init;
4. installs packages remotely;
5. writes a generated setup script to /tmp;
6. executes it to configure WireGuard.

JSch currently disables strict host-key checking, so there is no SSH host-key pinning. A future installer should authenticate the VM more strongly or document the bootstrap trust transition.

### 5.5 Progress and failure propagation

OciProvisioner emits ProvisioningEvent values on a SharedFlow. Phases include AUTH, API_KEY, NETWORK, VM_LAUNCH, WAIT_SSH, WIREGUARD, and DONE. ProvisioningViewModel collects them, updates the UI state/log, and classifies developer diagnostics. [ProvisioningScreen.kt](../../android/app/src/main/java/com/zerovpn/app/ui/screens/ProvisioningScreen.kt) presents current progress, failure, retry, and cleanup choices.

This event stream is appropriate for chat-install progress, but it is not a durable job journal. A process restart loses the active operation and its completed stages.

### 5.6 Retry, cleanup, and destruction

Current retry clears transient state and repeats authentication and provisioning from the beginning. It does not probe or resume individual stages.

OciProvisioner returns the resource bundle only after WireGuard setup completes. If the process dies or a stage fails earlier, newly created resource identifiers may never reach persisted application state. A pending Oracle operation marker records broad operation intent, not a per-resource or per-stage manifest. As a result:

- a failed in-process operation may clean up using still-live identifiers and authentication;
- a process death can leave resources that cannot be enumerated by the app's current recovery model;
- retry can duplicate resources;
- a multi-stage optional install cannot safely rely on the existing retry method.

Destruction attempts instance, route, gateway, subnet, security list, VCN, and API-key deletion. Most individual resource deletion errors are swallowed so cleanup can continue. Local secrets and configured-exit data are removed after the destroy call returns. There is no chat-only removal path.

The generic state waiter also exhausts attempts without consistently converting a non-terminal resource into a failure and suppresses some polling errors. Chat installation should not copy that behavior.

## 6. WireGuard peer and access-control model

### 6.1 Android-owned OCI peer creation

The Android provisioning path generates:

- owner client key material locally, using 10.66.66.2;
- three friend client keypairs locally, using 10.66.66.3 through 10.66.66.5;
- server WireGuard keys on the VM.

Only client public keys are placed in the server configuration. The generated client configurations route 0.0.0.0/0. The completed server configuration is written to /etc/wireguard/wg0.conf, and wg-quick@wg0 is enabled for reboot survival.

The owner configuration and SSH private key are stored in SecureSecretStore. Friend private configurations remain encrypted on the owner's device until [InviteHandshakeChecker.kt](../../android/app/src/main/java/com/zerovpn/app/friends/InviteHandshakeChecker.kt) observes use, after which the app burns the retained copy.

### 6.2 Add, reset, and revoke behavior

[InvitePeerResetter.kt](../../android/app/src/main/java/com/zerovpn/app/friends/InvitePeerResetter.kt) uses SSH to rewrite the server's wg0.conf, remove or replace a friend peer, restart wg0, and verify that the old key is absent and the new one is present. Restarting wg0 can briefly interrupt every peer.

The standalone [add-peer.sh](../../node/scripts/add-peer.sh) and [revoke-peer.sh](../../node/scripts/revoke-peer.sh) manipulate runtime WireGuard state and local peer artifacts differently. In particular, the standalone add path does not provide the same authoritative persistence model as the Android-generated wg0.conf. These scripts should not be used to infer Android lifecycle guarantees.

Private-chat peer management needs an atomic, persistent server-side representation that records peer class, assigned /32, bootstrap state, Matrix account/device binding, and revocation state. Runtime wg changes and persisted wg0.conf must be updated consistently without restarting unrelated owner connectivity where possible.

### 6.3 Current firewall

The OCI provisioning script uses iptables commands and enables IPv4 forwarding. It:

- accepts WireGuard UDP ingress;
- accepts wg0-to-public-interface forwarding;
- accepts established return traffic;
- masquerades traffic leaving the public interface.

The host may use an nftables-backed iptables implementation, but ZeroVPN does not currently manage a deliberate nftables ruleset. There is no source-specific chat peer class, tested peer-isolation policy, or explicit denial model for host services.

The standalone installer is broader and includes forwarding behavior that may allow lateral traffic. Neither path should be considered evidence of chat-only isolation.

### 6.4 Required chat-only policy

Client AllowedIPs should be narrow, normally the private Matrix host /32. That reduces accidental routing but is not an authorization control because a modified client can send other destinations through the tunnel.

The VM must enforce policy before existing broad owner forwarding rules:

| Traffic from a chat-only peer | Required server result |
|---|---|
| Private reverse proxy TCP 443 on the node's WireGuard address | Allow |
| SSH TCP 22 | Deny |
| PostgreSQL TCP 5432 | Deny; PostgreSQL should also listen only on loopback or a private service namespace |
| Synapse administration/internal listeners | Deny; bind behind the local reverse proxy where possible |
| Instance metadata 169.254.169.254 | Deny before any forwarding/NAT accept |
| Other WireGuard peers and the rest of 10.66.66.0/24 | Deny |
| Public Internet through VM forwarding/NAT | Deny |
| Unspecified host services | Default deny |

The owner peer may retain full-exit behavior. Rules must therefore classify source /32 addresses or another server-controlled peer registry and order chat-only drops before broad wg0 forwarding accepts. Explicit wg0-to-wg0 isolation is required. Tests must cover permitted Matrix access and every required negative case.

## 7. Lifecycle and recovery map

| Lifecycle concern | Existing behavior | Private-chat requirement |
|---|---|---|
| VM/app status | ConfiguredExit has PROVISIONING, READY, DESTROYING, and FAILED; active tunnel is separate state | Add node/install stages without redefining a healthy VPN as failed |
| Health checks | Tunnel state, handshake, public IP, DNS diagnostics; friend handshake query over SSH | Separate checks for WireGuard, TLS pin, reverse proxy, Synapse, PostgreSQL, bootstrap, storage, and schema/version |
| Updates | Initial apt update/install only; no workload update channel | Versioned, resumable node update with rollback/backup policy |
| Full destruction | Re-authenticate to OCI, delete cloud resources, then remove local records/secrets | Continue to support full exit destruction, including best-effort chat cleanup |
| Chat removal | Does not exist | Stop/remove chat services, accounts, database, TLS/bootstrap material, and chat firewall rules while preserving wg0 and owner exit |
| Logout/re-authentication | OCI auth is ephemeral; destructive operations after restart require browser login | Matrix logout/device revocation and OCI re-auth must be separate concepts |
| App restart | Exit metadata in SharedPreferences; secrets in SecureSecretStore; tunnel reconciled through GoBackend | Restore node/account metadata and encrypted Matrix repository without starting against the wrong tunnel |
| VM restart | wg0.conf, wg-quick systemd unit, and forwarding sysctl persist | Chat services and firewall policy must start in dependency order and fail closed |
| Interrupted provisioning | Running operation is not restored; only broad pending-operation intent is persisted | Durable app and VM stage ledger with probe-before-apply semantics |
| Configuration authority | App persists final cloud IDs and secrets; VM owns final WireGuard file | Define authoritative, versioned node manifest and reconcile app/server views |

## 8. Safe integration boundary

The existing provision method should not simply append Synapse installation after the WireGuard shell commands. That would make a healthy exit appear failed, delay persistence of recovery information, and couple optional workload cleanup to base-network cleanup.

The required boundary is:

1. Create OCI resources and persist each identifier as soon as OCI returns it.
2. Install and verify WireGuard.
3. Persist the configured exit, owner WireGuard secret, SSH secret, and a VPN_HEALTHY checkpoint.
4. Expose the exit as a working VPN even if no chat install is requested.
5. Start a separate optional ChatNodeInstaller operation.
6. For every chat stage, persist intent and result locally and in a root-owned VM manifest.
7. On retry, probe actual state before applying an idempotent stage.
8. On chat failure, preserve wg0, OCI networking, the owner peer, and the READY VPN state.
9. On chat removal, remove only chat-owned services, files, database, accounts, TLS/bootstrap assets, and class-specific firewall rules.

An illustrative stage ledger is:

    VPN_HEALTHY
      -> CHAT_PREFLIGHT
      -> PACKAGES_READY
      -> DATABASE_READY
      -> SYNAPSE_CONFIGURED
      -> PRIVATE_TLS_READY
      -> CHAT_FIREWALL_ACTIVE
      -> BOOTSTRAP_READY
      -> OWNER_ACCOUNT_READY
      -> CHAT_HEALTHY

Each transition needs a version, timestamps, last error, safe probe, apply operation, and compensation/removal behavior. The exact stages remain an implementation decision.

## 9. Proposed Android integration areas

The following package layout is directional, not created during Phase 0:

| Area | Proposed path | Responsibility |
|---|---|---|
| Node model and lifecycle | android/app/src/main/java/com/zerovpn/app/chat/node | Durable node identity, capability/version, install state, health, remove/update orchestration |
| Matrix transport rail | android/app/src/main/java/com/zerovpn/app/chat/matrix | Trixnity boundary, TLS pinning, tunnel gating, E2EE policy |
| Bootstrap/enrollment | android/app/src/main/java/com/zerovpn/app/chat/bootstrap | Signed QR envelope, redemption, expiry/single-use validation, device/account binding |
| Chat persistence | android/app/src/main/java/com/zerovpn/app/chat/persistence | Encrypted Room/SQLCipher schema with node/account scoping |
| Domain/repository | android/app/src/main/java/com/zerovpn/app/chat/data and chat/domain | Conversations, messages, outbox, unread state, retry semantics |
| Notifications | android/app/src/main/java/com/zerovpn/app/chat/notifications | Private notification rendering and permission/channel lifecycle |
| UI | android/app/src/main/java/com/zerovpn/app/ui/chat | Existing-theme conversation list and chat screens |
| Server installer client | android/app/src/main/java/com/zerovpn/app/chat/provisioning | Idempotent SSH install/update/remove stages, progress, reconciliation |

Server scripts/configuration should live in a dedicated subtree such as server/private-chat, not be embedded as another large string in ProvisioningViewModel. The installer must version and checksum its inputs.

## 10. Configuration survival model

The target ownership model should be:

- OCI resource IDs and coarse lifecycle state: non-secret Android persistence, written incrementally.
- WireGuard, SSH, Matrix credentials, database key material, bootstrap signing material, and TLS pins: Android Keystore-backed secret storage or encrypted database as appropriate.
- Chat messages, rooms, outbox, and sync tokens: encrypted, node/account-scoped database.
- WireGuard configuration: root-owned VM file plus systemd wg-quick unit.
- Chat service state: root-owned versioned VM manifest plus systemd units.
- Firewall policy: generated from the server-side peer registry and applied atomically at boot/reconciliation.
- Synapse/PostgreSQL state: private local storage with explicit backup/update/remove policy.

No Oracle security token, OCI signing private key, invite token, database password, Matrix access token, TLS private key, or WireGuard private key may enter tracked files or diagnostic logs.

## 11. Phase 0 risks and required proofs

The following are implementation gates:

1. Demonstrate a supported Kotlin/AGP/Gradle/Compose/KSP/Trixnity version set in a disposable branch or isolated build probe.
2. Decide whether existing E2 Micro exits can ever host chat; otherwise define an A1 Flex/new-node path and capacity preflight.
3. Persist OCI identifiers and stage state incrementally before adding optional workloads.
4. Define a durable, idempotent, separately removable server installer and manifest.
5. Specify the private TLS name and pin lifecycle, including reinstall, rotation, and device enrollment.
6. Prove host-enforced chat-only access with positive and negative network tests.
7. Define the public-bootstrap exception, if any, without exposing Matrix.
8. Define Matrix device verification, key backup/recovery, revocation, and future-message exclusion.
9. Add encrypted persistence, migration, backup-exclusion, notification, and tunnel-aware background lifecycle tests.
10. Reconcile the Android-owned WireGuard path with standalone node scripts or deprecate one as a peer-management authority.

## 12. Matrix dependency compatibility evidence

Zero Chat uses Trixnity 5.5.2 and a Kotlin 2.3-era build. The cached Trixnity MatrixClient artifact reports Kotlin metadata version 2.3.0. A temporary, isolated Kotlin 2.1 compiler probe against that artifact failed because Kotlin 2.1 accepts metadata only through 2.2.

The probe did not alter production source or build files, and its temporary files were removed. Therefore:

- Trixnity 5.5.2 is not directly consumable by the current ZeroVPN compiler.
- A build-tool upgrade is likely the preferred route, but it must be evaluated with AGP, Compose, Room, KSP, WireGuard, and existing native/volunteer build paths.
- Selecting an older Trixnity version is an alternative only after checking E2EE behavior, security fixes, API support, Kotlin metadata, and license provenance.

This is a proven integration boundary, not authorization to begin the Matrix implementation in Phase 0.
