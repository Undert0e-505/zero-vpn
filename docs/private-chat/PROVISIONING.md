# Private Chat Node Provisioning

Status: Phase 1 implementation

## Purpose and boundary

Private Chat is an optional workload installed after ZeroVPN has created, verified, and saved a working Oracle WireGuard exit. The Android app does not append Synapse to the existing WireGuard shell script. It commits the VPN profile, SSH key reference, OCI resource IDs, and owner peer first, then starts a separately recoverable workload operation.

If Private Chat is not selected, ZeroVPN keeps the existing `VM.Standard.E2.1.Micro` provisioning path and does not upload or run the chat installer. If it is selected, ZeroVPN requests `VM.Standard.A1.Flex` with 1 OCPU, 6 GB RAM, and a 50 GB boot volume.

### A1 capacity retry slots

Oracle Free Tier A1 host capacity is not guaranteed. ZeroVPN permits exactly
one OCI instance-launch request in each 15-minute eligibility window:

- **Initial foreground request:** `VM.Standard.A1.Flex` - 1 OCPU / 6 GB
- **First retry target:** `VM.Standard.A1.Flex` - 1 OCPU / 4 GB, no earlier
  than 15 minutes after an exact 6 GB capacity miss
- **Later retry targets:** alternate 6 GB and 4 GB after each exact capacity
  miss; a slot never sends both configurations
- **Capacity classification:** HTTP 500 with JSON `code` equal to
  `InternalError` and `message` equal to `Out of host capacity` (case-insensitive,
  with an optional trailing period)
- **No capacity fallback for:** 400, 401, 403, 429, network timeouts,
  `IOException`, a generic 500, or any other non-capacity failure. HTTP 401 is classified as `AuthenticationFailure` (not `LocalPreparationFailure`) and pauses the session with `PAUSED_AUTH_REQUIRED`.
- **HTTP 429:** keep the same pending memory target and the same fixed deadline,
  preserve the retry credential, and wait until the later of 15 minutes or an
  integer-seconds `Retry-After` value before another launch request
- **No shape escalation:** E2.1.Micro, paid shapes, increased OCPU, and other
  non-Free-Tier-eligible shapes are never automatic fallbacks
- **Retry policy:** When Private Chat is selected, the owner chooses whether
  automatic capacity retry is enabled before Oracle authentication starts.
  Enabling the policy records the choice only; it does not enqueue WorkManager
  or launch an OCI request.
- **Credential once:** The foreground workflow uploads one API key. Background
  and manual capacity retries reuse its securely stored signing credential;
  **Retry** never reopens browser authentication or uploads another API key.
- **Cleanup:** Capacity misses and rate limiting do not request instance cleanup
  when no instance OCID exists. Existing user-initiated cleanup controls remain
  available for partial network resources.

Capacity failures are attributed to **VM launch**, not **API key setup**. A
rate-limited session is shown as waiting for its next eligible request rather
than as a terminal provisioning error.

The product wording is deliberately limited:

> Requested resources appear Free Tier eligible. Oracle, not ZeroVPN, determines actual billing. Review the Oracle cost estimate before creating the VM.

The installer never resizes or recreates a VM in response to a resource warning.

## Integration sequence

1. `OciProvisioner` creates the OCI network and VM and installs WireGuard as before.
2. `ProvisioningViewModel` stores the owner WireGuard configuration and SSH key in `SecureSecretStore`, creates a `READY` `ConfiguredExit`, and persists it.
3. If Private Chat was requested, `PrivateChatNodeProvisioner` uploads the tracked `server/private-chat` bundle as a temporary APK asset over the existing SSH channel.
4. The VM runs `installer/install.py install --wireguard-interface wg0 --wireguard-address 10.66.66.1` as root.
5. Structured, non-secret stage events are returned through the existing provisioning event stream. Every stage emits a start and terminal status; terminal events include measured duration. Dev Mode adds the stage identifier, status, duration, redacted detail, post-install health results, and non-secret manifest summary.
6. Durable state is maintained on the VM, so an app/process interruption does not lose completed chat stages. A retry prefers the newly uploaded source bundle, checks it against `/opt`, and reconciles drifted templates/unit files rather than accepting process liveness alone.
7. On success, the app retrieves and validates the non-secret node manifest, separately retrieves the root-only owner credential envelope, and immediately stores that envelope in Android Keystore-backed storage. The secure preference file is excluded from cloud backup and device transfer.
8. The owner can connect the saved VPN while chat installation is running or failed. Once connected, the Home card can perform a scoped TLS-pinned Matrix login/logout check through WireGuard.

