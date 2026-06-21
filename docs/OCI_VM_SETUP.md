# OCI VM Setup and WireGuard Debugging Guide

> **Reference guide.** Manual console setup is no longer the recommended
> user path. Android in-app provisioning and the Cloud Shell bootstrap
> flow are the preferred paths. This document is kept as operational
> memory for OCI VM behavior, Android-created nodes, and WireGuard
> debugging.

Cloud Shell bootstrap:

```bash
bash <(curl -sL https://raw.githubusercontent.com/Undert0e-505/zero-vpn/main/cloud-shell/oci-bootstrap.sh)
```

## Current Android-Provisioned Architecture

- The Android app provisions a free-tier Oracle VM and configures it as
  a WireGuard exit node.
- The app generates per-node SSH material for the VM and injects the
  generated public key into OCI instance metadata.
- The app generates WireGuard server/client key material for each node.
- The server runs WireGuard on `wg0`, UDP `51820`.
- Android uses WireGuard `GoBackend` through Android `VpnService`.
- Server `wg0` address: `10.66.66.1/24`.
- Android client address: `10.66.66.2/32`.
- Android full-tunnel `AllowedIPs`: `0.0.0.0/0`.
- Server peer `AllowedIPs`: `10.66.66.2/32`.
- DNS currently uses `1.1.1.1`.
- IPv4 only for now. Do not add `::/0` unless IPv6 is deliberately
  implemented end-to-end.

## Important SSH Note

The Android provisioning path generates a fresh SSH keypair for each
provisioned node and injects that generated public key into OCI instance
metadata.

The repo key `secrets/oci-zerovpn-exit-01` may be valid for harness or
manual test paths, but it is not necessarily the key for Android-created
VMs. Do not assume the repo key can SSH into the current
Android-provisioned VM.

Manual Windows SSH to Android-created VMs requires either:

- the per-node key exposed by temporary developer diagnostics, or
- an app diagnostics SSH runner.

For Canonical Ubuntu images, the SSH username is `ubuntu`.

## Always Free Tier Summary

Always verify against Oracle's current Always Free documentation:
<https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm>

- **AMD micro:** up to 2 x `VM.Standard.E2.1.Micro`.
- **Ampere ARM (A1 Flex):** subject to current regional capacity and
  current free-tier limits.
- **Block storage:** currently 200 GB Always Free total.
- **Instances must be created in the tenancy home region.**
- **Idle reclamation:** Oracle may reclaim Always Free instances that
  stay below its idle thresholds for multiple days.

Recommended boring baseline:

| Setting | Value | Rationale |
|---|---|---|
| Shape | `VM.Standard.E2.1.Micro` | Always Free eligible, x86, widely available |
| Image | Canonical Ubuntu 22.04 or 24.04 | Tested Ubuntu path |
| Boot volume | 50 GB | Within Always Free storage |
| Public IP | Enabled | Required for a public exit node |

## OCI Network Requirements

OCI security list or NSG ingress must allow:

| Port | Protocol | Source | Purpose |
|---|---|---|---|
| 22 | TCP | operator/dev IP or temporary `0.0.0.0/0` | SSH provisioning/debug |
| 51820 | UDP | `0.0.0.0/0` | WireGuard |

OCI ingress is only one firewall. The Ubuntu guest firewall can still
reject packets after OCI has delivered them to the VM.

## Durable VM WireGuard Setup Expectations

A fresh Android-provisioned VM should generate a durable
`/etc/wireguard/wg0.conf` that:

- enables IPv4 forwarding persistently,
- detects the real outbound interface dynamically,
- allows UDP `51820` on `INPUT` before any guest firewall reject,
- inserts `FORWARD` accept rules before any unconditional reject,
- adds NAT masquerade for `10.66.66.0/24`,
- persists the `[Peer]` section in `wg0.conf`,
- avoids duplicate rules on restart by deleting matching old rules
  before inserting/appending,
