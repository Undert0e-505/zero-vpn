# Zero Chat Boneyard Map

- Status: Phase 0 audit
- Date: 2026-07-10
- Zero Chat revision audited: fad89a4a75951b8373020c2c4732dc2146fedb99
- ZeroVPN revision audited: 85f2e847bc656adde9090e9499be8bc759044697

## 1. Purpose and legal boundary

The Zero Chat repository at D:\dev\zero-chat is a read-only source of previously exercised design patterns. This map records what is technically useful and how its behavior could be reproduced in ZeroVPN.

The audited Zero Chat revision has no root LICENSE file. [LICENCE_RECOMMENDATION.md](../../../zero-chat/docs/LICENCE_RECOMMENDATION.md) recommends AGPL-3.0-or-later but explicitly says a license has not been applied. A recommendation is not a license grant.

Consequently:

- no original Zero Chat source is classified PORT DIRECTLY at this revision;
- original source must not be copied, translated line-for-line, or adapted into ZeroVPN without a documented license grant from the relevant rights holder;
- tests and documentation may be used to understand required behavior, then implemented independently;
- third-party dependencies have their own licenses and can be evaluated independently of the Zero Chat source, subject to a proper dependency/license review;
- every classification below describes the current audited state, not what might become possible after a future grant.

This is an engineering inventory, not legal advice.

## 2. Classification meanings

| Classification | Meaning in this audit |
|---|---|
| PORT DIRECTLY | Technically and legally suitable for copying with minimal changes. There are no entries in this class at the audited revision. |
| ADAPT SUBSTANTIALLY | An independently licensed dependency or integration recipe is viable, but ZeroVPN needs material toolchain, lifecycle, security, or product changes. This does not authorize copying unlicensed Zero Chat source. |
| USE AS BEHAVIOURAL REFERENCE | Preserve the demonstrated invariant or test intent through a clean ZeroVPN implementation; do not copy Zero Chat source. |
| REJECT | The component conflicts with ZeroVPN's product/security model, duplicates a stronger local implementation, or depends on an unsuitable API. A narrow test lesson may still be noted. |

All proposed destinations are planning locations only. They were not created as implementation packages in Phase 0.

## 3. Dependency and build compatibility

Zero Chat declares Trixnity 5.5.2, Room 2.8.4, SQLCipher for Android 4.15, WorkManager 2.11, Ktor 3.4, CameraX 1.5.2, ZXing 3.5.4, Kotlin 2.3.10, KSP 2.3.3, Gradle 8.13, and AGP 8.13.2.

ZeroVPN currently uses Kotlin 2.1.0, Gradle 8.12, AGP 8.7.3, Compose BOM 2024.12.01, CameraX 1.4.1, and ZXing 3.5.3. It has no KSP, Room, SQLCipher, WorkManager, Ktor, or Trixnity configuration.

An isolated compiler probe established that Trixnity 5.5.2's Kotlin 2.3.0 metadata is rejected by ZeroVPN's Kotlin 2.1 compiler. The temporary probe was removed and made no production changes.

Before Matrix implementation, select and test one of:

1. a coordinated Kotlin/AGP/Gradle/Compose/KSP upgrade compatible with Trixnity 5.5.2; or
2. an older, supported Trixnity line whose Kotlin metadata, E2EE behavior, security status, API surface, and licenses have been revalidated.

The Zero Chat documents identify Trixnity and Vodozemac as Apache-2.0 dependencies and other Android/Kotlin/Ktor/ZXing components as permissively licensed. SQLCipher also needs its exact distribution terms recorded. Phase 1 should generate an SBOM and verify every selected artifact directly; the boneyard's documentation is not sufficient legal provenance.

## 4. Build, application, and domain components

