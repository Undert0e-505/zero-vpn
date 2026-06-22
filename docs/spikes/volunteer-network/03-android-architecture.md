# Android Architecture

Volunteer Network needs a provider-aware connection architecture. It should
share the high-level ZeroVPN connection UI where possible, but it should not
pretend to be an Oracle-provisioned WireGuard exit.

## Current Baseline

ZeroVPN v0.1 has a working Oracle Free Exit path:

* provision Oracle Cloud resources
* install and configure WireGuard on a VM
* create a WireGuard client profile
* connect Android traffic through Android VpnService
* show basic diagnostics

Volunteer Network may not provision anything and may not use WireGuard.

## Provider Capability Model

Connection providers should advertise capabilities instead of forcing all
providers into one shape.

Possible capabilities:

* requires cloud account
* provisions infrastructure
* uses WireGuard
* uses local proxy
* owns Android VpnService
* supports UDP
* supports per-app exclusions
* supports destroy/recreate
* supports exit IP diagnostics
* supports handshake diagnostics

Oracle Free Exit and Volunteer Network would then expose different setup,
diagnostic, and recovery UI.

## Android VpnService Constraints

Android generally allows one active VpnService at a time. This matters for
Orbot companion integration.

If Orbot is running in VPN mode, ZeroVPN probably cannot also own the device VPN
at the same time. If ZeroVPN owns VpnService and tries to route through an
Orbot local SOCKS proxy, the integration must verify that Orbot exposes a
stable local proxy without requiring Orbot VPN mode.

This is the main reason Orbot companion integration should be treated as a proof
of concept before product commitment.

## Orbot Companion Flow

Potential implementation:

1. Detect whether Orbot is installed.
2. If missing, show install guidance.
3. If installed, open Orbot or its relevant settings.
4. Explain that the user may need to start Orbot.
5. Return to ZeroVPN and show status based on what can be detected.

Risks:

* package names and integration APIs need verification
* ZeroVPN may not be able to reliably detect routing readiness
* Orbot VPN mode can conflict with ZeroVPN VPN mode
* UX can feel like a handoff rather than an integrated mode

This is still the fastest proof of concept because it avoids embedding Tor and
tun2socks in the first iteration.

## Embedded Local Tor Flow

Potential implementation:

1. Start local Tor or Arti component.
2. Wait for bootstrap.
3. Expose local SOCKS.
4. Verify a single test request through SOCKS.
5. Later, attach Android VpnService plus tun2socks.

This should be implemented in stages. Do not start with full-device VPN routing
until local bootstrap, lifecycle, and proxy behavior are reliable.

## VpnService Plus tun2socks Flow

Potential implementation:

1. Create a TUN interface with Android VpnService.
2. Route TCP flows from TUN to local SOCKS.
3. Route DNS deliberately through a safe path.
4. Block or explicitly handle UDP.
5. Expose diagnostics for bootstrap, DNS, TCP test, and unsupported traffic.

Implementation risks:

* DNS leaks
* UDP apps failing silently
* battery drain
* native crash handling
* foreground service lifecycle
* Android VPN permission flow
* conflicts with existing WireGuard controller assumptions

## Diagnostics

Volunteer Network diagnostics should be capability-based.

Useful diagnostics:

* routing mode
* local proxy status
* bootstrap status
* DNS behavior
* exit IP test
* blocked UDP count if measurable
* last connection error

Avoid WireGuard-specific labels such as handshake or peer key unless the active
provider is actually WireGuard.

