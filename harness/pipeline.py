#!/usr/bin/env python3
"""
OCI ZeroVPN Pipeline — one script, auth to WireGuard.

Steps:
1. Browser auth (generate keypair, Oracle OAuth2, capture token)
2. Upload API key (MD5 fingerprint, permanent signing)
3. Create network (VCN, subnet, IGW, route table, security list)
4. Launch VM (Ubuntu 22.04 micro, public IP, SSH key)
5. SSH + WireGuard (install, configure, generate peer config)
6. Print client config

Usage:
    D:\\Python310\\python.exe D:\\dev\\zero-vpn\\harness\\pipeline.py --region uk-london-1
"""
import argparse, base64, hashlib, json, os, sys, tempfile, time, uuid, webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs, urlencode
from datetime import datetime
from threading import Thread

from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import (
    Encoding, PrivateFormat, PublicFormat, NoEncryption, load_pem_private_key
)

import oci
from oci.regions import REGIONS, REALMS, REGION_REALMS, REGIONS_SHORT_NAMES
from oci.identity import IdentityClient, models as id_models
from oci.core import ComputeClient, VirtualNetworkClient
from oci.core import models as core_models

DIR = os.path.dirname(os.path.abspath(__file__))
SSH_KEY = os.path.join(DIR, "..", "secrets", "oci-zerovpn-exit-01")
SSH_PUB = "ssh-ed25519 AAAA_REPLACE_WITH_TEST_PUBLIC_KEY example-only"

def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)

# ── JWK helpers (match CLI exactly) ──────────────────────────

def bytes_from_int(val):
    remaining, bl = val, 0
    while remaining != 0: remaining >>= 8; bl += 1
    return val.to_bytes(bl, 'big', signed=False)

def b64url_strip(data):
    return base64.urlsafe_b64encode(data).replace(b'=', b'')

def b64url_uint(val):
    b = bytes_from_int(val)
    if not b: b = b'\x00'
    return b64url_strip(b)

def build_jwk(public_key):
    """Build JWK exactly as OCI CLI does (cli_util.to_jwk)."""
    n = b64url_uint(public_key.public_numbers().n).decode()
    e = b64url_uint(public_key.public_numbers().e).decode()
    jwk_json = json.dumps({"kty":"RSA","n":n,"e":e,"kid":"Ignored"})
    jwk_b64 = base64.urlsafe_b64encode(jwk_json.encode()).decode()
    return jwk_json, jwk_b64

def md5_fingerprint(public_key):
    """OCI API key fingerprint = MD5 of DER public key (16 bytes, colon-separated)."""
    pem = public_key.public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo)
    stripped = pem.replace(b'-----BEGIN PUBLIC KEY-----', b'').replace(b'-----END PUBLIC KEY-----', b'').replace(b'\n', b'')
    der = base64.b64decode(stripped)
    md5_hex = hashlib.md5(der).hexdigest()
    return ':'.join(a+b for a,b in zip(md5_hex[::2], md5_hex[1::2]))

# ── Browser auth ─────────────────────────────────────────────

class TokenHandler(BaseHTTPRequestHandler):
    token = None
    def log_message(self, fmt, *args): pass
    def do_GET(self):
        if self.path == '/' or self.path.startswith('/?'):
            self.send_response(200); self.end_headers()
            self.wfile.write(b"""<script>h=window.location.hash;if(h[0]==='#')h=h.substr(1);
                var r=new XMLHttpRequest();r.onload=function(){document.write('OK')};
                r.open('GET','/token?'+h);r.send();</script>""")
        elif self.path.startswith('/token?'):
            p = parse_qs(urlparse(self.path).query)
            if 'security_token' in p:
                TokenHandler.token = p['security_token'][0]
                log("  Token captured!")
            self.send_response(200); self.end_headers()
            self.wfile.write(b'OK')
        else:
            self.send_response(404); self.end_headers()

