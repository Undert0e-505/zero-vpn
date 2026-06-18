# Volunteer Network Mode

## Purpose

Provide a free, no-card fallback routing path for users who do not have
access to a private exit node. Route ordinary clearnet traffic through a
public volunteer relay network.

## Constraints

- **One Android VPN service.** ZeroVPN cannot stack its own VPN on top
  of Orbot VPN. Any solution must work within a single `VpnService`.
- **Clearnet only.** No `.onion` access, discovery, or hosting.
- **Honest disclosure.** Technical docs must disclose Tor if used. Do
  not imply endorsement by The Tor Project.
- **No dark-web UI.** Product copy must not use "dark web" language.
- **`.onion` blocking.** Block `.onion` resolution/access in ZeroVPN
  mode if technically feasible.

## Implementation options

### A. Embedded Tor/Arti + tun2socks

Embed a Tor/Arti client and a tun2socks-style packet-to-SOCKS bridge
inside ZeroVPN's own `VpnService`. The app captures packet traffic via
the VPN TUN interface and routes it through Tor's SOCKS port.

**Pros:**
- Fully integrated — one app, one VPN service
- No external app dependency
- User does not need to install Orbot

**Cons:**
- Large dependency (Arti is Rust, cross-compile to Android is non-trivial)
- Complex to maintain and update
- Battery and performance impact
- Arti's Android readiness is still maturing

**Research questions:**
- Is Arti buildable for Android arm64 + armeabi-v7a?
- What is the binary size of an Arti + tun2socks bundle?
- Does tun2socks work reliably inside Android's VpnService?
- How does DNS resolution work through Tor's SOCKS proxy?
- Can `.onion` blocking be enforced at the tun2socks layer?

### B. Orbot handoff (MVP fallback)

Guide the user to install Orbot, configure it, and then ZeroVPN
launches Orbot's VPN. ZeroVPN does not run its own VPN service in this
mode — Orbot does.

**Pros:**
- Minimal implementation effort
- Orbot is maintained and tested on Android
- Tor network is established

**Cons:**
- External app dependency — user must install Orbot
- ZeroVPN cannot control Orbot's VPN settings
- Two-app flow is worse UX
- Orbot may not support deep integration via intents

**Research questions:**
- Does Orbot expose intents for start/stop/status?
- Can ZeroVPN detect whether Orbot's VPN is active?
- Can ZeroVPN configure Orbot's exit country?
- What is the Orbot licensing situation?

### C. Other public relay/proxy transports

Any free, no-card transport that meets the product requirements.

**Research questions:**
- Are there non-Tor public relay networks suitable for clearnet routing?
- What are the licensing implications?
- Do any have Android libraries ready to use?

## MVP decision

The MVP may implement Volunteer Network Mode as a guided Orbot handoff
if embedded routing is too large for the first release. The preferred
product direction is an integrated mode inside ZeroVPN.

The decision must be documented in a technical decision record before
implementation begins.

## Attribution

If Tor is used: "ZeroVPN's Volunteer Network Mode uses Tor for routing.
ZeroVPN is not affiliated with or endorsed by The Tor Project."