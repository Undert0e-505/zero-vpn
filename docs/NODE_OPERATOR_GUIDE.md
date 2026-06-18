# Node Operator Guide

This guide covers setting up and managing a ZeroVPN WireGuard exit
node on a fresh Ubuntu or Debian server.

## Prerequisites

- A Linux VPS or server **outside the UK** with:
  - Ubuntu 22.04, 24.04, or Debian 12
  - Root access via SSH
  - A public IP address
  - UDP port available (default: 51820)
- Basic command-line familiarity

## Quick start

On your server:

```bash
# Get the installer
git clone https://github.com/Undert0e-505/zero-vpn.git
cd zero-vpn/node

# Review the script before running — it's auditable shell
less install/install.sh

# Run it
sudo bash install/install.sh
```

The installer will:
1. Install WireGuard and dependencies
2. Generate server keys
3. Configure NAT and firewall
4. Start the WireGuard service
5. Generate a peer config and display a QR code

Scan the QR code with the ZeroVPN Android app to connect.

## What the installer does

| Step | Action |
|------|--------|
| 1 | Installs `wireguard`, `wireguard-tools`, `qrencode`, `iptables`, `iptables-persistent` |
| 2 | Enables IPv4 forwarding (`net.ipv4.ip_forward=1`) |
| 3 | Detects the public network interface automatically |
| 4 | Detects the server's public IP |
| 5 | Generates server private and public keys |
| 6 | Writes `/etc/wireguard/wg0.conf` with NAT rules |
| 7 | Opens the WireGuard UDP port in the firewall |
| 8 | Starts WireGuard and generates the first peer |

### Configuration paths

| Path | Purpose |
|------|---------|
| `/etc/wireguard/wg0.conf` | Server config (contains server private key — **keep secret**) |
| `/etc/wireguard/peers/` | Peer configs (contain peer private keys — **keep secret**) |
| `/etc/wireguard/install-summary.txt` | Install summary with server public key |
| `/etc/sysctl.d/99-zerovpn.conf` | IPv4 forwarding persistence |

### Custom options

```bash
# Custom port
sudo bash install/install.sh --port 51821

# Custom subnet
sudo bash install/install.sh --subnet 10.77.77.0/24

# Custom DNS
sudo bash install/install.sh --dns 8.8.8.8,8.8.4.4
```

## Managing peers

### Add a peer

```bash
sudo bash node/scripts/add-peer.sh
sudo bash node/scripts/add-peer.sh --name "Matt"
```

Outputs a QR code and saves the peer config to
`/etc/wireguard/peers/peer-<N>.conf`.

### List peers

```bash
sudo bash node/scripts/list-peers.sh
```

Shows each peer's name, IP, public key, last handshake, transfer
stats, and active/inactive status.

### Revoke a peer

```bash
# By public key
sudo bash node/scripts/revoke-peer.sh <peer-public-key>

# By name
sudo bash node/scripts/revoke-peer.sh --name Matt

# By IP
sudo bash node/scripts/revoke-peer.sh --ip 10.66.66.3
```

Revocation removes the peer from the WireGuard interface and deletes
the peer config file. The peer can no longer connect.

## Security notes

- The server only runs WireGuard — no email, no SOCKS proxy, no relay
- Only one UDP port is open (default: 51820)
- No SSH changes are made — your existing SSH config is untouched
- No telemetry, no callback, no analytics, no auto-update
- Keys are generated locally on the server
- Server and peer private keys are in `/etc/wireguard/` with `chmod 600`
- **Do not share `wg0.conf` or peer config files** — they contain
  private keys

### What's open

| Port | Protocol | Purpose |
|------|----------|---------|
| 51820 | UDP | WireGuard tunnel |
| 22 | TCP | SSH (your existing config) |

### What's not open

- No HTTP/HTTPS server
- No SOCKS proxy
- No DNS resolver (DNS is forwarded through the tunnel to Cloudflare)
- No Tor exit relay
- No onion service

## Troubleshooting

### WireGuard won't start

```bash
# Check status
systemctl status wg-quick@wg0

# Check config syntax
wg-quick strip wg0

# Restart
sudo wg-quick down wg0
sudo wg-quick up wg0
```

### Phone can't connect

1. **Check the QR/config is correct** — verify the endpoint IP and port
2. **Check the firewall** — ensure UDP port 51820 is open:
   ```bash
   sudo iptables -L INPUT -n | grep 51820
   ```
3. **Check IP forwarding** — ensure it's enabled:
   ```bash
   cat /proc/sys/net/ipv4/ip_forward
   # Should output: 1
   ```
4. **Check NAT** — ensure MASQUERADE is working:
   ```bash
   sudo iptables -t nat -L POSTROUTING -n
   ```
5. **Check from the phone** — try pinging the server's VPN IP
   (10.66.66.1) through the tunnel

### No internet through tunnel

1. Verify IP forwarding is enabled (see above)
2. Verify NAT rules are present (see above)
3. Verify the peer's `AllowedIPs` includes `0.0.0.0/0`
4. Check DNS — the peer config should have `DNS = 1.1.1.1, 1.0.0.1`

### Can't detect public IP

The installer uses `curl ifconfig.me` to auto-detect the public IP.
If that's blocked, provide it manually:

```bash
sudo bash install/install.sh
# When prompted, enter the server's public IP
```

## IPv6 notes

The current installer configures IPv4 NAT only. IPv6 is not
configured for MVP. The peer config includes `::/0` in `AllowedIPs`
for future compatibility, but IPv6 traffic will not route through
the tunnel until IPv6 NAT is configured. This is documented, not
broken — it's a known MVP limitation.

## Uninstall

To completely remove the ZeroVPN node:

```bash
# Stop WireGuard
sudo wg-quick down wg0
sudo systemctl disable wg-quick@wg0

# Remove config
sudo rm -rf /etc/wireguard/

# Remove sysctl
sudo rm -f /etc/sysctl.d/99-zerovpn.conf
sudo sysctl -w net.ipv4.ip_forward=0

# Remove iptables rules
sudo iptables -D FORWARD -s 10.66.66.0/24 -j ACCEPT 2>/dev/null || true
sudo iptables -D INPUT -p udp --dport 51820 -j ACCEPT 2>/dev/null || true
sudo iptables -t nat -D POSTROUTING -o <interface> -j MASQUERADE 2>/dev/null || true
sudo netfilter-persistent save 2>/dev/null || true

# Optionally remove packages
sudo apt-get remove -y wireguard wireguard-tools qrencode
```

Replace `<interface>` with your public network interface (check with
`ip route show default`).