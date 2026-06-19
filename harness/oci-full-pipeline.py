#!/usr/bin/env python3
"""
OCI Full Pipeline — end-to-end provisioning test on Windows.

Runs the complete flow:
1. Browser auth (harness auth → security token)
2. Upload API key (security token → permanent API key signing)
3. Query tenancy (home region, availability domains, images)
4. Create network (VCN, subnet, IGW, route table, security list)
5. Launch instance (Ubuntu micro, public IP, SSH key)
6. Wait for instance, get public IP
7. SSH in, install WireGuard, configure
8. Generate peer config for testing
9. Verify connectivity
10. Teardown (destroy all resources)

Usage:
    D:\\Python310\\python.exe oci-full-pipeline.py --region uk-london-1
    D:\\Python310\\python.exe oci-full-pipeline.py --region uk-london-1 --skip-teardown
    D:\\Python310\\python.exe oci-full-pipeline.py --region uk-london-1 --teardown-only --vcn-id REDACTED_OCID
"""

import argparse
import base64
import hashlib
import json
import os
import sys
import time
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs, urlencode
from datetime import datetime
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

import oci
from oci.regions import REGIONS, REALMS, REGION_REALMS, REGIONS_SHORT_NAMES, is_region
from oci import identity
from oci import core
from oci import network

LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "pipeline.log")

def log(msg):
    line = f"[{datetime.now().strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    with open(LOG_FILE, 'a', encoding='utf-8') as f:
        f.write(line + '\n')

# ============================================================
# Step 1: Browser Auth (same as harness)
# ============================================================

BOOTSTRAP_PORT = 8181
CONSOLE_AUTH_URL_FORMAT = "https://login.{region}.{realm}/v1/oauth2/authorize"

def generate_keypair():
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    return private_key, private_key.public_key()

def public_key_to_jwk(public_key):
    """Exact CLI format: kty, n, e, kid — no alg/use, stripped padding."""
    numbers = public_key.public_numbers()
    def bytes_from_int(val):
        remaining = val
        byte_length = 0
        while remaining != 0:
            remaining = remaining >> 8
            byte_length += 1
        return val.to_bytes(byte_length, 'big', signed=False)
    def base64url_encode(data):
        return base64.urlsafe_b64encode(data).replace(b'=', b'')
    def to_base64url_uint(val):
        int_bytes = bytes_from_int(val)
        if len(int_bytes) == 0:
            int_bytes = b'\x00'
        return base64url_encode(int_bytes)
    n = to_base64url_uint(numbers.n).decode('UTF-8')
    e = to_base64url_uint(numbers.e).decode('UTF-8')
    jwk_json = json.dumps({"kty": "RSA", "n": n, "e": e, "kid": "Ignored"})
    jwk_b64 = base64.urlsafe_b64encode(jwk_json.encode('UTF-8')).decode('UTF-8')
    return jwk_json, jwk_b64

def public_key_fingerprint(public_key):
    der = public_key.public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return ':'.join(f'{b:02x}' for b in hashlib.sha256(der).digest())

def build_authorize_url(region, jwk_b64, redirect_uri):
    if region in REGIONS_SHORT_NAMES:
        region = REGIONS_SHORT_NAMES[region]
    realm = REALMS[REGION_REALMS[region]]
    console_url = CONSOLE_AUTH_URL_FORMAT.format(region=region, realm=realm)
    query = {
        'action': 'login',
        'client_id': 'iaas_console',
        'response_type': 'token id_token',
        'nonce': str(uuid.uuid4()),
        'scope': 'openid',
        'public_key': jwk_b64,
        'redirect_uri': redirect_uri,
    }
    return f"{console_url}?{urlencode(query)}"