- safely removes those same rules in `PostDown`.

The outbound interface must not be hardcoded as `eth0`. OCI Ubuntu used
`ens3` in the debug session. Use dynamic detection:

```bash
PUBLIC_IF=$(ip route show default | awk '{print $5; exit}')
```

If `$PUBLIC_IF` is used inside `wg0.conf` `PostUp`/`PostDown`, it must
either be expanded while the file is written or defined in the same
command context. A literal undefined `$PUBLIC_IF` inside `wg0.conf` is a
bug.

Readable intended `PostUp` pattern:

```bash
iptables -D INPUT -p udp --dport 51820 -j ACCEPT 2>/dev/null || true
iptables -D FORWARD -i wg0 -o "$PUBLIC_IF" -j ACCEPT 2>/dev/null || true
iptables -D FORWARD -i "$PUBLIC_IF" -o wg0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true
iptables -t nat -D POSTROUTING -s 10.66.66.0/24 -o "$PUBLIC_IF" -j MASQUERADE 2>/dev/null || true

iptables -I INPUT 1 -p udp --dport 51820 -j ACCEPT
iptables -I FORWARD 1 -i wg0 -o "$PUBLIC_IF" -j ACCEPT
iptables -I FORWARD 2 -i "$PUBLIC_IF" -o wg0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
iptables -t nat -A POSTROUTING -s 10.66.66.0/24 -o "$PUBLIC_IF" -j MASQUERADE
```

Readable intended `PostDown` pattern:

```bash
iptables -D INPUT -p udp --dport 51820 -j ACCEPT 2>/dev/null || true
iptables -D FORWARD -i wg0 -o "$PUBLIC_IF" -j ACCEPT 2>/dev/null || true
iptables -D FORWARD -i "$PUBLIC_IF" -o wg0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true
iptables -t nat -D POSTROUTING -s 10.66.66.0/24 -o "$PUBLIC_IF" -j MASQUERADE 2>/dev/null || true
```

Persist the peer:

```ini
[Peer]
PublicKey = <android-client-public-key>
AllowedIPs = 10.66.66.2/32
```

## WireGuard Debugging: What Each Signal Actually Means

### 1. Android Says Connected

The Android key icon or app "Connected" state only proves Android
`VpnService`/WireGuard `GoBackend` accepted something locally. It does
not prove:

- server handshake,
- routing,
- NAT,
- DNS,
- internet reachability.

### 2. Browser Has No Internet

No browser internet through the VPN can be caused by:

- no WireGuard handshake,
- Linux guest firewall blocking UDP `51820` before WireGuard accepts it,
- WireGuard key mismatch,
- bad `FORWARD` chain ordering,
- missing NAT masquerade,
- DNS problem,
- app using stale tunnel/config.

### 3. `sudo wg show` Is the Server Truth Test

A working tunnel must show:

- peer endpoint for the Android phone,
- `latest handshake: ...` recent,
- non-zero `transfer` received and sent.

If `sudo wg show` shows the peer and `AllowedIPs` but no latest
handshake, do not debug NAT yet. NAT is after handshake. First debug
whether UDP `51820` reaches WireGuard and whether the server firewall
permits it.

### 4. `tcpdump` Can See Packets Before WireGuard Accepts Them

Command:

```bash
sudo tcpdump -ni any udp port 51820
```

During the 2026-06-21 debug session, `tcpdump` showed UDP length `148`
packets arriving from the phone while Android VPN was on. These are
WireGuard handshake initiation packets.

Interpretation:

- If `tcpdump` sees no UDP `51820` packets while Android connects,
  suspect OCI security list/NSG, wrong endpoint, or app not sending.
- If `tcpdump` sees UDP `51820` packets but `wg show` has no latest
  handshake, packets reached the VM network interface but were not
  accepted by WireGuard.
- This can be caused by Linux guest `INPUT` firewall rules rejecting
  before WireGuard, or by true WireGuard cryptographic mismatch.