No file or runtime dependency on `D:\dev\zero-chat` exists. The APK packages installer sources from this repository only.

## Stage model

The journal is `/var/lib/zerovpn/private-chat/install-state.json`. It is atomically replaced with mode `0600`. Every stage has an attempt count, start/completion timestamps, probe result, and redacted last error. Each stage also has a root-only log in `/var/log/zerovpn/private-chat/`.

| Stage | Probe and owned result |
|---|---|
| `PRIVATE_CHAT_PRECHECK` | Ubuntu 22.04/24.04; aarch64/x86_64; total/available RAM; free disk; healthy apt/dpkg; active `wg-quick@wg0`; `10.66.66.1` present; required ports; managed/partial PostgreSQL or Synapse state. |
| `PRIVATE_CHAT_PACKAGES` | Installs only required Ubuntu packages and copies the versioned installer to `/opt/zerovpn/private-chat`. Does not perform a distribution upgrade. |
| `PRIVATE_CHAT_POSTGRES` | Reconciles a dedicated role/database, UTF-8 with `C` locale, SCRAM password, loopback-only listener, and `pg_isready`/SQL probes. |
| `PRIVATE_CHAT_SYNAPSE` | Creates a dedicated service user and pinned Python virtual environment, writes a secret-free config template, starts loopback-only Synapse, and verifies `/versions`. |
| `PRIVATE_CHAT_TLS` | Generates one stable P-256 private certificate identity, binds nginx only to `10.66.66.1:443`, blocks admin/federation proxy paths, and records the SHA-256 SPKI pin. |
| `PRIVATE_CHAT_FIREWALL` | Adds dedicated iptables chains without flushing or rewriting ZeroVPN's existing INPUT/FORWARD/NAT/WireGuard rules. |
| `PRIVATE_CHAT_OWNER_ACCOUNT` | Creates `@owner:<stable-server-name>` through Synapse's local shared-secret registration API and retains its generated password only in root/Keystore-backed secret storage. |
| `PRIVATE_CHAT_HEALTH` | Checks PostgreSQL, Synapse, local Matrix `/versions`, private TLS, exact listener scope, disk, owner account, private firewall service/rules, and future chat-only policy chains. |
| `PRIVATE_CHAT_ENCRYPTION_SELF_TEST` | Uses two independent matrix-nio clients and real Synapse; creates encryption in initial room state; verifies exact plaintext after decryption and raw ciphertext in both directions; leaves/purges the room and deactivates both accounts. |
| `PRIVATE_CHAT_COMPLETE` | Re-runs manifest validation and writes the current component versions, health state, complete stage summary, and last self-test state. |

Every retry probes actual state before applying a stage. A completed ledger entry alone is not treated as proof.

Installer version `0.1.1` also checks the installed source bundle and managed
marker. PostgreSQL, Synapse, TLS, and firewall probes compare current managed
configuration with the uploaded templates, verify listener scope, and
reconcile safe drift. A changed TLS key/SPKI after owner trust has been
established is refused with explicit recovery guidance rather than silently
re-pinned.

## Resource preflight behavior

Hard failures are:

- unsupported OS/version or CPU architecture;
- less than 2 GiB total RAM or 768 MiB currently available RAM;
- less than 10 GiB free disk;
- broken/interrupted package-manager state or an active apt/dpkg lock;
- inactive/missing WireGuard interface or private address;
- an unmanaged Synapse installation;
- an unmanaged listener on a required port.

Less than 6 GiB total RAM or 20 GiB free disk produces a warning stating that no resize occurs. Existing loopback-only PostgreSQL is reconciled without deleting other databases or clusters. An unmanaged PostgreSQL listener exposed beyond loopback is refused rather than silently changed. Existing managed Synapse state is resumed; unmanaged Synapse is not adopted automatically.

## PostgreSQL and secret handling

PostgreSQL listens on loopback only. The `zerovpn_synapse` database uses a dedicated `zerovpn_synapse` login role. Its high-entropy password is generated once in:

