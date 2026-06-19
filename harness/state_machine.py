#!/usr/bin/env python3
"""
OCI Setup State Machine - Python reference implementation.

Handles all three user states:
1. No Oracle account -> Oracle handles signup in browser
2. Has account, not logged in -> Oracle handles login + 2FA
3. Already logged in -> auth completes quickly

The app doesn't care which state the user starts in.
It cares only about the current setup stage.

States:
  NotStarted -> OracleIntro -> AwaitingOracleBrowser -> AuthReceived
  -> PreflightRunning -> (PreflightFailed | ReadyToProvision)
  -> Provisioning -> (ExitReady | ProvisionFailed)
  -> Destroying -> (Destroyed | CleanupRequired)

Usage:
    D:\\Python310\\python.exe D:\\dev\\zero-vpn\\harness\\state_machine.py
    D:\\Python310\\python.exe D:\\dev\\zero-vpn\\harness\\state_machine.py --dev
    D:\\Python310\\python.exe D:\\dev\\zero-vpn\\harness\\state_machine.py --resume
    D:\\Python310\\python.exe D:\\dev\\zero-vpn\\harness\\state_machine.py --destroy
"""
import argparse, base64, hashlib, json, os, sys, time, uuid, webbrowser, subprocess
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs, urlencode
from datetime import datetime
from threading import Thread
from enum import Enum

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
SSH_KEY = os.path.join(os.path.dirname(DIR), "secrets", "oci-zerovpn-exit-01")
SSH_PUB = "ssh-ed25519 AAAA_REPLACE_WITH_TEST_PUBLIC_KEY example-only"
STATE_FILE = os.path.join(DIR, "setup-state.json")
SECRETS_DIR = os.path.join(os.path.dirname(DIR), "secrets")


# --- State machine ---

class State(str, Enum):
    NOT_STARTED = "NotStarted"
    ORACLE_INTRO = "OracleIntro"
    AWAITING_BROWSER = "AwaitingOracleBrowser"
    AUTH_RECEIVED = "AuthReceived"
    PREFLIGHT_RUNNING = "PreflightRunning"
    PREFLIGHT_FAILED = "PreflightFailed"
    READY_TO_PROVISION = "ReadyToProvision"
    PROVISIONING = "Provisioning"
    EXIT_READY = "ExitReady"
    PROVISION_FAILED = "ProvisionFailed"
    DESTROYING = "Destroying"
    DESTROYED = "Destroyed"
    CLEANUP_REQUIRED = "CleanupRequired"


class Phase(str, Enum):
    AUTH = "AUTH"
    API_KEY = "API_KEY"
    NETWORK = "NETWORK"
    VM_LAUNCH = "VM_LAUNCH"
    WAIT_SSH = "WAIT_SSH"
    WIREGUARD = "WIREGUARD"
    DONE = "DONE"


class Status(str, Enum):
    RUNNING = "RUNNING"
    SUCCESS = "SUCCESS"
    WARNING = "WARNING"
    ERROR = "ERROR"


class Event:
    def __init__(self, phase, status, message, technical=None):
        self.timestamp = int(time.time() * 1000)
        self.phase = phase
        self.status = status
        self.message = message  # user-safe, no secrets
        self.technical = technical  # redacted, no secrets

    def to_dict(self):
        return {
            "timestamp": self.timestamp,
            "phase": self.phase.value,
            "status": self.status.value,
            "message": self.message,
            "technical": self.technical,
        }

    def __str__(self):
        ts = datetime.fromtimestamp(self.timestamp / 1000).strftime("%H:%M:%S")
        phase_num = {"AUTH": "1/7", "API_KEY": "2/7", "NETWORK": "3/7",
                     "VM_LAUNCH": "4/7", "WAIT_SSH": "5/7", "WIREGUARD": "6/7",
                     "DONE": "7/7"}.get(self.phase.value, "?")
        marker = {"RUNNING": "...", "SUCCESS": "OK ", "WARNING": "WRN", "ERROR": "ERR"}.get(self.status.value, "?")
        return f"[{ts}] [{phase_num}] {self.phase.value:<12} {marker} {self.message}"


def log(msg, event=None):
    if event:
        print(str(event), flush=True)
    else:
        print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


# --- Persisted state ---

