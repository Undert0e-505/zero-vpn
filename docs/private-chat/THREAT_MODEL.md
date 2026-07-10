# Private Chat Node Threat Model

Status: Phase 1 implementation

## Security objectives

Phase 1 aims to ensure that:

- a Private Chat failure cannot destroy or invalidate a working WireGuard exit;
- Matrix is reachable only through the WireGuard service address;
- PostgreSQL and Synapse administration remain local-only;
- registration and federation are closed;
- generated credentials and private keys are absent from Git, ordinary preferences, commands, and logs;
- the app authenticates the private TLS identity with a node-scoped SPKI pin;
- a real two-client test proves bidirectional Matrix E2EE and server-side ciphertext;
- installer retry/removal affects only chat-owned state.

This is an engineering threat model, not a formal audit.

## Assets

- owner WireGuard private configuration and VM SSH private key;
- PostgreSQL role password and database contents;
- Synapse registration secret, signing key, database, encrypted events, and metadata;
- owner Matrix password and temporary test credentials/tokens;
- TLS private key and app-held SPKI pin;
- node/stage/health manifests;
- availability of WireGuard, PostgreSQL, Synapse, nginx, and the VM;
- decrypted messages and E2EE keys on future Android clients.

## Trust boundaries

1. Android app to Oracle APIs during VM provisioning.
2. Android app to the VM's public SSH endpoint during installation/operation.
3. Android WireGuard peer to `10.66.66.1:443`.
4. nginx to loopback Synapse.
5. Synapse to loopback PostgreSQL.
6. systemd root credential store to the unprivileged Synapse service.
7. owner-operated VM and Oracle control plane versus chat participants' endpoints.
8. PyPI/Ubuntu package supply chains versus the installed node.

## Threats and controls

| Threat | Phase 1 controls | Residual risk |
|---|---|---|
| Public Matrix exposure | OCI security list opens only SSH/WireGuard; nginx binds only `10.66.66.1:443`; Synapse binds loopback; host chain rejects non-WireGuard access to the private address; health checks listener scope. | Oracle/network/firewall misconfiguration outside tracked code can weaken this. Runtime public-port testing is still required. |
| Admin API exposure | nginx returns 404 for `/_synapse/admin`; direct Synapse listener is loopback; registration shared secret is a systemd credential. | Root/VM compromise can access the Admin API and secret. |
| PostgreSQL exposure | Loopback-only `listen_addresses`, dedicated role/database, SCRAM password, listener health test. | Root or the Synapse service can access message metadata/ciphertext. |
| Secret disclosure through persistence/logs | Root `0600` secret files, Android Keystore-backed envelope excluded from backup/device transfer, systemd runtime credentials/config, structured redacted events, root-only logs, no nginx access log. Manifest rejects secret-shaped fields. | Swap, crash dumps, root compromise, Android endpoint compromise, or operator copying can expose secrets. |
| TLS interception | Stable self-signed P-256 identity, SAN for private IP/name, SPKI fingerprint in manifest/Keystore, dedicated app trust manager, certificate validity and normal hostname verification. | Initial SSH provisioning remains the trust bootstrap. Key loss requires explicit recovery/repinning; rotation is not automated. |
| Plaintext Matrix fallback | Encrypted room is created with initial `m.room.encryption`; two independent clients must decrypt exact values; raw API events must be `m.room.encrypted` with ciphertext and without unique plaintext. | The Phase 1 matrix-nio/libolm harness proves the server path, not the later Android Trixnity device lifecycle. |
| Test artifact leakage | Temporary values are high entropy and never printed; client stores are root-only under `/tmp`; finally cleanup leaves/forgets, purges room, deactivates users, removes stores, and logs owner out. | VM crash during the test can leave a temporary account/room until retry or manual cleanup. The failed stage is explicit. |
| Installer replay/partial state | Atomic stage ledger, probe-before-apply, stable generated identity/secrets, idempotent role/database/firewall/service creation. | A malicious root user can forge state; root is inside the trust boundary. |
| Chat failure destroys VPN | VPN `ConfiguredExit` is persisted before chat; separate status; chat code never edits `/etc/wireguard` or OCI resources; removal targets named chat objects only. | Full VM destruction remains an explicit existing owner action. |
| Firewall clobbers VPN rules | Dedicated chains/jump, no built-in flush, no NAT change, systemd ordering after `wg-quick`; generation tests assert absence of WireGuard edits. | Manual firewall changes can reorder/delete policy. Future chat-peer attachment requires end-to-end negative tests. |
| Oracle metadata access by future invitees | Pre-created chat-peer forward chain rejects `169.254.169.254`, lateral subnet, and all forwarding. | Phase 1 creates no chat-only peer, so enforcement is not yet exercised with a real invitee. |
| Federation/data exfiltration | No federation listener, empty whitelist, nginx blocks federation/key endpoints, Synapse service network policy allows localhost only. URL previews/media/telemetry disabled. | An operator modifying systemd/Synapse config can re-enable outbound access. |
| Supply-chain compromise | Fixed Synapse and matrix-nio versions, Ubuntu signed packages, source kept in this repo, component versions recorded. | Python requirements are not hash-locked and installation needs network access. Dependency SBOM/reproducibility work remains. |
| Resource exhaustion | RAM/disk/package preflight; disk health thresholds; bounded 64 KiB nginx request body; no media/federation; small pool; retryable failure. | Deliberate authenticated event floods and database growth are not rate-limit tuned for invitees yet. |
| Malicious/compromised owner endpoint | App secrets are Android Keystore encrypted; owner verification access token is immediately logged out and never persisted/logged. | A compromised unlocked phone can access decrypted secrets and future chat history. |

## Metadata and owner powers

Matrix E2EE protects message bodies from routine server inspection, but Synapse and the VM operator can still observe Matrix identifiers, device/key traffic, room membership, event size/timing, IP/handshake data, and availability. The owner controls the VM, database, accounts, retention, backups, and service uptime. Oracle can observe infrastructure/network metadata.

The feature is private owner-operated infrastructure, not anonymity infrastructure. It cannot erase plaintext already displayed or stored on a participant device.

## Failure and recovery safety

Chat stage failure changes only `PrivateChatNodeState`, never the exit's `READY` lifecycle. App interruption converts a transient chat status into an explicit retryable failure. Removal leaves Ubuntu PostgreSQL/nginx packages installed but deletes the dedicated database, role, service account, units, firewall chains, configs, TLS material, node state, and Android owner credential envelope.

TLS private-key loss is fail-closed: automatic key replacement is refused. Database/signing-key recovery requires a coordinated backup. A server backup cannot recover missing client E2EE keys in later phases.

## Security validation still required

- Real Oracle A1 fresh install, interruption, reboot, removal, and VPN-survival runs.
- Public and WireGuard listener scans from independent hosts.
- Future restricted-peer positive/negative network suite.
- Dependency/SBOM and hash-lock review.
- SSH host-key bootstrap improvement.
- Independent engineering security review before release.
