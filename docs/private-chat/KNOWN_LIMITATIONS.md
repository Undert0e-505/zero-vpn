# Private Chat Phase 1 Known Limitations

Status: Phase 1

## Product scope

This phase installs and operates the owner's private Matrix node. It does not implement:

- QR invitations or a public redemption/bootstrap service;
- invited-person accounts or WireGuard peers;
- three-seat enforcement, invite expiry, reuse prevention, or revocation;
- conversation list or chat UI;
- a persistent Android Matrix/Trixnity session;
- federation, public registration, public rooms, media, attachments, groups, or calls;
- background sync, notifications, cross-signing, device verification, or key backup.

The owner-facing Android integration is operational status only. It stores the owner credentials in Keystore-backed storage and can perform a short login/logout through the active VPN with the node's SPKI pin. It is not yet the Phase 2/4 chat client.

## Provisioning and platform

- Private Chat must be selected while creating a new Oracle exit. There is no general **Enable on existing VM** UI yet, although the VM installer itself is idempotent.
- Supported targets are Ubuntu 22.04/24.04 on aarch64 or x86_64.
- The requested A1 shape is 1 OCPU/6 GB/50 GB. Capacity and Free Tier eligibility are not guaranteed, and Oracle determines billing.
- Before Oracle authentication, owners who request Private Chat can enable **Automatically retry if A1 capacity is unavailable**. This synchronously persists policy only; it does not authenticate, enqueue background work, or make an OCI request.
- The foreground path sends one 6 GB A1 launch request. After the exact HTTP 500 `InternalError` / `Out of host capacity` response, it persists 4 GB as the next target and sends no immediate fallback request. Each later 15-minute eligibility window permits one launch request, alternating 6 GB and 4 GB only after exact capacity misses. If automatic retry was off, the capacity screen offers manual opt-in. No micro or paid-shape fallback is attempted.
- HTTP 429 is non-terminal retry state. It preserves the stored signing credential, pending memory target, retry count, and fixed deadline, and delays the same target until at least 15 minutes after the completed request (or longer for an integer-seconds `Retry-After`). It does not authenticate, upload another API key, launch the other memory configuration, or request instance cleanup when no instance exists.
- No VM resize/recreate occurs after a resource warning.
- The existing OCI provisioning operation still has the Phase 0 limitations around incremental cloud-resource persistence before WireGuard succeeds. Chat begins only after a durable working exit exists, so those limitations do not expand into the chat workload.
- SSH uses the existing ZeroVPN JSch bootstrap behavior with strict host-key checking disabled. The private TLS pin is established through that channel; stronger SSH host authentication remains required.

## Server packages and updates

- Synapse `1.156.0` and matrix-nio `0.25.2` are pinned but Python dependency hashes are not locked. A full SBOM and reproducibility workflow are pending.
- Automatic security updates and major-version/database migrations are not implemented. Operators must review updates and backups manually.
- Chat removal retains Ubuntu PostgreSQL/nginx/build packages to avoid affecting shared package ownership. It removes only the dedicated database/role and chat-owned configuration/state.
- Unmanaged existing Synapse is refused rather than adopted. Existing PostgreSQL can be shared safely at the cluster level.

## TLS and identity

- The private certificate is self-signed and trusted by SPKI pin, not public Web PKI.
- Automatic certificate/key rotation is not implemented. Reissuing from the same key preserves the pin; losing the key requires explicit recovery and app repinning.
- `server_name` is permanently tied to the generated node identity. Transparent homeserver migration is not supported.
- The Android pin verifier is scoped to `https://10.66.66.1`; private DNS and alternate subnets are not supported in this phase.

## Matrix and encryption

- The deployment uses PostgreSQL; SQLite is not supported.
- Synapse itself sees Matrix metadata and encrypted event data. E2EE does not hide identifiers, membership, timing, size, device/key traffic, or availability from the VM owner.
- The real installer self-test uses matrix-nio/libolm. The planned Android production client remains Trixnity/Vodozemac after the documented Kotlin toolchain decision. The test proves the homeserver/E2EE path, not Android device verification or long-term key lifecycle.
- A VM crash during the encrypted test can leave temporary accounts/room state until retry or operator cleanup. Normal completion purges/deactivates them.
- Encrypted event retention defaults to 30 days. This is not guaranteed secure deletion and does not erase client copies or backups.
- Media is disabled, so avatars, attachments, voice/video, and calls do not work.

## Firewall and network isolation

- Phase 1 has only the existing owner/full-VPN peers. Future chat-only policy chains are generated but intentionally unattached until Phase 3 creates restricted `/32` peers.
- Consequently, metadata/lateral/Internet-denial behavior for invitees is unit-tested at rule-generation level but not yet proven with a real invited peer.
- Diagnostics therefore reports **Chat-only peer rules active: No** in Phase 1 while separately reporting whether both deny-policy chains are ready. This is expected, not evidence of a live restricted peer.
- Existing owner/friend WireGuard peers keep their current full-exit permissions; Phase 1 does not silently reclassify them.
- Host firewall policy uses iptables because that is ZeroVPN's current authoritative provisioning system. A deliberate nftables migration is outside this phase.

## Backups and recovery

- Automated encrypted backup/export and restore are not implemented.
- Required server backup inputs include PostgreSQL, `/etc/zerovpn/private-chat`, the Synapse signing key/data paths, and the node manifest.
- A server backup does not restore future client E2EE keys. Device loss may make old history undecryptable.
- Restore, TLS rotation, Synapse schema upgrade, and disaster-recovery drills remain release gates.

## Validation status