```text
/etc/zerovpn/private-chat/secrets/postgres_password
```

The file is root-owned `0600`. Synapse receives it through a systemd credential and an ephemeral `/run/zerovpn-private-chat-synapse/homeserver.yaml`; the persisted Synapse template contains no password. The local registration shared secret uses the same systemd-credential pattern.

Relevant backup material is documented in [server/private-chat/postgres/README.md](../../server/private-chat/postgres/README.md). A consistent PostgreSQL dump/base backup is required; copying live data files is not a supported backup procedure.

## Synapse policy

- Synapse `1.156.0` is pinned in a dedicated virtual environment. The virtual-environment path is used because Oracle A1 is Arm64 while Synapse's upstream Debian repository documents amd64 packages.
- The internal listener is `127.0.0.1:8008`, client resources only. The only peer-facing listener is nginx TLS on the WireGuard address.
- nginx returns `404` for Synapse Admin API and federation/key-server paths. Administration remains possible only through the local Synapse listener.
- registration, guests, public rooms, federation listeners, URL previews, media, metrics, presence, and phone-home statistics are disabled.
- logs run at warning level, nginx access logging is off, and the installer does not log message bodies or secrets.
- encrypted event retention defaults to 30 days in this phase, balancing modest storage with delayed delivery. This is not secure deletion from clients or backups.

## TLS identity

The certificate contains both the stable node name and `10.66.66.1` as subject alternative names. The private key is root-owned `0600`. The SPKI pin is stored in the non-secret node manifest and in the app's protected owner credential envelope.

The Android owner verification client uses a dedicated trust manager for this one private endpoint, checks certificate validity and the exact SPKI SHA-256 value, retains normal hostname/IP verification, and confirms the credential envelope URL/pin match the imported node. It does not install a global trust manager or disable TLS verification.

Automatic rotation is not implemented. Before certificate expiry, a later rotation flow must distribute and confirm a new pin. If only the certificate is lost, the installer can reissue it from the existing key and preserve the SPKI. If the private key is lost, automatic replacement is refused because that changes node trust.

## Firewall ownership

The host already uses iptables in the authoritative Android provisioning path, so Phase 1 uses iptables too. `zerovpn-private-chat-firewall.service` adds a jump to a dedicated chain and permits TCP 443 only on the WireGuard interface/address. PostgreSQL and the Synapse loopback port are rejected from `wg0`.

The installer also creates unattached `ZEROVPN_CHAT_PEER_INPUT` and `ZEROVPN_CHAT_PEER_FORWARD` chains for a later invitation phase. When future chat-only `/32` peers exist, enrollment must attach their source addresses before the existing broad owner forwarding rule. That policy allows only Matrix and denies Oracle metadata (`169.254.169.254`), peer/lateral access, and Internet forwarding.

Health now verifies the firewall service, private TLS INPUT policy, and both
future chat-only policy chains. Diagnostics reports the source-specific
chat-only peer attachments separately. Phase 1 creates no chat-only peers, so
that attachment status is accurately `false` while the policy chains are
ready. Existing owner/friend peer permissions are not altered.

## Node manifest

The root-owned manifest is `/etc/zerovpn/private-chat/node.json` and contains:

- schema/installer version;
- stable node UUID and `node-<12 hex>.zerovpn` server name;
- `https://10.66.66.1` private Matrix URL;
- TLS SPKI pin;
- installed PostgreSQL, Synapse, nginx, matrix-nio self-test, and installer versions;
- stable installation timestamp and last update time;
- full non-secret health result;
- complete stage status/attempt/probe summary and last self-test status;
- owner Matrix user ID;
- network/federation facts and backup-relevant paths.

Manifest validation rejects cleartext/public URLs, URL/WireGuard-address mismatches, invalid pins/IDs/listener ports, federation-enabled state, missing or unknown stages, owner/server-name mismatches, and any password/credential/secret/private-key/access-token-shaped field.

Owner credentials are deliberately separate at `/etc/zerovpn/private-chat/secrets/owner-matrix.json` and never enter the manifest, ordinary Android preferences, event stream, or logs.

## Failure, retry, and removal

On failure the app displays the precise chat stage, measured time when available, a bounded redacted reason, and guidance to retry from the saved VM stage. `ConfiguredExit.lifecycleState` remains `READY`; the owner WireGuard configuration and SSH key reference remain valid.

