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
- When A1 host capacity is exhausted, ZeroVPN automatically retries with a compact 4 GB configuration. If both 6 GB and 4 GB attempts fail with `Out of host capacity`, provisioning stops with a clear capacity error. No micro or paid-shape fallback is attempted. The capacity fallback triggers only on a precise HTTP 500 `InternalError` / `Out of host capacity` response — ambiguous transport failures, authentication errors, quota failures, or generic 500s do not trigger the fallback.
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