| Original Zero Chat path | Proposed ZeroVPN destination | Classification | Revision | Significant changes needed | Tests proving adapted behavior |
|---|---|---|---|---|---|
| gradle/libs.versions.toml; app/build.gradle.kts | android/gradle/libs.versions.toml; android/app/build.gradle.kts | ADAPT SUBSTANTIALLY | fad89a4a | Recreate dependency declarations from authoritative releases; align Kotlin, AGP, Gradle, Compose, KSP, Room, SQLCipher, Ktor, and Trixnity; preserve WireGuard and native feature builds; produce license inventory. | Clean debug/release builds; dependency lock/SBOM checks; existing VPN smoke tests; metadata compatibility compile test; release shrinker test. |
| app/src/main/java/org/zerochat/ZeroChatApplication.kt | android/app/src/main/java/com/zerovpn/app/ZeroVpnApp.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Give ZeroVPN application-scoped ownership of chat dependencies without copying the class; define deterministic startup/close and node-session isolation. | Process recreation; container initializes once; logout/node removal closes Matrix client and database; VPN-only startup remains unchanged. |
| app/src/main/java/org/zerochat/AppContainer.kt | android/app/src/main/java/com/zerovpn/app/chat/ChatAppContainer.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Reimplement explicit dependency wiring for multiple node/account identities, Keystore-backed database keys, pinned TLS, notifications, and tunnel state. Avoid embedding a public default homeserver. | Unit tests for dependency ownership; two-node isolation; close/reopen; injected fakes; no cross-account database or secret reuse. |
| app/src/main/java/org/zerochat/domain/model/Models.kt | android/app/src/main/java/com/zerovpn/app/chat/domain/model | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Add stable node, account, room, device, peer, trust, bootstrap, and lifecycle identifiers; distinguish local outbox state from Matrix event state; model redacted/undecryptable events. | Serialization round trips; node/account key uniqueness; exhaustive state-transition tests; migration fixtures. |
| app/src/main/java/org/zerochat/domain/rail/ChatRail.kt | android/app/src/main/java/com/zerovpn/app/chat/domain/rail/ChatRail.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Recreate a narrow transport boundary that includes tunnel availability, node identity, device verification, logout/revocation, and lifecycle; do not assume user-entered public homeservers. | Contract tests against fake rail; unavailable-tunnel failures; account/node isolation; cancellation and close semantics. |
| app/src/main/java/org/zerochat/data/ChatRepository.kt | android/app/src/main/java/com/zerovpn/app/chat/data/ChatRepository.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Retain offline-first/deduplication/retry ideas, but scope all queries by node/account, reconcile Matrix edits/redactions/encryption failures, and coordinate tunnel-aware sync. | Duplicate event idempotence; retry transitions; process-death outbox recovery; redaction/update; ordering; node isolation; no send while tunnel/node is unavailable. |

## 5. Matrix rail and enrollment components

