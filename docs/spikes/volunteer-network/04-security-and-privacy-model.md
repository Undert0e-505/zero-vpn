# Security and Privacy Model

Volunteer Network must use careful product language.

This mode can change where traffic exits the network. It does not guarantee
anonymity, does not make apps stop identifying the user, and does not make all
traffic private from every observer.

## Network Ownership

ZeroVPN does not operate the volunteer network or its exits.

Users should understand the difference between:

* a personal Oracle Free Exit in the user's own cloud account
* a public volunteer-operated network
* a commercial VPN service
* a private or trusted WireGuard node

Volunteer Network is not ZeroVPN infrastructure.

## Exit Abuse Risk

Public volunteer-operated exits can be abused by users. This can cause:

* blocked websites
* captchas
* rate limits
* abuse complaints to exit operators
* unstable availability

If ZeroVPN ever coordinates volunteers directly, abuse handling becomes a core
product and governance problem, not just an engineering problem.

## Traffic Metadata

A VPN-like tunnel changes routing, but metadata can still exist at many layers:

* the user's device and apps
* websites and app backends
* account logins
* the volunteer network
* the final exit network
* DNS infrastructure if DNS is not handled correctly
* the user's access network observing that a tunnel is active

If Tor-derived routing is used, ZeroVPN should still avoid promising Tor
Browser-level anonymity for arbitrary Android app traffic. Apps and browsers can
identify users through logins, device identifiers, fingerprinting, and behavior.

## DNS Handling

DNS must be designed explicitly.

Bad outcomes:

* DNS goes outside the tunnel.
* DNS goes to the user's normal resolver while TCP goes through the volunteer
  network.
* DNS works for some apps but not others.
* DNS failures look like generic connection failures.

Diagnostics should include a DNS check and plain explanations.

## UDP Handling

Many Tor/SOCKS/tun2socks approaches are TCP-first. UDP must not be ignored.

Acceptable early behavior:

* block UDP and explain that some apps may not work
* support only DNS over a controlled path
* show a clear unsupported-traffic message when possible

Do not silently claim full-device compatibility if UDP is unsupported.

## Android Per-App Routing

Per-app routing can reduce user surprise and failure surface.

Questions to answer:

* should Volunteer Network default to all apps or selected apps?
* should high-bandwidth apps be excluded by default?
* should system apps be excluded?
* how should excluded apps be explained?

## Battery and Performance

Volunteer Network may be slower and more battery-intensive than a direct
WireGuard tunnel.

Possible causes:

* local proxy process
* circuit/bootstrap time
* user-space packet handling
* extra encryption layers
* blocked or retried traffic

UX should set expectations before connection.

## User Mental Model

Plain explanation:

> This routes traffic through a public volunteer-operated network. It can help
> move traffic away from your normal network path, but it is not a guarantee of
> anonymity.

Avoid claims like:

* anonymous browsing
* universal site access
* secure against all observers
* private by default