Retry from the Home card uploads the current installer and resumes from VM probes. An `INSTALLING` or `REMOVING` state found after app restart becomes `FAILED` with an interruption message so the owner receives an explicit retry action.

Removal stops/disables chat-owned services, removes chat firewall chains, drops only the dedicated database/role, removes the chat nginx site, service account, configs, state, TLS, and credentials. PostgreSQL/nginx packages and unrelated nginx sites are retained to avoid package-level collateral damage. Removal does not edit `/etc/wireguard`, stop `wg-quick@wg0`, delete OCI resources, or remove the Android VPN profile.

Manual recovery commands on the VM are:

```bash
sudo python3 /opt/zerovpn/private-chat/installer/install.py health --json
sudo python3 /opt/zerovpn/private-chat/installer/install.py manifest
sudo python3 /opt/zerovpn/private-chat/installer/install.py install --wireguard-interface wg0 --wireguard-address 10.66.66.1
sudo python3 /opt/zerovpn/private-chat/installer/install.py remove --yes
```

Never paste the contents of `/etc/zerovpn/private-chat/secrets` into diagnostics.

## 24-hour A1 capacity retry and deferred Private Chat

Status: implemented for durable app state, relaunch UI, diagnostics, candidate records, explicit switch state, session-scoped credential vaulting, and unattended background OCI A1 launch attempts.

When Private Chat is enabled, the onboarding screen shows **Automatically retry if A1 capacity is unavailable** before either Oracle account action. The choice is synchronously persisted as `capacity_retry_policy_enabled` before browser authentication. Enabling it authorizes a later 24-hour retry window but does not create a retry session, enqueue WorkManager, or issue an OCI request.

That policy switch is an initial, pre-authentication choice only. Once a
capacity-retry session exists it is no longer rendered. The persisted retry
session makes `privateChatRequested` authoritative `true` on reload, and the
single Private Chat request switch is disabled while that session remains
active. The owner ends or bypasses the session through **Stop retrying** or
**VPN only**; toggling a second setup control cannot diverge the two states.

The corrected sequence is:

1. Record the Private Chat and automatic-retry choices.
2. Authenticate with Oracle once and generate one RSA signing keypair.
3. Upload that API key once.
4. Store the durable API-key credentials (tenancy OCID, user OCID, fingerprint, PKCS8 private key, region, public-key SHA-256 digest) in Android Keystore-backed storage under the pending provisioning ID. The background retry worker loads these durable API-key credentials — never the short-lived browser security token.
5. Send one foreground A1 Flex 1 OCPU / 6 GB launch request.
6. On success, continue foreground provisioning and cancel any retry state.
7. On the exact capacity response, persist 4 GB as the next target, enter `WAITING_FOR_RETRY`, and make no immediate fallback request. If the pre-auth policy was enabled, ZeroVPN schedules the first background slot no earlier than 15 minutes later. If it was disabled, the capacity screen retains **Keep trying for 24 hours** as a manual opt-in.
8. In later eligible slots, send exactly one request and alternate 6 GB and 4 GB only after exact capacity misses. HTTP 429 preserves the current target and delays that same target by at least another 15 minutes, or longer when `Retry-After` requires it.

The capacity-specific screen also retains:

- keep trying for 24 hours when automatic retry was not selected before authentication;
- set up VPN only instead;
- retry now;
- cancel/cleanup using the existing failure controls.

The retry session state is durable in the existing `zerovpn_provisioning` SharedPreferences file. It stores only non-secret scheduling, launch, and reconciliation metadata: session/candidate IDs, `lastLaunchAttemptFinishedAtUtc`, `nextEligibleAttemptAtUtc`, `pendingMemoryGb`, the absolute UTC deadline, worker timestamps, background cycle count, separate 6 GB and 4 GB launch request counts, last safe classification, retry-token identifiers, user OCID, tenancy/compartment OCID, API-key fingerprint, selected/token region diagnostics, subnet ID, VM SSH public key, acquired instance OCID if known, source exit ID for deferred candidates, and terminal/user-action state. New sessions begin in `WAITING_FOR_RETRY`; `ACTIVE` means a previous eligible slot ended without an instance, and `ACQUIRING` means a worker owns the launch lease. The production retry window is fixed at 24 hours from the first exact foreground capacity response. Manual Retry, HTTP 429, app relaunch, process death, device reboot, and WorkManager recreation never reconstruct or extend that deadline. WorkManager uses a 15-minute periodic request with network connectivity required, and the worker independently checks the persisted next-eligible timestamp before every possible launch. Android may delay or skip individual runs; it may not make a slot early.