| Original Zero Chat path | Proposed ZeroVPN destination | Classification | Revision | Significant changes needed | Tests proving adapted behavior |
|---|---|---|---|---|---|
| app/src/main/java/org/zerochat/rail/matrix/MatrixRail.kt | android/app/src/main/java/com/zerovpn/app/chat/matrix/MatrixRail.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Cleanly reimplement after toolchain selection. Replace arbitrary HTTPS endpoint/registration flow with provisioned private node identity, existing bootstrap-created account, WireGuard route gating, scoped TLS/SPKI pin, explicit lifecycle, multi-node storage, and device trust. Preserve encrypted-room/raw-event safeguards as requirements. | Real Synapse interop through WireGuard; create encrypted room; send/decrypt both ways; raw event is m.room.encrypted and contains no plaintext; reject plaintext room/invite; wrong TLS pin; wrong/no tunnel; close/reopen sync. |
| app/src/main/java/org/zerochat/rail/matrix/MatrixRailPolicy.kt | android/app/src/main/java/com/zerovpn/app/chat/matrix/MatrixRailPolicy.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Independently encode fail-closed policy for encrypted invites, joined encrypted rooms, bounded messages, safe error mapping, and outbox retries; add verified-node/device and revoked-peer conditions. | Table-driven policy tests for every membership/encryption/trust/tunnel combination; boundary-length tests; stable non-sensitive error messages. |
| app/src/main/java/org/zerochat/rail/InviteCodec.kt | android/app/src/main/java/com/zerovpn/app/chat/bootstrap/BootstrapEnvelopeCodec.kt | REJECT | fad89a4a | The zerochat URI contains an unsigned Matrix ID and HTTPS origin. Replace entirely with a versioned signed envelope carrying bounded node identity, redemption endpoint/token, expiry, nonce, TLS pin, WireGuard enrollment data or reference, and signature key id. Enforce single-use on the server. | Canonical encoding; signature success/failure; expiry/skew; replay and already-used token; unknown version/key; oversized fields/payload; malformed URI; destination substitution; fuzz/property tests. |
| app/src/main/java/org/zerochat/security/SecretStore.kt | android/app/src/main/java/com/zerovpn/app/storage/SecureSecretStore.kt plus chat-specific wrapper | REJECT | fad89a4a | Do not adopt deprecated EncryptedSharedPreferences or duplicate ZeroVPN's direct Keystore AES/GCM store. Extend the local store with typed node/account namespaces, database-passphrase handling, deletion, corruption reporting, and rotation/recovery policy. | Ciphertext-at-rest inspection; namespace separation; delete/logout/node removal; invalidated key/corrupt record behavior; backup/restore exclusion; no secret logging. |

### Matrix rail invariants worth preserving

The source itself is not reusable, but the following tested behaviors are valuable:

- create encryption in the room's initial state rather than enabling it after messages can race;
- accept only encrypted room invitations;
- require current JOIN membership and current encrypted state before send or retry;
- accept timeline events only when the room remains joined and encrypted;
- inspect the original raw Matrix event and require EncryptedMessageEventContent rather than trusting only decrypted content;
- keep plaintext out of diagnostic logs and user-safe error details;
- bound message size and represent pending, failed, and retry states explicitly;
- store Trixnity repository data with unencrypted timeline storage disabled.

The private-node design must add device verification/cross-signing and key-backup decisions. A signed bootstrap QR authenticates enrollment data; it does not by itself authenticate every future Matrix device.

## 6. Persistence and notifications

| Original Zero Chat path | Proposed ZeroVPN destination | Classification | Revision | Significant changes needed | Tests proving adapted behavior |
|---|---|---|---|---|---|
| app/src/main/java/org/zerochat/persistence/Entities.kt | android/app/src/main/java/com/zerovpn/app/chat/persistence/Entities.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Recreate entities with node/account composite scope, Matrix room/event ids, sender/device trust, local transaction ids, encryption/decryption status, edits/redactions, and durable outbox timestamps. | Room schema tests; uniqueness constraints; cross-node collision fixtures; migration and corrupt-row tests. |
| app/src/main/java/org/zerochat/persistence/Daos.kt | android/app/src/main/java/com/zerovpn/app/chat/persistence/Daos.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Preserve transactional dedupe/unread/retry intent; require node/account predicates on all access; add pagination, redaction/update, retention, and logout deletion operations. | In-memory and encrypted-device DAO tests; duplicate insertion; unread calculation; retry selection; transaction rollback; account erasure; paging order. |
| app/src/main/java/org/zerochat/persistence/ZeroChatDatabase.kt | android/app/src/main/java/com/zerovpn/app/chat/persistence/ZeroVpnChatDatabase.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Create a ZeroVPN-owned Room schema and migrations; do not copy unlicensed declarations. Decide one encrypted database per node/account or a rigorously scoped shared database. | Migration chain from every shipped schema; encrypted open/reopen; wrong key; process death; multi-node isolation; destructive migration forbidden in release. |
| app/src/main/java/org/zerochat/persistence/DatabaseFactory.kt | android/app/src/main/java/com/zerovpn/app/chat/persistence/ChatDatabaseFactory.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Generate/store SQLCipher key with existing Keystore facilities; place DB and Matrix media under no-backup storage; handle key loss without silently erasing; close on logout/removal. | File-header not plaintext SQLite; backup exclusion; key loss/corruption path; concurrent open; close/delete; media location inspection. |
| app/src/main/java/org/zerochat/notifications/PrivateNotificationManager.kt | android/app/src/main/java/com/zerovpn/app/chat/notifications/PrivateChatNotificationManager.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Recreate secret visibility, local-only, redacted public version, generic content, and permission fail-closed behavior using ZeroVPN icon/navigation. Add node/conversation-stable IDs and channel lifecycle. | Robolectric/instrumentation tests for permission denied, lock-screen public version, no plaintext sender/body, channel privacy, tap routing, cancel by conversation/node. |

