# tun2socks Feasibility Plan

Date: 2026-06-22

## Purpose

Phase 3 tests one technical pipe:

`Android VpnService TUN fd -> tun2socks -> local embedded Tor SOCKS`

Embedded Tor SOCKS has already been validated on device. ZeroVPN can start Tor
inside the app, expose `127.0.0.1:9050`, make an explicit SOCKS request to
`https://check.torproject.org/api/ip`, receive `HTTP 200 IsTor=true`, and stop
cleanly.

This phase tests whether Android traffic can be routed through a `VpnService`
TUN interface into that SOCKS proxy. It is not production-ready Volunteer
Network mode.

## Architecture Sketch

1. Developer starts the Volunteer VPN spike from Developer Mode diagnostics.
2. ZeroVPN starts or reuses embedded Tor until SOCKS is ready.
3. ZeroVPN requests Android VPN permission through `VpnService.prepare()`.
4. A new isolated Volunteer `VpnService` creates a TUN interface.
5. tun2socks receives the TUN file descriptor.
6. tun2socks forwards TCP flows to `127.0.0.1:9050`.
7. Diagnostics verify Android VPN transport state and routing behavior.
8. Stop shuts down tun2socks first, then closes the TUN interface.

This must stay separate from the Oracle/WireGuard controller.

## Candidate Evaluation

### hev-socks5-tunnel / SocksTun

Status: selected technical candidate, but not yet integrated.

Reasons:

* Android-oriented prior art exists in SocksTun.
* The native API accepts a TUN file descriptor:
  `hev_socks5_tunnel_main(config_path, tun_fd)`.
* It supports SOCKS5.
* It supports IPv4/IPv6.
* It can redirect TCP.
* It has explicit UDP modes, but Tor SOCKS does not provide general UDP support,
  so ZeroVPN should treat UDP as unsupported or blocked for this phase.
* It has map-DNS support that may be useful for DNS-over-SOCKS-style handling.
* License is MIT.

Integration concern:

* No clean Maven AAR was found during this spike.
* SocksTun builds native code with `ndk-build` and a git submodule pointing to
  `hev-socks5-tunnel`.
* Integrating it safely requires an explicit decision to add source/submodule
  native build plumbing, not a quick Gradle dependency.

### xjasonlyu/tun2socks

Status: not selected for the first Android proof.

Reasons:

* Actively maintained general tun2socks project.
* Supports SOCKS and multiple proxy types.
* MIT license.
* Uses a Go/gVisor stack, which is attractive technically but adds gomobile or
  native build complexity for Android.
* No direct maintained Maven AAR path was identified for this app.

### Outline go-tun2socks

Status: not selected.

Reasons:

* Apache 2.0 license.
* Android library build path exists.
* Upstream repository states it is no longer maintained and points users to
  Outline/Intra repositories instead.
* It would add Go/mobile build complexity and is not the lowest-risk path for a
  new ZeroVPN spike.

### Guardian Project / Orbot IPtProxy Prior Art

Status: prior art only.

Reasons:

* Useful for understanding Orbot-style native packaging and anti-censorship
  components.
* Does not provide the direct maintained tun2socks dependency path needed for
  this phase.
* ZeroVPN product direction is self-contained, not Orbot companion integration.

## Selected Candidate

Use `hev-socks5-tunnel` as the first tun2socks candidate if the project accepts
adding native source build plumbing or a pinned source dependency. Do not add
prebuilt binaries manually.

The next implementation step should decide one of:

1. Add `hev-socks5-tunnel` as a pinned source dependency/submodule and build it
   with Android NDK.
2. Create a small internal native wrapper around the documented
   `hev_socks5_tunnel_main_from_str(config, len, tun_fd)` and
   `hev_socks5_tunnel_quit()` APIs.
3. If a maintained AAR appears, prefer that over source vendoring.

## Proposed TUN Config

Initial Android `VpnService.Builder` config:

* session: `ZeroVPN Volunteer Spike`
* address: `10.111.0.2/32`
* route: `0.0.0.0/0`
* MTU: conservative default such as `1500`
* SOCKS target: `127.0.0.1:9050`

ZeroVPN itself should be handled deliberately. For routing validation, include
ZeroVPN app traffic only if the in-app validation request is expected to go
through the TUN path. If ZeroVPN is excluded, use browser validation instead.

## DNS Handling Plan

DNS must not silently use normal Android DNS.

Preferred plan for the first real implementation:

* Use `hev-socks5-tunnel` map-DNS if it works with Tor SOCKS for TCP flows.
* Configure the VPN DNS server to the mapped DNS address.
* Validate with a DNS leak test before making product claims.

Fallback for first feasibility run:

* Mark DNS as unsupported/incomplete.
* Avoid calling browser tests acceptable if DNS may leak.
* Use only explicit in-app validation and label it as app-level proof, not a
  full routing proof.

## UDP Handling Plan

Tor SOCKS is not a general UDP transport.

For this spike:

* Treat UDP as unsupported.
* Configure tun2socks to avoid implying UDP support.
* Diagnostics must say `udpMode=unsupported/blocked`.
* Do not claim full app compatibility.

## Acceptance Tests

Level 1:

* Developer Mode-only UI exists.
* Android VPN permission flow works.
* TUN interface is created.
* tun2socks starts with target `127.0.0.1:9050`.
* Android VPN transport is detected.
* Stop closes tun2socks and TUN.

Level 2:

* In-app request to `https://check.torproject.org/api/ip` without explicit
  SOCKS proxy returns `HTTP 200 IsTor=true`, if ZeroVPN app traffic is routed
  through the TUN path.
* Otherwise, browser check to `https://check.torproject.org/` confirms Tor
  routing, with DNS limitations documented.

## Developer Mode Only

This remains Developer Mode-only because:

* DNS behavior is not yet proven.
* UDP behavior is unsupported.
* native lifecycle and crash behavior are not yet proven.
* Android allows one active VPN owner, so this can conflict with WireGuard.
* product warnings and public documentation are not complete.
* this is a technical routing spike, not production Volunteer Network mode.

## Stop Condition Hit

This planning pass did not add Android code because no clean maintained Maven
dependency/AAR path was identified. The selected candidate is source-buildable,
but adding native source/submodule build plumbing needs an explicit follow-up
decision.
