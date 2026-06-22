# Device Test Results

Date: 2026-06-22

## Embedded Tor SOCKS Proof of Concept

The self-contained embedded path is viable on device. ZeroVPN can start
embedded Tor, expose a local SOCKS proxy, make an explicit SOCKS-routed request,
and confirm Tor usage without routing Android device traffic.

Observed successful diagnostics:

* transport: Embedded Tor
* SOCKS endpoint: `127.0.0.1:9050`
* bootstrap progress: 100%
* test endpoint: `https://check.torproject.org/api/ip`
* test result: `HTTP 200 IsTor=true`
* exit IP changed between successful runs
* final state: Ready

Early runs did not always complete. The first few attempts stalled or timed out
around 45% or 55% bootstrap progress. Later runs consistently reached 100%,
which suggests cold bootstrap and initial Tor state/cache creation need better
diagnostics and timeout handling.

## Current Limitations

* This proof of concept does not request Android VPN permission.
* It does not create a `VpnService` TUN interface.
* It does not use tun2socks.
* It does not route device or app traffic.
* The test surface remains Developer Mode-only.

## Next Step

Harden embedded Tor lifecycle and diagnostics, then proceed to the separate
VpnService plus tun2socks technical proof of concept.
