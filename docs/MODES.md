# Routing Modes

ZeroVPN supports three routing modes. Only one can be active at a time
(Android allows a single active VPN service).

## 1. Private Exit Mode

The main fast/stable path. The user or a trusted operator runs a
WireGuard exit node outside the UK. The app imports the WireGuard config
and routes all phone traffic through the tunnel.

**Trade-offs:**
- ✅ Fast and stable
- ✅ Dedicated IP (residential or datacenter)
- ✅ No CAPTCHAs or blocks under normal use
- ❌ Requires someone to set up and maintain a node
- ❌ Operator can see destination traffic

**Config sources:**
- Import from file/text
- Scan QR code
- `zerovpn://` invite link

**Capabilities (MVP):**
- import WireGuard config
- connect/disconnect via `VpnService`
- status display (connected, handshake, exit IP)
- DNS through tunnel
- IPv6 behaviour explicit
- multiple saved exits
- config deletion
- handshake failure diagnostics

## 2. Invite Mode

The most important no-bank-card path. A node operator issues a
revocable invite to a known person. The phone user scans a QR code or
opens a `zerovpn://` link. No cloud account, card, or technical
knowledge needed.

**V1 (operator-generated keys):**
- Operator generates peer config including private key
- Phone user scans QR, imports, connects
- Trust: operator has the private key; docs must explain this

**V2 (phone-generated keys):**
- Phone generates keypair locally
- Phone presents public key to operator
- Operator adds peer, returns config without phone's private key
- Trust: phone owns its key; operator never sees it

**Trade-offs:**
- ✅ No card, cloud account, or technical setup for the phone user
- ✅ Revocable by operator
- ❌ Depends on a trusted operator
- ❌ V1: operator has the peer's private key

## 3. Volunteer Network Mode

The free, no-card fallback. Routes ordinary clearnet traffic through a
public volunteer relay network. No server, card, cloud account, or
subscription required.

**Trade-offs:**
- ✅ Free and immediate
- ✅ No setup required
- ❌ Slower and less reliable
- ❌ May trigger CAPTCHAs or blocks
- ❌ Exit is not private
- ❌ Not stable by country

**Constraints:**
- Clearnet only — no `.onion` access in the user flow
- No dark-web discovery
- No onion-service hosting
- UI must warn about speed and reliability trade-offs
- Technical docs must disclose Tor if used
- Do not imply endorsement by The Tor Project

**Implementation options (research pending):**
- A. Embedded Tor/Arti + tun2socks inside ZeroVPN `VpnService`
- B. Orbot handoff (guided MVP, integrated preferred)
- C. Other public relay transports (if free/no-card)

See `docs/VOLUNTEER_NETWORK_MODE.md` for the research spike.

## Mode comparison

| | Private Exit | Invite | Volunteer |
|---|---|---|---|
| Speed | Fast | Fast | Slow |
| Stability | Stable | Stable | Variable |
| Setup | Node required | Operator + QR | None |
| Card needed | By operator | By operator | No |
| Revocable | Yes (operator) | Yes (operator) | N/A |
| Private IP | Yes | Yes | No |
| Blocks/CAPTCHAs | Rare | Rare | Common |