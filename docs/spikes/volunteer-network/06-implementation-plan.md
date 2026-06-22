# Implementation Plan

This plan is staged to avoid taking on embedded VPN, Tor, DNS, and UDP problems
all at once.

## Phase 0: Research Only

Output:

* this spike folder
* product definition
* technical options
* security and privacy language
* initial implementation recommendation

No production app code changes.

## Phase 1: Orbot Companion Proof of Concept

Goal: learn whether a companion-app flow is useful enough for v0.2.

Tasks:

1. Detect whether Orbot is installed.
2. Offer an install/open path.
3. Document whether Orbot can expose a useful integration surface.
4. Identify VpnService conflicts.
5. Test the user journey on a fresh Android device.

Acceptance:

* the app can guide the user to Orbot without confusing it with ZeroVPN-owned
  infrastructure
* the team understands whether Orbot VPN mode conflicts with ZeroVPN VPN mode
* no claims are made that ZeroVPN owns the volunteer network

Stop conditions:

* no reliable return path from Orbot
* no useful status detection
* VpnService ownership makes the flow too confusing

## Phase 2: Embedded Local Tor Proof of Concept

Goal: validate local Tor or Arti startup before building a full VPN tunnel.

Tasks:

1. Start a local Tor or Arti component.
2. Wait for bootstrap.
3. Expose a local SOCKS port.
4. Route one test HTTP request through SOCKS.
5. Shut down cleanly.
6. Record battery, startup time, crash behavior, and package size.

Acceptance:

* local bootstrap works repeatably
* one test request succeeds through SOCKS
* no full-device VPN routing is attempted yet

Stop conditions:

* component is too unstable or hard to package
* licensing is unclear
* startup time is unacceptable

## Phase 3: VpnService Plus tun2socks Proof of Concept

Goal: route Android traffic through local SOCKS intentionally.

Tasks:

1. Create an Android VpnService TUN interface.
2. Bridge TCP traffic to local SOCKS with a tun2socks option.
3. Decide and implement UDP behavior.
4. Route DNS through the intended path.
5. Add basic diagnostics.
6. Test common apps and failure modes.

Acceptance:

* TCP traffic routes through the volunteer path
* DNS behavior is verified
* UDP is either supported or clearly blocked
* user-facing errors are understandable

Stop conditions:

* DNS leaks are found and cannot be fixed quickly
* UDP behavior is too confusing
* battery or native stability is unacceptable

## Phase 4: Product Integration

Goal: integrate Volunteer Network as a provider-specific routing type.

Tasks:

1. Add Volunteer Network tile.
2. Add warning and consent screen.
3. Add provider-specific connection lifecycle.
4. Add diagnostics.
5. Add per-app exclusions if needed.
6. Add clear disconnect and failure recovery.
7. Add public README documentation.

Acceptance:

* Volunteer Network is not described as a ZeroVPN-operated service
* users see the warning before first use
* diagnostics distinguish Volunteer Network from WireGuard exits
* no unsupported privacy guarantees are made