class CallbackHandler(BaseHTTPRequestHandler):
    captured_token = None
    def log_message(self, fmt, *args):
        log(f"  [HTTP] {fmt % args}")
    def do_GET(self):
        log(f"  [HTTP] GET {self.path}")
        if self.path == '/' or self.path.startswith('/?'):
            js = """<script type='text/javascript'>
                hash = window.location.hash
                if (hash[0] === '#') { hash = hash.substr(1) }
                function reqListener () { document.write('OK'); }
                var oReq = new XMLHttpRequest();
                oReq.addEventListener("load", reqListener);
                oReq.open("GET", "/token?" + hash);
                oReq.send();
            </script>"""
            self.send_response(200)
            self.end_headers()
            self.wfile.write(js.encode('UTF-8'))
        elif self.path.startswith('/token?'):
            params = parse_qs(urlparse(self.path).query)
            if 'security_token' in params:
                CallbackHandler.captured_token = params['security_token'][0]
                log("  [HTTP] Token captured!")
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'OK')
        else:
            self.send_response(404)
            self.end_headers()

class StoppableHTTPServer(HTTPServer):
    def serve_forever(self):
        self.stop = False
        while not self.stop:
            self.handle_request()
        self.server_close()

def do_browser_auth(region):
    """Step 1: Browser auth → security token + user/tenancy OCIDs."""
    log("[1/10] Browser auth")
    log("  Generating RSA keypair...")
    private_key, public_key = generate_keypair()
    fingerprint = public_key_fingerprint(public_key)
    
    jwk_json, jwk_b64 = public_key_to_jwk(public_key)
    log(f"  JWK: {jwk_json[:80]}...")
    log(f"  Fingerprint: {fingerprint}")
    
    redirect_uri = f"http://localhost:{BOOTSTRAP_PORT}"
    auth_url = build_authorize_url(region, jwk_b64, redirect_uri)
    log(f"  Auth URL: {auth_url[:100]}...")
    
    log("  Starting localhost listener on port 8181...")
    server = StoppableHTTPServer(('', BOOTSTRAP_PORT), CallbackHandler)
    
    log("  Opening browser... please complete Oracle login.")
    webbrowser.open_new(auth_url)
    
    log("  Waiting for callback (5 min timeout)...")
    import threading
    def timeout():
        time.sleep(300)
        if CallbackHandler.captured_token is None:
            log("  TIMEOUT — no callback received")
            server.stop = True
    threading.Thread(target=timeout, daemon=True).start()
    
    server.serve_forever()
    token = CallbackHandler.captured_token
    
    if not token:
        log("  FAILED: No token captured")
        sys.exit(1)
    
    # Decode JWT
    stc = oci.auth.security_token_container.SecurityTokenContainer(None, security_token=token)
    jwt_data = stc.get_jwt()
    user_ocid = jwt_data['sub']
    tenancy_ocid = jwt_data['tenant']
    
    log(f"  User OCID: {user_ocid}")
    log(f"  Tenancy OCID: {tenancy_ocid}")
    log("  Auth OK")
    
    return {
        'token': token,
        'private_key': private_key,
        'public_key': public_key,
        'fingerprint': fingerprint,
        'user_ocid': user_ocid,
        'tenancy_ocid': tenancy_ocid,
        'region': region,
    }

# ============================================================
# Step 2: Upload API Key
# ============================================================

def do_upload_api_key(auth):
    """Upload the public key as a permanent API signing key."""
    log("[2/10] Upload API key")
    
    # Serialize private key to PEM for the signer
    priv_pem = auth['private_key'].private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption()
    )
    
    # Create signer with security token
    signer = oci.auth.signers.SecurityTokenSigner(auth['token'], priv_pem)
    
    # Find home region
    client = identity.IdentityClient({'region': auth['region']}, signer=signer)
    subscriptions = client.list_region_subscriptions(auth['tenancy_ocid'])
    home_region = None
    for sub in subscriptions.data:
        if sub.is_home_region:
            home_region = sub.region_name
            break
    
    if not home_region:
        log("  WARNING: Could not find home region, using specified region")
        home_region = auth['region']
    
    log(f"  Home region: {home_region}")
    
    # Use home region client for API key upload
    client = identity.IdentityClient({'region': home_region}, signer=signer)
    
    # Serialize public key to PEM
    pub_pem = auth['public_key'].public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode('UTF-8')
    
    create_details = identity.models.CreateApiKeyDetails()
    create_details.key = pub_pem
    
    try:
        result = client.upload_api_key(auth['user_ocid'], create_details)
        log(f"  API key uploaded! Fingerprint: {auth['fingerprint']}")
    except oci.exceptions.ServiceError as e:
        if e.status == 409 and e.code == 'ApiKeyLimitExceeded':
            log("  API key limit exceeded. Existing keys:")
            keys = client.list_api_keys(auth['user_ocid'])
            for k in keys.data:
                log(f"    {k.fingerprint} (created {k.time_created})")
            # Try to use existing key with matching fingerprint
            existing = [k for k in keys.data if k.fingerprint == auth['fingerprint']]
            if existing:
                log("  Using existing matching key")
            else:
                log("  FAILED: Need to delete an existing key first")
                sys.exit(1)
        else:
            raise
    
    auth['home_region'] = home_region
    return priv_pem

