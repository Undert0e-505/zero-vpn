# ZeroVPN Private Chat Node — Integration Plan

## Status

**Planning document only.**

This plan defines a new ZeroVPN feature that provisions a small private Matrix chat service alongside the existing WireGuard VPN on an Oracle Cloud Ubuntu VM.

The intended first release supports:

- one VM owner;
- up to three active invited people;
- one ZeroVPN Android app that can either create a private node or join one;
- private one-to-one encrypted text chat;
- access granted by single-use QR invitations;
- Matrix reachable only through the private WireGuard network;
- no public Matrix registration;
- no federation in the first release;
- no custom cryptography.

The implementation will be developed in a new ZeroVPN feature branch. The existing `zero-chat` repository will be used as a **boneyard**: a proven source of Matrix integration patterns, security policies, tests, documentation, and UI components to selectively port into ZeroVPN.

---

## 1. Product idea

ZeroVPN already makes it possible for a non-specialist user to provision and control a personal WireGuard VPN on an Oracle Cloud Ubuntu VM.

This feature extends that node into a small private communications service:

```text
Oracle Cloud Ubuntu VM
├── WireGuard
├── Synapse Matrix homeserver
├── PostgreSQL
├── private TLS endpoint
└── minimal invitation/bootstrap service
```

The owner provisions the VM through ZeroVPN, enables **Private Chat Node**, and receives the first account.

The owner may then create up to three separate QR invitations. Each invited person installs ZeroVPN, scans their personal QR code, and joins only the private chat network unless the owner separately grants full-VPN access.

The default invited-person experience should be:

```text
Install ZeroVPN
→ Scan invitation
→ Confirm private node
→ Choose display name
→ Connected
→ Encrypted chat with the owner
```

The invited person must not need:

- an Oracle account;
- SSH access;
- Synapse knowledge;
- manual WireGuard configuration;
- a public Matrix provider;
- a shared password;
- access to the owner’s ordinary VPN traffic.

---

## 2. Core design principles

### 2.1 User-owned infrastructure

The VM belongs to the owner’s Oracle tenancy. ZeroVPN must not operate a mandatory central messaging service.

### 2.2 Open rails

Messaging uses Matrix and Synapse rather than a proprietary chat backend. End-to-end encryption uses the maintained Matrix/Trixnity cryptographic implementation already proven in `zero-chat`.

### 2.3 Private by default

Synapse is not exposed as a public Matrix service. It is reachable only over WireGuard.

The only public network services should be:

- the existing WireGuard endpoint;
- a narrowly scoped invitation-redemption endpoint if client-generated WireGuard keys require it;
- existing ZeroVPN provisioning access where already required.

### 2.4 Separate access levels

A chat invitation must not silently turn the VM owner into the invitee’s general internet provider.

The default invite grants:

```text
Chat-only access
Allowed destination: private Matrix service
No public internet routing
No SSH
No PostgreSQL
No Oracle metadata
No access to other peers
```

Full-VPN access remains a separate, explicit owner-controlled permission.

### 2.5 Individual identity and revocation

Every person receives:

- a unique WireGuard peer;
- a unique Matrix account;
- a unique invitation;
- a separate revocation path.

There are no shared peer configurations, shared Matrix passwords, or reusable universal QR codes.

### 2.6 No false security claims

The product must distinguish clearly between:

- encrypted message content;
- metadata visible to Synapse and the VM operator;
- VPN connection metadata;
- data already decrypted and retained on recipient devices;
- service availability controlled by the VM owner.

---

## 3. Initial scope

### 3.1 Included

The first integrated release should provide:

- optional chat-node installation during Oracle VM provisioning;
- owner Matrix account creation;
- owner chat onboarding in the ZeroVPN app;
- three active invited-person slots;
- single-use, expiring QR invitations;
- invite redemption;
- chat-only WireGuard peers;
- one-to-one encrypted text conversations;
- conversation history on the Android device;
- private notifications with message previews hidden by default;
- owner dashboard showing node health and invited people;
- independent peer/account revocation;
- node health checks;
- clean uninstall or VM destruction handling;
- documented backup and recovery limitations;
- deterministic automated tests;
- real two-client encrypted interoperability tests.