def browser_auth(region):
    log("[1/6] Browser auth")
    priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pub = priv.public_key()
    fp = md5_fingerprint(pub)
    _, jwk_b64 = build_jwk(pub)

    if region in REGIONS_SHORT_NAMES: region = REGIONS_SHORT_NAMES[region]
    realm = REALMS[REGION_REALMS[region]]
    url = f"https://login.{region}.{realm}/v1/oauth2/authorize?" + urlencode({
        'action':'login','client_id':'iaas_console','response_type':'token id_token',
        'nonce':str(uuid.uuid4()),'scope':'openid','public_key':jwk_b64,
        'redirect_uri':'http://localhost:8181',
    })
    log(f"  Opening browser...")
    webbrowser.open_new(url)

    server = HTTPServer(('', 8181), TokenHandler)
    def timeout():
        time.sleep(300)
        if TokenHandler.token is None: log("  TIMEOUT"); server.shutdown()
    Thread(target=timeout, daemon=True).start()

    server.handle_request()
    server.handle_request()
    server.server_close()

    token = TokenHandler.token
    if not token: log("  FAILED"); sys.exit(1)

    stc = oci.auth.security_token_container.SecurityTokenContainer(None, security_token=token)
    jwt = stc.get_jwt()
    log(f"  User: {jwt['sub']}")
    log(f"  Tenancy: {jwt['tenant']}")
    log("  OK")
    return {'token':token, 'priv':priv, 'pub':pub, 'fp':fp, 'user':jwt['sub'], 'tenancy':jwt['tenant'], 'region':region}

# ── Upload API key ───────────────────────────────────────────

def upload_api_key(auth):
    log("[2/6] Upload API key")
    priv_pem = auth['priv'].private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption())
    priv_obj = load_pem_private_key(priv_pem, password=None)
    signer = oci.auth.signers.SecurityTokenSigner(auth['token'], priv_obj)
    idc = IdentityClient({'region': auth['region']}, signer=signer)

    # Home region
    home = auth['region']
    for s in idc.list_region_subscriptions(auth['tenancy']).data:
        if s.is_home_region: home = s.region_name; break
    auth['home'] = home
    log(f"  Home region: {home}")

    # Upload
    idc = IdentityClient({'region': home}, signer=signer)
    pub_pem = auth['pub'].public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo).decode()
    try:
        idc.upload_api_key(auth['user'], id_models.CreateApiKeyDetails(key=pub_pem))
    except oci.exceptions.ServiceError as e:
        if e.status != 409: raise
        log("  Key already exists")
    log(f"  Fingerprint: {auth['fp']}")

    # Write config file for API key auth
    cfg_path = os.path.join(DIR, "oci-config.txt")
    with open(cfg_path, 'w') as f:
        f.write(f"[DEFAULT]\nuser={auth['user']}\ntenancy={auth['tenancy']}\n")
        f.write(f"fingerprint={auth['fp']}\n")
        f.write(f"key_file={os.path.join(DIR, 'oci-key.pem').replace(chr(92), '/')}\n")
        f.write(f"region={home}\n")
    with open(os.path.join(DIR, "oci-key.pem"), 'wb') as f:
        f.write(priv_pem)

    config = oci.config.from_file(cfg_path)
    auth['config'] = config
    log("  OK")

# ── Create network + VM ──────────────────────────────────────