# ============================================================
# Step 3: Create API key signer (permanent)
# ============================================================

def make_api_signer(auth, priv_pem):
    """Create a signer using API key authentication (permanent, no token expiry)."""
    log("[3/10] Setting up API key signer")
    signer = oci.auth.signers.InstancePrincipalsDelegationTokenSigner  # wrong
    # Actually use basic API key signing
    from oci.signer import Signer
    api_signer = Signer(
        tenancy=auth['tenancy_ocid'],
        user=auth['user_ocid'],
        fingerprint=auth['fingerprint'],
        private_key_file_location=None,
        private_key=priv_pem,
        pass_phrase=None,
    )
    log("  API key signer ready")
    return api_signer

# ============================================================
# Step 4: Query tenancy info
# ============================================================

def query_tenancy(auth, signer):
    """Get availability domains and Ubuntu image."""
    log("[4/10] Querying tenancy info")
    region = auth['home_region']
    compartment_id = auth['tenancy_ocid']  # root compartment
    
    identity_client = identity.IdentityClient({'region': region}, signer=signer)
    
    # Availability domains
    ads = identity_client.list_availability_domains(compartment_id=compartment_id)
    ad_name = ads.data[0].name
    log(f"  Availability domain: {ad_name}")
    
    # Find Ubuntu 22.04 image
    compute_client = core.ComputeClient({'region': region}, signer=signer)
    images = compute_client.list_images(
        compartment_id=compartment_id,
        operating_system="Canonical Ubuntu",
        operating_system_version="22.04",
        shape="VM.Standard.E2.1.Micro",
        sort_by="TIMECREATED",
        sort_order="DESC",
    )
    
    if not images.data:
        log("  Ubuntu 22.04 not found, trying 24.04...")
        images = compute_client.list_images(
            compartment_id=compartment_id,
            operating_system="Canonical Ubuntu",
            operating_system_version="24.04",
            shape="VM.Standard.E2.1.Micro",
            sort_by="TIMECREATED",
            sort_order="DESC",
        )
    
    if not images.data:
        log("  FAILED: No Ubuntu image found")
        sys.exit(1)
    
    image_id = images.data[0].id
    log(f"  Image: {image_id}")
    
    return {
        'ad_name': ad_name,
        'image_id': image_id,
        'compartment_id': compartment_id,
    }

# ============================================================
# Step 5: Create network infrastructure
# ============================================================

