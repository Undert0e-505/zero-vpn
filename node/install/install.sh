#!/bin/bash
# ============================================================
# ZeroVPN Node Installer
# ============================================================
# Turns a fresh Ubuntu/Debian machine into a ZeroVPN WireGuard
# exit node. Generates server keys, creates the first peer,
# and outputs a QR code for the Android app to import.
#
# Usage:
#   sudo bash install.sh
#   sudo bash install.sh --port 51820 --subnet 10.66.66.0/24
#
# Requirements:
#   - Fresh Ubuntu 22.04/24.04 or Debian 12
#   - Root access via SSH
#   - Public IP address
# ============================================================

set -euo pipefail

# --- Defaults ---
WG_PORT=51820
WG_SUBNET="10.66.66.0/24"
WG_SERVER_IP="10.66.66.1"
WG_DNS="1.1.1.1, 1.0.0.1"
WG_DIR="/etc/wireguard"
WG_CONF="$WG_DIR/wg0.conf"
PEERS_DIR="$WG_DIR/peers"
KEEPALIVE=25

# --- Parse args ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        --port)    WG_PORT="$2"; shift 2 ;;
        --subnet)  WG_SUBNET="$2"; shift 2 ;;
        --dns)     WG_DNS="$2"; shift 2 ;;
        --help|-h)
            echo "Usage: sudo bash install.sh [--port 51820] [--subnet 10.66.66.0/24] [--dns 1.1.1.1,1.0.0.1]"
            exit 0 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# --- Checks ---
if [[ $EUID -ne 0 ]]; then
    echo "Error: This script must be run as root." >&2
    exit 1
fi

if [[ -f /etc/os-release ]]; then
    . /etc/os-release
    OS_ID="$ID"
    OS_FAMILY="$ID_LIKE"
    if [[ "$OS_ID" != "ubuntu" && "$OS_ID" != "debian" && "$OS_FAMILY" != *debian* ]]; then
        echo "Error: This script supports Ubuntu/Debian only. Detected: $OS_ID" >&2
        exit 1
    fi
    echo "OS: $PRETTY_NAME"
else
    echo "Error: Cannot detect OS. /etc/os-release not found." >&2
    exit 1
fi

# Check if already installed
if [[ -f "$WG_CONF" ]]; then
    echo "Warning: $WG_CONF already exists. Re-running may overwrite." >&2
    read -p "Continue? [y/N] " -n 1 -r
    echo
    [[ ! $REPLY =~ ^[Yy]$ ]] && exit 0
fi

# --- Step 1: Install packages ---
echo ""
echo "[1/8] Installing packages..."
apt-get update -qq
apt-get install -y -qq wireguard wireguard-tools qrencode iptables iptables-persistent
echo "  Packages installed  OK"

# --- Step 2: Enable IP forwarding ---
echo ""
echo "[2/8] Enabling IPv4 forwarding..."
cat > /etc/sysctl.d/99-zerovpn.conf <<EOF
net.ipv4.ip_forward = 1
EOF
sysctl -w net.ipv4.ip_forward=1
echo "  IPv4 forwarding enabled  OK"

# --- Step 3: Detect public interface ---
echo ""
echo "[3/8] Detecting network interface..."
PUB_IFACE=$(ip route show default | awk '{print $5}' | head -1)
if [[ -z "$PUB_IFACE" ]]; then
    echo "Error: Could not detect default network interface." >&2
    exit 1
fi
echo "  Public interface: $PUB_IFACE  OK"

# --- Step 4: Get public IP ---
echo ""
echo "[4/8] Detecting public IP..."
PUB_IP=$(curl -s4 ifconfig.me 2>/dev/null || curl -s4 icanhazip.com 2>/dev/null || echo "")
if [[ -z "$PUB_IP" ]]; then
    echo "Warning: Could not auto-detect public IP." >&2
    read -p "Enter server public IP manually: " PUB_IP
fi
echo "  Public IP: $PUB_IP  OK"

# --- Step 5: Generate server keys ---
echo ""
echo "[5/8] Generating server keys..."
SERVER_PRIV_KEY=$(wg genkey)
SERVER_PUB_KEY=$(echo "$SERVER_PRIV_KEY" | wg pubkey)
echo "  Server public key: $SERVER_PUB_KEY  OK"

# --- Step 6: Write server config ---
echo ""
echo "[6/8] Writing server configuration..."
mkdir -p "$WG_DIR" "$PEERS_DIR"

cat > "$WG_CONF" <<EOF
# ZeroVPN WireGuard exit node
# Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
# Do not share this file. It contains the server private key.

