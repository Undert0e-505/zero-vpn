# Open Questions

## Product

* Should Volunteer Network appear in Add Exit, or should it be a separate
  routing mode?
* Should Volunteer Network require explicit per-use confirmation, or only a
  first-use warning?
* Should this mode default to all apps or selected apps?

## Orbot Prior Art

* Which Orbot lifecycle patterns should ZeroVPN copy for diagnostics and
  recovery?
* Which Orbot UX warnings are useful references for Volunteer Network copy?
* Which Orbot VPN-mode constraints should inform ZeroVPN's later VpnService
  design?

## Embedded Tor or Arti

* Is Guardian Project tor-android the right embedded dependency for the first
  proof of concept?
* What is the native packaging cost?
* What is the startup time on low-end Android devices?
* How should bootstrap progress be surfaced?
* How should crashes and restarts be handled?
* How should ZeroVPN handle bootstrap failures caused by censored networks?

## tun2socks

* Which implementation is most maintainable for Android?
* Which licenses and native dependencies are acceptable?
* How are IPv6, DNS, and UDP handled?
* Can it run safely in the app lifecycle without battery problems?
* How much diagnostic visibility can it provide?

## Security and Privacy

* How should ZeroVPN explain the difference between Volunteer Network and Tor
  Browser?
* Which traffic should be blocked instead of routed?
* How can DNS leaks be tested automatically?
* Should unsafe browser/app combinations trigger warnings?
* What exact claims are acceptable in README and UI copy?

## Abuse and Operations

* If ZeroVPN ever supports volunteer WireGuard exits, who handles abuse reports?
* Should public volunteer WireGuard exits be avoided entirely?
* Is a trusted invite model a better fit than public volunteer nodes?
* Should ZeroVPN run any central directory service? Current product direction
  says no.

## Release Planning

* Is v0.2 only an internal embedded SOCKS proof of concept?
* What telemetry, if any, is acceptable? Current product direction avoids a
  central backend.
* What manual tests are required before exposing this to public users?