def load_state():
    if os.path.exists(STATE_FILE):
        with open(STATE_FILE) as f:
            return json.load(f)
    return {
        "state": State.NOT_STARTED.value,
        "region": "uk-london-1",
        "user_ocid": None,
        "tenancy_ocid": None,
        "fingerprint": None,
        "home_region": None,
        "resource_ids": {},
        "public_ip": None,
        "wireguard_port": 51820,
        "last_successful_phase": None,
        "last_error": None,
        "is_dev_mode": False,
    }

def save_state(state):
    # Strip secrets before persisting
    safe = {k: v for k, v in state.items()
            if k not in ("token", "priv_pem", "priv_key_obj")}
    with open(STATE_FILE, 'w') as f:
        json.dump(safe, f, indent=2)


# --- JWK + fingerprint helpers ---

def bytes_from_int(val):
    remaining, bl = val, 0
    while remaining != 0:
        remaining >>= 8
        bl += 1
    return val.to_bytes(bl, 'big', signed=False)

def b64url_strip(data):
    return base64.urlsafe_b64encode(data).replace(b'=', b'')

def b64url_uint(val):
    b = bytes_from_int(val)
    if not b:
        b = b'\x00'
    return b64url_strip(b)

def build_jwk(public_key):
    n = b64url_uint(public_key.public_numbers().n).decode()
    e = b64url_uint(public_key.public_numbers().e).decode()
    jwk_json = json.dumps({"kty": "RSA", "n": n, "e": e, "kid": "Ignored"})
    jwk_b64 = base64.urlsafe_b64encode(jwk_json.encode()).decode()
    return jwk_json, jwk_b64

def md5_fingerprint(public_key):
    pem = public_key.public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo)
    stripped = pem.replace(b'-----BEGIN PUBLIC KEY-----', b'').replace(b'-----END PUBLIC KEY-----', b'').replace(b'\n', b'')
    der = base64.b64decode(stripped)
    md5_hex = hashlib.md5(der).hexdigest()
    return ':'.join(a + b for a, b in zip(md5_hex[::2], md5_hex[1::2]))


# --- Browser auth ---

class TokenHandler(BaseHTTPRequestHandler):
    token = None
    def log_message(self, fmt, *args):
        pass
    def do_GET(self):
        if self.path == '/' or self.path.startswith('/?'):
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"""<script>h=window.location.hash;if(h[0]==='#')h=h.substr(1);
                var r=new XMLHttpRequest();r.onload=function(){document.write('OK')};
                r.open('GET','/token?'+h);r.send();</script>""")
        elif self.path.startswith('/token?'):
            p = parse_qs(urlparse(self.path).query)
            if 'security_token' in p:
                TokenHandler.token = p['security_token'][0]
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'OK')
        else:
            self.send_response(404)
            self.end_headers()

def do_browser_auth(state):
    """Open browser for Oracle auth. Works for all three user states."""
    priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pub = priv.public_key()
    fp = md5_fingerprint(pub)
    _, jwk_b64 = build_jwk(pub)

    region = state["region"]
    if region in REGIONS_SHORT_NAMES:
        region = REGIONS_SHORT_NAMES[region]
    realm = REALMS[REGION_REALMS[region]]
    url = f"https://login.{region}.{realm}/v1/oauth2/authorize?" + urlencode({
        'action': 'login',
        'client_id': 'iaas_console',
        'response_type': 'token id_token',
        'nonce': str(uuid.uuid4()),
        'scope': 'openid',
        'public_key': jwk_b64,
        'redirect_uri': 'http://localhost:8181',
    })

    log("  Opening browser for Oracle login/signup...")
    log("  If you don't have an Oracle account, sign up there.")
    log("  If you're already logged in, this will be quick.")
    webbrowser.open_new(url)

    server = HTTPServer(('', 8181), TokenHandler)

    def timeout():
        time.sleep(300)
        if TokenHandler.token is None:
            log("  TIMEOUT")
            server.shutdown()
    Thread(target=timeout, daemon=True).start()

    server.handle_request()
    server.handle_request()
    server.server_close()

    token = TokenHandler.token
    if not token:
        return None, None, None, None

    priv_pem = priv.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption())
    priv_obj = load_pem_private_key(priv_pem, password=None)
    stc = oci.auth.security_token_container.SecurityTokenContainer(None, security_token=token)
    jwt = stc.get_jwt()

    return token, priv_pem, priv_obj, {
        "user_ocid": jwt["sub"],
        "tenancy_ocid": jwt["tenant"],
        "fingerprint": fp,
    }


