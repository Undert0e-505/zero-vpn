#!/bin/bash
set -e

PUBLIC_IF=$(ip route show default | awk '{print $5; exit}')
if [ -z "$PUBLIC_IF" ]; then
  echo "ERROR: Could not detect default route interface" >&2
  exit 1
fi
echo "PUBLIC_INTERFACE=$PUBLIC_IF"

# Get server private key
SERVER_KEY=$(sudo cat /etc/wireguard/server.key)

# Write wg0.conf
sudo bash -c "cat > /etc/wireguard/wg0.conf << EOF
[Interface]
PrivateKey = $SERVER_KEY
Address = 10.66.66.1/24
ListenPort = 51820
PostUp = iptables -A FORWARD -i wg0 -o $PUBLIC_IF -j ACCEPT; iptables -A FORWARD -i $PUBLIC_IF -o wg0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; iptables -t nat -A POSTROUTING -s 10.66.66.0/24 -o $PUBLIC_IF -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -o $PUBLIC_IF -j ACCEPT; iptables -D FORWARD -i $PUBLIC_IF -o wg0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; iptables -t nat -D POSTROUTING -s 10.66.66.0/24 -o $PUBLIC_IF -j MASQUERADE
EOF"
sudo chmod 600 /etc/wireguard/wg0.conf

# Enable IP forwarding
sudo sysctl -w net.ipv4.ip_forward=1
echo 'net.ipv4.ip_forward=1' | sudo tee /etc/sysctl.d/99-zerovpn-forward.conf
sudo sysctl --system

# Start WireGuard
sudo systemctl enable wg-quick@wg0
sudo systemctl start wg-quick@wg0

# Generate peer keypair
wg genkey | tee /tmp/peer.key | wg pubkey > /tmp/peer.pub
PEER_KEY=$(cat /tmp/peer.key)
PEER_PUB=$(cat /tmp/peer.pub)

# Add peer to server
sudo wg set wg0 peer "$PEER_PUB" allowed-ips 10.66.66.2/32

# Get server public key
SERVER_PUB=$(sudo cat /etc/wireguard/server.pub)

# Output
echo "PEER_PRIVATE_KEY=$PEER_KEY"
echo "SERVER_PUBLIC_KEY=$SERVER_PUB"
echo "---"
sudo wg show