- Do not assume OCI ingress is broken just because WireGuard has no
  handshake.
- Do not assume WireGuard key mismatch until guest `INPUT` firewall has
  been checked.

### 5. OCI Security List Is Not the Only Firewall

OCI ingress can be correct and `tcpdump` can still show packets arriving
while Ubuntu rejects them before WireGuard processes them.

The actual blocker in the live debug session was an Ubuntu guest firewall
with rules like:

```bash
-A INPUT -m state --state RELATED,ESTABLISHED -j ACCEPT
-A INPUT -p icmp -j ACCEPT
-A INPUT -i lo -j ACCEPT
-A INPUT -p tcp -m state --state NEW -m tcp --dport 22 -j ACCEPT
-A INPUT -j REJECT --reject-with icmp-host-prohibited
```

There was no `INPUT` accept for UDP `51820` before the unconditional
reject.

Manual repair that enabled handshake:

```bash
sudo iptables -I INPUT 1 -p udp --dport 51820 -j ACCEPT
```

After this, `sudo wg show` showed a recent handshake and non-zero
transfer counters.

### 6. FORWARD Rule Order Matters

The VM also had `FORWARD` rules ordered like:

```bash
-A FORWARD -j REJECT --reject-with icmp-host-prohibited
-A FORWARD -i wg0 -o ens3 -j ACCEPT
-A FORWARD -i ens3 -o wg0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
```

This is wrong. iptables/nftables are first-match-wins. The unconditional
reject comes before the WireGuard accept rules, so forwarded VPN traffic
is rejected first.

Correct order:

```bash
-A FORWARD -i wg0 -o ens3 -j ACCEPT
-A FORWARD -i ens3 -o wg0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
-A FORWARD -j REJECT --reject-with icmp-host-prohibited
```

Use `iptables -I`, not `iptables -A`, when accept rules must precede
reject rules.

### 7. NAT Must Masquerade Out the Real Public Interface

The OCI Ubuntu interface was `ens3`, not `eth0`. Do not hardcode `eth0`.

```bash
PUBLIC_IF=$(ip route show default | awk '{print $5; exit}')
sudo iptables -t nat -A POSTROUTING -s 10.66.66.0/24 -o "$PUBLIC_IF" -j MASQUERADE
```

## Known Failure Signatures

### Case A: Android Connected, Browser Has No Internet, `wg show` Has No Handshake

Check:

```bash
sudo ss -lunp | grep 51820
sudo tcpdump -ni any udp port 51820
sudo iptables -S INPUT
sudo nft list ruleset
sudo wg show
```

Likely causes:

- guest `INPUT` firewall blocking UDP `51820`,
- WireGuard key mismatch,
- app using stale config,
- OCI UDP ingress missing if `tcpdump` sees nothing.

### Case B: `tcpdump` Sees UDP Length 148, But `wg show` Has No Handshake

Interpretation:

- packets reached the VM interface,
- if no UDP `51820` `INPUT` accept exists before reject, fix guest
  firewall first,
- if `INPUT` is correct, then investigate WireGuard key/config mismatch.

### Case C: `wg show` Has Handshake and Transfer Counters, But Browser Has No Internet

Interpretation:

- WireGuard handshake works,
- now debug forwarding, NAT, and DNS.

Check:

```bash
sudo iptables -S FORWARD
sudo iptables -t nat -S
sudo sysctl net.ipv4.ip_forward
ip route show default
curl -4 ifconfig.me
```

### Case D: Handshake Works, But FORWARD Reject Comes Before Accepts

Fix rule ordering with `iptables -I`, not `iptables -A`.

### Case E: VM Internet Works But Phone Internet Does Not

Check NAT, `FORWARD` ordering, DNS, `AllowedIPs`, and Android route.

## Minimal VM Debug Command Block