# --- Preflight ---

def do_preflight(state, token, priv_obj):
    """Tenancy/account preflight before provisioning."""
    region = state["region"]
    signer = oci.auth.signers.SecurityTokenSigner(token, priv_obj)
    idc = IdentityClient({'region': region}, signer=signer)

    # 1. Find home region
    home_region = region
    for s in idc.list_region_subscriptions(state["tenancy_ocid"]).data:
        if s.is_home_region:
            home_region = s.region_name
            break
    state["home_region"] = home_region
    log(f"  Home region: {home_region}")

    # 2. UK region check
    is_uk = home_region in ("uk-london-1", "uk-cardiff-1")
    if is_uk and not state.get("is_dev_mode", False):
        return False, (
            f"Your Oracle home region is {home_region} (UK). "
            "This account can be used for development/testing, but not as a non-UK "
            "Always Free exit. Create an Oracle account with a non-UK home region "
            "(e.g., us-ashburn-1, eu-frankfurt-1) for a production exit. "
            "Oracle home region cannot be changed later."
        )

    # 3. Check Always Free shape availability
    compute = ComputeClient({'region': home_region}, signer=signer)
    try:
        ad = idc.list_availability_domains(compartment_id=state["tenancy_ocid"]).data[0].name
        shapes = compute.list_shapes(compartment_id=state["tenancy_ocid"], availability_domain=ad)
        has_micro = any(s.shape == "VM.Standard.E2.1.Micro" for s in shapes.data)
        if not has_micro:
            return False, "VM.Standard.E2.1.Micro shape not available in this region"
    except Exception as e:
        log(f"  Shape check warning: {e}")

    # 4. Check API key count (max 3)
    try:
        keys = idc.list_api_keys(state["user_ocid"]).data
        if len(keys) >= 3:
            return False, (
                f"API key limit reached ({len(keys)}/3). "
                "Delete an existing API key in the Oracle console before continuing."
            )
    except Exception as e:
        log(f"  API key check warning: {e}")

    return True, None


# --- Provisioning ---

def do_upload_api_key(state, token, priv_obj):
    """Upload API key using security token."""
    pub_pem = priv_obj.public_key().public_bytes(
        Encoding.PEM, PublicFormat.SubjectPublicKeyInfo).decode()
    signer = oci.auth.signers.SecurityTokenSigner(token, priv_obj)
    idc = IdentityClient({'region': state["home_region"]}, signer=signer)
    try:
        idc.upload_api_key(state["user_ocid"], id_models.CreateApiKeyDetails(key=pub_pem))
    except oci.exceptions.ServiceError as e:
        if e.status != 409:
            raise
    time.sleep(5)  # propagation