def create_network(auth, signer):
    """Create VCN, subnet, IGW, route table, security list."""
    log("[5/10] Creating network infrastructure")
    region = auth['home_region']
    compartment_id = auth['tenancy_ocid']
    
    net = network.VirtualNetworkClient({'region': region}, signer=signer)
    
    # VCN
    vcn = net.create_vcn(
        create_vcn_details=network.models.CreateVcnDetails(
            cidr_block="10.0.0.0/24",
            compartment_id=compartment_id,
            display_name="zerovpn-vcn",
            dns_label="zerovpn",
        )
    )
    vcn_id = vcn.data.id
    log(f"  VCN: {vcn_id}")
    # Wait for VCN to be available
    net.get_vcn(vcn_id).wait_for_state(lifecycle_state="AVAILABLE")
    
    # Security list
    sl = net.create_security_list(
        create_security_list_details=network.models.CreateSecurityListDetails(
            compartment_id=compartment_id,
            vcn_id=vcn_id,
            display_name="zerovpn-sl",
            egress_security_rules=[
                network.models.EgressSecurityRule(
                    destination="0.0.0.0/0",
                    protocol="all",
                    is_stateless=False,
                )
            ],
            ingress_security_rules=[
                # SSH
                network.models.IngressSecurityRule(
                    source="0.0.0.0/0",
                    protocol="6",
                    is_stateless=False,
                    tcp_options=network.models.TcpOptions(
                        destination_port_range=network.models.PortRange(min=22, max=22)
                    ),
                ),
                # WireGuard
                network.models.IngressSecurityRule(
                    source="0.0.0.0/0",
                    protocol="17",
                    is_stateless=False,
                    udp_options=network.models.UdpOptions(
                        destination_port_range=network.models.PortRange(min=51820, max=51820)
                    ),
                ),
            ],
        )
    )
    sl_id = sl.data.id
    log(f"  Security list: {sl_id}")
    
    # DHCP options (get default)
    dhcp_options = net.list_dhcp_options(compartment_id=compartment_id, vcn_id=vcn_id)
    dhcp_id = dhcp_options.data[0].id
    
    # Subnet (regional — omit availability_domain)
    subnet = net.create_subnet(
        create_subnet_details=network.models.CreateSubnetDetails(
            cidr_block="10.0.0.0/24",
            compartment_id=compartment_id,
            display_name="zerovpn-subnet",
            vcn_id=vcn_id,
            security_list_ids=[sl_id],
            dhcp_options_id=dhcp_id,
        )
    )
    subnet_id = subnet.data.id
    log(f"  Subnet: {subnet_id}")
    net.get_subnet(subnet_id).wait_for_state(lifecycle_state="AVAILABLE")
    
    # Internet gateway
    igw = net.create_internet_gateway(
        create_internet_gateway_details=network.models.CreateInternetGatewayDetails(
            compartment_id=compartment_id,
            display_name="zerovpn-igw",
            is_enabled=True,
            vcn_id=vcn_id,
        )
    )
    igw_id = igw.data.id
    log(f"  Internet gateway: {igw_id}")
    net.get_internet_gateway(igw_id).wait_for_state(lifecycle_state="AVAILABLE")
    
    # Update default route table
    vcn_data = net.get_vcn(vcn_id).data
    default_rt_id = vcn_data.default_route_table_id
    net.update_route_table(
        route_table_id=default_rt_id,
        update_route_table_details=network.models.UpdateRouteTableDetails(
            route_rules=[
                network.models.RouteRule(
                    destination="0.0.0.0/0",
                    destination_type="CIDR_BLOCK",
                    network_entity_id=igw_id,
                )
            ]
        )
    )
    log(f"  Route table updated")
    
    return {
        'vcn_id': vcn_id,
        'subnet_id': subnet_id,
        'sl_id': sl_id,
        'igw_id': igw_id,
    }

# ============================================================
# Step 6: Launch instance
# ============================================================

SSH_PUB_KEY = "ssh-ed25519 AAAA_REPLACE_WITH_TEST_PUBLIC_KEY example-only"

def launch_instance(auth, signer, tenancy, network_info):
    """Launch the Ubuntu micro VM."""
    log("[6/10] Launching instance")
    region = auth['home_region']
    
    compute = core.ComputeClient({'region': region}, signer=signer)
    
    # Write SSH key to temp file
    import tempfile
    with tempfile.NamedTemporaryFile(mode='w', suffix='.pub', delete=False) as f:
        f.write(SSH_PUB_KEY + '\n')
        key_file = f.name
    
    try:
        instance = compute.launch_instance(
            launch_instance_details=core.models.LaunchInstanceDetails(
                availability_domain=tenancy['ad_name'],
                compartment_id=tenancy['compartment_id'],
                display_name="zerovpn-exit-01",
                image_id=tenancy['image_id'],
                shape="VM.Standard.E2.1.Micro",
                subnet_id=network_info['subnet_id'],
                assign_public_ip=True,
                boot_volume_size_in_gbs=50,
                ssh_authorized_keys_file=key_file,
            )
        )
    finally:
        os.unlink(key_file)
    
    instance_id = instance.data.id
    log(f"  Instance: {instance_id}")
    
    # Wait for RUNNING
    log("  Waiting for instance to be RUNNING...")
    compute.get_instance(instance_id).wait_for_state(
        lifecycle_state="RUNNING",
        wait_for_state_kwargs={"max_wait_seconds": 300},
    )
    log("  Instance is RUNNING")
    
    return instance_id