Zero Chat's local database pattern does not address Matrix's own repository schema migrations, device key backup, or secure media deletion. Those require separate tests and lifecycle decisions.

## 7. UI, navigation, and background work

| Original Zero Chat path | Proposed ZeroVPN destination | Classification | Revision | Significant changes needed | Tests proving adapted behavior |
|---|---|---|---|---|---|
| app/src/main/java/org/zerochat/MainActivity.kt; ui/ZeroChatApp.kt | android/app/src/main/java/com/zerovpn/app/ui/navigation/NavGraph.kt | REJECT | fad89a4a | Do not import a second app shell or navigation root. Add private-chat destinations to ZeroVPN's existing single activity and tabs, with node-aware deep links and state restoration. | Navigation tests from Home/node detail/notification; back stack; process recreation; VPN-only routes unaffected. |
| app/src/main/java/org/zerochat/ui/MainViewModel.kt | android/app/src/main/java/com/zerovpn/app/ui/chat/ChatViewModel.kt and focused screen models | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Split broad UI orchestration by node list, conversations, conversation detail, and enrollment. Expose immutable StateFlow and avoid owning long-lived Matrix resources in screen ViewModels. | Coroutine state tests; node/tunnel loss; retry; lifecycle cancellation; recreation; no data from another node. |
| app/src/main/java/org/zerochat/ui/screens/ConversationListScreen.kt | android/app/src/main/java/com/zerovpn/app/ui/chat/ConversationListScreen.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Recreate list/offline/unread/loading behavior in ZeroVPN theme; surface node health and tunnel requirement; support at most the product-approved rooms rather than public contact discovery. | Compose tests for empty/loading/offline/unread/error/node unavailable; accessibility; restored scroll/state. |
| app/src/main/java/org/zerochat/ui/screens/ConversationScreen.kt | android/app/src/main/java/com/zerovpn/app/ui/chat/ConversationScreen.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Recreate bounded composer, pending/failed/retry indicators, chronological list, and safe errors. Add device/trust state, undecryptable/redacted events, and disabled send when the private route is unavailable. | Compose tests for send/retry, bounds, pending/failure, tunnel loss, undecryptable/redacted content, accessibility, screenshot privacy policy. |
| app/src/main/java/org/zerochat/ui/components/Common.kt | android/app/src/main/java/com/zerovpn/app/ui/chat/components | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Reimplement only chat-specific primitives and reuse ZeroVPN's existing OptionButton, StatusCard, typography, and spacing where suitable. | Preview/Compose semantics; long text; dynamic font scale; dark theme; click/disabled behavior. |
| app/src/main/java/org/zerochat/ui/screens/OnboardingScreen.kt | No destination | REJECT | fad89a4a | ZeroVPN owners provision a node and invitees redeem an owner-issued bootstrap. General homeserver registration onboarding conflicts with that lifecycle. | Replacement provisioning/enrollment flow tests, not port tests. |
| app/src/main/java/org/zerochat/ui/screens/AddContactScreen.kt | No destination | REJECT | fad89a4a | Public Matrix contact/invite entry is not the planned one-owner/three-invitee enrollment flow. Room/account creation belongs to the bootstrap service and owner controls. | Owner invite-slot and enrollment tests; ensure arbitrary Matrix IDs cannot bypass policy. |
| app/src/main/java/org/zerochat/ui/screens/QrScannerScreen.kt | android/app/src/main/java/com/zerovpn/app/ui/screens/ScanInviteScreen.kt | REJECT | fad89a4a | Keep and extend ZeroVPN's existing CameraX/ZXing scanner rather than add a duplicate. Dispatch by an explicit private-chat envelope scheme/version and preserve current WireGuard invite behavior. | Camera permission; valid/invalid payload routing; duplicate frames; oversized QR; rotation/lifecycle; existing WireGuard scan regression. |
| app/src/main/java/org/zerochat/ui/qr/QrCode.kt | android/app/src/main/java/com/zerovpn/app/friends/QrCodeGenerator.kt or chat wrapper | REJECT | fad89a4a | Keep ZeroVPN's QR renderer; add only payload-size/error-correction decisions needed for enrollment and avoid exposing secrets after redemption. | Deterministic decode round trip; maximum supported envelope; screen capture/lifecycle policy; expired/redeemed presentation. |
| app/src/main/java/org/zerochat/ui/screens/SettingsScreen.kt | android/app/src/main/java/com/zerovpn/app/ui/screens/SettingsScreen.kt plus node detail | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Integrate node identity, device verification, notification privacy, logout, revoke, update, remove-chat, and diagnostics into existing settings/node surfaces. Avoid generic homeserver editing after enrollment. | Confirmations and destructive-action tests; remove chat preserves VPN; logout vs revoke semantics; node identity/pin display. |
| app/src/main/java/org/zerochat/ui/theme/Theme.kt | No destination; retain android/app/src/main/java/com/zerovpn/app/ui/theme | REJECT | fad89a4a | The apps already share a closely related TubePulse-derived visual language. Importing another theme would duplicate tokens and risk inconsistency. | Existing theme screenshot/contrast tests extended to new chat screens. |
| app/src/main/java/org/zerochat/work/SyncWorker.kt | android/app/src/main/java/com/zerovpn/app/chat/work/ChatSyncCoordinator.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | A periodic network constraint alone is unsafe for a private endpoint. Gate work on the selected, active WireGuard profile and node/session availability; coordinate continuous Matrix sync with foreground/app lifecycle; keep periodic work best effort. | Worker exits safely without tunnel/session; runs on correct node only; tunnel transition; reboot/process restart; cancellation on node removal; no retry storm. |