### 3.2 Explicitly excluded from the first release

- public federation;
- open registration;
- public room discovery;
- voice or video calling;
- attachments and large media uploads;
- bridges to WhatsApp, Signal, Telegram, Discord, SMS, or email;
- anonymous communication;
- more than three invited people;
- multi-owner administration;
- custom encryption protocols;
- automatic migration between homeservers;
- a guarantee that Oracle resources will remain free;
- background push through proprietary notification providers;
- automatic recovery of end-to-end encryption keys after device loss.

---

## 4. Repository and Git strategy

### 4.1 ZeroVPN is the implementation repository

Create a feature branch in the existing ZeroVPN repository:

```text
feature/oracle-private-chat-node
```

All production implementation belongs in ZeroVPN.

Do not implement this as a permanent cross-repository runtime dependency.

### 4.2 Zero Chat is the boneyard

The `zero-chat` repository is a reference implementation and source of proven parts.

Inspect and selectively port:

- Matrix/Trixnity client integration;
- encrypted-room enforcement;
- `MatrixRail` and policy concepts;
- secret-storage patterns;
- invite validation concepts;
- Room persistence models and tests;
- privacy-preserving notification behaviour;
- Matrix interoperability tests;
- local Synapse test infrastructure;
- security review findings;
- threat and privacy documentation;
- useful Compose UI patterns, if compatible with ZeroVPN’s application stack.

Do not:

- add `zero-chat` as a Git submodule;
- make ZeroVPN depend on a local checkout of `zero-chat`;
- copy the entire repository blindly;
- retain obsolete Zero Chat app scaffolding;
- import code whose licence or provenance is unclear;
- preserve Matrix-only assumptions that conflict with ZeroVPN’s existing architecture.

Create:

```text
docs/private-chat/ZERO_CHAT_BONEYARD_MAP.md
```

For every reused component, record:

- original Zero Chat path;
- destination ZeroVPN path;
- whether it was copied, adapted, or rewritten;
- relevant commit;
- significant changes;
- tests proving the adapted behaviour.

The integrated feature must build from a clean ZeroVPN checkout with no Zero Chat checkout present.

---

## 5. Target architecture

```text
                         Public internet
                               │
                 ┌─────────────┴─────────────┐
                 │                           │
          WireGuard UDP              Invite redemption
          existing endpoint          HTTPS, narrow API
                 │                           │
                 └─────────────┬─────────────┘
                               │
                    Oracle Ubuntu VM
        ┌──────────────────────┼──────────────────────┐
        │                      │                      │
   WireGuard              Bootstrap service      systemd health
        │                      │                  and lifecycle
        │                      │
        │              local privileged actions
        │                      │
        ├────────────── private network ──────────────┐
        │                                             │
   Owner peer                                    Invitee peers
   full VPN or chat                              chat-only ACL
        │                                             │
        └──────────────────┬──────────────────────────┘
                           │
                    Private TLS proxy
                           │
                        Synapse
                           │
                      PostgreSQL
```

### 5.1 VM components

The provisioned Ubuntu VM should contain:

- existing ZeroVPN WireGuard service;
- PostgreSQL;
- Synapse;
- a private reverse proxy or TLS terminator;
- a minimal ZeroVPN invitation/bootstrap service;
- systemd units;
- health-check tooling;
- backup/export scripts;
- upgrade scripts;
- a machine-readable node manifest.

Suggested paths:

```text
/etc/zerovpn/
  node.json
  private-chat.json
  secrets/
  tls/

/opt/zerovpn/
  bootstrap-service/
  scripts/

/var/lib/zerovpn/
  invites/
  state/

/var/lib/postgresql/
/var/lib/matrix-synapse/
```

