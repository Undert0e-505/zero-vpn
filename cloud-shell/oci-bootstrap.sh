#!/bin/bash
# ============================================================
# ZeroVPN OCI Cloud Shell Bootstrap
# ============================================================
# Creates a complete Always Free eligible OCI VM with:
#   - VCN + public subnet + internet gateway + route table
#   - Security list (SSH 22/tcp + WireGuard 51820/udp)
#   - Ubuntu 22.04 micro instance with public IP
#   - SSH key injection from the project keypair
#
# Designed to run in OCI Cloud Shell (pre-authenticated OCI CLI).
#
# Usage (from Cloud Shell):
#   bash oci-bootstrap.sh
#
# Or paste as a one-liner:
#   bash <(curl -sL https://raw.githubusercontent.com/Undert0e-505/zero-vpn/main/cloud-shell/oci-bootstrap.sh)
#
# Output: public IP, SSH username, and connection instructions.
# ============================================================

set -euo pipefail

# --- Config ---
INSTANCE_NAME="zerovpn-exit-01"
VCN_CIDR="10.0.0.0/24"
SUBNET_CIDR="10.0.0.0/24"
SHAPE="VM.Standard.E2.1.Micro"
SSH_PORT=22
WG_PORT=51820
BOOT_VOLUME_GB=50
SSH_USERNAME="ubuntu"

# The SSH public key generated for this project.
# Cloud Shell runs as the OCI user, not as Jimothy — we embed the key
# so the agent can SSH in from the workstation.
SSH_PUB_KEY="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAzBCGOBQ2w1+NG+QUGOpR7zggzhXEp5iaOx/ymYMZWz zerovpn-exit-01"

# --- Pre-flight ---
echo "=== ZeroVPN OCI Bootstrap ==="
echo ""

# Verify we're in Cloud Shell with OCI CLI auth
if ! command -v oci &>/dev/null; then
    echo "ERROR: oci CLI not found. This script must run in OCI Cloud Shell."
    exit 1
fi

# Test auth
echo "[1/9] Verifying OCI CLI authentication..."
if ! oci iam compartment list --output json >/dev/null 2>&1; then
    echo "ERROR: OCI CLI is not authenticated. Run this script in OCI Cloud Shell."
    exit 1
fi
echo "  Authenticated  OK"
echo ""

# --- Get tenancy / compartment ---
echo "[2/9] Finding compartment..."
COMPARTMENT_ID=$(oci iam compartment list \
    --access-level ACCESSIBLE \
    --include-root \
    --output json 2>/dev/null \
    | jq -r '.data[] | select(.lifecycle-state == "ACTIVE") | .id' | head -1)

if [[ -z "$COMPARTMENT_ID" ]]; then
    # Fallback: use tenancy OCID as compartment
    COMPARTMENT_ID=$(oci iam compartment list \
        --output json 2>/dev/null \
        | jq -r '.data[0].id')
fi

if [[ -z "$COMPARTMENT_ID" ]]; then
    echo "ERROR: Could not find an active compartment."
    exit 1
fi
echo "  Compartment: $COMPARTMENT_ID  OK"
echo ""

# --- Get availability domain ---
echo "[3/9] Getting availability domain..."
AD_NAME=$(oci iam availability-domain list \
    --compartment-id "$COMPARTMENT_ID" \
    --output json \
    | jq -r '.data[0].name')
echo "  AD: $AD_NAME  OK"
echo ""

# --- Get Ubuntu image ---
echo "[4/9] Finding Ubuntu 22.04 image..."
IMAGE_ID=$(oci compute image list \
    --compartment-id "$COMPARTMENT_ID" \
    --operating-system "Canonical Ubuntu" \
    --operating-system-version "22.04" \
    --shape "$SHAPE" \
    --sort-by TIMECREATED \
    --sort-order DESC \
    --output json \
    | jq -r '.data[0].id')