## 8. Interop and test infrastructure

| Original Zero Chat path | Proposed ZeroVPN destination | Classification | Revision | Significant changes needed | Tests proving adapted behavior |
|---|---|---|---|---|---|
| interop/build.gradle.kts | android/private-chat-interop/build.gradle.kts or a dedicated test module | ADAPT SUBSTANTIALLY | fad89a4a | Recreate an independently licensed test module on the selected ZeroVPN/Trixnity toolchain. Use test-only dependencies and no production credentials. Decide whether it is an Android, JVM, or mixed harness based on Vodozemac/native requirements. | Harness compiles on Windows and CI; pinned dependency check; no secrets in Gradle output; test isolation. |
| interop/src/test/kotlin/org/zerochat/interop/RealMatrixInteropTest.kt | android/private-chat-interop/src/test/.../PrivateNodeMatrixInteropTest.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Preserve two independent clients, encrypted initial room state, bidirectional send/decrypt, and raw ciphertext assertions. Replace public registration with provisioned owner/invitee accounts; connect through WireGuard and pinned private TLS; test closed federation/registration and revocation. | Real Synapse: owner-to-invitee and invitee-to-owner decrypt; raw m.room.encrypted; no plaintext in raw event/log/database; wrong pin/no tunnel denied; revoked peer gets no future traffic or room keys. |
| interop/scripts/start-synapse.ps1; health-synapse.ps1; stop-synapse.ps1; run-interop.ps1 | server/private-chat/tests/interop/scripts | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Reimplement safe start/health/stop orchestration for the actual server installer. Keep loopback-only development defaults, pinned images, ignored ephemeral runtime, ownership markers, and conservative cleanup. Add private TLS, WG namespace/VM target, PostgreSQL, bootstrap, federation-off checks, and Windows/CI support. | Start twice idempotently; health timeout/failure; only owned resources stopped; loopback/public-port scan; clean runtime; interruption recovery; no credential tracked or printed. |
| interop/scripts/windows_compat/fcntl.py; resource.py | Test-only compatibility utilities if still required | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Recreate only if the selected Synapse development harness still requires Windows shims. Never use them as production security/resource controls. | Windows loopback harness starts; production package does not include/import shims; failure is explicit outside supported test mode. |
| app/src/test/java/org/zerochat/rail/matrix/MatrixRailTest.kt; MatrixRailPolicyTest.kt | android/app/src/test/java/com/zerovpn/app/chat/matrix | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Recreate behavioral matrices for encrypted rooms/invites, raw event validation, size limits, safe errors, retry, and add tunnel/node/device trust cases. | Unit/contract suite with branch coverage across every fail-closed policy outcome. |
| app/src/test/java/org/zerochat/data/ChatRepositoryTest.kt; ChatRepositoryRetryTest.kt | android/app/src/test/java/com/zerovpn/app/chat/data | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Recreate repository dedupe, mapping, unread, retry, and durable outbox specifications with multi-node scoping and process-death cases. | In-memory fake rail/database tests plus encrypted integration tests. |
| app/src/test/java/org/zerochat/persistence/PersistenceDaoTest.kt; PersistenceTest.kt | android/app/src/test/java/com/zerovpn/app/chat/persistence | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Recreate database constraints and reopen tests against the new schema; add SQLCipher, migrations, backup exclusion, deletion, and account/node collision coverage. | JVM DAO tests where valid; Android instrumentation for real Keystore/SQLCipher/filesystem behavior. |
| app/src/test/java/org/zerochat/notifications/PrivateNotificationManagerTest.kt | android/app/src/test/java/com/zerovpn/app/chat/notifications | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Recreate redaction and permission expectations with ZeroVPN resources, channels, routes, and multiple nodes. | Robolectric plus device tests for lock screen, permission denial, tap/cancel, and no plaintext extras. |
| app/src/test/java/org/zerochat/privacy/PrivacyLoggingTest.kt; SecurityConfigurationTest.kt | android/app/src/test/java/com/zerovpn/app/chat/security | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Preserve source/config guards only as supplementary checks. Inspect built manifest, backup rules, network security, log calls, and release artifacts rather than relying solely on source strings. | Static checks plus APK manifest/resource inspection; seeded-secret log scan; cleartext/public-endpoint negative test. |
| app/src/test/java/org/zerochat/rail/InviteCodecTest.kt | android/app/src/test/java/com/zerovpn/app/chat/bootstrap/BootstrapEnvelopeCodecTest.kt | REJECT | fad89a4a | Do not preserve the unsigned URI format. Retain only strict parser-test discipline for the new signed, expiring, bounded, single-use format. | Signature, canonicalization, expiry, replay, version, bound, mutation, fuzz, and server redemption tests. |
| app/src/test/java/org/zerochat/TestSecretStore.kt | android/app/src/test/java/com/zerovpn/app/chat/testing/FakeSecretStore.kt | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Define a ZeroVPN-owned test interface/fake that models deletion and key invalidation; never let a plaintext fake enter production source sets. | Source-set/dependency checks; deterministic secret lifecycle tests; production binding test. |