def create_vm(auth):
    log("[3/6] Network + VM")
    config = auth['config']
    cid = config['tenancy']
    home = auth['home']
    net = VirtualNetworkClient(config)
    compute = ComputeClient(config)

    # AD
    ad = IdentityClient(config).list_availability_domains(compartment_id=cid).data[0].name

    # Image
    imgs = compute.list_images(compartment_id=cid, operating_system="Canonical Ubuntu",
        operating_system_version="22.04", shape="VM.Standard.E2.1.Micro",
        sort_by="TIMECREATED", sort_order="DESC")
    if not imgs.data:
        imgs = compute.list_images(compartment_id=cid, operating_system="Canonical Ubuntu",
            operating_system_version="24.04", shape="VM.Standard.E2.1.Micro",
            sort_by="TIMECREATED", sort_order="DESC")
    image_id = imgs.data[0].id

    # VCN
    vcn = net.create_vcn(core_models.CreateVcnDetails(cidr_block="10.0.0.0/24",
        compartment_id=cid, display_name="zerovpn-vcn", dns_label="zerovpn"))
    vcn_id = vcn.data.id
    oci.wait_until(net, net.get_vcn(vcn_id), 'lifecycle_state', 'AVAILABLE')

    # Security list
    sl = net.create_security_list(core_models.CreateSecurityListDetails(
        compartment_id=cid, vcn_id=vcn_id, display_name="zerovpn-sl",
        egress_security_rules=[core_models.EgressSecurityRule(destination="0.0.0.0/0", protocol="all", is_stateless=False)],
        ingress_security_rules=[
            core_models.IngressSecurityRule(source="0.0.0.0/0", protocol="6", is_stateless=False,
                tcp_options=core_models.TcpOptions(destination_port_range=core_models.PortRange(min=22, max=22))),
            core_models.IngressSecurityRule(source="0.0.0.0/0", protocol="17", is_stateless=False,
                udp_options=core_models.UdpOptions(destination_port_range=core_models.PortRange(min=51820, max=51820))),
        ]))
    sl_id = sl.data.id

    # Subnet
    dhcp_id = net.list_dhcp_options(compartment_id=cid, vcn_id=vcn_id).data[0].id
    subnet = net.create_subnet(core_models.CreateSubnetDetails(
        cidr_block="10.0.0.0/24", compartment_id=cid, display_name="zerovpn-subnet",
        vcn_id=vcn_id, security_list_ids=[sl_id], dhcp_options_id=dhcp_id))
    subnet_id = subnet.data.id
    oci.wait_until(net, net.get_subnet(subnet_id), 'lifecycle_state', 'AVAILABLE')

    # IGW
    igw = net.create_internet_gateway(core_models.CreateInternetGatewayDetails(
        compartment_id=cid, display_name="zerovpn-igw", is_enabled=True, vcn_id=vcn_id))
    igw_id = igw.data.id
    oci.wait_until(net, net.get_internet_gateway(igw_id), 'lifecycle_state', 'AVAILABLE')

    # Route
    rt_id = net.get_vcn(vcn_id).data.default_route_table_id
    net.update_route_table(rt_id, core_models.UpdateRouteTableDetails(
        route_rules=[core_models.RouteRule(destination="0.0.0.0/0", destination_type="CIDR_BLOCK", network_entity_id=igw_id)]))

    log("  Network ready")

    # Launch
    log("[4/6] Launching VM...")
    inst = compute.launch_instance(core_models.LaunchInstanceDetails(
        availability_domain=ad, compartment_id=cid, display_name="zerovpn-exit-01",
        shape="VM.Standard.E2.1.Micro", subnet_id=subnet_id,
        source_details=core_models.InstanceSourceViaImageDetails(image_id=image_id, boot_volume_size_in_gbs=50, source_type="image"),
        create_vnic_details=core_models.CreateVnicDetails(subnet_id=subnet_id, assign_public_ip=True),
        metadata={'ssh_authorized_keys': SSH_PUB},
    ))
    inst_id = inst.data.id
    log(f"  Instance: {inst_id}")
    oci.wait_until(compute, compute.get_instance(inst_id), 'lifecycle_state', 'RUNNING', max_wait_seconds=300)
    log("  RUNNING")

    # Public IP
    log("[5/6] Getting public IP...")
    time.sleep(5)
    public_ip = None
    for i in range(10):
        atts = compute.list_vnic_attachments(compartment_id=cid, instance_id=inst_id)
        if atts.data:
            vnic = net.get_vnic(atts.data[0].vnic_id).data
            if vnic.public_ip: public_ip = vnic.public_ip; break
        time.sleep(10)
    if not public_ip: log("  FAILED: no IP"); sys.exit(1)
    log(f"  Public IP: {public_ip}")

    auth.update({'vcn_id':vcn_id, 'subnet_id':subnet_id, 'sl_id':sl_id, 'igw_id':igw_id,
                 'inst_id':inst_id, 'public_ip':public_ip})

