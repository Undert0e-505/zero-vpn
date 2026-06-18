# Job: Volunteer Network Mode research spike

Branch: agent/2026-06-25_volunteer-network-research

## Objective

Produce a technical decision record for Volunteer Network Mode:
embedded Tor/Arti + tun2socks vs. Orbot handoff vs. other transports.
Determine the sane MVP and the preferred long-term direction.

## Context

- ZeroVPN's Volunteer Network Mode routes clearnet traffic through a
  public volunteer relay network (brief section 7.3).
- Only one Android VPN service can be active — ZeroVPN cannot stack
  its VPN on top of Orbot's.
- Clearnet only — no `.onion` access, no dark-web UI.
- Technical docs must disclose Tor if used.

## Scope

- `docs/VOLUNTEER_NETWORK_DECISION_RECORD.md`
- Web research on Arti Android, Orbot intents, tun2socks

## Non-goals

- No implementation
- No Tor exit relay hosting
- No onion-service hosting

## Constraints

- Must work within a single Android `VpnService`
- Must be free and require no card or account
- Must route clearnet only
- Must be buildable on Windows-native PowerShell (or clearly document
  if NDK cross-compilation requires Linux)

## Research questions

### Embedded Tor/Arti

1. Is Arti buildable for Android (arm64-v8a, armeabi-v7a)?
2. What is the binary size of an Arti + tun2socks bundle?
3. Does tun2socks work reliably inside Android's VpnService?
4. How does DNS resolution work through Tor's SOCKS proxy?
5. Can `.onion` blocking be enforced at the tun2socks layer?
6. What is Arti's current Android readiness (as of 2026)?
7. What NDK version is needed? Can it build on Windows?
8. What license is Arti (Rust crates)?

### Orbot handoff

1. Does Orbot expose intents for start/stop/status?
2. Can ZeroVPN detect whether Orbot's VPN is active?
3. Can ZeroVPN configure Orbot's exit country?
4. What is Orbot's license?
5. Does Orbot support app-level VPN scoping or only system-wide?
6. Can ZeroVPN launch Orbot and wait for connection before showing
   "connected" status?

### Other transports

1. Are there non-Tor public relay networks suitable for clearnet?
2. What are the licensing implications?
3. Do any have Android libraries?

### Android VPN constraint

1. Can ZeroVPN's `VpnService` route traffic to a local SOCKS port
   (where Tor/Arti listens) without conflict?
2. Does `tun2socks` (e.g. `hev-socks5-tunnel`, `tun2socks`) work inside
   Android's VpnService TUN interface?
3. What are the battery/performance implications?

## Expected files changed

- `docs/VOLUNTEER_NETWORK_DECISION_RECORD.md`

## Tests / validation

- Decision record cites sources for each claim
- At least one viable MVP path is identified
- Android VPN single-service constraint is addressed directly
- Build path is validated (or Linux cross-compile requirement documented)

## Done when

- [ ] Decision record written
- [ ] MVP path identified (embedded or Orbot handoff)
- [ ] Long-term direction documented
- [ ] `.onion` blocking strategy addressed
- [ ] Android VPN constraint addressed
- [ ] Licensing checked

## Report back with

- summary
- decision record file path
- recommended MVP approach
- recommended long-term approach
- blockers or unknowns remaining