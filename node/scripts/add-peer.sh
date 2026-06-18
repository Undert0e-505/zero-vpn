#!/bin/bash
# ============================================================
# ZeroVPN — Add a new WireGuard peer
# ============================================================
# Generates a new peer keypair, assigns an IP, adds the peer
# to the running WireGuard interface, and outputs a QR code.
#
# Usage:
#   sudo bash add-peer.sh
#   sudo bash add-peer.sh --name "Matt"
#   sudo bash add-peer.sh --name "Matt" --dns 1.1.1.1,1.0.0.1
# ============================================================

set -euo pipefail

WG_DIR="/etc/wireguard"
WG_CONF="$WG_DIR/wg0.conf"
PEERS_DIR="$WG_DIR/peers"
WG_SUBNET_BASE="10.66.66"
WG_SERVER_IP="${WG_SUBNET_BASE}.1"
WG_DNS="1.1.1.1, 1.0.0.1"
KEEPALIVE=25
PEER_NAME=""

# --- Parse args ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        --name) PEER_NAME="$2"; shift 2 ;;
        --dns)  WG_DNS="$2"; shift 2 ;;
        --help|-h)
            echo "Usage: sudo bash add-peer.sh [--name \"Matt\"] [--dns 1.1.1.1,1.0.0.1]"
            exit 0 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# --- Checks ---
if [[ $EUID -ne 0 ]]; then
    echo "Error: This script must be run as root." >&2
    exit 1
fi

if [[ ! -f "$WG_CONF" ]]; then
    echo "Error: $WG_CONF not found. Run install.sh first." >&2
    exit 1
fi

# Check WireGuard is running
if ! wg show wg0 &>/dev/null; then
    echo "Error: WireGuard interface wg0 is not running. Start it with: wg-quick up wg0" >&2
    exit 1
fi

# --- Get server public key from wg0.conf ---
SERVER_PUB_KEY=$(wg show wg0 public-key)
if [[ -z "$SERVER_PUB_KEY" ]]; then
    echo "Error: Could not read server public key." >&2
    exit 1
fi

# --- Get server public IP ---
PUB_IP=$(wg show wg0 listen-port 2>/dev/null | head -1)
# Better: parse from existing peer config or detect
PUB_IP=$(curl -s4 ifconfig.me 2>/dev/null || curl -s4 icanhazip.com 2>/dev/null || echo "")
if [[ -z "$PUB_IP" ]]; then
    echo "Warning: Could not auto-detect public IP." >&2
    read -p "Enter server public IP manually: " PUB_IP
fi

WG_PORT=$(wg show wg0 listen-port)

# --- Find next available peer IP ---
# Read existing peers from wg show output
EXISTING_IPS=$(wg show wg0 allowed-ips | grep -v "$SERVER_PUB_KEY" | awk '{print $3}' | sed 's|/32||' | sort -t. -k4 -n)
LAST_OCTET=1  # server is .1
for ip in $EXISTING_IPS; do
    octet=$(echo "$ip" | cut -d. -f4)
    if [[ "$octet" -gt "$LAST_OCTET" ]]; then
        LAST_OCTET="$octet"
    fi
done
PEER_NUM=$((LAST_OCTET + 1))
PEER_IP="${WG_SUBNET_BASE}.${PEER_NUM}"

# --- Generate peer keys ---
PEER_PRIV_KEY=$(wg genkey)
PEER_PUB_KEY=$(echo "$PEER_PRIV_KEY" | wg pubkey)

# --- Add peer to WireGuard ---
wg set wg0 peer "$PEER_PUB_KEY" allowed-ips "${PEER_IP}/32"

# --- Determine peer label for filename ---
if [[ -n "$PEER_NAME" ]]; then
    # Sanitise name for filename
    SAFE_NAME=$(echo "$PEER_NAME" | tr -cd 'a-zA-Z0-9-_' | tr ' ' '-')
    PEER_CONF="$PEERS_DIR/peer-${PEER_NUM}-${SAFE_NAME}.conf"
    TUNNEL_NAME="$PEER_NAME"
else
    PEER_CONF="$PEERS_DIR/peer-${PEER_NUM}.conf"
    TUNNEL_NAME="Exit Node $PEER_NUM"
fi

# --- Write peer config ---
mkdir -p "$PEERS_DIR"
cat > "$PEER_CONF" <<EOF
# ZeroVPN peer config
# Tunnel name: ${TUNNEL_NAME} ($PUB_IP)
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
echo "=========================================="
echo "  Peer added: ${TUNNEL_NAME}"
echo "  Peer IP: $PEER_IP"
echo "  Peer public key: $PEER_PUB_KEY"
echo "  Config file: $PEER_CONF"
echo "=========================================="
echo ""
echo "Scan this QR code with the ZeroVPN Android app:"
echo ""
qrencode -t ansiutf8 < "$PEER_CONF"
echo ""
echo "Or import the config file directly: $PEER_CONF"