All secret files should be root-readable only.

### 5.2 Matrix topology

The first release is:

- private;
- non-federated;
- closed registration;
- text-first;
- owner plus three invited users;
- accessible only over the WireGuard interface.

Synapse’s Admin API must remain local-only and must not be reachable by VPN peers.

PostgreSQL must listen only locally.

### 5.3 Stable addressing

The chat service should use the existing WireGuard server address rather than the Oracle public address.

Example only:

```text
WireGuard gateway: 10.77.0.1
Private Matrix base URL: https://10.77.0.1
```

Do not hard-code this subnet if ZeroVPN already allocates a different private range.

The Matrix `server_name` must be generated once and remain stable for the life of the node. It must not depend directly on an Oracle public IP that may change.

A suitable non-federated form could be derived from the node identity:

```text
node-<short-node-id>.zerovpn
```

The final implementation must verify that the chosen Synapse and Trixnity configuration supports the selected private server name cleanly.

---

## 6. TLS and node trust

Matrix must not be served as cleartext HTTP, even inside WireGuard.

A private node should not require the owner to buy or configure a domain merely to support three friends.

The preferred design is therefore:

1. generate a node TLS keypair during provisioning;
2. issue a certificate valid for the private service address or private node name;
3. include the node certificate/SPKI fingerprint in the invitation;
4. pin that fingerprint in the ZeroVPN client for that node;
5. reject certificate changes unless the owner performs an explicit recovery or rotation flow.

The QR scan is the trust-on-first-use ceremony. The app should show:

- owner-chosen node name;
- node fingerprint;
- invitation expiry;
- requested access level;
- inviter identity.

The user must confirm before joining.

Do not disable TLS verification globally. Custom trust must apply only to the enrolled private node.

Certificate rotation must be designed before expiry handling is shipped. A signed rotation record from the existing node identity is preferable.

---

## 7. Invitation and enrolment model

### 7.1 Slot definition

The node has four seats:

```text
Seat 0: owner
Seat 1: invited person
Seat 2: invited person
Seat 3: invited person
```

“Three QR invites” means **three active invited-person seats**, not three lifetime QR generations.

Revoking a person frees the seat.

An outstanding unredeemed invitation temporarily reserves a seat until it is redeemed, expires, or is cancelled.

### 7.2 Invitation properties

Each invitation must be:

- generated for one intended enrolment;
- high entropy;
- single use;
- short lived;
- independently cancellable;
- bound to a specific node;
- bound to the requested access class;
- protected against payload modification;
- safe to display and share as a QR code;
- non-secret after redemption because it can no longer be used.

Recommended default expiry:

```text
15 minutes
```

### 7.3 QR payload

The QR should carry a compact versioned bootstrap envelope, not permanent account credentials.

Conceptual payload:

```json
{
  "v": 1,
  "nodeId": "opaque-node-id",
  "nodeName": "Oakley Private Node",
  "endpoint": "https://public-bootstrap-endpoint",
  "wireguardEndpoint": "public-ip-or-host:port",
  "nodeSigningKey": "public-key",
  "tlsPin": "sha256/spki-fingerprint",
  "inviteToken": "single-use-high-entropy-token",
  "access": "chat-only",
  "inviter": "@owner:private-node",
  "expiresAt": "ISO-8601 timestamp",
  "signature": "node-signature"
}
```

The exact encoding may use compact CBOR or compressed JSON if QR capacity becomes a problem.

The QR must not contain:

- the owner’s private keys;
- the VM SSH key;
- Oracle credentials;
- a reusable Synapse registration secret;
- the Synapse Admin API token;
- a reusable WireGuard peer private key;
- database credentials.

### 7.4 Preferred redemption flow

The recipient device should generate its own WireGuard keypair locally.