if [[ -z "$IMAGE_ID" || "$IMAGE_ID" == "null" ]]; then
    echo "  Ubuntu 22.04 not found, trying 24.04..."
    IMAGE_ID=$(oci compute image list \
        --compartment-id "$COMPARTMENT_ID" \
        --operating-system "Canonical Ubuntu" \
        --operating-system-version "24.04" \
        --shape "$SHAPE" \
        --sort-by TIMECREATED \
        --sort-order DESC \
        --output json \
        | jq -r '.data[0].id')
fi

if [[ -z "$IMAGE_ID" || "$IMAGE_ID" == "null" ]]; then
    echo "ERROR: No suitable Ubuntu image found for shape $SHAPE"
    exit 1
fi
echo "  Image: $IMAGE_ID  OK"
echo ""

# --- Create VCN ---
echo "[5/9] Creating VCN..."
VCN_ID=$(oci network vcn create \
    --cidr-block "$VCN_CIDR" \
    --compartment-id "$COMPARTMENT_ID" \
    --display-name "zerovpn-vcn" \
    --dns-label "zerovpn" \
    --wait-for-state AVAILABLE \
    --output json 2>/dev/null \
    | jq -r '.data.id')
echo "  VCN: $VCN_ID  OK"
echo ""

# --- Create public subnet + security list ---
echo "[6/9] Creating security list and subnet..."

# Security list with SSH + WireGuard ingress
SECURITY_LIST_ID=$(oci network security-list create \
    --compartment-id "$COMPARTMENT_ID" \
    --vcn-id "$VCN_ID" \
    --display-name "zerovpn-security-list" \
    --egress-security-rules '[{"destination":"0.0.0.0/0","protocol":"all","isStateless":false}]' \
    --ingress-security-rules "[\
{\"source\":\"0.0.0.0/0\",\"protocol\":\"6\",\"isStateless\":false,\"tcpOptions\":{\"destinationPortRange\":{\"min\":${SSH_PORT},\"max\":${SSH_PORT}}}},\
{\"source\":\"0.0.0.0/0\",\"protocol\":\"17\",\"isStateless\":false,\"udpOptions\":{\"destinationPortRange\":{\"min\":${WG_PORT},\"max\":${WG_PORT}}}}\
]" \
    --wait-for-state AVAILABLE \
    --output json 2>/dev/null \
    | jq -r '.data.id')
echo "  Security list: $SECURITY_LIST_ID  OK"

# Regional public subnet (omit --availability-domain for regional)
SUBNET_ID=$(oci network subnet create \
    --cidr-block "$SUBNET_CIDR" \
    --compartment-id "$COMPARTMENT_ID" \
    --display-name "zerovpn-subnet" \
    --vcn-id "$VCN_ID" \
    --security-list-ids "[$(jq -rn --arg id "$SECURITY_LIST_ID" '\"\\($id)\"')]" \
    --dhcp-options-id "$(oci network dhcp-options list \
        --compartment-id "$COMPARTMENT_ID" \
        --vcn-id "$VCN_ID" \
        --output json \
        | jq -r '.data[0].id')" \
    --wait-for-state AVAILABLE \
    --output json 2>/dev/null \
    | jq -r '.data.id')
echo "  Subnet: $SUBNET_ID  OK"
echo ""

# --- Create internet gateway + route ---
echo "[7/9] Creating internet gateway and route..."
IGW_ID=$(oci network internet-gateway create \
    --compartment-id "$COMPARTMENT_ID" \
    --display-name "zerovpn-igw" \
    --is-enabled true \
    --vcn-id "$VCN_ID" \
    --wait-for-state AVAILABLE \
    --output json 2>/dev/null \
    | jq -r '.data.id')

# Update default route table
DEFAULT_RT_ID=$(oci network vcn get \
    --vcn-id "$VCN_ID" \
    --output json \
    | jq -r '.data."default-route-table-id"')

