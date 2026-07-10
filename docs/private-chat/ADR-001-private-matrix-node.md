# ADR-001: Self-Hosted Private Matrix Node over WireGuard

- **Status:** Accepted for phased implementation
- **Date:** 2026-07-10
- **Decision owners:** ZeroVPN maintainers
- **Scope:** Initial Private Chat Node release
- **Related evidence:** [Private Chat Integration Architecture](ARCHITECTURE.md), [Zero Chat Boneyard Map](ZERO_CHAT_BONEYARD_MAP.md), and [Private Chat Node Plan](../ZEROVPN_PRIVATE_CHAT_NODE_PLAN.md)

## Context

ZeroVPN already lets a non-specialist provision and operate a personal WireGuard exit on an Oracle Cloud Ubuntu VM. The Private Chat Node feature extends that user-owned node with private one-to-one text chat for the owner and up to three invited people.

The feature must provide asynchronous encrypted messaging, local history, individual identity and revocation, and a simple single-use QR enrollment flow. An invitee must not need an Oracle account, SSH access, Synapse knowledge, or a public messaging provider. Chat access must also be distinct from ordinary full-VPN access: by default an invitee may reach the private chat endpoint but may not use the owner's Internet exit or reach SSH, PostgreSQL, Oracle instance metadata, other VPN peers, or unrelated host services.

The messaging system therefore needs to satisfy several constraints at once:

- the owner operates the service in the owner's Oracle tenancy;
- ZeroVPN does not operate a mandatory central messaging backend;
- message content is end-to-end encrypted with maintained, reviewed library code rather than ZeroVPN-authored cryptography;
- delayed and offline delivery works without requiring both phones to be online together;
- enrollment, account creation, room policy, and revocation can be automated by ZeroVPN;
- the messaging endpoint is not exposed as a public service;
- open registration, federation, public rooms, and plaintext fallback are excluded from the first release; and
- failure or removal of chat does not destroy or disable a working WireGuard VPN.

End-to-end encryption is not anonymity. The VM operator and Synapse will still be able to observe service metadata such as Matrix identifiers, room membership, event timing and size, device/key-management traffic, and online behavior. WireGuard and Oracle can expose additional connection metadata. The owner also controls service availability, membership, retention, updates, and backups. These limits must remain explicit in the product and its security claims.

## Decision

ZeroVPN will implement the initial Private Chat Node with Matrix/Synapse hosted on the same owner-controlled Oracle Ubuntu VM as WireGuard. Android clients will reach the Matrix Client-Server API only through the node's WireGuard network.

The server workload will consist of:

- Synapse as the Matrix homeserver;
- PostgreSQL as Synapse's local database;
- a private TLS reverse proxy or terminator on the WireGuard service address;
- a minimal ZeroVPN bootstrap service for invitation redemption and privileged enrollment actions; and
- versioned installation, health, firewall, update, backup, and removal state that is separate from the base VPN lifecycle.

The Matrix client endpoint will not be published to the Internet. Synapse administration and PostgreSQL will remain local-only, and federation, open registration, guest access, and public room discovery will be disabled. If locally generated recipient WireGuard keys require a public redemption listener, that listener will be a narrowly scoped, rate-limited exception; it will not expose Matrix or a general administration API.

Each person will have a distinct WireGuard peer and Matrix account. Invitees will receive host-enforced chat-only network access by default. Client `AllowedIPs` will be narrow, but the VM firewall remains the authorization boundary. Private TLS identity will be authenticated with node-scoped pin material delivered through the signed QR enrollment ceremony; TLS verification will not be disabled globally.

The ZeroVPN Android client will use Trixnity as its Matrix SDK and Trixnity's Vodozemac crypto driver for Matrix Olm/Megolm end-to-end encryption. ZeroVPN will not implement cryptographic primitives. Rooms created by ZeroVPN must include `m.room.encryption` in their initial state, and send, receive, invite, and retry paths must fail closed when the room or raw event is not encrypted. There will be no plaintext room fallback.

Matrix-specific types and lifecycle will be kept behind a narrow application boundary so the UI, persistence, and VPN provisioning layers do not become coupled to Matrix protocol objects. This also leaves room for a future rail decision without weakening the first release's encryption and network-isolation invariants.

