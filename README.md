# ZeroVPN

A free, open-source Android VPN and exit-node kit.

ZeroVPN helps you route phone traffic through a non-UK exit path using
infrastructure you control or trust. It is not a VPN service provider —
it does not operate exit nodes, collect accounts, or run a central
server.

## What it does

- **Private Exit Mode:** route traffic through your own WireGuard node
  on a cloud VM, VPS, or residential machine abroad.
- **Invite Mode:** a trusted operator issues a revocable QR invite. The
  phone user scans it and connects — no card, cloud account, or technical
  knowledge needed.
- **Volunteer Network Mode:** route ordinary clearnet traffic through a
  public volunteer relay network when no private exit is available.

## What it doesn't do

- No subscription. No ZeroVPN account. No app store required.
- No ZeroVPN-operated servers. No central backend. No telemetry.
- No promise of anonymity. A VPN changes your exit, not your identity.

## Status

Pre-alpha. Nothing works yet. See `docs/ARCHITECTURE.md` and `jobs/` for
the build plan.

## License

MIT (see `LICENSE`). Dependencies may carry their own licenses — a full
license review will be done before the first release.

## Building

Build instructions will be in `docs/ANDROID_BUILD.md` once the Android
skeleton exists. Windows-native PowerShell only — no WSL required.