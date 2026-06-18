# Job: Linux node installer MVP

Branch: agent/2026-06-20_node-installer

## Objective

Create a one-command WireGuard node installer for Ubuntu/Debian that
turns a fresh Linux VM into a ZeroVPN exit node, generates a peer config,
and outputs a QR code for the Android app to import.

## Context

- ZeroVPN's first acceptance demo (brief section 14) starts with a fresh
  overseas Linux VM becoming a working exit node.
- Installer must be auditable shell, not a mystery blob.
- Target: Ubuntu 22.04/24.04 and Debian 12 first.
- The installer is run by the node operator, not the phone user.

## Scope

- `node/install/install.sh` — main installer script
- `node/scripts/add-peer.sh` — add a new peer, output QR
- `node/scripts/revoke-peer.sh` — revoke a peer
- `node/scripts/list-peers.sh` — list peers with status
- `node/templates/wg0.conf.template` — server config template
- `node/templates/peer.conf.template` — peer config template
- `docs/NODE_OPERATOR_GUIDE.md` — operator documentation

## Non-goals

- No Terraform or cloud automation (that's provider lanes)
- No web UI on the node
- No multi-protocol support (WireGuard only for MVP)
- No IPv6 NAT (document IPv6 behaviour, don't necessarily support it)
- No traffic shaping or QoS

## Constraints

- Auditable shell script — no compiled binary, no curl-pipe-bash from
  external sources
- Single-purpose VPN exit only — no email server, no SOCKS proxy, no
  relay extras
- Least privilege — no root login for WireGuard, no password auth
- No secret upload to any ZeroVPN service (there is no service)
- No central callback, no analytics, no auto-update
- Must work on a fresh Ubuntu/Debian install with only SSH access

## Expected files changed

- `node/install/install.sh`
- `node/scripts/add-peer.sh`
- `node/scripts/revoke-peer.sh`
- `node/scripts/list-peers.sh`
- `node/templates/wg0.conf.template`
- `node/templates/peer.conf.template`
- `docs/NODE_OPERATOR_GUIDE.md`

## Implementation notes

### install.sh

1. Check root, check OS (Ubuntu/Debian)
2. Install WireGuard (`apt install wireguard wireguard-tools`)
3. Enable IPv4 forwarding (`sysctl net.ipv4.ip_forward=1`)
4. Generate server keys (`wg genkey` / `wg pubkey`)
5. Pick a VPN subnet (default `10.66.66.0/24`, server `10.66.66.1`)
6. Configure NAT (`iptables` MASQUERADE on the public interface)
7. Configure firewall (allow WireGuard UDP port, default 51820)
8. Write `wg0.conf` from template
9. Enable + start `wg-quick@wg0`
10. Generate first peer (call `add-peer.sh` internally)
11. Output QR code to terminal (`qrencode -t ansiutf8`)
12. Output config file path
13. Print next steps

### add-peer.sh

1. Generate peer keys
2. Assign next available IP in VPN subnet
3. Add peer to running WireGuard interface (`wg set`)
4. Generate peer config from template
5. Output QR code to terminal
6. Output config file path
7. Reload WireGuard interface

### revoke-peer.sh

1. Take peer public key as argument
2. Remove peer from WireGuard interface (`wg set` ... peer ... remove)
3. Remove peer config file
4. Confirm removal

### list-peers.sh

1. `wg show wg0` with formatted output
2. For each peer: public key (truncated), IP, last handshake, transfer
   stats, status indicator

## Tests / validation

- Run on a fresh Ubuntu 22.04 VM (or Debian 12)
- Verify `wg0` interface is up after install
- Verify IP forwarding is enabled
- Verify NAT is working (traffic from VPN subnet reaches internet)
- Generate a peer config
- Import peer config into WireGuard on a test device
- Verify tunnel connects and traffic exits via server IP
- Revoke the peer
- Verify revoked peer cannot reconnect

## Done when

- [ ] Fresh VM → working WireGuard exit in one command
- [ ] QR code output to terminal
- [ ] Peer config file generated
- [ ] Android app (or WireGuard app) can import the config
- [ ] Traffic exits via server IP
- [ ] Revocation actually prevents reconnection
- [ ] Scripts are readable and documented
- [ ] Operator guide written

## Report back with

- summary
- files changed
- commands run (with output snippets)
- tests passed/failed
- manual checks still needed