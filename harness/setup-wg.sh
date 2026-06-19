#!/bin/bash
set -e

# Get server private key
SERVER_KEY=$(sudo cat /etc/wireguard/server.key)

# Write wg0.conf
sudo bash -c "cat > /etc/wireguard/wg0.conf << 'WGEOF'
[Interface]
PrivateKey = $SERVER_KEY
Address = 10.66.66.1/24
ListenPort = 51820
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o eth0 -j MASQUERADE
WGEOF"
sudo chmod 600 /etc/wireguard/wg0.conf

# Enable IP forwarding
sudo sysctl -w net.ipv4.ip_forward=1
echo 'net.ipv4.ip_forward=1' | sudo tee -a /etc/sysctl.conf

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