```text
Recipient app
1. scans QR;
2. validates envelope, signature, expiry and TLS pin;
3. generates WireGuard keypair locally;
4. submits invitation token and WireGuard public key;
5. server atomically redeems the token;
6. server creates restricted peer and Matrix account bootstrap;
7. app receives assigned VPN address and one-time account bootstrap;
8. app establishes WireGuard;
9. app logs into private Synapse through the VPN;
10. app discards one-time bootstrap secret;
11. owner and recipient enter an encrypted room.
```

This requires a minimal public redemption endpoint. It must not expose general Matrix, peer administration, or Synapse administration.

The endpoint should be rate-limited and should reveal as little node information as possible before a valid token is presented.

A server-generated WireGuard private key embedded in the QR may be considered only as a temporary prototype fallback. It is not the preferred production design.

---

## 8. Network isolation

### 8.1 Owner peer

The owner retains existing ZeroVPN behaviour.

### 8.2 Invited chat-only peers

Default invited peer rules must allow only what chat requires.

Conceptually:

```text
Invitee peer
ALLOW → private Synapse HTTPS endpoint
ALLOW → required private DNS, if used
ALLOW → tightly scoped node health endpoint
DENY  → SSH
DENY  → PostgreSQL
DENY  → Synapse Admin API
DENY  → Oracle instance metadata
DENY  → other VPN peers
DENY  → public internet forwarding
DENY  → host services not explicitly required
```

Use nftables or the firewall system already used by ZeroVPN.

Enforcement must be server-side. Android `AllowedIPs` alone is not sufficient because a modified client could ignore intended restrictions.

### 8.3 No peer-to-peer lateral access

Invited peers must not be able to connect directly to one another over the VPN subnet.

All chat flows through Synapse. Matrix end-to-end encryption protects content.

### 8.4 Oracle network rules

Do not expose:

- Synapse client ports;
- federation ports;
- PostgreSQL;
- internal health endpoints;
- Admin API.

The provisioning flow must configure both Oracle VCN/NSG rules and Ubuntu host firewall rules.

Existing ZeroVPN rules must remain intact.

---

## 9. Android application integration

### 9.1 Modes

The same ZeroVPN application should support:

#### Create my node

For the Oracle account holder:

- authenticate to Oracle;
- provision VM;
- optionally enable Private Chat Node;
- create owner identity;
- manage invited people;
- generate and revoke invitations;
- use chat.

#### Join a private node

For a friend:

- install ZeroVPN;
- scan a QR;
- join without Oracle credentials;
- receive a chat-only VPN profile;
- create or activate their Matrix identity;
- use chat;
- leave the node.

The invitee experience must not expose irrelevant Oracle provisioning screens.

### 9.2 Owner dashboard

Add a Private Chat Node card or section showing:

```text
Private Chat Node
Status: Healthy
Users: 1 of 4
Matrix: Connected
VPN: Connected
Last backup: Not configured

[Open Chat]
[Invite Person]
[Manage People]
[Node Settings]
```

People management should show:

- local display label;
- Matrix user ID;
- peer access class;
- invitation state;
- last WireGuard handshake, where available;
- Matrix account status;
- revoke action.

Do not display secrets.

### 9.3 Invitee screens

The join flow should include:

- QR scanner;
- invalid/expired invitation handling;
- node identity confirmation;
- access description;
- display-name entry;
- progress through enrolment stages;
- recoverable errors;
- successful connection;
- immediate conversation with inviter.

The app must explain that chat-only access does not route ordinary internet traffic through the owner’s VPN.

### 9.4 Chat UI

Port the useful Zero Chat UI and domain behaviour rather than recreating it unnecessarily.

First integrated chat scope:

- conversation list;
- one-to-one chat;
- send state;
- failure/retry state;
- local history;
- invitation-created owner conversation;
- private notifications;
- offline/reconnecting indication;
- settings showing active node and privacy limits.

The chat UI must be visually coherent with ZeroVPN rather than appearing as a separate app pasted inside it.

### 9.5 Background operation

Do not introduce Firebase Cloud Messaging in the first release.

