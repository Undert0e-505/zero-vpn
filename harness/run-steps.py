#!/usr/bin/env python3
"""Run steps 3-6 using existing API key auth."""
import oci, json, os, time, subprocess
from datetime import datetime

DIR = "D:/dev/zero-vpn/harness"
SSH_KEY = "D:/dev/zero-vpn/secrets/oci-zerovpn-exit-01"
SSH_PUB = "ssh-ed25519 AAAA_REPLACE_WITH_TEST_PUBLIC_KEY example-only"

def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)

config = oci.config.from_file(os.path.join(DIR, "oci-config.txt"))
cid = config['tenancy']
net = oci.core.VirtualNetworkClient(config)
compute = oci.core.ComputeClient(config)

# AD + Image
ad = oci.identity.IdentityClient(config).list_availability_domains(compartment_id=cid).data[0].name
imgs = compute.list_images(compartment_id=cid, operating_system="Canonical Ubuntu",
    operating_system_version="22.04", shape="VM.Standard.E2.1.Micro",
    sort_by="TIMECREATED", sort_order="DESC")
image_id = imgs.data[0].id
log(f"AD: {ad}")
log(f"Image: {image_id}")

# VCN
log("Creating VCN...")
vcn = net.create_vcn(oci.core.models.CreateVcnDetails(cidr_block="10.0.0.0/24", compartment_id=cid, display_name="zerovpn-vcn", dns_label="zerovpn"))
vcn_id = vcn.data.id
oci.wait_until(net, net.get_vcn(vcn_id), 'lifecycle_state', 'AVAILABLE')

# Security list
log("Creating security list...")
sl = net.create_security_list(oci.core.models.CreateSecurityListDetails(
    compartment_id=cid, vcn_id=vcn_id, display_name="zerovpn-sl",
    egress_security_rules=[oci.core.models.EgressSecurityRule(destination="0.0.0.0/0", protocol="all", is_stateless=False)],
    ingress_security_rules=[
        oci.core.models.IngressSecurityRule(source="0.0.0.0/0", protocol="6", is_stateless=False,
            tcp_options=oci.core.models.TcpOptions(destination_port_range=oci.core.models.PortRange(min=22, max=22))),
        oci.core.models.IngressSecurityRule(source="0.0.0.0/0", protocol="17", is_stateless=False,
            udp_options=oci.core.models.UdpOptions(destination_port_range=oci.core.models.PortRange(min=51820, max=51820))),
    ]))
sl_id = sl.data.id

# Subnet
log("Creating subnet...")
dhcp_id = net.list_dhcp_options(compartment_id=cid, vcn_id=vcn_id).data[0].id
subnet = net.create_subnet(oci.core.models.CreateSubnetDetails(
    cidr_block="10.0.0.0/24", compartment_id=cid, display_name="zerovpn-subnet",
    vcn_id=vcn_id, security_list_ids=[sl_id], dhcp_options_id=dhcp_id))
subnet_id = subnet.data.id
oci.wait_until(net, net.get_subnet(subnet_id), 'lifecycle_state', 'AVAILABLE')

# IGW
log("Creating IGW...")
igw = net.create_internet_gateway(oci.core.models.CreateInternetGatewayDetails(
    compartment_id=cid, display_name="zerovpn-igw", is_enabled=True, vcn_id=vcn_id))
igw_id = igw.data.id
oci.wait_until(net, net.get_internet_gateway(igw_id), 'lifecycle_state', 'AVAILABLE')

# Route
rt_id = net.get_vcn(vcn_id).data.default_route_table_id
net.update_route_table(rt_id, oci.core.models.UpdateRouteTableDetails(
    route_rules=[oci.core.models.RouteRule(destination="0.0.0.0/0", destination_type="CIDR_BLOCK", network_entity_id=igw_id)]))
log("Network ready")