def do_provision(state, token, priv_obj):
    """Full provisioning pipeline. Emits structured events."""
    events = []

    def emit(phase, status, message, technical=None):
        ev = Event(phase, status, message, technical)
        events.append(ev)
        log(str(ev))
        if status == Status.SUCCESS:
            state["last_successful_phase"] = phase.value

    signer = oci.auth.signers.SecurityTokenSigner(token, priv_obj)
    cfg = {'region': state["home_region"], 'tenancy': state["tenancy_ocid"]}
    cid = state["tenancy_ocid"]
    net = VirtualNetworkClient(cfg, signer=signer)
    compute = ComputeClient(cfg, signer=signer)
    idc = IdentityClient(cfg, signer=signer)

    # Phase 3: Network
    emit(Phase.NETWORK, Status.RUNNING, "Creating VCN...")
    vcn = net.create_vcn(core_models.CreateVcnDetails(
        cidr_block="10.0.0.0/24", compartment_id=cid,
        display_name="zerovpn-vcn", dns_label="zerovpn"))
    vcn_id = vcn.data.id
    oci.wait_until(net, net.get_vcn(vcn_id), 'lifecycle_state', 'AVAILABLE')
    state["resource_ids"]["vcn_id"] = vcn_id

    emit(Phase.NETWORK, Status.RUNNING, "Creating security list...")
    sl = net.create_security_list(core_models.CreateSecurityListDetails(
        compartment_id=cid, vcn_id=vcn_id, display_name="zerovpn-sl",
        egress_security_rules=[core_models.EgressSecurityRule(
            destination="0.0.0.0/0", protocol="all", is_stateless=False)],
        ingress_security_rules=[
            core_models.IngressSecurityRule(source="0.0.0.0/0", protocol="6",
                is_stateless=False,
                tcp_options=core_models.TcpOptions(
                    destination_port_range=core_models.PortRange(min=22, max=22))),
            core_models.IngressSecurityRule(source="0.0.0.0/0", protocol="17",
                is_stateless=False,
                udp_options=core_models.UdpOptions(
                    destination_port_range=core_models.PortRange(min=51820, max=51820))),
        ]))
    state["resource_ids"]["sl_id"] = sl.data.id

    emit(Phase.NETWORK, Status.RUNNING, "Creating subnet...")
    dhcp_id = net.list_dhcp_options(compartment_id=cid, vcn_id=vcn_id).data[0].id
    subnet = net.create_subnet(core_models.CreateSubnetDetails(
        cidr_block="10.0.0.0/24", compartment_id=cid, display_name="zerovpn-subnet",
        vcn_id=vcn_id, security_list_ids=[sl.data.id], dhcp_options_id=dhcp_id))
    state["resource_ids"]["subnet_id"] = subnet.data.id
    oci.wait_until(net, net.get_subnet(subnet.data.id), 'lifecycle_state', 'AVAILABLE')

    emit(Phase.NETWORK, Status.RUNNING, "Creating internet gateway...")
    igw = net.create_internet_gateway(core_models.CreateInternetGatewayDetails(
        compartment_id=cid, display_name="zerovpn-igw", is_enabled=True, vcn_id=vcn_id))
    state["resource_ids"]["igw_id"] = igw.data.id
    oci.wait_until(net, net.get_internet_gateway(igw.data.id), 'lifecycle_state', 'AVAILABLE')

    rt_id = net.get_vcn(vcn_id).data.default_route_table_id
    net.update_route_table(rt_id, core_models.UpdateRouteTableDetails(
        route_rules=[core_models.RouteRule(
            destination="0.0.0.0/0", destination_type="CIDR_BLOCK",
            network_entity_id=igw.data.id)]))
    emit(Phase.NETWORK, Status.SUCCESS, "Network ready")

    # Phase 4: VM launch
    emit(Phase.VM_LAUNCH, Status.RUNNING, "Launching instance...")
    ad = idc.list_availability_domains(compartment_id=cid).data[0].name
    imgs = compute.list_images(compartment_id=cid, operating_system="Canonical Ubuntu",
        operating_system_version="22.04", shape="VM.Standard.E2.1.Micro",
        sort_by="TIMECREATED", sort_order="DESC")
    if not imgs.data:
        imgs = compute.list_images(compartment_id=cid, operating_system="Canonical Ubuntu",
            operating_system_version="24.04", shape="VM.Standard.E2.1.Micro",
            sort_by="TIMECREATED", sort_order="DESC")
    image_id = imgs.data[0].id

    inst = compute.launch_instance(core_models.LaunchInstanceDetails(
        availability_domain=ad, compartment_id=cid, display_name="zerovpn-exit-01",
        shape="VM.Standard.E2.1.Micro", subnet_id=subnet.data.id,
        source_details=core_models.InstanceSourceViaImageDetails(
            image_id=image_id, boot_volume_size_in_gbs=50, source_type="image"),
        create_vnic_details=core_models.CreateVnicDetails(
            subnet_id=subnet.data.id, assign_public_ip=True),
        metadata={'ssh_authorized_keys': SSH_PUB}))
    inst_id = inst.data.id
    state["resource_ids"]["instance_id"] = inst_id
    oci.wait_until(compute, compute.get_instance(inst_id), 'lifecycle_state', 'RUNNING',
                   max_wait_seconds=300)
    emit(Phase.VM_LAUNCH, Status.SUCCESS, "Instance running")

    # Get public IP
    time.sleep(5)
    public_ip = None
    for i in range(10):
        atts = compute.list_vnic_attachments(compartment_id=cid, instance_id=inst_id)
        if atts.data:
            vnic = net.get_vnic(atts.data[0].vnic_id).data
            if vnic.public_ip:
                public_ip = vnic.public_ip
                break
        time.sleep(10)
    if not public_ip:
        raise Exception("Failed to get public IP")
    state["public_ip"] = public_ip
    log(f"  Public IP: {public_ip}")

    # Phase 5: SSH
    emit(Phase.WAIT_SSH, Status.RUNNING, "Waiting for SSH...")
    opts = ["-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null",
            "-o", "ConnectTimeout=30", "-i", SSH_KEY, f"ubuntu@{public_ip}"]
    for i in range(12):
        try:
            r = subprocess.run(["ssh"] + opts + ["echo", "ok"],
                               capture_output=True, text=True, timeout=15)
            if r.returncode == 0:
                break
        except Exception:
            pass
        time.sleep(10)
    else:
        raise Exception("SSH not available")
    emit(Phase.WAIT_SSH, Status.SUCCESS, "SSH connected")

    # Phase 6: WireGuard
    emit(Phase.WIREGUARD, Status.RUNNING, "Installing WireGuard...")
    subprocess.run(["ssh"] + opts + [
        "sudo apt-get update -y && sudo apt-get install -y wireguard wireguard-tools"
    ], capture_output=True, text=True, timeout=180)
    subprocess.run(["ssh"] + opts + [
        'sudo sh -c "umask 077; wg genkey > /etc/wireguard/server.key; '
        'wg pubkey < /etc/wireguard/server.key > /etc/wireguard/server.pub"'
    ], capture_output=True, text=True, timeout=30)

    emit(Phase.WIREGUARD, Status.RUNNING, "Configuring WireGuard...")
    setup_script = os.path.join(DIR, "setup-wg.sh")
    subprocess.run(["scp", "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null",
                    "-i", SSH_KEY, setup_script,
                    f"ubuntu@{public_ip}:/tmp/setup-wg.sh"],
                   capture_output=True, text=True, timeout=15)
    r = subprocess.run(["ssh"] + opts + ["bash /tmp/setup-wg.sh"],
                       capture_output=True, text=True, timeout=60)

    peer_key = server_pub = None
    for line in r.stdout.split('\n'):
        if line.startswith('PEER_PRIVATE_KEY='):
            peer_key = line.split('=', 1)[1].strip()
        elif line.startswith('SERVER_PUBLIC_KEY='):
            server_pub = line.split('=', 1)[1].strip()

    if not peer_key or not server_pub:
        raise Exception("WireGuard key extraction failed")

    # Save client config to secrets dir (not persisted in state)
    conf = (f"[Interface]\nPrivateKey = {peer_key}\n"
            f"Address = 10.66.66.2/24\nDNS = 1.1.1.1\n\n"
            f"[Peer]\nPublicKey = {server_pub}\n"
            f"Endpoint = {public_ip}:51820\n"
            f"AllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 25\n")
    os.makedirs(SECRETS_DIR, exist_ok=True)
    with open(os.path.join(SECRETS_DIR, "client.conf"), 'w') as f:
        f.write(conf)
    emit(Phase.WIREGUARD, Status.SUCCESS, "WireGuard configured")

    # Phase 7: Done
    emit(Phase.DONE, Status.SUCCESS,
         f"Exit created: {public_ip}:{state['wireguard_port']}")
    return events


