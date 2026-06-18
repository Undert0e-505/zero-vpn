#!/bin/bash
# ============================================================
# ZeroVPN — List WireGuard peers
# ============================================================
# Shows all configured peers with status, last handshake,
# and transfer stats.
#
# Usage:
#   sudo bash list-peers.sh
# ============================================================

set -euo pipefail

PEERS_DIR="/etc/wireguard/peers"

if [[ $EUID -ne 0 ]]; then
    echo "Error: This script must be run as root." >&2
    exit 1
fi

if ! wg show wg0 &>/dev/null; then
    echo "Error: WireGuard interface wg0 is not running." >&2
    exit 1
fi

SERVER_PUB_KEY=$(wg show wg0 public-key)
SERVER_PORT=$(wg show wg0 listen-port)

echo ""
echo "ZeroVPN WireGuard Exit Node"
echo "  Server public key: $SERVER_PUB_KEY"
echo "  Listen port:        $SERVER_PORT"
echo ""

PEERS=$(wg show wg0 peers)

if [[ -z "$PEERS" ]]; then
    echo "No peers configured."
    echo ""
    echo "Add a peer with:  sudo bash $(dirname "$0")/add-peer.sh"
    exit 0
fi

echo "Peers:"
echo "-------"
echo ""

wg show wg0 latest-handshakes | while read -r PK HANDSHAKE; do
    SHORT_PK="${PK:0:8}…${PK: -4}"
    IP=$(wg show wg0 allowed-ips "$PK" | awk '{print $1}' | sed 's|/32||')

    # Find config file for name
    CONF_FILE=$(grep -rl "$PK" "$PEERS_DIR/" 2>/dev/null | head -1)
    NAME="unnamed"
    if [[ -n "$CONF_FILE" && -f "$CONF_FILE" ]]; then
        NAME=$(grep "^# Tunnel name:" "$CONF_FILE" 2>/dev/null | sed 's/# Tunnel name: //' | sed 's/ (.*)//')
    fi

    # Transfer stats
    RX_TX=$(wg show wg0 transfer "$PK" 2>/dev/null)
    RX_BYTES=$(echo "$RX_TX" | awk '{print $2}')
    TX_BYTES=$(echo "$RX_TX" | awk '{print $3}')

    # Format bytes
    format_bytes() {
        local b=$1
        if [[ $b -ge 1073741824 ]]; then
            echo "$(awk "BEGIN{printf \"%.1f\", $b/1073741824}") GB"
        elif [[ $b -ge 1048576 ]]; then
            echo "$(awk "BEGIN{printf \"%.1f\", $b/1048576}") MB"
        elif [[ $b -ge 1024 ]]; then
            echo "$(awk "BEGIN{printf \"%.1f\", $b/1024}") KB"
        else
            echo "${b} B"
        fi
    }

    RX_FMT=$(format_bytes "${RX_BYTES:-0}")
    TX_FMT=$(format_bytes "${TX_BYTES:-0}")

    # Handshake status
    if [[ "$HANDSHAKE" -eq 0 ]]; then
        STATUS="✗ no handshake"
        HANDSHAKE_AGO="never"
    else
        STATUS="✓ active"
        NOW=$(date +%s)
        AGO=$((NOW - HANDSHAKE))
        if [[ $AGO -lt 60 ]]; then
            HANDSHAKE_AGO="${AGO}s ago"
        elif [[ $AGO -lt 3600 ]]; then
            HANDSHAKE_AGO="$((AGO / 60))m ago"
        else
            HANDSHAKE_AGO="$((AGO / 3600))h ago"
        fi
    fi

    echo "  $STATUS  $NAME"
    echo "    Key:       $SHORT_PK"
    echo "    IP:        ${IP:-unknown}"
    echo "    Handshake: $HANDSHAKE_AGO"
    echo "    RX:        $RX_FMT"
    echo "    TX:        $TX_FMT"
    echo ""
done

echo "Add peer:    sudo bash $(dirname "$0")/add-peer.sh"
echo "Revoke peer: sudo bash $(dirname "$0")/revoke-peer.sh <key>"