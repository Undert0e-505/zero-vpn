# Provider Lanes

ZeroVPN should offer several "create or get an exit" paths. Each lane
is a guided deployment path, not an automation script. ZeroVPN never
handles cloud credentials or card details.

## 1. Oracle Free Exit

**Status:** Phase 2 — active development.

Oracle's Always Free tier provides a durable VM suitable for a
WireGuard exit node. Signup is card-gated — the app must not collect
Oracle credentials or card details.

**Signup instructions:**
<https://docs.oracle.com/en-us/iaas/Content/GSG/Tasks/signingup_topic-Sign_Up_for_Free_Oracle_Cloud_Promotion.htm>

**Oracle Terms of Use:**
<https://www.oracle.com/legal/terms/>

**Deployment paths:**

1. **Cloud Shell script (developer/first-run path):** user opens OCI
   Cloud Shell, pastes one command, script creates all infrastructure
   via pre-authenticated OCI CLI. See `cloud-shell/oci-bootstrap.sh`.
2. **Resource Manager / Terraform stack (product path):** user clicks
   a link, OCI creates the stack, user reviews and applies. Future
   work — not yet built.

**Both paths create:**
- VCN + public subnet + internet gateway + route table
- Security list with SSH (22/tcp) and WireGuard (51820/udp) ingress
- Always Free eligible Ubuntu VM with public IP
- SSH key injection from project keypair
- Output: public IP, SSH username, connection instructions

**Manual console navigation is NOT a supported path.** The OCI console
VNIC/networking/firewall flow is too complex for a phone-based user.

**Must not do:**
- Automate Oracle signup
- Scrape Oracle
- Proxy credentials
- Attempt to bypass eligibility rules

**Validated (2026-06-19):**
- Always Free tier: 2 × VM.Standard.E2.1.Micro (AMD, 1/8 OCPU, 1 GB)
  or equivalent Ampere A1 Flex (2 OCPU, 12 GB after Oracle's cut)
- 200 GB Always Free block storage (min 47 GB boot volume)
- Instances must be in home region
- Idle reclamation: <20% CPU/network/memory for 7 days → reclaim risk
- `--assign-public-ip true` on `oci compute instance launch` works
  when subnet is public (no `--prohibit-public-ip`)

**Still open:**
- Bandwidth terms and acceptable-use constraints (TBD)
- Non-UK region selection for production exits (home region is UK South
  for Aaron's account — may need a second account or region migration)

## 2. Azure Student Exit

**Status:** Research phase.

Azure for Students may provide a cardless path to a Linux VM with
public IP. Eligibility is based on student status verification, not
card details.

**Research questions:**
- Can a student account create a Linux VM with public IP?
- What regions are available?
- What are the bandwidth and compute limits?
- Is WireGuard supported (kernel module or userspace)?
- Does the student subscription have restrictions on network security
  groups or port forwarding?

**Do not claim support until tested end-to-end.**

## 3. Existing Server

**Status:** Phase 3 target.

User or operator already has an overseas Linux server. Provide a
one-command installer and QR output.

Target: Ubuntu/Debian first. Installer must be auditable shell, not a
mystery blob. See `node/install/` for the installer design.

## 4. Residential Abroad Node

**Status:** Not MVP.

A trusted person outside the UK runs a node on a home machine, mini-PC,
router, NAS, or Raspberry Pi. May provide a residential IP, which can
be more useful than a datacentre IP.

**Needs:**
- Dynamic DNS guidance
- NAT traversal guidance
- Residential ISP terms awareness

Do not make this MVP unless the path is simple enough.

## 5. Google Account Only

**Status:** Research only.

Google Cloud billing requirements and Cloud Shell limitations may make
this unsuitable for a durable full VPN exit. Do not assume this works.

## 6. Cloudflare/Worker Proxy

**Status:** Research only.

May provide cardless proxy routing for web-like traffic, but is not
equivalent to full phone-wide WireGuard VPN. Treat as experimental.

## General principles

- ZeroVPN never sees cloud credentials
- Each lane is a guided UX, not a credential collection form
- Validate current terms before claiming support
- Do not overfit to any provider until end-to-end tested
- Provider lanes are for node operators, not universal end users
- The phone user's job is to scan a QR or import a config, not to
  understand cloud infrastructure