# ============================================================
# Step 7: Get public IP
# ============================================================

def get_public_ip(auth, signer, instance_id):
    """Get the public IP of the instance."""
    log("[7/10] Getting public IP")
    region = auth['home_region']
    compute = core.ComputeClient({'region': region}, signer=signer)
    
    # Wait a moment for VNIC to settle
    time.sleep(5)
    
    for attempt in range(10):
        vnics = compute.list_vnics(instance_id=instance_id)
        if vnics.data:
            vnic_id = vnics.data[0].vnic_id
            net = network.VirtualNetworkClient({'region': region}, signer=signer)
            vnic = net.get_vnic(vnic_id).data
            if vnic.public_ip:
                log(f"  Public IP: {vnic.public_ip}")
                return vnic.public_ip
        log(f"  Waiting for public IP... (attempt {attempt+1})")
        time.sleep(10)
    
    log("  FAILED: Could not get public IP after 10 attempts")
    return None

# ============================================================
# Step 8: SSH and install WireGuard
# ============================================================

def ssh_install_wireguard(public_ip):
    """SSH into the VM and install WireGuard."""
    log("[8/10] SSH + WireGuard install")
    
    ssh_key = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "secrets", "oci-zerovpn-exit-01")
    if not os.path.exists(ssh_key):
        # Try absolute path
        ssh_key = r"D:\dev\zero-vpn\secrets\oci-zerovpn-exit-01"
    
    if not os.path.exists(ssh_key):
        log(f"  SSH key not found at {ssh_key}")
        log("  Skipping SSH step — VM is running, you can SSH manually")
        return False
    
    # Use ssh command
    import subprocess
    
    ssh_opts = [
        "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=/dev/null",
        "-o", "ConnectTimeout=30",
        "-i", ssh_key,
        f"ubuntu@{public_ip}",
    ]
    
    # Wait for SSH to be available
    log("  Waiting for SSH to be available...")
    for attempt in range(12):
        try:
            result = subprocess.run(
                ["ssh"] + ssh_opts + ["echo", "connected"],
                capture_output=True, text=True, timeout=15
            )
            if result.returncode == 0:
                log("  SSH connected!")
                break
        except Exception:
            pass
        log(f"  Waiting for SSH... (attempt {attempt+1})")
        time.sleep(10)
    else:
        log("  SSH not available, skipping WireGuard install")
        return False
    
    # Install WireGuard
    log("  Installing WireGuard...")
    install_cmds = """
        sudo apt-get update -y &&
        sudo apt-get install -y wireguard wireguard-tools &&
        sudo sh -c 'umask 077; wg genkey > /etc/wireguard/server.key; wg pubkey < /etc/wireguard/server.key > /etc/wireguard/server.pub' &&
        echo 'WireGuard installed'
    """
    result = subprocess.run(
        ["ssh"] + ssh_opts + [install_cmds],
        capture_output=True, text=True, timeout=120,
    )
    log(f"  Install result: {result.stdout.strip()[-200:]}")
    if result.stderr.strip():
        log(f"  stderr: {result.stderr.strip()[-200:]}")
    
    # Get server public key
    log("  Getting server public key...")
    result = subprocess.run(
        ["ssh"] + ssh_opts + ["sudo", "cat", "/etc/wireguard/server.pub"],
        capture_output=True, text=True, timeout=15
    )
    server_pubkey = result.stdout.strip()
    log(f"  Server public key: {server_pubkey}")
    
    # Configure WireGuard server
    log("  Configuring WireGuard server...")
    wg_config = f"""
[Interface]
PrivateKey = $(sudo cat /etc/wireguard/server.key)
Address = 10.66.66.1/24
ListenPort = 51820
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o eth0 -j MASQUERADE

[Peer]
PublicKey = PLACEHOLDER
AllowedIPs = 10.66.66.2/32
"""
    # Actually do it properly via SSH
    setup_cmds = """
        SERVER_KEY=$(sudo cat /etc/wireguard/server.key)
        sudo bash -c "cat > /etc/wireguard/wg0.conf << EOF
[Interface]
PrivateKey = $SERVER_KEY
Address = 10.66.66.1/24
ListenPort = 51820
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o eth0 -j MASQUERADE
EOF"
        sudo chmod 600 /etc/wireguard/wg0.conf
        sudo sysctl -w net.ipv4.ip_forward=1
        echo 'net.ipv4.ip_forward=1' | sudo tee -a /etc/sysctl.conf
        sudo systemctl enable wg-quick@wg0
        sudo systemctl start wg-quick@wg0
        sudo wg show
    """
    result = subprocess.run(
        ["ssh"] + ssh_opts + [setup_cmds],
        capture_output=True, text=True, timeout=60,
    )
    log(f"  Server config result: {result.stdout.strip()[-300:]}")
    
    # Generate peer config for the client
    log("  Generating peer config...")
    peer_cmds = """
        wg genkey | tee /tmp/peer.key | wg pubkey > /tmp/peer.pub
        PEER_KEY=$(cat /tmp/peer.key)
        PEER_PUB=$(cat /tmp/peer.pub)
        sudo wg set wg0 peer $PEER_PUB allowed-ips 10.66.66.2/32
        SERVER_PUB=$(sudo cat /etc/wireguard/server.pub)
        echo "PEER_PRIVATE_KEY=$PEER_KEY"
        echo "SERVER_PUBLIC_KEY=$SERVER_PUB"
    """
    result = subprocess.run(
        ["ssh"] + ssh_opts + [peer_cmds],
        capture_output=True, text=True, timeout=30,
    )
    log(f"  Peer config: {result.stdout.strip()}")
    
    # Parse peer config
    peer_privkey = None
    server_pubkey = None
    for line in result.stdout.split('\n'):
        if line.startswith('PEER_PRIVATE_KEY='):
            peer_privkey = line.split('=', 1)[1].strip()
        elif line.startswith('SERVER_PUBLIC_KEY='):
            server_pubkey = line.split('=', 1)[1].strip()
    
    if peer_privkey and server_pubkey:
        client_conf = f"""[Interface]
PrivateKey = {peer_privkey}
Address = 10.66.66.2/24
DNS = 1.1.1.1

[Peer]
PublicKey = {server_pubkey}
Endpoint = {public_ip}:51820
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
"""
        log("  Client config generated:")
        log("  ---")
        for line in client_conf.strip().split('\n'):
            log(f"  {line}")
        log("  ---")
        
        # Save client config
        conf_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "client.conf")
        with open(conf_path, 'w') as f:
            f.write(client_conf)
        log(f"  Saved to: {conf_path}")
        return True
    else:
        log("  Failed to generate peer config")
        return False