ZeroVPN already maintains an Android VPN foreground service. When private chat is enabled, Matrix sync should be integrated carefully with the active VPN lifecycle so incoming messages can be received while the private tunnel is available.

Research and test:

- Matrix long-poll sync while the VPN foreground service is active;
- battery impact;
- Android process death;
- network changes;
- device reboot;
- Doze mode;
- notification delivery;
- whether chat-only users require an always-on VPN option.

The app must be honest if near-real-time delivery depends on keeping the VPN service active.

---

## 10. Provisioning flow

### 10.1 VM selection

Target the Oracle Ampere A1 flexible Ubuntu shape already supported by ZeroVPN where available.

A reasonable small-node target is:

```text
1 OCPU
6 GB RAM
50 GB or larger boot volume
Ubuntu
```

The implementation must query the actual tenancy and shape availability. It must not promise that provisioning will always be free or that capacity will always be available.

As of July 2026, Oracle documents an Always Free A1 allowance equivalent to a total of 2 OCPUs and 12 GB RAM across eligible instances. The UI should describe the requested shape as **free-tier eligible when applicable**, not “guaranteed free”.

### 10.2 Installation steps

Extend the existing Oracle provisioning workflow with optional, idempotent stages:

1. verify supported Ubuntu architecture;
2. verify RAM, disk and free space;
3. install system packages;
4. install/configure PostgreSQL;
5. install/configure Synapse;
6. generate node identity;
7. generate private TLS identity;
8. configure non-federated closed homeserver;
9. install bootstrap service;
10. configure systemd units;
11. configure firewall isolation;
12. create owner Matrix account;
13. run health checks;
14. run encrypted self-test;
15. return a signed node manifest to the app;
16. persist only the minimum required owner-side state.

Each stage must be resumable, retryable, logged without secrets, safe if executed twice, and independently diagnosable.

### 10.3 Existing VM enablement

The first implementation may require selection during new VM provisioning.

A later milestone should support:

```text
Enable Private Chat on existing ZeroVPN VM
```

using the same idempotent installer.

### 10.4 Failure and rollback

A failed chat installation must not destroy a working VPN.

The app should offer:

- retry failed stage;
- view redacted diagnostics;
- remove incomplete chat components;
- keep VPN only;
- destroy VM using the existing lifecycle flow.

---

## 11. Server lifecycle

### 11.1 Health

The owner app should check:

- WireGuard service;
- Synapse process;
- PostgreSQL process;
- private Matrix versions endpoint;
- bootstrap service;
- disk usage;
- pending invitations;
- configured user count.

### 11.2 Updates

Synapse and its dependencies require ongoing security updates.

The feature must include a versioned update strategy:

- record installed component versions;
- support safe package updates;
- back up before migrations;
- run health checks after update;
- roll back or report clearly if migration fails.

Do not allow silent automatic major-version upgrades in the first release.

### 11.3 Backups

A useful backup must consider:

- PostgreSQL;
- Synapse configuration;
- Synapse signing key;
- media store, even if media is initially disabled;
- ZeroVPN node configuration;
- invitation/bootstrap state;
- TLS and node identity keys.

Backups should be encrypted before leaving the VM.

The first release may provide a manual encrypted export downloaded to the owner’s device. Oracle Object Storage support can be considered later.

The UI must explain:

- a server backup does not automatically recover client end-to-end encryption keys;
- device loss may make old encrypted history undecryptable;
- messages already decrypted on another person’s phone cannot be remotely erased.

### 11.4 Revocation

Revoking an invited person must:

1. invalidate outstanding invitations;
2. remove the WireGuard peer;
3. deactivate or lock the Matrix account;
4. remove the user from active private rooms where appropriate;
5. ensure future room encryption sessions exclude the revoked device;
6. preserve an audit entry on the owner device without storing message content.

Revocation cannot erase plaintext already viewed or stored on the revoked person’s device. The UI must state this.

---

## 12. Security model

### 12.1 Owner powers

The owner controls VM availability, accounts, peer access, room membership, server upgrades, metadata retained by Synapse, and server backups.