oci network route-table update \
    --rt-id "$DEFAULT_RT_ID" \
    --force \
    --route-rules "[{\"destination\":\"0.0.0.0/0\",\"destinationType\":\"CIDR_BLOCK\",\"networkEntityId\":\"${IGW_ID}\"}]" \
    --wait-for-state AVAILABLE \
    --output json >/dev/null 2>&1
echo "  Internet gateway + route  OK"
echo ""

# --- Launch instance ---
echo "[8/9] Launching instance (this takes 2-5 minutes)..."
# Write SSH key to a temp file for --ssh-authorized-keys-file
SSH_KEY_FILE=$(mktemp)
echo "$SSH_PUB_KEY" > "$SSH_KEY_FILE"

INSTANCE_ID=$(oci compute instance launch \
    --availability-domain "$AD_NAME" \
    --compartment-id "$COMPARTMENT_ID" \
    --display-name "$INSTANCE_NAME" \
    --image-id "$IMAGE_ID" \
    --shape "$SHAPE" \
    --subnet-id "$SUBNET_ID" \
    --assign-public-ip true \
    --boot-volume-size-in-gbs "$BOOT_VOLUME_GB" \
    --ssh-authorized-keys-file "$SSH_KEY_FILE" \
    --wait-for-state RUNNING \
    --output json 2>/dev/null \
    | jq -r '.data.id')

rm -f "$SSH_KEY_FILE"
echo "  Instance: $INSTANCE_ID  OK"
echo ""

# --- Get public IP ---
echo "[9/9] Retrieving public IP..."
sleep 5  # Give VNIC a moment to settle

# Try a few times — VNIC can take a moment to report the public IP
PUBLIC_IP=""
for i in 1 2 3 4 5; do
    VNIC_ID=$(oci compute instance list-vnics \
        --instance-id "$INSTANCE_ID" \
        --output json \
        | jq -r '.data[0]."vnic-id" // .data[0].id // empty')
    if [[ -n "$VNIC_ID" ]]; then
        PUBLIC_IP=$(oci network vnic get \
            --vnic-id "$VNIC_ID" \
            --output json \
            | jq -r '.data."public-ip" // empty')
    fi
    if [[ -n "$PUBLIC_IP" && "$PUBLIC_IP" != "null" ]]; then
        break
    fi
    echo "  Waiting for public IP... (attempt $i)"
    sleep 10
done

if [[ -z "$PUBLIC_IP" || "$PUBLIC_IP" == "null" ]]; then
    echo ""
    echo "=========================================="
    echo "  Instance created but public IP not yet assigned."
    echo "  Check the console in a minute:"
    echo "  Compute > Instances > $INSTANCE_NAME"
    echo "=========================================="
    echo ""
    echo "Instance ID: $INSTANCE_ID"
    echo "VNIC ID:     $VNIC_ID"
else
    echo ""
    echo "=========================================="
    echo "  ZeroVPN VM is running!"
    echo "=========================================="
    echo ""
    echo "  Instance name:  $INSTANCE_NAME"
    echo "  Public IP:      $PUBLIC_IP"
    echo "  SSH username:   $SSH_USERNAME"
    echo "  SSH command:    ssh -i <private-key> $SSH_USERNAME@$PUBLIC_IP"
    echo ""
    echo "  WireGuard port: $WG_PORT/udp (opened in security list)"
    echo "  SSH port:       $SSH_PORT/tcp (opened in security list)"
    echo ""
    echo "  Send the public IP to Jimothy via Telegram"
    echo "  and he'll SSH in to install WireGuard."
    echo "=========================================="
fi

echo ""
echo "Resource IDs (save for cleanup):"
echo "  VCN:            $VCN_ID"
echo "  Subnet:         $SUBNET_ID"
echo "  Security list:  $SECURITY_LIST_ID"
echo "  Internet GW:    $IGW_ID"
echo "  Instance:       $INSTANCE_ID"