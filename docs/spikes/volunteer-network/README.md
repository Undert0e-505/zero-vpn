# Volunteer Network Research Spike

This spike explores how ZeroVPN could add Volunteer Network mode after v0.1.

ZeroVPN v0.1 currently supports Oracle Free Exit: the app provisions a small
Oracle Cloud VM, installs WireGuard, and connects Android traffic through that
VM. Volunteer Network is a different product shape. It routes traffic through a
public volunteer-operated network instead of through infrastructure owned by
the user.

## What Volunteer Network Means

Volunteer Network is the user-facing feature name.

It means:

* traffic may be routed through public volunteer-operated infrastructure
* ZeroVPN does not operate the exits
* ZeroVPN does not provide a commercial VPN service
* performance may be slower than a private exit
* some sites may block, rate-limit, or challenge this traffic

It does not mean:

* anonymity guarantees
* ZeroVPN-operated servers
* onion-site browsing unless explicitly implemented later
* a replacement for a professionally audited privacy product

Technical implementation may involve embedded Tor, Onionmasq, Arti, SOCKS, or
tun2socks-style routing, but the primary user-facing name should remain
Volunteer Network. Orbot is useful prior art, not the primary integration path.

## Current Recommendation

Short term, build a self-contained embedded Tor SOCKS proof of concept inside
ZeroVPN. The first step should start local Tor, wait for bootstrap, expose a
local SOCKS port, perform one explicitly SOCKS-routed test request, and shut
down cleanly. It should stay behind Developer Mode and must not route Android
device traffic.

Medium term, add a Volunteer Network internal controller and then prototype
VpnService plus tun2socks behind Developer Mode. This is the likely integrated
app path, but it has meaningful complexity around DNS, UDP, battery use,
licensing, and user expectations.

Long term, track the Tor Project's Onionmasq, Arti, and Tor VPN work. That
direction is more aligned with a modern embedded tunnel architecture, but it
should be treated as a reference until it is clearly suitable for reuse.

Separately, volunteer-operated WireGuard exits are technically closer to the
current ZeroVPN code, but the trust, abuse, and moderation model is different.
That path should be designed separately from public Volunteer Network mode.

## Spike Contents

* [Product definition](01-product-definition.md)
* [Technical options](02-technical-options.md)
* [Android architecture](03-android-architecture.md)
* [Security and privacy model](04-security-and-privacy-model.md)
* [UX copy](05-ux-copy.md)
* [Implementation plan](06-implementation-plan.md)
* [Open questions](07-open-questions.md)
* [Device test results](08-device-test-results.md)
* [Sources](sources.md)

## Next Action

Harden the Phase 1 embedded Tor SOCKS proof of concept:

1. Record richer bootstrap timing, phase, and retry diagnostics.
2. Treat cold and warm bootstrap timeouts differently.
3. Verify Stop shuts down Tor and marks SOCKS inactive.
4. Add developer controls to copy diagnostics and clear Tor state.
5. Keep the feature behind Developer Mode.
6. Do not route Android device traffic yet.
