# Job: WireGuard Android integration research

Branch: agent/2026-06-19_wireguard-research

## Objective

Produce a technical decision record for WireGuard integration on
Android. Identify the library/approach, licensing, and implementation
path before writing any tunnel code.

## Context

- ZeroVPN's MVP uses WireGuard for Private Exit Mode.
- The app uses Android `VpnService` for phone-wide routing.
- Only one Android VPN service can be active at a time.
- Research must identify the correct integration path — do not guess
  package coordinates or licensing.

## Scope

- `docs/WIREGUARD_DECISION_RECORD.md` — the decision record
- Web research on available WireGuard Android libraries

## Non-goals

- No implementation code
- No dependency selection without evidence

## Constraints

- Open-source dependencies only (MIT, Apache 2.0, or BSD preferred)
- Must work with Android `VpnService` (not root-based)
- Must support Android 10+
- Must be buildable on Windows-native PowerShell

## Research questions

1. **WireGuard libraries for Android:**
   - What is the official WireGuard Android library/app?
   - Is there a reusable tunnel library (not the full GUI app)?
   - What is `wireguard-android` / `wireguard-tunnel`?
   - Are there alternative userspace WireGuard implementations for
     Android (e.g. `wireguard-go`)?

2. **Licensing:**
   - What license does each option use?
   - Are there GPL constraints that affect ZeroVPN's MIT license?
   - If GPL, what are the implications for distribution?

3. **Integration patterns:**
   - How do other open-source Android VPN apps integrate WireGuard?
   - Is there a standard `VpnService` + WireGuard userspace pattern?
   - What does the WireGuard Android app itself use under the hood?

4. **Build requirements:**
   - Native code compilation? If so, what NDK version?
   - Cross-compile targets (arm64-v8a, armeabi-v7a)?
   - Does it build on Windows or require Linux for NDK?

5. **Feature support:**
   - Config import (text/file/QR)?
   - DNS through tunnel?
   - IPv6 handling?
   - Multiple tunnels / saved configs?
   - Status reporting (handshake, transfer stats)?

6. **Alternatives if WireGuard is blocked:**
   - Are there other VPN protocols suitable for MVP?
   - What are the trade-offs?

## Expected files changed

- `docs/WIREGUARD_DECISION_RECORD.md`

## Tests / validation

- Decision record cites sources for each claim
- License is verified (not assumed)
- Build path is validated against the Windows-native constraint
- At least one integration path is identified as viable

## Done when

- [ ] Decision record written
- [ ] At least one viable integration path identified
- [ ] Licensing checked and compatible with MIT
- [ ] Build requirements documented
- [ ] Feature gaps documented (if any)

## Report back with

- summary
- decision record file path
- sources cited
- recommended integration path
- blockers or unknowns remaining