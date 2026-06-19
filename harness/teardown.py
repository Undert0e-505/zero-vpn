#!/usr/bin/env python3
"""Teardown — destroy all ZeroVPN resources in OCI."""
import oci, time
from datetime import datetime

def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)

def main():
    config = oci.config.from_file("D:/dev/zero-vpn/harness/oci-config.txt")
    net = oci.core.VirtualNetworkClient(config)
    compute = oci.core.ComputeClient(config)
    cid = config['tenancy']

    # 1. Terminate all zerovpn instances
    log("Finding instances...")
    for inst in compute.list_instances(compartment_id=cid).data:
        if 'zerovpn' in inst.display_name and inst.lifecycle_state != 'TERMINATED':
            log(f"  Terminating {inst.display_name} ({inst.lifecycle_state})...")
            compute.terminate_instance(inst.id, preserve_boot_volume=False)
            try:
                oci.wait_until(compute, compute.get_instance(inst.id), 'lifecycle_state', 'TERMINATED', max_wait_seconds=120)
            except: pass
            log("  Terminated")
    time.sleep(5)

    # 2. Delete all zerovpn VCNs
    for vcn in net.list_vcns(compartment_id=cid).data:
        if 'zerovpn' not in vcn.display_name and not vcn.display_name.startswith('vcn-2026'):
            continue
        vid = vcn.id
        log(f"Cleaning VCN: {vcn.display_name}")

        # Clear route table
        try:
            rt_id = net.get_vcn(vid).data.default_route_table_id
            net.update_route_table(rt_id, oci.core.models.UpdateRouteTableDetails(route_rules=[]))
        except Exception as e:
            log(f"  Route clear: {e}")

        # Delete IGWs
        for ig in net.list_internet_gateways(compartment_id=cid, vcn_id=vid).data:
            log(f"  Deleting IGW {ig.display_name}")
            try: net.delete_internet_gateway(ig.id)
            except Exception as e: log(f"    {e}")

        # Delete security lists
        for sl in net.list_security_lists(compartment_id=cid, vcn_id=vid).data:
            if sl.display_name != 'Default Security List':
                log(f"  Deleting SL {sl.display_name}")
                try: net.delete_security_list(sl.id)
                except Exception as e: log(f"    {e}")

        # Delete subnets
        for s in net.list_subnets(compartment_id=cid, vcn_id=vid).data:
            log(f"  Deleting subnet {s.display_name}")
            try:
                net.delete_subnet(s.id)
                oci.wait_until(net, net.get_subnet(s.id), 'lifecycle_state', 'TERMINATED', max_wait_seconds=60)
            except Exception as e: log(f"    {e}")

        time.sleep(3)

        # Delete VCN
        log(f"  Deleting VCN")
        try: net.delete_vcn(vid)
        except Exception as e: log(f"    {e}")

    log("Teardown complete")

if __name__ == '__main__':
    main()