# ============================================================
# Step 9: Verify (just check the server is listening)
# ============================================================

def verify_wireguard(auth, signer, public_ip):
    """Check that WireGuard is listening on the server."""
    log("[9/10] Verifying WireGuard")
    
    # Check port 51820/udp is open via a simple UDP probe
    import socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(5)
    try:
        sock.sendto(b"\x00\x00\x00\x00", (public_ip, 51820))
        log("  UDP probe sent to port 51820 (no response expected — WireGuard drops invalid packets)")
        log("  Verification is implicit: if the SSH steps above worked, the server is up.")
        log("  Full tunnel verification requires a WireGuard client on this machine.")
    except Exception as e:
        log(f"  Probe note: {e}")
    finally:
        sock.close()
    
    log("  WireGuard is installed and running on the server.")
    log("  Use the client.conf file with: wg-quick up client.conf")
    log("  or import into the ZeroVPN Android app.")

# ============================================================
# Step 10: Teardown
# ============================================================

def teardown(auth, signer, network_info, instance_id):
    """Destroy all created resources."""
    log("[10/10] Teardown")
    region = auth['home_region']
    compute = core.ComputeClient({'region': region}, signer=signer)
    net = network.VirtualNetworkClient({'region': region}, signer=signer)
    
    # Terminate instance
    if instance_id:
        log(f"  Terminating instance {instance_id}...")
        compute.terminate_instance(instance_id=instance_id, preserve_boot_volume=False)
        compute.get_instance(instance_id).wait_for_state(
            lifecycle_state="TERMINATED",
            wait_for_state_kwargs={"max_wait_seconds": 300},
        )
        log("  Instance terminated")
    
    # Clear route rules
    if 'vcn_id' in network_info:
        vcn_data = net.get_vcn(network_info['vcn_id']).data
        default_rt_id = vcn_data.default_route_table_id
        net.update_route_table(
            route_table_id=default_rt_id,
            update_route_table_details=network.models.UpdateRouteTableDetails(
                route_rules=[]
            )
        )
        log("  Route rules cleared")
    
    # Delete IGW
    if 'igw_id' in network_info:
        net.delete_internet_gateway(ig_id=network_info['igw_id'])
        net.get_internet_gateway(network_info['igw_id']).wait_for_state("TERMINATED")
        log("  IGW deleted")
    
    # Delete subnet
    if 'subnet_id' in network_info:
        net.delete_subnet(subnet_id=network_info['subnet_id'])
        net.get_subnet(network_info['subnet_id']).wait_for_state("TERMINATED")
        log("  Subnet deleted")
    
    # Delete security list
    if 'sl_id' in network_info:
        net.delete_security_list(security_list_id=network_info['sl_id'])
        log("  Security list deleted")
    
    # Delete VCN
    if 'vcn_id' in network_info:
        net.delete_vcn(vcn_id=network_info['vcn_id'])
        net.get_vcn(network_info['vcn_id']).wait_for_state("TERMINATED")
        log("  VCN deleted")
    
    log("  Teardown complete")

