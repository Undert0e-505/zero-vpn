# Private Chat Node Provisioning

Status: Phase 1 implementation

## Purpose and boundary

Private Chat is an optional workload installed after ZeroVPN has created, verified, and saved a working Oracle WireGuard exit. The Android app does not append Synapse to the existing WireGuard shell script. It commits the VPN profile, SSH key reference, OCI resource IDs, and owner peer first, then starts a separately recoverable workload operation.

If Private Chat is not selected, ZeroVPN keeps the existing `VM.Standard.E2.1.Micro` provisioning path and does not upload or run the chat installer. If it is selected, ZeroVPN requests `VM.Standard.A1.Flex` with 1 OCPU, 6 GB RAM, and a 50 GB boot volume.

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
