# Architecture

## Overview

ZeroVPN is an Android VPN client + node kit. The Android app routes
phone traffic through a WireGuard tunnel to a user-controlled exit
node. The node kit turns a fresh Linux machine into a WireGuard exit
with one command.

There is no ZeroVPN backend. The app is local-only. The node is
operator-owned. The only network path is the WireGuard tunnel itself.

## Components

### Android app (`android/`)

Kotlin + Jetpack Compose. Targets Android 10+, sideloaded APK.

**Core service:** `VpnService` subclass implementing WireGuard tunnel
transport. Only one Android VPN service can be active at a time.

**UI surfaces:**
1. Home — status, selected mode, connect/disconnect
2. Add Exit — import config, scan QR, create node, volunteer mode
3. Node/Invite — operator peer management (add/revoke/list)
4. Diagnostics — exit IP check, DNS leak check, handshake, logs

**Storage:** local encrypted storage only. WireGuard configs (including
private keys) stored in Android Keystore / encrypted SharedPreferences.
No telemetry, no analytics, no remote config, no crash reporting.

**Design language:** inherits visual conventions from TubePulse — dark
background, surface cards, accent glow, compact typography. See
`docs/TUBEPULSE_UI_REPORT.md` for the mined design tokens.

### Node kit (`node/`)

Shell scripts for Ubuntu/Debian. Auditable, single-purpose.

**Installer (`node/install/`):**
- installs WireGuard
- configures IP forwarding + NAT
- generates server keys
- creates first peer
- outputs QR code + config file
- no email/proxy/extras

**Scripts (`node/scripts/`):**
- `add-peer.sh` — create a new peer, output QR
- `revoke-peer.sh` — remove a peer and its access
- `list-peers.sh` — show peers, last handshake, data usage

**Templates (`node/templates/`):**
- `wg0.conf.template` — server config template
- `peer.conf.template` — peer config template

### Provider lanes (`infra/`)

Guided deployment paths for specific cloud providers. Each lane is a
research-validated deployment guide, not an automation script. ZeroVPN
never handles cloud credentials.

- `infra/oracle/` — Oracle Free Tier guided lane
- `infra/azure-student/` — Azure Student cardless lane

### Docs (`docs/`)

All project documentation. Docs are written before implementation.

### Job packs (`jobs/`)

Numbered task definitions for the bounded coding-agent workflow. Each
job pack is a self-contained brief with objective, scope, constraints,
and acceptance tests.

## Data flow

```
Phone ──WireGuard tunnel──> Exit node ──NAT──> Internet
                                 │
                          (residential or datacenter IP)
```

The exit node sees the phone's traffic destination. The phone's local
network and ISP only see encrypted WireGuard UDP to the exit node.

For OCI Android-provisioned exits, the VM has three separate gates:
OCI ingress, the Ubuntu guest firewall, and WireGuard cryptographic
acceptance. Android `VpnService` reporting connected is not enough to
prove a server handshake. The server-side truth test is `sudo wg show`
with a recent handshake and non-zero transfer counters. See
`docs/OCI_VM_SETUP.md` for the 2026-06-21 OCI/WireGuard debugging
playbook and firewall ordering requirements.

In Volunteer Network Mode, the path is:

```
Phone ──VpnService/SOCKS──> Volunteer relay network ──> Internet
```

This is slower, less stable, and may trigger blocks. It does not
provide a private endpoint.

## Trust boundaries

| Boundary | Who | What they see |
|---|---|---|
| Phone → Exit | Local ISP | Encrypted UDP to one endpoint |
| Exit node | Node operator | All destination traffic (plaintext if not HTTPS) |
| Cloud provider | Provider | Node metadata, billing, possibly traffic logs |
| Volunteer network | Public relays | Exit node sees destination traffic |

ZeroVPN does not protect against a malicious or careless exit node. The
threat model is in `docs/THREAT_MODEL.md`.

## Constraints

- One Android VPN service at a time
- No backend, no accounts, no telemetry
- Windows-native PowerShell build (no WSL)
- MIT license, publishable from day one
- No secrets in git