# --- Teardown ---

def do_destroy(state, token=None, priv_obj=None):
    """Destroy all provisioned resources."""
    if token and priv_obj:
        signer = oci.auth.signers.SecurityTokenSigner(token, priv_obj)
        cfg = {'region': state.get("home_region", state["region"]),
               'tenancy': state["tenancy_ocid"]}
        net = VirtualNetworkClient(cfg, signer=signer)
        compute = ComputeClient(cfg, signer=signer)
        cid = state["tenancy_ocid"]
    elif os.path.exists(os.path.join(DIR, "oci-config.txt")):
        config = oci.config.from_file(os.path.join(DIR, "oci-config.txt"))
        net = VirtualNetworkClient(config)
        compute = ComputeClient(config)
        cid = config['tenancy']
    else:
        log("  No auth available for destroy")
        return

    rids = state.get("resource_ids", {})

    # Terminate instance
    if rids.get("instance_id"):
        try:
            compute.terminate_instance(rids["instance_id"], preserve_boot_volume=False)
            oci.wait_until(compute, compute.get_instance(rids["instance_id"]),
                'lifecycle_state', 'TERMINATED', max_wait_seconds=120)
        except Exception:
            pass
    time.sleep(5)

    # Clear routes
    if rids.get("vcn_id"):
        try:
            rt_id = net.get_vcn(rids["vcn_id"]).data.default_route_table_id
            net.update_route_table(rt_id, core_models.UpdateRouteTableDetails(route_rules=[]))
        except Exception:
            pass

    # Delete IGW
    if rids.get("igw_id"):
        try:
            net.delete_internet_gateway(rids["igw_id"])
        except Exception:
            pass

    # Delete subnet
    if rids.get("subnet_id"):
        try:
            net.delete_subnet(rids["subnet_id"])
            oci.wait_until(net, net.get_subnet(rids["subnet_id"]),
                'lifecycle_state', 'TERMINATED', max_wait_seconds=60)
        except Exception:
            pass

    time.sleep(3)

    # Delete security list
    if rids.get("sl_id"):
        try:
            net.delete_security_list(rids["sl_id"])
        except Exception:
            pass

    # Delete VCN
    if rids.get("vcn_id"):
        try:
            net.delete_vcn(rids["vcn_id"])
        except Exception:
            pass

    state["resource_ids"] = {}
    state["public_ip"] = None


