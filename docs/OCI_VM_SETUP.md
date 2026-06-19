# OCI VM Setup Guide

> **⚠️ Deprecated path.** Manual console setup is no longer the
> recommended approach. The OCI console VNIC/networking/firewall flow
> is too complex for phone-based operation. Use the **Cloud Shell
> bootstrap script** instead:
> ```
> bash <(curl -sL https://raw.githubusercontent.com/Undert0e-505/zero-vpn/main/cloud-shell/oci-bootstrap.sh)
> ```
> This guide is kept as reference only.

This guide covers manual console steps to create an Always Free eligible
Ubuntu VM in Oracle Cloud. The agent does not handle Oracle credentials,
card details, 2FA, or browser sessions.

## What the agent has prepared

- **SSH keypair:** generated locally at `secrets/oci-zerovpn-exit-01`
  (private key) and `.pub` (public key). Both are git-ignored.
- **Public key to paste into Oracle:**

```
ssh-ed25519 AAAA_REPLACE_WITH_TEST_PUBLIC_KEY example-only
```

## Always Free tier summary (validated 2026-06-19)

Source: <https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm>

- **AMD micro:** up to 2 × `VM.Standard.E2.1.Micro` (1/8 OCPU, 1 GB each)
- **Ampere ARM (A1 Flex):** equivalent to 2 OCPUs + 12 GB RAM total
  (1,500 OCPU-hours + 9,000 GB-hours/month)
- **Block storage:** 200 GB Always Free (min 47 GB boot volume per VM)
- **Instances must be created in your home region**
- **Idle reclamation:** if CPU/network/memory < 20% for 7 days, Oracle
  may reclaim the instance. A WireGuard exit with active traffic should
  stay well above this threshold.

**Note:** Oracle quietly cut the Ampere A1 Free allowance from 4 OCPU/24 GB
to 2 OCPU/12 GB. The docs now reflect the reduced allocation.

## Recommended VM configuration

| Setting | Value | Rationale |
|---|---|---|
| **Shape** | `VM.Standard.E2.1.Micro` (AMD) | Boring, x86, always available. ARM/Ampere has more specs but capacity is often "out of host capacity." Start simple. |
| **Image** | Canonical Ubuntu 22.04 (x86) | Matches the node installer's tested OS list. 24.04 also works if 22.04 isn't listed. |
| **OCPUs** | 1/8 (fixed for micro) | WireGuard barely uses CPU; this is fine for 1-3 peers. |
| **Memory** | 1 GB (fixed for micro) | Plenty for WireGuard + NAT. |
| **Boot volume** | 50 GB (default, within 200 GB Free) | Minimal; just need OS + WireGuard. |

If you want the beefier ARM node instead (2 OCPU, 12 GB, Ampere A1 Flex),
the steps are the same but choose `VM.Standard.A1.Flex` as the shape and
select an Ubuntu ARM image. Be prepared for "out of host capacity" errors
— retry or switch availability domain.

## Step-by-step: Create the VM

### 1. Go to Compute > Instances

1. Log into the OCI Console: <https://cloud.oracle.com>
2. Select your **home region** (top-right dropdown). This is where
   Always Free resources must be created.
3. Navigation menu → **Compute** → **Instances**

### 2. Create instance

Click **Create instance** and set:

- **Name:** `zerovpn-exit-01`
- **Compartment:** (root compartment — default)
- **Placement:** leave as default (any availability domain)

### 3. Image and shape

- **Image:** click **Change image** → select **Canonical Ubuntu 22.04**
  (look for the "Always Free Eligible" checkmark on the image)
- **Shape:** click **Change shape** → **VM.Standard.E2.1.Micro**
  (AMD, Always Free Eligible)

### 4. Networking (VCN)

If you don't have a VCN yet, OCI will offer to create a default one.
Accept the default VCN with a public subnet. You need:

- **A public subnet** (so the VM gets a public IP)
- **A public IP address** assigned to the instance (check "Assign a
  public IPv4 address" — should be default for public subnet)

If you already have a VCN, select it. If not, click **Create new VCN**
and accept defaults — OCI creates a VCN with an internet gateway and a
public subnet automatically.

### 5. SSH keys

- Select **Paste public keys**
- Paste this exact key (already generated for you):

```
ssh-ed25519 AAAA_REPLACE_WITH_TEST_PUBLIC_KEY example-only
```

- **Do not** select "Generate a key pair" — we already have one.

### 6. Boot volume

- Leave at default **50 GB** (within the 200 GB Always Free block storage)
- Uncheck "Use in-transit encryption" unless you have a specific reason

### 7. Review and create

- Click **Create**
- Wait ~2-5 minutes for provisioning to complete
- Note the **Public IP Address** shown on the instance detail page

## Security list / firewall settings

After the VM is created, you need to open UDP port 51820 for WireGuard.
OCI uses **security lists** (or Network Security Groups) on the VCN.

### Option A: Edit the default security list (simplest)

1. Navigation menu → **Networking** → **Virtual Cloud Networks**
2. Click your VCN → click the **Default Security List** (or the security
   list attached to your public subnet)
3. Click **Add Ingress Rules** and add:

| Field | Value |
|---|---|
| Source CIDR | `0.0.0.0/0` |
| IP Protocol | UDP |
| Destination Port Range | `51820` |
| Description | ZeroVPN WireGuard |

4. Leave the existing rules intact (they allow SSH on port 22 from
   `0.0.0.0/0` by default — that's fine for now)

### Option B: Use a Network Security Group (cleaner, optional)

1. Navigation menu → **Networking** → **Network Security Groups**
2. Create a new NSG: `zerovpn-nsg`
3. Add ingress rule: UDP, source `0.0.0.0/0`, port `51820`
4. Add ingress rule: TCP, source `0.0.0.0/0`, port `22` (SSH)
5. Assign the NSG to the VM instance (Instance details → VNIC → Edit)

## After the VM is up

Once the instance is running, send the agent:

1. **Public IP address** of the VM
2. **SSH username** (for Ubuntu images, this is always `ubuntu`)
3. Confirm you can SSH in (optional but helpful)

The agent already has the private key at
`secrets/oci-zerovpn-exit-01` and will use it to:

1. SSH in and run the ZeroVPN node installer (`node/install/install.sh`)
2. Configure WireGuard, generate keys, set up NAT
3. Generate a peer config + QR code for the Android app
4. Verify the tunnel works end-to-end

## Security notes

- The SSH private key is stored at `secrets/oci-zerovpn-exit-01` and is
  git-ignored (`secrets/` is in `.gitignore`).
- No Oracle credentials, 2FA secrets, or OCI API keys are stored
  anywhere in the repo or workspace.
- The VM is disposable — if anything goes wrong, terminate it and start
  over. Always Free quota resets immediately.
- **Do not** open any ports beyond 22 (SSH) and 51820 (WireGuard) in the
  security list.

## Oracle TOS reference

- <https://www.oracle.com/legal/terms/>
- <https://docs.oracle.com/en-us/iaas/Content/GSG/Tasks/signingup_topic-Sign_Up_for_Free_Oracle_Cloud_Promotion.htm>

## Idle reclamation warning

Oracle may reclaim Always Free instances that are idle for 7 days
(CPU/network/memory all under 20%). A WireGuard exit with active
traffic should clear this bar easily. If the node will be unused for
extended periods, consider running a lightweight keepalive (e.g.
`cron` job pinging through the tunnel every few hours) to avoid
reclamation.