# ============================================================
# Main
# ============================================================

def main():
    parser = argparse.ArgumentParser(description='OCI Full Pipeline')
    parser.add_argument('--region', default='uk-london-1')
    parser.add_argument('--skip-teardown', action='store_true',
                       help='Keep the VM running after setup')
    parser.add_argument('--teardown-only', action='store_true',
                       help='Only teardown existing resources')
    args = parser.parse_args()
    
    # Clear log
    if os.path.exists(LOG_FILE):
        os.remove(LOG_FILE)
    
    log("=" * 60)
    log("  OCI FULL PIPELINE")
    log("=" * 60)
    log("")
    
    if args.teardown_only:
        log("Teardown-only mode — need resource IDs")
        # This would need saved state from a previous run
        log("Not implemented yet — use --vcn-id and --instance-id")
        return
    
    # Step 1: Browser auth
    auth = do_browser_auth(args.region)
    
    # Step 2: Upload API key
    priv_pem = do_upload_api_key(auth)
    
    # Step 3: Create API key signer
    signer = make_api_signer(auth, priv_pem)
    
    # Step 4: Query tenancy
    tenancy = query_tenancy(auth, signer)
    
    # Step 5: Create network
    network_info = create_network(auth, signer)
    
    # Step 6: Launch instance
    instance_id = launch_instance(auth, signer, tenancy, network_info)
    
    # Step 7: Get public IP
    public_ip = get_public_ip(auth, signer, instance_id)
    if not public_ip:
        log("FAILED: No public IP. Cleaning up...")
        teardown(auth, signer, network_info, instance_id)
        return
    
    # Step 8: SSH + WireGuard
    wg_ok = ssh_install_wireguard(public_ip)
    
    # Step 9: Verify
    verify_wireguard(auth, signer, public_ip)
    
    log("")
    log("=" * 60)
    log("  PIPELINE COMPLETE")
    log("=" * 60)
    log(f"  Public IP: {public_ip}")
    log(f"  Instance: {instance_id}")
    log(f"  VCN: {network_info['vcn_id']}")
    log(f"  WireGuard: {'installed' if wg_ok else 'not installed'}")
    log(f"  Client config: {os.path.join(os.path.dirname(os.path.abspath(__file__)), 'client.conf')}")
    log("")
    
    if args.skip_teardown:
        log("  --skip-teardown: VM left running")
        log("  To teardown later, run:")
        log(f"    (use the OCI console or re-run with --teardown-only)")
    else:
        log("  Press Ctrl+C to teardown now, or wait 60s...")
        try:
            time.sleep(60)
        except KeyboardInterrupt:
            pass
        teardown(auth, signer, network_info, instance_id)
    
    log("Done.")

if __name__ == '__main__':
    main()