This decision selects Trixnity/Vodozemac as the implementation family, not an unverified artifact version. The Phase 0 audit proved that Trixnity 5.5.2's Kotlin metadata cannot be consumed by ZeroVPN's current Kotlin 2.1 compiler. Implementation must first validate either a coordinated Kotlin/Gradle/AGP/Compose/KSP upgrade or a still-supported Trixnity release with acceptable E2EE behavior, security status, API coverage, and licensing.

## Alternatives considered

### Signal Protocol

Signal Protocol offers mature end-to-end encryption properties and would avoid depending on Matrix room semantics. It is not, by itself, a complete asynchronous messaging service. ZeroVPN would still need to design and operate account identity, pre-key publication, mailboxes, multi-device state, offline delivery, retry, ordering, abuse controls, push/background behavior, and revocation.

That would turn the feature into a custom messaging backend with security-critical key-distribution and protocol integration work. It conflicts with the requirement to use open, already integrated messaging rails without inventing cryptography or a new central service. Signal Protocol may be reconsidered only if a complete, maintainable, self-hostable stack becomes a better fit than Matrix, not as a collection of primitives around a ZeroVPN-authored protocol.

### XMPP with OTR

XMPP is open, mature, self-hostable, and potentially lighter than Synapse. OTR provides strong encryption for interactive two-party conversations.

The combination is a poor fit for the required asynchronous mobile experience. OTR is oriented toward live pairwise sessions rather than durable offline delivery, encrypted history, and modern multi-device key management. XMPP server and client extension interoperability would also require a broader compatibility program for archives, invitations, device trust, and background delivery. OMEMO would be a materially different alternative and would require its own library, interoperability, and maturity evaluation. Matrix/Trixnity provides a more direct tested path to the required room, sync, encrypted-event, and retry behavior.

### Custom peer-to-peer protocol

A direct peer-to-peer design could reduce dependence on a store-and-forward homeserver and, in some topologies, reduce server-visible relationship metadata. It could also align superficially with ZeroVPN's existing private network.

In practice, mobile peers are frequently offline, suspended, behind NAT, or changing networks. Reliable asynchronous delivery would require an always-available relay or mailbox, returning the design to a server model. ZeroVPN would also have to create identity, discovery, authenticated enrollment, replay protection, message ordering, retry, multi-device behavior, persistence, and cryptographic session management. The resulting custom protocol would carry substantially more implementation and security risk than using Matrix and maintained E2EE libraries.

### MQTT

MQTT is lightweight, resource-efficient, easy to self-host, and well suited to authenticated publish/subscribe transport. It could run comfortably on a small VM and provide retained or queued delivery through a broker.

MQTT does not supply the messaging security and product semantics required here: end-user identity, device keys, end-to-end encrypted sessions, conversation membership, encrypted invitations, message history, edits/redactions, or safe member revocation. Adding those layers, especially E2EE and multi-device key distribution, would amount to defining a custom messaging protocol over MQTT. Broker ACLs and TLS alone protect transport, not message content from the broker. Matrix provides those higher-level semantics and an established encrypted client ecosystem.

### Public Matrix homeserver

Using an existing public Matrix homeserver would reduce owner installation, update, backup, and availability work. It could also offer established Internet reachability and wider interoperability.

It would violate the feature's core ownership and private-network boundaries. The public provider would control registration, account availability, retention, and operational policy; the Matrix endpoint would remain Internet-reachable; and a third party would observe account, room, timing, device, and connection metadata even though message bodies are encrypted. The owner could not reliably enforce the required closed registration, non-federation, server retention, or node lifecycle policy. Matrix identities would also be anchored to the provider's server name. Self-hosting Synapse behind WireGuard accepts greater operational burden in exchange for owner control and removal of a mandatory public messaging provider.

## Consequences

### Positive

- The service remains on infrastructure owned and controlled by the ZeroVPN user, with no mandatory ZeroVPN-operated messaging backend.
- Matrix supplies open protocol semantics for accounts, rooms, asynchronous sync, offline delivery, device keys, and encrypted events instead of requiring a new messaging protocol.
- Trixnity/Vodozemac keeps Olm/Megolm implementation outside ZeroVPN and provides a Kotlin-oriented path to real Matrix E2EE.
- WireGuard removes the Matrix client endpoint from the public Internet and gives ZeroVPN a server-enforced boundary between owner, chat-only, and revoked peers.
- Synapse provides a deterministic self-hosted integration target for two-client encryption, ciphertext, registration, federation, and revocation tests.
- Distinct WireGuard peers, Matrix accounts, and one-time invitations allow individual enrollment and revocation without shared credentials.
- The chat workload can be installed, retried, updated, removed, or fail independently while preserving the owner's working VPN.
- A Matrix-specific boundary lets the product use Matrix now without exposing Matrix protocol types throughout the app.

