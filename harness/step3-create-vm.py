#!/usr/bin/env python3
"""Step 3: Create network + launch VM."""
import json, os, time, tempfile
from datetime import datetime
import oci
from oci.core import VirtualNetworkClient, ComputeClient
from oci.core import models as models

DIR = os.path.dirname(os.path.abspath(__file__))

def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)

def main():
    config = oci.config.from_file(os.path.join(DIR, "oci-config.txt"))
    with open(os.path.join(DIR, "auth-state.json")) as f:
        state = json.load(f)
    
    compartment_id = config['tenancy']
    ad_name = state['ad_name']
    image_id = state['image_id']
    
    SSH_PUB_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAzBCGOBQ2w1+NG+QUGOpR7zggzhXEp5iaOx/ymYMZWz zerovpn-exit-01"
    
    net = VirtualNetworkClient(config)
    compute = ComputeClient(config)
    
    # === VCN ===
    log("Creating VCN...")
    vcn = net.create_vcn(models.CreateVcnDetails(
        cidr_block="10.0.0.0/24", compartment_id=compartment_id,
        display_name="zerovpn-vcn", dns_label="zerovpn"
    ))
    vcn_id = vcn.data.id
    log(f"  VCN: {vcn_id}")
    oci.wait_until(net, net.get_vcn(vcn_id), 'lifecycle_state', 'AVAILABLE')
    
    # === Security list ===
    log("Creating security list...")
    sl = net.create_security_list(models.CreateSecurityListDetails(
        compartment_id=compartment_id, vcn_id=vcn_id,
        display_name="zerovpn-sl",
        egress_security_rules=[models.EgressSecurityRule(destination="0.0.0.0/0", protocol="all", is_stateless=False)],
        ingress_security_rules=[
            models.IngressSecurityRule(source="0.0.0.0/0", protocol="6", is_stateless=False,
                tcp_options=models.TcpOptions(destination_port_range=models.PortRange(min=22, max=22))),
            models.IngressSecurityRule(source="0.0.0.0/0", protocol="17", is_stateless=False,
                udp_options=models.UdpOptions(destination_port_range=models.PortRange(min=51820, max=51820))),
        ],
    ))
    sl_id = sl.data.id
    log(f"  Security list: {sl_id}")
    
    # === Subnet ===
    log("Creating subnet...")
    dhcp_id = net.list_dhcp_options(compartment_id=compartment_id, vcn_id=vcn_id).data[0].id
    subnet = net.create_subnet(models.CreateSubnetDetails(
        cidr_block="10.0.0.0/24", compartment_id=compartment_id,
        display_name="zerovpn-subnet", vcn_id=vcn_id,
        security_list_ids=[sl_id], dhcp_options_id=dhcp_id,
    ))
    subnet_id = subnet.data.id
    log(f"  Subnet: {subnet_id}")
    oci.wait_until(net, net.get_subnet(subnet_id), 'lifecycle_state', 'AVAILABLE')
    
    # === Internet gateway ===
    log("Creating internet gateway...")
    igw = net.create_internet_gateway(models.CreateInternetGatewayDetails(
        compartment_id=compartment_id, display_name="zerovpn-igw",
        is_enabled=True, vcn_id=vcn_id,
    ))
    igw_id = igw.data.id
    log(f"  IGW: {igw_id}")
    oci.wait_until(net, net.get_internet_gateway(igw_id), 'lifecycle_state', 'AVAILABLE')
    
    # === Route table ===
    log("Updating route table...")
    default_rt_id = net.get_vcn(vcn_id).data.default_route_table_id
    net.update_route_table(default_rt_id, models.UpdateRouteTableDetails(
        route_rules=[models.RouteRule(destination="0.0.0.0/0", destination_type="CIDR_BLOCK", network_entity_id=igw_id)]
    ))
    log("  Route table updated")
    
    # === Launch instance ===
    log("Launching instance...")
    with tempfile.NamedTemporaryFile(mode='w', suffix='.pub', delete=False) as f:
        f.write(SSH_PUB_KEY + '\n')
        key_file = f.name
    try:
        instance = compute.launch_instance(models.LaunchInstanceDetails(
            availability_domain=ad_name,
            compartment_id=compartment_id,
            display_name="zerovpn-exit-01",
            shape="VM.Standard.E2.1.Micro",
            subnet_id=subnet_id,
            source_details=models.InstanceSourceViaImageDetails(
                image_id=image_id,
                boot_volume_size_in_gbs=50,
                source_type="image",
            ),
            create_vnic_details=models.CreateVnicDetails(
                subnet_id=subnet_id,
                assign_public_ip=True,
            ),
            metadata={'ssh_authorized_keys': SSH_PUB_KEY},
        ))
    finally:
        os.unlink(key_file)
    
    instance_id = instance.data.id
    log(f"  Instance: {instance_id}")
    log("  Waiting for RUNNING...")
    oci.wait_until(compute, compute.get_instance(instance_id), 'lifecycle_state', 'RUNNING', max_wait_seconds=300)
    log("  Instance is RUNNING")
    
    # === Get public IP ===
    log("Getting public IP...")
    time.sleep(5)
    public_ip = None
    for i in range(10):
        vnic_attachments = compute.list_vnic_attachments(compartment_id=compartment_id, instance_id=instance_id)
        if vnic_attachments.data:
            vnic_id = vnic_attachments.data[0].vnic_id
            vnic = net.get_vnic(vnic_id).data
            if vnic.public_ip:
                public_ip = vnic.public_ip
                break
        log(f"  Waiting... (attempt {i+1})")
        time.sleep(10)
    
    if public_ip:
        log(f"  Public IP: {public_ip}")
    else:
        log("  FAILED: No public IP")
    
    # === Save state ===
    state['vcn_id'] = vcn_id
    state['subnet_id'] = subnet_id
    state['sl_id'] = sl_id
    state['igw_id'] = igw_id
    state['instance_id'] = instance_id
    state['public_ip'] = public_ip
    with open(os.path.join(DIR, "auth-state.json"), 'w') as f:
        json.dump(state, f, indent=2)
    
    log("")
    log("=== Step 3 complete ===")
    log(f"  VCN: {vcn_id}")
    log(f"  Subnet: {subnet_id}")
    log(f"  Instance: {instance_id}")
    log(f"  Public IP: {public_ip}")

if __name__ == '__main__':
    main()