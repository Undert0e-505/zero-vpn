# UX Copy

Use "Volunteer Network" as the user-facing feature name.

Do not name the feature after an underlying transport in primary product UI.
Technical notes may mention Tor or Tor-derived routing when needed.

## Feature Tile

Title:

> Volunteer Network

Description:

> Free routing through a public volunteer-operated network. Slower than a
> private exit. Some sites may block this traffic.

## Warning Screen

Title:

> Use Volunteer Network

Body:

> This mode routes traffic through a public volunteer-operated network. ZeroVPN
> does not operate the network or the exits. It may be slower and some sites may
> block or challenge traffic. This is not a guarantee of anonymity.

Primary action:

> Continue

Secondary action:

> Back

## Advanced Technical Note

> This mode may use Tor or Tor-derived routing infrastructure under the hood.
> ZeroVPN does not provide onion-site browsing in v0.1/v0.2 unless explicitly
> implemented later.

## Orbot Companion Setup

If Orbot is not installed:

> Volunteer Network needs Orbot for this early test. Install Orbot, start it,
> then return to ZeroVPN.

Actions:

* Install Orbot
* I have Orbot installed
* Back

If Orbot is installed:

> Open Orbot and start its connection. Then return to ZeroVPN.

Actions:

* Open Orbot
* Continue in ZeroVPN
* Back

## Embedded Startup

Status:

> Starting Volunteer Network...

Longer startup:

> Volunteer Network is still starting. This can take longer than a private
> WireGuard exit.

## Unsupported Traffic

If UDP is blocked or unsupported:

> Some apps may not work in Volunteer Network mode because this early version
> only supports selected traffic types.

## DNS Problem

> Volunteer Network connected, but DNS did not resolve through the expected
> path. Disconnect and try again.

## Blocked Site Explanation

> This site may block or challenge traffic from public volunteer-operated
> networks. Try again later or use a private exit.

## Diagnostics Labels

Use provider-neutral labels where possible:

* Routing mode
* Network status
* Exit IP
* DNS check
* Last error

Use provider-specific labels only when accurate:

* WireGuard handshake
* Tor bootstrap
* Local SOCKS proxy