# ── WireGuard ────────────────────────────────────────────────

def setup_wireguard(auth):
    log("[6/6] SSH + WireGuard")
    import subprocess
    opts = ["-o","StrictHostKeyChecking=no","-o","UserKnownHostsFile=/dev/null",
            "-o","ConnectTimeout=30","-i",SSH_KEY,f"ubuntu@{auth['public_ip']}"]

    # Wait for SSH
    for i in range(12):
        try:
            r = subprocess.run(["ssh"]+opts+["echo","ok"], capture_output=True, text=True, timeout=15)
            if r.returncode == 0: break
        except: pass
        time.sleep(10)

    # Install
    subprocess.run(["ssh"]+opts+[
        "sudo apt-get update -y && sudo apt-get install -y wireguard wireguard-tools && "
        "sudo sh -c 'umask 077; wg genkey > /etc/wireguard/server.key; wg pubkey < /etc/wireguard/server.key > /etc/wireguard/server.pub'"
    ], capture_output=True, text=True, timeout=120)

    # Configure
    setup = """bash -c '
SERVER_KEY=*** cat /etc/wireguard/server.key)
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
echo net.ipv4.ip_forward=1 | sudo tee -a /etc/sysctl.conf
sudo systemctl enable wg-quick@wg0
sudo systemctl start wg-quick@wg0
wg genkey | tee /tmp/peer.key | wg pubkey > /tmp/peer.pub
PEER_PUB=*** /tmp/peer.pub)
sudo wg set wg0 peer "$PEER_PUB" allowed-ips 10.66.66.2/32
SERVER_PUB=$(sudo cat /etc/wireguard/server.pub)
PEER_KEY=$(cat /tmp/peer.key)
echo "PEER_KEY=$PEER_KEY"
echo "SERVER_PUB=$SERVER_PUB"
'
""".strip()
    r = subprocess.run(["ssh"]+opts+[setup], capture_output=True, text=True, timeout=60)
    peer_key = server_pub = None
    for line in r.stdout.split('\n'):
        if line.startswith('PEER_KEY='): peer_key = line.split('=',1)[1].strip()
        elif line.startswith('SERVER_PUB='): server_pub = line.split('=',1)[1].strip()

    if not peer_key or not server_pub:
        log("  FAILED: no keys"); log(f"  stdout: {r.stdout[-200:]}"); log(f"  stderr: {r.stderr[-200:]}"); return

    conf = f"""[Interface]
PrivateKey = {peer_key}
Address = 10.66.66.2/24
DNS = 1.1.1.1

[Peer]
PublicKey = {server_pub}
Endpoint = {auth['public_ip']}:51820
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25"""
    with open(os.path.join(DIR, "client.conf"), 'w') as f:
        f.write(conf)
    log("  WireGuard configured!")
    log(f"  Client config: {os.path.join(DIR, 'client.conf')}")

# ── Main ──────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--region', default='uk-london-1')
    args = parser.parse_args()

    log("=" * 50)
    log("  ZeroVPN OCI Pipeline")
    log("=" * 50)

    auth = browser_auth(args.region)
    upload_api_key(auth)
    create_vm(auth)
    setup_wireguard(auth)

    log("")
    log("=" * 50)
    log("  DONE!")
    log("=" * 50)
    log(f"  Public IP: {auth['public_ip']}")
    log(f"  WireGuard: 51820/udp")
    log(f"  Client config: {os.path.join(DIR, 'client.conf')}")
    log("=" * 50)

if __name__ == '__main__':
    main()