WorkManager uses unique periodic work named from the retry session ID with `ExistingPeriodicWorkPolicy.KEEP`, so app relaunch reconciliation does not replace an existing worker or restart its initial delay. Work input contains only the opaque retry session ID. No OCI private keys, WireGuard keys, Matrix secrets, request signatures, auth headers, or request bodies are placed in WorkManager `Data`, notifications, diagnostics, or Dev Mode text.

WorkManager is initialized by its default AndroidX Startup provider. `ZeroVpnApp`
does not supply a custom `Configuration` or `WorkerFactory`, and
`PrivateChatCapacityRetryWorker` has only the standard application-context and
`WorkerParameters` constructor. On process recreation the worker rebuilds
`CapacityRetryRepository`, `RetryCredentialVault(SecureSecretStore(...))`, and
`ProvisioningOperationLease` from `applicationContext`; it has no
ViewModel/activity or foreground-singleton dependency.

App-start reconciliation queries
`getWorkInfosForUniqueWork(session.uniqueWorkName)` before deciding whether to
enqueue. `ENQUEUED` and `RUNNING` work is retained. Missing, cancelled, failed,
or unexpectedly succeeded work is re-enqueued only while the persisted session
is inside its deadline, has readable scoped credentials, and no provisioning
lease is active. Replacement work derives its initial delay from
`nextEligibleAttemptAtUtc`; an overdue session therefore receives zero
additional initial delay rather than another fixed 15-minute wait. Android
still controls the actual execution time. Expired work is cancelled and its
session credential is cleared.

Immediately after the foreground API-key upload succeeds, ZeroVPN stores the minimum signing material in `SecureSecretStore`: the OCI browser security token and the same generated RSA API-signing private key serialized as PKCS8 PEM. Both values are encrypted with the existing Android Keystore-backed AES/GCM key alias `zerovpn.local.secretstore.v1` in the `zerovpn_secure_secrets` preference file. The initial scope is the pending provisioning ID. After exact foreground capacity failure, retry startup promotes those already-stored values to the retry session ID; it has no `AuthResult` dependency and accepts no newly generated key. The session copy is cleared on cancellation, timeout, or terminal failure. It remains available after background instance acquisition until foreground continuation completes, avoiding a second authentication solely to continue the same workflow. This is not a general Oracle credential cache.

The token and PKCS8 key are encrypted first and synchronously committed in one secure-preference update before network creation or the foreground launch request proceeds. If that durable write cannot be verified, provisioning fails closed before VM launch. Promotion to the retry-session scope is likewise verified before WorkManager can be enqueued.

During the authorized retry window, the device can reconstruct the RSA private key and sign OCI requests without another browser login. The worker loads credentials only from the vault, signs with `useSecurityToken=true`, reads `pendingMemoryGb`, and sends exactly one A1 Flex launch request for that slot. An exact capacity miss alternates the next target (6 GB to 4 GB or 4 GB to 6 GB). HTTP 429 records `RATE_LIMITED`, preserves the pending target and retry count, and sets the next eligible time to the later of 15 minutes or the integer-seconds `Retry-After` value. Success records `INSTANCE_ACQUIRED` with the instance OCID. If the scoped credentials are missing or unreadable, the worker fails closed with `PAUSED_AUTH_REQUIRED`; it never opens authentication or uploads another key.

The status card exposes the same guarded path for **Retry**. While cooldown is active it shows **Next attempt in Xm Ys** and makes no OCI request. Once eligible, **Retry now** makes one launch request with the stored credential and current pending memory target. It does not run the authentication, API-key generation, or API-key upload flow again. The card also observes `getWorkInfosForUniqueWorkLiveData` and shows the durable scheduler state (`Enqueued`, `Running`, `Cancelled`, `Failed`, `Succeeded`, or `Not found`). If eligibility is already in the past while work remains enqueued, it explicitly says that ZeroVPN is waiting for Android's scheduler rather than implying another OCI request has run. A missing overdue work record is re-enqueued and reported as such.