### Negative

- The owner becomes the service operator and is responsible for VM capacity, availability, security updates, database migrations, backups, recovery, and eventual removal.
- Self-hosting does not hide Matrix or WireGuard metadata from the VM operator, Oracle, or compromised endpoints, and the owner can deny service at any time.
- Synapse, PostgreSQL, TLS, bootstrap, firewall, and health/update tooling increase provisioning time, resource use, disk use, and failure modes on a small Oracle VM.
- Oracle A1 capacity and Free Tier eligibility are not guaranteed; the app must handle unavailable or potentially chargeable capacity honestly.
- Android chat depends on the correct WireGuard tunnel being active. Process death, reboot, Doze, and network changes may delay sync and notifications, while continuous operation may affect battery use.
- Matrix device verification, cross-signing, key backup, device loss, and removal from future encryption sessions are complex. Server backups alone cannot restore missing client E2EE keys or erase plaintext already retained by a recipient.
- A public invitation-redemption endpoint, if required, creates a new attack surface even though Matrix remains private.
- Private TLS pin issuance, scoped validation, recovery, and rotation add lifecycle work that public Web PKI would otherwise provide.
- Trixnity/Vodozemac and encrypted persistence add dependency and APK footprint, and the current ZeroVPN build toolchain cannot consume the audited Trixnity 5.5.2 artifacts without a validated compatibility change.
- Matrix accounts remain tied to the node's stable `server_name`; transparent account migration to a different homeserver is not part of this decision.

## Open questions for implementation

The following questions are carried forward from [section 18 of the governing plan](../ZEROVPN_PRIVATE_CHAT_NODE_PLAN.md#18-key-decisions-to-validate-during-implementation). They do not block this architecture decision, but their final answers must be recorded explicitly in this ADR or in follow-on ADRs:

1. How should ZeroVPN's current Android stack host the ported Trixnity/Matrix code?
2. Should the private Matrix URL use the WireGuard gateway IP or private DNS?
3. What exact node `server_name` format should be used?
4. What is the safest app-scoped TLS pinning implementation?
5. Should the bootstrap service remain permanently available or activate only while invitations exist?
6. How should Matrix long-poll sync share the existing Android VPN foreground-service lifecycle?
7. How should revocation trigger Matrix membership removal and encryption-session rotation?
8. Should the first backup be downloaded to the owner's device or stored in encrypted Oracle Object Storage?
9. Can chat be enabled on an already provisioned ZeroVPN VM in the first release?
10. How should the app handle Oracle A1 capacity unavailability without weakening the feature?

## Phase 1 implementation decisions

Phase 1 resolves the installer-specific subset of those questions as follows:

- The private Matrix URL is `https://10.66.66.1`, the existing WireGuard gateway. Synapse itself remains on loopback behind nginx.
- The stable server name is generated once as `node-<first-12-node-UUID-hex>.zerovpn` and is never derived from the Oracle public IP.
- The node uses a self-signed P-256 certificate with IP/name SANs. Android's Phase 1 owner verifier uses a dedicated SPKI-pinned trust manager for this endpoint and retains certificate-validity and hostname checks; global TLS validation is unchanged.
- Oracle A1 provisioning requests 1 OCPU and 6 GB RAM only when Private Chat is selected. The existing E2 Micro path remains unchanged when it is not selected. Capacity errors are surfaced and no automatic resize/recreate occurs.
- Synapse is installed in a pinned virtual environment rather than Ubuntu's outdated package or the upstream amd64-only Debian repository, allowing the same installer to support Oracle A1 Arm64 and x86_64.
- Private Chat is a separate, durable post-WireGuard workload. The VPN exit is persisted first, and chat retry/removal cannot enter OCI cleanup or edit WireGuard state.
- No bootstrap listener, invitation service, chat-only peer, Android Matrix session, or chat UI is introduced in Phase 1.

## References

- [ZeroVPN Private Chat Node Plan](../ZEROVPN_PRIVATE_CHAT_NODE_PLAN.md)
- [ZeroVPN Private Chat Integration Architecture](ARCHITECTURE.md)
- [Zero Chat Boneyard Map](ZERO_CHAT_BONEYARD_MAP.md)
