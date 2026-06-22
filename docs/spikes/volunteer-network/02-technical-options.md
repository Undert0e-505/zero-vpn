# Technical Options

This document compares likely implementation paths for Volunteer Network mode.

## Option A: Orbot Companion Integration as Prior Art

ZeroVPN detects whether Orbot is installed and guides the user to install,
open, or configure it.

Possible flows:

* open Orbot if installed
* send the user to the Play Store, F-Droid, or GitHub release page
* explain that Orbot provides the volunteer-network routing layer
* optionally detect whether a compatible proxy or VPN mode is available

Complexity: low.

UX complexity: medium. The user leaves ZeroVPN and may need to understand which
app owns the connection.

Security and privacy risks:

* ZeroVPN has less control over routing state.
* Android only allows one active VpnService at a time, so Orbot VPN mode can
  conflict with ZeroVPN's own VpnService.
* Product language can become confusing if users think ZeroVPN operates the
  network.

Maintenance burden: low to medium. ZeroVPN depends on Orbot behavior, package
names, and public integration surfaces.

Licensing concerns: low for companion-app integration, but still review links,
branding, and distribution wording.

Suitability:

* v0.2 proof of concept: useful only as prior art
* v0.3 integrated mode: weak by itself

Recommendation: do not use this as the primary Phase 1 path. Keep Orbot as
prior art for lifecycle, copy, diagnostics, and Android VPN constraints.

## Option B: Embedded Tor Plus tun2socks

ZeroVPN runs Tor locally on Android, exposes a local SOCKS port, creates an
Android VpnService TUN interface, and uses tun2socks to route TCP traffic into
the local SOCKS proxy.

Expected shape:

* local Tor or Arti process/library
* local SOCKS listener
* VpnService TUN interface
* tun2socks-style bridge from TUN TCP flows to SOCKS
* deliberate DNS handling
* UDP blocked, unsupported, or handled explicitly

Complexity: high.

UX complexity: medium to high. The app must explain slower performance,
blocked sites, startup/bootstrap delay, and unsupported traffic.

Security and privacy risks:

* DNS leaks if DNS is not routed or intercepted correctly.
* UDP behavior can surprise users if not blocked or explained.
* App traffic may still identify the user through account logins, app IDs,
  browser fingerprinting, or metadata.
* Native code and long-running local network services increase review burden.

Maintenance burden: high. ZeroVPN would own lifecycle, native packaging,
crashes, battery behavior, and compatibility with Android VPN rules.

Licensing concerns: requires detailed review of every embedded component and
its transitive dependencies.

Suitability:

* v0.2: appropriate if split into an embedded SOCKS proof of concept first
* v0.3: plausible route to full-device routing after DNS, UDP, and lifecycle
  behavior are proven

Recommendation: primary implementation path. Start with embedded Tor SOCKS
only, then add VpnService plus tun2socks after local bootstrap and proxy
behavior are proven.

## Option C: Onionmasq, Tor VPN, and Arti-Aligned Path

The Tor Project is building newer tunnel and VPN work around Arti and
Onionmasq. This is the most relevant long-term architecture reference.

Complexity: currently uncertain.

UX complexity: similar to Option B if embedded into ZeroVPN.

Security and privacy risks:

* project APIs and packaging may still change
* mobile VPN integration needs careful validation
* ZeroVPN must avoid implying Tor Browser-level anonymity for arbitrary app
  traffic

Maintenance burden: potentially lower long term if reusable components become
stable, but high while interfaces are moving.

Licensing concerns: review before embedding. The Tor Project VPN repository
currently lists BSD 3-Clause, but each dependency still needs review.

Suitability:

* v0.2: research/reference only
* v0.3: possible prototype if the upstream project is ready
* long term: strong direction to track

Recommendation: track closely, do not block the first proof of concept on it.

## Option D: Non-Tor Volunteer WireGuard Nodes

Volunteers operate WireGuard exits. ZeroVPN imports or receives trusted
WireGuard configuration, possibly through QR invite flows.

Complexity: medium. The transport is close to ZeroVPN's existing WireGuard
path, but the trust model is very different.

UX complexity: high. Users must understand who operates the exit and why they
should trust it.

Security and privacy risks:

* exit operators can observe traffic metadata and unencrypted traffic
* volunteers face abuse complaints from traffic exiting their networks
* invite distribution and revocation require a trust model
* public discovery would need moderation and abuse handling

Maintenance burden: high if ZeroVPN coordinates discovery, reputation, or
abuse handling. Lower if it is only a trusted invite/import flow.

Licensing concerns: low if using existing WireGuard components, but QR and
trust infrastructure may add dependencies.

Suitability:

* v0.2: maybe as trusted invite research
* v0.3: possible if separated from public Volunteer Network

Recommendation: treat as a separate design path, closer to QR Invite or
Private Node than public Volunteer Network.

## Overall Recommendation

1. Use Orbot only as prior art. Do not make a companion-app flow the planned
   Phase 1.
2. Prototype embedded local Tor with one SOCKS-routed test connection.
3. Add VpnService plus tun2socks only after DNS, UDP, lifecycle, and licensing
   questions have concrete answers.
4. Keep volunteer WireGuard nodes separate until there is a trust and abuse
   model.