[Interface]
PrivateKey = $SERVER_PRIV_KEY
Address = $WG_SERVER_IP/24
ListenPort = $WG_PORT

# NAT + forwarding rules
PostUp = iptables -A FORWARD -i %i -j ACCEPT; iptables -t nat -A POSTROUTING -o $PUB_IFACE -j MASQUERADE; iptables -A FORWARD -o %i -j ACCEPT
PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -t nat -D POSTROUTING -o $PUB_IFACE -j MASQUERADE; iptables -D FORWARD -o %i -j ACCEPT
EOF

chmod 600 "$WG_CONF"
echo "  $WG_CONF written  OK"

# --- Step 7: Configure firewall ---
echo ""
echo "[7/8] Configuring firewall..."

# Allow WireGuard port
iptables -C INPUT -p udp --dport "$WG_PORT" -j ACCEPT 2>/dev/null || \
    iptables -A INPUT -p udp --dport "$WG_PORT" -j ACCEPT

# Allow forwarding for VPN subnet
iptables -C FORWARD -s "$WG_SUBNET" -j ACCEPT 2>/dev/null || \
    iptables -A FORWARD -s "$WG_SUBNET" -j ACCEPT

# Save rules
if command -v netfilter-persistent &>/dev/null; then
    netfilter-persistent save
fi
echo "  Firewall configured (UDP $WG_PORT open)  OK"

# --- Step 8: Start WireGuard + generate first peer ---
echo ""
echo "[8/8] Starting WireGuard and generating first peer..."

# Enable + start
systemctl enable wg-quick@wg0
wg-quick up wg0 2>/dev/null || true
echo "  WireGuard started  OK"

# Generate first peer
PEER_NUM=2  # 1 is the server
PEER_IP="${WG_SUBNET%.*}.${PEER_NUM}"
PEER_PRIV_KEY=$(wg genkey)
PEER_PUB_KEY=$(echo "$PEER_PRIV_KEY" | wg pubkey)

# Add peer to running interface
wg set wg0 peer "$PEER_PUB_KEY" allowed-ips "${PEER_IP}/32"

# Write peer config
PEER_CONF="$PEERS_DIR/peer-${PEER_NUM}.conf"
cat > "$PEER_CONF" <<EOF
# ZeroVPN peer config
# Tunnel name: Exit Node ($PUB_IP)
# Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")

[Interface]
PrivateKey = $PEER_PRIV_KEY
Address = ${PEER_IP}/32
DNS = ${WG_DNS}

[Peer]
PublicKey = $SERVER_PUB_KEY
Endpoint = ${PUB_IP}:${WG_PORT}
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = ${KEEPALIVE}
EOF
chmod 600 "$PEER_CONF"

# --- Output ---
echo ""
echo "==========================================" | tee -a "$WG_DIR/install-summary.txt"
echo "  ZeroVPN node installed successfully!" | tee -a "$WG_DIR/install-summary.txt"
echo "  Server public key: $SERVER_PUB_KEY" | tee -a "$WG_DIR/install-summary.txt"
echo "  WireGuard port: $WG_PORT/udp" | tee -a "$WG_DIR/install-summary.txt"
echo "  VPN subnet: $WG_SUBNET" | tee -a "$WG_DIR/install-summary.txt"
echo "  Public IP: $PUB_IP" | tee -a "$WG_DIR/install-summary.txt"
echo "  Peer config: $PEER_CONF" | tee -a "$WG_DIR/install-summary.txt"
echo "==========================================" | tee -a "$WG_DIR/install-summary.txt"
echo ""
echo "Scan this QR code with the ZeroVPN Android app:"
echo ""
qrencode -t ansiutf8 < "$PEER_CONF"
echo ""
echo "Peer config file: $PEER_CONF"
echo ""
echo "Next steps:"
echo "  1. Install the ZeroVPN APK on your Android phone"
echo "  2. Open the app and go to Add Exit > Scan QR Invite"
echo "  3. Scan the QR code above (or import the config file)"
echo "  4. Tap Connect"
echo "  5. Check Diagnostics to verify your exit IP"
echo ""
echo "To add more peers:  sudo bash $(dirname "$0")/../scripts/add-peer.sh"
echo "To list peers:      sudo bash $(dirname "$0")/../scripts/list-peers.sh"
echo "To revoke a peer:   sudo bash $(dirname "$0")/../scripts/revoke-peer.sh <public-key>"
echo ""
echo "IMPORTANT: Save $WG_DIR/install-summary.txt. It contains your server public key."