With correctly implemented Matrix encryption, the owner should not receive message plaintext merely because they administer the VM.

### 12.2 Metadata still visible

The VM may observe:

- peer public IP addresses;
- WireGuard handshakes;
- Matrix account identifiers;
- room membership;
- message timing;
- encrypted event size;
- device/key-management traffic;
- online/offline behaviour.

This feature is private infrastructure, not anonymity infrastructure.

### 12.3 Endpoint compromise

A compromised Android device can reveal decrypted messages, local history, active access tokens, and notification content if the user changes privacy defaults.

### 12.4 Bootstrap service threat surface

The public redemption service is the main new public attack surface.

It must have:

- no shell execution from request input;
- no arbitrary username or path injection;
- strict schema and size limits;
- one-time token hashing at rest;
- constant-time token comparison where relevant;
- aggressive rate limiting;
- minimal response detail;
- atomic redemption;
- short token expiry;
- local-only privileged helper interface;
- redacted logs;
- no general admin API;
- no message handling.

Consider disabling the public listener automatically when no invitations are outstanding.

### 12.5 Matrix policy

The first release should enforce:

- encrypted rooms only;
- no plaintext fallback;
- federation disabled;
- public rooms disabled;
- guest access disabled;
- open registration disabled;
- account creation only through ZeroVPN enrolment;
- Admin API local-only;
- minimal retention consistent with reliable delivery;
- no message-body logging;
- no analytics or behavioural telemetry.

---

## 13. Data model

Suggested owner-side concepts:

```text
PrivateChatNode
- nodeId
- displayName
- state
- matrixPrivateUrl
- tlsPin
- serverName
- installedVersion
- userLimit
- createdAt
- lastHealthCheck

PrivateChatMember
- memberId
- displayLabel
- matrixUserId
- wireGuardPeerId
- accessClass
- status
- invitedAt
- joinedAt
- revokedAt
- lastHandshake

PrivateChatInvite
- inviteId
- slot
- status
- accessClass
- createdAt
- expiresAt
- redeemedAt
```

Never persist raw invitation tokens after use, invitee peer private keys, reusable registration secrets, Synapse admin credentials in ordinary preferences, or message content in logs.

Use Android Keystore-backed storage for owner credentials and node secrets retained on the device.

---

## 14. Implementation phases

### Phase 0 — Repository audit and boneyard map

- branch from the current clean ZeroVPN default branch;
- inspect ZeroVPN provisioning, VPN service and UI architecture;
- inspect the latest clean Zero Chat branch;
- create the boneyard mapping document;
- identify licences and dependencies;
- write an architecture decision record;
- confirm whether the existing app stack can directly consume Kotlin/Trixnity code.

Deliverables:

```text
docs/private-chat/ARCHITECTURE.md
docs/private-chat/ZERO_CHAT_BONEYARD_MAP.md
docs/private-chat/ADR-001-private-matrix-node.md
```

### Phase 1 — Ubuntu node installer

- install PostgreSQL and Synapse;
- configure private non-federated homeserver;
- configure TLS;
- create owner identity;
- produce node manifest;
- add idempotent health checks;
- prove encrypted Matrix exchange on an Ubuntu test VM.

### Phase 2 — Owner integration

- add provisioning toggle;
- surface provisioning stages;
- import node manifest securely;
- owner login;
- owner chat screen;
- node health dashboard.

### Phase 3 — Invitation service and QR redemption

- implement three-seat model;
- implement invitation issue/cancel/expiry;
- implement public bootstrap endpoint;
- generate recipient WireGuard keys on recipient device;
- create restricted chat-only peers;
- create Matrix accounts;
- join recipient to encrypted owner conversation;
- enforce single-use atomic redemption.

### Phase 4 — Chat integration

- port/adapt Zero Chat Matrix rail;
- port persistence and secret handling;
- integrate chat UI into ZeroVPN;
- add background sync under the VPN foreground service;
- add private notifications;
- handle reconnection and retry.

