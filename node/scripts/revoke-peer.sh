#!/bin/bash
# ============================================================
# ZeroVPN — Revoke a WireGuard peer
# ============================================================
# Removes a peer from the WireGuard interface and deletes its
# config file. The peer will no longer be able to connect.
#
# Usage:
#   sudo bash revoke-peer.sh <peer-public-key>
#   sudo bash revoke-peer.sh --name Matt
#   sudo bash revoke-peer.sh --ip 10.66.66.3
# ============================================================

set -euo pipefail

WG_DIR="/etc/wireguard"
PEERS_DIR="$WG_DIR/peers"

if [[ $EUID -ne 0 ]]; then
    echo "Error: This script must be run as root." >&2
    exit 1
fi

if [[ ! -d "$PEERS_DIR" ]]; then
    echo "Error: $PEERS_DIR not found. Run install.sh first." >&2
    exit 1
fi

if [[ $# -lt 1 ]]; then
    echo "Usage: sudo bash revoke-peer.sh <peer-public-key>"
    echo "       sudo bash revoke-peer.sh --name Matt"
    echo "       sudo bash revoke-peer.sh --ip 10.66.66.3"
    echo ""
    echo "Available peers:"
    wg show wg0 peers | while read -r pk; do
        SHORT_PK="${pk:0:8}…${pk: -4}"
        IP=$(wg show wg0 allowed-ips "$pk" | awk '{print $1}' | sed 's|/32||')
        CONF=$(grep -rl "$pk" "$PEERS_DIR/" 2>/dev/null | head -1)
        NAME=$(grep "^# Tunnel name:" "$CONF" 2>/dev/null | sed 's/# Tunnel name: //')
        echo "  $SHORT_PK  IP: $IP  Name: ${NAME:-unnamed}"
    done
    exit 1
fi

PEER_KEY=""
PEER_IP=""

# --- Parse args ---
if [[ "$1" == "--name" ]]; then
    PEER_NAME="$2"
    # Find peer config by name
    CONF_FILE=$(grep -rl "Tunnel name:.*$PEER_NAME" "$PEERS_DIR/" 2>/dev/null | head -1)
    if [[ -z "$CONF_FILE" ]]; then
        echo "Error: No peer found with name '$PEER_NAME'." >&2
        exit 1
    fi
    PEER_KEY=$(grep "PublicKey" "$CONF_FILE" | awk '{print $3}')
elif [[ "$1" == "--ip" ]]; then
    PEER_IP="$2"
    PEER_KEY=$(wg show wg0 allowed-ips | grep "$PEER_IP" | awk '{print $1}')
    if [[ -z "$PEER_KEY" ]]; then
        echo "Error: No peer found with IP '$PEER_IP'." >&2
        exit 1
    fi
else
    PEER_KEY="$1"
fi

# --- Validate peer exists ---
if ! wg show wg0 peers | grep -q "$PEER_KEY"; then
    echo "Error: Peer '$PEER_KEY' not found in WireGuard interface." >&2
    echo ""
    echo "Available peers:"
    wg show wg0 peers | while read -r pk; do
        SHORT_PK="${pk:0:8}…${pk: -4}"
        IP=$(wg show wg0 allowed-ips "$pk" | awk '{print $1}' | sed 's|/32||')
        echo "  $SHORT_PK  IP: $IP"
    done
    exit 1
fi

# --- Get peer info before removal ---
SHORT_PK="${PEER_KEY:0:8}…${PEER_KEY: -4}"
PEER_IP=$(wg show wg0 allowed-ips "$PEER_KEY" | awk '{print $1}' | sed 's|/32||')
CONF_FILE=$(grep -rl "$PEER_KEY" "$PEERS_DIR/" 2>/dev/null | head -1)

# --- Remove peer from WireGuard ---
wg set wg0 peer "$PEER_KEY" remove
echo "Peer removed from WireGuard interface  OK"

# --- Delete config file ---
if [[ -n "$CONF_FILE" && -f "$CONF_FILE" ]]; then
    rm -f "$CONF_FILE"
    echo "Config file deleted: $CONF_FILE  OK"
else
    echo "Warning: No config file found for this peer." >&2
fi

echo ""
echo "=========================================="
echo "  Peer revoked: $SHORT_PK"
echo "  Was IP: ${PEER_IP:-unknown}"
echo "  This peer can no longer connect."
echo "=========================================="