The ordinary Python suite is isolated from optional integration dependencies
and runs on Windows. The explicit encrypted Matrix integration entry point
skips on Windows because the real matrix-nio/libolm exchange is restricted to
the disposable Ubuntu/Synapse test node. This Windows skip is not passing E2EE
runtime evidence.

Host-side state/preflight/listener/firewall/manifest/service-policy,
self-test-contract, Android parser/redaction, provisioning-log, diagnostics,
and VPN-survival contract tests run on Windows. Android JVM tests, Gradle test,
lint, and debug assembly are build-time evidence only.

No Oracle login, Oracle VM creation/change, SSH connection to a real VM, or
interactive Android run was performed in this code-preparation lane. The test
APK has not been installed or exercised on a device here. In particular, the
following remain untested until Aaron follows the operator packet:

- fresh A1 provisioning and actual Free Tier/capacity behavior;
- Ubuntu package installation and Synapse/PostgreSQL startup on aarch64;
- real encrypted Matrix exchange against the installed Synapse;
- public-port scan and WireGuard-only Matrix reachability;
- Dev Mode stage log layout, scrolling, durations, and error guidance on-device;
- Diagnostics refresh, copy-safe summary, and rendered health/stage/firewall fields on-device;
- TLS-pinned owner login/logout through the active Android WireGuard route;
- interruption/retry, reboot recovery, chat removal, and demonstrated WireGuard survival on the VM.

Those runtime results must not be inferred from a successful build, unit test,
lint result, APK checksum, or Windows integration skip.


## Background retry and deferred candidate limitations

The original Oracle security token and PKCS8 signing key are encrypted and synchronously committed together before VM launch; retry-session promotion is verified before background work is eligible.

ZeroVPN now has durable capacity retry/candidate state, WorkManager scheduling, relaunch status UI, Diagnostics sections, explicit candidate switch state, and a session-scoped encrypted retry credential vault. Immediately after the initial authenticated workflow uploads its one API key, the app vaults durable API-key credentials (tenancy OCID, user OCID, fingerprint, PKCS8 private key, region, public-key SHA-256 digest) — not the short-lived browser security token. The provisioner switches from `SecurityTokenBootstrap` auth to `ApiKey` auth after upload. Foreground launch and any opted-in background retry therefore use the same durable API-key signing configuration. Retry startup promotes the already-vaulted durable API-key credentials instead of consulting an in-memory authentication result, creating another key, or reopening the browser. If those scoped credentials are missing or unreadable, or if OCI returns HTTP 401 on a signed request, it pauses with `PAUSED_AUTH_REQUIRED`.

Foreground provisioning, manual retry, post-authentication continuation, candidate reconciliation/switching, cleanup, and the capacity worker share a persisted process-aware provisioning lease with a distinct token for each acquisition. A session becomes `WAITING_FOR_RETRY` after the single foreground 6 GB request returns the exact capacity response; its first pending target is 4 GB. Work reconciliation will not enqueue while foreground work or that lease is active, and an already-enqueued worker repeats the complete persisted-session, deadline, and cooldown guards before launching. WorkManager uses unique periodic work with `KEEP`, so relaunch reconciliation does not reset a live worker's initial delay. A lease left by a killed process is treated as stale, so process death does not falsely imply that foreground work is still running forever.

The retry card now observes the unique WorkManager record itself rather than
inferring scheduler health from retry-session timestamps. It reports enqueued,
running, cancelled, failed, succeeded, and missing states. Relaunch
reconciliation retains live work and re-enqueues terminal or missing work only
when the fixed deadline, stored credential, and lease checks pass. An overdue
replacement receives no additional initial delay, but Android can still defer
its actual start for battery, network, quota, Doze, or other platform reasons.
An overdue enqueued row therefore says that Android controls background timing;
it is not evidence that an OCI launch has already happened.

The worker writes a bounded, process-death-safe diagnostic trace for its
cooldown, lease, credential, launch, result, and session-update stages. Those
entries are deliberately safe classifications, not Oracle request/response
logs, and are visible in the retry card.

If transmission of an OCI instance launch starts without a response, or a
generic launch HTTP 5xx response is received, ZeroVPN cannot prove whether an
A1 instance was created. The session therefore stops in
`FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED`; periodic work is cancelled and no
new retry session or VPN-only bypass is allowed for that candidate. The retry
token, memory target, fixed deadline, and encrypted signing credential are
retained for reconciliation. Automated OCI instance reconciliation/adoption is
not implemented yet, so the owner must review the redacted Dev Mode diagnostic
record and reconcile Oracle state before retry can safely resume.

An active capacity-retry session is authoritative Private Chat state on app
reload. The sole Private Chat request switch is forced on and disabled until
the owner uses **Stop retrying** or **VPN only**. The separate automatic-retry
policy switch appears only before authentication and is not rendered in an
active retry flow.

The 24-hour deadline is anchored to that first exact foreground capacity response and is stored as an absolute UTC value. App relaunch, WorkManager recreation, manual Retry, process death, reboot, authentication-screen entry, and HTTP 429 do not reset it. Manual Retry resumes the stored session and current memory target; it does not reopen Oracle authentication. Missing or unreadable stored credentials instead produce the explicit authentication-required state.

The app-state switch path is explicit and rollback-safe, and it refuses candidates that do not have persisted runtime health evidence. Android/WorkManager timing, device restart behavior, expired or revoked security tokens, final VPN reconnect, WireGuard handshake, regional exit-IP proof, Matrix reachability through the private path, and old-VM rollback evidence still require Aaron's owner-operated Android/Oracle run. No code-preparation test here logs into Oracle, creates/destroys VMs, or proves real A1 capacity behavior.

