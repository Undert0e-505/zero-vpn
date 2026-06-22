# Implementation Plan

This plan keeps Volunteer Network self-contained in ZeroVPN. Orbot is prior art
only. The primary path is embedded Tor first, then Android VpnService plus
tun2socks after local SOCKS behavior is proven.

Do not claim anonymity, Tor Browser-equivalent protection, or full VPN support
until the VpnService plus tun2socks path is working and tested.

## Phase 1: Embedded Tor SOCKS Proof of Concept

Goal: prove ZeroVPN can start embedded Tor locally and use its SOCKS listener
for one controlled test request.

Tasks:

1. Add tor-android or an equivalent embedded Tor dependency from Maven.
2. Start Tor locally from ZeroVPN.
3. Wait for bootstrap.
4. Expose or discover the local SOCKS host and port.
5. Perform one HTTP test request through `127.0.0.1:<socks-port>`.
6. Parse whether the test endpoint reports Tor usage when available.
7. Shut down Tor cleanly.
8. Keep the surface behind Developer Mode.
9. Do not request Android VPN permission.
10. Do not route Android device traffic.

Acceptance:

* embedded Tor starts or fails with a useful diagnostic
* bootstrap state is visible to developers
* one SOCKS-routed test request succeeds or reports a useful error
* Tor shuts down when the developer presses Stop
* no public UI tile is exposed

Stop conditions:

* tor-android cannot be resolved or packaged cleanly
* licensing is unclear
* bootstrap needs hidden setup inappropriate for app embedding
* the test request cannot be forced through SOCKS
* lifecycle creates crashes or stuck processes

## Phase 2: Volunteer Network Internal Controller

Goal: add a provider/controller skeleton without public product claims.

Tasks:

1. Add internal lifecycle states: Idle, Starting, Bootstrapping, ProxyReady,
   TestRequestRunning, Ready, Stopping, Failed.
2. Keep diagnostics developer-only.
3. Keep transport-specific logic isolated from Oracle and WireGuard code.
4. Record bootstrap progress, SOCKS endpoint, test status, exit IP if known,
   and last error.
5. Add safe repeated Start/Stop handling.

Acceptance:

* controller state transitions are deterministic
* diagnostics are useful enough for the next technical step
* no normal user-facing claims are introduced

## Phase 3: VpnService Plus tun2socks Technical Proof of Concept

Goal: prove Android traffic can be intentionally bridged to the local SOCKS
proxy.

Tasks:

1. Create an Android VpnService TUN interface.
2. Use tun2socks to bridge TUN TCP flows to local Tor SOCKS.
3. Handle DNS explicitly.
4. Block or explicitly handle UDP.
5. Keep the proof of concept behind Developer Mode.
6. Test common failure modes and leak scenarios.

Acceptance:

* TCP traffic routes through the intended local SOCKS path
* DNS behavior is verified
* UDP behavior is either supported or clearly blocked
* no unsupported privacy guarantees are made

Stop conditions:

* DNS leaks are found and cannot be fixed quickly
* UDP behavior is too confusing
* battery or native stability is unacceptable

## Phase 4: Product Integration

Goal: turn the proven technical path into a careful Volunteer Network product
surface.

Tasks:

1. Add a Volunteer Network tile.
2. Add a warning screen.
3. Add provider-aware diagnostics.
4. Consider per-app routing.
5. Add public documentation.
6. Keep Tor wording in technical notes and diagnostics; keep Volunteer Network
   as the product wording.

Acceptance:

* users see clear warnings before first use
* diagnostics distinguish Volunteer Network from Oracle Free Exit
* docs avoid anonymity and Tor Browser-equivalent claims

## Phase 5: Hardening

Goal: make the feature stable enough to consider release planning.

Tasks:

1. Battery and lifecycle testing.
2. Crash recovery.
3. DNS leak checks.
4. UDP behavior tests.
5. Bootstrap failure recovery.
6. Release documentation.
7. APK size and ABI review.

Acceptance:

* lifecycle is robust across app backgrounding, process death, and network
  changes
* leak checks are documented and repeatable
* release notes describe limitations plainly