Each worker cycle appends a bounded, non-secret durable trace to
`zerovpn_provisioning`: worker start, cooldown decision, lease decision,
credential-load outcome, launch start, safe launch classification, and final
session state. The latest entries survive process death and are rendered in
the retry card. The trace never includes tokens, private keys, signatures,
request bodies, or full Oracle responses.

The launch path records explicit preparation, signing, request-ready,
transmission-started, and response-received boundaries. A failure before the
instance POST is transmitted is a terminal local failure with a specific safe
category. A failure after transmission starts but before response headers are
received is ambiguous because Oracle may have created the instance. Generic
launch HTTP 5xx responses are also treated as ambiguous rather than as
capacity misses.

Ambiguous launch failures enter
`FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED`. ZeroVPN cancels the unique periodic
work, retains the current retry token, pending memory target, encrypted signing
credential, and original fixed deadline, and sends no later launch request.
VPN-only bypass and replacement retry sessions remain blocked until
reconciliation proves that Oracle did not create an A1 instance. The status
notification links to Diagnostics; Dev Mode shows the redacted exception,
root cause, failing operation/component, progress flags, abbreviated IDs, and
redacted stack trace stored in the existing capacity-retry diagnostic log.

Foreground and background OCI operations share one persisted provisioning lease. Foreground provisioning, manual retry, post-authentication continuation, cleanup, candidate reconciliation, and candidate switching exclude the background worker. Each acquisition has its own random token, so completion or cancellation of an older coroutine cannot release a newer operation's lease in the same process. Work reconciliation also refuses to enqueue while `ProvisioningState.Running`, while `provisioningJob` is active, or while the lease is held. The worker repeats the complete persisted-session and lease checks even if WorkManager already enqueued it; when blocked it returns success so the next periodic interval can try again. A process-instance owner makes an interrupted foreground lease stale after process death, so a killed process does not block retry forever. Unique periodic work remains a second same-session duplicate guard.

Android backup is disabled for the app (`android:allowBackup="false"`), so the encrypted secure preferences are not exported through Android backup/device-transfer transports.

VPN-only bypass records Private Chat as deferred and starts the existing standard `VM.Standard.E2.1.Micro` VPN-only path. It does not install PostgreSQL, Synapse, private TLS, or Matrix owner material on that VM. Eligible VPN-only OCI exits expose **Add Private Chat**, which creates one linked candidate record and enqueues the same capacity retry workflow while keeping the source VPN active. When a user returns to OCI setup, the provisioning screen renders a retry status card before normal controls. The card displays background retry cycles attempted, last attempt/result, next target attempt, remaining time from the persisted UTC deadline, deadline, and the preferred/fallback A1 configurations. It also exposes **Stop retrying**, **Set up VPN only instead**, and **View Dev Mode log**. On the immediate capacity-failure screen, a pre-enabled policy displays that active status instead of asking the owner to opt in again. Relaunch reconciliation marks expired sessions timed out and safely re-enqueues unique WorkManager work for still-waiting/active sessions only when no foreground lease is active; the countdown is never reconstructed from WorkManager timing.

Deferred-chat candidates are linked to their source exit and repeated **Add Private Chat** actions route to the existing candidate. The candidate may be marked ready only when the app has a persisted candidate exit with WireGuard config, a public endpoint, healthy Private Chat status, passing PostgreSQL/Synapse/TLS/Matrix/owner/firewall checks, a passing encrypted self-test, and a TLS SPKI pin. The explicit switch operation updates the selected active exit only after the candidate exit is already persisted, records the old exit as rollback-capable, retains both VMs, and restores the previous selected exit if app-state activation fails. Runtime VPN reconnect, handshake, and exit-IP proof still require Aaron's device/operator run.

Diagnostics now includes **CAPACITY RETRY** and **DEFERRED CHAT CANDIDATE** cards with PASS/FAIL/WARNING/NOT TESTED/NOT INSTALLED labels. Dev Mode events are emitted for session creation, worker reconciliation, cancellation, VPN-only bypass, candidate creation, ready-to-switch, and switch started/succeeded/failed. These records intentionally include only IDs, counts, timestamps, status labels, and abbreviated/non-secret metadata.
