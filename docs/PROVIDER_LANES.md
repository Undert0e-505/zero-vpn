# Provider Lanes

ZeroVPN should offer several "create or get an exit" paths. Each lane
is a guided deployment path, not an automation script. ZeroVPN never
handles cloud credentials or card details.

## 1. Oracle Free Exit

**Status:** Research phase.

Oracle's Always Free tier may provide a durable overseas VM suitable
for a WireGuard exit node. Signup is card-gated — the app must not
collect Oracle credentials or card details.

**Signup instructions:**
<https://docs.oracle.com/en-us/iaas/Content/GSG/Tasks/signingup_topic-Sign_Up_for_Free_Oracle_Cloud_Promotion.htm>

**Preferred flow:**
1. App opens Oracle signup in Chrome Custom Tab / external browser
2. User signs up directly with Oracle
3. App guides deployment via Oracle's console or Resource Manager
4. App imports the resulting WireGuard config

**Must not do:**
- Automate Oracle signup
- Scrape Oracle
- Proxy credentials
- Attempt to bypass eligibility rules

**Research questions (validate before implementing):**
- Current Always Free tier limits (VM shapes, memory, CPU)
- Available regions (non-UK)
- Public IP behaviour (reserved vs ephemeral)
- Bandwidth terms and acceptable-use constraints
- Whether ARM (Ampere) instances are suitable

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