### Required private-node interop assertions

The boneyard interop test proves a useful E2EE baseline, but ZeroVPN's test must additionally prove:

1. Matrix and Synapse administration listeners are unreachable from the public Internet.
2. A chat-only WireGuard peer can reach only the pinned private Matrix endpoint.
3. The same peer cannot reach SSH, PostgreSQL, instance metadata, other peers, or Internet forwarding.
4. Open registration and federation are disabled.
5. Bootstrap tokens expire and cannot be redeemed twice.
6. Revocation removes WireGuard access, bootstrap/session access, and future Matrix message/key access according to the selected device policy.
7. A failed or removed chat workload leaves owner WireGuard Internet access healthy.
8. Re-running every installer stage after interruption is idempotent.

## 9. Documentation and security findings

| Original Zero Chat path | Proposed ZeroVPN destination | Classification | Revision | Significant changes needed | Tests or review proving adapted behavior |
|---|---|---|---|---|---|
| README.md; docs/ARCHITECTURE.md; docs/ARCHITECTURE_DECISION_001.md | README.md; docs/private-chat/ARCHITECTURE.md; docs/private-chat/ADR-001-private-matrix-node.md | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Preserve concise architecture/decision structure, but document the Oracle/WireGuard/private-node model, actual ZeroVPN paths, lifecycle, and verified constraints. | Link/path check; review against governing plan; architecture-to-code traceability at each phase. |
| docs/THREAT_MODEL.md; docs/PRIVACY_MODEL.md; docs/SECURITY_REVIEW.md | docs/private-chat/THREAT_MODEL.md; PRIVACY_MODEL.md; SECURITY_REVIEW.md in a later phase | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Rebuild around owner-operated OCI, public bootstrap exposure, WireGuard peer classes, Oracle metadata, server compromise, TLS pins, device verification, backups, and revocation. Retain explicit residual-risk tracking. | Threat-to-control/test matrix; independent security review; negative network suite; privacy log/artifact checks. |
| docs/BUILD_AND_TEST.md; docs/SELF_HOSTING.md | docs/private-chat/BUILD_AND_TEST.md; OPERATIONS.md in later phases | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Document supported Windows/JDK/SDK setup, node install/update/backup/remove/recovery, OCI sizing, health probes, and private-only access. Do not carry over public local Synapse registration instructions. | Fresh-machine build; clean-VM install; reboot/update/restore/remove drills; documentation command validation. |
| docs/KNOWN_LIMITATIONS.md; docs/ROADMAP.md | docs/private-chat/KNOWN_LIMITATIONS.md; governing plan updates | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Track concrete limitations with release gates and owners: device verification, key backup, metadata leakage, background reliability, media at rest, secure deletion, and reproducibility. | Release checklist blocks unresolved critical/high findings; limitation links to issue/test evidence. |
| docs/RAIL_EVALUATION.md | docs/private-chat/MATRIX_CLIENT_EVALUATION.md if needed | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Re-run evaluation for private TLS pinning, Android minimum/API compatibility, Kotlin toolchain, repository encryption, E2EE/device verification, maintenance, and licensing. | Reproducible dependency spike; real private-node interop; upgrade rehearsal. |
| docs/LICENCE_RECOMMENDATION.md | Root LICENSE plus docs/private-chat/THIRD_PARTY_NOTICES.md/SBOM in later phase | USE AS BEHAVIOURAL REFERENCE | fad89a4a | Do not treat the recommendation as permission. Preserve ZeroVPN's MIT license for original work unless maintainers decide otherwise; record all dependencies and any future granted/imported material explicitly. | Automated license/SBOM scan; human review; source provenance record; release artifact notices. |