# --- Main flow ---

def main():
    parser = argparse.ArgumentParser(description="OCI Setup State Machine")
    parser.add_argument("--region", default="uk-london-1")
    parser.add_argument("--dev", action="store_true", help="Allow UK region in dev mode")
    parser.add_argument("--resume", action="store_true", help="Resume from saved state")
    parser.add_argument("--destroy", action="store_true", help="Destroy existing resources")
    args = parser.parse_args()

    # Load saved state or create fresh
    state = load_state() if args.resume else {
        "state": State.NOT_STARTED.value,
        "region": args.region,
        "user_ocid": None,
        "tenancy_ocid": None,
        "fingerprint": None,
        "home_region": None,
        "resource_ids": {},
        "public_ip": None,
        "wireguard_port": 51820,
        "last_successful_phase": None,
        "last_error": None,
        "is_dev_mode": args.dev,
    }

    # Handle --destroy
    if args.destroy:
        if not state.get("resource_ids"):
            log("No resources to destroy.")
            return
        log("Destroying all resources...")
        do_destroy(state)
        state["state"] = State.DESTROYED.value
        state["resource_ids"] = {}
        state["public_ip"] = None
        save_state(state)
        log("Destroyed.")
        return

    # Check for existing node (resume case)
    if state.get("state") == State.EXIT_READY.value and state.get("resource_ids", {}).get("instance_id"):
        log("An Oracle exit node already exists:")
        log(f"  Public IP: {state.get('public_ip', 'unknown')}")
        log(f"  Region: {state.get('home_region', 'unknown')}")
        log()
        log("  Options:")
        log("    1. Use existing (no action needed)")
        log("    2. Destroy and recreate")
        log("    3. Destroy only")
        choice = input("  Choose [1/2/3]: ").strip()
        if choice == "1":
            log("Using existing node.")
            return
        elif choice == "2":
            log("Destroying existing node...")
            do_destroy(state)
            state["resource_ids"] = {}
            state["public_ip"] = None
            state["state"] = State.NOT_STARTED.value
            save_state(state)
            log("Destroyed. Starting fresh...")
        elif choice == "3":
            log("Destroying...")
            do_destroy(state)
            state["resource_ids"] = {}
            state["public_ip"] = None
            state["state"] = State.DESTROYED.value
            save_state(state)
            log("Destroyed.")
            return

    # Resume at correct state
    current = State(state.get("state", State.NOT_STARTED.value))
    if current in (State.PROVISION_FAILED, State.CLEANUP_REQUIRED):
        log(f"Previous provisioning failed at: {state.get('last_successful_phase', 'unknown')}")
        log(f"Error: {state.get('last_error', 'unknown')}")
        log()
        log("  Options:")
        log("    1. Retry provisioning")
        log("    2. Cleanup partial resources")
        log("    3. Abort")
        choice = input("  Choose [1/2/3]: ").strip()
        if choice == "1":
            log("Retrying provisioning...")
        elif choice == "2":
            log("Cleaning up partial resources...")
            do_destroy(state)
            state["resource_ids"] = {}
            state["state"] = State.DESTROYED.value
            save_state(state)
            log("Cleanup done.")
            return
        else:
            log("Aborted.")
            return
    elif current == State.AWAITING_BROWSER:
        log("Resuming - you left off at Oracle browser auth.")
        log("Press Enter to continue...")
        input()
    elif current == State.READY_TO_PROVISION:
        log("Resuming - preflight passed, ready to provision.")
    elif current == State.PROVISIONING:
        log("Resuming - was mid-provisioning. Checking state...")
        if state.get("resource_ids", {}).get("instance_id"):
            log("  Instance exists. Offering cleanup/retry.")
            log("  1. Cleanup and retry")
            log("  2. Abort")
            choice = input("  Choose [1/2]: ").strip()
            if choice == "1":
                do_destroy(state)
                state["resource_ids"] = {}
                save_state(state)
            else:
                return

    # -- OracleIntro --
    log("=" * 60)
    log("  ZeroVPN Oracle Setup")
    log("=" * 60)
    log()
    log("  IMPORTANT before continuing:")
    log("  - Oracle signup or login may be required")
    log("  - Oracle may require card and 2FA")
    log("  - ZeroVPN never sees your Oracle password, card, 2FA, or cookies")
    log(f"  - Selected region: {state['region']}")
    if state["region"] in ("uk-london-1", "uk-cardiff-1"):
        if not args.dev and not state.get("is_dev_mode", False):
            log("  WARNING: UK region selected. Fine for testing, NOT for a non-UK exit.")
            log("     Use --dev to override, or choose a non-UK region with --region.")
            sys.exit(1)
        log("  WARNING: UK region (dev mode) - OK for testing, not for production exit")
    log("  - Home region cannot be changed later. Choose carefully during Oracle signup.")
    log("  - Setup takes 4-6 minutes once provisioning begins")
    log()
    log("  Press Enter to continue (opens browser for Oracle login/signup)...")
    input()

    state["state"] = State.AWAITING_BROWSER.value
    save_state(state)

    # -- AwaitingOracleBrowser -> AuthReceived --
    log()
    log("[1/7] Browser auth")
    token, priv_pem, priv_obj, auth_info = do_browser_auth(state)

    if not token:
        log("  Auth not completed. You can resume later with --resume")
        state["state"] = State.ORACLE_INTRO.value
        state["last_error"] = "Browser auth not completed"
        save_state(state)
        sys.exit(1)

    state.update(auth_info)
    state["state"] = State.AUTH_RECEIVED.value
    log(f"  User: {auth_info['user_ocid']}")
    log(f"  Tenancy: {auth_info['tenancy_ocid']}")
    log(f"  Fingerprint: {auth_info['fingerprint']}")
    log("  Auth OK")
    save_state(state)

    # -- Preflight --
    log()
    log("[2/7] Tenancy preflight")
    state["state"] = State.PREFLIGHT_RUNNING.value
    save_state(state)

    ok, error = do_preflight(state, token, priv_obj)
    if not ok:
        state["state"] = State.PREFLIGHT_FAILED.value
        state["last_error"] = error
        save_state(state)
        log(f"  PREFLIGHT FAILED: {error}")
        sys.exit(1)

    log("  Preflight passed")
    state["state"] = State.READY_TO_PROVISION.value
    save_state(state)

    # -- Provisioning --
    log()
    log("[3/7] Uploading API key...")
    do_upload_api_key(state, token, priv_obj)
    log("  API key uploaded")

    log()
    log("Starting provisioning pipeline...")
    state["state"] = State.PROVISIONING.value
    save_state(state)

    try:
        events = do_provision(state, token, priv_obj)
        state["state"] = State.EXIT_READY.value
        save_state(state)
        log()
        log("=" * 60)
        log("  EXIT READY!")
        log("=" * 60)
        log(f"  Public IP: {state['public_ip']}")
        log(f"  WireGuard: {state['wireguard_port']}/udp")
        log(f"  Region: {state['home_region']}")
        log("=" * 60)
    except Exception as e:
        state["state"] = State.PROVISION_FAILED.value
        state["last_error"] = str(e)
        save_state(state)
        log(f"  PROVISIONING FAILED: {e}")
        log(f"  Last successful phase: {state.get('last_successful_phase')}")
        log("  Run with --destroy to clean up partial resources")
        sys.exit(1)


if __name__ == '__main__':
    main()