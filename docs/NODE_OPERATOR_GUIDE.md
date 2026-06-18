# Node Operator Guide

> **Status:** Placeholder. Full guide will be written alongside the
> node installer (Phase 3). See `jobs/003_node_installer.md` for the
> plan.

## Quick start (planned)

On a fresh Ubuntu 22.04 or Debian 12 server outside the UK:

```bash
curl -O https://github.com/Undert0e-505/zero-vpn/raw/main/node/install/install.sh
# Review the script before running
less install.sh
sudo bash install.sh
```

The installer will:
1. Install WireGuard
2. Generate server keys
3. Configure NAT and firewall
4. Create a peer and output a QR code
5. Print the config file path

## Managing peers (planned)

```bash
# Add a peer
sudo bash node/scripts/add-peer.sh

# List peers
sudo bash node/scripts/list-peers.sh

# Revoke a peer
sudo bash node/scripts/revoke-peer.sh <peer-public-key>
```

## Security notes

- The installer is auditable shell — read it before running
- No telemetry, no callback, no auto-update
- Keys are generated locally on the server
- The server only runs WireGuard — no email, proxy, or relay extras
- You are responsible for lawful use of the exit node