### Security review findings that carry forward

The Zero Chat security review correctly treats these as unresolved or only partially addressed:

- Matrix device verification, cross-signing, and key backup/recovery are not complete.
- A QR invitation can authenticate bootstrap data without proving every later device identity.
- Matrix homeserver metadata remains visible even when message bodies are E2EE.
- Android behavior was not proven on representative real devices.
- Logical deletion is not guaranteed secure erasure on flash storage or in backups.
- Media storage needs a clear at-rest encryption and deletion policy.
- Build provenance, dependency inventory/SBOM, and reproducible release evidence are incomplete.
- Screenshot protection and notification redaction require an explicit product policy.
- Debug artifacts and logs must be treated differently from release artifacts.

ZeroVPN adds further server-side risks: Oracle API-key cleanup, instance metadata, public SSH bootstrap, host firewall ordering, owner/chat peer privilege separation, optional-workload failure, and VM update/backup operations.

## 10. Test adoption summary

The behavioral reference should become four test layers:

| Layer | Primary evidence |
|---|---|
| Pure unit tests | Bootstrap canonicalization/signatures/expiry, Matrix policy, state machines, repository dedupe/retry, safe error mapping |
| Android integration/instrumentation | Keystore, SQLCipher, migrations, CameraX/QR dispatch, notification privacy, process/reboot lifecycle, correct tunnel gating |
| Real Matrix interop | Two independent devices, encrypted room from initial state, raw ciphertext, sync/reopen, device verification/revocation |
| VM/network end-to-end | Idempotent install/resume/update/remove, private TLS, firewall positive/negative matrix, federation/registration closure, VPN survival |