# Launch
log("Launching instance...")
inst = compute.launch_instance(oci.core.models.LaunchInstanceDetails(
    availability_domain=ad, compartment_id=cid, display_name="zerovpn-exit-01",
    shape="VM.Standard.E2.1.Micro", subnet_id=subnet_id,
    source_details=oci.core.models.InstanceSourceViaImageDetails(image_id=image_id, boot_volume_size_in_gbs=50, source_type="image"),
    create_vnic_details=oci.core.models.CreateVnicDetails(subnet_id=subnet_id, assign_public_ip=True),
    metadata={'ssh_authorized_keys': SSH_PUB}))
inst_id = inst.data.id
log(f"Instance: {inst_id}")
log("Waiting for RUNNING...")
oci.wait_until(compute, compute.get_instance(inst_id), 'lifecycle_state', 'RUNNING', max_wait_seconds=300)
log("RUNNING")

# Public IP
log("Getting public IP...")
time.sleep(5)
public_ip = None
for i in range(10):
    atts = compute.list_vnic_attachments(compartment_id=cid, instance_id=inst_id)
    if atts.data:
        vnic = net.get_vnic(atts.data[0].vnic_id).data
        if vnic.public_ip: public_ip = vnic.public_ip; break
    time.sleep(10)

log(f"Public IP: {public_ip}")
if not public_ip:
    log("FAILED"); exit(1)

# Save state
with open(os.path.join(DIR, "auth-state.json"), 'w') as f:
    json.dump({'vcn_id':vcn_id,'subnet_id':subnet_id,'sl_id':sl_id,'igw_id':igw_id,'inst_id':inst_id,'public_ip':public_ip}, f, indent=2)

# SSH + WireGuard
log("Waiting for SSH...")
opts = ["-o","StrictHostKeyChecking=no","-o","UserKnownHostsFile=/dev/null","-o","ConnectTimeout=30","-i",SSH_KEY,f"ubuntu@{public_ip}"]
for i in range(12):
    r = subprocess.run(["ssh"]+opts+["echo","ok"], capture_output=True, text=True, timeout=15)
    if r.returncode == 0: break
    time.sleep(10)
log("SSH connected!")

log("Installing WireGuard...")
subprocess.run(["ssh"]+opts+[
    "sudo apt-get update -y && sudo apt-get install -y wireguard wireguard-tools && "
    "sudo sh -c 'umask 077; wg genkey > /etc/wireguard/server.key; wg pubkey < /etc/wireguard/server.key > /etc/wireguard/server.pub'"
], capture_output=True, text=True, timeout=120)

log("Configuring WireGuard...")
with open(os.path.join(DIR, "setup-wg.sh")) as f:
    script = f.read()
subprocess.run(["scp"]+opts[:-1]+[os.path.join(DIR, "setup-wg.sh"), f"ubuntu@{public_ip}:/tmp/setup-wg.sh"],
    capture_output=True, text=True, timeout=15)
r = subprocess.run(["ssh"]+opts+["bash /tmp/setup-wg.sh"], capture_output=True, text=True, timeout=60)

peer_key = server_pub = None
for line in r.stdout.split('\n'):
    if line.startswith('PEER_PRIVATE_KEY='): peer_key = line.split('=',1)[1].strip()
    elif line.startswith('SERVER_PUBLIC_KEY='): server_pub = line.split('=',1)[1].strip()

if peer_key and server_pub:
    conf = f"""[Interface]
PrivateKey = {peer_key}
Address = 10.66.66.2/24
DNS = 1.1.1.1

[Peer]
PublicKey = {server_pub}
Endpoint = {public_ip}:51820
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25"""
    with open(os.path.join(DIR, "client.conf"), 'w') as f:
        f.write(conf)
    log("WireGuard configured!")
    log(f"Client config: {os.path.join(DIR, 'client.conf')}")
else:
    log(f"Failed: stdout={r.stdout[-200:]} stderr={r.stderr[-200:]}")

log("")
log("=" * 50)
log(f"DONE! VM: {public_ip}")
log("=" * 50)