```bash
sudo wg show
sudo wg show all dump
sudo wg showconf wg0 | sed 's/^PrivateKey = .*/PrivateKey = REDACTED/; s/^PresharedKey = .*/PresharedKey = REDACTED/'
sudo ss -lunp | grep 51820
sudo tcpdump -ni any udp port 51820
sudo iptables -S INPUT
sudo iptables -S FORWARD
sudo iptables -t nat -S
sudo nft list ruleset 2>/dev/null | sed -n '1,220p'
sudo sysctl net.ipv4.ip_forward
ip route show default
curl -4 ifconfig.me
```

## Manual Live Repair Commands

These exact commands made the live VM work in the 2026-06-21 debug
session:

```bash
sudo iptables -I INPUT 1 -p udp --dport 51820 -j ACCEPT

sudo iptables -D FORWARD -i wg0 -o ens3 -j ACCEPT 2>/dev/null || true
sudo iptables -D FORWARD -i ens3 -o wg0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true

sudo iptables -I FORWARD 1 -i wg0 -o ens3 -j ACCEPT
sudo iptables -I FORWARD 2 -i ens3 -o wg0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT

sudo iptables -S INPUT
sudo iptables -S FORWARD
sudo wg show
```

Replace `ens3` with the real default route interface if different:

```bash
ip route show default
```

Prefer dynamic detection in provisioning. These manual commands repair
the current runtime only unless persisted in `wg0.conf` `PostUp`/`PostDown`
or the system firewall configuration.

## Successful End-to-End Criteria

- Android VPN key icon appears.
- `sudo wg show` shows latest handshake and non-zero transfer.
- Phone browser loads internet.
- IP check from the phone shows the Oracle VM public IP.
- ipinfo/IP lookup shows Oracle Public Cloud / AS31898 or equivalent.
- Disconnect restores normal phone internet.
- Destroy removes the VM and API key cleanup still works.

The 2026-06-21 working result was confirmed by the Android browser IP
check showing the Oracle VM public IP. ipinfo reported Oracle Public
Cloud / AS31898, proving phone -> WireGuard tunnel -> OCI VM -> internet.
That success came after manual VM firewall repair, which is why the
provisioning setup must enforce the same firewall ordering durably.

## Important Lessons from 2026-06-21 Debug Session

- Android `VpnService` "connected" is not proof of a WireGuard handshake.
- `tcpdump` seeing packets is not proof WireGuard accepted them after
  firewall processing.
- OCI ingress and Linux guest firewall are separate gates.
- Missing `INPUT` allow for UDP `51820` can produce `tcpdump` packets
  with no `wg show` handshake.
- NAT and `FORWARD` only matter after handshake.
- Rule order matters because Oracle Ubuntu can have unconditional reject
  rules.
- Use `iptables -I`, not `iptables -A`, when accept rules must precede
  reject rules.
- Do not hardcode `eth0`; OCI Ubuntu used `ens3`.
- Persist `[Peer]` in `wg0.conf` for debuggability and restart safety.
- Keep runtime diagnostics, but never show private WireGuard keys or SSH
  keys in production builds.
- Debug SSH key display is temporary only and should not ship publicly.

## Manual Console VM Creation Reference

For manual experiments only:

1. Create an Always Free eligible Ubuntu VM in the tenancy home region.
2. Use a public subnet with public IPv4.
3. Add OCI ingress for UDP `51820` and SSH as needed.
4. Use the correct SSH key for the path you are testing:
   - manual/harness path may use `secrets/oci-zerovpn-exit-01`,
   - Android-created VMs use the per-node generated key.
5. SSH username for Ubuntu is `ubuntu`.

## Security Notes

- Do not open ports beyond SSH and WireGuard unless a specific debug
  session requires it.
- Treat WireGuard client configs and SSH private keys as secrets.
- Do not log private WireGuard keys, preshared keys, OCI session tokens,
  or SSH private keys.
- The VM is disposable. If resource state is confused, destroy and
  recreate after preserving any debug evidence needed.