Each adapted behavior should have a new ZeroVPN-authored test. Passing a copied Zero Chat test would not resolve the source-license problem and would not cover the different product boundary.

## 11. Adoption decision

The boneyard validates that an Android/Trixnity/Vodozemac encrypted-chat rail, encrypted local persistence, private notifications, retryable outbox, and real Synapse interop are plausible. It does not provide drop-in ZeroVPN code.

Proceed by:

1. resolving dependency/toolchain compatibility and artifact licenses;
2. writing ZeroVPN-owned interfaces and security invariants from the governing plan;
3. implementing independently against the private WireGuard/TLS/bootstrap lifecycle;
4. translating the behavioral test matrix into new tests;
5. retaining provenance notes for every dependency and any material later accepted under an explicit license grant.

## 12. Phase 1 implementation record

The Phase 1 implementation was written independently in ZeroVPN; no Zero Chat source was copied or made a build/runtime dependency.

| Behavioral reference | ZeroVPN implementation | Treatment | Material differences and proof |
|---|---|---|---|
| `interop/scripts/start-synapse.ps1` | `server/private-chat/installer`, `synapse`, `postgres`, `firewall`, and `health` | REWRITTEN FROM BEHAVIOR | Ubuntu/systemd/PostgreSQL deployment rather than Windows-local disposable Synapse; WireGuard-only TLS; durable stages; root/systemd credentials; removal preserves VPN. Proved by private-chat unit/contract suite and documented real-VM matrix. |
| `interop/src/test/kotlin/org/zerochat/interop/RealMatrixInteropTest.kt` | `server/private-chat/tests/encrypted_self_test.py` | REWRITTEN FROM BEHAVIOR | Two independent matrix-nio identities against real local Synapse; encryption in initial state; exact bidirectional decrypt; raw ciphertext/plaintext-absence assertions; additionally purges room and deactivates both accounts. `test_encrypted_self_test_contract.py` guards the shipped procedure; real execution is an installer stage. |
| `app/src/main/java/org/zerochat/rail/matrix/MatrixRail.kt` | `android/app/src/main/java/com/zerovpn/app/chat/node/PrivateChatOwnerVerifier.kt` (narrow Phase 1 status check only) | REWRITTEN FROM SECURITY INVARIANTS | No Matrix rail/UI was ported. The Phase 1 app performs only node-scoped SPKI validation, `/versions`, owner login, and immediate logout over the active WireGuard route. Trixnity/Vodozemac session work remains a later phase. |

Reference revision remains `fad89a4a75951b8373020c2c4732dc2146fedb99`.
