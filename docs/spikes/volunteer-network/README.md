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

Technical implementation may involve Tor, Orbot, Onionmasq, Arti, SOCKS, or
tun2socks-style routing, but the primary user-facing name should remain
Volunteer Network.

## Current Recommendation

Short term, build an Orbot companion proof of concept. It is the fastest
low-risk way to learn whether a Volunteer Network flow is useful without
embedding Tor or native tunneling code into ZeroVPN immediately.

Medium term, prototype embedded local Tor plus tun2socks behind Android
VpnService. This is the likely integrated app path, but it has meaningful
complexity around DNS, UDP, battery use, licensing, and user expectations.

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
* [Sources](sources.md)

## Next Action

Run Phase 1 as a small Orbot companion proof of concept:

1. Detect whether Orbot is installed.
2. Offer a clear install/open path.
3. Document whether the UX is acceptable.
4. Do not claim this is a fully integrated ZeroVPN mode.