### Phase 5 — Revocation, lifecycle and recovery

- independent member revocation;
- room membership/key-session handling after revocation;
- health status;
- update process;
- encrypted manual backup;
- uninstall chat while preserving VPN;
- VM destruction warnings and cleanup.

### Phase 6 — Runtime validation and release preparation

- real Oracle Free Tier eligible VM;
- owner plus three physical/emulated Android clients;
- runtime screenshots;
- battery and background testing;
- clean install and upgrade testing;
- security review;
- documentation;
- release APK.

---

## 15. Required tests

### 15.1 Unit tests

- QR envelope encoding/decoding;
- maximum payload size;
- malformed and oversized QR rejection;
- signature verification;
- expiry handling;
- token hashing and redemption;
- slot reservation and release;
- fourth-invite rejection;
- URL and private-endpoint validation;
- chat-only firewall policy generation;
- no plaintext room fallback;
- no sensitive logging;
- revocation state transitions;
- node manifest validation.

### 15.2 Provisioning tests

- fresh supported Ubuntu VM;
- interrupted installation and resume;
- repeated idempotent execution;
- PostgreSQL unavailable;
- Synapse health failure;
- insufficient disk or memory;
- existing VPN remains operational after chat install failure;
- chat removal leaves VPN working;
- reboot recovery;
- package upgrade.

### 15.3 Network-isolation tests

For each invited peer, prove:

- Matrix endpoint reachable;
- SSH unreachable;
- PostgreSQL unreachable;
- Admin API unreachable;
- Oracle metadata unreachable;
- other peer addresses unreachable;
- public internet not routed through owner VM;
- owner’s existing VPN mode remains unaffected.

### 15.4 Matrix interoperability tests

Using two independent identities:

- authenticate separately;
- create encrypted room;
- verify `m.room.encryption`;
- send A to B;
- decrypt exact text at B;
- send B to A;
- decrypt exact text at A;
- prove raw events are encrypted;
- prove plaintext absent on wire;
- remove B;
- prove B receives no new room keys/messages after revocation.

Reuse and adapt the proven Zero Chat interoperability test.

### 15.5 Android end-to-end tests

- owner provisions node;
- owner chat onboarding succeeds;
- owner creates three invitations;
- each invitee scans a distinct QR;
- all three join successfully;
- fourth active invitation is blocked;
- expired QR is rejected;
- reused QR is rejected;
- invite cancellation works;
- invitee needs no Oracle credentials;
- invitee receives chat-only VPN access;
- bidirectional encrypted messages work;
- history survives process restart;
- notification body hidden by default;
- offline send retries;
- app recovers after VPN reconnect;
- owner revokes one person;
- revoked person loses future access;
- freed slot can be reused.

---

## 16. Definition of done

The feature is ready for merge only when all of the following are true:

- ZeroVPN provisions WireGuard and the private Matrix node on a supported Oracle Ubuntu VM;
- the VPN remains functional if chat provisioning fails;
- Synapse uses PostgreSQL;
- Matrix is inaccessible from the public internet;
- federation and open registration are disabled;
- owner onboarding works through ZeroVPN;
- three distinct QR invitations can be issued and redeemed;
- invitees do not need Oracle accounts;
- invitees receive chat-only network access by default;
- a fourth active invite is rejected;
- QR tokens are one-use and expire;
- two real devices exchange and decrypt messages in both directions;
- raw Matrix events contain ciphertext rather than plaintext;
- no unencrypted fallback exists;
- invited peers cannot reach SSH, PostgreSQL, Admin API, metadata, other peers, or public internet forwarding;
- revocation blocks future VPN and Matrix access;
- ordinary VPN functionality is not regressed;
- all tests, lint and release builds pass;
- secrets and generated server state are absent from Git;
- documentation accurately explains privacy and limitations;
- the branch is clean and reviewable;
- the implementation has been independently security-reviewed as an engineering review, not represented as a formal audit.

---

## 17. Branch deliverables

The feature branch should ultimately contain:

```text
docs/private-chat/
  ARCHITECTURE.md
  ZERO_CHAT_BONEYARD_MAP.md
  THREAT_MODEL.md
  PRIVACY_MODEL.md
  PROVISIONING.md
  INVITATION_PROTOCOL.md
  NETWORK_ISOLATION.md
  BUILD_AND_TEST.md
  BACKUP_AND_RECOVERY.md
  KNOWN_LIMITATIONS.md
  ADR-001-private-matrix-node.md

server/private-chat/
  installer/
  bootstrap-service/
  systemd/
  synapse/
  postgres/
  firewall/
  health/
  backup/
  tests/

android/
  adapted application modules and tests
```

Use the actual ZeroVPN repository structure rather than forcing these exact paths where they do not fit.

---

## 18. Key decisions to validate during implementation

These are implementation questions, not reasons to stop the project:

1. How ZeroVPN’s current Android stack should host the ported Trixnity/Matrix code.
2. Whether the private Matrix URL should use the WireGuard gateway IP or private DNS.
3. The exact node `server_name` format.
4. The safest app-scoped TLS pinning implementation.
5. Whether the bootstrap service should remain permanently available or activate only while invites exist.
6. How Matrix long-poll sync should share the existing Android VPN foreground-service lifecycle.
7. How revocation triggers Matrix membership removal and encryption-session rotation.
8. Whether the first backup is downloaded to the owner device or stored in encrypted Oracle Object Storage.
9. Whether chat can be enabled on an already provisioned ZeroVPN VM in the first release.
10. How the app handles Oracle A1 capacity unavailability without weakening the feature.

Record final answers as architecture decisions rather than leaving them implicit.

---

## 19. Resource and cost position

The design target is intentionally small:

```text
1 owner
3 invited people
encrypted text chat
no federation
no public rooms
no voice/video
no large media
```

This should be modest enough for an Oracle A1 Free Tier eligible VM shared with WireGuard, particularly around 1 OCPU and 6 GB RAM.

However:

- Oracle capacity may be unavailable;
- free-tier limits can change;
- boot volume, backups, public IPs, network transfer, or other services may create charges depending on account configuration;
- ZeroVPN must never present “free” as a guarantee.

The app should show:

```text
Requested resources appear Free Tier eligible.
Oracle, not ZeroVPN, determines actual billing.
Review the Oracle cost estimate before creating the VM.
```

---

## 20. Source references

Primary references current when this plan was written:

- [Oracle Cloud Free Tier](https://docs.oracle.com/iaas/Content/FreeTier/freetier.htm)
- [Oracle Always Free resources](https://docs.oracle.com/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)
- [Synapse installation](https://element-hq.github.io/synapse/latest/setup/installation.html)
- [Synapse PostgreSQL guidance](https://element-hq.github.io/synapse/latest/postgres.html)
- [Synapse backups](https://element-hq.github.io/synapse/latest/usage/administration/backups.html)
- [Synapse Admin API](https://element-hq.github.io/synapse/latest/usage/administration/admin_api/)
- [Matrix client-server specification](https://spec.matrix.org/latest/client-server-api/)
- [Matrix Megolm specification](https://spec.matrix.org/latest/olm-megolm/megolm/)

---

## 21. Immediate next action

1. Add this file to the ZeroVPN repository.
2. Create:

   ```text
   feature/oracle-private-chat-node
   ```

3. Commit the plan as the branch baseline.
4. Instruct the coding lane to perform **Phase 0 only** first:
   - inspect ZeroVPN;
   - inspect Zero Chat as the boneyard;
   - create the boneyard map;
   - produce the architecture decision;
   - identify integration boundaries;
   - make no broad implementation changes until the existing ZeroVPN provisioning and Android architecture have been mapped.

Once Phase 0 is reviewed, proceed through the remaining phases autonomously with testable milestones.
